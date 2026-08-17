package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentHandleState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

internal interface SubAgentHandleStoreFactory {
  fun forChatSession(sessionId: String): SubAgentHandleStore

  fun knownSessionIds(): List<String> = emptyList()
}

internal interface SubAgentHandleStore {
  fun list(): List<SubAgentHandleState>

  fun listClosed(): List<SubAgentHandleState> = emptyList()

  fun listForParentRun(parentRunId: String): List<SubAgentHandleState>

  fun get(parentRunId: String, agentId: String): SubAgentHandleState?

  fun getClosed(parentRunId: String, agentId: String): SubAgentHandleState? = null

  fun upsert(handle: SubAgentHandleState)

  fun upsertClosed(handle: SubAgentHandleState) = Unit

  fun remove(parentRunId: String, agentId: String): SubAgentHandleState?

  fun retainKnownParentRuns(parentRunIds: Set<String>)

  fun clear()
}

internal fun inMemorySubAgentHandleStoreFactory(): SubAgentHandleStoreFactory =
  InMemorySubAgentHandleStoreFactory()

internal class InMemorySubAgentHandleStoreFactory : SubAgentHandleStoreFactory {
  private val lock = Any()
  private val stores = linkedMapOf<String, SubAgentHandleStore>()

  override fun forChatSession(sessionId: String): SubAgentHandleStore = synchronized(lock) {
    stores.getOrPut(sessionId) { InMemorySubAgentHandleStore(sessionId = sessionId) }
  }

  override fun knownSessionIds(): List<String> = synchronized(lock) {
    stores.keys.toList()
  }
}

internal class FileBackedSubAgentHandleStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: SubAgentHandleStoreConfig = SubAgentHandleStoreConfig(),
) : SubAgentHandleStoreFactory {
  override fun forChatSession(sessionId: String): SubAgentHandleStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedSubAgentHandleStore(
      sessionId = sessionId,
      storage = DirectoryDurableTextStorage(sessionDirectory),
      lock = lockForSubAgentHandleDirectory(sessionDirectory),
      config = config,
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  override fun knownSessionIds(): List<String> = runtimeRootDirectory.listFiles()
    .orEmpty()
    .asSequence()
    .filter(File::isDirectory)
    .mapNotNull { directory -> FileBackedAgentQueueSnapshotStoreFactory.decodeSessionId(directory.name) }
    .distinct()
    .toList()

  companion object {
    fun fromContext(context: Context): SubAgentHandleStoreFactory =
      FileBackedSubAgentHandleStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal fun fileBackedSubAgentHandleStore(
  sessionId: String,
  storage: DurableTextStorage,
  config: SubAgentHandleStoreConfig = SubAgentHandleStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): SubAgentHandleStore = FileBackedSubAgentHandleStore(
  sessionId = sessionId,
  storage = storage,
  lock = Any(),
  config = config,
  clock = clock,
)

internal data class SubAgentHandleStoreConfig(
  val maxTrackedHandles: Int = 256,
) {
  init {
    require(maxTrackedHandles >= 1) {
      "SubAgentHandleStoreConfig maxTrackedHandles must be >= 1."
    }
  }
}

private class InMemorySubAgentHandleStore(
  private val sessionId: String,
) : SubAgentHandleStore {
  private val lock = Any()
  private val handlesByKey = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
  private val closedHandlesByKey = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()

  override fun list(): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values.sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun listClosed(): List<SubAgentHandleState> = synchronized(lock) {
    closedHandlesByKey.values.sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun listForParentRun(parentRunId: String): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values
      .filter { handle -> handle.parentRunId == parentRunId }
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun get(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    handlesByKey[SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId)]
  }

  override fun getClosed(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    closedHandlesByKey[SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId)]
  }

  override fun upsert(handle: SubAgentHandleState) {
    require(handle.parentRunId.isNotBlank()) {
      "Subagent handle parentRunId must not be blank."
    }
    synchronized(lock) {
      val key = SubAgentExecutionKey.from(handle)
      closedHandlesByKey.remove(key)
      handlesByKey[key] = handle
    }
  }

  override fun upsertClosed(handle: SubAgentHandleState) {
    require(handle.parentRunId.isNotBlank()) {
      "Closed subagent handle parentRunId must not be blank."
    }
    synchronized(lock) {
      val key = SubAgentExecutionKey.from(handle)
      handlesByKey.remove(key)
      closedHandlesByKey[key] = handle
    }
  }

  override fun remove(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    handlesByKey.remove(SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId))
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      handlesByKey.entries.removeIf { (_, handle) -> handle.parentRunId !in parentRunIds }
      closedHandlesByKey.entries.removeIf { (_, handle) -> handle.parentRunId !in parentRunIds }
    }
  }

  override fun clear() {
    synchronized(lock) {
      handlesByKey.clear()
      closedHandlesByKey.clear()
    }
  }
}

private class FileBackedSubAgentHandleStore(
  private val sessionId: String,
  private val storage: DurableTextStorage,
  private val lock: Any,
  private val config: SubAgentHandleStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SubAgentHandleStore {
  override fun list(): List<SubAgentHandleState> = synchronized(lock) {
    loadNormalizedRecord().handles
  }

  override fun listClosed(): List<SubAgentHandleState> = synchronized(lock) {
    loadNormalizedRecord().closedHandles
  }

  override fun listForParentRun(parentRunId: String): List<SubAgentHandleState> = synchronized(lock) {
    loadNormalizedRecord().handles
      .filter { handle -> handle.parentRunId == parentRunId }
  }

  override fun get(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    loadNormalizedRecord().handles.firstOrNull { handle ->
      handle.parentRunId == parentRunId && handle.agentId == agentId
    }
  }

  override fun getClosed(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    loadNormalizedRecord().closedHandles.firstOrNull { handle ->
      handle.parentRunId == parentRunId && handle.agentId == agentId
    }
  }

  override fun upsert(handle: SubAgentHandleState) {
    synchronized(lock) {
      updateRecord { existing ->
        val normalizedHandles = normalizeHandles(
          existing.handles.filterNot { stored ->
            stored.parentRunId == handle.parentRunId && stored.agentId == handle.agentId
          } + handle,
        )
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            handles = normalizedHandles,
            closedHandles = existing.closedHandles.filterNot { stored ->
              stored.parentRunId == handle.parentRunId && stored.agentId == handle.agentId
            },
          ),
          result = Unit,
        )
      }
    }
  }

  override fun upsertClosed(handle: SubAgentHandleState) {
    synchronized(lock) {
      updateRecord { existing ->
        val normalizedClosedHandles = normalizeHandles(
          existing.closedHandles.filterNot { stored ->
            stored.parentRunId == handle.parentRunId && stored.agentId == handle.agentId
          } + handle,
        )
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            handles = existing.handles.filterNot { stored ->
              stored.parentRunId == handle.parentRunId && stored.agentId == handle.agentId
            },
            closedHandles = normalizedClosedHandles,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun remove(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    updateRecord { existing ->
      val removed = existing.handles.firstOrNull { handle ->
        handle.parentRunId == parentRunId && handle.agentId == agentId
      } ?: return@updateRecord RecordStorageUpdate(
        value = existing,
        result = null,
        write = false,
      )
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          handles = existing.handles.filterNot { handle ->
            handle.parentRunId == parentRunId && handle.agentId == agentId
          },
        ),
        result = removed,
      )
    }
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      updateRecord { existing ->
        val retained = existing.handles.filter { handle -> handle.parentRunId in parentRunIds }
        val retainedClosed = existing.closedHandles.filter { handle -> handle.parentRunId in parentRunIds }
        if (retained.size == existing.handles.size && retainedClosed.size == existing.closedHandles.size) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = Unit,
            write = false,
          )
        }
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            handles = retained,
            closedHandles = retainedClosed,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): SubAgentHandleRecord =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = SubAgentHandleRecord.serializer(),
    ) { current ->
      val existing = current ?: SubAgentHandleRecord(sessionId = sessionId)
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun <T> updateRecord(
    update: (SubAgentHandleRecord) -> RecordStorageUpdate<SubAgentHandleRecord, T>,
  ): T =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = SubAgentHandleRecord.serializer(),
    ) { current ->
      update(normalizeRecord(current ?: SubAgentHandleRecord(sessionId = sessionId)))
    }

  private fun normalizeRecord(record: SubAgentHandleRecord): SubAgentHandleRecord {
    val normalizedHandles = normalizeHandles(record.handles)
    val normalizedClosedHandles = normalizeHandles(record.closedHandles).filterNot { closedHandle ->
      normalizedHandles.any { currentHandle ->
        currentHandle.parentRunId == closedHandle.parentRunId &&
          currentHandle.agentId == closedHandle.agentId
      }
    }
    return if (
      normalizedHandles == record.handles &&
      normalizedClosedHandles == record.closedHandles &&
      record.sessionId == sessionId
    ) {
      record
    } else {
      record.copy(
        sessionId = sessionId,
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        handles = normalizedHandles,
        closedHandles = normalizedClosedHandles,
      )
    }
  }

  private fun normalizeHandles(
    handles: List<SubAgentHandleState>,
  ): List<SubAgentHandleState> {
    val deduped = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
    handles
      .sortedWith(
        compareByDescending<SubAgentHandleState>(SubAgentHandleState::updatedAtEpochMs)
          .thenByDescending(SubAgentHandleState::createdAtEpochMs),
      )
      .forEach { handle ->
        val key = SubAgentExecutionKey.from(handle)
        if (key !in deduped) {
          deduped[key] = handle.copy(
            description = handle.description.trim(),
            prompt = handle.prompt.trim(),
            subagentType = handle.subagentType.trim(),
            contextMode = handle.contextMode.trim(),
            parentRunId = handle.parentRunId.trim(),
            parentTaskId = handle.parentTaskId.trim(),
          ).withNormalizedMailbox()
        }
      }
    return deduped.values
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
      .take(config.maxTrackedHandles)
  }

  @Serializable
  private data class SubAgentHandleRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val sessionId: String,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val handles: List<SubAgentHandleState> = emptyList(),
    val closedHandles: List<SubAgentHandleState> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-subagent-handles.json"
  }
}

private val SUBAGENT_HANDLE_FILE_LOCKS = ConcurrentHashMap<String, Any>()

private fun lockForSubAgentHandleDirectory(directory: File): Any =
  SUBAGENT_HANDLE_FILE_LOCKS.computeIfAbsent(
    File(directory, "runtime-subagent-handles.json").absolutePath,
  ) { Any() }
