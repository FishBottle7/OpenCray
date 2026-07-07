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

private const val DEFAULT_LIVE_CONTEXT_MODE_PREFERENCES = "opencray.live-context-mode"
private const val LIVE_CONTEXT_MODE_FILE_NAME = "live-context-mode.json"

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

  fun hasAnyPersistedSetting(): Boolean =
    LIVE_CONTEXT_MODE_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedLiveContextModeKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : LiveContextModeKeyValueStore {
  private val lock = Any()

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(
    key: String,
    value: String,
  ) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(LIVE_CONTEXT_MODE_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: LiveContextModeKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyMode = legacyStore.getString(LiveContextModeStoreKeys.MODE_ID)
        ?: return
      updateValues { values ->
        values + (LiveContextModeStoreKeys.MODE_ID to legacyMode)
      }
    }
  }

  private fun hasPersistedRecord(): Boolean = storage.updateRecord(
    name = LIVE_CONTEXT_MODE_FILE_NAME,
    serializer = PersistedLiveContextModeRecord.serializer(),
  ) { persisted ->
    RecordStorageUpdate(
      value = persisted,
      result = persisted != null,
      write = false,
    )
  }

  private fun loadRecord(): PersistedLiveContextModeRecord =
    storage.updateRecord(
      name = LIVE_CONTEXT_MODE_FILE_NAME,
      serializer = PersistedLiveContextModeRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedLiveContextModeRecord()
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
      name = LIVE_CONTEXT_MODE_FILE_NAME,
      serializer = PersistedLiveContextModeRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedLiveContextModeRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(LIVE_CONTEXT_MODE_SETTING_KEYS::contains)
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

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_LIVE_CONTEXT_MODE_PREFERENCES,
    ): LiveContextModeStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedLiveContextModeKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesLiveContextModeKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return LiveContextModeStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedLiveContextModeRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedLiveContextModeRecord = copy(
    values = values.filterKeys(LIVE_CONTEXT_MODE_SETTING_KEYS::contains),
  )
}

private val LIVE_CONTEXT_MODE_SETTING_KEYS: Set<String> = setOf(
  LiveContextModeStoreKeys.MODE_ID,
)
