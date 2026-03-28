package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingCommandExecutorTest {
  @Test
  fun localPreferenceUsesLocalExecutorWithoutResolvingSandboxExecutor() {
    val localExecutor = RecordingCommandExecutor("local")
    val sandboxExecutorCalls = AtomicInteger(0)
    val executor = RoutingCommandExecutor(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.LOCAL.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localExecutor = localExecutor,
      sandboxExecutorProvider = {
        sandboxExecutorCalls.incrementAndGet()
        RecordingCommandExecutor("sandbox")
      },
    )

    val result = executor.execute(
      request = request(),
      policyDecision = allowPolicy(),
      hooks = hooks(),
    )

    assertEquals(1, localExecutor.requests.size)
    assertEquals(0, sandboxExecutorCalls.get())
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("local_host", result.metadata["executionBackend"])
    assertEquals("local", result.metadata["executionBackendRequested"])
  }

  @Test
  fun autoPrefersSandboxExecutorWhenAvailableWithoutTouchingLocalExecutor() {
    val localExecutor = RecordingCommandExecutor("local")
    val sandboxExecutor = RecordingCommandExecutor("sandbox")
    val executor = RoutingCommandExecutor(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.AUTO.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localExecutor = localExecutor,
      sandboxExecutorProvider = { sandboxExecutor },
    )

    val result = executor.execute(
      request = request(),
      policyDecision = allowPolicy(),
      hooks = hooks(),
    )

    assertTrue(localExecutor.requests.isEmpty())
    assertEquals(1, sandboxExecutor.requests.size)
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
    assertEquals("auto_sandbox_selected", result.metadata["sandboxRouteReasonCode"])
  }

  @Test
  fun sandboxPreferenceFailsWhenSandboxExecutorUnavailable() {
    val localExecutor = RecordingCommandExecutor("local")
    val executor = RoutingCommandExecutor(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localExecutor = localExecutor,
      sandboxExecutorProvider = { null },
    )

    val result = executor.execute(
      request = request(),
      policyDecision = allowPolicy(),
      hooks = hooks(),
    )

    assertTrue(localExecutor.requests.isEmpty())
    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(RoutingPythonScriptRuntime.ERROR_SANDBOX_RUNTIME_UNAVAILABLE, result.errorCode)
    assertEquals("unavailable", result.metadata["executionBackend"])
  }

  private fun request(): CommandExecutionRequest = CommandExecutionRequest(
    taskId = "task-command",
    command = "git",
    args = listOf("status"),
    workingDirectory = ".",
    requestedAtEpochMs = 1_000L,
  )

  private fun allowPolicy(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "ALLOW_DEVELOPER_OVERRIDE",
  )

  private fun hooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in RoutingCommandExecutorTest.") },
  )

  private class RecordingCommandExecutor(
    private val backend: String,
  ) : CommandExecutor() {
    val requests = mutableListOf<CommandExecutionRequest>()

    override fun execute(
      request: CommandExecutionRequest,
      policyDecision: PolicyDecision,
      approvalToken: com.opencray.runtime.CommandApprovalToken?,
      hooks: RuntimeExecutionHooks,
    ): ExecutionResult {
      requests += request
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = backend,
        stderr = "",
        policyDecision = policyDecision,
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = request.metadata + mapOf("runtimeBackend" to backend),
      )
    }
  }
}
