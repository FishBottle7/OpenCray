package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus

internal class RecoveryAwareQueueSnapshotStore(
  private val sessionId: String,
  private val delegate: SessionQueueSnapshotStore,
  private val runRecordStore: AgentRunRecordStore,
  private val runEventJournalStore: RunEventJournalStore,
  private val promptCheckpointStore: PromptCheckpointStore,
  private val managedProcessesProvider: () -> List<ManagedProcessSnapshot>,
  private val planner: RunRecoveryPlanner = RunRecoveryPlanner(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SessionQueueSnapshotStore {
  override fun load(): SessionQueueSnapshot? {
    val snapshot = delegate.load() ?: return null
    if (snapshot.tasks.isEmpty()) {
      return snapshot
    }

    val restoreEpochMs = clock()
    val runRecordsByRunId = runRecordStore.list().associateBy(PersistedAgentRunRecord::runId)
    val checkpointsByTaskId = promptCheckpointStore.list().associateBy(PersistedPromptCheckpoint::taskId)
    val managedProcessesById = managedProcessesProvider().associateBy(ManagedProcessSnapshot::processId)

    var changed = false
    val rewrittenTasks = snapshot.tasks.map { entry ->
      val runId = runIdFor(entry.task)
      val runRecord = runRecordsByRunId[runId]
      val lastJournalEvent = runEventJournalStore.listForRun(runId)
        .lastOrNull()
        ?.payload
        ?.toRuntimeEvent()
        ?: runRecord?.lastEvent?.toRuntimeEvent()
      val checkpoint = checkpointsByTaskId[entry.task.id]
      val associatedManagedProcesses = associatedManagedProcesses(
        taskId = entry.task.id,
        existingIds = runRecord?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
      )
      val plannerProjection = plannerProjection(entry, restoreEpochMs)
      val recoveryPlan = planner.plan(
        RunRecoveryPlannerInput(
          run = plannerRun(
            entry = plannerProjection,
            runId = runId,
            runRecord = runRecord,
            managedProcesses = associatedManagedProcesses,
          ),
          checkpoint = checkpoint,
          lastJournalEvent = lastJournalEvent,
          approvalState = checkpointApprovalState(checkpoint),
        ),
      )
      val rewrittenEntry = rewriteForRecoveryPlan(
        entry = entry,
        runRecord = runRecord,
        checkpoint = checkpoint,
        recoveryPlan = recoveryPlan,
        restoreEpochMs = restoreEpochMs,
      )
      if (rewrittenEntry != entry) {
        changed = true
      }
      rewrittenEntry
    }

    return if (changed) {
      snapshot.copy(
        tasks = rewrittenTasks,
        updatedAtEpochMs = maxOf(snapshot.updatedAtEpochMs, restoreEpochMs),
      )
    } else {
      snapshot
    }
  }

  override fun save(snapshot: SessionQueueSnapshot) {
    delegate.save(snapshot)
  }

  override fun clear() {
    delegate.clear()
  }

  private fun rewriteForRecoveryPlan(
    entry: SessionQueueTaskSnapshot,
    runRecord: PersistedAgentRunRecord?,
    checkpoint: PersistedPromptCheckpoint?,
    recoveryPlan: RunRecoveryPlan?,
    restoreEpochMs: Long,
  ): SessionQueueTaskSnapshot = when (recoveryPlan?.action) {
    RunRecoveryAction.RESUME_FROM_CHECKPOINT ->
      if (canResumeFromCheckpoint(entry)) {
        entry.copy(
          lifecycleState = QueueTaskLifecycleState.QUEUED,
          task = entry.task.copy(
            state = AgentTaskState.QUEUED,
            updatedAtEpochMs = maxOf(
              entry.task.updatedAtEpochMs,
              entry.task.createdAtEpochMs,
              restoreEpochMs,
            ),
            metadata = rewrittenTaskMetadata(
              entry = entry,
              restoreEpochMs = restoreEpochMs,
              recoveryPlan = recoveryPlan,
            ),
          ),
          lastErrorCode = null,
          lastErrorMessage = null,
        )
      } else {
        entry
      }

    RunRecoveryAction.RESUME_WAITING_FOR_APPROVAL ->
      if (canRestoreWaitingApproval(entry)) {
        entry.copy(
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          task = entry.task.copy(
            state = AgentTaskState.SUSPENDED,
            updatedAtEpochMs = maxOf(
              entry.task.updatedAtEpochMs,
              entry.task.createdAtEpochMs,
              restoreEpochMs,
            ),
            metadata = rewrittenTaskMetadata(
              entry = entry,
              restoreEpochMs = restoreEpochMs,
              recoveryPlan = recoveryPlan,
            ),
          ),
          lastErrorCode = approvalErrorCode(runRecord?.lastResult, checkpoint),
          lastErrorMessage = approvalErrorMessage(runRecord?.lastResult, checkpoint),
        )
      } else {
        entry
      }

    else -> entry
  }

  private fun canResumeFromCheckpoint(entry: SessionQueueTaskSnapshot): Boolean = when {
    isRestoreInterruptedLifecycle(entry.lifecycleState) -> true
    entry.lifecycleState == QueueTaskLifecycleState.QUEUED -> true
    entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED -> true
    isRestartRestoreFailure(entry) -> true
    else -> false
  }

  private fun canRestoreWaitingApproval(entry: SessionQueueTaskSnapshot): Boolean = when {
    entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED -> true
    isRestoreInterruptedLifecycle(entry.lifecycleState) -> true
    isRestartRestoreFailure(entry) -> true
    else -> false
  }

  private fun plannerProjection(
    entry: SessionQueueTaskSnapshot,
    restoreEpochMs: Long,
  ): SessionQueueTaskSnapshot {
    if (!isRestoreInterruptedLifecycle(entry.lifecycleState)) {
      return entry
    }
    return entry.copy(
      lifecycleState = QueueTaskLifecycleState.FAILED,
      task = entry.task.copy(
        state = AgentTaskState.FAILED,
        updatedAtEpochMs = maxOf(
          entry.task.updatedAtEpochMs,
          entry.task.createdAtEpochMs,
          restoreEpochMs,
        ),
        metadata = buildMap {
          putAll(
            entry.task.metadata.filterKeys { key ->
              key != METADATA_QUEUE_RESTORE_EPOCH_MS &&
                key != METADATA_PREVIOUS_LIFECYCLE_STATE &&
                key != METADATA_RECOVERY_REASON
            },
          )
          put(METADATA_QUEUE_RESTORE_EPOCH_MS, restoreEpochMs.toString())
          put(METADATA_PREVIOUS_LIFECYCLE_STATE, entry.lifecycleState.name.lowercase())
          put(METADATA_RECOVERY_REASON, RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED)
        },
      ),
      lastErrorCode = ERROR_RESTART_REQUIRES_EXPLICIT_RETRY,
      lastErrorMessage = restoreInterruptedMessage(entry.lifecycleState),
    )
  }

  private fun plannerRun(
    entry: SessionQueueTaskSnapshot,
    runId: String,
    runRecord: PersistedAgentRunRecord?,
    managedProcesses: List<ManagedProcessSnapshot>,
  ): AgentRunSnapshot {
    val executionResult = visibleRunResult(
      taskSnapshot = entry,
      result = runRecord?.lastResult,
    )
    val runningManagedProcessCount = managedProcesses.count { snapshot ->
      snapshot.status == ManagedProcessStatus.RUNNING
    }
    val acceptedAtEpochMs = runRecord?.acceptedAtEpochMs ?: entry.task.createdAtEpochMs
    return AgentRunSnapshot(
      sessionId = sessionId,
      runId = runId,
      taskId = entry.task.id,
      acceptedAtEpochMs = acceptedAtEpochMs,
      updatedAtEpochMs = maxOf(
        entry.task.updatedAtEpochMs,
        executionResult?.finishedAtEpochMs ?: 0L,
        runRecord?.lastEvent?.emittedAtEpochMs ?: 0L,
        managedProcesses.maxOfOrNull(ManagedProcessSnapshot::updatedAtEpochMs) ?: 0L,
        acceptedAtEpochMs,
      ),
      lifecycleState = entry.lifecycleState,
      taskState = entry.task.state,
      attempt = entry.attempt,
      executionStatus = executionResult?.status,
      errorCode = executionResult?.errorCode ?: entry.lastErrorCode,
      errorMessage = executionResult?.errorMessage ?: entry.lastErrorMessage,
      pendingMessageId = entry.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?: runRecord?.pendingMessageId,
      managedProcessIds = managedProcesses.map(ManagedProcessSnapshot::processId).distinct(),
      runningManagedProcessCount = runningManagedProcessCount,
      hasLiveManagedProcesses = runningManagedProcessCount > 0,
      lastEvent = runRecord?.lastEvent?.toRuntimeEvent(),
      lifecycleDiagnostics = runLifecycleDiagnosticsFrom(
        taskMetadata = entry.task.metadata,
        resultMetadata = executionResult?.metadata.orEmpty(),
        resultErrorCode = executionResult?.errorCode,
      ),
    )
  }

  private fun rewrittenTaskMetadata(
    entry: SessionQueueTaskSnapshot,
    restoreEpochMs: Long,
    recoveryPlan: RunRecoveryPlan? = null,
  ): Map<String, String> {
    val shouldStampRestore = shouldStampRestoreMetadata(entry)
    return buildMap {
      putAll(
        entry.task.metadata.filterKeys { key ->
          key != METADATA_QUEUE_RESTORE_EPOCH_MS &&
            key != METADATA_PREVIOUS_LIFECYCLE_STATE &&
            key != METADATA_RECOVERY_REASON
        },
      )
      if (shouldStampRestore) {
        put(METADATA_QUEUE_RESTORE_EPOCH_MS, restoreEpochMs.toString())
        put(METADATA_PREVIOUS_LIFECYCLE_STATE, previousLifecycleState(entry))
        recoveryPlan
          ?.reasonCode
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { reasonCode ->
            put(METADATA_RECOVERY_REASON, reasonCode)
          }
      }
    }
  }

  private fun shouldStampRestoreMetadata(entry: SessionQueueTaskSnapshot): Boolean =
    isRestoreInterruptedLifecycle(entry.lifecycleState) ||
      isRestartRestoreFailure(entry) ||
      entry.task.metadata.containsKey(METADATA_QUEUE_RESTORE_EPOCH_MS) ||
      entry.task.metadata.containsKey(METADATA_PREVIOUS_LIFECYCLE_STATE)

  private fun previousLifecycleState(entry: SessionQueueTaskSnapshot): String =
    entry.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: entry.lifecycleState.name.lowercase()

  private fun checkpointApprovalState(
    checkpoint: PersistedPromptCheckpoint?,
  ): AgentTaskApprovalState? = when (checkpoint?.checkpointKind) {
    PromptCheckpointKind.APPROVED_PENDING_RESUME -> AgentTaskApprovalState.APPROVED
    PromptCheckpointKind.REJECTED_PENDING_RESUME -> AgentTaskApprovalState.REJECTED
    PromptCheckpointKind.GENERAL_RESUME,
    PromptCheckpointKind.WAITING_APPROVAL,
    null,
    -> null
  }

  private fun approvalErrorCode(
    result: ExecutionResult?,
    checkpoint: PersistedPromptCheckpoint?,
  ): String? = result
    ?.errorCode
    ?.takeIf { errorCode ->
      errorCode == APPROVAL_REQUIRED_ERROR_CODE || errorCode == HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
    }
    ?: when {
      checkpoint == null -> null
      checkpoint.isHighRisk -> HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
      else -> APPROVAL_REQUIRED_ERROR_CODE
    }

  private fun approvalErrorMessage(
    result: ExecutionResult?,
    checkpoint: PersistedPromptCheckpoint?,
  ): String? = result?.errorMessage?.takeIf(String::isNotBlank) ?: checkpoint?.let { persisted ->
    val toolLabel = persisted.toolName?.trim()?.takeIf(String::isNotBlank) ?: "tool"
    if (persisted.isHighRisk) {
      "High-risk approval is required before $toolLabel can run."
    } else {
      "Approval is required before $toolLabel can run."
    }
  }

  private fun visibleRunResult(
    taskSnapshot: SessionQueueTaskSnapshot,
    result: ExecutionResult?,
  ): ExecutionResult? {
    if (result == null) {
      return null
    }
    if (isInterruptedOnRestoreResult(result)) {
      return result
    }
    return when (taskSnapshot.lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> null

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> if (taskSnapshot.task.updatedAtEpochMs > result.finishedAtEpochMs) {
        null
      } else {
        result
      }

      else -> result
    }
  }

  private fun isRestartRestoreFailure(entry: SessionQueueTaskSnapshot): Boolean =
    entry.lifecycleState == QueueTaskLifecycleState.FAILED &&
      (
        entry.lastErrorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
          entry.task.metadata[METADATA_RECOVERY_REASON] ==
          RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED
      )

  private fun associatedManagedProcesses(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
  ): List<ManagedProcessSnapshot> = (
    existingIds +
      managedProcessesById.values
        .asSequence()
        .filter { snapshot -> snapshot.taskId == taskId }
        .map(ManagedProcessSnapshot::processId)
        .toList()
    ).distinct().mapNotNull(managedProcessesById::get)

  private fun isInterruptedOnRestoreResult(result: ExecutionResult): Boolean =
    result.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun isRestoreInterruptedLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.RETRY_PENDING,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> true

    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.SUSPENDED,
    QueueTaskLifecycleState.COMPLETED,
    QueueTaskLifecycleState.FAILED,
    QueueTaskLifecycleState.CANCELLED,
    -> false
  }

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun restoreInterruptedMessage(previousState: QueueTaskLifecycleState): String = when (previousState) {
    QueueTaskLifecycleState.RUNNING ->
      "The app host restarted while this run was in progress. OpenCray stopped it to avoid silently rerunning from the beginning. Retry explicitly when you want to continue."

    QueueTaskLifecycleState.RETRY_PENDING ->
      "The app host restarted while this run was waiting to retry. OpenCray stopped it to avoid silently resuming work. Retry explicitly when you want to continue."

    QueueTaskLifecycleState.CANCEL_REQUESTED ->
      "The app host restarted while cancellation was still settling. OpenCray stopped the run and now requires an explicit retry to continue."

    else ->
      "The app host restarted before this run could finish. OpenCray stopped it to avoid silently rerunning from the beginning. Retry explicitly when you want to continue."
  }

  private companion object {
    const val APPROVAL_REQUIRED_ERROR_CODE: String = "APPROVAL_REQUIRED"
    const val HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE: String = "HIGH_RISK_APPROVAL_REQUIRED"
  }
}
