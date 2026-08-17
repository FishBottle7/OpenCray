package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecision
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSubAgentActorDriverTest {
  @Test
  fun scheduleRecoverableSubAgentsEnsuresQueuedHandleOnlyOnce() {
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-1",
        parentRunId = "parent-run-1",
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations("session-scheduler")
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { emptySet() },
        approvedRecoveryTaskIds = { emptySet() },
        rejectedRecoveryTaskIds = { emptySet() },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = "session-scheduler",
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val firstScheduled = driver.scheduleRecoverableSubAgents()
    val secondScheduled = driver.scheduleRecoverableSubAgents()

    assertEquals(1, firstScheduled)
    assertEquals(0, secondScheduled)
    assertEquals(
      listOf("parent-run-1:child-1"),
      backgroundExecution.submittedKeys.map(keyLabel),
    )
    assertTrue(backgroundExecution.resumedKeys.isEmpty())
    assertTrue(backgroundExecution.cancelledKeys.isEmpty())
    assertTrue(recoveryOperations.submittedTaskIds.isEmpty())
  }

  @Test
  fun scheduleRecoverableSubAgentsSkipsHandleWhenParentRunIsStillActive() {
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-1",
        parentRunId = "parent-run-1",
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations("session-scheduler")
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { setOf("parent-run-1") },
        approvedRecoveryTaskIds = { emptySet() },
        rejectedRecoveryTaskIds = { emptySet() },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = "session-scheduler",
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val scheduled = driver.scheduleRecoverableSubAgents()

    assertEquals(0, scheduled)
    assertTrue(backgroundExecution.submittedKeys.isEmpty())
    assertTrue(backgroundExecution.resumedKeys.isEmpty())
    assertTrue(backgroundExecution.cancelledKeys.isEmpty())
    assertTrue(recoveryOperations.submittedTaskIds.isEmpty())
    assertTrue(recoveryOperations.listTasks().isEmpty())
  }

  @Test
  fun scheduleRecoverableSubAgentsSkipsApprovalPausedHandleWithoutDecision() {
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-paused",
        parentRunId = "parent-run-paused",
      ).copy(
        snapshot = com.opencray.runtime.subagent.SubAgentExecutionSnapshot(
          state = com.opencray.runtime.subagent.SubAgentExecutionState.WAITING_APPROVAL,
          continuationKind = com.opencray.runtime.subagent.SubAgentContinuationKind.PROMPT_RESUME,
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
    val recoveryOperations = RecordingSubAgentRecoveryOperations("session-scheduler")
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { emptySet() },
        approvedRecoveryTaskIds = { emptySet() },
        rejectedRecoveryTaskIds = { emptySet() },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = "session-scheduler",
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val scheduled = driver.scheduleRecoverableSubAgents()

    assertEquals(0, scheduled)
    assertTrue(backgroundExecution.submittedKeys.isEmpty())
    assertTrue(backgroundExecution.resumedKeys.isEmpty())
    assertTrue(backgroundExecution.cancelledKeys.isEmpty())
    assertTrue(recoveryOperations.submittedTaskIds.isEmpty())
  }

  @Test
  fun onSessionResumedStartsChildActorThenWakesPersistedQueuedRecoveryShell() {
    val sessionId = "session-scheduler"
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-1",
        parentRunId = "parent-run-1",
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations(sessionId)
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val persistedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-1",
      parentRunId = "parent-run-1",
    )
    recoveryOperations.restorePersistedTask(
      submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = syntheticSubAgentRecoveryRunId(persistedTaskId),
        taskId = persistedTaskId,
        acceptedAtEpochMs = 2_000L,
      ),
      task = syntheticSubAgentRecoveryWaitTask(
        sessionId = sessionId,
        agentId = "child-1",
        parentRunId = "parent-run-1",
        taskId = persistedTaskId,
        createdAtEpochMs = 2_000L,
        metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        ),
      ),
    )
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { emptySet() },
        approvedRecoveryTaskIds = { emptySet() },
        rejectedRecoveryTaskIds = { emptySet() },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = sessionId,
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val scheduled = driver.onSessionResumed()

    assertEquals(1, scheduled)
    assertEquals(listOf("parent-run-1:child-1"), backgroundExecution.submittedKeys.map(keyLabel))
    assertTrue(backgroundExecution.resumedKeys.isEmpty())
    assertTrue(backgroundExecution.cancelledKeys.isEmpty())
    assertEquals(listOf(persistedTaskId), recoveryOperations.wokenTaskIds)
    assertTrue(recoveryOperations.submittedTaskIds.isEmpty())
  }

  @Test
  fun onSessionResumedStartsApprovedChildActorThenWakesSuspendedRecoveryShell() {
    val sessionId = "session-scheduler"
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-approved",
        parentRunId = "parent-run-approved",
      ).copy(
        pendingApprovalDecision = pendingApprovalDecision(
          agentId = "child-approved",
          childRunId = "child-run-child-approved",
          childTaskId = "child-task-child-approved",
          approved = true,
        ),
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations(sessionId)
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val approvedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-approved",
      parentRunId = "parent-run-approved",
    )
    recoveryOperations.restorePersistedTask(
      submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = syntheticSubAgentRecoveryRunId(approvedTaskId),
        taskId = approvedTaskId,
        acceptedAtEpochMs = 2_000L,
      ),
      task = syntheticSubAgentRecoveryWaitTask(
        sessionId = sessionId,
        agentId = "child-approved",
        parentRunId = "parent-run-approved",
        taskId = approvedTaskId,
        createdAtEpochMs = 2_000L,
        metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        ),
      ).copy(
        state = AgentTaskState.SUSPENDED,
      ),
    )
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { emptySet() },
        approvedRecoveryTaskIds = { setOf(approvedTaskId) },
        rejectedRecoveryTaskIds = { emptySet() },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = sessionId,
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val scheduled = driver.onSessionResumed()

    assertEquals(1, scheduled)
    assertEquals(
      listOf("parent-run-approved:child-approved"),
      backgroundExecution.submittedKeys.map(keyLabel),
    )
    assertEquals(
      listOf("parent-run-approved:child-approved"),
      backgroundExecution.resumedKeys.map(keyLabel),
    )
    assertTrue(backgroundExecution.cancelledKeys.isEmpty())
    assertEquals(listOf(approvedTaskId), recoveryOperations.wokenTaskIds)
    assertTrue(recoveryOperations.submittedTaskIds.isEmpty())
  }

  @Test
  fun onSessionResumedStartsRejectedChildActorThenCancelsSuspendedRecoveryShell() {
    val sessionId = "session-scheduler"
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.upsertHandle(
      queuedSubAgentHandle(
        agentId = "child-rejected",
        parentRunId = "parent-run-rejected",
      ).copy(
        pendingApprovalDecision = pendingApprovalDecision(
          agentId = "child-rejected",
          childRunId = "child-run-child-rejected",
          childTaskId = "child-task-child-rejected",
          approved = false,
        ),
      ),
    )
    val recoveryOperations = RecordingSubAgentRecoveryOperations(sessionId)
    val backgroundExecution = RecordingSubAgentBackgroundExecution()
    val rejectedTaskId = syntheticSubAgentRecoveryTaskId(
      sessionId = sessionId,
      agentId = "child-rejected",
      parentRunId = "parent-run-rejected",
    )
    recoveryOperations.restorePersistedTask(
      submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = syntheticSubAgentRecoveryRunId(rejectedTaskId),
        taskId = rejectedTaskId,
        acceptedAtEpochMs = 2_000L,
      ),
      task = syntheticSubAgentRecoveryWaitTask(
        sessionId = sessionId,
        agentId = "child-rejected",
        parentRunId = "parent-run-rejected",
        taskId = rejectedTaskId,
        createdAtEpochMs = 2_000L,
        metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        ),
      ).copy(
        state = AgentTaskState.SUSPENDED,
      ),
    )
    val driver = SessionOwnedSubAgentActorDriver(
      handles = coordinator::allHandles,
      recoveryOperations = recoveryOperations,
      callbacks = SessionSubAgentActorDriverCallbacks(
        activeParentRunIds = { emptySet() },
        approvedRecoveryTaskIds = { emptySet() },
        rejectedRecoveryTaskIds = { setOf(rejectedTaskId) },
        recoveryTaskIdForHandle = { handle ->
          syntheticSubAgentRecoveryTaskId(
            sessionId = sessionId,
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          )
        },
        submitActorTask = backgroundExecution::submit,
        resumeActorTask = backgroundExecution::resume,
        cancelActorTask = backgroundExecution::cancel,
      ),
    )

    val scheduled = driver.onSessionResumed()

    assertEquals(1, scheduled)
    assertEquals(
      listOf("parent-run-rejected:child-rejected"),
      backgroundExecution.submittedKeys.map(keyLabel),
    )
    assertTrue(backgroundExecution.resumedKeys.isEmpty())
    assertEquals(
      listOf("parent-run-rejected:child-rejected"),
      backgroundExecution.cancelledKeys.map(keyLabel),
    )
    assertEquals(listOf(rejectedTaskId), recoveryOperations.cancelledTaskIds)
    assertTrue(recoveryOperations.wokenTaskIds.isEmpty())
    assertTrue(recoveryOperations.listTasks().isEmpty())
  }
}

private val keyLabel: (com.opencray.runtime.subagent.SubAgentExecutionKey) -> String = { key ->
  "${key.parentRunId}:${key.agentId}"
}

private fun pendingApprovalDecision(
  agentId: String,
  childRunId: String,
  childTaskId: String,
  approved: Boolean,
): SubAgentPendingApprovalDecision = SubAgentPendingApprovalDecision(
  state = if (approved) {
    SubAgentPendingApprovalDecisionState.APPROVED
  } else {
    SubAgentPendingApprovalDecisionState.REJECTED
  },
  resume = SubAgentApprovalResume(
    approvedToolName = "shell_command",
    promptResumeState = OpenCrayPromptResumeState(
      turnIndex = 0,
      toolCallCount = 0,
    ),
    agentId = agentId,
    childRunId = childRunId,
    childTaskId = childTaskId,
  ),
  recordedAtEpochMs = 2_500L,
)
