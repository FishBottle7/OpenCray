package com.opencray.app.facade.llm

import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.JSON_CODEC
import com.opencray.app.extractEmbeddedJsonObject
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredFinalAttachment
import com.opencray.llm.LiteLlmStructuredToolCall
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

internal fun OpenAiCompatibleLiteLlmProviderClient.structuredFinalSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put("additionalProperties", false)
    .put(
      "required",
      JSONArray()
        .put("type")
        .put("answer")
        .put("attachments"),
    )
    .put(
      "properties",
      JSONObject()
        .put(
          "type",
          JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("final")),
        )
        .put(
          "answer",
          JSONObject()
            .put("type", "string"),
        )
        .put(
          "attachments",
          JSONObject()
            .put("type", "array")
            .put("items", structuredFinalAttachmentSchema()),
        ),
    )

private fun structuredFinalAttachmentSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put("additionalProperties", false)
    .put(
      "required",
      JSONArray()
        .put("kind")
        .put("artifact_id")
        .put("relative_path")
        .put("path")
        .put("chat_attachment_id")
        .put("display_name")
        .put("mime_type")
        .put("duration_ms")
        .put("waveform_bars")
        .put("transcript_text"),
    )
    .put(
      "properties",
      JSONObject()
        .put("kind", nullableStringSchema())
        .put("artifact_id", nullableStringSchema())
        .put("relative_path", nullableStringSchema())
        .put("path", nullableStringSchema())
        .put("chat_attachment_id", nullableStringSchema())
        .put("display_name", nullableStringSchema())
        .put("mime_type", nullableStringSchema())
        .put(
          "duration_ms",
          JSONObject()
            .put("type", JSONArray().put("integer").put("null")),
        )
        .put(
          "waveform_bars",
          JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "integer")),
        )
        .put("transcript_text", nullableStringSchema()),
    )

private fun nullableStringSchema(): JSONObject = JSONObject()
    .put("type", JSONArray().put("string").put("null"))

internal fun OpenAiCompatibleLiteLlmProviderClient.buildStructuredCompletion(
    toolCalls: List<LiteLlmStructuredToolCall>,
    finalText: String? = null,
    finalAttachments: List<LiteLlmStructuredFinalAttachment> = emptyList(),
    commentaryText: String? = null,
    commentaryTexts: List<String> = emptyList(),
    reasoningText: String? = null,
    rawText: String? = null,
    toolCallErrors: List<String> = emptyList(),
  ): LiteLlmStructuredCompletion? {
    val normalizedFinalText = finalText?.trim()?.takeIf(String::isNotBlank)
    val normalizedFinalAttachments = finalAttachments.map(::normalizeStructuredFinalAttachment)
    val normalizedCommentaryTexts = commentaryTexts
      .map(String::trim)
      .filter(String::isNotBlank)
      .ifEmpty {
        commentaryText?.trim()?.takeIf(String::isNotBlank)?.let(::listOf) ?: emptyList()
      }
    val normalizedCommentaryText = normalizedCommentaryTexts
      .joinToString(separator = "\n")
      .trim()
      .takeIf(String::isNotBlank)
    val normalizedReasoningText = reasoningText?.trim()?.takeIf(String::isNotBlank)
    val normalizedRawText = rawText?.trim()?.takeIf(String::isNotBlank)
    val normalizedToolCallErrors = toolCallErrors.map(String::trim).filter(String::isNotBlank)
    if (
      toolCalls.isEmpty() &&
      normalizedFinalText == null &&
      normalizedFinalAttachments.isEmpty() &&
      normalizedCommentaryTexts.isEmpty() &&
      normalizedReasoningText == null &&
      normalizedRawText == null &&
      normalizedToolCallErrors.isEmpty()
    ) {
      return null
    }
    return LiteLlmStructuredCompletion(
      toolCalls = toolCalls,
      finalText = normalizedFinalText,
      finalAttachments = normalizedFinalAttachments,
      commentaryText = normalizedCommentaryText,
      commentaryTexts = normalizedCommentaryTexts,
      reasoningText = normalizedReasoningText,
      rawText = normalizedRawText,
      toolCallErrors = normalizedToolCallErrors,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.jsonObjectFrom(payload: JSONObject): JsonObject = runCatching {
    JSON_CODEC.parseToJsonElement(payload.toString()) as? JsonObject
  }.getOrNull() ?: JsonObject(emptyMap())

internal fun String.toProtocolFinalPayloadOrNull(): JSONObject? {
    val candidate = extractEmbeddedJsonObject(this) ?: return null
    val parsed = runCatching { JSONObject(candidate) }.getOrNull() ?: return null
    val type = parsed.optString("type").trim().lowercase()
      .ifBlank { parsed.optString("decision").trim().lowercase() }
    val hasFinalShape = type in setOf("final", "answer") ||
      parsed.optString("answer").isNotBlank() ||
      (parsed.optJSONArray("attachments")?.length() ?: 0) > 0
    return parsed.takeIf { hasFinalShape }
  }

internal fun JSONObject.structuredFinalAttachments(): List<LiteLlmStructuredFinalAttachment> {
    val rawAttachments = optJSONArray("attachments") ?: return emptyList()
    return buildList {
      for (index in 0 until rawAttachments.length()) {
        val attachment = rawAttachments.optJSONObject(index) ?: continue
        add(
          LiteLlmStructuredFinalAttachment(
            kind = attachment.nonBlankString("kind"),
            relativePath = attachment.nonBlankString("relative_path")
              ?: attachment.nonBlankString("relativePath"),
            path = attachment.nonBlankString("path"),
            artifactId = attachment.nonBlankString("artifact_id")
              ?: attachment.nonBlankString("artifactId"),
            chatAttachmentId = attachment.nonBlankString("chat_attachment_id")
              ?: attachment.nonBlankString("chatAttachmentId"),
            displayName = attachment.nonBlankString("display_name")
              ?: attachment.nonBlankString("displayName"),
            mimeType = attachment.nonBlankString("mime_type")
              ?: attachment.nonBlankString("mimeType"),
            durationMs = attachment.optLongValue("duration_ms")
              ?: attachment.optLongValue("durationMs"),
            waveformBars = attachment.optIntArray("waveform_bars")
              ?: attachment.optIntArray("waveformBars")
              ?: emptyList(),
            transcriptText = attachment.nonBlankString("transcript_text")
              ?: attachment.nonBlankString("transcriptText"),
          ),
        )
      }
    }
  }

private fun normalizeStructuredFinalAttachment(
    attachment: LiteLlmStructuredFinalAttachment,
  ): LiteLlmStructuredFinalAttachment = LiteLlmStructuredFinalAttachment(
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

internal fun JSONObject.optLongValue(
    key: String,
  ): Long? {
    if (!has(key) || isNull(key)) {
      return null
    }
    return when (val rawValue = opt(key)) {
      is Number -> rawValue.toLong()
      is String -> rawValue.trim().toLongOrNull()
      else -> null
    }
  }

private fun JSONObject.optIntArray(
    key: String,
  ): List<Int>? {
    val rawArray = optJSONArray(key) ?: return null
    return buildList {
      for (index in 0 until rawArray.length()) {
        when (val rawValue = rawArray.opt(index)) {
          is Number -> add(rawValue.toInt())
          is String -> rawValue.trim().toIntOrNull()?.let(::add)
        }
      }
    }
  }

internal fun JSONObject.nonBlankString(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)
