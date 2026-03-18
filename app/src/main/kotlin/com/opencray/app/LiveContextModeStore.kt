package com.opencray.app

import android.content.Context
import android.content.SharedPreferences

private const val DEFAULT_LIVE_CONTEXT_MODE_PREFERENCES = "opencray.live-context-mode"

internal object LiveContextModeStoreKeys {
  const val MODE_ID = "mode_id"
}

internal interface LiveContextModeKeyValueStore {
  fun getString(key: String): String?

  fun putString(
    key: String,
    value: String,
  )

  fun clear()
}

internal class InMemoryLiveContextModeKeyValueStore(
  initialValues: Map<String, String> = emptyMap(),
) : LiveContextModeKeyValueStore {
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

internal class SharedPreferencesLiveContextModeKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : LiveContextModeKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

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

internal class LiveContextModeStore(
  private val keyValueStore: LiveContextModeKeyValueStore,
) {
  fun load(defaultMode: LiveContextMode = LiveContextMode.FULL): LiveContextMode =
    LiveContextMode.fromWireValue(
      keyValueStore.getString(LiveContextModeStoreKeys.MODE_ID) ?: defaultMode.wireValue,
    )

  fun save(mode: LiveContextMode) {
    keyValueStore.putString(
      LiveContextModeStoreKeys.MODE_ID,
      mode.wireValue,
    )
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_LIVE_CONTEXT_MODE_PREFERENCES,
    ): LiveContextModeStore = LiveContextModeStore(
      keyValueStore = SharedPreferencesLiveContextModeKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
