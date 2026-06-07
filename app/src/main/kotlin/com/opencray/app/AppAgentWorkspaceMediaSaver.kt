package com.opencray.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import org.opencray.app.R

internal data class SavedWorkspaceMediaAttachment(
  val displayName: String,
  val collection: String,
  val uri: String? = null,
  val absolutePath: String? = null,
)

internal object AppAgentWorkspaceMediaSaver {
  fun saveAttachment(
    appContext: Context,
    workspaceRoot: Path,
    relativePath: String,
    kind: String,
  ): SavedWorkspaceMediaAttachment {
    val source = resolveSource(
      appContext = appContext,
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
    )
    require(source.exists()) {
      appContext.getString(R.string.media_save_error_missing_entry)
    }
    require(source.isRegularFile()) {
      appContext.getString(R.string.media_save_error_not_file)
    }

    val displayName = safeFileName(source.fileName?.toString().orEmpty())
    val mimeType = resolveMimeType(displayName)
    val collection = collectionFor(kind = kind, mimeType = mimeType)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      saveWithMediaStore(
        appContext = appContext,
        source = source,
        displayName = displayName,
        mimeType = mimeType,
        collection = collection,
      )
    } else {
      saveWithPublicDirectory(
        source = source,
        displayName = displayName,
        collection = collection,
      )
    }
  }

  private fun saveWithMediaStore(
    appContext: Context,
    source: Path,
    displayName: String,
    mimeType: String,
    collection: MediaSaveCollection,
  ): SavedWorkspaceMediaAttachment {
    val resolver = appContext.contentResolver
    val targetUri = when (collection) {
      MediaSaveCollection.RECORDINGS -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      MediaSaveCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
      put(MediaStore.MediaColumns.RELATIVE_PATH, collection.relativeMediaStorePath())
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(targetUri, values)
      ?: throw IllegalStateException(appContext.getString(R.string.media_save_error_storage_unavailable))
    try {
      resolver.openOutputStream(uri)?.use { output ->
        source.inputStream().use { input ->
          input.copyTo(output)
        }
      } ?: throw IllegalStateException(appContext.getString(R.string.media_save_error_storage_unavailable))
      resolver.update(
        uri,
        ContentValues().apply {
          put(MediaStore.MediaColumns.IS_PENDING, 0)
        },
        null,
        null,
      )
      return SavedWorkspaceMediaAttachment(
        displayName = displayName,
        collection = collection.wireValue,
        uri = uri.toString(),
      )
    } catch (throwable: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw IllegalStateException(appContext.getString(R.string.media_save_error_prepare_failed), throwable)
    }
  }

  private fun saveWithPublicDirectory(
    source: Path,
    displayName: String,
    collection: MediaSaveCollection,
  ): SavedWorkspaceMediaAttachment {
    val directory = collection.publicDirectory()
    Files.createDirectories(directory)
    val destination = uniqueDestination(
      directory = directory,
      desiredName = displayName,
    )
    Files.copy(
      source,
      destination,
      StandardCopyOption.COPY_ATTRIBUTES,
    )
    return SavedWorkspaceMediaAttachment(
      displayName = destination.fileName.toString(),
      collection = collection.wireValue,
      absolutePath = destination.toString(),
    )
  }

  private fun resolveSource(
    appContext: Context,
    workspaceRoot: Path,
    relativePath: String,
  ): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    require(trimmed.isNotEmpty()) {
      appContext.getString(R.string.media_save_error_outside_workspace)
    }
    val resolved = normalizedRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(normalizedRoot)) {
      appContext.getString(R.string.media_save_error_outside_workspace)
    }
    return resolved
  }

  private fun collectionFor(
    kind: String,
    mimeType: String,
  ): MediaSaveCollection {
    val normalizedKind = kind.trim().lowercase(Locale.US)
    return when {
      normalizedKind == "voice" || normalizedKind == "audio" -> MediaSaveCollection.RECORDINGS
      mimeType.lowercase(Locale.US).startsWith("audio/") -> MediaSaveCollection.RECORDINGS
      else -> MediaSaveCollection.DOWNLOADS
    }
  }

  private fun resolveMimeType(fileName: String): String {
    val guessed = URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (guessed != null) {
      return guessed
    }
    return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
      "m4a" -> "audio/mp4"
      "mp3" -> "audio/mpeg"
      "wav" -> "audio/wav"
      "webp" -> "image/webp"
      "png" -> "image/png"
      "jpg",
      "jpeg",
      -> "image/jpeg"
      "md" -> "text/markdown"
      "json" -> "application/json"
      "txt",
      "log",
      -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  private fun uniqueDestination(
    directory: Path,
    desiredName: String,
  ): Path {
    val extension = desiredName.substringAfterLast('.', "")
    val baseName = if (extension.isEmpty() || !desiredName.contains('.')) {
      desiredName
    } else {
      desiredName.substring(0, desiredName.length - extension.length - 1)
    }
    var candidate = directory.resolve(desiredName)
    var suffix = 2
    while (candidate.exists()) {
      val suffixName = if (extension.isEmpty()) {
        "$baseName ($suffix)"
      } else {
        "$baseName ($suffix).$extension"
      }
      candidate = directory.resolve(suffixName)
      suffix += 1
    }
    return candidate
  }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "opencray-attachment.bin" }

  private enum class MediaSaveCollection(
    val wireValue: String,
  ) {
    DOWNLOADS("downloads"),
    RECORDINGS("recordings"),
    ;

    fun relativeMediaStorePath(): String = when (this) {
      DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
      RECORDINGS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Environment.DIRECTORY_RECORDINGS
      } else {
        "${Environment.DIRECTORY_MUSIC}/Recordings"
      }
    }

    fun publicDirectory(): Path = when (this) {
      DOWNLOADS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath()
      RECORDINGS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS).toPath()
      } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).toPath().resolve("Recordings")
      }
    }
  }
}
