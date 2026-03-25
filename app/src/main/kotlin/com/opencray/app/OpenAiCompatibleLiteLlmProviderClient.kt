package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmBuiltinToolDefinition
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
import java.net.URL
import java.nio.charset.StandardCharsets
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
    val requestDiagnostics = requestDiagnosticsMetadata(request)
    val endpoint = buildEndpointUrl(baseUrl, protocol)
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = request.route.timeoutMs.toInt()
      readTimeout = request.route.timeoutMs.toInt()
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      request.request.authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
      setRequestProperty("User-Agent", userAgent)
    }

    return try {
      val body = buildRequestBody(request)
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }

      val responseCode = connection.responseCode
      val responseText = readStream(
        input = if (responseCode in 200..299) connection.inputStream else connection.errorStream,
      )

      when {
        responseCode == 429 -> LiteLlmProviderResult.RateLimited(
          retryAfterMs = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
          errorMessage = extractErrorMessage(responseText).ifBlank { "Provider returned HTTP 429." },
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
            payload = parsed,
            protocol = protocol,
            statusCode = responseCode,
            nativeToolCallRequested = request.request.tools.isNotEmpty(),
            completion = completion,
          )
          val content = extractMessageContent(parsed, protocol)
          if (content.isBlank() && (completion?.hasVisibleContent != true)) {
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
            LiteLlmProviderResult.Success(
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
      LlmProviderProtocols.ANTHROPIC -> buildAnthropicRequestBody(request)
      LlmProviderProtocols.OPENAI_RESPONSES -> buildOpenAiResponsesRequestBody(request)
      else -> buildOpenAiRequestBody(request)
    }
  }

  private fun buildOpenAiRequestBody(request: LiteLlmProviderRequest): String {
    val payload = JSONObject()
      .put("model", request.route.model)
      .put("messages", buildOpenAiMessagesArray(request))

    if (request.request.tools.isNotEmpty()) {
      payload.put("tools", buildOpenAiToolsArray(request))
    }

    applyOpenAiToolControl(payload, request.request)
    request.route.metadata["temperature"]?.toDoubleOrNull()?.let { payload.put("temperature", it) }
    request.route.metadata["max_tokens"]?.toIntOrNull()?.let { payload.put("max_tokens", it) }
    request.route.metadata["reasoning_effort"]?.takeIf { it.isNotBlank() }?.let { payload.put("reasoning_effort", it) }
    return payload.toString()
  }

  private fun buildAnthropicRequestBody(request: LiteLlmProviderRequest): String {
    val payload = JSONObject()
      .put("model", request.route.model)
      .put("messages", buildAnthropicMessagesArray(request))
      .put(
        "max_tokens",
        request.route.metadata["max_tokens"]?.toIntOrNull() ?: DEFAULT_ANTHROPIC_MAX_TOKENS,
      )

    if (request.request.tools.isNotEmpty()) {
      payload.put("tools", buildAnthropicToolsArray(request))
    }

    applyAnthropicToolControl(payload, request.request)
    request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
      payload.put("system", systemPrompt)
    }
    request.route.metadata["thinking_budget_tokens"]?.toIntOrNull()?.let { budgetTokens ->
      payload.put(
        "thinking",
        JSONObject()
          .put("type", "enabled")
          .put("budget_tokens", budgetTokens),
      )
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
    val progressText = textContent?.takeIf { toolCalls.isNotEmpty() }
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
      progressText = progressText,
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
      normalizedCalls += LiteLlmStructuredToolCall(
        id = toolCall.nonBlankString("id"),
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
    val commentaryText = textSegments.commentary.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val finalPhaseText = textSegments.finalAnswer.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val unphasedText = textSegments.unphased.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val progressText = buildList<String> {
      commentaryText?.let(::add)
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
      else -> finalText ?: progressText
    }
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = finalText,
      progressText = progressText,
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
      normalizedCalls += LiteLlmStructuredToolCall(
        id = item.nonBlankString("call_id") ?: item.nonBlankString("id"),
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
          toolCalls += LiteLlmStructuredToolCall(
            id = block.optString("id").trim().takeIf(String::isNotBlank),
            toolName = toolName,
            arguments = jsonObjectFrom(arguments),
          )
        }
      }
    }
    val textContent = textBlocks.joinToString(separator = "").trim().takeIf(String::isNotBlank)
    val progressText = textContent?.takeIf { toolCalls.isNotEmpty() }
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
      progressText = progressText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallErrors,
    )
  }

  private fun buildStructuredCompletion(
    toolCalls: List<LiteLlmStructuredToolCall>,
    finalText: String? = null,
    progressText: String? = null,
    reasoningText: String? = null,
    rawText: String? = null,
    toolCallErrors: List<String> = emptyList(),
  ): LiteLlmStructuredCompletion? {
    val normalizedFinalText = finalText?.trim()?.takeIf(String::isNotBlank)
    val normalizedProgressText = progressText?.trim()?.takeIf(String::isNotBlank)
    val normalizedReasoningText = reasoningText?.trim()?.takeIf(String::isNotBlank)
    val normalizedRawText = rawText?.trim()?.takeIf(String::isNotBlank)
    val normalizedToolCallErrors = toolCallErrors.map(String::trim).filter(String::isNotBlank)
    if (
      toolCalls.isEmpty() &&
      normalizedFinalText == null &&
      normalizedProgressText == null &&
      normalizedReasoningText == null &&
      normalizedRawText == null &&
      normalizedToolCallErrors.isEmpty()
    ) {
      return null
    }
    return LiteLlmStructuredCompletion(
      toolCalls = toolCalls,
      finalText = normalizedFinalText,
      progressText = normalizedProgressText,
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
    put(LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE, responseShape(payload, protocol))
    put(
      LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED,
      nativeToolCallObserved(payload, protocol).toString(),
    )
    val builtinWebSearchUsed = builtinWebSearchObserved(payload, protocol)
    put(LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED, builtinWebSearchUsed.toString())
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
    if (request.tools.isEmpty()) {
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
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(
              JSONObject()
                .put("role", "user")
                .put("content", content),
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
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(buildResponsesTextMessage(role = "user", content = content))
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
                .put("call_id", toolCall.id ?: "call_opencray")
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

  private fun buildOpenAiToolCallsArray(message: LiteLlmGatewayMessage): JSONArray = JSONArray().apply {
    message.toolCalls.forEach { toolCall ->
      put(
        JSONObject()
          .put("id", toolCall.id ?: "call_opencray")
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
    return JSONObject()
      .put("role", "tool")
      .put("content", serializedToolResultContent(result))
      .apply {
        result.toolCallId?.let { toolCallId ->
          put("tool_call_id", toolCallId)
        }
        result.toolName?.takeIf(String::isNotBlank)?.let { toolName ->
          put("name", toolName)
        }
      }
  }

  private fun buildResponsesToolResultItem(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val result = toolResult ?: return null
    val callId = result.toolCallId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return JSONObject()
      .put("type", "function_call_output")
      .put("call_id", callId)
      .put("output", serializedToolResultContent(result))
  }

  private fun buildAnthropicToolsArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
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

  private fun applyAnthropicToolControl(
    payload: JSONObject,
    request: LiteLlmGatewayRequest,
  ) {
    if (request.tools.isEmpty()) {
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
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(buildAnthropicUserTextMessage(content))
          }
          index += 1
        }

        LiteLlmGatewayMessageRole.ASSISTANT -> {
          put(buildAnthropicAssistantMessage(message))
          index += 1
        }

        LiteLlmGatewayMessageRole.TOOL -> {
          val assembly = buildAnthropicToolBoundaryUserTurn(messages, index)
          assembly.message?.let(::put)
          index = assembly.nextIndexExclusive
        }
      }
    }
  }

  private fun buildAnthropicUserTextMessage(content: String): JSONObject =
    JSONObject()
      .put("role", "user")
      .put("content", content)

  private fun buildAnthropicToolBoundaryUserTurn(
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
      message.content?.takeIf(String::isNotBlank)?.let { content ->
        blocks.put(buildAnthropicTextBlock(content))
      }
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
          .put("id", toolCall.id ?: "toolu_opencray")
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
    val toolUseId = result.toolCallId?.takeIf(String::isNotBlank) ?: return null
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
    payload: JSONObject,
    protocol: String,
  ): String = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicResponseShape(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesResponseShape(payload)
    else -> openAiResponseShape(payload)
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
    payload: JSONObject,
    protocol: String,
  ): Boolean = when (protocol) {
    LlmProviderProtocols.OPENAI_RESPONSES -> {
      val output = payload.optJSONArray("output")
      if (output == null) {
        false
      } else {
        var observed = false
        for (index in 0 until output.length()) {
          val item = output.optJSONObject(index) ?: continue
          if (item.optString("type") == "web_search_call") {
            observed = true
            break
          }
        }
        observed
      }
    }

    else -> false
  }

  private fun responseCitationCount(
    payload: JSONObject,
    protocol: String,
  ): Int = when (protocol) {
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesCitationCount(payload)
    else -> 0
  }

  private fun openAiResponseShape(payload: JSONObject): String {
    val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return "openai_empty"
    val message = choice.optJSONObject("message")
    val content = extractOpenAiContentValue(message?.opt("content"))
    val toolCalls = message?.optJSONArray("tool_calls")
    val hasToolCalls = toolCalls != null && toolCalls.length() > 0
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
      hasToolCalls && content.isNotBlank() -> "openai_text_and_tool_calls"
      hasToolCalls -> "openai_tool_calls"
      content.isNotBlank() -> "openai_text"
      !reasoningPayload.isNullOrBlank() -> "openai_reasoning_protocol"
      !reasoningText.isNullOrBlank() -> "openai_reasoning_text"
      else -> "openai_empty"
    }
  }

  private fun responsesResponseShape(payload: JSONObject): String {
    val output = payload.optJSONArray("output") ?: return "responses_empty"
    val textSegments = responsesMessageTextSegments(payload)
    val hasText = textSegments.ordered.isNotEmpty()
    val hasToolCalls = nativeToolCallObserved(
      payload = payload,
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
    )
    val hasBuiltinWebSearch = builtinWebSearchObserved(
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

  private fun anthropicResponseShape(payload: JSONObject): String {
    val content = payload.optJSONArray("content") ?: return "anthropic_empty"
    var hasText = false
    var hasToolUse = false
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

  private fun resolvedProtocol(request: LiteLlmProviderRequest): String =
    LlmProviderProtocols.normalize(request.route.metadata["protocol"])

  private fun JSONObject.nonBlankString(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)

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
    private val JSON_CODEC: Json = Json { ignoreUnknownKeys = true }

    internal fun providerUserAgent(versionName: String): String =
      OpenCrayUserAgent.providerApi(versionName)
  }
}
