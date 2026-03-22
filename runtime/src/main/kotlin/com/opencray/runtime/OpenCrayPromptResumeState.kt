package com.opencray.runtime

import com.opencray.runtime.context.RuntimeConversationMessage
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
