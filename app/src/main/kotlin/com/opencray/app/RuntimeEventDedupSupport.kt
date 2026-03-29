package com.opencray.app

import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent

internal fun dedupeRuntimeEventsPreservingOrder(
  events: Iterable<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val deduped = ArrayList<OpenCrayAgentRunEvent>()
  val seen = linkedSetOf<String>()
  events.forEach { event ->
    if (seen.add(runtimeEventDedupKey(event))) {
      deduped += event
    }
  }
  return deduped
}

internal fun runtimeEventDedupKey(event: OpenCrayAgentRunEvent): String = when (event) {
  is OpenCrayLifecycleEvent -> listOf(
    "lifecycle",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.orEmptyString(),
    event.phase.name,
    event.status?.name.orEmpty(),
    event.errorCode.orEmpty(),
    event.errorMessage.orEmpty(),
  ).joinToString(separator = "|")

  is OpenCrayAssistantPhaseEvent -> listOf(
    "assistant_phase",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.toString(),
    event.phase.name,
    event.responseFormat.trimToDedupField(),
    event.isFinal.toString(),
    event.stage.trimToDedupField(),
    event.text.collapseWhitespaceForDedup(),
  ).joinToString(separator = "|")

  is OpenCraySupplementEvent -> listOf(
    "supplement",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.toString(),
    event.entryId,
    event.checkpoint,
    event.text,
  ).joinToString(separator = "|")

  is OpenCrayApprovalEvent -> listOf(
    "approval",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.orEmptyString(),
    event.phase.name,
    event.toolName.orEmpty(),
    event.isHighRisk.toString(),
    event.text,
  ).joinToString(separator = "|")

  is OpenCraySubAgentEvent -> listOf(
    "subagent",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.orEmptyString(),
    event.phase.name,
    event.childRunId,
    event.childTaskId,
    event.label,
    event.subagentType,
    event.contextMode,
    event.depth.toString(),
    event.executionState?.wireValue.orEmpty(),
    event.continuationKind?.wireValue.orEmpty(),
    event.resumable.toString(),
    event.requiresUserAction.toString(),
    event.isHighRisk.toString(),
    event.summary.orEmpty(),
  ).joinToString(separator = "|")

  is OpenCrayToolCallEvent -> listOf(
    "tool_call",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.toString(),
    event.call.toolName,
    event.call.reason.orEmpty(),
    event.call.arguments.toString(),
  ).joinToString(separator = "|")

  is OpenCrayToolResultEvent -> listOf(
    "tool_result",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.toString(),
    event.result.toolName,
    event.result.status.name,
    event.result.errorCode.orEmpty(),
    event.result.errorMessage.orEmpty(),
  ).joinToString(separator = "|")

  is OpenCrayMemoryRetrievalEvent -> listOf(
    "memory_retrieval",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.toString(),
    event.toolName,
    event.operation,
    event.query.orEmpty(),
    event.path.orEmpty(),
  ).joinToString(separator = "|")

  is OpenCrayMemoryWriteEvent -> listOf(
    "memory_write",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.orEmptyString(),
    event.writtenRecordIds.joinToString(separator = ","),
    event.resolvedRecordIds.joinToString(separator = ","),
    event.suppressedRecordIds.joinToString(separator = ","),
    event.reaffirmedRecordIds.joinToString(separator = ","),
    event.expiredRecordIds.joinToString(separator = ","),
  ).joinToString(separator = "|")

  is OpenCrayCancellationEvent -> listOf(
    "cancelled",
    event.runId,
    event.taskId,
    event.executionId.orEmpty(),
    event.executionOrdinal.orEmptyString(),
    event.executionKind.orEmpty(),
    event.turn.orEmptyString(),
    event.toolName.orEmpty(),
    event.outcome.orEmpty(),
    event.text,
  ).joinToString(separator = "|")
}

private fun Int?.orEmptyString(): String = this?.toString().orEmpty()

private fun String?.trimToDedupField(): String =
  this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    .orEmpty()

private fun String.collapseWhitespaceForDedup(): String =
  trim().replace(Regex("\\s+"), " ")
