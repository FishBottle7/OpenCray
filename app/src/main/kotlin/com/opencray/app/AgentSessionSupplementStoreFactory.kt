package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

internal interface AgentSessionSupplementStoreFactory {
  fun forChatSession(sessionId: String): SessionSupplementStore
}

internal interface SessionSupplementStore {
  fun snapshot(): List<MidLoopSupplementEntry>

  fun append(
    runId: String,
    taskId: String,
    text: String,
  ): MidLoopSupplementEntry

  fun consumeForRun(
    runId: String,
    taskId: String,
  ): List<MidLoopSupplementEntry>

  fun consumeMatching(
    predicate: (MidLoopSupplementEntry) -> Boolean,
  ): List<MidLoopSupplementEntry>

  fun consumeAll(): List<MidLoopSupplementEntry>

  fun clear()
}

internal class FileBackedAgentSessionSupplementStoreFactory(
  private val runtimeRootDirectory: File,
) : AgentSessionSupplementStoreFactory {
  override fun forChatSession(sessionId: String): SessionSupplementStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedSessionSupplementStore(
      storage = DirectoryDurableTextStorage(sessionDirectory),
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentSessionSupplementStoreFactory =
      FileBackedAgentSessionSupplementStoreFactory(
        runtimeRootDirectory = File(context.filesDir, FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME),
      )

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): FileBackedAgentSessionSupplementStoreFactory = fromAgent(pathResolver, agentId)

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): FileBackedAgentSessionSupplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory(
      runtimeRootDirectory = rootDirectoryForAgent(pathResolver, agentId),
    )

    internal fun rootDirectoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).transcriptSupplementsRoot.toFile()
  }
}

internal fun fileBackedSessionSupplementStore(
  storage: DurableTextStorage,
  nowEpochMs: () -> Long = System::currentTimeMillis,
  entryIdSuffix: () -> String = { UUID.randomUUID().toString().take(8) },
): SessionSupplementStore = FileBackedSessionSupplementStore(
  storage = storage,
  nowEpochMs = nowEpochMs,
  entryIdSuffix = entryIdSuffix,
)

internal class InMemorySessionSupplementStore(
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : SessionSupplementStore {
  private val lock = Any()
  private val entries = mutableListOf<MidLoopSupplementEntry>()

  override fun snapshot(): List<MidLoopSupplementEntry> = synchronized(lock) {
    entries.toList()
  }

  override fun append(
    runId: String,
    taskId: String,
    text: String,
  ): MidLoopSupplementEntry {
    val normalizedText = text.trim()
    require(runId.isNotBlank()) { "append supplement runId must not be blank." }
    require(taskId.isNotBlank()) { "append supplement taskId must not be blank." }
    require(normalizedText.isNotBlank()) { "append supplement text must not be blank." }
    val entry = MidLoopSupplementEntry(
      entryId = "supplement-${nowEpochMs()}-${UUID.randomUUID().toString().take(8)}",
      runId = runId,
      taskId = taskId,
      text = normalizedText,
      createdAtEpochMs = nowEpochMs(),
    )
    synchronized(lock) {
      entries += entry
    }
    return entry
  }

  override fun consumeForRun(
    runId: String,
    taskId: String,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    if (entries.isEmpty()) {
      return@synchronized emptyList()
    }
    val matched = entries.filter { entry ->
      entry.runId == runId || entry.taskId == taskId
    }
    if (matched.isEmpty()) {
      return@synchronized emptyList()
    }
    entries.removeAll(matched.toSet())
    matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun consumeMatching(
    predicate: (MidLoopSupplementEntry) -> Boolean,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    if (entries.isEmpty()) {
      return@synchronized emptyList()
    }
    val matched = entries.filter(predicate)
    if (matched.isEmpty()) {
      return@synchronized emptyList()
    }
    entries.removeAll(matched.toSet())
    matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun consumeAll(): List<MidLoopSupplementEntry> = synchronized(lock) {
    if (entries.isEmpty()) {
      return@synchronized emptyList()
    }
    val snapshot = entries.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
    entries.clear()
    snapshot
  }

  override fun clear() {
    synchronized(lock) {
      entries.clear()
    }
  }
}

private class FileBackedSessionSupplementStore(
  private val storage: DurableTextStorage,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
  private val entryIdSuffix: () -> String = { UUID.randomUUID().toString().take(8) },
) : SessionSupplementStore {
  private val lock = Any()

  override fun snapshot(): List<MidLoopSupplementEntry> = synchronized(lock) {
    loadRecord().entries.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun append(
    runId: String,
    taskId: String,
    text: String,
  ): MidLoopSupplementEntry {
    val normalizedText = text.trim()
    require(runId.isNotBlank()) { "append supplement runId must not be blank." }
    require(taskId.isNotBlank()) { "append supplement taskId must not be blank." }
    require(normalizedText.isNotBlank()) { "append supplement text must not be blank." }
    synchronized(lock) {
      val now = nowEpochMs()
      val entry = MidLoopSupplementEntry(
        entryId = "supplement-$now-${entryIdSuffix()}",
        runId = runId,
        taskId = taskId,
        text = normalizedText,
        createdAtEpochMs = now,
      )
      return storage.updateRecord(
        name = FILE_NAME,
        serializer = SessionSupplementRecord.serializer(),
      ) { persisted ->
        val existing = persisted ?: SessionSupplementRecord()
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = now,
            entries = (existing.entries + entry).sortedBy(MidLoopSupplementEntry::createdAtEpochMs),
          ),
          result = entry,
        )
      }
    }
  }

  override fun consumeForRun(
    runId: String,
    taskId: String,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    storage.updateRecord(
      name = FILE_NAME,
      serializer = SessionSupplementRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: SessionSupplementRecord()
      val matched = existing.entries.filter { entry ->
        entry.runId == runId || entry.taskId == taskId
      }
      if (matched.isEmpty()) {
        return@updateRecord RecordStorageUpdate(
          value = existing,
          result = emptyList(),
          write = false,
        )
      }
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = nowEpochMs(),
          entries = existing.entries.filterNot { entry -> entry in matched },
        ),
        result = matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs),
      )
    }
  }

  override fun consumeMatching(
    predicate: (MidLoopSupplementEntry) -> Boolean,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    storage.updateRecord(
      name = FILE_NAME,
      serializer = SessionSupplementRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: SessionSupplementRecord()
      val matched = existing.entries.filter(predicate)
      if (matched.isEmpty()) {
        return@updateRecord RecordStorageUpdate(
          value = existing,
          result = emptyList(),
          write = false,
        )
      }
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = nowEpochMs(),
          entries = existing.entries.filterNot { entry -> entry in matched },
        ),
        result = matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs),
      )
    }
  }

  override fun consumeAll(): List<MidLoopSupplementEntry> = synchronized(lock) {
    storage.updateRecord(
      name = FILE_NAME,
      serializer = SessionSupplementRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: SessionSupplementRecord()
      if (existing.entries.isEmpty()) {
        return@updateRecord RecordStorageUpdate(
          value = existing,
          result = emptyList(),
          write = false,
        )
      }
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = nowEpochMs(),
          entries = emptyList(),
        ),
        result = existing.entries.sortedBy(MidLoopSupplementEntry::createdAtEpochMs),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SessionSupplementRecord {
    return storage.updateRecord(
      name = FILE_NAME,
      serializer = SessionSupplementRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: SessionSupplementRecord()
      RecordStorageUpdate(
        value = existing,
        result = existing,
        write = false,
      )
    }
  }

  @Serializable
  private data class SessionSupplementRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val entries: List<MidLoopSupplementEntry> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME = "runtime-supplements.json"
  }
}

@Serializable
internal data class MidLoopSupplementEntry(
  val entryId: String,
  val runId: String,
  val taskId: String,
  val text: String,
  val createdAtEpochMs: Long,
) {
  init {
    require(entryId.isNotBlank()) { "MidLoopSupplementEntry entryId must not be blank." }
    require(runId.isNotBlank()) { "MidLoopSupplementEntry runId must not be blank." }
    require(taskId.isNotBlank()) { "MidLoopSupplementEntry taskId must not be blank." }
    require(text.isNotBlank()) { "MidLoopSupplementEntry text must not be blank." }
    require(createdAtEpochMs >= 0L) { "MidLoopSupplementEntry createdAtEpochMs must be >= 0." }
  }
}
