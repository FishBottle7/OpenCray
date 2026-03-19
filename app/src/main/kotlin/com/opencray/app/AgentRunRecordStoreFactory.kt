package com.opencray.app

import android.content.Context
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal interface AgentRunRecordStoreFactory {
  fun forChatSession(sessionId: String): AgentRunRecordStore
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
      directory = sessionDirectory,
      config = config,
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentRunRecordStoreFactory =
      FileBackedAgentRunRecordStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

@Serializable
internal data class PersistedAgentRunRecord(
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
  val pendingMessageId: String? = null,
  val managedProcessIds: List<String> = emptyList(),
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
  val toolReason: String? = null,
  val argumentsJson: String? = null,
  val toolStatus: String? = null,
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
  val reaffirmedRecordIds: List<String> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
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
  ASSISTANT,
  PROGRESS,
  SUPPLEMENT,
  APPROVAL,
  SUBAGENT,
  TOOL_CALL,
  TOOL_RESULT,
  MEMORY_RETRIEVAL,
  MEMORY_WRITE,
  CANCELLATION,
}

private class FileBackedAgentRunRecordStore(
  directory: File,
  private val config: AgentRunRecordStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : AgentRunRecordStore {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  override fun list(): List<PersistedAgentRunRecord> = synchronized(lock) {
    loadNormalizedRecord().runs
  }

  override fun upsert(record: PersistedAgentRunRecord) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val normalizedRuns = normalizeRuns(
        existing.runs.filterNot { persisted -> persisted.runId == record.runId } + record,
      )
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          runs = normalizedRuns,
        ),
      )
    }
  }

  private fun loadRecord(): AgentRunStoreRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return AgentRunStoreRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = AgentRunStoreRecord.serializer(),
      string = encoded,
    )
  }

  private fun loadNormalizedRecord(): AgentRunStoreRecord {
    val existing = loadRecord()
    val normalizedRuns = normalizeRuns(existing.runs)
    if (normalizedRuns == existing.runs) {
      return existing
    }
    val repaired = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      runs = normalizedRuns,
    )
    saveRecord(repaired)
    return repaired
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
          deduped[run.runId] = run.copy(
            pendingMessageId = run.pendingMessageId?.trim()?.takeIf(String::isNotBlank),
            managedProcessIds = run.managedProcessIds
              .map(String::trim)
              .filter(String::isNotBlank)
              .distinct(),
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

  private fun saveRecord(record: AgentRunStoreRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = AgentRunStoreRecord.serializer(),
        value = record,
      ),
    )
  }

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

internal fun OpenCrayAgentRunEvent.toPersistedRecord(): PersistedAgentRunEvent = when (this) {
  is OpenCrayLifecycleEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.LIFECYCLE,
    runId = runId,
    taskId = taskId,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    phase = phase.name,
    status = status?.name,
    errorCode = errorCode,
    errorMessage = errorMessage,
  )
  is OpenCrayAssistantEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.ASSISTANT,
    runId = runId,
    taskId = taskId,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    responseFormat = responseFormat,
    isFinal = isFinal,
    text = text,
  )
  is OpenCrayProgressEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.PROGRESS,
    runId = runId,
    taskId = taskId,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    text = text,
    stage = stage,
  )
  is OpenCraySupplementEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.SUPPLEMENT,
    runId = runId,
    taskId = taskId,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    supplementEntryId = entryId,
    supplementCheckpoint = checkpoint,
    text = text,
  )
  is OpenCrayApprovalEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.APPROVAL,
    runId = runId,
    taskId = taskId,
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
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    phase = phase.name,
    childRunId = childRunId,
    childTaskId = childTaskId,
    subAgentLabel = label,
    subAgentType = subagentType,
    subAgentContextMode = contextMode,
    subAgentDepth = depth,
    text = summary,
  )
  is OpenCrayToolCallEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.TOOL_CALL,
    runId = runId,
    taskId = taskId,
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
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = call.toolName,
    toolReason = call.reason,
    argumentsJson = PersistenceJson.instance.encodeToString(JsonObject.serializer(), call.arguments),
    toolStatus = result.status.name,
    errorCode = result.errorCode,
    errorMessage = result.errorMessage,
    contentPreview = result.content.take(MAX_PERSISTED_TOOL_CONTENT_CHARS),
    resultMetadata = result.metadata,
  )
  is OpenCrayMemoryRetrievalEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.MEMORY_RETRIEVAL,
    runId = runId,
    taskId = taskId,
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
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    writtenRecordIds = writtenRecordIds,
    writtenKinds = writtenKinds,
    resolvedRecordIds = resolvedRecordIds,
    reaffirmedRecordIds = reaffirmedRecordIds,
    expiredRecordIds = expiredRecordIds,
  )
  is OpenCrayCancellationEvent -> PersistedAgentRunEvent(
    kind = PersistedAgentRunEventKind.CANCELLATION,
    runId = runId,
    taskId = taskId,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
    toolName = toolName,
    text = text,
    stage = outcome,
  )
}

internal fun PersistedAgentRunEvent.toRuntimeEvent(): OpenCrayAgentRunEvent = when (kind) {
  PersistedAgentRunEventKind.LIFECYCLE -> OpenCrayLifecycleEvent(
    runId = runId,
    taskId = taskId,
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
  PersistedAgentRunEventKind.ASSISTANT -> OpenCrayAssistantEvent(
    runId = runId,
    taskId = taskId,
    turn = turn ?: 0,
    text = text.orEmpty(),
    responseFormat = responseFormat.orEmpty(),
    isFinal = isFinal ?: false,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.PROGRESS -> OpenCrayProgressEvent(
    runId = runId,
    taskId = taskId,
    turn = turn ?: 0,
    text = text.orEmpty(),
    stage = stage,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.SUPPLEMENT -> OpenCraySupplementEvent(
    runId = runId,
    taskId = taskId,
    turn = turn ?: 0,
    entryId = supplementEntryId?.trim()?.takeIf(String::isNotBlank)
      ?: "supplement-$emittedAtEpochMs",
    text = text.orEmpty(),
    checkpoint = supplementCheckpoint?.trim()?.takeIf(String::isNotBlank)
      ?: "turn_start",
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.APPROVAL -> OpenCrayApprovalEvent(
    runId = runId,
    taskId = taskId,
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
  PersistedAgentRunEventKind.SUBAGENT -> OpenCraySubAgentEvent(
    runId = runId,
    taskId = taskId,
    phase = phase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { raw -> runCatching { OpenCraySubAgentPhase.valueOf(raw) }.getOrNull() }
      ?: OpenCraySubAgentPhase.STARTED,
    childRunId = childRunId?.trim()?.takeIf(String::isNotBlank) ?: runId,
    childTaskId = childTaskId?.trim()?.takeIf(String::isNotBlank) ?: taskId,
    label = subAgentLabel?.trim()?.takeIf(String::isNotBlank) ?: "Task",
    subagentType = subAgentType?.trim()?.takeIf(String::isNotBlank) ?: "general-purpose",
    contextMode = subAgentContextMode?.trim()?.takeIf(String::isNotBlank) ?: "delegated",
    depth = subAgentDepth ?: 1,
    summary = text?.trim()?.takeIf(String::isNotBlank),
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.TOOL_CALL -> OpenCrayToolCallEvent(
    runId = runId,
    taskId = taskId,
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
      content = contentPreview?.takeIf(String::isNotBlank)
        ?: "Tool result restored from durable run record.",
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = resultMetadata,
    ),
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.MEMORY_RETRIEVAL -> OpenCrayMemoryRetrievalEvent(
    runId = runId,
    taskId = taskId,
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
    writtenRecordIds = writtenRecordIds,
    writtenKinds = writtenKinds,
    resolvedRecordIds = resolvedRecordIds,
    reaffirmedRecordIds = reaffirmedRecordIds,
    expiredRecordIds = expiredRecordIds,
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
  PersistedAgentRunEventKind.CANCELLATION -> OpenCrayCancellationEvent(
    runId = runId,
    taskId = taskId,
    toolName = toolName,
    outcome = stage,
    text = text.orEmpty(),
    turn = turn,
    emittedAtEpochMs = emittedAtEpochMs,
  )
}

private fun parseArgumentsJson(argumentsJson: String?): JsonObject {
  val encoded = argumentsJson?.trim()?.takeIf(String::isNotBlank) ?: return JsonObject(emptyMap())
  return runCatching {
    PersistenceJson.instance.parseToJsonElement(encoded) as? JsonObject
  }.getOrNull() ?: JsonObject(emptyMap())
}

private const val MAX_PERSISTED_TOOL_CONTENT_CHARS: Int = 1_024
