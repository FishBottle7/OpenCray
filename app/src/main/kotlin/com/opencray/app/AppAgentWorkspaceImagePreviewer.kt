package com.opencray.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal object AppAgentWorkspaceImagePreviewer {
  fun loadPreview(
    workspaceRoot: Path,
    relativePath: String,
  ): Map<String, Any?> {
    val target = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
      allowRoot = false,
    )
    require(target.exists()) {
      "The selected file no longer exists."
    }
    require(!target.isDirectory()) {
      "Folders can't be previewed here."
    }
    require(isPreviewableName(target.name)) {
      "Image preview is available for image files only."
    }
    val preview = loadPreviewBitmap(target)
    val normalizedRelativePath = relativePath.trim().replace('\\', '/').removePrefix("/")
    val compressed = compressBitmap(preview.bitmap)
    return mapOf(
      "name" to target.name,
      "relativePath" to normalizedRelativePath,
      "mimeType" to compressed.mimeType,
      "width" to preview.width,
      "height" to preview.height,
      "bytesBase64" to Base64.getEncoder().encodeToString(compressed.bytes),
    )
  }

  private fun loadPreviewBitmap(target: Path): LoadedPreviewBitmap =
    if (isSvgName(target.name)) {
      loadSvgPreviewBitmap(target)
    } else {
      loadRasterPreviewBitmap(target)
    }

  private fun loadRasterPreviewBitmap(target: Path): LoadedPreviewBitmap {
    val boundsOptions = BitmapFactory.Options().apply {
      inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(target.toString(), boundsOptions)
    val sourceWidth = boundsOptions.outWidth
    val sourceHeight = boundsOptions.outHeight
    require(sourceWidth > 0 && sourceHeight > 0) {
      "This file can't be previewed as an image."
    }

    val decodeOptions = BitmapFactory.Options().apply {
      inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = requireNotNull(BitmapFactory.decodeFile(target.toString(), decodeOptions)) {
      "This file can't be previewed as an image."
    }
    return LoadedPreviewBitmap(
      bitmap = bitmap,
      width = bitmap.width,
      height = bitmap.height,
    )
  }

  private fun loadSvgPreviewBitmap(target: Path): LoadedPreviewBitmap {
    val svg = runCatching {
      target.toFile().inputStream().use { input ->
        SVG.getFromInputStream(input)
      }
    }.getOrElse {
      throw IllegalArgumentException("This file can't be previewed as an image.")
    }
    val dimensions = resolveSvgDimensions(svg)
    val inSampleSize = calculateInSampleSize(dimensions.width, dimensions.height)
    val outputWidth = (dimensions.width / inSampleSize.toFloat()).roundToInt().coerceAtLeast(1)
    val outputHeight = (dimensions.height / inSampleSize.toFloat()).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    svg.renderToCanvas(
      canvas,
      RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()),
    )
    return LoadedPreviewBitmap(
      bitmap = bitmap,
      width = outputWidth,
      height = outputHeight,
    )
  }

  private fun resolveSvgDimensions(svg: SVG): PreviewDimensions {
    val viewBox = svg.documentViewBox
    val viewBoxWidth = viewBox?.width()?.takeIf { width -> width > 0f }
    val viewBoxHeight = viewBox?.height()?.takeIf { height -> height > 0f }
    val aspectRatio = if (
      viewBoxWidth != null &&
      viewBoxHeight != null &&
      viewBoxHeight > 0f
    ) {
      viewBoxWidth / viewBoxHeight
    } else {
      null
    }
    val documentWidth = svg.documentWidth.takeIf { width -> width > 0f && width.isFinite() }
    val documentHeight = svg.documentHeight.takeIf { height -> height > 0f && height.isFinite() }
    val resolvedWidth = when {
      documentWidth != null -> documentWidth
      documentHeight != null && aspectRatio != null -> documentHeight * aspectRatio
      viewBoxWidth != null -> viewBoxWidth
      else -> DEFAULT_SVG_DIMENSION.toFloat()
    }
    val resolvedHeight = when {
      documentHeight != null -> documentHeight
      documentWidth != null && aspectRatio != null -> documentWidth / aspectRatio
      viewBoxHeight != null -> viewBoxHeight
      aspectRatio != null && aspectRatio > 0f -> resolvedWidth / aspectRatio
      else -> DEFAULT_SVG_DIMENSION.toFloat()
    }
    return PreviewDimensions(
      width = resolvedWidth.roundToInt().coerceAtLeast(1),
      height = resolvedHeight.roundToInt().coerceAtLeast(1),
    )
  }

  private fun compressBitmap(bitmap: Bitmap): CompressedPreviewImage =
    try {
      ByteArrayOutputStream().use { output ->
        val hasAlpha = bitmap.hasAlpha()
        val format = if (hasAlpha) {
          Bitmap.CompressFormat.PNG
        } else {
          Bitmap.CompressFormat.JPEG
        }
        val mimeType = if (hasAlpha) "image/png" else "image/jpeg"
        val quality = if (hasAlpha) 100 else 92
        check(bitmap.compress(format, quality, output)) {
          "This file can't be previewed as an image."
        }
        CompressedPreviewImage(
          bytes = output.toByteArray(),
          mimeType = mimeType,
        )
      }
    } finally {
      bitmap.recycle()
    }

  private fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
  ): Int {
    var inSampleSize = 1
    while (
      sourceWidth / inSampleSize > MAX_PREVIEW_DIMENSION ||
      sourceHeight / inSampleSize > MAX_PREVIEW_DIMENSION ||
      (sourceWidth / inSampleSize).toLong() * (sourceHeight / inSampleSize).toLong() >
        MAX_PREVIEW_PIXELS.toLong()
    ) {
      inSampleSize *= 2
    }
    return inSampleSize
  }

  private fun isPreviewableName(name: String): Boolean {
    val normalizedName = name.trim().lowercase(Locale.US)
    val extension = normalizedName.substringAfterLast('.', "")
    return extension in PREVIEWABLE_EXTENSIONS
  }

  private fun isSvgName(name: String): Boolean {
    val normalizedName = name.trim().lowercase(Locale.US)
    return normalizedName.substringAfterLast('.', "") == "svg"
  }

  private fun resolvePath(
    workspaceRoot: Path,
    relativePath: String,
    allowRoot: Boolean,
  ): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    if (trimmed.isEmpty()) {
      require(allowRoot) { "Workspace root cannot be targeted here." }
      return normalizedRoot
    }
    val resolved = normalizedRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(normalizedRoot)) {
      "Path escapes the workspace root."
    }
    return resolved
  }

  private data class CompressedPreviewImage(
    val bytes: ByteArray,
    val mimeType: String,
  )

  private data class LoadedPreviewBitmap(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
  )

  private data class PreviewDimensions(
    val width: Int,
    val height: Int,
  )

  private const val MAX_PREVIEW_DIMENSION: Int = 2_048
  private const val MAX_PREVIEW_PIXELS: Int = 5_242_880
  private const val DEFAULT_SVG_DIMENSION: Int = 512

  private val PREVIEWABLE_EXTENSIONS = setOf(
    "png",
    "jpg",
    "jpeg",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
    "svg",
  )
}
