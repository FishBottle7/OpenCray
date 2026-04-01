package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxSessionControlToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsIncludeSandboxSessionCloseToolWhenServiceIsConfigured() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-close-definitions").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionControlService = SandboxSessionControlService {
          SandboxSessionCloseResult(
            providerId = "e2b",
            outcome = SandboxSessionCloseOutcome.NOT_FOUND,
          )
        },
      ),
    )

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertTrue(toolNames.contains("sandbox_session_close"))
  }

  @Test
  fun hiddenSandboxPrefixHidesSandboxSessionCloseToolDefinition() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-close-hidden").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionControlService = SandboxSessionControlService {
          SandboxSessionCloseResult(
            providerId = "e2b",
            outcome = SandboxSessionCloseOutcome.NOT_FOUND,
          )
        },
        hiddenToolNamePrefixes = setOf("sandbox_"),
      ),
    )

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertFalse(toolNames.contains("sandbox_session_close"))
  }

  @Test
  fun sandboxSessionCloseReturnsSuccessWhenSessionIsTerminated() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-close-dispatch").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionControlService = SandboxSessionControlService {
          SandboxSessionCloseResult(
            providerId = "e2b",
            outcome = SandboxSessionCloseOutcome.TERMINATED,
            sandboxId = "sb-close",
            sandboxDomain = "e2b.app",
            previewCandidatePorts = listOf(3000, 4173),
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_session_close",
        arguments = JsonObject(emptyMap()),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("Closed the reusable cloud sandbox session"))
    assertTrue(result.content.contains("preview_candidate_ports=3000,4173"))
    assertEquals("terminated", result.metadata["sandboxSessionCloseOutcome"])
    assertEquals("e2b", result.metadata["sandboxProvider"])
    assertEquals("3000,4173", result.metadata["sandboxPreviewCandidatePorts"])
  }

  @Test
  fun sandboxSessionCloseFailsWhenSessionIsBusy() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-close-busy").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionControlService = SandboxSessionControlService {
          SandboxSessionCloseResult(
            providerId = "e2b",
            outcome = SandboxSessionCloseOutcome.BUSY,
            sandboxId = "sb-close-busy",
            blockingRequestId = "req-1",
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_session_close",
        arguments = JsonObject(emptyMap()),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SANDBOX_SESSION_BUSY", result.errorCode)
    assertEquals("busy", result.metadata["sandboxSessionCloseOutcome"])
    assertEquals("req-1", result.metadata["blockingRequestId"])
    assertTrue(result.content.contains("blocking_request_id=req-1"))
  }

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
    requestRetry = { _: RetryRequest -> error("Retry not expected in SandboxSessionControlToolTest.") },
  )
}
