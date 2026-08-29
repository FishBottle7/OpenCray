package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandBatchApprovalEndToEndTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun batchApprovalCoversSecondSamePrefixCommandWithoutReapproval() {
    val workspaceRoot = temporaryFolder.newFolder("batch-approval-e2e").toPath()
    val workingDirectory = WorkspaceBoundary(setOf(workspaceRoot))
      .defaultRoot
      .toString()
    val runner = RecordingRunner()
    val taskId = "task-batch-e2e"
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        commandExecutor = CommandExecutor(runner = runner),
        commandBatchApprovalToken = CommandApprovalToken(
          tokenId = "token-batch-e2e",
          taskId = taskId,
          approvedAtEpochMs = System.currentTimeMillis(),
          approvedBy = "user-test",
          batchPrefixArgs = listOf("git", "status"),
          batchWorkingDirectory = workingDirectory,
        ),
      ),
    )

    val first = dispatcher.dispatch(
      task = agentTask(id = taskId),
      call = commandExecCall(command = "git", args = listOf("status")),
      hooks = runtimeHooks(),
    )
    val second = dispatcher.dispatch(
      task = agentTask(id = taskId),
      call = commandExecCall(command = "git", args = listOf("status", "--short")),
      hooks = runtimeHooks(),
    )
    val third = dispatcher.dispatch(
      task = agentTask(id = taskId),
      call = commandExecCall(command = "git", args = listOf("log", "--oneline")),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, first.status)
    assertEquals(
      CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE,
      first.metadata["gateReasonCode"],
    )
    assertEquals(AgentToolResultStatus.SUCCESS, second.status)
    assertEquals(
      CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE,
      second.metadata["gateReasonCode"],
    )
    assertEquals(AgentToolResultStatus.DENIED, third.status)
    assertEquals("APPROVAL_REQUIRED", third.errorCode)
    assertEquals(
      CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH,
      third.metadata["gateReasonCode"],
    )
    assertEquals(2, runner.spawnCount)
  }

  @Test
  fun preciseTokenDoesNotStarveBatchRuleForSecondSamePrefixCommand() {
    val workspaceRoot = temporaryFolder.newFolder("batch-approval-e2e-precise").toPath()
    val workingDirectory = WorkspaceBoundary(setOf(workspaceRoot))
      .defaultRoot
      .toString()
    val runner = RecordingRunner()
    val taskId = "task-batch-e2e-precise"
    val preciseRequest = CommandExecutionRequest(
      taskId = taskId,
      command = "git",
      args = listOf("status"),
      workingDirectory = workingDirectory,
      requestedAtEpochMs = 1_000L,
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        commandExecutor = CommandExecutor(runner = runner),
        commandApprovalToken = CommandApprovalToken(
          tokenId = "token-precise-e2e",
          taskId = taskId,
          approvedAtEpochMs = System.currentTimeMillis(),
          approvedBy = "user-test",
          approvedRequestFingerprint = preciseRequest.approvalFingerprint(),
        ),
        commandBatchApprovalToken = CommandApprovalToken(
          tokenId = "token-batch-e2e-precise",
          taskId = taskId,
          approvedAtEpochMs = System.currentTimeMillis(),
          approvedBy = "user-test",
          batchPrefixArgs = listOf("git", "status"),
          batchWorkingDirectory = workingDirectory,
        ),
      ),
    )

    val first = dispatcher.dispatch(
      task = agentTask(id = taskId),
      call = commandExecCall(command = "git", args = listOf("status")),
      hooks = runtimeHooks(),
    )
    val second = dispatcher.dispatch(
      task = agentTask(id = taskId),
      call = commandExecCall(command = "git", args = listOf("status", "--short")),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, first.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_TOKEN, first.metadata["gateReasonCode"])
    assertEquals(AgentToolResultStatus.SUCCESS, second.status)
    assertEquals(
      CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE,
      second.metadata["gateReasonCode"],
    )
    assertEquals(2, runner.spawnCount)
  }

  private fun agentTask(id: String): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = mapOf("chatMode" to "AUTO"),
    createdAtEpochMs = 1_000L,
  )

  private fun commandExecCall(command: String, args: List<String>): AgentToolCall = AgentToolCall(
    toolName = "command_exec",
    arguments = JsonObject(
      mapOf(
        "command" to JsonPrimitive(command),
        "args" to JsonArray(args.map(::JsonPrimitive)),
      ),
    ),
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in CommandBatchApprovalEndToEndTest.") },
  )

  private class RecordingRunner : CommandProcessRunner {
    var spawnCount: Int = 0
      private set

    override fun run(
      commandLine: List<String>,
      workingDirectory: String?,
      config: CommandExecutionConfig,
      hooks: RuntimeExecutionHooks,
    ): CommandSpawnResult {
      spawnCount += 1
      return CommandSpawnResult(
        exitCode = 0,
        stdout = "",
        stderr = "",
        processStarted = true,
      )
    }
  }
}
