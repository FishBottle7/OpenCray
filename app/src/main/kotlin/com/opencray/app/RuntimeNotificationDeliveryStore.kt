package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable

internal interface RuntimeNotificationDeliveryStoreFactory {
  fun create(): RuntimeNotificationDeliveryStore
}

internal interface RuntimeNotificationDeliveryStore {
  fun wasDelivered(notificationKey: String, fingerprint: String): Boolean

  fun markDelivered(notificationKey: String, fingerprint: String)
}

internal fun inMemoryRuntimeNotificationDeliveryStoreFactory(): RuntimeNotificationDeliveryStoreFactory =
  InMemoryRuntimeNotificationDeliveryStoreFactory()

internal class InMemoryRuntimeNotificationDeliveryStoreFactory : RuntimeNotificationDeliveryStoreFactory {
  private val store = InMemoryRuntimeNotificationDeliveryStore()

  override fun create(): RuntimeNotificationDeliveryStore = store
}

internal class FileBackedRuntimeNotificationDeliveryStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: RuntimeNotificationDeliveryStoreConfig = RuntimeNotificationDeliveryStoreConfig(),
) : RuntimeNotificationDeliveryStoreFactory {
  override fun create(): RuntimeNotificationDeliveryStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedRuntimeNotificationDeliveryStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      config = config,
    )
  }

  companion object {
    fun fromContext(context: Context): RuntimeNotificationDeliveryStoreFactory =
      FileBackedRuntimeNotificationDeliveryStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal data class RuntimeNotificationDeliveryStoreConfig(
  val maxTrackedEntries: Int = 256,
) {
  init {
    require(maxTrackedEntries >= 1) {
      "RuntimeNotificationDeliveryStoreConfig maxTrackedEntries must be >= 1."
    }
  }
}

internal fun fileBackedRuntimeNotificationDeliveryStore(
  storage: DurableTextStorage,
  config: RuntimeNotificationDeliveryStoreConfig = RuntimeNotificationDeliveryStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): RuntimeNotificationDeliveryStore = FileBackedRuntimeNotificationDeliveryStore(
  storage = storage,
  config = config,
  clock = clock,
)

private class InMemoryRuntimeNotificationDeliveryStore : RuntimeNotificationDeliveryStore {
  private val lock = Any()
  private val fingerprintsByKey = linkedMapOf<String, String>()

  override fun wasDelivered(notificationKey: String, fingerprint: String): Boolean = synchronized(lock) {
    fingerprintsByKey[notificationKey] == fingerprint
  }

  override fun markDelivered(notificationKey: String, fingerprint: String) {
    synchronized(lock) {
      fingerprintsByKey[notificationKey] = fingerprint
    }
  }
}

private class FileBackedRuntimeNotificationDeliveryStore(
  private val storage: DurableTextStorage,
  private val config: RuntimeNotificationDeliveryStoreConfig,
  private val clock: () -> Long = System::currentTimeMillis,
) : RuntimeNotificationDeliveryStore {
  private val lock = Any()

  override fun wasDelivered(notificationKey: String, fingerprint: String): Boolean = synchronized(lock) {
    loadNormalizedRecord().entries
      .firstOrNull { entry -> entry.notificationKey == notificationKey }
      ?.fingerprint == fingerprint
  }

  override fun markDelivered(notificationKey: String, fingerprint: String) {
    synchronized(lock) {
      val now = clock()
      storage.updateRecord(
        name = FILE_NAME,
        serializer = PersistedRuntimeNotificationDeliveryRecord.serializer(),
      ) { persisted ->
        val existing = normalizeRecord(persisted ?: PersistedRuntimeNotificationDeliveryRecord())
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = now,
            entries = normalizeEntries(
              existing.entries.filterNot { entry -> entry.notificationKey == notificationKey } +
                PersistedRuntimeNotificationDeliveryEntry(
                  notificationKey = notificationKey,
                  fingerprint = fingerprint,
                  deliveredAtEpochMs = now,
                ),
            ),
          ),
          result = Unit,
        )
      }
    }
  }

  private fun loadNormalizedRecord(): PersistedRuntimeNotificationDeliveryRecord {
    return storage.updateRecord(
      name = FILE_NAME,
      serializer = PersistedRuntimeNotificationDeliveryRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedRuntimeNotificationDeliveryRecord()
      val repaired = normalizeRecord(existing)
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = repaired != existing,
      )
    }
  }

  private fun normalizeRecord(
    record: PersistedRuntimeNotificationDeliveryRecord,
  ): PersistedRuntimeNotificationDeliveryRecord {
    val normalizedEntries = normalizeEntries(record.entries)
    if (normalizedEntries == record.entries) {
      return record
    }
    return record.copy(
      recordVersion = record.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      entries = normalizedEntries,
    )
  }

  private fun normalizeEntries(
    entries: List<PersistedRuntimeNotificationDeliveryEntry>,
  ): List<PersistedRuntimeNotificationDeliveryEntry> = entries
    .filter { entry ->
      entry.notificationKey.isNotBlank() && entry.fingerprint.isNotBlank()
    }
    .sortedByDescending(PersistedRuntimeNotificationDeliveryEntry::deliveredAtEpochMs)
    .groupBy(PersistedRuntimeNotificationDeliveryEntry::notificationKey)
    .values
    .mapNotNull { grouped ->
      grouped.maxByOrNull(PersistedRuntimeNotificationDeliveryEntry::deliveredAtEpochMs)
    }
    .sortedByDescending(PersistedRuntimeNotificationDeliveryEntry::deliveredAtEpochMs)
    .take(config.maxTrackedEntries)

  private companion object {
    const val FILE_NAME: String = "runtime-notification-delivery.json"
  }
}

@Serializable
private data class PersistedRuntimeNotificationDeliveryRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val entries: List<PersistedRuntimeNotificationDeliveryEntry> = emptyList(),
)

@Serializable
private data class PersistedRuntimeNotificationDeliveryEntry(
  val notificationKey: String,
  val fingerprint: String,
  val deliveredAtEpochMs: Long,
)
