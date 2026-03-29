package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

private data class ResponsesTextSegments(
  val commentary: List<String> = emptyList(),
  val finalAnswer: List<String> = emptyList(),
  val unphased: List<String> = emptyList(),
  val ordered: List<String> = emptyList(),
)

private data class AnthropicUserTurnAssembly(
  val message: JSONObject?,
  val nextIndexExclusive: Int,
)

private data class EncodedImageAttachment(
  val attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  val mimeType: String,
  val base64Data: String,
)

private data class EncodedPdfAttachment(
  val attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  val displayName: String,
  val mimeType: String,
  val base64Data: String,
)

private data class MultimodalMessageAssembly(
  val text: String? = null,
  val inlinePdfs: List<EncodedPdfAttachment> = emptyList(),
  val inlineImages: List<EncodedImageAttachment> = emptyList(),
)

private enum class OpenAiBuiltinWebSearchDialect(
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

internal class OpenAiCompatibleLiteLlmProviderClient(
  private val userAgent: String = OpenCrayUserAgent.providerApi("0"),
) : LiteLlmProviderClient {
  override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
    val baseUrl = request.route.baseUrl?.trim().orEmpty()
    if (baseUrl.isEmpty()) {
      return LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_BASE_URL_MISSING",
        errorMessage = "Provider route baseUrl is required.",
        metadata = requestDiagnosticsMetadata(request),
      )
    }

    val protocol = resolvedProtocol(request)
    val streamResponses = shouldStreamResponses(request, protocol)
    val requestDiagnostics = requestDiagnosticsMetadata(request)
    invalidToolMessageContract(request.request.messages)?.let { validationError ->
      return LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_REQUEST_INVALID_TOOL_CALL_ID",
        errorMessage = validationError,
        metadata = requestDiagnostics,
      )
    }
    val endpoint = buildEndpointUrl(baseUrl, protocol)
    val timeoutMs = request.route.timeoutMs
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = timeoutMs.toInt()
      readTimeout = timeoutMs.toInt()
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty(
        "Accept",
        if (streamResponses) {
          "text/event-stream, application/json"
        } else {
          "application/json"
        },
      )
      request.request.authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
      setRequestProperty("User-Agent", userAgent)
    }

    return try {
      val body = buildRequestBody(request, streamResponses = streamResponses)
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }

      val responseCode = connection.responseCode
      val responseText = if (responseCode in 200..299) {
        readSuccessResponse(
          input = connection.inputStream,
          protocol = protocol,
          streamResponses = streamResponses,
          contentType = connection.contentType,
        )
      } else {
        readStream(connection.errorStream)
      }

      when {
        responseCode == 429 -> LiteLlmProviderResult.RateLimited(
          retryAfterMs = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
          errorMessage = extractErrorMessage(responseText).ifBlank { "Provider returned HTTP 429." },
          metadata = requestDiagnostics + mapOf("statusCode" to responseCode.toString()),
        )

        responseCode == 449 || responseCode == 499 -> LiteLlmProviderResult.Timeout(
          errorMessage = extractErrorMessage(responseText).ifBlank {
            "Provider request timed out or was cancelled upstream (HTTP $responseCode)."
          },
          metadata = requestDiagnostics + mapOf("statusCode" to responseCode.toString()),
        )

        responseCode !in 200..299 -> LiteLlmProviderResult.Failure(
          errorCode = "HTTP_$responseCode",
          errorMessage = extractErrorMessage(responseText).ifBlank { "Provider returned HTTP $responseCode." },
          metadata = requestDiagnostics + mapOf("statusCode" to responseCode.toString()),
        )

        else -> {
          val parsed = JSONObject(responseText)
          val finishReason = finishReasonFor(parsed, protocol)
          val providerResponseId = parsed.optString("id").trim().takeIf(String::isNotBlank)
          val completion = structuredCompletion(
            payload = parsed,
            protocol = protocol,
          )
          val responseMetadata = responseMetadata(
            request = request,
            payload = parsed,
            protocol = protocol,
            statusCode = responseCode,
            nativeToolCallRequested = request.request.tools.isNotEmpty(),
            completion = completion,
          )
          val content = extractMessageContent(parsed, protocol)
          val success = LiteLlmProviderResult.Success(
            outputText = content,
            completion = completion,
            finishReason = finishReason,
            providerResponseId = providerResponseId,
            providerLineageId = providerLineageId(
              request = request,
              protocol = protocol,
              providerResponseId = providerResponseId,
            ),
            metadata = responseMetadata,
          )
          maybeAutoContinueBuiltinWebSearch(
            request = request,
            payload = parsed,
            completion = completion,
            success = success,
          ) ?: if (content.isBlank() && (completion?.hasVisibleContent != true)) {
            LiteLlmProviderResult.Failure(
              errorCode = "PROVIDER_EMPTY_RESPONSE",
              errorMessage = "Provider returned an empty completion payload.",
              completion = completion,
              providerResponseId = providerResponseId,
              providerLineageId = providerLineageId(
                request = request,
                protocol = protocol,
                providerResponseId = providerResponseId,
              ),
              metadata = responseMetadata,
            )
          } else {
            success
          }
        }
      }
    } catch (timeout: java.net.SocketTimeoutException) {
      LiteLlmProviderResult.Timeout(
        errorMessage = timeout.message ?: "Provider request timed out.",
        metadata = requestDiagnostics,
      )
    } catch (exception: Exception) {
      LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_TRANSPORT_ERROR",
        errorMessage = exception.message ?: exception::class.java.simpleName,
        metadata = requestDiagnostics + mapOf("exceptionType" to exception::class.java.name),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun buildEndpointUrl(
    baseUrl: String,
    protocol: String,
  ): String {
    val trimmed = baseUrl.trimEnd('/')
    return when (protocol) {
      LlmProviderProtocols.ANTHROPIC -> when {
        trimmed.endsWith("/v1/messages") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/messages"
        else -> "$trimmed/v1/messages"
      }

      LlmProviderProtocols.OPENAI_RESPONSES -> when {
        trimmed.endsWith("/v1/responses") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/responses"
        else -> "$trimmed/v1/responses"
      }

      else -> when {
        trimmed.endsWith("/chat/completions") -> trimmed
        trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
        else -> "$trimmed/chat/completions"
      }
    }
  }

  private fun buildRequestBody(request: LiteLlmProviderRequest): String {
    val protocol = resolvedProtocol(request)
    return when (protocol) {
      LlmProviderProtocols.ANTHROPIC -> buildAnthropicRequestBody(
        request,
        streamResponses = shouldStreamResponses(request, protocol),
      )
      LlmProviderProtocols.OPENAI_RESPONSES -> buildOpenAiResponsesRequestBody(request)
      else -> buildOpenAiRequestBody(request)
    }
  }

  private fun buildRequestBody(
    request: LiteLlmProviderRequest,
    streamResponses: Boolean,
  ): String {
    val protocol = resolvedProtocol(request)
    return when (protocol) {
      LlmProviderProtocols.ANTHROPIC -> buildAnthropicRequestBody(
        request,
        streamResponses = streamResponses,
      )
      LlmProviderProtocols.OPENAI_RESPONSES -> buildOpenAiResponsesRequestBody(request)
      else -> buildOpenAiRequestBody(request)
    }
  }

  private fun buildOpenAiRequestBody(request: LiteLlmProviderRequest): String {
    val builtinWebSearchDialect = openAiBuiltinWebSearchDialect(request)
    val payload = JSONObject()
      .put("model", request.route.model)
      .put("messages", buildOpenAiMessagesArray(request))

    if (request.request.tools.isNotEmpty() || request.request.builtinTools.isNotEmpty()) {
      buildOpenAiToolsArray(request)
        .takeIf { tools -> tools.length() > 0 }
        ?.let { tools -> payload.put("tools", tools) }
    }

    applyOpenAiToolControl(payload, request.request)
    if (builtinWebSearchDialect == OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH &&
      request.request.builtinTools.any { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }
    ) {
      payload.put(
        "thinking",
        JSONObject().put("type", "disabled"),
      )
    }
    request.route.metadata["temperature"]?.toDoubleOrNull()?.let { payload.put("temperature", it) }
    request.route.metadata["max_tokens"]?.toIntOrNull()?.let { payload.put("max_tokens", it) }
    request.route.metadata["reasoning_effort"]?.takeIf { it.isNotBlank() }?.let { payload.put("reasoning_effort", it) }
    return payload.toString()
  }

  private fun buildAnthropicRequestBody(
    request: LiteLlmProviderRequest,
    streamResponses: Boolean = false,
  ): String {
    val payload = JSONObject()
      .put("model", request.route.model)
      .put("messages", buildAnthropicMessagesArray(request))
      .put(
        "max_tokens",
        request.route.metadata["max_tokens"]?.toIntOrNull() ?: DEFAULT_ANTHROPIC_MAX_TOKENS,
      )

    if (request.request.tools.isNotEmpty() || request.request.builtinTools.isNotEmpty()) {
      payload.put("tools", buildAnthropicToolsArray(request))
    }

    applyAnthropicToolControl(payload, request.request)
    request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
      payload.put("system", systemPrompt)
    }
    if (shouldDisableAnthropicThinking(request)) {
      payload.put(
        "thinking",
        JSONObject()
          .put("type", "disabled"),
      )
    } else {
      request.route.metadata["thinking_budget_tokens"]?.toIntOrNull()?.let { budgetTokens ->
        payload.put(
          "thinking",
          JSONObject()
            .put("type", "enabled")
            .put("budget_tokens", budgetTokens),
        )
      }
    }
    if (streamResponses) {
      payload.put("stream", true)
    }
    return payload.toString()
  }

  private fun buildOpenAiResponsesRequestBody(request: LiteLlmProviderRequest): String {
    val payload = JSONObject()
      .put("model", request.route.model)
      .put("input", buildResponsesInputArray(request))

    request.request.systemPrompt?.takeIf(String::isNotBlank)?.let { systemPrompt ->
      payload.put("instructions", systemPrompt)
    }
    if (responsesContinuationSupported(request)) {
      request.request.previousResponseId?.takeIf(String::isNotBlank)?.let { previousResponseId ->
        payload.put("previous_response_id", previousResponseId)
      }
    }
    buildResponsesToolsArray(request)
      ?.takeIf { tools -> tools.length() > 0 }
      ?.let { tools -> payload.put("tools", tools) }
    if (responsesCitationIncludeSupported(request)) {
      buildResponsesIncludeArray(request.request.builtinTools)
        ?.takeIf { include -> include.length() > 0 }
        ?.let { include -> payload.put("include", include) }
    }
    if (request.request.tools.isNotEmpty() || request.request.builtinTools.isNotEmpty()) {
      applyResponsesToolControl(payload, request.request)
    }
    request.route.metadata["temperature"]?.toDoubleOrNull()?.let { payload.put("temperature", it) }
    request.route.metadata["max_output_tokens"]
      ?.toIntOrNull()
      ?.let { maxOutputTokens -> payload.put("max_output_tokens", maxOutputTokens) }
      ?: request.route.metadata["max_tokens"]
        ?.toIntOrNull()
        ?.let { maxOutputTokens -> payload.put("max_output_tokens", maxOutputTokens) }
    request.route.metadata["reasoning_effort"]?.takeIf(String::isNotBlank)?.let { effort ->
      payload.put(
        "reasoning",
        JSONObject().put("effort", effort),
      )
    }
    return payload.toString()
  }

  private fun extractMessageContent(
    payload: JSONObject,
    protocol: String,
  ): String = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> extractAnthropicMessageContent(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> extractResponsesMessageContent(payload)
    else -> {
      val choice = payload.optJSONArray("choices")?.optJSONObject(0)
      extractOpenAiMessageContent(choice)
    }
  }

  private fun extractOpenAiMessageContent(choice: JSONObject?): String {
    if (choice == null) return ""
    val message = choice.optJSONObject("message") ?: return ""
    extractOpenAiContentValue(message.opt("content"))
      .takeIf(String::isNotBlank)
      ?.let { content ->
        return content
      }
    synthesizeToolCallPayload(message.optJSONArray("tool_calls"))
      ?.let { toolPayload ->
        return toolPayload
      }
    return extractProtocolPayloadFromAlternateFields(
      choice = choice,
      message = message,
    ).orEmpty()
  }

  private fun extractOpenAiContentValue(rawContent: Any?): String = when (rawContent) {
    is String -> rawContent
    is JSONArray -> buildString {
      for (index in 0 until rawContent.length()) {
        val segment = extractOpenAiContentValue(rawContent.opt(index))
        if (segment.isNotBlank()) {
          append(segment)
        }
      }
    }

    is JSONObject -> firstNonBlankString(
      rawContent.nonBlankString("text"),
      rawContent.optJSONObject("text")?.nonBlankString("value"),
      rawContent.nonBlankString("content"),
      rawContent.optJSONObject("content")?.nonBlankString("text"),
      rawContent.nonBlankString("value"),
    ).orEmpty()

    else -> ""
  }

  private fun synthesizeToolCallPayload(toolCalls: JSONArray?): String? {
    if (toolCalls == null || toolCalls.length() == 0) {
      return null
    }
    val normalizedCalls = JSONArray()
    for (index in 0 until toolCalls.length()) {
      val toolCall = toolCalls.optJSONObject(index) ?: continue
      val function = toolCall.optJSONObject("function") ?: continue
      val toolName = function.nonBlankString("name") ?: continue
      val arguments = parseToolCallArguments(
        rawArguments = function.opt("arguments"),
        location = "tool_calls[$index].function.arguments",
      )
      if (arguments.error != null) {
        continue
      }
      normalizedCalls.put(
        JSONObject()
          .put("tool_name", toolName)
          .put("arguments", arguments.arguments),
      )
    }
    if (normalizedCalls.length() == 0) {
      return null
    }
    return JSONObject()
      .put("tool_calls", normalizedCalls)
      .toString()
  }

  private fun parseToolCallArguments(
    rawArguments: Any?,
    location: String,
  ): ToolArgumentsParseResult = when (rawArguments) {
    null,
    JSONObject.NULL,
    -> ToolArgumentsParseResult(arguments = JSONObject())

    is JSONObject -> ToolArgumentsParseResult(arguments = rawArguments)

    is String -> {
      val trimmed = rawArguments.trim()
      if (trimmed.isEmpty()) {
        ToolArgumentsParseResult(
          arguments = JSONObject(),
          error = "$location must be a valid JSON object; received an empty string.",
        )
      } else {
        runCatching { JSONObject(trimmed) }
          .fold(
            onSuccess = { parsed ->
              ToolArgumentsParseResult(arguments = parsed)
            },
            onFailure = { error ->
              ToolArgumentsParseResult(
                arguments = JSONObject(),
                error = buildString {
                  append("$location must be a valid JSON object. ")
                  append("Parser error: ${error.message ?: error::class.java.simpleName}. ")
                  append("Received: ${previewForDiagnostic(trimmed)}")
                },
              )
            },
          )
      }
    }

    else -> ToolArgumentsParseResult(
      arguments = JSONObject(),
      error = "$location must be a JSON object; received ${describeJsonValue(rawArguments)}.",
    )
  }

  private fun extractProtocolPayloadFromAlternateFields(
    choice: JSONObject,
    message: JSONObject,
  ): String? = listOf(
    extractOpenAiContentValue(message.opt("reasoning_content")),
    extractOpenAiContentValue(message.opt("reasoning")),
    extractOpenAiContentValue(choice.opt("text")),
  )
    .asSequence()
    .map(String::trim)
    .firstOrNull { candidate ->
      candidate.isNotBlank() && looksLikeProtocolPayload(candidate)
    }

  private fun extractOpenAiReasoningText(
    choice: JSONObject,
    message: JSONObject,
  ): String? = listOf(
    extractOpenAiContentValue(message.opt("reasoning_content")),
    extractOpenAiContentValue(message.opt("reasoning")),
    extractOpenAiContentValue(choice.opt("text")),
  )
    .asSequence()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)

  private fun extractResponsesMessageContent(payload: JSONObject): String {
    val textSegments = responsesMessageTextSegments(payload)
    val orderedText = textSegments.ordered.joinToString(separator = "\n").trim()
    if (orderedText.isNotBlank()) {
      return orderedText
    }
    return synthesizeResponsesToolCallPayload(payload.optJSONArray("output")).orEmpty()
  }

  private fun responsesMessageTextSegments(payload: JSONObject): ResponsesTextSegments {
    val output = payload.optJSONArray("output") ?: return ResponsesTextSegments()
    val commentary = mutableListOf<String>()
    val finalAnswer = mutableListOf<String>()
    val unphased = mutableListOf<String>()
    val ordered = mutableListOf<String>()
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "message") {
        continue
      }
      val text = extractResponsesMessageText(item)
        .trim()
        .takeIf(String::isNotBlank)
        ?: continue
      ordered += text
      when (item.optString("phase").trim().lowercase()) {
        "commentary" -> commentary += text
        "final_answer" -> finalAnswer += text
        else -> unphased += text
      }
    }
    return ResponsesTextSegments(
      commentary = commentary.toList(),
      finalAnswer = finalAnswer.toList(),
      unphased = unphased.toList(),
      ordered = ordered.toList(),
    )
  }

  private fun extractResponsesMessageText(message: JSONObject): String = when (val content = message.opt("content")) {
    is String -> content
    is JSONArray -> buildString {
      for (index in 0 until content.length()) {
        val segment = extractResponsesContentText(content.opt(index))
        if (segment.isNotBlank()) {
          if (isNotEmpty()) {
            append('\n')
          }
          append(segment)
        }
      }
    }

    is JSONObject -> extractResponsesContentText(content)
    else -> firstNonBlankString(
      message.nonBlankString("text"),
      message.optJSONObject("text")?.nonBlankString("value"),
    ).orEmpty()
  }

  private fun extractResponsesContentText(content: Any?): String = when (content) {
    is String -> content
    is JSONArray -> buildString {
      for (index in 0 until content.length()) {
        val segment = extractResponsesContentText(content.opt(index))
        if (segment.isNotBlank()) {
          if (isNotEmpty()) {
            append('\n')
          }
          append(segment)
        }
      }
    }

    is JSONObject -> firstNonBlankString(
      content.nonBlankString("text"),
      content.optJSONObject("text")?.nonBlankString("value"),
      content.nonBlankString("output_text"),
      content.nonBlankString("summary_text"),
      content.nonBlankString("refusal"),
      content.nonBlankString("content"),
      content.nonBlankString("value"),
    ).orEmpty()

    else -> ""
  }

  private fun synthesizeResponsesToolCallPayload(output: JSONArray?): String? {
    if (output == null || output.length() == 0) {
      return null
    }
    val normalizedCalls = JSONArray()
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "function_call") {
        continue
      }
      val toolName = item.nonBlankString("name") ?: continue
      val arguments = parseToolCallArguments(
        rawArguments = item.opt("arguments"),
        location = "output[$index].arguments",
      )
      if (arguments.error != null) {
        continue
      }
      normalizedCalls.put(
        JSONObject()
          .put("tool_name", toolName)
          .put("arguments", arguments.arguments),
      )
    }
    if (normalizedCalls.length() == 0) {
      return null
    }
    return JSONObject()
      .put("tool_calls", normalizedCalls)
      .toString()
  }

  private fun extractResponsesReasoningText(payload: JSONObject): String? {
    val output = payload.optJSONArray("output") ?: return null
    val reasoningBlocks = mutableListOf<String>()
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "reasoning") {
        continue
      }
      val reasoningText = firstNonBlankString(
        extractResponsesContentText(item.opt("summary")).trim().takeIf(String::isNotBlank),
        extractResponsesContentText(item.opt("content")).trim().takeIf(String::isNotBlank),
        item.nonBlankString("text"),
      )
      reasoningText?.let(reasoningBlocks::add)
    }
    return reasoningBlocks.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
  }

  private fun looksLikeProtocolPayload(text: String): Boolean {
    val jsonCandidate = extractEmbeddedJsonObject(text) ?: return false
    val parsed = runCatching { JSONObject(jsonCandidate) }.getOrNull() ?: return false
    val type = parsed.optString("type").trim().lowercase()
    return parsed.optJSONArray("tool_calls") != null ||
      parsed.optString("tool_name").isNotBlank() ||
      parsed.optString("answer").isNotBlank() ||
      type in setOf("tool_call", "tool", "final", "answer", "progress", "commentary", "status")
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
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
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  private fun describeJsonValue(value: Any?): String = when (value) {
    null,
    JSONObject.NULL,
    -> "null"
    is JSONObject -> "object"
    is JSONArray -> "array"
    is String -> "string"
    is Boolean -> "boolean"
    is Number -> "number"
    else -> value::class.java.simpleName
  }

  private fun previewForDiagnostic(raw: String): String {
    val flattened = raw.replace(Regex("\\s+"), " ").trim()
    if (flattened.length <= 160) {
      return flattened
    }
    return flattened.take(157) + "..."
  }

  private fun extractAnthropicMessageContent(payload: JSONObject): String {
    val content = payload.optJSONArray("content") ?: return ""
    return buildString {
      for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        if (block.optString("type") != "text") {
          continue
        }
        val text = block.optString("text")
        if (text.isNotBlank()) {
          append(text)
        }
      }
    }
  }

  private fun extractAnthropicThinkingText(block: JSONObject): String? = firstNonBlankString(
    block.optString("thinking").trim().takeIf(String::isNotBlank),
    extractOpenAiContentValue(block.opt("thinking")),
    block.optString("text").trim().takeIf(String::isNotBlank),
  )

  private fun structuredCompletion(
    payload: JSONObject,
    protocol: String,
  ): LiteLlmStructuredCompletion? = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicStructuredCompletion(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesStructuredCompletion(payload)
    else -> openAiStructuredCompletion(payload)
  }

  private fun openAiStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val message = choice.optJSONObject("message") ?: return null
    val toolCallParse = openAiStructuredToolCalls(message.optJSONArray("tool_calls"))
    val toolCalls = toolCallParse.toolCalls
    val textContent = extractOpenAiContentValue(message.opt("content"))
      .trim()
      .takeIf(String::isNotBlank)
    val protocolPayload = firstNonBlankString(
      textContent?.takeIf(::looksLikeProtocolPayload),
      extractProtocolPayloadFromAlternateFields(
        choice = choice,
        message = message,
      )?.trim()?.takeIf(String::isNotBlank),
    )
    val commentaryText = textContent?.takeIf { toolCalls.isNotEmpty() }
    val finalText = textContent?.takeUnless { text ->
      toolCalls.isNotEmpty() || looksLikeProtocolPayload(text)
    }
    val reasoningText = extractOpenAiReasoningText(
      choice = choice,
      message = message,
    )?.trim()?.takeIf { text ->
      text.isNotBlank() && !looksLikeProtocolPayload(text)
    }
    val rawText = when {
      !protocolPayload.isNullOrBlank() -> protocolPayload
      toolCallParse.errors.isNotEmpty() -> toolCallParse.rawPreview
      toolCalls.isNotEmpty() -> null
      else -> finalText
    }
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = finalText,
      commentaryText = commentaryText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallParse.errors,
    )
  }

  private fun openAiStructuredToolCalls(toolCalls: JSONArray?): StructuredToolCallParseResult {
    if (toolCalls == null || toolCalls.length() == 0) {
      return StructuredToolCallParseResult()
    }
    val normalizedCalls = mutableListOf<LiteLlmStructuredToolCall>()
    val errors = mutableListOf<String>()
    val seenToolCallIds = linkedSetOf<String>()
    for (index in 0 until toolCalls.length()) {
      val location = "tool_calls[$index]"
      val toolCall = toolCalls.optJSONObject(index)
      if (toolCall == null) {
        errors += "$location must be a JSON object."
        continue
      }
      val function = toolCall.optJSONObject("function")
      if (function == null) {
        errors += "$location.function is missing or not a JSON object."
        continue
      }
      val toolName = function.nonBlankString("name")
      if (toolName == null) {
        errors += "$location.function.name must be a non-blank string."
        continue
      }
      val argumentsResult = parseToolCallArguments(
        rawArguments = function.opt("arguments"),
        location = "$location.function.arguments",
      )
      argumentsResult.error?.let { error -> errors += error }
      if (argumentsResult.error != null) {
        continue
      }
      val toolCallId = toolCall.nonBlankString("id")
      if (toolCallId == null) {
        errors += "$location.id must be a non-blank string."
        continue
      }
      if (!seenToolCallIds.add(toolCallId)) {
        errors += "$location.id duplicates tool call id '$toolCallId'."
        continue
      }
      normalizedCalls += LiteLlmStructuredToolCall(
        id = toolCallId,
        toolName = toolName,
        arguments = jsonObjectFrom(argumentsResult.arguments),
      )
    }
    return StructuredToolCallParseResult(
      toolCalls = normalizedCalls,
      errors = errors.toList(),
      rawPreview = toolCalls.toString().trim().takeIf(String::isNotBlank),
    )
  }

  private fun responsesStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val output = payload.optJSONArray("output") ?: return null
    val toolCallParse = responsesStructuredToolCalls(output)
    val toolCalls = toolCallParse.toolCalls
    val textSegments = responsesMessageTextSegments(payload)
    val orderedText = textSegments.ordered.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val phasedCommentaryText = textSegments.commentary.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val finalPhaseText = textSegments.finalAnswer.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val unphasedText = textSegments.unphased.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val commentaryText = buildList<String> {
      phasedCommentaryText?.let(::add)
      if (toolCalls.isNotEmpty()) {
        unphasedText?.let(::add)
      }
    }.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val finalText = firstNonBlankString(
      finalPhaseText,
      unphasedText?.takeIf { toolCalls.isEmpty() },
    )?.takeUnless(::looksLikeProtocolPayload)
    val reasoningText = extractResponsesReasoningText(payload)
    val rawText = when {
      orderedText != null && looksLikeProtocolPayload(orderedText) -> orderedText
      toolCallParse.errors.isNotEmpty() -> toolCallParse.rawPreview
      toolCalls.isNotEmpty() -> null
      else -> finalText ?: commentaryText
    }
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = finalText,
      commentaryText = commentaryText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallParse.errors,
    )
  }

  private fun responsesStructuredToolCalls(output: JSONArray?): StructuredToolCallParseResult {
    if (output == null || output.length() == 0) {
      return StructuredToolCallParseResult()
    }
    val normalizedCalls = mutableListOf<LiteLlmStructuredToolCall>()
    val errors = mutableListOf<String>()
    val seenToolCallIds = linkedSetOf<String>()
    for (index in 0 until output.length()) {
      val location = "output[$index]"
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "function_call") {
        continue
      }
      val toolName = item.nonBlankString("name")
      if (toolName == null) {
        errors += "$location.name must be a non-blank string."
        continue
      }
      val argumentsResult = parseToolCallArguments(
        rawArguments = item.opt("arguments"),
        location = "$location.arguments",
      )
      argumentsResult.error?.let { error -> errors += error }
      if (argumentsResult.error != null) {
        continue
      }
      val toolCallId = item.nonBlankString("call_id") ?: item.nonBlankString("id")
      if (toolCallId == null) {
        errors += "$location.call_id must be a non-blank string."
        continue
      }
      if (!seenToolCallIds.add(toolCallId)) {
        errors += "$location.call_id duplicates tool call id '$toolCallId'."
        continue
      }
      normalizedCalls += LiteLlmStructuredToolCall(
        id = toolCallId,
        toolName = toolName,
        arguments = jsonObjectFrom(argumentsResult.arguments),
      )
    }
    return StructuredToolCallParseResult(
      toolCalls = normalizedCalls,
      errors = errors.toList(),
      rawPreview = output.toString().trim().takeIf(String::isNotBlank),
    )
  }

  private fun anthropicStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val content = payload.optJSONArray("content") ?: return null
    val textBlocks = mutableListOf<String>()
    val thinkingBlocks = mutableListOf<String>()
    val toolCalls = mutableListOf<LiteLlmStructuredToolCall>()
    val toolCallErrors = mutableListOf<String>()
    val seenToolCallIds = linkedSetOf<String>()
    anthropicBlocks@ for (index in 0 until content.length()) {
      val location = "content[$index]"
      val block = content.optJSONObject(index)
      if (block == null) {
        toolCallErrors += "$location must be a JSON object."
        continue@anthropicBlocks
      }
      when (block.optString("type")) {
        "text" -> block.optString("text")
          .trim()
          .takeIf(String::isNotBlank)
          ?.let(textBlocks::add)

        "thinking" -> extractAnthropicThinkingText(block)
          ?.let(thinkingBlocks::add)

        "tool_use" -> {
          val toolName = block.optString("name").trim().takeIf(String::isNotBlank)
          if (toolName == null) {
            toolCallErrors += "$location.name must be a non-blank string."
            continue@anthropicBlocks
          }
          val input = block.opt("input")
          val arguments = when (input) {
            null,
            JSONObject.NULL,
            -> JSONObject()
            is JSONObject -> input
            else -> {
              toolCallErrors += "$location.input must be a JSON object; received ${describeJsonValue(input)}."
              continue@anthropicBlocks
            }
          }
          val toolCallId = block.optString("id").trim().takeIf(String::isNotBlank)
          if (toolCallId == null) {
            toolCallErrors += "$location.id must be a non-blank string."
            continue@anthropicBlocks
          }
          if (!seenToolCallIds.add(toolCallId)) {
            toolCallErrors += "$location.id duplicates tool call id '$toolCallId'."
            continue@anthropicBlocks
          }
          toolCalls += LiteLlmStructuredToolCall(
            id = toolCallId,
            toolName = toolName,
            arguments = jsonObjectFrom(arguments),
          )
        }
      }
    }
    val textContent = textBlocks.joinToString(separator = "").trim().takeIf(String::isNotBlank)
    val commentaryText = textContent?.takeIf { toolCalls.isNotEmpty() }
    val finalText = textContent?.takeUnless { text ->
      toolCalls.isNotEmpty() || looksLikeProtocolPayload(text)
    }
    val rawText = when {
      textContent != null && looksLikeProtocolPayload(textContent) -> textContent
      toolCallErrors.isNotEmpty() -> content.toString().trim().takeIf(String::isNotBlank)
      toolCalls.isNotEmpty() -> null
      else -> finalText
    }
    val reasoningText = thinkingBlocks.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = finalText,
      commentaryText = commentaryText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallErrors,
    )
  }

  private fun buildStructuredCompletion(
    toolCalls: List<LiteLlmStructuredToolCall>,
    finalText: String? = null,
    commentaryText: String? = null,
    reasoningText: String? = null,
    rawText: String? = null,
    toolCallErrors: List<String> = emptyList(),
  ): LiteLlmStructuredCompletion? {
    val normalizedFinalText = finalText?.trim()?.takeIf(String::isNotBlank)
    val normalizedCommentaryText = commentaryText?.trim()?.takeIf(String::isNotBlank)
    val normalizedReasoningText = reasoningText?.trim()?.takeIf(String::isNotBlank)
    val normalizedRawText = rawText?.trim()?.takeIf(String::isNotBlank)
    val normalizedToolCallErrors = toolCallErrors.map(String::trim).filter(String::isNotBlank)
    if (
      toolCalls.isEmpty() &&
      normalizedFinalText == null &&
      normalizedCommentaryText == null &&
      normalizedReasoningText == null &&
      normalizedRawText == null &&
      normalizedToolCallErrors.isEmpty()
    ) {
      return null
    }
    return LiteLlmStructuredCompletion(
      toolCalls = toolCalls,
      finalText = normalizedFinalText,
      commentaryText = normalizedCommentaryText,
      reasoningText = normalizedReasoningText,
      rawText = normalizedRawText,
      toolCallErrors = normalizedToolCallErrors,
    )
  }

  private fun jsonObjectFrom(payload: JSONObject): JsonObject = runCatching {
    JSON_CODEC.parseToJsonElement(payload.toString()) as? JsonObject
  }.getOrNull() ?: JsonObject(emptyMap())

  private fun finishReasonFor(
    payload: JSONObject,
    protocol: String,
  ): String? = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> payload.optString("stop_reason").takeIf { it.isNotBlank() }
    LlmProviderProtocols.OPENAI_RESPONSES -> payload.optString("status")
      .trim()
      .takeIf(String::isNotBlank)
      ?: payload.optJSONObject("incomplete_details")
        ?.optString("reason")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    else -> payload.optJSONArray("choices")
      ?.optJSONObject(0)
      ?.optString("finish_reason")
      ?.takeIf { it.isNotBlank() }
  }

  private fun responseMetadata(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    protocol: String,
    statusCode: Int,
    nativeToolCallRequested: Boolean,
    completion: LiteLlmStructuredCompletion?,
  ): Map<String, String> = buildMap {
    put("statusCode", statusCode.toString())
    put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, nativeToolCallRequested.toString())
    payload.optString("id")
      .takeIf { value -> value.isNotBlank() }
      ?.let { providerRequestId ->
        put("providerRequestId", providerRequestId)
      }
    put(LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE, responseShape(request, payload, protocol))
    put(
      LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED,
      nativeToolCallObserved(payload, protocol).toString(),
    )
    val builtinWebSearchObservations = builtinWebSearchObservations(
      request = request,
      payload = payload,
      protocol = protocol,
    )
    put(
      LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED,
      builtinWebSearchObservations.isNotEmpty().toString(),
    )
    if (builtinWebSearchObservations.isNotEmpty()) {
      put(
        LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON,
        JSON_CODEC.encodeToString(
          ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
          builtinWebSearchObservations,
        ),
      )
      openAiBuiltinWebSearchDialect(request)
        ?.takeIf { protocol == LlmProviderProtocols.OPENAI }
        ?.let { dialect ->
          put(LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT, dialect.wireValue)
        }
    }
    val citationCount = responseCitationCount(payload, protocol)
    if (citationCount > 0) {
      put(LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT, citationCount.toString())
    }
    if (protocol == LlmProviderProtocols.OPENAI_RESPONSES) {
      val textSegments = responsesMessageTextSegments(payload)
      put(
        LiteLlmMetadataKeys.RESPONSES_COMMENTARY_PHASE_OBSERVED,
        textSegments.commentary.isNotEmpty().toString(),
      )
      put(
        LiteLlmMetadataKeys.RESPONSES_FINAL_PHASE_OBSERVED,
        textSegments.finalAnswer.isNotEmpty().toString(),
      )
    }
    val reasoningText = completion?.reasoningText?.trim()?.takeIf(String::isNotBlank)
    put(LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED, (reasoningText != null).toString())
    if (reasoningText != null) {
      put(LiteLlmMetadataKeys.PROVIDER_REASONING_TURN_COUNT, "1")
      put(LiteLlmMetadataKeys.PROVIDER_REASONING_CHARS, reasoningText.length.toString())
    }
  }

  private fun requestDiagnosticsMetadata(
    request: LiteLlmProviderRequest,
  ): Map<String, String> = buildMap {
    put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, request.request.tools.isNotEmpty().toString())
    put(
      LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_REQUESTED,
      request.request.builtinTools.any { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }.toString(),
    )
    request.request.previousResponseId
      ?.takeIf(String::isNotBlank)
      ?.let { put("previousResponseIdPresent", "true") }
    if (request.request.responseApiPreferred) {
      put("responseApiPreferred", "true")
    }
  }

  private fun providerLineageId(
    request: LiteLlmProviderRequest,
    protocol: String,
    providerResponseId: String?,
  ): String? = when (protocol) {
    LlmProviderProtocols.OPENAI_RESPONSES -> request.request.metadata[LiteLlmMetadataKeys.RESPONSES_LINEAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: request.request.previousResponseId
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: providerResponseId?.trim()?.takeIf(String::isNotBlank)
    else -> null
  }

  private fun openAiBuiltinWebSearchDialect(
    request: LiteLlmProviderRequest,
  ): OpenAiBuiltinWebSearchDialect? {
    if (request.request.builtinTools.none { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }) {
      return null
    }
    OpenAiBuiltinWebSearchDialect.fromWireValue(
      request.route.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT],
    )?.let { dialect ->
      return dialect
    }
    inferOpenAiBuiltinWebSearchDialectFromModel(request.route.model)?.let { dialect ->
      return dialect
    }
    val host = runCatching {
      URI(request.route.baseUrl?.trim().orEmpty()).host.orEmpty().lowercase()
    }.getOrDefault("")
    return when {
      host.contains("bigmodel.cn") -> OpenAiBuiltinWebSearchDialect.OPENAI_CHAT_WEB_SEARCH
      host.contains("moonshot.ai") || host.contains("moonshot.cn") ->
        OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH
      else -> null
    }
  }

  private fun inferOpenAiBuiltinWebSearchDialectFromModel(
    model: String?,
  ): OpenAiBuiltinWebSearchDialect? {
    val normalized = model
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
      ?: return null
    if (normalized.contains("kimi") || normalized.contains("moonshot")) {
      return OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH
    }
    if (normalized.contains("glm")) {
      return OpenAiBuiltinWebSearchDialect.OPENAI_CHAT_WEB_SEARCH
    }
    return null
  }

  private fun maybeAutoContinueBuiltinWebSearch(
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

  private fun maybeAutoContinueOpenAiBuiltinWebSearch(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    completion: LiteLlmStructuredCompletion?,
    success: LiteLlmProviderResult.Success,
  ): LiteLlmProviderResult? {
    if (resolvedProtocol(request) != LlmProviderProtocols.OPENAI) {
      return null
    }
    val dialect = openAiBuiltinWebSearchDialect(request)
      ?: return null
    if (dialect != OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH) {
      return null
    }
    val toolCalls = completion?.toolCalls.orEmpty()
    if (toolCalls.isEmpty() || toolCalls.any { toolCall -> toolCall.toolName != KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME }) {
      return null
    }
    val loopDepth = request.request.metadata[KIMI_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY]
      ?.toIntOrNull()
      ?: 0
    if (loopDepth >= MAX_KIMI_BUILTIN_WEB_SEARCH_AUTO_TURNS) {
      return LiteLlmProviderResult.Failure(
        errorCode = "KIMI_BUILTIN_WEB_SEARCH_LOOP_EXHAUSTED",
        errorMessage = "Kimi builtin web search did not converge to a final answer.",
        completion = completion,
        providerResponseId = success.providerResponseId,
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = success.metadata,
          dialect = dialect,
          observations = toolCalls.mapNotNull { toolCall ->
            openAiBuiltinWebSearchObservationFromArguments(JSONObject(toolCall.arguments.toString()))
          },
        ),
      )
    }
    val assistantMessage = openAiAssistantMessageFromPayload(payload) ?: return success
    val echoedToolResults = toolCalls.map { toolCall ->
      LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.TOOL,
        toolResult = LiteLlmGatewayToolResult(
          toolCallId = toolCall.id,
          toolName = toolCall.toolName,
          content = toolCall.arguments.toString(),
        ),
      )
    }
    val observations = toolCalls.mapNotNull { toolCall ->
      openAiBuiltinWebSearchObservationFromArguments(JSONObject(toolCall.arguments.toString()))
    }
    val followupRequest = request.copy(
      request = request.request.copy(
        messages = openAiConversationMessages(request.request) + assistantMessage + echoedToolResults,
        metadata = request.request.metadata + mapOf(
          KIMI_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY to (loopDepth + 1).toString(),
        ),
      ),
    )
    val followupResult = execute(followupRequest)
    return when (followupResult) {
      is LiteLlmProviderResult.Success -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          dialect = dialect,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.Failure -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          dialect = dialect,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.Timeout -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          dialect = dialect,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.RateLimited -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          dialect = dialect,
          observations = observations,
        ),
      )
    }
  }

  private fun maybeAutoContinueAnthropicBuiltinWebSearch(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    success: LiteLlmProviderResult.Success,
  ): LiteLlmProviderResult? {
    if (request.request.builtinTools.none { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }) {
      return null
    }
    if (!payload.optString("stop_reason").trim().equals("pause_turn", ignoreCase = true)) {
      return null
    }
    val content = payload.optJSONArray("content") ?: return null
    if (!hasAnthropicServerToolUse(content)) {
      return null
    }
    val observations = anthropicBuiltinWebSearchObservations(
      request = request,
      payload = payload,
    )
    val loopDepth = request.request.metadata[ANTHROPIC_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY]
      ?.toIntOrNull()
      ?: 0
    if (loopDepth >= MAX_ANTHROPIC_BUILTIN_WEB_SEARCH_AUTO_TURNS) {
      return LiteLlmProviderResult.Failure(
        errorCode = "ANTHROPIC_BUILTIN_WEB_SEARCH_LOOP_EXHAUSTED",
        errorMessage = "Anthropic builtin web search did not converge to a final answer.",
        completion = success.completion,
        providerResponseId = success.providerResponseId,
        providerLineageId = success.providerLineageId,
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = success.metadata,
          observations = observations,
        ),
      )
    }
    val followupRequest = request.copy(
      request = request.request.copy(
        messages = anthropicConversationMessages(request.request),
        metadata = request.request.metadata + mapOf(
          ANTHROPIC_SERVER_TOOL_CONTINUATION_CONTENT_KEY to content.toString(),
          ANTHROPIC_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY to (loopDepth + 1).toString(),
        ),
      ),
    )
    val followupResult = execute(followupRequest)
    return when (followupResult) {
      is LiteLlmProviderResult.Success -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.Failure -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.Timeout -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          observations = observations,
        ),
      )

      is LiteLlmProviderResult.RateLimited -> followupResult.copy(
        metadata = mergeBuiltinWebSearchMetadata(
          metadata = followupResult.metadata,
          observations = observations,
        ),
      )
    }
  }

  private fun hasAnthropicServerToolUse(
    content: JSONArray,
  ): Boolean {
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      if (block.optString("type") == "server_tool_use") {
        return true
      }
    }
    return false
  }

  private fun openAiConversationMessages(
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

  private fun anthropicConversationMessages(
    request: LiteLlmGatewayRequest,
  ): List<LiteLlmGatewayMessage> = openAiConversationMessages(request)

  private fun mergeBuiltinWebSearchMetadata(
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

  private fun openAiAssistantMessageFromPayload(
    payload: JSONObject,
  ): LiteLlmGatewayMessage? {
    val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val message = choice.optJSONObject("message") ?: return null
    val content = extractOpenAiContentValue(message.opt("content"))
      .trim()
      .takeIf(String::isNotBlank)
    val toolCalls = openAiStructuredToolCalls(message.optJSONArray("tool_calls")).toolCalls
    if (content == null && toolCalls.isEmpty()) {
      return null
    }
    return LiteLlmGatewayMessage(
      role = LiteLlmGatewayMessageRole.ASSISTANT,
      content = content,
      toolCalls = toolCalls,
    )
  }

  private fun mergeBuiltinWebSearchMetadata(
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

  private fun responsesContinuationSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CONTINUATION) ||
      request.route.metadata["responsesContinuationSupported"]
        ?.trim()
        ?.lowercase() == "true"

  private fun responsesAssistantPhasesSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_ASSISTANT_PHASES) ||
      request.route.metadata["assistantPhaseSupported"]
        ?.trim()
        ?.lowercase() == "true"

  private fun responsesCitationIncludeSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CITATION_INCLUDE) ||
      request.route.metadata["citationIncludeSupported"]
        ?.trim()
        ?.lowercase() == "true"

  private fun requestMetadataBoolean(
    request: LiteLlmProviderRequest,
    key: String,
  ): Boolean = request.request.metadata[key]
    ?.trim()
    ?.lowercase() == "true"

  private fun buildOpenAiToolsArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    val builtinWebSearchDialect = openAiBuiltinWebSearchDialect(request)
    request.request.builtinTools.forEach { tool ->
      buildOpenAiBuiltinTool(
        tool = tool,
        dialect = builtinWebSearchDialect,
      )?.let(::put)
    }
    request.request.tools.forEach { tool ->
      put(
        JSONObject()
          .put("type", "function")
          .put(
            "function",
            JSONObject()
              .put("name", tool.name)
              .put("description", tool.description)
              .put("parameters", JSONObject(tool.inputSchema.toString()))
              .apply {
                tool.strict?.let { strict -> put("strict", strict) }
              },
          ),
      )
    }
  }

  private fun buildOpenAiBuiltinTool(
    tool: LiteLlmBuiltinToolDefinition,
    dialect: OpenAiBuiltinWebSearchDialect?,
  ): JSONObject? = when (tool.type) {
    LiteLlmBuiltinToolType.WEB_SEARCH -> when (dialect) {
      OpenAiBuiltinWebSearchDialect.OPENAI_CHAT_WEB_SEARCH -> JSONObject()
        .put("type", "web_search")
        .put(
          "web_search",
          JSONObject()
            .put("enable", true)
            .apply {
              if (tool.includeSources) {
                put("search_result", true)
              }
              if (tool.domains.isNotEmpty()) {
                put("search_domain_filter", tool.domains.joinToString(separator = ","))
              }
            },
        )

      OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH -> JSONObject()
        .put("type", "builtin_function")
        .put(
          "function",
          JSONObject().put("name", KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME),
        )

      null -> null
    }
  }

  private fun buildResponsesToolsArray(request: LiteLlmProviderRequest): JSONArray? {
    val tools = JSONArray()
    request.request.builtinTools.forEach { tool ->
      buildResponsesBuiltinTool(tool)?.let(tools::put)
    }
    request.request.tools.forEach { tool ->
      tools.put(
        JSONObject()
          .put("type", "function")
          .put("name", tool.name)
          .put("description", tool.description)
          .put("parameters", JSONObject(tool.inputSchema.toString()))
          .apply {
            tool.strict?.let { strict -> put("strict", strict) }
          },
      )
    }
    return tools.takeIf { it.length() > 0 }
  }

  private fun buildResponsesBuiltinTool(
    tool: LiteLlmBuiltinToolDefinition,
  ): JSONObject? = when (tool.type) {
    LiteLlmBuiltinToolType.WEB_SEARCH -> JSONObject()
      .put("type", "web_search")
  }

  private fun buildResponsesIncludeArray(
    builtinTools: List<LiteLlmBuiltinToolDefinition>,
  ): JSONArray? {
    val include = linkedSetOf<String>()
    builtinTools.forEach { tool ->
      when (tool.type) {
        LiteLlmBuiltinToolType.WEB_SEARCH -> if (tool.includeSources) {
          include += "web_search_call.action.sources"
        }
      }
    }
    if (include.isEmpty()) {
      return null
    }
    return JSONArray().apply {
      include.forEach(::put)
    }
  }

  private fun applyOpenAiToolControl(
    payload: JSONObject,
    request: LiteLlmGatewayRequest,
  ) {
    if (request.tools.isEmpty() && request.builtinTools.isEmpty()) {
      return
    }
    request.toolChoice?.let { toolChoice ->
      payload.put("tool_choice", buildOpenAiToolChoice(toolChoice))
    }
    request.parallelToolCalls?.let { parallelToolCalls ->
      payload.put("parallel_tool_calls", parallelToolCalls)
    }
  }

  private fun applyResponsesToolControl(
    payload: JSONObject,
    request: LiteLlmGatewayRequest,
  ) {
    if (request.tools.isEmpty() && request.builtinTools.isEmpty()) {
      return
    }
    request.toolChoice?.let { toolChoice ->
      payload.put("tool_choice", buildResponsesToolChoice(toolChoice))
    }
    request.parallelToolCalls?.let { parallelToolCalls ->
      payload.put("parallel_tool_calls", parallelToolCalls)
    }
  }

  private fun buildOpenAiToolChoice(toolChoice: LiteLlmToolChoice): Any = when (toolChoice.mode) {
    LiteLlmToolChoiceMode.AUTO -> "auto"
    LiteLlmToolChoiceMode.NONE -> "none"
    LiteLlmToolChoiceMode.REQUIRED -> "required"
    LiteLlmToolChoiceMode.TOOL -> JSONObject()
      .put("type", "function")
      .put("function", JSONObject().put("name", toolChoice.toolName))
  }

  private fun buildResponsesToolChoice(toolChoice: LiteLlmToolChoice): Any = when (toolChoice.mode) {
    LiteLlmToolChoiceMode.AUTO -> "auto"
    LiteLlmToolChoiceMode.NONE -> "none"
    LiteLlmToolChoiceMode.REQUIRED -> "required"
    LiteLlmToolChoiceMode.TOOL -> JSONObject()
      .put("type", "function")
      .put("name", toolChoice.toolName)
  }

  private fun buildOpenAiMessagesArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
      put(
        JSONObject()
          .put("role", "system")
          .put("content", systemPrompt),
      )
    }
    if (request.request.messages.isEmpty()) {
      put(
        JSONObject()
          .put("role", "user")
          .put("content", request.request.prompt),
      )
      return@apply
    }
    request.request.messages.forEach { message ->
      when (message.role) {
        LiteLlmGatewayMessageRole.SYSTEM -> {
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(
              JSONObject()
                .put("role", "system")
                .put("content", content),
            )
          }
        }

        LiteLlmGatewayMessageRole.USER -> {
          val assembly = multimodalAssemblyFor(
            request = request,
            message = message,
            allowInlineImages = true,
          )
          if (assembly.text != null || assembly.inlinePdfs.isNotEmpty() || assembly.inlineImages.isNotEmpty()) {
            put(
              JSONObject()
                .put("role", "user")
                .put("content", openAiUserContentPayload(assembly)),
            )
          }
        }

        LiteLlmGatewayMessageRole.ASSISTANT -> {
          val payload = JSONObject()
            .put("role", "assistant")
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            payload.put("content", content)
          }
          if (message.toolCalls.isNotEmpty()) {
            payload.put("tool_calls", buildOpenAiToolCallsArray(message))
            if (message.content.isNullOrBlank()) {
              payload.put("content", JSONObject.NULL)
            }
          }
          put(payload)
        }

        LiteLlmGatewayMessageRole.TOOL -> {
          buildOpenAiToolResultMessage(message.toolResult)?.let(::put)
        }
      }
    }
  }

  private fun buildResponsesInputArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    val assistantPhasesSupported = responsesAssistantPhasesSupported(request)
    if (request.request.messages.isEmpty()) {
      put(buildResponsesTextMessage(role = "user", content = request.request.prompt))
      return@apply
    }
    request.request.messages.forEach { message ->
      when (message.role) {
        LiteLlmGatewayMessageRole.SYSTEM -> {
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(buildResponsesTextMessage(role = "system", content = content))
          }
        }

        LiteLlmGatewayMessageRole.USER -> {
          val assembly = multimodalAssemblyFor(
            request = request,
            message = message,
            allowInlineImages = true,
          )
          when {
            assembly.inlinePdfs.isNotEmpty() || assembly.inlineImages.isNotEmpty() -> {
              put(buildResponsesUserMultimodalMessage(assembly))
            }
            !assembly.text.isNullOrBlank() -> put(buildResponsesTextMessage(role = "user", content = assembly.text))
          }
        }

        LiteLlmGatewayMessageRole.ASSISTANT -> {
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(
              buildResponsesTextMessage(
                role = "assistant",
                content = content,
                phase = if (assistantPhasesSupported) message.assistantPhase?.wireValue else null,
              ),
            )
          }
          message.toolCalls.forEach { toolCall ->
            put(
              JSONObject()
                .put("type", "function_call")
                .put(
                  "call_id",
                  requireToolCallId(
                    toolCall = toolCall,
                    location = "responses assistant tool call",
                  ),
                )
                .put("name", toolCall.toolName)
                .put("arguments", toolCall.arguments.toString()),
            )
          }
        }

        LiteLlmGatewayMessageRole.TOOL -> {
          buildResponsesToolResultItem(message.toolResult)?.let(::put)
        }
      }
    }
  }

  private fun buildResponsesTextMessage(
    role: String,
    content: String,
    phase: String? = null,
  ): JSONObject = JSONObject()
    .put("type", "message")
    .put("role", role)
    .put("content", content)
    .apply {
      phase?.takeIf(String::isNotBlank)?.let { normalizedPhase ->
        put("phase", normalizedPhase)
      }
    }

  private fun multimodalAssemblyFor(
    request: LiteLlmProviderRequest,
    message: LiteLlmGatewayMessage,
    allowInlineImages: Boolean,
  ): MultimodalMessageAssembly {
    val inlinePdfs = if (pdfInputSupported(request)) {
      message.attachments.mapNotNull(::encodeInlinePdfAttachment)
    } else {
      emptyList()
    }
    val inlineImages = if (allowInlineImages && visionInputSupported(request)) {
      message.attachments.mapNotNull(::encodeInlineImageAttachment)
    } else {
      emptyList()
    }
    val consumedAttachments = (inlinePdfs.map { encoded -> encoded.attachment } +
      inlineImages.map { encoded -> encoded.attachment })
      .toSet()
    val residualAttachments = message.attachments.filterNot { attachment -> attachment in consumedAttachments }
    return MultimodalMessageAssembly(
      text = contentWithAttachmentFallback(
        content = message.content,
        attachments = residualAttachments,
      ),
      inlinePdfs = inlinePdfs,
      inlineImages = inlineImages,
    )
  }

  private fun visionInputSupported(request: LiteLlmProviderRequest): Boolean =
    request.route.metadata["visionInputSupported"]
      ?.trim()
      ?.lowercase() == "true"

  private fun pdfInputSupported(request: LiteLlmProviderRequest): Boolean =
    request.route.metadata["pdfInputSupported"]
      ?.trim()
      ?.lowercase() == "true"

  private fun contentWithAttachmentFallback(
    content: String?,
    attachments: List<com.opencray.llm.LiteLlmGatewayAttachment>,
  ): String? {
    val blocks = mutableListOf<String>()
    content?.trim()?.takeIf(String::isNotBlank)?.let(blocks::add)
    if (attachments.isNotEmpty()) {
      blocks += attachmentFallbackText(attachments)
    }
    return blocks.joinToString(separator = "\n\n").takeIf(String::isNotBlank)
  }

  private fun attachmentFallbackText(
    attachments: List<com.opencray.llm.LiteLlmGatewayAttachment>,
  ): String = buildString {
    appendLine("Attachments:")
    attachments.forEach { attachment ->
      append("- ")
      append(
        attachment.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachment.attachmentId
          ?: "attachment",
      )
      append(" [kind=")
      append(attachment.kind.name.lowercase())
      attachment.attachmentId?.trim()?.takeIf(String::isNotBlank)?.let { attachmentId ->
        append(", attachment_id=")
        append(attachmentId)
      }
      attachment.mimeType?.trim()?.takeIf(String::isNotBlank)?.let { mimeType ->
        append(", mime_type=")
        append(mimeType)
      }
      append(']')
      appendLine()
    }
  }.trim()

  private fun openAiUserContentPayload(
    assembly: MultimodalMessageAssembly,
  ): Any = if (assembly.inlinePdfs.isEmpty() && assembly.inlineImages.isEmpty()) {
    assembly.text.orEmpty()
  } else {
    JSONArray().apply {
      assembly.text?.let { text ->
        put(
          JSONObject()
            .put("type", "text")
            .put("text", text),
        )
      }
      assembly.inlinePdfs.forEach { pdf ->
        put(buildOpenAiPdfBlock(pdf))
      }
      assembly.inlineImages.forEach { image ->
        put(buildOpenAiImageBlock(image))
      }
    }
  }

  private fun buildOpenAiPdfBlock(
    pdf: EncodedPdfAttachment,
  ): JSONObject = JSONObject()
    .put("type", "file")
    .put(
      "file",
      JSONObject()
        .put("filename", pdf.displayName)
        .put("file_data", inlinePdfDataUrl(pdf)),
    )

  private fun buildOpenAiImageBlock(
    image: EncodedImageAttachment,
  ): JSONObject = JSONObject()
    .put("type", "image_url")
    .put(
      "image_url",
      JSONObject().put("url", inlineImageDataUrl(image)),
    )

  private fun buildResponsesUserMultimodalMessage(
    assembly: MultimodalMessageAssembly,
  ): JSONObject = JSONObject()
    .put("type", "message")
    .put("role", "user")
    .put(
      "content",
      JSONArray().apply {
      assembly.text?.let { text ->
        put(
          JSONObject()
            .put("type", "input_text")
            .put("text", text),
        )
      }
      assembly.inlinePdfs.forEach { pdf ->
        put(
          JSONObject()
            .put("type", "input_file")
            .put("filename", pdf.displayName)
            .put("file_data", inlinePdfDataUrl(pdf)),
        )
      }
      assembly.inlineImages.forEach { image ->
        put(
          JSONObject()
            .put("type", "input_image")
            .put("image_url", inlineImageDataUrl(image)),
          )
        }
      },
    )

  private fun inlineImageDataUrl(image: EncodedImageAttachment): String =
    "data:${image.mimeType};base64,${image.base64Data}"

  private fun inlinePdfDataUrl(pdf: EncodedPdfAttachment): String =
    "data:${pdf.mimeType};base64,${pdf.base64Data}"

  private fun encodeInlinePdfAttachment(
    attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  ): EncodedPdfAttachment? {
    if (attachment.kind != com.opencray.llm.LiteLlmGatewayAttachmentKind.FILE) {
      return null
    }
    val filePath = attachment.filePath?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      return null
    }
    val sizeBytes = runCatching { Files.size(path) }.getOrNull() ?: return null
    if (sizeBytes <= 0L || sizeBytes > MAX_INLINE_PDF_BYTES) {
      return null
    }
    val mimeType = normalizedPdfMimeType(
      preferredMimeType = attachment.mimeType,
      path = path,
    ) ?: return null
    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return EncodedPdfAttachment(
      attachment = attachment,
      displayName = attachment.displayName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: path.fileName.toString(),
      mimeType = mimeType,
      base64Data = Base64.getEncoder().encodeToString(bytes),
    )
  }

  private fun encodeInlineImageAttachment(
    attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  ): EncodedImageAttachment? {
    if (attachment.kind != com.opencray.llm.LiteLlmGatewayAttachmentKind.IMAGE) {
      return null
    }
    val filePath = attachment.filePath?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      return null
    }
    val sizeBytes = runCatching { Files.size(path) }.getOrNull() ?: return null
    if (sizeBytes <= 0L || sizeBytes > MAX_INLINE_IMAGE_BYTES) {
      return null
    }
    val mimeType = normalizedImageMimeType(
      preferredMimeType = attachment.mimeType,
      path = path,
    ) ?: return null
    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return EncodedImageAttachment(
      attachment = attachment,
      mimeType = mimeType,
      base64Data = Base64.getEncoder().encodeToString(bytes),
    )
  }

  private fun normalizedImageMimeType(
    preferredMimeType: String?,
    path: Path,
  ): String? {
    val normalizedPreferred = preferredMimeType
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (normalizedPreferred?.startsWith("image/") == true) {
      return normalizedPreferred
    }
    val probedMimeType = runCatching { Files.probeContentType(path) }
      .getOrNull()
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (probedMimeType?.startsWith("image/") == true) {
      return probedMimeType
    }
    return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "jpg",
      "jpeg",
      -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "bmp" -> "image/bmp"
      "heic" -> "image/heic"
      "heif" -> "image/heif"
      else -> null
    }
  }

  private fun normalizedPdfMimeType(
    preferredMimeType: String?,
    path: Path,
  ): String? {
    val normalizedPreferred = preferredMimeType
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (normalizedPreferred == "application/pdf") {
      return normalizedPreferred
    }
    val probedMimeType = runCatching { Files.probeContentType(path) }
      .getOrNull()
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (probedMimeType == "application/pdf") {
      return probedMimeType
    }
    return path.fileName.toString()
      .substringAfterLast('.', "")
      .lowercase()
      .takeIf { extension -> extension == "pdf" }
      ?.let { "application/pdf" }
  }

  private fun buildOpenAiToolCallsArray(message: LiteLlmGatewayMessage): JSONArray = JSONArray().apply {
    message.toolCalls.forEach { toolCall ->
      put(
        JSONObject()
          .put(
            "id",
            requireToolCallId(
              toolCall = toolCall,
              location = "chat completions assistant tool call",
            ),
          )
          .put("type", "function")
          .put(
            "function",
            JSONObject()
              .put("name", toolCall.toolName)
              .put("arguments", toolCall.arguments.toString()),
          ),
      )
    }
  }

  private fun buildOpenAiToolResultMessage(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val result = toolResult ?: return null
    val toolCallId = requireToolResultCallId(
      toolResult = result,
      location = "chat completions tool result",
    )
    return JSONObject()
      .put("role", "tool")
      .put("content", serializedToolResultContent(result))
      .apply {
        put("tool_call_id", toolCallId)
        result.toolName?.takeIf(String::isNotBlank)?.let { toolName ->
          put("name", toolName)
        }
      }
  }

  private fun buildResponsesToolResultItem(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val result = toolResult ?: return null
    val callId = requireToolResultCallId(
      toolResult = result,
      location = "responses tool result",
    )
    return JSONObject()
      .put("type", "function_call_output")
      .put("call_id", callId)
      .put("output", serializedToolResultContent(result))
  }

  private fun buildAnthropicToolsArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    request.request.builtinTools.forEach { tool ->
      buildAnthropicBuiltinTool(tool)?.let(::put)
    }
    request.request.tools.forEach { tool ->
      put(
        JSONObject()
          .put("name", tool.name)
          .put("description", tool.description)
          .put("input_schema", JSONObject(tool.inputSchema.toString()))
          .apply {
            tool.strict?.let { strict -> put("strict", strict) }
          },
      )
    }
  }

  private fun buildAnthropicBuiltinTool(
    tool: LiteLlmBuiltinToolDefinition,
  ): JSONObject? = when (tool.type) {
    LiteLlmBuiltinToolType.WEB_SEARCH -> JSONObject()
      .put("type", ANTHROPIC_WEB_SEARCH_TOOL_TYPE)
      .put("name", ANTHROPIC_WEB_SEARCH_TOOL_NAME)
      .put("max_uses", DEFAULT_ANTHROPIC_WEB_SEARCH_MAX_USES)
      .apply {
        if (tool.domains.isNotEmpty()) {
          put(
            "allowed_domains",
            JSONArray().apply {
              tool.domains.distinct().forEach(::put)
            },
          )
        }
      }
  }

  private fun applyAnthropicToolControl(
    payload: JSONObject,
    request: LiteLlmGatewayRequest,
  ) {
    if (request.tools.isEmpty() && request.builtinTools.isEmpty()) {
      return
    }
    val toolChoice = request.toolChoice
    if (toolChoice != null || request.parallelToolCalls == false) {
      payload.put(
        "tool_choice",
        buildAnthropicToolChoice(
          toolChoice = toolChoice ?: LiteLlmToolChoice(mode = LiteLlmToolChoiceMode.AUTO),
          parallelToolCalls = request.parallelToolCalls,
        ),
      )
    }
  }

  private fun buildAnthropicToolChoice(
    toolChoice: LiteLlmToolChoice,
    parallelToolCalls: Boolean?,
  ): JSONObject = JSONObject().apply {
    when (toolChoice.mode) {
      LiteLlmToolChoiceMode.AUTO -> put("type", "auto")
      LiteLlmToolChoiceMode.NONE -> put("type", "none")
      LiteLlmToolChoiceMode.REQUIRED -> put("type", "any")
      LiteLlmToolChoiceMode.TOOL -> {
        put("type", "tool")
        put("name", toolChoice.toolName)
      }
    }
    if (parallelToolCalls == false) {
      put("disable_parallel_tool_use", true)
    }
  }

  private fun buildAnthropicMessagesArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    if (request.request.messages.isEmpty()) {
      put(
        JSONObject()
          .put("role", "user")
          .put("content", request.request.prompt),
      )
      return@apply
    }
    val messages = request.request.messages
    var index = 0
    while (index < messages.size) {
      val message = messages[index]
      when (message.role) {
        LiteLlmGatewayMessageRole.SYSTEM,
        LiteLlmGatewayMessageRole.USER,
        -> {
          buildAnthropicUserMessage(
            request = request,
            message = message,
          )?.let(::put)
          index += 1
        }

        LiteLlmGatewayMessageRole.ASSISTANT -> {
          put(buildAnthropicAssistantMessage(message))
          index += 1
        }

        LiteLlmGatewayMessageRole.TOOL -> {
          val assembly = buildAnthropicToolBoundaryUserTurn(
            request = request,
            messages = messages,
            startIndex = index,
          )
          assembly.message?.let(::put)
          index = assembly.nextIndexExclusive
        }
      }
    }
    anthropicServerToolContinuationMessage(request.request.metadata)?.let(::put)
  }

  private fun anthropicServerToolContinuationMessage(
    metadata: Map<String, String>,
  ): JSONObject? {
    val rawContent = metadata[ANTHROPIC_SERVER_TOOL_CONTINUATION_CONTENT_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val parsedContent = runCatching { JSONArray(rawContent) }.getOrNull() ?: return null
    return JSONObject()
      .put("role", "assistant")
      .put("content", parsedContent)
  }

  private fun buildAnthropicUserMessage(
    request: LiteLlmProviderRequest,
    message: LiteLlmGatewayMessage,
  ): JSONObject? {
    val assembly = multimodalAssemblyFor(
      request = request,
      message = message,
      allowInlineImages = message.role == LiteLlmGatewayMessageRole.USER,
    )
    if (assembly.text == null && assembly.inlinePdfs.isEmpty() && assembly.inlineImages.isEmpty()) {
      return null
    }
    if (assembly.inlinePdfs.isEmpty() && assembly.inlineImages.isEmpty()) {
      return buildAnthropicUserTextMessage(assembly.text.orEmpty())
    }
    val blocks = JSONArray()
    assembly.text?.let { text ->
      blocks.put(buildAnthropicTextBlock(text))
    }
    assembly.inlinePdfs.forEach { pdf ->
      blocks.put(buildAnthropicPdfBlock(pdf))
    }
    assembly.inlineImages.forEach { image ->
      blocks.put(buildAnthropicImageBlock(image))
    }
    return JSONObject()
      .put("role", "user")
      .put("content", blocks)
  }

  private fun buildAnthropicUserTextMessage(content: String): JSONObject =
    JSONObject()
      .put("role", "user")
      .put("content", content)

  private fun buildAnthropicToolBoundaryUserTurn(
    request: LiteLlmProviderRequest,
    messages: List<LiteLlmGatewayMessage>,
    startIndex: Int,
  ): AnthropicUserTurnAssembly {
    val blocks = JSONArray()
    var index = startIndex
    while (index < messages.size && messages[index].role == LiteLlmGatewayMessageRole.TOOL) {
      buildAnthropicToolResultBlock(messages[index].toolResult)?.let(blocks::put)
      index += 1
    }
    while (index < messages.size) {
      val message = messages[index]
      if (message.role != LiteLlmGatewayMessageRole.SYSTEM && message.role != LiteLlmGatewayMessageRole.USER) {
        break
      }
      appendAnthropicMessageBlocks(
        request = request,
        message = message,
        target = blocks,
      )
      index += 1
    }
    if (blocks.length() == 0) {
      return AnthropicUserTurnAssembly(
        message = null,
        nextIndexExclusive = index,
      )
    }
    return AnthropicUserTurnAssembly(
      message =
        JSONObject()
          .put("role", "user")
          .put("content", blocks),
      nextIndexExclusive = index,
    )
  }

  private fun appendAnthropicMessageBlocks(
    request: LiteLlmProviderRequest,
    message: LiteLlmGatewayMessage,
    target: JSONArray,
  ) {
    val assembly = multimodalAssemblyFor(
      request = request,
      message = message,
      allowInlineImages = message.role == LiteLlmGatewayMessageRole.USER,
    )
    assembly.text?.let { text ->
      target.put(buildAnthropicTextBlock(text))
    }
    assembly.inlinePdfs.forEach { pdf ->
      target.put(buildAnthropicPdfBlock(pdf))
    }
    assembly.inlineImages.forEach { image ->
      target.put(buildAnthropicImageBlock(image))
    }
  }

  private fun buildAnthropicPdfBlock(
    pdf: EncodedPdfAttachment,
  ): JSONObject = JSONObject()
    .put("type", "document")
    .put(
      "source",
      JSONObject()
        .put("type", "base64")
        .put("media_type", pdf.mimeType)
        .put("data", pdf.base64Data),
    )

  private fun buildAnthropicImageBlock(
    image: EncodedImageAttachment,
  ): JSONObject = JSONObject()
    .put("type", "image")
    .put(
      "source",
      JSONObject()
        .put("type", "base64")
        .put("media_type", image.mimeType)
        .put("data", image.base64Data),
    )

  private fun buildAnthropicAssistantMessage(message: LiteLlmGatewayMessage): JSONObject {
    if (message.toolCalls.isEmpty()) {
      return JSONObject()
        .put("role", "assistant")
        .put("content", message.content.orEmpty())
    }
    val blocks = JSONArray()
    message.content?.takeIf(String::isNotBlank)?.let { content ->
      blocks.put(
        JSONObject()
          .put("type", "text")
          .put("text", content),
      )
    }
    message.toolCalls.forEach { toolCall ->
      blocks.put(
        JSONObject()
          .put("type", "tool_use")
          .put(
            "id",
            requireToolCallId(
              toolCall = toolCall,
              location = "anthropic assistant tool call",
            ),
          )
          .put("name", toolCall.toolName)
          .put("input", JSONObject(toolCall.arguments.toString())),
      )
    }
    return JSONObject()
      .put("role", "assistant")
      .put("content", blocks)
  }

  private fun buildAnthropicToolResultMessage(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val block = buildAnthropicToolResultBlock(toolResult) ?: return null
    return JSONObject()
      .put("role", "user")
      .put(
        "content",
        JSONArray().put(block),
      )
  }

  private fun buildAnthropicToolResultBlock(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val result = toolResult ?: return null
    val toolUseId = requireToolResultCallId(
      toolResult = result,
      location = "anthropic tool result",
    )
    return JSONObject()
      .put("type", "tool_result")
      .put("tool_use_id", toolUseId)
      .put("content", serializedToolResultContent(result))
      .apply {
        if (result.isError == true) {
          put("is_error", true)
        }
      }
  }

  private fun buildAnthropicTextBlock(content: String): JSONObject =
    JSONObject()
      .put("type", "text")
      .put("text", content)

  private fun serializedToolResultContent(result: LiteLlmGatewayToolResult): String {
    if (!hasRichToolResultPayload(result)) {
      return result.content
    }
    return JSONObject()
      .put("content", result.content)
      .apply {
        result.structuredContent?.let { structuredContent ->
          put("structured_content", JSONObject(structuredContent.toString()))
        }
        result.exitCode?.let { exitCode -> put("exit_code", exitCode) }
        result.stdout?.takeIf(String::isNotBlank)?.let { stdout -> put("stdout", stdout) }
        result.stderr?.takeIf(String::isNotBlank)?.let { stderr -> put("stderr", stderr) }
        result.errorCode?.takeIf(String::isNotBlank)?.let { errorCode -> put("error_code", errorCode) }
        result.errorMessage?.takeIf(String::isNotBlank)?.let { errorMessage -> put("error_message", errorMessage) }
        if (result.metadata.isNotEmpty()) {
          put("metadata", JSONObject(result.metadata))
        }
      }
      .toString()
  }

  private fun hasRichToolResultPayload(result: LiteLlmGatewayToolResult): Boolean =
    result.structuredContent != null ||
      result.exitCode != null ||
      !result.stdout.isNullOrBlank() ||
      !result.stderr.isNullOrBlank() ||
      !result.errorCode.isNullOrBlank() ||
      !result.errorMessage.isNullOrBlank() ||
      result.metadata.isNotEmpty()

  private fun responseShape(
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

  private fun nativeToolCallObserved(
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
            if (block.optString("type") == "tool_use") {
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

  private fun builtinWebSearchObserved(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
    protocol: String,
  ): Boolean = builtinWebSearchObservations(
    request = request,
    payload = payload,
    protocol = protocol,
  ).isNotEmpty()

  private fun builtinWebSearchObservations(
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

  private fun anthropicBuiltinWebSearchObservations(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): List<LiteLlmBuiltinWebSearchObservation> {
    if (request.request.builtinTools.none { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }) {
      return emptyList()
    }
    val content = payload.optJSONArray("content") ?: return emptyList()
    val requestedDomains = request.request.builtinTools
      .filter { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }
      .flatMap(LiteLlmBuiltinToolDefinition::domains)
      .distinct()
    val queriesByToolUseId = linkedMapOf<String, List<String>>()
    val observations = mutableListOf<LiteLlmBuiltinWebSearchObservation>()
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      when (block.optString("type")) {
        "server_tool_use" -> {
          if (block.nonBlankString("name") != ANTHROPIC_WEB_SEARCH_TOOL_NAME) {
            continue
          }
          val toolUseId = block.nonBlankString("id") ?: continue
          val input = block.optJSONObject("input")
          val queries = linkedSetOf<String>().apply {
            input?.nonBlankString("query")?.let(::add)
            addAll(nonBlankJsonArrayStrings(input?.optJSONArray("queries")))
          }.toList()
          queriesByToolUseId[toolUseId] = queries
        }

        "web_search_tool_result" -> {
          val queries = block.nonBlankString("tool_use_id")
            ?.let(queriesByToolUseId::get)
            .orEmpty()
          val resultContent = block.opt("content")
          val sources = anthropicWebSearchSources(resultContent)
          observations += LiteLlmBuiltinWebSearchObservation(
            actionType = "search",
            status = anthropicWebSearchStatus(resultContent),
            queries = queries,
            domains = requestedDomains,
            url = sources.firstOrNull()?.url,
            sources = sources,
          )
        }
      }
    }
    if (observations.isNotEmpty()) {
      return observations
    }
    val searchRequestCount = payload.optJSONObject("usage")
      ?.optJSONObject("server_tool_use")
      ?.optInt("web_search_requests")
      ?: 0
    if (searchRequestCount <= 0 && queriesByToolUseId.isEmpty()) {
      return emptyList()
    }
    return queriesByToolUseId.values
      .ifEmpty {
        listOf(
          listOf(request.request.prompt.trim()).filter(String::isNotBlank),
        )
      }
      .map { queries ->
        LiteLlmBuiltinWebSearchObservation(
          actionType = "search",
          status = "completed",
          queries = queries,
          domains = requestedDomains,
        )
      }
  }

  private fun anthropicWebSearchSources(
    resultContent: Any?,
  ): List<LiteLlmBuiltinWebSearchSource> {
    val contentArray = resultContent as? JSONArray ?: return emptyList()
    return buildList {
      for (index in 0 until contentArray.length()) {
        val item = contentArray.optJSONObject(index) ?: continue
        if (item.optString("type") != "web_search_result") {
          continue
        }
        item.nonBlankString("url")?.let { url ->
          add(
            LiteLlmBuiltinWebSearchSource(
              title = item.nonBlankString("title"),
              url = url,
            ),
          )
        }
      }
    }
  }

  private fun anthropicWebSearchStatus(
    resultContent: Any?,
  ): String = when (resultContent) {
    is JSONObject -> if (resultContent.optString("type") == "web_search_tool_result_error") {
      "error"
    } else {
      "completed"
    }

    else -> "completed"
  }

  private fun openAiBuiltinWebSearchObservations(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): List<LiteLlmBuiltinWebSearchObservation> {
    if (request.request.builtinTools.none { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }) {
      return emptyList()
    }
    return when (openAiBuiltinWebSearchDialect(request)) {
      OpenAiBuiltinWebSearchDialect.OPENAI_CHAT_WEB_SEARCH -> listOf(
        LiteLlmBuiltinWebSearchObservation(
          actionType = "search",
          status = "completed",
          queries = listOf(request.request.prompt.trim()).filter(String::isNotBlank),
          domains = request.request.builtinTools
            .flatMap(LiteLlmBuiltinToolDefinition::domains)
            .distinct(),
        ),
      )

      OpenAiBuiltinWebSearchDialect.KIMI_BUILTIN_FUNCTION_WEB_SEARCH -> {
        val message = payload.optJSONArray("choices")
          ?.optJSONObject(0)
          ?.optJSONObject("message")
          ?: return emptyList()
        val toolCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        buildList {
          for (index in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(index) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            if (function.nonBlankString("name") != KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME) {
              continue
            }
            val arguments = parseToolCallArguments(
              rawArguments = function.opt("arguments"),
              location = "tool_calls[$index].function.arguments",
            )
            if (arguments.error != null) {
              continue
            }
            openAiBuiltinWebSearchObservationFromArguments(arguments.arguments)?.let(::add)
          }
        }
      }

      null -> emptyList()
    }
  }

  private fun openAiBuiltinWebSearchObservationFromArguments(
    arguments: JSONObject,
  ): LiteLlmBuiltinWebSearchObservation? {
    val queries = linkedSetOf<String>().apply {
      arguments.nonBlankString("query")?.let(::add)
      arguments.nonBlankString("q")?.let(::add)
      arguments.nonBlankString("text")?.let(::add)
      addAll(nonBlankJsonArrayStrings(arguments.optJSONArray("queries")))
    }.toList()
    val domains = linkedSetOf<String>().apply {
      addAll(nonBlankJsonArrayStrings(arguments.optJSONArray("domains")))
      arguments.nonBlankString("domain")?.let(::add)
    }.toList()
    val url = firstNonBlankString(
      arguments.nonBlankString("url"),
      arguments.nonBlankString("page_url"),
    )
    val findText = firstNonBlankString(
      arguments.nonBlankString("text"),
      arguments.nonBlankString("pattern"),
      arguments.nonBlankString("query"),
    )
    if (queries.isEmpty() && domains.isEmpty() && url == null && findText == null) {
      return null
    }
    return LiteLlmBuiltinWebSearchObservation(
      actionType = "search",
      status = "completed",
      queries = queries,
      domains = domains,
      url = url,
      findText = findText,
    )
  }

  private fun responsesBuiltinWebSearchObservations(
    payload: JSONObject,
  ): List<LiteLlmBuiltinWebSearchObservation> {
    val output = payload.optJSONArray("output") ?: return emptyList()
    val observations = mutableListOf<LiteLlmBuiltinWebSearchObservation>()
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") != "web_search_call") {
        continue
      }
      responsesBuiltinWebSearchObservation(item)?.let(observations::add)
    }
    return observations
  }

  private fun responsesBuiltinWebSearchObservation(
    item: JSONObject,
  ): LiteLlmBuiltinWebSearchObservation? {
    val action = item.optJSONObject("action")
    val actionType = firstNonBlankString(
      action?.nonBlankString("type"),
      item.nonBlankString("action_type"),
      "search",
    ) ?: return null
    val queries = linkedSetOf<String>().apply {
      addAll(nonBlankJsonArrayStrings(action?.optJSONArray("queries")))
      action?.nonBlankString("query")?.let(::add)
      item.nonBlankString("query")?.let(::add)
    }.toList()
    val domains = linkedSetOf<String>().apply {
      addAll(nonBlankJsonArrayStrings(action?.optJSONArray("domains")))
      addAll(nonBlankJsonArrayStrings(item.optJSONArray("domains")))
    }.toList()
    val url = firstNonBlankString(
      action?.nonBlankString("url"),
      action?.nonBlankString("page_url"),
      item.nonBlankString("url"),
      item.nonBlankString("page_url"),
    )
    val findText = firstNonBlankString(
      action?.nonBlankString("text"),
      action?.nonBlankString("pattern"),
      action?.nonBlankString("query"),
      item.nonBlankString("text"),
      item.nonBlankString("pattern"),
    )
    val sources = builtinWebSearchSources(
      actionSources = action?.optJSONArray("sources"),
      itemSources = item.optJSONArray("sources"),
    )
    return LiteLlmBuiltinWebSearchObservation(
      actionType = actionType,
      status = item.nonBlankString("status"),
      queries = queries,
      domains = domains,
      url = url,
      findText = findText,
      sources = sources,
    )
  }

  private fun builtinWebSearchSources(
    actionSources: JSONArray?,
    itemSources: JSONArray?,
  ): List<LiteLlmBuiltinWebSearchSource> {
    val resolved = actionSources ?: itemSources ?: return emptyList()
    val byUrl = linkedMapOf<String, LiteLlmBuiltinWebSearchSource>()
    for (index in 0 until resolved.length()) {
      val source = resolved.optJSONObject(index) ?: continue
      val url = source.nonBlankString("url") ?: continue
      byUrl[url] = LiteLlmBuiltinWebSearchSource(
        title = source.nonBlankString("title"),
        url = url,
      )
    }
    return byUrl.values.toList()
  }

  private fun nonBlankJsonArrayStrings(array: JSONArray?): List<String> {
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

  private fun responseCitationCount(
    payload: JSONObject,
    protocol: String,
  ): Int = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicCitationCount(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesCitationCount(payload)
    else -> 0
  }

  private fun anthropicCitationCount(payload: JSONObject): Int {
    val content = payload.optJSONArray("content") ?: return 0
    var count = 0
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      when (block.optString("type")) {
        "text" -> {
          count += block.optJSONArray("citations")?.length() ?: 0
        }

        "web_search_tool_result" -> {
          val resultContent = block.optJSONArray("content") ?: continue
          for (resultIndex in 0 until resultContent.length()) {
            val item = resultContent.optJSONObject(resultIndex) ?: continue
            if (item.optString("type") == "web_search_result") {
              count += 1
            }
          }
        }
      }
    }
    return count
  }

  private fun openAiResponseShape(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): String {
    val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return "openai_empty"
    val message = choice.optJSONObject("message")
    val content = extractOpenAiContentValue(message?.opt("content"))
    val toolCalls = message?.optJSONArray("tool_calls")
    val hasToolCalls = toolCalls != null && toolCalls.length() > 0
    val hasBuiltinWebSearch = builtinWebSearchObserved(
      request = request,
      payload = payload,
      protocol = LlmProviderProtocols.OPENAI,
    )
    val reasoningPayload = extractProtocolPayloadFromAlternateFields(
      choice = choice,
      message = message ?: JSONObject(),
    )
    val reasoningText = message?.let { nonNullMessage ->
      extractOpenAiReasoningText(
        choice = choice,
        message = nonNullMessage,
      )
    }
    return when {
      hasToolCalls && content.isNotBlank() && hasBuiltinWebSearch -> "openai_text_tool_calls_and_builtin_web_search"
      content.isNotBlank() && hasBuiltinWebSearch -> "openai_text_and_builtin_web_search"
      hasBuiltinWebSearch -> "openai_builtin_web_search"
      hasToolCalls && content.isNotBlank() -> "openai_text_and_tool_calls"
      hasToolCalls -> "openai_tool_calls"
      content.isNotBlank() -> "openai_text"
      !reasoningPayload.isNullOrBlank() -> "openai_reasoning_protocol"
      !reasoningText.isNullOrBlank() -> "openai_reasoning_text"
      else -> "openai_empty"
    }
  }

  private fun responsesResponseShape(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): String {
    val output = payload.optJSONArray("output") ?: return "responses_empty"
    val textSegments = responsesMessageTextSegments(payload)
    val hasText = textSegments.ordered.isNotEmpty()
    val hasToolCalls = nativeToolCallObserved(
      payload = payload,
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
    )
    val hasBuiltinWebSearch = builtinWebSearchObserved(
      request = request,
      payload = payload,
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
    )
    val hasReasoning = extractResponsesReasoningText(payload) != null
    return when {
      hasText && hasToolCalls && hasBuiltinWebSearch -> "responses_text_tool_calls_and_builtin_web_search"
      hasText && hasBuiltinWebSearch -> "responses_text_and_builtin_web_search"
      hasBuiltinWebSearch && hasReasoning -> "responses_builtin_web_search_and_reasoning"
      hasBuiltinWebSearch -> "responses_builtin_web_search"
      hasText && hasToolCalls -> "responses_text_and_tool_calls"
      hasToolCalls && hasReasoning -> "responses_reasoning_and_tool_calls"
      hasToolCalls -> "responses_tool_calls"
      hasText && hasReasoning -> "responses_text_and_reasoning"
      hasText -> "responses_text"
      hasReasoning -> "responses_reasoning"
      output.length() > 0 -> "responses_output_only"
      else -> "responses_empty"
    }
  }

  private fun responsesCitationCount(payload: JSONObject): Int {
    val output = payload.optJSONArray("output") ?: return 0
    var count = 0
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      if (item.optString("type") == "web_search_call") {
        val sources = item.optJSONObject("action")
          ?.optJSONArray("sources")
          ?: item.optJSONArray("sources")
        if (sources != null) {
          count += sources.length()
        }
      }
      if (item.optString("type") != "message") {
        continue
      }
      val content = item.optJSONArray("content") ?: continue
      for (contentIndex in 0 until content.length()) {
        val segment = content.optJSONObject(contentIndex) ?: continue
        count += segment.optJSONArray("annotations")?.length() ?: 0
      }
    }
    return count
  }

  private fun anthropicResponseShape(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): String {
    val content = payload.optJSONArray("content") ?: return "anthropic_empty"
    var hasText = false
    var hasToolUse = false
    val hasBuiltinWebSearch = builtinWebSearchObserved(
      request = request,
      payload = payload,
      protocol = LlmProviderProtocols.ANTHROPIC,
    )
    val blockTypes = linkedSetOf<String>()
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      val type = block.optString("type").trim().ifEmpty { "unknown" }
      blockTypes += type
      when (type) {
        "text" -> if (block.optString("text").isNotBlank()) {
          hasText = true
        }

        "tool_use" -> hasToolUse = true
      }
    }
    return when {
      hasText && hasToolUse && hasBuiltinWebSearch -> "anthropic_text_tool_use_and_builtin_web_search"
      hasText && hasBuiltinWebSearch -> "anthropic_text_and_builtin_web_search"
      hasBuiltinWebSearch -> "anthropic_builtin_web_search"
      hasText && hasToolUse -> "anthropic_text_and_tool_use"
      hasToolUse -> "anthropic_tool_use"
      hasText -> "anthropic_text"
      blockTypes.isNotEmpty() -> "anthropic_${blockTypes.joinToString(separator = "_")}"
      else -> "anthropic_empty"
    }
  }

  private fun extractErrorMessage(responseText: String): String = runCatching {
    val errorObject = JSONObject(responseText).optJSONObject("error")
    errorObject?.optString("message")?.takeIf { it.isNotBlank() } ?: responseText
  }.getOrDefault(responseText)

  private fun parseRetryAfterMillis(rawValue: String?): Long? {
    val seconds = rawValue?.trim()?.toLongOrNull() ?: return null
    return seconds * 1_000L
  }

  private fun readSuccessResponse(
    input: InputStream?,
    protocol: String,
    streamResponses: Boolean,
    contentType: String?,
  ): String = when {
    input == null -> ""
    streamResponses &&
      protocol == LlmProviderProtocols.ANTHROPIC &&
      contentType.orEmpty().contains("text/event-stream", ignoreCase = true) ->
      readAnthropicStream(input)
    else -> readStream(input)
  }

  private fun readAnthropicStream(input: InputStream): String {
    val payload = JSONObject()
    val contentBlocks = linkedMapOf<Int, JSONObject>()
    val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
      var currentEvent = ""
      val dataLines = mutableListOf<String>()

      fun flushEvent() {
        if (dataLines.isEmpty()) {
          currentEvent = ""
          return
        }
        processAnthropicStreamEvent(
          eventName = currentEvent,
          data = dataLines.joinToString(separator = "\n"),
          payload = payload,
          contentBlocks = contentBlocks,
          toolInputBuffers = toolInputBuffers,
        )
        currentEvent = ""
        dataLines.clear()
      }

      var line = reader.readLine()
      while (line != null) {
        when {
          line.isBlank() -> flushEvent()
          line.startsWith(":") -> Unit
          line.startsWith("event:") -> currentEvent = line.substringAfter(':').trim()
          line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
        }
        line = reader.readLine()
      }
      flushEvent()
    }
    val content = JSONArray()
    contentBlocks.toSortedMap().values.forEach(content::put)
    payload.put("content", content)
    return payload.toString()
  }

  private fun processAnthropicStreamEvent(
    eventName: String,
    data: String,
    payload: JSONObject,
    contentBlocks: MutableMap<Int, JSONObject>,
    toolInputBuffers: MutableMap<Int, StringBuilder>,
  ) {
    val trimmedData = data.trim()
    if (trimmedData.isBlank() || trimmedData == "[DONE]") {
      return
    }
    val eventPayload = runCatching { JSONObject(trimmedData) }.getOrElse { error ->
      throw IllegalStateException("Failed to parse Anthropic streaming event.", error)
    }
    val eventType = eventPayload.optString("type").ifBlank { eventName }
    when (eventType) {
      "message_start" -> {
        val message = eventPayload.optJSONObject("message") ?: return
        message.nonBlankString("id")?.let { payload.put("id", it) }
        if (message.has("stop_reason")) {
          payload.put("stop_reason", message.opt("stop_reason"))
        }
        message.optJSONObject("usage")?.let { payload.put("usage", JSONObject(it.toString())) }
      }

      "content_block_start" -> {
        val index = eventPayload.optInt("index", -1)
        if (index < 0) return
        val block = eventPayload.optJSONObject("content_block") ?: return
        val normalizedBlock = JSONObject(block.toString())
        if (normalizedBlock.optString("type") == "tool_use" && !normalizedBlock.has("input")) {
          normalizedBlock.put("input", JSONObject())
        }
        contentBlocks[index] = normalizedBlock
      }

      "content_block_delta" -> {
        val index = eventPayload.optInt("index", -1)
        if (index < 0) return
        val delta = eventPayload.optJSONObject("delta") ?: return
        val block = contentBlocks.getOrPut(index) { JSONObject() }
        when (delta.optString("type")) {
          "text_delta" -> appendJsonStringField(block, "text", delta.optString("text"))
          "thinking_delta" -> {
            if (!block.has("type")) {
              block.put("type", "thinking")
            }
            appendJsonStringField(block, "thinking", delta.optString("thinking"))
          }

          "input_json_delta" -> {
            toolInputBuffers.getOrPut(index) { StringBuilder() }
              .append(delta.optString("partial_json"))
          }
        }
      }

      "content_block_stop" -> {
        val index = eventPayload.optInt("index", -1)
        if (index < 0) return
        val block = contentBlocks[index] ?: return
        val bufferedInput = toolInputBuffers.remove(index)?.toString()?.trim().orEmpty()
        if (bufferedInput.isNotBlank()) {
          val parsedInput = runCatching { JSONObject(bufferedInput) }.getOrDefault(JSONObject())
          block.put("input", parsedInput)
        }
      }

      "message_delta" -> {
        eventPayload.optJSONObject("delta")
          ?.nonBlankString("stop_reason")
          ?.let { payload.put("stop_reason", it) }
        eventPayload.optJSONObject("usage")?.let { payload.put("usage", JSONObject(it.toString())) }
      }

      "error" -> {
        val errorObject = eventPayload.optJSONObject("error")
        val message = errorObject?.nonBlankString("message")
          ?: eventPayload.nonBlankString("message")
          ?: "Anthropic streaming request failed."
        throw IllegalStateException(message)
      }
    }
  }

  private fun appendJsonStringField(
    target: JSONObject,
    key: String,
    value: String?,
  ) {
    val delta = value?.takeIf(String::isNotBlank) ?: return
    val existing = target.optString(key)
    target.put(key, existing + delta)
  }

  private fun readStream(input: InputStream?): String {
    if (input == null) return ""
    return BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
      buildString {
        var line = reader.readLine()
        while (line != null) {
          append(line)
          line = reader.readLine()
          if (line != null) {
            append('\n')
          }
        }
      }
    }
  }

  private fun shouldStreamResponses(
    request: LiteLlmProviderRequest,
    protocol: String = resolvedProtocol(request),
  ): Boolean {
    explicitStreamPreference(request.route.metadata)?.let { return it }
    return protocol == LlmProviderProtocols.ANTHROPIC &&
      isKimiModel(request.route.model)
  }

  private fun explicitStreamPreference(
    metadata: Map<String, String>,
  ): Boolean? = when (metadata["stream"]?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
  }

  private fun isKimiModel(model: String?): Boolean =
    model?.trim()?.lowercase()?.contains("kimi") == true

  private fun shouldDisableAnthropicThinking(
    request: LiteLlmProviderRequest,
  ): Boolean = resolvedProtocol(request) == LlmProviderProtocols.ANTHROPIC &&
    isKimiModel(request.route.model) &&
    !isKimiThinkingModel(request.route.model)

  private fun isKimiThinkingModel(model: String?): Boolean {
    val normalized = model?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) {
      return false
    }
    return normalized.contains("kimi") && normalized.contains("thinking")
  }

  private fun resolvedProtocol(request: LiteLlmProviderRequest): String =
    LlmProviderProtocols.normalize(request.route.metadata["protocol"])

  private fun JSONObject.nonBlankString(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)

  private fun invalidToolMessageContract(messages: List<LiteLlmGatewayMessage>): String? {
    val seenAssistantToolCallIds = linkedSetOf<String>()
    val seenToolResultCallIds = linkedSetOf<String>()
    messages.forEachIndexed { messageIndex, message ->
      if (message.role == LiteLlmGatewayMessageRole.ASSISTANT) {
        message.toolCalls.forEachIndexed { toolCallIndex, toolCall ->
          val toolCallId = toolCall.id?.trim()?.takeIf(String::isNotBlank)
          if (toolCallId == null) {
            return "messages[$messageIndex].toolCalls[$toolCallIndex].id must be present for provider-native tool calling."
          }
          if (!seenAssistantToolCallIds.add(toolCallId)) {
            return "messages[$messageIndex].toolCalls[$toolCallIndex].id duplicates provider-native tool call id '$toolCallId'."
          }
        }
      }
      if (message.role == LiteLlmGatewayMessageRole.TOOL) {
        val toolResult = message.toolResult ?: return "messages[$messageIndex].toolResult is missing."
        val toolCallId = toolResult.toolCallId?.trim()?.takeIf(String::isNotBlank)
        if (toolCallId == null) {
          return "messages[$messageIndex].toolResult.toolCallId must be present for provider-native tool calling."
        }
        if (!seenToolResultCallIds.add(toolCallId)) {
          return "messages[$messageIndex].toolResult.toolCallId duplicates provider-native tool result id '$toolCallId'."
        }
      }
    }
    return null
  }

  private fun requireToolCallId(
    toolCall: LiteLlmStructuredToolCall,
    location: String,
  ): String = toolCall.id?.trim()?.takeIf(String::isNotBlank)
    ?: error("$location id must be present for provider-native tool calling.")

  private fun requireToolResultCallId(
    toolResult: LiteLlmGatewayToolResult,
    location: String,
  ): String = toolResult.toolCallId?.trim()?.takeIf(String::isNotBlank)
    ?: error("$location toolCallId must be present for provider-native tool calling.")

  private fun firstNonBlankString(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() }

  private data class ResponseTextSegments(
    val commentary: List<String> = emptyList(),
    val finalAnswer: List<String> = emptyList(),
    val unphased: List<String> = emptyList(),
    val ordered: List<String> = emptyList(),
  )

  private data class ToolArgumentsParseResult(
    val arguments: JSONObject,
    val error: String? = null,
  )

  private data class StructuredToolCallParseResult(
    val toolCalls: List<LiteLlmStructuredToolCall> = emptyList(),
    val errors: List<String> = emptyList(),
    val rawPreview: String? = null,
  )

  companion object {
    private const val DEFAULT_ANTHROPIC_MAX_TOKENS: Int = 4096
    private const val MAX_INLINE_IMAGE_BYTES: Long = 20L * 1024L * 1024L
    private const val MAX_INLINE_PDF_BYTES: Long = 32L * 1024L * 1024L
    private const val ANTHROPIC_WEB_SEARCH_TOOL_TYPE: String = "web_search_20250305"
    private const val ANTHROPIC_WEB_SEARCH_TOOL_NAME: String = "web_search"
    private const val DEFAULT_ANTHROPIC_WEB_SEARCH_MAX_USES: Int = 5
    private const val ANTHROPIC_SERVER_TOOL_CONTINUATION_CONTENT_KEY: String =
      "_host.anthropicServerToolContinuationContent"
    private const val ANTHROPIC_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY: String =
      "_host.anthropicBuiltinWebSearchLoopDepth"
    private const val MAX_ANTHROPIC_BUILTIN_WEB_SEARCH_AUTO_TURNS: Int = 4
    private const val KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME: String = "\$web_search"
    private const val KIMI_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY: String = "_host.kimiBuiltinWebSearchLoopDepth"
    private const val MAX_KIMI_BUILTIN_WEB_SEARCH_AUTO_TURNS: Int = 4
    private val JSON_CODEC: Json = Json { ignoreUnknownKeys = true }

    internal fun providerUserAgent(versionName: String): String =
      OpenCrayUserAgent.providerApi(versionName)
  }
}
