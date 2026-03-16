package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonScriptRuntimeInjectionTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun pythonExecDispatchesThroughInjectedRuntimeBackend() {
    val workspaceRoot = temporaryFolder.newFolder("python-runtime-injection").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello from injection test')".toByteArray(StandardCharsets.UTF_8),
    )
    val runtime = RecordingPythonScriptRuntime()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        pythonRuntimeAdapter = runtime,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "python_exec",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    val expectedWorkspaceRoot = workspaceRoot.toRealPath()
    val expectedScriptPath = workspaceRoot.resolve("scripts").resolve("run.py").toRealPath()
    val request = requireNotNull(runtime.lastRequest)
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(expectedWorkspaceRoot, request.workspaceRoot)
    assertEquals(expectedScriptPath, request.scriptPath)
    assertEquals(listOf("--flag"), request.args)
    assertEquals("runtime-ok", result.content)
    assertEquals("scripts/run.py", result.metadata["scriptPath"]?.replace('\\', '/'))
    assertEquals("ALLOW_DEVELOPER_OVERRIDE", result.metadata["policyReasonCode"])
    assertEquals("recording-runtime", result.metadata["runtimeBackend"])
  }

  private fun agentTask(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in PythonScriptRuntimeInjectionTest.") },
  )

  private class RecordingPythonScriptRuntime : PythonScriptRuntime {
    var lastRequest: PythonExecRequest? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      lastRequest = request
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = "runtime-ok",
        stderr = "",
        startedAtEpochMs = 1_200L,
        finishedAtEpochMs = 1_250L,
        metadata = mapOf("runtimeBackend" to "recording-runtime"),
      )
    }
  }
}
