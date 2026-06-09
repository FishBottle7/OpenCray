package com.opencray.runtime

import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.context.FrontContextZones
import com.opencray.runtime.context.FrozenToolResultReplayProjection
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentHandleState
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class OpenCrayPromptCheckpointBoundary(
  val wireValue: String,
) {
  PRE_MODEL_REQUEST("pre_model_request"),
  ACTION_BATCH_PARSED("action_batch_parsed"),
  COMMENTARY_EMITTED("commentary_emitted"),
  TOOL_RESULT_COMMITTED("tool_result_committed"),
  SUPPLEMENT_INGESTED("supplement_ingested");

  companion object {
    fun fromWireValue(raw: String): OpenCrayPromptCheckpointBoundary? =
      entries.firstOrNull { boundary -> boundary.wireValue.equals(raw, ignoreCase = true) }
  }
}

data class OpenCrayPromptCheckpointEmission(
  val boundary: OpenCrayPromptCheckpointBoundary,
  val state: OpenCrayPromptResumeState,
  val emittedAtEpochMs: Long,
  val toolName: String? = null,
)

@Serializable
data class OpenCraySerializableToolCall(
  val id: String? = null,
  val toolName: String,
  val arguments: JsonObject = JsonObject(emptyMap()),
  val reason: String? = null,
) {
  init {
    require(id == null || id.isNotBlank()) { "OpenCraySerializableToolCall id must not be blank." }
    require(toolName.isNotBlank()) { "OpenCraySerializableToolCall toolName must not be blank." }
  }

  fun toAgentToolCall(): AgentToolCall = AgentToolCall(
    id = id,
    toolName = toolName,
    arguments = arguments,
    reason = reason,
  )

  companion object {
    fun from(call: AgentToolCall): OpenCraySerializableToolCall = OpenCraySerializableToolCall(
      id = call.id,
      toolName = call.toolName,
      arguments = call.arguments,
      reason = call.reason,
    )
  }
}

@Serializable
data class OpenCraySerializableGatewayToolResult(
  val toolCallId: String? = null,
  val toolName: String? = null,
  val content: String,
  val structuredContent: JsonObject? = null,
  val isError: Boolean? = null,
  val exitCode: Int? = null,
  val stdout: String? = null,
  val stderr: String? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(content.isNotBlank()) { "OpenCraySerializableGatewayToolResult content must not be blank." }
    require(toolCallId == null || toolCallId.isNotBlank()) {
      "OpenCraySerializableGatewayToolResult toolCallId must not be blank."
    }
    require(toolName == null || toolName.isNotBlank()) {
      "OpenCraySerializableGatewayToolResult toolName must not be blank."
    }
  }

  fun toGatewayToolResult(): LiteLlmGatewayToolResult = LiteLlmGatewayToolResult(
    toolCallId = toolCallId,
    toolName = toolName,
    content = content,
    structuredContent = structuredContent,
    isError = isError,
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    errorCode = errorCode,
    errorMessage = errorMessage,
    metadata = metadata,
  )

  companion object {
    fun from(result: LiteLlmGatewayToolResult): OpenCraySerializableGatewayToolResult = OpenCraySerializableGatewayToolResult(
      toolCallId = result.toolCallId,
      toolName = result.toolName,
      content = result.content,
      structuredContent = result.structuredContent,
      isError = result.isError,
      exitCode = result.exitCode,
      stdout = result.stdout,
      stderr = result.stderr,
      errorCode = result.errorCode,
      errorMessage = result.errorMessage,
      metadata = result.metadata,
    )
  }
}

@Serializable
data class OpenCraySerializableGatewayAttachment(
  val attachmentId: String? = null,
  val kind: String,
  val displayName: String? = null,
  val filePath: String? = null,
  val mimeType: String? = null,
  val transcriptText: String? = null,
) {
  init {
    require(kind.isNotBlank()) { "OpenCraySerializableGatewayAttachment kind must not be blank." }
    require(attachmentId == null || attachmentId.isNotBlank()) {
      "OpenCraySerializableGatewayAttachment attachmentId must not be blank."
    }
    require(displayName == null || displayName.isNotBlank()) {
      "OpenCraySerializableGatewayAttachment displayName must not be blank."
    }
    require(filePath == null || filePath.isNotBlank()) {
      "OpenCraySerializableGatewayAttachment filePath must not be blank."
    }
    require(mimeType == null || mimeType.isNotBlank()) {
      "OpenCraySerializableGatewayAttachment mimeType must not be blank."
    }
    require(transcriptText == null || transcriptText.isNotBlank()) {
      "OpenCraySerializableGatewayAttachment transcriptText must not be blank."
    }
  }

  fun toGatewayAttachment(): LiteLlmGatewayAttachment = LiteLlmGatewayAttachment(
    attachmentId = attachmentId,
    kind = LiteLlmGatewayAttachmentKind.valueOf(kind),
    displayName = displayName,
    filePath = filePath,
    mimeType = mimeType,
    transcriptText = transcriptText,
  )

  companion object {
    fun from(attachment: LiteLlmGatewayAttachment): OpenCraySerializableGatewayAttachment =
      OpenCraySerializableGatewayAttachment(
        attachmentId = attachment.attachmentId,
        kind = attachment.kind.name,
        displayName = attachment.displayName,
        filePath = attachment.filePath,
        mimeType = attachment.mimeType,
        transcriptText = attachment.transcriptText,
      )
  }
}

@Serializable
data class OpenCraySerializableGatewayMessage(
  val role: String,
  val content: String? = null,
  val attachments: List<OpenCraySerializableGatewayAttachment> = emptyList(),
  val toolCalls: List<OpenCraySerializableToolCall> = emptyList(),
  val toolResult: OpenCraySerializableGatewayToolResult? = null,
  val assistantPhase: String? = null,
) {
  init {
    require(role.isNotBlank()) { "OpenCraySerializableGatewayMessage role must not be blank." }
    require(
      !content.isNullOrBlank() || attachments.isNotEmpty() || toolCalls.isNotEmpty() || toolResult != null,
    ) {
      "OpenCraySerializableGatewayMessage must carry content, attachments, toolCalls, or toolResult."
    }
    require(assistantPhase == null || assistantPhase.isNotBlank()) {
      "OpenCraySerializableGatewayMessage assistantPhase must not be blank."
    }
  }

  fun toGatewayMessage(): LiteLlmGatewayMessage = LiteLlmGatewayMessage(
    role = LiteLlmGatewayMessageRole.valueOf(role),
    content = content,
    attachments = attachments.map(OpenCraySerializableGatewayAttachment::toGatewayAttachment),
    toolCalls = toolCalls.map { call ->
      LiteLlmStructuredToolCall(
        id = call.id,
        toolName = call.toolName,
        arguments = call.arguments,
        reason = call.reason,
      )
    },
    toolResult = toolResult?.toGatewayToolResult(),
    assistantPhase = assistantPhase
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(LiteLlmAssistantPhase::valueOf),
  )

  companion object {
    fun from(message: LiteLlmGatewayMessage): OpenCraySerializableGatewayMessage = OpenCraySerializableGatewayMessage(
      role = message.role.name,
      content = message.content,
      attachments = message.attachments.map(OpenCraySerializableGatewayAttachment::from),
      toolCalls = message.toolCalls.map { toolCall ->
        OpenCraySerializableToolCall(
          id = toolCall.id,
          toolName = toolCall.toolName,
          arguments = toolCall.arguments,
          reason = toolCall.reason,
        )
      },
      toolResult = message.toolResult?.let(OpenCraySerializableGatewayToolResult::from),
      assistantPhase = message.assistantPhase?.name,
    )
  }
}

@Serializable
data class OpenCraySerializableLocalContinuationEnvelope(
  val stableAnchor: String,
  val frontContextPrompts: List<String> = emptyList(),
  val durableContextPrompt: String? = null,
  val dynamicContextPrompt: String? = null,
  val toolPoolFingerprint: String? = null,
  val toolSchemaFingerprint: String? = null,
  val requestSettingsFingerprint: String? = null,
  val transcriptFrontier: List<RuntimeConversationMessage> = emptyList(),
  val gatewayMessages: List<OpenCraySerializableGatewayMessage>,
) {
  init {
    require(stableAnchor.isNotBlank()) {
      "OpenCraySerializableLocalContinuationEnvelope stableAnchor must not be blank."
    }
    require(gatewayMessages.isNotEmpty()) {
      "OpenCraySerializableLocalContinuationEnvelope gatewayMessages must not be empty."
    }
    require(toolPoolFingerprint == null || toolPoolFingerprint.isNotBlank()) {
      "OpenCraySerializableLocalContinuationEnvelope toolPoolFingerprint must not be blank."
    }
    require(toolSchemaFingerprint == null || toolSchemaFingerprint.isNotBlank()) {
      "OpenCraySerializableLocalContinuationEnvelope toolSchemaFingerprint must not be blank."
    }
    require(requestSettingsFingerprint == null || requestSettingsFingerprint.isNotBlank()) {
      "OpenCraySerializableLocalContinuationEnvelope requestSettingsFingerprint must not be blank."
    }
  }

  fun restoredGatewayMessages(): List<LiteLlmGatewayMessage> =
    gatewayMessages.map(OpenCraySerializableGatewayMessage::toGatewayMessage)

  fun restoredFrontContextZones(): FrontContextZones {
    val legacyZones = FrontContextZones.fromTransportPrompts(frontContextPrompts)
    return FrontContextZones(
      durableContextPrompt = durableContextPrompt
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: legacyZones.durableContextPrompt,
      dynamicContextPrompt = dynamicContextPrompt
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: legacyZones.dynamicContextPrompt,
    )
  }

  fun restoredTranscriptFrontier(
    fallback: List<RuntimeConversationMessage>,
  ): List<RuntimeConversationMessage> = transcriptFrontier.takeIf { frontier ->
    frontier.isNotEmpty()
  } ?: fallback
}

@Serializable
data class OpenCraySerializableResponsesContinuationShape(
  val stableAnchor: String,
  val durableContextPrompt: String? = null,
  val dynamicContextPrompt: String? = null,
  val dynamicContextHash: String? = null,
  val appliedContextUpdateCount: Int = 0,
  val toolPoolFingerprint: String,
  val toolSchemaFingerprint: String,
  val requestSettingsFingerprint: String,
) {
  init {
    require(stableAnchor.isNotBlank()) {
      "OpenCraySerializableResponsesContinuationShape stableAnchor must not be blank."
    }
    require(toolPoolFingerprint.isNotBlank()) {
      "OpenCraySerializableResponsesContinuationShape toolPoolFingerprint must not be blank."
    }
    require(toolSchemaFingerprint.isNotBlank()) {
      "OpenCraySerializableResponsesContinuationShape toolSchemaFingerprint must not be blank."
    }
    require(requestSettingsFingerprint.isNotBlank()) {
      "OpenCraySerializableResponsesContinuationShape requestSettingsFingerprint must not be blank."
    }
    require(dynamicContextHash == null || dynamicContextHash.isNotBlank()) {
      "OpenCraySerializableResponsesContinuationShape dynamicContextHash must not be blank."
    }
    require(appliedContextUpdateCount >= 0) {
      "OpenCraySerializableResponsesContinuationShape appliedContextUpdateCount must be >= 0."
    }
  }

  fun restoredFrontContextZones(): FrontContextZones = FrontContextZones(
    durableContextPrompt = durableContextPrompt?.trim()?.takeIf(String::isNotBlank).orEmpty(),
    dynamicContextPrompt = dynamicContextPrompt?.trim()?.takeIf(String::isNotBlank).orEmpty(),
  )
}

@Serializable
sealed interface OpenCraySerializableModelAction {
  @Serializable
  @SerialName("commentary")
  data class Commentary(
    val text: String,
    val stage: String? = null,
  ) : OpenCraySerializableModelAction {
    init {
      require(text.isNotBlank()) { "OpenCraySerializableModelAction.Commentary text must not be blank." }
    }
  }

  @Serializable
  @SerialName("tool_call")
  data class ToolCall(
    val call: OpenCraySerializableToolCall,
  ) : OpenCraySerializableModelAction

  @Serializable
  @SerialName("final")
  data class Final(
    val answer: String,
    val responseFormat: String,
    val attachments: List<OpenCrayFinalAttachment> = emptyList(),
  ) : OpenCraySerializableModelAction {
    init {
      require(answer.isNotBlank() || attachments.isNotEmpty()) {
        "OpenCraySerializableModelAction.Final answer must not be blank unless attachments are present."
      }
      require(responseFormat.isNotBlank()) {
        "OpenCraySerializableModelAction.Final responseFormat must not be blank."
      }
    }
  }
}

@Serializable
data class OpenCrayPromptResumeState(
  val transcript: List<RuntimeConversationMessage> = emptyList(),
  val turnIndex: Int,
  val toolCallCount: Int,
  val pendingActions: List<OpenCraySerializableModelAction> = emptyList(),
  val nextActionIndex: Int = 0,
  val requiresSingleActionReminder: Boolean = false,
  val activeSkillName: String? = null,
  val activeSkillActivationSource: String? = null,
  val activeSkillPinned: Boolean = false,
  val transcriptDelta: List<RuntimeConversationMessage> = emptyList(),
  val pendingToolCall: OpenCraySerializableToolCall? = null,
  val localContinuationEnvelope: OpenCraySerializableLocalContinuationEnvelope? = null,
  val responsesPreviousResponseId: String? = null,
  val responsesProviderLineageId: String? = null,
  val responsesLineageTrusted: Boolean = false,
  val responsesContinuationShape: OpenCraySerializableResponsesContinuationShape? = null,
  val responsesPendingMessages: List<OpenCraySerializableGatewayMessage> = emptyList(),
  val replayToolResultProjections: Map<String, FrozenToolResultReplayProjection> = emptyMap(),
  val subAgentHandles: List<SubAgentHandleState> = emptyList(),
) {
  init {
    require(turnIndex >= 0) { "OpenCrayPromptResumeState turnIndex must be >= 0." }
    require(toolCallCount >= 0) { "OpenCrayPromptResumeState toolCallCount must be >= 0." }
    require(nextActionIndex >= 0) { "OpenCrayPromptResumeState nextActionIndex must be >= 0." }
  }

  fun transcriptFor(seededTranscript: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> =
    transcript.takeIf { it.isNotEmpty() }
      ?: seededTranscript + transcriptDelta

  fun resumableActions(): List<OpenCraySerializableModelAction> =
    if (pendingActions.isNotEmpty()) {
      pendingActions
    } else {
      pendingToolCall?.let { pending ->
        listOf(OpenCraySerializableModelAction.ToolCall(call = pending))
      }.orEmpty()
    }

  fun normalizedNextActionIndex(): Int {
    val actions = resumableActions()
    if (actions.isEmpty()) {
      return 0
    }
    return nextActionIndex.coerceIn(0, actions.lastIndex)
  }

  fun restoredResponsesPendingMessages(): List<LiteLlmGatewayMessage> =
    responsesPendingMessages.map(OpenCraySerializableGatewayMessage::toGatewayMessage)

  fun withAppendedUserInput(input: String): OpenCrayPromptResumeState {
    val normalizedInput = input.trim()
    if (normalizedInput.isBlank()) {
      return this
    }
    return withAppendedUserMessage(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = normalizedInput,
      ),
    )
  }

  fun withAppendedUserMessage(message: RuntimeConversationMessage): OpenCrayPromptResumeState {
    require(message.role == RuntimeConversationRole.USER) {
      "OpenCrayPromptResumeState can only append user messages."
    }
    if (message.content.trim().isBlank() && message.attachments.isEmpty()) {
      return this
    }
    return if (transcript.isNotEmpty()) {
      copy(transcript = transcript + message)
    } else {
      copy(transcriptDelta = transcriptDelta + message)
    }
  }
}

object OpenCrayPromptResumeMetadata {
  const val KEY_PROMPT_RESUME_JSON: String = "opencray_prompt_resume_json"
  const val KEY_PROMPT_CHECKPOINT_BOUNDARY: String = "opencray_prompt_checkpoint_boundary"

  fun sanitizeToolResultMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> {
    if (metadata.isEmpty()) {
      return metadata
    }
    val sanitized = linkedMapOf<String, String>()
    var changed = false
    metadata.forEach { (key, value) ->
      if (key in hiddenToolResultMetadataKeys()) {
        changed = true
      } else {
        sanitized[key] = value
      }
    }
    return if (changed) sanitized else metadata
  }

  fun normalizeState(
    state: OpenCrayPromptResumeState,
    json: Json,
  ): OpenCrayPromptResumeState {
    val transcript = normalizeRuntimeMessages(state.transcript, json)
    val transcriptDelta = normalizeRuntimeMessages(state.transcriptDelta, json)
    val pendingMessages = normalizeGatewayMessages(state.responsesPendingMessages)
    val continuationEnvelope = state.localContinuationEnvelope?.let { envelope ->
      val normalizedFrontier = normalizeRuntimeMessages(envelope.transcriptFrontier, json)
      val normalizedGatewayMessages = normalizeGatewayMessages(envelope.gatewayMessages)
      if (
        normalizedFrontier == envelope.transcriptFrontier &&
        normalizedGatewayMessages == envelope.gatewayMessages
      ) {
        envelope
      } else {
        envelope.copy(
          transcriptFrontier = normalizedFrontier,
          gatewayMessages = normalizedGatewayMessages,
        )
      }
    }
    val replayToolResultProjections = state.replayToolResultProjections.mapValues { (_, projection) ->
      val normalizedToolResult = normalizeGatewayToolResult(projection.projectedToolResult)
      if (normalizedToolResult == projection.projectedToolResult) {
        projection
      } else {
        projection.copy(projectedToolResult = normalizedToolResult)
      }
    }
    if (
      transcript == state.transcript &&
      transcriptDelta == state.transcriptDelta &&
      pendingMessages == state.responsesPendingMessages &&
      continuationEnvelope == state.localContinuationEnvelope &&
      replayToolResultProjections == state.replayToolResultProjections
    ) {
      return state
    }
    return state.copy(
      transcript = transcript,
      transcriptDelta = transcriptDelta,
      localContinuationEnvelope = continuationEnvelope,
      responsesPendingMessages = pendingMessages,
      replayToolResultProjections = replayToolResultProjections,
    )
  }

  fun encodeToMetadata(
    state: OpenCrayPromptResumeState,
    json: Json,
    checkpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
  ): Map<String, String> {
    val normalizedState = normalizeState(state, json)
    return mapOf(
      KEY_PROMPT_RESUME_JSON to json.encodeToString(
        serializer = OpenCrayPromptResumeState.serializer(),
        value = normalizedState,
      ),
    ) + checkpointBoundary?.let { boundary ->
      mapOf(KEY_PROMPT_CHECKPOINT_BOUNDARY to boundary.wireValue)
    }.orEmpty()
  }

  fun normalizeMetadata(
    metadata: Map<String, String>,
    json: Json,
  ): Map<String, String> {
    val state = decodeFromMetadata(metadata, json) ?: return metadata
    val normalizedState = normalizeState(state, json)
    if (normalizedState == state) {
      return metadata
    }
    return metadata + mapOf(
      KEY_PROMPT_RESUME_JSON to json.encodeToString(
        serializer = OpenCrayPromptResumeState.serializer(),
        value = normalizedState,
      ),
    )
  }

  fun decodeFromMetadata(
    metadata: Map<String, String>,
    json: Json,
  ): OpenCrayPromptResumeState? {
    val encoded = metadata[KEY_PROMPT_RESUME_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    return runCatching {
      json.decodeFromString(
        deserializer = OpenCrayPromptResumeState.serializer(),
        string = encoded,
      )
    }.getOrNull()
  }

  fun decodeCheckpointBoundary(
    metadata: Map<String, String>,
  ): OpenCrayPromptCheckpointBoundary? = metadata[KEY_PROMPT_CHECKPOINT_BOUNDARY]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(OpenCrayPromptCheckpointBoundary::fromWireValue)

  private fun hiddenToolResultMetadataKeys(): Set<String> = setOf(
    KEY_PROMPT_RESUME_JSON,
    KEY_PROMPT_CHECKPOINT_BOUNDARY,
    SubAgentApprovalResumeMetadata.KEY_PROMPT_RESUME_JSON,
  )
}

private fun normalizeRuntimeMessages(
  messages: List<RuntimeConversationMessage>,
  json: Json,
): List<RuntimeConversationMessage> {
  if (messages.isEmpty()) {
    return messages
  }
  val normalized = messages.map { message -> normalizeRuntimeMessage(message, json) }
  return if (normalized == messages) messages else normalized
}

private fun normalizeRuntimeMessage(
  message: RuntimeConversationMessage,
  json: Json,
): RuntimeConversationMessage {
  if (
    message.role != RuntimeConversationRole.TOOL ||
    message.kind != RuntimeConversationMessageKind.TOOL_RESULT
  ) {
    return message
  }
  val payload = message.content.trim().takeIf { content -> content.startsWith("{") } ?: return message
  val decoded = runCatching {
    json.parseToJsonElement(payload) as? JsonObject
  }.getOrNull() ?: return message
  val metadata = decoded["metadata"] as? JsonObject ?: return message
  val normalizedMetadata = OpenCrayPromptResumeMetadata.sanitizeToolResultMetadata(
    metadata.toStringMap(),
  )
  if (normalizedMetadata == metadata.toStringMap()) {
    return message
  }
  val normalizedPayload = JsonObject(
    buildMap<String, JsonElement> {
      decoded.forEach { (key, value) ->
        put(key, value)
      }
      put("metadata", normalizedMetadata.toJsonObject())
    },
  )
  return message.copy(
    content = json.encodeToString(
      serializer = JsonObject.serializer(),
      value = normalizedPayload,
    ),
  )
}

private fun normalizeGatewayMessages(
  messages: List<OpenCraySerializableGatewayMessage>,
): List<OpenCraySerializableGatewayMessage> {
  if (messages.isEmpty()) {
    return messages
  }
  val normalized = messages.map { message ->
    val toolResult = message.toolResult ?: return@map message
    val normalizedToolResult = normalizeGatewayToolResult(toolResult)
    if (normalizedToolResult == toolResult) {
      message
    } else {
      message.copy(toolResult = normalizedToolResult)
    }
  }
  return if (normalized == messages) messages else normalized
}

private fun normalizeGatewayToolResult(
  result: OpenCraySerializableGatewayToolResult,
): OpenCraySerializableGatewayToolResult {
  val normalizedMetadata = OpenCrayPromptResumeMetadata.sanitizeToolResultMetadata(result.metadata)
  return if (normalizedMetadata == result.metadata) {
    result
  } else {
    result.copy(metadata = normalizedMetadata)
  }
}

private fun JsonObject.toStringMap(): Map<String, String> = entries.associate { (key, value) ->
  key to value.jsonPrimitive.content
}

private fun Map<String, String>.toJsonObject(): JsonObject = JsonObject(
  entries.associate { (key, value) -> key to JsonPrimitive(value) },
)
