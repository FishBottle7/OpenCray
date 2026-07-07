package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable

private const val DEFAULT_TELEMETRY_SETTINGS_PREFERENCES = "opencray.telemetry-settings"
private const val TELEMETRY_SETTINGS_FILE_NAME = "telemetry-settings.json"

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

  fun hasAnyPersistedSetting(): Boolean =
    TELEMETRY_SETTINGS_KEYS.any(sharedPreferences::contains)
}

class FileBackedTelemetrySettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : TelemetrySettingsKeyValueStore {
  private val lock = Any()

  override fun getBoolean(key: String): Boolean? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putBoolean(
    key: String,
    value: Boolean,
  ) {
    synchronized(lock) {
      updateValues { values -> values + (key to value) }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(TELEMETRY_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: TelemetrySettingsKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyValues = TELEMETRY_SETTINGS_KEYS.mapNotNull { key ->
        legacyStore.getBoolean(key)?.let { value -> key to value }
      }.toMap()
      if (legacyValues.isEmpty()) {
        return
      }
      updateValues { values -> values + legacyValues }
    }
  }

  private fun hasPersistedRecord(): Boolean = storage.updateRecord(
    name = TELEMETRY_SETTINGS_FILE_NAME,
    serializer = PersistedTelemetrySettingsRecord.serializer(),
  ) { persisted ->
    RecordStorageUpdate(
      value = persisted,
      result = persisted != null,
      write = false,
    )
  }

  private fun loadRecord(): PersistedTelemetrySettingsRecord =
    storage.updateRecord(
      name = TELEMETRY_SETTINGS_FILE_NAME,
      serializer = PersistedTelemetrySettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedTelemetrySettingsRecord()
      val repaired = existing.normalized()
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = persisted != null && repaired != existing,
      )
    }

  private fun updateValues(
    update: (Map<String, Boolean>) -> Map<String, Boolean>,
  ) {
    val now = clock()
    storage.updateRecord(
      name = TELEMETRY_SETTINGS_FILE_NAME,
      serializer = PersistedTelemetrySettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedTelemetrySettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(TELEMETRY_SETTINGS_KEYS::contains)
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
    ): TelemetrySettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedTelemetrySettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesTelemetrySettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return TelemetrySettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedTelemetrySettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, Boolean> = emptyMap(),
) {
  fun normalized(): PersistedTelemetrySettingsRecord = copy(
    values = values.filterKeys(TELEMETRY_SETTINGS_KEYS::contains),
  )
}

private val TELEMETRY_SETTINGS_KEYS: Set<String> = setOf(
  TelemetrySettingsStoreKeys.TELEMETRY_ENABLED,
  TelemetrySettingsStoreKeys.PRIVACY_GUARD_ENABLED,
)
