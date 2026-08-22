package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.subagent.synchronizedSubAgentHandles
import java.util.UUID

private const val SUPPLEMENT_CHECKPOINT_TURN_START: String = "turn_start"
private const val SUPPLEMENT_CHECKPOINT_POST_TOOL_PRE_MODEL: String = "post_tool_pre_model"
private const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
internal fun supplementCheckpointFor(
  transcript: List<RuntimeConversationMessage>,
): String = transcript.lastOrNull()
  ?.takeIf { message ->
    message.role == RuntimeConversationRole.TOOL &&
      message.kind == RuntimeConversationMessageKind.TOOL_RESULT
  }
  ?.let { SUPPLEMENT_CHECKPOINT_POST_TOOL_PRE_MODEL }
  ?: SUPPLEMENT_CHECKPOINT_TURN_START

internal fun OpenCrayAgentRuntime.promptCheckpointState(
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  turnIndex: Int,
  pendingActions: List<OpenCrayAgentRuntime.AgentModelAction> = emptyList(),
  nextActionIndex: Int = 0,
  requiresSingleActionReminder: Boolean = false,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
): OpenCrayPromptResumeState = OpenCrayPromptResumeState(
  transcript = cursor.transcript.toList(),
  turnIndex = turnIndex,
  toolCallCount = cursor.toolCallCount,
  pendingActions = pendingActions.map { action -> action.toSerializableModelAction() },
  nextActionIndex = nextActionIndex,
  requiresSingleActionReminder = requiresSingleActionReminder,
  activeSkillName = cursor.activeSkillName,
  activeSkillActivationSource = cursor.activeSkillActivationSource,
  activeSkillPinned = cursor.activeSkillPinned,
  localContinuationEnvelope = localContinuationContextPrompts
    ?.takeIf { prompts -> prompts.isNotEmpty() }
    ?.let { frontContextPrompts ->
      localContinuationStableAnchor?.let { stableAnchor ->
        buildLocalContinuationEnvelope(
          cursor = cursor,
          frontContextPrompts = frontContextPrompts,
          stableAnchor = stableAnchor,
          gatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
          toolPoolFingerprint = localContinuationToolPoolFingerprint
            ?: cursor.localContinuationEnvelope?.toolPoolFingerprint
            ?: "absent",
          toolSchemaFingerprint = localContinuationToolSchemaFingerprint
            ?: cursor.localContinuationEnvelope?.toolSchemaFingerprint
            ?: "absent",
          requestSettingsFingerprint = localContinuationRequestSettingsFingerprint
            ?: cursor.localContinuationEnvelope?.requestSettingsFingerprint
            ?: "absent",
        )?.toSerializable()
      }
    }
    ?: cursor.localContinuationEnvelope?.toSerializable(),
  responsesPreviousResponseId = cursor.responsesPreviousResponseId,
  responsesProviderLineageId = cursor.responsesProviderLineageId,
  responsesLineageTrusted = cursor.responsesLineageTrusted,
  responsesContinuationShape = cursor.responsesContinuationShape?.toSerializable(),
  responsesPendingMessages = cursor.responsesPendingMessages.map(OpenCraySerializableGatewayMessage::from),
  replayToolResultProjections = cursor.replayToolResultProjections.toSortedMap(),
  subAgentHandles = synchronizedSubAgentHandles(cursor),
)

internal fun OpenCrayAgentRuntime.promptCheckpointMetadata(
  boundary: OpenCrayPromptCheckpointBoundary,
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  turnIndex: Int,
  pendingActions: List<OpenCrayAgentRuntime.AgentModelAction> = emptyList(),
  nextActionIndex: Int = 0,
  requiresSingleActionReminder: Boolean = false,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
): Map<String, String> = OpenCrayPromptResumeMetadata.encodeToMetadata(
  state = promptCheckpointState(
    cursor = cursor,
    turnIndex = turnIndex,
    pendingActions = pendingActions,
    nextActionIndex = nextActionIndex,
    requiresSingleActionReminder = requiresSingleActionReminder,
    localContinuationContextPrompts = localContinuationContextPrompts,
    localContinuationStableAnchor = localContinuationStableAnchor,
    localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
    localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
    localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
    localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
  ),
  json = config.json,
  checkpointBoundary = boundary,
)

internal fun OpenCrayAgentRuntime.emitPromptCheckpoint(
  boundary: OpenCrayPromptCheckpointBoundary,
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  turnIndex: Int,
  emittedAtEpochMs: Long,
  toolName: String? = null,
  pendingActions: List<OpenCrayAgentRuntime.AgentModelAction> = emptyList(),
  nextActionIndex: Int = 0,
  requiresSingleActionReminder: Boolean = false,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
) {
  config.promptCheckpointSink(
    OpenCrayPromptCheckpointEmission(
      boundary = boundary,
      state = promptCheckpointState(
        cursor = cursor,
        turnIndex = turnIndex,
        pendingActions = pendingActions,
        nextActionIndex = nextActionIndex,
        requiresSingleActionReminder = requiresSingleActionReminder,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
        localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
      ),
      emittedAtEpochMs = emittedAtEpochMs,
      toolName = toolName,
    ),
  )
}

internal fun OpenCrayAgentRuntime.emitInternalCheckpointJournalMarker(
  task: AgentTask,
  turn: Int,
  boundary: OpenCrayPromptCheckpointBoundary,
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  turnIndex: Int,
  emittedAtEpochMs: Long,
  pendingActions: List<OpenCrayAgentRuntime.AgentModelAction> = emptyList(),
  nextActionIndex: Int = 0,
  requiresSingleActionReminder: Boolean = false,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
) {
  if (
    boundary != OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST &&
      boundary != OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED
  ) {
    return
  }
  eventSink.onRunEvent(
    task = task,
    event = OpenCraySupplementEvent(
      runId = runIdFor(task),
      taskId = task.id,
      turn = turn,
      entryId = "checkpoint-${boundary.wireValue}-${UUID.randomUUID().toString().take(8)}",
      text = "",
      checkpoint = INTERNAL_PROMPT_CHECKPOINT_MARKER,
      metadata = promptCheckpointMetadata(
        boundary = boundary,
        cursor = cursor,
        turnIndex = turnIndex,
        pendingActions = pendingActions,
        nextActionIndex = nextActionIndex,
        requiresSingleActionReminder = requiresSingleActionReminder,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
        localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
      ),
      emittedAtEpochMs = emittedAtEpochMs,
    ),
  )
}

internal fun OpenCrayAgentRuntime.promptCheckpointMetadataAfterActionIndex(
  boundary: OpenCrayPromptCheckpointBoundary,
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  batchActions: List<OpenCrayAgentRuntime.AgentModelAction>,
  nextActionIndex: Int,
  requiresSingleActionReminder: Boolean,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
): Map<String, String> = if (nextActionIndex < batchActions.size) {
  promptCheckpointMetadata(
    boundary = boundary,
    cursor = cursor,
    turnIndex = cursor.turn,
    pendingActions = batchActions,
    nextActionIndex = nextActionIndex,
    requiresSingleActionReminder = requiresSingleActionReminder,
    localContinuationContextPrompts = localContinuationContextPrompts,
    localContinuationStableAnchor = localContinuationStableAnchor,
    localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
    localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
    localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
    localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
  )
} else {
  promptCheckpointMetadata(
    boundary = boundary,
    cursor = cursor,
    turnIndex = cursor.turn + 1,
    localContinuationContextPrompts = localContinuationContextPrompts,
    localContinuationStableAnchor = localContinuationStableAnchor,
    localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
    localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
    localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
    localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
  )
}
