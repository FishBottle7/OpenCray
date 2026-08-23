package com.opencray.app.facade.llm

import com.opencray.app.LlmProviderProtocols
import com.opencray.app.MultimodalMessageAssembly
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.STRUCTURED_FINAL_SCHEMA_NAME
import com.opencray.app.StructuredToolCallParseResult
import com.opencray.app.VisibleTextSnapshotCoalescer
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderCompactRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesTextSegments(
    items: Iterable<JSONObject>,
  ): ResponsesTextSegments {
    val commentary = mutableListOf<String>()
    val finalAnswer = mutableListOf<String>()
    val unphased = mutableListOf<String>()
    val ordered = mutableListOf<String>()
    items.forEach { item ->
      if (item.optString("type") != "message") {
        return@forEach
      }
      val text = extractResponsesMessageText(item)
        .trim()
        .takeIf(String::isNotBlank)
        ?: return@forEach
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

private data class ResponseTextSegments(
  val commentary: List<String> = emptyList(),
  val finalAnswer: List<String> = emptyList(),
  val unphased: List<String> = emptyList(),
  val ordered: List<String> = emptyList(),
)

internal data class ResponsesTextSegments(
  val commentary: List<String> = emptyList(),
  val finalAnswer: List<String> = emptyList(),
  val unphased: List<String> = emptyList(),
  val ordered: List<String> = emptyList(),
)

internal data class ResponsesCompactionSummary(
  val summaryText: String,
  val outputItemCount: Int,
  val compactionItemCount: Int,
  val encryptedContentCount: Int,
)

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesCompactionSummary(payload: JSONObject): ResponsesCompactionSummary {
    val output = payload.optJSONArray("output") ?: JSONArray()
    val outputItems = buildList {
      for (index in 0 until output.length()) {
        output.optJSONObject(index)?.let(::add)
      }
    }
    val textSegments = responsesTextSegments(outputItems)
    var compactionItemCount = 0
    var encryptedContentCount = 0
    outputItems.forEach { item ->
      if (item.optString("type") == "compaction") {
        compactionItemCount += 1
      }
      item.nonBlankString("encrypted_content")
        ?.let { encryptedContentCount += 1 }
    }
    return ResponsesCompactionSummary(
      summaryText = textSegments.ordered.joinToString(separator = "\n").trim(),
      outputItemCount = outputItems.size,
      compactionItemCount = compactionItemCount,
      encryptedContentCount = encryptedContentCount,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesCompactEndpointUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return when {
      trimmed.endsWith("/v1/responses/compact") -> trimmed
      trimmed.endsWith("/v1/responses") -> "$trimmed/compact"
      trimmed.endsWith("/v1") -> "$trimmed/responses/compact"
      else -> "$trimmed/v1/responses/compact"
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiResponsesCompactRequestBody(request: LiteLlmProviderCompactRequest): String {
    val payload = JSONObject(buildOpenAiResponsesRequestBody(request.toProviderRequestForPayload()))
    payload.remove("stream")
    payload.remove("previous_response_id")
    return payload.toString()
  }

private fun LiteLlmProviderCompactRequest.toProviderRequestForPayload(): LiteLlmProviderRequest =
    LiteLlmProviderRequest(
      route = route,
      request = request.gatewayRequest,
      selection = selection,
    )

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiResponsesRequestBody(
    request: LiteLlmProviderRequest,
    streamResponses: Boolean = false,
  ): String {
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
    openAiPromptCacheKey(request)?.let { promptCacheKey ->
      payload.put("prompt_cache_key", promptCacheKey)
    }
    openAiPromptCacheRetention(request)?.let { retention ->
      payload.put("prompt_cache_retention", retention)
    }
    if (openAiStructuredFinalSchemaSupported(request)) {
      payload.put("text", openAiResponsesStructuredFinalTextFormat())
    }
    if (streamResponses) {
      payload.put("stream", true)
    }
    return payload.toString()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiResponsesStructuredFinalTextFormat(): JSONObject = JSONObject()
    .put(
      "format",
      JSONObject()
        .put("type", "json_schema")
        .put("name", STRUCTURED_FINAL_SCHEMA_NAME)
        .put("strict", true)
        .put("schema", structuredFinalSchema()),
    )

internal fun OpenAiCompatibleLiteLlmProviderClient.extractResponsesMessageContent(payload: JSONObject): String {
    val textSegments = responsesMessageTextSegments(payload)
    val orderedText = textSegments.ordered.joinToString(separator = "\n").trim()
    if (orderedText.isNotBlank()) {
      return orderedText
    }
    return synthesizeResponsesToolCallPayload(payload.optJSONArray("output")).orEmpty()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesMessageTextSegments(payload: JSONObject): ResponsesTextSegments {
    val output = payload.optJSONArray("output") ?: return ResponsesTextSegments()
    return responsesTextSegments(
      buildList {
        for (index in 0 until output.length()) {
          output.optJSONObject(index)?.let(::add)
        }
      },
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.extractResponsesMessageText(message: JSONObject): String = when (val content = message.opt("content")) {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractResponsesContentText(content: Any?): String = when (content) {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.synthesizeResponsesToolCallPayload(output: JSONArray?): String? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractResponsesReasoningText(payload: JSONObject): String? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
    val output = payload.optJSONArray("output") ?: return null
    val toolCallParse = responsesStructuredToolCalls(output)
    val toolCalls = toolCallParse.toolCalls
    val textSegments = responsesMessageTextSegments(payload)
    val orderedText = textSegments.ordered.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val finalPhaseText = textSegments.finalAnswer.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val unphasedText = textSegments.unphased.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val commentaryTexts = buildList<String> {
      addAll(
        textSegments.commentary
          .map(String::trim)
          .filter(String::isNotBlank),
      )
      if (toolCalls.isNotEmpty()) {
        unphasedText?.let(::add)
      }
    }
    val commentaryText = commentaryTexts.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    val finalText = firstNonBlankString(
      finalPhaseText,
      unphasedText?.takeIf { toolCalls.isEmpty() },
    )?.takeUnless(::looksLikeProtocolPayload)
    val finalAttachmentPayload = firstNonBlankString(
      orderedText?.takeIf(::looksLikeProtocolPayload),
      finalPhaseText?.takeIf(::looksLikeProtocolPayload),
      unphasedText?.takeIf(::looksLikeProtocolPayload),
    )?.toProtocolFinalPayloadOrNull()
    val nativeFinalText = finalAttachmentPayload?.nonBlankString("answer") ?: finalText
    val finalAttachments = finalAttachmentPayload?.structuredFinalAttachments().orEmpty()
    val reasoningText = extractResponsesReasoningText(payload)
    val rawText = when {
      orderedText != null && looksLikeProtocolPayload(orderedText) -> orderedText
      toolCallParse.errors.isNotEmpty() -> toolCallParse.rawPreview
      toolCalls.isNotEmpty() -> null
      else -> finalText ?: commentaryText
    }
    return buildStructuredCompletion(
      toolCalls = toolCalls,
      finalText = nativeFinalText,
      finalAttachments = finalAttachments,
      commentaryText = commentaryText,
      commentaryTexts = commentaryTexts,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallParse.errors,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesStructuredToolCalls(output: JSONArray?): StructuredToolCallParseResult {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesContinuationSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CONTINUATION) ||
      request.route.metadata["responsesContinuationSupported"]
        ?.trim()
        ?.lowercase() == "true"

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesAssistantPhasesSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_ASSISTANT_PHASES) ||
      request.route.metadata["assistantPhaseSupported"]
        ?.trim()
        ?.lowercase() == "true"

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesCitationIncludeSupported(request: LiteLlmProviderRequest): Boolean =
    requestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CITATION_INCLUDE) ||
      request.route.metadata["citationIncludeSupported"]
        ?.trim()
        ?.lowercase() == "true"

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesRemoteCompactionSupported(request: LiteLlmProviderCompactRequest): Boolean =
    compactRequestMetadataBoolean(request, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_REMOTE_COMPACTION) ||
      request.route.metadata[LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_SUPPORTED]
        ?.trim()
        ?.lowercase() == "true" ||
      request.route.metadata["responsesRemoteCompactionSupported"]
        ?.trim()
        ?.lowercase() == "true"

private fun requestMetadataBoolean(
    request: LiteLlmProviderRequest,
    key: String,
  ): Boolean = request.request.metadata[key]
    ?.trim()
    ?.lowercase() == "true"

private fun compactRequestMetadataBoolean(
    request: LiteLlmProviderCompactRequest,
    key: String,
  ): Boolean = request.request.metadata[key]
    ?.trim()
    ?.lowercase() == "true" ||
    request.request.gatewayRequest.metadata[key]
      ?.trim()
      ?.lowercase() == "true"

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesToolsArray(request: LiteLlmProviderRequest): JSONArray? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesBuiltinTool(
    tool: LiteLlmBuiltinToolDefinition,
  ): JSONObject? = when (tool.type) {
    LiteLlmBuiltinToolType.WEB_SEARCH -> JSONObject()
      .put("type", "web_search")
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesIncludeArray(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.applyResponsesToolControl(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesToolChoice(toolChoice: LiteLlmToolChoice): Any = when (toolChoice.mode) {
    LiteLlmToolChoiceMode.AUTO -> "auto"
    LiteLlmToolChoiceMode.NONE -> "none"
    LiteLlmToolChoiceMode.REQUIRED -> "required"
    LiteLlmToolChoiceMode.TOOL -> JSONObject()
      .put("type", "function")
      .put("name", toolChoice.toolName)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesInputArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    val assistantPhasesSupported = responsesAssistantPhasesSupported(request)
    openAiConversationMessages(request.request).forEach { message ->
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesTextMessage(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesUserMultimodalMessage(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildResponsesToolResultItem(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesBuiltinWebSearchObservations(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesBuiltinWebSearchObservation(
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

private fun OpenAiCompatibleLiteLlmProviderClient.builtinWebSearchSources(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesResponseShape(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesCitationCount(payload: JSONObject): Int {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.readOpenAiResponsesStream(
    input: InputStream,
    streamObserver: LiteLlmVisibleTextObserver,
  ): String {
    val payload = JSONObject()
    val outputItems = linkedMapOf<Int, JSONObject>()
    val outputIndexByItemId = mutableMapOf<String, Int>()
    var activeAssistantMessageIndex: Int? = null
    var lastVisibleTextSnapshot: String? = null
    val visibleTextCoalescer = VisibleTextSnapshotCoalescer(
      observer = streamObserver,
      minIntervalMs = streamUpdateMinIntervalMs,
      normalizer = ::visibleAssistantDraftText,
    )
    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
      var currentEvent = ""
      val dataLines = mutableListOf<String>()

      fun flushEvent() {
        if (dataLines.isEmpty()) {
          currentEvent = ""
          return
        }
        activeAssistantMessageIndex = processOpenAiResponsesStreamEvent(
          eventName = currentEvent,
          data = dataLines.joinToString(separator = "\n"),
          payload = payload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          activeAssistantMessageIndex = activeAssistantMessageIndex,
          visibleTextCoalescer = visibleTextCoalescer,
        )
        responsesVisibleText(outputItems)
          .trim()
          .takeIf(String::isNotBlank)
          ?.let { visibleText -> lastVisibleTextSnapshot = visibleText }
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
    backfillResponsesVisibleTextOutput(
      outputItems = outputItems,
      visibleText = lastVisibleTextSnapshot,
    )
    visibleTextCoalescer.flush()
    val output = JSONArray()
    outputItems.toSortedMap().values.forEach(output::put)
    payload.put("output", output)
    return payload.toString()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.processOpenAiResponsesStreamEvent(
    eventName: String,
    data: String,
    payload: JSONObject,
    outputItems: MutableMap<Int, JSONObject>,
    outputIndexByItemId: MutableMap<String, Int>,
    activeAssistantMessageIndex: Int?,
    visibleTextCoalescer: VisibleTextSnapshotCoalescer,
  ): Int? {
    val trimmedData = data.trim()
    if (trimmedData.isBlank() || trimmedData == "[DONE]") {
      return activeAssistantMessageIndex
    }
    val eventPayload = runCatching { JSONObject(trimmedData) }.getOrElse { error ->
      throw IllegalStateException("Failed to parse OpenAI responses streaming event.", error)
    }
    val eventType = eventPayload.optString("type").ifBlank { eventName }
    var nextActiveAssistantMessageIndex = activeAssistantMessageIndex
    var visibleTextMayHaveChanged = false
    when (eventType) {
      "error" -> {
        val errorObject = eventPayload.optJSONObject("error")
        val message = errorObject?.nonBlankString("message")
          ?: eventPayload.nonBlankString("message")
          ?: "OpenAI responses streaming request failed."
        throw IllegalStateException(message)
      }

      "response.created" -> {
        eventPayload.optJSONObject("response")?.let { response ->
          mergeResponseObjectIntoPayload(payload, response)
        }
      }

      "response.output_item.added" -> {
        val item = eventPayload.optJSONObject("item") ?: return nextActiveAssistantMessageIndex
        val itemIndex = storeResponsesOutputItem(
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          item = item,
          explicitIndex = eventPayload.optInt("output_index", -1).takeIf { index -> index >= 0 },
          replace = false,
        )
        if (isResponsesAssistantMessage(outputItems[itemIndex])) {
          nextActiveAssistantMessageIndex = itemIndex
          visibleTextMayHaveChanged = true
        }
      }

      "response.output_text.delta" -> {
        val delta = eventPayload.optString("delta")
        val outputIndex = resolveResponsesDeltaOutputIndex(
          eventPayload = eventPayload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          activeAssistantMessageIndex = nextActiveAssistantMessageIndex,
        )
        val item = ensureResponsesAssistantMessage(outputItems, outputIndex)
        appendResponsesOutputTextDelta(
          item = item,
          delta = delta,
          contentIndex = eventPayload.optInt("content_index", 0).coerceAtLeast(0),
        )
        nextActiveAssistantMessageIndex = outputIndex
        visibleTextMayHaveChanged = true
      }

      "response.output_text.done" -> {
        val outputIndex = resolveResponsesDeltaOutputIndex(
          eventPayload = eventPayload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          activeAssistantMessageIndex = nextActiveAssistantMessageIndex,
        )
        val item = ensureResponsesAssistantMessage(outputItems, outputIndex)
        setResponsesOutputTextValue(
          item = item,
          text = eventPayload.optString("text"),
          contentIndex = eventPayload.optInt("content_index", 0).coerceAtLeast(0),
        )
        nextActiveAssistantMessageIndex = outputIndex
        visibleTextMayHaveChanged = true
      }

      "response.content_part.added",
      "response.content_part.done" -> {
        val outputIndex = resolveResponsesDeltaOutputIndex(
          eventPayload = eventPayload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          activeAssistantMessageIndex = nextActiveAssistantMessageIndex,
        )
        val item = ensureResponsesAssistantMessage(outputItems, outputIndex)
        mergeResponsesContentPart(
          item = item,
          part = eventPayload.optJSONObject("part"),
          contentIndex = eventPayload.optInt("content_index", 0).coerceAtLeast(0),
        )
        nextActiveAssistantMessageIndex = outputIndex
        visibleTextMayHaveChanged = true
      }

      "response.function_call_arguments.delta" -> {
        val outputIndex = resolveResponsesFunctionCallOutputIndex(
          eventPayload = eventPayload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
        )
        val item = ensureResponsesFunctionCallItem(
          outputItems = outputItems,
          outputIndex = outputIndex,
          eventPayload = eventPayload,
        )
        appendResponsesFunctionCallArgumentsDelta(
          item = item,
          delta = eventPayload.optString("delta"),
        )
        registerResponsesOutputItemIdentities(
          outputIndexByItemId = outputIndexByItemId,
          item = item,
          outputIndex = outputIndex,
        )
      }

      "response.function_call_arguments.done" -> {
        val outputIndex = resolveResponsesFunctionCallOutputIndex(
          eventPayload = eventPayload,
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
        )
        val item = ensureResponsesFunctionCallItem(
          outputItems = outputItems,
          outputIndex = outputIndex,
          eventPayload = eventPayload,
        )
        setResponsesFunctionCallArgumentsValue(
          item = item,
          arguments = eventPayload.optString("arguments"),
        )
        registerResponsesOutputItemIdentities(
          outputIndexByItemId = outputIndexByItemId,
          item = item,
          outputIndex = outputIndex,
        )
      }

      "response.output_item.done" -> {
        val item = eventPayload.optJSONObject("item") ?: return nextActiveAssistantMessageIndex
        val itemIndex = storeResponsesOutputItem(
          outputItems = outputItems,
          outputIndexByItemId = outputIndexByItemId,
          item = item,
          explicitIndex = eventPayload.optInt("output_index", -1).takeIf { index -> index >= 0 }
            ?: if (isResponsesAssistantMessage(item)) {
              nextActiveAssistantMessageIndex
            } else {
              null
            },
          replace = true,
        )
        if (isResponsesAssistantMessage(outputItems[itemIndex])) {
          nextActiveAssistantMessageIndex = itemIndex
          visibleTextMayHaveChanged = true
        }
      }

      "response.completed" -> {
        val response = eventPayload.optJSONObject("response")
        if (response != null) {
          mergeResponseObjectIntoPayload(payload, response)
          response.optJSONArray("output")?.let { output ->
            if (output.length() > 0) {
              replaceResponsesOutputItems(
                outputItems = outputItems,
                outputIndexByItemId = outputIndexByItemId,
                output = output,
              )
            }
          }
        }
        if (!payload.has("status")) {
          payload.put("status", "completed")
        }
        visibleTextMayHaveChanged = outputItems.isNotEmpty()
      }

      "response.incomplete" -> {
        eventPayload.optJSONObject("response")?.let { response ->
          mergeResponseObjectIntoPayload(payload, response)
          response.optJSONArray("output")?.let { output ->
            if (output.length() > 0) {
              replaceResponsesOutputItems(
                outputItems = outputItems,
                outputIndexByItemId = outputIndexByItemId,
                output = output,
              )
            }
          }
        }
        if (!payload.has("status")) {
          payload.put("status", "incomplete")
        }
        visibleTextMayHaveChanged = outputItems.isNotEmpty()
      }

      "response.failed" -> {
        val errorObject = eventPayload.optJSONObject("response")
          ?.optJSONObject("error")
        val message = errorObject?.nonBlankString("message")
          ?: "OpenAI responses streaming request failed."
        throw IllegalStateException(message)
      }
    }
    streamDebug(
      "provider.responsesEvent type=$eventType activeOutputItems=${outputItems.size} visibleChanged=$visibleTextMayHaveChanged",
    )
    if (visibleTextMayHaveChanged) {
      val visibleText = responsesVisibleText(outputItems)
      visibleTextCoalescer.update(visibleText)
    }
    return nextActiveAssistantMessageIndex
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeResponseObjectIntoPayload(
    payload: JSONObject,
    response: JSONObject,
  ) {
    val keys = response.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      if (key == "output") {
        continue
      }
      copyJsonFieldIfPresent(response, payload, key)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.storeResponsesOutputItem(
    outputItems: MutableMap<Int, JSONObject>,
    outputIndexByItemId: MutableMap<String, Int>,
    item: JSONObject,
    explicitIndex: Int?,
    replace: Boolean,
  ): Int {
    val normalizedItem = sanitizeResponsesOutputItem(item)
    val resolvedIndex = resolveResponsesStoredOutputIndex(
      outputItems = outputItems,
      outputIndexByItemId = outputIndexByItemId,
      item = normalizedItem,
      explicitIndex = explicitIndex,
    )
    val storedItem = if (!replace) {
      outputItems[resolvedIndex]?.apply {
        mergeResponsesStreamItemSkeleton(this, normalizedItem)
      } ?: normalizedItem.also { inserted ->
        outputItems[resolvedIndex] = inserted
      }
    } else {
      outputItems[resolvedIndex]?.let { existing ->
        responsesOutputItemIdentities(existing).forEach(outputIndexByItemId::remove)
      }
      normalizedItem.also { inserted ->
        outputItems[resolvedIndex] = inserted
      }
    }
    registerResponsesOutputItemIdentities(
      outputIndexByItemId = outputIndexByItemId,
      item = storedItem,
      outputIndex = resolvedIndex,
    )
    return resolvedIndex
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.resolveResponsesStoredOutputIndex(
    outputItems: Map<Int, JSONObject>,
    outputIndexByItemId: Map<String, Int>,
    item: JSONObject,
    explicitIndex: Int?,
  ): Int {
    responsesOutputItemIdentities(item)
      .firstNotNullOfOrNull(outputIndexByItemId::get)
      ?.let { index -> return index }
    explicitIndex?.let { index ->
      if (shouldSplitResponsesOutputIndex(existing = outputItems[index], incoming = item)) {
        return nextResponsesOutputIndex(outputItems)
      }
      return index
    }
    return nextResponsesOutputIndex(outputItems)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeResponsesStreamItemSkeleton(
    target: JSONObject,
    source: JSONObject,
  ) {
    val keys = source.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      val existingValue = target.opt(key)
      if (key == "content") {
        if (isMissingJsonValue(existingValue)) {
          copyJsonFieldIfPresent(source, target, key)
        }
        continue
      }
      if (isMissingJsonValue(existingValue)) {
        copyJsonFieldIfPresent(source, target, key)
      }
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.replaceResponsesOutputItems(
    outputItems: MutableMap<Int, JSONObject>,
    outputIndexByItemId: MutableMap<String, Int>,
    output: JSONArray,
  ) {
    val preservedWebSearchItems = outputItems.values
      .filter { item -> item.optString("type") == "web_search_call" }
      .map(::sanitizeResponsesWebSearchCallItem)
    outputItems.clear()
    outputIndexByItemId.clear()
    val webSearchKeys = linkedSetOf<String>()
    for (index in 0 until output.length()) {
      val item = output.optJSONObject(index) ?: continue
      val copiedItem = sanitizeResponsesOutputItem(item)
      outputItems[index] = copiedItem
      if (copiedItem.optString("type") == "web_search_call") {
        webSearchKeys += responsesWebSearchItemPreservationKey(copiedItem)
      }
      registerResponsesOutputItemIdentities(
        outputIndexByItemId = outputIndexByItemId,
        item = copiedItem,
        outputIndex = index,
      )
    }
    var nextIndex = output.length()
    for (item in preservedWebSearchItems) {
      if (!webSearchKeys.add(responsesWebSearchItemPreservationKey(item))) {
        continue
      }
      outputItems[nextIndex] = item
      registerResponsesOutputItemIdentities(
        outputIndexByItemId = outputIndexByItemId,
        item = item,
        outputIndex = nextIndex,
      )
      nextIndex += 1
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesWebSearchItemPreservationKey(
    item: JSONObject,
  ): String = responsesOutputItemIdentities(item)
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString(separator = "\u0001", prefix = "id:")
    ?: "body:${item.toString()}"

internal fun OpenAiCompatibleLiteLlmProviderClient.registerResponsesOutputItemIdentities(
    outputIndexByItemId: MutableMap<String, Int>,
    item: JSONObject,
    outputIndex: Int,
  ) {
    responsesOutputItemIdentities(item).forEach { itemId ->
      outputIndexByItemId[itemId] = outputIndex
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.resolveResponsesDeltaOutputIndex(
    eventPayload: JSONObject,
    outputItems: Map<Int, JSONObject>,
    outputIndexByItemId: Map<String, Int>,
    activeAssistantMessageIndex: Int?,
  ): Int {
    firstNonBlankString(
      eventPayload.nonBlankString("item_id"),
      eventPayload.nonBlankString("output_item_id"),
    )?.let { itemId ->
      outputIndexByItemId[itemId]?.let { return it }
    }
    eventPayload.optInt("output_index", -1).takeIf { index -> index >= 0 }?.let { index ->
      if (
        shouldSplitResponsesOutputIndex(
          existing = outputItems[index],
          incoming = responsesMessageCandidateFrom(eventPayload),
        )
      ) {
        return nextResponsesOutputIndex(outputItems)
      }
      return index
    }
    activeAssistantMessageIndex?.let { return it }
    outputItems.toSortedMap().entries.lastOrNull { (_, item) ->
      isResponsesAssistantMessage(item)
    }?.key?.let { return it }
    return nextResponsesOutputIndex(outputItems)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.ensureResponsesAssistantMessage(
    outputItems: MutableMap<Int, JSONObject>,
    outputIndex: Int,
  ): JSONObject = outputItems.getOrPut(outputIndex) {
    JSONObject()
      .put("type", "message")
      .put("role", "assistant")
      .put("content", JSONArray())
  }.apply {
    if (optString("type").isBlank()) {
      put("type", "message")
    }
    if (optString("role").isBlank()) {
      put("role", "assistant")
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.resolveResponsesFunctionCallOutputIndex(
    eventPayload: JSONObject,
    outputItems: Map<Int, JSONObject>,
    outputIndexByItemId: Map<String, Int>,
  ): Int {
    firstNonBlankString(
      eventPayload.nonBlankString("item_id"),
      eventPayload.nonBlankString("output_item_id"),
      eventPayload.nonBlankString("call_id"),
      eventPayload.nonBlankString("id"),
    )?.let { itemId ->
      outputIndexByItemId[itemId]?.let { return it }
    }
    eventPayload.optInt("output_index", -1).takeIf { index -> index >= 0 }?.let { index ->
      if (
        shouldSplitResponsesOutputIndex(
          existing = outputItems[index],
          incoming = responsesFunctionCallCandidateFrom(eventPayload),
        )
      ) {
        return nextResponsesOutputIndex(outputItems)
      }
      return index
    }
    return nextResponsesOutputIndex(outputItems)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesMessageCandidateFrom(eventPayload: JSONObject): JSONObject = JSONObject()
    .put("type", "message")
    .put("role", "assistant")
    .apply {
      firstNonBlankString(
        eventPayload.nonBlankString("item_id"),
        eventPayload.nonBlankString("output_item_id"),
      )?.let { itemId ->
        put("id", itemId)
      }
    }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesFunctionCallCandidateFrom(eventPayload: JSONObject): JSONObject = JSONObject()
    .put("type", "function_call")
    .apply {
      firstNonBlankString(
        eventPayload.nonBlankString("item_id"),
        eventPayload.nonBlankString("output_item_id"),
        eventPayload.nonBlankString("id"),
      )?.let { itemId ->
        put("id", itemId)
      }
      eventPayload.nonBlankString("call_id")?.let { callId ->
        put("call_id", callId)
      }
      eventPayload.nonBlankString("name")?.let { toolName ->
        put("name", toolName)
      }
    }

internal fun OpenAiCompatibleLiteLlmProviderClient.shouldSplitResponsesOutputIndex(
    existing: JSONObject?,
    incoming: JSONObject,
  ): Boolean {
    val current = existing ?: return false
    if (responsesItemsShareIdentity(current, incoming)) {
      return false
    }
    val currentType = current.optString("type").trim().lowercase()
    val incomingType = incoming.optString("type").trim().lowercase()
    if (currentType.isBlank() || incomingType.isBlank()) {
      return false
    }
    if (currentType != incomingType) {
      return true
    }
    val currentIdentities = responsesOutputItemIdentities(current)
    val incomingIdentities = responsesOutputItemIdentities(incoming)
    if (currentIdentities.isNotEmpty() && incomingIdentities.isNotEmpty()) {
      return true
    }
    return currentType == "message" && responsesMessageItemsConflict(current, incoming)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesItemsShareIdentity(
    left: JSONObject,
    right: JSONObject,
  ): Boolean {
    val leftIdentities = responsesOutputItemIdentities(left)
    if (leftIdentities.isEmpty()) {
      return false
    }
    return responsesOutputItemIdentities(right).any(leftIdentities::contains)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesMessageItemsConflict(
    existing: JSONObject,
    incoming: JSONObject,
  ): Boolean {
    val existingId = existing.nonBlankString("id")
    val incomingId = incoming.nonBlankString("id")
    if (
      existingId != null &&
      incomingId != null &&
      !existingId.equals(incomingId, ignoreCase = true)
    ) {
      return true
    }
    val existingPhase = existing.nonBlankString("phase")
      ?.trim()
      ?.lowercase()
    val incomingPhase = incoming.nonBlankString("phase")
      ?.trim()
      ?.lowercase()
    return existingPhase != null &&
      incomingPhase != null &&
      existingPhase != incomingPhase
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.ensureResponsesFunctionCallItem(
    outputItems: MutableMap<Int, JSONObject>,
    outputIndex: Int,
    eventPayload: JSONObject,
  ): JSONObject = outputItems.getOrPut(outputIndex) {
    JSONObject()
      .put("type", "function_call")
      .put("arguments", "")
  }.apply {
    put("type", "function_call")
    if (isMissingJsonValue(opt("arguments"))) {
      put("arguments", "")
    }
    firstNonBlankString(
      nonBlankString("id"),
      eventPayload.nonBlankString("item_id"),
      eventPayload.nonBlankString("output_item_id"),
      eventPayload.nonBlankString("id"),
    )?.let { itemId ->
      put("id", itemId)
    }
    firstNonBlankString(
      nonBlankString("call_id"),
      eventPayload.nonBlankString("call_id"),
    )?.let { callId ->
      put("call_id", callId)
    }
    firstNonBlankString(
      nonBlankString("name"),
      eventPayload.nonBlankString("name"),
    )?.let { toolName ->
      put("name", toolName)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.appendResponsesFunctionCallArgumentsDelta(
    item: JSONObject,
    delta: String,
  ) {
    if (delta.isEmpty()) {
      return
    }
    appendJsonStringField(item, "arguments", delta)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.setResponsesFunctionCallArgumentsValue(
    item: JSONObject,
    arguments: String,
  ) {
    item.put("arguments", arguments)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.appendResponsesOutputTextDelta(
    item: JSONObject,
    delta: String,
    contentIndex: Int,
  ) {
    if (delta.isEmpty()) {
      return
    }
    val content = when (val existing = item.opt("content")) {
      is JSONArray -> existing
      is JSONObject -> JSONArray().put(JSONObject(existing.toString())).also { array ->
        item.put("content", array)
      }
      is String -> JSONArray()
        .put(
          JSONObject()
            .put("type", "output_text")
            .put("text", existing),
        )
        .also { array -> item.put("content", array) }
      else -> JSONArray().also { array -> item.put("content", array) }
    }
    while (content.length() <= contentIndex) {
      content.put(
        JSONObject()
          .put("type", "output_text")
          .put("text", ""),
      )
    }
    val contentItem = content.optJSONObject(contentIndex)
      ?: JSONObject()
        .put("type", "output_text")
        .put("text", "")
        .also { created -> content.put(contentIndex, created) }
    if (contentItem.optString("type").isBlank()) {
      contentItem.put("type", "output_text")
    }
    appendJsonStringField(contentItem, "text", delta)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.setResponsesOutputTextValue(
    item: JSONObject,
    text: String,
    contentIndex: Int,
  ) {
    val contentItem = ensureResponsesContentItem(item, contentIndex)
    if (contentItem.optString("type").isBlank()) {
      contentItem.put("type", "output_text")
    }
    contentItem.put("text", text)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeResponsesContentPart(
    item: JSONObject,
    part: JSONObject?,
    contentIndex: Int,
  ) {
    val contentItem = ensureResponsesContentItem(item, contentIndex)
    val normalizedPart = part ?: return
    val keys = normalizedPart.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      if (key == "text") {
        if (normalizedPart.has("text")) {
          contentItem.put("text", normalizedPart.optString("text"))
        }
        continue
      }
      if (key == "type") {
        normalizedPart.nonBlankString("type")?.let { type ->
          contentItem.put("type", type)
        }
        continue
      }
      copyJsonFieldIfPresent(normalizedPart, contentItem, key)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.ensureResponsesContentItem(
    item: JSONObject,
    contentIndex: Int,
  ): JSONObject {
    val content = when (val existing = item.opt("content")) {
      is JSONArray -> existing
      is JSONObject -> JSONArray().put(JSONObject(existing.toString())).also { array ->
        item.put("content", array)
      }
      is String -> JSONArray()
        .put(
          JSONObject()
            .put("type", "output_text")
            .put("text", existing),
        )
        .also { array -> item.put("content", array) }
      else -> JSONArray().also { array -> item.put("content", array) }
    }
    while (content.length() <= contentIndex) {
      content.put(JSONObject())
    }
    return content.optJSONObject(contentIndex)
      ?: JSONObject().also { created ->
        content.put(contentIndex, created)
      }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesVisibleText(
    outputItems: Map<Int, JSONObject>,
  ): String {
    val textSegments = responsesTextSegments(outputItems.toSortedMap().values)
    return firstNonBlankString(
      textSegments.finalAnswer.joinToString(separator = "\n").trim().takeIf(String::isNotBlank),
      textSegments.unphased.joinToString(separator = "\n").trim().takeIf(String::isNotBlank),
      textSegments.commentary.lastOrNull()?.trim()?.takeIf(String::isNotBlank),
      textSegments.ordered.lastOrNull()?.trim()?.takeIf(String::isNotBlank),
    ).orEmpty()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.backfillResponsesVisibleTextOutput(
    outputItems: MutableMap<Int, JSONObject>,
    visibleText: String?,
  ) {
    val normalizedText = visibleText?.trim()?.takeIf(String::isNotBlank) ?: return
    if (responsesVisibleText(outputItems).isNotBlank()) {
      return
    }
    outputItems[nextResponsesOutputIndex(outputItems)] = JSONObject()
      .put("type", "message")
      .put("role", "assistant")
      .put(
        "content",
        JSONArray().put(
          JSONObject()
            .put("type", "output_text")
            .put("text", normalizedText),
        ),
      )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.isResponsesAssistantMessage(item: JSONObject?): Boolean =
    item?.optString("type") == "message" &&
      item.optString("role").trim().ifEmpty { "assistant" } == "assistant"

internal fun OpenAiCompatibleLiteLlmProviderClient.responsesOutputItemIdentities(item: JSONObject): List<String> = buildList {
    item.nonBlankString("id")?.let(::add)
    item.nonBlankString("call_id")
      ?.takeIf { callId -> callId !in this }
      ?.let(::add)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesOutputItem(
    item: JSONObject,
  ): JSONObject = when (item.optString("type").trim().lowercase()) {
    "message" -> sanitizeResponsesMessageItem(item)
    "function_call" -> sanitizeResponsesFunctionCallItem(item)
    "web_search_call" -> sanitizeResponsesWebSearchCallItem(item)
    "reasoning" -> sanitizeResponsesReasoningItem(item)
    else -> sanitizeResponsesGenericItem(item)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesMessageItem(
    item: JSONObject,
  ): JSONObject = JSONObject().apply {
    copyJsonScalarFieldIfPresent(item, this, "id")
    copyJsonScalarFieldIfPresent(item, this, "type")
    copyJsonScalarFieldIfPresent(item, this, "role")
    copyJsonScalarFieldIfPresent(item, this, "status")
    copyJsonScalarFieldIfPresent(item, this, "phase")
    when (val content = item.opt("content")) {
      is String -> put("content", content)
      is JSONArray -> sanitizeResponsesContentArray(content)?.let { sanitized -> put("content", sanitized) }
      is JSONObject -> sanitizeResponsesContentPart(content)?.let { sanitized ->
        put("content", JSONArray().put(sanitized))
      }
    }
    if (!has("content")) {
      copyJsonScalarFieldIfPresent(item, this, "text")
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesFunctionCallItem(
    item: JSONObject,
  ): JSONObject = JSONObject().apply {
    copyJsonScalarFieldIfPresent(item, this, "id")
    copyJsonScalarFieldIfPresent(item, this, "type")
    copyJsonScalarFieldIfPresent(item, this, "call_id")
    copyJsonScalarFieldIfPresent(item, this, "name")
    copyJsonScalarFieldIfPresent(item, this, "arguments")
    copyJsonScalarFieldIfPresent(item, this, "status")
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesWebSearchCallItem(
    item: JSONObject,
  ): JSONObject = JSONObject().apply {
    copyJsonScalarFieldIfPresent(item, this, "id")
    copyJsonScalarFieldIfPresent(item, this, "type")
    copyJsonScalarFieldIfPresent(item, this, "call_id")
    copyJsonScalarFieldIfPresent(item, this, "status")
    copyJsonScalarFieldIfPresent(item, this, "action_type")
    copyJsonScalarFieldIfPresent(item, this, "query")
    copyJsonScalarFieldIfPresent(item, this, "url")
    copyJsonScalarFieldIfPresent(item, this, "page_url")
    copyJsonScalarFieldIfPresent(item, this, "text")
    copyJsonScalarFieldIfPresent(item, this, "pattern")
    sanitizeJsonStringArray(item.optJSONArray("domains"))?.let { domains ->
      put("domains", domains)
    }
    sanitizeResponsesWebSearchAction(item.optJSONObject("action"))?.let { action ->
      put("action", action)
    }
    sanitizeResponsesWebSearchSources(item.optJSONArray("sources"))?.let { sources ->
      put("sources", sources)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesReasoningItem(
    item: JSONObject,
  ): JSONObject = JSONObject().apply {
    copyJsonScalarFieldIfPresent(item, this, "id")
    copyJsonScalarFieldIfPresent(item, this, "type")
    copyJsonScalarFieldIfPresent(item, this, "status")
    copyJsonScalarFieldIfPresent(item, this, "text")
    sanitizeResponsesTextPayload(item.opt("summary"))?.let { summary ->
      put("summary", summary)
    }
    sanitizeResponsesTextPayload(item.opt("content"))?.let { content ->
      put("content", content)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesGenericItem(
    item: JSONObject,
  ): JSONObject = JSONObject().apply {
    copyJsonScalarFieldIfPresent(item, this, "id")
    copyJsonScalarFieldIfPresent(item, this, "type")
    copyJsonScalarFieldIfPresent(item, this, "call_id")
    copyJsonScalarFieldIfPresent(item, this, "status")
    copyJsonScalarFieldIfPresent(item, this, "name")
    copyJsonScalarFieldIfPresent(item, this, "phase")
    copyJsonScalarFieldIfPresent(item, this, "text")
    sanitizeResponsesTextPayload(item.opt("content"))?.let { content ->
      put("content", content)
    }
    sanitizeResponsesTextPayload(item.opt("summary"))?.let { summary ->
      put("summary", summary)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesTextPayload(
    value: Any?,
  ): Any? = when (value) {
    null,
    JSONObject.NULL,
    -> null
    is String -> value
    is JSONObject -> sanitizeResponsesContentPart(value)
    is JSONArray -> sanitizeResponsesContentArray(value)
    else -> null
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesContentArray(
    content: JSONArray,
  ): JSONArray? {
    val sanitized = JSONArray()
    for (index in 0 until content.length()) {
      when (val item = content.opt(index)) {
        is String -> sanitized.put(item)
        is JSONObject -> sanitizeResponsesContentPart(item)?.let(sanitized::put)
      }
    }
    return sanitized.takeIf { it.length() > 0 }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesContentPart(
    content: JSONObject,
  ): JSONObject? = JSONObject().apply {
    copyJsonScalarFieldIfPresent(content, this, "type")
    copyJsonScalarFieldIfPresent(content, this, "text")
    copyJsonScalarFieldIfPresent(content, this, "output_text")
    copyJsonScalarFieldIfPresent(content, this, "summary_text")
    copyJsonScalarFieldIfPresent(content, this, "refusal")
    copyJsonScalarFieldIfPresent(content, this, "content")
    copyJsonScalarFieldIfPresent(content, this, "value")
    sanitizeResponsesAnnotations(content.optJSONArray("annotations"))?.let { annotations ->
      put("annotations", annotations)
    }
  }.takeIf { sanitized ->
    sanitized.length() > 0
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesAnnotations(
    annotations: JSONArray?,
  ): JSONArray? {
    if (annotations == null || annotations.length() == 0) {
      return null
    }
    val sanitized = JSONArray()
    for (index in 0 until annotations.length()) {
      val annotation = annotations.optJSONObject(index) ?: continue
      val normalized = JSONObject().apply {
        copyJsonScalarFieldIfPresent(annotation, this, "type")
        copyJsonScalarFieldIfPresent(annotation, this, "title")
        copyJsonScalarFieldIfPresent(annotation, this, "url")
        copyJsonScalarFieldIfPresent(annotation, this, "start_index")
        copyJsonScalarFieldIfPresent(annotation, this, "end_index")
      }
      if (normalized.length() > 0) {
        sanitized.put(normalized)
      }
    }
    return sanitized.takeIf { it.length() > 0 }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesWebSearchAction(
    action: JSONObject?,
  ): JSONObject? {
    if (action == null) {
      return null
    }
    return JSONObject().apply {
      copyJsonScalarFieldIfPresent(action, this, "type")
      copyJsonScalarFieldIfPresent(action, this, "query")
      copyJsonScalarFieldIfPresent(action, this, "url")
      copyJsonScalarFieldIfPresent(action, this, "page_url")
      copyJsonScalarFieldIfPresent(action, this, "text")
      copyJsonScalarFieldIfPresent(action, this, "pattern")
      sanitizeJsonStringArray(action.optJSONArray("queries"))?.let { queries ->
        put("queries", queries)
      }
      sanitizeJsonStringArray(action.optJSONArray("domains"))?.let { domains ->
        put("domains", domains)
      }
      sanitizeResponsesWebSearchSources(action.optJSONArray("sources"))?.let { sources ->
        put("sources", sources)
      }
    }.takeIf { sanitized -> sanitized.length() > 0 }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.sanitizeResponsesWebSearchSources(
    sources: JSONArray?,
  ): JSONArray? {
    if (sources == null || sources.length() == 0) {
      return null
    }
    val sanitized = JSONArray()
    for (index in 0 until sources.length()) {
      val source = sources.optJSONObject(index) ?: continue
      val normalized = JSONObject().apply {
        copyJsonScalarFieldIfPresent(source, this, "title")
        copyJsonScalarFieldIfPresent(source, this, "url")
      }
      if (normalized.length() > 0) {
        sanitized.put(normalized)
      }
    }
    return sanitized.takeIf { it.length() > 0 }
  }

private fun sanitizeJsonStringArray(
    source: JSONArray?,
  ): JSONArray? {
    if (source == null || source.length() == 0) {
      return null
    }
    val sanitized = JSONArray()
    for (index in 0 until source.length()) {
      val value = source.optString(index).trim()
      if (value.isNotBlank()) {
        sanitized.put(value)
      }
    }
    return sanitized.takeIf { it.length() > 0 }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.nextResponsesOutputIndex(
    outputItems: Map<Int, JSONObject>,
  ): Int = (outputItems.keys.maxOrNull() ?: -1) + 1

internal fun OpenAiCompatibleLiteLlmProviderClient.copyJsonScalarFieldIfPresent(
    source: JSONObject,
    target: JSONObject,
    key: String,
  ) {
    if (!source.has(key)) {
      return
    }
    when (val value = source.opt(key)) {
      is String,
      is Number,
      is Boolean,
      -> target.put(key, value)
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.deepCopyJsonValue(value: Any?): Any? = when (value) {
    null,
    JSONObject.NULL,
    -> null
    is JSONObject -> JSONObject(value.toString())
    is JSONArray -> JSONArray(value.toString())
    else -> value
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.isMissingJsonValue(value: Any?): Boolean = value == null ||
    value == JSONObject.NULL ||
    (value is String && value.isBlank())
