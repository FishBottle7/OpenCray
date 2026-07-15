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
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
  fun exhaustedCheckpointResumeBudgetRequiresExplicitRetry() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.QUEUED,
            diagnostics = RunLifecycleDiagnostics(
              checkpointResumeAttemptCount =
                DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
            ),
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

    assertEquals(RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED, plan.action)
    assertEquals("automatic_checkpoint_resume_budget_exhausted", plan.reasonCode)
    assertEquals(
      DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
      plan.checkpointResumeAttemptCount,
    )
    assertEquals(
      DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
      plan.maxAutomaticCheckpointResumeAttempts,
    )
    assertEquals(
      DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
      plan.toMap()["checkpointResumeAttemptCount"],
    )
    assertTrue(plan.requiresUserAction)
    assertFalse(plan.safeToAutoResume)
  }

  @Test
  fun durableTerminalResultTakesPriorityOverStaleResumableCheckpoint() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.FAILED,
            errorCode = "RESTART_REQUIRES_EXPLICIT_RETRY",
            diagnostics = RunLifecycleDiagnostics(
              recoveryReason =
                com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED,
              checkpointResumeAttemptCount =
                DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
            ),
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
    assertEquals(ManagedProcessContinuationBases.RECONNECT_HOLD, plan.managedProcessContinuationBasis)
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
            managedProcesses = listOf(
              ManagedProcessSnapshot(
                processId = processId,
                taskId = "task-1",
                command = "server",
                status = ManagedProcessStatus.RUNNING,
                processStarted = true,
                timeoutMs = 300_000L,
                startedAtEpochMs = 90L,
                updatedAtEpochMs = 150L,
                reconnectState = ManagedProcessReconnectState(
                  status = "attached",
                  recoveryState = "attached_live",
                  retryable = false,
                  attemptCount = 1,
                ),
                metadata = mapOf(
                  MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY to
                    ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
                  MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY to
                    ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
                ),
              ),
            ),
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
    assertEquals(ManagedProcessContinuationBases.CHECKPOINT_RESUME, plan.managedProcessContinuationBasis)
    assertEquals(
      ManagedProcessContinuationBases.CHECKPOINT_RESUME,
      plan.toMap()["managedProcessContinuationBasis"],
    )
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      plan.managedProcessRestoreScope,
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      plan.managedProcessRestoreDecision,
    )
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      plan.toMap()["managedProcessRestoreScope"],
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      plan.toMap()["managedProcessRestoreDecision"],
    )
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
  }

  @Test
  fun stableReconnectHoldManagedProcessObservationPromotesToCheckpointResume() {
    val processId = "proc-reconnected"
    val checkpoint = PersistedPromptCheckpoint(
      sessionId = "session-1",
      runId = "run-1",
      taskId = "task-1",
      checkpointId = "checkpoint-reconnect-hold",
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
              id = "oc-call-reconnect-hold",
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
          run = runSnapshot(
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            hasLiveManagedProcesses = true,
            hasAutoResumeEligibleManagedProcesses = true,
            diagnostics = RunLifecycleDiagnostics(
              managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
            ),
          ).copy(
            managedProcesses = listOf(
              ManagedProcessSnapshot(
                processId = processId,
                taskId = "task-1",
                command = "server",
                status = ManagedProcessStatus.RUNNING,
                processStarted = true,
                timeoutMs = 300_000L,
                startedAtEpochMs = 90L,
                updatedAtEpochMs = 150L,
                reconnectState = ManagedProcessReconnectState(
                  status = "attached",
                  recoveryState = "attached_live",
                  retryable = false,
                  attemptCount = 2,
                ),
              ),
            ),
          ),
          checkpoint = checkpoint,
          lastJournalEvent = OpenCrayToolCallEvent(
            runId = "run-1",
            taskId = "task-1",
            turn = 0,
            call = AgentToolCall(
              id = "oc-call-reconnect-hold",
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
    assertTrue(plan.safeToAutoResume)
    assertFalse(plan.requiresUserAction)
    assertEquals(ManagedProcessContinuationBases.CHECKPOINT_RESUME, plan.managedProcessContinuationBasis)
  }

  @Test
  fun interruptedRestoreManagedProcessObservationReconnectBackoffPlansReconnectState() {
    val processId = "proc-retry"
    val checkpoint = PersistedPromptCheckpoint(
      sessionId = "session-1",
      runId = "run-1",
      taskId = "task-1",
      checkpointId = "checkpoint-1",
      checkpointKind = PromptCheckpointKind.COMMENTARY_EMITTED,
      createdAtEpochMs = 100L,
      updatedAtEpochMs = 100L,
      toolName = "ProcessWait",
    )
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun().copy(
            hasLiveManagedProcesses = true,
            hasAutoResumeEligibleManagedProcesses = false,
            managedProcesses = listOf(
              ManagedProcessSnapshot(
                processId = processId,
                taskId = "task-1",
                command = "server",
                status = ManagedProcessStatus.RUNNING,
                processStarted = true,
                timeoutMs = 300_000L,
                startedAtEpochMs = 90L,
                updatedAtEpochMs = 150L,
                reconnectState = ManagedProcessReconnectState(
                  status = "connecting",
                  recoveryState = "retry_scheduled",
                  retryable = true,
                  retryAfterEpochMs = 9_000L,
                  attemptCount = 2,
                ),
                metadata = mapOf(
                  MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY to
                    ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
                  MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY to
                    ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
                ),
              ),
            ),
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

    assertEquals(RunRecoveryAction.RESUME_RECONNECT_PROCESS, plan.action)
    assertEquals("managed_process_observation_reconnect_pending", plan.reasonCode)
    assertEquals(PromptCheckpointKind.COMMENTARY_EMITTED, plan.checkpointKind)
    assertEquals("tool_call", plan.journalTailKind)
    assertEquals(listOf(processId), plan.managedProcessReconnectProcessIds)
    assertEquals("connecting", plan.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", plan.managedProcessReconnectRecoveryState)
    assertEquals(9_000L, plan.managedProcessReconnectRetryAfterEpochMs)
    assertEquals(2, plan.managedProcessReconnectAttemptCount)
    assertEquals(ManagedProcessContinuationBases.RECONNECT_HOLD, plan.managedProcessContinuationBasis)
    assertEquals(ManagedProcessRestoreScope.CROSS_PROCESS.wireValue, plan.managedProcessRestoreScope)
    assertEquals(ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue, plan.managedProcessRestoreDecision)
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
    assertEquals(
      listOf(processId),
      plan.toMap()["managedProcessReconnectProcessIds"],
    )
    assertEquals("retry_scheduled", plan.toMap()["managedProcessReconnectRecoveryState"])
    assertEquals(
      ManagedProcessContinuationBases.RECONNECT_HOLD,
      plan.toMap()["managedProcessContinuationBasis"],
    )
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      plan.toMap()["managedProcessRestoreScope"],
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      plan.toMap()["managedProcessRestoreDecision"],
    )
  }

  @Test
  fun interruptedRestoreManagedProcessObservationUsesNewerMetadataReconnectEvidence() {
    val processId = "proc-attached"
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun().copy(
            hasLiveManagedProcesses = true,
            hasAutoResumeEligibleManagedProcesses = false,
            managedProcesses = listOf(
              ManagedProcessSnapshot(
                processId = processId,
                taskId = "task-1",
                command = "server",
                status = ManagedProcessStatus.RUNNING,
                processStarted = true,
                timeoutMs = 300_000L,
                startedAtEpochMs = 90L,
                updatedAtEpochMs = 150L,
                reconnectState = ManagedProcessReconnectState(
                  status = "connecting",
                  recoveryState = "retry_scheduled",
                  retryable = true,
                  retryAfterEpochMs = 9_000L,
                  attemptCount = 1,
                ),
                metadata = mapOf(
                  "sandboxCommandReconnectStatus" to "attached",
                  "sandboxCommandReconnectRecoveryState" to "attached_live",
                  "sandboxCommandReconnectRetryable" to "false",
                  "sandboxCommandReconnectAttemptCount" to "2",
                  MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY to
                    ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
                  MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY to
                    ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
                ),
              ),
            ),
          ),
          checkpoint = PersistedPromptCheckpoint(
            sessionId = "session-1",
            runId = "run-1",
            taskId = "task-1",
            checkpointId = "checkpoint-1",
            checkpointKind = PromptCheckpointKind.COMMENTARY_EMITTED,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 100L,
            toolName = "ProcessWait",
          ),
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

    assertEquals(RunRecoveryAction.RESUME_RECONNECT_PROCESS, plan.action)
    assertEquals(listOf(processId), plan.managedProcessReconnectProcessIds)
    assertEquals("attached", plan.managedProcessReconnectStatus)
    assertEquals("attached_live", plan.managedProcessReconnectRecoveryState)
    assertNull(plan.managedProcessReconnectRetryAfterEpochMs)
    assertEquals(2, plan.managedProcessReconnectAttemptCount)
    assertEquals(ManagedProcessContinuationBases.LIVE_REATTACH, plan.managedProcessContinuationBasis)
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      plan.managedProcessRestoreScope,
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      plan.managedProcessRestoreDecision,
    )
  }

  @Test
  fun interruptedRestoreWithLiveManagedProcessAndMutatingToolCallStillRequiresExplicitRecovery() {
    val plan = requireNotNull(
      planner.plan(
        RunRecoveryPlannerInput(
          run = interruptedRestoreRun().copy(
            hasLiveManagedProcesses = true,
            managedProcesses = listOf(
              ManagedProcessSnapshot(
                processId = "proc-live",
                taskId = "task-1",
                command = "server",
                status = ManagedProcessStatus.RUNNING,
                processStarted = true,
                timeoutMs = 300_000L,
                startedAtEpochMs = 90L,
                updatedAtEpochMs = 150L,
              ),
            ),
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
    assertFalse(plan.safeToAutoResume)
    assertTrue(plan.requiresUserAction)
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
