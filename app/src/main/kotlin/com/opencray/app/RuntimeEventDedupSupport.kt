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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun dedupeRuntimeEventsPreservingOrder(
  events: Iterable<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val dedupedByKey = linkedMapOf<String, OpenCrayAgentRunEvent>()
  events.forEach { event ->
    dedupedByKey[runtimeEventDedupKey(event)] = event
  }
  return dedupedByKey.values.toList()
}

internal fun runtimeEventStableId(event: OpenCrayAgentRunEvent): String =
  event.eventId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: runtimeEventSemanticKey(event).sha256Hex().let { digest -> "runtime-event-${digest.take(32)}" }

internal fun runtimeEventDedupKey(event: OpenCrayAgentRunEvent): String =
  "event|${runtimeEventStableId(event)}"

private fun runtimeEventSemanticKey(event: OpenCrayAgentRunEvent): String = when (event) {
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
    event.call.id.orEmpty(),
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
    event.call.id.orEmpty(),
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
    event.reopenedRecordIds.joinToString(separator = ","),
    event.reaffirmedRecordIds.joinToString(separator = ","),
    event.expiredRecordIds.joinToString(separator = ","),
    event.stewardshipPlanSteps.joinToString(separator = ",") { step ->
      listOf(
        step.action.wireValue,
        step.outcome.wireValue,
        step.recordId.orEmpty(),
        step.candidateIndex?.toString().orEmpty(),
        step.producedRecordId.orEmpty(),
        step.reason.orEmpty(),
      ).joinToString(separator = ":")
    },
    event.stewardshipPlanGraph.nodes.joinToString(separator = ",") { node ->
      listOf(
        node.id,
        node.kind,
        node.action.orEmpty(),
        node.outcome.orEmpty(),
        node.recordId.orEmpty(),
        node.candidateIndex?.toString().orEmpty(),
        node.producedRecordId.orEmpty(),
        node.reason.orEmpty(),
      ).joinToString(separator = ":")
    },
    event.stewardshipPlanGraph.edges.joinToString(separator = ",") { edge ->
      listOf(edge.from, edge.to, edge.kind).joinToString(separator = ":")
    },
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

private fun String.sha256Hex(): String = MessageDigest
  .getInstance("SHA-256")
  .digest(toByteArray(StandardCharsets.UTF_8))
  .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
