package com.opencray.app.facade.llm

import com.opencray.app.LlmPromptCacheKeyStrategies
import com.opencray.app.LlmPromptCacheRetentionPolicies
import com.opencray.app.LlmPromptCachingMetadataKeys
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmStructuredFinalMetadataKeys
import com.opencray.app.llmRouteFingerprint
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.STRUCTURED_FINAL_SCHEMA_NAME
import com.opencray.app.PromptCacheUsageSnapshot
import com.opencray.app.StructuredToolCallParseResult
import com.opencray.app.VisibleTextSnapshotCoalescer
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
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
import com.opencray.llm.LiteLlmVisibleTextObserver
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private const val KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME: String = "\$web_search"
private const val KIMI_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY: String = "_host.kimiBuiltinWebSearchLoopDepth"
private const val MAX_KIMI_BUILTIN_WEB_SEARCH_AUTO_TURNS: Int = 4

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiRequestBody(
    request: LiteLlmProviderRequest,
    streamResponses: Boolean = false,
  ): String {
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
    openAiPromptCacheKey(request)?.let { promptCacheKey ->
      payload.put("prompt_cache_key", promptCacheKey)
    }
    openAiPromptCacheRetention(request)?.let { retention ->
      payload.put("prompt_cache_retention", retention)
    }
    if (openAiStructuredFinalSchemaSupported(request)) {
      payload.put("response_format", openAiStructuredFinalResponseFormat())
    }
    if (streamResponses) {
      payload.put("stream", true)
      payload.put(
        "stream_options",
        JSONObject().put("include_usage", true),
      )
    }
    return payload.toString()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiStructuredFinalResponseFormat(): JSONObject = JSONObject()
    .put("type", "json_schema")
    .put(
      "json_schema",
      JSONObject()
        .put("name", STRUCTURED_FINAL_SCHEMA_NAME)
        .put("strict", true)
        .put("schema", structuredFinalSchema()),
    )

internal fun OpenAiCompatibleLiteLlmProviderClient.extractOpenAiMessageContent(choice: JSONObject?): String {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.synthesizeToolCallPayload(toolCalls: JSONArray?): String? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractProtocolPayloadFromAlternateFields(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractOpenAiReasoningText(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
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
    val finalAttachmentPayload = protocolPayload?.toProtocolFinalPayloadOrNull()
    val finalAttachments = finalAttachmentPayload?.structuredFinalAttachments().orEmpty()
    val commentaryText = textContent?.takeIf { toolCalls.isNotEmpty() }
    val finalText = finalAttachmentPayload?.nonBlankString("answer")
      ?: textContent?.takeUnless { text ->
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
      finalAttachments = finalAttachments,
      commentaryText = commentaryText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallParse.errors,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiStructuredToolCalls(toolCalls: JSONArray?): StructuredToolCallParseResult {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiPromptCacheUsage(
    payload: JSONObject,
  ): PromptCacheUsageSnapshot? {
    val usage = payload.optJSONObject("usage") ?: return null
    val cachedTokens = usage.optJSONObject("prompt_tokens_details")
      ?.optLongValue("cached_tokens")
      ?: usage.optJSONObject("input_tokens_details")
        ?.optLongValue("cached_tokens")
      ?: usage.optLongValue("cached_tokens")
      ?: return null
    return PromptCacheUsageSnapshot(
      cacheUsed = cachedTokens > 0L,
      readTokens = cachedTokens,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiBuiltinWebSearchDialect(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.inferOpenAiBuiltinWebSearchDialectFromModel(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiPromptCacheKey(
    request: LiteLlmProviderRequest,
  ): String? {
    if (!openAiPromptCacheHintsSupported(request)) {
      return null
    }
    return when (resolvedPromptCacheKeyStrategy(request)) {
      LlmPromptCacheKeyStrategies.ROUTE -> openAiRoutePromptCacheKey(request)
      LlmPromptCacheKeyStrategies.SESSION -> {
        val sessionId = request.request.metadata["sessionId"]
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: return null
        "${openAiRoutePromptCacheKey(request)}|session=$sessionId"
      }

      else -> null
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiPromptCacheRetention(
    request: LiteLlmProviderRequest,
  ): String? {
    if (!openAiPromptCacheHintsSupported(request)) {
      return null
    }
    return when (
      resolvedPromptCachingMetadataValue(
        request = request,
        key = LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION,
      )?.lowercase()
    ) {
      LlmPromptCacheRetentionPolicies.IN_MEMORY -> LlmPromptCacheRetentionPolicies.IN_MEMORY
      LlmPromptCacheRetentionPolicies.HOURS_24 -> LlmPromptCacheRetentionPolicies.HOURS_24
      else -> null
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiPromptCacheHintsSupported(
    request: LiteLlmProviderRequest,
  ): Boolean {
    if (resolvedProtocol(request) != LlmProviderProtocols.OPENAI &&
      resolvedProtocol(request) != LlmProviderProtocols.OPENAI_RESPONSES
    ) {
      return false
    }
    resolvedPromptCachingMetadataValue(
      request = request,
      key = LlmPromptCachingMetadataKeys.PROMPT_CACHE_HINTS_SUPPORTED,
    )?.lowercase()?.let { rawValue ->
      return when (rawValue) {
        "true" -> true
        "false" -> false
        else -> false
      }
    }
    return isOfficialOpenAiRoute(request)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiStructuredFinalSchemaSupported(
    request: LiteLlmProviderRequest,
  ): Boolean {
    if (resolvedProtocol(request) != LlmProviderProtocols.OPENAI &&
      resolvedProtocol(request) != LlmProviderProtocols.OPENAI_RESPONSES
    ) {
      return false
    }
    resolvedStructuredFinalMetadataValue(
      request = request,
      key = LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED,
    )?.lowercase()?.let { rawValue ->
      return when (rawValue) {
        "true" -> true
        "false" -> false
        else -> false
      }
    }
    return isOfficialOpenAiRoute(request)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.isOfficialOpenAiRoute(request: LiteLlmProviderRequest): Boolean {
    val host = providerHost(request)
    return host == "api.openai.com" || host.endsWith(".openai.com")
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.resolvedPromptCacheKeyStrategy(
    request: LiteLlmProviderRequest,
  ): String? = when (
    resolvedPromptCachingMetadataValue(
      request = request,
      key = LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY,
    )?.lowercase()
  ) {
    LlmPromptCacheKeyStrategies.NONE -> null
    LlmPromptCacheKeyStrategies.ROUTE -> LlmPromptCacheKeyStrategies.ROUTE
    LlmPromptCacheKeyStrategies.SESSION -> LlmPromptCacheKeyStrategies.SESSION
    else -> null
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiRoutePromptCacheKey(
    request: LiteLlmProviderRequest,
  ): String = llmRouteFingerprint(
    protocol = resolvedProtocol(request),
    baseUrl = request.route.baseUrl.orEmpty(),
    model = request.route.model,
  )

internal fun OpenAiCompatibleLiteLlmProviderClient.maybeAutoContinueOpenAiBuiltinWebSearch(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiConversationMessages(
    request: LiteLlmGatewayRequest,
  ): List<LiteLlmGatewayMessage> = projectedConversationMessages(request)

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiAssistantMessageFromPayload(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiToolsArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiBuiltinTool(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.applyOpenAiToolControl(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiToolChoice(toolChoice: LiteLlmToolChoice): Any = when (toolChoice.mode) {
    LiteLlmToolChoiceMode.AUTO -> "auto"
    LiteLlmToolChoiceMode.NONE -> "none"
    LiteLlmToolChoiceMode.REQUIRED -> "required"
    LiteLlmToolChoiceMode.TOOL -> JSONObject()
      .put("type", "function")
      .put("function", JSONObject().put("name", toolChoice.toolName))
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiMessagesArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    val conversationMessages = openAiConversationMessages(request.request)
    request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
      put(
        JSONObject()
          .put("role", "system")
          .put("content", systemPrompt),
      )
    }
    conversationMessages.forEach { message ->
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiUserContentPayload(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiPdfBlock(
    pdf: EncodedPdfAttachment,
  ): JSONObject = JSONObject()
    .put("type", "file")
    .put(
      "file",
      JSONObject()
        .put("filename", pdf.displayName)
        .put("file_data", inlinePdfDataUrl(pdf)),
    )

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiImageBlock(
    image: EncodedImageAttachment,
  ): JSONObject = JSONObject()
    .put("type", "image_url")
    .put(
      "image_url",
      JSONObject().put("url", inlineImageDataUrl(image)),
    )

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiToolCallsArray(message: LiteLlmGatewayMessage): JSONArray = JSONArray().apply {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildOpenAiToolResultMessage(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiBuiltinWebSearchObservations(
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
          queries = builtinWebSearchFallbackQueries(request),
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiBuiltinWebSearchObservationFromArguments(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiResponseShape(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.readOpenAiChatCompletionsStream(
    input: InputStream,
    streamObserver: LiteLlmVisibleTextObserver,
  ): String {
    val payload = JSONObject()
    val choices = linkedMapOf<Int, JSONObject>()
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
        processOpenAiChatCompletionsStreamEvent(
          eventName = currentEvent,
          data = dataLines.joinToString(separator = "\n"),
          payload = payload,
          choices = choices,
          visibleTextCoalescer = visibleTextCoalescer,
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
    visibleTextCoalescer.flush()
    val choicesArray = JSONArray()
    choices.toSortedMap().values.forEach(choicesArray::put)
    payload.put("choices", choicesArray)
    return payload.toString()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.processOpenAiChatCompletionsStreamEvent(
    eventName: String,
    data: String,
    payload: JSONObject,
    choices: MutableMap<Int, JSONObject>,
    visibleTextCoalescer: VisibleTextSnapshotCoalescer,
  ) {
    val trimmedData = data.trim()
    if (trimmedData.isBlank() || trimmedData == "[DONE]") {
      return
    }
    val eventPayload = runCatching { JSONObject(trimmedData) }.getOrElse { error ->
      throw IllegalStateException("Failed to parse OpenAI chat completions streaming event.", error)
    }
    val eventType = eventPayload.optString("type").ifBlank { eventName }
    val inlineErrorObject = eventPayload.optJSONObject("error")
    if (eventType == "error" || inlineErrorObject != null) {
      val errorObject = inlineErrorObject
      val message = errorObject?.nonBlankString("message")
        ?: eventPayload.nonBlankString("message")
        ?: "OpenAI chat completions streaming request failed."
      throw ProviderStreamErrorException(
        providerErrorCode = firstNonBlankString(
          errorObject?.nonBlankString("code"),
          errorObject?.nonBlankString("type"),
          eventPayload.nonBlankString("code"),
        ),
        message = message,
      )
    }
    copyJsonFieldIfPresent(eventPayload, payload, "id")
    copyJsonFieldIfPresent(eventPayload, payload, "model")
    copyJsonFieldIfPresent(eventPayload, payload, "object")
    copyJsonFieldIfPresent(eventPayload, payload, "created")
    copyJsonFieldIfPresent(eventPayload, payload, "service_tier")
    copyJsonFieldIfPresent(eventPayload, payload, "system_fingerprint")
    copyJsonFieldIfPresent(eventPayload, payload, "usage")
    var visibleTextMayHaveChanged = false
    val eventChoices = eventPayload.optJSONArray("choices") ?: return
    for (choiceIndex in 0 until eventChoices.length()) {
      val eventChoice = eventChoices.optJSONObject(choiceIndex) ?: continue
      val index = eventChoice.optInt("index", choiceIndex)
      val choice = choices.getOrPut(index) {
        JSONObject().put("index", index).put("message", JSONObject().put("role", "assistant"))
      }
      val message = choice.optJSONObject("message")
        ?: JSONObject().put("role", "assistant").also { choice.put("message", it) }
      val delta = eventChoice.optJSONObject("delta")
      delta?.nonBlankString("role")?.let { role -> message.put("role", role) }
      if (delta != null) {
        val priorVisibleText = extractOpenAiContentValue(message.opt("content"))
        appendOpenAiContentField(message, "content", delta.opt("content"))
        appendOpenAiContentField(message, "reasoning_content", delta.opt("reasoning_content"))
        appendOpenAiContentField(message, "reasoning", delta.opt("reasoning"))
        if (delta.has("tool_calls")) {
          mergeOpenAiStreamToolCalls(
            message = message,
            toolCallsDelta = delta.optJSONArray("tool_calls"),
          )
        }
        if (extractOpenAiContentValue(message.opt("content")) != priorVisibleText) {
          visibleTextMayHaveChanged = true
        }
      }
      if (eventChoice.has("finish_reason")) {
        choice.put("finish_reason", eventChoice.opt("finish_reason"))
      }
      copyJsonFieldIfPresent(eventChoice, choice, "logprobs")
    }
    if (visibleTextMayHaveChanged) {
      visibleTextCoalescer.update(openAiStreamVisibleText(choices))
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.appendOpenAiContentField(
    target: JSONObject,
    key: String,
    rawValue: Any?,
  ) {
    val delta = extractOpenAiContentValue(rawValue)
    if (delta.isEmpty()) {
      return
    }
    val existing = extractOpenAiContentValue(target.opt(key))
    target.put(key, existing + delta)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.mergeOpenAiStreamToolCalls(
    message: JSONObject,
    toolCallsDelta: JSONArray?,
  ) {
    if (toolCallsDelta == null || toolCallsDelta.length() == 0) {
      return
    }
    val toolCalls = when (val existing = message.opt("tool_calls")) {
      is JSONArray -> existing
      else -> JSONArray().also { message.put("tool_calls", it) }
    }
    for (deltaIndex in 0 until toolCallsDelta.length()) {
      val deltaToolCall = toolCallsDelta.optJSONObject(deltaIndex) ?: continue
      val toolIndex = deltaToolCall.optInt("index", deltaIndex)
      while (toolCalls.length() <= toolIndex) {
        toolCalls.put(JSONObject())
      }
      val toolCall = toolCalls.optJSONObject(toolIndex)
        ?: JSONObject().also { toolCalls.put(toolIndex, it) }
      deltaToolCall.nonBlankString("id")?.let { id -> toolCall.put("id", id) }
      deltaToolCall.nonBlankString("type")?.let { type -> toolCall.put("type", type) }
      val functionDelta = deltaToolCall.optJSONObject("function")
      if (functionDelta != null) {
        val function = toolCall.optJSONObject("function")
          ?: JSONObject().also { toolCall.put("function", it) }
        functionDelta.nonBlankString("name")?.let { name ->
          appendJsonStringField(function, "name", name)
        }
        if (functionDelta.has("arguments")) {
          appendJsonStringField(
            function,
            "arguments",
            functionDelta.opt("arguments")?.toString(),
          )
        }
      }
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.openAiStreamVisibleText(
    choices: Map<Int, JSONObject>,
  ): String = choices.toSortedMap().values.firstOrNull()
    ?.optJSONObject("message")
    ?.let { message -> extractOpenAiContentValue(message.opt("content")) }
    .orEmpty()
    .trim()
