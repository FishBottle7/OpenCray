package com.opencray.runtime.web

import com.opencray.core.contracts.AgentTask
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.promptCheckpointMetadata
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun OpenCrayAgentRuntime.emitBuiltinWebSearchObservations(
  task: AgentTask,
  turn: Int,
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  gatewayResult: LiteLlmGatewayResult,
  diagnostics: OpenCrayAgentRuntime.PromptRunDiagnostics,
  localContinuationContextPrompts: List<String>? = null,
  localContinuationStableAnchor: String? = null,
  localContinuationGatewayMessagesEnabled: Boolean = false,
  localContinuationToolPoolFingerprint: String? = null,
  localContinuationToolSchemaFingerprint: String? = null,
  localContinuationRequestSettingsFingerprint: String? = null,
) {
  val observations = decodeBuiltinWebSearchObservations(gatewayResult)
  if (observations.isEmpty()) {
    return
  }
  observations.forEach { observation ->
    val call = syntheticProviderNativeWebSearchCall(cursor, observation)
    val result = providerNativeWebSearchResult(observation)
    val checkpointMetadata = promptCheckpointMetadata(
      boundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
      cursor = cursor,
      turnIndex = cursor.turn + 1,
      localContinuationContextPrompts = localContinuationContextPrompts,
      localContinuationStableAnchor = localContinuationStableAnchor,
      localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
      localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
      localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
    )
    val eventToolResult = if (checkpointMetadata.isEmpty()) {
      result
    } else {
      result.copy(metadata = result.metadata + checkpointMetadata)
    }
    announcePromptToolCall(
      task = task,
      turn = turn,
      call = call,
      cursor = cursor,
      diagnostics = diagnostics,
      suppressToolCallEvent = false,
    )
    cursor.transcript += RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      kind = RuntimeConversationMessageKind.TOOL_RESULT,
      content = buildToolResultTranscriptEntry(
        task = task,
        turn = turn,
        call = call,
        result = eventToolResult,
      ),
      toolResult = RuntimeConversationToolResult(
        toolCallId = call.id,
        toolName = eventToolResult.toolName,
        status = eventToolResult.status.name.lowercase(),
        isError = eventToolResult.status != AgentToolResultStatus.SUCCESS,
      ),
    )
    emitToolResultEvent(
      task = task,
      turn = turn,
      call = call,
      result = eventToolResult,
      diagnostics = diagnostics,
    )
  }
}

internal fun OpenCrayAgentRuntime.decodeBuiltinWebSearchObservations(
  gatewayResult: LiteLlmGatewayResult,
): List<LiteLlmBuiltinWebSearchObservation> {
  val raw = gatewayResult.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return emptyList()
  return runCatching {
    config.json.decodeFromString(
      ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
      raw,
    )
  }.getOrDefault(emptyList())
}

internal fun OpenCrayAgentRuntime.syntheticProviderNativeWebSearchCall(
  cursor: OpenCrayAgentRuntime.PromptTurnCursor,
  observation: LiteLlmBuiltinWebSearchObservation,
): AgentToolCall = AgentToolCall(
  id = "oc-call-${cursor.nextSyntheticToolCallSequence++}",
  toolName = "WebSearch",
  arguments = providerNativeWebSearchArguments(observation),
)

internal fun providerNativeWebSearchArguments(
  observation: LiteLlmBuiltinWebSearchObservation,
): JsonObject = buildJsonObject {
  put("operation", observation.actionType)
  observation.queries.firstOrNull()?.let { query -> put("query", query) }
  if (observation.queries.isNotEmpty()) {
    put(
      "queries",
      JsonArray(observation.queries.map(::JsonPrimitive)),
    )
  }
  if (observation.domains.isNotEmpty()) {
    put(
      "domains",
      JsonArray(observation.domains.map(::JsonPrimitive)),
    )
  }
  observation.url?.let { url -> put("url", url) }
  observation.findText?.let { text -> put("text", text) }
}

internal fun providerNativeWebSearchResult(
  observation: LiteLlmBuiltinWebSearchObservation,
): AgentToolResult {
  val status = providerNativeWebSearchResultStatus(observation)
  val content = providerNativeWebSearchResultContent(observation)
  val errorCode = if (status == AgentToolResultStatus.SUCCESS) {
    null
  } else {
    "PROVIDER_MANAGED_WEB_SEARCH_FAILED"
  }
  val errorMessage = if (status == AgentToolResultStatus.SUCCESS) {
    null
  } else {
    content
  }
  return AgentToolResult(
    toolName = "WebSearch",
    status = status,
    content = content,
    errorCode = errorCode,
    errorMessage = errorMessage,
    metadata = buildMap {
      put(ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED, "true")
      put(ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION, observation.actionType)
      observation.status
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { statusValue ->
          put(ProviderNativeWebSearchSupport.RESULT_METADATA_STATUS, statusValue)
        }
      observation.queries.firstOrNull()?.let { query -> put("query", query) }
      if (observation.queries.isNotEmpty()) {
        put("queries", observation.queries.joinToString(separator = ","))
      }
      if (observation.domains.isNotEmpty()) {
        put("domains", observation.domains.joinToString(separator = ","))
      }
      observation.url?.let { url -> put("url", url) }
      observation.findText?.let { text -> put("text", text) }
      if (observation.sources.isNotEmpty()) {
        put("sourceCount", observation.sources.size.toString())
        put(
          "sourceUrls",
          observation.sources.joinToString(separator = ",") { source -> source.url },
        )
      }
    },
  )
}

internal fun providerNativeWebSearchResultStatus(
  observation: LiteLlmBuiltinWebSearchObservation,
): AgentToolResultStatus = when (observation.status?.trim()?.lowercase()) {
  "failed",
  "error",
  "incomplete",
  "cancelled",
  -> AgentToolResultStatus.FAILED

  else -> AgentToolResultStatus.SUCCESS
}

internal fun providerNativeWebSearchResultContent(
  observation: LiteLlmBuiltinWebSearchObservation,
): String = buildString {
  when (observation.actionType.trim().lowercase()) {
    "open_page" -> {
      append("Provider-native web search opened a page")
      observation.url?.let { url ->
        append(": ")
        append(url)
      }
      append(".")
    }

    "find_in_page" -> {
      append("Provider-native web search searched within a page")
      observation.url?.let { url ->
        append(": ")
        append(url)
      }
      observation.findText?.let { text ->
        append(" for \"")
        append(text)
        append("\"")
      }
      append(".")
    }

    else -> {
      append("Provider-native web search ran")
      observation.queries.firstOrNull()?.let { query ->
        append(" for \"")
        append(query)
        append("\"")
      }
      if (observation.domains.isNotEmpty()) {
        append(" within ")
        append(observation.domains.joinToString(separator = ", "))
      }
      append(".")
    }
  }
  observation.status
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { status ->
      appendLine()
      append("Status: ")
      append(status)
    }
  if (observation.sources.isNotEmpty()) {
    appendLine()
    appendLine("Sources:")
    observation.sources.forEach { source ->
      append("- ")
      append(source.title?.takeIf(String::isNotBlank) ?: source.url)
      append(" - ")
      append(source.url)
      appendLine()
    }
  }
}.trim()
