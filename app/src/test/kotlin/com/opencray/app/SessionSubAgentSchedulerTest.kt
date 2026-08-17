package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSubAgentSchedulerTest {
  @Test
  fun restorePersistedVisibleTasksRestoresQueuedAndSuspendedRecoveryLanes() {
    val sessionId = "session-scheduler"
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-queued",
        parentRunId = "parent-run-queued",
      ),
    )
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-suspended",
        parentRunId = "parent-run-suspended",
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations(sessionId)
    val queuedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-queued",
      parentRunId = "parent-run-queued",
    )
    val suspendedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-suspended",
      parentRunId = "parent-run-suspended",
    )
    val scheduler = SessionOwnedSubAgentScheduler(
      sessionId = sessionId,
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentSchedulerCallbacks(
        persistedRecoveryRunStates = {
          listOf(
            SessionSubAgentRecoveryRunState(
              submission = AgentRunSubmission(
                sessionId = sessionId,
                runId = syntheticSubAgentRecoveryRunId(queuedTaskId),
                taskId = queuedTaskId,
                acceptedAtEpochMs = 2_000L,
              ),
              lastResult = null,
            ),
            SessionSubAgentRecoveryRunState(
              submission = AgentRunSubmission(
                sessionId = sessionId,
                runId = syntheticSubAgentRecoveryRunId(suspendedTaskId),
                taskId = suspendedTaskId,
                acceptedAtEpochMs = 3_000L,
              ),
              lastResult = awaitingManualResumeSubAgentResult(suspendedTaskId),
            ),
          )
        },
      ),
      isAwaitingManualResume = { result ->
        result.errorCode == "APPROVAL_REQUIRED"
      },
    )

    scheduler.restorePersistedVisibleTasks()

    assertEquals(listOf(queuedTaskId, suspendedTaskId), recoveryOperations.restoredTaskIds)
    assertEquals(
      listOf(AgentTaskState.QUEUED, AgentTaskState.SUSPENDED),
      recoveryOperations.restoredTaskStates,
    )
  }

  @Test
  fun restorePersistedVisibleTasksDoesNotTreatApprovalPausedHandleAsQueued() {
    val sessionId = "session-scheduler"
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-paused",
        parentRunId = "parent-run-paused",
      ).copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.WAITING_APPROVAL,
          continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
          resumable = true,
          requiresUserAction = true,
          isHighRisk = false,
          headline = "Waiting for approval.",
        ),
        pendingApprovalResume = SubAgentApprovalResume(
          approvedToolName = "shell_command",
          promptResumeState = OpenCrayPromptResumeState(
            turnIndex = 0,
            toolCallCount = 0,
          ),
          agentId = "child-paused",
          childRunId = "child-run-child-paused",
          childTaskId = "child-task-child-paused",
        ),
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations(sessionId)
    val queuedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-paused",
      parentRunId = "parent-run-paused",
    )
    val scheduler = SessionOwnedSubAgentScheduler(
      sessionId = sessionId,
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentSchedulerCallbacks(
        persistedRecoveryRunStates = {
          listOf(
            SessionSubAgentRecoveryRunState(
              submission = AgentRunSubmission(
                sessionId = sessionId,
                runId = syntheticSubAgentRecoveryRunId(queuedTaskId),
                taskId = queuedTaskId,
                acceptedAtEpochMs = 2_000L,
              ),
              lastResult = null,
            ),
          )
        },
      ),
      isAwaitingManualResume = { result ->
        result.errorCode == "APPROVAL_REQUIRED"
      },
    )

    scheduler.restorePersistedVisibleTasks()

    assertEquals(emptyList<String>(), recoveryOperations.restoredTaskIds)
    assertEquals(emptyList<AgentTaskState>(), recoveryOperations.restoredTaskStates)
  }
}
