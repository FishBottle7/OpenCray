package com.opencray.app

import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal object AppAgentWorkspaceVoicePlaybackLoader {
  fun loadSource(
    workspaceRoot: Path,
    relativePath: String,
  ): Map<String, Any?> {
    val target = AppAgentWorkspaceTextDocumentStore.resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
      allowRoot = false,
    )
    require(target.exists()) {
      "The selected file no longer exists."
    }
    require(target.isRegularFile()) {
      "Folders can't be played here."
    }
    val mimeType = resolveMimeType(target.name)
    require(isSupportedVoiceFile(target.name, mimeType)) {
      "Voice playback is available for MP3, WAV, and M4A files only."
    }
    val normalizedRelativePath = relativePath.trim().replace('\\', '/').removePrefix("/")
    return buildMap {
      put("name", target.name)
      put("relativePath", normalizedRelativePath)
      put("localFilePath", target.toAbsolutePath().normalize().toString())
      mimeType?.let { resolvedMimeType ->
        put("mimeType", resolvedMimeType)
      }
      put("sizeBytes", Files.size(target))
      AppAgentWorkspaceVoiceMetadataSupport.readDurationMs(target)?.let { durationMs ->
        put("durationMs", durationMs)
      }
    }
  }

  private fun isSupportedVoiceFile(
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in SUPPORTED_VOICE_EXTENSIONS || mimeType in SUPPORTED_VOICE_MIME_TYPES
  }

  private fun resolveMimeType(fileName: String): String? {
    val guessed = URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (guessed != null) {
      return guessed
    }
    return FALLBACK_MIME_TYPES[fileName.substringAfterLast('.', "").lowercase(Locale.US)]
  }

  private val SUPPORTED_VOICE_EXTENSIONS = setOf(
    "mp3",
    "wav",
    "m4a",
  )

  private val SUPPORTED_VOICE_MIME_TYPES = setOf(
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
  )

  private val FALLBACK_MIME_TYPES = mapOf(
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "m4a" to "audio/mp4",
  )
}
