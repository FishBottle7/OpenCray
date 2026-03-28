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
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandExecToolRoutingIntegrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun commandExecUsesLocalExecutorOnlyWhenBackendPreferenceIsLocal() {
    val workspaceRoot = temporaryFolder.newFolder("command-tool-local").toPath()
    val localExecutor = RecordingCommandExecutor("local")
    val sandboxExecutor = RecordingCommandExecutor("sandbox")
    val sandboxExecutorCalls = AtomicInteger(0)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      executor = RoutingCommandExecutor(
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
          sandboxExecutor
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "command_exec",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("git"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("local", result.content)
    assertEquals(1, localExecutor.requests.size)
    assertTrue(sandboxExecutor.requests.isEmpty())
    assertEquals(0, sandboxExecutorCalls.get())
    assertEquals("local_host", result.metadata["executionBackend"])
  }

  @Test
  fun commandExecUsesSandboxExecutorOnlyWhenBackendPreferenceIsSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("command-tool-sandbox").toPath()
    val localExecutor = RecordingCommandExecutor("local")
    val sandboxExecutor = RecordingCommandExecutor("sandbox")
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      executor = RoutingCommandExecutor(
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
        sandboxExecutorProvider = { sandboxExecutor },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "command_exec",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("git"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("sandbox", result.content)
    assertTrue(localExecutor.requests.isEmpty())
    assertEquals(1, sandboxExecutor.requests.size)
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path,
    executor: CommandExecutor,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      commandExecutor = executor,
    ),
  )

  private fun developerTask(): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_DEVELOPER_OVERRIDE",
    ),
    metadata = mapOf("chatMode" to "DEVELOPER"),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in CommandExecToolRoutingIntegrationTest.") },
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
