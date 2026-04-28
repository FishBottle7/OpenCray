package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import java.io.File
import java.util.Locale
import java.util.UUID
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

  fun hasEntries(): Boolean

  fun list(): List<PersistedRunJournalEntry>

  fun listForRun(runId: String): List<PersistedRunJournalEntry>

  fun listRuntimeEvents(): List<com.opencray.runtime.OpenCrayAgentRunEvent> =
    list().mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }

  fun clear()
}

internal fun List<PersistedRunJournalEntry>.latestRuntimeEventOrNull():
  com.opencray.runtime.OpenCrayAgentRunEvent? = asSequence()
  .mapNotNull { entry ->
    entry.payload.toRuntimeEventOrNull()?.let { runtimeEvent -> entry to runtimeEvent }
  }
  .maxWithOrNull(
    compareBy<Pair<PersistedRunJournalEntry, com.opencray.runtime.OpenCrayAgentRunEvent>> {
      it.first.persistedAtEpochMs
    }
      .thenBy { it.first.runId }
      .thenBy { it.first.seq }
      .thenBy { it.first.emittedAtEpochMs }
      .thenBy { it.first.eventId },
  )
  ?.second

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
      val payload = event.toPersistedRecord()
      entriesByRunId[payload.runId]
        ?.firstOrNull { entry -> entry.payload == payload }
        ?.let { existing ->
          return@synchronized existing
        }
      appendPayloadLocked(payload)
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
      ?.sortedWith(PERSISTED_RUN_JOURNAL_ENTRY_COMPARATOR)
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
  sessionDirectory: File,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : RunEventJournalStore {
  private val lock = Any()
  private val journalDirectory = File(sessionDirectory, JOURNAL_DIRECTORY_NAME)
  private val nextSeqByRunId = linkedMapOf<String, Long>()

  override fun append(event: com.opencray.runtime.OpenCrayAgentRunEvent): PersistedRunJournalEntry =
    synchronized(lock) {
      val payload = event.toPersistedRecord()
      val existing = listForRun(payload.runId)
        .firstOrNull { entry -> entry.payload == payload }
      if (existing != null) {
        return@synchronized existing
      }
      appendPayloadLocked(payload)
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

  override fun list(): List<PersistedRunJournalEntry> = synchronized(lock) {
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

  override fun hasEntries(): Boolean = synchronized(lock) {
    journalDirectory.listFiles()
      ?.asSequence()
      ?.filter(File::isDirectory)
      ?.flatMap { runDirectory -> runDirectory.listFiles().orEmpty().asSequence() }
      ?.any { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?: false
  }

  override fun listForRun(runId: String): List<PersistedRunJournalEntry> = synchronized(lock) {
    val runDirectory = directoryForRun(runId)
    if (!runDirectory.exists()) {
      return@synchronized emptyList()
    }
    runDirectory.listFiles()
      ?.asSequence()
      ?.filter { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?.mapNotNull(::decodeJournalEntry)
      ?.sortedWith(PERSISTED_RUN_JOURNAL_ENTRY_COMPARATOR)
      ?.toList()
      .orEmpty()
  }

  override fun clear() {
    synchronized(lock) {
      if (journalDirectory.exists()) {
        journalDirectory.deleteRecursively()
      }
      nextSeqByRunId.clear()
    }
  }

  private fun directoryForRun(runId: String): File =
    File(journalDirectory, "run-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(runId)}")

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
    val runDirectory = directoryForRun(payload.runId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    val seq = nextSeqByRunId[payload.runId] ?: inferNextSeq(runDirectory)
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
    journalFileFor(runDirectory, entry).writeText(
      PersistenceJson.instance.encodeToString(
        serializer = PersistedRunJournalEntry.serializer(),
        value = entry,
      ),
      Charsets.UTF_8,
    )
    return entry
  }

  private fun decodeJournalEntry(file: File): PersistedRunJournalEntry? = runCatching {
    val decoded = PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRunJournalEntry.serializer(),
      string = file.readText(Charsets.UTF_8),
    )
    val normalized = normalizeJournalEntry(decoded)
    if (normalized != decoded) {
      file.writeText(
        PersistenceJson.instance.encodeToString(
          serializer = PersistedRunJournalEntry.serializer(),
          value = normalized,
        ),
        Charsets.UTF_8,
      )
    }
    normalized
  }.getOrNull()

  private companion object {
    private const val JOURNAL_DIRECTORY_NAME = "run-journal"
    private const val FILE_SUFFIX = ".json"
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

private fun normalizeJournalEntry(
  entry: PersistedRunJournalEntry,
): PersistedRunJournalEntry {
  val normalizedMetadata = OpenCrayPromptResumeMetadata.normalizeMetadata(
    metadata = entry.payload.resultMetadata,
    json = PROMPT_CHECKPOINT_JSON,
  )
  return if (normalizedMetadata == entry.payload.resultMetadata) {
    entry
  } else {
    entry.copy(
      payload = entry.payload.copy(resultMetadata = normalizedMetadata),
    )
  }
}

private val PERSISTED_JOURNAL_ENTRY_COMPARATOR = compareBy<PersistedRunJournalEntry>(
  PersistedRunJournalEntry::persistedAtEpochMs,
  PersistedRunJournalEntry::runId,
  PersistedRunJournalEntry::seq,
  PersistedRunJournalEntry::emittedAtEpochMs,
  PersistedRunJournalEntry::eventId,
)

private val PERSISTED_RUN_JOURNAL_ENTRY_COMPARATOR = compareBy<PersistedRunJournalEntry>(
  PersistedRunJournalEntry::persistedAtEpochMs,
  PersistedRunJournalEntry::seq,
  PersistedRunJournalEntry::emittedAtEpochMs,
  PersistedRunJournalEntry::eventId,
)

private val PROMPT_CHECKPOINT_JSON: Json = Json { prettyPrint = false }
