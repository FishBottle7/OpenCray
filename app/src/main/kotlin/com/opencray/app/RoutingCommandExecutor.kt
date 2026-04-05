package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandApprovalToken
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor

internal class RoutingCommandExecutor(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val localExecutor: CommandExecutor,
  private val sandboxExecutorProvider: (ResolvedSandboxSettings) -> CommandExecutor? = { null },
  private val clock: () -> Long = { System.currentTimeMillis() },
) : CommandExecutor() {
  override fun execute(
    request: CommandExecutionRequest,
    policyDecision: PolicyDecision,
    approvalToken: CommandApprovalToken?,
    hooks: RuntimeExecutionHooks,
  ): ExecutionResult {
    val settings = settingsProvider()
    val sandboxExecutor = if (SandboxExecutionRouting.shouldResolveSandboxBackend(settings)) {
      sandboxExecutorProvider(settings)
    } else {
      null
    }
    val selection = SandboxExecutionRouting.resolveSelection(
      settings = settings,
      sandboxRuntimeAvailable = sandboxExecutor != null,
    )
    val traceMetadata = SandboxExecutionTraceMetadata.routeMetadata(
      metadata = request.metadata,
      routeKind = "command_exec",
      executionBackend = selection.resolvedBackend.wireValue,
    )
    val routedRequest = request.copy(
      metadata = request.metadata + traceMetadata + selection.metadata(),
    )
    return when (selection.resolvedBackend) {
      ResolvedExecutionBackend.LOCAL_HOST -> localExecutor.execute(
        request = routedRequest,
        policyDecision = policyDecision,
        approvalToken = approvalToken,
        hooks = hooks,
      )

      ResolvedExecutionBackend.SANDBOX_REMOTE -> requireNotNull(sandboxExecutor).execute(
        request = routedRequest,
        policyDecision = policyDecision,
        approvalToken = approvalToken,
        hooks = hooks,
      )

      ResolvedExecutionBackend.UNAVAILABLE -> unavailableResult(
        request = routedRequest,
        policyDecision = policyDecision,
        selection = selection,
      )
    }
  }

  private fun unavailableResult(
    request: CommandExecutionRequest,
    policyDecision: PolicyDecision,
    selection: SandboxExecutionRouteSelection,
  ): ExecutionResult {
    val finishedAt = clock()
    return ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.FAILED,
      exitCode = null,
      stdout = "",
      stderr = "",
      errorCode = selection.errorCode ?: RoutingPythonScriptRuntime.ERROR_SANDBOX_BACKEND_UNAVAILABLE,
      errorMessage = selection.detail,
      policyDecision = policyDecision,
      startedAtEpochMs = finishedAt,
      finishedAtEpochMs = finishedAt,
      metadata = request.metadata,
    )
  }
}
