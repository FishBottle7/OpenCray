package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.ProcessFileLockChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

internal interface ScheduledTaskTriggerSyncStateStoreFactory {
  fun create(): ScheduledTaskTriggerSyncStateStore
}

internal interface ScheduledTaskTriggerSyncStateStore {
  fun loadScheduleIds(): Set<String>

  fun replaceScheduleIds(scheduleIds: Set<String>)

  fun clear()

  fun <T> withResyncLock(block: () -> T): T = block()
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
      runtimeRootDirectory = runtimeRootDirectory,
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

  override fun <T> withResyncLock(block: () -> T): T = synchronized(lock) {
    block()
  }
}

internal class FileBackedScheduledTaskTriggerSyncStateStore(
  private val storage: DurableTextStorage,
  private val runtimeRootDirectory: File,
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
      storage.updateText(TRIGGER_SYNC_STATE_FILE_NAME) {
        DurableTextUpdate(
          text = encodeRecord(
            ScheduledTaskTriggerSyncStateRecord(
              scheduleIds = normalized.toList(),
            ),
          ),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.updateText(TRIGGER_SYNC_STATE_FILE_NAME) {
        DurableTextUpdate(
          text = null,
          result = Unit,
        )
      }
    }
  }

  override fun <T> withResyncLock(block: () -> T): T =
    ScheduledTaskTriggerResyncLock.withLock(runtimeRootDirectory, block)

  private fun loadRecord(): ScheduledTaskTriggerSyncStateRecord {
    val encoded = storage.readText(TRIGGER_SYNC_STATE_FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return ScheduledTaskTriggerSyncStateRecord()
    }
    return runCatching {
      PersistenceJson.instance.decodeFromString(
        ScheduledTaskTriggerSyncStateRecord.serializer(),
        encoded,
      )
    }.getOrDefault(ScheduledTaskTriggerSyncStateRecord())
  }

  private fun encodeRecord(record: ScheduledTaskTriggerSyncStateRecord): String =
    PersistenceJson.instance.encodeToString(
      ScheduledTaskTriggerSyncStateRecord.serializer(),
      record,
    )
}

@Serializable
private data class ScheduledTaskTriggerSyncStateRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val scheduleIds: List<String> = emptyList(),
)

private const val TRIGGER_SYNC_STATE_FILE_NAME: String = "scheduled-task-trigger-sync-state-v2.json"
private const val TRIGGER_SYNC_RESYNC_LOCK_FILE_NAME: String = "scheduled-task-trigger-resync.lock"

private object ScheduledTaskTriggerResyncLock {
  private val locksByDirectory = ConcurrentHashMap<String, Any>()

  fun <T> withLock(runtimeRootDirectory: File, block: () -> T): T {
    val normalizedDirectory = runtimeRootDirectory.absoluteFile.normalize()
    val directoryKey = normalizedDirectory.path
    val processLocalLock = locksByDirectory.computeIfAbsent(directoryKey) { Any() }
    if (Thread.holdsLock(processLocalLock)) {
      // The JVM monitor is reentrant, but reacquiring its OS sidecar lock is not.
      return block()
    }
    return synchronized(processLocalLock) {
      ensureDirectory(normalizedDirectory)
      val lockFile = File(normalizedDirectory, TRIGGER_SYNC_RESYNC_LOCK_FILE_NAME)
      ProcessFileLockChannel.withLock(lockFile, block)
    }
  }

  private fun ensureDirectory(directory: File) {
    if (!directory.exists()) {
      directory.mkdirs()
    }
  }
}
