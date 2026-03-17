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
import org.json.JSONArray
import org.json.JSONObject

internal class OpenCrayLocalRuntimeServer(
  private val hostRuntimeProvider: () -> OpenCrayHostRuntime,
  private val requestedPort: Int = DEFAULT_PORT,
  private val bindAddress: InetAddress = LOOPBACK_ADDRESS,
  private val executor: ExecutorService = Executors.newCachedThreadPool(),
  private val shutdownExecutorOnClose: Boolean = false,
) {
  @Volatile
  private var serverSocket: ServerSocket? = null

  @Volatile
  var listeningPort: Int = requestedPort
    private set

  fun ensureStarted() {
    if (serverSocket != null) {
      return
    }
    synchronized(this) {
      if (serverSocket != null) {
        return
      }
      try {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, requestedPort))
        listeningPort = socket.localPort
        serverSocket = socket
        executor.execute {
          acceptLoop(socket)
        }
      } catch (_: BindException) {
        return
      } catch (_: IOException) {
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
    val hostRuntime = hostRuntimeProvider()
    val body = request.jsonBody()
    val payload: Any? = when (request.method to request.path) {
      "GET" to "/v1/shell_snapshot" -> hostRuntime.loadShellSnapshot()
      "GET" to "/v1/files_snapshot" -> hostRuntime.loadFilesSnapshot()
      "GET" to "/v1/workspace_image_preview" -> hostRuntime.loadWorkspaceImagePreview(
        relativePath = request.queryParameter("relativePath"),
      )
      "GET" to "/v1/workspace_text_preview" -> hostRuntime.loadWorkspaceTextPreview(
        relativePath = request.queryParameter("relativePath"),
      )
      "GET" to "/v1/workspace_text_document" -> hostRuntime.loadWorkspaceTextDocument(
        relativePath = request.queryParameter("relativePath"),
      )
      "POST" to "/v1/create_workspace_folder" -> hostRuntime.createWorkspaceFolder(
        parentRelativePath = body.optString("parentRelativePath"),
        name = body.optString("name"),
      )
      "POST" to "/v1/create_workspace_text_file" -> hostRuntime.createWorkspaceTextFile(
        parentRelativePath = body.optString("parentRelativePath"),
        name = body.optString("name"),
      )
      "POST" to "/v1/rename_workspace_entry" -> hostRuntime.renameWorkspaceEntry(
        targetRelativePath = body.optString("targetRelativePath"),
        newName = body.optString("newName"),
      )
      "POST" to "/v1/delete_workspace_entries" -> hostRuntime.deleteWorkspaceEntries(
        relativePaths = body.optJSONArray("relativePaths")?.let(::jsonArrayToStrings) ?: emptyList(),
      )
      "POST" to "/v1/save_workspace_text_document" -> hostRuntime.saveWorkspaceTextDocument(
        targetRelativePath = body.optString("targetRelativePath"),
        content = body.optString("content"),
      )
      "POST" to "/v1/paste_workspace_entries" -> hostRuntime.pasteWorkspaceEntries(
        sourceRelativePaths = body.optJSONArray("sourceRelativePaths")?.let(::jsonArrayToStrings)
          ?: emptyList(),
        destinationRelativePath = body.optString("destinationRelativePath"),
        move = body.optBoolean("move"),
      )
      "POST" to "/v1/share_workspace_entries" -> {
        hostRuntime.shareWorkspaceEntries(
          relativePaths = body.optJSONArray("relativePaths")?.let(::jsonArrayToStrings) ?: emptyList(),
        )
        null
      }
      "GET" to "/v1/settings_overview" -> hostRuntime.loadSettingsOverview()
      "GET" to "/v1/settings_detail" -> hostRuntime.loadSettingsDetail(
        routeIdRaw = request.queryParameter("routeId"),
      )
      "GET" to "/v1/network_search_config" -> hostRuntime.loadNetworkSearchConfig()
      "POST" to "/v1/save_network_search_config" -> hostRuntime.saveNetworkSearchConfig(
        slots = body.optJSONArray("slots")?.let(::jsonArrayToMaps) ?: emptyList(),
      )
      "GET" to "/v1/llm_config" -> hostRuntime.loadLlmConfig()
      "POST" to "/v1/save_llm_config" -> hostRuntime.saveLlmConfig(
        enabled = body.optBoolean("enabled"),
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
      )
      "POST" to "/v1/save_custom_llm_provider" -> hostRuntime.saveCustomLlmProvider(
        selectedProviderOptionId = body.optString("selectedProviderOptionId"),
        protocol = body.optString("protocol"),
        providerName = body.optString("providerName"),
        providerNotes = body.optString("providerNotes"),
        baseUrl = body.optString("baseUrl"),
        apiKey = body.optString("apiKey"),
        model = body.optString("model"),
        reasoningEffort = body.optString("reasoningEffort"),
        systemPrompt = body.optString("systemPrompt"),
      )
      "POST" to "/v1/validate_llm_config" -> hostRuntime.validateLlmConfig(
        providerId = body.optString("providerId"),
        protocol = body.optString("protocol"),
        baseUrl = body.optString("baseUrl"),
        apiKey = body.optString("apiKey"),
        model = body.optString("model"),
        reasoningEffort = body.optString("reasoningEffort"),
      )
      "GET" to "/v1/personalization_config" -> hostRuntime.loadPersonalizationConfig()
      "POST" to "/v1/save_personalization_config" -> hostRuntime.savePersonalizationConfig(
        presetId = body.optString("presetId"),
        customLabel = body.optString("customLabel"),
        customGuidance = body.optString("customGuidance"),
      )
      "POST" to "/v1/set_app_language" -> hostRuntime.setAppLanguage(
        languageId = body.optString("languageId"),
      )
      "POST" to "/v1/run_personalization_reset" -> hostRuntime.runPersonalizationReset(
        scopeId = body.optString("scopeId"),
      )
      "GET" to "/v1/mcp_settings" -> hostRuntime.loadMcpSettings()
      "POST" to "/v1/set_mcp_master_enabled" -> hostRuntime.setMcpMasterEnabled(
        enabled = body.optBoolean("enabled"),
      )
      "POST" to "/v1/set_mcp_server_enabled" -> hostRuntime.setMcpServerEnabled(
        serverId = body.optString("serverId"),
        enabled = body.optBoolean("enabled"),
      )
      "GET" to "/v1/safety_settings" -> hostRuntime.loadSafetySettings()
      "POST" to "/v1/save_safety_settings" -> hostRuntime.saveSafetySettings(
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
      )
      "GET" to "/v1/skills_snapshot" -> hostRuntime.loadSkillsSnapshot()
      "POST" to "/v1/set_skill_enabled" -> {
        hostRuntime.setSkillEnabled(
          skillId = body.optString("skillId"),
          enabled = body.optBoolean("enabled"),
        )
        null
      }
      "POST" to "/v1/refresh_skills" -> hostRuntime.refreshSkills()
      "POST" to "/v1/install_suggested_skill" -> hostRuntime.installSuggestedSkill(
        skillId = body.optString("skillId"),
      )
      "POST" to "/v1/delete_installed_skill" -> hostRuntime.deleteInstalledSkill(
        skillId = body.optString("skillId"),
      )
      "GET" to "/v1/skill_instructions" -> hostRuntime.loadSkillInstructions(
        skillId = request.queryParameter("skillId"),
      )
      "GET" to "/v1/chat_snapshot" -> hostRuntime.loadChatSnapshot()
      "GET" to "/v1/chat_runtime_snapshot" -> hostRuntime.loadChatRuntimeSnapshot()
      "GET" to "/v1/chat_run_snapshot" -> hostRuntime.loadChatRunSnapshot(
        runId = request.queryParameter("runId"),
      )
      "GET" to "/v1/memory_debug_snapshot" -> hostRuntime.loadMemoryDebugSnapshot()
      "GET" to "/v1/memory_debug_links_snapshot" -> hostRuntime.loadMemoryDebugLinksSnapshot()
      "GET" to "/v1/soul_debug_snapshot" -> hostRuntime.loadSoulDebugSnapshot()
      "POST" to "/v1/create_chat_session" -> {
        hostRuntime.createChatSession()
        null
      }
      "POST" to "/v1/copy_chat_session" -> {
        hostRuntime.copyChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/delete_chat_session" -> {
        hostRuntime.deleteChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/select_chat_session" -> {
        hostRuntime.selectChatSession(body.optString("sessionId"))
        null
      }
      "POST" to "/v1/branch_chat_session_from_message" -> {
        hostRuntime.branchChatSessionFromMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/delete_chat_message" -> {
        hostRuntime.deleteChatMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/recall_chat_message" -> {
        hostRuntime.recallChatMessage(
          sessionId = body.optString("sessionId"),
          messageId = body.optString("messageId"),
        )
        null
      }
      "POST" to "/v1/submit_chat_message" -> hostRuntime.submitChatMessage(body.optString("text"))
      "POST" to "/v1/wait_chat_run" -> hostRuntime.waitForChatRun(
        runId = body.optString("runId"),
        timeoutMs = body.optLong("timeoutMs", 15_000L),
      )
      "POST" to "/v1/approve_chat_approval" -> {
        hostRuntime.approveChatApproval(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/reject_chat_approval" -> {
        hostRuntime.rejectChatApproval(
          body.optString("runId").takeIf(String::isNotBlank) ?: body.optString("taskId"),
        )
        null
      }
      "POST" to "/v1/cancel_chat_run" -> {
        hostRuntime.cancelChatRun(
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
    private val LOOPBACK_ADDRESS: InetAddress = InetAddress.getByName("127.0.0.1")
    private const val SOCKET_TIMEOUT_MS: Int = 2_000
    internal const val DEFAULT_PORT: Int = 42_617

    @Volatile
    private var instance: OpenCrayLocalRuntimeServer? = null

    fun fromContext(context: Context): OpenCrayLocalRuntimeServer =
      instance ?: synchronized(this) {
        instance ?: OpenCrayLocalRuntimeServer(
          hostRuntimeProvider = { OpenCrayHostRuntime.fromContext(context.applicationContext) },
        ).also { created ->
          instance = created
        }
      }
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
