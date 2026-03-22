package com.opencray.app

internal object LiteLlmJsonExtractionSupport {
  const val DEFAULT_TIMEOUT_MS: Long = 45_000L

  private const val DEFAULT_REASONING_EFFORT: String = "low"
  private const val DEFAULT_MAX_TOKENS: String = "512"
  private const val DEFAULT_TEMPERATURE: String = "0"

  fun routeMetadata(settings: LlmSettingsState): Map<String, String> =
    LlmProviderProtocols.routeMetadata(
      protocol = settings.protocol,
      model = settings.model,
      reasoningEffort = DEFAULT_REASONING_EFFORT,
    ) + mapOf(
      "max_tokens" to DEFAULT_MAX_TOKENS,
      "temperature" to DEFAULT_TEMPERATURE,
    )
}
