package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class PythonExecRequest(
  val taskId: String,
  val workspaceRoot: Path,
  val scriptPath: Path,
  val args: List<String> = emptyList(),
  val timeoutMs: Long = 30_000,
  /** Python executable used to invoke the runner module (defaults to 'python'). */
  val pythonExecutable: String = "python",
)

/**
 * Adapter that executes Python scripts in an app-managed workspace.
 *
 * Current implementation shells out to `python -m python_runner.runner exec ...`.
 * On Android, this is expected to be replaced by an embedded interpreter implementation.
 */
class PythonRuntimeAdapter(
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  fun exec(request: PythonExecRequest): ExecutionResult {
    val startedAt = System.currentTimeMillis()
    val cmd = commandFor(request)

    val stdoutBuf = ByteArrayOutputStream()
    val stderrBuf = ByteArrayOutputStream()
    val proc = try {
      ProcessBuilder(cmd)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .start()
    } catch (e: Exception) {
      val finishedAt = System.currentTimeMillis()
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = null,
        stdout = "",
        stderr = "",
        errorCode = "SPAWN_ERROR",
        errorMessage = e.message,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = mapOf(
          "command" to cmd.joinToString(" "),
        ),
      )
    }

    proc.inputStream.copyTo(stdoutBuf)
    proc.errorStream.copyTo(stderrBuf)

    val finishedNormally = proc.waitFor(request.timeoutMs + 5_000, TimeUnit.MILLISECONDS)
    if (!finishedNormally) {
      proc.destroyForcibly()
      val finishedAt = System.currentTimeMillis()
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.TIMEOUT,
        exitCode = null,
        stdout = stdoutBuf.toString(StandardCharsets.UTF_8.name()),
        stderr = stderrBuf.toString(StandardCharsets.UTF_8.name()),
        errorCode = "TIMEOUT",
        errorMessage = "Runner process exceeded timeout.",
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = mapOf(
          "command" to cmd.joinToString(" "),
        ),
      )
    }

    val stdout = stdoutBuf.toString(StandardCharsets.UTF_8.name())
    val stderr = stderrBuf.toString(StandardCharsets.UTF_8.name())

    val runnerResult = try {
      json.decodeFromString(PythonRunnerResult.serializer(), stdout.trim())
    } catch (e: Exception) {
      val finishedAt = System.currentTimeMillis()
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.FAILED,
        exitCode = proc.exitValue(),
        stdout = stdout,
        stderr = stderr,
        errorCode = "RUNNER_OUTPUT_PARSE_ERROR",
        errorMessage = e.message,
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
        metadata = mapOf(
          "command" to cmd.joinToString(" "),
        ),
      )
    }

    return ExecutionResult(
      taskId = request.taskId,
      status = runnerResult.toExecutionStatus(),
      exitCode = runnerResult.exitCode,
      stdout = runnerResult.stdout,
      stderr = runnerResult.stderr,
      errorCode = runnerResult.errorCode,
      errorMessage = runnerResult.errorMessage,
      startedAtEpochMs = runnerResult.startedAtEpochMs,
      finishedAtEpochMs = runnerResult.finishedAtEpochMs,
      metadata = runnerResult.metadata,
    )
  }

  @Serializable
  private data class PythonRunnerResult(
    val status: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
    @SerialName("finished_at_epoch_ms") val finishedAtEpochMs: Long,
    val metadata: Map<String, String> = emptyMap(),
  ) {
    fun toExecutionStatus(): ExecutionStatus = when (status.lowercase()) {
      "success" -> ExecutionStatus.SUCCESS
      "timeout" -> ExecutionStatus.TIMEOUT
      "cancelled" -> ExecutionStatus.CANCELLED
      "denied" -> ExecutionStatus.DENIED
      else -> ExecutionStatus.FAILED
    }
  }

  companion object {
    internal fun commandFor(request: PythonExecRequest): List<String> = buildList {
      add(request.pythonExecutable)
      add("-m")
      add("python_runner.runner")
      add("exec")
      add("--workspace")
      add(request.workspaceRoot.toString())
      add("--script")
      add(request.scriptPath.toString())
      add("--timeout-seconds")
      add((request.timeoutMs.toDouble() / 1000.0).toString())
      if (request.args.isNotEmpty()) {
        add("--")
        addAll(request.args)
      }
    }
  }
}
