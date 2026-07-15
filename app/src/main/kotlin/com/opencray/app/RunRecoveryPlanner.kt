package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.EXECUTION_KIND_INITIAL
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCraySerializableModelAction
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class RunRecoveryAction {
  RESTORE_TERMINAL_RESULT,
  RESUME_FROM_CHECKPOINT,
  RESUME_WAITING_FOR_APPROVAL,
  RESUME_WAITING_FOR_USER,
  RESUME_RECONNECT_PROCESS,
  STOP_REJECTED_AWAITING_DIRECTION,
  INTERRUPT_RECOVERY_REQUIRED,
}

internal data class RunRecoveryPlannerInput(
  val run: AgentRunSnapshot,
  val checkpoint: PersistedPromptCheckpoint? = null,
  val lastJournalEvent: OpenCrayAgentRunEvent? = null,
  val durableResult: ExecutionResult? = null,
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
  val managedProcessReconnectProcessIds: List<String> = emptyList(),
  val managedProcessReconnectStatus: String? = null,
  val managedProcessReconnectRecoveryState: String? = null,
  val managedProcessReconnectRetryAfterEpochMs: Long? = null,
  val managedProcessReconnectAttemptCount: Int? = null,
  val managedProcessContinuationBasis: String? = null,
  val managedProcessRestoreScope: String? = null,
  val managedProcessRestoreDecision: String? = null,
  val checkpointResumeAttemptCount: Int? = null,
  val maxAutomaticCheckpointResumeAttempts: Int? = null,
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
    if (managedProcessReconnectProcessIds.isNotEmpty()) {
      put("managedProcessReconnectProcessIds", managedProcessReconnectProcessIds)
    }
    managedProcessReconnectStatus?.let { put("managedProcessReconnectStatus", it) }
    managedProcessReconnectRecoveryState?.let { put("managedProcessReconnectRecoveryState", it) }
    managedProcessReconnectRetryAfterEpochMs?.let { put("managedProcessReconnectRetryAfterEpochMs", it) }
    managedProcessReconnectAttemptCount?.let { put("managedProcessReconnectAttemptCount", it) }
    managedProcessContinuationBasis?.let { put("managedProcessContinuationBasis", it) }
    managedProcessRestoreScope?.let { put("managedProcessRestoreScope", it) }
    managedProcessRestoreDecision?.let { put("managedProcessRestoreDecision", it) }
    checkpointResumeAttemptCount?.let { put("checkpointResumeAttemptCount", it) }
    maxAutomaticCheckpointResumeAttempts?.let { put("maxAutomaticCheckpointResumeAttempts", it) }
  }
}

internal class RunRecoveryPlanner(
  private val maxAutomaticCheckpointResumeAttempts: Int =
    DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS,
) {
  init {
    require(maxAutomaticCheckpointResumeAttempts >= 1) {
      "RunRecoveryPlanner maxAutomaticCheckpointResumeAttempts must be >= 1."
    }
  }

  fun plan(input: RunRecoveryPlannerInput): RunRecoveryPlan? {
    val checkpoint = input.checkpoint
    val run = input.run
    val durableResult = input.durableResult
    val journalTailKind = recoveryJournalTailKind(input.lastJournalEvent)

    if (durableResult.isRestorableTerminalResult() && runNeedsTerminalRepair(run = run, result = requireNotNull(durableResult))) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESTORE_TERMINAL_RESULT,
        reasonCode = "durable_terminal_result_persisted",
        summary =
          "A terminal execution result was already durably persisted before recovery. Restore the run to its terminal state instead of replaying the task.",
        safeToAutoResume = true,
        requiresUserAction = false,
        checkpointKind = checkpoint?.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (
      isInterruptedRestoreRun(run) &&
      checkpoint != null &&
      safeManagedProcessObservationResume(
        run = run,
        checkpoint = checkpoint,
        event = input.lastJournalEvent,
      )
    ) {
      checkpointResumeBudgetExhaustedPlan(
        run = run,
        checkpoint = checkpoint,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )?.let { plan -> return plan }
      val managedProcessEvidence = managedProcessReconnectEvidence(run)
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_FROM_CHECKPOINT,
        reasonCode = "managed_process_observation_checkpoint_resume",
        summary =
          "The host was rebuilt while a ProcessRead or ProcessWait observation against a still-live managed process was in flight. Recovery can safely resume from the durable checkpoint and reissue that observation after reconnecting the process controller.",
        safeToAutoResume = true,
        requiresUserAction = false,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
        managedProcessContinuationBasis = ManagedProcessContinuationBases.CHECKPOINT_RESUME,
        managedProcessRestoreScope = managedProcessEvidence.restoreScope,
        managedProcessRestoreDecision = managedProcessEvidence.restoreDecision,
      )
    }

    if (
      isInterruptedRestoreRun(run) &&
      run.hasLiveManagedProcesses &&
      isManagedProcessObservationReconnectPending(
        run = run,
        event = input.lastJournalEvent,
      )
    ) {
      return reconnectProcessPlan(
        run = run,
        checkpoint = checkpoint,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
        reasonCode = "managed_process_observation_reconnect_pending",
        summary =
          "The host was rebuilt while a ProcessRead or ProcessWait observation was in flight against a live managed process, but reconnect state is not yet stable enough for checkpoint auto-resume. Preserve the process and surface reconnect state instead of replaying the task.",
      )
    }

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
      run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      run.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME &&
      checkpoint?.checkpointKind?.isGeneralPromptResumeKind() == true
    ) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_WAITING_FOR_USER,
        reasonCode = "llm_retry_exhausted_waiting_for_resume",
        summary = "Recoverable LLM retries were exhausted before recovery. Keep the run paused until the user sends another message or explicitly resumes it.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (
      run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      checkpoint?.checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME
    ) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_WAITING_FOR_USER,
        reasonCode = "approval_granted_waiting_for_manual_resume",
        summary =
          "Approval was already granted, but this run was explicitly interrupted before it resumed. Keep it paused until the user explicitly resumes it.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (checkpoint?.checkpointKind == PromptCheckpointKind.FINALIZATION_COMPLETE) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
        reasonCode = "finalization_checkpoint_missing_terminal_result",
        summary =
          "Recovery found a finalization checkpoint, but no durable terminal result is available to prove the final answer completed. Stop here instead of replaying or guessing.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    if (checkpoint?.checkpointKind?.isCheckpointResumeKind() == true) {
      checkpointResumeBudgetExhaustedPlan(
        run = run,
        checkpoint = checkpoint,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )?.let { plan -> return plan }
      return RunRecoveryPlan(
        action = RunRecoveryAction.RESUME_FROM_CHECKPOINT,
        reasonCode = when (checkpoint.checkpointKind) {
          PromptCheckpointKind.GENERAL_RESUME -> "durable_general_resume_checkpoint"
          PromptCheckpointKind.PRE_MODEL_REQUEST -> "durable_pre_model_request_checkpoint"
          PromptCheckpointKind.ACTION_BATCH_PARSED -> "durable_action_batch_parsed_checkpoint"
          PromptCheckpointKind.COMMENTARY_EMITTED -> "durable_commentary_emitted_checkpoint"
          PromptCheckpointKind.TOOL_RESULT_COMMITTED -> "durable_tool_result_checkpoint"
          PromptCheckpointKind.SUPPLEMENT_INGESTED -> "durable_supplement_ingested_checkpoint"
          PromptCheckpointKind.FINALIZATION_COMPLETE -> "finalization_checkpoint_missing_terminal_result"
          PromptCheckpointKind.APPROVED_PENDING_RESUME -> "approval_already_granted_resume_pending"
          PromptCheckpointKind.REJECTED_PENDING_RESUME -> "approval_already_rejected_waiting_for_instruction"
          PromptCheckpointKind.WAITING_APPROVAL -> "approval_waiting_checkpoint"
        },
        summary = when (checkpoint.checkpointKind) {
          PromptCheckpointKind.GENERAL_RESUME ->
            "The run has a durable general resume checkpoint and should continue from that checkpoint instead of rerunning from task input."
          PromptCheckpointKind.PRE_MODEL_REQUEST ->
            "The run was already durably staged at the next model-request boundary and should resume from that boundary instead of rerunning from task input."
          PromptCheckpointKind.ACTION_BATCH_PARSED ->
            "The run already durably captured the parsed model action batch and should resume from that action cursor instead of rerunning from task input."
          PromptCheckpointKind.COMMENTARY_EMITTED ->
            "The run durably committed a commentary update and should continue from the remaining parsed actions instead of rerunning from task input."
          PromptCheckpointKind.TOOL_RESULT_COMMITTED ->
            "The run durably committed a tool-result boundary and should continue from that boundary instead of rerunning from task input."
          PromptCheckpointKind.SUPPLEMENT_INGESTED ->
            "The run durably committed a supplement-ingestion boundary and should continue from that boundary instead of rerunning from task input."
          PromptCheckpointKind.FINALIZATION_COMPLETE ->
            "The run has terminal finalization evidence and must not be resumed from the prompt checkpoint path."
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

    if (checkpoint?.checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.STOP_REJECTED_AWAITING_DIRECTION,
        reasonCode = "approval_already_rejected_waiting_for_instruction",
        summary = "Approval was already rejected before recovery. Keep the run stopped and wait for a new user instruction.",
        safeToAutoResume = false,
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
      return reconnectProcessPlan(
        run = run,
        checkpoint = checkpoint,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
        reasonCode = "live_managed_process_detected",
        summary = "A managed process is still live for this run, but current recovery can only preserve that live process and surface reconnect state. Automatic continuation should not replay the task without a durable checkpoint.",
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

    if (queuedRunHasUnsafePriorProgress(run = run, event = input.lastJournalEvent)) {
      return RunRecoveryPlan(
        action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
        reasonCode = "queued_progress_without_checkpoint",
        summary = "The run was queued to continue, but recovery found prior execution progress without a durable checkpoint. Keep it interrupted until the user explicitly retries or sends a new instruction.",
        safeToAutoResume = false,
        requiresUserAction = true,
        checkpointKind = checkpoint?.checkpointKind,
        approvalState = input.approvalState,
        journalTailKind = journalTailKind,
      )
    }

    return null
  }

  private fun checkpointResumeBudgetExhaustedPlan(
    run: AgentRunSnapshot,
    checkpoint: PersistedPromptCheckpoint,
    approvalState: AgentTaskApprovalState?,
    journalTailKind: String?,
  ): RunRecoveryPlan? {
    val attemptCount = run.lifecycleDiagnostics.checkpointResumeAttemptCount ?: 0
    if (attemptCount < maxAutomaticCheckpointResumeAttempts) {
      return null
    }
    return RunRecoveryPlan(
      action = RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
      reasonCode = "automatic_checkpoint_resume_budget_exhausted",
      summary =
        "The run reached its automatic checkpoint-resume limit after $attemptCount attempts. Keep it interrupted until the user explicitly retries it.",
      safeToAutoResume = false,
      requiresUserAction = true,
      checkpointKind = checkpoint.checkpointKind,
      approvalState = approvalState,
      journalTailKind = journalTailKind,
      checkpointResumeAttemptCount = attemptCount,
      maxAutomaticCheckpointResumeAttempts = maxAutomaticCheckpointResumeAttempts,
    )
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
      run.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      run.lifecycleDiagnostics.managedProcessContinuationBasis ==
      ManagedProcessContinuationBases.RECONNECT_HOLD

  private fun queuedRunHasUnsafePriorProgress(
    run: AgentRunSnapshot,
    event: OpenCrayAgentRunEvent?,
  ): Boolean {
    if (run.lifecycleState != QueueTaskLifecycleState.QUEUED || run.isTerminal) {
      return false
    }
    val pendingExecutionKind = run.pendingExecutionKind
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    return run.executionOrdinal > 0 ||
      !run.executionKind.isNullOrBlank() ||
      (
        pendingExecutionKind != null &&
          pendingExecutionKind != EXECUTION_KIND_INITIAL
        ) ||
      run.executionStatus != null ||
      !run.errorCode.isNullOrBlank() ||
      event != null
  }

  private fun isUncertainInFlightAction(event: OpenCrayAgentRunEvent?): Boolean = when (event) {
    is OpenCrayToolCallEvent -> true
    is OpenCraySubAgentEvent ->
      event.phase == OpenCraySubAgentPhase.STARTED ||
        event.phase == OpenCraySubAgentPhase.RESUMED
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
      is OpenCraySubAgentEvent ->
        event.phase == OpenCraySubAgentPhase.STARTED ||
          event.phase == OpenCraySubAgentPhase.RESUMED
      else -> false
    }
  }

  private fun safeManagedProcessObservationResume(
    run: AgentRunSnapshot,
    checkpoint: PersistedPromptCheckpoint,
    event: OpenCrayAgentRunEvent?,
  ): Boolean {
    if (!run.hasAutoResumeEligibleManagedProcesses) {
      return false
    }
    val toolCallEvent = event as? OpenCrayToolCallEvent ?: return false
    if (!toolCallEvent.call.isManagedProcessObservationCall()) {
      return false
    }
    val resumeState = checkpoint.promptResumeState ?: return false
    val resumableAction = resumeState.resumableActions()
      .getOrNull(resumeState.normalizedNextActionIndex()) as? OpenCraySerializableModelAction.ToolCall
      ?: return false
    val resumedCall = resumableAction.call
    if (!resumedCall.toolName.equals(toolCallEvent.call.toolName, ignoreCase = true)) {
      return false
    }
    val resumedProcessId = managedProcessObservationProcessId(resumedCall.arguments) ?: return false
    val eventProcessId = managedProcessObservationProcessId(toolCallEvent.call.arguments) ?: return false
    return resumedProcessId == eventProcessId
  }

  private fun isManagedProcessObservationReconnectPending(
    run: AgentRunSnapshot,
    event: OpenCrayAgentRunEvent?,
  ): Boolean {
    val toolCallEvent = event as? OpenCrayToolCallEvent ?: return false
    if (!toolCallEvent.call.isManagedProcessObservationCall()) {
      return false
    }
    val eventProcessId = managedProcessObservationProcessId(toolCallEvent.call.arguments) ?: return false
    return liveManagedProcesses(run).any { process -> process.processId == eventProcessId }
  }

  private fun reconnectProcessPlan(
    run: AgentRunSnapshot,
    checkpoint: PersistedPromptCheckpoint?,
    approvalState: AgentTaskApprovalState?,
    journalTailKind: String?,
    reasonCode: String,
    summary: String,
  ): RunRecoveryPlan {
    val evidence = managedProcessReconnectEvidence(run)
    return RunRecoveryPlan(
      action = RunRecoveryAction.RESUME_RECONNECT_PROCESS,
      reasonCode = reasonCode,
      summary = summary,
      safeToAutoResume = false,
      requiresUserAction = true,
      checkpointKind = checkpoint?.checkpointKind,
      approvalState = approvalState,
      journalTailKind = journalTailKind,
      managedProcessReconnectProcessIds = evidence.processIds,
      managedProcessReconnectStatus = evidence.status,
      managedProcessReconnectRecoveryState = evidence.recoveryState,
      managedProcessReconnectRetryAfterEpochMs = evidence.retryAfterEpochMs,
      managedProcessReconnectAttemptCount = evidence.attemptCount,
      managedProcessContinuationBasis = evidence.continuationBasis,
      managedProcessRestoreScope = evidence.restoreScope,
      managedProcessRestoreDecision = evidence.restoreDecision,
    )
  }

  private fun managedProcessReconnectEvidence(run: AgentRunSnapshot): ManagedProcessReconnectEvidence {
    val liveProcesses = liveManagedProcesses(run)
    return ManagedProcessReconnectEvidence(
      processIds = liveProcesses.map(ManagedProcessSnapshot::processId).distinct(),
      status = liveProcesses
        .mapNotNull { process -> process.reconnectStatus() }
        .distinct()
        .sorted()
        .joinToString(",")
        .takeIf(String::isNotBlank),
      recoveryState = liveProcesses
        .mapNotNull { process -> process.reconnectRecoveryState() }
        .distinct()
        .sorted()
        .joinToString(",")
        .takeIf(String::isNotBlank),
      retryAfterEpochMs = liveProcesses
        .mapNotNull { process -> process.reconnectRetryAfterEpochMs() }
        .minOrNull(),
      attemptCount = liveProcesses
        .mapNotNull { process -> process.reconnectAttemptCount() }
        .maxOrNull(),
      continuationBasis = if (liveProcesses.hasStableLiveReconnectEvidence()) {
        ManagedProcessContinuationBases.LIVE_REATTACH
      } else {
        ManagedProcessContinuationBases.RECONNECT_HOLD
      },
      restoreScope = liveProcesses
        .mapNotNull { process -> process.managedProcessRestoreScope() }
        .distinct()
        .sorted()
        .joinToString(",")
        .takeIf(String::isNotBlank),
      restoreDecision = liveProcesses
        .mapNotNull { process -> process.managedProcessRestoreDecision() }
        .distinct()
        .sorted()
        .joinToString(",")
        .takeIf(String::isNotBlank),
    )
  }

  private fun liveManagedProcesses(run: AgentRunSnapshot): List<ManagedProcessSnapshot> =
    run.managedProcesses
      .filter { process -> process.status == ManagedProcessStatus.RUNNING }
      .distinctBy(ManagedProcessSnapshot::processId)

  private fun com.opencray.runtime.AgentToolCall.isManagedProcessObservationCall(): Boolean =
    toolName.equals("ProcessRead", ignoreCase = true) ||
      toolName.equals("ProcessWait", ignoreCase = true)

  private fun managedProcessObservationProcessId(arguments: JsonObject): String? =
    (arguments["process_id"] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun ManagedProcessSnapshot.reconnectStatus(): String? =
    reconnectSnapshotEvidence().status

  private fun ManagedProcessSnapshot.reconnectRecoveryState(): String? =
    reconnectSnapshotEvidence().recoveryState

  private fun ManagedProcessSnapshot.reconnectRetryAfterEpochMs(): Long? =
    reconnectSnapshotEvidence().retryAfterEpochMs

  private fun ManagedProcessSnapshot.reconnectAttemptCount(): Int? =
    reconnectSnapshotEvidence().attemptCount

  private fun ManagedProcessSnapshot.managedProcessRestoreScope(): String? =
    metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun ManagedProcessSnapshot.managedProcessRestoreDecision(): String? =
    metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun List<ManagedProcessSnapshot>.hasStableLiveReconnectEvidence(): Boolean =
    isNotEmpty() && all { process ->
      process.reconnectStatus() == "attached" ||
        process.reconnectRecoveryState() == "attached_live"
    }

  private fun runNeedsTerminalRepair(
    run: AgentRunSnapshot,
    result: ExecutionResult,
  ): Boolean = when (result.status) {
    ExecutionStatus.SUCCESS ->
      run.lifecycleState != QueueTaskLifecycleState.COMPLETED || run.executionStatus != ExecutionStatus.SUCCESS

    ExecutionStatus.CANCELLED ->
      run.lifecycleState != QueueTaskLifecycleState.CANCELLED || run.executionStatus != ExecutionStatus.CANCELLED

    ExecutionStatus.FAILED,
    ExecutionStatus.TIMEOUT,
    ->
      run.lifecycleState != QueueTaskLifecycleState.FAILED || run.executionStatus != result.status

    ExecutionStatus.DENIED -> false
  }

  private fun ExecutionResult?.isRestorableTerminalResult(): Boolean = when (this?.status) {
    ExecutionStatus.FAILED ->
      errorCode != ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME

    ExecutionStatus.SUCCESS,
    ExecutionStatus.CANCELLED,
    ExecutionStatus.TIMEOUT,
    -> true

    ExecutionStatus.DENIED,
    null,
    -> false
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

internal const val DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS: Int = 3

private data class ManagedProcessReconnectEvidence(
  val processIds: List<String> = emptyList(),
  val status: String? = null,
  val recoveryState: String? = null,
  val retryAfterEpochMs: Long? = null,
  val attemptCount: Int? = null,
  val continuationBasis: String? = null,
  val restoreScope: String? = null,
  val restoreDecision: String? = null,
)
