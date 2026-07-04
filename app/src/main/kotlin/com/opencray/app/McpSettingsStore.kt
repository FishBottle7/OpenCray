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

private const val DEFAULT_MCP_SETTINGS_PREFERENCES = "opencray.mcp-settings"
private const val MCP_SETTINGS_FILE_NAME = "mcp-settings.json"

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

  fun hasAnyPersistedSetting(): Boolean =
    MCP_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedMcpSettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : McpSettingsKeyValueStore {
  private val lock = Any()

  override fun getBoolean(key: String): Boolean? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putBoolean(key: String, value: Boolean) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(MCP_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: McpSettingsKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val masterEnabled = legacyStore.getBoolean(McpSettingsStoreKeys.MASTER_ENABLED)
        ?: return
      updateValues { values ->
        values + (McpSettingsStoreKeys.MASTER_ENABLED to masterEnabled)
      }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(MCP_SETTINGS_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedMcpSettingsRecord =
    storage.updateRecord(
      name = MCP_SETTINGS_FILE_NAME,
      serializer = PersistedMcpSettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedMcpSettingsRecord()
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
      name = MCP_SETTINGS_FILE_NAME,
      serializer = PersistedMcpSettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedMcpSettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(MCP_SETTING_KEYS::contains)
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
    ): McpSettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedMcpSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesMcpSettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return McpSettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedMcpSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, Boolean> = emptyMap(),
) {
  fun normalized(): PersistedMcpSettingsRecord = copy(
    values = values.filterKeys(MCP_SETTING_KEYS::contains),
  )
}

private val MCP_SETTING_KEYS: Set<String> = setOf(
  McpSettingsStoreKeys.MASTER_ENABLED,
)
