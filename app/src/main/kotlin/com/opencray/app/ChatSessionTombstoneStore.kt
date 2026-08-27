package com.opencray.app

import android.content.Context
import android.util.Log
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import java.io.File

internal interface ChatSessionTombstoneStore {
  fun isTombstoned(sessionId: String): Boolean

  fun tombstone(sessionId: String)
}

internal fun inMemoryChatSessionTombstoneStore(): ChatSessionTombstoneStore =
  InMemoryChatSessionTombstoneStore()

internal class InMemoryChatSessionTombstoneStore : ChatSessionTombstoneStore {
  private val lock = Any()
  private val tombstonedAtEpochMsBySessionId = linkedMapOf<String, Long>()

  override fun isTombstoned(sessionId: String): Boolean = synchronized(lock) {
    sessionId.isNotBlank() && tombstonedAtEpochMsBySessionId.containsKey(sessionId)
  }

  override fun tombstone(sessionId: String) {
    if (sessionId.isBlank()) {
      return
    }
    synchronized(lock) {
      tombstonedAtEpochMsBySessionId[sessionId] = System.currentTimeMillis()
    }
  }
}

internal data class ChatDeletedSessionCleanupDependencies(
  val tombstoneStore: ChatSessionTombstoneStore,
  val queueSnapshotStoreFactory: AgentQueueSnapshotStoreFactory? = null,
  val runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  val processRegistryFactory: AgentProcessRegistryFactory? = null,
  val promptCheckpointStoreFactory: PromptCheckpointStoreFactory? = null,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore? = null,
)

internal fun defaultChatSessionTombstoneStore(context: Context): ChatSessionTombstoneStore =
  FileBackedChatSessionTombstoneStore.fromRootDirectory(
    File(
      context.filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    ),
  )

internal fun defaultDeletedChatSessionCleanupDependencies(
  context: Context,
): ChatDeletedSessionCleanupDependencies = ChatDeletedSessionCleanupDependencies(
  tombstoneStore = defaultChatSessionTombstoneStore(context),
  queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(context),
  runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(context),
  processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(context),
  promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(context),
  scheduledTaskSpecStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(context).create(),
)

internal data class ChatSessionTombstoneStoreConfig(
  val maxTrackedSessions: Int = 256,
) {
  init {
    require(maxTrackedSessions >= 1) { "ChatSessionTombstoneStoreConfig maxTrackedSessions must be >= 1." }
  }
}

internal class FileBackedChatSessionTombstoneStore(
  private val storage: DurableTextStorage,
  private val config: ChatSessionTombstoneStoreConfig = ChatSessionTombstoneStoreConfig(),
  private val clock: () -> Long = System::currentTimeMillis,
) : ChatSessionTombstoneStore {
  private val lock = Any()

  override fun isTombstoned(sessionId: String): Boolean {
    if (sessionId.isBlank()) {
      return false
    }
    synchronized(lock) {
      return loadRecord().sessionIds.any { entry -> entry.sessionId == sessionId }
    }
  }

  override fun tombstone(sessionId: String) {
    if (sessionId.isBlank()) {
      return
    }
    synchronized(lock) {
      updateRecord { existing ->
        val nowEpochMs = clock()
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = nowEpochMs,
            sessionIds = normalizeEntries(
              existing.sessionIds.filterNot { entry -> entry.sessionId == sessionId } +
                PersistedChatSessionTombstoneEntry(
                  sessionId = sessionId,
                  tombstonedAtEpochMs = nowEpochMs,
                ),
            ),
          ),
          result = Unit,
        )
      }
    }
  }

  private fun normalizeEntries(
    entries: List<PersistedChatSessionTombstoneEntry>,
  ): List<PersistedChatSessionTombstoneEntry> = entries
    .filter { entry -> entry.sessionId.isNotBlank() }
    .groupBy(PersistedChatSessionTombstoneEntry::sessionId)
    .values
    .mapNotNull { grouped -> grouped.maxByOrNull(PersistedChatSessionTombstoneEntry::tombstonedAtEpochMs) }
    .sortedByDescending(PersistedChatSessionTombstoneEntry::tombstonedAtEpochMs)
    .take(config.maxTrackedSessions)

  private fun loadRecord(): PersistedChatSessionTombstoneRecord =
    storage.updateRecord(
      name = TOMBSTONE_STORE_FILE_NAME,
      serializer = PersistedChatSessionTombstoneRecord.serializer(),
    ) { current ->
      val existing = current ?: PersistedChatSessionTombstoneRecord()
      RecordStorageUpdate(
        value = existing,
        result = existing,
        write = false,
      )
    }

  private fun <T> updateRecord(
    update: (PersistedChatSessionTombstoneRecord) ->
      RecordStorageUpdate<PersistedChatSessionTombstoneRecord, T>,
  ): T = storage.updateRecord(
    name = TOMBSTONE_STORE_FILE_NAME,
    serializer = PersistedChatSessionTombstoneRecord.serializer(),
  ) { current ->
    update(current ?: PersistedChatSessionTombstoneRecord())
  }

  companion object {
    internal fun fromRootDirectory(runtimeRootDirectory: File): ChatSessionTombstoneStore =
      FileBackedChatSessionTombstoneStore(
        storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      )
  }
}

internal fun tombstoneGuardDebug(message: String) {
  runCatching { Log.d(CHAT_DELETED_SESSION_DIAG_TAG, message) }
}

internal const val CHAT_DELETED_SESSION_DIAG_TAG: String = "OpenCrayDiag"

internal class TombstoneGuardedRunEventJournalStore(
  private val delegate: RunEventJournalStore,
  private val sessionId: String,
  private val tombstones: ChatSessionTombstoneStore,
  private val clock: () -> Long = System::currentTimeMillis,
) : RunEventJournalStore {
  override fun append(event: OpenCrayAgentRunEvent): PersistedRunJournalEntry {
    if (blocked(operation = "journalAppend", runId = event.runId)) {
      return droppedEntry(payload = event.toPersistedRecord())
    }
    return delegate.append(event)
  }

  override fun appendCheckpoint(
    runId: String,
    taskId: String,
    emission: OpenCrayPromptCheckpointEmission,
  ): PersistedRunJournalEntry {
    if (blocked(operation = "journalAppendCheckpoint", runId = runId)) {
      return droppedEntry(
        payload = PersistedAgentRunEvent(
          kind = PersistedAgentRunEventKind.CHECKPOINT,
          runId = runId,
          taskId = taskId,
          emittedAtEpochMs = emission.emittedAtEpochMs,
        ),
      )
    }
    return delegate.appendCheckpoint(runId, taskId, emission)
  }

  override fun appendRecovery(
    runId: String,
    taskId: String,
    emittedAtEpochMs: Long,
    metadata: Map<String, String>,
  ): PersistedRunJournalEntry {
    if (blocked(operation = "journalAppendRecovery", runId = runId)) {
      return droppedEntry(
        payload = PersistedAgentRunEvent(
          kind = PersistedAgentRunEventKind.RECOVERY,
          runId = runId,
          taskId = taskId,
          emittedAtEpochMs = emittedAtEpochMs,
          resultMetadata = metadata.filterValues { value -> value.isNotBlank() },
        ),
      )
    }
    return delegate.appendRecovery(runId, taskId, emittedAtEpochMs, metadata)
  }

  override fun hasEntries(): Boolean = delegate.hasEntries()

  override fun list(): List<PersistedRunJournalEntry> = delegate.list()

  override fun listForRun(runId: String): List<PersistedRunJournalEntry> =
    delegate.listForRun(runId)

  override fun clear() = delegate.clear()

  private fun blocked(operation: String, runId: String?): Boolean {
    if (!tombstones.isTombstoned(sessionId)) {
      return false
    }
    tombstoneGuardDebug(
      "chat.deletedSessionDrop session=$sessionId operation=$operation run=${runId ?: "-"}",
    )
    return true
  }

  private fun droppedEntry(payload: PersistedAgentRunEvent): PersistedRunJournalEntry =
    PersistedRunJournalEntry(
      sessionId = sessionId,
      runId = payload.runId,
      taskId = payload.taskId,
      seq = 1L,
      eventId = payload.eventId ?: "event-${clock()}-dropped",
      kind = payload.kind,
      emittedAtEpochMs = payload.emittedAtEpochMs,
      persistedAtEpochMs = clock(),
      payload = payload,
    )
}

internal class TombstoneGuardedRunEventJournalStoreFactory(
  private val delegate: RunEventJournalStoreFactory,
  private val tombstones: ChatSessionTombstoneStore,
) : RunEventJournalStoreFactory {
  override fun forChatSession(sessionId: String): RunEventJournalStore =
    TombstoneGuardedRunEventJournalStore(
      delegate = delegate.forChatSession(sessionId),
      sessionId = sessionId,
      tombstones = tombstones,
    )

  override fun knownSessionIds(): List<String> = delegate.knownSessionIds()
}

internal class TombstoneGuardedAgentRunRecordStore(
  private val delegate: AgentRunRecordStore,
  private val sessionId: String,
  private val tombstones: ChatSessionTombstoneStore,
) : AgentRunRecordStore {
  override fun list(): List<PersistedAgentRunRecord> = delegate.list()

  override fun upsert(record: PersistedAgentRunRecord) {
    if (!tombstones.isTombstoned(sessionId)) {
      delegate.upsert(record)
      return
    }
    tombstoneGuardDebug(
      "chat.deletedSessionDrop session=$sessionId operation=runRecordUpsert run=${record.runId}",
    )
  }

  override fun clear() = delegate.clear()
}

internal class TombstoneGuardedQueueSnapshotStore(
  private val delegate: SessionQueueSnapshotStore,
  private val sessionId: String,
  private val tombstones: ChatSessionTombstoneStore,
) : SessionQueueSnapshotStore {
  override fun load(): SessionQueueSnapshot? = delegate.load()

  override fun save(snapshot: SessionQueueSnapshot) {
    if (!tombstones.isTombstoned(sessionId)) {
      delegate.save(snapshot)
      return
    }
    tombstoneGuardDebug(
      "chat.deletedSessionDrop session=$sessionId operation=queueSnapshotPersist tasks=${snapshot.tasks.size}",
    )
  }

  override fun clear() = delegate.clear()
}

internal class TombstoneGuardedAgentQueueSnapshotStoreFactory(
  private val delegate: AgentQueueSnapshotStoreFactory,
  private val tombstones: ChatSessionTombstoneStore,
) : AgentQueueSnapshotStoreFactory {
  override fun forChatSession(sessionId: String): SessionQueueSnapshotStore =
    TombstoneGuardedQueueSnapshotStore(delegate.forChatSession(sessionId), sessionId, tombstones)

  override fun knownSessionIds(): List<String> = delegate.knownSessionIds()
}

@kotlinx.serialization.Serializable
private data class PersistedChatSessionTombstoneEntry(
  val sessionId: String,
  val tombstonedAtEpochMs: Long,
)

@kotlinx.serialization.Serializable
private data class PersistedChatSessionTombstoneRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val sessionIds: List<PersistedChatSessionTombstoneEntry> = emptyList(),
)

private const val TOMBSTONE_STORE_FILE_NAME: String = "chat-session-tombstones-v1.json"
