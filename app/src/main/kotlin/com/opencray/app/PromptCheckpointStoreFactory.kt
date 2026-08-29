package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

internal interface PromptCheckpointStoreFactory {
  fun forChatSession(sessionId: String): PromptCheckpointStore

  fun knownSessionIds(): List<String> = emptyList()
}

internal interface PromptCheckpointStore {
  fun list(): List<PersistedPromptCheckpoint>

  fun get(taskId: String): PersistedPromptCheckpoint?

  fun consume(
    taskId: String,
    checkpointKinds: Set<PromptCheckpointKind>,
  ): PersistedPromptCheckpoint?

  fun upsert(checkpoint: PersistedPromptCheckpoint)

  fun remove(taskId: String)

  fun retainKnownTasks(taskIds: Set<String>)

  fun clear()
}

internal fun inMemoryPromptCheckpointStoreFactory(): PromptCheckpointStoreFactory =
  InMemoryPromptCheckpointStoreFactory()

internal class InMemoryPromptCheckpointStoreFactory : PromptCheckpointStoreFactory {
  private val lock = Any()
  private val stores = linkedMapOf<String, PromptCheckpointStore>()

  override fun forChatSession(sessionId: String): PromptCheckpointStore = synchronized(lock) {
    stores.getOrPut(sessionId) { InMemoryPromptCheckpointStore(sessionId = sessionId) }
  }

  override fun knownSessionIds(): List<String> = synchronized(lock) {
    stores.keys.toList()
  }
}

internal class FileBackedPromptCheckpointStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: PromptCheckpointStoreConfig = PromptCheckpointStoreConfig(),
) : PromptCheckpointStoreFactory {
  override fun forChatSession(sessionId: String): PromptCheckpointStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedPromptCheckpointStore(
      sessionId = sessionId,
      storage = DirectoryDurableTextStorage(sessionDirectory),
      lock = lockForPromptCheckpointDirectory(sessionDirectory),
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
    fun fromContext(context: Context): PromptCheckpointStoreFactory =
      FileBackedPromptCheckpointStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal fun fileBackedPromptCheckpointStore(
  sessionId: String,
  storage: DurableTextStorage,
  config: PromptCheckpointStoreConfig = PromptCheckpointStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): PromptCheckpointStore = FileBackedPromptCheckpointStore(
  sessionId = sessionId,
  storage = storage,
  lock = Any(),
  config = config,
  clock = clock,
)

internal data class PromptCheckpointStoreConfig(
  val maxTrackedCheckpoints: Int = 128,
) {
  init {
    require(maxTrackedCheckpoints >= 1) {
      "PromptCheckpointStoreConfig maxTrackedCheckpoints must be >= 1."
    }
  }
}

@Serializable
internal enum class PromptCheckpointKind {
  GENERAL_RESUME,
  PRE_MODEL_REQUEST,
  ACTION_BATCH_PARSED,
  COMMENTARY_EMITTED,
  TOOL_RESULT_COMMITTED,
  SUPPLEMENT_INGESTED,
  FINALIZATION_COMPLETE,
  WAITING_APPROVAL,
  APPROVED_PENDING_RESUME,
  REJECTED_PENDING_RESUME,
}

internal fun PromptCheckpointKind.isCheckpointResumeKind(): Boolean = when (this) {
  PromptCheckpointKind.GENERAL_RESUME,
  PromptCheckpointKind.PRE_MODEL_REQUEST,
  PromptCheckpointKind.ACTION_BATCH_PARSED,
  PromptCheckpointKind.COMMENTARY_EMITTED,
  PromptCheckpointKind.TOOL_RESULT_COMMITTED,
  PromptCheckpointKind.SUPPLEMENT_INGESTED,
  PromptCheckpointKind.APPROVED_PENDING_RESUME,
  -> true

  PromptCheckpointKind.FINALIZATION_COMPLETE,
  PromptCheckpointKind.WAITING_APPROVAL,
  PromptCheckpointKind.REJECTED_PENDING_RESUME,
  -> false
}

internal fun PromptCheckpointKind.isGeneralPromptResumeKind(): Boolean = when (this) {
  PromptCheckpointKind.GENERAL_RESUME,
  PromptCheckpointKind.PRE_MODEL_REQUEST,
  PromptCheckpointKind.ACTION_BATCH_PARSED,
  PromptCheckpointKind.COMMENTARY_EMITTED,
  PromptCheckpointKind.TOOL_RESULT_COMMITTED,
  PromptCheckpointKind.SUPPLEMENT_INGESTED,
  -> true

  PromptCheckpointKind.FINALIZATION_COMPLETE,
  PromptCheckpointKind.WAITING_APPROVAL,
  PromptCheckpointKind.APPROVED_PENDING_RESUME,
  PromptCheckpointKind.REJECTED_PENDING_RESUME,
  -> false
}

internal fun PromptCheckpointKind.toRuntimeCheckpointBoundaryOrNull():
  OpenCrayPromptCheckpointBoundary? = when (this) {
  PromptCheckpointKind.PRE_MODEL_REQUEST -> OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST
  PromptCheckpointKind.ACTION_BATCH_PARSED -> OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED
  PromptCheckpointKind.COMMENTARY_EMITTED -> OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED
  PromptCheckpointKind.TOOL_RESULT_COMMITTED -> OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED
  PromptCheckpointKind.SUPPLEMENT_INGESTED -> OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED
  PromptCheckpointKind.FINALIZATION_COMPLETE ->
    OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE
  PromptCheckpointKind.GENERAL_RESUME,
  PromptCheckpointKind.WAITING_APPROVAL,
  PromptCheckpointKind.APPROVED_PENDING_RESUME,
  PromptCheckpointKind.REJECTED_PENDING_RESUME,
  -> null
}

@Serializable
internal data class PersistedPromptCheckpoint(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val checkpointId: String,
  val checkpointKind: PromptCheckpointKind,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val toolName: String? = null,
  val pendingMessageId: String? = null,
  val isHighRisk: Boolean = false,
  val promptCheckpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
  val subAgentApprovedToolName: String? = null,
  val subAgentPromptResumeState: com.opencray.runtime.OpenCrayPromptResumeState? = null,
  val subAgentIsHighRisk: Boolean? = null,
  val subAgentAgentId: String? = null,
  val subAgentChildRunId: String? = null,
  val subAgentChildTaskId: String? = null,
  val approvedRequestFingerprint: String? = null,
) {
  init {
    require(sessionId.isNotBlank()) { "PersistedPromptCheckpoint sessionId must not be blank." }
    require(runId.isNotBlank()) { "PersistedPromptCheckpoint runId must not be blank." }
    require(taskId.isNotBlank()) { "PersistedPromptCheckpoint taskId must not be blank." }
    require(checkpointId.isNotBlank()) { "PersistedPromptCheckpoint checkpointId must not be blank." }
    require(createdAtEpochMs >= 0L) { "PersistedPromptCheckpoint createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "PersistedPromptCheckpoint updatedAtEpochMs must be >= 0." }
    require(subAgentAgentId == null || subAgentAgentId.isNotBlank()) {
      "PersistedPromptCheckpoint subAgentAgentId must not be blank."
    }
    require(subAgentChildRunId == null || subAgentChildRunId.isNotBlank()) {
      "PersistedPromptCheckpoint subAgentChildRunId must not be blank."
    }
    require(subAgentChildTaskId == null || subAgentChildTaskId.isNotBlank()) {
      "PersistedPromptCheckpoint subAgentChildTaskId must not be blank."
    }
  }
}

internal fun PersistedPromptCheckpoint.runtimeCheckpointBoundaryOrNull(): OpenCrayPromptCheckpointBoundary? =
  promptCheckpointBoundary ?: checkpointKind.toRuntimeCheckpointBoundaryOrNull()

internal fun PersistedPromptCheckpoint.toApprovalGrantOrNull(): AgentTaskApprovalGrant? {
  if (checkpointKind != PromptCheckpointKind.APPROVED_PENDING_RESUME) {
    return null
  }
  return AgentTaskApprovalGrant(
    taskId = taskId,
    toolName = toolName,
    promptCheckpointBoundary = runtimeCheckpointBoundaryOrNull(),
    promptResumeState = promptResumeState,
    subAgentApprovalResume = restoredSubAgentApprovalResume(),
    commandApprovalToken = approvedRequestFingerprint
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { fingerprint ->
        com.opencray.runtime.CommandApprovalToken(
          tokenId = UUID.randomUUID().toString(),
          taskId = taskId,
          approvedAtEpochMs = createdAtEpochMs,
          approvedRequestFingerprint = fingerprint,
        )
      },
  )
}

internal fun PersistedPromptCheckpoint.toApprovalRejectionOrNull(): AgentTaskApprovalRejection? {
  if (checkpointKind != PromptCheckpointKind.REJECTED_PENDING_RESUME) {
    return null
  }
  return AgentTaskApprovalRejection(
    taskId = taskId,
    toolName = toolName,
    promptCheckpointBoundary = runtimeCheckpointBoundaryOrNull(),
    promptResumeState = promptResumeState,
    subAgentApprovalResume = restoredSubAgentApprovalResume(),
  )
}

private fun PersistedPromptCheckpoint.restoredSubAgentApprovalResume():
  com.opencray.runtime.subagent.SubAgentApprovalResume? {
  val approvedToolName = subAgentApprovedToolName?.trim()?.takeIf(String::isNotBlank) ?: return null
  val promptResumeState = subAgentPromptResumeState ?: return null
  return com.opencray.runtime.subagent.SubAgentApprovalResume(
    approvedToolName = approvedToolName,
    promptResumeState = promptResumeState,
    isHighRisk = subAgentIsHighRisk == true,
    agentId = subAgentAgentId?.trim()?.takeIf(String::isNotBlank),
    childRunId = subAgentChildRunId?.trim()?.takeIf(String::isNotBlank),
    childTaskId = subAgentChildTaskId?.trim()?.takeIf(String::isNotBlank),
  )
}

private class InMemoryPromptCheckpointStore(
  private val sessionId: String,
) : PromptCheckpointStore {
  private val lock = Any()
  private val checkpointsByTaskId = linkedMapOf<String, PersistedPromptCheckpoint>()

  override fun list(): List<PersistedPromptCheckpoint> = synchronized(lock) {
    checkpointsByTaskId.values
      .sortedByDescending(PersistedPromptCheckpoint::updatedAtEpochMs)
  }

  override fun get(taskId: String): PersistedPromptCheckpoint? = synchronized(lock) {
    checkpointsByTaskId[taskId]
  }

  override fun consume(
    taskId: String,
    checkpointKinds: Set<PromptCheckpointKind>,
  ): PersistedPromptCheckpoint? = synchronized(lock) {
    val checkpoint = checkpointsByTaskId[taskId] ?: return null
    if (checkpoint.checkpointKind !in checkpointKinds) {
      return null
    }
    checkpointsByTaskId.remove(taskId)
    checkpoint
  }

  override fun upsert(checkpoint: PersistedPromptCheckpoint) {
    require(checkpoint.sessionId == sessionId) {
      "Prompt checkpoint session mismatch: expected $sessionId but was ${checkpoint.sessionId}."
    }
    synchronized(lock) {
      checkpointsByTaskId[checkpoint.taskId] = checkpoint
    }
  }

  override fun remove(taskId: String) {
    synchronized(lock) {
      checkpointsByTaskId.remove(taskId)
    }
  }

  override fun retainKnownTasks(taskIds: Set<String>) {
    synchronized(lock) {
      checkpointsByTaskId.keys.retainAll(taskIds)
    }
  }

  override fun clear() {
    synchronized(lock) {
      checkpointsByTaskId.clear()
    }
  }
}

private class FileBackedPromptCheckpointStore(
  private val sessionId: String,
  private val storage: DurableTextStorage,
  private val lock: Any,
  private val config: PromptCheckpointStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : PromptCheckpointStore {
  override fun list(): List<PersistedPromptCheckpoint> = synchronized(lock) {
    loadNormalizedRecord().checkpoints
  }

  override fun get(taskId: String): PersistedPromptCheckpoint? = synchronized(lock) {
    loadNormalizedRecord().checkpoints.firstOrNull { checkpoint -> checkpoint.taskId == taskId }
  }

  override fun consume(
    taskId: String,
    checkpointKinds: Set<PromptCheckpointKind>,
  ): PersistedPromptCheckpoint? = synchronized(lock) {
    updateRecord { existing ->
      val checkpoint = existing.checkpoints.firstOrNull { persisted ->
        persisted.taskId == taskId && persisted.checkpointKind in checkpointKinds
      } ?: return@updateRecord RecordStorageUpdate(
        value = existing,
        result = null,
        write = false,
      )
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          checkpoints = existing.checkpoints.filterNot { persisted -> persisted.taskId == taskId },
        ),
        result = checkpoint,
      )
    }
  }

  override fun upsert(checkpoint: PersistedPromptCheckpoint) {
    require(checkpoint.sessionId == sessionId) {
      "Prompt checkpoint session mismatch: expected $sessionId but was ${checkpoint.sessionId}."
    }
    synchronized(lock) {
      updateRecord { existing ->
        val normalizedCheckpoints = normalizeCheckpoints(
          existing.checkpoints.filterNot { persisted -> persisted.taskId == checkpoint.taskId } + checkpoint,
        )
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            checkpoints = normalizedCheckpoints,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun remove(taskId: String) {
    synchronized(lock) {
      updateRecord { existing ->
        if (existing.checkpoints.none { checkpoint -> checkpoint.taskId == taskId }) {
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
            checkpoints = existing.checkpoints.filterNot { checkpoint -> checkpoint.taskId == taskId },
          ),
          result = Unit,
        )
      }
    }
  }

  override fun retainKnownTasks(taskIds: Set<String>) {
    synchronized(lock) {
      updateRecord { existing ->
        val retained = existing.checkpoints.filter { checkpoint -> checkpoint.taskId in taskIds }
        if (retained.size == existing.checkpoints.size) {
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
            checkpoints = retained,
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

  private fun loadNormalizedRecord(): PromptCheckpointRecord =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = PromptCheckpointRecord.serializer(),
    ) { current ->
      val existing = current ?: PromptCheckpointRecord()
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun <T> updateRecord(
    update: (PromptCheckpointRecord) -> RecordStorageUpdate<PromptCheckpointRecord, T>,
  ): T =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = PromptCheckpointRecord.serializer(),
    ) { current ->
      update(normalizeRecord(current ?: PromptCheckpointRecord()))
    }

  private fun normalizeRecord(record: PromptCheckpointRecord): PromptCheckpointRecord {
    val normalizedCheckpoints = normalizeCheckpoints(record.checkpoints)
    return if (normalizedCheckpoints == record.checkpoints) {
      record
    } else {
      record.copy(
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        checkpoints = normalizedCheckpoints,
      )
    }
  }

  private fun normalizeCheckpoints(
    checkpoints: List<PersistedPromptCheckpoint>,
  ): List<PersistedPromptCheckpoint> {
    val deduped = linkedMapOf<String, PersistedPromptCheckpoint>()
    checkpoints
      .sortedWith(
        compareByDescending<PersistedPromptCheckpoint>(PersistedPromptCheckpoint::updatedAtEpochMs)
          .thenByDescending(PersistedPromptCheckpoint::createdAtEpochMs),
      )
      .forEach { checkpoint ->
        if (checkpoint.taskId !in deduped) {
          val normalizedPromptResumeState = checkpoint.promptResumeState?.let { state ->
            OpenCrayPromptResumeMetadata.normalizeState(
              state = state,
              json = PersistenceJson.instance,
            )
          }
          val normalizedSubAgentPromptResumeState = checkpoint.subAgentPromptResumeState?.let { state ->
            OpenCrayPromptResumeMetadata.normalizeState(
              state = state,
              json = PersistenceJson.instance,
            )
          }
          deduped[checkpoint.taskId] = checkpoint.copy(
            toolName = checkpoint.toolName?.trim()?.takeIf(String::isNotBlank),
            pendingMessageId = checkpoint.pendingMessageId?.trim()?.takeIf(String::isNotBlank),
            promptResumeState = normalizedPromptResumeState,
            subAgentApprovedToolName = checkpoint.subAgentApprovedToolName
              ?.trim()
              ?.takeIf(String::isNotBlank),
            subAgentPromptResumeState = normalizedSubAgentPromptResumeState,
            subAgentAgentId = checkpoint.subAgentAgentId?.trim()?.takeIf(String::isNotBlank),
            subAgentChildRunId = checkpoint.subAgentChildRunId?.trim()?.takeIf(String::isNotBlank),
            subAgentChildTaskId = checkpoint.subAgentChildTaskId?.trim()?.takeIf(String::isNotBlank),
          )
        }
      }
    return deduped.values
      .sortedByDescending(PersistedPromptCheckpoint::updatedAtEpochMs)
      .take(config.maxTrackedCheckpoints)
  }

  @Serializable
  private data class PromptCheckpointRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val checkpoints: List<PersistedPromptCheckpoint> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-prompt-checkpoints.json"
  }
}

private val PROMPT_CHECKPOINT_FILE_LOCKS = ConcurrentHashMap<String, Any>()

private fun lockForPromptCheckpointDirectory(directory: File): Any =
  PROMPT_CHECKPOINT_FILE_LOCKS.computeIfAbsent(
    File(directory, "runtime-prompt-checkpoints.json").absolutePath,
  ) { Any() }
