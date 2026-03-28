package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessStartToolRoutingIntegrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun processStartUsesLocalFactoryOnlyWhenBackendPreferenceIsLocal() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-local-route").toPath()
    val localFactory = RecordingManagedProcessControllerFactory("local")
    val sandboxFactory = RecordingManagedProcessControllerFactory("sandbox")
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      controllerFactory = RoutingManagedProcessControllerFactory(
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
        pythonRuntimeFactory = RecordingManagedProcessControllerFactory("python"),
        localFactory = localFactory,
        sandboxFactoryProvider = { sandboxFactory },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("npm"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(1, localFactory.requests.size)
    assertTrue(sandboxFactory.requests.isEmpty())
    assertEquals("local", result.metadata["factoryBackend"])
    assertEquals("local_host", result.metadata["executionBackend"])
  }

  @Test
  fun processStartUsesSandboxFactoryOnlyWhenBackendPreferenceIsSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-sandbox-route").toPath()
    val localFactory = RecordingManagedProcessControllerFactory("local")
    val sandboxFactory = RecordingManagedProcessControllerFactory("sandbox")
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      controllerFactory = RoutingManagedProcessControllerFactory(
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
        pythonRuntimeFactory = RecordingManagedProcessControllerFactory("python"),
        localFactory = localFactory,
        sandboxFactoryProvider = { sandboxFactory },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("npm"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(localFactory.requests.isEmpty())
    assertEquals(1, sandboxFactory.requests.size)
    assertEquals("sandbox", result.metadata["factoryBackend"])
    assertEquals("sandbox_remote", result.metadata["executionBackend"])
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path,
    controllerFactory: ManagedProcessControllerFactory,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      processRegistry = InMemoryAgentProcessRegistry(controllerFactory = controllerFactory),
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in ProcessStartToolRoutingIntegrationTest.") },
  )

  private class RecordingManagedProcessControllerFactory(
    private val backend: String,
  ) : ManagedProcessControllerFactory {
    val requests = mutableListOf<ManagedProcessStartRequest>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
      requests += request
      return object : ManagedProcessController {
        override fun snapshot(): ManagedProcessSnapshot = ManagedProcessSnapshot(
          processId = request.processId,
          taskId = request.taskId,
          command = request.command,
          args = request.args,
          workingDirectory = request.workingDirectory,
          status = ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = request.timeoutMs,
          startedAtEpochMs = 100L,
          updatedAtEpochMs = 100L,
          metadata = request.metadata + mapOf("factoryBackend" to backend),
        )

        override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot()

        override fun terminate(): ManagedProcessSnapshot = snapshot()
      }
    }
  }
}
