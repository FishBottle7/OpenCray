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
    streamingEnabled: Boolean = LlmSettingsState.DEFAULT_STREAMING_ENABLED,
    openAiPromptCacheKeyStrategy: String = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
    openAiPromptCacheRetention: String = LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
    anthropicPromptCachingEnabled: Boolean =
      LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
    anthropicPromptCacheTtl: String = LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
  ): Map<String, String> = when (normalize(protocol)) {
    ANTHROPIC -> anthropicRouteMetadata(
      model = model,
      reasoningEffort = reasoningEffort,
      streamingEnabled = streamingEnabled,
      promptCachingEnabled = anthropicPromptCachingEnabled,
      promptCacheTtl = anthropicPromptCacheTtl,
    )

    OPENAI_RESPONSES -> openAiResponsesRouteMetadata(
      model = model,
      reasoningEffort = reasoningEffort,
      streamingEnabled = streamingEnabled,
      promptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
      promptCacheRetention = openAiPromptCacheRetention,
    )

    else -> openAiRouteMetadata(
      model = model,
      reasoningEffort = reasoningEffort,
      streamingEnabled = streamingEnabled,
      promptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
      promptCacheRetention = openAiPromptCacheRetention,
    )
  }

  private fun openAiRouteMetadata(
    model: String,
    reasoningEffort: String,
    streamingEnabled: Boolean,
    promptCacheKeyStrategy: String,
    promptCacheRetention: String,
  ): Map<String, String> {
    val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
    return buildMap {
      put("protocol", OPENAI)
      put("stream", streamingEnabled.toString())
      if (model.contains("gpt", ignoreCase = true) &&
        normalizedEffort != REASONING_EFFORT_OFF
      ) {
        put("reasoning_effort", normalizedEffort)
      }
      putAll(
        openAiPromptCacheMetadata(
          promptCacheKeyStrategy = promptCacheKeyStrategy,
          promptCacheRetention = promptCacheRetention,
        ),
      )
    }
  }

  private fun openAiResponsesRouteMetadata(
    model: String,
    reasoningEffort: String,
    streamingEnabled: Boolean,
    promptCacheKeyStrategy: String,
    promptCacheRetention: String,
  ): Map<String, String> {
    val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
    return buildMap {
      put("protocol", OPENAI_RESPONSES)
      put("stream", streamingEnabled.toString())
      put("responseApiPreferred", "true")
      if (model.contains("gpt", ignoreCase = true) &&
        normalizedEffort != REASONING_EFFORT_OFF
      ) {
        put("reasoning_effort", normalizedEffort)
      }
      putAll(
        openAiPromptCacheMetadata(
          promptCacheKeyStrategy = promptCacheKeyStrategy,
          promptCacheRetention = promptCacheRetention,
        ),
      )
    }
  }

  private fun anthropicRouteMetadata(
    model: String,
    reasoningEffort: String,
    streamingEnabled: Boolean,
    promptCachingEnabled: Boolean,
    promptCacheTtl: String,
  ): Map<String, String> {
    return buildMap {
      put("protocol", ANTHROPIC)
      put("stream", streamingEnabled.toString())
      if (!shouldDisableAnthropicThinkingForModel(model)) {
        val normalizedEffort = normalizedReasoningEffort(reasoningEffort)
        if (normalizedEffort != REASONING_EFFORT_OFF) {
          val thinkingBudget = when (normalizedEffort) {
            "low" -> "1024"
            "high" -> "8192"
            "xhigh" -> "16000"
            else -> "4096"
          }
          put("thinking_budget_tokens", thinkingBudget)
        }
      }
      if (promptCachingEnabled) {
        put(LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHING_ENABLED, "true")
        put(
          LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHE_TTL,
          LlmSettingsState.normalizedAnthropicPromptCacheTtl(promptCacheTtl),
        )
      }
    }
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

  private fun openAiPromptCacheMetadata(
    promptCacheKeyStrategy: String,
    promptCacheRetention: String,
  ): Map<String, String> = buildMap {
    val normalizedStrategy = LlmSettingsState.normalizedOpenAiPromptCacheKeyStrategy(
      promptCacheKeyStrategy,
    )
    if (normalizedStrategy != LlmPromptCacheKeyStrategies.NONE) {
      put(LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY, normalizedStrategy)
    }
    LlmSettingsState.normalizedOpenAiPromptCacheRetention(promptCacheRetention)
      .takeIf(String::isNotBlank)
      ?.let { normalizedRetention ->
        put(LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION, normalizedRetention)
      }
  }
}

internal object LlmPromptCachingMetadataKeys {
  const val PROMPT_CACHE_KEY_STRATEGY: String = "promptCacheKeyStrategy"
  const val PROMPT_CACHE_RETENTION: String = "promptCacheRetention"
  const val PROMPT_CACHE_HINTS_SUPPORTED: String = "promptCacheHintsSupported"
  const val ANTHROPIC_PROMPT_CACHING_ENABLED: String = "anthropicPromptCachingEnabled"
  const val ANTHROPIC_PROMPT_CACHE_TTL: String = "anthropicPromptCacheTtl"
}

internal object LlmPromptCacheKeyStrategies {
  const val NONE: String = "none"
  const val ROUTE: String = "route"
  const val SESSION: String = "session"
}

internal object LlmPromptCacheRetentionPolicies {
  const val IN_MEMORY: String = "in_memory"
  const val HOURS_24: String = "24h"
}

internal object AnthropicPromptCacheTtlPolicies {
  const val MINUTES_5: String = "5m"
  const val HOUR_1: String = "1h"
}

private const val DEFAULT_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS: Long = 30_000L
private const val DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS: Long = 15_000L
private const val ON_DEVICE_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS: Long = 180_000L
private const val ON_DEVICE_SHORT_PROVIDER_ROUTE_TIMEOUT_MS: Long = 60_000L
private const val KIMI_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS: Long = 120_000L
private const val KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS: Long = 60_000L

internal fun recommendedInteractiveProviderRouteTimeoutMs(
  model: String,
): Long = when {
  isOnDeviceRuntimeModel(model) -> ON_DEVICE_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS
  isLongRunningKimiModel(model) -> KIMI_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS
  else -> DEFAULT_INTERACTIVE_PROVIDER_ROUTE_TIMEOUT_MS
}

internal fun recommendedInterpreterProviderRouteTimeoutMs(
  model: String,
): Long = when {
  isOnDeviceRuntimeModel(model) -> ON_DEVICE_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
  isLongRunningKimiModel(model) -> KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
  else -> DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
}

internal fun recommendedValidationProviderRouteTimeoutMs(
  model: String,
): Long = when {
  isOnDeviceRuntimeModel(model) -> ON_DEVICE_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
  isLongRunningKimiModel(model) -> KIMI_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
  else -> DEFAULT_SHORT_PROVIDER_ROUTE_TIMEOUT_MS
}

private fun isOnDeviceRuntimeModel(
  model: String,
): Boolean {
  val normalized = model.trim().lowercase()
  if (normalized.isBlank()) {
    return false
  }
  return OnDeviceLlmCatalog.hasModel(normalized)
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
