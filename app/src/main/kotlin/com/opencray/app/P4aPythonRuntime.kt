package com.opencray.app

import android.content.Context
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android-side file bridge and service launcher contract for the embedded `python-for-android`
 * runtime backend.
 */
internal class P4aPythonRuntime private constructor(
  runtimeRoot: Path,
  private val launcher: P4aPythonRuntimeLauncher = UnavailableP4aPythonRuntimeLauncher,
  private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) : PythonScriptRuntime, CancellablePythonScriptRuntime {
  private val runtimeRoot: Path = runtimeRoot
  private val requestsDir: Path = runtimeRoot.resolve("requests")
  private val resultsDir: Path = runtimeRoot.resolve("results")
  private val logsDir: Path = runtimeRoot.resolve("logs")
  private val cancelsDir: Path = runtimeRoot.resolve("cancels")
  private val serviceStateDir: Path = runtimeRoot.resolve("service_state")

  override fun exec(request: PythonExecRequest): ExecutionResult {
    val startedAt = System.currentTimeMillis()
    val scriptTimeoutMs = request.timeoutMs.coerceAtLeast(0L)
    val startupTimeoutMs = resolveStartupTimeoutMs(
      explicitStartupTimeoutMs = request.startupTimeoutMs,
      scriptTimeoutMs = scriptTimeoutMs,
    )
    val servicePollIntervalMs = resolveServicePollIntervalMs(startupTimeoutMs)
    val requestId = request.requestId?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
    val requestPath = requestsDir.resolve("$requestId.json")
    val resultPath = resultsDir.resolve("$requestId.json")
    val logPath = logsDir.resolve("$requestId.log")
    val cancelPath = cancelPathFor(requestId)
    val serviceStatePath = serviceStatePath()
    val serviceReadyPath = serviceReadyPath()
    val runtimeMetadata = linkedMapOf(
      "runtimeBackend" to "p4a",
      "runtimeTransport" to "file_json_bridge",
      "requestId" to requestId,
      "requestPath" to requestPath.toString(),
      "resultPath" to resultPath.toString(),
      "logPath" to logPath.toString(),
      "cancelPath" to cancelPath.toString(),
      "scriptTimeoutMs" to scriptTimeoutMs.toString(),
      "startupTimeoutMs" to startupTimeoutMs.toString(),
      "servicePollIntervalMs" to servicePollIntervalMs.toString(),
      "serviceRunMode" to "once",
      "serviceStatePath" to serviceStatePath.toString(),
      "serviceReadyPath" to serviceReadyPath.toString(),
    )

    return try {
      Files.createDirectories(requestsDir)
      Files.createDirectories(resultsDir)
      Files.createDirectories(logsDir)
      Files.createDirectories(cancelsDir)
      Files.createDirectories(serviceStateDir)
      Files.deleteIfExists(requestPath)
      Files.deleteIfExists(resultPath)
      Files.deleteIfExists(logPath)
      Files.deleteIfExists(cancelPath)

      val payload = P4aPythonExecBridgeRequest(
        schemaVersion = BRIDGE_SCHEMA_VERSION,
        requestId = requestId,
        taskId = request.taskId,
        workspaceRoot = request.workspaceRoot.toString(),
        scriptPath = request.scriptPath.toString(),
        args = request.args,
        timeoutMs = scriptTimeoutMs,
        requestedAtEpochMs = startedAt,
        cancelPath = cancelPath.toString(),
      )
      writeAtomicText(
        requestPath,
        json.encodeToString(payload) + "\n",
      )

      val launcherDispatchStartedAt = System.currentTimeMillis()
      val launchResult = launcher.launch(
        P4aPythonLaunchRequest(
          bridgeRequest = payload,
          requestPath = requestPath,
          resultPath = resultPath,
          logPath = logPath,
          servicePollIntervalMs = servicePollIntervalMs,
          runOnce = true,
        ),
      )
      val launcherDispatchCompletedAt = System.currentTimeMillis()
      val launchTimingMetadata = mapOf(
        "launcherDispatchStartedAtEpochMs" to launcherDispatchStartedAt.toString(),
        "launcherDispatchCompletedAtEpochMs" to launcherDispatchCompletedAt.toString(),
        "launcherDispatchDurationMs" to (launcherDispatchCompletedAt - launcherDispatchStartedAt).toString(),
        "startupTimerStartedAtEpochMs" to launcherDispatchCompletedAt.toString(),
      )

      when (launchResult) {
        is P4aPythonRuntimeLaunchResult.Dispatched -> waitForBridgeResult(
          taskId = request.taskId,
          requestId = requestId,
          startupTimeoutMs = startupTimeoutMs,
          scriptTimeoutMs = scriptTimeoutMs,
          startedAt = startedAt,
          startupWaitStartedAt = launcherDispatchCompletedAt,
          requestPath = requestPath,
          resultPath = resultPath,
          logPath = logPath,
          cancelPath = cancelPath,
          serviceStatePath = serviceStatePath,
          serviceReadyPath = serviceReadyPath,
          metadata = runtimeMetadata + launchResult.metadata + launchTimingMetadata,
        )

        is P4aPythonRuntimeLaunchResult.Unavailable -> {
          val finishedAt = System.currentTimeMillis()
          val cleanupMetadata = cleanupRequestArtifacts(
            requestPath = requestPath,
            cancelPath = cancelPath,
            writeCancelMarker = true,
          )
          ExecutionResult(
            taskId = request.taskId,
            status = ExecutionStatus.FAILED,
            exitCode = null,
            stdout = "",
            stderr = "",
            errorCode = launchResult.errorCode,
            errorMessage = launchResult.errorMessage,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            metadata = runtimeMetadata + launchResult.metadata + launchTimingMetadata + cleanupMetadata,
          )
        }
      }
    } catch (error: Throwable) {
      val finishedAt = System.currentTimeMillis()
      val cleanupMetadata = cleanupRequestArtifacts(
        requestPath = requestPath,
        cancelPath = cancelPath,
        writeCancelMarker = true,
      )
      ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = null,
        stdout = "",
        stderr = "",
        errorCode = ERROR_P4A_REQUEST_PREPARATION_FAILED,
        errorMessage = error.message ?: "Failed to prepare embedded Python runtime request.",
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = runtimeMetadata + cleanupMetadata,
      )
    }
  }

  override fun requestCancellation(requestId: String): Boolean {
    val normalizedRequestId = requestId.trim()
    if (normalizedRequestId.isBlank()) {
      return false
    }
    return runCatching {
      Files.createDirectories(cancelsDir)
      Files.write(
        cancelPathFor(normalizedRequestId),
        "cancelled\n".toByteArray(StandardCharsets.UTF_8),
      )
      true
    }.getOrDefault(false)
  }

  private fun waitForBridgeResult(
    taskId: String,
    requestId: String,
    startupTimeoutMs: Long,
    scriptTimeoutMs: Long,
    startedAt: Long,
    startupWaitStartedAt: Long,
    requestPath: Path,
    resultPath: Path,
    logPath: Path,
    cancelPath: Path,
    serviceStatePath: Path,
    serviceReadyPath: Path,
    metadata: Map<String, String>,
  ): ExecutionResult {
    val startupBudgetMs = startupTimeoutMs.coerceAtLeast(0L)
    val scriptBudgetMs = scriptTimeoutMs.coerceAtLeast(0L)
    val startupDeadline = startupWaitStartedAt + startupBudgetMs
    var requestExecutionObservation: RequestExecutionObservation? = null

    while (true) {
      if (Files.exists(resultPath)) {
        return readBridgeResult(
          taskId = taskId,
          requestPath = requestPath,
          cancelPath = cancelPath,
          resultPath = resultPath,
          metadata = metadata,
        )
      }
      val now = System.currentTimeMillis()
      if (requestExecutionObservation == null) {
        requestExecutionObservation = findRequestExecutionObservation(
          requestId = requestId,
          serviceReadyPath = serviceReadyPath,
          serviceStatePath = serviceStatePath,
        )
      }
      val deadline = requestExecutionObservation?.executionStartedAtEpochMs
        ?.let { executionStartedAt -> executionStartedAt + scriptBudgetMs }
        ?: startupDeadline
      if (now > deadline) {
        break
      }
      Thread.sleep(resolvePollIntervalMs(maxOf(startupBudgetMs, scriptBudgetMs)))
    }

    val latestServiceMarker = findLatestServiceLifecycleMarker(
      serviceReadyPath = serviceReadyPath,
      serviceStatePath = serviceStatePath,
    )
    val timeoutStage = resolveTimeoutStage(
      requestExecutionObservation = requestExecutionObservation,
      latestServiceMarker = latestServiceMarker,
    )
    val diagnostics = collectTimeoutDiagnostics(
      requestPath = requestPath,
      resultPath = resultPath,
      logPath = logPath,
      cancelPath = cancelPath,
      serviceReadyPath = serviceReadyPath,
      serviceStatePath = serviceStatePath,
    )
    val cleanupMetadata = cleanupRequestArtifacts(
      requestPath = requestPath,
      cancelPath = cancelPath,
      writeCancelMarker = true,
    )
    val stopMetadata = if (timeoutStage == "result") {
      launcher.stop()
    } else {
      emptyMap()
    }
    val finishedAt = System.currentTimeMillis()
    return ExecutionResult(
      taskId = taskId,
      status = ExecutionStatus.TIMEOUT,
      exitCode = null,
      stdout = "",
      stderr = diagnostics.stderr,
      errorCode = when (timeoutStage) {
        "queue" -> ERROR_P4A_QUEUE_TIMEOUT
        "result" -> ERROR_P4A_RESULT_TIMEOUT
        else -> ERROR_P4A_STARTUP_TIMEOUT
      },
      errorMessage = when (timeoutStage) {
        "queue" -> "Timed out waiting for the embedded Python runtime service to claim the request."
        "result" -> "Timed out waiting for the embedded Python runtime result after service startup."
        else -> "Timed out waiting for the embedded Python runtime service to become ready."
      },
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = finishedAt,
      metadata = metadata + diagnostics.metadata + cleanupMetadata + stopMetadata + buildMap {
        put("timeoutStage", timeoutStage)
        put("serviceReadyObserved", (requestExecutionObservation != null).toString())
        latestServiceMarker?.let { marker ->
          put("serviceMarkerSource", marker.source)
          marker.state?.let { put("serviceState", it) }
          marker.startupRequestId?.let { put("serviceStartupRequestId", it) }
          marker.currentRequestId?.let { put("serviceCurrentRequestId", it) }
          marker.claimedRequestId?.let { put("serviceClaimedRequestId", it) }
          marker.updatedAtEpochMs?.let { put("serviceMarkerUpdatedAtEpochMs", it.toString()) }
          marker.lastModifiedEpochMs?.let { put("serviceMarkerLastModifiedEpochMs", it.toString()) }
          marker.blockingRequestId(requestId)?.let { put("blockingRequestId", it) }
        }
        requestExecutionObservation?.let { observation ->
          put("serviceReadyObservedAtEpochMs", observation.observedAtEpochMs.toString())
          put("serviceExecutionStartedAtEpochMs", observation.executionStartedAtEpochMs.toString())
          put("serviceExecutionMarkerSource", observation.source)
          observation.state?.let { put("serviceExecutionState", it) }
          observation.startupRequestId?.let { put("serviceStartupRequestId", it) }
          observation.currentRequestId?.let { put("serviceCurrentRequestId", it) }
          observation.claimedRequestId?.let { put("serviceClaimedRequestId", it) }
          observation.markerUpdatedAtEpochMs?.let { put("serviceMarkerUpdatedAtEpochMs", it.toString()) }
        }
      },
    )
  }

  private fun readBridgeResult(
    taskId: String,
    requestPath: Path,
    cancelPath: Path,
    resultPath: Path,
    metadata: Map<String, String>,
  ): ExecutionResult = try {
    val bridgeResult = json.decodeFromString<P4aPythonExecBridgeResult>(
      String(Files.readAllBytes(resultPath), StandardCharsets.UTF_8),
    )
    ExecutionResult(
      taskId = bridgeResult.taskId.ifBlank { taskId },
      status = bridgeResult.toExecutionStatus(),
      exitCode = bridgeResult.exitCode,
      stdout = bridgeResult.stdout,
      stderr = bridgeResult.stderr,
      errorCode = bridgeResult.errorCode,
      errorMessage = bridgeResult.errorMessage,
      startedAtEpochMs = bridgeResult.startedAtEpochMs,
      finishedAtEpochMs = bridgeResult.finishedAtEpochMs,
      metadata = bridgeResult.metadata + metadata + cleanupRequestArtifacts(
        requestPath = requestPath,
        cancelPath = cancelPath,
        writeCancelMarker = false,
      ),
    )
  } catch (error: Throwable) {
    val finishedAt = System.currentTimeMillis()
    val cleanupMetadata = cleanupRequestArtifacts(
      requestPath = requestPath,
      cancelPath = cancelPath,
      writeCancelMarker = false,
    )
    ExecutionResult(
      taskId = taskId,
      status = ExecutionStatus.FAILED,
      exitCode = null,
      stdout = "",
      stderr = "",
      errorCode = ERROR_P4A_RESULT_PARSE_FAILED,
      errorMessage = error.message ?: "Failed to parse embedded Python runtime result.",
      startedAtEpochMs = finishedAt,
      finishedAtEpochMs = finishedAt,
      metadata = metadata + cleanupMetadata,
    )
  }

  private fun findRequestExecutionObservation(
    requestId: String,
    serviceReadyPath: Path,
    serviceStatePath: Path,
  ): RequestExecutionObservation? {
    val observedAt = System.currentTimeMillis()
    return listOf(
      parseServiceLifecycleMarker(serviceStatePath, "service_state"),
      parseServiceLifecycleMarker(serviceReadyPath, "service_ready"),
    ).filterNotNull()
      .firstOrNull { marker -> marker.matchesRequest(requestId) }
      ?.toExecutionObservation(observedAtEpochMs = observedAt)
  }

  private fun findLatestServiceLifecycleMarker(
    serviceReadyPath: Path,
    serviceStatePath: Path,
  ): ServiceLifecycleMarker? = listOf(
    parseServiceLifecycleMarker(serviceStatePath, "service_state"),
    parseServiceLifecycleMarker(serviceReadyPath, "service_ready"),
  ).filterNotNull()
    .maxByOrNull(ServiceLifecycleMarker::recencyEpochMs)

  private fun resolveTimeoutStage(
    requestExecutionObservation: RequestExecutionObservation?,
    latestServiceMarker: ServiceLifecycleMarker?,
  ): String = when {
    requestExecutionObservation != null -> "result"
    latestServiceMarker == null || latestServiceMarker.state == "startup_error" -> "startup"
    else -> "queue"
  }

  private fun parseServiceLifecycleMarker(
    path: Path,
    source: String,
  ): ServiceLifecycleMarker? = runCatching {
    if (!Files.exists(path)) {
      null
    } else {
      val lastModifiedEpochMs = Files.getLastModifiedTime(path).toMillis()
      val payload = json.parseToJsonElement(
        String(Files.readAllBytes(path), StandardCharsets.UTF_8),
      ).jsonObject
      ServiceLifecycleMarker(
        source = source,
        state = payload.stringValue("state"),
        startupRequestId = payload.stringValue("startupRequestId"),
        currentRequestId = payload.stringValue("currentRequestId"),
        claimedRequestId = payload.stringValue("claimedRequestId"),
        executionStartedAtEpochMs = payload.longValue("executionStartedAtEpochMs"),
        updatedAtEpochMs = payload.longValue("updatedAtEpochMs"),
        lastModifiedEpochMs = lastModifiedEpochMs,
      )
    }
  }.getOrNull()

  private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

  private fun kotlinx.serialization.json.JsonObject.longValue(key: String): Long? =
    get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()

  private fun collectTimeoutDiagnostics(
    requestPath: Path,
    resultPath: Path,
    logPath: Path,
    cancelPath: Path,
    serviceReadyPath: Path,
    serviceStatePath: Path,
  ): TimeoutDiagnostics {
    val requestInfo = snapshotFile(requestPath, previewMode = PreviewMode.NONE)
    val resultInfo = snapshotFile(resultPath, previewMode = PreviewMode.NONE)
    val logInfo = snapshotFile(logPath, previewMode = PreviewMode.TAIL)
    val cancelInfo = snapshotFile(cancelPath, previewMode = PreviewMode.NONE)
    val serviceReadyInfo = snapshotFile(serviceReadyPath, previewMode = PreviewMode.HEAD)
    val serviceStateInfo = snapshotFile(serviceStatePath, previewMode = PreviewMode.HEAD)

    val metadata = linkedMapOf<String, String>()
    appendSnapshotMetadata(metadata, "request", requestInfo)
    appendSnapshotMetadata(metadata, "result", resultInfo)
    appendSnapshotMetadata(metadata, "log", logInfo)
    appendSnapshotMetadata(metadata, "cancel", cancelInfo)
    appendSnapshotMetadata(metadata, "serviceReady", serviceReadyInfo)
    appendSnapshotMetadata(metadata, "serviceState", serviceStateInfo)

    return TimeoutDiagnostics(
      metadata = metadata,
      stderr = buildString {
        appendLine("Embedded Python runtime timeout diagnostics:")
        appendLine(renderSnapshotLine("request", requestInfo))
        appendLine(renderSnapshotLine("result", resultInfo))
        appendLine(renderSnapshotLine("log", logInfo))
        appendLine(renderSnapshotLine("cancel", cancelInfo))
        appendLine(renderSnapshotLine("service_ready", serviceReadyInfo))
        appendLine(renderSnapshotLine("service_state", serviceStateInfo))
        serviceStateInfo.preview?.let { preview ->
          appendLine("service_state_preview:")
          appendLine(preview)
        }
        serviceReadyInfo.preview?.let { preview ->
          appendLine("service_ready_preview:")
          appendLine(preview)
        }
        logInfo.preview?.let { preview ->
          appendLine("log_tail:")
          append(preview)
        }
      }.trimEnd(),
    )
  }

  private fun snapshotFile(
    path: Path,
    previewMode: PreviewMode,
  ): FileSnapshot {
    val exists = runCatching { Files.exists(path) }.getOrDefault(false)
    val sizeBytes = if (exists) runCatching { Files.size(path) }.getOrNull() else null
    val lastModifiedEpochMs = if (exists) runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull() else null
    val preview = when {
      !exists || previewMode == PreviewMode.NONE -> null
      previewMode == PreviewMode.HEAD -> readFileHeadPreview(path)
      else -> readFileTailPreview(path)
    }
    return FileSnapshot(
      path = path,
      exists = exists,
      sizeBytes = sizeBytes,
      lastModifiedEpochMs = lastModifiedEpochMs,
      preview = preview,
    )
  }

  private fun appendSnapshotMetadata(
    metadata: MutableMap<String, String>,
    prefix: String,
    snapshot: FileSnapshot,
  ) {
    metadata["${prefix}Exists"] = snapshot.exists.toString()
    snapshot.sizeBytes?.let { metadata["${prefix}SizeBytes"] = it.toString() }
    snapshot.lastModifiedEpochMs?.let { metadata["${prefix}LastModifiedEpochMs"] = it.toString() }
    snapshot.preview?.let { metadata["${prefix}Preview"] = sanitizeMetadataPreview(it) }
  }

  private fun renderSnapshotLine(prefix: String, snapshot: FileSnapshot): String = buildString {
    append(prefix)
    append(": exists=")
    append(snapshot.exists)
    append(" path=")
    append(snapshot.path)
    snapshot.sizeBytes?.let {
      append(" size_bytes=")
      append(it)
    }
    snapshot.lastModifiedEpochMs?.let {
      append(" last_modified_epoch_ms=")
      append(it)
    }
  }

  private fun readFileHeadPreview(path: Path): String? =
    runCatching {
      truncateDiagnosticPreview(
        String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim(),
        fromEnd = false,
      )
    }.getOrNull()?.takeIf(String::isNotBlank)

  private fun readFileTailPreview(path: Path): String? =
    runCatching {
      truncateDiagnosticPreview(
        String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim(),
        fromEnd = true,
      )
    }.getOrNull()?.takeIf(String::isNotBlank)

  private fun truncateDiagnosticPreview(
    content: String,
    fromEnd: Boolean,
  ): String {
    if (content.length <= MAX_DIAGNOSTIC_PREVIEW_CHARS) {
      return content
    }
    val slice = if (fromEnd) {
      content.takeLast(MAX_DIAGNOSTIC_PREVIEW_CHARS)
    } else {
      content.take(MAX_DIAGNOSTIC_PREVIEW_CHARS)
    }
    return if (fromEnd) {
      "...$slice"
    } else {
      "$slice..."
    }
  }

  private fun sanitizeMetadataPreview(content: String): String =
    content.replace("\r", "").replace("\n", "\\n")

  private fun writeAtomicText(path: Path, content: String) {
    Files.createDirectories(path.parent)
    val tempPath = path.resolveSibling("${path.fileName}.tmp")
    Files.write(tempPath, content.toByteArray(StandardCharsets.UTF_8))
    runCatching {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    }.getOrElse {
      Files.move(
        tempPath,
        path,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private fun cleanupRequestArtifacts(
    requestPath: Path,
    cancelPath: Path?,
    writeCancelMarker: Boolean,
  ): Map<String, String> {
    val metadata = linkedMapOf<String, String>()
    if (writeCancelMarker && cancelPath != null) {
      val cancelWritten = runCatching {
        Files.createDirectories(cancelPath.parent)
        Files.write(
          cancelPath,
          "cancelled\n".toByteArray(StandardCharsets.UTF_8),
        )
        true
      }.getOrDefault(false)
      metadata["cleanupCancelMarkerWritten"] = cancelWritten.toString()
    }
    val requestDeleted = runCatching {
      Files.deleteIfExists(requestPath)
    }.getOrDefault(false)
    metadata["cleanupRequestDeleted"] = requestDeleted.toString()
    if (!writeCancelMarker && cancelPath != null) {
      val cancelDeleted = runCatching {
        Files.deleteIfExists(cancelPath)
      }.getOrDefault(false)
      metadata["cleanupCancelMarkerDeleted"] = cancelDeleted.toString()
    }
    return metadata
  }

  private fun resolveStartupTimeoutMs(
    explicitStartupTimeoutMs: Long?,
    scriptTimeoutMs: Long,
  ): Long = explicitStartupTimeoutMs?.coerceAtLeast(0L)
    ?: scriptTimeoutMs.coerceIn(MIN_STARTUP_TIMEOUT_MS, MAX_STARTUP_TIMEOUT_MS)

  private fun resolveServicePollIntervalMs(startupTimeoutMs: Long): Long =
    (startupTimeoutMs.coerceAtLeast(0L) / 20L).coerceIn(
      MIN_SERVICE_POLL_INTERVAL_MS,
      MAX_SERVICE_POLL_INTERVAL_MS,
    )

  private fun resolvePollIntervalMs(timeoutMs: Long): Long =
    (timeoutMs / 20L).coerceIn(DEFAULT_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)

  @Serializable
  internal data class P4aPythonExecBridgeRequest(
    val schemaVersion: Int = BRIDGE_SCHEMA_VERSION,
    val requestId: String,
    val taskId: String,
    val workspaceRoot: String,
    val scriptPath: String,
    val args: List<String>,
    val timeoutMs: Long,
    val requestedAtEpochMs: Long,
    val cancelPath: String? = null,
  )

  @Serializable
  internal data class P4aPythonExecBridgeResult(
    val schemaVersion: Int = BRIDGE_SCHEMA_VERSION,
    val requestId: String,
    val taskId: String,
    val status: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val metadata: Map<String, String> = emptyMap(),
  ) {
    fun toExecutionStatus(): ExecutionStatus = when (status.trim().lowercase()) {
      "success" -> ExecutionStatus.SUCCESS
      "timeout" -> ExecutionStatus.TIMEOUT
      "cancelled" -> ExecutionStatus.CANCELLED
      "denied" -> ExecutionStatus.DENIED
      else -> ExecutionStatus.FAILED
    }
  }

  internal data class P4aPythonLaunchRequest(
    val bridgeRequest: P4aPythonExecBridgeRequest,
    val requestPath: Path,
    val resultPath: Path,
    val logPath: Path,
    val servicePollIntervalMs: Long,
    val runOnce: Boolean,
  )

  internal interface P4aPythonRuntimeLauncher {
    fun launch(request: P4aPythonLaunchRequest): P4aPythonRuntimeLaunchResult

    fun stop(): Map<String, String> = emptyMap()
  }

  internal sealed interface P4aPythonRuntimeLaunchResult {
    data class Dispatched(
      val metadata: Map<String, String> = emptyMap(),
    ) : P4aPythonRuntimeLaunchResult

    data class Unavailable(
      val errorCode: String,
      val errorMessage: String,
      val metadata: Map<String, String> = emptyMap(),
    ) : P4aPythonRuntimeLaunchResult
  }

  companion object {
    internal const val BRIDGE_SCHEMA_VERSION: Int = 1
    private const val DEFAULT_POLL_INTERVAL_MS: Long = 25L
    private const val MAX_POLL_INTERVAL_MS: Long = 250L
    private const val MIN_SERVICE_POLL_INTERVAL_MS: Long = 25L
    private const val MAX_SERVICE_POLL_INTERVAL_MS: Long = 100L
    private const val MIN_STARTUP_TIMEOUT_MS: Long = 15_000L
    private const val MAX_STARTUP_TIMEOUT_MS: Long = 60_000L
    private const val MAX_DIAGNOSTIC_PREVIEW_CHARS: Int = 1_200

    const val ERROR_P4A_RUNTIME_UNAVAILABLE: String = "P4A_RUNTIME_UNAVAILABLE"
    const val ERROR_P4A_REQUEST_PREPARATION_FAILED: String = "P4A_REQUEST_PREPARATION_FAILED"
    const val ERROR_P4A_STARTUP_TIMEOUT: String = "P4A_STARTUP_TIMEOUT"
    const val ERROR_P4A_QUEUE_TIMEOUT: String = "P4A_QUEUE_TIMEOUT"
    const val ERROR_P4A_RESULT_TIMEOUT: String = "P4A_RESULT_TIMEOUT"
    const val ERROR_P4A_RESULT_PARSE_FAILED: String = "P4A_RESULT_PARSE_FAILED"

    fun fromContext(context: Context): P4aPythonRuntime = P4aPythonRuntime(
      runtimeRoot = context.applicationContext.filesDir.toPath().resolve("python_runtime"),
      launcher = ServiceBackedP4aPythonRuntimeLauncher.fromContext(context.applicationContext),
    )

    internal fun fromRuntimeRoot(
      runtimeRoot: Path,
      launcher: P4aPythonRuntimeLauncher = UnavailableP4aPythonRuntimeLauncher,
      json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
    ): P4aPythonRuntime = P4aPythonRuntime(
      runtimeRoot = runtimeRoot,
      launcher = launcher,
      json = json,
    )
  }

  internal fun cancelPathFor(requestId: String): Path = cancelsDir.resolve("$requestId.cancel")
  internal fun serviceStatePath(): Path = serviceStateDir.resolve("service-state.json")
  internal fun serviceReadyPath(): Path = serviceStateDir.resolve("service-ready.json")

  private data class FileSnapshot(
    val path: Path,
    val exists: Boolean,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    val preview: String?,
  )

  private data class TimeoutDiagnostics(
    val metadata: Map<String, String>,
    val stderr: String,
  )

  private data class ServiceLifecycleMarker(
    val source: String,
    val state: String?,
    val startupRequestId: String?,
    val currentRequestId: String?,
    val claimedRequestId: String?,
    val executionStartedAtEpochMs: Long?,
    val updatedAtEpochMs: Long?,
    val lastModifiedEpochMs: Long?,
  ) {
    fun matchesRequest(requestId: String): Boolean =
      claimedRequestId == requestId || currentRequestId == requestId

    val recencyEpochMs: Long
      get() = updatedAtEpochMs ?: lastModifiedEpochMs ?: Long.MIN_VALUE

    fun blockingRequestId(requestId: String): String? = when {
      claimedRequestId != null && claimedRequestId != requestId -> claimedRequestId
      currentRequestId != null && currentRequestId != requestId -> currentRequestId
      else -> null
    }

    fun toExecutionObservation(observedAtEpochMs: Long): RequestExecutionObservation {
      val executionStartedAt = executionStartedAtEpochMs
        ?: updatedAtEpochMs
        ?: lastModifiedEpochMs
        ?: observedAtEpochMs
      return RequestExecutionObservation(
        source = source,
        state = state,
        startupRequestId = startupRequestId,
        currentRequestId = currentRequestId,
        claimedRequestId = claimedRequestId,
        executionStartedAtEpochMs = executionStartedAt,
        markerUpdatedAtEpochMs = updatedAtEpochMs,
        observedAtEpochMs = observedAtEpochMs,
      )
    }
  }

  private data class RequestExecutionObservation(
    val source: String,
    val state: String?,
    val startupRequestId: String?,
    val currentRequestId: String?,
    val claimedRequestId: String?,
    val executionStartedAtEpochMs: Long,
    val markerUpdatedAtEpochMs: Long?,
    val observedAtEpochMs: Long,
  )

  private enum class PreviewMode {
    NONE,
    HEAD,
    TAIL,
  }
}

internal object UnavailableP4aPythonRuntimeLauncher : P4aPythonRuntime.P4aPythonRuntimeLauncher {
  override fun launch(
    request: P4aPythonRuntime.P4aPythonLaunchRequest,
  ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult = P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable(
    errorCode = P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE,
    errorMessage = "Android embedded Python runtime is not wired yet.",
    metadata = mapOf("launcherState" to "unwired"),
  )
}
