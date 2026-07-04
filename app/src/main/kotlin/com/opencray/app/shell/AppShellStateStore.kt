package com.opencray.app.shell

import android.content.Context
import android.content.SharedPreferences
import com.opencray.app.FileBackedAgentQueueSnapshotStoreFactory
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable

private const val DEFAULT_APP_SHELL_PREFERENCES = "opencray.app-shell"
private const val APP_SHELL_STATE_FILE_NAME = "app-shell-state.json"

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
    APP_SHELL_STATE_KEYS.any(sharedPreferences::contains)
}

class FileBackedAppShellKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : AppShellKeyValueStore {
  private val lock = Any()

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(
    key: String,
    value: String,
  ) {
    synchronized(lock) {
      updateValues { values -> values + (key to value) }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(APP_SHELL_STATE_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: AppShellKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyValues = APP_SHELL_STATE_KEYS.mapNotNull { key ->
        legacyStore.getString(key)
          ?.let { value -> key to value }
      }.toMap()
      if (legacyValues.isEmpty()) {
        return
      }
      updateValues { values -> values + legacyValues }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(APP_SHELL_STATE_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedAppShellStateRecord =
    storage.updateRecord(
      name = APP_SHELL_STATE_FILE_NAME,
      serializer = PersistedAppShellStateRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedAppShellStateRecord()
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
      name = APP_SHELL_STATE_FILE_NAME,
      serializer = PersistedAppShellStateRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedAppShellStateRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(APP_SHELL_STATE_KEYS::contains)
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
    ): AppShellStateStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedAppShellKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesAppShellKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return AppShellStateStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedAppShellStateRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedAppShellStateRecord = copy(
    values = values.filterKeys(APP_SHELL_STATE_KEYS::contains),
  )
}

private val APP_SHELL_STATE_KEYS: Set<String> = setOf(
  AppShellStateStoreKeys.SELECTED_TAB,
  AppShellStateStoreKeys.SETTINGS_SUBPAGE,
)
