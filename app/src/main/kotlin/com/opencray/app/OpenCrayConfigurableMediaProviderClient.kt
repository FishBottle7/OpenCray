package com.opencray.app

import com.opencray.runtime.OpenCrayBinaryAsset
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayImageGenerationRequest
import com.opencray.runtime.OpenCrayImageGenerationResponse
import com.opencray.runtime.OpenCrayMediaJobClient
import com.opencray.runtime.OpenCrayMediaJobPollResult
import com.opencray.runtime.OpenCrayMediaJobReceipt
import com.opencray.runtime.OpenCrayMediaJobSnapshot
import com.opencray.runtime.OpenCrayMediaJobStatus
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisRequest
import com.opencray.runtime.OpenCraySpeechSynthesisResponse
import com.opencray.runtime.OpenCrayVideoGenerationClient
import com.opencray.runtime.OpenCrayVideoGenerationRequest
import com.opencray.runtime.OpenCrayVideoGenerationResponse
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class OpenCrayConfigurableMediaProviderClient(
  private val userAgent: String = OpenCrayUserAgent.providerApi("0"),
) : OpenCrayImageGenerationClient,
  OpenCrayVideoGenerationClient,
  OpenCraySpeechSynthesisClient,
  OpenCrayMediaJobClient {
  override fun generate(
    request: OpenCrayImageGenerationRequest,
    cancellationRequested: () -> Boolean,
  ): OpenCrayImageGenerationResponse {
    val endpoint = buildEndpointUrl(
      baseUrl = request.settings.baseUrl,
      endpoint = request.settings.endpoint,
    )
    val usesChatCompletions = isOpenAiCompatibleChatCompletionsEndpoint(endpoint)
    val response = executeJsonPost(
      endpoint = endpoint,
      body = if (usesChatCompletions) {
        buildOpenAiCompatibleChatImageRequestBody(request).toString()
      } else {
        buildImageRequestBody(request).toString()
      },
      authHeaders = request.settings.authHeaders,
      trustedBaseUrl = request.settings.baseUrl,
      cancellationRequested = cancellationRequested,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith("image/") == true) {
      return OpenCrayImageGenerationResponse(
        images = listOf(
          response.toDirectBinaryAsset(contentType),
        ),
        providerRequestId = response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObjectOrEmpty(response)
    parsePendingJob(
      payload = payload,
      response = response,
      toolName = TOOL_NAME_GENERATE_IMAGE,
    )?.let { pendingJob ->
      return OpenCrayImageGenerationResponse(
        providerRequestId = pendingJob.providerRequestId ?: response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
        pendingJob = pendingJob,
      )
    }
    val images = if (usesChatCompletions) {
      parseChatCompletionImageAssetList(
        payload = payload,
        providerBaseUrl = request.settings.baseUrl,
        authHeaders = request.settings.authHeaders,
        cancellationRequested = cancellationRequested,
      )
    } else {
      emptyList()
    }.ifEmpty {
      parseBinaryAssetList(
        payload = payload,
        providerBaseUrl = request.settings.baseUrl,
        authHeaders = request.settings.authHeaders,
        cancellationRequested = cancellationRequested,
        arrayKeys = listOf("images", "data", "outputs", "artifacts", "files"),
        singleKeys = listOf("image", "output", "file"),
      )
    }
    if (images.isEmpty()) {
      throw IllegalStateException("Image provider returned no images.")
    }
    return OpenCrayImageGenerationResponse(
      images = images.take(request.count),
      providerRequestId = payload.optString("id").ifBlank {
        payload.optString("request_id")
      }.ifBlank {
        response.providerRequestId().orEmpty()
      }.ifBlank { null },
      metadata = buildMap {
        put("statusCode", response.statusCode.toString())
        if (usesChatCompletions) {
          put("protocol", "openai_chat_completions")
        }
      },
    )
  }

  override fun generateVideo(
    request: OpenCrayVideoGenerationRequest,
    cancellationRequested: () -> Boolean,
  ): OpenCrayVideoGenerationResponse {
    val endpoint = buildEndpointUrl(
      baseUrl = request.settings.baseUrl,
      endpoint = request.settings.endpoint,
    )
    val response = executeJsonPost(
      endpoint = endpoint,
      body = buildVideoRequestBody(request).toString(),
      authHeaders = request.settings.authHeaders,
      trustedBaseUrl = request.settings.baseUrl,
      cancellationRequested = cancellationRequested,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith("video/") == true) {
      return OpenCrayVideoGenerationResponse(
        videos = listOf(
          response.toDirectBinaryAsset(contentType),
        ),
        providerRequestId = response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObjectOrEmpty(response)
    parsePendingJob(
      payload = payload,
      response = response,
      toolName = TOOL_NAME_GENERATE_VIDEO,
    )?.let { pendingJob ->
      return OpenCrayVideoGenerationResponse(
        providerRequestId = pendingJob.providerRequestId ?: response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
        pendingJob = pendingJob,
      )
    }
    val videos = parseBinaryAssetList(
      payload = payload,
      providerBaseUrl = request.settings.baseUrl,
      authHeaders = request.settings.authHeaders,
      cancellationRequested = cancellationRequested,
      arrayKeys = listOf("videos", "data", "outputs", "artifacts", "files"),
      singleKeys = listOf("video", "output", "file"),
    )
    if (videos.isEmpty()) {
      throw IllegalStateException("Video provider returned no video payload.")
    }
    return OpenCrayVideoGenerationResponse(
      videos = videos,
      providerRequestId = payload.optString("id").ifBlank {
        payload.optString("request_id")
      }.ifBlank {
        response.providerRequestId().orEmpty()
      }.ifBlank { null },
      metadata = mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  override fun synthesize(
    request: OpenCraySpeechSynthesisRequest,
    cancellationRequested: () -> Boolean,
  ): OpenCraySpeechSynthesisResponse {
    val endpoint = buildEndpointUrl(
      baseUrl = request.settings.baseUrl,
      endpoint = request.settings.endpoint,
    )
    val response = executeJsonPost(
      endpoint = endpoint,
      body = buildSpeechRequestBody(request).toString(),
      authHeaders = request.settings.authHeaders,
      trustedBaseUrl = request.settings.baseUrl,
      cancellationRequested = cancellationRequested,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith("audio/") == true) {
      return OpenCraySpeechSynthesisResponse(
        audio = response.toDirectBinaryAsset(contentType),
        providerRequestId = response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObjectOrEmpty(response)
    parsePendingJob(
      payload = payload,
      response = response,
      toolName = TOOL_NAME_SYNTHESIZE_SPEECH,
    )?.let { pendingJob ->
      return OpenCraySpeechSynthesisResponse(
        providerRequestId = pendingJob.providerRequestId ?: response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
        pendingJob = pendingJob,
      )
    }
    val audio = parseBinaryAssetList(
      payload = payload,
      providerBaseUrl = request.settings.baseUrl,
      authHeaders = request.settings.authHeaders,
      cancellationRequested = cancellationRequested,
      arrayKeys = listOf("audio", "data", "outputs", "files"),
      singleKeys = listOf("audio", "file", "output", "data"),
    ).firstOrNull()
      ?: throw IllegalStateException("Speech provider returned no audio payload.")
    return OpenCraySpeechSynthesisResponse(
      audio = audio,
      providerRequestId = payload.optString("id").ifBlank {
        payload.optString("request_id")
      }.ifBlank {
        response.providerRequestId().orEmpty()
      }.ifBlank { null },
      durationMs = payload.optLong("duration_ms").takeIf { it > 0L }
        ?: payload.optLong("durationMs").takeIf { it > 0L },
      transcriptText = payload.optString("transcript_text").ifBlank {
        payload.optString("transcript")
      }.ifBlank {
        payload.optString("text")
      }.ifBlank { null },
      metadata = mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  override fun poll(
    job: OpenCrayMediaJobSnapshot,
    settings: OpenCrayMediaToolSettings,
    cancellationRequested: () -> Boolean,
  ): OpenCrayMediaJobPollResult {
    val toolKind = toolKindFor(job.receipt.toolName)
    val endpoint = pollEndpointFor(job, toolKind, settings)
    ensureEndpointOriginAllowed(
      endpoint = endpoint,
      job = job,
      toolKind = toolKind,
      settings = settings,
    )
    val response = executeRequest(
      endpoint = endpoint,
      method = "GET",
      authHeaders = toolKind.authHeaders(settings),
      trustedBaseUrl = toolKind.baseUrl(settings),
      cancellationRequested = cancellationRequested,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith(toolKind.directContentPrefix) == true) {
      return OpenCrayMediaJobPollResult(
        snapshot = job.completed(
          providerRequestId = response.providerRequestId(),
          metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
        ),
        images = if (toolKind == MediaToolKind.IMAGE) listOf(response.toDirectBinaryAsset(contentType)) else emptyList(),
        videos = if (toolKind == MediaToolKind.VIDEO) listOf(response.toDirectBinaryAsset(contentType)) else emptyList(),
        audio = if (toolKind == MediaToolKind.SPEECH) response.toDirectBinaryAsset(contentType) else null,
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObjectOrEmpty(response)
    val pendingJob = parsePendingJob(
      payload = payload,
      response = response,
      toolName = job.receipt.toolName,
      previousSnapshot = job,
    )
    if (pendingJob != null) {
      return OpenCrayMediaJobPollResult(
        snapshot = pendingJob,
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }
    if (isCancelledStatus(payload)) {
      return OpenCrayMediaJobPollResult(
        snapshot = job.cancelled(
          providerRequestId = response.providerRequestId(),
          metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
        ),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val assets = parseBinaryAssetList(
      payload = payload,
      providerBaseUrl = toolKind.baseUrl(settings),
      authHeaders = toolKind.authHeaders(settings),
      cancellationRequested = cancellationRequested,
      arrayKeys = toolKind.arrayKeys,
      singleKeys = toolKind.singleKeys,
    )
    if (toolKind == MediaToolKind.SPEECH) {
      val audio = assets.firstOrNull()
        ?: throw IllegalStateException("Speech provider returned no audio payload.")
      return OpenCrayMediaJobPollResult(
        snapshot = job.completed(
          providerRequestId = payload.providerRequestId().ifBlank { response.providerRequestId().orEmpty() }.ifBlank { null },
          metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
        ),
        audio = audio,
        durationMs = payload.optLong("duration_ms").takeIf { it > 0L }
          ?: payload.optLong("durationMs").takeIf { it > 0L },
        transcriptText = payload.optString("transcript_text").ifBlank {
          payload.optString("transcript")
        }.ifBlank {
          payload.optString("text")
        }.ifBlank { null },
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }
    if (assets.isEmpty()) {
      throw IllegalStateException("${toolKind.label} provider returned no completed media payload.")
    }
    return OpenCrayMediaJobPollResult(
      snapshot = job.completed(
        providerRequestId = payload.providerRequestId().ifBlank { response.providerRequestId().orEmpty() }.ifBlank { null },
        metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
      ),
      images = if (toolKind == MediaToolKind.IMAGE) assets else emptyList(),
      videos = if (toolKind == MediaToolKind.VIDEO) assets else emptyList(),
      metadata = mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  override fun cancel(
    job: OpenCrayMediaJobSnapshot,
    settings: OpenCrayMediaToolSettings,
    cancellationRequested: () -> Boolean,
  ): OpenCrayMediaJobSnapshot {
    val toolKind = toolKindFor(job.receipt.toolName)
    val endpoint = cancelEndpointFor(job, toolKind, settings)
    ensureEndpointOriginAllowed(
      endpoint = endpoint,
      job = job,
      toolKind = toolKind,
      settings = settings,
    )
    val response = executeRequest(
      endpoint = endpoint,
      method = job.metadata[CANCEL_METHOD_METADATA_KEY].orEmpty().ifBlank { "POST" },
      authHeaders = toolKind.authHeaders(settings),
      trustedBaseUrl = toolKind.baseUrl(settings),
      cancellationRequested = cancellationRequested,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    if (response.body.isEmpty()) {
      parsePendingJob(
        payload = JSONObject(),
        response = response,
        toolName = job.receipt.toolName,
        previousSnapshot = job,
      )?.let { return it }
      return if (response.statusCode == 202) {
        job.pending(
          providerRequestId = response.providerRequestId(),
          metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
        )
      } else {
        job.cancelled(
          providerRequestId = response.providerRequestId(),
          metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
        )
      }
    }
    val payload = parseJsonObjectOrEmpty(response)
    parsePendingJob(
      payload = payload,
      response = response,
      toolName = job.receipt.toolName,
      previousSnapshot = job,
    )?.let { return it }
    if (isCancelledStatus(payload) || response.statusCode == 200) {
      return job.cancelled(
        providerRequestId = payload.providerRequestId().ifBlank { response.providerRequestId().orEmpty() }.ifBlank { null },
        metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
      )
    }
    return job.pending(
      providerRequestId = payload.providerRequestId().ifBlank { response.providerRequestId().orEmpty() }.ifBlank { null },
      metadata = job.metadata + mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  private fun buildImageRequestBody(request: OpenCrayImageGenerationRequest): JSONObject =
    JSONObject()
      .put("prompt", request.prompt)
      .put("model", request.modelOverride ?: request.settings.model)
      .put("n", request.count)
      .apply {
        if (request.preferAsync) {
          put("async", true)
        }
        request.size?.let { put("size", it) }
        request.format?.let { put("format", it) }
      }

  private fun buildOpenAiCompatibleChatImageRequestBody(request: OpenCrayImageGenerationRequest): JSONObject =
    JSONObject()
      .put("model", request.modelOverride ?: request.settings.model)
      .put(
        "messages",
        JSONArray()
          .put(
            JSONObject()
              .put("role", "user")
              .put("content", openAiCompatibleChatImagePrompt(request)),
          ),
      )
      .put("stream", false)
      .apply {
        if (request.count > 1) {
          put("n", request.count)
        }
      }

  private fun openAiCompatibleChatImagePrompt(request: OpenCrayImageGenerationRequest): String =
    buildString {
      append(request.prompt)
      val hints = buildList {
        request.size?.trim()?.takeIf(String::isNotBlank)?.let { size -> add("size=$size") }
        request.format?.trim()?.takeIf(String::isNotBlank)?.let { format -> add("format=$format") }
      }
      if (hints.isNotEmpty()) {
        append("\n\nImage generation hints: ")
        append(hints.joinToString(separator = ", "))
        append('.')
      }
    }

  private fun buildVideoRequestBody(request: OpenCrayVideoGenerationRequest): JSONObject =
    JSONObject()
      .put("prompt", request.prompt)
      .put("model", request.modelOverride ?: request.settings.model)
      .apply {
        if (request.preferAsync) {
          put("async", true)
        }
        request.durationSeconds?.let { put("duration_seconds", it) }
        request.size?.let { put("size", it) }
        request.format?.let { put("format", it) }
      }

  private fun buildSpeechRequestBody(request: OpenCraySpeechSynthesisRequest): JSONObject =
    JSONObject()
      .put("input", request.text)
      .put("model", request.modelOverride ?: request.settings.defaultModel)
      .put("voice", request.voiceOverride ?: request.settings.defaultVoice)
      .apply {
        if (request.preferAsync) {
          put("async", true)
        }
        request.format?.let { format ->
          put("response_format", format)
        }
      }

  private fun executeJsonPost(
    endpoint: String,
    body: String,
    authHeaders: Map<String, String>,
    trustedBaseUrl: String,
    cancellationRequested: () -> Boolean,
  ): HttpResponse = executeRequest(
    endpoint = endpoint,
    method = "POST",
    body = body.toByteArray(StandardCharsets.UTF_8),
    contentType = "application/json",
    authHeaders = authHeaders,
    trustedBaseUrl = trustedBaseUrl,
    cancellationRequested = cancellationRequested,
  )

  private fun executeRequest(
    endpoint: String,
    method: String,
    authHeaders: Map<String, String>,
    trustedBaseUrl: String,
    cancellationRequested: () -> Boolean,
    body: ByteArray? = null,
    contentType: String? = null,
  ): HttpResponse {
    throwIfCancelled(cancellationRequested)
    var currentEndpoint = endpoint
    var currentMethod = method
    var currentBody = body
    var currentContentType = contentType
    repeat(MAX_REDIRECTS + 1) { redirectIndex ->
      val connection = (URL(currentEndpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = currentMethod
        connectTimeout = DEFAULT_TIMEOUT_MS
        readTimeout = DEFAULT_TIMEOUT_MS
        doInput = true
        doOutput = currentBody != null
        instanceFollowRedirects = false
        currentContentType
          ?.takeIf(String::isNotBlank)
          ?.let { setRequestProperty("Content-Type", it) }
        setRequestProperty("Accept", "*/*")
        setRequestProperty("User-Agent", userAgent)
        authorizedHeadersForEndpoint(
          endpoint = currentEndpoint,
          trustedBaseUrl = trustedBaseUrl,
          authHeaders = authHeaders,
        ).forEach { (name, value) ->
          if (name.isNotBlank() && value.isNotBlank()) {
            setRequestProperty(name, value)
          }
        }
      }
      try {
        val response = withConnectionCancellation(connection, cancellationRequested) {
          if (currentBody != null) {
            connection.outputStream.use { output ->
              output.write(currentBody)
            }
          }
          readHttpResponse(connection, currentEndpoint)
        }
        val redirectTarget = redirectTargetFor(response)
        if (redirectTarget == null) {
          return response
        }
        if (redirectIndex >= MAX_REDIRECTS) {
          throw IllegalStateException("Provider redirected too many times.")
        }
        when (response.statusCode) {
          HttpURLConnection.HTTP_SEE_OTHER -> {
            currentMethod = "GET"
            currentBody = null
            currentContentType = null
          }
          HttpURLConnection.HTTP_MOVED_PERM,
          HttpURLConnection.HTTP_MOVED_TEMP -> {
            val normalizedMethod = currentMethod.trim().uppercase(Locale.US)
            if (normalizedMethod !in setOf("GET", "HEAD")) {
              currentMethod = "GET"
              currentBody = null
              currentContentType = null
            }
          }
        }
        currentEndpoint = redirectTarget
      } finally {
        connection.disconnect()
      }
    }
    throw IllegalStateException("Provider redirected too many times.")
  }

  private fun readHttpResponse(
    connection: HttpURLConnection,
    requestUrl: String,
  ): HttpResponse {
    val statusCode = connection.responseCode
    val contentType = connection.getHeaderField("Content-Type")
    val bodySourcePath = if (statusCode in 200..299 && isDirectBinaryContentType(contentType)) {
      readStreamToTempFile(connection.inputStream)
    } else {
      null
    }
    val body = if (bodySourcePath == null) {
      readStreamBytes(
        if (statusCode in 200..299) connection.inputStream else connection.errorStream,
      )
    } else {
      ByteArray(0)
    }
    return HttpResponse(
      requestUrl = requestUrl,
      statusCode = statusCode,
      contentType = contentType,
      body = body,
      bodySourcePath = bodySourcePath,
      headers = mapOf(
        "X-Request-Id" to connection.getHeaderField("X-Request-Id").orEmpty(),
        "OpenAI-Request-ID" to connection.getHeaderField("OpenAI-Request-ID").orEmpty(),
        "Retry-After" to connection.getHeaderField("Retry-After").orEmpty(),
        "Location" to connection.getHeaderField("Location").orEmpty(),
      ),
    )
  }

  private fun parseJsonObject(response: HttpResponse): JSONObject =
    runCatching {
      JSONObject(response.body.toString(StandardCharsets.UTF_8))
    }.getOrElse {
      throw IllegalStateException(
        extractErrorMessage(response).ifBlank { "Provider returned a non-JSON response." },
      )
    }

  private fun parseJsonObjectOrEmpty(response: HttpResponse): JSONObject =
    response.body
      .takeIf(ByteArray::isNotEmpty)
      ?.let { bytes ->
        runCatching {
          JSONObject(bytes.toString(StandardCharsets.UTF_8))
        }.getOrElse {
          throw IllegalStateException(
            extractErrorMessage(response).ifBlank { "Provider returned a non-JSON response." },
          )
        }
      }
      ?: JSONObject()

  private fun parseBinaryAssetList(
    payload: JSONObject,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
    arrayKeys: List<String>,
    singleKeys: List<String>,
  ): List<OpenCrayBinaryAsset> {
    arrayKeys.forEach { key ->
      payload.optJSONArray(key)?.let { array ->
        val parsed = parseBinaryAssetArray(
          array = array,
          providerBaseUrl = providerBaseUrl,
          authHeaders = authHeaders,
          cancellationRequested = cancellationRequested,
        )
        if (parsed.isNotEmpty()) {
          return parsed
        }
      }
    }
    singleKeys.forEach { key ->
      parseBinaryAssetCandidate(
        rawValue = payload.opt(key),
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let { candidate ->
        return listOf(candidate)
      }
    }
    parseBinaryAssetCandidate(
      rawValue = payload,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { candidate ->
      return listOf(candidate)
    }
    return emptyList()
  }

  private fun parseBinaryAssetArray(
    array: JSONArray,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> = buildList {
    for (index in 0 until array.length()) {
      parseBinaryAssetCandidate(
        rawValue = array.opt(index),
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let(::add)
    }
  }

  private fun parseChatCompletionImageAssetList(
    payload: JSONObject,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> {
    val choices = payload.optJSONArray("choices") ?: return emptyList()
    return buildList {
      for (choiceIndex in 0 until choices.length()) {
        val choice = choices.optJSONObject(choiceIndex) ?: continue
        val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: continue
        parseChatCompletionMessageContentAssets(
          rawContent = message.opt("content"),
          providerBaseUrl = providerBaseUrl,
          authHeaders = authHeaders,
          cancellationRequested = cancellationRequested,
        ).forEach(::add)
      }
    }
  }

  private fun parseChatCompletionMessageContentAssets(
    rawContent: Any?,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> = when (rawContent) {
    is JSONArray -> parseChatCompletionContentArrayAssets(
      array = rawContent,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )
    is JSONObject -> parseChatCompletionContentObjectAssets(
      payload = rawContent,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )
    is String -> parseChatCompletionTextAssets(
      text = rawContent,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )
    else -> emptyList()
  }

  private fun parseChatCompletionContentArrayAssets(
    array: JSONArray,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> = buildList {
    for (index in 0 until array.length()) {
      val assets = when (val item = array.opt(index)) {
        is JSONArray -> parseChatCompletionContentArrayAssets(
          array = item,
          providerBaseUrl = providerBaseUrl,
          authHeaders = authHeaders,
          cancellationRequested = cancellationRequested,
        )
        is JSONObject -> parseChatCompletionContentObjectAssets(
          payload = item,
          providerBaseUrl = providerBaseUrl,
          authHeaders = authHeaders,
          cancellationRequested = cancellationRequested,
        )
        is String -> parseChatCompletionTextAssets(
          text = item,
          providerBaseUrl = providerBaseUrl,
          authHeaders = authHeaders,
          cancellationRequested = cancellationRequested,
        )
        else -> emptyList()
      }
      assets.forEach(::add)
    }
  }

  private fun parseChatCompletionContentObjectAssets(
    payload: JSONObject,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> {
    parseBinaryAssetObject(
      payload = payload,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { return listOf(it) }
    val textAssets = listOf("text", "content")
      .flatMap { key ->
        when (val nested = payload.opt(key)) {
          is JSONArray -> parseChatCompletionContentArrayAssets(
            array = nested,
            providerBaseUrl = providerBaseUrl,
            authHeaders = authHeaders,
            cancellationRequested = cancellationRequested,
          )
          is JSONObject -> parseChatCompletionContentObjectAssets(
            payload = nested,
            providerBaseUrl = providerBaseUrl,
            authHeaders = authHeaders,
            cancellationRequested = cancellationRequested,
          )
          is String -> parseChatCompletionTextAssets(
            text = nested,
            providerBaseUrl = providerBaseUrl,
            authHeaders = authHeaders,
            cancellationRequested = cancellationRequested,
          )
          else -> emptyList()
        }
      }
    return textAssets
  }

  private fun parseChatCompletionTextAssets(
    text: String,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): List<OpenCrayBinaryAsset> {
    val normalized = text.trim()
    if (normalized.isBlank()) {
      return emptyList()
    }
    runCatching { JSONObject(normalized) }.getOrNull()?.let { payload ->
      val parsed = parseBinaryAssetList(
        payload = payload,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
        arrayKeys = listOf("images", "data", "outputs", "artifacts", "files"),
        singleKeys = listOf("image", "image_url", "url", "output", "file"),
      )
      if (parsed.isNotEmpty()) {
        return parsed
      }
    }
    runCatching { JSONArray(normalized) }.getOrNull()?.let { array ->
      val parsed = parseBinaryAssetArray(
        array = array,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )
      if (parsed.isNotEmpty()) {
        return parsed
      }
    }
    val candidates = buildList {
      DATA_URI_REGEX.findAll(normalized).forEach { match -> add(match.value) }
      URL_REGEX.findAll(normalized).forEach { match -> add(match.value.trimEnd('.', ',', ')', ']', '"', '\'')) }
    }
    return candidates.mapNotNull { candidate ->
      parseBinaryAssetString(
        rawValue = candidate,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )
    }
  }

  private fun parseBinaryAssetCandidate(
    rawValue: Any?,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): OpenCrayBinaryAsset? = when (rawValue) {
    null,
    JSONObject.NULL,
    -> null

    is JSONObject -> parseBinaryAssetObject(
      payload = rawValue,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )
    is String -> parseBinaryAssetString(
      rawValue = rawValue,
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )
    else -> null
  }

  private fun parseBinaryAssetObject(
    payload: JSONObject,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): OpenCrayBinaryAsset? {
    parseBinaryAssetString(
      rawValue = payload.optString("url"),
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { return it }
    parseBinaryAssetString(
      rawValue = payload.optString("image_url"),
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { return it }
    payload.optJSONObject("image_url")?.let { imageUrl ->
      parseBinaryAssetString(
        rawValue = imageUrl.optString("url"),
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let { return it }
    }
    parseBinaryAssetString(
      rawValue = payload.optString("output_url"),
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { return it }
    parseBinaryAssetString(
      rawValue = payload.optString("video_url"),
      providerBaseUrl = providerBaseUrl,
      authHeaders = authHeaders,
      cancellationRequested = cancellationRequested,
    )?.let { return it }
    decodeBase64Asset(
      base64Value = payload.optString("b64_json").ifBlank {
        payload.optString("base64")
      }.ifBlank {
        payload.optString("data")
      }.ifBlank {
        payload.optString("bytes")
      },
      mimeType = payload.optString("mime_type").ifBlank {
        payload.optString("mimeType")
      }.ifBlank { null },
      fileName = payload.optString("file_name").ifBlank {
        payload.optString("filename")
      }.ifBlank {
        payload.optString("name")
      }.ifBlank { null },
    )?.let { return it }
    payload.optJSONObject("file")?.let { nested ->
      parseBinaryAssetObject(
        payload = nested,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let { return it }
    }
    payload.optJSONObject("audio")?.let { nested ->
      parseBinaryAssetObject(
        payload = nested,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let { return it }
    }
    payload.optJSONObject("video")?.let { nested ->
      parseBinaryAssetObject(
        payload = nested,
        providerBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )?.let { return it }
    }
    return null
  }

  private fun parseBinaryAssetString(
    rawValue: String?,
    providerBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): OpenCrayBinaryAsset? {
    val normalized = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (normalized.startsWith("data:", ignoreCase = true)) {
      return parseDataUri(normalized)
    }
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      return downloadAsset(
        url = normalized,
        trustedBaseUrl = providerBaseUrl,
        authHeaders = authHeaders,
        cancellationRequested = cancellationRequested,
      )
    }
    return decodeBase64Asset(
      base64Value = normalized,
      mimeType = null,
      fileName = null,
    )
  }

  private fun parseDataUri(rawValue: String): OpenCrayBinaryAsset? {
    val commaIndex = rawValue.indexOf(',')
    if (commaIndex <= 0) {
      return null
    }
    val metadata = rawValue.substring(5, commaIndex)
    val mimeType = metadata.substringBefore(';').trim().ifBlank { null }
    val body = rawValue.substring(commaIndex + 1)
    val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
      decodeBase64(body) ?: return null
    } else {
      body.toByteArray(StandardCharsets.UTF_8)
    }
    return OpenCrayBinaryAsset(
      bytes = bytes,
      mimeType = mimeType,
    )
  }

  private fun decodeBase64Asset(
    base64Value: String?,
    mimeType: String?,
    fileName: String?,
  ): OpenCrayBinaryAsset? {
    val decoded = decodeBase64(base64Value) ?: return null
    return OpenCrayBinaryAsset(
      bytes = decoded,
      mimeType = mimeType,
      fileName = fileName,
    )
  }

  private fun decodeBase64(rawValue: String?): ByteArray? {
    val normalized = rawValue
      ?.trim()
      ?.replace("\\s".toRegex(), "")
      ?.takeIf(String::isNotBlank)
      ?: return null
    return runCatching {
      Base64.getDecoder().decode(normalized)
    }.getOrNull()
  }

  private fun parsePendingJob(
    payload: JSONObject,
    response: HttpResponse,
    toolName: String,
    previousSnapshot: OpenCrayMediaJobSnapshot? = null,
  ): OpenCrayMediaJobSnapshot? {
    val normalizedStatus = payload.normalizedStatus().ifBlank {
      if (response.statusCode == 202) {
        OpenCrayMediaJobStatus.PENDING.name.lowercase(Locale.US)
      } else {
        ""
      }
    }
    val pollUrl = sequenceOf(
      payload.optString("poll_url"),
      payload.optString("pollUrl"),
      payload.optString("status_url"),
      payload.optString("statusUrl"),
      response.headers["Location"].orEmpty(),
    )
      .mapNotNull { candidate -> resolveEndpointUrl(response.requestUrl, candidate) }
      .firstOrNull()
    val cancelUrl = sequenceOf(
      payload.optString("cancel_url"),
      payload.optString("cancelUrl"),
    )
      .mapNotNull { candidate -> resolveEndpointUrl(response.requestUrl, candidate) }
      .firstOrNull()
    val hasPendingSignal = response.statusCode == 202 ||
      normalizedStatus in PROVIDER_PENDING_STATUSES ||
      pollUrl != null ||
      cancelUrl != null
    if (!hasPendingSignal) {
      return null
    }
    val providerJobId = payload.optString("job_id").ifBlank {
      payload.optString("jobId")
    }.ifBlank {
      payload.optString("id")
    }.ifBlank {
      payload.optString("request_id")
    }.ifBlank {
      pollUrl?.let(::jobIdFromEndpoint).orEmpty()
    }.ifBlank {
      previousSnapshot?.receipt?.jobId.orEmpty()
    }.ifBlank {
      pollUrl?.let(::syntheticJobIdFromEndpoint).orEmpty()
    }.ifBlank { null }
      ?: return null
    val pollAfterMs = payload.optLong("poll_after_ms").takeIf { it > 0L }
      ?: payload.optLong("pollAfterMs").takeIf { it > 0L }
      ?: payload.optLong("retry_after_ms").takeIf { it > 0L }
      ?: response.retryAfterMs()
      ?: previousSnapshot?.receipt?.pollAfterMs
      ?: DEFAULT_POLL_AFTER_MS
    val mergedMetadata = previousSnapshot?.metadata.orEmpty() + buildMap {
      pollUrl?.let { put(POLL_URL_METADATA_KEY, it) }
      cancelUrl?.let { put(CANCEL_URL_METADATA_KEY, it) }
      payload.optString("cancel_method").takeIf(String::isNotBlank)?.let { put(CANCEL_METHOD_METADATA_KEY, it) }
      payload.optString("cancelMethod").takeIf(String::isNotBlank)?.let { put(CANCEL_METHOD_METADATA_KEY, it) }
      normalizedStatus.takeIf(String::isNotBlank)?.let { put(PROVIDER_STATUS_METADATA_KEY, it) }
    }
    val providerRequestId = payload.providerRequestId().ifBlank {
      response.providerRequestId().orEmpty()
    }.ifBlank {
      previousSnapshot?.providerRequestId.orEmpty()
    }.ifBlank { null }
    return OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = providerJobId,
        toolName = toolName,
        status = OpenCrayMediaJobStatus.PENDING,
        pollAfterMs = pollAfterMs,
      ),
      providerRequestId = providerRequestId,
      metadata = mergedMetadata,
    )
  }

  private fun ensureEndpointOriginAllowed(
    endpoint: String,
    job: OpenCrayMediaJobSnapshot,
    toolKind: MediaToolKind,
    settings: OpenCrayMediaToolSettings,
  ) {
    val allowedOrigins = buildSet {
      job.metadata[POLL_URL_METADATA_KEY]?.let { url -> endpointOrigin(url)?.let(::add) }
      job.metadata[CANCEL_URL_METADATA_KEY]?.let { url -> endpointOrigin(url)?.let(::add) }
      endpointOrigin(toolKind.baseUrl(settings))?.let(::add)
    }
    if (allowedOrigins.isEmpty()) {
      return
    }
    val targetOrigin = endpointOrigin(endpoint)
    if (targetOrigin == null || targetOrigin !in allowedOrigins) {
      throw IllegalStateException(
        "Provider media job endpoint origin is not allowed: $endpoint",
      )
    }
  }

  private fun pollEndpointFor(
    job: OpenCrayMediaJobSnapshot,
    toolKind: MediaToolKind,
    settings: OpenCrayMediaToolSettings,
  ): String = job.metadata[POLL_URL_METADATA_KEY]
    ?.takeIf(String::isNotBlank)
    ?: buildEndpointUrl(
      baseUrl = toolKind.baseUrl(settings),
      endpoint = "${toolKind.endpoint(settings).trimEnd('/')}/${job.receipt.jobId}",
    )

  private fun cancelEndpointFor(
    job: OpenCrayMediaJobSnapshot,
    toolKind: MediaToolKind,
    settings: OpenCrayMediaToolSettings,
  ): String = job.metadata[CANCEL_URL_METADATA_KEY]
    ?.takeIf(String::isNotBlank)
    ?: buildEndpointUrl(
      baseUrl = toolKind.baseUrl(settings),
      endpoint = "${toolKind.endpoint(settings).trimEnd('/')}/${job.receipt.jobId}/cancel",
    )

  private fun downloadAsset(
    url: String,
    trustedBaseUrl: String,
    authHeaders: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): OpenCrayBinaryAsset {
    throwIfCancelled(cancellationRequested)
    val tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX)
    var currentUrl = url
    try {
      repeat(MAX_REDIRECTS + 1) { redirectIndex ->
        val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = DEFAULT_TIMEOUT_MS
          readTimeout = DEFAULT_TIMEOUT_MS
          doInput = true
          instanceFollowRedirects = false
          setRequestProperty("Accept", "*/*")
          setRequestProperty("User-Agent", userAgent)
          authorizedHeadersForEndpoint(
            endpoint = currentUrl,
            trustedBaseUrl = trustedBaseUrl,
            authHeaders = authHeaders,
          ).forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
              setRequestProperty(name, value)
            }
          }
        }
        try {
          val redirectTarget = withConnectionCancellation(connection, cancellationRequested) {
            redirectTargetFor(
              HttpResponse(
                requestUrl = currentUrl,
                statusCode = connection.responseCode,
                contentType = connection.getHeaderField("Content-Type"),
                body = ByteArray(0),
                headers = mapOf(
                  "Location" to connection.getHeaderField("Location").orEmpty(),
                ),
              ),
            )
          }
          if (redirectTarget != null) {
            if (redirectIndex >= MAX_REDIRECTS) {
              throw IllegalStateException("Provider redirected too many times.")
            }
            currentUrl = redirectTarget
            return@repeat
          }
          val statusCode = connection.responseCode
          if (statusCode !in 200..299) {
            val errorBody = readStreamBytes(connection.errorStream)
            throw IllegalStateException(
              extractErrorMessage(
                HttpResponse(
                  requestUrl = currentUrl,
                  statusCode = statusCode,
                  contentType = connection.getHeaderField("Content-Type"),
                  body = errorBody,
                  headers = emptyMap(),
                ),
              ),
            )
          }
          Files.newOutputStream(tempFile).use { output ->
            connection.inputStream.use { input ->
              copyStream(input, output)
            }
          }
          return OpenCrayBinaryAsset(
            sourcePath = tempFile,
            mimeType = normalizedContentType(connection.getHeaderField("Content-Type")),
            fileName = fileNameFromUrl(currentUrl),
          )
        } finally {
          connection.disconnect()
        }
      }
      throw IllegalStateException("Provider redirected too many times.")
    } catch (exception: Throwable) {
      runCatching { Files.deleteIfExists(tempFile) }
      if (exception is CancellationException) {
        throw exception
      }
      throw exception
    }
  }

  private fun authorizedHeadersForEndpoint(
    endpoint: String,
    trustedBaseUrl: String,
    authHeaders: Map<String, String>,
  ): Map<String, String> {
    if (authHeaders.isEmpty() || trustedBaseUrl.isBlank()) {
      return authHeaders
    }
    val trustedOrigin = endpointOrigin(trustedBaseUrl) ?: return emptyMap()
    val endpointOrigin = endpointOrigin(endpoint) ?: return emptyMap()
    return if (trustedOrigin == endpointOrigin) {
      authHeaders
    } else {
      emptyMap()
    }
  }

  private fun endpointOrigin(url: String): String? = runCatching {
    val parsed = URL(url)
    val scheme = parsed.protocol.trim().lowercase(Locale.US)
    val host = parsed.host.trim().lowercase(Locale.US)
    if (scheme.isBlank() || host.isBlank()) {
      null
    } else {
      val port = when {
        parsed.port >= 0 -> parsed.port
        parsed.defaultPort >= 0 -> parsed.defaultPort
        scheme == "https" -> 443
        else -> 80
      }
      "$scheme://$host:$port"
    }
  }.getOrNull()

  private fun redirectTargetFor(response: HttpResponse): String? =
    response
      .takeIf { it.statusCode in 300..399 }
      ?.headers
      ?.get("Location")
      ?.let { location -> resolveEndpointUrl(response.requestUrl, location) }

  private fun resolveEndpointUrl(
    requestUrl: String,
    candidate: String,
  ): String? = candidate
    .trim()
    .takeIf(String::isNotBlank)
    ?.let { value ->
      runCatching {
        URL(URL(requestUrl), value).toString()
      }.getOrNull()
    }

  private fun jobIdFromEndpoint(url: String): String? = runCatching {
    val parsed = URL(url)
    queryParameters(parsed)
      .firstNotNullOfOrNull { (name, value) ->
        value.takeIf { name in PROVIDER_JOB_ID_QUERY_KEYS && it.isNotBlank() }
      }
      ?: parsed.path
        .split('/')
        .asReversed()
        .map(String::trim)
        .firstOrNull { segment ->
          segment.isNotBlank() &&
            segment.lowercase(Locale.US) !in NON_JOB_ID_PATH_SEGMENTS
        }
  }.getOrNull()

  private fun syntheticJobIdFromEndpoint(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(url.toByteArray(StandardCharsets.UTF_8))
    val token = digest
      .take(8)
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "provider-job-$token"
  }

  private fun queryParameters(parsed: URL): List<Pair<String, String>> = parsed
    .query
    .orEmpty()
    .split('&')
    .mapNotNull { entry ->
      val separatorIndex = entry.indexOf('=')
      when {
        separatorIndex > 0 -> {
          val name = entry.substring(0, separatorIndex).trim().lowercase(Locale.US)
          val value = entry.substring(separatorIndex + 1).trim()
          name.takeIf(String::isNotBlank)?.let { it to value }
        }
        entry.isNotBlank() -> entry.trim().lowercase(Locale.US) to ""
        else -> null
      }
    }

  private fun extractErrorMessage(response: HttpResponse): String {
    val contentType = normalizedContentType(response.contentType)
    if (contentType == "application/json" || contentType?.endsWith("+json") == true) {
      runCatching {
        val payload = JSONObject(response.body.toString(StandardCharsets.UTF_8))
        payload.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
          ?: payload.optString("message").takeIf(String::isNotBlank)
      }.getOrNull()?.let { message ->
        return message
      }
    }
    val text = response.body.toString(StandardCharsets.UTF_8).trim()
    return text.ifBlank { "Provider returned HTTP ${response.statusCode}." }
  }

  private fun normalizedContentType(rawValue: String?): String? =
    rawValue
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)

  private fun isDirectBinaryContentType(rawValue: String?): Boolean {
    val normalized = normalizedContentType(rawValue) ?: return false
    return normalized.startsWith("image/") ||
      normalized.startsWith("video/") ||
      normalized.startsWith("audio/")
  }

  private fun readStreamBytes(input: java.io.InputStream?): ByteArray {
    if (input == null) {
      return ByteArray(0)
    }
    return input.use { stream ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      val output = ByteArrayOutputStream()
      while (true) {
        ensureThreadNotInterrupted()
        val read = stream.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          output.write(buffer, 0, read)
        }
      }
      output.toByteArray()
    }
  }

  private fun readStreamToTempFile(input: java.io.InputStream?): Path? {
    if (input == null) {
      return null
    }
    val tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX)
    return try {
      input.use { stream ->
        Files.newOutputStream(tempFile).use { output ->
          copyStream(stream, output)
        }
      }
      tempFile
    } catch (exception: Throwable) {
      runCatching { Files.deleteIfExists(tempFile) }
      throw exception
    }
  }

  private fun copyStream(
    input: java.io.InputStream,
    output: OutputStream,
  ) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      ensureThreadNotInterrupted()
      val read = input.read(buffer)
      if (read < 0) {
        break
      }
      if (read > 0) {
        output.write(buffer, 0, read)
      }
    }
  }

  private fun buildEndpointUrl(
    baseUrl: String,
    endpoint: String,
  ): String {
    val trimmedEndpoint = endpoint.trim()
    if (trimmedEndpoint.startsWith("http://") || trimmedEndpoint.startsWith("https://")) {
      return trimmedEndpoint
    }
    val trimmedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedEndpoint = trimmedEndpoint.trimStart('/')
    return when {
      trimmedBaseUrl.isBlank() -> normalizedEndpoint
      normalizedEndpoint.isBlank() -> trimmedBaseUrl
      else -> "$trimmedBaseUrl/$normalizedEndpoint"
    }
  }

  private fun isOpenAiCompatibleChatCompletionsEndpoint(endpoint: String): Boolean =
    runCatching {
      URL(endpoint).path
    }.getOrDefault(endpoint)
      .trimEnd('/')
      .lowercase(Locale.US)
      .endsWith("/chat/completions")

  private fun fileNameFromUrl(url: String): String? =
    url.substringBefore('?')
      .substringAfterLast('/')
      .trim()
      .takeIf(String::isNotBlank)

  private fun <T> withConnectionCancellation(
    connection: HttpURLConnection,
    cancellationRequested: () -> Boolean,
    block: () -> T,
  ): T {
    throwIfCancelled(cancellationRequested)
    val stopRequested = AtomicBoolean(false)
    val requestThread = Thread.currentThread()
    val watcher = Thread {
      while (!stopRequested.get()) {
        if (cancellationRequested() || requestThread.isInterrupted) {
          runCatching { connection.disconnect() }
          break
        }
        try {
          Thread.sleep(CANCELLATION_POLL_INTERVAL_MS)
        } catch (_: InterruptedException) {
          break
        }
      }
    }.apply {
      name = "OpenCrayMediaProviderClientCancelWatcher"
      isDaemon = true
      start()
    }
    return try {
      block()
    } catch (exception: Throwable) {
      if (cancellationRequested() || requestThread.isInterrupted) {
        throw CancellationException("Media request cancelled.").apply {
          initCause(exception)
        }
      }
      throw exception
    } finally {
      stopRequested.set(true)
      watcher.interrupt()
    }
  }

  private fun throwIfCancelled(cancellationRequested: () -> Boolean) {
    ensureThreadNotInterrupted()
    if (cancellationRequested()) {
      throw CancellationException("Media request cancelled.")
    }
  }

  private fun ensureThreadNotInterrupted() {
    if (Thread.currentThread().isInterrupted) {
      throw CancellationException("Media request cancelled.")
    }
  }

  private data class HttpResponse(
    val requestUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    val bodySourcePath: Path? = null,
    val headers: Map<String, String>,
  ) {
    fun providerRequestId(): String? = sequenceOf(
      headers["X-Request-Id"],
      headers["OpenAI-Request-ID"],
    )
      .filterNotNull()
      .map(String::trim)
      .firstOrNull(String::isNotBlank)

    fun retryAfterMs(): Long? = headers["Retry-After"]
      ?.trim()
      ?.toLongOrNull()
      ?.takeIf { it > 0L }
      ?.times(1_000L)
  }

  private fun HttpResponse.toDirectBinaryAsset(contentType: String): OpenCrayBinaryAsset =
    bodySourcePath?.let { sourcePath ->
      OpenCrayBinaryAsset(
        sourcePath = sourcePath,
        mimeType = contentType,
      )
    } ?: OpenCrayBinaryAsset(
      bytes = body,
      mimeType = contentType,
    )

  private fun JSONObject.normalizedStatus(): String = optString("status").ifBlank {
    optString("state")
  }.ifBlank {
    optString("job_status")
  }.trim().lowercase(Locale.US)

  private fun JSONObject.providerRequestId(): String = optString("request_id").ifBlank {
    optString("requestId")
  }.ifBlank {
    optString("id")
  }

  private fun isCancelledStatus(payload: JSONObject): Boolean =
    payload.normalizedStatus() in PROVIDER_CANCELLED_STATUSES

  private fun OpenCrayMediaJobSnapshot.pending(
    providerRequestId: String?,
    metadata: Map<String, String>,
  ): OpenCrayMediaJobSnapshot = copy(
    receipt = receipt.copy(status = OpenCrayMediaJobStatus.PENDING),
    providerRequestId = providerRequestId ?: this.providerRequestId,
    metadata = metadata,
  )

  private fun OpenCrayMediaJobSnapshot.completed(
    providerRequestId: String?,
    metadata: Map<String, String>,
  ): OpenCrayMediaJobSnapshot = copy(
    receipt = receipt.copy(status = OpenCrayMediaJobStatus.COMPLETED),
    providerRequestId = providerRequestId ?: this.providerRequestId,
    metadata = metadata,
  )

  private fun OpenCrayMediaJobSnapshot.cancelled(
    providerRequestId: String?,
    metadata: Map<String, String>,
  ): OpenCrayMediaJobSnapshot = copy(
    receipt = receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
    providerRequestId = providerRequestId ?: this.providerRequestId,
    metadata = metadata,
  )

  private enum class MediaToolKind(
    val toolName: String,
    val directContentPrefix: String,
    val arrayKeys: List<String>,
    val singleKeys: List<String>,
    val label: String,
  ) {
    IMAGE(
      toolName = TOOL_NAME_GENERATE_IMAGE,
      directContentPrefix = "image/",
      arrayKeys = listOf("images", "data", "outputs", "artifacts", "files"),
      singleKeys = listOf("image", "output", "file"),
      label = "Image",
    ),
    VIDEO(
      toolName = TOOL_NAME_GENERATE_VIDEO,
      directContentPrefix = "video/",
      arrayKeys = listOf("videos", "data", "outputs", "artifacts", "files"),
      singleKeys = listOf("video", "output", "file"),
      label = "Video",
    ),
    SPEECH(
      toolName = TOOL_NAME_SYNTHESIZE_SPEECH,
      directContentPrefix = "audio/",
      arrayKeys = listOf("audio", "data", "outputs", "files"),
      singleKeys = listOf("audio", "file", "output", "data"),
      label = "Speech",
    );

    fun baseUrl(settings: OpenCrayMediaToolSettings): String = when (this) {
      IMAGE -> requireNotNull(settings.imageGeneration).baseUrl
      VIDEO -> requireNotNull(settings.videoGeneration).baseUrl
      SPEECH -> requireNotNull(settings.speechSynthesis).baseUrl
    }

    fun endpoint(settings: OpenCrayMediaToolSettings): String = when (this) {
      IMAGE -> requireNotNull(settings.imageGeneration).endpoint
      VIDEO -> requireNotNull(settings.videoGeneration).endpoint
      SPEECH -> requireNotNull(settings.speechSynthesis).endpoint
    }

    fun authHeaders(settings: OpenCrayMediaToolSettings): Map<String, String> = when (this) {
      IMAGE -> requireNotNull(settings.imageGeneration).authHeaders
      VIDEO -> requireNotNull(settings.videoGeneration).authHeaders
      SPEECH -> requireNotNull(settings.speechSynthesis).authHeaders
    }
  }

  private fun toolKindFor(toolName: String): MediaToolKind = when (toolName) {
    TOOL_NAME_GENERATE_IMAGE -> MediaToolKind.IMAGE
    TOOL_NAME_GENERATE_VIDEO -> MediaToolKind.VIDEO
    TOOL_NAME_SYNTHESIZE_SPEECH -> MediaToolKind.SPEECH
    else -> throw IllegalArgumentException("Unsupported media job tool '$toolName'.")
  }

  companion object {
    private const val DEFAULT_TIMEOUT_MS: Int = 60_000
    private const val CANCELLATION_POLL_INTERVAL_MS: Long = 50L
    private const val TEMP_FILE_PREFIX: String = "opencray-media-"
    private const val TEMP_FILE_SUFFIX: String = ".bin"
    private const val DEFAULT_POLL_AFTER_MS: Long = 1_000L
    private const val MAX_REDIRECTS: Int = 5
    private val DATA_URI_REGEX: Regex = Regex("""data:image/[^,\s]+;base64,[A-Za-z0-9+/=_-]+""")
    private val URL_REGEX: Regex = Regex("""https?://[^\s<>\]]+""")
    private const val TOOL_NAME_GENERATE_IMAGE: String = "GenerateImage"
    private const val TOOL_NAME_GENERATE_VIDEO: String = "GenerateVideo"
    private const val TOOL_NAME_SYNTHESIZE_SPEECH: String = "SynthesizeSpeech"
    private const val POLL_URL_METADATA_KEY: String = "providerPollUrl"
    private const val CANCEL_URL_METADATA_KEY: String = "providerCancelUrl"
    private const val CANCEL_METHOD_METADATA_KEY: String = "providerCancelMethod"
    private const val PROVIDER_STATUS_METADATA_KEY: String = "providerStatus"
    private val PROVIDER_PENDING_STATUSES: Set<String> = setOf(
      "pending",
      "queued",
      "processing",
      "running",
      "in_progress",
      "submitted",
      "accepted",
      "starting",
      "cancel_requested",
      "cancelling",
    )
    private val PROVIDER_CANCELLED_STATUSES: Set<String> = setOf(
      "cancelled",
      "canceled",
    )
    private val PROVIDER_JOB_ID_QUERY_KEYS: Set<String> = setOf(
      "job_id",
      "jobid",
      "id",
      "request_id",
      "requestid",
    )
    private val NON_JOB_ID_PATH_SEGMENTS: Set<String> = setOf(
      "job",
      "jobs",
      "status",
      "poll",
      "result",
      "results",
      "cancel",
      "operation",
      "operations",
    )
  }
}
