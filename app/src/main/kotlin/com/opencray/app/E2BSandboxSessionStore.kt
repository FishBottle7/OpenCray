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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_E2B_SANDBOX_SESSION_PREFERENCES = "opencray.e2b-sandbox-session"
private const val E2B_SANDBOX_SESSION_FILE_NAME = "e2b-sandbox-session.json"
private const val KEY_ACTIVE_SESSION = "active_session"

@Serializable
internal data class E2BSandboxSessionSnapshot(
  val sandboxId: String,
  val sandboxDomain: String,
  val envdAccessToken: String? = null,
  val trafficAccessToken: String? = null,
  val workspaceRoot: String,
  val templateId: String,
  val updatedAtEpochMs: Long,
  val previewCandidatePorts: List<Int> = emptyList(),
  val remoteWorkspaceRoot: String? = null,
  val lastPreviewUrl: String? = null,
  val lastPreviewPort: Int? = null,
  val lastPreviewPath: String? = null,
  val lastPreviewProbeStatus: String? = null,
  val lastPreviewProbeHttpStatusCode: Int? = null,
  val lastPreviewProbeMessage: String? = null,
  val lastPreviewOpenedAtEpochMs: Long? = null,
  val lastPreviewProbeObservedAtEpochMs: Long? = null,
  val lastPreviewProbeSource: String? = null,
)

internal interface E2BSandboxSessionKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun remove(key: String)

  fun updateString(
    key: String,
    transform: (String?) -> String?,
  ): String? {
    val updated = transform(getString(key))
    if (updated == null) {
      remove(key)
    } else {
      putString(key, updated)
    }
    return updated
  }
}

internal class InMemoryE2BSandboxSessionKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : E2BSandboxSessionKeyValueStore {
  private val lock = Any()

  override fun getString(key: String): String? = synchronized(lock) { values[key] }

  override fun putString(key: String, value: String) {
    synchronized(lock) {
      values[key] = value
    }
  }

  override fun remove(key: String) {
    synchronized(lock) {
      values.remove(key)
    }
  }

  override fun updateString(
    key: String,
    transform: (String?) -> String?,
  ): String? = synchronized(lock) {
    transform(values[key]).also { updated ->
      if (updated == null) {
        values.remove(key)
      } else {
        values[key] = updated
      }
    }
  }
}

internal class SharedPreferencesE2BSandboxSessionKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : E2BSandboxSessionKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun remove(key: String) {
    sharedPreferences.edit().remove(key).apply()
  }

  fun hasAnyPersistedSetting(): Boolean =
    E2B_SANDBOX_SESSION_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedE2BSandboxSessionKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : E2BSandboxSessionKeyValueStore {
  private val lock = Any()

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(key: String, value: String) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun remove(key: String) {
    synchronized(lock) {
      updateValues { values ->
        values - key
      }
    }
  }

  override fun updateString(
    key: String,
    transform: (String?) -> String?,
  ): String? = synchronized(lock) {
    val now = clock()
    storage.updateRecord(
      name = E2B_SANDBOX_SESSION_FILE_NAME,
      serializer = PersistedE2BSandboxSessionRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedE2BSandboxSessionRecord()).normalized()
      val updatedValue = transform(existing.values[key])
      val updatedValues = if (updatedValue == null) {
        existing.values - key
      } else {
        existing.values + (key to updatedValue)
      }.filterKeys(E2B_SANDBOX_SESSION_KEYS::contains)
      val changed = updatedValues != existing.values
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + if (changed) 1L else 0L,
          updatedAtEpochMs = if (changed) now else existing.updatedAtEpochMs,
          values = updatedValues,
        ),
        result = updatedValue,
        write = changed || (persisted != null && existing != persisted),
      )
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: E2BSandboxSessionKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacySession = legacyStore.getString(KEY_ACTIVE_SESSION)
        ?: return
      updateValues { values ->
        values + (KEY_ACTIVE_SESSION to legacySession)
      }
    }
  }

  private fun hasPersistedRecord(): Boolean = storage.updateRecord(
    name = E2B_SANDBOX_SESSION_FILE_NAME,
    serializer = PersistedE2BSandboxSessionRecord.serializer(),
  ) { persisted ->
    RecordStorageUpdate(
      value = persisted,
      result = persisted != null,
      write = false,
    )
  }

  private fun loadRecord(): PersistedE2BSandboxSessionRecord =
    storage.updateRecord(
      name = E2B_SANDBOX_SESSION_FILE_NAME,
      serializer = PersistedE2BSandboxSessionRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedE2BSandboxSessionRecord()
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
      name = E2B_SANDBOX_SESSION_FILE_NAME,
      serializer = PersistedE2BSandboxSessionRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedE2BSandboxSessionRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(E2B_SANDBOX_SESSION_KEYS::contains)
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

internal class E2BSandboxSessionStore(
  private val keyValueStore: E2BSandboxSessionKeyValueStore,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
  fun load(): E2BSandboxSessionSnapshot? =
    decodeSnapshot(keyValueStore.getString(KEY_ACTIVE_SESSION))

  fun save(snapshot: E2BSandboxSessionSnapshot) {
    keyValueStore.putString(
      KEY_ACTIVE_SESSION,
      encodeSnapshot(snapshot),
    )
  }

  fun update(
    transform: (E2BSandboxSessionSnapshot?) -> E2BSandboxSessionSnapshot?,
  ): E2BSandboxSessionSnapshot? = decodeSnapshot(
    keyValueStore.updateString(KEY_ACTIVE_SESSION) { raw ->
      transform(decodeSnapshot(raw))?.let(::encodeSnapshot)
    },
  )

  fun clear() {
    keyValueStore.remove(KEY_ACTIVE_SESSION)
  }

  private fun decodeSnapshot(raw: String?): E2BSandboxSessionSnapshot? =
    raw
      ?.takeIf(String::isNotBlank)
      ?.let { encoded ->
        runCatching { json.decodeFromString(E2BSandboxSessionSnapshot.serializer(), encoded) }
          .getOrNull()
      }

  private fun encodeSnapshot(snapshot: E2BSandboxSessionSnapshot): String =
    json.encodeToString(E2BSandboxSessionSnapshot.serializer(), snapshot)

  companion object {
    fun fromContext(context: Context): E2BSandboxSessionStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedE2BSandboxSessionKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesE2BSandboxSessionKeyValueStore(
        appContext.getSharedPreferences(
          DEFAULT_E2B_SANDBOX_SESSION_PREFERENCES,
          Context.MODE_PRIVATE,
        ),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return E2BSandboxSessionStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedE2BSandboxSessionRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedE2BSandboxSessionRecord = copy(
    values = values.filterKeys(E2B_SANDBOX_SESSION_KEYS::contains),
  )
}

private val E2B_SANDBOX_SESSION_KEYS: Set<String> = setOf(
  KEY_ACTIVE_SESSION,
)
