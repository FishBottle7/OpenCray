package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_E2B_CONNECT_TIMEOUT_MS: Int = 30_000
private const val DEFAULT_E2B_READ_TIMEOUT_MS: Int = 300_000
private const val DEFAULT_E2B_CONTROL_API_URL: String = "https://api.e2b.app"
private const val DEFAULT_E2B_USER_AGENT: String = "OpenCray-E2B/1.0"
internal const val WORKSPACE_SYNC_MANIFEST_PREFIX: String = "__OPENCRAY_SYNC_MANIFEST__="

internal class E2BCodeInterpreterPythonRuntime(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val transport: E2BTransport = UrlConnectionE2BTransport(),
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : PythonScriptRuntime, CancellablePythonScriptRuntime {
  private val lock = Any()
  private val activeRequests: MutableMap<String, ActiveExecution> = ConcurrentHashMap()

  @Volatile
  private var currentStickySession: E2BSandboxSessionSnapshot? = null

  internal fun activeStickySessionSnapshot(): E2BSandboxSessionSnapshot? = synchronized(lock) {
    currentStickySession
  }

  override fun exec(request: PythonExecRequest): ExecutionResult {
    val startedAt = clock()
    val settings = settingsProvider()
    val state = settings.state.sanitized()
    val apiKey = settings.e2bApiKey?.trim()?.takeIf(String::isNotBlank)
      ?: return failure(
        request = request,
        startedAt = startedAt,
        errorCode = ERROR_E2B_API_KEY_MISSING,
        errorMessage = "E2B API key is not configured.",
        metadata = baseMetadata(state),
      )
    val workspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
    if (!Files.isDirectory(workspaceRoot)) {
      return failure(
        request = request,
        startedAt = startedAt,
        errorCode = ERROR_INVALID_WORKSPACE,
        errorMessage = "Workspace root does not exist or is not a directory.",
        metadata = baseMetadata(state),
      )
    }
    val resolvedScript = resolveScriptInWorkspace(workspaceRoot, request.scriptPath)
      ?: return deniedScriptPathResult(
        request = request,
        startedAt = startedAt,
        workspaceRoot = workspaceRoot,
        state = state,
      )
    if (!Files.isRegularFile(resolvedScript)) {
      return failure(
        request = request,
        startedAt = startedAt,
        errorCode = ERROR_SCRIPT_NOT_FOUND,
        errorMessage = "Script path not found.",
        metadata = baseMetadata(state) + mapOf(
          "workspaceRoot" to workspaceRoot.toString(),
          "scriptPath" to resolvedScript.toString(),
        ),
      )
    }

    val templateId = state.templateId.trim().ifBlank { DEFAULT_TEMPLATE_ID }
    val requestId = request.requestId?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
    val remoteWorkspaceRoot = remoteWorkspaceRootForRequest(requestId)
    val effectiveRequestTimeoutMs = resolveEffectiveRequestTimeoutMs(request, state)
    val startupTimeoutMs = resolveStartupTimeoutMs(request, state)
    val session = runCatching {
      acquireSandboxSession(
        settings = settings,
        workspaceRoot = workspaceRoot,
        templateId = templateId,
        startupTimeoutMs = startupTimeoutMs,
      )
    }.getOrElse { error ->
      return failure(
        request = request,
        startedAt = startedAt,
        errorCode = ERROR_E2B_SESSION_START_FAILED,
        errorMessage = error.message ?: "Failed to start or connect to the E2B sandbox.",
        metadata = baseMetadata(state) + mapOf(
          "workspaceRoot" to workspaceRoot.toString(),
          "scriptPath" to resolvedScript.toString(),
          "templateId" to templateId,
          "requestId" to requestId,
          "remoteWorkspaceRoot" to remoteWorkspaceRoot,
          "effectiveRequestTimeoutMs" to effectiveRequestTimeoutMs.toString(),
          "startupTimeoutMs" to startupTimeoutMs.toString(),
        ),
      )
    }

    val activeExecution = ActiveExecution(
      requestId = requestId,
      apiKey = apiKey,
      session = session,
      effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
    )
    activeRequests[requestId] = activeExecution
    val timeoutWatchdog = startTimeoutWatchdog(activeExecution)

    try {
      val syncSummary = syncWorkspace(
        session = session,
        workspaceRoot = workspaceRoot,
        remoteWorkspaceRoot = remoteWorkspaceRoot,
        requestTimeoutMs = effectiveRequestTimeoutMs,
      )
      if (activeExecution.cancelled.get()) {
        return cancelled(
          request = request,
          startedAt = startedAt,
          metadata = commonExecutionMetadata(
            request = request,
            state = state,
            session = session,
            syncSummary = syncSummary,
            downloadSummary = WorkspaceDownloadSummary(),
            remoteWorkspaceRoot = remoteWorkspaceRoot,
            remoteScriptPath = remotePathFor(remoteWorkspaceRoot, workspaceRoot, resolvedScript),
            contextId = null,
            requestId = requestId,
            effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
            startupTimeoutMs = startupTimeoutMs,
          ),
        )
      }
      if (activeExecution.timedOut.get()) {
        return timeout(
          request = request,
          startedAt = startedAt,
          metadata = commonExecutionMetadata(
            request = request,
            state = state,
            session = session,
            syncSummary = syncSummary,
            downloadSummary = WorkspaceDownloadSummary(),
            remoteWorkspaceRoot = remoteWorkspaceRoot,
            remoteScriptPath = remotePathFor(remoteWorkspaceRoot, workspaceRoot, resolvedScript),
            contextId = null,
            requestId = requestId,
            effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
            startupTimeoutMs = startupTimeoutMs,
          ),
        )
      }
      val contextId = createContext(
        session = session,
        remoteWorkspaceRoot = remoteWorkspaceRoot,
        requestTimeoutMs = effectiveRequestTimeoutMs,
      )
      activeExecution.contextId = contextId
      return try {
        executeRemoteScript(
          request = request,
          startedAt = startedAt,
          state = state,
          session = session,
          resolvedScript = resolvedScript,
          workspaceRoot = workspaceRoot,
          remoteWorkspaceRoot = remoteWorkspaceRoot,
          syncSummary = syncSummary,
          contextId = contextId,
          activeExecution = activeExecution,
          requestId = requestId,
          effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
          startupTimeoutMs = startupTimeoutMs,
        )
      } finally {
        runCatching {
          deleteContext(
            session = session,
            requestTimeoutMs = effectiveRequestTimeoutMs,
            contextId = contextId,
          )
        }
      }
    } catch (error: Throwable) {
      val remoteScriptPath = remotePathFor(remoteWorkspaceRoot, workspaceRoot, resolvedScript)
      return when {
        activeExecution.cancelled.get() -> cancelled(
          request = request,
          startedAt = startedAt,
          metadata = commonExecutionMetadata(
            request = request,
            state = state,
            session = session,
            syncSummary = WorkspaceSyncSummary(),
            downloadSummary = WorkspaceDownloadSummary(),
            remoteWorkspaceRoot = remoteWorkspaceRoot,
            remoteScriptPath = remoteScriptPath,
            contextId = activeExecution.contextId,
            requestId = requestId,
            effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
            startupTimeoutMs = startupTimeoutMs,
          ),
        )

        activeExecution.timedOut.get() -> timeout(
          request = request,
          startedAt = startedAt,
          metadata = commonExecutionMetadata(
            request = request,
            state = state,
            session = session,
            syncSummary = WorkspaceSyncSummary(),
            downloadSummary = WorkspaceDownloadSummary(),
            remoteWorkspaceRoot = remoteWorkspaceRoot,
            remoteScriptPath = remoteScriptPath,
            contextId = activeExecution.contextId,
            requestId = requestId,
            effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
            startupTimeoutMs = startupTimeoutMs,
          ),
        )

        else -> failure(
          request = request,
          startedAt = startedAt,
          errorCode = ERROR_E2B_EXEC_FAILED,
          errorMessage = error.message ?: "Remote execution in E2B failed.",
          metadata = commonExecutionMetadata(
            request = request,
            state = state,
            session = session,
            syncSummary = WorkspaceSyncSummary(),
            downloadSummary = WorkspaceDownloadSummary(),
            remoteWorkspaceRoot = remoteWorkspaceRoot,
            remoteScriptPath = remoteScriptPath,
            contextId = activeExecution.contextId,
            requestId = requestId,
            effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
            startupTimeoutMs = startupTimeoutMs,
          ),
        )
      }
    } finally {
      activeExecution.completed.set(true)
      timeoutWatchdog?.interrupt()
      activeRequests.remove(requestId)
      cleanupSessionAfterExecution(
        session = session,
        settings = settings,
        cancelled = activeExecution.cancelled.get(),
        timedOut = activeExecution.timedOut.get(),
      )
    }
  }

  override fun requestCancellation(requestId: String): Boolean {
    val normalizedRequestId = requestId.trim()
    if (normalizedRequestId.isBlank()) {
      return false
    }
    val activeExecution = activeRequests[normalizedRequestId] ?: return false
    activeExecution.cancelled.set(true)
    return runCatching {
      killSandbox(
        sandboxId = activeExecution.session.sandboxId,
        apiKey = activeExecution.apiKey,
      )
      clearIfCurrentSession(activeExecution.session.sandboxId)
      true
    }.getOrDefault(false)
  }

  private fun executeRemoteScript(
    request: PythonExecRequest,
    startedAt: Long,
    state: SandboxSettingsState,
    session: E2BSandboxSessionSnapshot,
    resolvedScript: Path,
    workspaceRoot: Path,
    remoteWorkspaceRoot: String,
    syncSummary: WorkspaceSyncSummary,
    contextId: String,
    activeExecution: ActiveExecution,
    requestId: String,
    effectiveRequestTimeoutMs: Long,
    startupTimeoutMs: Long,
  ): ExecutionResult {
    val remoteScriptPath = remotePathFor(remoteWorkspaceRoot, workspaceRoot, resolvedScript)
    val stdoutLines = mutableListOf<String>()
    val stderrLines = mutableListOf<String>()
    var executionError: RemoteExecutionError? = null
    var workspaceDiffManifest: RemoteWorkspaceDiffManifest? = null
    var syncManifestParseFailed = false
    val code = buildScriptExecutionCode(
      remoteWorkspaceRoot = remoteWorkspaceRoot,
      remoteScriptPath = remoteScriptPath,
      args = request.args,
    )
    val response = executeCode(
      session = session,
      requestTimeoutMs = effectiveRequestTimeoutMs,
      contextId = contextId,
      code = code,
    ) { line ->
      when (val parsed = parseExecutionEvent(line)) {
        null -> Unit
        is ExecutionEvent.Stdout -> when (val manifestParse = parseWorkspaceDiffManifest(parsed.text)) {
          WorkspaceDiffManifestParseResult.NotManifest -> stdoutLines += parsed.text
          is WorkspaceDiffManifestParseResult.Parsed -> workspaceDiffManifest = manifestParse.manifest
          WorkspaceDiffManifestParseResult.Invalid -> syncManifestParseFailed = true
        }
        is ExecutionEvent.Stderr -> stderrLines += parsed.text
        is ExecutionEvent.Error -> executionError = parsed.error
        ExecutionEvent.ExecutionCount -> Unit
        ExecutionEvent.Result -> Unit
      }
    }
    val downloadSummary = if (activeExecution.cancelled.get() || activeExecution.timedOut.get()) {
      WorkspaceDownloadSummary()
    } else {
      downloadRemoteWorkspaceChanges(
        session = session,
        workspaceRoot = workspaceRoot,
        remoteWorkspaceRoot = remoteWorkspaceRoot,
        manifest = workspaceDiffManifest,
        requestTimeoutMs = effectiveRequestTimeoutMs,
      )
    }
    val metadata = commonExecutionMetadata(
      request = request,
      state = state,
      session = session,
      syncSummary = syncSummary,
      downloadSummary = downloadSummary,
      remoteWorkspaceRoot = remoteWorkspaceRoot,
      remoteScriptPath = remoteScriptPath,
      contextId = contextId,
      requestId = requestId,
      effectiveRequestTimeoutMs = effectiveRequestTimeoutMs,
      startupTimeoutMs = startupTimeoutMs,
      syncManifestObserved = workspaceDiffManifest != null,
      syncManifestParseFailed = syncManifestParseFailed,
    )
    if (activeExecution.cancelled.get()) {
      return cancelled(request, startedAt, metadata)
    }
    if (activeExecution.timedOut.get()) {
      return timeout(
        request = request,
        startedAt = startedAt,
        metadata = metadata,
        stdout = stdoutLines.joinToString(separator = "\n"),
        stderr = stderrLines.joinToString(separator = "\n"),
      )
    }
    if (response.statusCode >= 400) {
      return failure(
        request = request,
        startedAt = startedAt,
        errorCode = ERROR_E2B_EXEC_FAILED,
        errorMessage = response.message(),
        metadata = metadata,
        stdout = stdoutLines.joinToString(separator = "\n"),
        stderr = stderrLines.joinToString(separator = "\n"),
      )
    }
    if (executionError != null) {
      val stderr = buildString {
        val stderrText = stderrLines.joinToString(separator = "\n")
        if (stderrText.isNotBlank()) {
          appendLine(stderrText)
        }
        append(executionError!!.traceback)
      }.trim()
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = 1,
        stdout = stdoutLines.joinToString(separator = "\n"),
        stderr = stderr,
        errorCode = ERROR_E2B_EXEC_ERROR,
        errorMessage = "${executionError!!.name}: ${executionError!!.value}",
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = clock(),
        metadata = metadata,
      )
    }
    return ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.SUCCESS,
      exitCode = 0,
      stdout = stdoutLines.joinToString(separator = "\n"),
      stderr = stderrLines.joinToString(separator = "\n"),
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = clock(),
      metadata = metadata,
    )
  }

  private fun downloadRemoteWorkspaceChanges(
    session: E2BSandboxSessionSnapshot,
    workspaceRoot: Path,
    remoteWorkspaceRoot: String,
    manifest: RemoteWorkspaceDiffManifest?,
    requestTimeoutMs: Long,
  ): WorkspaceDownloadSummary {
    val resolvedManifest = manifest ?: return WorkspaceDownloadSummary()
    var downloadedFiles = 0
    var downloadedBytes = 0L
    var skippedDownloadFiles = 0
    var skippedRemoteDeletes = 0
    var downloadFailures = 0
    val downloadedRelativePaths = mutableListOf<String>()

    resolvedManifest.changedFiles
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .forEach { relativePath ->
        if (shouldSkipRemoteWorkspaceDownload(relativePath)) {
          skippedDownloadFiles += 1
          return@forEach
        }
        val localPath = resolveRemoteRelativePathInWorkspace(
          workspaceRoot = workspaceRoot,
          relativePath = relativePath,
        )
        if (localPath == null) {
          skippedDownloadFiles += 1
          return@forEach
        }
        val remotePath = "$remoteWorkspaceRoot/$relativePath"
        val response = transport.download(
          E2BDownloadRequest(
            url = sandboxFilesUrl(session, remotePath),
            headers = sandboxHeaders(session),
            connectTimeoutMs = timeoutInt(requestTimeoutMs),
            readTimeoutMs = timeoutInt(requestTimeoutMs),
          ),
        )
        if (response.statusCode !in 200..299) {
          downloadFailures += 1
          return@forEach
        }
        val persisted = runCatching {
          writeBytesAtomically(localPath, response.bodyBytes)
          true
        }.getOrDefault(false)
        if (!persisted) {
          downloadFailures += 1
          return@forEach
        }
        downloadedFiles += 1
        downloadedBytes += response.bodyBytes.size.toLong()
        downloadedRelativePaths += relativePath
      }

    skippedRemoteDeletes += resolvedManifest.deletedFiles
      .map(String::trim)
      .filter(String::isNotBlank)
      .count { relativePath ->
        !shouldSkipRemoteWorkspaceDownload(relativePath) &&
          resolveRemoteRelativePathInWorkspace(workspaceRoot = workspaceRoot, relativePath = relativePath) != null
      }

    return WorkspaceDownloadSummary(
      downloadedFiles = downloadedFiles,
      downloadedBytes = downloadedBytes,
      remoteChangedFiles = resolvedManifest.changedFiles.size,
      remoteDeletedFiles = resolvedManifest.deletedFiles.size,
      skippedDownloadFiles = skippedDownloadFiles,
      skippedRemoteDeletes = skippedRemoteDeletes,
      downloadFailures = downloadFailures,
      downloadedRelativePaths = downloadedRelativePaths,
    )
  }

  private fun acquireSandboxSession(
    settings: ResolvedSandboxSettings,
    workspaceRoot: Path,
    templateId: String,
    startupTimeoutMs: Long,
  ): E2BSandboxSessionSnapshot {
    val state = settings.state.sanitized()
    if (SandboxSessionMode.fromWireValue(state.sessionMode) != SandboxSessionMode.STICKY) {
      return createSandbox(settings, workspaceRoot, templateId, startupTimeoutMs)
    }

    val current = synchronized(lock) { currentStickySession }
    if (current != null && current.matches(workspaceRoot, templateId)) {
      val resumed = runCatching {
        connectSandbox(
          sandboxId = current.sandboxId,
          settings = settings,
          workspaceRoot = workspaceRoot,
          templateId = templateId,
          startupTimeoutMs = startupTimeoutMs,
        )
      }.getOrNull()
      if (resumed != null) {
        rememberStickySession(resumed, persist = state.autoResume)
        return resumed
      }
      clearIfCurrentSession(current.sandboxId)
    }

    if (state.autoResume) {
      val stored = sessionStore.load()
      if (stored != null && stored.matches(workspaceRoot, templateId)) {
        val resumed = runCatching {
          connectSandbox(
            sandboxId = stored.sandboxId,
            settings = settings,
            workspaceRoot = workspaceRoot,
            templateId = templateId,
            startupTimeoutMs = startupTimeoutMs,
          )
        }.getOrNull()
        if (resumed != null) {
          rememberStickySession(resumed, persist = true)
          return resumed
        }
        sessionStore.clear()
      }
    }

    val created = createSandbox(settings, workspaceRoot, templateId, startupTimeoutMs)
    rememberStickySession(created, persist = state.autoResume)
    return created
  }

  private fun createSandbox(
    settings: ResolvedSandboxSettings,
    workspaceRoot: Path,
    templateId: String,
    startupTimeoutMs: Long,
  ): E2BSandboxSessionSnapshot {
    val state = settings.state.sanitized()
    val response = transport.request(
      E2BRequest(
        method = "POST",
        url = "$DEFAULT_E2B_CONTROL_API_URL/sandboxes",
        headers = apiHeaders(requireNotNull(settings.e2bApiKey), jsonBody = true),
        body = buildJsonObject {
          put("templateID", templateId)
          put("timeout", state.idleTimeoutMinutes * 60)
          put("secure", true)
          put("allow_internet_access", true)
          put("metadata", buildJsonObject {
            put("source", "opencray")
            put("workspaceRoot", workspaceRoot.toString())
            put("templateId", templateId)
          })
          when (SandboxTimeoutAction.fromWireValue(state.timeoutAction)) {
            SandboxTimeoutAction.PAUSE -> {
              put("autoPause", true)
              if (state.autoResume) {
                put("autoResume", buildJsonObject {
                  put("enabled", true)
                })
              }
            }

            else -> put("autoPause", false)
          }
        }.toString(),
        connectTimeoutMs = timeoutInt(startupTimeoutMs),
        readTimeoutMs = timeoutInt(startupTimeoutMs),
      ),
    )
    if (response.statusCode !in 200..299) {
      error("E2B sandbox creation failed: ${response.message()}")
    }
    return parseSandboxSessionResponse(
      payload = response.body,
      workspaceRoot = workspaceRoot,
      templateId = templateId,
    )
  }

  private fun connectSandbox(
    sandboxId: String,
    settings: ResolvedSandboxSettings,
    workspaceRoot: Path,
    templateId: String,
    startupTimeoutMs: Long,
  ): E2BSandboxSessionSnapshot {
    val state = settings.state.sanitized()
    val response = transport.request(
      E2BRequest(
        method = "POST",
        url = "$DEFAULT_E2B_CONTROL_API_URL/sandboxes/${encodePathSegment(sandboxId)}/connect",
        headers = apiHeaders(requireNotNull(settings.e2bApiKey), jsonBody = true),
        body = buildJsonObject {
          put("timeout", state.idleTimeoutMinutes * 60)
        }.toString(),
        connectTimeoutMs = timeoutInt(startupTimeoutMs),
        readTimeoutMs = timeoutInt(startupTimeoutMs),
      ),
    )
    if (response.statusCode !in 200..299) {
      error("E2B sandbox connect failed: ${response.message()}")
    }
    return parseSandboxSessionResponse(
      payload = response.body,
      workspaceRoot = workspaceRoot,
      templateId = templateId,
    )
  }

  private fun syncWorkspace(
    session: E2BSandboxSessionSnapshot,
    workspaceRoot: Path,
    remoteWorkspaceRoot: String,
    requestTimeoutMs: Long,
  ): WorkspaceSyncSummary {
    val plan = planWorkspaceFiles(workspaceRoot)
    var uploadedFiles = 0
    var uploadedBytes = 0L
    plan.files.forEach { file ->
      val remotePath = remotePathFor(remoteWorkspaceRoot, workspaceRoot, file)
      val content = Files.readAllBytes(file)
      val response = transport.upload(
        E2BUploadRequest(
          url = sandboxFilesUrl(session, remotePath),
          headers = sandboxHeaders(session),
          fieldName = "file",
          fileName = file.fileName?.toString() ?: "file",
          fileBytes = content,
          connectTimeoutMs = timeoutInt(requestTimeoutMs),
          readTimeoutMs = timeoutInt(requestTimeoutMs),
        ),
      )
      if (response.statusCode !in 200..299) {
        error("E2B workspace upload failed for ${file.fileName}: ${response.message()}")
      }
      uploadedFiles += 1
      uploadedBytes += content.size.toLong()
    }
    return WorkspaceSyncSummary(
      uploadedFiles = uploadedFiles,
      uploadedBytes = uploadedBytes,
      skippedDirectories = plan.skippedDirectories,
      skippedFiles = plan.skippedFiles,
    )
  }

  private fun createContext(
    session: E2BSandboxSessionSnapshot,
    remoteWorkspaceRoot: String,
    requestTimeoutMs: Long,
  ): String {
    val response = transport.request(
      E2BRequest(
        method = "POST",
        url = "${codeInterpreterBaseUrl(session)}/contexts",
        headers = sandboxHeaders(session, jsonBody = true),
        body = buildJsonObject {
          put("language", "python")
          put("cwd", remoteWorkspaceRoot)
        }.toString(),
        connectTimeoutMs = timeoutInt(requestTimeoutMs),
        readTimeoutMs = timeoutInt(requestTimeoutMs),
      ),
    )
    if (response.statusCode !in 200..299) {
      error("E2B code context creation failed: ${response.message()}")
    }
    return json.parseToJsonElement(response.body).jsonObject.stringValue("id")
      ?: error("E2B code context response did not include an id.")
  }

  private fun deleteContext(
    session: E2BSandboxSessionSnapshot,
    requestTimeoutMs: Long,
    contextId: String,
  ) {
    val response = transport.request(
      E2BRequest(
        method = "DELETE",
        url = "${codeInterpreterBaseUrl(session)}/contexts/${encodePathSegment(contextId)}",
        headers = sandboxHeaders(session, jsonBody = true),
        connectTimeoutMs = timeoutInt(requestTimeoutMs),
        readTimeoutMs = timeoutInt(requestTimeoutMs),
      ),
    )
    if (response.statusCode == 404) {
      return
    }
    if (response.statusCode !in 200..299) {
      error("E2B code context deletion failed: ${response.message()}")
    }
  }

  private fun executeCode(
    session: E2BSandboxSessionSnapshot,
    requestTimeoutMs: Long,
    contextId: String,
    code: String,
    onLine: (String) -> Unit,
  ): E2BResponse = transport.stream(
    E2BRequest(
      method = "POST",
      url = "${codeInterpreterBaseUrl(session)}/execute",
      headers = sandboxHeaders(session, jsonBody = true),
      body = buildJsonObject {
        put("code", code)
        put("context_id", contextId)
        put("language", "python")
        put("env_vars", buildJsonObject {})
      }.toString(),
      connectTimeoutMs = timeoutInt(requestTimeoutMs),
      readTimeoutMs = timeoutInt(requestTimeoutMs),
    ),
    onLine = onLine,
  )

  private fun cleanupSessionAfterExecution(
    session: E2BSandboxSessionSnapshot,
    settings: ResolvedSandboxSettings,
    cancelled: Boolean,
    timedOut: Boolean,
  ) {
    val state = settings.state.sanitized()
    val sticky = SandboxSessionMode.fromWireValue(state.sessionMode) == SandboxSessionMode.STICKY
    if (!sticky || cancelled || timedOut) {
      settings.e2bApiKey?.trim()?.takeIf(String::isNotBlank)?.let { apiKey ->
        runCatching {
          killSandbox(session.sandboxId, apiKey)
        }
      }
      clearIfCurrentSession(session.sandboxId)
      return
    }
    rememberStickySession(session, persist = state.autoResume)
  }

  private fun rememberStickySession(
    session: E2BSandboxSessionSnapshot,
    persist: Boolean,
  ) {
    val updated = session.copy(updatedAtEpochMs = clock())
    synchronized(lock) {
      currentStickySession = updated
    }
    if (persist) {
      sessionStore.save(updated)
    } else {
      sessionStore.clear()
    }
  }

  private fun clearIfCurrentSession(sandboxId: String) {
    synchronized(lock) {
      if (currentStickySession?.sandboxId == sandboxId) {
        currentStickySession = null
      }
    }
    if (sessionStore.load()?.sandboxId == sandboxId) {
      sessionStore.clear()
    }
  }

  private fun killSandbox(
    sandboxId: String,
    apiKey: String,
  ) {
    val response = transport.request(
      E2BRequest(
        method = "DELETE",
        url = "$DEFAULT_E2B_CONTROL_API_URL/sandboxes/${encodePathSegment(sandboxId)}",
        headers = apiHeaders(apiKey),
      ),
    )
    if (response.statusCode == 404) {
      return
    }
    if (response.statusCode !in 200..299) {
      error("E2B sandbox termination failed: ${response.message()}")
    }
  }

  private fun startTimeoutWatchdog(activeExecution: ActiveExecution): Thread? {
    val timeoutMs = activeExecution.effectiveRequestTimeoutMs.coerceAtLeast(0L)
    return Thread(
      {
        try {
          Thread.sleep(timeoutMs)
        } catch (_: InterruptedException) {
          return@Thread
        }
        if (activeExecution.completed.get() || activeExecution.cancelled.get()) {
          return@Thread
        }
        activeExecution.timedOut.set(true)
        runCatching {
          killSandbox(
            sandboxId = activeExecution.session.sandboxId,
            apiKey = activeExecution.apiKey,
          )
        }
        clearIfCurrentSession(activeExecution.session.sandboxId)
      },
      "e2b-python-timeout-${activeExecution.requestId}",
    ).apply {
      isDaemon = true
      start()
    }
  }

  private fun resolveScriptInWorkspace(
    workspaceRoot: Path,
    scriptPath: Path,
  ): Path? {
    val normalized = if (scriptPath.isAbsolute) {
      scriptPath.normalize()
    } else {
      workspaceRoot.resolve(scriptPath).normalize()
    }
    return normalized.takeIf { candidate -> candidate.startsWith(workspaceRoot) }
  }

  private fun deniedScriptPathResult(
    request: PythonExecRequest,
    startedAt: Long,
    workspaceRoot: Path,
    state: SandboxSettingsState,
  ): ExecutionResult = ExecutionResult(
    taskId = request.taskId,
    status = ExecutionStatus.DENIED,
    exitCode = null,
    stdout = "",
    stderr = "",
    errorCode = ERROR_SCRIPT_PATH_NOT_ALLOWED,
    errorMessage = "Script path escapes the workspace root.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = clock(),
    metadata = baseMetadata(state) + mapOf(
      "workspaceRoot" to workspaceRoot.toString(),
      "scriptPath" to request.scriptPath.toString(),
    ),
  )

  private fun failure(
    request: PythonExecRequest,
    startedAt: Long,
    errorCode: String,
    errorMessage: String,
    metadata: Map<String, String>,
    stdout: String = "",
    stderr: String = "",
  ): ExecutionResult = ExecutionResult(
    taskId = request.taskId,
    status = ExecutionStatus.FAILED,
    exitCode = null,
    stdout = stdout,
    stderr = stderr,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = clock(),
    metadata = metadata,
  )

  private fun cancelled(
    request: PythonExecRequest,
    startedAt: Long,
    metadata: Map<String, String>,
  ): ExecutionResult = ExecutionResult(
    taskId = request.taskId,
    status = ExecutionStatus.CANCELLED,
    exitCode = null,
    stdout = "",
    stderr = "",
    errorCode = ERROR_E2B_CANCELLED,
    errorMessage = "Remote Python execution was cancelled.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = clock(),
    metadata = metadata,
  )

  private fun timeout(
    request: PythonExecRequest,
    startedAt: Long,
    metadata: Map<String, String>,
    stdout: String = "",
    stderr: String = "",
  ): ExecutionResult = ExecutionResult(
    taskId = request.taskId,
    status = ExecutionStatus.TIMEOUT,
    exitCode = null,
    stdout = stdout,
    stderr = stderr,
    errorCode = ERROR_E2B_TIMEOUT,
    errorMessage = "Remote Python execution exceeded the configured timeout.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = clock(),
    metadata = metadata,
  )

  private fun baseMetadata(state: SandboxSettingsState): Map<String, String> = mapOf(
    "runtimeBackend" to "e2b_code_interpreter",
    "runtimeTransport" to "http_json_api",
    "sandboxProvider" to SandboxProviderId.E2B.wireValue,
    "sandboxSessionMode" to state.sessionMode,
    "sandboxTimeoutAction" to state.timeoutAction,
    "sandboxAutoResume" to state.autoResume.toString(),
    "sandboxProviderRequestTimeoutMs" to state.requestTimeoutMs.toString(),
    "sandboxStartupTimeoutMs" to state.startupTimeoutMs.toString(),
  )

  private fun commonExecutionMetadata(
    request: PythonExecRequest,
    state: SandboxSettingsState,
    session: E2BSandboxSessionSnapshot,
    syncSummary: WorkspaceSyncSummary,
    downloadSummary: WorkspaceDownloadSummary,
    remoteWorkspaceRoot: String,
    remoteScriptPath: String,
    contextId: String?,
    requestId: String,
    effectiveRequestTimeoutMs: Long,
    startupTimeoutMs: Long,
    syncManifestObserved: Boolean = false,
    syncManifestParseFailed: Boolean = false,
  ): Map<String, String> = buildMap {
    putAll(baseMetadata(state))
    put("requestId", requestId)
    put("taskId", request.taskId)
    put("workspaceRoot", request.workspaceRoot.toAbsolutePath().normalize().toString())
    put("scriptPath", request.scriptPath.toString())
    put("sandboxId", session.sandboxId)
    put("sandboxDomain", session.sandboxDomain)
    put("sandboxTemplateId", session.templateId)
    put("remoteWorkspaceRoot", remoteWorkspaceRoot)
    put("remoteScriptPath", remoteScriptPath)
    put("uploadedFiles", syncSummary.uploadedFiles.toString())
    put("uploadedBytes", syncSummary.uploadedBytes.toString())
    put("skippedDirectories", syncSummary.skippedDirectories.toString())
    put("skippedFiles", syncSummary.skippedFiles.toString())
    put("workspaceSyncManifestObserved", syncManifestObserved.toString())
    put("workspaceSyncManifestParseFailed", syncManifestParseFailed.toString())
    put("remoteChangedFiles", downloadSummary.remoteChangedFiles.toString())
    put("remoteDeletedFiles", downloadSummary.remoteDeletedFiles.toString())
    put("downloadedFiles", downloadSummary.downloadedFiles.toString())
    put("downloadedBytes", downloadSummary.downloadedBytes.toString())
    put("skippedDownloadFiles", downloadSummary.skippedDownloadFiles.toString())
    put("skippedRemoteDeletes", downloadSummary.skippedRemoteDeletes.toString())
    put("downloadFailures", downloadSummary.downloadFailures.toString())
    putAll(
      OpenCrayAttachmentArtifacts.encodeMetadata(
        json = json,
        artifacts = OpenCrayAttachmentArtifacts.fromWorkspaceRelativePaths(downloadSummary.downloadedRelativePaths),
      ),
    )
    put("effectiveRequestTimeoutMs", effectiveRequestTimeoutMs.toString())
    put("startupTimeoutMs", startupTimeoutMs.toString())
    put("requestTimeoutMs", request.timeoutMs.toString())
    contextId?.let { put("contextId", it) }
  }

  private fun resolveEffectiveRequestTimeoutMs(
    request: PythonExecRequest,
    state: SandboxSettingsState,
  ): Long = request.timeoutMs
    .coerceAtLeast(1L)
    .coerceAtMost(state.requestTimeoutMs.coerceAtLeast(1L))

  private fun resolveStartupTimeoutMs(
    request: PythonExecRequest,
    state: SandboxSettingsState,
  ): Long = (request.startupTimeoutMs ?: state.startupTimeoutMs)
    .coerceAtLeast(1L)

  private fun buildScriptExecutionCode(
    remoteWorkspaceRoot: String,
    remoteScriptPath: String,
    args: List<String>,
  ): String = buildString {
    appendLine("import base64")
    appendLine("import json")
    appendLine("import os")
    appendLine("import runpy")
    appendLine("import sys")
    appendLine()
    appendLine("workspace_root = ${json.encodeToString(String.serializer(), remoteWorkspaceRoot)}")
    appendLine("script_path = ${json.encodeToString(String.serializer(), remoteScriptPath)}")
    appendLine("argv = ${json.encodeToString(ListSerializer(String.serializer()), args)}")
    appendLine("sync_manifest_prefix = ${json.encodeToString(String.serializer(), WORKSPACE_SYNC_MANIFEST_PREFIX)}")
    appendLine(
      "sync_ignored_segments = set(${
        json.encodeToString(
          ListSerializer(String.serializer()),
          DOWNLOAD_SYNC_SKIPPED_DIRECTORY_NAMES.sorted(),
        )
      })",
    )
    appendLine()
    appendLine("def should_sync_relative_path(relative_path):")
    appendLine("    normalized = relative_path.replace('\\\\', '/').strip('/')")
    appendLine("    if not normalized:")
    appendLine("        return False")
    appendLine("    parts = [part for part in normalized.split('/') if part not in ('', '.')]")
    appendLine("    if not parts:")
    appendLine("        return False")
    appendLine("    return all(part not in sync_ignored_segments for part in parts)")
    appendLine()
    appendLine("def stat_mtime_ns(stat_result):")
    appendLine("    return int(getattr(stat_result, 'st_mtime_ns', int(stat_result.st_mtime * 1_000_000_000)))")
    appendLine()
    appendLine("def snapshot_workspace(root_path):")
    appendLine("    snapshot = {}")
    appendLine("    for current_root, dirnames, filenames in os.walk(root_path):")
    appendLine("        dirnames[:] = sorted([name for name in dirnames if name not in sync_ignored_segments])")
    appendLine("        for file_name in sorted(filenames):")
    appendLine("            absolute_path = os.path.join(current_root, file_name)")
    appendLine("            relative_path = os.path.relpath(absolute_path, root_path).replace('\\\\', '/')")
    appendLine("            if not should_sync_relative_path(relative_path):")
    appendLine("                continue")
    appendLine("            try:")
    appendLine("                stat_result = os.stat(absolute_path)")
    appendLine("            except OSError:")
    appendLine("                continue")
    appendLine("            snapshot[relative_path] = [int(stat_result.st_size), stat_mtime_ns(stat_result)]")
    appendLine("    return snapshot")
    appendLine()
    appendLine("def emit_sync_manifest(before_snapshot, after_snapshot):")
    appendLine("    changed_files = sorted([path for path, metadata in after_snapshot.items() if before_snapshot.get(path) != metadata])")
    appendLine("    deleted_files = sorted([path for path in before_snapshot.keys() if path not in after_snapshot])")
    appendLine("    payload = json.dumps({'changedFiles': changed_files, 'deletedFiles': deleted_files}, separators=(',', ':'))")
    appendLine("    encoded = base64.b64encode(payload.encode('utf-8')).decode('ascii')")
    appendLine("    print(sync_manifest_prefix + encoded, flush=True)")
    appendLine()
    appendLine("os.chdir(workspace_root)")
    appendLine("script_dir = os.path.dirname(script_path)")
    appendLine("sys.argv = [script_path, *argv]")
    appendLine("if script_dir and script_dir not in sys.path:")
    appendLine("    sys.path.insert(0, script_dir)")
    appendLine("if workspace_root not in sys.path:")
    appendLine("    sys.path.insert(0, workspace_root)")
    appendLine()
    appendLine("before_workspace_snapshot = snapshot_workspace(workspace_root)")
    appendLine("captured_error = None")
    appendLine("try:")
    appendLine("    runpy.run_path(script_path, run_name='__main__')")
    appendLine("except BaseException:")
    appendLine("    captured_error = sys.exc_info()")
    appendLine("after_workspace_snapshot = snapshot_workspace(workspace_root)")
    appendLine("try:")
    appendLine("    emit_sync_manifest(before_workspace_snapshot, after_workspace_snapshot)")
    appendLine("except Exception:")
    appendLine("    pass")
    appendLine("if captured_error is not None:")
    append("    raise captured_error[1].with_traceback(captured_error[2])")
  }

  private fun parseSandboxSessionResponse(
    payload: String,
    workspaceRoot: Path,
    templateId: String,
  ): E2BSandboxSessionSnapshot {
    val body = json.parseToJsonElement(payload).jsonObject
    val sandboxId = body.stringValue("sandboxID")
      ?: error("E2B sandbox response did not include sandboxID.")
    return E2BSandboxSessionSnapshot(
      sandboxId = sandboxId,
      sandboxDomain = body.stringValue("domain") ?: DEFAULT_SANDBOX_DOMAIN,
      envdAccessToken = body.stringValue("envdAccessToken"),
      trafficAccessToken = body.stringValue("trafficAccessToken"),
      workspaceRoot = workspaceRoot.toString(),
      templateId = templateId,
      updatedAtEpochMs = clock(),
    )
  }

  private fun planWorkspaceFiles(workspaceRoot: Path): WorkspaceFilesPlan {
    val files = mutableListOf<Path>()
    var skippedDirectories = 0
    var skippedFiles = 0
    Files.walkFileTree(
      workspaceRoot,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
          dir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (dir != workspaceRoot && dir.fileName?.toString() in SKIPPED_DIRECTORY_NAMES) {
            skippedDirectories += 1
            return FileVisitResult.SKIP_SUBTREE
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (attrs.isRegularFile) {
            files.add(file)
          } else {
            skippedFiles += 1
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(
          file: Path,
          exc: java.io.IOException?,
        ): FileVisitResult {
          skippedFiles += 1
          return FileVisitResult.CONTINUE
        }
      },
    )
    return WorkspaceFilesPlan(
      files = files.sortedBy(Path::toString),
      skippedDirectories = skippedDirectories,
      skippedFiles = skippedFiles,
    )
  }

  private fun parseExecutionEvent(line: String): ExecutionEvent? {
    val trimmed = line.trim()
    if (trimmed.isBlank()) {
      return null
    }
    val payload = json.parseToJsonElement(trimmed).jsonObject
    return when (payload.stringValue("type")) {
      "stdout" -> ExecutionEvent.Stdout(payload.stringValue("text").orEmpty())
      "stderr" -> ExecutionEvent.Stderr(payload.stringValue("text").orEmpty())
      "error" -> ExecutionEvent.Error(
        RemoteExecutionError(
          name = payload.stringValue("name").orEmpty(),
          value = payload.stringValue("value").orEmpty(),
          traceback = payload.stringValue("traceback").orEmpty(),
        ),
      )

      "result" -> ExecutionEvent.Result
      "number_of_executions" -> ExecutionEvent.ExecutionCount
      else -> null
    }
  }

  private fun parseWorkspaceDiffManifest(stdoutLine: String): WorkspaceDiffManifestParseResult {
    val trimmed = stdoutLine.trim()
    if (!trimmed.startsWith(WORKSPACE_SYNC_MANIFEST_PREFIX)) {
      return WorkspaceDiffManifestParseResult.NotManifest
    }
    val encodedPayload = trimmed.removePrefix(WORKSPACE_SYNC_MANIFEST_PREFIX)
    if (encodedPayload.isBlank()) {
      return WorkspaceDiffManifestParseResult.Invalid
    }
    return runCatching {
      val decoded = String(Base64.getDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
      val payload = json.parseToJsonElement(decoded).jsonObject
      WorkspaceDiffManifestParseResult.Parsed(
        RemoteWorkspaceDiffManifest(
          changedFiles = payload.stringList("changedFiles"),
          deletedFiles = payload.stringList("deletedFiles"),
        ),
      )
    }.getOrElse {
      WorkspaceDiffManifestParseResult.Invalid
    }
  }

  private fun shouldSkipRemoteWorkspaceDownload(relativePath: String): Boolean {
    val normalized = relativePath.trim().replace('\\', '/').trim('/')
    if (normalized.isBlank()) {
      return true
    }
    val segments = normalized.split('/').filter(String::isNotBlank)
    if (segments.isEmpty()) {
      return true
    }
    return segments.any { segment -> segment in DOWNLOAD_SYNC_SKIPPED_DIRECTORY_NAMES }
  }

  private fun resolveRemoteRelativePathInWorkspace(
    workspaceRoot: Path,
    relativePath: String,
  ): Path? {
    val normalized = relativePath.trim().replace('\\', '/').trim('/')
    if (normalized.isBlank()) {
      return null
    }
    val resolved = normalized
      .split('/')
      .filter(String::isNotBlank)
      .fold(workspaceRoot.toAbsolutePath().normalize()) { current, segment -> current.resolve(segment) }
      .normalize()
    return resolved.takeIf { candidate -> candidate.startsWith(workspaceRoot.toAbsolutePath().normalize()) }
  }

  private fun writeBytesAtomically(
    path: Path,
    bytes: ByteArray,
  ) {
    path.parent?.let(Files::createDirectories)
    val tempPath = path.resolveSibling("${path.fileName}.opencray-download-${UUID.randomUUID()}.tmp")
    Files.write(tempPath, bytes)
    try {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: Exception) {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private fun apiHeaders(
    apiKey: String,
    jsonBody: Boolean = false,
  ): Map<String, String> = buildMap {
    put("Accept", "application/json")
    put("Authorization", "Bearer $apiKey")
    put("User-Agent", DEFAULT_E2B_USER_AGENT)
    if (jsonBody) {
      put("Content-Type", "application/json")
    }
  }

  private fun sandboxHeaders(
    session: E2BSandboxSessionSnapshot,
    jsonBody: Boolean = false,
  ): Map<String, String> = buildMap {
    put("Accept", "application/json")
    put("User-Agent", DEFAULT_E2B_USER_AGENT)
    session.envdAccessToken?.trim()?.takeIf(String::isNotBlank)?.let { token ->
      put("X-Access-Token", token)
    }
    session.trafficAccessToken?.trim()?.takeIf(String::isNotBlank)?.let { token ->
      put("E2B-Traffic-Access-Token", token)
    }
    if (jsonBody) {
      put("Content-Type", "application/json")
    }
  }

  private fun sandboxPortBaseUrl(
    session: E2BSandboxSessionSnapshot,
    port: Int,
  ): String = "https://${port}-${session.sandboxId}.${session.sandboxDomain}"

  private fun sandboxFilesUrl(
    session: E2BSandboxSessionSnapshot,
    remotePath: String,
  ): String = "${sandboxPortBaseUrl(session, ENVD_PORT)}/files?path=${encodeQueryComponent(remotePath)}"

  private fun codeInterpreterBaseUrl(session: E2BSandboxSessionSnapshot): String =
    sandboxPortBaseUrl(session, JUPYTER_PORT)

  private fun remoteWorkspaceRootForRequest(requestId: String): String =
    "$REMOTE_WORKSPACE_ROOT_BASE/${encodePathComponentForRemotePath(requestId)}"

  private fun remotePathFor(
    remoteWorkspaceRoot: String,
    workspaceRoot: Path,
    localPath: Path,
  ): String {
    val relative = workspaceRoot.relativize(localPath.toAbsolutePath().normalize())
    val relativeText = relative.joinToString(separator = "/") { component -> component.toString() }
    return if (relativeText.isBlank()) remoteWorkspaceRoot else "$remoteWorkspaceRoot/$relativeText"
  }

  private fun timeoutInt(value: Long): Int = value
    .coerceAtLeast(1L)
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()

  companion object {
    const val DEFAULT_TEMPLATE_ID: String = "code-interpreter-v1"
    const val DEFAULT_SANDBOX_DOMAIN: String = "e2b.app"
    const val REMOTE_WORKSPACE_ROOT_BASE: String = "/home/user/opencray/workspace"
    const val ERROR_INVALID_WORKSPACE: String = "INVALID_WORKSPACE"
    const val ERROR_SCRIPT_PATH_NOT_ALLOWED: String = "DENY_PATH_ESCAPE"
    const val ERROR_SCRIPT_NOT_FOUND: String = "SCRIPT_NOT_FOUND"
    const val ERROR_E2B_API_KEY_MISSING: String = "E2B_API_KEY_MISSING"
    const val ERROR_E2B_SESSION_START_FAILED: String = "E2B_SESSION_START_FAILED"
    const val ERROR_E2B_EXEC_FAILED: String = "E2B_EXEC_FAILED"
    const val ERROR_E2B_EXEC_ERROR: String = "EXEC_ERROR"
    const val ERROR_E2B_TIMEOUT: String = "TIMEOUT"
    const val ERROR_E2B_CANCELLED: String = "CANCELLED"
    private const val ENVD_PORT: Int = 49983
    private const val JUPYTER_PORT: Int = 49999
    private val SKIPPED_DIRECTORY_NAMES: Set<String> = setOf(
      ".git",
      ".gradle",
      ".idea",
      ".dart_tool",
      ".pytest_cache",
      ".tmp",
      "__pycache__",
      "build",
      "node_modules",
      "venv",
      "wheelhouse",
    )
    private val DOWNLOAD_SYNC_SKIPPED_DIRECTORY_NAMES: Set<String> = setOf(
      ".git",
      ".gradle",
      ".idea",
      ".dart_tool",
      ".opencray",
      ".pytest_cache",
      ".tmp",
      "__pycache__",
      "node_modules",
      "venv",
      "wheelhouse",
    )
  }
}

internal sealed interface ExecutionEvent {
  data class Stdout(
    val text: String,
  ) : ExecutionEvent

  data class Stderr(
    val text: String,
  ) : ExecutionEvent

  data class Error(
    val error: RemoteExecutionError,
  ) : ExecutionEvent

  data object Result : ExecutionEvent

  data object ExecutionCount : ExecutionEvent
}

internal data class RemoteWorkspaceDiffManifest(
  val changedFiles: List<String> = emptyList(),
  val deletedFiles: List<String> = emptyList(),
)

internal sealed interface WorkspaceDiffManifestParseResult {
  data object NotManifest : WorkspaceDiffManifestParseResult

  data object Invalid : WorkspaceDiffManifestParseResult

  data class Parsed(
    val manifest: RemoteWorkspaceDiffManifest,
  ) : WorkspaceDiffManifestParseResult
}

internal data class RemoteExecutionError(
  val name: String,
  val value: String,
  val traceback: String,
)

internal data class WorkspaceFilesPlan(
  val files: List<Path> = emptyList(),
  val skippedDirectories: Int = 0,
  val skippedFiles: Int = 0,
)

internal data class WorkspaceSyncSummary(
  val uploadedFiles: Int = 0,
  val uploadedBytes: Long = 0L,
  val skippedDirectories: Int = 0,
  val skippedFiles: Int = 0,
)

internal data class WorkspaceDownloadSummary(
  val downloadedFiles: Int = 0,
  val downloadedBytes: Long = 0L,
  val remoteChangedFiles: Int = 0,
  val remoteDeletedFiles: Int = 0,
  val skippedDownloadFiles: Int = 0,
  val skippedRemoteDeletes: Int = 0,
  val downloadFailures: Int = 0,
  val downloadedRelativePaths: List<String> = emptyList(),
)

internal data class ActiveExecution(
  val requestId: String,
  val apiKey: String,
  val session: E2BSandboxSessionSnapshot,
  val effectiveRequestTimeoutMs: Long,
  val cancelled: AtomicBoolean = AtomicBoolean(false),
  val timedOut: AtomicBoolean = AtomicBoolean(false),
  val completed: AtomicBoolean = AtomicBoolean(false),
  @Volatile var contextId: String? = null,
)

internal data class E2BRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: String? = null,
  val connectTimeoutMs: Int = DEFAULT_E2B_CONNECT_TIMEOUT_MS,
  val readTimeoutMs: Int = DEFAULT_E2B_READ_TIMEOUT_MS,
)

internal data class E2BUploadRequest(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val fieldName: String,
  val fileName: String,
  val fileBytes: ByteArray,
  val connectTimeoutMs: Int = DEFAULT_E2B_CONNECT_TIMEOUT_MS,
  val readTimeoutMs: Int = DEFAULT_E2B_READ_TIMEOUT_MS,
)

internal data class E2BDownloadRequest(
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val connectTimeoutMs: Int = DEFAULT_E2B_CONNECT_TIMEOUT_MS,
  val readTimeoutMs: Int = DEFAULT_E2B_READ_TIMEOUT_MS,
)

internal data class E2BResponse(
  val statusCode: Int,
  val body: String = "",
) {
  fun message(): String {
    val trimmed = body.trim()
    return if (trimmed.isNotBlank()) trimmed else "HTTP $statusCode"
  }
}

internal data class E2BBinaryResponse(
  val statusCode: Int,
  val bodyBytes: ByteArray = ByteArray(0),
  val errorBody: String = "",
) {
  fun message(): String {
    val trimmed = errorBody.trim()
    return if (trimmed.isNotBlank()) trimmed else "HTTP $statusCode"
  }
}

internal interface E2BTransport {
  fun request(request: E2BRequest): E2BResponse

  fun upload(request: E2BUploadRequest): E2BResponse

  fun download(request: E2BDownloadRequest): E2BBinaryResponse

  fun stream(
    request: E2BRequest,
    onLine: (String) -> Unit,
  ): E2BResponse
}

internal class UrlConnectionE2BTransport : E2BTransport {
  override fun request(request: E2BRequest): E2BResponse {
    val connection = openConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.body != null,
    )
    return try {
      request.body?.let { body ->
        connection.outputStream.use { output ->
          output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val statusCode = connection.responseCode
      E2BResponse(
        statusCode = statusCode,
        body = readFully(
          input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
        ),
      )
    } finally {
      connection.disconnect()
    }
  }

  override fun upload(request: E2BUploadRequest): E2BResponse {
    val boundary = "----OpenCrayE2B${UUID.randomUUID()}"
    val headers = request.headers + mapOf(
      "Content-Type" to "multipart/form-data; boundary=$boundary",
    )
    val connection = openConnection(
      method = "POST",
      url = request.url,
      headers = headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = true,
    )
    return try {
      connection.outputStream.use { output ->
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(
          buildString {
            append("Content-Disposition: form-data; name=\"")
            append(request.fieldName)
            append("\"; filename=\"")
            append(request.fileName)
            append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
          }.toByteArray(StandardCharsets.UTF_8),
        )
        output.write(request.fileBytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
      }
      val statusCode = connection.responseCode
      E2BResponse(
        statusCode = statusCode,
        body = readFully(
          input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
        ),
      )
    } finally {
      connection.disconnect()
    }
  }

  override fun download(request: E2BDownloadRequest): E2BBinaryResponse {
    val connection = openConnection(
      method = "GET",
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = false,
    )
    return try {
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        E2BBinaryResponse(
          statusCode = statusCode,
          bodyBytes = readFullyBytes(connection.inputStream),
        )
      } else {
        E2BBinaryResponse(
          statusCode = statusCode,
          errorBody = readFully(connection.errorStream),
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  override fun stream(
    request: E2BRequest,
    onLine: (String) -> Unit,
  ): E2BResponse {
    val connection = openConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.body != null,
    )
    return try {
      request.body?.let { body ->
        connection.outputStream.use { output ->
          output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        BufferedReader(
          InputStreamReader(connection.inputStream, StandardCharsets.UTF_8),
        ).use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) {
              onLine(line)
            }
          }
        }
        E2BResponse(statusCode = statusCode)
      } else {
        E2BResponse(
          statusCode = statusCode,
          body = readFully(connection.errorStream),
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(
    method: String,
    url: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    doOutput: Boolean,
  ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = method
    connectTimeout = connectTimeoutMs
    readTimeout = readTimeoutMs
    instanceFollowRedirects = true
    doInput = true
    this.doOutput = doOutput
    useCaches = false
    headers.forEach { (name, value) ->
      if (name.isNotBlank() && value.isNotBlank()) {
        setRequestProperty(name, value)
      }
    }
  }

  private fun readFully(input: InputStream?): String {
    if (input == null) {
      return ""
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toString(StandardCharsets.UTF_8.name())
    }
  }

  private fun readFullyBytes(input: InputStream?): ByteArray {
    if (input == null) {
      return ByteArray(0)
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toByteArray()
    }
  }
}

private fun E2BSandboxSessionSnapshot.matches(
  workspaceRoot: Path,
  templateId: String,
): Boolean {
  val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize()
  val storedWorkspace = runCatching { Paths.get(this.workspaceRoot) }
    .getOrNull()
    ?.toAbsolutePath()
    ?.normalize()
    ?: return false
  return storedWorkspace == normalizedWorkspace && this.templateId == templateId
}

private fun JsonObject.stringValue(key: String): String? =
  this[key]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun JsonObject.stringList(key: String): List<String> =
  (this[key] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { element ->
      element.jsonPrimitive.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
    }
    ?.distinct()
    ?: emptyList()

private fun encodeQueryComponent(value: String): String =
  URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun encodePathSegment(value: String): String = encodeQueryComponent(value)

private fun encodePathComponentForRemotePath(value: String): String =
  value.trim().ifBlank { UUID.randomUUID().toString() }
    .replace('\\', '-')
    .replace('/', '-')
    .replace(':', '-')
    .replace(' ', '-')
    .lowercase(Locale.US)
