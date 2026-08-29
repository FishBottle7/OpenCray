package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandExecutionConfig
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.CommandGateReasonCode
import com.opencray.runtime.CommandProcessRunner
import com.opencray.runtime.CommandSpawnResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandBatchApprovalFlowTest {
  @Test
  fun batchApprovalLetsSameTaskSecondSamePrefixCommandRunWithoutReapproval() {
    val registry = AgentTaskApprovalRegistry()
    val executor = CommandExecutor(runner = RecordingCommandRunner())
    val firstRequest = commandRequest(
      taskId = "task-1",
      command = "git",
      args = listOf("status"),
      workingDirectory = "/workspace",
    )

    val blocked = executor.execute(
      request = firstRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = null,
      hooks = runtimeHooks(),
    )
    assertEquals(ExecutionStatus.DENIED, blocked.status)
    assertEquals("APPROVAL_REQUIRED", blocked.errorCode)

    registry.markApproved(
      sessionId = "session-1",
      taskId = "task-1",
      approvedRequestFingerprint = blocked.metadata["approvalRequestFingerprint"],
      commandBatchApproval = commandBatchApprovalSpecFromMetadata(blocked.metadata),
    )
    val batchToken = requireNotNull(registry.batchCommandApprovalToken("session-1")) {
      "Batch approval should be derivable from the blocked command metadata."
    }
    assertEquals(listOf("git", "status"), batchToken.batchPrefixArgs)
    assertEquals("/workspace", batchToken.batchWorkingDirectory)

    val second = executor.execute(
      request = commandRequest(
        taskId = "task-1",
        command = "git",
        args = listOf("status", "--short"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = batchToken,
      hooks = runtimeHooks(),
    )
    assertEquals(ExecutionStatus.SUCCESS, second.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, second.metadata["gateReasonCode"])

    val third = executor.execute(
      request = commandRequest(
        taskId = "task-1",
        command = "git",
        args = listOf("push", "origin"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = registry.batchCommandApprovalToken("session-1"),
      hooks = runtimeHooks(),
    )
    assertEquals(ExecutionStatus.DENIED, third.status)
    assertEquals("APPROVAL_REQUIRED", third.errorCode)
    assertEquals(
      CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH,
      third.metadata["gateReasonCode"],
    )
  }

  private fun commandRequest(
    taskId: String,
    command: String,
    args: List<String>,
    workingDirectory: String,
  ): CommandExecutionRequest = CommandExecutionRequest(
    taskId = taskId,
    command = command,
    args = args,
    workingDirectory = workingDirectory,
    requestedAtEpochMs = System.currentTimeMillis(),
  )

  private fun askPolicyDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ASK,
    reasonCode = "ASK_TEST",
    detail = "Approval required.",
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in CommandBatchApprovalFlowTest.") },
  )

  private class RecordingCommandRunner : CommandProcessRunner {
    override fun run(
      commandLine: List<String>,
      workingDirectory: String?,
      config: CommandExecutionConfig,
      hooks: RuntimeExecutionHooks,
    ): CommandSpawnResult = CommandSpawnResult(
      exitCode = 0,
      stdout = "",
      stderr = "",
      processStarted = true,
    )
  }
}
