package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_LLM_SETTINGS_PREFERENCES = "opencray.llm-settings"

internal object LlmSettingsStoreKeys {
  const val ENABLED = "enabled"
  const val STREAMING_ENABLED = "streaming_enabled"
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
  const val SAVED_CUSTOM_PROVIDERS = "saved_custom_providers"
  const val AGENT_CAPABILITY_CACHE = "agent_capability_cache"
}

internal data class LlmSettingsState(
  val enabled: Boolean = false,
  val streamingEnabled: Boolean = DEFAULT_STREAMING_ENABLED,
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
  val agentCapability: LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(),
) {
  fun isConfigured(): Boolean =
    baseUrl.trim().isNotEmpty() &&
      apiKey.trim().isNotEmpty()

  fun sanitized(): LlmSettingsState {
    val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
    val normalizedBaseUrl = baseUrl.trim()
    val normalizedModel = model.trim()
    return copy(
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
  }
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
}

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
}

internal class LlmSettingsStore(
  private val keyValueStore: LlmSettingsKeyValueStore,
) {
  fun load(defaults: LlmSettingsState = LlmSettingsState()): LlmSettingsState {
    val resolved = defaults.copy(
      enabled = keyValueStore.getBoolean(LlmSettingsStoreKeys.ENABLED) ?: defaults.enabled,
      streamingEnabled =
        keyValueStore.getBoolean(LlmSettingsStoreKeys.STREAMING_ENABLED)
          ?: defaults.streamingEnabled,
      providerId = keyValueStore.getString(LlmSettingsStoreKeys.PROVIDER_ID)
        ?: LlmSettingsState.inferProviderId(
          keyValueStore.getString(LlmSettingsStoreKeys.BASE_URL) ?: defaults.baseUrl,
        ),
      protocol = keyValueStore.getString(LlmSettingsStoreKeys.PROTOCOL) ?: defaults.protocol,
      providerName = keyValueStore.getString(LlmSettingsStoreKeys.PROVIDER_NAME) ?: defaults.providerName,
      providerNotes = keyValueStore.getString(LlmSettingsStoreKeys.PROVIDER_NOTES) ?: defaults.providerNotes,
      baseUrl = keyValueStore.getString(LlmSettingsStoreKeys.BASE_URL) ?: defaults.baseUrl,
      apiKey = keyValueStore.getString(LlmSettingsStoreKeys.API_KEY) ?: defaults.apiKey,
      model = keyValueStore.getString(LlmSettingsStoreKeys.MODEL) ?: defaults.model,
      reasoningEffort = keyValueStore.getString(LlmSettingsStoreKeys.REASONING_EFFORT) ?: defaults.reasoningEffort,
      systemPrompt = keyValueStore.getString(LlmSettingsStoreKeys.SYSTEM_PROMPT) ?: defaults.systemPrompt,
      openAiPromptCacheKeyStrategy =
        keyValueStore.getString(LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_KEY_STRATEGY)
          ?: defaults.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention =
        keyValueStore.getString(LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_RETENTION)
          ?: defaults.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled =
        keyValueStore.getBoolean(LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHING_ENABLED)
          ?: defaults.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl =
        keyValueStore.getString(LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHE_TTL)
          ?: defaults.anthropicPromptCacheTtl,
    ).sanitized()
    return resolved.copy(
      enabled = resolved.isConfigured(),
      agentCapability = loadAgentCapability(
        protocol = resolved.protocol,
        baseUrl = resolved.baseUrl,
        model = resolved.model,
      ),
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
    keyValueStore.putBoolean(LlmSettingsStoreKeys.ENABLED, sanitized.enabled)
    keyValueStore.putBoolean(
      LlmSettingsStoreKeys.STREAMING_ENABLED,
      sanitized.streamingEnabled,
    )
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_ID, sanitized.providerId)
    keyValueStore.putString(
      LlmSettingsStoreKeys.SELECTED_PROVIDER_OPTION_ID,
      selectedProviderOptionId.trim().ifBlank { sanitized.providerId },
    )
    keyValueStore.putString(LlmSettingsStoreKeys.PROTOCOL, sanitized.protocol)
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_NAME, sanitized.providerName)
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_NOTES, sanitized.providerNotes)
    keyValueStore.putString(LlmSettingsStoreKeys.BASE_URL, sanitized.baseUrl)
    keyValueStore.putString(LlmSettingsStoreKeys.API_KEY, sanitized.apiKey)
    keyValueStore.putString(LlmSettingsStoreKeys.MODEL, sanitized.model)
    keyValueStore.putString(LlmSettingsStoreKeys.REASONING_EFFORT, sanitized.reasoningEffort)
    keyValueStore.putString(LlmSettingsStoreKeys.SYSTEM_PROMPT, sanitized.systemPrompt)
    keyValueStore.putString(
      LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_KEY_STRATEGY,
      sanitized.openAiPromptCacheKeyStrategy,
    )
    keyValueStore.putString(
      LlmSettingsStoreKeys.OPENAI_PROMPT_CACHE_RETENTION,
      sanitized.openAiPromptCacheRetention,
    )
    keyValueStore.putBoolean(
      LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHING_ENABLED,
      sanitized.anthropicPromptCachingEnabled,
    )
    keyValueStore.putString(
      LlmSettingsStoreKeys.ANTHROPIC_PROMPT_CACHE_TTL,
      sanitized.anthropicPromptCacheTtl,
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
    ): LlmSettingsStore = LlmSettingsStore(
      keyValueStore = SharedPreferencesLlmSettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
