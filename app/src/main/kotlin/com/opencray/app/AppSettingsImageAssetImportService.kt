package com.opencray.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.io.path.deleteIfExists

internal data class AppImportedSettingsImageCandidate(
  val path: Path,
  val displayName: String? = null,
  val mimeType: String? = null,
)

internal fun interface AppSettingsImageCandidateImporter {
  fun import(uriStrings: List<String>): List<AppImportedSettingsImageCandidate>
}

internal class AppSettingsImageAssetImportService(
  private val candidateImporter: AppSettingsImageCandidateImporter,
  private val assetStore: AppSettingsImageAssetStore,
) {
  fun import(uriStrings: List<String>): List<AppSettingsImageAsset> = uriStrings
    .takeIf(List<String>::isNotEmpty)
    ?.let(candidateImporter::import)
    .orEmpty()
    .mapNotNull { candidate ->
      try {
        assetStore.importImage(
          sourcePath = candidate.path,
          displayName = candidate.displayName,
          mimeType = candidate.mimeType,
        )
      } finally {
        cleanupCandidate(candidate.path)
      }
    }
    .distinctBy(AppSettingsImageAsset::assetId)

  private fun cleanupCandidate(path: Path) {
    runCatching {
      path.deleteIfExists()
      cleanupEmptyParentDirectories(path.parent)
    }
  }

  private fun cleanupEmptyParentDirectories(directory: Path?) {
    var current = directory
    repeat(2) {
      val path = current ?: return
      if (!Files.exists(path)) {
        current = path.parent
        return@repeat
      }
      val isEmpty = Files.list(path).use { stream ->
        !stream.findAny().isPresent
      }
      if (!isEmpty) {
        return
      }
      path.deleteIfExists()
      current = path.parent
    }
  }
}

internal class ContextBackedSettingsImageCandidateImporter(
  private val appContext: Context,
  private val stagingRootProvider: () -> Path = {
    appContext.cacheDir.toPath()
      .toAbsolutePath()
      .normalize()
      .resolve("opencray-settings-image-imports")
  },
) : AppSettingsImageCandidateImporter {
  override fun import(uriStrings: List<String>): List<AppImportedSettingsImageCandidate> {
    val stagingRoot = stagingRootProvider().toAbsolutePath().normalize()
    Files.createDirectories(stagingRoot)
    val contentResolver = appContext.contentResolver
    return uriStrings.mapNotNull { rawUri ->
      var destination: Path? = null
      runCatching {
        val uri = Uri.parse(rawUri)
        val metadata = loadMetadata(uri)
        val displayName = resolveDisplayName(
          rawDisplayName = metadata.displayName,
          mimeType = metadata.mimeType,
        )
        destination = stagingRoot.resolve("tmp-${UUID.randomUUID().toString().take(12)}-$displayName")
        contentResolver.openInputStream(uri)?.use { input ->
          Files.newOutputStream(requireNotNull(destination)).use { output ->
            input.copyTo(output)
          }
        } ?: return@runCatching null
        AppImportedSettingsImageCandidate(
          path = requireNotNull(destination),
          displayName = displayName,
          mimeType = metadata.mimeType,
        )
      }.getOrElse {
        destination?.deleteIfExists()
        null
      }
    }
  }

  private fun loadMetadata(uri: Uri): ImportedImageMetadata {
    val contentResolver = appContext.contentResolver
    var displayName: String? = null
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex >= 0) {
          displayName = cursor.getString(columnIndex)?.trim()?.takeIf(String::isNotBlank)
        }
      }
    }
    return ImportedImageMetadata(
      displayName = displayName,
      mimeType = contentResolver.getType(uri)?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun resolveDisplayName(
    rawDisplayName: String?,
    mimeType: String?,
  ): String {
    rawDisplayName?.trim()?.takeIf(String::isNotBlank)?.let(::safeFileName)?.let { safeName ->
      return safeName
    }
    val extension = MimeTypeMap.getSingleton()
      .getExtensionFromMimeType(mimeType)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.lowercase(Locale.US)
    return if (extension == null) {
      "image-${UUID.randomUUID().toString().take(8)}"
    } else {
      "image-${UUID.randomUUID().toString().take(8)}.$extension"
    }
  }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "image.bin" }

  private data class ImportedImageMetadata(
    val displayName: String?,
    val mimeType: String?,
  )
}
