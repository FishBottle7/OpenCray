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
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android-side file bridge for the future `python-for-android` runtime backend.
 *
 * The real p4a launcher is not wired yet. This runtime already owns the on-device request/result
 * contract so later phases only need to swap the launcher implementation.
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

  override fun exec(request: PythonExecRequest): ExecutionResult {
    val startedAt = System.currentTimeMillis()
    val requestId = request.requestId?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
    val requestPath = requestsDir.resolve("$requestId.json")
    val resultPath = resultsDir.resolve("$requestId.json")
    val logPath = logsDir.resolve("$requestId.log")
    val cancelPath = cancelPathFor(requestId)
    val runtimeMetadata = linkedMapOf(
      "runtimeBackend" to "p4a",
      "runtimeTransport" to "file_json_bridge",
      "requestId" to requestId,
      "requestPath" to requestPath.toString(),
      "resultPath" to resultPath.toString(),
      "logPath" to logPath.toString(),
      "cancelPath" to cancelPath.toString(),
    )

    return try {
      Files.createDirectories(requestsDir)
      Files.createDirectories(resultsDir)
      Files.createDirectories(logsDir)
      Files.createDirectories(cancelsDir)
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
        timeoutMs = request.timeoutMs,
        requestedAtEpochMs = startedAt,
        cancelPath = cancelPath.toString(),
      )
      Files.write(
        requestPath,
        (json.encodeToString(payload) + "\n").toByteArray(StandardCharsets.UTF_8),
      )

      when (
        val launchResult = launcher.launch(
          P4aPythonLaunchRequest(
            bridgeRequest = payload,
            requestPath = requestPath,
            resultPath = resultPath,
            logPath = logPath,
          ),
        )
      ) {
        is P4aPythonRuntimeLaunchResult.Dispatched -> waitForBridgeResult(
          taskId = request.taskId,
          timeoutMs = request.timeoutMs,
          startedAt = startedAt,
          resultPath = resultPath,
          metadata = runtimeMetadata + launchResult.metadata,
        )

        is P4aPythonRuntimeLaunchResult.Unavailable -> {
          val finishedAt = System.currentTimeMillis()
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
            metadata = runtimeMetadata + launchResult.metadata,
          )
        }
      }
    } catch (error: Throwable) {
      val finishedAt = System.currentTimeMillis()
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
        metadata = runtimeMetadata,
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
    timeoutMs: Long,
    startedAt: Long,
    resultPath: Path,
    metadata: Map<String, String>,
  ): ExecutionResult {
    val deadline = startedAt + timeoutMs.coerceAtLeast(0L)
    while (System.currentTimeMillis() <= deadline) {
      if (Files.exists(resultPath)) {
        return readBridgeResult(taskId = taskId, resultPath = resultPath, metadata = metadata)
      }
      Thread.sleep(resolvePollIntervalMs(timeoutMs))
    }
    val finishedAt = System.currentTimeMillis()
    return ExecutionResult(
      taskId = taskId,
      status = ExecutionStatus.TIMEOUT,
      exitCode = null,
      stdout = "",
      stderr = "",
      errorCode = ERROR_P4A_RESULT_TIMEOUT,
      errorMessage = "Timed out waiting for the embedded Python runtime result.",
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = finishedAt,
      metadata = metadata,
    )
  }

  private fun readBridgeResult(
    taskId: String,
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
      metadata = bridgeResult.metadata + metadata,
    )
  } catch (error: Throwable) {
    val finishedAt = System.currentTimeMillis()
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
      metadata = metadata,
    )
  }

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
  )

  internal interface P4aPythonRuntimeLauncher {
    fun launch(request: P4aPythonLaunchRequest): P4aPythonRuntimeLaunchResult
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

    const val ERROR_P4A_RUNTIME_UNAVAILABLE: String = "P4A_RUNTIME_UNAVAILABLE"
    const val ERROR_P4A_REQUEST_PREPARATION_FAILED: String = "P4A_REQUEST_PREPARATION_FAILED"
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
