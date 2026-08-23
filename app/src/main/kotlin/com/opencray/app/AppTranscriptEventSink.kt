package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptSupplementMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.session.SessionTranscriptStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun appTranscriptEventSink(
  replayJson: Json,
  sessionId: String,
  transcriptStore: SessionTranscriptStore,
  delegate: OpenCrayAgentRuntimeEventSink,
): OpenCrayAgentRuntimeEventSink = object : OpenCrayAgentRuntimeEventSink {
  override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
    when (event) {
      is OpenCraySupplementEvent -> recordSupplementReplayEvent(
        json = replayJson,
        transcriptStore = transcriptStore,
        event = event,
      )

      is OpenCrayToolResultEvent -> recordToolInteraction(
        json = replayJson,
        transcriptStore = transcriptStore,
        event = event,
      )

      is OpenCrayAssistantPhaseEvent -> recordAssistantReplayEvent(
        json = replayJson,
        transcriptStore = transcriptStore,
        event = event.toAssistantEvent(),
      )

      is OpenCraySubAgentEvent -> appendSubAgentReplayEvent(
        json = replayJson,
        transcriptStore = transcriptStore,
        event = event,
      )

      else -> Unit
    }
    delegate.onRunEvent(task, event)
  }

  override fun onAssistantDraftUpdated(
    task: AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ) {
    delegate.onAssistantDraftUpdated(
      task = task,
      text = text,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }

  override fun onAssistantDraftCleared(
    task: AgentTask,
    emittedAtEpochMs: Long,
  ) {
    delegate.onAssistantDraftCleared(
      task = task,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }
}

internal fun appendSubAgentReplayEvent(
  json: Json,
  transcriptStore: SessionTranscriptStore,
  event: OpenCraySubAgentEvent,
) {
  appendIfMissing(
    transcriptStore = transcriptStore,
    message = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildSubAgentReplayContent(json = json, event = event),
    ),
  )
}

private fun recordSupplementReplayEvent(
  json: Json,
  transcriptStore: SessionTranscriptStore,
  event: OpenCraySupplementEvent,
) {
  val replayContent = buildSupplementReplayContent(json = json, event = event)
  if (transcriptStore.snapshot().any { message -> message.content == replayContent }) {
    return
  }
  val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
    metadata = event.metadata,
    json = json,
  )
  if (event.text.isBlank() && attachments.isEmpty()) {
    return
  }
  transcriptStore.appendIfDistinct(
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = event.text,
      attachments = attachments,
    ),
  )
  appendIfMissing(
    transcriptStore = transcriptStore,
    message = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = replayContent,
    ),
  )
}

internal fun recordToolInteraction(
  json: Json,
  transcriptStore: SessionTranscriptStore,
  event: OpenCrayToolResultEvent,
) {
  val callObservation = RuntimeConversationMessage(
    role = RuntimeConversationRole.ASSISTANT,
    content = buildToolCallReplayContent(json = json, event = event),
    kind = RuntimeConversationMessageKind.TOOL_CALL,
    toolCall = RuntimeConversationToolCall(
      id = event.call.id,
      toolName = event.call.toolName,
      arguments = event.call.arguments,
      reason = event.call.reason,
    ),
  )
  val resultObservation = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = buildToolResultReplayContent(json = json, event = event),
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    toolResult = RuntimeConversationToolResult(
      toolCallId = event.call.id,
      toolName = event.result.toolName,
      status = event.result.status.name.lowercase(),
      isError = event.result.status != AgentToolResultStatus.SUCCESS,
    ),
  )
  appendIfMissing(
    transcriptStore = transcriptStore,
    message = callObservation,
  )
  appendIfMissing(
    transcriptStore = transcriptStore,
    message = resultObservation,
  )
}

private fun recordAssistantReplayEvent(
  json: Json,
  transcriptStore: SessionTranscriptStore,
  event: OpenCrayAssistantEvent,
) {
  if (isPersistedDraftAssistantPhase(event)) {
    return
  }
  if (event.isFinal) {
    val finalText = event.text.trim()
    if (finalText.isBlank()) {
      return
    }
    appendTrailingFinalAssistantReplayTurn(
      transcriptStore = transcriptStore,
      message = RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = finalText,
        assistantPhase = RuntimeConversationAssistantPhase.FINAL_ANSWER,
      ),
    )
    return
  }
  appendIfMissing(
    transcriptStore = transcriptStore,
    message = RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = buildCommentaryReplayContent(json = json, event = event),
      kind = RuntimeConversationMessageKind.COMMENTARY,
      commentary = RuntimeConversationCommentary(
        runId = event.runId,
        taskId = event.taskId,
        turn = event.turn,
        text = event.text,
        stage = event.stage,
      ),
      assistantPhase = RuntimeConversationAssistantPhase.COMMENTARY,
    ),
  )
}

private fun appendTrailingFinalAssistantReplayTurn(
  transcriptStore: SessionTranscriptStore,
  message: RuntimeConversationMessage,
) {
  if (isFinalAssistantTranscriptTurn(transcriptStore.snapshot().lastOrNull())) {
    return
  }
  transcriptStore.appendIfDistinct(message)
}

internal fun isFinalAssistantTranscriptTurn(
  message: RuntimeConversationMessage?,
): Boolean = message?.role == RuntimeConversationRole.ASSISTANT &&
  message.assistantPhase == RuntimeConversationAssistantPhase.FINAL_ANSWER

private fun appendIfMissing(
  transcriptStore: SessionTranscriptStore,
  message: RuntimeConversationMessage,
) {
  val existingContents = transcriptStore.snapshot().mapTo(linkedSetOf(), RuntimeConversationMessage::content)
  if (message.content in existingContents) {
    return
  }
  transcriptStore.appendIfDistinct(message)
}

private fun isPersistedDraftAssistantPhase(
  event: OpenCrayAssistantEvent,
): Boolean = event.stage
  ?.trim()
  ?.equals(AppAgentSessionTaskRuntimeFactory.PERSISTED_DRAFT_ASSISTANT_STAGE, ignoreCase = true) == true

private fun buildToolCallReplayContent(
  json: Json,
  event: OpenCrayToolResultEvent,
): String =
  encodeReplayJsonObject(json) {
    put("run_id", event.runId)
    put("task_id", event.taskId)
    event.executionId?.let { executionId -> put("execution_id", executionId) }
    event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
    event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
    put("turn", event.turn)
    event.call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
    put("tool_name", event.call.toolName)
    event.call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reason ->
        put("reason", collapseReplayWhitespace(reason))
      }
    put("arguments", event.call.arguments)
  }

private fun buildToolResultReplayContent(
  json: Json,
  event: OpenCrayToolResultEvent,
): String =
  encodeReplayJsonObject(json) {
    put("run_id", event.runId)
    put("task_id", event.taskId)
    event.executionId?.let { executionId -> put("execution_id", executionId) }
    event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
    event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
    put("turn", event.turn)
    event.call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
    put("tool_name", event.result.toolName)
    put("status", event.result.status.name.lowercase())
    put("content", event.result.content)
    event.result.exitCode?.let { exitCode -> put("exit_code", exitCode) }
    if (event.result.stdout.isNotBlank()) {
      put("stdout", event.result.stdout)
    }
    if (event.result.stderr.isNotBlank()) {
      put("stderr", event.result.stderr)
    }
    event.result.errorCode?.let { errorCode -> put("error_code", errorCode) }
    event.result.errorMessage?.let { errorMessage -> put("error_message", errorMessage) }
    val replayMetadata = replayMetadataSnapshot(event.result.metadata)
    if (replayMetadata.isNotEmpty()) {
      put(
        "metadata",
        buildJsonObject {
          replayMetadata.toSortedMap().forEach { (key, value) ->
            put(key, value)
          }
        },
      )
    }
  }

private fun buildCommentaryReplayContent(
  json: Json,
  event: OpenCrayAssistantEvent,
): String =
  encodeReplayJsonObject(json) {
    put("event_kind", "assistant_phase")
    put("phase", event.phase.name.lowercase())
    put("run_id", event.runId)
    put("task_id", event.taskId)
    event.executionId?.let { executionId -> put("execution_id", executionId) }
    event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
    event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
    put("turn", event.turn)
    put("text", collapseReplayWhitespace(event.text))
    event.responseFormat
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { responseFormat -> put("response_format", responseFormat) }
    event.stage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { stage -> put("stage", stage) }
  }

private fun buildSupplementReplayContent(
  json: Json,
  event: OpenCraySupplementEvent,
): String =
  encodeReplayJsonObject(json) {
    put("event_kind", "supplement")
    put("run_id", event.runId)
    put("task_id", event.taskId)
    event.executionId?.let { executionId -> put("execution_id", executionId) }
    event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
    event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
    put("turn", event.turn)
    put("entry_id", event.entryId)
    put("text", collapseReplayWhitespace(event.text))
    put("checkpoint", event.checkpoint)
    val replayMetadata = replayMetadataSnapshot(event.metadata)
    if (replayMetadata.isNotEmpty()) {
      put(
        "metadata",
        buildJsonObject {
          replayMetadata.toSortedMap().forEach { (key, value) ->
            put(key, value)
          }
        },
      )
    }
  }

private fun replayMetadataSnapshot(metadata: Map<String, String>): Map<String, String> =
  metadata.filterKeys { key ->
    key != OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON
  }

private fun buildSubAgentReplayContent(
  json: Json,
  event: OpenCraySubAgentEvent,
): String =
  encodeReplayJsonObject(json) {
    put("event_kind", "subagent")
    put("run_id", event.runId)
    put("task_id", event.taskId)
    event.executionId?.let { executionId -> put("execution_id", executionId) }
    event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
    event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
    event.agentId?.let { agentId -> put("agent_id", agentId) }
    put("turn", event.turn)
    put("phase", event.phase.name.lowercase())
    put("child_run_id", event.childRunId)
    put("child_task_id", event.childTaskId)
    put("label", collapseReplayWhitespace(event.label))
    put("subagent_type", event.subagentType)
    put("context_mode", event.contextMode)
    put("depth", event.depth)
    event.executionState
      ?.wireValue
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { executionState -> put("execution_state", executionState) }
    event.continuationKind
      ?.wireValue
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { continuationKind -> put("continuation_kind", continuationKind) }
    put("resumable", event.resumable)
    put("requires_user_action", event.requiresUserAction)
    put("is_high_risk", event.isHighRisk)
    put("closed", event.closed)
    event.summary
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { summary -> put("summary", collapseReplayWhitespace(summary)) }
    event.liveContext
      ?.takeUnless { it.isEmpty }
      ?.toMap()
      ?.let { liveContext ->
        put(
          "live_context",
          buildJsonObject {
            (liveContext["mode"] as String?)?.let { put("mode", it) }
            (liveContext["soulEnabled"] as Boolean?)?.let { put("soulEnabled", it) }
            (liveContext["memoryRecallEnabled"] as Boolean?)?.let {
              put("memoryRecallEnabled", it)
            }
            (liveContext["replaySource"] as String?)?.let { put("replaySource", it) }
            (liveContext["replayMessageCount"] as Int?)?.let {
              put("replayMessageCount", it)
            }
            (liveContext["canonicalSource"] as String?)?.let {
              put("canonicalSource", it)
            }
            (liveContext["canonicalMessageCount"] as Int?)?.let {
              put("canonicalMessageCount", it)
            }
            (liveContext["canonicalHistoryPreserved"] as Boolean?)?.let {
              put("canonicalHistoryPreserved", it)
            }
          },
        )
      }
  }

private fun encodeReplayJsonObject(
  json: Json,
  builder: JsonObjectBuilder.() -> Unit,
): String =
  json.encodeToString(
    serializer = JsonObject.serializer(),
    value = buildJsonObject(builder),
  )

private fun collapseReplayWhitespace(content: String): String =
  content.replace(Regex("\\s+"), " ").trim()
