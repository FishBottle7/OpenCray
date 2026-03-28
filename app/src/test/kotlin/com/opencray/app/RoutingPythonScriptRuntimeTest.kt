package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPythonScriptRuntimeTest {
  @Test
  fun localPreferenceUsesLocalRuntimeWithoutResolvingSandboxRuntime() {
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val sandboxProviderCalls = AtomicInteger(0)
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "local",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = localRuntime,
      sandboxRuntimeProvider = {
        sandboxProviderCalls.incrementAndGet()
        RecordingPythonRuntime("sandbox-runtime")
      },
    )

    val result = runtime.exec(request())

    assertEquals(1, localRuntime.requests.size)
    assertEquals(0, sandboxProviderCalls.get())
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("local", result.metadata["executionBackendRequested"])
    assertEquals("local_host", result.metadata["executionBackend"])
    assertEquals("local_preference", result.metadata["sandboxRouteReasonCode"])
  }

  @Test
  fun autoFallsBackToLocalWhenSandboxRuntimeUnavailable() {
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "auto",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = localRuntime,
      sandboxRuntimeProvider = { null },
    )

    val result = runtime.exec(request())

    assertEquals(1, localRuntime.requests.size)
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("auto", result.metadata["executionBackendRequested"])
    assertEquals("local_host", result.metadata["executionBackend"])
    assertEquals("auto_fallback_runtime_unavailable", result.metadata["sandboxRouteReasonCode"])
  }

  @Test
  fun sandboxPreferenceFailsWhenSandboxRuntimeUnavailable() {
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "sandbox",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = localRuntime,
      sandboxRuntimeProvider = { null },
    )

    val result = runtime.exec(request())

    assertTrue(localRuntime.requests.isEmpty())
    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(
      RoutingPythonScriptRuntime.ERROR_SANDBOX_RUNTIME_UNAVAILABLE,
      result.errorCode,
    )
    assertEquals("sandbox", result.metadata["executionBackendRequested"])
    assertEquals("unavailable", result.metadata["executionBackend"])
  }

  @Test
  fun sandboxPreferenceDispatchesToSandboxRuntimeWhenAvailable() {
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val sandboxRuntime = RecordingPythonRuntime("sandbox-runtime")
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "sandbox",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = localRuntime,
      sandboxRuntimeProvider = { sandboxRuntime },
    )

    val result = runtime.exec(request())

    assertTrue(localRuntime.requests.isEmpty())
    assertEquals(1, sandboxRuntime.requests.size)
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
    assertEquals("sandbox-preference", result.stdout)
  }

  @Test
  fun autoPrefersSandboxRuntimeWhenAvailableWithoutTouchingLocalRuntime() {
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val sandboxRuntime = RecordingPythonRuntime("sandbox-runtime")
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "auto",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = localRuntime,
      sandboxRuntimeProvider = { sandboxRuntime },
    )

    val result = runtime.exec(request())

    assertTrue(localRuntime.requests.isEmpty())
    assertEquals(1, sandboxRuntime.requests.size)
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
    assertEquals("auto_sandbox_selected", result.metadata["sandboxRouteReasonCode"])
  }

  @Test
  fun requestCancellationDelegatesToActiveRuntimeForRequestId() {
    val blockingRuntime = BlockingCancellablePythonRuntime()
    val runtime = RoutingPythonScriptRuntime(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            defaultBackend = "sandbox",
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      localRuntime = RecordingPythonRuntime("local-runtime"),
      sandboxRuntimeProvider = { blockingRuntime },
    )

    val executionThread = Thread {
      runtime.exec(request(requestId = "req-1"))
    }
    executionThread.start()
    assertTrue(blockingRuntime.execStarted.await(2, TimeUnit.SECONDS))

    assertTrue(runtime.requestCancellation("req-1"))

    blockingRuntime.finish.countDown()
    executionThread.join(2_000L)
    assertEquals("req-1", blockingRuntime.cancelledRequestId)
  }

  private fun request(requestId: String? = "req-0"): PythonExecRequest = PythonExecRequest(
    taskId = "task-1",
    workspaceRoot = Paths.get("workspace"),
    scriptPath = Paths.get("workspace", "scripts", "run.py"),
    args = listOf("--flag"),
    timeoutMs = 30_000L,
    requestId = requestId,
  )

  private class RecordingPythonRuntime(
    private val runtimeBackend: String,
  ) : PythonScriptRuntime {
    val requests = mutableListOf<PythonExecRequest>()

    override fun exec(request: PythonExecRequest): ExecutionResult {
      requests += request
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = if (runtimeBackend == "sandbox-runtime") "sandbox-preference" else "local-preference",
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to runtimeBackend),
      )
    }
  }

  private class BlockingCancellablePythonRuntime : PythonScriptRuntime, CancellablePythonScriptRuntime {
    val execStarted = CountDownLatch(1)
    val finish = CountDownLatch(1)
    @Volatile
    var cancelledRequestId: String? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      execStarted.countDown()
      finish.await(2, TimeUnit.SECONDS)
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.CANCELLED,
        exitCode = null,
        stdout = "",
        stderr = "",
        errorCode = "CANCELLED",
        errorMessage = "cancelled",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
      )
    }

    override fun requestCancellation(requestId: String): Boolean {
      cancelledRequestId = requestId
      return true
    }
  }
}
