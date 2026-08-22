package com.opencray.runtime

import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredFinalAttachment
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.OpenCrayAgentRuntime.AgentModelAction
import com.opencray.runtime.OpenCrayAgentRuntime.ParsedModelActionBatch
import com.opencray.runtime.OpenCrayAgentRuntime.PromptRunDiagnostics
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun OpenCrayAgentRuntime.parseModelActionBatch(rawOutput: String): ParsedModelActionBatch {
  val trimmed = rawOutput.trim()
  val jsonSequence = extractJsonSequence(trimmed)
  if (jsonSequence == null) {
    return ParsedModelActionBatch.ProtocolError(
      reason = "Model output must be a single JSON action object.",
    )
  }
  return runCatching {
    val actions = mutableListOf<AgentModelAction>()
    var ignoredNonActionContent = false
    jsonSequence.envelopes.forEach { envelope ->
      if (envelope.leadingText.isNotBlank()) {
        ignoredNonActionContent = true
      }
      val parsedObjectActions = parseActionObject(envelope.json)
      actions += parsedObjectActions.actions
      ignoredNonActionContent = ignoredNonActionContent || parsedObjectActions.ignoredNonActionContent
    }
    if (jsonSequence.trailingText.isNotBlank()) {
      ignoredNonActionContent = true
    }
    if (actions.isEmpty()) {
      error("Model output did not contain any executable actions.")
    }
    ParsedModelActionBatch.Actions(
      actions = actions,
      ignoredNonActionContent = ignoredNonActionContent,
    )
  }.getOrElse {
    ParsedModelActionBatch.ProtocolError(
      reason = it.message ?: "Model output could not be parsed as a JSON action.",
    )
  }
}

internal fun OpenCrayAgentRuntime.parseGatewayResultActionBatch(
  gatewayResult: LiteLlmGatewayResult,
  diagnostics: PromptRunDiagnostics,
): ParsedGatewayActionBatch? {
  gatewayResult.completion?.let { completion ->
    completion.reasoningText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reasoningText ->
        diagnostics.providerReasoningObserved = true
        diagnostics.providerReasoningTurnCount += 1
        diagnostics.providerReasoningChars += reasoningText.length
      }
    parseStructuredCompletion(completion)?.let { parsed ->
      return ParsedGatewayActionBatch(batch = parsed)
    }
    completion.rawText?.takeIf(String::isNotBlank)?.let { rawText ->
      diagnostics.fallbackParserAttempted = true
      val parsed = parseModelActionBatch(rawText)
      diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
      return ParsedGatewayActionBatch(
        batch = parsed,
        usedLegacyJsonFallback = parsed is ParsedModelActionBatch.Actions,
      )
    }
  }
  val outputText = gatewayResult.outputText?.takeIf { it.isNotBlank() } ?: return null
  diagnostics.fallbackParserAttempted = true
  val parsed = parseModelActionBatch(outputText)
  diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
  return ParsedGatewayActionBatch(
    batch = parsed,
    usedLegacyJsonFallback = parsed is ParsedModelActionBatch.Actions,
  )
}

internal fun OpenCrayAgentRuntime.parseStructuredCompletion(
  completion: LiteLlmStructuredCompletion,
): ParsedModelActionBatch? {
  if (completion.toolCallErrors.isNotEmpty()) {
    return ParsedModelActionBatch.ProtocolError(
      reason = buildStructuredToolCallRecoveryReason(completion.toolCallErrors),
    )
  }
  duplicateStructuredToolCallErrors(completion.toolCalls)
    .takeIf { duplicateErrors -> duplicateErrors.isNotEmpty() }
    ?.let { duplicateErrors ->
      return ParsedModelActionBatch.ProtocolError(
        reason = buildStructuredToolCallRecoveryReason(duplicateErrors),
      )
    }
  val actions = mutableListOf<AgentModelAction>()
  structuredCompletionCommentaryTexts(completion).forEach { commentaryText ->
    actions += AgentModelAction.Commentary(text = commentaryText)
  }
  completion.toolCalls
    .mapTo(actions) { toolCall ->
      AgentModelAction.ToolCall(call = parseStructuredToolCall(toolCall))
    }
  val finalText = completion.finalText
    ?.trim()
    ?.takeIf(String::isNotBlank)
  val finalAttachments = completion.finalAttachments.map(::toOpenCrayFinalAttachment)
  if (completion.toolCalls.isEmpty() && (finalText != null || finalAttachments.isNotEmpty())) {
    actions += AgentModelAction.Final(
      answer = finalText.orEmpty(),
      responseFormat = if (finalAttachments.isEmpty()) {
        "native_text_final"
      } else {
        "native_structured_final"
      },
      attachments = finalAttachments,
    )
  }
  if (actions.isNotEmpty()) {
    return ParsedModelActionBatch.Actions(
      actions = actions,
      ignoredNonActionContent = !completion.rawText.isNullOrBlank() &&
        actions.any { action -> action !is AgentModelAction.Final },
    )
  }
  return null
}

internal data class ParsedGatewayActionBatch(
  val batch: ParsedModelActionBatch,
  val usedLegacyJsonFallback: Boolean = false,
)

internal fun parseStructuredToolCall(
  toolCall: LiteLlmStructuredToolCall,
): AgentToolCall = AgentToolCall(
  id = toolCall.id,
  toolName = toolCall.toolName,
  arguments = toolCall.arguments,
  reason = toolCall.reason,
)

internal data class ParsedActionObject(
  val actions: List<AgentModelAction>,
  val ignoredNonActionContent: Boolean = false,
)

internal fun OpenCrayAgentRuntime.parseActionObject(rawJson: String): ParsedActionObject {
  val parsed = config.json.parseToJsonElement(rawJson) as? JsonObject
    ?: error("Model output must decode to a JSON object.")
  val nestedActions = (parsed["actions"] as? kotlinx.serialization.json.JsonArray)
    ?.map { element ->
      val nestedObject = element as? JsonObject ?: error("Each action inside 'actions' must be a JSON object.")
      parseActionObject(config.json.encodeToString(JsonObject.serializer(), nestedObject))
    }
    .orEmpty()
  if (nestedActions.isNotEmpty()) {
    return ParsedActionObject(
      actions = nestedActions.flatMap { action -> action.actions },
      ignoredNonActionContent = nestedActions.any(ParsedActionObject::ignoredNonActionContent),
    )
  }

  val type = parsed.primitiveContent("type")?.trim()?.lowercase()
    ?: parsed.primitiveContent("decision")?.trim()?.lowercase()
  val hasToolCallShape = parsed.primitiveContent("tool_name")?.isNotBlank() == true
  val hasFinalAnswerShape = parsed.primitiveContent("answer")?.isNotBlank() == true
  val hasFinalAttachmentShape = (parsed["attachments"] as? JsonArray)?.isNotEmpty() == true
  val toolCalls = (parsed["tool_calls"] as? JsonArray)
    ?.map { element ->
      val toolCallObject = element as? JsonObject ?: error("Each entry inside 'tool_calls' must be a JSON object.")
      AgentModelAction.ToolCall(call = parseToolCall(toolCallObject))
    }
    .orEmpty()
  if (toolCalls.isNotEmpty()) {
    return ParsedActionObject(
      actions = toolCalls,
      ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
        parsed.primitiveContent("message")?.isNotBlank() == true,
    )
  }

  return when {
    type in setOf("tool_call", "tool") || hasToolCallShape -> ParsedActionObject(
      actions = listOf(AgentModelAction.ToolCall(call = parseToolCall(parsed))),
      ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
        parsed.primitiveContent("message")?.isNotBlank() == true,
    )

    type in setOf("progress", "commentary", "status") -> ParsedActionObject(
      actions = listOf(
        AgentModelAction.Commentary(
          text = parsed.primitiveContent("text")
            ?.trim()
            .orEmpty()
            .ifBlank {
              parsed.primitiveContent("summary")
                ?.trim()
                .orEmpty()
                .ifBlank {
                  parsed.primitiveContent("message")
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                      error("commentary action must contain a non-blank 'text'.")
                    }
                }
            },
          stage = parsed.primitiveContent("stage")?.trim()?.takeIf(String::isNotBlank),
        ),
      ),
      ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
        parsed.primitiveContent("tool_name")?.isNotBlank() == true,
    )

    type in setOf("final", "answer") ||
      (type == null && (hasFinalAnswerShape || hasFinalAttachmentShape)) -> ParsedActionObject(
      actions = listOf(parseFinalAction(parsed)),
    )

    type == null -> error("Model output must contain 'type' or 'decision'.")

    else -> error("Unsupported model action type '$type'.")
  }
}

internal fun parseToolCall(parsed: JsonObject): AgentToolCall = AgentToolCall(
  id = parsed.primitiveContent("id")?.trim()?.takeIf(String::isNotBlank)
    ?: parsed.primitiveContent("tool_call_id")?.trim()?.takeIf(String::isNotBlank),
  toolName = parsed.primitiveContent("tool_name")?.trim().orEmpty().ifBlank {
    error("tool_call action must contain a non-blank 'tool_name'.")
  },
  arguments = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
  reason = parsed.primitiveContent("reason")?.trim()?.takeIf(String::isNotBlank)
    ?: parsed.primitiveContent("justification")?.trim()?.takeIf(String::isNotBlank),
)

internal fun parseFinalAction(parsed: JsonObject): AgentModelAction.Final {
  val attachments = parseFinalAttachments(parsed["attachments"])
  val answer = parsed.primitiveContent("answer")?.trim().orEmpty()
  require(answer.isNotBlank() || attachments.isNotEmpty()) {
    "Final action must contain a non-blank 'answer' or a non-empty 'attachments' array."
  }
  return AgentModelAction.Final(
    answer = answer,
    responseFormat = "json_final",
    attachments = attachments,
  )
}

internal fun parseFinalAttachments(
  rawAttachments: kotlinx.serialization.json.JsonElement?,
): List<OpenCrayFinalAttachment> = (rawAttachments as? JsonArray)
  ?.map { element ->
    val attachmentObject = element as? JsonObject
      ?: error("Each entry inside 'attachments' must be a JSON object.")
    OpenCrayFinalAttachment(
      kind = attachmentObject.primitiveContent("kind")?.trim()?.takeIf(String::isNotBlank),
      relativePath = attachmentObject.primitiveContent("relative_path")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("relativePath")
          ?.trim()
          ?.takeIf(String::isNotBlank),
      path = attachmentObject.primitiveContent("path")?.trim()?.takeIf(String::isNotBlank),
      artifactId = attachmentObject.primitiveContent("artifact_id")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("artifactId")
          ?.trim()
          ?.takeIf(String::isNotBlank),
      chatAttachmentId = attachmentObject.primitiveContent("chat_attachment_id")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("chatAttachmentId")
          ?.trim()
          ?.takeIf(String::isNotBlank),
      displayName = attachmentObject.primitiveContent("display_name")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("displayName")
          ?.trim()
          ?.takeIf(String::isNotBlank),
      mimeType = attachmentObject.primitiveContent("mime_type")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("mimeType")
          ?.trim()
          ?.takeIf(String::isNotBlank),
      durationMs = attachmentObject.longContent("duration_ms")
        ?: attachmentObject.longContent("durationMs"),
      waveformBars = attachmentObject.intArrayContent("waveform_bars")
        ?: attachmentObject.intArrayContent("waveformBars")
        ?: emptyList(),
      transcriptText = attachmentObject.primitiveContent("transcript_text")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: attachmentObject.primitiveContent("transcriptText")
          ?.trim()
          ?.takeIf(String::isNotBlank),
    )
  }
  .orEmpty()

internal fun toOpenCrayFinalAttachment(
  attachment: LiteLlmStructuredFinalAttachment,
): OpenCrayFinalAttachment = OpenCrayFinalAttachment(
  kind = attachment.kind?.trim()?.takeIf(String::isNotBlank),
  relativePath = attachment.relativePath?.trim()?.takeIf(String::isNotBlank),
  path = attachment.path?.trim()?.takeIf(String::isNotBlank),
  artifactId = attachment.artifactId?.trim()?.takeIf(String::isNotBlank),
  chatAttachmentId = attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank),
  displayName = attachment.displayName?.trim()?.takeIf(String::isNotBlank),
  mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
  durationMs = attachment.durationMs,
  waveformBars = attachment.waveformBars,
  transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
)

internal fun OpenCrayAgentRuntime.finalAttachmentMetadata(
  attachments: List<OpenCrayFinalAttachment>,
): Map<String, String> = attachments
  .takeIf(List<OpenCrayFinalAttachment>::isNotEmpty)
  ?.let { resolved ->
    mapOf(
      OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to config.json.encodeToString(
        ListSerializer(OpenCrayFinalAttachment.serializer()),
        resolved,
      ),
    )
  }
  ?: emptyMap()

internal data class JsonEnvelope(
  val json: String,
  val leadingText: String,
)

internal data class JsonSequence(
  val envelopes: List<JsonEnvelope>,
  val trailingText: String,
)

internal fun extractJsonSequence(raw: String): JsonSequence? {
  val fenced = raw.lines()
    .dropWhile { line -> !line.trimStart().startsWith("```") }
    .drop(1)
    .takeWhile { line -> !line.trimStart().startsWith("```") }
    .joinToString(separator = "\n")
    .trim()
  val source = when {
    fenced.startsWith("{") && fenced.endsWith("}") -> fenced
    raw.startsWith("{") && raw.endsWith("}") -> raw
    else -> raw
  }

  val envelopes = mutableListOf<JsonEnvelope>()
  var depth = 0
  var startIndex = -1
  var inString = false
  var escaped = false
  var cursor = 0
  for ((index, character) in source.withIndex()) {
    when {
      inString && escaped -> escaped = false
      inString && character == '\\' -> escaped = true
      character == '"' -> inString = !inString
      !inString && character == '{' -> {
        if (depth == 0) {
          startIndex = index
        }
        depth += 1
      }

      !inString && character == '}' -> {
        depth -= 1
        if (depth == 0 && startIndex >= 0) {
          envelopes += JsonEnvelope(
            json = source.substring(startIndex, index + 1),
            leadingText = source.substring(cursor, startIndex),
          )
          cursor = index + 1
        }
      }
    }
  }
  if (envelopes.isEmpty()) return null
  return JsonSequence(
    envelopes = envelopes,
    trailingText = source.substring(cursor),
  )
}

internal fun JsonObject.primitiveContent(key: String): String? =
  (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.booleanContent(key: String): Boolean? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toBooleanStrictOrNull()

internal fun JsonObject.optionalBooleanContent(key: String): Boolean? =
  primitiveContent(key)
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotBlank)
    ?.let { value ->
      when (value) {
        "true" -> true
        "false" -> false
        else -> null
      }
    }

internal fun JsonObject.longContent(key: String): Long? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toLongOrNull()

internal fun JsonObject.intContent(key: String): Int? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toIntOrNull()

internal fun JsonObject.jsonObjectContent(key: String): JsonObject? =
  this[key] as? JsonObject

internal fun JsonObject.intArrayContent(key: String): List<Int>? =
  (this[key] as? JsonArray)
    ?.mapNotNull { element ->
      (element as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.toIntOrNull()
    }

internal fun JsonObject.stringArrayContent(key: String): List<String>? =
  (this[key] as? JsonArray)
    ?.mapNotNull { element ->
      (element as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotBlank)
    }
