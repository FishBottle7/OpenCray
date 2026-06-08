package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface RunEventJournalStoreFactory {
  fun forChatSession(sessionId: String): RunEventJournalStore
}

internal interface RunEventJournalStore {
  fun append(event: com.opencray.runtime.OpenCrayAgentRunEvent): PersistedRunJournalEntry

  fun appendCheckpoint(
    runId: String,
    taskId: String,
    emission: OpenCrayPromptCheckpointEmission,
  ): PersistedRunJournalEntry

  fun appendRecovery(
    runId: String,
    taskId: String,
    emittedAtEpochMs: Long,
    metadata: Map<String, String>,
  ): PersistedRunJournalEntry

  fun hasEntries(): Boolean

  fun list(): List<PersistedRunJournalEntry>

  fun listForRun(runId: String): List<PersistedRunJournalEntry>

  fun listRuntimeEvents(): List<com.opencray.runtime.OpenCrayAgentRunEvent> =
    list().mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }

  fun clear()
}

internal fun List<PersistedRunJournalEntry>.latestRuntimeEventOrNull():
  com.opencray.runtime.OpenCrayAgentRunEvent? = asReversed()
  .asSequence()
  .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }
  .firstOrNull()

internal fun inMemoryRunEventJournalStoreFactory(): RunEventJournalStoreFactory =
  InMemoryRunEventJournalStoreFactory()

internal class InMemoryRunEventJournalStoreFactory : RunEventJournalStoreFactory {
  private val lock = Any()
  private val stores = linkedMapOf<String, RunEventJournalStore>()

  override fun forChatSession(sessionId: String): RunEventJournalStore = synchronized(lock) {
    stores.getOrPut(sessionId) { InMemoryRunEventJournalStore(sessionId = sessionId) }
  }
}

internal class FileBackedRunEventJournalStoreFactory(
  private val runtimeRootDirectory: File,
) : RunEventJournalStoreFactory {
  private val lock = Any()
  private val stores = linkedMapOf<String, RunEventJournalStore>()

  override fun forChatSession(sessionId: String): RunEventJournalStore = synchronized(lock) {
    stores.getOrPut(sessionId) {
      val sessionDirectory = directoryForSession(sessionId).apply {
        if (!exists()) {
          mkdirs()
        }
      }
      FileBackedRunEventJournalStore(
        sessionId = sessionId,
        sessionDirectory = sessionDirectory,
      )
    }
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): RunEventJournalStoreFactory =
      FileBackedRunEventJournalStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

@Serializable
internal data class PersistedRunJournalEntry(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val seq: Long,
  val eventId: String,
  val kind: PersistedAgentRunEventKind,
  val emittedAtEpochMs: Long,
  val persistedAtEpochMs: Long,
  val payload: PersistedAgentRunEvent,
) {
  init {
    require(sessionId.isNotBlank()) { "PersistedRunJournalEntry sessionId must not be blank." }
    require(runId.isNotBlank()) { "PersistedRunJournalEntry runId must not be blank." }
    require(taskId.isNotBlank()) { "PersistedRunJournalEntry taskId must not be blank." }
    require(seq >= 1L) { "PersistedRunJournalEntry seq must be >= 1." }
    require(eventId.isNotBlank()) { "PersistedRunJournalEntry eventId must not be blank." }
    require(emittedAtEpochMs >= 0L) { "PersistedRunJournalEntry emittedAtEpochMs must be >= 0." }
    require(persistedAtEpochMs >= 0L) { "PersistedRunJournalEntry persistedAtEpochMs must be >= 0." }
  }
}

private class InMemoryRunEventJournalStore(
  private val sessionId: String,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : RunEventJournalStore {
  private val lock = Any()
  private val entriesByRunId = linkedMapOf<String, MutableList<PersistedRunJournalEntry>>()
  private val nextSeqByRunId = linkedMapOf<String, Long>()

  override fun append(event: com.opencray.runtime.OpenCrayAgentRunEvent): PersistedRunJournalEntry =
    synchronized(lock) {
      appendPayloadLocked(event.toPersistedRecord())
    }

  override fun appendCheckpoint(
    runId: String,
    taskId: String,
    emission: OpenCrayPromptCheckpointEmission,
  ): PersistedRunJournalEntry = synchronized(lock) {
    appendPayloadLocked(
      checkpointPayload(
        runId = runId,
        taskId = taskId,
        emission = emission,
      ),
    )
  }

  override fun appendRecovery(
    runId: String,
    taskId: String,
    emittedAtEpochMs: Long,
    metadata: Map<String, String>,
  ): PersistedRunJournalEntry = synchronized(lock) {
    appendPayloadLocked(
      recoveryPayload(
        runId = runId,
        taskId = taskId,
        emittedAtEpochMs = emittedAtEpochMs,
        metadata = metadata,
      ),
    )
  }

  override fun list(): List<PersistedRunJournalEntry> = synchronized(lock) {
    entriesByRunId.values
      .asSequence()
      .flatten()
      .sortedWith(PERSISTED_JOURNAL_ENTRY_COMPARATOR)
      .toList()
  }

  override fun hasEntries(): Boolean = synchronized(lock) {
    entriesByRunId.values.any(List<PersistedRunJournalEntry>::isNotEmpty)
  }

  override fun listForRun(runId: String): List<PersistedRunJournalEntry> = synchronized(lock) {
    entriesByRunId[runId]
      ?.sortedWith(PERSISTED_JOURNAL_ENTRY_COMPARATOR)
      .orEmpty()
  }

  override fun clear() {
    synchronized(lock) {
      entriesByRunId.clear()
      nextSeqByRunId.clear()
    }
  }

  private fun appendPayloadLocked(
    payload: PersistedAgentRunEvent,
  ): PersistedRunJournalEntry {
    val seq = nextSeqByRunId[payload.runId] ?: 1L
    nextSeqByRunId[payload.runId] = seq + 1L
    val persistedAtEpochMs = clock()
    val entry = PersistedRunJournalEntry(
      sessionId = sessionId,
      runId = payload.runId,
      taskId = payload.taskId,
      seq = seq,
      eventId = journalEventId(persistedAtEpochMs),
      kind = payload.kind,
      emittedAtEpochMs = payload.emittedAtEpochMs,
      persistedAtEpochMs = persistedAtEpochMs,
      payload = payload,
    )
    entriesByRunId.getOrPut(payload.runId) { mutableListOf() } += entry
    return entry
  }
}

private class FileBackedRunEventJournalStore(
  private val sessionId: String,
  private val sessionDirectory: File,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : RunEventJournalStore {
  private val journalDirectory = File(sessionDirectory, JOURNAL_DIRECTORY_NAME)

  override fun append(event: com.opencray.runtime.OpenCrayAgentRunEvent): PersistedRunJournalEntry =
    withJournalLock {
      appendPayloadLocked(event.toPersistedRecord())
    }

  override fun appendCheckpoint(
    runId: String,
    taskId: String,
    emission: OpenCrayPromptCheckpointEmission,
  ): PersistedRunJournalEntry = withJournalLock {
    appendPayloadLocked(
      checkpointPayload(
        runId = runId,
        taskId = taskId,
        emission = emission,
      ),
    )
  }

  override fun appendRecovery(
    runId: String,
    taskId: String,
    emittedAtEpochMs: Long,
    metadata: Map<String, String>,
  ): PersistedRunJournalEntry = withJournalLock {
    appendPayloadLocked(
      recoveryPayload(
        runId = runId,
        taskId = taskId,
        emittedAtEpochMs = emittedAtEpochMs,
        metadata = metadata,
      ),
    )
  }

  override fun list(): List<PersistedRunJournalEntry> = withJournalLock {
    journalDirectory.listFiles()
      ?.asSequence()
      ?.filter(File::isDirectory)
      ?.flatMap { runDirectory -> runDirectory.listFiles().orEmpty().asSequence() }
      ?.filter { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?.mapNotNull(::decodeJournalEntry)
      ?.sortedWith(PERSISTED_JOURNAL_ENTRY_COMPARATOR)
      ?.toList()
      .orEmpty()
  }

  override fun hasEntries(): Boolean = withJournalLock {
    journalDirectory.listFiles()
      ?.asSequence()
      ?.filter(File::isDirectory)
      ?.flatMap { runDirectory -> runDirectory.listFiles().orEmpty().asSequence() }
      ?.any { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?: false
  }

  override fun listForRun(runId: String): List<PersistedRunJournalEntry> = withJournalLock {
    val runDirectory = directoryForRun(runId)
    if (!runDirectory.exists()) {
      return@withJournalLock emptyList()
    }
    runDirectory.listFiles()
      ?.asSequence()
      ?.filter { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?.mapNotNull(::decodeJournalEntry)
      ?.sortedWith(PERSISTED_JOURNAL_ENTRY_COMPARATOR)
      ?.toList()
      .orEmpty()
  }

  override fun clear() {
    withJournalLock {
      if (journalDirectory.exists()) {
        journalDirectory.deleteRecursively()
      }
    }
  }

  private fun directoryForRun(runId: String): File =
    File(journalDirectory, "run-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(runId)}")

  private fun <T> withJournalLock(block: () -> T): T =
    RunEventJournalStoreFileLock.withLock(sessionDirectory, block)

  private fun inferNextSeq(runDirectory: File): Long = runDirectory.listFiles()
    ?.asSequence()
    ?.filter { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
    ?.mapNotNull { file -> file.name.substringBefore('-').toLongOrNull() }
    ?.maxOrNull()
    ?.plus(1L)
    ?: 1L

  private fun journalFileFor(
    runDirectory: File,
    entry: PersistedRunJournalEntry,
  ): File = File(
    runDirectory,
    buildString {
      append(entry.seq.toString().padStart(12, '0'))
      append('-')
      append(entry.kind.name.lowercase(Locale.US))
      append(FILE_SUFFIX)
    },
  )

  private fun appendPayloadLocked(
    payload: PersistedAgentRunEvent,
  ): PersistedRunJournalEntry {
    val runDirectory = ensureDirectory(directoryForRun(payload.runId))
    val seq = inferNextSeq(runDirectory)
    val persistedAtEpochMs = clock()
    val entry = PersistedRunJournalEntry(
      sessionId = sessionId,
      runId = payload.runId,
      taskId = payload.taskId,
      seq = seq,
      eventId = journalEventId(persistedAtEpochMs),
      kind = payload.kind,
      emittedAtEpochMs = payload.emittedAtEpochMs,
      persistedAtEpochMs = persistedAtEpochMs,
      payload = payload,
    )
    writeJournalEntryAtomically(
      file = journalFileFor(runDirectory, entry),
      text = PersistenceJson.instance.encodeToString(
        serializer = PersistedRunJournalEntry.serializer(),
        value = entry,
      ),
    )
    return entry
  }

  private fun decodeJournalEntry(file: File): PersistedRunJournalEntry? = runCatching {
    PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRunJournalEntry.serializer(),
      string = file.readText(Charsets.UTF_8),
    )
  }.getOrNull()

  private fun ensureDirectory(directory: File): File {
    if (directory.exists()) {
      if (!directory.isDirectory) {
        throw IOException("Run journal path must be a directory: ${directory.path}")
      }
      return directory
    }
    if (!directory.mkdirs()) {
      throw IOException("Failed to create run journal directory: ${directory.path}")
    }
    return directory
  }

  private fun writeJournalEntryAtomically(file: File, text: String) {
    if (file.exists()) {
      throw IOException("Run journal entry already exists: ${file.path}")
    }
    val tmp = Files.createTempFile(file.parentFile.toPath(), "${file.name}.", ".tmp").toFile()
    try {
      tmp.writeText(text, Charsets.UTF_8)
      replaceAtomically(tmp = tmp, destination = file)
    } finally {
      if (tmp.exists()) {
        tmp.delete()
      }
    }
  }

  private fun replaceAtomically(tmp: File, destination: File) {
    try {
      Files.move(
        tmp.toPath(),
        destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        tmp.toPath(),
        destination.toPath(),
      )
    }
  }

  private companion object {
    private const val JOURNAL_DIRECTORY_NAME = "run-journal"
    private const val FILE_SUFFIX = ".json"
  }
}

private object RunEventJournalStoreFileLock {
  private const val LOCK_FILE_NAME = ".run-journal.lock"
  private val locksByDirectory = ConcurrentHashMap<String, Any>()

  fun <T> withLock(sessionDirectory: File, block: () -> T): T {
    val normalizedDirectory = sessionDirectory.absoluteFile.normalize()
    val directoryKey = normalizedDirectory.path
    val processLocalLock = locksByDirectory.computeIfAbsent(directoryKey) { Any() }
    synchronized(processLocalLock) {
      ensureDirectory(normalizedDirectory)
      val lockFile = File(normalizedDirectory, LOCK_FILE_NAME)
      RandomAccessFile(lockFile, "rw").channel.use { channel ->
        channel.lock().use {
          return block()
        }
      }
    }
  }

  private fun ensureDirectory(directory: File) {
    if (directory.exists()) {
      require(directory.isDirectory) {
        "Run journal lock path must be a directory: ${directory.path}"
      }
      return
    }
    require(directory.mkdirs()) {
      "Failed to create run journal lock directory: ${directory.path}"
    }
  }
}

private fun journalEventId(nowEpochMs: Long): String =
  "event-$nowEpochMs-${UUID.randomUUID().toString().take(8)}"

private fun checkpointPayload(
  runId: String,
  taskId: String,
  emission: OpenCrayPromptCheckpointEmission,
): PersistedAgentRunEvent = PersistedAgentRunEvent(
  kind = PersistedAgentRunEventKind.CHECKPOINT,
  runId = runId,
  taskId = taskId,
  emittedAtEpochMs = emission.emittedAtEpochMs,
  toolName = emission.toolName?.trim()?.takeIf(String::isNotBlank),
  resultMetadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
    state = emission.state,
    json = PROMPT_CHECKPOINT_JSON,
    checkpointBoundary = emission.boundary,
  ),
)

private fun recoveryPayload(
  runId: String,
  taskId: String,
  emittedAtEpochMs: Long,
  metadata: Map<String, String>,
): PersistedAgentRunEvent = PersistedAgentRunEvent(
  kind = PersistedAgentRunEventKind.RECOVERY,
  runId = runId,
  taskId = taskId,
  emittedAtEpochMs = emittedAtEpochMs,
  resultMetadata = metadata.filterValues { value -> value.isNotBlank() },
)

private val PERSISTED_JOURNAL_ENTRY_COMPARATOR = compareBy<PersistedRunJournalEntry>(
  PersistedRunJournalEntry::emittedAtEpochMs,
  PersistedRunJournalEntry::persistedAtEpochMs,
  PersistedRunJournalEntry::runId,
  PersistedRunJournalEntry::seq,
)

private val PROMPT_CHECKPOINT_JSON: Json = Json { prettyPrint = false }
