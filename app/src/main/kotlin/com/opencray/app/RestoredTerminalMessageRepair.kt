package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata

internal data class RestoredTerminalMessage(
  val text: String,
  val attachments: List<ChatAttachmentEntry>,
)

internal fun OpenCrayHostRuntime.repairRestoredTerminalMessagesLocked(
  sessionId: String,
  runs: List<AgentRunSnapshot>,
) {
  runs
    .asSequence()
    .filter(AgentRunSnapshot::isTerminal)
    .sortedBy(AgentRunSnapshot::acceptedAtEpochMs)
    .forEach { run ->
      val pendingMessageId = run.pendingMessageId?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
      val repaired = restoredTerminalMessageForRunLocked(
        sessionId = sessionId,
        run = run,
      ) ?: return@forEach
      val message = chatSessionStore.loadSession(sessionId)
        ?.messages
        ?.firstOrNull { candidate -> candidate.messageId == pendingMessageId }
        ?: return@forEach
      if (
        message.role == ChatTranscriptRole.ASSISTANT &&
        message.text.orEmpty() == repaired.text &&
        message.attachments == repaired.attachments
      ) {
        return@forEach
      }
      clearAssistantDraftLocked(
        sessionId = sessionId,
        pendingMessageId = pendingMessageId,
      )
      chatSessionStore.replaceMessage(
        sessionId = sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = repaired.text,
        attachments = repaired.attachments,
      )
    }
}

internal fun OpenCrayHostRuntime.restoredTerminalMessageForRunLocked(
  sessionId: String,
  run: AgentRunSnapshot,
): RestoredTerminalMessage? {
  val result = restoredTerminalResultForRunLocked(
    sessionId = sessionId,
    run = run,
  ) ?: return null
  val baseFinalText = finalTextForRunLocked(
    sessionId = sessionId,
    runId = run.runId,
    result = result,
    allowToolSummaryFallback = false,
  )
  val markdownCompatibility = attachmentMarkdownCompatibilityLocked(
    sessionId = sessionId,
    runId = run.runId,
    text = baseFinalText,
  )
  val finalAttachmentArchive = finalAttachmentArchiveForResultLocked(
    sessionId = sessionId,
    runId = run.runId,
    result = result,
    compatibilityAttachments = markdownCompatibility.attachments,
  )
  val text = finalizedAssistantText(
    text = markdownCompatibility.rewrittenText,
    attachments = finalAttachmentArchive.attachments,
    attachmentFailureText = finalAttachmentArchive.failureText,
  )
  if (text.isBlank() && finalAttachmentArchive.attachments.isEmpty()) {
    return null
  }
  return RestoredTerminalMessage(
    text = text,
    attachments = finalAttachmentArchive.attachments,
  )
}

internal fun OpenCrayHostRuntime.restoredTerminalResultForRunLocked(
  sessionId: String,
  run: AgentRunSnapshot,
): ExecutionResult? {
  val status = run.executionStatus ?: when (run.lifecycleState) {
    QueueTaskLifecycleState.COMPLETED -> ExecutionStatus.SUCCESS
    QueueTaskLifecycleState.CANCELLED -> ExecutionStatus.CANCELLED
    QueueTaskLifecycleState.FAILED -> ExecutionStatus.FAILED
    else -> null
  } ?: return null
  return if (status == ExecutionStatus.SUCCESS) {
    synthesizedTerminalSuccessResultForRunLocked(
      sessionId = sessionId,
      run = run,
    )
  } else {
    synthesizedTerminalResultForRun(
      run = run,
      status = status,
    )
  }
}

internal fun OpenCrayHostRuntime.synthesizedTerminalSuccessResultForRunLocked(
  sessionId: String,
  run: AgentRunSnapshot,
): ExecutionResult? {
  val event = latestFinalizationAssistantEvent(
    journalEntries = runEventJournalStoreForSession(sessionId).listForRun(run.runId),
    fallbackEvent = run.lastEvent,
  ) ?: return null
  if (
    event.text.isBlank() &&
    !event.metadata.containsKey(OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON)
  ) {
    return null
  }
  val startedAtEpochMs = minOf(run.acceptedAtEpochMs, event.emittedAtEpochMs)
  return ExecutionResult(
    taskId = run.taskId,
    status = ExecutionStatus.SUCCESS,
    stdout = event.text,
    startedAtEpochMs = startedAtEpochMs,
    finishedAtEpochMs = maxOf(startedAtEpochMs, event.emittedAtEpochMs),
    metadata = buildMap {
      putAll(run.resultMetadata)
      putAll(event.metadata)
      event.responseFormat
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { responseFormat ->
          if (!containsKey("responseFormat")) {
            put("responseFormat", responseFormat)
          }
        }
      run.executionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { executionId ->
          if (!containsKey(METADATA_EXECUTION_ID)) {
            put(METADATA_EXECUTION_ID, executionId)
          }
        }
      if (run.executionOrdinal > 0 && !containsKey(METADATA_EXECUTION_ORDINAL)) {
        put(METADATA_EXECUTION_ORDINAL, run.executionOrdinal.toString())
      }
      run.executionKind
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { executionKind ->
          if (!containsKey(METADATA_EXECUTION_KIND)) {
            put(METADATA_EXECUTION_KIND, executionKind)
          }
        }
    },
  )
}

internal fun OpenCrayHostRuntime.synthesizedTerminalResultForRun(
  run: AgentRunSnapshot,
  status: ExecutionStatus,
): ExecutionResult = ExecutionResult(
  taskId = run.taskId,
  status = status,
  errorCode = run.errorCode,
  errorMessage = run.errorMessage,
  startedAtEpochMs = run.acceptedAtEpochMs,
  finishedAtEpochMs = maxOf(run.acceptedAtEpochMs, run.updatedAtEpochMs),
  metadata = run.resultMetadata,
)

internal fun OpenCrayHostRuntime.latestFinalizationAssistantEvent(
  journalEntries: List<PersistedRunJournalEntry>,
  fallbackEvent: OpenCrayAgentRunEvent?,
): OpenCrayAssistantPhaseEvent? = journalEntries
  .asReversed()
  .asSequence()
  .mapNotNull { entry ->
    entry.payload.toRuntimeEventOrNull() as? OpenCrayAssistantPhaseEvent
  }
  .firstOrNull(::hasFinalizationBoundary)
  ?: (fallbackEvent as? OpenCrayAssistantPhaseEvent)?.takeIf(::hasFinalizationBoundary)

private fun hasFinalizationBoundary(event: OpenCrayAssistantPhaseEvent): Boolean =
  event.isFinal &&
    OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata) ==
    OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE
