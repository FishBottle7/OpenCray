package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
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

  fun listForParentRun(parentRunId: String): List<SubAgentHandleState>

  fun get(parentRunId: String, agentId: String): SubAgentHandleState?

  fun upsert(handle: SubAgentHandleState)

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
      directory = sessionDirectory,
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

  override fun list(): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values.sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun listForParentRun(parentRunId: String): List<SubAgentHandleState> = synchronized(lock) {
    handlesByKey.values
      .filter { handle -> handle.parentRunId == parentRunId }
      .sortedByDescending(SubAgentHandleState::updatedAtEpochMs)
  }

  override fun get(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    handlesByKey[SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId)]
  }

  override fun upsert(handle: SubAgentHandleState) {
    require(handle.parentRunId.isNotBlank()) {
      "Subagent handle parentRunId must not be blank."
    }
    synchronized(lock) {
      handlesByKey[SubAgentExecutionKey.from(handle)] = handle
    }
  }

  override fun remove(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    handlesByKey.remove(SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId))
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      handlesByKey.entries.removeIf { (_, handle) -> handle.parentRunId !in parentRunIds }
    }
  }

  override fun clear() {
    synchronized(lock) {
      handlesByKey.clear()
    }
  }
}

private class FileBackedSubAgentHandleStore(
  private val sessionId: String,
  directory: File,
  private val config: SubAgentHandleStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SubAgentHandleStore {
  private val lock = lockFor(directory)
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  override fun list(): List<SubAgentHandleState> = synchronized(lock) {
    loadNormalizedRecord().handles
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

  override fun upsert(handle: SubAgentHandleState) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val normalizedHandles = normalizeHandles(
        existing.handles.filterNot { stored ->
          stored.parentRunId == handle.parentRunId && stored.agentId == handle.agentId
        } + handle,
      )
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          handles = normalizedHandles,
        ),
      )
    }
  }

  override fun remove(parentRunId: String, agentId: String): SubAgentHandleState? = synchronized(lock) {
    val existing = loadNormalizedRecord()
    val removed = existing.handles.firstOrNull { handle ->
      handle.parentRunId == parentRunId && handle.agentId == agentId
    } ?: return null
    saveRecord(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        handles = existing.handles.filterNot { handle ->
          handle.parentRunId == parentRunId && handle.agentId == agentId
        },
      ),
    )
    removed
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val retained = existing.handles.filter { handle -> handle.parentRunId in parentRunIds }
      if (retained.size == existing.handles.size) {
        return
      }
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          handles = retained,
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SubAgentHandleRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return SubAgentHandleRecord(sessionId = sessionId)
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SubAgentHandleRecord.serializer(),
      string = encoded,
    )
  }

  private fun loadNormalizedRecord(): SubAgentHandleRecord {
    val existing = loadRecord()
    val normalizedHandles = normalizeHandles(existing.handles)
    if (normalizedHandles == existing.handles && existing.sessionId == sessionId) {
      return existing
    }
    val repaired = existing.copy(
      sessionId = sessionId,
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      handles = normalizedHandles,
    )
    saveRecord(repaired)
    return repaired
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

  private fun saveRecord(record: SubAgentHandleRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = SubAgentHandleRecord.serializer(),
        value = record,
      ),
    )
  }

  @Serializable
  private data class SubAgentHandleRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val sessionId: String,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val handles: List<SubAgentHandleState> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-subagent-handles.json"

    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()

    private fun lockFor(directory: File): Any =
      FILE_LOCKS.computeIfAbsent(File(directory, FILE_NAME).absolutePath) { Any() }
  }
}
