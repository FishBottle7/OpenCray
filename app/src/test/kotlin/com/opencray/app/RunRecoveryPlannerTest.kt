package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableModelAction
import com.opencray.runtime.OpenCraySerializableToolCall
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCrayToolCallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
  fun durableTerminalResultTakesPriorityOverStaleResumableCheckpoint() {
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
          durableResult = ExecutionResult(
            taskId = "task-1",
            status = ExecutionStatus.SUCCESS,
            stdout = "Final answer",
            startedAtEpochMs = 50L,
            finishedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESTORE_TERMINAL_RESULT, plan.action)
    assertEquals("durable_terminal_result_persisted", plan.reasonCode)
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, plan.checkpointKind)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun finalizationCheckpointWithDurableTerminalResultRestoresTerminalState() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.FINALIZATION_COMPLETE,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          durableResult = ExecutionResult(
            taskId = "task-1",
            status = ExecutionStatus.SUCCESS,
            stdout = "Final answer",
            startedAtEpochMs = 50L,
            finishedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESTORE_TERMINAL_RESULT, plan.action)
    assertEquals("durable_terminal_result_persisted", plan.reasonCode)
    assertEquals(PromptCheckpointKind.FINALIZATION_COMPLETE, plan.checkpointKind)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun finalizationCheckpointWithoutDurableTerminalResultDoesNotPlanResume() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun(),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.FINALIZATION_COMPLETE,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("finalization_checkpoint_missing_terminal_result", plan.reasonCode)
    assertEquals(PromptCheckpointKind.FINALIZATION_COMPLETE, plan.checkpointKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun pausedLlmRetryCheckpointStaysWaitingForUserResume() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            executionStatus = ExecutionStatus.FAILED,
            errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
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

    assertEquals(RunRecoveryAction.RESUME_WAITING_FOR_USER, plan.action)
    assertEquals("llm_retry_exhausted_waiting_for_resume", plan.reasonCode)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun suspendedApprovedResumeCheckpointStaysWaitingForManualResume() {
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
            checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.APPROVED,
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_WAITING_FOR_USER, plan.action)
    assertEquals("approval_granted_waiting_for_manual_resume", plan.reasonCode)
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, plan.checkpointKind)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun suspendedRejectedResumeCheckpointStopsRunUntilNewInstruction() {
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
            checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.REJECTED,
        ),
      ),
    )

    assertEquals(RunRecoveryAction.STOP_REJECTED_AWAITING_DIRECTION, plan.action)
    assertEquals("approval_already_rejected_waiting_for_instruction", plan.reasonCode)
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, plan.checkpointKind)
    assertFalse(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun preModelRequestCheckpointPlansResumeFromCheckpoint() {
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
            checkpointKind = PromptCheckpointKind.PRE_MODEL_REQUEST,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_FROM_CHECKPOINT, plan.action)
    assertEquals("durable_pre_model_request_checkpoint", plan.reasonCode)
    assertEquals(PromptCheckpointKind.PRE_MODEL_REQUEST, plan.checkpointKind)
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
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
  }

  @Test
  fun interruptedRestoreManagedProcessObservationCheckpointAutoResumesFromCheckpoint() {
    val processId = "proc-live"
    val checkpoint = PersistedPromptCheckpoint(
      sessionId = "session-1",
      runId = "run-1",
      taskId = "task-1",
      checkpointId = "checkpoint-1",
      checkpointKind = PromptCheckpointKind.COMMENTARY_EMITTED,
      createdAtEpochMs = 100L,
      updatedAtEpochMs = 100L,
      toolName = "ProcessWait",
      promptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
        pendingActions = listOf(
          OpenCraySerializableModelAction.ToolCall(
            call = OpenCraySerializableToolCall(
              id = "oc-call-1",
              toolName = "ProcessWait",
              arguments = JsonObject(
                mapOf(
                  "process_id" to JsonPrimitive(processId),
                  "timeout_ms" to JsonPrimitive("250"),
                ),
              ),
            ),
          ),
        ),
        nextActionIndex = 0,
      ),
    )
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun().copy(
            hasLiveManagedProcesses = true,
            hasAutoResumeEligibleManagedProcesses = true,
          ),
          checkpoint = checkpoint,
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 0,
            call = AgentToolCall(
              id = "oc-call-1",
              toolName = "ProcessWait",
              arguments = JsonObject(
                mapOf(
                  "process_id" to JsonPrimitive(processId),
                  "timeout_ms" to JsonPrimitive("250"),
                ),
              ),
            ),
            emittedAtEpochMs = 150L,
          ),
        ),
      ),
    )

    assertEquals(RunRecoveryAction.RESUME_FROM_CHECKPOINT, plan.action)
    assertEquals("managed_process_observation_checkpoint_resume", plan.reasonCode)
    assertEquals(PromptCheckpointKind.COMMENTARY_EMITTED, plan.checkpointKind)
    assertEquals("tool_call", plan.journalTailKind)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun checkpointReplayTakesPriorityOverLiveManagedProcess() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            hasLiveManagedProcesses = true,
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
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, plan.checkpointKind)
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
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
  fun rejectedResumeCheckpointStopsRunAndWaitsForNewInstruction() {
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
            checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
          ),
          approvalState = AgentTaskApprovalState.REJECTED,
        ),
      ),
    )

    assertEquals(RunRecoveryAction.STOP_REJECTED_AWAITING_DIRECTION, plan.action)
    assertEquals("approval_already_rejected_waiting_for_instruction", plan.reasonCode)
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, plan.checkpointKind)
    assertFalse(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
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
  fun interruptedRestoreWithStaleGeneralResumeCheckpointAndResumedSubAgentRequiresExplicitRecovery() {
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
            phase = OpenCraySubAgentPhase.RESUMED,
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
    assertEquals("subagent_resumed", plan.journalTailKind)
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
  fun untouchedQueuedRunWithoutCheckpointDoesNotNeedRecoveryPlan() {
    val plan = planner.plan(
      RunRecoveryPlannerInput(
        run = runSnapshot(
          lifecycleState = QueueTaskLifecycleState.QUEUED,
        ),
      ),
    )

    assertEquals(null, plan)
  }

  @Test
  fun queuedRunWithPriorProgressWithoutCheckpointRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.QUEUED,
            executionOrdinal = 1,
            pendingExecutionKind = "approval_resume",
          ),
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
    assertEquals("queued_progress_without_checkpoint", plan.reasonCode)
    assertEquals("tool_call", plan.journalTailKind)
    assertTrue(plan.requiresUserAction)
    assertFalse(plan.safeToAutoResume)
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
    hasAutoResumeEligibleManagedProcesses: Boolean = false,
    diagnostics: RunLifecycleDiagnostics = RunLifecycleDiagnostics(),
    executionOrdinal: Int = 0,
    pendingExecutionKind: String? = null,
    executionKind: String? = null,
  ): AgentRunSnapshot = AgentRunSnapshot(
    sessionId = "session-1",
    runId = "run-1",
    taskId = "task-1",
    acceptedAtEpochMs = 0L,
    updatedAtEpochMs = 0L,
    lifecycleState = lifecycleState,
    taskState = null,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    pendingExecutionKind = pendingExecutionKind,
    executionStatus = executionStatus,
    errorCode = errorCode,
    hasLiveManagedProcesses = hasLiveManagedProcesses,
    hasAutoResumeEligibleManagedProcesses = hasAutoResumeEligibleManagedProcesses,
    lifecycleDiagnostics = diagnostics,
  )
}
