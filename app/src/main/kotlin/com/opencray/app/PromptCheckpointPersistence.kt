package com.opencray.app

import com.opencray.app.projection.runIdFor
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import java.util.UUID

internal fun OpenCrayHostRuntime.maybePersistGeneralResumeCheckpointLocked(
  sessionId: String,
  task: AgentTask,
  event: OpenCrayAgentRunEvent,
) {
  val eventRunId: String
  val eventTaskId: String
  val eventToolName: String?
  val eventMetadata: Map<String, String>
  val emittedAtEpochMs: Long
  val checkpointKind: PromptCheckpointKind
  when (event) {
    is OpenCrayToolResultEvent -> {
      eventRunId = event.runId
      eventTaskId = event.taskId
      eventToolName = event.result.toolName
      eventMetadata = event.result.metadata
      emittedAtEpochMs = event.emittedAtEpochMs
      checkpointKind = promptCheckpointKindForRuntimeEvent(
        event = event,
        metadata = eventMetadata,
      ) ?: return
    }

    is OpenCraySupplementEvent -> {
      eventRunId = event.runId
      eventTaskId = event.taskId
      eventToolName = null
      eventMetadata = event.metadata
      emittedAtEpochMs = event.emittedAtEpochMs
      checkpointKind = promptCheckpointKindForRuntimeEvent(
        event = event,
        metadata = eventMetadata,
      ) ?: return
    }

    is OpenCrayAssistantPhaseEvent -> {
      eventRunId = event.runId
      eventTaskId = event.taskId
      eventToolName = null
      eventMetadata = event.metadata
      emittedAtEpochMs = event.emittedAtEpochMs
      checkpointKind = promptCheckpointKindForRuntimeEvent(
        event = event,
        metadata = eventMetadata,
      ) ?: return
    }

    else -> return
  }
  val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
    metadata = eventMetadata,
    json = OpenCrayHostRuntime.replayJson,
  ) ?: return
  val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(eventMetadata)
  persistPromptCheckpointLocked(
    sessionId = sessionId,
    checkpoint = PersistedPromptCheckpoint(
      sessionId = sessionId,
      runId = eventRunId,
      taskId = eventTaskId,
      checkpointId = "checkpoint-$emittedAtEpochMs-${UUID.randomUUID().toString().take(8)}",
      checkpointKind = checkpointKind,
      createdAtEpochMs = emittedAtEpochMs,
      updatedAtEpochMs = emittedAtEpochMs,
      toolName = eventToolName,
      pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.takeIf(String::isNotBlank),
      promptCheckpointBoundary = promptCheckpointBoundary,
      promptResumeState = promptResumeState,
    ),
  )
}

internal fun OpenCrayHostRuntime.persistGeneralResumeCheckpointFromResultLocked(
  sessionId: String,
  task: AgentTask,
  result: ExecutionResult,
) {
  val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
    metadata = result.metadata,
    json = OpenCrayHostRuntime.replayJson,
  ) ?: return
  val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(result.metadata)
  persistPromptCheckpointLocked(
    sessionId = sessionId,
    checkpoint = PersistedPromptCheckpoint(
      sessionId = sessionId,
      runId = runIdFor(task),
      taskId = task.id,
      checkpointId = "checkpoint-${result.finishedAtEpochMs}-${UUID.randomUUID().toString().take(8)}",
      checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
      createdAtEpochMs = result.finishedAtEpochMs,
      updatedAtEpochMs = result.finishedAtEpochMs,
      pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.takeIf(String::isNotBlank),
      promptCheckpointBoundary = promptCheckpointBoundary,
      promptResumeState = promptResumeState,
    ),
  )
}

internal fun OpenCrayHostRuntime.persistPromptCheckpointLocked(
  sessionId: String,
  checkpoint: PersistedPromptCheckpoint,
) {
  promptCheckpointStoreForSession(sessionId).upsert(checkpoint)
}

internal fun OpenCrayHostRuntime.clearPromptCheckpointLocked(sessionId: String, taskId: String) {
  promptCheckpointStoreForSession(sessionId).remove(taskId)
}

internal fun OpenCrayHostRuntime.maybeClearPromptCheckpointAfterRuntimeEventLocked(
  sessionId: String,
  event: OpenCrayAgentRunEvent,
) {
  val checkpoint = promptCheckpointStoreForSession(sessionId).get(event.taskId) ?: return
  if (
    checkpoint.checkpointKind != PromptCheckpointKind.APPROVED_PENDING_RESUME &&
    checkpoint.checkpointKind != PromptCheckpointKind.REJECTED_PENDING_RESUME
  ) {
    return
  }
  when (event) {
    is OpenCrayApprovalEvent -> return
    is OpenCraySubAgentEvent -> if (event.phase == OpenCraySubAgentPhase.RESUMED) {
      return
    }
    is OpenCrayLifecycleEvent -> if (event.phase == OpenCrayRunLifecyclePhase.START) {
      return
    }
    else -> Unit
  }
  clearPromptCheckpointLocked(sessionId = sessionId, taskId = event.taskId)
}

internal fun OpenCrayHostRuntime.promptCheckpointKindForRuntimeEvent(
  event: OpenCrayAgentRunEvent,
  metadata: Map<String, String>,
): PromptCheckpointKind? {
  val runtimeBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata)
  return when (runtimeBoundary) {
    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST ->
      PromptCheckpointKind.PRE_MODEL_REQUEST

    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED ->
      PromptCheckpointKind.ACTION_BATCH_PARSED

    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED ->
      PromptCheckpointKind.COMMENTARY_EMITTED

    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED ->
      PromptCheckpointKind.TOOL_RESULT_COMMITTED

    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED ->
      PromptCheckpointKind.SUPPLEMENT_INGESTED

    com.opencray.runtime.OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE ->
      PromptCheckpointKind.FINALIZATION_COMPLETE

    null -> when (event) {
      is OpenCrayToolResultEvent,
      is OpenCraySupplementEvent,
      -> PromptCheckpointKind.GENERAL_RESUME

      is OpenCrayAssistantPhaseEvent -> null
      else -> null
    }
  }
}
