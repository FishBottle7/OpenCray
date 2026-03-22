package com.opencray.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class ImportedChatAttachmentDraft(
  val kind: String,
  val displayName: String,
  val relativePath: String,
  val mimeType: String? = null,
  val sizeBytes: Long? = null,
)

internal object AppChatAttachmentDraftImporter {
  fun importAttachments(
    appContext: Context,
    workspaceRoot: Path,
    requestedKind: String,
    uriStrings: List<String>,
  ): List<ImportedChatAttachmentDraft> {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val importRoot = normalizedWorkspaceRoot.resolve(".opencray").resolve("chat-drafts")
    Files.createDirectories(importRoot)
    val contentResolver = appContext.contentResolver

    return uriStrings.mapNotNull { rawUri ->
      runCatching {
        val uri = Uri.parse(rawUri)
        val metadata = loadMetadata(appContext, uri)
        val mimeType = metadata.mimeType
        val displayName = resolveDisplayName(
          rawDisplayName = metadata.displayName,
          mimeType = mimeType,
          requestedKind = requestedKind,
        )
        val temporaryDestination = temporaryDestination(importRoot, displayName)
        contentResolver.openInputStream(uri)?.use { input ->
          Files.newOutputStream(temporaryDestination).use { output ->
            input.copyTo(output)
          }
        } ?: return@runCatching null
        val contentSha256 = sha256Hex(temporaryDestination)
        val destination = finalizeImportedDraft(
          importRoot = importRoot,
          temporaryPath = temporaryDestination,
          contentSha256 = contentSha256,
          displayName = displayName,
        )
        ImportedChatAttachmentDraft(
          kind = resolveDraftKind(
            requestedKind = requestedKind,
            displayName = displayName,
            mimeType = mimeType,
          ),
          displayName = displayName,
          relativePath = normalizedWorkspaceRoot.relativize(destination).toString().replace('\\', '/'),
          mimeType = mimeType,
          sizeBytes = Files.size(destination),
        )
      }.getOrNull()
    }.distinctBy(ImportedChatAttachmentDraft::relativePath)
  }

  private fun loadMetadata(
    appContext: Context,
    uri: Uri,
  ): DraftAttachmentMetadata {
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
    return DraftAttachmentMetadata(
      displayName = displayName,
      mimeType = contentResolver.getType(uri)?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun resolveDisplayName(
    rawDisplayName: String?,
    mimeType: String?,
    requestedKind: String,
  ): String {
    rawDisplayName?.trim()?.takeIf(String::isNotBlank)?.let(::safeFileName)?.let { safeName ->
      return safeName
    }
    val extension = MimeTypeMap.getSingleton()
      .getExtensionFromMimeType(mimeType)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.lowercase(Locale.US)
    val baseName = when (requestedKind.trim().lowercase(Locale.US)) {
      "image" -> "image"
      else -> "file"
    }
    return if (extension == null) {
      "$baseName-${UUID.randomUUID().toString().take(8)}"
    } else {
      "$baseName-${UUID.randomUUID().toString().take(8)}.$extension"
    }
  }

  private fun temporaryDestination(importRoot: Path, displayName: String): Path {
    val prefix = UUID.randomUUID().toString().take(12)
    return importRoot.resolve("tmp-$prefix-${safeFileName(displayName)}")
  }

  private fun finalizeImportedDraft(
    importRoot: Path,
    temporaryPath: Path,
    contentSha256: String,
    displayName: String,
  ): Path {
    val contentDirectory = importRoot.resolve(contentSha256)
    Files.createDirectories(contentDirectory)
    Files.list(contentDirectory).use { stream ->
      stream
        .filter { candidate -> Files.isRegularFile(candidate) }
        .findFirst()
        .orElse(null)
        ?.let { existing ->
          Files.deleteIfExists(temporaryPath)
          return existing
        }
    }
    val destination = contentDirectory.resolve(safeFileName(displayName))
    Files.move(
      temporaryPath,
      destination,
      StandardCopyOption.REPLACE_EXISTING,
    )
    return destination
  }

  private fun resolveDraftKind(
    requestedKind: String,
    displayName: String,
    mimeType: String?,
  ): String {
    if (requestedKind.trim().lowercase(Locale.US) == "image") {
      return "image"
    }
    val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.US)
    if (normalizedMimeType?.startsWith("image/") == true) {
      return "image"
    }
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    return if (extension in IMAGE_EXTENSIONS) "image" else "file"
  }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "attachment.bin" }

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

  private data class DraftAttachmentMetadata(
    val displayName: String?,
    val mimeType: String?,
  )

  private val IMAGE_EXTENSIONS = setOf(
    "bmp",
    "gif",
    "heic",
    "heif",
    "jpeg",
    "jpg",
    "png",
    "webp",
  )
}
