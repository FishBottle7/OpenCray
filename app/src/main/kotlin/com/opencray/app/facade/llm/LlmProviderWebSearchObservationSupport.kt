package com.opencray.app.facade.llm

import com.opencray.app.LlmProviderProtocols
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.JSON_CODEC
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmStructuredCompletion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject

internal enum class OpenAiBuiltinWebSearchDialect(
  val wireValue: String,
) {
  OPENAI_CHAT_WEB_SEARCH("openai_chat_web_search"),
  KIMI_BUILTIN_FUNCTION_WEB_SEARCH("kimi_builtin_function_web_search");

  companion object {
    fun fromWireValue(rawValue: String?): OpenAiBuiltinWebSearchDialect? =
      entries.firstOrNull { dialect ->
        dialect.wireValue.equals(rawValue?.trim(), ignoreCase = true)
      }
  }
}

internal fun OpenAiCompatibleLiteLlmProviderClient.maybeAutoContinueBuiltinWebSearch(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    completion: LiteLlmStructuredCompletion?,
    success: LiteLlmProviderResult.Success,
  ): LiteLlmProviderResult? = when (resolvedProtocol(request)) {
    LlmProviderProtocols.OPENAI -> maybeAutoContinueOpenAiBuiltinWebSearch(
      request = request,
      payload = payload,
      completion = completion,
      success = success,
    )

    LlmProviderProtocols.ANTHROPIC -> maybeAutoContinueAnthropicBuiltinWebSearch(
      request = request,
      payload = payload,
      success = success,
    )

    else -> null
  }

// Keep provider encoding message-first even when a legacy caller still sends only `prompt`.
internal fun OpenAiCompatibleLiteLlmProviderClient.projectedConversationMessages(
    request: LiteLlmGatewayRequest,
  ): List<LiteLlmGatewayMessage> = if (request.messages.isNotEmpty()) {
    request.messages
  } else {
    listOf(
      LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.USER,
        content = request.prompt,
      ),
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.builtinWebSearchFallbackQueries(
    request: LiteLlmProviderRequest,
  ): List<String> = request.request.messages
    .asReversed()
    .asSequence()
    .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
    .mapNotNull(::builtinWebSearchFallbackQueryText)
    .firstOrNull()
    ?.let(::listOf)
    ?: listOf(request.request.prompt.trim()).filter(String::isNotBlank)

private fun builtinWebSearchFallbackQueryText(
    message: LiteLlmGatewayMessage,
  ): String? {
    message.content?.trim()?.takeIf(String::isNotBlank)?.let { content ->
      return content
    }
    return message.attachments
      .asSequence()
      .mapNotNull { attachment ->
        attachment.transcriptText?.trim()?.takeIf(String::isNotBlank)
          ?: attachment.displayName?.trim()?.takeIf(String::isNotBlank)
      }
      .firstOrNull()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeBuiltinWebSearchMetadata(
    metadata: Map<String, String>,
    observations: List<LiteLlmBuiltinWebSearchObservation>,
  ): Map<String, String> {
    if (observations.isEmpty() && metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED] == "true") {
      return metadata
    }
    val mergedObservations = (
      decodeBuiltinWebSearchObservations(metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON]) +
        observations
      )
      .distinctBy { observation ->
        listOf(
          observation.actionType,
          observation.status.orEmpty(),
          observation.url.orEmpty(),
          observation.findText.orEmpty(),
          observation.queries.joinToString(separator = "|"),
          observation.domains.joinToString(separator = "|"),
        ).joinToString(separator = "::")
      }
    return metadata.toMutableMap().apply {
      put(LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED, "true")
      if (mergedObservations.isNotEmpty()) {
        put(
          LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON,
          JSON_CODEC.encodeToString(
            ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
            mergedObservations,
          ),
        )
      }
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeBuiltinWebSearchMetadata(
    metadata: Map<String, String>,
    dialect: OpenAiBuiltinWebSearchDialect,
    observations: List<LiteLlmBuiltinWebSearchObservation>,
  ): Map<String, String> = mergeBuiltinWebSearchMetadata(
    metadata = metadata,
    observations = observations,
  ).toMutableMap().apply {
      put(LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT, dialect.wireValue)
    }

private fun decodeBuiltinWebSearchObservations(
    rawValue: String?,
  ): List<LiteLlmBuiltinWebSearchObservation> {
    val encoded = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
    return runCatching {
      JSON_CODEC.decodeFromString(
        ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
        encoded,
      )
    }.getOrDefault(emptyList())
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responseShape(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    protocol: String,
  ): String = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicResponseShape(
      request = request,
      payload = payload,
    )
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesResponseShape(
      request = request,
      payload = payload,
    )
    else -> openAiResponseShape(
      request = request,
      payload = payload,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.nativeToolCallObserved(
    payload: JSONObject,
    protocol: String,
  ): Boolean {
    return when (protocol) {
      LlmProviderProtocols.ANTHROPIC -> {
        val content = payload.optJSONArray("content")
        if (content == null) {
          false
        } else {
          for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (isExecutableAnthropicToolUse(block)) {
              return true
            }
          }
          false
        }
      }

      LlmProviderProtocols.OPENAI_RESPONSES -> {
        val output = payload.optJSONArray("output")
        if (output == null) {
          false
        } else {
          for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") == "function_call") {
              return true
            }
          }
          false
        }
      }

      else -> {
        val message = payload.optJSONArray("choices")
          ?.optJSONObject(0)
          ?.optJSONObject("message")
        if (message == null) {
          false
        } else {
          val toolCalls = message.optJSONArray("tool_calls")
          toolCalls != null && toolCalls.length() > 0
        }
      }
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.builtinWebSearchObserved(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    protocol: String,
  ): Boolean = builtinWebSearchObservations(
    request = request,
    payload = payload,
    protocol = protocol,
  ).isNotEmpty()

internal fun OpenAiCompatibleLiteLlmProviderClient.builtinWebSearchObservations(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    protocol: String,
  ): List<LiteLlmBuiltinWebSearchObservation> = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicBuiltinWebSearchObservations(
      request = request,
      payload = payload,
    )
    LlmProviderProtocols.OPENAI -> openAiBuiltinWebSearchObservations(
      request = request,
      payload = payload,
    )
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesBuiltinWebSearchObservations(payload)
    else -> emptyList()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.nonBlankJsonArrayStrings(array: JSONArray?): List<String> {
    if (array == null || array.length() == 0) {
      return emptyList()
    }
    return buildList {
      for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotBlank()) {
          add(value)
        }
      }
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responseCitationCount(
    payload: JSONObject,
    protocol: String,
  ): Int = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicCitationCount(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesCitationCount(payload)
    else -> 0
  }
