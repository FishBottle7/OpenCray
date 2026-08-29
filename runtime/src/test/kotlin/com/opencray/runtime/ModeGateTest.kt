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

  private fun commandRequest(
    taskId: String = "task-1",
    command: String = "rm",
    args: List<String> = listOf("tmp/x"),
  ): CommandExecutionRequest = CommandExecutionRequest(
    taskId = taskId,
    command = command,
    args = args,
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

  private fun askPolicyDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ASK,
    reasonCode = "ASK_TEST",
    detail = "Approval required.",
  )
}
