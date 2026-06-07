package com.opencray.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.opencray.runtime.OpenCrayFinalAttachment
import org.json.JSONArray
import org.json.JSONObject

private val DEFAULT_LOCAL_RUNTIME_LOOPBACK_ADDRESS: InetAddress = InetAddress.getByName("127.0.0.1")

private fun InetAddress.asRuntimeBindAddress(): String = hostAddress ?: hostName ?: "127.0.0.1"

internal data class LocalRuntimeServerState(
  val phase: String = PHASE_NOT_CREATED,
  val bindAddress: String,
  val requestedPort: Int,
  val listeningPort: Int? = null,
  val lastStartAttemptAtEpochMs: Long? = null,
  val lastStartedAtEpochMs: Long? = null,
  val failureReason: String? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("bindAddress", bindAddress)
    put("requestedPort", requestedPort)
    put("listeningPort", listeningPort)
    put("lastStartAttemptAtEpochMs", lastStartAttemptAtEpochMs)
    put("lastStartedAtEpochMs", lastStartedAtEpochMs)
    failureReason?.trim()?.takeIf(String::isNotBlank)?.let { reason ->
      put("failureReason", reason)
    }
    put("changedAtEpochMs", changedAtEpochMs)
  }

  companion object {
    const val PHASE_NOT_CREATED: String = "not_created"
    const val PHASE_CREATED: String = "created"
    const val PHASE_LISTENING: String = "listening"
    const val PHASE_BIND_FAILED: String = "bind_failed"
    const val PHASE_IO_FAILED: String = "io_failed"
    const val PHASE_CLOSED: String = "closed"
  }
}

internal class OpenCrayLocalRuntimeServer(
  private val localGatewayProvider: () -> OpenCrayLocalHostGateway,
  private val shellGatewayProvider: () -> OpenCrayShellGateway,
  private val chatRuntimeGatewayProvider: () -> OpenCrayChatRuntimeGateway,
  private val skillsGatewayProvider: () -> OpenCraySkillsGateway,
  private val settingsGatewayProvider: () -> OpenCraySettingsGateway,
  private val requestedPort: Int = DEFAULT_PORT,
  private val bindAddress: InetAddress = DEFAULT_LOCAL_RUNTIME_LOOPBACK_ADDRESS,
  private val executor: ExecutorService = Executors.newCachedThreadPool(),
  private val shutdownExecutorOnClose: Boolean = false,
) {
  @Volatile
  private var serverSocket: ServerSocket? = null

  @Volatile
  var listeningPort: Int = requestedPort
    private set

  private var serverState: LocalRuntimeServerState = LocalRuntimeServerState(
    phase = LocalRuntimeServerState.PHASE_CREATED,
    bindAddress = bindAddress.asRuntimeBindAddress(),
    requestedPort = requestedPort,
  )

  fun currentState(): LocalRuntimeServerState = synchronized(this) { serverState }

  fun ensureStarted() {
    if (serverSocket != null) {
      return
    }
    synchronized(this) {
      if (serverSocket != null) {
        return
      }
      val startAttemptAtEpochMs = System.currentTimeMillis()
      serverState = serverState.copy(
        lastStartAttemptAtEpochMs = startAttemptAtEpochMs,
        failureReason = null,
        changedAtEpochMs = startAttemptAtEpochMs,
      )
      try {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, requestedPort))
        listeningPort = socket.localPort
        serverSocket = socket
        val startedAtEpochMs = System.currentTimeMillis()
        serverState = serverState.copy(
          phase = LocalRuntimeServerState.PHASE_LISTENING,
          listeningPort = listeningPort,
          lastStartedAtEpochMs = startedAtEpochMs,
          failureReason = null,
          changedAtEpochMs = startedAtEpochMs,
        )
        executor.execute {
          acceptLoop(socket)
        }
      } catch (throwable: BindException) {
        serverState = serverState.copy(
          phase = LocalRuntimeServerState.PHASE_BIND_FAILED,
          listeningPort = null,
          failureReason = throwable.message ?: "bind_exception",
          changedAtEpochMs = System.currentTimeMillis(),
        )
        return
      } catch (throwable: IOException) {
        serverState = serverState.copy(
          phase = LocalRuntimeServerState.PHASE_IO_FAILED,
          listeningPort = null,
          failureReason = throwable.message ?: "io_exception",
          changedAtEpochMs = System.currentTimeMillis(),
        )
        return
      }
    }
  }

  fun close() {
    val socket = synchronized(this) {
      serverSocket.also {
        serverSocket = null
      }
    }
    runCatching {
      socket?.close()
    }
    synchronized(this) {
      serverState = serverState.copy(
        phase = LocalRuntimeServerState.PHASE_CLOSED,
        listeningPort = null,
        changedAtEpochMs = System.currentTimeMillis(),
      )
    }
    if (shutdownExecutorOnClose) {
      executor.shutdownNow()
    }
  }

  private fun acceptLoop(socket: ServerSocket) {
    while (!socket.isClosed) {
      val client = try {
        socket.accept()
      } catch (_: SocketException) {
        return
      } catch (_: IOException) {
        return
      }
      executor.execute {
        handleClient(client)
      }
    }
  }

  private fun handleClient(socket: Socket) {
    socket.use { client ->
      client.soTimeout = SOCKET_TIMEOUT_MS
      val request = try {
        parseRequest(client.getInputStream()) ?: return
      } catch (throwable: Throwable) {
        writeResponse(
          client,
          LocalRuntimeResponse(
            statusCode = 400,
            body = mapOf("error" to (throwable.message ?: "Malformed request.")),
          ),
        )
        return
      }
      val response = try {
        dispatch(request)
      } catch (throwable: Throwable) {
        LocalRuntimeResponse(
          statusCode = 500,
          body = mapOf("error" to (throwable.message ?: throwable::class.java.simpleName)),
        )
      }
      writeResponse(client, response)
    }
  }

  private fun dispatch(request: LocalRuntimeRequest): LocalRuntimeResponse {
    val localGateway = localGatewayProvider()
    val shellGateway = shellGatewayProvider()
    val chatRuntimeGateway = chatRuntimeGatewayProvider()
    val skillsGateway = skillsGatewayProvider()
    val settingsGateway = settingsGatewayProvider()
    val body = request.jsonBody()
    val payload: Any? = when (request.method to request.path) {
      "GET" to "/v1/shell_snapshot" -> shellGateway.loadShellSnapshot()
      "POST" to "/v1/save_shell_destination" -> {
        shellGateway.saveShellDestination(
          selectedTab = body.optString("selectedTab"),
          settingsSubpage = body.optString("settingsSubpage").takeIf(String::isNotBlank),
        )
        null
      }
      "GET" to "/v1/files_snapshot" -> localGateway.loadFilesSnapshot()
      "POST" to "/v1/resolve_sandbox_preview_embed_config" -> localGateway.resolveSandboxPreviewEmbedConfig(
        previewUrl = body.optString("previewUrl"),
      )
      "GET" to "/v1/settings_image_assets" -> localGateway.listSettingsImageAssets()
      "GET" to "/v1/soul_visual_identity" -> localGateway.loadSoulVisualIdentity()
      "GET" to "/v1/memory_image_references" -> localGateway.listMemoryImageReferences(
        memoryId = request.queryParameter("memoryId"),
      )
      "GET" to "/v1/workspace_image_preview" -> localGateway.loadWorkspaceImagePreview(
        relativePath = request.queryParameter("relativePath"),
      )
      "GET" to "/v1/workspace_text_preview" -> localGateway.loadWorkspaceTextPreview(
        relativePath = request.queryParameter("relativePath"),
      )
      "GET" to "/v1/workspace_voice_playback_source" -> localGateway.loadWorkspaceVoicePlaybackSource(
        relativePath = request.queryParameter("relativePath"),
      )
      "GET" to "/v1/workspace_text_document" -> localGateway.loadWorkspaceTextDocument(
        relativePath = request.queryParameter("relativePath"),
      )
      "POST" to "/v1/open_workspace_entry" -> {
        localGateway.openWorkspaceEntry(
          relativePath = body.optString("relativePath"),
        )
        null
      }
      "POST" to "/v1/open_external_uri" -> {
        localGateway.openExternalUri(
          uri = body.optString("uri"),
        )
        null
      }
      "POST" to "/v1/create_workspace_folder" -> localGateway.createWorkspaceFolder(
        parentRelativePath = body.optString("parentRelativePath"),
        name = body.optString("name"),
      )
      "POST" to "/v1/create_workspace_text_file" -> localGateway.createWorkspaceTextFile(
        parentRelativePath = body.optString("parentRelativePath"),
        name = body.optString("name"),
      )
      "POST" to "/v1/rename_workspace_entry" -> localGateway.renameWorkspaceEntry(
        targetRelativePath = body.optString("targetRelativePath"),
        newName = body.optString("newName"),
      )
      "POST" to "/v1/delete_workspace_entries" -> localGateway.deleteWorkspaceEntries(
        relativePaths = body.optJSONArray("relativePaths")?.let(::jsonArrayToStrings) ?: emptyList(),
      )
      "POST" to "/v1/save_workspace_text_document" -> localGateway.saveWorkspaceTextDocument(
        targetRelativePath = body.optString("targetRelativePath"),
        content = body.optString("content"),
      )
      "POST" to "/v1/paste_workspace_entries" -> localGateway.pasteWorkspaceEntries(
        sourceRelativePaths = body.optJSONArray("sourceRelativePaths")?.let(::jsonArrayToStrings)
          ?: emptyList(),
        destinationRelativePath = body.optString("destinationRelativePath"),
        move = body.optBoolean("move"),
      )
      "POST" to "/v1/share_workspace_entries" -> {
        localGateway.shareWorkspaceEntries(
          relativePaths = body.optJSONArray("relativePaths")?.let(::jsonArrayToStrings) ?: emptyList(),
        )
        null
      }
      "POST" to "/v1/save_workspace_media_attachment" -> localGateway.saveWorkspaceMediaAttachment(
        relativePath = body.optString("relativePath"),
        kind = body.optString("kind"),
      )
      "GET" to "/v1/settings_overview" -> settingsGateway.loadSettingsOverview()
      "GET" to "/v1/settings_detail" -> settingsGateway.loadSettingsDetail(
        routeIdRaw = request.queryParameter("routeId"),
      )
      "GET" to "/v1/notification_settings" -> settingsGateway.loadNotificationSettings()
      "POST" to "/v1/save_notification_settings" -> settingsGateway.saveNotificationSettings(
        payload = jsonObjectToMap(body),
      )
      "GET" to "/v1/strong_background_snapshot" -> settingsGateway.loadStrongBackgroundSnapshot()
      "POST" to "/v1/perform_strong_background_action" -> settingsGateway.performStrongBackgroundAction(
        actionId = body.optString("actionId"),
      )
      "GET" to "/v1/network_search_config" -> settingsGateway.loadNetworkSearchConfig()
      "POST" to "/v1/save_network_search_config" -> settingsGateway.saveNetworkSearchConfig(
        slots = body.optJSONArray("slots")?.let(::jsonArrayToMaps) ?: emptyList(),
      )
      "GET" to "/v1/media_speech_config" -> settingsGateway.loadMediaSpeechConfig()
      "POST" to "/v1/save_media_speech_config" -> settingsGateway.saveMediaSpeechConfig(
        payload = jsonObjectToMap(body),
      )
      "GET" to "/v1/sandbox_settings" -> settingsGateway.loadSandboxSettings()
      "POST" to "/v1/save_sandbox_settings" -> settingsGateway.saveSandboxSettings(
        payload = jsonObjectToMap(body),
      )
      "GET" to "/v1/llm_config" -> settingsGateway.loadLlmConfig()
      "POST" to "/v1/save_llm_config" -> settingsGateway.saveLlmConfig(
        enabled = body.optBoolean("enabled"),
        streamingEnabled = if (body.has("streamingEnabled")) {
          body.optBoolean("streamingEnabled")
        } else {
          null
        },
        providerMode = body.optString("providerMode", LlmProviderModes.CLOUD),
        providerId = body.optString("providerId"),
        selectedProviderOptionId = body.optString("selectedProviderOptionId"),
        protocol = body.optString("protocol"),
        providerName = body.optString("providerName"),
        providerNotes = body.optString("providerNotes"),
        baseUrl = body.optString("baseUrl"),
        apiKey = body.optString("apiKey"),
        model = body.optString("model"),
        reasoningEffort = body.optString("reasoningEffort"),
        systemPrompt = body.optString("systemPrompt"),
        openAiPromptCacheKeyStrategy = if (body.has("openAiPromptCacheKeyStrategy")) {
          body.optString("openAiPromptCacheKeyStrategy")
        } else {
          null
        },
        openAiPromptCacheRetention = if (body.has("openAiPromptCacheRetention")) {
          body.optString("openAiPromptCacheRetention")
        } else {
          null
        },
        anthropicPromptCachingEnabled = if (body.has("anthropicPromptCachingEnabled")) {
          body.optBoolean("anthropicPromptCachingEnabled")
        } else {
          null
        },
        anthropicPromptCacheTtl = if (body.has("anthropicPromptCacheTtl")) {
          body.optString("anthropicPromptCacheTtl")
        } else {
          null
        },
        contextBudgetPreset = if (body.has("contextBudgetPreset")) {
          body.optString("contextBudgetPreset")
        } else {
          null
        },
        contextBudgetReservedOutputTokens =
          body.takeIf { !it.isNull("contextBudgetReservedOutputTokens") }
            ?.optInt("contextBudgetReservedOutputTokens"),
        contextBudgetSafetyMarginTokens =
          body.takeIf { !it.isNull("contextBudgetSafetyMarginTokens") }
            ?.optInt("contextBudgetSafetyMarginTokens"),
        contextBudgetEffectiveInputPercent =
          body.takeIf { !it.isNull("contextBudgetEffectiveInputPercent") }
            ?.optDouble("contextBudgetEffectiveInputPercent"),
        selectedOnDeviceModelId = body.optString(
          "selectedOnDeviceModelId",
          LlmSettingsState.DEFAULT_ON_DEVICE_MODEL_ID,
        ),
        onDeviceMaxContextWindow = body.optInt(
          "onDeviceMaxContextWindow",
          LlmSettingsState.DEFAULT_ON_DEVICE_MAX_CONTEXT_WINDOW,
        ),
        onDeviceMaxTokens = body.optInt(
          "onDeviceMaxTokens",
          LlmSettingsState.DEFAULT_ON_DEVICE_MAX_TOKENS,
        ),
        onDeviceTopK = body.optInt(
          "onDeviceTopK",
          LlmSettingsState.DEFAULT_ON_DEVICE_TOP_K,
        ),
        onDeviceTopP = body.optDouble(
          "onDeviceTopP",
          LlmSettingsState.DEFAULT_ON_DEVICE_TOP_P,
        ),
        onDeviceTemperature = body.optDouble(
          "onDeviceTemperature",
          LlmSettingsState.DEFAULT_ON_DEVICE_TEMPERATURE,
        ),
        onDeviceAccelerator = body.optString(
          "onDeviceAccelerator",
          LlmSettingsState.DEFAULT_ON_DEVICE_ACCELERATOR,
        ),
        onDeviceThinkingEnabled = body.optBoolean(
          "onDeviceThinkingEnabled",
          LlmSettingsState.DEFAULT_ON_DEVICE_THINKING_ENABLED,
        ),
        onDeviceLiteModeEnabled = body.optBoolean(
          "onDeviceLiteModeEnabled",
          LlmSettingsState.DEFAULT_ON_DEVICE_LITE_MODE_ENABLED,
        ),
      )
      "POST" to "/v1/save_custom_llm_provider" -> settingsGateway.saveCustomLlmProvider(
        selectedProviderOptionId = body.optString("selectedProviderOptionId"),
        streamingEnabled = if (body.has("streamingEnabled")) {
          body.optBoolean("streamingEnabled")
        } else {
          null
        },
        protocol = body.optString("protocol"),
        providerName = body.optString("providerName"),
        providerNotes = body.optString("providerNotes"),
        baseUrl = body.optString("baseUrl"),
        apiKey = body.optString("apiKey"),
        model = body.optString("model"),
        reasoningEffort = body.optString("reasoningEffort"),
        systemPrompt = body.optString("systemPrompt"),
        openAiPromptCacheKeyStrategy = if (body.has("openAiPromptCacheKeyStrategy")) {
          body.optString("openAiPromptCacheKeyStrategy")
        } else {
          null
        },
        openAiPromptCacheRetention = if (body.has("openAiPromptCacheRetention")) {
          body.optString("openAiPromptCacheRetention")
        } else {
          null
        },
        anthropicPromptCachingEnabled = if (body.has("anthropicPromptCachingEnabled")) {
          body.optBoolean("anthropicPromptCachingEnabled")
        } else {
          null
        },
        anthropicPromptCacheTtl = if (body.has("anthropicPromptCacheTtl")) {
          body.optString("anthropicPromptCacheTtl")
        } else {
          null
        },
        contextBudgetPreset = if (body.has("contextBudgetPreset")) {
          body.optString("contextBudgetPreset")
        } else {
          null
        },
        contextBudgetReservedOutputTokens =
          body.takeIf { !it.isNull("contextBudgetReservedOutputTokens") }
            ?.optInt("contextBudgetReservedOutputTokens"),
        contextBudgetSafetyMarginTokens =
          body.takeIf { !it.isNull("contextBudgetSafetyMarginTokens") }
            ?.optInt("contextBudgetSafetyMarginTokens"),
        contextBudgetEffectiveInputPercent =
          body.takeIf { !it.isNull("contextBudgetEffectiveInputPercent") }
            ?.optDouble("contextBudgetEffectiveInputPercent"),
      )
      "POST" to "/v1/validate_llm_config" -> settingsGateway.validateLlmConfig(
        providerId = body.optString("providerId"),
        protocol = body.optString("protocol"),
        baseUrl = body.optString("baseUrl"),
        apiKey = body.optString("apiKey"),
        model = body.optString("model"),
        reasoningEffort = body.optString("reasoningEffort"),
      )
      "POST" to "/v1/download_on_device_llm_model" -> settingsGateway.downloadOnDeviceLlmModel(
        modelId = body.optString("modelId"),
      )
      "POST" to "/v1/cancel_on_device_llm_model_download" -> settingsGateway.cancelOnDeviceLlmModelDownload(
        modelId = body.optString("modelId"),
      )
      "POST" to "/v1/delete_on_device_llm_model" -> settingsGateway.deleteOnDeviceLlmModel(
        modelId = body.optString("modelId"),
      )
      "GET" to "/v1/personalization_config" -> settingsGateway.loadPersonalizationConfig()
      "POST" to "/v1/save_personalization_config" -> settingsGateway.savePersonalizationConfig(
        presetId = body.optString("presetId"),
        customLabel = body.optString("customLabel"),
        customGuidance = body.optString("customGuidance"),
      )
      "POST" to "/v1/set_app_language" -> settingsGateway.setAppLanguage(
        languageId = body.optString("languageId"),
      )
      "POST" to "/v1/run_personalization_reset" -> settingsGateway.runPersonalizationReset(
        scopeId = body.optString("scopeId"),
      )
      "POST" to "/v1/probe_twin_import_source" -> localGateway.probeTwinImportSource(
        filePath = body.optString("filePath"),
      )
      "POST" to "/v1/import_settings_image_assets" -> localGateway.importSettingsImageAssets(
        uriStrings = body.optJSONArray("uriStrings")?.let(::jsonArrayToStrings) ?: emptyList(),
      )
      "POST" to "/v1/save_soul_primary_portrait" -> localGateway.saveSoulPrimaryPortrait(
        source = body.optJSONObject("source")?.let(::jsonObjectToMap) ?: emptyMap(),
      )
      "POST" to "/v1/save_soul_reference_image" -> localGateway.saveSoulReferenceImage(
        refId = body.optString("refId"),
        source = body.optJSONObject("source")?.let(::jsonObjectToMap) ?: emptyMap(),
      )
      "POST" to "/v1/attach_memory_image_reference" -> localGateway.attachMemoryImageReference(
        memoryId = body.optString("memoryId"),
        source = body.optJSONObject("source")?.let(::jsonObjectToMap) ?: emptyMap(),
        preferredMode = body.optString("preferredMode").takeIf(String::isNotBlank),
      )
      "GET" to "/v1/mcp_settings" -> settingsGateway.loadMcpSettings()
      "POST" to "/v1/set_mcp_master_enabled" -> settingsGateway.setMcpMasterEnabled(
        enabled = body.optBoolean("enabled"),
      )
      "POST" to "/v1/set_mcp_server_enabled" -> settingsGateway.setMcpServerEnabled(
        serverId = body.optString("serverId"),
        enabled = body.optBoolean("enabled"),
      )
      "GET" to "/v1/safety_settings" -> settingsGateway.loadSafetySettings()
      "POST" to "/v1/save_safety_settings" -> settingsGateway.saveSafetySettings(
        automationModeId = body.optString("automationModeId"),
        rollbackJournalEnabled = body.optBoolean("rollbackJournalEnabled", true),
        maxFilesPerBatch = body.optInt("maxFilesPerBatch", 20),
        maxAgentTurns = body.optInt(
          "maxAgentTurns",
          SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
        ),
        maxToolCalls = body.optInt(
          "maxToolCalls",
          SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
        ),
        undoWindowHours = body.optInt("undoWindowHours", 24),
        fileChangesPolicyId = body.optString("fileChangesPolicyId"),
        fileDeletesPolicyId = body.optString("fileDeletesPolicyId"),
        shellCommandsPolicyId = body.optString("shellCommandsPolicyId"),
        externalAccessModeId = body.optString("externalAccessModeId"),
        photoLibraryEnabled = body.optBoolean("photoLibraryEnabled", true),
        downloadsEnabled = body.optBoolean("downloadsEnabled", true),
        documentsEnabled = body.optBoolean("documentsEnabled", false),
        recordingsEnabled = body.optBoolean("recordingsEnabled", false),
        workspaceAccessProfileId = body.optString("workspaceAccessProfileId"),
        readOnlyOutsideWorkspace = body.optBoolean("readOnlyOutsideWorkspace", true),
        liveContextModeId = body.optString("liveContextModeId", LiveContextMode.FULL.wireValue),
        memoryToolsEnabled = body.optBoolean("memoryToolsEnabled", true),
        subAgentContextDefaultModeId = body.optString("subAgentContextDefaultModeId").ifBlank { null },
        subAgentContextProfileOverrides =
          body.optJSONObject("subAgentContextProfileOverrides")?.let(::jsonObjectToStringMap)
            ?: emptyMap(),
      )
      "GET" to "/v1/skills_snapshot" -> skillsGateway.loadSkillsSnapshot(
        query = request.queryParameter("query"),
        suggestedLimit = request.queryParameter("suggestedLimit").toIntOrNull() ?: 0,
      )
      "POST" to "/v1/set_skill_enabled" -> {
        skillsGateway.setSkillEnabled(
          skillId = body.optString("skillId"),
          enabled = body.optBoolean("enabled"),
        )
        null
      }
      "POST" to "/v1/refresh_skills" -> skillsGateway.refreshSkills()
      "GET" to "/v1/check_installed_skill_updates" -> skillsGateway.checkInstalledSkillUpdates(
        skillId = request.queryParameter("skillId"),
      )
      "POST" to "/v1/update_installed_skill" -> skillsGateway.updateInstalledSkill(
        skillId = body.optString("skillId"),
      )
      "POST" to "/v1/inspect_skill_source" -> skillsGateway.inspectSkillSource(
        sourceRef = body.optString("sourceRef"),
      )
      "POST" to "/v1/install_skill_source" -> skillsGateway.installSkillSource(
        sourceRef = body.optString("sourceRef"),
        selectedSkillName = body.optString("selectedSkillName"),
      )
      "POST" to "/v1/install_skill_source_batch" -> skillsGateway.installSkillSourceBatch(
        sourceRef = body.optString("sourceRef"),
        selectedSkillNames = body.optJSONArray("selectedSkillNames")?.let(::jsonArrayToStrings) ?: emptyList(),
      )
      "POST" to "/v1/install_suggested_skill" -> skillsGateway.installSuggestedSkill(
        skillId = body.optString("skillId"),
      )
      "POST" to "/v1/delete_installed_skill" -> skillsGateway.deleteInstalledSkill(
        skillId = body.optString("skillId"),
      )
      "GET" to "/v1/skill_instructions" -> skillsGateway.loadSkillInstructions(
        skillId = request.queryParameter("skillId"),
      )
      "GET" to "/v1/suggested_skill_instructions" -> skillsGateway.loadSuggestedSkillInstructions(
        sourceRef = request.queryParameter("sourceRef"),
        selectedSkillName = request.queryParameter("selectedSkillName"),
      )
      "GET" to "/v1/chat_snapshot" -> chatRuntimeGateway.loadChatSnapshot()
      "GET" to "/v1/chat_runtime_snapshot" -> chatRuntimeGateway.loadChatRuntimeSnapshot()
      "GET" to "/v1/chat_run_snapshot" -> chatRuntimeGateway.loadChatRunSnapshot(
        runId = request.queryParameter("runId"),
      )
      "GET" to "/v1/memory_debug_snapshot" -> chatRuntimeGateway.loadMemoryDebugSnapshot()
      "GET" to "/v1/memory_debug_links_snapshot" -> chatRuntimeGateway.loadMemoryDebugLinksSnapshot()
      "GET" to "/v1/soul_debug_snapshot" -> chatRuntimeGateway.loadSoulDebugSnapshot()
      "POST" to "/v1/memory_debug_search" -> chatRuntimeGateway.searchMemoryDebug(
        query = body.optString("query"),
        maxResults = body.optInt("maxResults", 4),
        minScore = body.optInt("minScore", 1),
      )
      "POST" to "/v1/memory_debug_slice" -> chatRuntimeGateway.getMemoryDebugSlice(
        path = body.optString("path"),
        fromLine = body.takeIf { !it.isNull("fromLine") }?.optInt("fromLine"),
        lines = body.optInt("lines", 12),
      )
      "POST" to "/v1/memory_debug_action" -> chatRuntimeGateway.applyMemoryDebugAction(
        recordId = body.optString("recordId"),
        actionId = body.optString("actionId"),
      )
      "POST" to "/v1/create_chat_session" -> {
        chatRuntimeGateway.createChatSession()
        null
      }
      "POST" to "/v1/copy_chat_session" -> {
        chatRuntimeGateway.copyChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/delete_chat_session" -> {
        chatRuntimeGateway.deleteChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/select_chat_session" -> {
        chatRuntimeGateway.selectChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/branch_chat_session_from_message" -> {
        chatRuntimeGateway.branchChatSessionFromMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/delete_chat_message" -> {
        chatRuntimeGateway.deleteChatMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/recall_chat_message" -> {
        chatRuntimeGateway.recallChatMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/submit_chat_message" -> chatRuntimeGateway.submitChatMessage(
        text = body.optString("text"),
        attachments = parseSubmitChatMessageAttachments(body),
      )
      "POST" to "/v1/wait_chat_run" -> chatRuntimeGateway.waitForChatRun(
        runId = body.optString("runId"),
        timeoutMs = body.optLong("timeoutMs", 15_000L),
      )
      "POST" to "/v1/refresh_sandbox_session_info" -> {
        chatRuntimeGateway.refreshSandboxSessionInfo()
        null
      }
      "POST" to "/v1/approve_chat_approval" -> {
        chatRuntimeGateway.approveChatApproval(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/approve_chat_approval_for_session" -> {
        chatRuntimeGateway.approveChatApprovalForSession(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/reject_chat_approval" -> {
        chatRuntimeGateway.rejectChatApproval(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/interrupt_chat_run" -> {
        chatRuntimeGateway.interruptChatRun(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/retry_chat_run" -> {
        chatRuntimeGateway.retryChatRun(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      else -> return LocalRuntimeResponse(
        statusCode = 404,
        body = mapOf("error" to "Unsupported runtime route '${request.path}'."),
      )
    }
    return LocalRuntimeResponse(statusCode = 200, body = payload)
  }

  private fun parseRequest(inputStream: InputStream): LocalRuntimeRequest? {
    val input = BufferedInputStream(inputStream)
    val requestLine = input.readHttpLine() ?: return null
    if (requestLine.isBlank()) {
      return null
    }
    val parts = requestLine.split(' ')
    require(parts.size >= 2) {
      "Malformed request line."
    }
    val headers = linkedMapOf<String, String>()
    while (true) {
      val line = input.readHttpLine() ?: break
      if (line.isEmpty()) {
        break
      }
      val separatorIndex = line.indexOf(':')
      if (separatorIndex <= 0) {
        continue
      }
      headers[line.substring(0, separatorIndex).trim().lowercase()] =
        line.substring(separatorIndex + 1).trim()
    }
    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    val body = input.readExactBytes(contentLength)
    val uri = URI(parts[1])
    return LocalRuntimeRequest(
      method = parts[0].uppercase(),
      path = uri.path ?: "/",
      queryParameters = parseQueryParameters(uri.rawQuery),
      body = body,
    )
  }

  private fun writeResponse(
    socket: Socket,
    response: LocalRuntimeResponse,
  ) {
    runCatching {
      val responseBytes = encodeJson(response.body).toByteArray(Charsets.UTF_8)
      val output = BufferedOutputStream(socket.getOutputStream())
      output.write(
        buildString {
          append("HTTP/1.1 ${response.statusCode} ${reasonPhrase(response.statusCode)}\r\n")
          append("Content-Type: application/json; charset=utf-8\r\n")
          append("Content-Length: ${responseBytes.size}\r\n")
          append("Connection: close\r\n")
          append("\r\n")
        }.toByteArray(Charsets.US_ASCII),
      )
      output.write(responseBytes)
      output.flush()
    }
  }

  private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) {
      return emptyMap()
    }
    return rawQuery.split('&')
      .filter(String::isNotBlank)
      .associate { part ->
        val separatorIndex = part.indexOf('=')
        if (separatorIndex < 0) {
          decode(part) to ""
        } else {
          decode(part.substring(0, separatorIndex)) to decode(part.substring(separatorIndex + 1))
        }
      }
  }

  private fun encodeJson(value: Any?): String {
    val encoded = toJsonValue(value)
    return when (encoded) {
      JSONObject.NULL -> "null"
      is JSONObject -> encoded.toString()
      is JSONArray -> encoded.toString()
      is String -> JSONObject.quote(encoded)
      else -> encoded.toString()
    }
  }

  private fun toJsonValue(value: Any?): Any =
    when (value) {
      null -> JSONObject.NULL
      is JSONObject -> value
      is JSONArray -> value
      is Map<*, *> -> JSONObject().apply {
        value.forEach { (key, nestedValue) ->
          put(key?.toString() ?: "", toJsonValue(nestedValue))
        }
      }
      is Iterable<*> -> JSONArray().apply {
        value.forEach { nestedValue ->
          put(toJsonValue(nestedValue))
        }
      }
      is Array<*> -> JSONArray().apply {
        value.forEach { nestedValue ->
          put(toJsonValue(nestedValue))
        }
      }
      is Number,
      is Boolean,
      is String,
      -> value
      else -> value.toString()
    }

  private fun reasonPhrase(statusCode: Int): String =
    when (statusCode) {
      200 -> "OK"
      400 -> "Bad Request"
      404 -> "Not Found"
      500 -> "Internal Server Error"
      else -> "OK"
    }

  private fun decode(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8.name())

  companion object {
    private const val SOCKET_TIMEOUT_MS: Int = 2_000
    internal const val DEFAULT_PORT: Int = 42_617
  }
}

internal data class OpenCrayLocalRuntimeServerProviders(
  val localGatewayProvider: () -> OpenCrayLocalHostGateway,
  val shellGatewayProvider: () -> OpenCrayShellGateway,
  val chatRuntimeGatewayProvider: () -> OpenCrayChatRuntimeGateway,
  val skillsGatewayProvider: () -> OpenCraySkillsGateway,
  val settingsGatewayProvider: () -> OpenCraySettingsGateway,
)

internal object OpenCrayLocalRuntimeServerRegistry {
  @Volatile
  private var instance: OpenCrayLocalRuntimeServer? = null
  @Volatile
  private var providersFactory: OpenCrayLocalRuntimeServerProvidersFactory =
    DefaultOpenCrayLocalRuntimeServerProvidersFactory

  fun peekState(): LocalRuntimeServerState = synchronized(this) {
    instance?.currentState() ?: LocalRuntimeServerState(
      bindAddress = DEFAULT_LOCAL_RUNTIME_LOOPBACK_ADDRESS.asRuntimeBindAddress(),
      requestedPort = OpenCrayLocalRuntimeServer.DEFAULT_PORT,
    )
  }

  fun fromContext(
    context: Context,
    providers: OpenCrayLocalRuntimeServerProviders? = null,
  ): OpenCrayLocalRuntimeServer {
    val appContext = context.applicationContext
    return instance ?: synchronized(this) {
      instance ?: run {
        val resolvedProviders = providers ?: providersFactory.create(appContext)
        OpenCrayLocalRuntimeServer(
          localGatewayProvider = resolvedProviders.localGatewayProvider,
          shellGatewayProvider = resolvedProviders.shellGatewayProvider,
          chatRuntimeGatewayProvider = resolvedProviders.chatRuntimeGatewayProvider,
          skillsGatewayProvider = resolvedProviders.skillsGatewayProvider,
          settingsGatewayProvider = resolvedProviders.settingsGatewayProvider,
          bindAddress = DEFAULT_LOCAL_RUNTIME_LOOPBACK_ADDRESS,
        ).also { created ->
          instance = created
        }
      }
    }
  }

  fun ensureStarted(
    context: Context,
    providers: OpenCrayLocalRuntimeServerProviders? = null,
  ): OpenCrayLocalRuntimeServer =
    fromContext(context, providers = providers).also { server -> server.ensureStarted() }

  internal fun setProvidersFactoryForTest(
    factory: OpenCrayLocalRuntimeServerProvidersFactory?,
  ) {
    providersFactory = factory ?: DefaultOpenCrayLocalRuntimeServerProvidersFactory
  }

  internal fun clearForTest() {
    val existing = synchronized(this) {
      instance.also {
        instance = null
        providersFactory = DefaultOpenCrayLocalRuntimeServerProvidersFactory
      }
    }
    existing?.close()
  }
}

private fun jsonArrayToStrings(array: JSONArray): List<String> =
  List(array.length()) { index ->
    array.optString(index)
  }

private fun jsonArrayToMaps(array: JSONArray): List<Map<String, Any?>> =
  List(array.length()) { index ->
    array.optJSONObject(index)?.let(::jsonObjectToMap) ?: emptyMap()
  }

private fun jsonObjectToMap(payload: JSONObject): Map<String, Any?> =
  payload.keys().asSequence().associateWith { key ->
    when (val value = payload.opt(key)) {
      JSONObject.NULL -> null
      is JSONObject -> jsonObjectToMap(value)
      is JSONArray -> List(value.length()) { index ->
        when (val entry = value.opt(index)) {
          JSONObject.NULL -> null
          is JSONObject -> jsonObjectToMap(entry)
          else -> entry
        }
      }
      else -> value
    }
  }

private fun jsonObjectToStringMap(payload: JSONObject): Map<String, String> =
  payload.keys().asSequence().mapNotNull { key ->
    val normalizedKey = key.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
    payload.optString(key).trim().takeIf(String::isNotBlank)?.let { value ->
      normalizedKey to value
    }
  }.toMap()

private fun parseSubmitChatMessageAttachments(body: JSONObject): List<OpenCrayFinalAttachment> {
  val attachments = body.optJSONArray("attachments") ?: return emptyList()
  return jsonArrayToMaps(attachments).mapNotNull { payload ->
    val relativePath = payload["relativePath"] as String?
    val path = payload["path"] as String?
    val artifactId = payload["artifactId"] as String?
    val chatAttachmentId = payload["chatAttachmentId"] as String?
    if (
      relativePath.isNullOrBlank() &&
      path.isNullOrBlank() &&
      artifactId.isNullOrBlank() &&
      chatAttachmentId.isNullOrBlank()
    ) {
      return@mapNotNull null
    }
    OpenCrayFinalAttachment(
      kind = payload["kind"] as String?,
      relativePath = relativePath,
      path = path,
      artifactId = artifactId,
      chatAttachmentId = chatAttachmentId,
      displayName = payload["displayName"] as String?,
      mimeType = payload["mimeType"] as String?,
      durationMs = (payload["durationMs"] as Number?)?.toLong(),
      waveformBars = (payload["waveformBars"] as? List<*>)?.mapNotNull { value ->
        (value as? Number)?.toInt()
      }.orEmpty(),
      transcriptText = payload["transcriptText"] as String?,
    )
  }
}

private data class LocalRuntimeRequest(
  val method: String,
  val path: String,
  val queryParameters: Map<String, String>,
  val body: ByteArray,
) {
  fun queryParameter(name: String): String = queryParameters[name].orEmpty()

  fun jsonBody(): JSONObject =
    if (body.isEmpty()) {
      JSONObject()
    } else {
      JSONObject(String(body, Charsets.UTF_8))
    }
}

private data class LocalRuntimeResponse(
  val statusCode: Int,
  val body: Any?,
)

private fun BufferedInputStream.readHttpLine(): String? {
  val buffer = ByteArrayOutputStream()
  while (true) {
    val next = read()
    if (next < 0) {
      return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }
    if (next == '\n'.code) {
      return buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }
    buffer.write(next)
  }
}

private fun InputStream.readExactBytes(length: Int): ByteArray {
  if (length <= 0) {
    return ByteArray(0)
  }
  val bytes = ByteArray(length)
  var offset = 0
  while (offset < length) {
    val read = read(bytes, offset, length - offset)
    if (read < 0) {
      throw IOException("Unexpected end of stream.")
    }
    offset += read
  }
  return bytes
}
