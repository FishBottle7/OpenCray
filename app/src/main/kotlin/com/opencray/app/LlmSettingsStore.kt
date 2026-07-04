package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.context.ModelContextBudgetPreset
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_LLM_SETTINGS_PREFERENCES = "opencray.llm-settings"
private const val LLM_SETTINGS_FILE_NAME = "llm-settings.json"

internal object LlmSettingsStoreKeys {
  const val ENABLED = "enabled"
  const val STREAMING_ENABLED = "streaming_enabled"
  const val PROVIDER_MODE = "provider_mode"
  const val PROVIDER_ID = "provider_id"
  const val SELECTED_PROVIDER_OPTION_ID = "selected_provider_option_id"
  const val PROTOCOL = "protocol"
  const val PROVIDER_NAME = "provider_name"
  const val PROVIDER_NOTES = "provider_notes"
  const val BASE_URL = "base_url"
  const val API_KEY = "api_key"
  const val MODEL = "model"
  const val REASONING_EFFORT = "reasoning_effort"
  const val SYSTEM_PROMPT = "system_prompt"
  const val OPENAI_PROMPT_CACHE_KEY_STRATEGY = "openai_prompt_cache_key_strategy"
  const val OPENAI_PROMPT_CACHE_RETENTION = "openai_prompt_cache_retention"
  const val ANTHROPIC_PROMPT_CACHING_ENABLED = "anthropic_prompt_caching_enabled"
  const val ANTHROPIC_PROMPT_CACHE_TTL = "anthropic_prompt_cache_ttl"
  const val CONTEXT_BUDGET_PRESET = "context_budget_preset"
  const val CONTEXT_BUDGET_RESERVED_OUTPUT_TOKENS = "context_budget_reserved_output_tokens"
  const val CONTEXT_BUDGET_SAFETY_MARGIN_TOKENS = "context_budget_safety_margin_tokens"
  const val CONTEXT_BUDGET_EFFECTIVE_INPUT_PERCENT = "context_budget_effective_input_percent"
  const val SELECTED_ON_DEVICE_MODEL_ID = "selected_on_device_model_id"
  const val ON_DEVICE_MAX_CONTEXT_WINDOW = "on_device_max_context_window"
  const val ON_DEVICE_MAX_TOKENS = "on_device_max_tokens"
  const val ON_DEVICE_TOP_K = "on_device_top_k"
  const val ON_DEVICE_TOP_P = "on_device_top_p"
  const val ON_DEVICE_TEMPERATURE = "on_device_temperature"
  const val ON_DEVICE_ACCELERATOR = "on_device_accelerator"
  const val ON_DEVICE_THINKING_ENABLED = "on_device_thinking_enabled"
  const val ON_DEVICE_LITE_MODE_ENABLED = "on_device_lite_mode_enabled"
  const val SAVED_CUSTOM_PROVIDERS = "saved_custom_providers"
  const val AGENT_CAPABILITY_CACHE = "agent_capability_cache"
}

internal data class LlmSettingsState(
  val enabled: Boolean = false,
  val streamingEnabled: Boolean = DEFAULT_STREAMING_ENABLED,
  val providerMode: String = DEFAULT_PROVIDER_MODE,
  val providerId: String = DEFAULT_PROVIDER_ID,
  val protocol: String = DEFAULT_PROTOCOL,
  val providerName: String = DEFAULT_PROVIDER_NAME,
  val providerNotes: String = "",
  val baseUrl: String = DEFAULT_BASE_URL,
  val apiKey: String = "",
  val model: String = DEFAULT_MODEL,
  val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
  val systemPrompt: String = "",
  val openAiPromptCacheKeyStrategy: String = DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
  val openAiPromptCacheRetention: String = DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
  val anthropicPromptCachingEnabled: Boolean = DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
  val anthropicPromptCacheTtl: String = DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
  val contextBudgetPreset: String = DEFAULT_CONTEXT_BUDGET_PRESET,
  val contextBudgetReservedOutputTokens: Int? = null,
  val contextBudgetSafetyMarginTokens: Int? = null,
  val contextBudgetEffectiveInputPercent: Double? = null,
  val selectedOnDeviceModelId: String = DEFAULT_ON_DEVICE_MODEL_ID,
  val onDeviceMaxContextWindow: Int = DEFAULT_ON_DEVICE_MAX_CONTEXT_WINDOW,
  val onDeviceMaxTokens: Int = DEFAULT_ON_DEVICE_MAX_TOKENS,
  val onDeviceTopK: Int = DEFAULT_ON_DEVICE_TOP_K,
  val onDeviceTopP: Double = DEFAULT_ON_DEVICE_TOP_P,
  val onDeviceTemperature: Double = DEFAULT_ON_DEVICE_TEMPERATURE,
  val onDeviceAccelerator: String = DEFAULT_ON_DEVICE_ACCELERATOR,
  val onDeviceThinkingEnabled: Boolean = DEFAULT_ON_DEVICE_THINKING_ENABLED,
  val onDeviceLiteModeEnabled: Boolean = DEFAULT_ON_DEVICE_LITE_MODE_ENABLED,
  val agentCapability: LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(),
) {
  fun isOnDeviceProviderMode(): Boolean = providerMode == LlmProviderModes.ON_DEVICE_MODEL

  fun isOnDeviceLiteModeEnabled(): Boolean =
    isOnDeviceProviderMode() && onDeviceLiteModeEnabled

  fun isConfigured(): Boolean =
    if (isOnDeviceProviderMode()) {
      selectedOnDeviceModelId.trim().isNotEmpty()
    } else {
      baseUrl.trim().isNotEmpty() &&
        (
          apiKey.trim().isNotEmpty() ||
            llmEndpointAllowsBlankApiKey(
              protocol = protocol,
              baseUrl = baseUrl,
            )
          )
    }

  fun contextBudgetRuntimeMetadataOverrides(): Map<String, String> = buildMap {
    put("context_budget_preset", contextBudgetPreset)
    contextBudgetReservedOutputTokens?.let { override ->
      put("reserved_output_tokens", override.toString())
    }
    contextBudgetSafetyMarginTokens?.let { override ->
      put("prompt_safety_margin_tokens", override.toString())
    }
    contextBudgetEffectiveInputPercent?.let { override ->
      put("effective_input_percent", override.toString())
    }
  }

  fun sanitized(): LlmSettingsState {
    val normalizedProviderMode = LlmProviderModes.normalize(providerMode)
    val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
    val normalizedBaseUrl = baseUrl.trim()
    val normalizedModel = model.trim()
    val normalizedContextWindow = normalizedOnDeviceMaxContextWindow(onDeviceMaxContextWindow)
    return copy(
      providerMode = normalizedProviderMode,
      providerId = providerId.trim().ifBlank { inferProviderId(normalizedBaseUrl) },
      protocol = normalizedProtocol,
      providerName = providerName.trim(),
      providerNotes = providerNotes.trim(),
      baseUrl = normalizedBaseUrl,
      apiKey = apiKey.trim(),
      model = normalizedModel,
      reasoningEffort = reasoningEffort.trim().ifBlank { DEFAULT_REASONING_EFFORT },
      systemPrompt = systemPrompt.trim(),
      openAiPromptCacheKeyStrategy = normalizedOpenAiPromptCacheKeyStrategy(
        openAiPromptCacheKeyStrategy,
      ),
      openAiPromptCacheRetention = normalizedOpenAiPromptCacheRetention(
        openAiPromptCacheRetention,
      ),
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = normalizedAnthropicPromptCacheTtl(
        anthropicPromptCacheTtl,
      ),
      contextBudgetPreset = normalizedContextBudgetPreset(contextBudgetPreset),
      contextBudgetReservedOutputTokens = normalizedContextBudgetTokenOverride(
        contextBudgetReservedOutputTokens,
      ),
      contextBudgetSafetyMarginTokens = normalizedContextBudgetTokenOverride(
        contextBudgetSafetyMarginTokens,
      ),
      contextBudgetEffectiveInputPercent = normalizedContextBudgetEffectiveInputPercent(
        contextBudgetEffectiveInputPercent,
      ),
      selectedOnDeviceModelId = normalizedOnDeviceModelId(selectedOnDeviceModelId),
      onDeviceMaxContextWindow = normalizedContextWindow,
      onDeviceMaxTokens = normalizedOnDeviceMaxTokens(
        rawValue = onDeviceMaxTokens,
        contextWindow = normalizedContextWindow,
      ),
      onDeviceTopK = normalizedOnDeviceTopK(onDeviceTopK),
      onDeviceTopP = normalizedOnDeviceTopP(onDeviceTopP),
      onDeviceTemperature = normalizedOnDeviceTemperature(onDeviceTemperature),
      onDeviceAccelerator = OnDeviceLlmAccelerators.normalize(onDeviceAccelerator),
      onDeviceThinkingEnabled = onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
      agentCapability = agentCapability.normalizedForRoute(
        protocol = normalizedProtocol,
        baseUrl = normalizedBaseUrl,
        model = normalizedModel,
      ),
    )
  }

  companion object {
    const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
    const val DEFAULT_MODEL: String = "gpt-4o-mini"
    const val DEFAULT_PROVIDER_MODE: String = LlmProviderModes.CLOUD
    const val DEFAULT_PROVIDER_ID: String = "openai"
    const val DEFAULT_PROTOCOL: String = LlmProviderProtocols.OPENAI
    const val DEFAULT_PROVIDER_NAME: String = "OpenAI"
    const val DEFAULT_STREAMING_ENABLED: Boolean = true
    const val DEFAULT_REASONING_EFFORT: String = "medium"
    const val DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY: String =
      LlmPromptCacheKeyStrategies.NONE
    const val DEFAULT_OPENAI_PROMPT_CACHE_RETENTION: String = ""
    const val DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED: Boolean = false
    const val DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL: String =
      AnthropicPromptCacheTtlPolicies.MINUTES_5
    val DEFAULT_CONTEXT_BUDGET_PRESET: String = ModelContextBudgetPreset.BALANCED.wireValue
    const val DEFAULT_ON_DEVICE_MODEL_ID: String = OnDeviceLlmCatalog.DEFAULT_MODEL_ID
    const val DEFAULT_ON_DEVICE_MAX_CONTEXT_WINDOW: Int = 32_768
    const val DEFAULT_ON_DEVICE_MAX_TOKENS: Int = 4_096
    const val DEFAULT_ON_DEVICE_TOP_K: Int = 40
    const val DEFAULT_ON_DEVICE_TOP_P: Double = 0.95
    const val DEFAULT_ON_DEVICE_TEMPERATURE: Double = 0.70
    const val DEFAULT_ON_DEVICE_ACCELERATOR: String = OnDeviceLlmAccelerators.GPU
    const val DEFAULT_ON_DEVICE_THINKING_ENABLED: Boolean = false
    const val DEFAULT_ON_DEVICE_LITE_MODE_ENABLED: Boolean = false
    const val MIN_ON_DEVICE_MAX_CONTEXT_WINDOW: Int = 1_024
    const val MAX_ON_DEVICE_MAX_CONTEXT_WINDOW: Int = 131_072
    const val MIN_ON_DEVICE_MAX_TOKENS: Int = 256
    const val MIN_ON_DEVICE_TOP_K: Int = 1
    const val MAX_ON_DEVICE_TOP_K: Int = 128
    const val MIN_ON_DEVICE_TOP_P: Double = 0.0
    const val MAX_ON_DEVICE_TOP_P: Double = 1.0
    const val MIN_ON_DEVICE_TEMPERATURE: Double = 0.0
    const val MAX_ON_DEVICE_TEMPERATURE: Double = 2.0

    fun inferProviderId(baseUrl: String): String =
      LlmProviderCatalog.inferPresetId(baseUrl)

    fun normalizedOpenAiPromptCacheKeyStrategy(rawValue: String): String = when (
      rawValue.trim().lowercase()
    ) {
      LlmPromptCacheKeyStrategies.ROUTE -> LlmPromptCacheKeyStrategies.ROUTE
      LlmPromptCacheKeyStrategies.SESSION -> LlmPromptCacheKeyStrategies.SESSION
      else -> DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY
    }

    fun normalizedOpenAiPromptCacheRetention(rawValue: String): String = when (
      rawValue.trim().lowercase()
    ) {
      LlmPromptCacheRetentionPolicies.IN_MEMORY -> LlmPromptCacheRetentionPolicies.IN_MEMORY
      LlmPromptCacheRetentionPolicies.HOURS_24 -> LlmPromptCacheRetentionPolicies.HOURS_24
      else -> DEFAULT_OPENAI_PROMPT_CACHE_RETENTION
    }

    fun normalizedAnthropicPromptCacheTtl(rawValue: String): String = when (
      rawValue.trim().lowercase()
    ) {
      AnthropicPromptCacheTtlPolicies.HOUR_1 -> AnthropicPromptCacheTtlPolicies.HOUR_1
      else -> DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL
    }

    fun normalizedContextBudgetPreset(rawValue: String): String =
      ModelContextBudgetPreset.fromWireValue(rawValue)?.wireValue
        ?: DEFAULT_CONTEXT_BUDGET_PRESET

    private fun normalizedContextBudgetTokenOverride(rawValue: Int?): Int? =
      rawValue?.takeIf { value -> value > 0 }

    private fun normalizedContextBudgetEffectiveInputPercent(rawValue: Double?): Double? =
      rawValue
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.1, 1.0)
    fun normalizedOnDeviceModelId(rawValue: String): String {
      val normalized = rawValue.trim().lowercase()
      return normalized.takeIf(OnDeviceLlmCatalog::hasModel) ?: DEFAULT_ON_DEVICE_MODEL_ID
    }

    fun normalizedOnDeviceMaxContextWindow(rawValue: Int): Int =
      rawValue.coerceIn(
        MIN_ON_DEVICE_MAX_CONTEXT_WINDOW,
        MAX_ON_DEVICE_MAX_CONTEXT_WINDOW,
      )

    fun normalizedOnDeviceMaxTokens(
      rawValue: Int,
      contextWindow: Int,
    ): Int = rawValue.coerceIn(
      MIN_ON_DEVICE_MAX_TOKENS,
      contextWindow.coerceAtLeast(MIN_ON_DEVICE_MAX_TOKENS),
    )

    fun normalizedOnDeviceTopK(rawValue: Int): Int =
      rawValue.coerceIn(MIN_ON_DEVICE_TOP_K, MAX_ON_DEVICE_TOP_K)

    fun normalizedOnDeviceTopP(rawValue: Double): Double =
      rawValue.coerceIn(MIN_ON_DEVICE_TOP_P, MAX_ON_DEVICE_TOP_P)
        .roundToDecimals(2)

    fun normalizedOnDeviceTemperature(rawValue: Double): Double =
      rawValue.coerceIn(MIN_ON_DEVICE_TEMPERATURE, MAX_ON_DEVICE_TEMPERATURE)
        .roundToDecimals(2)
  }
}

internal fun llmEndpointAllowsBlankApiKey(
  protocol: String,
  baseUrl: String,
): Boolean {
  val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
  if (
    normalizedProtocol != LlmProviderProtocols.OPENAI &&
    normalizedProtocol != LlmProviderProtocols.OPENAI_RESPONSES
  ) {
    return false
  }
  return isLikelyLocalLlmBaseUrl(baseUrl)
}

internal fun isLikelyLocalLlmBaseUrl(baseUrl: String): Boolean {
  val host = runCatching {
    URI(baseUrl.trim()).host
      .orEmpty()
      .trim()
      .removePrefix("[")
      .removeSuffix("]")
      .lowercase()
  }.getOrDefault("")
  if (host.isBlank()) {
    return false
  }
  if (
    host == "localhost" ||
    host == "localhost.localdomain" ||
    host == "0.0.0.0" ||
    host == "::1" ||
    host == "10.0.2.2" ||
    host == "host.docker.internal" ||
    host.endsWith(".local")
  ) {
    return true
  }
  return when {
    host.startsWith("127.") -> true
    host.startsWith("10.") -> true
    host.startsWith("192.168.") -> true
    host.isPrivate172SubnetHost() -> true
    else -> false
  }
}

private fun String.isPrivate172SubnetHost(): Boolean {
  if (!startsWith("172.")) {
    return false
  }
  val secondOctet = split('.').getOrNull(1)?.toIntOrNull() ?: return false
  return secondOctet in 16..31
}

private fun Double.roundToDecimals(decimals: Int): Double {
  val factor = 10.0.pow(decimals.toDouble())
  return (this * factor).roundToInt() / factor
}

internal data class SavedCustomLlmProvider(
  val id: String,
  val protocol: String,
  val providerName: String,
  val providerNotes: String,
  val baseUrl: String,
  val apiKey: String,
  val model: String,
) {
  fun sanitized(): SavedCustomLlmProvider = copy(
    id = id.trim(),
    protocol = LlmProviderProtocols.normalize(protocol),
    providerName = providerName.trim(),
    providerNotes = providerNotes.trim(),
    baseUrl = baseUrl.trim(),
    apiKey = apiKey.trim(),
    model = model.trim(),
  )

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("protocol", protocol)
    .put("providerName", providerName)
    .put("providerNotes", providerNotes)
    .put("baseUrl", baseUrl)
    .put("apiKey", apiKey)
    .put("model", model)

  companion object {
    fun create(
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      existingId: String? = null,
    ): SavedCustomLlmProvider = SavedCustomLlmProvider(
      id = existingId?.trim().orEmpty().ifBlank { "saved-custom-${UUID.randomUUID()}" },
      protocol = protocol,
      providerName = providerName,
      providerNotes = providerNotes,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
    ).sanitized()

    fun fromJson(payload: JSONObject): SavedCustomLlmProvider? {
      val provider = SavedCustomLlmProvider(
        id = payload.optString("id"),
        protocol = payload.optString("protocol", LlmProviderProtocols.OPENAI),
        providerName = payload.optString("providerName"),
        providerNotes = payload.optString("providerNotes"),
        baseUrl = payload.optString("baseUrl"),
        apiKey = payload.optString("apiKey"),
        model = payload.optString("model"),
      ).sanitized()
      return provider.takeIf { it.id.isNotBlank() }
    }
  }
}

internal interface LlmSettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(key: String, value: Boolean)

  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun clear()

  fun loadState(defaults: LlmSettingsState = LlmSettingsState()): LlmSettingsState =
    defaults.copy(
      enabled = getBoolean(LlmSettingsStoreKeys.ENABLED) ?: defaults.enabled,
      streamingEnabled =
        getBoolean(LlmSettingsStoreKeys.STREAMING_ENABLED)
          ?: defaults.streamingEnabled,
      providerMode =
        getString(LlmSettingsStoreKeys.PROVIDER_MODE) ?: defaults.providerMode,
      providerId = getString(LlmSettingsStoreKeys.PROVIDER_ID)
        ?: LlmSettingsState.inferProviderId(
          getString(LlmSettingsStoreKeys.BASE_URL) ?: defaults.baseUrl,
        ),
      protocol = getString(LlmSettingsStoreKeys.PROTOCOL) ?: defaults.protocol,
      providerName = getString(LlmSettingsStoreKeys.PROVIDER_NAME) ?: defaults.providerName,
      providerNotes = getString(LlmSettingsStoreKeys.PROVIDER_NOTES) ?: defaults.providerNotes,
      baseUrl = getString(LlmSettingsStoreKeys.BASE_URL) ?: defaults.baseUrl,
      apiKey = getString(LlmSettingsStoreKeys.API_KEY) ?: defaults.apiKey,
      model = getString(LlmSettingsStoreKeys.MODEL) ?: defaults.model,
      reasoningEffort = getString(LlmSettingsStoreKeys.REASONING_EFFORT) ?: defaults.reasoningEffort,
      systemPrompt = getString(LlmSettingsStoreKeys.SYSTEM_PROMPT) ?: defaults.systemPrompt,
      openAiPromptCacheKeyStrategy =
        getString(LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_KEY_STRATEGY)
          ?: defaults.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention =
        getString(LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_RETENTION)
          ?: defaults.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled =
        getBoolean(LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHING_ENABLED)
          ?: defaults.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl =
        getString(LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHE_TTL)
          ?: defaults.anthropicPromptCacheTtl,
      contextBudgetPreset =
        getString(LlmSettingsStoreKeys.CONTEXT_BUDGET_PRESET)
          ?: defaults.contextBudgetPreset,
      contextBudgetReservedOutputTokens = optionalIntValueFromStore(
        keyValueStore = this,
        key = LlmSettingsStoreKeys.CONTEXT_BUDGET_RESERVED_OUTPUT_TOKENS,
        defaultValue = defaults.contextBudgetReservedOutputTokens,
      ),
      contextBudgetSafetyMarginTokens = optionalIntValueFromStore(
        keyValueStore = this,
        key = LlmSettingsStoreKeys.CONTEXT_BUDGET_SAFETY_MARGIN_TOKENS,
        defaultValue = defaults.contextBudgetSafetyMarginTokens,
      ),
      contextBudgetEffectiveInputPercent = optionalDoubleValueFromStore(
        keyValueStore = this,
        key = LlmSettingsStoreKeys.CONTEXT_BUDGET_EFFECTIVE_INPUT_PERCENT,
        defaultValue = defaults.contextBudgetEffectiveInputPercent,
      ),
      selectedOnDeviceModelId =
        getString(LlmSettingsStoreKeys.SELECTED_ON_DEVICE_MODEL_ID)
          ?: defaults.selectedOnDeviceModelId,
      onDeviceMaxContextWindow =
        getString(LlmSettingsStoreKeys.ON_DEVICE_MAX_CONTEXT_WINDOW)
          ?.toIntOrNull()
          ?: defaults.onDeviceMaxContextWindow,
      onDeviceMaxTokens =
        getString(LlmSettingsStoreKeys.ON_DEVICE_MAX_TOKENS)
          ?.toIntOrNull()
          ?: defaults.onDeviceMaxTokens,
      onDeviceTopK =
        getString(LlmSettingsStoreKeys.ON_DEVICE_TOP_K)
          ?.toIntOrNull()
          ?: defaults.onDeviceTopK,
      onDeviceTopP =
        getString(LlmSettingsStoreKeys.ON_DEVICE_TOP_P)
          ?.toDoubleOrNull()
          ?: defaults.onDeviceTopP,
      onDeviceTemperature =
        getString(LlmSettingsStoreKeys.ON_DEVICE_TEMPERATURE)
          ?.toDoubleOrNull()
          ?: defaults.onDeviceTemperature,
      onDeviceAccelerator =
        getString(LlmSettingsStoreKeys.ON_DEVICE_ACCELERATOR)
          ?: defaults.onDeviceAccelerator,
      onDeviceThinkingEnabled =
        getBoolean(LlmSettingsStoreKeys.ON_DEVICE_THINKING_ENABLED)
          ?: defaults.onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled =
        getBoolean(LlmSettingsStoreKeys.ON_DEVICE_LITE_MODE_ENABLED)
          ?: defaults.onDeviceLiteModeEnabled,
    ).sanitized()

  fun saveState(
    state: LlmSettingsState,
    selectedProviderOptionId: String,
  ) {
    val values = valuesForLlmState(
      state = state,
      selectedProviderOptionId = selectedProviderOptionId,
    )
    values.forEach { (key, value) ->
      if (key in LLM_BOOLEAN_SETTING_KEYS) {
        putBoolean(key, value.toBooleanStrictOrNull() ?: false)
      } else {
        putString(key, value)
      }
    }
  }
}

@Serializable
private data class PersistedLlmSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedLlmSettingsRecord = copy(
    values = values.filterKeys(LLM_SETTING_KEYS::contains),
  )

  fun toState(defaults: LlmSettingsState): LlmSettingsState {
    val legacy = PersistedLlmSettingsKeyValueStore(values)
    return legacy.loadState(defaults)
  }
}

private class PersistedLlmSettingsKeyValueStore(
  private val values: Map<String, String>,
) : LlmSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) = Unit

  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) = Unit

  override fun clear() = Unit
}

private fun optionalIntValueFromStore(
  keyValueStore: LlmSettingsKeyValueStore,
  key: String,
  defaultValue: Int?,
): Int? = when (val rawValue = keyValueStore.getString(key)) {
  null -> defaultValue
  else -> rawValue.trim().takeIf(String::isNotBlank)?.toIntOrNull()
}

private fun optionalDoubleValueFromStore(
  keyValueStore: LlmSettingsKeyValueStore,
  key: String,
  defaultValue: Double?,
): Double? = when (val rawValue = keyValueStore.getString(key)) {
  null -> defaultValue
  else -> rawValue.trim().takeIf(String::isNotBlank)?.toDoubleOrNull()
}

private fun valuesForLlmState(
  state: LlmSettingsState,
  selectedProviderOptionId: String,
): Map<String, String> {
  val resolved = state.sanitized()
  val sanitized = resolved.copy(enabled = resolved.isConfigured())
  return linkedMapOf(
    LlmSettingsStoreKeys.ENABLED to sanitized.enabled.toString(),
    LlmSettingsStoreKeys.STREAMING_ENABLED to sanitized.streamingEnabled.toString(),
    LlmSettingsStoreKeys.PROVIDER_MODE to sanitized.providerMode,
    LlmSettingsStoreKeys.PROVIDER_ID to sanitized.providerId,
    LlmSettingsStoreKeys.SELECTED_PROVIDER_OPTION_ID to
      selectedProviderOptionId.trim().ifBlank { sanitized.providerId },
    LlmSettingsStoreKeys.PROTOCOL to sanitized.protocol,
    LlmSettingsStoreKeys.PROVIDER_NAME to sanitized.providerName,
    LlmSettingsStoreKeys.PROVIDER_NOTES to sanitized.providerNotes,
    LlmSettingsStoreKeys.BASE_URL to sanitized.baseUrl,
    LlmSettingsStoreKeys.API_KEY to sanitized.apiKey,
    LlmSettingsStoreKeys.MODEL to sanitized.model,
    LlmSettingsStoreKeys.REASONING_EFFORT to sanitized.reasoningEffort,
    LlmSettingsStoreKeys.SYSTEM_PROMPT to sanitized.systemPrompt,
    LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_KEY_STRATEGY to
      sanitized.openAiPromptCacheKeyStrategy,
    LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_RETENTION to
      sanitized.openAiPromptCacheRetention,
    LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHING_ENABLED to
      sanitized.anthropicPromptCachingEnabled.toString(),
    LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHE_TTL to
      sanitized.anthropicPromptCacheTtl,
    LlmSettingsStoreKeys.CONTEXT_BUDGET_PRESET to sanitized.contextBudgetPreset,
    LlmSettingsStoreKeys.CONTEXT_BUDGET_RESERVED_OUTPUT_TOKENS to
      sanitized.contextBudgetReservedOutputTokens?.toString().orEmpty(),
    LlmSettingsStoreKeys.CONTEXT_BUDGET_SAFETY_MARGIN_TOKENS to
      sanitized.contextBudgetSafetyMarginTokens?.toString().orEmpty(),
    LlmSettingsStoreKeys.CONTEXT_BUDGET_EFFECTIVE_INPUT_PERCENT to
      sanitized.contextBudgetEffectiveInputPercent?.toString().orEmpty(),
    LlmSettingsStoreKeys.SELECTED_ON_DEVICE_MODEL_ID to sanitized.selectedOnDeviceModelId,
    LlmSettingsStoreKeys.ON_DEVICE_MAX_CONTEXT_WINDOW to
      sanitized.onDeviceMaxContextWindow.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_MAX_TOKENS to sanitized.onDeviceMaxTokens.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_TOP_K to sanitized.onDeviceTopK.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_TOP_P to sanitized.onDeviceTopP.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_TEMPERATURE to sanitized.onDeviceTemperature.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_ACCELERATOR to sanitized.onDeviceAccelerator,
    LlmSettingsStoreKeys.ON_DEVICE_THINKING_ENABLED to sanitized.onDeviceThinkingEnabled.toString(),
    LlmSettingsStoreKeys.ON_DEVICE_LITE_MODE_ENABLED to
      sanitized.onDeviceLiteModeEnabled.toString(),
  )
}

private fun valuesFromLlmStore(
  store: LlmSettingsKeyValueStore,
): Map<String, String> = buildMap {
  LLM_BOOLEAN_SETTING_KEYS.forEach { key ->
    store.getBoolean(key)?.let { value ->
      put(key, value.toString())
    }
  }
  LLM_STRING_SETTING_KEYS.forEach { key ->
    store.getString(key)?.let { value ->
      put(key, value)
    }
  }
}

private val LLM_BOOLEAN_SETTING_KEYS: Set<String> = setOf(
  LlmSettingsStoreKeys.ENABLED,
  LlmSettingsStoreKeys.STREAMING_ENABLED,
  LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHING_ENABLED,
  LlmSettingsStoreKeys.ON_DEVICE_THINKING_ENABLED,
  LlmSettingsStoreKeys.ON_DEVICE_LITE_MODE_ENABLED,
)

private val LLM_STRING_SETTING_KEYS: Set<String> = setOf(
  LlmSettingsStoreKeys.PROVIDER_MODE,
  LlmSettingsStoreKeys.PROVIDER_ID,
  LlmSettingsStoreKeys.SELECTED_PROVIDER_OPTION_ID,
  LlmSettingsStoreKeys.PROTOCOL,
  LlmSettingsStoreKeys.PROVIDER_NAME,
  LlmSettingsStoreKeys.PROVIDER_NOTES,
  LlmSettingsStoreKeys.BASE_URL,
  LlmSettingsStoreKeys.API_KEY,
  LlmSettingsStoreKeys.MODEL,
  LlmSettingsStoreKeys.REASONING_EFFORT,
  LlmSettingsStoreKeys.SYSTEM_PROMPT,
  LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_KEY_STRATEGY,
  LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_RETENTION,
  LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHE_TTL,
  LlmSettingsStoreKeys.CONTEXT_BUDGET_PRESET,
  LlmSettingsStoreKeys.CONTEXT_BUDGET_RESERVED_OUTPUT_TOKENS,
  LlmSettingsStoreKeys.CONTEXT_BUDGET_SAFETY_MARGIN_TOKENS,
  LlmSettingsStoreKeys.CONTEXT_BUDGET_EFFECTIVE_INPUT_PERCENT,
  LlmSettingsStoreKeys.SELECTED_ON_DEVICE_MODEL_ID,
  LlmSettingsStoreKeys.ON_DEVICE_MAX_CONTEXT_WINDOW,
  LlmSettingsStoreKeys.ON_DEVICE_MAX_TOKENS,
  LlmSettingsStoreKeys.ON_DEVICE_TOP_K,
  LlmSettingsStoreKeys.ON_DEVICE_TOP_P,
  LlmSettingsStoreKeys.ON_DEVICE_TEMPERATURE,
  LlmSettingsStoreKeys.ON_DEVICE_ACCELERATOR,
  LlmSettingsStoreKeys.SAVED_CUSTOM_PROVIDERS,
  LlmSettingsStoreKeys.AGENT_CAPABILITY_CACHE,
)

private val LLM_SETTING_KEYS: Set<String> =
  LLM_BOOLEAN_SETTING_KEYS + LLM_STRING_SETTING_KEYS

internal class InMemoryLlmSettingsKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : LlmSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) {
    values[key] = value.toString()
  }

  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesLlmSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : LlmSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(key: String, value: Boolean) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }

  fun hasAnyPersistedSetting(): Boolean =
    LLM_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedLlmSettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : LlmSettingsKeyValueStore {
  private val lock = Any()

  override fun getBoolean(key: String): Boolean? =
    getString(key)?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) {
    putString(key, value.toString())
  }

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(key: String, value: String) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun loadState(defaults: LlmSettingsState): LlmSettingsState =
    synchronized(lock) {
      loadRecord().toState(defaults)
    }

  override fun saveState(
    state: LlmSettingsState,
    selectedProviderOptionId: String,
  ) {
    synchronized(lock) {
      val values = valuesForLlmState(
        state = state,
        selectedProviderOptionId = selectedProviderOptionId,
      )
      updateValues { existingValues ->
        existingValues + values
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(LLM_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: LlmSettingsKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyValues = valuesFromLlmStore(legacyStore)
      if (legacyValues.isEmpty()) {
        return
      }
      updateValues { values ->
        values + legacyValues
      }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(LLM_SETTINGS_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedLlmSettingsRecord =
    storage.updateRecord(
      name = LLM_SETTINGS_FILE_NAME,
      serializer = PersistedLlmSettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedLlmSettingsRecord()
      val repaired = existing.normalized()
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = persisted != null && repaired != existing,
      )
    }

  private fun updateValues(
    update: (Map<String, String>) -> Map<String, String>,
  ) {
    val now = clock()
    storage.updateRecord(
      name = LLM_SETTINGS_FILE_NAME,
      serializer = PersistedLlmSettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedLlmSettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(LLM_SETTING_KEYS::contains)
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = now,
          values = updatedValues,
        ),
        result = Unit,
      )
    }
  }
}

internal class LlmSettingsStore(
  private val keyValueStore: LlmSettingsKeyValueStore,
) {
  fun load(defaults: LlmSettingsState = LlmSettingsState()): LlmSettingsState {
    val resolved = keyValueStore.loadState(defaults).sanitized()
    return resolved.copy(
      enabled = resolved.isConfigured(),
      agentCapability = if (resolved.isOnDeviceProviderMode()) {
        resolved.agentCapability
      } else {
        loadAgentCapability(
          protocol = resolved.protocol,
          baseUrl = resolved.baseUrl,
          model = resolved.model,
        )
      },
    ).sanitized()
  }

  fun save(state: LlmSettingsState) {
    save(state = state, selectedProviderOptionId = state.providerId)
  }

  fun save(
    state: LlmSettingsState,
    selectedProviderOptionId: String,
  ) {
    val resolved = state.sanitized()
    val sanitized = resolved.copy(enabled = resolved.isConfigured())
    keyValueStore.saveState(
      state = sanitized,
      selectedProviderOptionId = selectedProviderOptionId,
    )
    if (sanitized.agentCapability.wasVerified) {
      saveAgentCapability(sanitized.agentCapability)
    }
  }

  fun loadSelectedProviderOptionId(defaultProviderId: String): String =
    keyValueStore.getString(LlmSettingsStoreKeys.SELECTED_PROVIDER_OPTION_ID)
      ?.trim()
      .orEmpty()
      .ifBlank { defaultProviderId }

  fun loadSavedCustomProviders(): List<SavedCustomLlmProvider> {
    val rawPayload = keyValueStore.getString(LlmSettingsStoreKeys.SAVED_CUSTOM_PROVIDERS).orEmpty()
    if (rawPayload.isBlank()) {
      return emptyList()
    }
    val providers = runCatching { JSONArray(rawPayload) }
      .getOrElse { return emptyList() }
    return buildList {
      repeat(providers.length()) { index ->
        val provider = providers.optJSONObject(index)
          ?.let(SavedCustomLlmProvider::fromJson)
        if (provider != null) {
          add(provider)
        }
      }
    }
  }

  fun saveSavedCustomProviders(providers: List<SavedCustomLlmProvider>) {
    val normalized = JSONArray().apply {
      providers
        .map(SavedCustomLlmProvider::sanitized)
        .filter { provider -> provider.id.isNotBlank() }
        .forEach { provider -> put(provider.toJson()) }
    }
    keyValueStore.putString(LlmSettingsStoreKeys.SAVED_CUSTOM_PROVIDERS, normalized.toString())
  }

  fun loadAgentCapability(
    protocol: String,
    baseUrl: String,
    model: String,
  ): LlmAgentCapabilitySnapshot {
    val normalized = LlmAgentCapabilitySnapshot.unknown(
      protocol = protocol,
      baseUrl = baseUrl,
      model = model,
    )
    return loadAgentCapabilityCache()
      .firstOrNull { capability ->
        capability.matchesRoute(
          protocol = protocol,
          baseUrl = baseUrl,
          model = model,
        )
      }
      ?.normalizedForRoute(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
      )
      ?: normalized
  }

  fun saveAgentCapability(capability: LlmAgentCapabilitySnapshot) {
    val normalized = capability.takeIf { snapshot ->
      snapshot.routeFingerprint.isNotBlank() && snapshot.wasVerified
    } ?: return
    val updatedCache = buildList {
      add(normalized)
      loadAgentCapabilityCache()
        .filterNot { existing -> existing.routeFingerprint == normalized.routeFingerprint }
        .forEach(::add)
    }.take(MAX_AGENT_CAPABILITY_CACHE_ENTRIES)
    saveAgentCapabilityCache(updatedCache)
  }

  fun clear() {
    keyValueStore.clear()
  }

  private fun loadAgentCapabilityCache(): List<LlmAgentCapabilitySnapshot> {
    val rawPayload = keyValueStore.getString(LlmSettingsStoreKeys.AGENT_CAPABILITY_CACHE).orEmpty()
    if (rawPayload.isBlank()) {
      return emptyList()
    }
    val payload = runCatching { JSONArray(rawPayload) }
      .getOrElse { return emptyList() }
    return buildList {
      repeat(payload.length()) { index ->
        val snapshot = payload.optJSONObject(index)
          ?.let(LlmAgentCapabilitySnapshot::fromJson)
        if (snapshot != null) {
          add(snapshot)
        }
      }
    }
  }

  private fun saveAgentCapabilityCache(entries: List<LlmAgentCapabilitySnapshot>) {
    val normalized = JSONArray().apply {
      entries
        .filter { entry -> entry.routeFingerprint.isNotBlank() && entry.wasVerified }
        .take(MAX_AGENT_CAPABILITY_CACHE_ENTRIES)
        .forEach { entry -> put(entry.toJson()) }
    }
    keyValueStore.putString(LlmSettingsStoreKeys.AGENT_CAPABILITY_CACHE, normalized.toString())
  }

  companion object {
    private const val MAX_AGENT_CAPABILITY_CACHE_ENTRIES: Int = 8

    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_LLM_SETTINGS_PREFERENCES,
    ): LlmSettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedLlmSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesLlmSettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return LlmSettingsStore(keyValueStore = fileBackedStore)
    }
  }
}
