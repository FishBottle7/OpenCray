package com.opencray.runtime.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun RuntimeConversationMessage.isAssistantToolCallMessage(): Boolean =
  role == RuntimeConversationRole.ASSISTANT &&
    kind == RuntimeConversationMessageKind.TOOL_CALL &&
    content.trim().startsWith("{")

internal fun RuntimeConversationMessage.toolCallJsonPayloadOrNull(): String? {
  if (role != RuntimeConversationRole.ASSISTANT || kind != RuntimeConversationMessageKind.TOOL_CALL) {
    return null
  }
  return plainJsonPayloadOrNull(content)
}

internal fun RuntimeConversationMessage.toolResultJsonPayloadOrNull(): String? {
  if (role != RuntimeConversationRole.TOOL || kind != RuntimeConversationMessageKind.TOOL_RESULT) {
    return null
  }
  return plainJsonPayloadOrNull(content)
}

internal fun RuntimeConversationMessage.progressJsonPayloadOrNull(): String? {
  if (role != RuntimeConversationRole.TOOL || kind != RuntimeConversationMessageKind.PROGRESS) {
    return null
  }
  return plainJsonPayloadOrNull(content)
}

internal fun RuntimeConversationMessage.toolResultContentOrNull(): String? {
  if (role != RuntimeConversationRole.TOOL || kind != RuntimeConversationMessageKind.TOOL_RESULT) {
    return null
  }
  val decoded = toolResultJsonPayloadOrNull()?.let(::replayJsonObjectOrNull)
  return decoded?.replayString("content")
    ?: content.takeIf(String::isNotBlank)
}

internal fun plainReplayJsonObjectOrNull(content: String): JsonObject? =
  plainJsonPayloadOrNull(content)?.let(::replayJsonObjectOrNull)

internal fun JsonObject.isSupplementReplayPayload(): Boolean =
  replayString("event_kind") == "supplement" || replayString("entry_id") != null

internal fun JsonObject.isSubAgentReplayPayload(): Boolean =
  replayString("event_kind") == "subagent" ||
    replayString("child_run_id") != null ||
    replayString("child_task_id") != null ||
    replayString("subagent_type") != null

private fun plainJsonPayloadOrNull(content: String): String? {
  val normalized = content.trim()
  if (normalized.isBlank()) {
    return null
  }
  return normalized.takeIf { payload -> payload.startsWith("{") }
}

internal fun replayJsonObjectOrNull(payload: String): JsonObject? = runCatching {
  replayJson.parseToJsonElement(payload).jsonObject
}.getOrNull()

internal fun JsonObject.replayString(key: String): String? =
  (get(key) as? JsonPrimitive)?.content

private val replayJson = Json { ignoreUnknownKeys = true }
