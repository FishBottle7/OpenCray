package com.opencray.app

import com.opencray.runtime.OpenCrayFinalAttachment
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
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
  requestClient: OpenCrayLocalRuntimeLoopbackHttpClient = OpenCrayLocalRuntimeLoopbackHttpClient(),
  mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
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

internal class LoopbackHttpRuntimeServiceCommandFallbackTransport(
  private val requestClient: OpenCrayLocalRuntimeLoopbackHttpClient = OpenCrayLocalRuntimeLoopbackHttpClient(),
) : RuntimeServiceCommandFallbackTransport {
  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = when (command) {
    OpenCrayChatWriteCommand.RefreshSandboxSessionInfo -> {
      requestClient.post(path = "v1/refresh_sandbox_session_info")
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.ApplyMemoryDebugAction -> OpenCrayChatWriteDispatchResult.Payload(
      requestClient.postObject(
        path = "v1/memory_debug_action",
        body = JSONObject()
          .put("recordId", command.recordId)
          .put("actionId", command.actionId),
      ),
    )

    OpenCrayChatWriteCommand.CreateChatSession -> {
      requestClient.post(path = "v1/create_chat_session")
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.CopyChatSession -> {
      requestClient.post(
        path = "v1/copy_chat_session",
        body = JSONObject().put("sessionId", command.sessionId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.DeleteChatSession -> {
      requestClient.post(
        path = "v1/delete_chat_session",
        body = JSONObject().put("sessionId", command.sessionId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.SelectChatSession -> {
      requestClient.post(
        path = "v1/select_chat_session",
        body = JSONObject().put("sessionId", command.sessionId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.BranchChatSessionFromMessage -> {
      requestClient.post(
        path = "v1/branch_chat_session_from_message",
        body = JSONObject()
          .put("sessionId", command.sessionId)
          .put("messageId", command.messageId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.DeleteChatMessage -> {
      requestClient.post(
        path = "v1/delete_chat_message",
        body = JSONObject()
          .put("sessionId", command.sessionId)
          .put("messageId", command.messageId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.RecallChatMessage -> {
      requestClient.post(
        path = "v1/recall_chat_message",
        body = JSONObject()
          .put("sessionId", command.sessionId)
          .put("messageId", command.messageId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.SubmitChatMessage -> OpenCrayChatWriteDispatchResult.Payload(
      requestClient.postObjectOrNull(
        path = "v1/submit_chat_message",
        body = JSONObject()
          .put("text", command.text)
          .put(
            "attachments",
            JSONArray().apply {
              command.attachments.forEach { attachment ->
                put(attachment.toJson())
              }
            },
          ),
      ),
    )

    is OpenCrayChatWriteCommand.ApproveChatApproval -> {
      requestClient.post(
        path = "v1/approve_chat_approval",
        body = approvalBody(command.taskIdOrRunId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> {
      requestClient.post(
        path = "v1/approve_chat_approval_for_session",
        body = approvalBody(command.taskIdOrRunId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.RejectChatApproval -> {
      requestClient.post(
        path = "v1/reject_chat_approval",
        body = approvalBody(command.taskIdOrRunId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.InterruptChatRun -> {
      requestClient.post(
        path = "v1/interrupt_chat_run",
        body = approvalBody(command.taskIdOrRunId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.RetryChatRun -> {
      requestClient.post(
        path = "v1/retry_chat_run",
        body = approvalBody(command.taskIdOrRunId),
      )
      OpenCrayChatWriteDispatchResult.Completed
    }
  }

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult = when (command) {
    is OpenCraySkillsWriteCommand.SetSkillEnabled -> {
      requestClient.post(
        path = "v1/set_skill_enabled",
        body = JSONObject()
          .put("skillId", command.skillId)
          .put("enabled", command.enabled),
      )
      OpenCraySkillsWriteDispatchResult.Completed
    }

    is OpenCraySkillsWriteCommand.InstallSuggestedSkill -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/install_suggested_skill",
        body = JSONObject().put("skillId", command.skillId),
      ),
    )

    is OpenCraySkillsWriteCommand.InstallSkillSource -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/install_skill_source",
        body = JSONObject()
          .put("sourceRef", command.sourceRef)
          .put("selectedSkillName", command.selectedSkillName),
      ),
    )

    is OpenCraySkillsWriteCommand.InstallSkillSourceBatch -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/install_skill_source_batch",
        body = JSONObject()
          .put("sourceRef", command.sourceRef)
          .put("selectedSkillNames", JSONArray(command.selectedSkillNames)),
      ),
    )

    is OpenCraySkillsWriteCommand.InspectSkillSource -> OpenCraySkillsWriteDispatchResult.Payload(
      requestClient.postObject(
        path = "v1/inspect_skill_source",
        body = JSONObject().put("sourceRef", command.sourceRef),
      ),
    )

    is OpenCraySkillsWriteCommand.DeleteInstalledSkill -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/delete_installed_skill",
        body = JSONObject().put("skillId", command.skillId),
      ),
    )

    OpenCraySkillsWriteCommand.RefreshSkills -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(path = "v1/refresh_skills"),
    )

    is OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.getString(
        path = "v1/check_installed_skill_updates",
        queryParameters = mapOf("skillId" to command.skillId),
      ),
    )

    is OpenCraySkillsWriteCommand.UpdateInstalledSkill -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/update_installed_skill",
        body = JSONObject().put("skillId", command.skillId),
      ),
    )

    is OpenCraySkillsWriteCommand.ActivateSkillsInstallSource -> OpenCraySkillsWriteDispatchResult.Message(
      requestClient.postString(
        path = "v1/activate_skills_install_source",
        body = JSONObject().put("sourceId", command.sourceId),
      ),
    )
  }

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult = when (command) {
    is OpenCraySettingsWriteCommand.SaveNotificationSettings -> payloadResult(
      requestClient.postObject(
        path = "v1/save_notification_settings",
        body = command.payload.toJsonObject(),
      ),
    )

    is OpenCraySettingsWriteCommand.PerformStrongBackgroundAction -> payloadResult(
      requestClient.postObject(
        path = "v1/perform_strong_background_action",
        body = JSONObject().put("actionId", command.actionId),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveNetworkSearchConfig -> payloadResult(
      requestClient.postObject(
        path = "v1/save_network_search_config",
        body = JSONObject().put("slots", command.slots.toJsonArray()),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveMediaSpeechConfig -> payloadResult(
      requestClient.postObject(
        path = "v1/save_media_speech_config",
        body = command.payload.toJsonObject(),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveSandboxSettings -> payloadResult(
      requestClient.postObject(
        path = "v1/save_sandbox_settings",
        body = command.payload.toJsonObject(),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveLlmConfig -> payloadResult(
      requestClient.postObject(
        path = "v1/save_llm_config",
        body = JSONObject()
          .put("enabled", command.enabled)
          .putIfNotNull("streamingEnabled", command.streamingEnabled)
          .put("providerMode", command.providerMode)
          .put("providerId", command.providerId)
          .put("selectedProviderOptionId", command.selectedProviderOptionId)
          .put("protocol", command.protocol)
          .put("providerName", command.providerName)
          .put("providerNotes", command.providerNotes)
          .put("baseUrl", command.baseUrl)
          .put("apiKey", command.apiKey)
          .put("model", command.model)
          .put("reasoningEffort", command.reasoningEffort)
          .put("systemPrompt", command.systemPrompt)
          .putIfNotNull("openAiPromptCacheKeyStrategy", command.openAiPromptCacheKeyStrategy)
          .putIfNotNull("openAiPromptCacheRetention", command.openAiPromptCacheRetention)
          .putIfNotNull("anthropicPromptCachingEnabled", command.anthropicPromptCachingEnabled)
          .putIfNotNull("anthropicPromptCacheTtl", command.anthropicPromptCacheTtl)
          .putIfNotNull("contextBudgetPreset", command.contextBudgetPreset)
          .putIfNotNull(
            "contextBudgetReservedOutputTokens",
            command.contextBudgetReservedOutputTokens,
          )
          .putIfNotNull(
            "contextBudgetSafetyMarginTokens",
            command.contextBudgetSafetyMarginTokens,
          )
          .putIfNotNull(
            "contextBudgetEffectiveInputPercent",
            command.contextBudgetEffectiveInputPercent,
          )
          .put("selectedOnDeviceModelId", command.selectedOnDeviceModelId)
          .put("onDeviceMaxContextWindow", command.onDeviceMaxContextWindow)
          .put("onDeviceMaxTokens", command.onDeviceMaxTokens)
          .put("onDeviceTopK", command.onDeviceTopK)
          .put("onDeviceTopP", command.onDeviceTopP)
          .put("onDeviceTemperature", command.onDeviceTemperature)
          .put("onDeviceAccelerator", command.onDeviceAccelerator)
          .put("onDeviceThinkingEnabled", command.onDeviceThinkingEnabled)
          .put("onDeviceLiteModeEnabled", command.onDeviceLiteModeEnabled),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveCustomLlmProvider -> payloadResult(
      requestClient.postObject(
        path = "v1/save_custom_llm_provider",
        body = JSONObject()
          .put("selectedProviderOptionId", command.selectedProviderOptionId)
          .putIfNotNull("streamingEnabled", command.streamingEnabled)
          .put("protocol", command.protocol)
          .put("providerName", command.providerName)
          .put("providerNotes", command.providerNotes)
          .put("baseUrl", command.baseUrl)
          .put("apiKey", command.apiKey)
          .put("model", command.model)
          .put("reasoningEffort", command.reasoningEffort)
          .put("systemPrompt", command.systemPrompt)
          .putIfNotNull("openAiPromptCacheKeyStrategy", command.openAiPromptCacheKeyStrategy)
          .putIfNotNull("openAiPromptCacheRetention", command.openAiPromptCacheRetention)
          .putIfNotNull("anthropicPromptCachingEnabled", command.anthropicPromptCachingEnabled)
          .putIfNotNull("anthropicPromptCacheTtl", command.anthropicPromptCacheTtl),
      ),
    )

    is OpenCraySettingsWriteCommand.ValidateLlmConfig -> payloadResult(
      requestClient.postObject(
        path = "v1/validate_llm_config",
        body = JSONObject()
          .put("providerId", command.providerId)
          .put("protocol", command.protocol)
          .put("baseUrl", command.baseUrl)
          .put("apiKey", command.apiKey)
          .put("model", command.model)
          .put("reasoningEffort", command.reasoningEffort),
      ),
    )

    is OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel -> payloadResult(
      requestClient.postObject(
        path = "v1/download_on_device_llm_model",
        body = JSONObject().put("modelId", command.modelId),
      ),
    )

    is OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload -> payloadResult(
      requestClient.postObject(
        path = "v1/cancel_on_device_llm_model_download",
        body = JSONObject().put("modelId", command.modelId),
      ),
    )

    is OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel -> payloadResult(
      requestClient.postObject(
        path = "v1/delete_on_device_llm_model",
        body = JSONObject().put("modelId", command.modelId),
      ),
    )

    is OpenCraySettingsWriteCommand.SavePersonalizationConfig -> payloadResult(
      requestClient.postObject(
        path = "v1/save_personalization_config",
        body = JSONObject()
          .put("presetId", command.presetId)
          .put("customLabel", command.customLabel)
          .put("customGuidance", command.customGuidance),
      ),
    )

    is OpenCraySettingsWriteCommand.SetAppLanguage -> payloadResult(
      requestClient.postObject(
        path = "v1/set_app_language",
        body = JSONObject().put("languageId", command.languageId),
      ),
    )

    is OpenCraySettingsWriteCommand.RunPersonalizationReset -> payloadResult(
      requestClient.postObject(
        path = "v1/run_personalization_reset",
        body = JSONObject().put("scopeId", command.scopeId),
      ),
    )

    is OpenCraySettingsWriteCommand.SetMcpMasterEnabled -> payloadResult(
      requestClient.postObject(
        path = "v1/set_mcp_master_enabled",
        body = JSONObject().put("enabled", command.enabled),
      ),
    )

    is OpenCraySettingsWriteCommand.SetMcpServerEnabled -> payloadResult(
      requestClient.postObject(
        path = "v1/set_mcp_server_enabled",
        body = JSONObject()
          .put("serverId", command.serverId)
          .put("enabled", command.enabled),
      ),
    )

    is OpenCraySettingsWriteCommand.SaveSafetySettings -> payloadResult(
      requestClient.postObject(
        path = "v1/save_safety_settings",
        body = JSONObject()
          .put("automationModeId", command.automationModeId)
          .put("rollbackJournalEnabled", command.rollbackJournalEnabled)
          .put("maxFilesPerBatch", command.maxFilesPerBatch)
          .put("maxAgentTurns", command.maxAgentTurns)
          .put("maxToolCalls", command.maxToolCalls)
          .put("undoWindowHours", command.undoWindowHours)
          .put("fileChangesPolicyId", command.fileChangesPolicyId)
          .put("fileDeletesPolicyId", command.fileDeletesPolicyId)
          .put("shellCommandsPolicyId", command.shellCommandsPolicyId)
          .put("externalAccessModeId", command.externalAccessModeId)
          .put("photoLibraryEnabled", command.photoLibraryEnabled)
          .put("downloadsEnabled", command.downloadsEnabled)
          .put("documentsEnabled", command.documentsEnabled)
          .put("recordingsEnabled", command.recordingsEnabled)
          .put("workspaceAccessProfileId", command.workspaceAccessProfileId)
          .put("readOnlyOutsideWorkspace", command.readOnlyOutsideWorkspace)
          .put("liveContextModeId", command.liveContextModeId)
          .put("memoryToolsEnabled", command.memoryToolsEnabled)
          .putIfNotNull("subAgentContextDefaultModeId", command.subAgentContextDefaultModeId)
          .put(
            "subAgentContextProfileOverrides",
            command.subAgentContextProfileOverrides.toJsonObject(),
          ),
      ),
    )
  }

  private fun approvalBody(taskIdOrRunId: String): JSONObject = JSONObject()
    .put("runId", taskIdOrRunId)
    .put("taskId", taskIdOrRunId)

  private fun payloadResult(value: Map<String, Any?>): OpenCraySettingsWriteDispatchResult =
    OpenCraySettingsWriteDispatchResult.Payload(value)
}

internal class OpenCrayLocalRuntimeLoopbackHttpClient(
  private val baseUrlProvider: () -> String = {
    "http://127.0.0.1:${OpenCrayLocalRuntimeServer.DEFAULT_PORT}/"
  },
  private val bootstrapTimeoutMs: Long = 1_500L,
  private val retryDelayMs: Long = 100L,
  private val connectTimeoutMs: Int = 300,
  private val readTimeoutMs: Int = 60_000,
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
    val deadlineAt = System.currentTimeMillis() + bootstrapTimeoutMs
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
      if (System.currentTimeMillis() >= deadlineAt) {
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
    val connection = (URL(buildUrl(path, queryParameters)).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      doInput = true
      setRequestProperty("Accept", "application/json")
      if (body != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
    }
    return try {
      if (body != null) {
        connection.outputStream.use { output ->
          output.write(body.toString().toByteArray(Charsets.UTF_8))
        }
      }
      val statusCode = connection.responseCode
      val responseBody = readResponseBody(connection, statusCode)
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
    path: String,
    queryParameters: Map<String, String>,
  ): String {
    val normalizedBaseUrl = baseUrlProvider().trim().ifBlank {
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

  private fun parseJsonPayload(rawBody: String): Any? {
    if (rawBody.isBlank()) {
      return null
    }
    return jsonValueToKotlin(JSONTokener(rawBody).nextValue())
  }

  private fun readResponseBody(
    connection: HttpURLConnection,
    statusCode: Int,
  ): String =
    (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
      ?.bufferedReader(Charsets.UTF_8)
      ?.use { reader -> reader.readText() }
      .orEmpty()

  private fun formatErrorSuffix(rawBody: String): String {
    val parsed = runCatching { parseJsonPayload(rawBody) }.getOrNull()
    val message = (parsed as? Map<*, *>)?.get("error") as? String
    return when {
      !message.isNullOrBlank() -> ": $message"
      rawBody.isBlank() -> ""
      else -> ": ${rawBody.take(200)}"
    }
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

private fun Map<String, *>.toJsonObject(): JSONObject = JSONObject().apply {
  entries.forEach { (key, value) ->
    put(key, value.toJsonValue())
  }
}

private fun List<Map<String, *>>.toJsonArray(): JSONArray = JSONArray().apply {
  forEach { entry ->
    put(entry.toJsonObject())
  }
}

private fun JSONObject.putIfNotNull(
  key: String,
  value: Any?,
): JSONObject = apply {
  if (value != null) {
    put(key, value)
  }
}

private fun Any?.toJsonValue(): Any? = when (this) {
  null -> JSONObject.NULL
  is Map<*, *> -> JSONObject().apply {
    entries.forEach { (key, value) ->
      if (key != null) {
        put(key.toString(), value.toJsonValue())
      }
    }
  }

  is List<*> -> JSONArray().apply {
    forEach { item ->
      put(item.toJsonValue())
    }
  }

  else -> this
}

private fun OpenCrayFinalAttachment.toJson(): JSONObject = JSONObject().apply {
  putIfNotNull("kind", kind)
  putIfNotNull("relativePath", relativePath)
  putIfNotNull("path", path)
  putIfNotNull("artifactId", artifactId)
  putIfNotNull("chatAttachmentId", chatAttachmentId)
  putIfNotNull("displayName", displayName)
  putIfNotNull("mimeType", mimeType)
  putIfNotNull("durationMs", durationMs)
  if (waveformBars.isNotEmpty()) {
    put("waveformBars", JSONArray(waveformBars))
  }
  putIfNotNull("transcriptText", transcriptText)
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
