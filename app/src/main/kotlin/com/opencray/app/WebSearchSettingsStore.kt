package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_WEB_SEARCH_SETTINGS_PREFERENCES = "opencray.web-search-settings"

internal object WebSearchSettingsStoreKeys {
  const val SLOTS = "slots"
}

internal enum class WebSearchProviderId(
  val wireValue: String,
) {
  EXA("exa"),
  TAVILY("tavily"),
  BRAVE("brave"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): WebSearchProviderId? =
      entries.firstOrNull { provider ->
        provider.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal data class WebSearchSlotConfig(
  val id: String,
  val providerId: String,
  val label: String,
  val apiKey: String,
  val enabled: Boolean,
) {
  fun sanitized(): WebSearchSlotConfig {
    val normalizedProvider = WebSearchProviderId.fromWireValue(providerId)?.wireValue
      ?: WebSearchProviderId.EXA.wireValue
    return copy(
      id = id.trim().ifBlank { "search-slot-${UUID.randomUUID()}" },
      providerId = normalizedProvider,
      label = label.trim(),
      apiKey = apiKey.trim(),
      enabled = enabled,
    )
  }

  companion object {
    fun create(
      id: String? = null,
      providerId: String = WebSearchProviderId.EXA.wireValue,
      label: String = "",
      apiKey: String = "",
      enabled: Boolean = true,
    ): WebSearchSlotConfig = WebSearchSlotConfig(
      id = id?.trim().orEmpty().ifBlank { "search-slot-${UUID.randomUUID()}" },
      providerId = providerId,
      label = label,
      apiKey = apiKey,
      enabled = enabled,
    ).sanitized()

    fun fromJson(payload: JSONObject): WebSearchSlotConfig? = WebSearchSlotConfig(
      id = payload.optString("id"),
      providerId = payload.optString("providerId", WebSearchProviderId.EXA.wireValue),
      label = payload.optString("label"),
      apiKey = payload.optString("apiKey"),
      enabled = payload.optBoolean("enabled", true),
    ).sanitized()
  }

  fun toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("providerId", providerId)
    .put("label", label)
    .put("apiKey", apiKey)
    .put("enabled", enabled)
}

internal interface WebSearchSettingsKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun clear()
}

internal class InMemoryWebSearchSettingsKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : WebSearchSettingsKeyValueStore {
  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesWebSearchSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : WebSearchSettingsKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

internal class WebSearchSettingsStore(
  private val keyValueStore: WebSearchSettingsKeyValueStore,
) {
  fun load(): List<WebSearchSlotConfig> {
    val rawPayload = keyValueStore.getString(WebSearchSettingsStoreKeys.SLOTS).orEmpty()
    if (rawPayload.isBlank()) {
      return emptyList()
    }
    val slots = runCatching { JSONArray(rawPayload) }
      .getOrElse { return emptyList() }
    return buildList {
      val seenIds = linkedSetOf<String>()
      repeat(slots.length()) { index ->
        val slot = slots.optJSONObject(index)
          ?.let(WebSearchSlotConfig::fromJson)
          ?: return@repeat
        if (seenIds.add(slot.id)) {
          add(slot)
        }
      }
    }
  }

  fun save(slots: List<WebSearchSlotConfig>) {
    val normalized = JSONArray().apply {
      val seenIds = linkedSetOf<String>()
      slots
        .map(WebSearchSlotConfig::sanitized)
        .forEach { slot ->
          if (seenIds.add(slot.id)) {
            put(slot.toJson())
          }
        }
    }
    keyValueStore.putString(WebSearchSettingsStoreKeys.SLOTS, normalized.toString())
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_WEB_SEARCH_SETTINGS_PREFERENCES,
    ): WebSearchSettingsStore = WebSearchSettingsStore(
      keyValueStore = SharedPreferencesWebSearchSettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
