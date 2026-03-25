package com.opencray.runtime

import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentHandleState
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
data class OpenCraySerializableGatewayMessage(
  val role: String,
  val content: String? = null,
  val toolCalls: List<OpenCraySerializableToolCall> = emptyList(),
  val toolResult: OpenCraySerializableGatewayToolResult? = null,
  val assistantPhase: String? = null,
) {
  init {
    require(role.isNotBlank()) { "OpenCraySerializableGatewayMessage role must not be blank." }
    require(
      !content.isNullOrBlank() || toolCalls.isNotEmpty() || toolResult != null,
    ) {
      "OpenCraySerializableGatewayMessage must carry content, toolCalls, or toolResult."
    }
    require(assistantPhase == null || assistantPhase.isNotBlank()) {
      "OpenCraySerializableGatewayMessage assistantPhase must not be blank."
    }
  }

  fun toGatewayMessage(): LiteLlmGatewayMessage = LiteLlmGatewayMessage(
    role = LiteLlmGatewayMessageRole.valueOf(role),
    content = content,
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
  }

  fun restoredGatewayMessages(): List<LiteLlmGatewayMessage> =
    gatewayMessages.map(OpenCraySerializableGatewayMessage::toGatewayMessage)

  fun restoredTranscriptFrontier(
    fallback: List<RuntimeConversationMessage>,
  ): List<RuntimeConversationMessage> = transcriptFrontier.takeIf { frontier ->
    frontier.isNotEmpty()
  } ?: fallback
}

@Serializable
sealed interface OpenCraySerializableModelAction {
  @Serializable
  @SerialName("progress")
  data class Progress(
    val text: String,
    val stage: String? = null,
  ) : OpenCraySerializableModelAction {
    init {
      require(text.isNotBlank()) { "OpenCraySerializableModelAction.Progress text must not be blank." }
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
  val transcriptDelta: List<RuntimeConversationMessage> = emptyList(),
  val pendingToolCall: OpenCraySerializableToolCall? = null,
  val localContinuationEnvelope: OpenCraySerializableLocalContinuationEnvelope? = null,
  val responsesPreviousResponseId: String? = null,
  val responsesProviderLineageId: String? = null,
  val responsesLineageTrusted: Boolean = false,
  val responsesPendingMessages: List<OpenCraySerializableGatewayMessage> = emptyList(),
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
}

object OpenCrayPromptResumeMetadata {
  const val KEY_PROMPT_RESUME_JSON: String = "opencray_prompt_resume_json"

  fun encodeToMetadata(
    state: OpenCrayPromptResumeState,
    json: Json,
  ): Map<String, String> = mapOf(
    KEY_PROMPT_RESUME_JSON to json.encodeToString(
      serializer = OpenCrayPromptResumeState.serializer(),
      value = state,
    ),
  )

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
}
