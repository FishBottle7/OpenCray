package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.METADATA_PENDING_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import kotlinx.serialization.json.Json

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
  private val promptResumeJson: Json = Json { prettyPrint = false }

  override fun load(): SessionQueueSnapshot? {
    val snapshot = delegate.load() ?: return null
    if (snapshot.tasks.isEmpty()) {
      return snapshot
    }

    val restoreEpochMs = clock()
    val runRecordsByRunId = runRecordStore.list().associateBy(PersistedAgentRunRecord::runId)
    val checkpointsByTaskId = promptCheckpointStore.list()
      .associateBy(PersistedPromptCheckpoint::taskId)
      .toMutableMap()
    val managedProcessesById = managedProcessesProvider().associateBy(ManagedProcessSnapshot::processId)

    var changed = false
    val rewrittenTasks = snapshot.tasks.map { entry ->
      val runId = runIdFor(entry.task)
      val runRecord = runRecordsByRunId[runId]
      val journalEntries = runEventJournalStore.listForRun(runId)
      val lastJournalEvent = journalEntries.latestRuntimeEventOrNull()
        ?: runRecord?.lastEvent?.toRuntimeEventOrNull()
      val checkpoint = checkpointsByTaskId[entry.task.id]
        ?: synthesizeApprovalCheckpoint(
          entry = entry,
          runId = runId,
          runRecord = runRecord,
          lastJournalEvent = lastJournalEvent,
        )?.also { syntheticCheckpoint ->
          promptCheckpointStore.upsert(syntheticCheckpoint)
          checkpointsByTaskId[entry.task.id] = syntheticCheckpoint
        }
        ?: synthesizeGeneralResumeCheckpoint(
          entry = entry,
          runId = runId,
          runRecord = runRecord,
          journalEntries = journalEntries,
          lastJournalEvent = lastJournalEvent,
        )?.also { syntheticCheckpoint ->
          promptCheckpointStore.upsert(syntheticCheckpoint)
          checkpointsByTaskId[entry.task.id] = syntheticCheckpoint
        }
      val associatedManagedProcesses = associatedManagedProcesses(
        taskId = entry.task.id,
        existingIds = runRecord?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
      )
      val plannerProjection = plannerProjection(entry, restoreEpochMs)
      val recoveryPlan = planRunRecovery(
        run = plannerRun(
          entry = plannerProjection,
          runId = runId,
          runRecord = runRecord,
          managedProcesses = associatedManagedProcesses,
        ),
        checkpoint = checkpoint,
        lastJournalEvent = lastJournalEvent,
        planner = planner,
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

  private fun synthesizeApprovalCheckpoint(
    entry: SessionQueueTaskSnapshot,
    runId: String,
    runRecord: PersistedAgentRunRecord?,
    lastJournalEvent: OpenCrayAgentRunEvent?,
  ): PersistedPromptCheckpoint? {
    if (entry.task.type != AgentTaskType.PROMPT) {
      return null
    }
    val boundary = approvalCheckpointBoundary(
      runId = runId,
      taskId = entry.task.id,
      runRecord = runRecord,
      lastJournalEvent = lastJournalEvent,
    ) ?: return null
    return PersistedPromptCheckpoint(
      sessionId = sessionId,
      runId = boundary.runId,
      taskId = boundary.taskId,
      checkpointId = "synthetic-${boundary.checkpointKind.name.lowercase()}-${boundary.emittedAtEpochMs}",
      checkpointKind = boundary.checkpointKind,
      createdAtEpochMs = boundary.emittedAtEpochMs,
      updatedAtEpochMs = boundary.emittedAtEpochMs,
      toolName = boundary.toolName,
      pendingMessageId = boundary.pendingMessageId
        ?: entry.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      isHighRisk = boundary.isHighRisk,
      promptCheckpointBoundary = boundary.promptCheckpointBoundary,
      promptResumeState = boundary.promptResumeState,
      subAgentApprovedToolName = boundary.subAgentApprovalResume?.approvedToolName,
      subAgentPromptResumeState = boundary.subAgentApprovalResume?.promptResumeState,
      subAgentIsHighRisk = boundary.subAgentApprovalResume?.isHighRisk,
      subAgentAgentId = boundary.subAgentApprovalResume?.agentId,
      subAgentChildRunId = boundary.subAgentApprovalResume?.childRunId,
      subAgentChildTaskId = boundary.subAgentApprovalResume?.childTaskId,
    )
  }

  private fun synthesizeGeneralResumeCheckpoint(
    entry: SessionQueueTaskSnapshot,
    runId: String,
    runRecord: PersistedAgentRunRecord?,
    journalEntries: List<PersistedRunJournalEntry>,
    lastJournalEvent: OpenCrayAgentRunEvent?,
  ): PersistedPromptCheckpoint? {
    if (entry.task.type != AgentTaskType.PROMPT) {
      return null
    }
    val checkpointBoundary = latestGeneralResumeBoundary(
      journalEntries = journalEntries,
      fallbackEvent = lastJournalEvent,
    )
      ?.takeIf { boundary ->
        boundary.runId == runId && boundary.taskId == entry.task.id
      }
      ?: generalResumeBoundary(
        runId = runId,
        taskId = entry.task.id,
        result = runRecord?.lastResult,
      )
      ?: return null
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = checkpointBoundary.metadata,
      json = promptResumeJson,
    ) ?: return null
    val pendingMessageId = entry.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: runRecord?.pendingMessageId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    return PersistedPromptCheckpoint(
      sessionId = sessionId,
      runId = checkpointBoundary.runId,
      taskId = checkpointBoundary.taskId,
      checkpointId =
        "synthetic-${checkpointBoundary.checkpointKind.name.lowercase()}-${checkpointBoundary.emittedAtEpochMs}",
      checkpointKind = checkpointBoundary.checkpointKind,
      createdAtEpochMs = checkpointBoundary.emittedAtEpochMs,
      updatedAtEpochMs = checkpointBoundary.emittedAtEpochMs,
      toolName = checkpointBoundary.toolName,
      pendingMessageId = pendingMessageId,
      promptCheckpointBoundary = checkpointBoundary.promptCheckpointBoundary,
      promptResumeState = promptResumeState,
    )
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
          executionId = null,
          executionKind = null,
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

    RunRecoveryAction.RESUME_WAITING_FOR_USER ->
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
          lastErrorCode = entry.lastErrorCode,
          lastErrorMessage = entry.lastErrorMessage,
        )
      } else {
        entry
      }

    RunRecoveryAction.RESUME_RECONNECT_PROCESS ->
      if (canReconnectManagedProcess(entry)) {
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
          lastErrorCode = null,
          lastErrorMessage = null,
        )
      } else {
        entry
      }

    RunRecoveryAction.STOP_REJECTED_AWAITING_DIRECTION ->
      if (canStopRejectedAwaitingDirection(entry)) {
        entry.copy(
          lifecycleState = QueueTaskLifecycleState.CANCELLED,
          task = entry.task.copy(
            state = AgentTaskState.CANCELLED,
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
        )
      } else {
        entry
      }

    RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED ->
      if (canInterruptRecovery(entry)) {
        entry.copy(
          lifecycleState = QueueTaskLifecycleState.FAILED,
          task = entry.task.copy(
            state = AgentTaskState.FAILED,
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
          lastErrorCode = ERROR_RESTART_REQUIRES_EXPLICIT_RETRY,
          lastErrorMessage = interruptRecoveryMessage(entry, recoveryPlan),
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
    entry.lifecycleState == QueueTaskLifecycleState.QUEUED -> true
    entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED -> true
    isRestoreInterruptedLifecycle(entry.lifecycleState) -> true
    isRestartRestoreFailure(entry) -> true
    else -> false
  }

  private fun canReconnectManagedProcess(entry: SessionQueueTaskSnapshot): Boolean = when (entry.lifecycleState) {
    QueueTaskLifecycleState.COMPLETED,
    QueueTaskLifecycleState.CANCELLED,
    -> false

    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.SUSPENDED,
    QueueTaskLifecycleState.RETRY_PENDING,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    QueueTaskLifecycleState.FAILED,
    -> true
  }

  private fun canStopRejectedAwaitingDirection(entry: SessionQueueTaskSnapshot): Boolean = when {
    entry.lifecycleState == QueueTaskLifecycleState.QUEUED -> true
    entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED -> true
    entry.lifecycleState == QueueTaskLifecycleState.RETRY_PENDING -> true
    isRestoreInterruptedLifecycle(entry.lifecycleState) -> true
    isRestartRestoreFailure(entry) -> true
    else -> false
  }

  private fun canInterruptRecovery(
    entry: SessionQueueTaskSnapshot,
  ): Boolean = entry.lifecycleState == QueueTaskLifecycleState.QUEUED ||
    isRestoreInterruptedLifecycle(entry.lifecycleState) ||
    isRestartRestoreFailure(entry)

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
      executionOrdinal = entry.executionOrdinal,
      executionId = entry.executionId
        ?: entry.task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
        ?: executionResult?.metadata?.get(METADATA_EXECUTION_ID)?.trim()?.takeIf(String::isNotBlank),
      executionKind = entry.executionKind
        ?: entry.task.metadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
        ?: executionResult?.metadata?.get(METADATA_EXECUTION_KIND)?.trim()?.takeIf(String::isNotBlank),
      pendingExecutionKind = entry.task.metadata[METADATA_PENDING_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank),
      executionStatus = executionResult?.status,
      errorCode = executionResult?.errorCode ?: entry.lastErrorCode,
      errorMessage = executionResult?.errorMessage ?: entry.lastErrorMessage,
      pendingMessageId = entry.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?: runRecord?.pendingMessageId,
      managedProcessIds = managedProcesses.map(ManagedProcessSnapshot::processId).distinct(),
      managedProcesses = managedProcesses,
      runningManagedProcessCount = runningManagedProcessCount,
      hasLiveManagedProcesses = runningManagedProcessCount > 0,
      lastEvent = runRecord?.lastEvent?.toRuntimeEventOrNull(),
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
    val shouldStampRestore = shouldStampRestoreMetadata(
      entry = entry,
      recoveryPlan = recoveryPlan,
    )
    return buildMap {
      putAll(
        entry.task.metadata.filterKeys { key ->
          key != METADATA_QUEUE_RESTORE_EPOCH_MS &&
            key != METADATA_PREVIOUS_LIFECYCLE_STATE &&
            key != METADATA_RECOVERY_REASON &&
            (
              recoveryPlan?.action != RunRecoveryAction.RESUME_FROM_CHECKPOINT ||
                (
                  key != METADATA_EXECUTION_ID &&
                    key != METADATA_EXECUTION_KIND &&
                    key != METADATA_PENDING_EXECUTION_KIND
                  )
              )
        },
      )
      if (
        recoveryPlan?.action == RunRecoveryAction.RESUME_FROM_CHECKPOINT &&
        entry.executionOrdinal > 0
      ) {
        put(METADATA_EXECUTION_ORDINAL, entry.executionOrdinal.toString())
        put(METADATA_PENDING_EXECUTION_KIND, EXECUTION_KIND_CHECKPOINT_RESUME)
      }
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

  private fun shouldStampRestoreMetadata(
    entry: SessionQueueTaskSnapshot,
    recoveryPlan: RunRecoveryPlan?,
  ): Boolean = recoveryPlan != null ||
    isRestoreInterruptedLifecycle(entry.lifecycleState) ||
    isRestartRestoreFailure(entry) ||
    entry.task.metadata.containsKey(METADATA_QUEUE_RESTORE_EPOCH_MS) ||
    entry.task.metadata.containsKey(METADATA_PREVIOUS_LIFECYCLE_STATE)

  private fun previousLifecycleState(entry: SessionQueueTaskSnapshot): String =
    entry.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: entry.lifecycleState.name.lowercase()

  private fun approvalErrorCode(
    result: ExecutionResult?,
    checkpoint: PersistedPromptCheckpoint?,
  ): String? = result
    ?.errorCode
    ?.takeIf { errorCode ->
      errorCode == SNAPSHOT_APPROVAL_REQUIRED_ERROR_CODE ||
        errorCode == SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
    }
    ?: when {
      checkpoint == null -> null
      checkpoint.isHighRisk -> SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
      else -> SNAPSHOT_APPROVAL_REQUIRED_ERROR_CODE
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
  ): ExecutionResult? = visibleProjectedRunResult(
    taskSnapshot = taskSnapshot,
    result = result,
  )

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
  ): List<ManagedProcessSnapshot> = associatedManagedProcessesForProjection(
    taskId = taskId,
    existingIds = existingIds,
    managedProcessesById = managedProcessesById,
  )

  private fun isInterruptedOnRestoreResult(result: ExecutionResult): Boolean =
    isInterruptedOnRestoreProjectionResult(result)

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

  private fun interruptRecoveryMessage(
    entry: SessionQueueTaskSnapshot,
    recoveryPlan: RunRecoveryPlan?,
  ): String = when (recoveryPlan?.reasonCode) {
    "queued_progress_without_checkpoint" ->
      "This run was queued to continue, but recovery found prior execution progress without a durable checkpoint. OpenCray stopped it to avoid silently rerunning from the beginning. Retry explicitly when you want to continue."

    "uncertain_inflight_mutation" ->
      "The app host restarted after this run advanced beyond the last durable checkpoint. OpenCray stopped it to avoid replaying an uncertain in-flight action. Retry explicitly when you want to continue."

    else -> restoreInterruptedMessage(entry.lifecycleState)
  }

  private companion object {
  }
}

private data class GeneralResumeBoundary(
  val runId: String,
  val taskId: String,
  val checkpointKind: PromptCheckpointKind,
  val toolName: String?,
  val promptCheckpointBoundary: OpenCrayPromptCheckpointBoundary?,
  val metadata: Map<String, String>,
  val emittedAtEpochMs: Long,
)

private data class ApprovalCheckpointBoundary(
  val runId: String,
  val taskId: String,
  val checkpointKind: PromptCheckpointKind,
  val toolName: String?,
  val pendingMessageId: String?,
  val isHighRisk: Boolean,
  val promptCheckpointBoundary: OpenCrayPromptCheckpointBoundary?,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
  val subAgentApprovalResume: SubAgentApprovalResume?,
  val emittedAtEpochMs: Long,
)

private fun generalResumeBoundary(event: OpenCrayAgentRunEvent?): GeneralResumeBoundary? {
  return when (event) {
    is OpenCrayToolResultEvent -> GeneralResumeBoundary(
      runId = event.runId,
      taskId = event.taskId,
      checkpointKind = promptCheckpointKindFromMetadata(event.result.metadata) ?: PromptCheckpointKind.GENERAL_RESUME,
      toolName = event.result.toolName,
      promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.result.metadata),
      metadata = event.result.metadata,
      emittedAtEpochMs = event.emittedAtEpochMs,
    )

    is OpenCraySupplementEvent -> GeneralResumeBoundary(
      runId = event.runId,
      taskId = event.taskId,
      checkpointKind = promptCheckpointKindFromMetadata(event.metadata) ?: PromptCheckpointKind.GENERAL_RESUME,
      toolName = null,
      promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata),
      metadata = event.metadata,
      emittedAtEpochMs = event.emittedAtEpochMs,
    )

    is OpenCrayAssistantPhaseEvent ->
      promptCheckpointKindFromMetadata(event.metadata)?.let { checkpointKind ->
        GeneralResumeBoundary(
          runId = event.runId,
          taskId = event.taskId,
          checkpointKind = checkpointKind,
          toolName = null,
          promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata),
          metadata = event.metadata,
          emittedAtEpochMs = event.emittedAtEpochMs,
        )
      }

    else -> null
  }
}

private fun latestGeneralResumeBoundary(
  journalEntries: List<PersistedRunJournalEntry>,
  fallbackEvent: OpenCrayAgentRunEvent?,
): GeneralResumeBoundary? = journalEntries
  .asReversed()
  .asSequence()
  .mapNotNull { entry -> generalResumeBoundary(entry.payload) }
  .firstOrNull()
  ?: generalResumeBoundary(fallbackEvent)

private fun generalResumeBoundary(payload: PersistedAgentRunEvent): GeneralResumeBoundary? = when (payload.kind) {
  PersistedAgentRunEventKind.CHECKPOINT ->
    promptCheckpointKindFromMetadata(payload.resultMetadata)?.let { checkpointKind ->
      GeneralResumeBoundary(
        runId = payload.runId,
        taskId = payload.taskId,
        checkpointKind = checkpointKind,
        toolName = payload.toolName?.trim()?.takeIf(String::isNotBlank),
        promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(payload.resultMetadata),
        metadata = payload.resultMetadata,
        emittedAtEpochMs = payload.emittedAtEpochMs,
      )
    }

  else -> payload.toRuntimeEventOrNull()?.let(::generalResumeBoundary)
}

private fun generalResumeBoundary(
  runId: String,
  taskId: String,
  result: ExecutionResult?,
): GeneralResumeBoundary? {
  val metadata = result?.metadata.orEmpty()
  if (metadata.isEmpty()) {
    return null
  }
  return GeneralResumeBoundary(
    runId = runId,
    taskId = taskId,
    checkpointKind = promptCheckpointKindFromMetadata(metadata) ?: PromptCheckpointKind.GENERAL_RESUME,
    toolName = null,
    promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata),
    metadata = metadata,
    emittedAtEpochMs = result?.finishedAtEpochMs ?: return null,
  )
}

private fun promptCheckpointKindFromMetadata(
  metadata: Map<String, String>,
): PromptCheckpointKind? = when (OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata)) {
  OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST -> PromptCheckpointKind.PRE_MODEL_REQUEST
  OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED -> PromptCheckpointKind.ACTION_BATCH_PARSED
  OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED -> PromptCheckpointKind.COMMENTARY_EMITTED
  OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED -> PromptCheckpointKind.TOOL_RESULT_COMMITTED
  OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED -> PromptCheckpointKind.SUPPLEMENT_INGESTED
  null -> null
}

private fun approvalCheckpointBoundary(
  runId: String,
  taskId: String,
  runRecord: PersistedAgentRunRecord?,
  lastJournalEvent: OpenCrayAgentRunEvent?,
): ApprovalCheckpointBoundary? {
  val approvalDenial = approvalDenialResult(
    runRecord = runRecord,
    lastJournalEvent = lastJournalEvent,
  ) ?: return null
  val checkpointKind = syntheticApprovalCheckpointKind(lastJournalEvent) ?: return null
  val metadata = approvalDenial.metadata
  val json = Json { prettyPrint = false }
  return ApprovalCheckpointBoundary(
    runId = runId,
    taskId = taskId,
    checkpointKind = checkpointKind,
    toolName = approvalMetadataResumeToolName(metadata) ?: approvalMetadataToolName(metadata),
    pendingMessageId = runRecord?.pendingMessageId?.trim()?.takeIf(String::isNotBlank),
    isHighRisk = approvalMetadataIsHighRisk(
      errorCode = approvalDenial.errorCode,
      highRiskErrorCode = SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
      metadata = metadata,
    ),
    promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata),
    promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = metadata,
      json = json,
    ),
    subAgentApprovalResume = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = metadata,
      json = json,
    ),
    emittedAtEpochMs = syntheticApprovalBoundaryEpochMs(
      approvalDenial = approvalDenial,
      lastJournalEvent = lastJournalEvent,
    ),
  )
}

private fun approvalDenialResult(
  runRecord: PersistedAgentRunRecord?,
  lastJournalEvent: OpenCrayAgentRunEvent?,
): ExecutionResult? = when {
  runRecord?.lastResult.isApprovalRequiredDenial() -> runRecord?.lastResult
  lastJournalEvent is OpenCrayToolResultEvent && lastJournalEvent.result.isApprovalRequiredDenial() ->
    ExecutionResult(
      taskId = lastJournalEvent.taskId,
      status = ExecutionStatus.DENIED,
      errorCode = lastJournalEvent.result.errorCode,
      errorMessage = lastJournalEvent.result.errorMessage,
      startedAtEpochMs = lastJournalEvent.emittedAtEpochMs,
      finishedAtEpochMs = lastJournalEvent.emittedAtEpochMs,
      metadata = lastJournalEvent.result.metadata,
    )

  else -> null
}

private fun syntheticApprovalCheckpointKind(
  event: OpenCrayAgentRunEvent?,
): PromptCheckpointKind? = when (event) {
  is OpenCrayApprovalEvent -> when (event.phase) {
    OpenCrayApprovalPhase.REQUIRED -> PromptCheckpointKind.WAITING_APPROVAL
    OpenCrayApprovalPhase.APPROVED -> PromptCheckpointKind.APPROVED_PENDING_RESUME
    OpenCrayApprovalPhase.REJECTED -> PromptCheckpointKind.REJECTED_PENDING_RESUME
  }

  is OpenCrayToolResultEvent -> if (event.result.isApprovalRequiredDenial()) {
    PromptCheckpointKind.WAITING_APPROVAL
  } else {
    null
  }

  null -> PromptCheckpointKind.WAITING_APPROVAL
  else -> null
}

private fun syntheticApprovalBoundaryEpochMs(
  approvalDenial: ExecutionResult,
  lastJournalEvent: OpenCrayAgentRunEvent?,
): Long = when (lastJournalEvent) {
  is OpenCrayApprovalEvent -> lastJournalEvent.emittedAtEpochMs
  is OpenCrayToolResultEvent -> lastJournalEvent.emittedAtEpochMs
  else -> approvalDenial.finishedAtEpochMs
}

private fun ExecutionResult?.isApprovalRequiredDenial(): Boolean =
  this?.status == ExecutionStatus.DENIED &&
    (
      errorCode == SNAPSHOT_APPROVAL_REQUIRED_ERROR_CODE ||
        errorCode == SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
      )

private fun AgentToolResult.isApprovalRequiredDenial(): Boolean =
  status == com.opencray.runtime.AgentToolResultStatus.DENIED &&
    (
      errorCode == SNAPSHOT_APPROVAL_REQUIRED_ERROR_CODE ||
        errorCode == SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
      )

private const val SNAPSHOT_APPROVAL_REQUIRED_ERROR_CODE: String = "APPROVAL_REQUIRED"
private const val SNAPSHOT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE: String = "HIGH_RISK_APPROVAL_REQUIRED"
