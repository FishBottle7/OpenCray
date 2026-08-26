package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentBashTimeoutCapTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun bashToolCapsHugeWaitAndProcessTimeoutsBeforeRegistryStart() {
    val registry = CapturingProcessRegistry()
    val dispatcher = dispatcherFor(registry)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("Get-Date"),
            "wait_timeout_ms" to JsonPrimitive(999999999L),
            "process_timeout_ms" to JsonPrimitive(999999999L),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(1, registry.startRequests.size)
    assertEquals(1_800_000L, registry.startRequests.single().timeoutMs)
    assertEquals(1, registry.waitTimeouts.size)
    assertEquals(300_000L, registry.waitTimeouts.single())
    assertEquals("300000", result.metadata["waitTimeoutMs"])
    assertEquals(1_800_000L.toString(), result.metadata["timeoutMs"])
  }

  @Test
  fun processWaitToolCapsHugeTimeoutBeforeControllerAwait() {
    val registry = CapturingProcessRegistry()
    val dispatcher = dispatcherFor(registry)
    val startResult = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("Get-Date"))),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])
    registry.waitTimeouts.clear()

    val waitResult = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(999999999L),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, waitResult.status)
    assertEquals(listOf(300_000L), registry.waitTimeouts)
  }

  private fun dispatcherFor(registry: CapturingProcessRegistry): OpenCrayToolDispatcher =
    OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(temporaryFolder.newFolder("bash-timeout-cap").toPath()),
        processRegistry = registry,
      ),
    )

  private fun agentTask(): AgentTask = AgentTask(
    id = "task-bash-timeout-cap-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    createdAtEpochMs = System.currentTimeMillis(),
    metadata = mapOf("chatMode" to "DEVELOPER"),
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentBashTimeoutCapTest.") },
  )

  private class CapturingProcessRegistry : AgentProcessRegistry {
    val startRequests = mutableListOf<ManagedProcessStartRequest>()
    val waitTimeouts = mutableListOf<Long>()
    private val snapshotsByProcessId = linkedMapOf<String, ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startRequests += request
      val snapshot = ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = System.currentTimeMillis(),
        updatedAtEpochMs = System.currentTimeMillis(),
        metadata = request.metadata,
      )
      snapshotsByProcessId[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsByProcessId.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? =
      snapshotsByProcessId[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      waitTimeouts += timeoutMs
      return snapshotsByProcessId[processId]
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? =
      snapshotsByProcessId[processId]
  }
}
