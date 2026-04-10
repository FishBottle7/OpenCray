package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal interface ScheduledTaskTriggerSyncStateStoreFactory {
  fun create(): ScheduledTaskTriggerSyncStateStore
}

internal interface ScheduledTaskTriggerSyncStateStore {
  fun loadScheduleIds(): Set<String>

  fun replaceScheduleIds(scheduleIds: Set<String>)

  fun clear()
}

internal fun inMemoryScheduledTaskTriggerSyncStateStoreFactory():
  ScheduledTaskTriggerSyncStateStoreFactory = InMemoryScheduledTaskTriggerSyncStateStoreFactory()

internal class InMemoryScheduledTaskTriggerSyncStateStoreFactory :
  ScheduledTaskTriggerSyncStateStoreFactory {
  private val store = InMemoryScheduledTaskTriggerSyncStateStore()

  override fun create(): ScheduledTaskTriggerSyncStateStore = store
}

internal class FileBackedScheduledTaskTriggerSyncStateStoreFactory(
  private val runtimeRootDirectory: File,
) : ScheduledTaskTriggerSyncStateStoreFactory {
  override fun create(): ScheduledTaskTriggerSyncStateStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedScheduledTaskTriggerSyncStateStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
    )
  }

  companion object {
    fun fromContext(context: Context): ScheduledTaskTriggerSyncStateStoreFactory =
      FileBackedScheduledTaskTriggerSyncStateStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

private class InMemoryScheduledTaskTriggerSyncStateStore :
  ScheduledTaskTriggerSyncStateStore {
  private val lock = Any()
  private var scheduleIds: LinkedHashSet<String> = linkedSetOf()

  override fun loadScheduleIds(): Set<String> = synchronized(lock) {
    LinkedHashSet(scheduleIds)
  }

  override fun replaceScheduleIds(scheduleIds: Set<String>) {
    synchronized(lock) {
      this.scheduleIds = scheduleIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())
    }
  }

  override fun clear() {
    synchronized(lock) {
      scheduleIds.clear()
    }
  }
}

private class FileBackedScheduledTaskTriggerSyncStateStore(
  private val storage: DurableTextStorage,
) : ScheduledTaskTriggerSyncStateStore {
  private val lock = Any()

  override fun loadScheduleIds(): Set<String> = synchronized(lock) {
    loadRecord().scheduleIds
      .map(String::trim)
      .filter(String::isNotBlank)
      .toCollection(linkedSetOf())
  }

  override fun replaceScheduleIds(scheduleIds: Set<String>) {
    synchronized(lock) {
      val normalized = scheduleIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())
      saveRecord(
        ScheduledTaskTriggerSyncStateRecord(
          scheduleIds = normalized.toList(),
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(TRIGGER_SYNC_STATE_FILE_NAME)
    }
  }

  private fun loadRecord(): ScheduledTaskTriggerSyncStateRecord {
    val encoded = storage.readText(TRIGGER_SYNC_STATE_FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return ScheduledTaskTriggerSyncStateRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      ScheduledTaskTriggerSyncStateRecord.serializer(),
      encoded,
    )
  }

  private fun saveRecord(record: ScheduledTaskTriggerSyncStateRecord) {
    storage.writeText(
      TRIGGER_SYNC_STATE_FILE_NAME,
      PersistenceJson.instance.encodeToString(
        ScheduledTaskTriggerSyncStateRecord.serializer(),
        record,
      ),
    )
  }
}

@Serializable
private data class ScheduledTaskTriggerSyncStateRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val scheduleIds: List<String> = emptyList(),
)

private const val TRIGGER_SYNC_STATE_FILE_NAME: String = "scheduled-task-trigger-sync-state-v2.json"
