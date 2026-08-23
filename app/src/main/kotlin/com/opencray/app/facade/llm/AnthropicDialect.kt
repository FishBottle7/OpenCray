package com.opencray.app.facade.llm

import com.opencray.app.AnthropicPromptCacheTtlPolicies
import com.opencray.app.LlmPromptCachingMetadataKeys
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmStructuredFinalMetadataKeys
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.PromptCacheUsageSnapshot
import com.opencray.app.VisibleTextSnapshotCoalescer
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
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
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_ANTHROPIC_MAX_TOKENS: Int = 4096
private const val ANTHROPIC_WEB_SEARCH_TOOL_TYPE: String = "web_search_20250305"
private const val ANTHROPIC_WEB_SEARCH_TOOL_NAME: String = "web_search"
private const val ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME: String = "OpenCrayFinalResponse"
private const val DEFAULT_ANTHROPIC_WEB_SEARCH_MAX_USES: Int = 5
private const val ANTHROPIC_SERVER_TOOL_CONTINUATION_CONTENT_KEY: String =
  "_host.anthropicServerToolContinuationContent"
private const val ANTHROPIC_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY: String =
  "_host.anthropicBuiltinWebSearchLoopDepth"
private const val MAX_ANTHROPIC_BUILTIN_WEB_SEARCH_AUTO_TURNS: Int = 4

internal data class AnthropicUserTurnAssembly(
  val message: JSONObject?,
  val nextIndexExclusive: Int,
)

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicRequestBody(
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

    buildAnthropicToolsArray(request)
      .takeIf { tools -> tools.length() > 0 }
      ?.let { tools -> payload.put("tools", tools) }

    applyAnthropicToolControl(payload, request.request)
    request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
      payload.put("system", systemPrompt)
    }
    anthropicPromptCacheControl(request)?.let { cacheControl ->
      payload.put("cache_control", cacheControl)
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractAnthropicMessageContent(payload: JSONObject): String {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.extractAnthropicThinkingText(block: JSONObject): String? = firstNonBlankString(
    block.optString("thinking").trim().takeIf(String::isNotBlank),
    extractOpenAiContentValue(block.opt("thinking")),
    block.optString("text").trim().takeIf(String::isNotBlank),
  )

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicStructuredCompletion(payload: JSONObject): LiteLlmStructuredCompletion? {
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
    val structuredFinalToolPayload = extractAnthropicStructuredFinalToolPayload(toolCalls)
    val executableToolCalls = toolCalls.filterNot { toolCall ->
      toolCall.toolName == ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME
    }
    val commentaryText = textContent?.takeIf { executableToolCalls.isNotEmpty() }
    val finalText = textContent?.takeUnless { text ->
      executableToolCalls.isNotEmpty() || looksLikeProtocolPayload(text)
    }
    val finalAttachmentPayload = textContent
      ?.takeIf(::looksLikeProtocolPayload)
      ?.toProtocolFinalPayloadOrNull()
      ?: structuredFinalToolPayload
    val nativeFinalText = finalAttachmentPayload?.nonBlankString("answer") ?: finalText
    val finalAttachments = finalAttachmentPayload?.structuredFinalAttachments().orEmpty()
    val rawText = when {
      textContent != null && looksLikeProtocolPayload(textContent) -> textContent
      toolCallErrors.isNotEmpty() -> content.toString().trim().takeIf(String::isNotBlank)
      executableToolCalls.isNotEmpty() -> null
      else -> finalText
    }
    val reasoningText = thinkingBlocks.joinToString(separator = "\n").trim().takeIf(String::isNotBlank)
    return buildStructuredCompletion(
      toolCalls = executableToolCalls,
      finalText = nativeFinalText,
      finalAttachments = finalAttachments,
      commentaryText = commentaryText,
      reasoningText = reasoningText,
      rawText = rawText,
      toolCallErrors = toolCallErrors,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.extractAnthropicStructuredFinalToolPayload(
    toolCalls: List<LiteLlmStructuredToolCall>,
  ): JSONObject? {
    val finalToolCall = toolCalls.firstOrNull { toolCall ->
      toolCall.toolName == ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME
    } ?: return null
    return runCatching { JSONObject(finalToolCall.arguments.toString()) }.getOrNull()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicPromptCacheUsage(
    payload: JSONObject,
  ): PromptCacheUsageSnapshot? {
    val usage = payload.optJSONObject("usage") ?: return null
    val readTokens = usage.optLongValue("cache_read_input_tokens")
    val writeTokens = usage.optLongValue("cache_creation_input_tokens")
    val cacheCreation = usage.optJSONObject("cache_creation")
    val write5mTokens = cacheCreation?.optLongValue("ephemeral_5m_input_tokens")
      ?: usage.optLongValue("cache_creation_ephemeral_5m_input_tokens")
    val write1hTokens = cacheCreation?.optLongValue("ephemeral_1h_input_tokens")
      ?: usage.optLongValue("cache_creation_ephemeral_1h_input_tokens")
    if (readTokens == null && writeTokens == null && write5mTokens == null && write1hTokens == null) {
      return null
    }
    val resolvedWriteTokens = writeTokens ?: listOfNotNull(write5mTokens, write1hTokens)
      .takeIf { values -> values.isNotEmpty() }
      ?.sum()
    val retention = when {
      write5mTokens != null && write1hTokens != null -> "mixed"
      write1hTokens != null -> "1h"
      write5mTokens != null -> "5m"
      else -> null
    }
    return PromptCacheUsageSnapshot(
      cacheUsed = (readTokens ?: 0L) > 0L || (resolvedWriteTokens ?: 0L) > 0L,
      readTokens = readTokens,
      writeTokens = resolvedWriteTokens,
      write5mTokens = write5mTokens,
      write1hTokens = write1hTokens,
      retention = retention,
    )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicPromptCacheControl(
    request: LiteLlmProviderRequest,
  ): JSONObject? {
    if (!anthropicPromptCachingEnabled(request)) {
      return null
    }
    return JSONObject()
      .put("type", "ephemeral")
      .apply {
        if (anthropicPromptCacheRetention(request) == AnthropicPromptCacheTtlPolicies.HOUR_1) {
          put("ttl", AnthropicPromptCacheTtlPolicies.HOUR_1)
        }
      }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicPromptCachingEnabled(
    request: LiteLlmProviderRequest,
  ): Boolean {
    if (resolvedProtocol(request) != LlmProviderProtocols.ANTHROPIC) {
      return false
    }
    return resolvedPromptCachingMetadataValue(
      request = request,
      key = LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHING_ENABLED,
    )?.lowercase() == "true"
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicPromptCacheRetention(
    request: LiteLlmProviderRequest,
  ): String? {
    if (!anthropicPromptCachingEnabled(request)) {
      return null
    }
    return when (
      resolvedPromptCachingMetadataValue(
        request = request,
        key = LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHE_TTL,
      )?.lowercase()
    ) {
      null, "", AnthropicPromptCacheTtlPolicies.MINUTES_5 -> AnthropicPromptCacheTtlPolicies.MINUTES_5
      AnthropicPromptCacheTtlPolicies.HOUR_1 -> AnthropicPromptCacheTtlPolicies.HOUR_1
      else -> AnthropicPromptCacheTtlPolicies.MINUTES_5
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicStructuredFinalToolSupported(
    request: LiteLlmProviderRequest,
  ): Boolean {
    if (resolvedProtocol(request) != LlmProviderProtocols.ANTHROPIC) {
      return false
    }
    resolvedStructuredFinalMetadataValue(
      request = request,
      key = LlmStructuredFinalMetadataKeys.ANTHROPIC_STRUCTURED_FINAL_TOOL_SUPPORTED,
    )?.lowercase()?.let { rawValue ->
      return when (rawValue) {
        "true" -> true
        "false" -> false
        else -> false
      }
    }
    return isOfficialAnthropicRoute(request)
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.isOfficialAnthropicRoute(request: LiteLlmProviderRequest): Boolean {
    val host = providerHost(request)
    return host == "api.anthropic.com" || host.endsWith(".anthropic.com")
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.maybeAutoContinueAnthropicBuiltinWebSearch(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.hasAnthropicServerToolUse(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicConversationMessages(
    request: LiteLlmGatewayRequest,
  ): List<LiteLlmGatewayMessage> = projectedConversationMessages(request)

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicToolsArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
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
    if (anthropicStructuredFinalToolSupported(request)) {
      put(anthropicStructuredFinalTool())
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicBuiltinTool(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.applyAnthropicToolControl(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicStructuredFinalTool(): JSONObject = JSONObject()
    .put("name", ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME)
    .put(
      "description",
      "Return the final user-facing answer, optionally referencing existing OpenCray media artifacts.",
    )
    .put("input_schema", structuredFinalSchema())

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicToolChoice(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicMessagesArray(request: LiteLlmProviderRequest): JSONArray = JSONArray().apply {
    val messages = anthropicConversationMessages(request.request)
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicServerToolContinuationMessage(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicUserMessage(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicUserTextMessage(content: String): JSONObject =
    JSONObject()
      .put("role", "user")
      .put("content", content)

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicToolBoundaryUserTurn(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.appendAnthropicMessageBlocks(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicPdfBlock(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicImageBlock(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicAssistantMessage(message: LiteLlmGatewayMessage): JSONObject {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicToolResultMessage(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
    val block = buildAnthropicToolResultBlock(toolResult) ?: return null
    return JSONObject()
      .put("role", "user")
      .put(
        "content",
        JSONArray().put(block),
      )
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicToolResultBlock(toolResult: LiteLlmGatewayToolResult?): JSONObject? {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.buildAnthropicTextBlock(content: String): JSONObject =
    JSONObject()
      .put("type", "text")
      .put("text", content)

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicBuiltinWebSearchObservations(
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
        listOf(builtinWebSearchFallbackQueries(request))
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicWebSearchSources(
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicWebSearchStatus(
    resultContent: Any?,
  ): String = when (resultContent) {
    is JSONObject -> if (resultContent.optString("type") == "web_search_tool_result_error") {
      "error"
    } else {
      "completed"
    }

    else -> "completed"
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicCitationCount(payload: JSONObject): Int {
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

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicResponseShape(
    request: LiteLlmProviderRequest,
    payload: JSONObject,
  ): String {
    val content = payload.optJSONArray("content") ?: return "anthropic_empty"
    var hasText = false
    var hasToolUse = false
    var hasStructuredFinalTool = false
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

        "tool_use" -> if (isExecutableAnthropicToolUse(block)) {
          hasToolUse = true
        } else if (block.optString("name") == ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME) {
          hasStructuredFinalTool = true
        }
      }
    }
    return when {
      hasText && hasToolUse && hasStructuredFinalTool -> "anthropic_text_tool_use_and_structured_final_tool"
      hasToolUse && hasStructuredFinalTool -> "anthropic_tool_use_and_structured_final_tool"
      hasText && hasStructuredFinalTool -> "anthropic_text_and_structured_final_tool"
      hasStructuredFinalTool -> "anthropic_structured_final_tool"
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

internal fun OpenAiCompatibleLiteLlmProviderClient.isExecutableAnthropicToolUse(block: JSONObject): Boolean =
    block.optString("type") == "tool_use" &&
      block.optString("name") != ANTHROPIC_STRUCTURED_FINAL_TOOL_NAME

internal fun OpenAiCompatibleLiteLlmProviderClient.readAnthropicStream(
    input: InputStream,
    streamObserver: LiteLlmVisibleTextObserver,
  ): String {
    val payload = JSONObject()
    val contentBlocks = linkedMapOf<Int, JSONObject>()
    val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
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
        processAnthropicStreamEvent(
          eventName = currentEvent,
          data = dataLines.joinToString(separator = "\n"),
          payload = payload,
          contentBlocks = contentBlocks,
          toolInputBuffers = toolInputBuffers,
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
    val content = JSONArray()
    contentBlocks.toSortedMap().values.forEach(content::put)
    payload.put("content", content)
    return payload.toString()
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.processAnthropicStreamEvent(
    eventName: String,
    data: String,
    payload: JSONObject,
    contentBlocks: MutableMap<Int, JSONObject>,
    toolInputBuffers: MutableMap<Int, StringBuilder>,
    visibleTextCoalescer: VisibleTextSnapshotCoalescer,
  ) {
    val trimmedData = data.trim()
    if (trimmedData.isBlank() || trimmedData == "[DONE]") {
      return
    }
    val eventPayload = runCatching { JSONObject(trimmedData) }.getOrElse { error ->
      throw IllegalStateException("Failed to parse Anthropic streaming event.", error)
    }
    val eventType = eventPayload.optString("type").ifBlank { eventName }
    var visibleTextMayHaveChanged = false
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
        visibleTextMayHaveChanged = normalizedBlock.optString("type") == "text"
      }

      "content_block_delta" -> {
        val index = eventPayload.optInt("index", -1)
        if (index < 0) return
        val delta = eventPayload.optJSONObject("delta") ?: return
        val block = contentBlocks.getOrPut(index) { JSONObject() }
        when (delta.optString("type")) {
          "text_delta" -> {
            appendJsonStringField(block, "text", delta.optString("text"))
            visibleTextMayHaveChanged = true
          }
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
        visibleTextMayHaveChanged = block.optString("type") == "text"
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
    if (visibleTextMayHaveChanged) {
      visibleTextCoalescer.update(anthropicVisibleText(contentBlocks))
    }
  }

internal fun OpenAiCompatibleLiteLlmProviderClient.anthropicVisibleText(
    contentBlocks: Map<Int, JSONObject>,
  ): String = buildString {
    contentBlocks.toSortedMap().values.forEach { block ->
      if (block.optString("type") != "text") {
        return@forEach
      }
      val text = block.optString("text")
      if (text.isNotBlank()) {
        append(text)
      }
    }
  }.trim()

internal fun OpenAiCompatibleLiteLlmProviderClient.shouldDisableAnthropicThinking(
    request: LiteLlmProviderRequest,
  ): Boolean = resolvedProtocol(request) == LlmProviderProtocols.ANTHROPIC &&
    isKimiModel(request.route.model) &&
    !isKimiThinkingModel(request.route.model)

