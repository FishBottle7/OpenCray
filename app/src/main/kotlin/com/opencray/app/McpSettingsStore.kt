package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_MCP_SETTINGS_PREFERENCES = "opencray.mcp-settings"

internal object McpSettingsStoreKeys {
  const val MASTER_ENABLED = "master_enabled"
}

internal interface McpSettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(key: String, value: Boolean)

  fun clear()
}

internal class InMemoryMcpSettingsKeyValueStore(
  private val values: LinkedHashMap<String, Boolean> = linkedMapOf(),
) : McpSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]

  override fun putBoolean(key: String, value: Boolean) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesMcpSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : McpSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(key: String, value: Boolean) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

internal class McpSettingsStore(
  private val keyValueStore: McpSettingsKeyValueStore,
) {
  fun loadMasterEnabled(defaultValue: Boolean = true): Boolean =
    keyValueStore.getBoolean(McpSettingsStoreKeys.MASTER_ENABLED) ?: defaultValue

  fun saveMasterEnabled(enabled: Boolean) {
    keyValueStore.putBoolean(McpSettingsStoreKeys.MASTER_ENABLED, enabled)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_MCP_SETTINGS_PREFERENCES,
    ): McpSettingsStore = McpSettingsStore(
      keyValueStore = SharedPreferencesMcpSettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
