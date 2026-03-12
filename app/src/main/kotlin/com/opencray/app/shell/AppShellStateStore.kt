package com.opencray.app.shell

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_APP_SHELL_PREFERENCES = "opencray.app-shell"

internal object AppShellStateStoreKeys {
  const val SELECTED_TAB = "selected_tab"
  const val SETTINGS_SUBPAGE = "settings_subpage"
}

interface AppShellKeyValueStore {
  fun getString(key: String): String?

  fun putString(
    key: String,
    value: String,
  )

  fun clear()
}

class InMemoryAppShellKeyValueStore(
  initialValues: Map<String, String> = emptyMap(),
) : AppShellKeyValueStore {
  private val values = linkedMapOf<String, String>().apply {
    putAll(initialValues)
  }

  override fun getString(key: String): String? = values[key]

  override fun putString(
    key: String,
    value: String,
  ) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

class SharedPreferencesAppShellKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : AppShellKeyValueStore {
  override fun getString(key: String): String? = sharedPreferences.getString(key, null)

  override fun putString(
    key: String,
    value: String,
  ) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

class AppShellStateStore(
  private val keyValueStore: AppShellKeyValueStore,
) {
  fun load(): AppShellDestination = AppShellDestination.fromRaw(
    selectedTabRaw = keyValueStore.getString(AppShellStateStoreKeys.SELECTED_TAB),
    settingsSubpageRaw = keyValueStore.getString(AppShellStateStoreKeys.SETTINGS_SUBPAGE),
  )

  fun save(destination: AppShellDestination) {
    keyValueStore.putString(AppShellStateStoreKeys.SELECTED_TAB, destination.selectedTab.name)
    keyValueStore.putString(AppShellStateStoreKeys.SETTINGS_SUBPAGE, destination.settingsSubpage.name)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_APP_SHELL_PREFERENCES,
    ): AppShellStateStore = AppShellStateStore(
      keyValueStore = SharedPreferencesAppShellKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
