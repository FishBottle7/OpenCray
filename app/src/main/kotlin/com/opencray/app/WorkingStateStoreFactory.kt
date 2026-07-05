package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

internal interface WorkingStateStoreFactory {
  fun forChatSession(sessionId: String): WorkingStateStore
}

internal class FileBackedWorkingStateStoreFactory(
  private val runtimeRootDirectory: File,
) : WorkingStateStoreFactory {
  override fun forChatSession(sessionId: String): WorkingStateStore {
    val sessionDirectory = File(
      runtimeRootDirectory,
      FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId),
    ).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedWorkingStateStore(
      sessionId = sessionId,
      storage = DirectoryDurableTextStorage(sessionDirectory),
      lock = lockForWorkingStateDirectory(sessionDirectory),
    )
  }

  companion object {
    fun fromContext(context: Context): WorkingStateStoreFactory =
      FileBackedWorkingStateStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal fun fileBackedWorkingStateStore(
  sessionId: String,
  storage: DurableTextStorage,
  clock: () -> Long = System::currentTimeMillis,
): WorkingStateStore = FileBackedWorkingStateStore(
  sessionId = sessionId,
  storage = storage,
  lock = Any(),
  clock = clock,
)

private class FileBackedWorkingStateStore(
  private val sessionId: String,
  private val storage: DurableTextStorage,
  private val lock: Any,
  private val clock: () -> Long = System::currentTimeMillis,
) : WorkingStateStore {
  override fun snapshot(): WorkingState = synchronized(lock) {
    loadNormalizedRecord().state
  }

  override fun replace(state: WorkingState) {
    synchronized(lock) {
      if (state.isEmpty) {
        storage.delete(FILE_NAME)
        return@synchronized
      }
      storage.updateRecord(
        name = FILE_NAME,
        serializer = WorkingStateRecord.serializer(),
      ) { current ->
        val existing = current ?: WorkingStateRecord(sessionId = sessionId)
        RecordStorageUpdate(
          value = WorkingStateRecord(
            sessionId = sessionId,
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            state = state,
          ),
          result = Unit,
        )
      }
    }
  }

  private fun loadNormalizedRecord(): WorkingStateRecord =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = WorkingStateRecord.serializer(),
    ) { current ->
      val existing = current ?: WorkingStateRecord(sessionId = sessionId)
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun normalizeRecord(record: WorkingStateRecord): WorkingStateRecord {
    if (record.sessionId == sessionId) {
      return record
    }
    return record.copy(
      sessionId = sessionId,
      recordVersion = record.recordVersion + 1L,
      updatedAtEpochMs = clock(),
    )
  }

  @Serializable
  private data class WorkingStateRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val sessionId: String = "",
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val state: WorkingState = WorkingState(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-working-state.json"
  }
}

private val WORKING_STATE_FILE_LOCKS = ConcurrentHashMap<String, Any>()

private fun lockForWorkingStateDirectory(directory: File): Any =
  WORKING_STATE_FILE_LOCKS.computeIfAbsent(
    File(directory, "runtime-working-state.json").absolutePath,
  ) { Any() }
