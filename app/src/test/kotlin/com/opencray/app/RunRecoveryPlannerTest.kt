package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCrayToolCallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecoveryPlannerTest {
  private val planner = RunRecoveryPlanner()

  @Test
  fun waitingApprovalCheckpointPlansResumeWaitingForApproval() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            executionStatus = ExecutionStatus.DENIED,
            errorCode = "APPROVAL_REQUIRED",
          ),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_WAITING_FOR_APPROVAL, plan.action)
    assertTrue(plan.requiresUserAction)
    assertFalse(plan.safeToAutoResume)
  }

  @Test
  fun approvedResumeCheckpointPlansResumeFromCheckpoint() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.QUEUED,
            executionStatus = ExecutionStatus.DENIED,
            errorCode = "APPROVAL_REQUIRED",
          ),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.APPROVED,
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_FROM_CHECKPOINT, plan.action)
    assertEquals("approval_already_granted_resume_pending", plan.reasonCode)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun generalResumeCheckpointPlansResumeFromCheckpoint() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.QUEUED,
          ),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_FROM_CHECKPOINT, plan.action)
    assertEquals("durable_general_resume_checkpoint", plan.reasonCode)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun liveManagedProcessPlansReconnect() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            hasLiveManagedProcesses = true,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_RECONNECT_PROCESS, plan.action)
    assertEquals("live_managed_process_detected", plan.reasonCode)
  }

  @Test
  fun interruptedRestoreWithInFlightToolCallRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.FAILED,
            errorCode = "RESTART_REQUIRES_EXPLICIT_RETRY",
            diagnostics = RunLifecycleDiagnostics(
              recoveryReason = com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED,
            ),
          ),
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 1,
            call = AgentToolCall(toolName = "Bash"),
            emittedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("tool_call", plan.journalTailKind)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreWithStaleGeneralResumeCheckpointAndInFlightToolCallRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 1,
            call = AgentToolCall(toolName = "Write"),
            emittedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("uncertain_inflight_mutation", plan.reasonCode)
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, plan.checkpointKind)
    assertEquals("tool_call", plan.journalTailKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreWithStaleApprovedResumeCheckpointAndInFlightToolCallRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.APPROVED,
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 1,
            call = AgentToolCall(toolName = "Write"),
            emittedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("uncertain_inflight_mutation", plan.reasonCode)
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, plan.checkpointKind)
    assertEquals("tool_call", plan.journalTailKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreWithStaleRejectedResumeCheckpointAndInFlightToolCallRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.REJECTED,
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 1,
            call = AgentToolCall(toolName = "Write"),
            emittedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("uncertain_inflight_mutation", plan.reasonCode)
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, plan.checkpointKind)
    assertEquals("tool_call", plan.journalTailKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreWithStaleGeneralResumeCheckpointAndStartedSubAgentRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          lastJournalEvent = OpenCraySubAgentEvent(
            runId = "run-1",
            taskId = "task-1",
            phase = OpenCraySubAgentPhase.STARTED,
            childRunId = "child-run-1",
            childTaskId = "child-task-1",
            label = "Inspect docs",
            subagentType = "explorer",
            contextMode = "fork",
            depth = 1,
            emittedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("uncertain_inflight_mutation", plan.reasonCode)
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, plan.checkpointKind)
    assertEquals("subagent_started", plan.journalTailKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreWithoutCheckpointStaysInterruptedUntilExplicitDecision() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("no_recoverable_checkpoint_after_restore", plan.reasonCode)
    assertTrue(plan.requiresUserAction)
    assertFalse(plan.safeToAutoResume)
  }

  @Test
  fun queuedRunWithoutCheckpointStillReportsLegacyQueueExecution() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.QUEUED,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.LEGACY_REQUEUE, plan.action)
    assertEquals("queued_without_checkpoint", plan.reasonCode)
  }

  private fun interruptedRestoreRun(): AgentRunSnapshot = runSnapshot(
    lifecycleState = QueueTaskLifecycleState.FAILED,
    errorCode = "RESTART_REQUIRES_EXPLICIT_RETRY",
    diagnostics = RunLifecycleDiagnostics(
      recoveryReason = com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED,
    ),
  )

  private fun runSnapshot(
    lifecycleState: QueueTaskLifecycleState,
    executionStatus: ExecutionStatus? = null,
    errorCode: String? = null,
    hasLiveManagedProcesses: Boolean = false,
    diagnostics: RunLifecycleDiagnostics = RunLifecycleDiagnostics(),
  ): AgentRunSnapshot = AgentRunSnapshot(
    sessionId = "session-1",
    runId = "run-1",
    taskId = "task-1",
    acceptedAtEpochMs = 0L,
    updatedAtEpochMs = 0L,
    lifecycleState = lifecycleState,
    taskState = null,
    executionStatus = executionStatus,
    errorCode = errorCode,
    hasLiveManagedProcesses = hasLiveManagedProcesses,
    lifecycleDiagnostics = diagnostics,
  )
}
