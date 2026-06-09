package com.opencray.runtime

import java.nio.file.Path

data class OpenCrayImageGenerationSettings(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
  val authHeaders: Map<String, String> = emptyMap(),
) {
  fun isConfigured(): Boolean =
    baseUrl.trim().isNotEmpty() &&
      endpoint.trim().isNotEmpty() &&
      model.trim().isNotEmpty()
}

data class OpenCrayVideoGenerationSettings(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
  val authHeaders: Map<String, String> = emptyMap(),
) {
  fun isConfigured(): Boolean =
    baseUrl.trim().isNotEmpty() &&
      endpoint.trim().isNotEmpty() &&
      model.trim().isNotEmpty()
}

data class OpenCraySpeechSynthesisSettings(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val defaultModel: String = DEFAULT_MODEL,
  val defaultVoice: String,
  val authHeaders: Map<String, String> = emptyMap(),
) {
  fun isConfigured(): Boolean =
    baseUrl.trim().isNotEmpty() &&
      endpoint.trim().isNotEmpty() &&
      defaultVoice.trim().isNotEmpty()

  companion object {
    const val DEFAULT_MODEL: String = "tts-1"
  }
}

data class OpenCrayMediaToolSettings(
  val imageGeneration: OpenCrayImageGenerationSettings? = null,
  val videoGeneration: OpenCrayVideoGenerationSettings? = null,
  val speechSynthesis: OpenCraySpeechSynthesisSettings? = null,
)

data class OpenCrayBinaryAsset(
  val bytes: ByteArray = ByteArray(0),
  val sourcePath: Path? = null,
  val mimeType: String? = null,
  val fileName: String? = null,
) {
  init {
    require(bytes.isNotEmpty() || sourcePath != null) {
      "OpenCrayBinaryAsset requires non-empty bytes or a sourcePath."
    }
  }
}

data class OpenCrayImageGenerationRequest(
  val prompt: String,
  val count: Int = 1,
  val size: String? = null,
  val format: String? = null,
  val modelOverride: String? = null,
  val preferAsync: Boolean = false,
  val settings: OpenCrayImageGenerationSettings,
)

data class OpenCrayImageGenerationResponse(
  val images: List<OpenCrayBinaryAsset> = emptyList(),
  val providerRequestId: String? = null,
  val metadata: Map<String, String> = emptyMap(),
  val pendingJob: OpenCrayMediaJobSnapshot? = null,
)

interface OpenCrayImageGenerationClient {
  fun generate(
    request: OpenCrayImageGenerationRequest,
    cancellationRequested: () -> Boolean = { false },
  ): OpenCrayImageGenerationResponse
}

data class OpenCrayVideoGenerationRequest(
  val prompt: String,
  val durationSeconds: Int? = null,
  val size: String? = null,
  val format: String? = null,
  val modelOverride: String? = null,
  val preferAsync: Boolean = false,
  val settings: OpenCrayVideoGenerationSettings,
)

data class OpenCrayVideoGenerationResponse(
  val videos: List<OpenCrayBinaryAsset> = emptyList(),
  val providerRequestId: String? = null,
  val metadata: Map<String, String> = emptyMap(),
  val pendingJob: OpenCrayMediaJobSnapshot? = null,
)

interface OpenCrayVideoGenerationClient {
  fun generateVideo(
    request: OpenCrayVideoGenerationRequest,
    cancellationRequested: () -> Boolean = { false },
  ): OpenCrayVideoGenerationResponse
}

data class OpenCraySpeechSynthesisRequest(
  val text: String,
  val format: String? = null,
  val voiceOverride: String? = null,
  val modelOverride: String? = null,
  val preferAsync: Boolean = false,
  val settings: OpenCraySpeechSynthesisSettings,
)

data class OpenCraySpeechSynthesisResponse(
  val audio: OpenCrayBinaryAsset? = null,
  val providerRequestId: String? = null,
  val durationMs: Long? = null,
  val transcriptText: String? = null,
  val metadata: Map<String, String> = emptyMap(),
  val pendingJob: OpenCrayMediaJobSnapshot? = null,
)

interface OpenCraySpeechSynthesisClient {
  fun synthesize(
    request: OpenCraySpeechSynthesisRequest,
    cancellationRequested: () -> Boolean = { false },
  ): OpenCraySpeechSynthesisResponse
}

enum class OpenCrayMediaJobStatus {
  PENDING,
  COMPLETED,
  FAILED,
  CANCELLED,
}

data class OpenCrayMediaJobReceipt(
  val jobId: String,
  val toolName: String,
  val status: OpenCrayMediaJobStatus,
  val pollToolName: String = "PollMediaJob",
  val cancelToolName: String = "CancelMediaJob",
  val pollAfterMs: Long = 1_000L,
)

data class OpenCrayMediaJobSnapshot(
  val receipt: OpenCrayMediaJobReceipt,
  val providerRequestId: String? = null,
  val metadata: Map<String, String> = emptyMap(),
)

data class OpenCrayMediaJobPollResult(
  val snapshot: OpenCrayMediaJobSnapshot,
  val images: List<OpenCrayBinaryAsset> = emptyList(),
  val videos: List<OpenCrayBinaryAsset> = emptyList(),
  val audio: OpenCrayBinaryAsset? = null,
  val durationMs: Long? = null,
  val transcriptText: String? = null,
  val metadata: Map<String, String> = emptyMap(),
)

interface OpenCrayMediaJobClient {
  fun poll(
    job: OpenCrayMediaJobSnapshot,
    settings: OpenCrayMediaToolSettings,
    cancellationRequested: () -> Boolean = { false },
  ): OpenCrayMediaJobPollResult

  fun cancel(
    job: OpenCrayMediaJobSnapshot,
    settings: OpenCrayMediaToolSettings,
    cancellationRequested: () -> Boolean = { false },
  ): OpenCrayMediaJobSnapshot
}

data class OpenCrayGeneratedWorkspaceArtifact(
  val path: Path,
  val kindHint: String? = null,
  val mimeType: String? = null,
  val displayName: String? = null,
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
)
