package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.CommandExecutionConfig
import com.opencray.runtime.CommandProcessRunner
import com.opencray.runtime.CommandSpawnResult
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal const val COMMAND_RESULT_PREFIX: String = "__OPENCRAY_COMMAND_RESULT__="

internal class PythonBackedCommandProcessRunner(
  private val workspaceRoot: Path,
  private val pythonRuntime: PythonScriptRuntime,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  private val capabilities: SandboxCommandBackendCapabilities = SandboxCommandBackendCapabilities(
    backendKind = "python_wrapper",
    providerNative = false,
    supportsStreamingLogs = false,
    supportsReconnect = false,
  ),
) : CommandProcessRunner {
  override fun run(
    commandLine: List<String>,
    workingDirectory: String?,
    config: CommandExecutionConfig,
    hooks: RuntimeExecutionHooks,
  ): CommandSpawnResult {
    if (hooks.isCancellationRequested()) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        processStarted = false,
        cancelled = true,
        metadata = capabilities.metadata(),
      )
    }

    val requestId = "command-${UUID.randomUUID()}"
    val scriptPath = writeWrapperScript(requestId = requestId)
    return try {
      val executionResult = pythonRuntime.exec(
        PythonExecRequest(
          taskId = requestId,
          workspaceRoot = workspaceRoot,
          scriptPath = scriptPath,
          args = listOf(
            encodePayload(
              CommandWrapperPayload(
                command = commandLine,
                workingDirectory = resolveWorkingDirectory(workingDirectory),
                localWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize().toString(),
                timeoutMs = config.timeoutMs,
                outputByteLimit = config.outputByteLimit,
              ),
            ),
          ),
          timeoutMs = config.timeoutMs,
          requestId = requestId,
        ),
      )
      executionResult.toCommandSpawnResult()
    } finally {
      runCatching { Files.deleteIfExists(scriptPath) }
    }
  }

  private fun writeWrapperScript(requestId: String): Path {
    val wrapperDirectory = workspaceRoot.resolve(".opencray").resolve("sandbox-wrappers")
    Files.createDirectories(wrapperDirectory)
    val scriptPath = wrapperDirectory.resolve("$requestId.py")
    Files.write(scriptPath, wrapperScriptSource().toByteArray(StandardCharsets.UTF_8))
    return scriptPath
  }

  private fun resolveWorkingDirectory(workingDirectory: String?): String =
    workingDirectory?.trim()?.takeIf(String::isNotBlank)
      ?: workspaceRoot.toAbsolutePath().normalize().toString()

  private fun encodePayload(payload: CommandWrapperPayload): String =
    Base64.getEncoder().encodeToString(
      json.encodeToString(CommandWrapperPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8),
    )

  private fun ExecutionResult.toCommandSpawnResult(): CommandSpawnResult {
    val payload = decodePayload(stdout)
    return when {
      status == ExecutionStatus.CANCELLED -> CommandSpawnResult(
        exitCode = payload?.exitCode,
        stdout = payload?.stdout.orEmpty(),
        stderr = payload?.stderr.orEmpty(),
        processStarted = payload?.processStarted ?: true,
        cancelled = true,
        metadata = metadata,
      )

      status == ExecutionStatus.TIMEOUT -> CommandSpawnResult(
        exitCode = payload?.exitCode,
        stdout = payload?.stdout.orEmpty(),
        stderr = payload?.stderr.orEmpty(),
        processStarted = payload?.processStarted ?: true,
        timedOut = true,
        metadata = metadata,
      )

      payload != null -> CommandSpawnResult(
        exitCode = payload.exitCode,
        stdout = payload.stdout,
        stderr = payload.stderr,
        spawnErrorMessage = payload.spawnErrorMessage,
        processStarted = payload.processStarted,
        timedOut = payload.timedOut,
        cancelled = payload.cancelled,
        outputLimitExceeded = payload.outputLimitExceeded,
        metadata = metadata + capabilities.metadata(),
      )

      else -> CommandSpawnResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        spawnErrorMessage = errorMessage ?: errorCode,
        processStarted = false,
        metadata = metadata + capabilities.metadata(),
      )
    }
  }

  private fun decodePayload(stdout: String): CommandWrapperResultPayload? =
    stdout.lineSequence()
      .map(String::trim)
      .firstOrNull { line -> line.startsWith(COMMAND_RESULT_PREFIX) }
      ?.removePrefix(COMMAND_RESULT_PREFIX)
      ?.let { encoded ->
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        json.decodeFromString(CommandWrapperResultPayload.serializer(), decoded)
      }

  private fun wrapperScriptSource(): String = buildString {
    appendLine("import base64")
    appendLine("import json")
    appendLine("import os")
    appendLine("import subprocess")
    appendLine("import sys")
    appendLine()
    appendLine("payload = json.loads(base64.b64decode(sys.argv[1]).decode('utf-8'))")
    appendLine("command = payload['command']")
    appendLine("working_directory = payload['workingDirectory']")
    appendLine("local_workspace_root = payload['localWorkspaceRoot']")
    appendLine("timeout_seconds = max(float(payload['timeoutMs']) / 1000.0, 0.001)")
    appendLine("limit = int(payload['outputByteLimit'])")
    appendLine("remote_workspace_root = os.getcwd()")
    appendLine()
    appendLine("def resolve_working_directory(raw_directory, local_root, remote_root):")
    appendLine("    normalized = (raw_directory or '').strip()")
    appendLine("    if not normalized:")
    appendLine("        return remote_root")
    appendLine("    local_root_normalized = os.path.normpath(local_root) if local_root else ''")
    appendLine("    raw_normalized = os.path.normpath(normalized)")
    appendLine("    if local_root_normalized and os.path.isabs(raw_normalized):")
    appendLine("        try:")
    appendLine("            relative = os.path.relpath(raw_normalized, local_root_normalized)")
    appendLine("            if relative not in ('.', '') and not relative.startswith('..') and relative != os.pardir:")
    appendLine("                return os.path.normpath(os.path.join(remote_root, relative))")
    appendLine("            if relative in ('.', ''):")
    appendLine("                return remote_root")
    appendLine("        except ValueError:")
    appendLine("            pass")
    appendLine("    return raw_normalized")
    appendLine()
    appendLine("working_directory = resolve_working_directory(working_directory, local_workspace_root, remote_workspace_root)")
    appendLine()
    appendLine("def clamp(stdout_text, stderr_text, limit_bytes):")
    appendLine("    stdout_bytes = stdout_text.encode('utf-8', errors='replace')")
    appendLine("    stderr_bytes = stderr_text.encode('utf-8', errors='replace')")
    appendLine("    if len(stdout_bytes) + len(stderr_bytes) <= limit_bytes:")
    appendLine("        return stdout_text, stderr_text, False")
    appendLine("    stdout_budget = limit_bytes // 2")
    appendLine("    stderr_budget = max(limit_bytes - stdout_budget, 0)")
    appendLine("    return (")
    appendLine("        stdout_bytes[:stdout_budget].decode('utf-8', errors='replace'),")
    appendLine("        stderr_bytes[:stderr_budget].decode('utf-8', errors='replace'),")
    appendLine("        True,")
    appendLine("    )")
    appendLine()
    appendLine("result = None")
    appendLine("try:")
    appendLine("    completed = subprocess.run(")
    appendLine("        command,")
    appendLine("        cwd=working_directory,")
    appendLine("        capture_output=True,")
    appendLine("        text=True,")
    appendLine("        encoding='utf-8',")
    appendLine("        errors='replace',")
    appendLine("        timeout=timeout_seconds,")
    appendLine("    )")
    appendLine("    stdout_text, stderr_text, truncated = clamp(completed.stdout or '', completed.stderr or '', limit)")
    appendLine("    result = {")
    appendLine("        'exitCode': completed.returncode,")
    appendLine("        'stdout': stdout_text,")
    appendLine("        'stderr': stderr_text,")
    appendLine("        'processStarted': True,")
    appendLine("        'timedOut': False,")
    appendLine("        'cancelled': False,")
    appendLine("        'outputLimitExceeded': truncated,")
    appendLine("        'spawnErrorMessage': None,")
    appendLine("    }")
    appendLine("except subprocess.TimeoutExpired as exc:")
    appendLine("    stdout_text, stderr_text, truncated = clamp(exc.stdout or '', exc.stderr or '', limit)")
    appendLine("    result = {")
    appendLine("        'exitCode': None,")
    appendLine("        'stdout': stdout_text,")
    appendLine("        'stderr': stderr_text,")
    appendLine("        'processStarted': True,")
    appendLine("        'timedOut': True,")
    appendLine("        'cancelled': False,")
    appendLine("        'outputLimitExceeded': truncated,")
    appendLine("        'spawnErrorMessage': None,")
    appendLine("    }")
    appendLine("except Exception as exc:")
    appendLine("    result = {")
    appendLine("        'exitCode': None,")
    appendLine("        'stdout': '',")
    appendLine("        'stderr': '',")
    appendLine("        'processStarted': False,")
    appendLine("        'timedOut': False,")
    appendLine("        'cancelled': False,")
    appendLine("        'outputLimitExceeded': False,")
    appendLine("        'spawnErrorMessage': str(exc),")
    appendLine("    }")
    appendLine()
    appendLine("encoded = base64.b64encode(json.dumps(result).encode('utf-8')).decode('ascii')")
    appendLine("print(")
    appendLine("    ${json.encodeToString(String.serializer(), COMMAND_RESULT_PREFIX)} + encoded")
    appendLine(")")
  }
}

internal class SandboxPythonManagedCommandControllerFactory(
  private val workspaceRoot: Path,
  private val pythonRuntime: PythonScriptRuntime,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  private val capabilities: SandboxCommandBackendCapabilities = SandboxCommandBackendCapabilities(
    backendKind = "python_wrapper",
    providerNative = false,
    supportsStreamingLogs = false,
    supportsReconnect = false,
  ),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ManagedProcessControllerFactory {
  override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
    SandboxPythonManagedCommandController(
      request = request,
      workspaceRoot = workspaceRoot,
      pythonRuntime = pythonRuntime,
      json = json,
      capabilities = capabilities,
      clock = clock,
    )
}

private class SandboxPythonManagedCommandController(
  private val request: ManagedProcessStartRequest,
  private val workspaceRoot: Path,
  private val pythonRuntime: PythonScriptRuntime,
  private val json: Json,
  private val capabilities: SandboxCommandBackendCapabilities,
  private val clock: () -> Long,
) : ManagedProcessController {
  private val lock = Any()
  private val completion = CountDownLatch(1)
  private val cancellationRuntime = pythonRuntime as? CancellablePythonScriptRuntime
  private val helperRunner = PythonBackedCommandProcessRunner(
    workspaceRoot = workspaceRoot,
    pythonRuntime = pythonRuntime,
    json = json,
    capabilities = capabilities,
  )

  private var status: ManagedProcessStatus = ManagedProcessStatus.RUNNING
  private var processStarted: Boolean = true
  private var stdout: String = ""
  private var stderr: String = ""
  private var exitCode: Int? = null
  private var errorCode: String? = null
  private var errorMessage: String? = null
  private var startedAtEpochMs: Long = clock()
  private var updatedAtEpochMs: Long = startedAtEpochMs
  private var finishedAtEpochMs: Long? = null
  private var timedOut: Boolean = false
  private var cancelled: Boolean = false
  private var outputLimitExceeded: Boolean = false
  private var runtimeMetadata: Map<String, String> = request.metadata +
    capabilities.metadata() +
    mapOf(
      "runtimeKind" to "command_exec",
      "terminationSupport" to if (cancellationRuntime != null) "cooperative" else "unsupported",
    )
  private var terminationRequested: Boolean = false
  private var terminationRequestAccepted: Boolean? = null

  init {
    Thread(
      { executeRemoteCommand() },
      "managed-sandbox-command-${request.processId}",
    ).apply {
      isDaemon = true
      start()
    }
  }

  override fun snapshot(): ManagedProcessSnapshot = synchronized(lock) { snapshotLocked() }

  override fun await(timeoutMs: Long): ManagedProcessSnapshot {
    completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    return snapshot()
  }

  override fun terminate(): ManagedProcessSnapshot = synchronized(lock) {
    if (!status.isTerminal) {
      terminationRequested = true
      terminationRequestAccepted = cancellationRuntime?.requestCancellation(request.processId)
      updatedAtEpochMs = maxOf(updatedAtEpochMs, clock())
    }
    snapshotLocked()
  }

  private fun executeRemoteCommand() {
    val spawnResult = helperRunner.run(
      commandLine = buildList {
        add(request.command)
        addAll(request.args)
      },
      workingDirectory = request.workingDirectory,
      config = CommandExecutionConfig(
        timeoutMs = request.timeoutMs,
      ),
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { terminationRequested },
        requestRetry = { error("Retry not expected for managed sandbox command.") },
      ),
    )
    val finishedAt = clock()
    synchronized(lock) {
      stdout = spawnResult.stdout
      stderr = spawnResult.stderr
      exitCode = spawnResult.exitCode
      outputLimitExceeded = spawnResult.outputLimitExceeded
      runtimeMetadata = runtimeMetadata + spawnResult.metadata
      if (terminationRequested) {
        runtimeMetadata = runtimeMetadata + mapOf("terminationRequested" to "true")
      }
      terminationRequestAccepted?.let { accepted ->
        runtimeMetadata = runtimeMetadata + mapOf("terminationRequestAccepted" to accepted.toString())
      }
      when {
        spawnResult.cancelled -> {
          status = ManagedProcessStatus.CANCELLED
          cancelled = true
          errorCode = "CANCELLED"
          errorMessage = "Managed sandbox command terminated."
        }

        spawnResult.timedOut -> {
          status = ManagedProcessStatus.TIMEOUT
          timedOut = true
          errorCode = "TIMEOUT"
          errorMessage = "Managed sandbox command exceeded timeout."
        }

        spawnResult.spawnErrorMessage != null -> {
          status = if (spawnResult.processStarted) ManagedProcessStatus.FAILED else ManagedProcessStatus.SPAWN_ERROR
          errorCode = if (spawnResult.processStarted) "EXEC_ERROR" else "SPAWN_ERROR"
          errorMessage = spawnResult.spawnErrorMessage
        }

        spawnResult.exitCode == 0 -> {
          status = ManagedProcessStatus.SUCCESS
        }

        else -> {
          status = ManagedProcessStatus.FAILED
          errorCode = "EXEC_ERROR"
          errorMessage = "Process exited with code ${spawnResult.exitCode ?: -1}."
        }
      }
      updatedAtEpochMs = finishedAt
      finishedAtEpochMs = finishedAt
    }
    completion.countDown()
  }

  private fun snapshotLocked(): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = request.processId,
    taskId = request.taskId,
    command = request.command,
    args = request.args,
    workingDirectory = request.workingDirectory,
    status = status,
    processStarted = processStarted,
    timeoutMs = request.timeoutMs,
    stdout = stdout,
    stderr = stderr,
    exitCode = exitCode,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = startedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    timedOut = timedOut,
    cancelled = cancelled,
    outputLimitExceeded = outputLimitExceeded,
    ownerIdentity = request.ownerIdentity,
    metadata = buildMap {
      putAll(runtimeMetadata)
      if (terminationRequested) {
        put("terminationRequested", "true")
      }
      terminationRequestAccepted?.let { accepted ->
        put("terminationRequestAccepted", accepted.toString())
      }
    },
  )
}

@Serializable
internal data class CommandWrapperPayload(
  val command: List<String>,
  val workingDirectory: String,
  val localWorkspaceRoot: String,
  val timeoutMs: Long,
  val outputByteLimit: Int,
)

@Serializable
internal data class CommandWrapperResultPayload(
  val exitCode: Int? = null,
  val stdout: String = "",
  val stderr: String = "",
  val processStarted: Boolean = false,
  val timedOut: Boolean = false,
  val cancelled: Boolean = false,
  val outputLimitExceeded: Boolean = false,
  val spawnErrorMessage: String? = null,
)
