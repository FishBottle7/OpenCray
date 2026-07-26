package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCrayFinalAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal interface RuntimeServiceCommandFallbackTransport {
  val transportId: String
    get() = "loopback_http"

  fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? = null

  fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? = null

  fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? = null
}

internal data class RuntimeServiceReadFallbackGatewayBundle(
  val shellGateway: OpenCrayShellGateway? = null,
  val chatRuntimeGateway: OpenCrayChatRuntimeGateway? = null,
  val skillsGateway: OpenCraySkillsGateway? = null,
  val settingsGateway: OpenCraySettingsGateway? = null,
)

internal fun loopbackRuntimeServiceReadFallbackGatewayBundle(
  appContext: Context,
  target: RuntimeServiceTarget = RuntimeServiceTarget.INTERACTIVE,
  requestClient: OpenCrayLocalRuntimeLoopbackHttpClient =
    openCrayLocalRuntimeLoopbackHttpClientForTarget(appContext, target),
  mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
): RuntimeServiceReadFallbackGatewayBundle = loopbackRuntimeServiceReadFallbackGatewayBundleInternal(
  requestClient = requestClient,
  mainThreadPoster = mainThreadPoster,
)

@Deprecated("Legacy fixed-port test compatibility only; production callers must pass appContext.")
internal fun loopbackRuntimeServiceReadFallbackGatewayBundle(
  target: RuntimeServiceTarget = RuntimeServiceTarget.INTERACTIVE,
  requestClient: OpenCrayLocalRuntimeLoopbackHttpClient =
    openCrayLocalRuntimeLoopbackHttpClientForTarget(target),
  mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
): RuntimeServiceReadFallbackGatewayBundle = loopbackRuntimeServiceReadFallbackGatewayBundleInternal(
  requestClient = requestClient,
  mainThreadPoster = mainThreadPoster,
)

private fun loopbackRuntimeServiceReadFallbackGatewayBundleInternal(
  requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
  mainThreadPoster: MainThreadPoster,
): RuntimeServiceReadFallbackGatewayBundle {
  val commandTransport = LoopbackHttpRuntimeServiceCommandFallbackTransport(
    requestClient = requestClient,
  )
  return RuntimeServiceReadFallbackGatewayBundle(
    shellGateway = LoopbackHttpOpenCrayShellGateway(
      requestClient = requestClient,
      mainThreadPoster = mainThreadPoster,
    ),
    chatRuntimeGateway = LoopbackHttpOpenCrayChatRuntimeGateway(
      requestClient = requestClient,
      commandTransport = commandTransport,
      mainThreadPoster = mainThreadPoster,
    ),
    skillsGateway = LoopbackHttpOpenCraySkillsGateway(
      requestClient = requestClient,
      commandTransport = commandTransport,
      mainThreadPoster = mainThreadPoster,
    ),
    settingsGateway = LoopbackHttpOpenCraySettingsGateway(
      requestClient = requestClient,
      commandTransport = commandTransport,
      mainThreadPoster = mainThreadPoster,
    ),
  )
}

internal fun openCrayLocalRuntimeLoopbackHttpClientForTarget(
  appContext: Context,
  target: RuntimeServiceTarget,
): OpenCrayLocalRuntimeLoopbackHttpClient {
  val descriptorStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    RuntimeServiceLoopbackDescriptorStore.fromContext(
      appContext.applicationContext ?: appContext,
    )
  }
  return OpenCrayLocalRuntimeLoopbackHttpClient(
    descriptorProvider = { descriptorStore.read(target) },
  )
}

@Deprecated("Legacy fixed-port test compatibility only; production callers must pass appContext.")
internal fun openCrayLocalRuntimeLoopbackHttpClientForTarget(
  target: RuntimeServiceTarget,
): OpenCrayLocalRuntimeLoopbackHttpClient = OpenCrayLocalRuntimeLoopbackHttpClient(
  baseUrlProvider = {
    "http://127.0.0.1:${localRuntimeLoopbackPortForTarget(target)}/"
  },
)

internal class LoopbackHttpRuntimeServiceCommandFallbackTransport(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
) : RuntimeServiceCommandFallbackTransport {
  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = decodeLoopbackRuntimeServiceWriteResult(
    command = command,
    payload = dispatchCommand(runtimeServiceWriteCommandEnvelope(command)),
  )

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult = decodeLoopbackRuntimeServiceWriteResult(
    command = command,
    payload = dispatchCommand(runtimeServiceWriteCommandEnvelope(command)),
  )

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult = decodeLoopbackRuntimeServiceWriteResult(
    command = command,
    payload = dispatchCommand(runtimeServiceWriteCommandEnvelope(command)),
  )

  private fun dispatchCommand(
    envelope: RuntimeServiceWriteCommandEnvelope,
  ): Any? = requestClient.request(
    method = envelope.method,
    path = envelope.route,
    queryParameters = envelope.queryParameters,
    body = envelope.payload
      .takeUnless { payload -> payload.isEmpty() }
      ?.let { payload -> JSONObject(payload.toString()) },
  )
}

internal class OpenCrayLocalRuntimeLoopbackHttpClient(
  private val baseUrlProvider: () -> String = {
    "http://127.0.0.1:${OpenCrayLocalRuntimeServer.DEFAULT_PORT}/"
  },
  private val bootstrapTimeoutMs: Long = 1_500L,
  private val retryDelayMs: Long = 100L,
  private val connectTimeoutMs: Int = 300,
  private val readTimeoutMs: Int = 60_000,
  private val descriptorProvider: (() -> RuntimeServiceLoopbackDescriptor?)? = null,
) {
  fun post(
    path: String,
    body: JSONObject? = null,
  ) {
    request(method = "POST", path = path, body = body)
  }

  fun postObject(
    path: String,
    body: JSONObject? = null,
  ): Map<String, Any?> = requireMapPayload(
    request(method = "POST", path = path, body = body),
    path = path,
  )

  fun postObjectOrNull(
    path: String,
    body: JSONObject? = null,
  ): Map<String, Any?>? {
    val payload = request(method = "POST", path = path, body = body) ?: return null
    return requireMapPayload(payload, path = path)
  }

  fun postString(
    path: String,
    body: JSONObject? = null,
  ): String = requireStringPayload(
    request(method = "POST", path = path, body = body),
    path = path,
  )

  fun getString(
    path: String,
    queryParameters: Map<String, String> = emptyMap(),
  ): String = requireStringPayload(
    request(method = "GET", path = path, queryParameters = queryParameters),
    path = path,
  )

  fun getObject(
    path: String,
    queryParameters: Map<String, String> = emptyMap(),
  ): Map<String, Any?> = requireMapPayload(
    request(method = "GET", path = path, queryParameters = queryParameters),
    path = path,
  )

  fun getObjectOrNull(
    path: String,
    queryParameters: Map<String, String> = emptyMap(),
  ): Map<String, Any?>? {
    val payload = request(method = "GET", path = path, queryParameters = queryParameters) ?: return null
    return requireMapPayload(payload, path = path)
  }

  fun request(
    method: String,
    path: String,
    queryParameters: Map<String, String> = emptyMap(),
    body: JSONObject? = null,
  ): Any? {
    val deadlineAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
      bootstrapTimeoutMs.coerceAtLeast(0L),
    )
    var lastConnectionFailure: Throwable? = null
    while (true) {
      try {
        return executeRequest(
          method = method,
          path = path,
          queryParameters = queryParameters,
          body = body,
        )
      } catch (throwable: ConnectException) {
        lastConnectionFailure = throwable
      } catch (throwable: SocketTimeoutException) {
        throw IllegalStateException(
          "Loopback runtime transport timed out for '$path'.",
          throwable,
        )
      } catch (throwable: IOException) {
        throw IllegalStateException(
          "Loopback runtime transport failed for '$path': ${throwable.message ?: throwable::class.java.simpleName}",
          throwable,
        )
      }
      if (System.nanoTime() - deadlineAtNanos >= 0L) {
        throw IllegalStateException(
          "Loopback runtime transport is unavailable for '$path'.",
          lastConnectionFailure,
        )
      }
      Thread.sleep(retryDelayMs)
    }
  }

  private fun executeRequest(
    method: String,
    path: String,
    queryParameters: Map<String, String>,
    body: JSONObject?,
  ): Any? {
    val normalizedMethod = method.uppercase()
    val bodyBytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
    val endpoint = resolveEndpoint(path = path, queryParameters = queryParameters)
    val requestTimestampEpochMs = System.currentTimeMillis()
    val authenticationHeaders = endpoint.descriptor?.let { descriptor ->
      RuntimeServiceLoopbackHttpAuth.requestHeaders(
        credentials = descriptor.credentials,
        timestampEpochMs = requestTimestampEpochMs,
        method = normalizedMethod,
        requestTarget = endpoint.requestTarget,
        body = bodyBytes,
      )
    }.orEmpty()
    val requestNonce = authenticationHeaders[RuntimeServiceLoopbackHttpAuth.HEADER_NONCE_WIRE]
    val connection = (endpoint.url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
      requestMethod = normalizedMethod
      instanceFollowRedirects = false
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      doInput = true
      setRequestProperty("Accept", "application/json")
      authenticationHeaders.forEach { (name, value) ->
        setRequestProperty(name, value)
      }
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setFixedLengthStreamingMode(bodyBytes.size)
      }
    }
    return try {
      if (body != null) {
        connection.outputStream.use { output ->
          output.write(bodyBytes)
        }
      }
      val statusCode = connection.responseCode
      val responseBytes = readResponseBody(connection, statusCode)
      endpoint.descriptor?.let { descriptor ->
        val responseHeaders = mapOf(
          RuntimeServiceLoopbackHttpAuth.HEADER_EPOCH to
            connection.getHeaderField(RuntimeServiceLoopbackHttpAuth.HEADER_EPOCH_WIRE).orEmpty(),
          RuntimeServiceLoopbackHttpAuth.HEADER_TIMESTAMP to
            connection.getHeaderField(RuntimeServiceLoopbackHttpAuth.HEADER_TIMESTAMP_WIRE).orEmpty(),
          RuntimeServiceLoopbackHttpAuth.HEADER_NONCE to
            connection.getHeaderField(RuntimeServiceLoopbackHttpAuth.HEADER_NONCE_WIRE).orEmpty(),
          RuntimeServiceLoopbackHttpAuth.HEADER_SIGNATURE to
            connection.getHeaderField(RuntimeServiceLoopbackHttpAuth.HEADER_SIGNATURE_WIRE).orEmpty(),
        )
        val responseAuthenticated =
          requestNonce != null &&
            RuntimeServiceLoopbackHttpAuth.verifyResponse(
              credentials = descriptor.credentials,
              requestTimestampEpochMs = requestTimestampEpochMs,
              requestNonce = requestNonce,
              method = normalizedMethod,
              requestTarget = endpoint.requestTarget,
              statusCode = statusCode,
              body = responseBytes,
              headers = responseHeaders,
            )
        if (!responseAuthenticated && statusCode == 401) {
          // Authentication rejection happens before dispatch, so this attempt
          // is safe to repeat with a freshly read descriptor.
          throw LoopbackDescriptorRotationException(
            "Loopback runtime transport returned an unauthenticated response for '$path'.",
          )
        }
        if (!responseAuthenticated) {
          // A non-401 response may have followed an executed write. Never
          // replay that ambiguous request merely because its response failed
          // authentication.
          throw IOException(
            "Loopback runtime transport response authentication failed for '$path'.",
          )
        }
      }
      val responseBody = String(responseBytes, Charsets.UTF_8)
      if (statusCode !in 200..299) {
        throw IllegalStateException(
          "Loopback runtime transport returned HTTP $statusCode for '$path'${formatErrorSuffix(responseBody)}",
        )
      }
      parseJsonPayload(responseBody)
    } finally {
      connection.disconnect()
    }
  }

  private fun buildUrl(
    baseUrl: String,
    path: String,
    queryParameters: Map<String, String>,
  ): String {
    val normalizedBaseUrl = baseUrl.trim().ifBlank {
      "http://127.0.0.1:${OpenCrayLocalRuntimeServer.DEFAULT_PORT}/"
    }
    val prefix = if (normalizedBaseUrl.endsWith("/")) normalizedBaseUrl else "$normalizedBaseUrl/"
    val normalizedPath = path.trimStart('/')
    if (queryParameters.isEmpty()) {
      return "$prefix$normalizedPath"
    }
    val query = queryParameters.entries.joinToString(separator = "&") { (key, value) ->
      "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
    }
    return "$prefix$normalizedPath?$query"
  }

  private fun resolveEndpoint(
    path: String,
    queryParameters: Map<String, String>,
  ): LoopbackRequestEndpoint {
    val descriptor = descriptorProvider?.invoke()
    if (descriptorProvider != null && descriptor == null) {
      throw ConnectException("Loopback runtime descriptor is unavailable.")
    }
    val url = URL(
      buildUrl(
        baseUrl = descriptor?.baseUrl() ?: baseUrlProvider(),
        path = path,
        queryParameters = queryParameters,
      ),
    )
    return LoopbackRequestEndpoint(
      url = url,
      requestTarget = url.file.takeIf(String::isNotBlank) ?: "/",
      descriptor = descriptor,
    )
  }

  private fun parseJsonPayload(rawBody: String): Any? {
    if (rawBody.isBlank()) {
      return null
    }
    return jsonValueToKotlin(JSONTokener(rawBody).nextValue())
  }

  private fun readResponseBody(
    connection: HttpURLConnection,
    statusCode: Int,
  ): ByteArray {
    val contentLength = connection.contentLengthLong
    if (contentLength > MAX_RESPONSE_BODY_BYTES) {
      throw IOException("Loopback runtime response exceeds the size limit.")
    }
    val input = if (statusCode in 200..299) {
      connection.inputStream
    } else {
      connection.errorStream
    } ?: return ByteArray(0)
    return input.use { stream ->
      val output = ByteArrayOutputStream(
        contentLength.takeIf { it in 1..MAX_RESPONSE_BODY_BYTES }
          ?.toInt()
          ?: DEFAULT_RESPONSE_BUFFER_BYTES,
      )
      val buffer = ByteArray(DEFAULT_RESPONSE_BUFFER_BYTES)
      var total = 0L
      while (true) {
        val count = stream.read(buffer)
        if (count < 0) {
          break
        }
        total += count
        if (total > MAX_RESPONSE_BODY_BYTES) {
          throw IOException("Loopback runtime response exceeds the size limit.")
        }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
  }

  private fun formatErrorSuffix(rawBody: String): String {
    val parsed = runCatching { parseJsonPayload(rawBody) }.getOrNull()
    val message = (parsed as? Map<*, *>)?.get("error") as? String
    return when {
      !message.isNullOrBlank() -> ": $message"
      rawBody.isBlank() -> ""
      else -> ": ${rawBody.take(200)}"
    }
  }

  private data class LoopbackRequestEndpoint(
    val url: URL,
    val requestTarget: String,
    val descriptor: RuntimeServiceLoopbackDescriptor?,
  )

  private class LoopbackDescriptorRotationException(
    message: String,
  ) : ConnectException(message)

  companion object {
    private const val DEFAULT_RESPONSE_BUFFER_BYTES: Int = 8 * 1024
    private const val MAX_RESPONSE_BODY_BYTES: Long = 8L * 1024L * 1024L
  }
}

private fun requireMapPayload(
  payload: Any?,
  path: String,
): Map<String, Any?> = payload as? Map<String, Any?> ?: error(
  "Loopback runtime transport returned a non-object payload for '$path'.",
)

private fun requireStringPayload(
  payload: Any?,
  path: String,
): String = payload as? String ?: error(
  "Loopback runtime transport returned a non-string payload for '$path'.",
)

private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
  null,
  JSONObject.NULL,
  -> null

  is JSONObject -> buildMap {
    val keys = value.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      put(key, jsonValueToKotlin(value.opt(key)))
    }
  }

  is JSONArray -> List(value.length()) { index ->
    jsonValueToKotlin(value.opt(index))
  }

  else -> value
}

private fun JSONObject.putIfNotNull(
  key: String,
  value: Any?,
): JSONObject = apply {
  if (value != null) {
    put(key, value)
  }
}

private fun encodeQueryComponent(value: String): String =
  URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private class LoopbackHttpOpenCrayShellGateway(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
  private val mainThreadPoster: MainThreadPoster,
  private val pollIntervalMs: Long = 2_000L,
) : OpenCrayShellGateway {
  override fun loadShellSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/shell_snapshot")

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadShellSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    requestClient.post(
      path = "v1/save_shell_destination",
      body = JSONObject().apply {
        put("selectedTab", selectedTab)
        settingsSubpage?.let { put("settingsSubpage", it) }
      },
    )
  }
}

private class LoopbackHttpOpenCrayChatRuntimeGateway(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
  private val commandTransport: RuntimeServiceCommandFallbackTransport,
  private val mainThreadPoster: MainThreadPoster,
  private val pollIntervalMs: Long = 2_000L,
) : OpenCrayChatRuntimeGateway {
  override fun loadChatSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/chat_snapshot")

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadChatSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/chat_runtime_snapshot")

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeLiveAssistantDraftsWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      runtimePayloadProvider = ::loadChatRuntimeSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    requestClient.getObjectOrNull(
      path = "v1/chat_run_snapshot",
      queryParameters = mapOf("runId" to runId),
    )

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = requestClient.postObjectOrNull(
    path = "v1/wait_chat_run",
    body = JSONObject()
      .put("runId", runId)
      .put("timeoutMs", timeoutMs),
  )

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadChatRuntimeSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun refreshSandboxSessionInfo() {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo)
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/memory_debug_snapshot")

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/memory_debug_links_snapshot")

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/soul_debug_snapshot")

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = requestClient.postObject(
    path = "v1/memory_debug_search",
    body = JSONObject()
      .put("query", query)
      .put("maxResults", maxResults)
      .put("minScore", minScore),
  )

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = requestClient.postObject(
    path = "v1/memory_debug_slice",
    body = JSONObject()
      .put("path", path)
      .putIfNotNull("fromLine", fromLine)
      .put("lines", lines),
  )

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = commandTransport.requireChatPayload(
    OpenCrayChatWriteCommand.ApplyMemoryDebugAction(
      recordId = recordId,
      actionId = actionId,
    ),
  )

  override fun createChatSession() {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.CreateChatSession)
  }

  override fun copyChatSession(sessionId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.CopyChatSession(sessionId))
  }

  override fun deleteChatSession(sessionId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.DeleteChatSession(sessionId))
  }

  override fun selectChatSession(sessionId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.SelectChatSession(sessionId))
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    commandTransport.requireChatDispatch(
      OpenCrayChatWriteCommand.BranchChatSessionFromMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    commandTransport.requireChatDispatch(
      OpenCrayChatWriteCommand.DeleteChatMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    commandTransport.requireChatDispatch(
      OpenCrayChatWriteCommand.RecallChatMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? = commandTransport.requireChatPayloadOrNull(
    OpenCrayChatWriteCommand.SubmitChatMessage(
      text = text,
      attachments = attachments,
    ),
  )

  override fun approveChatApproval(taskIdOrRunId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.ApproveChatApproval(taskIdOrRunId))
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    commandTransport.requireChatDispatch(
      OpenCrayChatWriteCommand.ApproveChatApprovalForSession(taskIdOrRunId),
    )
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.RejectChatApproval(taskIdOrRunId))
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.InterruptChatRun(taskIdOrRunId))
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    commandTransport.requireChatDispatch(OpenCrayChatWriteCommand.RetryChatRun(taskIdOrRunId))
  }
}

private class LoopbackHttpOpenCraySkillsGateway(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
  private val commandTransport: RuntimeServiceCommandFallbackTransport,
  private val mainThreadPoster: MainThreadPoster,
  private val pollIntervalMs: Long = 2_000L,
) : OpenCraySkillsGateway {
  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> = requestClient.getObject(
    path = "v1/skills_snapshot",
    queryParameters = buildMap {
      if (query.isNotBlank()) {
        put("query", query)
        put("suggestedLimit", suggestedLimit.toString())
      }
    },
  )

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { loadSkillsSnapshot(query = "", suggestedLimit = 0) },
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    commandTransport.requireSkillsDispatch(
      OpenCraySkillsWriteCommand.SetSkillEnabled(skillId = skillId, enabled = enabled),
    )
  }

  override fun installSuggestedSkill(skillId: String): String =
    commandTransport.requireSkillsMessage(OpenCraySkillsWriteCommand.InstallSuggestedSkill(skillId))

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String = commandTransport.requireSkillsMessage(
    OpenCraySkillsWriteCommand.InstallSkillSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    ),
  )

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String = commandTransport.requireSkillsMessage(
    OpenCraySkillsWriteCommand.InstallSkillSourceBatch(
      sourceRef = sourceRef,
      selectedSkillNames = selectedSkillNames,
    ),
  )

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
    commandTransport.requireSkillsPayload(OpenCraySkillsWriteCommand.InspectSkillSource(sourceRef))

  override fun deleteInstalledSkill(skillId: String): String =
    commandTransport.requireSkillsMessage(OpenCraySkillsWriteCommand.DeleteInstalledSkill(skillId))

  override fun refreshSkills(): String =
    commandTransport.requireSkillsMessage(OpenCraySkillsWriteCommand.RefreshSkills)

  override fun checkInstalledSkillUpdates(skillId: String): String =
    commandTransport.requireSkillsMessage(OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates(skillId))

  override fun updateInstalledSkill(skillId: String): String =
    commandTransport.requireSkillsMessage(OpenCraySkillsWriteCommand.UpdateInstalledSkill(skillId))

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
    requestClient.getObject(
      path = "v1/skill_instructions",
      queryParameters = mapOf("skillId" to skillId),
    )

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> = requestClient.getObject(
    path = "v1/suggested_skill_instructions",
    queryParameters = buildMap {
      put("sourceRef", sourceRef)
      if (selectedSkillName.isNotBlank()) {
        put("selectedSkillName", selectedSkillName)
      }
    },
  )

  override fun activateSkillsInstallSource(sourceId: String): String =
    commandTransport.requireSkillsMessage(
      OpenCraySkillsWriteCommand.ActivateSkillsInstallSource(sourceId),
    )
}

private class LoopbackHttpOpenCraySettingsGateway(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient,
  private val commandTransport: RuntimeServiceCommandFallbackTransport,
  private val mainThreadPoster: MainThreadPoster,
  private val pollIntervalMs: Long = 2_000L,
) : OpenCraySettingsGateway {
  override fun loadSettingsOverview(): Map<String, Any?> =
    requestClient.getObject(path = "v1/settings_overview")

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadSettingsOverview,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
    requestClient.getObject(
      path = "v1/settings_detail",
      queryParameters = mapOf("routeId" to routeIdRaw),
    )

  override fun loadNotificationSettings(): Map<String, Any?> =
    requestClient.getObject(path = "v1/notification_settings")

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    commandTransport.requireSettingsPayload(OpenCraySettingsWriteCommand.SaveNotificationSettings(payload))

  override fun loadScheduledTasks(): Map<String, Any?> =
    requestClient.getObject(path = "v1/scheduled_tasks")

  override fun loadScheduledTask(scheduleId: String): Map<String, Any?> =
    requestClient.getObject(
      path = "v1/scheduled_task",
      queryParameters = mapOf("scheduleId" to scheduleId),
    )

  override fun updateScheduledTaskEnabled(
    scheduleId: String,
    enabled: Boolean,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.UpdateScheduledTaskEnabled(scheduleId, enabled),
  )

  override fun runScheduledTaskNow(scheduleId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.RunScheduledTaskNow(scheduleId),
    )

  override fun snoozeScheduledTask(
    scheduleId: String,
    durationMinutes: Int,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SnoozeScheduledTask(scheduleId, durationMinutes),
  )

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
    requestClient.getObject(path = "v1/strong_background_snapshot")

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.PerformStrongBackgroundAction(actionId),
    )

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    requestClient.getObject(path = "v1/network_search_config")

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.SaveNetworkSearchConfig(slots),
    )

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    requestClient.getObject(path = "v1/media_speech_config")

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
    commandTransport.requireSettingsPayload(OpenCraySettingsWriteCommand.SaveMediaSpeechConfig(payload))

  override fun loadSandboxSettings(): Map<String, Any?> =
    requestClient.getObject(path = "v1/sandbox_settings")

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
    commandTransport.requireSettingsPayload(OpenCraySettingsWriteCommand.SaveSandboxSettings(payload))

  override fun loadLlmConfig(): Map<String, Any?> =
    requestClient.getObject(path = "v1/llm_config")

  override fun saveLlmConfig(
    enabled: Boolean,
    streamingEnabled: Boolean?,
    providerMode: String,
    providerId: String,
    selectedProviderOptionId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
    selectedOnDeviceModelId: String,
    onDeviceMaxContextWindow: Int,
    onDeviceMaxTokens: Int,
    onDeviceTopK: Int,
    onDeviceTopP: Double,
    onDeviceTemperature: Double,
    onDeviceAccelerator: String,
    onDeviceThinkingEnabled: Boolean,
    onDeviceLiteModeEnabled: Boolean,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SaveLlmConfig(
      enabled = enabled,
      streamingEnabled = streamingEnabled,
      providerMode = providerMode,
      providerId = providerId,
      selectedProviderOptionId = selectedProviderOptionId,
      protocol = protocol,
      providerName = providerName,
      providerNotes = providerNotes,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
      systemPrompt = systemPrompt,
      openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = anthropicPromptCacheTtl,
      contextBudgetPreset = contextBudgetPreset,
      contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
      selectedOnDeviceModelId = selectedOnDeviceModelId,
      onDeviceMaxContextWindow = onDeviceMaxContextWindow,
      onDeviceMaxTokens = onDeviceMaxTokens,
      onDeviceTopK = onDeviceTopK,
      onDeviceTopP = onDeviceTopP,
      onDeviceTemperature = onDeviceTemperature,
      onDeviceAccelerator = onDeviceAccelerator,
      onDeviceThinkingEnabled = onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
    ),
  )

  override fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    streamingEnabled: Boolean?,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SaveCustomLlmProvider(
      selectedProviderOptionId = selectedProviderOptionId,
      streamingEnabled = streamingEnabled,
      protocol = protocol,
      providerName = providerName,
      providerNotes = providerNotes,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
      systemPrompt = systemPrompt,
      openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = anthropicPromptCacheTtl,
      contextBudgetPreset = contextBudgetPreset,
      contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
    ),
  )

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.ValidateLlmConfig(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
    ),
  )

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel(modelId),
    )

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload(modelId),
    )

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel(modelId),
    )

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    requestClient.getObject(path = "v1/personalization_config")

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SavePersonalizationConfig(
      presetId = presetId,
      customLabel = customLabel,
      customGuidance = customGuidance,
    ),
  )

  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(OpenCraySettingsWriteCommand.SetAppLanguage(languageId))

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
    commandTransport.requireSettingsPayload(
      OpenCraySettingsWriteCommand.RunPersonalizationReset(scopeId),
    )

  override fun loadMcpSettings(): Map<String, Any?> =
    requestClient.getObject(path = "v1/mcp_settings")

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    commandTransport.requireSettingsPayload(OpenCraySettingsWriteCommand.SetMcpMasterEnabled(enabled))

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SetMcpServerEnabled(serverId = serverId, enabled = enabled),
  )

  override fun loadSafetySettings(): Map<String, Any?> =
    requestClient.getObject(path = "v1/safety_settings")

  override fun saveSafetySettings(
    automationModeId: String,
    rollbackJournalEnabled: Boolean,
    maxFilesPerBatch: Int,
    maxAgentTurns: Int,
    maxToolCalls: Int,
    undoWindowHours: Int,
    fileChangesPolicyId: String,
    fileDeletesPolicyId: String,
    shellCommandsPolicyId: String,
    externalAccessModeId: String,
    photoLibraryEnabled: Boolean,
    downloadsEnabled: Boolean,
    documentsEnabled: Boolean,
    recordingsEnabled: Boolean,
    workspaceAccessProfileId: String,
    readOnlyOutsideWorkspace: Boolean,
    liveContextModeId: String,
    memoryToolsEnabled: Boolean,
    subAgentContextDefaultModeId: String?,
    subAgentContextProfileOverrides: Map<String, String>,
  ): Map<String, Any?> = commandTransport.requireSettingsPayload(
    OpenCraySettingsWriteCommand.SaveSafetySettings(
      automationModeId = automationModeId,
      rollbackJournalEnabled = rollbackJournalEnabled,
      maxFilesPerBatch = maxFilesPerBatch,
      maxAgentTurns = maxAgentTurns,
      maxToolCalls = maxToolCalls,
      undoWindowHours = undoWindowHours,
      fileChangesPolicyId = fileChangesPolicyId,
      fileDeletesPolicyId = fileDeletesPolicyId,
      shellCommandsPolicyId = shellCommandsPolicyId,
      externalAccessModeId = externalAccessModeId,
      photoLibraryEnabled = photoLibraryEnabled,
      downloadsEnabled = downloadsEnabled,
      documentsEnabled = documentsEnabled,
      recordingsEnabled = recordingsEnabled,
      workspaceAccessProfileId = workspaceAccessProfileId,
      readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
      liveContextModeId = liveContextModeId,
      memoryToolsEnabled = memoryToolsEnabled,
      subAgentContextDefaultModeId = subAgentContextDefaultModeId,
      subAgentContextProfileOverrides = subAgentContextProfileOverrides,
    ),
  )
}

private fun RuntimeServiceCommandFallbackTransport.requireChatDispatch(
  command: OpenCrayChatWriteCommand,
) {
  dispatchChatWriteCommand(command)
}

private fun RuntimeServiceCommandFallbackTransport.requireChatPayload(
  command: OpenCrayChatWriteCommand,
): Map<String, Any?> = when (val result = dispatchChatWriteCommand(command)) {
  is OpenCrayChatWriteDispatchResult.Payload -> result.value
    ?: error("Loopback chat transport returned a null payload for '${command::class.java.simpleName}'.")

  OpenCrayChatWriteDispatchResult.Completed,
  null,
  -> error("Loopback chat transport returned no payload for '${command::class.java.simpleName}'.")
}

private fun RuntimeServiceCommandFallbackTransport.requireChatPayloadOrNull(
  command: OpenCrayChatWriteCommand,
): Map<String, Any?>? = when (val result = dispatchChatWriteCommand(command)) {
  is OpenCrayChatWriteDispatchResult.Payload -> result.value
  OpenCrayChatWriteDispatchResult.Completed,
  null,
  -> null
}

private fun RuntimeServiceCommandFallbackTransport.requireSkillsDispatch(
  command: OpenCraySkillsWriteCommand,
) {
  dispatchSkillsWriteCommand(command)
}

private fun RuntimeServiceCommandFallbackTransport.requireSkillsMessage(
  command: OpenCraySkillsWriteCommand,
): String = when (val result = dispatchSkillsWriteCommand(command)) {
  is OpenCraySkillsWriteDispatchResult.Message -> result.value
  OpenCraySkillsWriteDispatchResult.Completed,
  is OpenCraySkillsWriteDispatchResult.Payload,
  null,
  -> error("Loopback skills transport returned no message for '${command::class.java.simpleName}'.")
}

private fun RuntimeServiceCommandFallbackTransport.requireSkillsPayload(
  command: OpenCraySkillsWriteCommand,
): Map<String, Any?> = when (val result = dispatchSkillsWriteCommand(command)) {
  is OpenCraySkillsWriteDispatchResult.Payload -> result.value
  OpenCraySkillsWriteDispatchResult.Completed,
  is OpenCraySkillsWriteDispatchResult.Message,
  null,
  -> error("Loopback skills transport returned no payload for '${command::class.java.simpleName}'.")
}

private fun RuntimeServiceCommandFallbackTransport.requireSettingsPayload(
  command: OpenCraySettingsWriteCommand,
): Map<String, Any?> = when (val result = dispatchSettingsWriteCommand(command)) {
  is OpenCraySettingsWriteDispatchResult.Payload -> result.value
  null -> error("Loopback settings transport returned no payload for '${command::class.java.simpleName}'.")
}
