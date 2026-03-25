package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent

internal enum class RunRecoveryAction {
  RESUME_FROM_CHECKPOINT,
  RESUME_WAITING_FOR_APPROVAL,
  RESUME_RECONNECT_PROCESS,
  INTERRUPT_RECOVERY_REQUIRED,
  LEGACY_REQUEUE,
}

internal data class RunRecoveryPlannerInput(
  val run: AgentRunSnapshot,
  val checkpoint: PersistedPromptCheckpoint? = null,
  val lastJournalEvent: OpenCrayAgentRunEvent? = null,
  val approvalState: AgentTaskApprovalState? = null,
)

internal data class RunRecoveryPlan(
  val action: RunRecoveryAction,
  val reasonCode: String,
  val summary: String,
  val safeToAutoResume: Boolean,
  val requiresUserAction: Boolean,
  val checkpointKind: PromptCheckpointKind? = null,
  val approvalState: AgentTaskApprovalState? = null,
  val journalTailKind: String? = null,
) {
  fun toMap(): Map<String, Any?> = buildMap {
    put("action", action.name.lowercase())
    put("reasonCode", reasonCode)
    put("summary", summary)
    put("safeToAutoResume", safeToAutoResume)
    put("requiresUserAction", requiresUserAction)
    checkpointKind?.let { put("checkpointKind", it.name.lowercase()) }
    approvalState?.let { put("approvalState", it.name.lowercase()) }
    journalTailKind?.let { put("journalTailKind", it) }
  }
}

internal class RunRecoveryPlanner {
  fun plan(input: RunRecoveryPlannerInput): RunRecoveryPlan? {
    val checkpoint = input.checkpoint
    val run = input.run
    val journalTailKind = recoveryJournalTailKind(input.lastJournalEvent)

    if (
      isInterruptedRestoreRun(run) &&
      checkpoint != null &&
      checkpointSupersededByUncertainInFlightAction(
        checkpoint = checkpoint,
        event = input.lastJournalEvent,
      )
    ) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
        reasonCode = "uncertain_inflight_mutation",
        summary = "The host was rebuilt after an in-flight action advanced beyond the last durable checkpoint. Recovery should stop and require explicit user or model intervention.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (
      checkpoint?.checkpointKind == PromptCheckpointKind.GENERAL_RESUME ||
      checkpoint?.checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME ||
      checkpoint?.checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME
    ) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_FROM_CHECKPOINT,
        reasonCode = when (checkpoint.checkpointKind) {
          PromptCheckpointKind.GENERAL_RESUME -> "durable_general_resume_checkpoint"
          PromptCheckpointKind.APPROVED_PENDING_RESUME -> "approval_already_granted_resume_pending"
          PromptCheckpointKind.REJECTED_PENDING_RESUME -> "approval_already_rejected_resume_pending"
          PromptCheckpointKind.WAITING_APPROVAL -> "approval_waiting_checkpoint"
        },
        summary = when (checkpoint.checkpointKind) {
          PromptCheckpointKind.GENERAL_RESUME ->
            "The run has a durable general resume checkpoint and should continue from that checkpoint instead of rerunning from task input."
          PromptCheckpointKind.APPROVED_PENDING_RESUME,
          PromptCheckpointKind.REJECTED_PENDING_RESUME,
          PromptCheckpointKind.WAITING_APPROVAL,
          ->
            "The run has a durable post-approval checkpoint and should continue from that checkpoint instead of rerunning from task input."
        },
        safeToAutoResume = true,
        requiresUserAction = false,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (checkpoint?.checkpointKind == PromptCheckpointKind.WAITING_APPROVAL || isApprovalWaitingRun(run)) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_WAITING_FOR_APPROVAL,
        reasonCode = "approval_waiting_checkpoint",
        summary = "The run reached a durable approval boundary and should return to waiting for user approval.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint?.checkpointKind ?: PromptCheckpointKind.WAITING_APPROVAL,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (run.hasLiveManagedProcesses) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_RECONNECT_PROCESS,
        reasonCode = "live_managed_process_detected",
        summary = "A managed process is still live for this run, so recovery should reconnect to that process instead of replaying the task.",
        safeToAutoResume = true,
        requiresUserAction = false,
        checkpointKind = checkpoint?.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (isInterruptedRestoreRun(run)) {
      if (isUncertainInFlightAction(input.lastJournalEvent)) {
        return RunRecoveryPlan(
          action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
          reasonCode = "uncertain_inflight_mutation",
          summary = "The host was rebuilt after an in-flight action without a safe resume checkpoint. Recovery should stop and require explicit user or model intervention.",
          safeToAutoResume = false,
          requiresUserAction = true,
          checkpointKind = checkpoint?.checkpointKind,
          approvalState = input.approvalState,
          journalTailKind = journalTailKind,
        )
      }
      return RunRecoveryPlan(
        action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
        reasonCode = "no_recoverable_checkpoint_after_restore",
        summary = "The run was interrupted during restore and no durable checkpoint is available. Keep it interrupted until the user explicitly decides how to continue.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint?.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (run.lifecycleState == QueueTaskLifecycleState.QUEUED && !run.isTerminal) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.LEGACY_REQUEUE,
        reasonCode = "queued_without_checkpoint",
        summary = "The run is queued without a durable prompt checkpoint, so current behavior remains task-level execution from input.",
        safeToAutoResume = false,
        requiresUserAction = false,
        checkpointKind = checkpoint?.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    return null
  }

  private fun isApprovalWaitingRun(run: AgentRunSnapshot): Boolean =
    run.executionStatus == ExecutionStatus.DENIED &&
      (
        run.errorCode == ERROR_APPROVAL_REQUIRED ||
          run.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED
        )

  private fun isInterruptedRestoreRun(run: AgentRunSnapshot): Boolean =
    run.errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      run.lifecycleDiagnostics.recoveryReason ==
      com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED ||
      run.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE

  private fun isUncertainInFlightAction(event: OpenCrayAgentRunEvent?): Boolean = when (event) {
    is OpenCrayToolCallEvent -> true
    is OpenCraySubAgentEvent -> event.phase == OpenCraySubAgentPhase.STARTED
    is OpenCrayLifecycleEvent -> event.phase == com.opencray.runtime.OpenCrayRunLifecyclePhase.START
    is OpenCrayToolResultEvent -> false
    else -> false
  }

  private fun checkpointSupersededByUncertainInFlightAction(
    checkpoint: PersistedPromptCheckpoint,
    event: OpenCrayAgentRunEvent?,
  ): Boolean {
    if (event == null || event.emittedAtEpochMs < checkpoint.updatedAtEpochMs) {
      return false
    }
    return when (event) {
      is OpenCrayToolCallEvent -> true
      is OpenCraySubAgentEvent -> event.phase == OpenCraySubAgentPhase.STARTED
      else -> false
    }
  }

  private fun recoveryJournalTailKind(event: OpenCrayAgentRunEvent?): String? = when (event) {
    is OpenCrayToolCallEvent -> "tool_call"
    is OpenCrayToolResultEvent -> "tool_result"
    is OpenCraySubAgentEvent -> "subagent_${event.phase.name.lowercase()}"
    is OpenCrayLifecycleEvent -> "lifecycle_${event.phase.name.lowercase()}"
    null -> null
    else -> event::class.java.simpleName
      .removeSuffix("Event")
      .replace(Regex("([a-z])([A-Z])"), "$1_$2")
      .lowercase()
  }

  private companion object {
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
  }
}
