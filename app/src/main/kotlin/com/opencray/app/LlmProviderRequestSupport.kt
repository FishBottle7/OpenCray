package com.opencray.app

internal object LlmProviderProtocols {
  const val OPENAI: String = "openai"
  const val OPENAI_RESPONSES: String = "openai_responses"
  const val ANTHROPIC: String = "anthropic"

  private const val DEFAULT_ANTHROPIC_VERSION: String = "2023-06-01"
  private const val REASONING_EFFORT_OFF: String = "off"

  fun normalize(rawValue: String?): String = when (rawValue?.trim()?.lowercase()) {
    OPENAI_RESPONSES -> OPENAI_RESPONSES
    ANTHROPIC -> ANTHROPIC
    else -> OPENAI
  }

  fun authHeaders(
    protocol: String,
    apiKey: String,
  ): Map<String, String> {
    val sanitizedApiKey = apiKey.trim()
    if (sanitizedApiKey.isEmpty()) {
      return emptyMap()
    }
    return when (normalize(protocol)) {
      ANTHROPIC -> mapOf(
        "x-api-key" to sanitizedApiKey,
        "anthropic-version" to DEFAULT_ANTHROPIC_VERSION,
      )

      else -> mapOf(
        "Authorization" to "Bearer $sanitizedApiKey",
      )
    }
  }

  fun routeMetadata(
    protocol: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, String> = when (normalize(protocol)) {
    ANTHROPIC -> anthropicRouteMetadata(model = model, reasoningEffort = reasoningEffort)
    OPENAI_RESPONSES -> openAiResponsesRouteMetadata(model = model, reasoningEffort = reasoningEffort)
    else -> openAiRouteMetadata(model = model, reasoningEffort = reasoningEffort)
  }

  private fun openAiRouteMetadata(
    model: String,
    reasoningEffort: String,
  ): Map<String, String> {
    val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
    return if (model.contains("gpt", ignoreCase = true) &&
      normalizedEffort != REASONING_EFFORT_OFF
    ) {
      mapOf(
        "protocol" to OPENAI,
        "reasoning_effort" to normalizedEffort,
      )
    } else {
      mapOf("protocol" to OPENAI)
    }
  }

  private fun openAiResponsesRouteMetadata(
    model: String,
    reasoningEffort: String,
  ): Map<String, String> {
    val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
    return buildMap {
      put("protocol", OPENAI_RESPONSES)
      put("responseApiPreferred", "true")
      if (model.contains("gpt", ignoreCase = true) &&
        normalizedEffort != REASONING_EFFORT_OFF
      ) {
        put("reasoning_effort", normalizedEffort)
      }
    }
  }

  private fun anthropicRouteMetadata(
    model: String,
    reasoningEffort: String,
  ): Map<String, String> {
    if (shouldDisableAnthropicThinkingForModel(model)) {
      return mapOf("protocol" to ANTHROPIC)
    }
    val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
    if (normalizedEffort == REASONING_EFFORT_OFF) {
      return mapOf("protocol" to ANTHROPIC)
    }
    val thinkingBudget = when (normalizedEffort) {
      "low" -> "1024"
      "high" -> "8192"
      "xhigh" -> "16000"
      else -> "4096"
    }
    return mapOf(
      "protocol" to ANTHROPIC,
      "thinking_budget_tokens" to thinkingBudget,
    )
  }

  private fun shouldDisableAnthropicThinkingForModel(model: String): Boolean {
    val normalized = model.trim().lowercase()
    if (normalized.isBlank()) {
      return false
    }
    if (!normalized.contains("kimi")) {
      return false
    }
    return !normalized.contains("thinking")
  }

  private fun normalizedReasoningEffort(reasoningEffort: String): String =
    reasoningEffort.trim().ifBlank {
      LlmSettingsState.DEFAULT_REASONING_EFFORT
    }
}

private const val DEFAULT_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS: Long = 30_000L
private const val DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS: Long = 15_000L
private const val KIMI_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS: Long = 120_000L
private const val KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS: Long = 60_000L

internal fun recommendedInteractiveProviderRouteTimeoutMs(
  model: String,
): Long = if (isLongRunningKimiModel(model)) {
  KIMI_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS
} else {
  DEFAULT_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS
}

internal fun recommendedInterpreterProviderRouteTimeoutMs(
  model: String,
): Long = if (isLongRunningKimiModel(model)) {
  KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
} else {
  DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
}

internal fun recommendedValidationProviderRouteTimeoutMs(
  model: String,
): Long = if (isLongRunningKimiModel(model)) {
  KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
} else {
  DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
}

private fun isLongRunningKimiModel(
  model: String,
): Boolean {
  val normalized = model.trim().lowercase()
  if (normalized.isBlank()) {
    return false
  }
  return normalized.contains("kimi-k2")
}
