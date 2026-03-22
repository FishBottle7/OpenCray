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
  val speechSynthesis: OpenCraySpeechSynthesisSettings? = null,
)

data class OpenCrayBinaryAsset(
  val bytes: ByteArray,
  val mimeType: String? = null,
  val fileName: String? = null,
)

data class OpenCrayImageGenerationRequest(
  val prompt: String,
  val count: Int = 1,
  val size: String? = null,
  val format: String? = null,
  val modelOverride: String? = null,
  val settings: OpenCrayImageGenerationSettings,
)

data class OpenCrayImageGenerationResponse(
  val images: List<OpenCrayBinaryAsset>,
  val providerRequestId: String? = null,
  val metadata: Map<String, String> = emptyMap(),
)

interface OpenCrayImageGenerationClient {
  fun generate(request: OpenCrayImageGenerationRequest): OpenCrayImageGenerationResponse
}

data class OpenCraySpeechSynthesisRequest(
  val text: String,
  val format: String? = null,
  val voiceOverride: String? = null,
  val modelOverride: String? = null,
  val settings: OpenCraySpeechSynthesisSettings,
)

data class OpenCraySpeechSynthesisResponse(
  val audio: OpenCrayBinaryAsset,
  val providerRequestId: String? = null,
  val durationMs: Long? = null,
  val transcriptText: String? = null,
  val metadata: Map<String, String> = emptyMap(),
)

interface OpenCraySpeechSynthesisClient {
  fun synthesize(request: OpenCraySpeechSynthesisRequest): OpenCraySpeechSynthesisResponse
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
