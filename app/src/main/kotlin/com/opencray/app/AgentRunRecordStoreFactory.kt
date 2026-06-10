package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipPlanGraph
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphEdge
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphNode
import com.opencray.runtime.memory.MemoryStewardshipPlanStep
import com.opencray.runtime.memory.MemoryStewardshipPlanStepOutcome
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal interface AgentRunRecordStoreFactory {
  fun forChatSession(sessionId: String): AgentRunRecordStore

  fun knownSessionIds(): List<String> = emptyList()
}

internal interface AgentRunRecordStore {
  fun list(): List<PersistedAgentRunRecord>

  fun upsert(record: PersistedAgentRunRecord)
}

internal data class AgentRunRecordStoreConfig(
  val maxTrackedRuns: Int = 128,
) {
  init {
    require(maxTrackedRuns >= 1) { "AgentRunRecordStoreConfig maxTrackedRuns must be >= 1." }
  }
}

internal class FileBackedAgentRunRecordStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: AgentRunRecordStoreConfig = AgentRunRecordStoreConfig(),
) : AgentRunRecordStoreFactory {
  override fun forChatSession(sessionId: String): AgentRunRecordStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedAgentRunRecordStore(
      storage = DirectoryDurableTextStorage(sessionDirectory),
      lock = lockForAgentRunRecordDirectory(sessionDirectory),
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
    fun fromContext(context: Context): AgentRunRecordStoreFactory =
      FileBackedAgentRunRecordStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): FileBackedAgentRunRecordStoreFactory = fromAgent(pathResolver, agentId)

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): FileBackedAgentRunRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(
      runtimeRootDirectory = rootDirectoryForAgent(pathResolver, agentId),
    )

    internal fun rootDirectoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).runRecordsRoot.toFile()
  }
}

internal fun fileBackedAgentRunRecordStore(
  storage: DurableTextStorage,
  config: AgentRunRecordStoreConfig = AgentRunRecordStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): AgentRunRecordStore = FileBackedAgentRunRecordStore(
  storage = storage,
  lock = Any(),
  config = config,
  clock = clock,
)

@Serializable
internal data class PersistedAgentRunRecord(
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
  val pendingMessageId: String? = null,
  val managedProcessIds: List<String> = emptyList(),
  val detachedTask: AgentTask? = null,
  val lastResult: ExecutionResult? = null,
  val lastEvent: PersistedAgentRunEvent? = null,
) {
  init {
    require(runId.isNotBlank()) { "PersistedAgentRunRecord runId must not be blank." }
    require(taskId.isNotBlank()) { "PersistedAgentRunRecord taskId must not be blank." }
    require(acceptedAtEpochMs >= 0L) { "PersistedAgentRunRecord acceptedAtEpochMs must be >= 0." }
  }
}

@Serializable
internal data class PersistedAgentRunEvent(
  val kind: PersistedAgentRunEventKind,
  val runId: String,
  val taskId: String,
  val executionId: String? = null,
  val executionOrdinal: Int? = null,
  val executionKind: String? = null,
  val turn: Int? = null,
  val emittedAtEpochMs: Long,
  val phase: String? = null,
  val status: String? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val supplementEntryId: String? = null,
  val supplementCheckpoint: String? = null,
  val responseFormat: String? = null,
  val isFinal: Boolean? = null,
  val text: String? = null,
  val stage: String? = null,
  val approvalPhase: String? = null,
  val isHighRisk: Boolean? = null,
  val toolName: String? = null,
  val childRunId: String? = null,
  val childTaskId: String? = null,
  val subAgentLabel: String? = null,
  val subAgentType: String? = null,
  val subAgentContextMode: String? = null,
  val subAgentDepth: Int? = null,
  val subAgentExecutionState: String? = null,
  val subAgentContinuationKind: String? = null,
  val subAgentLiveContext: SubAgentLiveContextSnapshot? = null,
  val subAgentResumable: Boolean? = null,
  val subAgentRequiresUserAction: Boolean? = null,
  val subAgentIsHighRisk: Boolean? = null,
  val toolReason: String? = null,
  val argumentsJson: String? = null,
  val toolStatus: String? = null,
  val content: String? = null,
  val contentPreview: String? = null,
  val resultMetadata: Map<String, String> = emptyMap(),
  val operation: String? = null,
  val query: String? = null,
  val queryTerms: List<String> = emptyList(),
  val resultCount: Int? = null,
  val corpusFileCount: Int? = null,
  val recordIds: List<String> = emptyList(),
  val paths: List<String> = emptyList(),
  val lineRanges: List<String> = emptyList(),
  val path: String? = null,
  val fromLine: Int? = null,
  val returnedLineCount: Int? = null,
  val totalLineCount: Int? = null,
  val writtenRecordIds: List<String> = emptyList(),
  val writtenKinds: List<String> = emptyList(),
  val resolvedRecordIds: List<String> = emptyList(),
  val suppressedRecordIds: List<String> = emptyList(),
  val reopenedRecordIds: List<String> = emptyList(),
  val reaffirmedRecordIds: List<String> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
  val stewardshipPlanSteps: List<PersistedMemoryStewardshipPlanStep> = emptyList(),
  val stewardshipPlanGraph: PersistedMemoryStewardshipPlanGraph = PersistedMemoryStewardshipPlanGraph(),
) {
  init {
    require(runId.isNotBlank()) { "PersistedAgentRunEvent runId must not be blank." }
    require(taskId.isNotBlank()) { "PersistedAgentRunEvent taskId must not be blank." }
    require(emittedAtEpochMs >= 0L) { "PersistedAgentRunEvent emittedAtEpochMs must be >= 0." }
  }
}

@Serializable
internal enum class PersistedAgentRunEventKind {
  LIFECYCLE,
  ASSISTANT_PHASE,
  SUPPLEMENT,
  APPROVAL,
  SUBAGENT,
  TOOL_CALL,
  TOOL_RESULT,
  CHECKPOINT,
  MEMORY_RETRIEVAL,
  MEMORY_WRITE,
  CANCELLATION,
  RECOVERY,
}

private class FileBackedAgentRunRecordStore(
  private val storage: DurableTextStorage,
  private val lock: Any,
  private val config: AgentRunRecordStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : AgentRunRecordStore {
  override fun list(): List<PersistedAgentRunRecord> = synchronized(lock) {
    loadNormalizedRecord().runs
  }

  override fun upsert(record: PersistedAgentRunRecord) {
    synchronized(lock) {
      updateRecord { existing ->
        val normalizedRuns = normalizeRuns(
          existing.runs.filterNot { persisted -> persisted.runId == record.runId } + record,
        )
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          runs = normalizedRuns,
        )
      }
    }
  }

  private fun loadNormalizedRecord(): AgentRunStoreRecord =
    storage.updateRecord(
      name = FILE_NAME,
      serializer = AgentRunStoreRecord.serializer(),
    ) { current ->
      val existing = current ?: AgentRunStoreRecord()
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun updateRecord(update: (AgentRunStoreRecord) -> AgentRunStoreRecord) {
    storage.updateRecord(
      name = FILE_NAME,
      serializer = AgentRunStoreRecord.serializer(),
    ) { current ->
      RecordStorageUpdate(
        value = update(normalizeRecord(current ?: AgentRunStoreRecord())),
        result = Unit,
      )
    }
  }

  private fun normalizeRecord(record: AgentRunStoreRecord): AgentRunStoreRecord {
    val normalizedRuns = normalizeRuns(record.runs)
    return if (normalizedRuns == record.runs) {
      record
    } else {
      record.copy(
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        runs = normalizedRuns,
      )
    }
  }

  private fun normalizeRuns(runs: List<PersistedAgentRunRecord>): List<PersistedAgentRunRecord> {
    val deduped = linkedMapOf<String, PersistedAgentRunRecord>()
    runs
      .sortedWith(
        compareByDescending<PersistedAgentRunRecord>(::recordUpdatedAtEpochMs)
          .thenByDescending(PersistedAgentRunRecord::acceptedAtEpochMs),
      )
      .forEach { run ->
        if (run.runId !in deduped) {
          val normalizedLastResult = run.lastResult?.let { result ->
            val normalizedMetadata = OpenCrayPromptResumeMetadata.normalizeMetadata(
              metadata = result.metadata,
              json = PersistenceJson.instance,
            )
            if (normalizedMetadata == result.metadata) {
              result
            } else {
              result.copy(metadata = normalizedMetadata)
            }
          }
          val normalizedLastEvent = run.lastEvent?.let { event ->
            val normalizedMetadata = OpenCrayPromptResumeMetadata.normalizeMetadata(
              metadata = event.resultMetadata,
              json = PersistenceJson.instance,
            )
            if (normalizedMetadata == event.resultMetadata) {
              event
            } else {
              event.copy(resultMetadata = normalizedMetadata)
            }
          }
          deduped[run.runId] = run.copy(
            pendingMessageId = run.pendingMessageId?.trim()?.takeIf(String::isNotBlank),
            managedProcessIds = run.managedProcessIds
              .map(String::trim)
              .filter(String::isNotBlank)
              .distinct(),
            detachedTask = run.detachedTask?.let { detachedTask ->
              detachedTask.copy(
                input = detachedTask.input.trim(),
                metadata = detachedTask.metadata.entries
                  .mapNotNull { entry ->
                    val normalizedKey = entry.key.trim().takeIf(String::isNotBlank)
                      ?: return@mapNotNull null
                    normalizedKey to entry.value
                  }
                  .toMap(),
              )
            },
            lastResult = normalizedLastResult,
            lastEvent = normalizedLastEvent,
          )
        }
      }
    return deduped.values
      .sortedByDescending(PersistedAgentRunRecord::acceptedAtEpochMs)
      .take(config.maxTrackedRuns)
  }

  private fun recordUpdatedAtEpochMs(record: PersistedAgentRunRecord): Long = maxOf(
    record.acceptedAtEpochMs,
    record.lastResult?.finishedAtEpochMs ?: 0L,
    record.lastEvent?.emittedAtEpochMs ?: 0L,
  )

  @Serializable
  private data class AgentRunStoreRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val runs: List<PersistedAgentRunRecord> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-runs.json"
  }
}

private val AGENT_RUN_RECORD_FILE_LOCKS = ConcurrentHashMap<String, Any>()

private fun lockForAgentRunRecordDirectory(directory: File): Any =
  AGENT_RUN_RECORD_FILE_LOCKS.computeIfAbsent(
    File(directory, "runtime-runs.json").absolutePath,
  ) { Any() }

@Serializable
internal data class PersistedMemoryStewardshipPlanStep(
  val action: String,
  val outcome: String,
  val recordId: String? = null,
  val candidateIndex: Int? = null,
  val producedRecordId: String? = null,
  val reason: String? = null,
)

@Serializable
internal data class PersistedMemoryStewardshipPlanGraph(
  val nodes: List<PersistedMemoryStewardshipPlanGraphNode> = emptyList(),
  val edges: List<PersistedMemoryStewardshipPlanGraphEdge> = emptyList(),
)

@Serializable
internal data class PersistedMemoryStewardshipPlanGraphNode(
  val id: String,
  val kind: String,
  val label: String,
  val action: String? = null,
  val outcome: String? = null,
  val recordId: String? = null,
  val candidateIndex: Int? = null,
  val producedRecordId: String? = null,
  val reason: String? = null,
)

@Serializable
internal data class PersistedMemoryStewardshipPlanGraphEdge(
  val from: String,
  val to: String,
  val kind: String,
)

internal fun OpenCrayAgentRunEvent.toPersistedRecord(): PersistedAgentRunEvent = when (this) {
  is OpenCrayLifecycleEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.LIFECYCLE,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    phase = phase.name,
    status = status?.name,
    errorCode = errorCode,
    errorMessage = errorMessage,
  )
  is OpenCrayAssistantPhaseEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.ASSISTANT_PHASE,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    phase = phase.name,
    responseFormat = responseFormat,
    isFinal = isFinal,
    text = text,
    stage = stage,
    resultMetadata = metadata,
  )
  is OpenCraySupplementEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.SUPPLEMENT,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    supplementEntryId = entryId,
    supplementCheckpoint = checkpoint,
    text = text,
    resultMetadata = metadata,
  )
  is OpenCrayApprovalEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.APPROVAL,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    approvalPhase = phase.name,
    toolName = toolName,
    text = text,
    isHighRisk = isHighRisk,
  )
  is OpenCraySubAgentEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.SUBAGENT,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    phase = phase.name,
    childRunId = childRunId,
    childTaskId = childTaskId,
    subAgentLabel = label,
    subAgentType = subagentType,
    subAgentContextMode = contextMode,
    subAgentDepth = depth,
    subAgentExecutionState = executionState?.wireValue,
    subAgentContinuationKind = continuationKind?.wireValue,
    subAgentLiveContext = liveContext?.takeUnless { it.isEmpty },
    subAgentResumable = resumable,
    subAgentRequiresUserAction = requiresUserAction,
    subAgentIsHighRisk = isHighRisk,
    text = summary,
  )
  is OpenCrayToolCallEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.TOOL_CALL,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = call.toolName,
    toolReason = call.reason,
    argumentsJson = PersistenceJson.instance.encodeToString(JsonObject.serializer(), call.arguments),
  )
  is OpenCrayToolResultEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.TOOL_RESULT,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = call.toolName,
    toolReason = call.reason,
    argumentsJson = PersistenceJson.instance.encodeToString(JsonObject.serializer(), call.arguments),
    toolStatus = result.status.name,
    errorCode = result.errorCode,
    errorMessage = result.errorMessage,
    content = if (result.status == AgentToolResultStatus.SUCCESS) {
      result.content.takeIf(String::isNotBlank)
    } else {
      result.content.take(MAX_PERSISTED_FAILURE_TOOL_CONTENT_CHARS)
    },
    contentPreview = result.content.take(MAX_PERSISTED_TOOL_CONTENT_CHARS),
    resultMetadata = result.metadata,
  )
  is OpenCrayMemoryRetrievalEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.MEMORY_RETRIEVAL,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = toolName,
    operation = operation,
    query = query,
    queryTerms = queryTerms,
    resultCount = resultCount,
    corpusFileCount = corpusFileCount,
    recordIds = recordIds,
    paths = paths,
    lineRanges = lineRanges,
    path = path,
    fromLine = fromLine,
    returnedLineCount = returnedLineCount,
    totalLineCount = totalLineCount,
  )
  is OpenCrayMemoryWriteEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.MEMORY_WRITE,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    writtenRecordIds = writtenRecordIds,
    writtenKinds = writtenKinds,
    resolvedRecordIds = resolvedRecordIds,
    suppressedRecordIds = suppressedRecordIds,
    reopenedRecordIds = reopenedRecordIds,
    reaffirmedRecordIds = reaffirmedRecordIds,
    expiredRecordIds = expiredRecordIds,
    stewardshipPlanSteps = stewardshipPlanSteps.map(MemoryStewardshipPlanStep::toPersisted),
    stewardshipPlanGraph = stewardshipPlanGraph.toPersisted(),
  )
  is OpenCrayCancellationEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.CANCELLATION,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = toolName,
    text = text,
    stage = outcome,
  )
}

internal fun PersistedAgentRunEvent.toRuntimeEventOrNull(): OpenCrayAgentRunEvent? =
  if (
    kind == PersistedAgentRunEventKind.CHECKPOINT ||
    kind == PersistedAgentRunEventKind.RECOVERY
  ) {
    null
  } else {
    toRuntimeEvent()
  }

internal fun PersistedAgentRunEvent.toRuntimeEvent(): OpenCrayAgentRunEvent = when (kind) {
  PersistedAgentRunEventKind.LIFECYCLE -> OpenCrayLifecycleEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    phase = phase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { OpenCrayRunLifecyclePhase.valueOf(raw) }.getOrNull() }
      ?: OpenCrayRunLifecyclePhase.START,
    status = status
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { ExecutionStatus.valueOf(raw) }.getOrNull() },
    errorCode = errorCode,
    errorMessage = errorMessage,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.ASSISTANT_PHASE -> OpenCrayAssistantEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn ?: 0,
    text = text.orEmpty(),
    responseFormat = responseFormat?.trim()?.takeIf(String::isNotBlank),
    isFinal = phase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.equals("FINAL_ANSWER", ignoreCase = true)
      ?: (isFinal == true),
    stage = stage,
    metadata = resultMetadata,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.SUPPLEMENT -> OpenCraySupplementEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn ?: 0,
    entryId = supplementEntryId?.trim()?.takeIf(String::isNotBlank)
      ?: "supplement-$emittedAtEpochMs",
    text = text.orEmpty(),
    checkpoint = supplementCheckpoint?.trim()?.takeIf(String::isNotBlank)
      ?: "turn_start",
    metadata = resultMetadata,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.APPROVAL -> OpenCrayApprovalEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    phase = approvalPhase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { OpenCrayApprovalPhase.valueOf(raw) }.getOrNull() }
      ?: OpenCrayApprovalPhase.REQUIRED,
    toolName = toolName,
    text = text.orEmpty(),
    isHighRisk = isHighRisk ?: false,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.SUBAGENT -> run {
    val restoredPhase = phase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { OpenCraySubAgentPhase.valueOf(raw) }.getOrNull() }
      ?: OpenCraySubAgentPhase.STARTED
    val restoredExecutionState = subAgentExecutionState
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::parseSubAgentExecutionState)
      ?: defaultSubAgentExecutionStateFor(restoredPhase)
    val restoredContinuationKind = subAgentContinuationKind
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::parseSubAgentContinuationKind)
      ?: if (
        restoredExecutionState == SubAgentExecutionState.BACKGROUND_QUEUED ||
        restoredExecutionState == SubAgentExecutionState.BACKGROUND_RUNNING
      ) {
        SubAgentContinuationKind.BACKGROUND_RESUME
      } else {
        SubAgentContinuationKind.NONE
      }

    OpenCraySubAgentEvent(
      runId = runId,
      taskId = taskId,
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
      phase = restoredPhase,
      childRunId = childRunId?.trim()?.takeIf(String::isNotBlank) ?: runId,
      childTaskId = childTaskId?.trim()?.takeIf(String::isNotBlank) ?: taskId,
      label = subAgentLabel?.trim()?.takeIf(String::isNotBlank) ?: "Task",
      subagentType = subAgentType?.trim()?.takeIf(String::isNotBlank) ?: "general-purpose",
      contextMode = subAgentContextMode?.trim()?.takeIf(String::isNotBlank) ?: "delegated",
      depth = subAgentDepth ?: 1,
      summary = text?.trim()?.takeIf(String::isNotBlank),
      executionState = restoredExecutionState,
      continuationKind = restoredContinuationKind,
      liveContext = subAgentLiveContext?.takeUnless { it.isEmpty },
      resumable = subAgentResumable ?: (restoredContinuationKind != SubAgentContinuationKind.NONE),
      requiresUserAction = subAgentRequiresUserAction ?: (
        restoredExecutionState == SubAgentExecutionState.WAITING_APPROVAL ||
          restoredExecutionState == SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL
      ),
      isHighRisk = subAgentIsHighRisk ?: (
        restoredExecutionState == SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL
      ),
      turn = turn,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }
  PersistedAgentRunEventKind.TOOL_CALL -> OpenCrayToolCallEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn ?: 0,
    call = AgentToolCall(
      toolName = toolName?.trim()?.takeIf(String::isNotBlank) ?: "unknown",
      arguments = parseArgumentsJson(argumentsJson),
      reason = toolReason,
    ),
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.TOOL_RESULT -> OpenCrayToolResultEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn ?: 0,
    call = AgentToolCall(
      toolName = toolName?.trim()?.takeIf(String::isNotBlank) ?: "unknown",
      arguments = parseArgumentsJson(argumentsJson),
      reason = toolReason,
    ),
    result = AgentToolResult(
      toolName = toolName?.trim()?.takeIf(String::isNotBlank) ?: "unknown",
      status = toolStatus
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { raw -> runCatching { AgentToolResultStatus.valueOf(raw) }.getOrNull() }
        ?: AgentToolResultStatus.SUCCESS,
      content = content?.takeIf(String::isNotBlank)
        ?: contentPreview?.takeIf(String::isNotBlank)
        ?: "Tool result restored from durable run record.",
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = resultMetadata,
    ),
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.CHECKPOINT -> error(
    "Checkpoint journal entries are recovery markers and do not map to runtime events.",
  )
  PersistedAgentRunEventKind.RECOVERY -> error(
    "Recovery journal entries are diagnostics markers and do not map to runtime events.",
  )
  PersistedAgentRunEventKind.MEMORY_RETRIEVAL -> OpenCrayMemoryRetrievalEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    turn = turn ?: 0,
    toolName = toolName.orEmpty(),
    operation = operation.orEmpty(),
    query = query,
    queryTerms = queryTerms,
    resultCount = resultCount,
    corpusFileCount = corpusFileCount,
    recordIds = recordIds,
    paths = paths,
    lineRanges = lineRanges,
    path = path,
    fromLine = fromLine,
    returnedLineCount = returnedLineCount,
    totalLineCount = totalLineCount,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.MEMORY_WRITE -> OpenCrayMemoryWriteEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    writtenRecordIds = writtenRecordIds,
    writtenKinds = writtenKinds,
    resolvedRecordIds = resolvedRecordIds,
    suppressedRecordIds = suppressedRecordIds,
    reopenedRecordIds = reopenedRecordIds,
    reaffirmedRecordIds = reaffirmedRecordIds,
    expiredRecordIds = expiredRecordIds,
    stewardshipPlanSteps = stewardshipPlanSteps.mapNotNull(PersistedMemoryStewardshipPlanStep::toRuntime),
    stewardshipPlanGraph = stewardshipPlanGraph.toRuntime(),
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.CANCELLATION -> OpenCrayCancellationEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    toolName = toolName,
    outcome = stage,
    text = text.orEmpty(),
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
}

private fun parseSubAgentExecutionState(raw: String): SubAgentExecutionState? =
  SubAgentExecutionState.fromWireValue(raw)

private fun parseSubAgentContinuationKind(raw: String): SubAgentContinuationKind? =
  SubAgentContinuationKind.fromWireValue(raw)

private fun defaultSubAgentExecutionStateFor(
  phase: OpenCraySubAgentPhase,
): SubAgentExecutionState = when (phase) {
  OpenCraySubAgentPhase.STARTED -> SubAgentExecutionState.RUNNING
  OpenCraySubAgentPhase.RESUMED -> SubAgentExecutionState.RUNNING
  OpenCraySubAgentPhase.COMPLETED -> SubAgentExecutionState.COMPLETED
  OpenCraySubAgentPhase.FAILED -> SubAgentExecutionState.FAILED
  OpenCraySubAgentPhase.CANCELLED -> SubAgentExecutionState.CANCELLED
}

private fun parseArgumentsJson(argumentsJson: String?): JsonObject {
  val encoded = argumentsJson?.trim()?.takeIf(String::isNotBlank) ?: return JsonObject(emptyMap())
  return runCatching {
    PersistenceJson.instance.parseToJsonElement(encoded) as? JsonObject
  }.getOrNull() ?: JsonObject(emptyMap())
}

private fun MemoryStewardshipPlanStep.toPersisted(): PersistedMemoryStewardshipPlanStep =
  PersistedMemoryStewardshipPlanStep(
    action = action.wireValue,
    outcome = outcome.wireValue,
    recordId = recordId,
    candidateIndex = candidateIndex,
    producedRecordId = producedRecordId,
    reason = reason,
  )

private fun PersistedMemoryStewardshipPlanStep.toRuntime(): MemoryStewardshipPlanStep? {
  val parsedAction = MemoryStewardshipAction.fromWireValue(action) ?: return null
  val parsedOutcome = MemoryStewardshipPlanStepOutcome.fromWireValue(outcome) ?: return null
  return MemoryStewardshipPlanStep(
    action = parsedAction,
    outcome = parsedOutcome,
    recordId = recordId,
    candidateIndex = candidateIndex,
    producedRecordId = producedRecordId,
    reason = reason,
  )
}

private fun MemoryStewardshipPlanGraph.toPersisted(): PersistedMemoryStewardshipPlanGraph =
  PersistedMemoryStewardshipPlanGraph(
    nodes = nodes.map(MemoryStewardshipPlanGraphNode::toPersisted),
    edges = edges.map(MemoryStewardshipPlanGraphEdge::toPersisted),
  )

private fun MemoryStewardshipPlanGraphNode.toPersisted(): PersistedMemoryStewardshipPlanGraphNode =
  PersistedMemoryStewardshipPlanGraphNode(
    id = id,
    kind = kind,
    label = label,
    action = action,
    outcome = outcome,
    recordId = recordId,
    candidateIndex = candidateIndex,
    producedRecordId = producedRecordId,
    reason = reason,
  )

private fun MemoryStewardshipPlanGraphEdge.toPersisted(): PersistedMemoryStewardshipPlanGraphEdge =
  PersistedMemoryStewardshipPlanGraphEdge(
    from = from,
    to = to,
    kind = kind,
  )

private fun PersistedMemoryStewardshipPlanGraph.toRuntime(): MemoryStewardshipPlanGraph =
  MemoryStewardshipPlanGraph(
    nodes = nodes.map(PersistedMemoryStewardshipPlanGraphNode::toRuntime),
    edges = edges.map(PersistedMemoryStewardshipPlanGraphEdge::toRuntime),
  )

private fun PersistedMemoryStewardshipPlanGraphNode.toRuntime(): MemoryStewardshipPlanGraphNode =
  MemoryStewardshipPlanGraphNode(
    id = id,
    kind = kind,
    label = label,
    action = action,
    outcome = outcome,
    recordId = recordId,
    candidateIndex = candidateIndex,
    producedRecordId = producedRecordId,
    reason = reason,
  )

private fun PersistedMemoryStewardshipPlanGraphEdge.toRuntime(): MemoryStewardshipPlanGraphEdge =
  MemoryStewardshipPlanGraphEdge(
    from = from,
    to = to,
    kind = kind,
  )

private const val MAX_PERSISTED_TOOL_CONTENT_CHARS: Int = 1_024
private const val MAX_PERSISTED_FAILURE_TOOL_CONTENT_CHARS: Int = 16_384
