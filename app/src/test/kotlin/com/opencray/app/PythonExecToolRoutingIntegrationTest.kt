package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonExecToolRoutingIntegrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun pythonExecUsesLocalRuntimeOnlyWhenBackendPreferenceIsLocal() {
    val workspaceRoot = temporaryFolder.newFolder("python-tool-local").toPath()
    writeScript(workspaceRoot)
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val sandboxRuntime = RecordingPythonRuntime("sandbox-runtime")
    val sandboxProviderCalls = AtomicInteger(0)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      runtime = RoutingPythonScriptRuntime(
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
        localRuntime = localRuntime,
        sandboxRuntimeProvider = {
          sandboxProviderCalls.incrementAndGet()
          sandboxRuntime
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = pythonExecCall(),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("local-preference", result.content)
    assertEquals(1, localRuntime.requests.size)
    assertTrue(sandboxRuntime.requests.isEmpty())
    assertEquals(0, sandboxProviderCalls.get())
    assertEquals("local_host", result.metadata["executionBackend"])
    assertEquals("local", result.metadata["executionBackendRequested"])
  }

  @Test
  fun pythonExecUsesSandboxRuntimeOnlyWhenBackendPreferenceIsSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("python-tool-sandbox").toPath()
    writeScript(workspaceRoot)
    val localRuntime = RecordingPythonRuntime("local-runtime")
    val sandboxRuntime = RecordingPythonRuntime("sandbox-runtime")
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      runtime = RoutingPythonScriptRuntime(
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
        localRuntime = localRuntime,
        sandboxRuntimeProvider = { sandboxRuntime },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = pythonExecCall(),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("sandbox-preference", result.content)
    assertTrue(localRuntime.requests.isEmpty())
    assertEquals(1, sandboxRuntime.requests.size)
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
    assertEquals("sandbox", result.metadata["executionBackendRequested"])
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path,
    runtime: PythonScriptRuntime,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      pythonRuntimeAdapter = runtime,
    ),
  )

  private fun writeScript(workspaceRoot: java.nio.file.Path) {
    val scriptPath = workspaceRoot.resolve("scripts").resolve("run.py")
    Files.createDirectories(scriptPath.parent)
    Files.write(
      scriptPath,
      "print('hello tool routing')".toByteArray(StandardCharsets.UTF_8),
    )
  }

  private fun pythonExecCall(): AgentToolCall = AgentToolCall(
    toolName = "python_exec",
    arguments = JsonObject(
      mapOf(
        "script_path" to JsonPrimitive("scripts/run.py"),
      ),
    ),
  )

  private fun developerTask(): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = mapOf("chatMode" to "DEVELOPER"),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in PythonExecToolRoutingIntegrationTest.") },
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
}
