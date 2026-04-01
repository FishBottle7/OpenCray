package com.opencray.app

import android.graphics.BitmapFactory
import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal enum class AppImageReferencePromotionMode {
  COPY_PROMOTE,
  REFERENCE_PROMOTE,
}

internal enum class AppImageReferenceTargetKind {
  MEMORY,
  SOUL_PRIMARY_PORTRAIT,
  SOUL_REFERENCE,
}

internal data class AppImageDimensions(
  val widthPx: Int,
  val heightPx: Int,
) {
  init {
    require(widthPx > 0) { "AppImageDimensions widthPx must be > 0." }
    require(heightPx > 0) { "AppImageDimensions heightPx must be > 0." }
  }
}

internal data class AppImageSummary(
  val caption: String,
  val summary: String,
  val portraitSummary: String? = null,
) {
  init {
    require(caption.isNotBlank()) { "AppImageSummary caption must not be blank." }
    require(summary.isNotBlank()) { "AppImageSummary summary must not be blank." }
    require(portraitSummary == null || portraitSummary.isNotBlank()) {
      "AppImageSummary portraitSummary must not be blank when provided."
    }
  }
}

internal data class AppImageSummaryExtractionRequest(
  val imagePath: Path,
  val source: OpenCrayImageReferenceSource,
  val targetKind: AppImageReferenceTargetKind,
)

internal data class ResolvedAppImageReferenceSource(
  val source: OpenCrayImageReferenceSource,
  val path: Path,
  val displayName: String? = null,
  val mimeType: String? = null,
  val sourceSessionId: String? = source.sourceSessionId,
  val sourceMessageId: String? = source.sourceMessageId,
) {
  init {
    require(displayName == null || displayName.isNotBlank()) {
      "ResolvedAppImageReferenceSource displayName must not be blank when provided."
    }
    require(mimeType == null || mimeType.isNotBlank()) {
      "ResolvedAppImageReferenceSource mimeType must not be blank when provided."
    }
    require(sourceSessionId == null || sourceSessionId.isNotBlank()) {
      "ResolvedAppImageReferenceSource sourceSessionId must not be blank when provided."
    }
    require(sourceMessageId == null || sourceMessageId.isNotBlank()) {
      "ResolvedAppImageReferenceSource sourceMessageId must not be blank when provided."
    }
  }
}

internal fun interface AppImageReferenceSourceResolver {
  fun resolve(source: OpenCrayImageReferenceSource): ResolvedAppImageReferenceSource?
}

internal fun interface AppImageDimensionsReader {
  fun read(path: Path): AppImageDimensions?
}

internal fun interface AppImageSummaryExtractor {
  fun extract(request: AppImageSummaryExtractionRequest): AppImageSummary?
}

internal data class AppPromotedImageReference(
  val reference: OpenCrayImageReference,
  val mode: AppImageReferencePromotionMode,
  val resolvedAssetPath: Path,
  val portraitSummary: String? = null,
  val reusedExistingAsset: Boolean = false,
)

internal class DefaultAppImageReferenceSourceResolver(
  workspaceRoot: Path?,
  privateRoot: Path,
) : AppImageReferenceSourceResolver {
  private val normalizedWorkspaceRoot: Path? = workspaceRoot?.toAbsolutePath()?.normalize()
  private val normalizedPrivateRoot: Path = privateRoot.toAbsolutePath().normalize()

  override fun resolve(source: OpenCrayImageReferenceSource): ResolvedAppImageReferenceSource? {
    val path = when (source.sourceKind) {
      OpenCrayImageReferenceSourceKind.WORKSPACE_PATH ->
        resolveRelativePath(
          root = normalizedWorkspaceRoot ?: return null,
          relativePath = source.relativePath ?: return null,
        )

      OpenCrayImageReferenceSourceKind.DURABLE_ASSET ->
        resolveRelativePath(
          root = normalizedPrivateRoot,
          relativePath = source.relativePath ?: return null,
        )

      else -> return null
    } ?: return null
    return ResolvedAppImageReferenceSource(
      source = source,
      path = path,
      displayName = source.displayName,
      mimeType = source.mimeType,
    )
  }

  private fun resolveRelativePath(
    root: Path,
    relativePath: String,
  ): Path? {
    val normalizedRelativePath = relativePath
      .trim()
      .replace('\\', '/')
      .removePrefix("/")
      .takeIf(String::isNotEmpty)
      ?: return null
    val resolved = root.resolve(normalizedRelativePath).normalize()
    if (!resolved.startsWith(root)) {
      return null
    }
    return resolved.takeIf { path -> path.exists() && path.isRegularFile() }
  }
}

internal object DefaultAppImageDimensionsReader : AppImageDimensionsReader {
  override fun read(path: Path): AppImageDimensions? {
    val options = BitmapFactory.Options().apply {
      inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path.toString(), options)
    val width = options.outWidth.takeIf { it > 0 } ?: return null
    val height = options.outHeight.takeIf { it > 0 } ?: return null
    return AppImageDimensions(
      widthPx = width,
      heightPx = height,
    )
  }
}

internal object NoOpAppImageSummaryExtractor : AppImageSummaryExtractor {
  override fun extract(request: AppImageSummaryExtractionRequest): AppImageSummary? = null
}

internal class AppImageReferencePromotionService(
  privateRoot: Path,
  workspaceRoot: Path? = null,
  private val sourceResolver: AppImageReferenceSourceResolver = AppCompositeImageReferenceSourceResolver(
    workspaceRoot = workspaceRoot,
    privateRoot = privateRoot,
  ),
  private val dimensionsReader: AppImageDimensionsReader = DefaultAppImageDimensionsReader,
  private val summaryExtractor: AppImageSummaryExtractor = NoOpAppImageSummaryExtractor,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val normalizedPrivateRoot: Path = privateRoot.toAbsolutePath().normalize()
  private val normalizedWorkspaceRoot: Path? = workspaceRoot?.toAbsolutePath()?.normalize()

  fun promoteForMemory(
    memoryId: String,
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode? = null,
  ): AppPromotedImageReference? {
    require(memoryId.isNotBlank()) { "memoryId must not be blank." }
    return promote(
      targetKind = AppImageReferenceTargetKind.MEMORY,
      targetKey = memoryId,
      role = OpenCrayImageReferenceRole.EVIDENCE,
      source = source,
      preferredMode = preferredMode,
    )
  }

  fun promoteForSoulPrimaryPortrait(
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode? = null,
  ): AppPromotedImageReference? = promote(
    targetKind = AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT,
    targetKey = "primary",
    role = OpenCrayImageReferenceRole.PORTRAIT,
    source = source,
    preferredMode = preferredMode,
  )

  fun promoteForSoulReference(
    refId: String,
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode? = null,
  ): AppPromotedImageReference? {
    require(refId.isNotBlank()) { "refId must not be blank." }
    return promote(
      targetKind = AppImageReferenceTargetKind.SOUL_REFERENCE,
      targetKey = refId,
      role = OpenCrayImageReferenceRole.REFERENCE,
      source = source,
      preferredMode = preferredMode,
    )
  }

  private fun promote(
    targetKind: AppImageReferenceTargetKind,
    targetKey: String,
    role: OpenCrayImageReferenceRole,
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode?,
  ): AppPromotedImageReference? {
    val resolvedSource = sourceResolver.resolve(source) ?: return null
    val sourcePath = resolvedSource.path.toAbsolutePath().normalize()
    if (!sourcePath.exists() || !sourcePath.isRegularFile()) {
      return null
    }
    val normalizedDisplayName = resolvedSource.displayName
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?: sourcePath.name
    val normalizedMimeType = resolveMimeType(
      preferredMimeType = resolvedSource.mimeType ?: source.mimeType,
      fileName = normalizedDisplayName,
    )
    if (!isSupportedImage(normalizedDisplayName, normalizedMimeType)) {
      return null
    }
    val effectiveMode = resolvePromotionMode(
      targetKind = targetKind,
      sourceKind = source.sourceKind,
      preferredMode = preferredMode,
    )
    val stagedAsset = when (effectiveMode) {
      AppImageReferencePromotionMode.COPY_PROMOTE -> copyPromote(
        targetKind = targetKind,
        targetKey = targetKey,
        sourcePath = sourcePath,
        displayName = normalizedDisplayName,
      )

      AppImageReferencePromotionMode.REFERENCE_PROMOTE -> referencePromote(
        sourcePath = sourcePath,
      ) ?: return null
    }
    val extractedSummary = runCatching {
      summaryExtractor.extract(
        AppImageSummaryExtractionRequest(
          imagePath = stagedAsset.path,
          source = source,
          targetKind = targetKind,
        ),
      )
    }.getOrNull()
    if (extractedSummary == null) {
      cleanupIfStagedCopy(stagedAsset)
      return null
    }
    val dimensions = dimensionsReader.read(stagedAsset.path)
    val createdAtEpochMs = clock()
    val normalizedSourceLabel = normalizedDisplayName.takeIf(String::isNotBlank)
      ?: source.sourceKind.name.lowercase(Locale.US)
    return AppPromotedImageReference(
      reference = OpenCrayImageReference(
        refId = referenceIdFor(
          targetKind = targetKind,
          targetKey = targetKey,
          sha256 = stagedAsset.sha256,
        ),
        role = role,
        storageScope = stagedAsset.storageScope,
        relativePath = stagedAsset.relativePath,
        mimeType = normalizedMimeType,
        sha256 = stagedAsset.sha256,
        widthPx = dimensions?.widthPx,
        heightPx = dimensions?.heightPx,
        caption = extractedSummary.caption,
        summary = extractedSummary.summary,
        sourceLabel = normalizedSourceLabel,
        sourceSessionId = resolvedSource.sourceSessionId,
        sourceMessageId = resolvedSource.sourceMessageId,
        createdAtEpochMs = createdAtEpochMs,
      ),
      mode = effectiveMode,
      resolvedAssetPath = stagedAsset.path,
      portraitSummary = extractedSummary.portraitSummary,
      reusedExistingAsset = stagedAsset.reusedExistingAsset,
    )
  }

  private fun cleanupIfStagedCopy(stagedAsset: StagedImageAsset) {
    if (!stagedAsset.isNewlyCopied) {
      return
    }
    runCatching {
      stagedAsset.path.deleteIfExists()
    }
  }

  private fun resolvePromotionMode(
    targetKind: AppImageReferenceTargetKind,
    sourceKind: OpenCrayImageReferenceSourceKind,
    preferredMode: AppImageReferencePromotionMode?,
  ): AppImageReferencePromotionMode {
    if (preferredMode != null) {
      require(isModeAllowed(targetKind, sourceKind, preferredMode)) {
        "Promotion mode $preferredMode is not supported for $targetKind from $sourceKind."
      }
      return preferredMode
    }
    return when {
      sourceKind == OpenCrayImageReferenceSourceKind.DURABLE_ASSET ->
        AppImageReferencePromotionMode.REFERENCE_PROMOTE

      else -> AppImageReferencePromotionMode.COPY_PROMOTE
    }
  }

  private fun isModeAllowed(
    targetKind: AppImageReferenceTargetKind,
    sourceKind: OpenCrayImageReferenceSourceKind,
    mode: AppImageReferencePromotionMode,
  ): Boolean = when (mode) {
    AppImageReferencePromotionMode.COPY_PROMOTE -> true
    AppImageReferencePromotionMode.REFERENCE_PROMOTE -> when (targetKind) {
      AppImageReferenceTargetKind.MEMORY ->
        sourceKind == OpenCrayImageReferenceSourceKind.WORKSPACE_PATH ||
          sourceKind == OpenCrayImageReferenceSourceKind.DURABLE_ASSET

      AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT,
      AppImageReferenceTargetKind.SOUL_REFERENCE,
      -> sourceKind == OpenCrayImageReferenceSourceKind.DURABLE_ASSET
    }
  }

  private fun copyPromote(
    targetKind: AppImageReferenceTargetKind,
    targetKey: String,
    sourcePath: Path,
    displayName: String,
  ): StagedImageAsset {
    val sha256 = sha256Hex(sourcePath)
    val destination = when (targetKind) {
      AppImageReferenceTargetKind.MEMORY -> normalizedPrivateRoot
        .resolve("memory-media")
        .resolve(safePathSegment(targetKey))
        .resolve("${sha256.take(12)}-${safeFileName(displayName)}")

      AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT -> normalizedPrivateRoot
        .resolve("soul-assets")
        .resolve("portrait")
        .resolve("${sha256.take(12)}-${safeFileName(displayName)}")

      AppImageReferenceTargetKind.SOUL_REFERENCE -> normalizedPrivateRoot
        .resolve("soul-assets")
        .resolve("references")
        .resolve(safePathSegment(targetKey))
        .resolve("${sha256.take(12)}-${safeFileName(displayName)}")
    }.normalize()
    require(destination.startsWith(normalizedPrivateRoot)) {
      "Destination path escapes the private root."
    }
    Files.createDirectories(requireNotNull(destination.parent))
    val reusedExistingAsset = Files.exists(destination)
    if (!reusedExistingAsset) {
      Files.copy(
        sourcePath,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
      )
    }
    return StagedImageAsset(
      path = destination,
      storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
      relativePath = normalizedPrivateRoot.relativize(destination).toString().replace('\\', '/'),
      sha256 = sha256,
      reusedExistingAsset = reusedExistingAsset,
      isNewlyCopied = !reusedExistingAsset,
    )
  }

  private fun referencePromote(sourcePath: Path): StagedImageAsset? {
    val normalizedPath = sourcePath.toAbsolutePath().normalize()
    val privateRoot = normalizedPrivateRoot
    if (normalizedPath.startsWith(privateRoot)) {
      return StagedImageAsset(
        path = normalizedPath,
        storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
        relativePath = privateRoot.relativize(normalizedPath).toString().replace('\\', '/'),
        sha256 = sha256Hex(normalizedPath),
        reusedExistingAsset = true,
        isNewlyCopied = false,
      )
    }
    val workspaceRoot = normalizedWorkspaceRoot
    if (workspaceRoot != null && normalizedPath.startsWith(workspaceRoot)) {
      return StagedImageAsset(
        path = normalizedPath,
        storageScope = OpenCrayImageReferenceStorageScope.WORKSPACE,
        relativePath = workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'),
        sha256 = sha256Hex(normalizedPath),
        reusedExistingAsset = true,
        isNewlyCopied = false,
      )
    }
    return null
  }

  private fun referenceIdFor(
    targetKind: AppImageReferenceTargetKind,
    targetKey: String,
    sha256: String,
  ): String = when (targetKind) {
    AppImageReferenceTargetKind.MEMORY -> "memory-${sha256.take(12)}"
    AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT -> "portrait-${sha256.take(12)}"
    AppImageReferenceTargetKind.SOUL_REFERENCE -> safePathSegment(targetKey)
  }

  private fun resolveMimeType(
    preferredMimeType: String?,
    fileName: String,
  ): String? {
    val normalizedPreferred = preferredMimeType?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedPreferred != null) {
      return normalizedPreferred
    }
    return URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?: FALLBACK_MIME_TYPES[fileName.substringAfterLast('.', "").lowercase(Locale.US)]
  }

  private fun isSupportedImage(
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.US)
    return extension in SUPPORTED_IMAGE_EXTENSIONS || normalizedMimeType in SUPPORTED_IMAGE_MIME_TYPES
  }

  private fun safePathSegment(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "image-ref" }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "image.bin" }

  private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read == 0) {
          continue
        }
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString(separator = "") { byte ->
      "%02x".format(byte)
    }
  }

  private data class StagedImageAsset(
    val path: Path,
    val storageScope: OpenCrayImageReferenceStorageScope,
    val relativePath: String,
    val sha256: String,
    val reusedExistingAsset: Boolean,
    val isNewlyCopied: Boolean,
  )

  companion object {
    private val SUPPORTED_IMAGE_EXTENSIONS: Set<String> = setOf(
      "bmp",
      "gif",
      "heic",
      "heif",
      "jpeg",
      "jpg",
      "png",
      "webp",
    )

    private val SUPPORTED_IMAGE_MIME_TYPES: Set<String> = setOf(
      "image/bmp",
      "image/gif",
      "image/heic",
      "image/heif",
      "image/jpeg",
      "image/png",
      "image/webp",
    )

    private val FALLBACK_MIME_TYPES: Map<String, String> = mapOf(
      "bmp" to "image/bmp",
      "gif" to "image/gif",
      "heic" to "image/heic",
      "heif" to "image/heif",
      "jpeg" to "image/jpeg",
      "jpg" to "image/jpeg",
      "png" to "image/png",
      "webp" to "image/webp",
    )
  }
}
