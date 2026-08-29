package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalDecisionSupportTest {
  @Test
  fun toApprovalDecisionRecordReadsFingerprintFromResultMetadata() {
    val projection = approvalProjection(
      metadata = mapOf(
        "command" to "rm",
        "args" to "tmp/x",
        "approvalRequestFingerprint" to " fingerprint-abc ",
      ),
    )

    val record = projection.toApprovalDecisionRecord(
      highRiskApprovalRequiredErrorCode = "HIGH_RISK_APPROVAL_REQUIRED",
    )

    assertEquals("fingerprint-abc", record.approvedRequestFingerprint)
  }

  @Test
  fun toApprovalDecisionRecordWithoutFingerprintMetadataYieldsNull() {
    val projection = approvalProjection(metadata = emptyMap())

    val record = projection.toApprovalDecisionRecord(
      highRiskApprovalRequiredErrorCode = "HIGH_RISK_APPROVAL_REQUIRED",
    )

    assertNull(record.approvedRequestFingerprint)
  }

  @Test
  fun decisionCheckpointPersistsFingerprintAndGrantRebuildsEquivalentToken() {
    val record = approvalRecord(approvedRequestFingerprint = "fingerprint-abc")

    val checkpoint = record.decisionCheckpoint(
      sessionId = "session-1",
      checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
      nowEpochMs = 100L,
    )

    assertEquals("fingerprint-abc", checkpoint.approvedRequestFingerprint)
    val grant = requireNotNull(checkpoint.toApprovalGrantOrNull())
    val token = requireNotNull(grant.commandApprovalToken)
    assertEquals("fingerprint-abc", token.approvedRequestFingerprint)
    assertEquals(checkpoint.taskId, token.taskId)
    assertEquals(100L, token.approvedAtEpochMs)
  }

  @Test
  fun decisionCheckpointWithoutFingerprintRestoresGrantWithoutToken() {
    val record = approvalRecord(approvedRequestFingerprint = null)

    val checkpoint = record.decisionCheckpoint(
      sessionId = "session-1",
      checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
      nowEpochMs = 100L,
    )

    assertNull(checkpoint.approvedRequestFingerprint)
    assertNull(checkpoint.toApprovalGrantOrNull()?.commandApprovalToken)
  }

  private fun approvalRecord(
    approvedRequestFingerprint: String?,
  ): ApprovalDecisionRecord = ApprovalDecisionRecord(
    runId = "run-1",
    taskId = "task-1",
    pendingMessageId = null,
    executionId = null,
    executionOrdinal = null,
    executionKind = null,
    toolName = "command_exec",
    resumeToolName = null,
    promptCheckpointBoundary = null,
    promptResumeState = null,
    subAgentApprovalResume = null,
    isHighRisk = false,
    approvedRequestFingerprint = approvedRequestFingerprint,
  )

  private fun approvalProjection(
    metadata: Map<String, String>,
  ): ApprovalRequiredTaskProjection = ApprovalRequiredTaskProjection(
    sessionId = "session-1",
    taskSnapshot = SessionQueueTaskSnapshot(
      enqueueOrder = 0L,
      task = AgentTask(
        id = "task-1",
        type = AgentTaskType.PROMPT,
        input = "Approval required",
        state = AgentTaskState.SUSPENDED,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = "APPROVAL_REQUIRED",
        ),
        createdAtEpochMs = 100L,
      ),
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      lastErrorCode = "APPROVAL_REQUIRED",
    ),
    runSnapshot = AgentRunSnapshot(
      sessionId = "session-1",
      runId = "run-1",
      taskId = "task-1",
      acceptedAtEpochMs = 100L,
      updatedAtEpochMs = 100L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
      executionStatus = com.opencray.core.contracts.ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      resultMetadata = metadata,
    ),
    checkpoint = null,
  )
}
