package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.app.projection.LiveAssistantDraftSnapshot
import com.opencray.app.projection.runIdFor

internal fun OpenCrayHostRuntime.updateAssistantDraft(
  sessionId: String,
  task: AgentTask,
  text: String,
  emittedAtEpochMs: Long,
): LiveAssistantDraftSnapshot? {
  val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return null
  val updatedDraft = LiveAssistantDraftSnapshot(
    runId = runIdFor(task),
    taskId = task.id,
    executionId = executionIdFromMetadata(task.metadata),
    pendingMessageId = pendingMessageId,
    text = normalizedText,
    updatedAtEpochMs = emittedAtEpochMs,
  )
  return synchronized(liveAssistantDraftLock) {
    val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
    val existing = sessionDrafts[pendingMessageId]
    if (existing == updatedDraft) {
      null
    } else {
      sessionDrafts[pendingMessageId] = updatedDraft
      updatedDraft
    }
  }
}

internal fun OpenCrayHostRuntime.clearAssistantDraftLocked(
  sessionId: String,
  pendingMessageId: String?,
): Boolean = clearAssistantDraft(
  sessionId = sessionId,
  pendingMessageId = pendingMessageId,
)

internal fun OpenCrayHostRuntime.clearAssistantDraft(
  sessionId: String,
  pendingMessageId: String?,
): Boolean {
  val normalizedPendingMessageId = pendingMessageId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return false
  return synchronized(liveAssistantDraftLock) {
    val sessionDrafts = liveAssistantDraftsBySession[sessionId] ?: return@synchronized false
    val removed = sessionDrafts.remove(normalizedPendingMessageId) != null
    if (sessionDrafts.isEmpty()) {
      liveAssistantDraftsBySession.remove(sessionId)
    }
    removed
  }
}

internal fun liveAssistantDraftEventPayload(
  sessionId: String,
  runId: String,
  taskId: String,
  executionId: String?,
  pendingMessageId: String,
  text: String,
  updatedAtEpochMs: Long,
  cleared: Boolean,
): Map<String, Any?> = mapOf(
  "sessionId" to sessionId,
  "runId" to runId,
  "taskId" to taskId,
  "executionId" to executionId,
  "pendingMessageId" to pendingMessageId,
  "text" to text,
  "updatedAtEpochMs" to updatedAtEpochMs,
  "cleared" to cleared,
)

internal fun OpenCrayHostRuntime.assistantDraftRuntimeEvent(
  task: AgentTask,
  text: String,
  emittedAtEpochMs: Long,
): OpenCrayAssistantEvent = OpenCrayAssistantEvent(
  runId = runIdFor(task),
  taskId = task.id,
  turn = -1,
  text = text.trim(),
  isFinal = false,
  stage = AppAgentSessionTaskRuntimeFactory.PERSISTED_DRAFT_ASSISTANT_STAGE,
  emittedAtEpochMs = emittedAtEpochMs,
)

internal fun LiveAssistantDraftSnapshot.toLiveAssistantDraftEventPayload(
  sessionId: String,
  cleared: Boolean,
): Map<String, Any?> = liveAssistantDraftEventPayload(
  sessionId = sessionId,
  runId = runId,
  taskId = taskId,
  executionId = executionId,
  pendingMessageId = pendingMessageId,
  text = text,
  updatedAtEpochMs = updatedAtEpochMs,
  cleared = cleared,
)
