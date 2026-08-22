package com.opencray.runtime

import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.NON_RESPONSES_CONTEXT_CACHE_CONTRACT_VERSION
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.RESPONSES_CONTEXT_UPDATE_CHAIN_LIMIT
import com.opencray.runtime.OpenCrayAgentRuntime.Companion.RESPONSES_CONTEXT_UPDATE_MAX_CHARS
import com.opencray.runtime.OpenCrayAgentRuntime.GatewayMessagePlan
import com.opencray.runtime.OpenCrayAgentRuntime.LocalContinuationEnvelope
import com.opencray.runtime.OpenCrayAgentRuntime.LocalContinuationMode
import com.opencray.runtime.OpenCrayAgentRuntime.PromptRunDiagnostics
import com.opencray.runtime.OpenCrayAgentRuntime.PromptTurnCursor
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesContextBaselineSnapshot
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesContextReferenceState
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesContinuationDecision
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesContinuationShape
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesPendingContextUpdate
import com.opencray.runtime.OpenCrayAgentRuntime.ResponsesPendingContextUpdatePlan
import com.opencray.runtime.context.FrontContextZones
import com.opencray.runtime.context.FrozenToolResultReplayProjection
import com.opencray.runtime.context.PromptLayerTransportGroup
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole

internal fun OpenCrayAgentRuntime.nonResponsesContextCacheShapeMetadata(
  stableAnchor: String,
  frontContextZones: FrontContextZones,
): Map<String, String> {
  if (isResponsesProtocol()) {
    return emptyMap()
  }
  val zoneMask = buildList {
    if (frontContextZones.durableContextPrompt.trim().isNotBlank()) {
      add("durable")
    }
    if (frontContextZones.dynamicContextPrompt.trim().isNotBlank()) {
      add("dynamic")
    }
  }.joinToString(separator = ",")
    .ifBlank { "none" }
  return buildMap {
    put(LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION, NON_RESPONSES_CONTEXT_CACHE_CONTRACT_VERSION)
    put(LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH, promptCacheFingerprint(stableAnchor))
    put(
      LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH,
      promptCacheFingerprint(frontContextZones.durableContextPrompt.trim()),
    )
    put(
      LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH,
      promptCacheFingerprint(frontContextZones.dynamicContextPrompt.trim()),
    )
    put(LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK, zoneMask)
    put(
      LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT,
      frontContextZones.promptsInTransportOrder.size.toString(),
    )
  }
}

internal fun OpenCrayAgentRuntime.buildResponsesGatewayMessagePlan(
  cursor: PromptTurnCursor,
  frontContextPrompts: List<String>,
  stableAnchor: String,
  toolPoolFingerprint: String,
  toolSchemaFingerprint: String,
  requestSettingsFingerprint: String,
  transcript: List<RuntimeConversationMessage>,
): GatewayMessagePlan {
  val requestedFrontContextZones = normalizeFrontContextZones(frontContextPrompts)
  val decision = responsesContinuationDecision(
    cursor = cursor,
    requestedShape = ResponsesContinuationShape(
      stableAnchor = stableAnchor,
      baseline = ResponsesContextBaselineSnapshot(
        durableContextPrompt = requestedFrontContextZones.durableContextPrompt,
      ),
      referenceState = ResponsesContextReferenceState(
        dynamicContextHash = promptCacheFingerprint(requestedFrontContextZones.dynamicContextPrompt),
      ),
      toolPoolFingerprint = toolPoolFingerprint,
      toolSchemaFingerprint = toolSchemaFingerprint,
      requestSettingsFingerprint = requestSettingsFingerprint,
    ),
    requestedFrontContextZones = requestedFrontContextZones,
  )
  val previousResponseId = decision.previousResponseId
  return if (previousResponseId != null) {
    GatewayMessagePlan(
      messages = cursor.responsesPendingMessages + decision.pendingContextUpdates.map { update ->
        responsesPendingContextUpdateMessage(update)
      },
      mode = LocalContinuationMode.RESPONSES_NATIVE,
      reason = decision.reason,
      previousResponseId = previousResponseId,
      responsesPendingContextUpdateCount = decision.pendingContextUpdates.size,
      responsesPendingContextUpdateHash = responsesPendingContextUpdateHash(decision.pendingContextUpdates),
    )
  } else {
    fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = transcript,
      reason = decision.reason,
    )
  }
}

internal fun OpenCrayAgentRuntime.responsesContinuationDecision(
  cursor: PromptTurnCursor,
  requestedShape: ResponsesContinuationShape,
  requestedFrontContextZones: FrontContextZones,
): ResponsesContinuationDecision {
  if (!responsesContinuationSupported()) {
    return ResponsesContinuationDecision(reason = "responses_continuation_disabled")
  }
  if (cursor.responsesFullReplayRequired) {
    return ResponsesContinuationDecision(reason = "responses_restored_replay_required")
  }
  if (cursor.legacyJsonFallbackEnabled) {
    return ResponsesContinuationDecision(reason = "responses_legacy_json_fallback_enabled")
  }
  if (!hasResponsesLineage(cursor)) {
    return ResponsesContinuationDecision(reason = "responses_lineage_unavailable")
  }
  val previousResponseId = cursor.responsesPreviousResponseId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return ResponsesContinuationDecision(reason = "responses_lineage_unavailable")
  val pendingMessages = cursor.responsesPendingMessages
  if (pendingMessages.isEmpty()) {
    return ResponsesContinuationDecision(reason = "responses_no_pending_messages")
  }
  if (hasDuplicateResponsesPendingToolResultIds(pendingMessages)) {
    return ResponsesContinuationDecision(
      reason = "responses_pending_tool_result_duplicate_call_id",
    )
  }
  pendingMessages.firstOrNull { message -> !isSafeResponsesContinuationPendingMessage(message) }
    ?.let { message ->
      return ResponsesContinuationDecision(
        reason = responsesContinuationFallbackReason(message),
      )
    }
  responsesContinuationShapeMismatchReason(
    storedShape = cursor.responsesContinuationShape,
    requestedShape = requestedShape,
  )?.let { reason ->
    return ResponsesContinuationDecision(reason = reason)
  }
  val pendingContextUpdates = responsesPendingContextUpdates(
    cursor = cursor,
    requestedShape = requestedShape,
    requestedFrontContextZones = requestedFrontContextZones,
  )
  pendingContextUpdates.fallbackReason?.let { reason ->
    return ResponsesContinuationDecision(reason = reason)
  }
  return ResponsesContinuationDecision(
    previousResponseId = previousResponseId,
    reason = "responses_previous_response_id",
    pendingContextUpdates = pendingContextUpdates.updates,
  )
}

internal fun responsesContinuationShapeMismatchReason(
  storedShape: ResponsesContinuationShape?,
  requestedShape: ResponsesContinuationShape,
): String? {
  val shape = storedShape ?: return "responses_shape_unavailable"
  if (shape.toolPoolFingerprint != requestedShape.toolPoolFingerprint) {
    return "tool_pool_changed"
  }
  if (shape.toolSchemaFingerprint != requestedShape.toolSchemaFingerprint) {
    return "tool_schema_changed"
  }
  if (shape.requestSettingsFingerprint != requestedShape.requestSettingsFingerprint) {
    return "user_setting_changed"
  }
  if (shape.stableAnchor != requestedShape.stableAnchor) {
    return "anchor_changed"
  }
  if (shape.baseline.durableContextPrompt != requestedShape.baseline.durableContextPrompt) {
    return "durable_context_changed"
  }
  return null
}

internal fun responsesPendingContextUpdates(
  cursor: PromptTurnCursor,
  requestedShape: ResponsesContinuationShape,
  requestedFrontContextZones: FrontContextZones,
): ResponsesPendingContextUpdatePlan {
  val storedShape = cursor.responsesContinuationShape ?: return ResponsesPendingContextUpdatePlan()
  if (storedShape.referenceState.dynamicContextHash == requestedShape.referenceState.dynamicContextHash) {
    return ResponsesPendingContextUpdatePlan()
  }
  if (storedShape.referenceState.appliedUpdateCount >= RESPONSES_CONTEXT_UPDATE_CHAIN_LIMIT) {
    return ResponsesPendingContextUpdatePlan(
      fallbackReason = "responses_context_update_chain_limit",
    )
  }
  val updateText = requestedFrontContextZones.dynamicContextPrompt.trim().takeIf(String::isNotBlank)
    ?: "Dynamic operational context is currently empty."
  if (updateText.length > RESPONSES_CONTEXT_UPDATE_MAX_CHARS) {
    return ResponsesPendingContextUpdatePlan(
      fallbackReason = "responses_context_update_too_large",
    )
  }
  return ResponsesPendingContextUpdatePlan(
    updates = listOf(
      ResponsesPendingContextUpdate(
        sequence = storedShape.referenceState.appliedUpdateCount + 1,
        dynamicContextHash = requestedShape.referenceState.dynamicContextHash,
        content = updateText,
        truncated = false,
      ),
    ),
  )
}

internal fun responsesPendingContextUpdateMessage(
  update: ResponsesPendingContextUpdate,
): LiteLlmGatewayMessage = LiteLlmGatewayMessage(
  role = LiteLlmGatewayMessageRole.USER,
  content = buildString {
    append("[OpenCray Context Update]\n")
    append("zone=dynamic_operational\n")
    append("sequence=")
    append(update.sequence)
    append("\n")
    append("dynamic_context_hash=")
    append(update.dynamicContextHash)
    append("\n")
    append("truncated=")
    append(update.truncated)
    append("\n\n")
    append(update.content)
  },
)

internal fun OpenCrayAgentRuntime.responsesPendingContextUpdateHash(
  updates: List<ResponsesPendingContextUpdate>,
): String? = updates
  .takeIf(List<ResponsesPendingContextUpdate>::isNotEmpty)
  ?.joinToString(separator = "\n") { update ->
    "${update.sequence}:${update.dynamicContextHash}:${update.truncated}:${update.content}"
  }
  ?.let(::promptCacheFingerprint)

internal fun hasResponsesLineage(cursor: PromptTurnCursor): Boolean =
  cursor.responsesLineageTrusted &&
    !cursor.responsesProviderLineageId.isNullOrBlank() &&
    !cursor.responsesPreviousResponseId.isNullOrBlank()

internal fun OpenCrayAgentRuntime.isSafeResponsesContinuationPendingMessage(
  message: LiteLlmGatewayMessage,
): Boolean {
  return when (message.role) {
    LiteLlmGatewayMessageRole.TOOL -> {
      val toolResult = message.toolResult ?: return false
      !toolResult.toolCallId.isNullOrBlank() &&
        !toolResult.toolName.isNullOrBlank() &&
        toolResult.content.isNotBlank() &&
        !toolResultPublishesAttachmentArtifacts(toolResult)
    }

    LiteLlmGatewayMessageRole.USER -> isResponsesPendingContextUpdateMessage(message)

    else -> false
  }
}

internal fun isResponsesPendingContextUpdateMessage(
  message: LiteLlmGatewayMessage,
): Boolean =
  message.content
    ?.trimStart()
    ?.startsWith("[OpenCray Context Update]\nzone=dynamic_operational\n") == true &&
    message.attachments.isEmpty() &&
    message.toolCalls.isEmpty() &&
    message.toolResult == null &&
    message.assistantPhase == null

internal fun OpenCrayAgentRuntime.toolResultPublishesAttachmentArtifacts(
  toolResult: LiteLlmGatewayToolResult,
): Boolean = OpenCrayAttachmentArtifacts.decodeMetadata(
  json = config.json,
  metadata = toolResult.metadata,
).isNotEmpty()

internal fun OpenCrayAgentRuntime.responsesContinuationFallbackReason(
  message: LiteLlmGatewayMessage,
): String = when (message.role) {
  LiteLlmGatewayMessageRole.USER -> "responses_pending_user_message"
  LiteLlmGatewayMessageRole.SYSTEM -> "responses_pending_system_message"
  LiteLlmGatewayMessageRole.ASSISTANT -> "responses_pending_assistant_message"
  LiteLlmGatewayMessageRole.TOOL -> {
    val toolResult = message.toolResult
    when {
      toolResult == null -> "responses_pending_tool_result_missing_payload"
      toolResult.toolCallId.isNullOrBlank() -> "responses_pending_tool_result_missing_call_id"
      toolResult.toolName.isNullOrBlank() -> "responses_pending_tool_result_missing_name"
      toolResult.content.isBlank() -> "responses_pending_tool_result_blank_content"
      toolResultPublishesAttachmentArtifacts(toolResult) ->
        "responses_pending_tool_result_attachment_artifact"
      else -> "responses_pending_tool_result_invalid"
    }
  }
}

internal fun hasDuplicateResponsesPendingToolResultIds(
  pendingMessages: List<LiteLlmGatewayMessage>,
): Boolean {
  val seenToolCallIds = linkedSetOf<String>()
  pendingMessages.forEach { message ->
    val toolCallId = message.toolResult?.toolCallId?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
    if (!seenToolCallIds.add(toolCallId)) {
      return true
    }
  }
  return false
}

internal fun OpenCrayAgentRuntime.buildNonResponsesGatewayMessagePlan(
  cursor: PromptTurnCursor,
  transcript: List<RuntimeConversationMessage>,
  turnAwareConversation: List<RuntimeConversationMessage>,
  frontContextPrompts: List<String>,
  stableAnchor: String,
  toolPoolFingerprint: String,
  toolSchemaFingerprint: String,
  requestSettingsFingerprint: String,
): GatewayMessagePlan {
  val envelope = cursor.localContinuationEnvelope ?: return fullGatewayMessageRebuild(
    cursor = cursor,
    frontContextPrompts = frontContextPrompts,
    transcript = turnAwareConversation,
    reason = "no_envelope",
  )
  val normalizedFrontContextZones = normalizeFrontContextZones(frontContextPrompts)
  if (envelope.toolPoolFingerprint != null && envelope.toolPoolFingerprint != toolPoolFingerprint) {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "tool_pool_changed",
    )
  }
  if (envelope.toolSchemaFingerprint != null && envelope.toolSchemaFingerprint != toolSchemaFingerprint) {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "tool_schema_changed",
    )
  }
  if (
    envelope.requestSettingsFingerprint != null &&
    envelope.requestSettingsFingerprint != requestSettingsFingerprint
  ) {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "user_setting_changed",
    )
  }
  if (envelope.stableAnchor != stableAnchor) {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "anchor_changed",
    )
  }
  if (envelope.frontContextZones.durableContextPrompt != normalizedFrontContextZones.durableContextPrompt) {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "durable_context_changed",
    )
  }
  val transcriptDelta = transcriptDeltaSince(
    frontier = envelope.transcriptFrontier,
    transcript = transcript,
  ) ?: run {
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "transcript_mismatch",
    )
  }
  val promptOnlyDelta = if (turnAwareConversation.size > transcript.size) {
    turnAwareConversation.subList(transcript.size, turnAwareConversation.size)
  } else {
    emptyList()
  }
  if (envelope.frontContextZones.dynamicContextPrompt != normalizedFrontContextZones.dynamicContextPrompt) {
    val patchedGatewayMessages = patchDynamicFrontContextGatewayMessages(
      gatewayMessages = envelope.gatewayMessages,
      existingFrontContextZones = envelope.frontContextZones,
      requestedFrontContextZones = normalizedFrontContextZones,
    )
    if (patchedGatewayMessages != null) {
      return GatewayMessagePlan(
        messages = patchedGatewayMessages + buildLocalContinuationDeltaMessages(
          transcriptDelta = transcriptDelta,
          promptOnlyDelta = promptOnlyDelta,
          replayToolResultProjections = cursor.replayToolResultProjections,
        ),
        mode = LocalContinuationMode.LOCAL_FRONT_PATCH,
        reason = "dynamic_context_changed",
      )
    }
    invalidateLocalContinuation(cursor)
    return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "dynamic_context_changed",
    )
  }
  return GatewayMessagePlan(
    messages = envelope.gatewayMessages + buildLocalContinuationDeltaMessages(
      transcriptDelta = transcriptDelta,
      promptOnlyDelta = promptOnlyDelta,
      replayToolResultProjections = cursor.replayToolResultProjections,
    ),
    mode = LocalContinuationMode.LOCAL_DELTA,
    reason = if (transcriptDelta.isEmpty()) "steady_turn" else "transcript_delta",
  )
}

internal fun OpenCrayAgentRuntime.fullGatewayMessageRebuild(
  cursor: PromptTurnCursor,
  frontContextPrompts: List<String>,
  transcript: List<RuntimeConversationMessage>,
  reason: String,
): GatewayMessagePlan = GatewayMessagePlan(
  messages = buildGatewayMessages(
    frontContextPrompts = frontContextPrompts,
    transcript = transcript,
    replayToolResultProjections = cursor.replayToolResultProjections,
  ),
  mode = LocalContinuationMode.FULL_REBUILD,
    reason = reason,
)

internal fun OpenCrayAgentRuntime.buildLocalContinuationDeltaMessages(
  transcriptDelta: List<RuntimeConversationMessage>,
  promptOnlyDelta: List<RuntimeConversationMessage>,
  replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>?,
): List<LiteLlmGatewayMessage> = buildGatewayMessages(
  frontContextPrompts = emptyList(),
  transcript = transcriptDelta + promptOnlyDelta,
  replayToolResultProjections = replayToolResultProjections,
)

internal fun patchDynamicFrontContextGatewayMessages(
  gatewayMessages: List<LiteLlmGatewayMessage>,
  existingFrontContextZones: FrontContextZones,
  requestedFrontContextZones: FrontContextZones,
): List<LiteLlmGatewayMessage>? {
  val existingFrontContextPrompts = existingFrontContextZones.promptsInTransportOrder
  if (!gatewayMessagesStartWithFrontContextPrompts(gatewayMessages, existingFrontContextPrompts)) {
    return null
  }
  return requestedFrontContextZones.promptsInTransportOrder.map { prompt -> frontContextGatewayMessage(prompt) } +
    gatewayMessages.drop(existingFrontContextPrompts.size)
}

internal fun gatewayMessagesStartWithFrontContextPrompts(
  gatewayMessages: List<LiteLlmGatewayMessage>,
  frontContextPrompts: List<String>,
): Boolean {
  if (frontContextPrompts.size > gatewayMessages.size) {
    return false
  }
  return frontContextPrompts.indices.all { index ->
    val message = gatewayMessages[index]
    message.role == LiteLlmGatewayMessageRole.USER &&
      message.attachments.isEmpty() &&
      message.toolCalls.isEmpty() &&
      message.toolResult == null &&
      message.assistantPhase == null &&
      message.content?.trim().orEmpty() == frontContextPrompts[index]
  }
}

internal fun frontContextGatewayMessage(prompt: String): LiteLlmGatewayMessage =
  LiteLlmGatewayMessage(
    role = LiteLlmGatewayMessageRole.USER,
    content = prompt,
  )

internal fun OpenCrayAgentRuntime.contextCacheBreakReason(
  cursor: PromptTurnCursor,
  plan: GatewayMessagePlan,
): String? = deriveContextCacheBreakReason(
  localContinuationReason = plan.reason,
  hasHistoricalResponsesContinuation = config.promptResumeState != null ||
    cursor.turn > 0 ||
    cursor.toolCallCount > 0 ||
    !cursor.responsesPreviousResponseId.isNullOrBlank() ||
    !cursor.responsesProviderLineageId.isNullOrBlank() ||
    cursor.responsesPendingMessages.isNotEmpty(),
)

internal fun OpenCrayAgentRuntime.updateResponsesContinuationState(
  cursor: PromptTurnCursor,
  gatewayResult: LiteLlmGatewayResult,
  continuationShape: ResponsesContinuationShape,
) {
  if (!isResponsesProtocol()) {
    invalidateResponsesLineage(cursor)
    return
  }
  val providerResponseId = gatewayResult.providerResponseId?.trim()?.takeIf(String::isNotBlank)
  if (providerResponseId == null) {
    invalidateResponsesLineage(cursor)
    return
  }
  val providerLineageId = gatewayResult.providerLineageId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: cursor.responsesProviderLineageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
    ?: providerResponseId
  cursor.responsesPreviousResponseId = providerResponseId
  cursor.responsesProviderLineageId = providerLineageId
  cursor.responsesLineageTrusted = providerLineageId.isNotBlank()
  cursor.responsesFullReplayRequired = false
  cursor.responsesContinuationShape = continuationShape
  cursor.responsesPendingMessages.clear()
}

internal fun invalidateResponsesLineage(cursor: PromptTurnCursor) {
  cursor.responsesPreviousResponseId = null
  cursor.responsesProviderLineageId = null
  cursor.responsesLineageTrusted = false
  cursor.responsesContinuationShape = null
  cursor.responsesPendingMessages.clear()
}

internal fun OpenCrayAgentRuntime.refreshLocalContinuationEnvelope(
  cursor: PromptTurnCursor,
  frontContextPrompts: List<String>,
  stableAnchor: String,
  gatewayMessagesEnabled: Boolean,
  toolPoolFingerprint: String,
  toolSchemaFingerprint: String,
  requestSettingsFingerprint: String,
) {
  cursor.localContinuationEnvelope = buildLocalContinuationEnvelope(
    cursor = cursor,
    frontContextPrompts = frontContextPrompts,
    stableAnchor = stableAnchor,
    gatewayMessagesEnabled = gatewayMessagesEnabled,
    toolPoolFingerprint = toolPoolFingerprint,
    toolSchemaFingerprint = toolSchemaFingerprint,
    requestSettingsFingerprint = requestSettingsFingerprint,
  )
}

internal fun invalidateLocalContinuation(cursor: PromptTurnCursor) {
  cursor.localContinuationEnvelope = null
}

internal fun OpenCrayAgentRuntime.buildLocalContinuationEnvelope(
  cursor: PromptTurnCursor,
  frontContextPrompts: List<String>,
  stableAnchor: String,
  gatewayMessagesEnabled: Boolean,
  toolPoolFingerprint: String,
  toolSchemaFingerprint: String,
  requestSettingsFingerprint: String,
): LocalContinuationEnvelope? {
  if (isResponsesProtocol() || !gatewayMessagesEnabled) {
    return null
  }
  val normalizedFrontContextZones = normalizeFrontContextZones(frontContextPrompts)
  return LocalContinuationEnvelope(
    stableAnchor = stableAnchor,
    frontContextZones = normalizedFrontContextZones,
    toolPoolFingerprint = toolPoolFingerprint,
    toolSchemaFingerprint = toolSchemaFingerprint,
    requestSettingsFingerprint = requestSettingsFingerprint,
    transcriptFrontier = cursor.transcript.toList(),
    gatewayMessages = buildGatewayMessages(
      frontContextPrompts = normalizedFrontContextZones.promptsInTransportOrder,
      transcript = cursor.transcript,
      replayToolResultProjections = cursor.replayToolResultProjections,
    ),
  )
}

internal fun transcriptDeltaSince(
  frontier: List<RuntimeConversationMessage>,
  transcript: List<RuntimeConversationMessage>,
): List<RuntimeConversationMessage>? {
  if (frontier.size > transcript.size) {
    return null
  }
  if (!transcript.subList(0, frontier.size).equals(frontier)) {
    return null
  }
  return transcript.drop(frontier.size)
}

internal fun normalizeFrontContextZones(
  prompts: List<String>,
): FrontContextZones = FrontContextZones.fromTransportPrompts(prompts)

internal fun stableLocalContinuationAnchor(assembledPrompt: com.opencray.runtime.context.AssembledPrompt): String =
  assembledPrompt.layers
    .filter { layer ->
      layer.transportGroup == PromptLayerTransportGroup.SYSTEM_PREFIX
    }
    .joinToString(separator = "\n\n") { layer ->
      buildString {
        append("[")
        append(layer.kind.name)
        append(":")
        append(layer.name)
        appendLine("]")
        append(stableLocalContinuationLayerContent(layer))
      }
    }

internal fun stableLocalContinuationLayerContent(
  layer: com.opencray.runtime.context.PromptLayer,
): String = layer.content.trim()

internal fun OpenCrayAgentRuntime.buildGatewayMessages(
  frontContextPrompts: List<String>,
  transcript: List<RuntimeConversationMessage>,
  replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>? = null,
): List<LiteLlmGatewayMessage> {
  val messages = mutableListOf<LiteLlmGatewayMessage>()
  frontContextPrompts
    .map(String::trim)
    .filter(String::isNotBlank)
    .forEach { prompt ->
      messages += LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.USER,
        content = prompt,
      )
    }
  var syntheticToolCallIndex = nextSyntheticToolCallSequence(transcript) - 1
  var pendingToolCallId: String? = null
  transcript.forEach { entry ->
    when (entry.role) {
      RuntimeConversationRole.SYSTEM -> {
        messages += LiteLlmGatewayMessage(
          role = LiteLlmGatewayMessageRole.USER,
          content = "[system]\n${entry.content}",
        )
      }

      RuntimeConversationRole.USER -> {
        messages += LiteLlmGatewayMessage(
          role = LiteLlmGatewayMessageRole.USER,
          content = entry.content,
          attachments = entry.attachments.map { attachment ->
            liteLlmGatewayAttachmentFor(attachment)
          },
        )
      }

      RuntimeConversationRole.ASSISTANT -> {
        if (entry.kind == RuntimeConversationMessageKind.COMMENTARY) {
          val commentary = entry.commentary?.text?.trim()?.takeIf(String::isNotBlank)
            ?: entry.content.trim().takeIf(String::isNotBlank)
          commentary?.let { commentaryText ->
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              content = commentaryText,
              assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase()
                ?: LiteLlmAssistantPhase.COMMENTARY,
            )
          }
          return@forEach
        }
        val toolCall = runtimeToolCallFor(entry)
        if (toolCall != null) {
          val normalizedToolCall = toolCall.id
            ?.takeIf(String::isNotBlank)
            ?.let { existingId -> toolCall.copy(id = existingId) }
            ?: toolCall.copy(
              id = "oc-call-${++syntheticToolCallIndex}",
            )
          pendingToolCallId = normalizedToolCall.id
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.ASSISTANT,
            toolCalls = listOf(
              LiteLlmStructuredToolCall(
                id = normalizedToolCall.id,
                toolName = normalizedToolCall.toolName,
                arguments = normalizedToolCall.arguments,
                reason = normalizedToolCall.reason,
              ),
            ),
            assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase(),
          )
        } else {
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.ASSISTANT,
            content = entry.content,
            assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase(),
          )
        }
      }

      RuntimeConversationRole.TOOL -> {
        if (entry.kind == RuntimeConversationMessageKind.COMMENTARY) {
          val commentaryText = entry.commentary?.text?.trim()?.takeIf(String::isNotBlank)
            ?: entry.content.trim().takeIf(String::isNotBlank)
          commentaryText?.let { commentary ->
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              content = commentary,
              assistantPhase = LiteLlmAssistantPhase.COMMENTARY,
            )
          }
          return@forEach
        }
        val observation = runtimeToolResultFor(entry)
        if (observation != null) {
          val canonicalToolResult = LiteLlmGatewayToolResult(
            toolCallId = observation.toolCallId ?: pendingToolCallId,
            toolName = observation.toolName,
            content = observation.content,
            structuredContent = observation.structuredContent,
            isError = observation.isError,
            exitCode = observation.exitCode,
            stdout = observation.stdout,
            stderr = observation.stderr,
            errorCode = observation.errorCode,
            errorMessage = observation.errorMessage,
            metadata = observation.metadata,
          )
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.TOOL,
            toolResult = applyFrozenReplayProjection(
              entry = entry,
              toolResult = canonicalToolResult,
              replayToolResultProjections = replayToolResultProjections,
            ),
          )
          pendingToolCallId = null
        } else {
          projectedPlainToolTranscriptContent(entry)?.let { toolContent ->
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.USER,
              content = "[tool]\n$toolContent",
            )
          }
        }
      }
    }
  }
  return messages
}
internal fun OpenCrayAgentRuntime.applyFrozenReplayProjection(
  entry: RuntimeConversationMessage,
  toolResult: LiteLlmGatewayToolResult,
  replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>?,
): LiteLlmGatewayToolResult {
  if (replayToolResultProjections == null) {
    return toolResult
  }
  val projectionKey = toolResultReplayProjector.projectionKey(
    entry = entry,
    toolResult = toolResult,
  )
  replayToolResultProjections[projectionKey]?.let { projection ->
    return projection.restoredToolResult()
  }
  val projection = toolResultReplayProjector.maybeProject(
    entry = entry,
    toolResult = toolResult,
  ) ?: return toolResult
  replayToolResultProjections.putIfAbsent(
    projection.projectionKey,
    projection,
  )
  return replayToolResultProjections[projection.projectionKey]
    ?.restoredToolResult()
    ?: projection.restoredToolResult()
}
internal fun projectedPlainToolTranscriptContent(
  entry: RuntimeConversationMessage,
): String? {
  val content = entry.content.trim().takeIf(String::isNotBlank) ?: return null
  if (entry.role != RuntimeConversationRole.TOOL || entry.kind != RuntimeConversationMessageKind.PLAIN) {
    return content
  }
  return runCatching {
    com.opencray.runtime.context.ContextPruner()
      .prune(listOf(entry.copy(content = content)))
      .messages
      .single()
      .content
      .trim()
  }.getOrNull()?.takeIf(String::isNotBlank) ?: content
}

internal fun PromptRunDiagnostics.recordGatewayMessagePlan(
  plan: GatewayMessagePlan,
  contextCacheBreakReason: String?,
) {
  localContinuationLastMode = plan.mode
  localContinuationLastReason = plan.reason
  this.contextCacheBreakReason = contextCacheBreakReason
  responsesPendingContextUpdateCount = plan.responsesPendingContextUpdateCount
  responsesPendingContextUpdateHash = plan.responsesPendingContextUpdateHash
  if (plan.mode == LocalContinuationMode.LOCAL_DELTA || plan.mode == LocalContinuationMode.LOCAL_FRONT_PATCH) {
    localContinuationUsedCount += 1
  } else if (plan.mode == LocalContinuationMode.RESPONSES_NATIVE) {
    localContinuationUsedCount += 1
  } else if (
    plan.mode == LocalContinuationMode.FULL_REBUILD &&
    (
        plan.reason == "anchor_changed" ||
          plan.reason == "durable_context_changed" ||
          plan.reason == "dynamic_context_changed" ||
          plan.reason == "transcript_mismatch" ||
          plan.reason == "responses_shape_unavailable" ||
          plan.reason == "responses_context_update_chain_limit" ||
          plan.reason == "responses_context_update_too_large" ||
          plan.reason == "tool_pool_changed" ||
          plan.reason == "tool_schema_changed" ||
          plan.reason == "user_setting_changed"
      )
  ) {
    localContinuationFallbackCount += 1
  }
}

internal fun PromptRunDiagnostics.recordContextCacheShapeMetadata(
  metadata: Map<String, String>,
) {
  contextCacheShapeMetadata = metadata
}

internal fun LocalContinuationEnvelope.toSerializable(): OpenCraySerializableLocalContinuationEnvelope =
  OpenCraySerializableLocalContinuationEnvelope(
    stableAnchor = stableAnchor,
    frontContextPrompts = frontContextPrompts,
    durableContextPrompt = frontContextZones.durableContextPrompt.takeIf(String::isNotBlank),
    dynamicContextPrompt = frontContextZones.dynamicContextPrompt.takeIf(String::isNotBlank),
    toolPoolFingerprint = toolPoolFingerprint,
    toolSchemaFingerprint = toolSchemaFingerprint,
    requestSettingsFingerprint = requestSettingsFingerprint,
    transcriptFrontier = transcriptFrontier,
    gatewayMessages = gatewayMessages.map(OpenCraySerializableGatewayMessage::from),
  )

internal fun ResponsesContinuationShape.toSerializable(): OpenCraySerializableResponsesContinuationShape =
  OpenCraySerializableResponsesContinuationShape(
    stableAnchor = stableAnchor,
    durableContextPrompt = baseline.durableContextPrompt.takeIf(String::isNotBlank),
    dynamicContextHash = referenceState.dynamicContextHash,
    appliedContextUpdateCount = referenceState.appliedUpdateCount,
    toolPoolFingerprint = toolPoolFingerprint,
    toolSchemaFingerprint = toolSchemaFingerprint,
    requestSettingsFingerprint = requestSettingsFingerprint,
  )
