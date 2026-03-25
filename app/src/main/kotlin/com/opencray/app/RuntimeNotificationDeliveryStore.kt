package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
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
      val existing = loadNormalizedRecord()
      saveRecord(
        existing.copy(
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
      )
    }
  }

  private fun loadNormalizedRecord(): PersistedRuntimeNotificationDeliveryRecord {
    val existing = loadRecord()
    val normalizedEntries = normalizeEntries(existing.entries)
    if (normalizedEntries == existing.entries) {
      return existing
    }
    val repaired = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      entries = normalizedEntries,
    )
    saveRecord(repaired)
    return repaired
  }

  private fun loadRecord(): PersistedRuntimeNotificationDeliveryRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return PersistedRuntimeNotificationDeliveryRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeNotificationDeliveryRecord.serializer(),
      string = encoded,
    )
  }

  private fun saveRecord(record: PersistedRuntimeNotificationDeliveryRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = PersistedRuntimeNotificationDeliveryRecord.serializer(),
        value = record,
      ),
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
