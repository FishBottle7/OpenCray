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
import com.opencray.core.orchestrator.SessionQueueRestoreTransformer
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
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
) : SessionQueueSnapshotStore, SessionQueueRestoreTransformer {
  private val promptResumeJson: Json = Json { prettyPrint = false }

  override fun load(): SessionQueueSnapshot? = restore(
    snapshot = delegate.load(),
    restoreEpochMs = clock(),
  )

  override fun restore(
    snapshot: SessionQueueSnapshot?,
    restoreEpochMs: Long,
  ): SessionQueueSnapshot? {
    snapshot ?: return null
    if (snapshot.tasks.isEmpty()) {
      return snapshot
    }
    val runRecordsByRunId = runRecordStore.list()
      .associateBy(PersistedAgentRunRecord::runId)
      .toMutableMap()
    val checkpointsByTaskId = promptCheckpointStore.list()
      .associateBy(PersistedPromptCheckpoint::taskId)
      .toMutableMap()
    val managedProcessesById = managedProcessesProvider().associateBy(ManagedProcessSnapshot::processId)

    var changed = false
    val rewrittenTasks = snapshot.tasks.map { entry ->
      val runId = runIdFor(entry.task)
      var runRecord = runRecordsByRunId[runId]
      val journalEntries = runEventJournalStore.listForRun(runId)
      val lastJournalEvent = journalEntries.latestRuntimeEventOrNull()
        ?: runRecord?.lastEvent?.toRuntimeEventOrNull()
      val repairedRunRecord = repairTerminalResultIfNeeded(
        entry = entry,
        runId = runId,
        runRecord = runRecord,
        journalEntries = journalEntries,
        fallbackEvent = lastJournalEvent,
      )
      if (repairedRunRecord != null) {
        runRecord = repairedRunRecord
        runRecordsByRunId[runId] = repairedRunRecord
      }
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
        durableResult = runRecord?.lastResult,
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
        appendRecoveryJournalEntryIfNeeded(
          runId = runId,
          originalEntry = entry,
          rewrittenEntry = rewrittenEntry,
          recoveryPlan = recoveryPlan,
          restoreEpochMs = restoreEpochMs,
          existingEntries = journalEntries,
        )
      }
      if (recoveryPlan?.action == RunRecoveryAction.RESTORE_TERMINAL_RESULT) {
        promptCheckpointStore.remove(entry.task.id)
        checkpointsByTaskId.remove(entry.task.id)
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

  private fun repairTerminalResultIfNeeded(
    entry: SessionQueueTaskSnapshot,
    runId: String,
    runRecord: PersistedAgentRunRecord?,
    journalEntries: List<PersistedRunJournalEntry>,
    fallbackEvent: OpenCrayAgentRunEvent?,
  ): PersistedAgentRunRecord? {
    if (runRecord?.lastResult.isRestorableTerminalResult()) {
      return null
    }
    val finalAssistantEvent = latestFinalizationAssistantEvent(
      journalEntries = journalEntries,
      fallbackEvent = fallbackEvent,
    ) ?: return null
    val repairedResult = synthesizedTerminalSuccessResult(
      entry = entry,
      runRecord = runRecord,
      event = finalAssistantEvent,
    ) ?: return null
    val repairedRunRecord = PersistedAgentRunRecord(
      runId = runId,
      taskId = entry.task.id,
      acceptedAtEpochMs = runRecord?.acceptedAtEpochMs ?: entry.task.createdAtEpochMs,
      pendingMessageId = runRecord?.pendingMessageId
        ?: entry.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      managedProcessIds = runRecord?.managedProcessIds.orEmpty(),
      lastResult = repairedResult,
      lastEvent = finalAssistantEvent.toPersistedRecord(),
    )
    runRecordStore.upsert(repairedRunRecord)
    return repairedRunRecord
  }

  private fun rewriteForRecoveryPlan(
    entry: SessionQueueTaskSnapshot,
    runRecord: PersistedAgentRunRecord?,
    checkpoint: PersistedPromptCheckpoint?,
    recoveryPlan: RunRecoveryPlan?,
    restoreEpochMs: Long,
  ): SessionQueueTaskSnapshot = when (recoveryPlan?.action) {
    RunRecoveryAction.RESTORE_TERMINAL_RESULT ->
      if (canRestoreTerminalResult(entry, runRecord?.lastResult)) {
        rewriteTerminalResult(
          entry = entry,
          result = requireNotNull(runRecord?.lastResult),
          restoreEpochMs = restoreEpochMs,
          recoveryPlan = recoveryPlan,
          checkpoint = checkpoint,
        )
      } else {
        entry
      }

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
              checkpoint = checkpoint,
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
              checkpoint = checkpoint,
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
              checkpoint = checkpoint,
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
              checkpoint = checkpoint,
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
              checkpoint = checkpoint,
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
              checkpoint = checkpoint,
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

  private fun rewriteTerminalResult(
    entry: SessionQueueTaskSnapshot,
    result: ExecutionResult,
    restoreEpochMs: Long,
    recoveryPlan: RunRecoveryPlan,
    checkpoint: PersistedPromptCheckpoint?,
  ): SessionQueueTaskSnapshot {
    val lifecycleState = when (result.status) {
      ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
      ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> QueueTaskLifecycleState.FAILED
      ExecutionStatus.DENIED -> entry.lifecycleState
    }
    val taskState = when (lifecycleState) {
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      else -> entry.task.state
    }
    val clearedErrorCode = when (result.status) {
      ExecutionStatus.SUCCESS -> null
      ExecutionStatus.CANCELLED -> result.errorCode
      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> result.errorCode ?: entry.lastErrorCode
      ExecutionStatus.DENIED -> entry.lastErrorCode
    }
    val clearedErrorMessage = when (result.status) {
      ExecutionStatus.SUCCESS -> null
      ExecutionStatus.CANCELLED -> result.errorMessage
      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> result.errorMessage ?: entry.lastErrorMessage
      ExecutionStatus.DENIED -> entry.lastErrorMessage
    }
    return entry.copy(
      lifecycleState = lifecycleState,
      task = entry.task.copy(
        state = taskState,
        updatedAtEpochMs = maxOf(
          entry.task.updatedAtEpochMs,
          entry.task.createdAtEpochMs,
          result.finishedAtEpochMs,
          restoreEpochMs,
        ),
        metadata = rewrittenTaskMetadata(
          entry = entry,
          restoreEpochMs = restoreEpochMs,
          recoveryPlan = recoveryPlan,
          checkpoint = checkpoint,
        ),
      ),
      lastErrorCode = clearedErrorCode,
      lastErrorMessage = clearedErrorMessage,
    )
  }

  private fun canResumeFromCheckpoint(entry: SessionQueueTaskSnapshot): Boolean = when {
    isRestoreInterruptedLifecycle(entry.lifecycleState) -> true
    entry.lifecycleState == QueueTaskLifecycleState.QUEUED -> true
    entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED -> true
    isRestartRestoreFailure(entry) -> true
    else -> false
  }

  private fun canRestoreTerminalResult(
    entry: SessionQueueTaskSnapshot,
    result: ExecutionResult?,
  ): Boolean {
    val terminalResult = result ?: return false
    if (!terminalResult.isRestorableTerminalResult()) {
      return false
    }
    return when (entry.lifecycleState) {
      QueueTaskLifecycleState.COMPLETED ->
        terminalResult.status != ExecutionStatus.SUCCESS || entry.task.state != AgentTaskState.COMPLETED
      QueueTaskLifecycleState.CANCELLED ->
        terminalResult.status != ExecutionStatus.CANCELLED || entry.task.state != AgentTaskState.CANCELLED
      QueueTaskLifecycleState.FAILED ->
        terminalResult.status !in setOf(ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT) ||
          entry.task.state != AgentTaskState.FAILED ||
          entry.lastErrorCode != terminalResult.errorCode ||
          entry.lastErrorMessage != terminalResult.errorMessage
      else -> true
    }
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
    val autoResumeEligibleManagedProcessCount = managedProcesses.count { snapshot ->
      snapshot.isAutoResumeEligibleManagedProcess()
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
      hasAutoResumeEligibleManagedProcesses = autoResumeEligibleManagedProcessCount > 0,
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
    checkpoint: PersistedPromptCheckpoint? = null,
  ): Map<String, String> {
    val shouldStampRestore = shouldStampRestoreMetadata(
      entry = entry,
      recoveryPlan = recoveryPlan,
    )
    val recoveryReason = recoveryPlan
      ?.reasonCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val recoveredCheckpointId = recoveredCheckpointIdFor(
      recoveryPlan = recoveryPlan,
      checkpoint = checkpoint,
    )
    return buildMap {
      putAll(
        entry.task.metadata.filterKeys { key ->
          key != METADATA_QUEUE_RESTORE_EPOCH_MS &&
            key != METADATA_PREVIOUS_LIFECYCLE_STATE &&
            key != METADATA_RECOVERY_REASON &&
            key != RunLifecycleMetadataKeys.RUN_ATTEMPT &&
            key != RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE &&
            key != RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION &&
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
        put(
          RunLifecycleMetadataKeys.RUN_ATTEMPT,
          restoredRunAttempt(
            entry = entry,
            recoveryReason = recoveryReason,
            recoveredCheckpointId = recoveredCheckpointId,
          ).toString(),
        )
        recoveryReason?.let { reasonCode ->
          put(METADATA_RECOVERY_REASON, reasonCode)
        }
        recoveredCheckpointId?.let { checkpointId ->
          put(RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID, checkpointId)
        }
        putRuntimeExecutionOwnershipMetadata(entry.task.metadata)
        putAll(managedProcessRecoveryMetadata(recoveryPlan))
      }
    }
  }

  private fun MutableMap<String, String>.putRuntimeExecutionOwnershipMetadata(
    sourceMetadata: Map<String, String>,
  ) {
    put(
      RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER,
      sourceMetadata[RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
    )
    put(
      RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE,
      sourceMetadata[RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: false.toString(),
    )
  }

  private fun managedProcessRecoveryMetadata(
    recoveryPlan: RunRecoveryPlan?,
  ): Map<String, String> {
    val plan = recoveryPlan ?: return emptyMap()
    return buildMap {
      plan.managedProcessContinuationBasis
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { basis ->
          put(RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS, basis)
        }
      plan.managedProcessRestoreScope
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { restoreScope ->
          put(RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE, restoreScope)
        }
      plan.managedProcessRestoreDecision
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { restoreDecision ->
          put(RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION, restoreDecision)
        }
      if (plan.action != RunRecoveryAction.RESUME_RECONNECT_PROCESS) {
        return@buildMap
      }
      plan.managedProcessReconnectProcessIds
        .takeIf { processIds -> processIds.isNotEmpty() }
        ?.let { processIds ->
          put(
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS,
            processIds.joinToString(","),
          )
        }
      plan.managedProcessReconnectStatus
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { status ->
          put(RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS, status)
        }
      plan.managedProcessReconnectRecoveryState
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { recoveryState ->
          put(RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE, recoveryState)
        }
      plan.managedProcessReconnectRetryAfterEpochMs
        ?.let { retryAfterEpochMs ->
          put(
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS,
            retryAfterEpochMs.toString(),
          )
        }
      plan.managedProcessReconnectAttemptCount
        ?.let { attemptCount ->
          put(
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT,
            attemptCount.toString(),
          )
        }
    }
  }

  private fun restoredRunAttempt(
    entry: SessionQueueTaskSnapshot,
    recoveryReason: String?,
    recoveredCheckpointId: String?,
  ): Int {
    val existingRunAttempt = entry.task.metadata[RunLifecycleMetadataKeys.RUN_ATTEMPT]
      ?.trim()
      ?.toIntOrNull()
      ?.takeIf { attempt -> attempt > 0 }
    val previousRecoveryReason = entry.task.metadata[METADATA_RECOVERY_REASON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val previousCheckpointId = entry.task.metadata[RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (
      existingRunAttempt != null &&
      previousRecoveryReason == recoveryReason &&
      previousCheckpointId == recoveredCheckpointId
    ) {
      return existingRunAttempt
    }
    return (existingRunAttempt ?: DEFAULT_RUN_ATTEMPT) + 1
  }

  private fun recoveredCheckpointIdFor(
    recoveryPlan: RunRecoveryPlan?,
    checkpoint: PersistedPromptCheckpoint?,
  ): String? {
    val checkpointId = checkpoint
      ?.checkpointId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    return when (recoveryPlan?.action) {
      RunRecoveryAction.RESUME_FROM_CHECKPOINT,
      RunRecoveryAction.RESUME_WAITING_FOR_APPROVAL,
      RunRecoveryAction.RESUME_WAITING_FOR_USER,
      RunRecoveryAction.STOP_REJECTED_AWAITING_DIRECTION,
      -> checkpointId

      RunRecoveryAction.RESTORE_TERMINAL_RESULT,
      RunRecoveryAction.RESUME_RECONNECT_PROCESS,
      RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED,
      null,
      -> null
    }
  }

  private fun appendRecoveryJournalEntryIfNeeded(
    runId: String,
    originalEntry: SessionQueueTaskSnapshot,
    rewrittenEntry: SessionQueueTaskSnapshot,
    recoveryPlan: RunRecoveryPlan?,
    restoreEpochMs: Long,
    existingEntries: List<PersistedRunJournalEntry>,
  ) {
    recoveryPlan ?: return
    val metadata = recoveryJournalMetadata(
      originalEntry = originalEntry,
      rewrittenEntry = rewrittenEntry,
      recoveryPlan = recoveryPlan,
      restoreEpochMs = restoreEpochMs,
    )
    if (metadata.isEmpty()) {
      return
    }
    val basis = recoveryJournalBasis(metadata)
    val alreadyStamped = existingEntries.any { entry ->
      entry.kind == PersistedAgentRunEventKind.RECOVERY &&
        recoveryJournalBasis(entry.payload.resultMetadata) == basis
    }
    if (alreadyStamped) {
      return
    }
    runEventJournalStore.appendRecovery(
      runId = runId,
      taskId = rewrittenEntry.task.id,
      emittedAtEpochMs = restoreEpochMs,
      metadata = metadata,
    )
  }

  private fun recoveryJournalMetadata(
    originalEntry: SessionQueueTaskSnapshot,
    rewrittenEntry: SessionQueueTaskSnapshot,
    recoveryPlan: RunRecoveryPlan,
    restoreEpochMs: Long,
  ): Map<String, String> = buildMap {
    put(RunLifecycleMetadataKeys.RECOVERY_ACTION, recoveryPlan.action.name.lowercase())
    put(
      METADATA_QUEUE_RESTORE_EPOCH_MS,
      rewrittenEntry.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: restoreEpochMs.toString(),
    )
    put(
      METADATA_PREVIOUS_LIFECYCLE_STATE,
      rewrittenEntry.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: previousLifecycleState(originalEntry),
    )
    val recoveryReason = rewrittenEntry.task.metadata[METADATA_RECOVERY_REASON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: recoveryPlan.reasonCode.trim().takeIf(String::isNotBlank)
    recoveryReason?.let { reason -> put(METADATA_RECOVERY_REASON, reason) }
    rewrittenEntry.task.metadata[RunLifecycleMetadataKeys.RUN_ATTEMPT]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { runAttempt -> put(RunLifecycleMetadataKeys.RUN_ATTEMPT, runAttempt) }
    rewrittenEntry.task.metadata[RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { checkpointId -> put(RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID, checkpointId) }
    copyTrimmedMetadata(
      source = rewrittenEntry.task.metadata,
      key = RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER,
    )
    copyTrimmedMetadata(
      source = rewrittenEntry.task.metadata,
      key = RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE,
    )
    putAll(managedProcessRecoveryMetadata(recoveryPlan))
  }

  private fun MutableMap<String, String>.copyTrimmedMetadata(
    source: Map<String, String>,
    key: String,
  ) {
    source[key]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { value -> put(key, value) }
  }

  private fun recoveryJournalBasis(metadata: Map<String, String>): Map<String, String> =
    RECOVERY_JOURNAL_BASIS_KEYS.mapNotNull { key ->
      metadata[key]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value -> key to value }
    }.toMap()

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

  private fun latestFinalizationAssistantEvent(
    journalEntries: List<PersistedRunJournalEntry>,
    fallbackEvent: OpenCrayAgentRunEvent?,
  ): OpenCrayAssistantPhaseEvent? = journalEntries
    .asReversed()
    .asSequence()
    .mapNotNull { entry ->
      entry.payload.toRuntimeEventOrNull() as? OpenCrayAssistantPhaseEvent
    }
    .firstOrNull(::hasFinalizationBoundary)
    ?: (fallbackEvent as? OpenCrayAssistantPhaseEvent)?.takeIf(::hasFinalizationBoundary)

  private fun synthesizedTerminalSuccessResult(
    entry: SessionQueueTaskSnapshot,
    runRecord: PersistedAgentRunRecord?,
    event: OpenCrayAssistantPhaseEvent,
  ): ExecutionResult? {
    if (
      event.text.isBlank() &&
      !event.metadata.containsKey(OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON)
    ) {
      return null
    }
    val startedAtEpochMs = minOf(
      runRecord?.acceptedAtEpochMs ?: entry.task.createdAtEpochMs,
      event.emittedAtEpochMs,
    )
    return ExecutionResult(
      taskId = entry.task.id,
      status = ExecutionStatus.SUCCESS,
      stdout = event.text,
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = maxOf(startedAtEpochMs, event.emittedAtEpochMs),
      metadata = buildMap {
        putAll(event.metadata)
        event.responseFormat
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { responseFormat ->
            if (!containsKey("responseFormat")) {
              put("responseFormat", responseFormat)
            }
          }
        val executionId = entry.executionId
          ?: entry.task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
        if (executionId != null && !containsKey(METADATA_EXECUTION_ID)) {
          put(METADATA_EXECUTION_ID, executionId)
        }
        if (entry.executionOrdinal > 0 && !containsKey(METADATA_EXECUTION_ORDINAL)) {
          put(METADATA_EXECUTION_ORDINAL, entry.executionOrdinal.toString())
        }
        val executionKind = entry.executionKind
          ?: entry.task.metadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
        if (executionKind != null && !containsKey(METADATA_EXECUTION_KIND)) {
          put(METADATA_EXECUTION_KIND, executionKind)
        }
      },
    )
  }

  private fun hasFinalizationBoundary(event: OpenCrayAssistantPhaseEvent): Boolean =
    event.isFinal &&
      OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata) ==
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE

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

  private companion object {
    private const val DEFAULT_RUN_ATTEMPT: Int = 1
    private val RECOVERY_JOURNAL_BASIS_KEYS: Set<String> = setOf(
      RunLifecycleMetadataKeys.RECOVERY_ACTION,
      METADATA_RECOVERY_REASON,
      RunLifecycleMetadataKeys.RUN_ATTEMPT,
      RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE,
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION,
    )
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
  OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE -> PromptCheckpointKind.FINALIZATION_COMPLETE
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
