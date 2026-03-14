package com.opencray.app

internal object LlmProviderProtocols {
  const val OPENAI: String = "openai"
  const val ANTHROPIC: String = "anthropic"

  private const val DEFAULT_ANTHROPIC_VERSION: String = "2023-06-01"

  fun normalize(rawValue: String?): String = when (rawValue?.trim()?.lowercase()) {
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
    ANTHROPIC -> anthropicRouteMetadata(reasoningEffort)
    else -> openAiRouteMetadata(model = model, reasoningEffort = reasoningEffort)
  }

  private fun openAiRouteMetadata(
    model: String,
    reasoningEffort: String,
  ): Map<String, String> = if (model.contains("gpt", ignoreCase = true)) {
    mapOf(
      "protocol" to OPENAI,
      "reasoning_effort" to reasoningEffort.trim().ifBlank {
        LlmSettingsState.DEFAULT_REASONING_EFFORT
      },
    )
  } else {
    mapOf("protocol" to OPENAI)
  }

  private fun anthropicRouteMetadata(reasoningEffort: String): Map<String, String> {
    val normalizedEffort = reasoningEffort.trim().ifBlank {
      LlmSettingsState.DEFAULT_REASONING_EFFORT
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
}
