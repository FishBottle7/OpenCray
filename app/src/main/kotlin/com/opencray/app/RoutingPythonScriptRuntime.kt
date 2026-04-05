package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal class RoutingPythonScriptRuntime(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val localRuntime: PythonScriptRuntime,
  private val sandboxRuntimeProvider: (ResolvedSandboxSettings) -> PythonScriptRuntime? = { null },
  private val clock: () -> Long = { System.currentTimeMillis() },
) : PythonScriptRuntime, CancellablePythonScriptRuntime {
  private val activeCancellableRuntimesByRequestId: ConcurrentMap<String, CancellablePythonScriptRuntime> =
    ConcurrentHashMap()

  override fun exec(request: PythonExecRequest): ExecutionResult {
    val settings = settingsProvider()
    val sandboxRuntime = if (SandboxExecutionRouting.shouldResolveSandboxBackend(settings)) {
      sandboxRuntimeProvider(settings)
    } else {
      null
    }
    val selection = SandboxExecutionRouting.resolveSelection(
      settings = settings,
      sandboxRuntimeAvailable = sandboxRuntime != null,
    )
    val traceMetadata = SandboxExecutionTraceMetadata.routeMetadata(
      metadata = emptyMap(),
      routeKind = "python_exec",
      executionBackend = selection.resolvedBackend.wireValue,
    )
    return when (selection.resolvedBackend) {
      ResolvedExecutionBackend.LOCAL_HOST -> executeWithDelegate(
        delegate = localRuntime,
        request = request,
        selection = selection,
        traceMetadata = traceMetadata,
      )

      ResolvedExecutionBackend.SANDBOX_REMOTE -> executeWithDelegate(
        delegate = requireNotNull(sandboxRuntime),
        request = request,
        selection = selection,
        traceMetadata = traceMetadata,
      )

      ResolvedExecutionBackend.UNAVAILABLE -> unavailableResult(
        request = request,
        selection = selection,
        traceMetadata = traceMetadata,
      )
    }
  }

  override fun requestCancellation(requestId: String): Boolean {
    val normalizedRequestId = requestId.trim()
    if (normalizedRequestId.isBlank()) {
      return false
    }
    return activeCancellableRuntimesByRequestId[normalizedRequestId]
      ?.requestCancellation(normalizedRequestId)
      ?: false
  }

  private fun executeWithDelegate(
    delegate: PythonScriptRuntime,
    request: PythonExecRequest,
    selection: SandboxExecutionRouteSelection,
    traceMetadata: Map<String, String>,
  ): ExecutionResult {
    val cancellable = delegate as? CancellablePythonScriptRuntime
    val requestId = request.requestId?.trim()?.takeIf(String::isNotBlank)
    if (requestId != null && cancellable != null) {
      activeCancellableRuntimesByRequestId[requestId] = cancellable
    }
    return try {
      val result = delegate.exec(request)
      result.copy(metadata = result.metadata + traceMetadata + selection.metadata())
    } finally {
      if (requestId != null) {
        activeCancellableRuntimesByRequestId.remove(requestId)
      }
    }
  }

  private fun unavailableResult(
    request: PythonExecRequest,
    selection: SandboxExecutionRouteSelection,
    traceMetadata: Map<String, String>,
  ): ExecutionResult {
    val finishedAt = clock()
    return ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.FAILED,
      exitCode = null,
      stdout = "",
      stderr = "",
      errorCode = selection.errorCode ?: ERROR_SANDBOX_BACKEND_UNAVAILABLE,
      errorMessage = selection.detail,
      startedAtEpochMs = finishedAt,
      finishedAtEpochMs = finishedAt,
      metadata = traceMetadata + selection.metadata(),
    )
  }

  companion object {
    const val ERROR_SANDBOX_BACKEND_UNAVAILABLE: String = SandboxExecutionRouting.ERROR_SANDBOX_BACKEND_UNAVAILABLE
    const val ERROR_SANDBOX_PROVIDER_DISABLED: String = SandboxExecutionRouting.ERROR_SANDBOX_PROVIDER_DISABLED
    const val ERROR_SANDBOX_CREDENTIALS_MISSING: String = SandboxExecutionRouting.ERROR_SANDBOX_CREDENTIALS_MISSING
    const val ERROR_SANDBOX_PROVIDER_UNSUPPORTED: String = SandboxExecutionRouting.ERROR_SANDBOX_PROVIDER_UNSUPPORTED
    const val ERROR_SANDBOX_RUNTIME_UNAVAILABLE: String = SandboxExecutionRouting.ERROR_SANDBOX_RUNTIME_UNAVAILABLE
  }
}
