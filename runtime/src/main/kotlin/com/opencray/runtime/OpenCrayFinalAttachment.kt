package com.opencray.runtime

import kotlinx.serialization.Serializable

@Serializable
data class OpenCrayFinalAttachment(
  val kind: String? = null,
  val relativePath: String? = null,
  val path: String? = null,
  val artifactId: String? = null,
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
