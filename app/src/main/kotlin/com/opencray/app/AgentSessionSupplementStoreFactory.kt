package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
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
    return FileBackedSessionSupplementStore(sessionDirectory)
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentSessionSupplementStoreFactory =
      FileBackedAgentSessionSupplementStoreFactory(
        runtimeRootDirectory = File(context.filesDir, FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME),
      )
  }
}

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
  directory: File,
) : SessionSupplementStore {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

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
      val existing = loadRecord()
      val now = System.currentTimeMillis()
      val entry = MidLoopSupplementEntry(
        entryId = "supplement-$now-${UUID.randomUUID().toString().take(8)}",
        runId = runId,
        taskId = taskId,
        text = normalizedText,
        createdAtEpochMs = now,
      )
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = now,
          entries = (existing.entries + entry).sortedBy(MidLoopSupplementEntry::createdAtEpochMs),
        ),
      )
      return entry
    }
  }

  override fun consumeForRun(
    runId: String,
    taskId: String,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    val existing = loadRecord()
    val matched = existing.entries.filter { entry ->
      entry.runId == runId || entry.taskId == taskId
    }
    if (matched.isEmpty()) {
      return@synchronized emptyList()
    }
    saveRecord(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = System.currentTimeMillis(),
        entries = existing.entries.filterNot { entry -> entry in matched },
      ),
    )
    matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun consumeMatching(
    predicate: (MidLoopSupplementEntry) -> Boolean,
  ): List<MidLoopSupplementEntry> = synchronized(lock) {
    val existing = loadRecord()
    val matched = existing.entries.filter(predicate)
    if (matched.isEmpty()) {
      return@synchronized emptyList()
    }
    saveRecord(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = System.currentTimeMillis(),
        entries = existing.entries.filterNot { entry -> entry in matched },
      ),
    )
    matched.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun consumeAll(): List<MidLoopSupplementEntry> = synchronized(lock) {
    val existing = loadRecord()
    if (existing.entries.isEmpty()) {
      return@synchronized emptyList()
    }
    saveRecord(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = System.currentTimeMillis(),
        entries = emptyList(),
      ),
    )
    existing.entries.sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SessionSupplementRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return SessionSupplementRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SessionSupplementRecord.serializer(),
      string = encoded,
    )
  }

  private fun saveRecord(record: SessionSupplementRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = SessionSupplementRecord.serializer(),
        value = record,
      ),
    )
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
