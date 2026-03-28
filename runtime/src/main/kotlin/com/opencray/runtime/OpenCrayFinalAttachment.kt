package com.opencray.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OpenCrayFinalAttachment(
  val kind: String? = null,
  val relativePath: String? = null,
  val path: String? = null,
  val artifactId: String? = null,
  val chatAttachmentId: String? = null,
  val displayName: String? = null,
  val mimeType: String? = null,
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
)

@Serializable
data class OpenCrayAttachmentArtifact(
  val artifactId: String,
  val relativePath: String,
  val displayName: String? = null,
  val kindHint: String? = null,
  val mimeType: String? = null,
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
)

object OpenCrayExecutionMetadataKeys {
  const val FINAL_ATTACHMENTS_JSON: String = "_host.finalAttachmentsJson"
  const val APPROVAL_RESUME_TOOL_NAME: String = "approvalResumeToolName"
}

object OpenCrayAttachmentArtifactMetadataKeys {
  const val ARTIFACTS_JSON: String = "attachmentArtifactsJson"
  const val ARTIFACT_ID: String = "attachmentArtifactId"
  const val ARTIFACT_RELATIVE_PATH: String = "attachmentArtifactRelativePath"
  const val ARTIFACT_DISPLAY_NAME: String = "attachmentArtifactDisplayName"
  const val ARTIFACT_KIND_HINT: String = "attachmentArtifactKindHint"
  const val ARTIFACT_MIME_TYPE: String = "attachmentArtifactMimeType"
  const val ARTIFACT_DURATION_MS: String = "attachmentArtifactDurationMs"
  const val ARTIFACT_WAVEFORM_BARS: String = "attachmentArtifactWaveformBars"
  const val ARTIFACT_TRANSCRIPT_TEXT: String = "attachmentArtifactTranscriptText"
}

object OpenCrayAttachmentArtifacts {
  private val IMAGE_EXTENSIONS: Set<String> = setOf(
    "png",
    "jpg",
    "jpeg",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
  )
  private val AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3",
    "wav",
    "m4a",
    "aac",
    "ogg",
    "flac",
    "opus",
  )
  private val FALLBACK_MIME_TYPES: Map<String, String> = mapOf(
    "aac" to "audio/aac",
    "bmp" to "image/bmp",
    "csv" to "text/csv",
    "flac" to "audio/flac",
    "gif" to "image/gif",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "json" to "application/json",
    "m4a" to "audio/mp4",
    "md" to "text/markdown",
    "mp3" to "audio/mpeg",
    "ogg" to "audio/ogg",
    "opus" to "audio/ogg",
    "pdf" to "application/pdf",
    "png" to "image/png",
    "svg" to "image/svg+xml",
    "txt" to "text/plain",
    "wav" to "audio/wav",
    "webp" to "image/webp",
    "xml" to "application/xml",
    "yml" to "application/yaml",
    "yaml" to "application/yaml",
  )

  fun fromWorkspaceRelativePaths(relativePaths: List<String>): List<OpenCrayAttachmentArtifact> =
    relativePaths
      .mapNotNull(::fromWorkspaceRelativePath)
      .distinctBy(OpenCrayAttachmentArtifact::artifactId)

  fun fromWorkspaceRelativePath(relativePath: String): OpenCrayAttachmentArtifact? {
    val normalizedRelativePath = relativePath
      .trim()
      .replace('\\', '/')
      .trim('/')
      .takeIf(String::isNotBlank)
      ?: return null
    val displayName = normalizedRelativePath.substringAfterLast('/')
      .trim()
      .takeIf(String::isNotBlank)
      ?: return null
    return OpenCrayAttachmentArtifact(
      artifactId = buildArtifactId(
        relativePath = normalizedRelativePath,
        displayName = displayName,
      ),
      relativePath = normalizedRelativePath,
      displayName = displayName,
      kindHint = kindHintForDisplayName(displayName),
      mimeType = mimeTypeForDisplayName(displayName),
    )
  }

  fun encodeMetadata(
    json: Json,
    artifacts: List<OpenCrayAttachmentArtifact>,
  ): Map<String, String> {
    if (artifacts.isEmpty()) {
      return emptyMap()
    }
    val primary = artifacts.first()
    return buildMap {
      put(
        OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON,
        json.encodeToString(
          ListSerializer(OpenCrayAttachmentArtifact.serializer()),
          artifacts,
        ),
      )
      put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID, primary.artifactId)
      put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH, primary.relativePath)
      primary.displayName?.let { put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME, it) }
      primary.kindHint?.let { put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT, it) }
      primary.mimeType?.let { put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE, it) }
      primary.durationMs?.let { put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DURATION_MS, it.toString()) }
      if (primary.waveformBars.isNotEmpty()) {
        put(
          OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_WAVEFORM_BARS,
          primary.waveformBars.joinToString(separator = ","),
        )
      }
      primary.transcriptText?.let { put(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_TRANSCRIPT_TEXT, it) }
    }
  }

  fun decodeMetadata(
    json: Json,
    metadata: Map<String, String>,
  ): List<OpenCrayAttachmentArtifact> {
    metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { artifactsJson ->
        runCatching {
          json.decodeFromString(
            ListSerializer(OpenCrayAttachmentArtifact.serializer()),
            artifactsJson,
          )
        }.getOrNull()?.takeIf(List<OpenCrayAttachmentArtifact>::isNotEmpty)?.let { artifacts ->
          return artifacts
        }
      }
    val artifactId = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    val relativePath = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return listOf(
      OpenCrayAttachmentArtifact(
        artifactId = artifactId,
        relativePath = relativePath,
        displayName = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        kindHint = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        mimeType = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        durationMs = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DURATION_MS]
          ?.trim()
          ?.toLongOrNull(),
        waveformBars = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_WAVEFORM_BARS]
          ?.split(',')
          ?.mapNotNull { value -> value.trim().toIntOrNull() }
          .orEmpty(),
        transcriptText = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_TRANSCRIPT_TEXT]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      ),
    )
  }

  fun buildArtifactId(
    relativePath: String,
    displayName: String,
  ): String {
    val baseName = displayName.substringBeforeLast('.', displayName)
      .lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .ifBlank { "file" }
      .take(48)
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(relativePath.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }
      .take(8)
    return "artifact-$baseName-$digest"
  }

  fun kindHintForDisplayName(displayName: String): String? {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    return when {
      extension in IMAGE_EXTENSIONS -> "image"
      extension in AUDIO_EXTENSIONS -> "voice"
      else -> "file"
    }
  }

  fun mimeTypeForDisplayName(displayName: String): String? {
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    return FALLBACK_MIME_TYPES[extension]
  }
}
