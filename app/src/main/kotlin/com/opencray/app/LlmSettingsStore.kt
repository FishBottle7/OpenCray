package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_LLM_SETTINGS_PREFERENCES = "opencray.llm-settings"

internal object LlmSettingsStoreKeys {
  const val ENABLED = "enabled"
  const val PROVIDER_ID = "provider_id"
  const val PROTOCOL = "protocol"
  const val PROVIDER_NAME = "provider_name"
  const val PROVIDER_NOTES = "provider_notes"
  const val BASE_URL = "base_url"
  const val API_KEY = "api_key"
  const val MODEL = "model"
  const val REASONING_EFFORT = "reasoning_effort"
  const val SYSTEM_PROMPT = "system_prompt"
}

internal data class LlmSettingsState(
  val enabled: Boolean = false,
  val providerId: String = DEFAULT_PROVIDER_ID,
  val protocol: String = DEFAULT_PROTOCOL,
  val providerName: String = DEFAULT_PROVIDER_NAME,
  val providerNotes: String = "",
  val baseUrl: String = DEFAULT_BASE_URL,
  val apiKey: String = "",
  val model: String = DEFAULT_MODEL,
  val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
  val systemPrompt: String = "",
) {
  fun isConfigured(): Boolean =
    baseUrl.trim().isNotEmpty() &&
      apiKey.trim().isNotEmpty()

  fun sanitized(): LlmSettingsState = copy(
    providerId = providerId.trim().ifBlank { inferProviderId(baseUrl) },
    protocol = LlmProviderProtocols.normalize(protocol),
    providerName = providerName.trim(),
    providerNotes = providerNotes.trim(),
    baseUrl = baseUrl.trim(),
    apiKey = apiKey.trim(),
    model = model.trim(),
    reasoningEffort = reasoningEffort.trim().ifBlank { DEFAULT_REASONING_EFFORT },
    systemPrompt = systemPrompt.trim(),
  )

  companion object {
    const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
    const val DEFAULT_MODEL: String = "gpt-4o-mini"
    const val DEFAULT_PROVIDER_ID: String = "openai"
    const val DEFAULT_PROTOCOL: String = LlmProviderProtocols.OPENAI
    const val DEFAULT_PROVIDER_NAME: String = "OpenAI"
    const val DEFAULT_REASONING_EFFORT: String = "medium"

    fun inferProviderId(baseUrl: String): String =
      LlmProviderCatalog.inferPresetId(baseUrl)
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
    ).sanitized()
    return resolved.copy(enabled = resolved.isConfigured())
  }

  fun save(state: LlmSettingsState) {
    val resolved = state.sanitized()
    val sanitized = resolved.copy(enabled = resolved.isConfigured())
    keyValueStore.putBoolean(LlmSettingsStoreKeys.ENABLED, sanitized.enabled)
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_ID, sanitized.providerId)
    keyValueStore.putString(LlmSettingsStoreKeys.PROTOCOL, sanitized.protocol)
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_NAME, sanitized.providerName)
    keyValueStore.putString(LlmSettingsStoreKeys.PROVIDER_NOTES, sanitized.providerNotes)
    keyValueStore.putString(LlmSettingsStoreKeys.BASE_URL, sanitized.baseUrl)
    keyValueStore.putString(LlmSettingsStoreKeys.API_KEY, sanitized.apiKey)
    keyValueStore.putString(LlmSettingsStoreKeys.MODEL, sanitized.model)
    keyValueStore.putString(LlmSettingsStoreKeys.REASONING_EFFORT, sanitized.reasoningEffort)
    keyValueStore.putString(LlmSettingsStoreKeys.SYSTEM_PROMPT, sanitized.systemPrompt)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
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
