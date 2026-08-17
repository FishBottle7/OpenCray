package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentHandleStateTest {
  @Test
  fun shouldEnsureDetachedBackgroundExecutionOnlyForQueuedOrDecisionReadyHandles() {
    val queuedHandle = handle(
      state = SubAgentExecutionState.BACKGROUND_QUEUED,
    )
    val waitingWithoutDecision = handle(
      state = SubAgentExecutionState.WAITING_APPROVAL,
      pendingApprovalResume = approvalResume(),
    )
    val waitingWithDecision = waitingWithoutDecision.copy(
      pendingApprovalDecision = pendingApprovalDecision(approved = true),
    )

    assertTrue(queuedHandle.shouldEnsureDetachedBackgroundExecution())
    assertFalse(waitingWithoutDecision.shouldEnsureDetachedBackgroundExecution())
    assertTrue(waitingWithDecision.shouldEnsureDetachedBackgroundExecution())
  }

  @Test
  fun canAcceptMailboxInputForQueuedRunningAndApprovalPausedHandles() {
    assertTrue(
      handle(
        state = SubAgentExecutionState.BACKGROUND_QUEUED,
      ).canAcceptMailboxInput(),
    )
    assertTrue(
      handle(
        state = SubAgentExecutionState.BACKGROUND_RUNNING,
      ).canAcceptMailboxInput(),
    )
    assertTrue(
      handle(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        pendingApprovalResume = approvalResume(),
      ).canAcceptMailboxInput(),
    )
    assertFalse(
      handle(
        state = SubAgentExecutionState.COMPLETED,
      ).canAcceptMailboxInput(),
    )
  }

  @Test
  fun hasLiveBackgroundExecutionOnlyForQueuedOrRunningDetachedHandles() {
    assertTrue(
      handle(
        state = SubAgentExecutionState.BACKGROUND_QUEUED,
      ).hasLiveBackgroundExecution(),
    )
    assertTrue(
      handle(
        state = SubAgentExecutionState.BACKGROUND_RUNNING,
      ).hasLiveBackgroundExecution(),
    )
    assertFalse(
      handle(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        pendingApprovalResume = approvalResume(),
      ).hasLiveBackgroundExecution(),
    )
  }

  @Test
  fun canContinueDetachedExecutionRequiresOpenOrApprovalContinuation() {
    assertTrue(
      handle(
        state = SubAgentExecutionState.BACKGROUND_QUEUED,
      ).canContinueDetachedExecution(hasApprovalContinuation = false),
    )
    assertFalse(
      handle(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        pendingApprovalResume = approvalResume(),
      ).canContinueDetachedExecution(hasApprovalContinuation = false),
    )
    assertTrue(
      handle(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        pendingApprovalResume = approvalResume(),
      ).canContinueDetachedExecution(hasApprovalContinuation = true),
    )
    assertFalse(
      handle(
        state = SubAgentExecutionState.COMPLETED,
      ).canContinueDetachedExecution(hasApprovalContinuation = true),
    )
  }
}

private fun handle(
  state: SubAgentExecutionState,
  pendingApprovalResume: SubAgentApprovalResume? = null,
  pendingApprovalDecision: SubAgentPendingApprovalDecision? = null,
): SubAgentHandleState = SubAgentHandleState(
  agentId = "child-1",
  childRunId = "child-run-1",
  childTaskId = "child-task-1",
  description = "Inspect docs",
  prompt = "Inspect README.md.",
  subagentType = "worker",
  contextMode = "delegated",
  parentRunId = "parent-run-1",
  parentTaskId = "parent-task-1",
  parentTurn = 0,
  depth = 1,
  snapshot = snapshot(state),
  pendingApprovalResume = pendingApprovalResume,
  pendingApprovalDecision = pendingApprovalDecision,
  createdAtEpochMs = 1_000L,
  updatedAtEpochMs = 1_000L,
)

private fun snapshot(
  state: SubAgentExecutionState,
): SubAgentExecutionSnapshot = when (state) {
  SubAgentExecutionState.RUNNING -> SubAgentExecutionSnapshot.running()
  SubAgentExecutionState.BACKGROUND_QUEUED -> SubAgentExecutionSnapshot.backgroundQueued()
  SubAgentExecutionState.BACKGROUND_RUNNING -> SubAgentExecutionSnapshot.backgroundRunning()
  SubAgentExecutionState.WAITING_APPROVAL -> SubAgentExecutionSnapshot(
    state = SubAgentExecutionState.WAITING_APPROVAL,
    continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
    resumable = true,
    requiresUserAction = true,
    isHighRisk = false,
    headline = "Waiting for approval.",
  )
  SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL -> SubAgentExecutionSnapshot(
    state = SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
    continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
    resumable = true,
    requiresUserAction = true,
    isHighRisk = true,
    headline = "Waiting for high-risk approval.",
  )
  SubAgentExecutionState.COMPLETED -> SubAgentExecutionSnapshot(
    state = SubAgentExecutionState.COMPLETED,
    continuationKind = SubAgentContinuationKind.NONE,
    resumable = false,
    requiresUserAction = false,
    isHighRisk = false,
    headline = "Completed.",
  )
  SubAgentExecutionState.FAILED -> SubAgentExecutionSnapshot(
    state = SubAgentExecutionState.FAILED,
    continuationKind = SubAgentContinuationKind.NONE,
    resumable = false,
    requiresUserAction = false,
    isHighRisk = false,
    headline = "Failed.",
  )
  SubAgentExecutionState.CANCELLED -> SubAgentExecutionSnapshot(
    state = SubAgentExecutionState.CANCELLED,
    continuationKind = SubAgentContinuationKind.NONE,
    resumable = false,
    requiresUserAction = false,
    isHighRisk = false,
    headline = "Cancelled.",
  )
}

private fun approvalResume(): SubAgentApprovalResume = SubAgentApprovalResume(
  approvedToolName = "Edit",
  promptResumeState = OpenCrayPromptResumeState(
    turnIndex = 1,
    toolCallCount = 1,
  ),
  agentId = "child-1",
  childRunId = "child-run-1",
  childTaskId = "child-task-1",
)

private fun pendingApprovalDecision(
  approved: Boolean,
): SubAgentPendingApprovalDecision = SubAgentPendingApprovalDecision(
  state = if (approved) {
    SubAgentPendingApprovalDecisionState.APPROVED
  } else {
    SubAgentPendingApprovalDecisionState.REJECTED
  },
  resume = approvalResume(),
  recordedAtEpochMs = 2_000L,
)
