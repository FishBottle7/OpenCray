package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandExecutorTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun runAllowedCommandExecutionEmitsAuditAndSpawnsOnce() {
    val runner = RecordingRunner(
      resultProvider = {
        CommandSpawnResult(
          exitCode = 0,
          stdout = "allowed stdout",
          stderr = "",
          processStarted = true,
        )
      }
    )
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(1_000L, 1_025L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-allowed",
      command = "echo",
      args = listOf("hello"),
      requestedAtEpochMs = 900L,
      metadata = mapOf("traceId" to "trace-allowed"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals(1, runner.spawnCount)
    assertEquals(1, audits.size)
    assertEquals(listOf("echo", "hello"), runner.commandLines.single())

    val audit = audits.single()
    assertEquals(CommandGateStatus.ALLOWED, audit.gateStatus)
    assertEquals(ExecutionStatus.SUCCESS, audit.executionStatus)
    assertTrue(audit.spawned)
    assertEquals(0, audit.exitCode)

    printEvidenceLine(
      label = "task9-command-happy",
      request = request,
      result = result,
      audit = audit,
      spawnCount = runner.spawnCount,
    )
  }

  @Test
  fun runDeniedCommandNoSpawnEmitsAuditRecord() {
    val runner = RecordingRunner()
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(2_000L, 2_005L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-denied",
      command = "rm",
      args = listOf("-rf", "/"),
      requestedAtEpochMs = 1_950L,
      metadata = mapOf("traceId" to "trace-denied"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.DENY,
      reasonCode = "DENY_DANGEROUS_COMMAND",
      detail = "Denied by policy.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals(0, runner.spawnCount)
    assertEquals(1, audits.size)

    val audit = audits.single()
    assertEquals(CommandGateStatus.DENIED, audit.gateStatus)
    assertEquals(ExecutionStatus.DENIED, audit.executionStatus)
    assertFalse(audit.spawned)
    assertEquals("DENY_POLICY", audit.errorCode)

    printEvidenceLine(
      label = "task9-command-deny",
      request = request,
      result = result,
      audit = audit,
      spawnCount = runner.spawnCount,
    )
  }

  @Test
  fun runAskWithoutApprovalTokenReturnsDeniedApprovalRequiredAndDoesNotSpawn() {
    val runner = RecordingRunner()
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(2_500L, 2_510L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-approval-required",
      command = "git",
      args = listOf("push"),
      requestedAtEpochMs = 2_450L,
      metadata = mapOf("traceId" to "trace-approval-required"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ASK,
      reasonCode = "ASK_APPROVAL_REQUIRED",
      detail = "Approval is required before running this command.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals(0, runner.spawnCount)
    assertEquals(1, audits.size)

    val audit = audits.single()
    assertEquals(CommandGateStatus.BLOCKED, audit.gateStatus)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_REQUIRED, audit.gateReasonCode)
    assertEquals(ExecutionStatus.DENIED, audit.executionStatus)
    assertFalse(audit.spawned)
    assertEquals("APPROVAL_REQUIRED", audit.errorCode)
  }

  @Test
  fun runHighRiskAskWithoutApprovalTokenReturnsDedicatedHighRiskCodeAndDoesNotSpawn() {
    val runner = RecordingRunner()
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(2_700L, 2_710L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-high-risk-approval-required",
      command = "python",
      args = listOf("script.py"),
      requestedAtEpochMs = 2_650L,
      metadata = mapOf("traceId" to "trace-high-risk-approval-required"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ASK,
      reasonCode = "ASK_SAFE_COMMAND_HIGH_RISK",
      detail = "Approval is required before command execution.",
      approvalRisk = PolicyApprovalRisk.HIGH_RISK,
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertTrue(result.errorMessage.orEmpty().contains("High-risk approval"))
    assertEquals(0, runner.spawnCount)
    assertEquals(1, audits.size)

    val audit = audits.single()
    assertEquals(CommandGateStatus.BLOCKED, audit.gateStatus)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_REQUIRED, audit.gateReasonCode)
    assertEquals(ExecutionStatus.DENIED, audit.executionStatus)
    assertFalse(audit.spawned)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", audit.errorCode)
  }

  @Test
  fun runTimeoutCommandExecutionMapsToDeterministicStatusAndCode() {
    val runner = RecordingRunner(
      resultProvider = {
        CommandSpawnResult(
          exitCode = 124,
          stdout = "partial stdout",
          stderr = "",
          processStarted = true,
          timedOut = true,
        )
      }
    )
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(3_000L, 3_050L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-timeout",
      command = "sleep",
      args = listOf("5"),
      requestedAtEpochMs = 2_950L,
      metadata = mapOf("traceId" to "trace-timeout"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals("TIMEOUT", result.errorCode)
    assertEquals(1, runner.spawnCount)
    assertEquals(1, audits.size)

    val audit = audits.single()
    assertEquals(CommandGateStatus.ALLOWED, audit.gateStatus)
    assertEquals(ExecutionStatus.TIMEOUT, audit.executionStatus)
    assertTrue(audit.spawned)
    assertTrue(audit.timedOut)
    assertEquals("TIMEOUT", audit.errorCode)
  }

  @Test
  fun runOutputLimitCommandExecutionMapsToDeterministicStatusAndCode() {
    val runner = RecordingRunner(
      resultProvider = {
        CommandSpawnResult(
          exitCode = 137,
          stdout = "truncated",
          stderr = "",
          processStarted = true,
          outputLimitExceeded = true,
        )
      }
    )
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(4_000L, 4_030L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-output-limit",
      command = "python",
      args = listOf("-c", "print('x' * 100000)"),
      requestedAtEpochMs = 3_950L,
      metadata = mapOf("traceId" to "trace-output-limit"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("OUTPUT_LIMIT_EXCEEDED", result.errorCode)
    assertEquals(1, runner.spawnCount)
    assertEquals(1, audits.size)

    val audit = audits.single()
    assertEquals(CommandGateStatus.ALLOWED, audit.gateStatus)
    assertEquals(ExecutionStatus.FAILED, audit.executionStatus)
    assertTrue(audit.spawned)
    assertTrue(audit.outputLimitExceeded)
    assertEquals("OUTPUT_LIMIT_EXCEEDED", audit.errorCode)
  }

  @Test
  fun runCommandOutsideApprovedWorkspaceIsDeniedBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("command-workspace").toPath()
    val runner = RecordingRunner()
    val audits = mutableListOf<CommandExecutionAuditRecord>()
    val executor = CommandExecutor(
      runner = runner,
      config = CommandExecutionConfig(
        approvedWorkingDirectories = setOf(workspaceRoot),
      ),
      auditSink = CommandAuditSink { record -> audits += record },
      clock = clockOf(5_000L, 5_010L),
    )
    val request = CommandExecutionRequest(
      taskId = "task-workspace-boundary",
      command = "git",
      args = listOf("status"),
      workingDirectory = workspaceRoot.parent.toString(),
      requestedAtEpochMs = 4_950L,
      metadata = mapOf("traceId" to "trace-workspace-boundary"),
    )
    val policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )

    val result = executor.execute(
      request = request,
      policyDecision = policyDecision,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("WORKSPACE_BOUNDARY_DENIED", result.errorCode)
    assertEquals(0, runner.spawnCount)
    assertEquals(1, audits.size)
    assertEquals(CommandGateStatus.DENIED, audits.single().gateStatus)
  }

  private fun runtimeHooks(cancelled: Boolean = false): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { cancelled },
    requestRetry = { _: RetryRequest -> error("Retry not expected in CommandExecutor unit tests.") },
  )

  private fun clockOf(vararg values: Long): () -> Long {
    require(values.isNotEmpty()) { "clockOf requires at least one value." }
    var index = 0
    return {
      val next = values.getOrElse(index) { values.last() }
      index += 1
      next
    }
  }

  private fun printEvidenceLine(
    label: String,
    request: CommandExecutionRequest,
    result: ExecutionResult,
    audit: CommandExecutionAuditRecord,
    spawnCount: Int,
  ) {
    println(
      "$label command=${request.command} args=${request.args.joinToString(" ")} " +
        "spawn_count=$spawnCount spawned=${audit.spawned} " +
        "execution_status=${result.status} exit_code=${result.exitCode} " +
        "gate_status=${audit.gateStatus} gate_reason=${audit.gateReasonCode} " +
        "error_code=${result.errorCode}"
    )
  }

  private class RecordingRunner(
    private val resultProvider: () -> CommandSpawnResult = {
      error("Command runner should not be invoked for denied executions.")
    }
  ) : CommandProcessRunner {
    var spawnCount: Int = 0
      private set

    val commandLines: MutableList<List<String>> = mutableListOf()

    override fun run(
      commandLine: List<String>,
      workingDirectory: String?,
      config: CommandExecutionConfig,
      hooks: RuntimeExecutionHooks,
    ): CommandSpawnResult {
      spawnCount += 1
      commandLines += commandLine
      return resultProvider()
    }
  }
}

// Learnings: CommandExecutor already cleanly separates gate decisions from process spawning, which makes deny-path unit tests deterministic.
// Issues: This focused file covers only the allow and deny branches, so timeout and cancellation behavior still belongs in separate tests.
// Learnings: Fake spawn results make timeout and output-limit mapping testable without relying on real processes or wall-clock timing.
// Issues: These unit tests intentionally cover mapping only, so collector-thread behavior still depends on the LocalCommandProcessRunner tests that do not exist yet.
// Learnings: ASK-mode without an approval token is treated as a blocked gate but still returns a denied execution result with a stable approval-required code.
// Issues: Approval-token mismatch and successful ASK-with-token execution are still separate edge cases outside this focused acceptance test.
// Learnings: Printing a single deterministic audit line from the relevant passing tests gives the evidence files the exact gate and execution details the plan expects.
// Issues: The evidence refresh still depends on Gradle surfacing standard output for passing unit tests in this module.
