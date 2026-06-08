package com.opencray.runtime.process

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RoutedManagedProcessControllerFactory(
  private val workspaceRoot: Path,
  private val pythonRuntime: PythonScriptRuntime,
  private val defaultFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ReconnectableManagedProcessControllerFactory {
  private val reconnectableDefaultFactory =
    defaultFactory as? ReconnectableManagedProcessControllerFactory

  override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
    val managedByPythonRuntime = request.isManagedByPythonRuntime()
    val runtimeKind = request.metadata["runtimeKind"]?.trim()
    val scriptPath = request.metadata["scriptPath"]?.trim()?.takeIf(String::isNotBlank)
    if (!managedByPythonRuntime || runtimeKind != "python_exec" || scriptPath == null) {
      return defaultFactory.start(request)
    }
    return PythonRuntimeManagedProcessController(
      request = request,
      workspaceRoot = workspaceRoot,
      scriptPath = scriptPath,
      pythonRuntime = pythonRuntime,
      clock = clock,
    )
  }

  override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
    if (snapshot.isManagedByPythonRuntime()) {
      return null
    }
    return reconnectableDefaultFactory?.reconnect(snapshot)
  }

  private fun ManagedProcessStartRequest.isManagedByPythonRuntime(): Boolean =
    metadata["managedByPythonRuntime"]?.equals("true", ignoreCase = true) ?: false

  private fun ManagedProcessSnapshot.isManagedByPythonRuntime(): Boolean =
    metadata["managedByPythonRuntime"]?.equals("true", ignoreCase = true) ?: false
}

private class PythonRuntimeManagedProcessController(
  private val request: ManagedProcessStartRequest,
  workspaceRoot: Path,
  scriptPath: String,
  private val pythonRuntime: PythonScriptRuntime,
  private val clock: () -> Long,
) : ManagedProcessController {
  private val lock = Any()
  private val completion = CountDownLatch(1)
  private val terminationRequested = AtomicBoolean(false)
  private val cancellationRuntime = pythonRuntime as? CancellablePythonScriptRuntime
  private val workspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()
  private val scriptPath: Path = resolveScriptPath(
    workspaceRoot = workspaceRoot,
    scriptPath = scriptPath,
  )
  private val terminationSupport: String = if (cancellationRuntime != null) "cooperative" else "unsupported"

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
  private var terminationRequestAccepted: Boolean? = null
  private var resultMetadata: Map<String, String> = emptyMap()

  init {
    Thread(
      { executeRuntimeRequest() },
      "managed-python-runtime-${request.processId}",
    ).apply {
      isDaemon = true
      start()
    }
  }

  override fun snapshot(): ManagedProcessSnapshot = synchronized(lock) {
    snapshotLocked()
  }

  override fun await(timeoutMs: Long): ManagedProcessSnapshot {
    completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    return snapshot()
  }

  override fun terminate(): ManagedProcessSnapshot = synchronized(lock) {
    if (!status.isTerminal) {
      terminationRequested.set(true)
      if (cancellationRuntime != null) {
        terminationRequestAccepted = cancellationRuntime.requestCancellation(request.processId)
      }
      updatedAtEpochMs = maxOf(updatedAtEpochMs, clock())
    }
    snapshotLocked()
  }

  private fun executeRuntimeRequest() {
    val executionResult = runCatching {
      pythonRuntime.exec(
        PythonExecRequest(
          taskId = request.taskId,
          workspaceRoot = workspaceRoot,
          scriptPath = scriptPath,
          args = request.args,
          timeoutMs = request.timeoutMs,
          pythonExecutable = request.metadata["pythonExecutable"] ?: "python",
          requestId = request.processId,
        ),
      )
    }.getOrElse { error ->
      val failedAt = clock()
      synchronized(lock) {
        status = ManagedProcessStatus.FAILED
        exitCode = null
        errorCode = ERROR_RUNTIME_EXECUTION_FAILED
        errorMessage = error.message ?: "Python runtime managed process failed."
        updatedAtEpochMs = failedAt
        finishedAtEpochMs = failedAt
      }
      completion.countDown()
      return
    }

    val finishedAt = maxOf(clock(), executionResult.finishedAtEpochMs)
    synchronized(lock) {
      status = executionResult.toManagedProcessStatus()
      stdout = executionResult.stdout
      stderr = executionResult.stderr
      exitCode = executionResult.exitCode
      errorCode = executionResult.errorCode
      errorMessage = executionResult.errorMessage
      updatedAtEpochMs = finishedAt
      finishedAtEpochMs = finishedAt
      timedOut = executionResult.status == ExecutionStatus.TIMEOUT
      cancelled = executionResult.status == ExecutionStatus.CANCELLED
      resultMetadata = executionResult.metadata
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
    ownerIdentity = request.ownerIdentity,
    metadata = buildMetadata(),
  )

  private fun buildMetadata(): Map<String, String> = buildMap {
    putAll(request.metadata)
    put("terminationSupport", terminationSupport)
    if (terminationRequested.get()) {
      put("terminationRequested", "true")
    }
    terminationRequestAccepted?.let { accepted ->
      put("terminationRequestAccepted", accepted.toString())
    }
    putAll(resultMetadata)
  }

  private fun resolveScriptPath(
    workspaceRoot: Path,
    scriptPath: String,
  ): Path {
    val parsed = Paths.get(scriptPath)
    val resolved = if (parsed.isAbsolute) parsed else workspaceRoot.resolve(parsed)
    val normalized = resolved.normalize()
    return runCatching { normalized.toRealPath() }.getOrDefault(normalized)
  }

  private fun ExecutionResult.toManagedProcessStatus(): ManagedProcessStatus = when (this.status) {
    ExecutionStatus.SUCCESS -> ManagedProcessStatus.SUCCESS
    ExecutionStatus.TIMEOUT -> ManagedProcessStatus.TIMEOUT
    ExecutionStatus.CANCELLED -> ManagedProcessStatus.CANCELLED
    else -> ManagedProcessStatus.FAILED
  }

  private companion object {
    const val ERROR_RUNTIME_EXECUTION_FAILED: String = "PYTHON_RUNTIME_EXECUTION_FAILED"
  }
}
