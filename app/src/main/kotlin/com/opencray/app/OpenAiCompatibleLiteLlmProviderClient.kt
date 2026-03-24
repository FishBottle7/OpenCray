package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
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
              metadata = responseMetadata,
            )
          } else {
            LiteLlmProviderResult.Success(
              outputText = content,
              completion = completion,
              finishReason = finishReason,
              providerResponseId = providerResponseId,
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

  private fun extractMessageContent(
    payload: JSONObject,
    protocol: String,
  ): String = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> extractAnthropicMessageContent(payload)
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
      normalizedCalls.put(
        JSONObject()
          .put("tool_name", toolName)
          .put("arguments", parseToolCallArguments(function.opt("arguments"))),
      )
    }
    if (normalizedCalls.length() == 0) {
      return null
    }
    return JSONObject()
      .put("tool_calls", normalizedCalls)
      .toString()
  }

  private fun parseToolCallArguments(rawArguments: Any?): JSONObject = when (rawArguments) {
    is JSONObject -> rawArguments
    is String -> runCatching {
      JSONObject(rawArguments)
    }.getOrElse {
      JSONObject()
    }

    else -> JSONObject()
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
    else -> openAiStructuredCompletion(payload)
  }

  private fun openAiStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val message = choice.optJSONObject("message") ?: return null
    val toolCalls = openAiStructuredToolCalls(message.optJSONArray("tool_calls"))
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
      toolCalls.isNotEmpty() -> null
      else -> finalText
    }
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = finalText,
      progressText = progressText,
      reasoningText = reasoningText,
      rawText = rawText,
    )
  }

  private fun openAiStructuredToolCalls(toolCalls: JSONArray?): List<LiteLlmStructuredToolCall> {
    if (toolCalls == null || toolCalls.length() == 0) {
      return emptyList()
    }
    val normalizedCalls = mutableListOf<LiteLlmStructuredToolCall>()
    for (index in 0 until toolCalls.length()) {
      val toolCall = toolCalls.optJSONObject(index) ?: continue
      val function = toolCall.optJSONObject("function") ?: continue
      val toolName = function.nonBlankString("name") ?: continue
      normalizedCalls += LiteLlmStructuredToolCall(
        id = toolCall.nonBlankString("id"),
        toolName = toolName,
        arguments = jsonObjectFrom(parseToolCallArguments(function.opt("arguments"))),
      )
    }
    return normalizedCalls
  }

  private fun anthropicStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val content = payload.optJSONArray("content") ?: return null
    val textBlocks = mutableListOf<String>()
    val thinkingBlocks = mutableListOf<String>()
    val toolCalls = mutableListOf<LiteLlmStructuredToolCall>()
    for (index in 0 until content.length()) {
      val block = content.optJSONObject(index) ?: continue
      when (block.optString("type")) {
        "text" -> block.optString("text")
          .trim()
          .takeIf(String::isNotBlank)
          ?.let(textBlocks::add)

        "thinking" -> extractAnthropicThinkingText(block)
          ?.let(thinkingBlocks::add)

        "tool_use" -> {
          block.optString("name").trim().takeIf(String::isNotBlank)?.let { toolName ->
            toolCalls += LiteLlmStructuredToolCall(
              id = block.optString("id").trim().takeIf(String::isNotBlank),
              toolName = toolName,
              arguments = jsonObjectFrom(block.optJSONObject("input") ?: JSONObject()),
            )
          }
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
    )
  }

  private fun buildStructuredCompletion(
    toolCalls: List<LiteLlmStructuredToolCall>,
    finalText: String? = null,
    progressText: String? = null,
    reasoningText: String? = null,
    rawText: String? = null,
  ): LiteLlmStructuredCompletion? {
    val normalizedFinalText = finalText?.trim()?.takeIf(String::isNotBlank)
    val normalizedProgressText = progressText?.trim()?.takeIf(String::isNotBlank)
    val normalizedReasoningText = reasoningText?.trim()?.takeIf(String::isNotBlank)
    val normalizedRawText = rawText?.trim()?.takeIf(String::isNotBlank)
    if (
      toolCalls.isEmpty() &&
      normalizedFinalText == null &&
      normalizedProgressText == null &&
      normalizedReasoningText == null &&
      normalizedRawText == null
    ) {
      return null
    }
    return LiteLlmStructuredCompletion(
      toolCalls = toolCalls,
      finalText = normalizedFinalText,
      progressText = normalizedProgressText,
      reasoningText = normalizedReasoningText,
      rawText = normalizedRawText,
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
    request.request.previousResponseId
      ?.takeIf(String::isNotBlank)
      ?.let { put("previousResponseIdPresent", "true") }
    if (request.request.responseApiPreferred) {
      put("responseApiPreferred", "true")
    }
  }

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

  private fun buildOpenAiToolChoice(toolChoice: LiteLlmToolChoice): Any = when (toolChoice.mode) {
    LiteLlmToolChoiceMode.AUTO -> "auto"
    LiteLlmToolChoiceMode.NONE -> "none"
    LiteLlmToolChoiceMode.REQUIRED -> "required"
    LiteLlmToolChoiceMode.TOOL -> JSONObject()
      .put("type", "function")
      .put("function", JSONObject().put("name", toolChoice.toolName))
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
    request.request.messages.forEach { message ->
      when (message.role) {
        LiteLlmGatewayMessageRole.SYSTEM,
        LiteLlmGatewayMessageRole.USER,
        -> {
          message.content?.takeIf(String::isNotBlank)?.let { content ->
            put(
              JSONObject()
                .put("role", "user")
                .put("content", content),
            )
          }
        }

        LiteLlmGatewayMessageRole.ASSISTANT -> {
          put(buildAnthropicAssistantMessage(message))
        }

        LiteLlmGatewayMessageRole.TOOL -> {
          buildAnthropicToolResultMessage(message.toolResult)?.let(::put)
        }
      }
    }
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
    val result = toolResult ?: return null
    val toolUseId = result.toolCallId?.takeIf(String::isNotBlank) ?: return null
    return JSONObject()
      .put("role", "user")
      .put(
        "content",
        JSONArray().put(
          JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", toolUseId)
            .put("content", serializedToolResultContent(result))
            .apply {
              if (result.isError == true) {
                put("is_error", true)
              }
            },
        ),
      )
  }

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

  companion object {
    private const val DEFAULT_ANTHROPIC_MAX_TOKENS: Int = 4096
    private val JSON_CODEC: Json = Json { ignoreUnknownKeys = true }

    internal fun providerUserAgent(versionName: String): String =
      OpenCrayUserAgent.providerApi(versionName)
  }
}
