package com.opencray.app

import android.util.Log
import com.opencray.app.facade.llm.anthropicPromptCacheControl
import com.opencray.app.facade.llm.anthropicPromptCacheRetention
import com.opencray.app.facade.llm.anthropicPromptCacheUsage
import com.opencray.app.facade.llm.anthropicStructuredCompletion
import com.opencray.app.facade.llm.anthropicStructuredFinalToolSupported
import com.opencray.app.facade.llm.buildAnthropicRequestBody
import com.opencray.app.facade.llm.builtinWebSearchObservations
import com.opencray.app.facade.llm.extractAnthropicMessageContent
import com.opencray.app.facade.llm.maybeAutoContinueBuiltinWebSearch
import com.opencray.app.facade.llm.nativeToolCallObserved
import com.opencray.app.facade.llm.nonBlankString
import com.opencray.app.facade.llm.readAnthropicStream
import com.opencray.app.facade.llm.buildOpenAiRequestBody
import com.opencray.app.facade.llm.extractOpenAiMessageContent
import com.opencray.app.facade.llm.openAiBuiltinWebSearchDialect
import com.opencray.app.facade.llm.openAiConversationMessages
import com.opencray.app.facade.llm.openAiPromptCacheKey
import com.opencray.app.facade.llm.openAiPromptCacheRetention
import com.opencray.app.facade.llm.openAiPromptCacheUsage
import com.opencray.app.facade.llm.openAiStructuredCompletion
import com.opencray.app.facade.llm.openAiStructuredFinalSchemaSupported
import com.opencray.app.facade.llm.ProviderStreamErrorException
import com.opencray.app.facade.llm.readOpenAiChatCompletionsStream
import com.opencray.app.facade.llm.buildOpenAiResponsesCompactRequestBody
import com.opencray.app.facade.llm.buildOpenAiResponsesRequestBody
import com.opencray.app.facade.llm.buildResponsesCompactEndpointUrl
import com.opencray.app.facade.llm.deepCopyJsonValue
import com.opencray.app.facade.llm.extractResponsesMessageContent
import com.opencray.app.facade.llm.readOpenAiResponsesStream
import com.opencray.app.facade.llm.responseCitationCount
import com.opencray.app.facade.llm.responseShape
import com.opencray.app.facade.llm.responsesCompactionSummary
import com.opencray.app.facade.llm.responsesMessageTextSegments
import com.opencray.app.facade.llm.responsesRemoteCompactionSupported
import com.opencray.app.facade.llm.responsesStructuredCompletion
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmCompactResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderCompactRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject


internal data class PromptCacheUsageSnapshot(
  val cacheUsed: Boolean,
  val readTokens: Long? = null,
  val writeTokens: Long? = null,
  val write5mTokens: Long? = null,
  val write1hTokens: Long? = null,
  val retention: String? = null,
)


internal data class ToolArgumentsParseResult(
  val arguments: JSONObject,
  val error: String? = null,
)

internal data class StructuredToolCallParseResult(
  val toolCalls: List<LiteLlmStructuredToolCall> = emptyList(),
  val errors: List<String> = emptyList(),
  val rawPreview: String? = null,
)

internal data class SuccessResponseRead(
  val text: String,
  val streamTransportMode: String = STREAM_TRANSPORT_MODE_PLAIN_BODY,
  val streamDowngradeReason: String? = null,
)

internal class OpenAiCompatibleLiteLlmProviderClient(
  private val userAgent: String = OpenCrayUserAgent.providerApi("0"),
  internal val streamUpdateMinIntervalMs: Long = DEFAULT_STREAM_UPDATE_MIN_INTERVAL_MS,
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
    val startedAtEpochMs = System.currentTimeMillis()
    streamDebug(
      "provider.execute protocol=$protocol stream=$streamResponses model=${request.route.model} routeStream=${request.route.metadata["stream"]}",
    )
    providerFlowDebug(
      "provider.executeStart ${request.debugSummary(protocol = protocol, streamResponses = streamResponses)}",
    )
    val requestDiagnostics = requestDiagnosticsMetadata(request)
    if (request.cancelRequested()) {
      return cancelledProviderResult(requestDiagnostics)
    }
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

    val cancellationProbe = request.request.isCancelled
    if (cancellationProbe == null) {
      return executeProviderExchange(
        request = request,
        connection = connection,
        protocol = protocol,
        streamResponses = streamResponses,
        startedAtEpochMs = startedAtEpochMs,
        requestDiagnostics = requestDiagnostics,
      )
    }
    val exchangeExecutor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable).apply {
        isDaemon = true
        name = "opencray-provider-exchange"
      }
    }
    try {
      val exchangeFuture = exchangeExecutor.submit(
        java.util.concurrent.Callable {
          executeProviderExchange(
            request = request,
            connection = connection,
            protocol = protocol,
            streamResponses = streamResponses,
            startedAtEpochMs = startedAtEpochMs,
            requestDiagnostics = requestDiagnostics,
          )
        },
      )
      while (!exchangeFuture.isDone) {
        if (cancellationProbe.invoke()) {
          Thread {
            runCatching { connection.disconnect() }
          }.apply {
            isDaemon = true
            name = "opencray-provider-cancel-disconnect"
            start()
          }
          return cancelledProviderResult(requestDiagnostics)
        }
        Thread.sleep(CANCELLATION_WATCHDOG_POLL_INTERVAL_MS)
      }
      return try {
        exchangeFuture.get()
      } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
      }
    } finally {
      exchangeExecutor.shutdownNow()
      Thread {
        runCatching { connection.disconnect() }
      }.apply {
        isDaemon = true
        name = "opencray-provider-cancel-disconnect"
        start()
      }
    }
  }

  private fun executeProviderExchange(
    request: LiteLlmProviderRequest,
    connection: HttpURLConnection,
    protocol: String,
    streamResponses: Boolean,
    startedAtEpochMs: Long,
    requestDiagnostics: Map<String, String>,
  ): LiteLlmProviderResult {
    var cancellationWatchdog: RequestCancellationWatchdog? = null
    return try {
      cancellationWatchdog = RequestCancellationWatchdog(
        connection = connection,
        cancelRequested = { request.cancelRequested() },
      )
      cancellationWatchdog.start()
      val body = buildRequestBody(request, streamResponses = streamResponses)
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }

      val responseCode = connection.responseCode
      streamDebug(
        "provider.response code=$responseCode protocol=$protocol contentType=${connection.contentType ?: "-"} contentEncoding=${connection.contentEncoding ?: "-"}",
      )
      val successResponse = if (responseCode in 200..299) {
        readSuccessResponse(
          input = connection.inputStream,
          protocol = protocol,
          streamResponses = streamResponses,
          contentType = connection.contentType,
          streamObserver = request.request.streamObserver,
        )
      } else {
        SuccessResponseRead(text = readStream(connection.errorStream))
      }
      val responseText = successResponse.text

      val providerResult = when {
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
            streamRequested = streamResponses,
            successResponse = successResponse,
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
      providerFlowDebug(
        "provider.executeEnd ${request.debugSummary(protocol = protocol, streamResponses = streamResponses)} http=$responseCode durationMs=${System.currentTimeMillis() - startedAtEpochMs} outcome=${providerResult.debugOutcome()}",
      )
      providerResult
    } catch (timeout: java.net.SocketTimeoutException) {
      if (request.cancelRequested()) {
        return cancelledProviderResult(requestDiagnostics)
      }
      streamDebug("provider.timeout protocol=$protocol message=${timeout.message ?: "-"}")
      val providerResult = LiteLlmProviderResult.Timeout(
        errorMessage = timeout.message ?: "Provider request timed out.",
        metadata = requestDiagnostics,
      )
      providerFlowDebug(
        "provider.executeEnd ${request.debugSummary(protocol = protocol, streamResponses = streamResponses)} http=- durationMs=${System.currentTimeMillis() - startedAtEpochMs} outcome=${providerResult.debugOutcome()}",
      )
      providerResult
    } catch (streamError: ProviderStreamErrorException) {
      if (request.cancelRequested()) {
        return cancelledProviderResult(requestDiagnostics)
      }
      streamDebug(
        "provider.streamError protocol=$protocol type=${streamError.providerErrorCode ?: "-"} message=${streamError.message ?: "-"}",
      )
      val providerResult = LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_FAILURE",
        errorMessage = streamError.message ?: "Provider returned an error inside the response stream.",
        metadata = requestDiagnostics + buildMap {
          put(LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_EVENT, "true")
          streamError.providerErrorCode?.let { providerCode ->
            put(LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_TYPE, providerCode)
          }
        },
      )
      providerFlowDebug(
        "provider.executeEnd ${request.debugSummary(protocol = protocol, streamResponses = streamResponses)} http=- durationMs=${System.currentTimeMillis() - startedAtEpochMs} outcome=${providerResult.debugOutcome()}",
      )
      providerResult
    } catch (exception: Exception) {
      if (request.cancelRequested()) {
        return cancelledProviderResult(requestDiagnostics)
      }
      streamDebug(
        "provider.exception protocol=$protocol type=${exception::class.java.name} message=${exception.message ?: "-"}",
      )
      val providerResult = LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_TRANSPORT_ERROR",
        errorMessage = exception.message ?: exception::class.java.simpleName,
        metadata = requestDiagnostics + mapOf("exceptionType" to exception::class.java.name),
      )
      providerFlowDebug(
        "provider.executeEnd ${request.debugSummary(protocol = protocol, streamResponses = streamResponses)} http=- durationMs=${System.currentTimeMillis() - startedAtEpochMs} outcome=${providerResult.debugOutcome()}",
      )
      providerResult
    } finally {
      cancellationWatchdog?.stop()
      connection.disconnect()
    }
  }

  override fun compactConversation(request: LiteLlmProviderCompactRequest): LiteLlmCompactResult {
    val baseUrl = request.route.baseUrl?.trim().orEmpty()
    if (baseUrl.isEmpty()) {
      return LiteLlmCompactResult.Failure(
        errorCode = "PROVIDER_BASE_URL_MISSING",
        errorMessage = "Provider route baseUrl is required.",
        metadata = compactDiagnosticsMetadata(request),
      )
    }
    val protocol = resolvedProtocol(request)
    if (protocol != LlmProviderProtocols.OPENAI_RESPONSES) {
      return LiteLlmCompactResult.Unavailable(
        reason = "protocol_remote_compaction_not_supported",
        metadata = compactDiagnosticsMetadata(request),
      )
    }
    if (!responsesRemoteCompactionSupported(request)) {
      return LiteLlmCompactResult.Unavailable(
        reason = "responses_remote_compaction_not_enabled",
        metadata = compactDiagnosticsMetadata(request),
      )
    }
    invalidToolMessageContract(request.request.gatewayRequest.messages)?.let { validationError ->
      return LiteLlmCompactResult.Failure(
        errorCode = "PROVIDER_COMPACT_REQUEST_INVALID_TOOL_CALL_ID",
        errorMessage = validationError,
        metadata = compactDiagnosticsMetadata(request),
      )
    }
    val endpoint = buildResponsesCompactEndpointUrl(baseUrl)
    val timeoutMs = request.route.timeoutMs
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = timeoutMs.toInt()
      readTimeout = timeoutMs.toInt()
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      request.request.gatewayRequest.authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
      setRequestProperty("User-Agent", userAgent)
    }

    val diagnostics = compactDiagnosticsMetadata(request)
    return try {
      val body = buildOpenAiResponsesCompactRequestBody(request)
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }
      val responseCode = connection.responseCode
      val responseText = if (responseCode in 200..299) {
        readStream(connection.inputStream)
      } else {
        readStream(connection.errorStream)
      }
      when {
        responseCode !in 200..299 -> LiteLlmCompactResult.Failure(
          errorCode = "HTTP_$responseCode",
          errorMessage = extractErrorMessage(responseText).ifBlank {
            "Provider compact endpoint returned HTTP $responseCode."
          },
          metadata = diagnostics + mapOf("statusCode" to responseCode.toString()),
        )

        else -> {
          val payload = JSONObject(responseText)
          val summary = responsesCompactionSummary(payload)
          LiteLlmCompactResult.Success(
            summaryText = summary.summaryText,
            outputItemCount = summary.outputItemCount,
            compactionItemCount = summary.compactionItemCount,
            encryptedContentCount = summary.encryptedContentCount,
            metadata = diagnostics + mapOf(
              "statusCode" to responseCode.toString(),
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_REQUESTED to "true",
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED to "true",
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE to request.request.triggerStage,
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_OUTPUT_ITEM_COUNT to summary.outputItemCount.toString(),
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_ITEM_COUNT to summary.compactionItemCount.toString(),
              LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_ENCRYPTED_CONTENT_COUNT to
                summary.encryptedContentCount.toString(),
            ),
          )
        }
      }
    } catch (timeout: java.net.SocketTimeoutException) {
      LiteLlmCompactResult.Failure(
        errorCode = "PROVIDER_COMPACT_TIMEOUT",
        errorMessage = timeout.message ?: "Provider compact request timed out.",
        metadata = diagnostics,
      )
    } catch (exception: Exception) {
      LiteLlmCompactResult.Failure(
        errorCode = "PROVIDER_COMPACT_TRANSPORT_ERROR",
        errorMessage = exception.message ?: exception::class.java.simpleName,
        metadata = diagnostics + mapOf("exceptionType" to exception::class.java.name),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun LiteLlmProviderRequest.debugSummary(
    protocol: String,
    streamResponses: Boolean,
  ): String {
    val gatewayRequestId = request.requestId
    val source = request.metadata["source"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: gatewayRequestId.substringBefore('-')
    val sessionId = request.metadata["sessionId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "-"
    val taskId = request.metadata["taskId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "-"
    val runId = request.metadata["runId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: gatewayRequestId
        .takeIf { candidate -> candidate.startsWith("agent-") && "-turn-" in candidate }
        ?.removePrefix("agent-")
        ?.substringBefore("-turn-")
      ?: "-"
    val previousResponseId = request.previousResponseId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.take(24)
      ?: "-"
    return buildString {
      append("requestId=")
      append(gatewayRequestId)
      append(" source=")
      append(source)
      append(" session=")
      append(sessionId)
      append(" run=")
      append(runId)
      append(" task=")
      append(taskId)
      append(" protocol=")
      append(protocol)
      append(" stream=")
      append(streamResponses)
      append(" model=")
      append(route.model)
      append(" prev=")
      append(previousResponseId)
      append(" tools=")
      append(request.tools.size)
      append(" builtinTools=")
      append(request.builtinTools.size)
    }
  }

  private fun LiteLlmProviderResult.debugOutcome(): String = when (this) {
    is LiteLlmProviderResult.Success -> buildString {
      append("success")
      providerResponseId?.trim()?.takeIf(String::isNotBlank)?.let { responseId ->
        append(" providerResponseId=")
        append(responseId)
      }
      finishReason?.trim()?.takeIf(String::isNotBlank)?.let { finish ->
        append(" finish=")
        append(finish)
      }
      append(" outputChars=")
      append(outputText.length)
    }

    is LiteLlmProviderResult.Timeout -> buildString {
      append("timeout")
      metadata["statusCode"]?.trim()?.takeIf(String::isNotBlank)?.let { statusCode ->
        append(" statusCode=")
        append(statusCode)
      }
      append(" detail=")
      append(errorMessage)
    }

    is LiteLlmProviderResult.RateLimited -> buildString {
      append("rate_limited")
      retryAfterMs?.let { retryAfter ->
        append(" retryAfterMs=")
        append(retryAfter)
      }
      append(" detail=")
      append(errorMessage)
    }

    is LiteLlmProviderResult.Failure -> buildString {
      append("failure")
      append(" errorCode=")
      append(errorCode)
      providerResponseId?.trim()?.takeIf(String::isNotBlank)?.let { responseId ->
        append(" providerResponseId=")
        append(responseId)
      }
      append(" detail=")
      append(errorMessage)
    }
  }

  private fun LiteLlmProviderRequest.cancelRequested(): Boolean =
    request.isCancelled?.invoke() == true

  private fun cancelledProviderResult(
    diagnostics: Map<String, String>,
  ): LiteLlmProviderResult.Failure = LiteLlmProviderResult.Failure(
    errorCode = PROVIDER_REQUEST_CANCELLED_ERROR_CODE,
    errorMessage = "Provider request was cancelled by the user.",
    metadata = diagnostics,
  )

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
      LlmProviderProtocols.OPENAI_RESPONSES -> buildOpenAiResponsesRequestBody(
        request,
        streamResponses = shouldStreamResponses(request, protocol),
      )
      else -> buildOpenAiRequestBody(
        request,
        streamResponses = shouldStreamResponses(request, protocol),
      )
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
      LlmProviderProtocols.OPENAI_RESPONSES -> buildOpenAiResponsesRequestBody(
        request,
        streamResponses = streamResponses,
      )
      else -> buildOpenAiRequestBody(
        request,
        streamResponses = streamResponses,
      )
    }
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

  internal fun extractOpenAiContentValue(rawContent: Any?): String = when (rawContent) {
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

  internal fun parseToolCallArguments(
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









  internal fun looksLikeProtocolPayload(text: String): Boolean {
    val jsonCandidate = extractEmbeddedJsonObject(text) ?: return false
    val parsed = runCatching { JSONObject(jsonCandidate) }.getOrNull() ?: return false
    val type = parsed.optString("type").trim().lowercase()
    return parsed.optJSONArray("tool_calls") != null ||
      parsed.optString("tool_name").isNotBlank() ||
      parsed.optString("answer").isNotBlank() ||
      (parsed.optJSONArray("attachments")?.length() ?: 0) > 0 ||
      type in setOf("tool_call", "tool", "final", "answer", "progress", "commentary", "status")
  }

  internal fun describeJsonValue(value: Any?): String = when (value) {
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

  private fun structuredCompletion(
    payload: JSONObject,
    protocol: String,
  ): LiteLlmStructuredCompletion? = when (protocol) {
    LlmProviderProtocols.ANTHROPIC -> anthropicStructuredCompletion(payload)
    LlmProviderProtocols.OPENAI_RESPONSES -> responsesStructuredCompletion(payload)
    else -> openAiStructuredCompletion(payload)
  }



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
    streamRequested: Boolean,
    successResponse: SuccessResponseRead,
  ): Map<String, String> = buildMap {
    put("statusCode", statusCode.toString())
    put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, nativeToolCallRequested.toString())
    put(
      "conversationTransportMode",
      if (request.request.messages.isNotEmpty()) "messages" else "prompt_projection",
    )
    put(LiteLlmMetadataKeys.STREAM_REQUESTED, streamRequested.toString())
    put(LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE, successResponse.streamTransportMode)
    successResponse.streamDowngradeReason?.let { downgradeReason ->
      put(LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON, downgradeReason)
    }
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
    if (openAiStructuredFinalSchemaSupported(request)) {
      put(LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED, "true")
    }
    if (anthropicStructuredFinalToolSupported(request)) {
      put(LlmStructuredFinalMetadataKeys.ANTHROPIC_STRUCTURED_FINAL_TOOL_SUPPORTED, "true")
    }
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
    putAll(promptCacheRequestMetadata(request))
    putAll(promptCacheUsageMetadata(payload = payload, protocol = protocol))
  }

  private fun promptCacheRequestMetadata(
    request: LiteLlmProviderRequest,
  ): Map<String, String> = buildMap {
    openAiPromptCacheKey(request)?.let {
      put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_KEY_PRESENT, "true")
    }
    openAiPromptCacheRetention(request)?.let { retention ->
      put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION, retention)
    }
    anthropicPromptCacheControl(request)?.let {
      put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_CONTROL_PRESENT, "true")
    }
    anthropicPromptCacheRetention(request)?.let { retention ->
      put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION, retention)
    }
  }

  private fun promptCacheUsageMetadata(
    payload: JSONObject,
    protocol: String,
  ): Map<String, String> {
    val usage = when (protocol) {
      LlmProviderProtocols.ANTHROPIC -> anthropicPromptCacheUsage(payload)
      LlmProviderProtocols.OPENAI,
      LlmProviderProtocols.OPENAI_RESPONSES,
      -> openAiPromptCacheUsage(payload)
      else -> null
    } ?: return emptyMap()
    return buildMap {
      put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED, usage.cacheUsed.toString())
      usage.readTokens?.let { tokens ->
        put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS, tokens.toString())
      }
      usage.writeTokens?.let { tokens ->
        put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_TOKENS, tokens.toString())
      }
      usage.write5mTokens?.let { tokens ->
        put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_5M_TOKENS, tokens.toString())
      }
      usage.write1hTokens?.let { tokens ->
        put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_1H_TOKENS, tokens.toString())
      }
      usage.retention?.takeIf(String::isNotBlank)?.let { value ->
        put(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION, value)
      }
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
    put(
      "conversationTransportMode",
      if (request.request.messages.isNotEmpty()) "messages" else "prompt_projection",
    )
    putAll(promptCacheRequestMetadata(request))
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

  internal fun providerHost(request: LiteLlmProviderRequest): String = runCatching {
    URI(request.route.baseUrl?.trim().orEmpty()).host.orEmpty().lowercase()
  }.getOrDefault("")

  internal fun resolvedStructuredFinalMetadataValue(
    request: LiteLlmProviderRequest,
    key: String,
  ): String? = request.request.metadata[key]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: request.route.metadata[key]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  internal fun resolvedPromptCachingMetadataValue(
    request: LiteLlmProviderRequest,
    key: String,
  ): String? = request.request.metadata[key]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: request.route.metadata[key]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun resolvedProtocol(request: LiteLlmProviderCompactRequest): String =
    LlmProviderProtocols.normalize(request.route.metadata["protocol"])


  private fun compactDiagnosticsMetadata(
    request: LiteLlmProviderCompactRequest,
  ): Map<String, String> = buildMap {
    put("providerId", request.route.providerId)
    put("model", request.route.model)
    put("routeId", request.route.id)
    put("protocol", resolvedProtocol(request))
    put(LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_REQUESTED, "true")
    put(
      LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_SUPPORTED,
      responsesRemoteCompactionSupported(request).toString(),
    )
    request.request.triggerStage
      .trim()
      .takeIf(String::isNotBlank)
      ?.let { triggerStage ->
        put(LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE, triggerStage)
      }
  }










  internal fun serializedToolResultContent(result: LiteLlmGatewayToolResult): String {
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

  private fun extractErrorMessage(responseText: String): String = runCatching {
    val errorObject = JSONObject(responseText).optJSONObject("error")
    errorObject?.optString("message")?.takeIf { it.isNotBlank() } ?: responseText
  }.getOrDefault(responseText)

  internal fun parseRetryAfterMillis(
    rawValue: String?,
    clockEpochMs: Long = System.currentTimeMillis(),
  ): Long? {
    val trimmed = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
    trimmed.toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L) * 1_000L }
    return retryAfterHttpDateMillis(trimmed, clockEpochMs)
  }

  private fun retryAfterHttpDateMillis(httpDate: String, clockEpochMs: Long): Long? {
    val expiresAtEpochMs = runCatching {
      ZonedDateTime.parse(httpDate, RETRY_AFTER_HTTP_DATE_FORMATTER).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    return (expiresAtEpochMs - clockEpochMs).coerceAtLeast(0L)
  }

  private fun readSuccessResponse(
    input: InputStream?,
    protocol: String,
    streamResponses: Boolean,
    contentType: String?,
    streamObserver: LiteLlmVisibleTextObserver,
  ): SuccessResponseRead {
    if (input == null) {
      return SuccessResponseRead(
        text = "",
        streamTransportMode = STREAM_TRANSPORT_MODE_PLAIN_BODY,
        streamDowngradeReason = streamDowngradeReason(
          streamResponses = streamResponses,
          contentType = contentType,
        ),
      )
    }
    val normalizedInput = if (input is BufferedInputStream) {
      input
    } else {
      BufferedInputStream(input)
    }
    if (
      streamResponses &&
      shouldTreatSuccessResponseAsEventStream(
        input = normalizedInput,
        contentType = contentType,
      )
    ) {
      streamDebug(
        "provider.readSuccessResponse protocol=$protocol stream=true contentType=$contentType mode=event_stream",
      )
      val text = when (protocol) {
        LlmProviderProtocols.ANTHROPIC -> readAnthropicStream(
          input = normalizedInput,
          streamObserver = streamObserver,
        )

        LlmProviderProtocols.OPENAI_RESPONSES -> readOpenAiResponsesStream(
          input = normalizedInput,
          streamObserver = streamObserver,
        )

        else -> readOpenAiChatCompletionsStream(
          input = normalizedInput,
          streamObserver = streamObserver,
        )
      }
      return SuccessResponseRead(
        text = text,
        streamTransportMode = STREAM_TRANSPORT_MODE_SSE,
      )
    }
    streamDebug(
      "provider.readSuccessResponse protocol=$protocol stream=$streamResponses contentType=$contentType mode=plain_body",
    )
    return SuccessResponseRead(
      text = readStream(normalizedInput),
      streamTransportMode = STREAM_TRANSPORT_MODE_PLAIN_BODY,
      streamDowngradeReason = streamDowngradeReason(
        streamResponses = streamResponses,
        contentType = contentType,
      ),
    )
  }

  private fun streamDowngradeReason(
    streamResponses: Boolean,
    contentType: String?,
  ): String? {
    if (!streamResponses) {
      return null
    }
    return if (contentType.isNullOrBlank()) {
      STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM_CONTENT_TYPE_MISSING
    } else {
      STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM
    }
  }

  private fun shouldTreatSuccessResponseAsEventStream(
    input: BufferedInputStream,
    contentType: String?,
  ): Boolean {
    if (contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
      return true
    }
    input.mark(STREAM_SNIFF_LIMIT_BYTES)
    val buffer = ByteArray(STREAM_SNIFF_LIMIT_BYTES)
    val bytesRead = runCatching { input.read(buffer) }.getOrDefault(-1)
    input.reset()
    if (bytesRead <= 0) {
      return false
    }
    val preview = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
    val firstSignalLine = preview
      .lineSequence()
      .map(String::trimStart)
      .firstOrNull { line -> line.isNotBlank() }
      ?: return false
    return firstSignalLine.startsWith("event:") ||
      firstSignalLine.startsWith("data:") ||
      firstSignalLine.startsWith(":")
  }











































  internal fun appendJsonStringField(
    target: JSONObject,
    key: String,
    value: String?,
  ) {
    val delta = value?.takeIf(String::isNotEmpty) ?: return
    val existing = target.optString(key)
    target.put(key, existing + delta)
  }


  internal fun copyJsonFieldIfPresent(
    source: JSONObject,
    target: JSONObject,
    key: String,
  ) {
    if (!source.has(key)) {
      return
    }
    val copiedValue = deepCopyJsonValue(source.opt(key)) ?: return
    target.put(key, copiedValue)
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

  internal fun isKimiModel(model: String?): Boolean =
    model?.trim()?.lowercase()?.contains("kimi") == true

  internal fun isKimiThinkingModel(model: String?): Boolean {
    val normalized = model?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) {
      return false
    }
    return normalized.contains("kimi") && normalized.contains("thinking")
  }

  internal fun resolvedProtocol(request: LiteLlmProviderRequest): String =
    LlmProviderProtocols.normalize(request.route.metadata["protocol"])

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

  internal fun requireToolCallId(
    toolCall: LiteLlmStructuredToolCall,
    location: String,
  ): String = toolCall.id?.trim()?.takeIf(String::isNotBlank)
    ?: error("$location id must be present for provider-native tool calling.")

  internal fun requireToolResultCallId(
    toolResult: LiteLlmGatewayToolResult,
    location: String,
  ): String = toolResult.toolCallId?.trim()?.takeIf(String::isNotBlank)
    ?: error("$location toolCallId must be present for provider-native tool calling.")

  internal fun firstNonBlankString(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() }

  internal fun visibleAssistantDraftText(rawText: String): String? {
    val normalized = rawText.trim().takeIf(String::isNotBlank) ?: return null
    val startsLikeJson = normalized.startsWith('{') || normalized.startsWith('[')
    val lowercase = normalized.lowercase()
    val looksLikeStructuredProtocol =
      startsLikeJson && (
        "\"type\"" in lowercase ||
          "\"decision\"" in lowercase ||
          "\"actions\"" in lowercase ||
          "\"tool_name\"" in lowercase ||
          "\"tool_calls\"" in lowercase ||
          "\"function_call\"" in lowercase ||
          "\"call_id\"" in lowercase ||
          "\"arguments\"" in lowercase
        )
    val looksLikeInternalSignal =
      startsLikeJson && (
        "\"is_task_bearing_request\"" in lowercase ||
          "\"user_affect\"" in lowercase ||
          "\"user_invites_playfulness\"" in lowercase ||
          "\"user_requests_relational_support\"" in lowercase ||
          "\"clarification_needed\"" in lowercase
        )
    if (!startsLikeJson) {
      return normalized
    }
    extractStructuredAssistantDraftText(normalized)?.let { return it }
    if (looksLikeStructuredProtocol || looksLikeInternalSignal) {
      return null
    }
    if (!normalized.endsWith('}') && !normalized.endsWith(']')) {
      return null
    }
    return normalized
  }

  private fun extractStructuredAssistantDraftText(rawText: String): String? {
    val lowercase = rawText.lowercase()
    val hasExplicitTypeField = "\"type\"" in lowercase || "\"decision\"" in lowercase
    if ("\"actions\"" in lowercase) {
      extractStructuredActionsDraftText(rawText)?.let { return it }
    }
    if (containsStructuredAssistantExecutionSignal(lowercase)) {
      return null
    }
    val actionType = structuredAssistantDraftActionType(rawText)
    return when (actionType) {
      "final",
      "answer",
      -> firstNonBlankString(
        partialJsonStringFieldValue(rawText, "answer"),
        partialJsonStringFieldValue(rawText, "text"),
        partialJsonStringFieldValue(rawText, "message"),
        partialJsonStringFieldValue(rawText, "summary"),
      )?.trim()?.takeIf(String::isNotBlank)

      "progress",
      "commentary",
      "status",
      -> firstNonBlankString(
        partialJsonStringFieldValue(rawText, "text"),
        partialJsonStringFieldValue(rawText, "summary"),
        partialJsonStringFieldValue(rawText, "message"),
      )?.trim()?.takeIf(String::isNotBlank)

      null,
      "",
      -> if (hasExplicitTypeField) {
        partialJsonStringFieldValue(rawText, "answer")
          ?.trim()
          ?.takeIf(String::isNotBlank)
      } else {
        null
      }

      else -> null
    }
  }

  private fun extractStructuredActionsDraftText(rawText: String): String? {
    val actions = partialJsonObjectFieldArrayElements(rawText, "actions")
    if (actions.isEmpty()) {
      return null
    }
    val hasExecutionAction = actions.any(::structuredAssistantActionSuppressesFinalDraft)
    return actions
      .mapNotNull { rawAction ->
        val visibleText = extractStructuredAssistantDraftTextFromAction(rawAction) ?: return@mapNotNull null
        if (hasExecutionAction && isStructuredAssistantFinalAction(rawAction)) {
          return@mapNotNull null
        }
        visibleText
      }
      .lastOrNull()
  }

  private fun extractStructuredAssistantDraftTextFromAction(rawAction: String): String? {
    val actionType = structuredAssistantDraftActionType(rawAction)
    return when (actionType) {
      "final",
      "answer",
      -> firstNonBlankString(
        partialJsonStringFieldValue(rawAction, "answer"),
        partialJsonStringFieldValue(rawAction, "text"),
        partialJsonStringFieldValue(rawAction, "message"),
        partialJsonStringFieldValue(rawAction, "summary"),
      )?.trim()?.takeIf(String::isNotBlank)

      "progress",
      "commentary",
      "status",
      -> firstNonBlankString(
        partialJsonStringFieldValue(rawAction, "text"),
        partialJsonStringFieldValue(rawAction, "summary"),
        partialJsonStringFieldValue(rawAction, "message"),
      )?.trim()?.takeIf(String::isNotBlank)

      else -> null
    }
  }

  private fun structuredAssistantDraftActionType(rawText: String): String? = firstNonBlankString(
    partialJsonStringFieldValue(rawText, "type")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
    partialJsonStringFieldValue(rawText, "decision")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
  )

  private fun isStructuredAssistantFinalAction(rawText: String): Boolean =
    structuredAssistantDraftActionType(rawText) in setOf("final", "answer")

  private fun structuredAssistantActionSuppressesFinalDraft(rawAction: String): Boolean {
    val lowercase = rawAction.lowercase()
    if (containsStructuredAssistantExecutionSignal(lowercase)) {
      return true
    }
    return when (structuredAssistantDraftActionType(rawAction)) {
      null,
      "",
      "final",
      "answer",
      "progress",
      "commentary",
      "status",
      -> false

      else -> true
    }
  }

  private fun containsStructuredAssistantExecutionSignal(lowercase: String): Boolean =
    containsStructuredAssistantToolSignal(lowercase) ||
      "\"is_task_bearing_request\"" in lowercase ||
      "\"user_affect\"" in lowercase ||
      "\"user_invites_playfulness\"" in lowercase ||
      "\"user_requests_relational_support\"" in lowercase ||
      "\"clarification_needed\"" in lowercase

  private fun containsStructuredAssistantToolSignal(lowercase: String): Boolean =
    "\"tool_name\"" in lowercase ||
      "\"tool_calls\"" in lowercase ||
      "\"function_call\"" in lowercase ||
      "\"call_id\"" in lowercase ||
      "\"arguments\"" in lowercase

  private fun partialJsonObjectFieldArrayElements(
    rawText: String,
    fieldName: String,
  ): List<String> {
    val fieldPattern = "\"$fieldName\""
    var searchFrom = 0
    var keyIndex = -1
    while (searchFrom < rawText.length) {
      val candidateIndex = rawText.indexOf(fieldPattern, searchFrom)
      if (candidateIndex < 0) {
        return emptyList()
      }
      if (isTopLevelPartialJsonObjectKey(rawText = rawText, keyIndex = candidateIndex)) {
        keyIndex = candidateIndex
        break
      }
      searchFrom = candidateIndex + fieldPattern.length
    }
    var index = keyIndex + fieldPattern.length
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != ':') {
      return emptyList()
    }
    index += 1
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != '[') {
      return emptyList()
    }
    index += 1
    val elements = mutableListOf<String>()
    var objectStart = -1
    var objectDepth = 0
    var inString = false
    var escaped = false
    while (index < rawText.length) {
      val character = rawText[index]
      if (inString) {
        if (escaped) {
          escaped = false
        } else {
          when (character) {
            '\\' -> escaped = true
            '"' -> inString = false
          }
        }
        index += 1
        continue
      }
      when (character) {
        '"' -> inString = true
        '{' -> {
          if (objectDepth == 0) {
            objectStart = index
          }
          objectDepth += 1
        }
        '}' -> {
          if (objectDepth > 0) {
            objectDepth -= 1
            if (objectDepth == 0 && objectStart >= 0) {
              elements += rawText.substring(objectStart, index + 1)
              objectStart = -1
            }
          }
        }
        ']' -> {
          if (objectDepth == 0) {
            return elements
          }
        }
      }
      index += 1
    }
    if (objectStart >= 0) {
      elements += rawText.substring(objectStart)
    }
    return elements
  }

  private fun isTopLevelPartialJsonObjectKey(
    rawText: String,
    keyIndex: Int,
  ): Boolean {
    var objectDepth = 0
    var arrayDepth = 0
    var inString = false
    var escaped = false
    for (index in 0 until keyIndex) {
      val character = rawText[index]
      if (inString) {
        if (escaped) {
          escaped = false
        } else {
          when (character) {
            '\\' -> escaped = true
            '"' -> inString = false
          }
        }
        continue
      }
      when (character) {
        '"' -> inString = true
        '{' -> objectDepth += 1
        '}' -> if (objectDepth > 0) {
          objectDepth -= 1
        }
        '[' -> arrayDepth += 1
        ']' -> if (arrayDepth > 0) {
          arrayDepth -= 1
        }
      }
    }
    return objectDepth == 1 && arrayDepth == 0 && !inString
  }

  private fun partialJsonStringFieldValue(
    rawText: String,
    fieldName: String,
  ): String? {
    val fieldPattern = "\"$fieldName\""
    var searchStart = 0
    while (true) {
      val keyIndex = rawText.indexOf(fieldPattern, startIndex = searchStart)
      if (keyIndex < 0) {
        return null
      }
      var index = keyIndex + fieldPattern.length
      while (index < rawText.length && rawText[index].isWhitespace()) {
        index += 1
      }
      if (index >= rawText.length || rawText[index] != ':') {
        searchStart = keyIndex + fieldPattern.length
        continue
      }
      index += 1
      while (index < rawText.length && rawText[index].isWhitespace()) {
        index += 1
      }
      if (index >= rawText.length || rawText[index] != '"') {
        return null
      }
      index += 1
      val builder = StringBuilder()
      var escaped = false
      while (index < rawText.length) {
        val character = rawText[index]
        if (escaped) {
          builder.append(
            when (character) {
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              '\\',
              '"',
              '/',
              -> character
              else -> character
            },
          )
          escaped = false
          index += 1
          continue
        }
        when (character) {
          '\\' -> {
            escaped = true
            index += 1
          }

          '"' -> return builder.toString()
          else -> {
            builder.append(character)
            index += 1
          }
        }
      }
      return builder.toString()
    }
  }


  companion object {
    private const val DEFAULT_STREAM_UPDATE_MIN_INTERVAL_MS: Long = 24L
    private val RETRY_AFTER_HTTP_DATE_FORMATTER =
      DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
    private const val STREAM_SNIFF_LIMIT_BYTES: Int = 2_048
    internal const val MAX_INLINE_IMAGE_BYTES: Long = 20L * 1024L * 1024L
    internal const val MAX_INLINE_PDF_BYTES: Long = 32L * 1024L * 1024L
    internal const val STRUCTURED_FINAL_SCHEMA_NAME: String = "opencray_final_response"
    private const val KIMI_BUILTIN_WEB_SEARCH_FUNCTION_NAME: String = "\$web_search"
    private const val KIMI_BUILTIN_WEB_SEARCH_LOOP_DEPTH_KEY: String = "_host.kimiBuiltinWebSearchLoopDepth"
    private const val MAX_KIMI_BUILTIN_WEB_SEARCH_AUTO_TURNS: Int = 4
    internal val JSON_CODEC: Json = Json { ignoreUnknownKeys = true }

    internal fun providerUserAgent(versionName: String): String =
      OpenCrayUserAgent.providerApi(versionName)
  }

  internal fun streamDebug(message: String) {
    runCatching { Log.d(STREAM_DEBUG_TAG, message) }
  }
}

internal fun extractEmbeddedJsonObject(raw: String): String? {
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

internal class VisibleTextSnapshotCoalescer(
  private val observer: LiteLlmVisibleTextObserver,
  private val minIntervalMs: Long,
  private val normalizer: (String) -> String? = { text ->
    text.trim().takeIf(String::isNotBlank)
  },
) {
  private var lastEmittedText: String? = null
  private var lastEmittedAtEpochMs: Long = 0L
  private var pendingText: String? = null

  fun update(text: String) {
    val normalized = normalizer(text) ?: return
    pendingText = normalized
    emitIfEligible(force = lastEmittedText == null)
  }

  fun flush() {
    emitIfEligible(force = true)
  }

  private fun emitIfEligible(force: Boolean) {
    val text = pendingText ?: return
    if (text == lastEmittedText) {
      pendingText = null
      return
    }
    val now = System.currentTimeMillis()
    if (!force && minIntervalMs > 0L && now - lastEmittedAtEpochMs < minIntervalMs) {
      return
    }
    runCatching {
      Log.d(
        STREAM_DEBUG_TAG,
        "provider.visibleDraft len=${text.length}",
      )
    }
    observer.onVisibleTextSnapshot(text)
    lastEmittedText = text
    lastEmittedAtEpochMs = now
    pendingText = null
  }
}

private const val STREAM_DEBUG_TAG: String = "OpenCrayStream"

internal const val STREAM_TRANSPORT_MODE_SSE: String = "sse"

internal const val STREAM_TRANSPORT_MODE_PLAIN_BODY: String = "plain_body"

internal const val STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM: String = "provider_returned_non_event_stream"

internal const val STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM_CONTENT_TYPE_MISSING: String =
  "provider_returned_non_event_stream_content_type_missing"

internal const val PROVIDER_REQUEST_CANCELLED_ERROR_CODE: String = "PROVIDER_REQUEST_CANCELLED"

private const val CANCELLATION_WATCHDOG_POLL_INTERVAL_MS: Long = 250L

internal class RequestCancellationWatchdog(
  private val connection: HttpURLConnection,
  private val cancelRequested: () -> Boolean,
  private val pollIntervalMs: Long = CANCELLATION_WATCHDOG_POLL_INTERVAL_MS,
) {
  private val stopped = AtomicBoolean(false)
  private val thread = Thread {
    try {
      while (!stopped.get()) {
        if (cancelRequested()) {
          runCatching { connection.disconnect() }
          return@Thread
        }
        Thread.sleep(pollIntervalMs)
      }
    } catch (_: InterruptedException) {
    }
  }

  fun start() {
    thread.isDaemon = true
    thread.name = "OpenCrayLlmCancelWatchdog"
    thread.start()
  }

  fun stop() {
    stopped.set(true)
    thread.interrupt()
  }
}

private const val PROVIDER_FLOW_DEBUG_TAG: String = "OpenCrayDiag"

private fun providerFlowDebug(message: String) {
  runCatching { Log.d(PROVIDER_FLOW_DEBUG_TAG, message) }
}
