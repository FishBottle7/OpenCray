package com.opencray.runtime.subagent

import com.opencray.core.contracts.ExecutionStatus

fun SubAgentHandleState.hasDurableChildPromptCheckpoint(): Boolean =
  childPromptResumeState != null

fun SubAgentHandleState.withUpdatedChildPromptCheckpoint(
  checkpointState: com.opencray.runtime.OpenCrayPromptResumeState,
  checkpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary,
  emittedAtEpochMs: Long,
): SubAgentHandleState = copy(
  childPromptResumeState = checkpointState,
  childPromptCheckpointBoundary = checkpointBoundary,
  childPromptCheckpointAtEpochMs = emittedAtEpochMs,
  updatedAtEpochMs = maxOf(updatedAtEpochMs, emittedAtEpochMs),
)

fun SubAgentHandleState.withClearedChildPromptCheckpoint(
  updatedAtEpochMs: Long = this.updatedAtEpochMs,
): SubAgentHandleState = copy(
  childPromptResumeState = null,
  childPromptCheckpointBoundary = null,
  childPromptCheckpointAtEpochMs = null,
  updatedAtEpochMs = updatedAtEpochMs,
)

fun SubAgentHandleState.withPendingApprovalDecision(
  state: SubAgentPendingApprovalDecisionState,
  resume: SubAgentApprovalResume,
  recordedAtEpochMs: Long,
): SubAgentHandleState = copy(
  pendingApprovalDecision = SubAgentPendingApprovalDecision(
    state = state,
    resume = resume,
    recordedAtEpochMs = recordedAtEpochMs,
  ),
  updatedAtEpochMs = maxOf(updatedAtEpochMs, recordedAtEpochMs),
)

fun SubAgentHandleState.withClearedPendingApprovalDecision(
  updatedAtEpochMs: Long = this.updatedAtEpochMs,
): SubAgentHandleState = copy(
  pendingApprovalDecision = null,
  updatedAtEpochMs = maxOf(this.updatedAtEpochMs, updatedAtEpochMs),
)

fun restoredInterruptedBackgroundSubAgentHandle(
  handle: SubAgentHandleState,
  restoredAtEpochMs: Long,
): SubAgentHandleState = when {
  handle.snapshot.state != SubAgentExecutionState.BACKGROUND_RUNNING -> handle
  handle.hasDurableChildPromptCheckpoint() -> handle.copy(
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(
      headline = "Background delegated child run '${handle.description}' was interrupted by a cold restart and is queued to resume from its last durable checkpoint.",
    ),
    pendingApprovalResume = null,
    pendingApprovalDecision = null,
    updatedAtEpochMs = restoredAtEpochMs,
  )

  else -> handle
    .withClearedChildPromptCheckpoint(updatedAtEpochMs = restoredAtEpochMs)
    .copy(
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.FAILED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "Background delegated child run '${handle.description}' was interrupted before it could be harvested.",
        detailLines = listOf("Restart the child with spawn_agent if you still need this work."),
        childErrorCode = ERROR_CODE_BACKGROUND_INTERRUPTED,
      ),
      pendingApprovalResume = null,
      pendingApprovalDecision = null,
      childExecutionStatus = ExecutionStatus.FAILED.name,
    )
}

const val ERROR_CODE_BACKGROUND_INTERRUPTED: String = "SUBAGENT_BACKGROUND_INTERRUPTED"
