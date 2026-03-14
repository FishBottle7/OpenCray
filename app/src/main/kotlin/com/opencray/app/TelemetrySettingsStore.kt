package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_TELEMETRY_SETTINGS_PREFERENCES = "opencray.telemetry-settings"

internal object TelemetrySettingsStoreKeys {
  const val TELEMETRY_ENABLED = "telemetry_enabled"
  const val PRIVACY_GUARD_ENABLED = "privacy_guard_enabled"
}

interface TelemetrySettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(
    key: String,
    value: Boolean,
  )

  fun clear()
}

class InMemoryTelemetrySettingsKeyValueStore(
  initialValues: Map<String, Boolean> = emptyMap(),
) : TelemetrySettingsKeyValueStore {
  private val values = linkedMapOf<String, Boolean>().apply {
    putAll(initialValues)
  }

  override fun getBoolean(key: String): Boolean? = values[key]

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

class SharedPreferencesTelemetrySettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : TelemetrySettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }
}

class TelemetrySettingsStore(
  private val keyValueStore: TelemetrySettingsKeyValueStore,
) {
  fun load(defaults: TelemetryTogglesState): TelemetryTogglesState = defaults.copy(
    telemetry = defaults.telemetry.copy(
      isChecked =
        keyValueStore.getBoolean(TelemetrySettingsStoreKeys.TELEMETRY_ENABLED)
          ?: defaults.telemetry.defaultValue,
    ),
    privacyGuard = defaults.privacyGuard.copy(
      isChecked =
        keyValueStore.getBoolean(TelemetrySettingsStoreKeys.PRIVACY_GUARD_ENABLED)
          ?: defaults.privacyGuard.defaultValue,
    ),
  )

  fun save(state: TelemetryTogglesState) {
    keyValueStore.putBoolean(
      TelemetrySettingsStoreKeys.TELEMETRY_ENABLED,
      state.telemetry.isChecked,
    )
    keyValueStore.putBoolean(
      TelemetrySettingsStoreKeys.PRIVACY_GUARD_ENABLED,
      state.privacyGuard.isChecked,
    )
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_TELEMETRY_SETTINGS_PREFERENCES,
    ): TelemetrySettingsStore = TelemetrySettingsStore(
      keyValueStore = SharedPreferencesTelemetrySettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
