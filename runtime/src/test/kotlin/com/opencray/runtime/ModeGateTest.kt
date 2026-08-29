package com.opencray.runtime

import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeGateTest {

  @Test
  fun askWithoutApprovalTokenStaysBlocked() {
    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(),
      policyDecision = askPolicyDecision(),
      approvalToken = null,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_REQUIRED, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun askWithMatchingTokenAllowsFirstUseThenConsumedTokenBlocksReplay() {
    val request = commandRequest()
    val token = approvalToken(tokenId = "token-consume-once")

    val first = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )
    val second = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_100L,
    )

    assertEquals(CommandGateStatus.ALLOWED, first.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_TOKEN, first.reasonCode)
    assertTrue(first.shouldExecute)
    assertEquals(CommandGateStatus.BLOCKED, second.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_TOKEN_CONSUMED, second.reasonCode)
    assertFalse(second.shouldExecute)
  }

  @Test
  fun askWithFingerprintBoundTokenRejectsDifferentCommandContent() {
    val approvedRequest = commandRequest(command = "rm", args = listOf("tmp/x"))
    val token = approvalToken(
      tokenId = "token-content-bound",
      approvedRequestFingerprint = approvedRequest.approvalFingerprint(),
    )
    val differentRequest = commandRequest(command = "rm", args = listOf("-rf", "/"))

    val decision = ModeGate.evaluatePreExec(
      request = differentRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun askWithFingerprintBoundTokenAllowsMatchingCommand() {
    val request = commandRequest(command = "rm", args = listOf("tmp/x"))
    val token = approvalToken(
      tokenId = "token-content-match",
      approvedRequestFingerprint = request.approvalFingerprint(),
    )

    val decision = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.ALLOWED, decision.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_TOKEN, decision.reasonCode)
    assertTrue(decision.shouldExecute)
  }

  @Test
  fun expiredApprovalTokenIsBlockedBeforeConsumption() {
    val token = approvalToken(tokenId = "token-expired", approvedAtEpochMs = 0L)

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = ModeGate.APPROVAL_TOKEN_VALIDITY_MS + 1L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_EXPIRED, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun approvalTokenAtValidityBoundaryIsStillAllowed() {
    val token = approvalToken(tokenId = "token-boundary", approvedAtEpochMs = 0L)

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = ModeGate.APPROVAL_TOKEN_VALIDITY_MS,
    )

    assertEquals(CommandGateStatus.ALLOWED, decision.status)
    assertTrue(decision.shouldExecute)
  }

  @Test
  fun tokenForDifferentTaskRemainsBlocked() {
    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(taskId = "task-requested"),
      policyDecision = askPolicyDecision(),
      approvalToken = approvalToken(tokenId = "token-other-task", taskId = "task-approved"),
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_TASK_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun policyAllowWithoutApprovalTokenKeepsUnconditionalAllow() {
    val allowDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(),
      policyDecision = allowDecision,
      approvalToken = null,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.ALLOWED, decision.status)
    assertEquals(CommandGateReasonCode.ALLOW_POLICY_ALLOW, decision.reasonCode)
    assertTrue(decision.shouldExecute)
  }

  @Test
  fun policyAllowWithMatchingTokenAllowsFirstUseThenConsumedTokenBlocksReplay() {
    val allowDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )
    val request = commandRequest()
    val token = approvalToken(tokenId = "token-allow-consume-once")

    val first = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )
    val second = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_100L,
    )

    assertEquals(CommandGateStatus.ALLOWED, first.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_TOKEN, first.reasonCode)
    assertTrue(first.shouldExecute)
    assertEquals(CommandGateStatus.BLOCKED, second.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_TOKEN_CONSUMED, second.reasonCode)
    assertFalse(second.shouldExecute)
  }

  @Test
  fun policyAllowWithFingerprintBoundTokenRejectsDifferentCommandContent() {
    val allowDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )
    val approvedRequest = commandRequest(command = "rm", args = listOf("tmp/x"))
    val token = approvalToken(
      tokenId = "token-allow-content-bound",
      approvedRequestFingerprint = approvedRequest.approvalFingerprint(),
    )
    val differentRequest = commandRequest(command = "rm", args = listOf("-rf", "/"))

    val decision = ModeGate.evaluatePreExec(
      request = differentRequest,
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun policyAllowWithExpiredTokenIsBlocked() {
    val allowDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )
    val token = approvalToken(
      tokenId = "token-allow-expired",
      approvedAtEpochMs = 0L,
    )

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(),
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = ModeGate.APPROVAL_TOKEN_VALIDITY_MS + 1L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_EXPIRED, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchApprovalTokenAllowsMatchingPrefixWithoutConsuming() {
    val request = commandRequest(
      command = "git",
      args = listOf("status", "--short"),
      workingDirectory = "/workspace",
    )
    val token = batchApprovalToken(tokenId = "token-batch-match")

    val first = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )
    val second = ModeGate.evaluatePreExec(
      request = request,
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_100L,
    )

    assertEquals(CommandGateStatus.ALLOWED, first.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, first.reasonCode)
    assertTrue(first.shouldExecute)
    assertEquals(CommandGateStatus.ALLOWED, second.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, second.reasonCode)
    assertTrue(second.shouldExecute)
  }

  @Test
  fun batchApprovalTokenRejectsNonMatchingArgs() {
    val token = batchApprovalToken(tokenId = "token-batch-args")

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("push", "origin"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchApprovalTokenRejectsNonMatchingWorkingDirectory() {
    val token = batchApprovalToken(tokenId = "token-batch-cwd")

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/elsewhere",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchApprovalTokenDoesNotMatchSiblingCommandToken() {
    val token = batchApprovalToken(tokenId = "token-batch-sibling")

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "gitfoo",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchApprovalTokenWithInvalidPrefixIsBlockedUnderAsk() {
    val invalidPrefixes = listOf(
      listOf("bash", "-c"),
      listOf("git"),
      listOf("npm", "run"),
      listOf("sudo", "apt"),
    )
    invalidPrefixes.forEachIndexed { index, prefix ->
      val token = batchApprovalToken(
        tokenId = "token-batch-invalid-$index",
        batchPrefixArgs = prefix,
      )

      val decision = ModeGate.evaluatePreExec(
        request = commandRequest(
          command = prefix.first(),
          args = prefix.drop(1),
          workingDirectory = "/workspace",
        ),
        policyDecision = askPolicyDecision(),
        approvalToken = token,
        decidedAtEpochMs = 1_000L,
      )

      assertEquals(CommandGateStatus.BLOCKED, decision.status)
      assertEquals(
        CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH,
        decision.reasonCode,
      )
      assertFalse(decision.shouldExecute)
    }
  }

  @Test
  fun batchApprovalTokenUnderAllowPolicyFallsBackToUnconditionalAllow() {
    val allowDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_SAFE_COMMAND",
      detail = "Allowed by policy.",
    )
    val token = batchApprovalToken(tokenId = "token-batch-allow")

    val matching = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )
    val nonMatching = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "ls",
        args = listOf("-la"),
        workingDirectory = "/workspace",
      ),
      policyDecision = allowDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.ALLOWED, matching.status)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, matching.reasonCode)
    assertTrue(matching.shouldExecute)
    assertEquals(CommandGateStatus.ALLOWED, nonMatching.status)
    assertEquals(CommandGateReasonCode.ALLOW_POLICY_ALLOW, nonMatching.reasonCode)
    assertTrue(nonMatching.shouldExecute)
  }

  @Test
  fun denyPolicyBeatsBatchApprovalToken() {
    val denyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.DENY,
      reasonCode = "DENY_TEST",
      detail = "Denied by policy.",
    )
    val token = batchApprovalToken(tokenId = "token-batch-deny")

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
      policyDecision = denyDecision,
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.DENIED, decision.status)
    assertEquals(CommandGateReasonCode.DENY_POLICY_DECISION, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchRuleAndFingerprintTokenStayIndependent() {
    val fingerprintRequest = commandRequest(
      command = "rm",
      args = listOf("tmp/x"),
      workingDirectory = "/workspace",
    )
    val fingerprintToken = approvalToken(
      tokenId = "token-fingerprint",
      approvedRequestFingerprint = fingerprintRequest.approvalFingerprint(),
    )
    val batchToken = batchApprovalToken(tokenId = "token-batch-independent")
    val batchRequest = commandRequest(
      command = "git",
      args = listOf("status", "--short"),
      workingDirectory = "/workspace",
    )

    val fingerprintFirst = ModeGate.evaluatePreExec(
      request = fingerprintRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = fingerprintToken,
      decidedAtEpochMs = 1_000L,
    )
    val fingerprintReplay = ModeGate.evaluatePreExec(
      request = fingerprintRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = fingerprintToken,
      decidedAtEpochMs = 1_100L,
    )
    val batchFirst = ModeGate.evaluatePreExec(
      request = batchRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = batchToken,
      decidedAtEpochMs = 1_200L,
    )
    val batchRepeat = ModeGate.evaluatePreExec(
      request = batchRequest,
      policyDecision = askPolicyDecision(),
      approvalToken = batchToken,
      decidedAtEpochMs = 1_300L,
    )

    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_TOKEN, fingerprintFirst.reasonCode)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_TOKEN_CONSUMED, fingerprintReplay.reasonCode)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, batchFirst.reasonCode)
    assertEquals(CommandGateReasonCode.ALLOW_APPROVAL_BATCH_RULE, batchRepeat.reasonCode)
  }

  @Test
  fun batchApprovalTokenExpiredIsBlocked() {
    val token = batchApprovalToken(
      tokenId = "token-batch-expired",
      approvedAtEpochMs = 0L,
    )

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = ModeGate.APPROVAL_TOKEN_VALIDITY_MS + 1L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_EXPIRED, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchApprovalTokenForDifferentTaskRemainsBlocked() {
    val token = batchApprovalToken(tokenId = "token-batch-task", taskId = "task-approved")

    val decision = ModeGate.evaluatePreExec(
      request = commandRequest(
        command = "git",
        args = listOf("status"),
        workingDirectory = "/workspace",
      ),
      policyDecision = askPolicyDecision(),
      approvalToken = token,
      decidedAtEpochMs = 1_000L,
    )

    assertEquals(CommandGateStatus.BLOCKED, decision.status)
    assertEquals(CommandGateReasonCode.BLOCK_APPROVAL_TASK_MISMATCH, decision.reasonCode)
    assertFalse(decision.shouldExecute)
  }

  @Test
  fun batchPrefixGovernanceValidatesShapeAndBlocklist() {
    assertTrue(isValidBatchPrefix(listOf("git", "status")))
    assertTrue(isValidBatchPrefix(listOf("npm", "test")))
    assertTrue(isValidBatchPrefix(listOf("ls")))
    assertTrue(isValidBatchPrefix(listOf("cargo", "build", "--release")))

    assertFalse(isValidBatchPrefix(emptyList()))
    assertFalse(isValidBatchPrefix(listOf("")))
    assertFalse(isValidBatchPrefix(listOf("git", "st\u0000atus")))
    assertFalse(isValidBatchPrefix(listOf("git", "sta tus")))
    assertFalse(isValidBatchPrefix(List(MAX_BATCH_PREFIX_TOKENS + 1) { "arg$it" }))
    assertFalse(isValidBatchPrefix(listOf("a".repeat(MAX_BATCH_PREFIX_TOKEN_LENGTH + 1))))

    BATCH_BLOCKLIST_EXAMPLES.forEach { token ->
      assertFalse("wrapper token should be rejected: $token", isValidBatchPrefix(listOf(token)))
    }
    assertFalse(isValidBatchPrefix(listOf("Bash", "-c")))
    assertFalse(isValidBatchPrefix(listOf("git")))
    assertFalse(isValidBatchPrefix(listOf("rm")))
    assertFalse(isValidBatchPrefix(listOf("npm")))
    assertFalse(isValidBatchPrefix(listOf("docker")))
    assertFalse(isValidBatchPrefix(listOf("Git")))
    assertFalse(isValidBatchPrefix(listOf("npm", "run", "build")))
  }

  private val BATCH_BLOCKLIST_EXAMPLES: List<String> = listOf(
    "bash",
    "sh",
    "zsh",
    "cmd",
    "pwsh",
    "powershell",
    "python",
    "python3",
    "node",
    "perl",
    "ruby",
    "osascript",
    "Rscript",
    "env",
    "sudo",
    "doas",
    "pkexec",
    "xargs",
    "nohup",
    "timeout",
    "nice",
    "time",
    "stdbuf",
  )

  private fun commandRequest(
    taskId: String = "task-1",
    command: String = "rm",
    args: List<String> = listOf("tmp/x"),
    workingDirectory: String? = null,
  ): CommandExecutionRequest = CommandExecutionRequest(
    taskId = taskId,
    command = command,
    args = args,
    workingDirectory = workingDirectory,
    requestedAtEpochMs = 900L,
  )

  private fun approvalToken(
    tokenId: String,
    taskId: String = "task-1",
    approvedAtEpochMs: Long = 900L,
    approvedRequestFingerprint: String? = null,
  ): CommandApprovalToken = CommandApprovalToken(
    tokenId = tokenId,
    taskId = taskId,
    approvedAtEpochMs = approvedAtEpochMs,
    approvedBy = "user-test",
    approvedRequestFingerprint = approvedRequestFingerprint,
  )

  private fun batchApprovalToken(
    tokenId: String,
    taskId: String = "task-1",
    approvedAtEpochMs: Long = 900L,
    batchPrefixArgs: List<String> = listOf("git", "status"),
    batchWorkingDirectory: String = "/workspace",
  ): CommandApprovalToken = CommandApprovalToken(
    tokenId = tokenId,
    taskId = taskId,
    approvedAtEpochMs = approvedAtEpochMs,
    approvedBy = "user-test",
    batchPrefixArgs = batchPrefixArgs,
    batchWorkingDirectory = batchWorkingDirectory,
  )

  private fun askPolicyDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ASK,
    reasonCode = "ASK_TEST",
    detail = "Approval required.",
  )
}
