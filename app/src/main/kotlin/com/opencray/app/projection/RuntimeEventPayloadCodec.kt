package com.opencray.app.projection

import com.opencray.app.stewardshipPlanGraphToMap
import com.opencray.app.stewardshipPlanStepToMap
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.app.runtimeEventStableId

internal const val MAX_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240

internal fun runtimeEventToMap(
  event: OpenCrayAgentRunEvent,
  hasPromptResumeCheckpointMetadata: (Map<String, String>) -> Boolean,
  supplementMetadataSnapshot: (Map<String, String>) -> Map<String, String>,
  toolResultMetadataSnapshot: (Map<String, String>) -> Map<String, String>,
): Map<String, Any?> =
  runtimeEventPayload(
    event = event,
    hasPromptResumeCheckpointMetadata = hasPromptResumeCheckpointMetadata,
    supplementMetadataSnapshot = supplementMetadataSnapshot,
    toolResultMetadataSnapshot = toolResultMetadataSnapshot,
  ).toMutableMap().apply {
    put("eventId", runtimeEventStableId(event))
  }

internal fun runtimeEventPayload(
  event: OpenCrayAgentRunEvent,
  hasPromptResumeCheckpointMetadata: (Map<String, String>) -> Boolean,
  supplementMetadataSnapshot: (Map<String, String>) -> Map<String, String>,
  toolResultMetadataSnapshot: (Map<String, String>) -> Map<String, String>,
): Map<String, Any?> = when (event) {
  is OpenCrayLifecycleEvent -> mapOf(
    "kind" to "lifecycle",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "phase" to event.phase.name.lowercase(),
    "status" to event.status?.name?.lowercase(),
    "errorCode" to event.errorCode,
    "errorMessage" to event.errorMessage,
  )
  is OpenCrayAssistantPhaseEvent -> buildMap<String, Any?> {
    put("kind", "assistant_phase")
    put("runId", event.runId)
    put("taskId", event.taskId)
    put("executionId", event.executionId)
    put("executionOrdinal", event.executionOrdinal)
    put("executionKind", event.executionKind)
    put("turn", event.turn)
    put("emittedAtEpochMs", event.emittedAtEpochMs)
    put("phase", event.phase.name.lowercase())
    put("responseFormat", event.responseFormat)
    put("isFinal", event.isFinal)
    put("stage", event.stage)
    put("text", event.text)
    if (hasPromptResumeCheckpointMetadata(event.metadata)) {
      put("hasResumeCheckpointMetadata", true)
    }
  }
  is OpenCraySupplementEvent -> buildMap<String, Any?> {
    put("kind", "supplement")
    put("runId", event.runId)
    put("taskId", event.taskId)
    put("executionId", event.executionId)
    put("executionOrdinal", event.executionOrdinal)
    put("executionKind", event.executionKind)
    put("turn", event.turn)
    put("emittedAtEpochMs", event.emittedAtEpochMs)
    put("entryId", event.entryId)
    put("text", event.text)
    put("checkpoint", event.checkpoint)
    val metadataSnapshot = supplementMetadataSnapshot(event.metadata)
    if (metadataSnapshot.isNotEmpty()) {
      put("metadata", metadataSnapshot)
    }
    if (hasPromptResumeCheckpointMetadata(event.metadata)) {
      put("hasResumeCheckpointMetadata", true)
    }
  }
  is OpenCrayApprovalEvent -> mapOf(
    "kind" to if (event.phase == OpenCrayApprovalPhase.REQUIRED) "approval_wait" else "approval_result",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "toolName" to event.toolName,
    "text" to event.text,
    "stage" to event.phase.name.lowercase(),
    "status" to event.phase.name.lowercase(),
    "isHighRisk" to event.isHighRisk,
  )
  is OpenCraySubAgentEvent -> mapOf(
    "kind" to "subagent",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "agentId" to event.agentId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "phase" to event.phase.name.lowercase(),
    "status" to event.executionState?.wireValue,
    "childRunId" to event.childRunId,
    "childTaskId" to event.childTaskId,
    "label" to event.label,
    "subagentType" to event.subagentType,
    "contextMode" to event.contextMode,
    "depth" to event.depth,
    "executionState" to event.executionState?.wireValue,
    "continuationKind" to event.continuationKind?.wireValue,
    "resumable" to event.resumable,
    "requiresUserAction" to event.requiresUserAction,
    "isHighRisk" to event.isHighRisk,
    "closed" to event.closed,
    "text" to event.summary,
  )
  is OpenCrayToolCallEvent -> mapOf(
    "kind" to "tool_call",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "toolName" to event.call.toolName,
    "toolReason" to event.call.reason,
    "argumentsJson" to event.call.arguments.toString(),
  )
  is OpenCrayToolResultEvent -> mapOf(
    "kind" to "tool_result",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "toolName" to event.call.toolName,
    "toolStatus" to event.result.status.name.lowercase(),
    "errorCode" to event.result.errorCode,
    "errorMessage" to event.result.errorMessage,
    "content" to toolResultDetailedContentSnapshot(event.result),
    "contentPreview" to event.result.content.take(MAX_RUNTIME_EVENT_PREVIEW_CHARS),
    "resultMetadata" to toolResultMetadataSnapshot(event.result.metadata),
  )
  is OpenCrayMemoryRetrievalEvent -> buildMap<String, Any?> {
    put("kind", "memory_retrieval")
    put("runId", event.runId)
    put("taskId", event.taskId)
    put("executionId", event.executionId)
    put("executionOrdinal", event.executionOrdinal)
    put("executionKind", event.executionKind)
    put("turn", event.turn)
    put("emittedAtEpochMs", event.emittedAtEpochMs)
    put("toolName", event.toolName)
    put("operation", event.operation)
    event.query?.let { query -> put("query", query) }
    if (event.queryTerms.isNotEmpty()) {
      put("queryTerms", event.queryTerms)
    }
    event.resultCount?.let { resultCount -> put("resultCount", resultCount) }
    event.corpusFileCount?.let { corpusFileCount -> put("corpusFileCount", corpusFileCount) }
    if (event.recordIds.isNotEmpty()) {
      put("recordIds", event.recordIds)
    }
    if (event.paths.isNotEmpty()) {
      put("paths", event.paths)
    }
    if (event.lineRanges.isNotEmpty()) {
      put("lineRanges", event.lineRanges)
    }
    event.path?.let { path -> put("path", path) }
    event.fromLine?.let { fromLine -> put("fromLine", fromLine) }
    event.returnedLineCount?.let { returnedLineCount -> put("returnedLineCount", returnedLineCount) }
    event.totalLineCount?.let { totalLineCount -> put("totalLineCount", totalLineCount) }
  }
  is OpenCrayMemoryWriteEvent -> mapOf(
    "kind" to "memory_write",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "writtenRecordIds" to event.writtenRecordIds,
    "writtenKinds" to event.writtenKinds,
    "resolvedRecordIds" to event.resolvedRecordIds,
    "suppressedRecordIds" to event.suppressedRecordIds,
    "reopenedRecordIds" to event.reopenedRecordIds,
    "reaffirmedRecordIds" to event.reaffirmedRecordIds,
    "expiredRecordIds" to event.expiredRecordIds,
    "stewardshipPlanSteps" to event.stewardshipPlanSteps.map(::stewardshipPlanStepToMap),
    "stewardshipPlanGraph" to stewardshipPlanGraphToMap(event.stewardshipPlanGraph),
  )
  is OpenCrayCancellationEvent -> mapOf(
    "kind" to "interrupted",
    "runId" to event.runId,
    "taskId" to event.taskId,
    "executionId" to event.executionId,
    "executionOrdinal" to event.executionOrdinal,
    "executionKind" to event.executionKind,
    "turn" to event.turn,
    "emittedAtEpochMs" to event.emittedAtEpochMs,
    "toolName" to event.toolName,
    "text" to event.text,
    "stage" to event.outcome,
    "status" to event.outcome,
  )
}

internal fun assignRuntimeRealtimeEnvelope(
  sessionId: String,
  payload: Map<String, Any?>,
  streamInstanceId: String,
  nextSequence: () -> Long,
  skipWhenSessionIdBlank: Boolean = false,
  fallbackExecutionIdToRunId: Boolean = false,
): Map<String, Any?> {
  if (skipWhenSessionIdBlank && sessionId.isBlank()) {
    return payload
  }
  val sequence = nextSequence()
  return payload.toMutableMap().apply {
    put("streamInstanceId", streamInstanceId)
    put("sequence", sequence)
    put("lastSequence", sequence)
    put("eventId", runtimeRealtimeEnvelopeEventId(sessionId, sequence, streamInstanceId))
    if (fallbackExecutionIdToRunId) {
      put("executionId", payload["executionId"] ?: payload["runId"])
    }
  }
}

internal fun runtimeRealtimeEnvelopeEventId(
  sessionId: String,
  sequence: Long,
  streamInstanceId: String,
): String =
  "runtime-stream-$streamInstanceId-$sessionId-$sequence"
