package com.opencray.app

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal enum class ReplayedRuntimeEventKind {
  TOOL_CALL,
  TOOL_RESULT,
  ASSISTANT_PHASE,
  SUPPLEMENT,
  SUBAGENT,
}

internal data class ReplayedRuntimePayload(
  val kind: ReplayedRuntimeEventKind,
  val payload: String,
)

internal fun RuntimeConversationMessage.replayedRuntimePayloadOrNull(): ReplayedRuntimePayload? {
  val normalized = content.trim()
  if (normalized.isEmpty()) {
    return null
  }
  when (kind) {
    RuntimeConversationMessageKind.TOOL_CALL ->
      if (role == RuntimeConversationRole.ASSISTANT) {
        plainJsonPayloadOrNull(normalized)?.let { payload ->
          return ReplayedRuntimePayload(
            kind = ReplayedRuntimeEventKind.TOOL_CALL,
            payload = payload,
          )
        }
      }

    RuntimeConversationMessageKind.TOOL_RESULT ->
      if (role == RuntimeConversationRole.TOOL) {
        plainJsonPayloadOrNull(normalized)?.let { payload ->
          return ReplayedRuntimePayload(
            kind = ReplayedRuntimeEventKind.TOOL_RESULT,
            payload = payload,
          )
        }
      }

    RuntimeConversationMessageKind.PROGRESS ->
      if (role == RuntimeConversationRole.TOOL || role == RuntimeConversationRole.ASSISTANT) {
        plainJsonPayloadOrNull(normalized)?.let { payload ->
          return ReplayedRuntimePayload(
            kind = ReplayedRuntimeEventKind.ASSISTANT_PHASE,
            payload = payload,
          )
        }
      }

    RuntimeConversationMessageKind.PLAIN -> Unit
  }
  replayPayloadFromPlainJson(normalized)?.let { return it }
  return null
}

private fun replayPayloadFromPlainJson(normalized: String): ReplayedRuntimePayload? {
  if (!normalized.startsWith("{")) {
    return null
  }
  val decoded = replayJsonObjectOrNull(normalized) ?: return null
  inferReplayEventKind(decoded)?.let { kind ->
    return ReplayedRuntimePayload(kind = kind, payload = normalized)
  }
  return null
}

private fun inferReplayEventKind(decoded: JsonObject): ReplayedRuntimeEventKind? {
  decoded.replayString("event_kind")
    ?.lowercase()
    ?.let { rawKind ->
      when (rawKind) {
        "tool_call" -> return ReplayedRuntimeEventKind.TOOL_CALL
        "tool_result" -> return ReplayedRuntimeEventKind.TOOL_RESULT
        "assistant_phase" -> return ReplayedRuntimeEventKind.ASSISTANT_PHASE
        "supplement" -> return ReplayedRuntimeEventKind.SUPPLEMENT
        "subagent" -> return ReplayedRuntimeEventKind.SUBAGENT
        else -> Unit
      }
    }
  if (decoded.replayString("tool_name") != null) {
    if (
      decoded.replayString("status") != null ||
      decoded.replayString("content") != null ||
      decoded.replayString("stdout") != null ||
      decoded.replayString("stderr") != null ||
      decoded.replayString("error_code") != null
    ) {
      return ReplayedRuntimeEventKind.TOOL_RESULT
    }
    if (decoded["arguments"] is JsonObject || decoded.replayString("reason") != null) {
      return ReplayedRuntimeEventKind.TOOL_CALL
    }
  }
  if (decoded.replayString("entry_id") != null) {
    return ReplayedRuntimeEventKind.SUPPLEMENT
  }
  if (
    decoded.replayString("child_run_id") != null ||
    decoded.replayString("child_task_id") != null ||
    decoded.replayString("subagent_type") != null
  ) {
    return ReplayedRuntimeEventKind.SUBAGENT
  }
  return null
}

private fun plainJsonPayloadOrNull(normalizedContent: String): String? {
  if (normalizedContent.isEmpty()) {
    return null
  }
  return normalizedContent.takeIf { payload -> payload.startsWith("{") }
}

private fun replayJsonObjectOrNull(payload: String): JsonObject? = runCatching {
  replayJson.parseToJsonElement(payload).jsonObject
}.getOrNull()

private fun JsonObject.replayString(key: String): String? =
  (get(key) as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)

private val replayJson = Json { ignoreUnknownKeys = true }
