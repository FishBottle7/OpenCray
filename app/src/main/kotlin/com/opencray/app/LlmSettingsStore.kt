package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_LLM_SETTINGS_PREFERENCES = "opencray.llm-settings"

internal object LlmSettingsStoreKeys {
  const val ENABLED = "enabled"
  const val BASE_URL = "base_url"
  const val API_KEY = "api_key"
  const val MODEL = "model"
  const val SYSTEM_PROMPT = "system_prompt"
}

internal data class LlmSettingsState(
  val enabled: Boolean = false,
  val baseUrl: String = DEFAULT_BASE_URL,
  val apiKey: String = "",
  val model: String = DEFAULT_MODEL,
  val systemPrompt: String = "",
) {
  fun isConfigured(): Boolean =
    enabled &&
      baseUrl.trim().isNotEmpty() &&
      apiKey.trim().isNotEmpty() &&
      model.trim().isNotEmpty()

  fun sanitized(): LlmSettingsState = copy(
    baseUrl = baseUrl.trim(),
    apiKey = apiKey.trim(),
    model = model.trim(),
    systemPrompt = systemPrompt.trim(),
  )

  companion object {
    const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"
    const val DEFAULT_MODEL: String = "gpt-4o-mini"
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
  fun load(defaults: LlmSettingsState = LlmSettingsState()): LlmSettingsState = defaults.copy(
    enabled = keyValueStore.getBoolean(LlmSettingsStoreKeys.ENABLED) ?: defaults.enabled,
    baseUrl = keyValueStore.getString(LlmSettingsStoreKeys.BASE_URL) ?: defaults.baseUrl,
    apiKey = keyValueStore.getString(LlmSettingsStoreKeys.API_KEY) ?: defaults.apiKey,
    model = keyValueStore.getString(LlmSettingsStoreKeys.MODEL) ?: defaults.model,
    systemPrompt = keyValueStore.getString(LlmSettingsStoreKeys.SYSTEM_PROMPT) ?: defaults.systemPrompt,
  ).sanitized()

  fun save(state: LlmSettingsState) {
    val sanitized = state.sanitized()
    keyValueStore.putBoolean(LlmSettingsStoreKeys.ENABLED, sanitized.enabled)
    keyValueStore.putString(LlmSettingsStoreKeys.BASE_URL, sanitized.baseUrl)
    keyValueStore.putString(LlmSettingsStoreKeys.API_KEY, sanitized.apiKey)
    keyValueStore.putString(LlmSettingsStoreKeys.MODEL, sanitized.model)
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
