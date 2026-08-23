package com.opencray.app

import android.util.Log
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_INITIAL
import com.opencray.core.orchestrator.EXECUTION_KIND_RETRY
import com.opencray.core.orchestrator.AgentLoop
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.METADATA_PENDING_EXECUTION_KIND
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayAgentEngine
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecision
import java.util.UUID
import java.util.concurrent.ExecutorService

internal data class AgentRunSubmission(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
  val lifecycleDiagnostics: RunLifecycleDiagnostics = RunLifecycleDiagnostics(),
)

internal data class AgentRunSnapshot(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val acceptedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val lifecycleState: QueueTaskLifecycleState?,
  val taskState: AgentTaskState?,
  val attempt: Int = 0,
  val executionOrdinal: Int = 0,
  val executionId: String? = null,
  val executionKind: String? = null,
  val pendingExecutionKind: String? = null,
  val executionStatus: ExecutionStatus? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val responseFormat: String? = null,
  val resultMetadata: Map<String, String> = emptyMap(),
  val pendingMessageId: String? = null,
  val managedProcessIds: List<String> = emptyList(),
  val managedProcesses: List<ManagedProcessSnapshot> = emptyList(),
  val runningManagedProcessCount: Int = 0,
  val hasLiveManagedProcesses: Boolean = false,
  val hasAutoResumeEligibleManagedProcesses: Boolean = false,
  val lastEvent: OpenCrayAgentRunEvent? = null,
  val lifecycleDiagnostics: RunLifecycleDiagnostics = RunLifecycleDiagnostics(),
) {
  // Host listeners observe runtime results before SessionQueue settles lifecycle transitions.
  private val hasTerminalExecutionStatus: Boolean
    get() = when (executionStatus) {
      null -> false
      ExecutionStatus.SUCCESS,
      ExecutionStatus.FAILED,
      ExecutionStatus.CANCELLED,
      ExecutionStatus.TIMEOUT,
      -> true

      ExecutionStatus.DENIED -> !isApprovalRequiredDenial
    }

  private val isApprovalRequiredDenial: Boolean
    get() = executionStatus == ExecutionStatus.DENIED &&
      (
        errorCode == RUN_ERROR_APPROVAL_REQUIRED ||
          errorCode == RUN_ERROR_HIGH_RISK_APPROVAL_REQUIRED
        )

  val isTerminal: Boolean
    get() = when (lifecycleState) {
      QueueTaskLifecycleState.RETRY_PENDING,
      QueueTaskLifecycleState.SUSPENDED,
      -> false

      QueueTaskLifecycleState.COMPLETED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> true

      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      null,
      -> hasTerminalExecutionStatus
    }

  val isActive: Boolean
    get() = !isTerminal || hasLiveManagedProcesses
}

private const val RUN_ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val RUN_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
internal const val METADATA_ACKNOWLEDGED_INTERRUPTED_PROCESS_IDS: String =
  "_runtime.acknowledgedInterruptedProcessIds"
private const val ACKNOWLEDGED_PROCESS_ID_SEPARATOR: String = "\u001f"
const val ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE: String = "PROCESS_INTERRUPTED_ON_RESTORE"
const val METADATA_RESTORED_TERMINAL_STATE: String = "restoredTerminalState"
const val METADATA_RESTORED_FROM_DURABLE_STORE: String = "restoredFromDurableStore"
const val METADATA_RUN_REPAIR_SOURCE: String = "runRepairSource"
const val RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE: String = "managed_process_restore"
const val RESTORED_TERMINAL_STATE_INTERRUPTED: String = "interrupted"
private const val RUNTIME_FLOW_DEBUG_TAG: String = "OpenCrayDiag"

private fun runtimeFlowDebug(message: String) {
  runCatching { Log.d(RUNTIME_FLOW_DEBUG_TAG, message) }
}

private fun notifyRuntimeListenersSafely(
  listenerProvider: () -> List<AgentSessionRuntimeListener>,
  callbackName: String,
  notify: (AgentSessionRuntimeListener) -> Unit,
) {
  listenerProvider().forEach { listener ->
    try {
      notify(listener)
    } catch (failure: Exception) {
      runCatching {
        Log.e(
          RUNTIME_FLOW_DEBUG_TAG,
          "runtime.listenerFailure callback=$callbackName type=${failure::class.java.name}",
        )
      }
    }
  }
}

internal class DefaultAgentSessionRuntimeManager(
  private val agentId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  private val snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStoreFactory: AgentRunRecordStoreFactory,
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
  private val promptCheckpointStoreFactory: PromptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
  private val executor: ExecutorService,
  private val subAgentRecoveryExecutor: ExecutorService = executor,
  private val runtimeLifecycleProvider: () -> HostRuntimeLifecycleDescriptor,
  private val runtimeTarget: RuntimeServiceTarget? = null,
  private val sessionOwnerLeaseStore: RuntimeSessionOwnerLeaseStore? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val sessionOwnerLeaseDurationMs: Long =
    DEFAULT_RUNTIME_SESSION_OWNER_LEASE_DURATION_MS,
) : AgentSessionRuntimeManager {
  private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
  private val sessions = linkedMapOf<String, ManagedAgentSessionHandle>()
  private val ownedSessionLeases = linkedMapOf<String, RuntimeSessionOwnerLease>()
  private val lock = Any()

  override fun forSession(sessionId: String): AgentSessionHandle = synchronized(lock) {
    acquireSessionOwnershipLocked(sessionId)
    sessions.getOrPut(sessionId) {
      try {
        ManagedAgentSessionHandle(
          sessionId = sessionId,
          agentId = agentId,
          runtimeFactory = runtimeFactory,
          snapshotStoreFactory = snapshotStoreFactory,
          runRecordStore = runRecordStoreFactory.forChatSession(sessionId),
          runEventJournalStore = runEventJournalStoreFactory.forChatSession(sessionId),
          promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(sessionId),
          executor = executor,
          subAgentRecoveryExecutor = subAgentRecoveryExecutor,
          runtimeLifecycleProvider = runtimeLifecycleProvider,
          listenerProvider = { synchronized(lock) { listeners.toList() } },
        )
      } catch (failure: Throwable) {
        releaseSessionOwnershipLocked(sessionId)
        throw failure
      }
    }.also { it.touch() }
  }

  override fun existingSession(sessionId: String): AgentSessionHandle? = synchronized(lock) {
    sessions[sessionId]
  }

  override fun sessionOwnerTarget(sessionId: String): RuntimeServiceTarget? =
    sessionOwnerLeaseStore?.loadLiveOwner(sessionId, clock())?.target

  override fun ownsSession(sessionId: String): Boolean {
    val resolvedRuntimeTarget = runtimeTarget ?: return true
    val lifecycle = runtimeLifecycleProvider()
    val owner = sessionOwnerLeaseStore?.loadLiveOwner(sessionId, clock()) ?: return false
    return owner.target == resolvedRuntimeTarget &&
      owner.processStartId == lifecycle.processStartId &&
      owner.runtimeControllerId == lifecycle.runtimeControllerId &&
      owner.durableRuntimeControllerId == lifecycle.durableRuntimeControllerId
  }

  override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = synchronized(lock) {
    listeners += listener
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun activeWorkSummary(): RuntimeOwnerWorkSummary {
    val handles = synchronized(lock) { sessions.values.toList() }
    if (handles.isEmpty()) {
      return RuntimeOwnerWorkSummary()
    }
    synchronized(lock) {
      handles.forEach { handle -> acquireSessionOwnershipLocked(handle.sessionId) }
    }
    val activeSessionIds = linkedSetOf<String>()
    val pendingWorkSessionIds = mutableListOf<String>()
    val liveManagedProcessSessionIds = mutableListOf<String>()
    val liveSubAgentSessionIds = mutableListOf<String>()
    var activeRunCount = 0

    handles.forEach { handle ->
      val runs = handle.listRuns()
      val hasPendingWork = runs.any { snapshot -> !snapshot.isTerminal }
      val hasLiveManagedProcesses = runs.any(AgentRunSnapshot::hasLiveManagedProcesses) ||
        handle.hasLiveManagedProcesses()
      val hasLiveSubAgents = handle.hasLiveSubAgentWork()
      if (hasPendingWork) {
        pendingWorkSessionIds += handle.sessionId
        activeSessionIds += handle.sessionId
      }
      if (hasLiveManagedProcesses) {
        liveManagedProcessSessionIds += handle.sessionId
        activeSessionIds += handle.sessionId
      }
      if (hasLiveSubAgents) {
        liveSubAgentSessionIds += handle.sessionId
        activeSessionIds += handle.sessionId
      }
      activeRunCount += runs.count(AgentRunSnapshot::isActive)
    }

    return RuntimeOwnerWorkSummary(
      trackedSessionCount = handles.size,
      activeRunCount = activeRunCount,
      activeSessionIds = activeSessionIds.toList(),
      pendingWorkSessionIds = pendingWorkSessionIds.distinct(),
      liveManagedProcessSessionIds = liveManagedProcessSessionIds.distinct(),
      liveSubAgentSessionIds = liveSubAgentSessionIds.distinct(),
    )
  }

  override fun release(sessionId: String) {
    val removed = synchronized(lock) {
      sessions.remove(sessionId).also {
        releaseSessionOwnershipLocked(sessionId)
      }
    }
    if (removed != null) {
      runtimeFactory.releaseSession(sessionId)
    }
  }

  override fun releaseAllSessions() {
    val releasedSessionIds = synchronized(lock) {
      val currentSessionIds = sessions.keys.toList()
      sessions.clear()
      currentSessionIds.forEach(::releaseSessionOwnershipLocked)
      currentSessionIds
    }
    releasedSessionIds.forEach(runtimeFactory::releaseSession)
  }

  override fun releaseIdleSessions() {
    val releasedSessionIds = mutableListOf<String>()
    synchronized(lock) {
      val iterator = sessions.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (
          !entry.value.hasPendingWork() &&
          !entry.value.hasLiveManagedProcesses() &&
          !entry.value.hasLiveSubAgentWork()
        ) {
          releasedSessionIds += entry.key
          iterator.remove()
          releaseSessionOwnershipLocked(entry.key)
        }
      }
    }
    releasedSessionIds.forEach(runtimeFactory::releaseSession)
  }

  private fun acquireSessionOwnershipLocked(sessionId: String) {
    val resolvedRuntimeTarget = runtimeTarget ?: return
    val store = sessionOwnerLeaseStore ?: return
    val nowEpochMs = clock()
    val attemptedLease = runtimeSessionOwnerLease(
      sessionId = sessionId,
      target = resolvedRuntimeTarget,
      runtimeOwnerLifecycle = runtimeLifecycleProvider(),
      acquiredAtEpochMs = ownedSessionLeases[sessionId]?.acquiredAtEpochMs ?: nowEpochMs,
      heartbeatAtEpochMs = nowEpochMs,
      leaseDurationMs = sessionOwnerLeaseDurationMs,
    )
    val acquiredLease = store.acquire(attemptedLease)
    if (!acquiredLease.sameRuntimeSessionOwnerAs(attemptedLease)) {
      throw RuntimeSessionOwnershipException(
        sessionId = sessionId,
        requestedTarget = resolvedRuntimeTarget,
        ownerTarget = acquiredLease.target,
      )
    }
    ownedSessionLeases[sessionId] = acquiredLease
  }

  private fun releaseSessionOwnershipLocked(sessionId: String) {
    val store = sessionOwnerLeaseStore ?: return
    val lease = ownedSessionLeases.remove(sessionId) ?: return
    store.release(lease.released(clock()))
  }
}

internal class RuntimeSessionOwnershipException(
  val sessionId: String,
  val requestedTarget: RuntimeServiceTarget,
  val ownerTarget: RuntimeServiceTarget,
) : IllegalStateException(
  "Runtime session '$sessionId' is owned by '${ownerTarget.wireValue}', " +
    "not '${requestedTarget.wireValue}'.",
)

internal class ManagedAgentSessionHandle(
  override val sessionId: String,
  private val agentId: String,
  internal val runtimeFactory: AgentSessionTaskRuntimeFactory,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  internal val runRecordStore: AgentRunRecordStore,
  private val runEventJournalStore: RunEventJournalStore,
  private val promptCheckpointStore: PromptCheckpointStore,
  private val executor: ExecutorService,
  private val subAgentRecoveryExecutor: ExecutorService,
  private val runtimeLifecycleProvider: () -> HostRuntimeLifecycleDescriptor,
  private val listenerProvider: () -> List<AgentSessionRuntimeListener>,
) : AgentSessionHandle {
  private val runLock = Any()
  internal val runRecordsById = linkedMapOf<String, ManagedRunRecord>()
  private val processingLock = Any()
  private var processing: Boolean = false
  private var processingThread: Thread? = null
  private var lastAccessEpochMs: Long = System.currentTimeMillis()
  private val snapshotStore: SessionQueueSnapshotStore = snapshotStoreFactory.forChatSession(sessionId)
  private val snapshotRestoreTransformer: RecoveryAwareQueueSnapshotStore =
    RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = snapshotStore,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { runtimeFactory.listManagedProcesses(sessionId) },
    )
  private val runtimeEventSink: OpenCrayAgentRuntimeEventSink = object : OpenCrayAgentRuntimeEventSink {
    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      val enrichedEvent = enrichEventExecutionContext(
        event = event,
        metadata = task.metadata,
      )
      recordRunEvent(enrichedEvent)
      runEventJournalStore.append(enrichedEvent)
      cleanupVisibleSubAgentRecoveryForClosedHandle(enrichedEvent)
      notifyRuntimeListenersSafely(listenerProvider, "runEvent") { listener ->
        listener.onRunEvent(sessionId = sessionId, task = task, event = enrichedEvent)
        when (enrichedEvent) {
          is com.opencray.runtime.OpenCrayToolCallEvent -> listener.onToolCall(
            sessionId = sessionId,
            task = task,
            turn = enrichedEvent.turn,
            call = enrichedEvent.call,
          )
          is com.opencray.runtime.OpenCrayToolResultEvent -> listener.onToolResult(
            sessionId = sessionId,
            task = task,
            turn = enrichedEvent.turn,
            call = enrichedEvent.call,
            result = enrichedEvent.result,
          )
          else -> Unit
        }
      }
    }

    override fun onAssistantDraftUpdated(
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      runtimeFlowDebug(
        "runtime.draftUpdated session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} len=${text.length}",
      )
      notifyRuntimeListenersSafely(listenerProvider, "assistantDraftUpdated") { listener ->
        listener.onAssistantDraftUpdated(
          sessionId = sessionId,
          task = task,
          text = text,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    override fun onAssistantDraftCleared(
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      runtimeFlowDebug(
        "runtime.draftCleared session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"}",
      )
      notifyRuntimeListenersSafely(listenerProvider, "assistantDraftCleared") { listener ->
        listener.onAssistantDraftCleared(
          sessionId = sessionId,
          task = task,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }
  }
  private val subAgentRecoveryDriver = SessionSubAgentRecoveryDriver(
    sessionId = sessionId,
    runtimeFactory = runtimeFactory,
    executor = subAgentRecoveryExecutor,
    runtimeLifecycle = runtimeLifecycleProvider(),
    runtimeEventSink = runtimeEventSink,
    callbacks = SessionSubAgentRecoveryDriverCallbacks(
      recordSubmission = ::recordDetachedRecoverySubmission,
      runStateByTaskId = ::subAgentRecoveryRunStateByTaskId,
      runStateByRunId = ::subAgentRecoveryRunStateByRunId,
      replaceLastResult = ::replaceRunLastResult,
      notifyTaskStarted = { task ->
        notifyRuntimeListenersSafely(listenerProvider, "taskStarted") { listener ->
          listener.onTaskStarted(sessionId = sessionId, task = task)
        }
      },
      finalizeTaskResult = { task, result ->
        val enrichedResult = enrichResultExecutionContext(task = task, result = result)
        recordRunResult(task = task, result = enrichedResult)
        notifyRuntimeListenersSafely(listenerProvider, "taskFinished") { listener ->
          listener.onTaskFinished(sessionId = sessionId, task = task, result = enrichedResult)
        }
        enrichedResult
      },
      prepareExecutionTask = ::taskWithSubAgentRecoveryExecutionMetadata,
      interruptedResultForTask = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.CANCELLED,
          errorCode = "SUBAGENT_RECOVERY_INTERRUPTED",
          errorMessage = "Subagent recovery execution was interrupted.",
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = System.currentTimeMillis(),
          metadata = executionMetadataFrom(task.metadata),
        )
      },
      isAwaitingManualResume = ::isSubAgentRecoveryAwaitingManualResume,
    ),
  )
  private val subAgentActorTaskDriver = SessionSubAgentActorTaskDriver(
    sessionId = sessionId,
    runtimeFactory = runtimeFactory,
    executor = subAgentRecoveryExecutor,
    runtimeLifecycle = runtimeLifecycleProvider(),
    runtimeEventSink = runtimeEventSink,
    callbacks = SessionSubAgentActorTaskDriverCallbacks(
      recordSubmission = ::recordDetachedRecoverySubmission,
      runStateByRunId = ::subAgentRecoveryRunStateByRunId,
      replaceLastResult = ::replaceRunLastResult,
      notifyTaskStarted = { task ->
        notifyRuntimeListenersSafely(listenerProvider, "taskStarted") { listener ->
          listener.onTaskStarted(sessionId = sessionId, task = task)
        }
      },
      finalizeTaskResult = { task, result ->
        val finalized = enrichResultExecutionContext(
          task = task,
          result = result,
        )
        recordRunResult(task = task, result = finalized)
        notifyRuntimeListenersSafely(listenerProvider, "taskFinished") { listener ->
          listener.onTaskFinished(sessionId = sessionId, task = task, result = finalized)
        }
        finalized
      },
      prepareExecutionTask = ::taskWithSubAgentRecoveryExecutionMetadata,
      interruptedResultForTask = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.CANCELLED,
          errorCode = "SUBAGENT_ACTOR_INTERRUPTED",
          errorMessage = "Subagent actor execution was interrupted.",
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = System.currentTimeMillis(),
          metadata = executionMetadataFrom(task.metadata),
        )
      },
      isAwaitingManualResume = ::isSubAgentRecoveryAwaitingManualResume,
    ),
  )
  private val subAgentScheduler = SessionOwnedSubAgentScheduler(
    sessionId = sessionId,
    handles = ::listSubAgentHandles,
    recoveryOperations = subAgentRecoveryDriver,
    callbacks = SessionSubAgentSchedulerCallbacks(
      persistedRecoveryRunStates = {
        synchronized(runLock) {
          runRecordsById.values
            .map { record -> record.toSubAgentRecoveryRunState() }
        }
      },
    ),
    runtimeLifecycle = runtimeLifecycleProvider(),
    isAwaitingManualResume = ::isSubAgentRecoveryAwaitingManualResume,
  )
  private val subAgentActorDriver = SessionOwnedSubAgentActorDriver(
    handles = ::listSubAgentHandles,
    recoveryOperations = subAgentRecoveryDriver,
    callbacks = SessionSubAgentActorDriverCallbacks(
      activeParentRunIds = {
        listRuns()
          .filter(AgentRunSnapshot::isActive)
          .mapTo(linkedSetOf(), AgentRunSnapshot::runId)
      },
      approvedRecoveryTaskIds = {
        val checkpointTaskIds = promptCheckpointStore.list()
          .asSequence()
          .filter { checkpoint ->
            checkpoint.checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME
          }
          .mapTo(linkedSetOf(), PersistedPromptCheckpoint::taskId)
        val handleTaskIds = listSubAgentHandles()
          .asSequence()
          .filter { handle ->
            handle.pendingApprovalDecision?.approved == true
          }
          .mapTo(linkedSetOf()) { handle ->
            syntheticSubAgentRecoveryTaskId(
              sessionId = sessionId,
              agentId = handle.agentId,
              parentRunId = handle.parentRunId,
            )
          }
        checkpointTaskIds + handleTaskIds
      },
      rejectedRecoveryTaskIds = {
        val checkpointTaskIds = promptCheckpointStore.list()
          .asSequence()
          .filter { checkpoint ->
            checkpoint.checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME
          }
          .mapTo(linkedSetOf(), PersistedPromptCheckpoint::taskId)
        val handleTaskIds = listSubAgentHandles()
          .asSequence()
          .filter { handle ->
            handle.pendingApprovalDecision?.approved == false
          }
          .mapTo(linkedSetOf()) { handle ->
            syntheticSubAgentRecoveryTaskId(
              sessionId = sessionId,
              agentId = handle.agentId,
              parentRunId = handle.parentRunId,
            )
          }
        checkpointTaskIds + handleTaskIds
      },
      recoveryTaskIdForHandle = { handle ->
        syntheticSubAgentRecoveryTaskId(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
      },
      submitActorTask = { handle ->
        subAgentActorTaskDriver.submitActorTask(
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
          createdAtEpochMs = handle.createdAtEpochMs,
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        )
      },
      resumeActorTask = { handle ->
        subAgentActorTaskDriver.requestResume(
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
          executionKind = EXECUTION_KIND_APPROVAL_RESUME,
          taskMetadataUpdates = emptyMap(),
        )
      },
      cancelActorTask = { handle ->
        subAgentActorTaskDriver.requestCancel(
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
      },
    ),
  )
  private val baseRuntime = runtimeFactory.create(
    sessionId = sessionId,
    eventSink = runtimeEventSink,
  )
  internal val loop = OpenCrayAgentEngine(
    runtime = SessionTaskRuntime { task, hooks ->
      runtimeFlowDebug(
        "runtime.taskStarted session=$sessionId task=${task.id} run=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "-"} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} type=${task.type}",
      )
      notifyRuntimeListenersSafely(listenerProvider, "taskStarted") { listener ->
        listener.onTaskStarted(sessionId = sessionId, task = task)
      }
      val result = enrichResultExecutionContext(
        task = task,
        result = baseRuntime.execute(task, hooks),
      )
      runtimeFlowDebug(
        "runtime.taskFinished session=$sessionId task=${task.id} run=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "-"} status=${result.status} error=${result.errorCode ?: "-"}",
      )
      recordRunResult(task = task, result = result)
      notifyRuntimeListenersSafely(listenerProvider, "taskFinished") { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      result
    },
    restoreTransformer = snapshotRestoreTransformer,
  ).create(
    sessionId = sessionId,
    agentId = agentId,
    snapshotStore = snapshotStore,
  )

  init {
    synchronized(runLock) {
      restorePersistedRunRecordsLocked()
      seedMissingRunRecordsLocked(loop.snapshot())
    }
    subAgentScheduler.restorePersistedVisibleTasks()
  }

  override fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String>,
  ): AgentRunSubmission {
    touch()
    val task = AgentTask(
      id = "prompt-$sessionId-${UUID.randomUUID().toString().take(8)}",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = userText,
      policyDecision = policyDecision,
      createdAtEpochMs = System.currentTimeMillis(),
      metadata = runtimeLifecycleProvider().stampTaskMetadata(metadata) + mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-$sessionId-${UUID.randomUUID().toString().take(8)}",
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
      ),
    )
    return submitTask(task)
  }

  override fun submitTask(task: AgentTask): AgentRunSubmission {
    touch()
    val acceptedAtEpochMs = maxOf(System.currentTimeMillis(), task.createdAtEpochMs)
    val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: "run-$sessionId-${UUID.randomUUID().toString().take(8)}"
    val normalizedMetadata = runtimeLifecycleProvider().stampTaskMetadata(task.metadata)
    val queuedTask = if (
      task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank) == runId
    ) {
      task.copy(metadata = normalizedMetadata)
    } else {
      task.copy(
        metadata = normalizedMetadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
        ),
      )
    }
    val submittedTask = loop.submit(queuedTask)
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = runId,
      taskId = submittedTask.id,
      acceptedAtEpochMs = acceptedAtEpochMs,
      lifecycleDiagnostics = runLifecycleDiagnosticsFrom(submittedTask.metadata),
    )
    synchronized(runLock) {
      val record = ManagedRunRecord(
        submission = submission,
        pendingMessageId = submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      runRecordsById[runId] = record
      persistRunRecordLocked(record)
    }
    runtimeFlowDebug(
      "runtime.submit session=$sessionId task=${submittedTask.id} run=$runId pending=${submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} source=${submission.lifecycleDiagnostics.submissionSource ?: "-"}",
    )
    return submission
  }

  override fun submitSubAgentRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission {
    touch()
    val existingVisibleTask = subAgentScheduler.listVisibleTasks()
      .firstOrNull { visibleTask ->
        val recoverySpec = syntheticSubAgentTaskSpec(visibleTask) as? SyntheticSubAgentRecoveryWaitTaskSpec
          ?: return@firstOrNull false
        recoverySpec.agentId == agentId && recoverySpec.parentRunId == parentRunId
      }
    subAgentActorTaskDriver.submitActorTask(
      agentId = agentId,
      parentRunId = parentRunId,
      createdAtEpochMs = createdAtEpochMs,
      submissionSource = submissionSource,
    )
    if (existingVisibleTask?.state == AgentTaskState.SUSPENDED) {
      subAgentActorTaskDriver.requestResume(
        agentId = agentId,
        parentRunId = parentRunId,
        executionKind = EXECUTION_KIND_APPROVAL_RESUME,
        taskMetadataUpdates = emptyMap(),
      )
    }
    return subAgentScheduler.submitRecoveryTask(
      agentId = agentId,
      parentRunId = parentRunId,
      taskId = taskId,
      createdAtEpochMs = createdAtEpochMs,
      submissionSource = submissionSource,
    )
  }

  override fun ensureProcessing() {
    touch()
    if (!hasRunnableWork()) {
      runtimeFlowDebug("runtime.ensureProcessingSkipped session=$sessionId reason=no_runnable_work")
      return
    }
    val shouldSchedule = synchronized(processingLock) {
      if (processing) {
        false
      } else {
        processing = true
        true
      }
    }
    if (!shouldSchedule) {
      runtimeFlowDebug("runtime.ensureProcessingSkipped session=$sessionId reason=already_processing")
      return
    }
    runtimeFlowDebug("runtime.ensureProcessingScheduled session=$sessionId")
    executor.execute {
      synchronized(processingLock) {
        processingThread = Thread.currentThread()
      }
      try {
        loop.resume()
        while (true) {
          if (!hasPendingWork()) {
            break
          }
          val results = loop.runUntilIdle()
          if (results.isEmpty()) {
            runtimeFlowDebug("runtime.ensureProcessingIdle session=$sessionId reason=no_results")
            break
          }
        }
      } finally {
        Thread.interrupted()
        val reschedule = synchronized(processingLock) {
          if (processingThread === Thread.currentThread()) {
            processingThread = null
          }
          processing = false
          hasRunnableWork()
        }
        runtimeFlowDebug("runtime.ensureProcessingFinished session=$sessionId reschedule=$reschedule")
        if (reschedule) {
          ensureProcessing()
        }
      }
    }
  }

  private fun requestSubAgentRecoveryCancel(taskId: String): Boolean {
    val cancelledHiddenActorByRecoveryTask = cancelHiddenActorForRecoveryTask(taskId)
    val cancelledHiddenActorByTaskId = subAgentActorTaskDriver.requestCancel(taskId)
    val cancelledVisibleRecovery = subAgentScheduler.requestCancel(taskId)
    return (
      cancelledHiddenActorByRecoveryTask ||
        cancelledHiddenActorByTaskId ||
        cancelledVisibleRecovery
      )
  }

  private fun requestSubAgentRecoveryResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean {
    val recoveryTaskContext = visibleRecoveryTaskContext(taskId)
    recoveryTaskContext?.let { context ->
      subAgentActorTaskDriver.submitActorTask(
        agentId = context.agentId,
        parentRunId = context.parentRunId,
        createdAtEpochMs = context.createdAtEpochMs,
        submissionSource = context.submissionSource,
      )
    }
    val actorResumed = when (recoveryTaskContext) {
      null -> subAgentActorTaskDriver.requestResume(
        taskId = taskId,
        executionKind = executionKind,
        taskMetadataUpdates = taskMetadataUpdates,
      )

      else -> subAgentActorTaskDriver.requestResume(
        agentId = recoveryTaskContext.agentId,
        parentRunId = recoveryTaskContext.parentRunId,
        executionKind = executionKind,
        taskMetadataUpdates = taskMetadataUpdates,
      )
    }
    val recoveryResumed = subAgentScheduler.requestResume(
      taskId = taskId,
      executionKind = executionKind,
      taskMetadataUpdates = taskMetadataUpdates,
    )
    return actorResumed || recoveryResumed
  }

  private fun visibleRecoveryTaskContext(taskId: String): VisibleRecoveryTaskContext? {
    val task = subAgentScheduler.listVisibleTasks()
      .firstOrNull { visibleTask -> visibleTask.id == taskId }
      ?: return null
    val recoverySpec = syntheticSubAgentTaskSpec(task) as? SyntheticSubAgentRecoveryWaitTaskSpec
      ?: return null
    return VisibleRecoveryTaskContext(
      agentId = recoverySpec.agentId,
      parentRunId = recoverySpec.parentRunId,
      createdAtEpochMs = task.createdAtEpochMs,
      submissionSource = task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE]
        ?: RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )
  }

  private fun cancelHiddenActorForRecoveryTask(taskId: String): Boolean {
    val recoveryTaskContext = visibleRecoveryTaskContext(taskId) ?: return false
    return subAgentActorTaskDriver.requestCancel(
      agentId = recoveryTaskContext.agentId,
      parentRunId = recoveryTaskContext.parentRunId,
    )
  }

  private fun taskWithSubAgentRecoveryExecutionMetadata(
    task: AgentTask,
    executionKind: String,
  ): AgentTask {
    val previousOrdinal = task.metadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull() ?: 0
    val nextOrdinal = previousOrdinal + 1
    val now = System.currentTimeMillis()
    val executionId = buildString {
      append("detached-")
      append(task.id.take(24))
      append('-')
      append(nextOrdinal)
      append('-')
      append(now)
      append('-')
      append(UUID.randomUUID().toString().take(8))
    }
    val metadata = buildMap<String, String> {
      task.metadata.forEach { (key, value) ->
        if (
          key != METADATA_EXECUTION_ID &&
          key != METADATA_EXECUTION_KIND &&
          key != METADATA_EXECUTION_ORDINAL &&
          key != METADATA_PENDING_EXECUTION_KIND
        ) {
          put(key, value)
        }
      }
      put(METADATA_EXECUTION_ID, executionId)
      put(METADATA_EXECUTION_KIND, executionKind)
      put(METADATA_EXECUTION_ORDINAL, nextOrdinal.toString())
    }
    return task.copy(
      updatedAtEpochMs = maxOf(now, task.createdAtEpochMs),
      metadata = metadata,
    )
  }

  private fun isSubAgentRecoveryAwaitingManualResume(
    result: ExecutionResult,
  ): Boolean = when {
    result.status == ExecutionStatus.DENIED &&
      (
        result.errorCode == RUN_ERROR_APPROVAL_REQUIRED ||
          result.errorCode == RUN_ERROR_HIGH_RISK_APPROVAL_REQUIRED
        ) -> true

    result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME -> true
    else -> false
  }

  override fun requestCancel(taskId: String): Boolean {
    touch()
    if (loop.requestCancel(taskId)) {
      if (isQueueTaskAwaitingCancellation(taskId)) {
        interruptProcessingThread()
      }
      return true
    }
    return requestSubAgentRecoveryCancel(taskId)
  }

  override fun requestRetry(taskId: String): Boolean {
    touch()
    val retried = synchronized(runLock) {
      val runId = loop.snapshot().tasks
        .firstOrNull { taskSnapshot -> taskSnapshot.task.id == taskId }
        ?.let { taskSnapshot -> runIdFor(taskSnapshot.task) }
        ?: runRecordsById.values
          .firstOrNull { record -> record.submission.taskId == taskId }
          ?.submission
          ?.runId
      val acknowledgedProcessIds = runId
        ?.let(runRecordsById::get)
        ?.managedProcessIds
        .orEmpty()
        .filter { processId ->
          runtimeFactory.readManagedProcess(sessionId, processId)?.isInterruptedOnRestore() == true
        }
        .toSet()
      val existingAcknowledgedProcessIds = loop.snapshot().tasks
        .firstOrNull { taskSnapshot -> taskSnapshot.task.id == taskId }
        ?.task
        ?.metadata
        ?.get(METADATA_ACKNOWLEDGED_INTERRUPTED_PROCESS_IDS)
        ?.let(::decodeAcknowledgedInterruptedProcessIds)
        .orEmpty()
      val acknowledgedMetadata = (existingAcknowledgedProcessIds + acknowledgedProcessIds)
        .takeIf(Set<String>::isNotEmpty)
        ?.let { processIds ->
          mapOf(
            METADATA_ACKNOWLEDGED_INTERRUPTED_PROCESS_IDS to
              encodeAcknowledgedInterruptedProcessIds(processIds),
          )
        }
        .orEmpty()
      if (!loop.requestRetry(taskId, taskMetadataUpdates = acknowledgedMetadata)) {
        return@synchronized false
      }
      runId?.let { resolvedRunId ->
        runRecordsById[resolvedRunId]?.let { existing ->
          val updated = existing.copy(lastResult = null)
          runRecordsById[resolvedRunId] = updated
          persistRunRecordLocked(updated)
        }
      }
      true
    }
    if (retried) {
      ensureProcessing()
    }
    return retried
  }

  override fun requestResumeTask(taskId: String): Boolean {
    return requestResumeTask(
      taskId = taskId,
      executionKind = EXECUTION_KIND_APPROVAL_RESUME,
      taskMetadataUpdates = emptyMap(),
    )
  }

  override fun requestResumeTask(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean {
    touch()
    val resumed = loop.requestResumeTask(
      taskId = taskId,
      executionKind = executionKind,
      taskMetadataUpdates = taskMetadataUpdates,
    )
    if (resumed) {
      ensureProcessing()
      return true
    }
    return requestSubAgentRecoveryResume(
      taskId = taskId,
      executionKind = executionKind,
      taskMetadataUpdates = taskMetadataUpdates,
    )
  }

  override fun listRuns(): List<AgentRunSnapshot> {
    touch()
    return currentRunSnapshots()
  }

  override fun findRun(runId: String): AgentRunSnapshot? {
    touch()
    return currentRunSnapshots().firstOrNull { snapshot -> snapshot.runId == runId }
  }

  override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? {
    touch()
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (true) {
      val snapshot = findRun(runId)
      if (snapshot?.isTerminal == true) {
        return snapshot
      }
      val now = System.currentTimeMillis()
      if (now >= deadline) {
        return snapshot
      }
      val sleepMs = minOf(RUN_WAIT_POLL_INTERVAL_MS, deadline - now).coerceAtLeast(1L)
      try {
        Thread.sleep(sleepMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return snapshot
      }
    }
  }

  override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
    if (pendingMessageIds.isEmpty()) {
      return 0
    }
    val candidateTaskIds = (
      snapshot().tasks
      .filter { taskSnapshot ->
        taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.COMPLETED &&
          taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.CANCELLED &&
          taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED &&
          taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] in pendingMessageIds
      }
      .map { taskSnapshot -> taskSnapshot.task.id } +
      listVisibleSubAgentTasks()
        .filter { task ->
          task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] in pendingMessageIds
        }
        .map(AgentTask::id)
      ).distinct()

    var cancelled = 0
    candidateTaskIds.forEach { taskId ->
      if (requestCancel(taskId)) {
        cancelled += 1
      }
    }
    return cancelled
  }

  override fun resume(): SessionLifecycleState {
    touch()
    refreshDueManagedProcessReconnectRecovery()
    val state = loop.resume()
    subAgentActorDriver.onSessionResumed()
    if (hasRunnableWork()) {
      ensureProcessing()
    }
    return state
  }

  private fun refreshDueManagedProcessReconnectRecovery() {
    val nowEpochMs = System.currentTimeMillis()
    val currentSnapshot = loop.snapshot()
    val dueReconnectHolds = currentSnapshot.tasks.filter { entry ->
      entry.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
        entry.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS] ==
        ManagedProcessContinuationBases.RECONNECT_HOLD &&
        (
          entry.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS]
            ?.toLongOrNull()
            ?.let { retryAfterEpochMs -> retryAfterEpochMs <= nowEpochMs }
            ?: true
        )
    }
    if (dueReconnectHolds.isEmpty()) {
      return
    }
    val refreshedSnapshot = snapshotRestoreTransformer.restore(
      snapshot = currentSnapshot.copy(tasks = dueReconnectHolds),
      restoreEpochMs = nowEpochMs,
    ) ?: return
    val refreshedByTaskId = refreshedSnapshot.tasks.associateBy { entry -> entry.task.id }
    dueReconnectHolds.forEach { heldEntry ->
      val refreshedEntry = refreshedByTaskId[heldEntry.task.id] ?: return@forEach
      if (
        refreshedEntry.lifecycleState != QueueTaskLifecycleState.QUEUED ||
        refreshedEntry.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS] !=
        ManagedProcessContinuationBases.CHECKPOINT_RESUME
      ) {
        return@forEach
      }
      loop.requestResumeTask(
        taskId = heldEntry.task.id,
        executionKind = EXECUTION_KIND_CHECKPOINT_RESUME,
        taskMetadataUpdates = refreshedEntry.task.metadata + CLEARED_MANAGED_PROCESS_RECONNECT_METADATA,
      )
    }
  }

  override fun snapshot(): SessionQueueSnapshot {
    touch()
    return loop.snapshot()
  }

  override fun hasPendingWork(): Boolean = currentRunSnapshots().any { snapshot -> !snapshot.isTerminal }

  private fun isQueueTaskAwaitingCancellation(taskId: String): Boolean =
    loop.snapshot().tasks.any { taskSnapshot ->
      taskSnapshot.task.id == taskId &&
        taskSnapshot.lifecycleState == QueueTaskLifecycleState.CANCEL_REQUESTED
    }

  private fun interruptProcessingThread() {
    val activeThread = synchronized(processingLock) { processingThread }
    runtimeFlowDebug(
      "runtime.requestCancelInterrupt session=$sessionId threadActive=${activeThread != null}",
    )
    activeThread?.interrupt()
  }

  private fun hasRunnableWork(): Boolean {
    val runnableTaskIds = loop.snapshot().tasks
      .asSequence()
      .filter { taskSnapshot ->
        taskSnapshot.lifecycleState == QueueTaskLifecycleState.QUEUED ||
          taskSnapshot.lifecycleState == QueueTaskLifecycleState.RETRY_PENDING
      }
      .map { taskSnapshot -> taskSnapshot.task.id }
      .toSet()
    if (runnableTaskIds.isEmpty()) {
      return false
    }
    val runSnapshotsByTaskId = currentRunSnapshots().associateBy(AgentRunSnapshot::taskId)
    return runnableTaskIds.any { taskId ->
      runSnapshotsByTaskId[taskId]?.isTerminal != true
    }
  }

  override fun listManagedProcesses(): List<ManagedProcessSnapshot> =
    runtimeFactory.listManagedProcesses(sessionId)

  override fun listSubAgentHandles(): List<SubAgentHandleState> =
    runtimeFactory.listSubAgentHandles(sessionId)

  override fun listClosedSubAgentHandles(): List<SubAgentHandleState> =
    runtimeFactory.listClosedSubAgentHandles(sessionId)

  override fun setSubAgentPendingApprovalDecision(
    agentId: String,
    parentRunId: String,
    pendingApprovalDecision: SubAgentPendingApprovalDecision?,
  ): Boolean {
    touch()
    val updatedHandle = runtimeFactory.updateSubAgentHandlePendingApprovalDecision(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      pendingApprovalDecision = pendingApprovalDecision,
    ) ?: return false
    if (updatedHandle.shouldEnsureDetachedBackgroundExecution()) {
      subAgentActorDriver.scheduleRecoverableSubAgents()
    }
    return true
  }

  override fun hasActiveSubAgentExecution(
    agentId: String,
    parentRunId: String,
  ): Boolean = runtimeFactory.hasActiveSubAgentExecution(
    sessionId = sessionId,
    agentId = agentId,
    parentRunId = parentRunId,
  )

  override fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) {
    runtimeFactory.retainKnownSubAgentParentRuns(
      sessionId = sessionId,
      parentRunIds = parentRunIds,
    )
  }

  override fun listVisibleSubAgentTasks(): List<AgentTask> =
    subAgentScheduler.listVisibleTasks()

  override fun terminateManagedProcesses(
    processIds: Set<String>,
  ): List<ManagedProcessSnapshot> = processIds
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .mapNotNull { processId ->
      runtimeFactory.terminateManagedProcess(
        sessionId = sessionId,
        processId = processId,
      )
    }

  override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> =
    terminateManagedProcesses(
      listManagedProcesses()
        .filter { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }
        .map(ManagedProcessSnapshot::processId)
        .toSet(),
    )

  fun touch() {
    lastAccessEpochMs = System.currentTimeMillis()
  }

  private fun recordDetachedRecoverySubmission(
    submission: AgentRunSubmission,
    task: AgentTask,
  ) {
    synchronized(runLock) {
      val record = ManagedRunRecord(
        submission = submission,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      runRecordsById[submission.runId] = record
      persistRunRecordLocked(record)
    }
  }

  private fun subAgentRecoveryRunStateByTaskId(taskId: String): SessionSubAgentRecoveryRunState? =
    synchronized(runLock) {
      runRecordsById[syntheticSubAgentRecoveryRunId(taskId)]
        ?.toSubAgentRecoveryRunState()
    }

  private fun subAgentRecoveryRunStateByRunId(runId: String): SessionSubAgentRecoveryRunState? =
    synchronized(runLock) {
      runRecordsById[runId]?.toSubAgentRecoveryRunState()
    }

  private fun replaceRunLastResult(
    runId: String,
    result: ExecutionResult?,
  ) {
    synchronized(runLock) {
      val existing = runRecordsById[runId] ?: return
      val updated = existing.copy(lastResult = result)
      runRecordsById[runId] = updated
      persistRunRecordLocked(updated)
    }
  }

  private fun recordRunEvent(event: OpenCrayAgentRunEvent) {
    synchronized(runLock) {
      val existing = runRecordsById[event.runId] ?: ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = event.runId,
          taskId = event.taskId,
          acceptedAtEpochMs = event.emittedAtEpochMs,
        ),
      )
      val updated = existing.copy(
        lastEvent = event,
        managedProcessIds = mergeManagedProcessIds(
          existing = existing.managedProcessIds,
          candidate = managedProcessIdFrom(event),
        ),
      )
      runRecordsById[event.runId] = updated
      persistRunRecordLocked(updated)
    }
  }

  private fun cleanupVisibleSubAgentRecoveryForClosedHandle(
    event: OpenCrayAgentRunEvent,
  ) {
    val toolResultEvent = event as? com.opencray.runtime.OpenCrayToolResultEvent ?: return
    if (!toolResultEvent.call.toolName.equals("close_agent", ignoreCase = true)) {
      return
    }
    if (toolResultEvent.result.status != com.opencray.runtime.AgentToolResultStatus.SUCCESS) {
      return
    }
    if (
      !toolResultEvent.result.metadata["closed"]
        .equals("true", ignoreCase = true)
    ) {
      return
    }
    val agentId = toolResultEvent.result.metadata["agentId"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    val parentRunId = toolResultEvent.runId
      .trim()
      .takeIf(String::isNotBlank)
      ?: return
    subAgentScheduler.requestCancel(
      syntheticSubAgentRecoveryTaskId(
        sessionId = sessionId,
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )
    subAgentActorTaskDriver.requestCancel(
      agentId = agentId,
      parentRunId = parentRunId,
    )
  }

  private fun recordRunResult(task: AgentTask, result: ExecutionResult) {
    val runId = runIdFor(task)
    synchronized(runLock) {
      val existing = runRecordsById[runId] ?: ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = runId,
          taskId = task.id,
          acceptedAtEpochMs = task.createdAtEpochMs,
        ),
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val updated = existing.copy(
        pendingMessageId = existing.pendingMessageId
          ?: task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        lastResult = result,
      )
      runRecordsById[runId] = updated
      persistRunRecordLocked(updated)
    }
    // Keep terminal checkpoint cleanup under runtime ownership across host-listener rebinds.
    if (!isSubAgentRecoveryAwaitingManualResume(result)) {
      promptCheckpointStore.remove(task.id)
    }
  }

  private fun currentRunSnapshots(): List<AgentRunSnapshot> {
    var queueSnapshot = loop.snapshot()
    val managedProcesses = listManagedProcesses()
    synchronized(runLock) {
      seedMissingRunRecordsLocked(queueSnapshot)
      val repaired = repairRestoredInterruptedRunsLocked(
        queueSnapshot = queueSnapshot,
        managedProcesses = managedProcesses,
      )
      if (repaired) {
        queueSnapshot = loop.snapshot()
      }
    }
    val managedProcessesById = managedProcesses.associateBy(ManagedProcessSnapshot::processId)
    val detachedTaskSnapshotsByRunId = detachedRunTaskSnapshots().associateBy { taskSnapshot ->
      runIdFor(taskSnapshot.task)
    }
    val taskSnapshotsByRunId = detachedTaskSnapshotsByRunId + queueSnapshot.tasks.associateBy { taskSnapshot ->
      runIdFor(taskSnapshot.task)
    }
    val records = synchronized(runLock) { runRecordsById.toMap() }
    val runIds = linkedSetOf<String>().apply {
      addAll(records.keys)
      addAll(taskSnapshotsByRunId.keys)
    }
    return runIds.map { runId ->
      val record = records[runId]
      val taskSnapshot = taskSnapshotsByRunId[runId]
      val result = visibleRunResult(
        taskSnapshot = taskSnapshot,
        result = record?.lastResult,
      )
      val executionContext = runExecutionContext(
        taskSnapshot = taskSnapshot,
        result = result,
        fallbackResult = record?.lastResult,
        lastEvent = record?.lastEvent,
      )
      val taskMetadata = taskSnapshot?.task?.metadata.orEmpty()
      val taskId = taskSnapshot?.task?.id ?: record?.submission?.taskId ?: runId
      val managedProcessIds = associatedManagedProcessIds(
        taskId = taskId,
        existingIds = record?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
      )
      val associatedProcesses = associatedManagedProcesses(
        taskId = taskId,
        existingIds = managedProcessIds,
        managedProcessesById = managedProcessesById,
        managedProcessReader = { processId -> runtimeFactory.readManagedProcess(sessionId, processId) },
      )
      val runningManagedProcessCount = associatedProcesses.count { process ->
        process.status == ManagedProcessStatus.RUNNING
      }
      val acceptedAtEpochMs = record?.submission?.acceptedAtEpochMs
        ?: taskSnapshot?.task?.createdAtEpochMs
        ?: 0L
      val updatedAtEpochMs = maxOf(
        taskSnapshot?.task?.updatedAtEpochMs ?: 0L,
        result?.finishedAtEpochMs ?: 0L,
        record?.lastEvent?.emittedAtEpochMs ?: 0L,
        associatedProcesses.maxOfOrNull(ManagedProcessSnapshot::updatedAtEpochMs) ?: 0L,
        acceptedAtEpochMs,
      )
      val projectedLifecycleState = projectedLifecycleState(
        original = taskSnapshot?.lifecycleState,
        result = result,
      )
      val projectedTaskState = projectedTaskState(
        original = taskSnapshot?.task?.state,
        result = result,
      )
      AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = acceptedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        lifecycleState = projectedLifecycleState,
        taskState = projectedTaskState,
        attempt = taskSnapshot?.attempt ?: 0,
        executionOrdinal = executionContext.executionOrdinal,
        executionId = executionContext.executionId,
        executionKind = executionContext.executionKind,
        pendingExecutionKind = executionContext.pendingExecutionKind,
        executionStatus = result?.status,
        errorCode = if (result != null) {
          result.errorCode
        } else if (shouldShowTaskSnapshotError(taskSnapshot)) {
          taskSnapshot?.lastErrorCode
        } else {
          null
        },
        errorMessage = if (result != null) {
          result.errorMessage
        } else if (shouldShowTaskSnapshotError(taskSnapshot)) {
          taskSnapshot?.lastErrorMessage
        } else {
          null
        },
        responseFormat = result?.metadata?.get("responseFormat"),
        resultMetadata = result?.metadata.orEmpty(),
        pendingMessageId = taskSnapshot?.task?.metadata
          ?.get(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID)
          ?: record?.pendingMessageId,
        managedProcessIds = managedProcessIds,
        managedProcesses = associatedProcesses,
        runningManagedProcessCount = runningManagedProcessCount,
        hasLiveManagedProcesses = runningManagedProcessCount > 0,
        hasAutoResumeEligibleManagedProcesses = associatedProcesses.any { snapshot ->
          snapshot.isAutoResumeEligibleManagedProcess()
        },
        lastEvent = record?.lastEvent,
        lifecycleDiagnostics = runLifecycleDiagnosticsFrom(
          taskMetadata = taskMetadata,
          resultMetadata = result?.metadata.orEmpty(),
          resultErrorCode = result?.errorCode,
        ),
      )
    }.sortedByDescending { snapshot -> snapshot.acceptedAtEpochMs }
  }

  private fun detachedRunTaskSnapshots(): List<SessionQueueTaskSnapshot> = (
    subAgentScheduler.listVisibleTasks() +
      subAgentActorTaskDriver.listTasks()
    ).distinctBy(AgentTask::id)
    .sortedBy(AgentTask::createdAtEpochMs)
    .mapIndexed { index, task ->
      SessionQueueTaskSnapshot(
        enqueueOrder = index.toLong(),
        task = task,
        lifecycleState = detachedTaskLifecycleState(task.state),
        executionOrdinal = task.metadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull() ?: 0,
        executionId = task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank),
        executionKind = task.metadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank),
      )
    }

  private fun managedProcessIdFrom(event: OpenCrayAgentRunEvent): String? =
    (event as? com.opencray.runtime.OpenCrayToolResultEvent)
      ?.result
      ?.metadata
      ?.get("processId")
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun mergeManagedProcessIds(
    existing: List<String>,
    candidate: String?,
  ): List<String> = listOfNotNull(candidate)
    .fold(existing) { current, processId ->
      if (processId in current) current else current + processId
    }

  private fun enrichResultExecutionContext(
    task: AgentTask,
    result: ExecutionResult,
  ): ExecutionResult {
    val executionMetadata = executionMetadataFrom(task.metadata)
    if (executionMetadata.isEmpty()) {
      return result
    }
    val mergedMetadata = result.metadata.toMutableMap()
    executionMetadata.forEach { (key, value) ->
      if (mergedMetadata[key].isNullOrBlank()) {
        mergedMetadata[key] = value
      }
    }
    return if (mergedMetadata == result.metadata) {
      result
    } else {
      result.copy(metadata = mergedMetadata)
    }
  }

  private fun executionMetadataFrom(
    metadata: Map<String, String>,
  ): Map<String, String> = buildMap {
    metadata[METADATA_EXECUTION_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { put(METADATA_EXECUTION_ID, it) }
    metadata[METADATA_EXECUTION_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { put(METADATA_EXECUTION_KIND, it) }
    metadata[METADATA_EXECUTION_ORDINAL]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { put(METADATA_EXECUTION_ORDINAL, it) }
    metadata[METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { put(METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT, it) }
  }

  private fun enrichEventExecutionContext(
    event: OpenCrayAgentRunEvent,
    metadata: Map<String, String>,
  ): OpenCrayAgentRunEvent {
    val executionId = metadata[METADATA_EXECUTION_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val executionKind = metadata[METADATA_EXECUTION_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val executionOrdinal = metadata[METADATA_EXECUTION_ORDINAL]
      ?.trim()
      ?.toIntOrNull()
    if (
      executionId == null &&
      executionKind == null &&
      executionOrdinal == null
    ) {
      return event
    }
    return event.withExecutionContext(
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
    )
  }

  private fun runExecutionContext(
    taskSnapshot: SessionQueueTaskSnapshot?,
    result: ExecutionResult?,
    fallbackResult: ExecutionResult?,
    lastEvent: OpenCrayAgentRunEvent?,
  ): RunExecutionContext {
    val taskMetadata = taskSnapshot?.task?.metadata.orEmpty()
    val resultMetadata = result?.metadata ?: fallbackResult?.metadata.orEmpty()
    val executionOrdinal = taskSnapshot?.executionOrdinal
      ?: taskMetadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull()
      ?: resultMetadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull()
      ?: lastEvent?.executionOrdinal
      ?: 0
    val executionId = if (taskSnapshot != null) {
      taskSnapshot.executionId
        ?: taskMetadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
    } else {
      resultMetadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
        ?: lastEvent?.executionId?.trim()?.takeIf(String::isNotBlank)
    }
    val executionKind = if (taskSnapshot != null) {
      taskSnapshot.executionKind
        ?: taskMetadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
    } else {
      resultMetadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
        ?: lastEvent?.executionKind?.trim()?.takeIf(String::isNotBlank)
    }
    val pendingExecutionKind = taskMetadata[METADATA_PENDING_EXECUTION_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    return RunExecutionContext(
      executionOrdinal = executionOrdinal,
      executionId = executionId,
      executionKind = executionKind,
      pendingExecutionKind = pendingExecutionKind,
    )
  }

  private fun associatedManagedProcessIds(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
  ): List<String> = (
    existingIds +
      managedProcessesById.values
        .asSequence()
        .filter { snapshot -> snapshot.taskId == taskId }
        .map(ManagedProcessSnapshot::processId)
        .toList()
    ).distinct()

  internal fun associatedManagedProcesses(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
    managedProcessReader: ((String) -> ManagedProcessSnapshot?)? = null,
  ): List<ManagedProcessSnapshot> = associatedManagedProcessIds(
    taskId = taskId,
    existingIds = existingIds,
    managedProcessesById = managedProcessesById,
  ).mapNotNull { processId ->
    managedProcessesById[processId] ?: managedProcessReader?.invoke(processId)
  }

  private fun projectedLifecycleState(
    original: QueueTaskLifecycleState?,
    result: ExecutionResult?,
  ): QueueTaskLifecycleState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalLifecycle(original))
  ) {
    QueueTaskLifecycleState.FAILED
  } else if (original != null) {
    original
  } else {
    terminalLifecycleState(result)
  }

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalTaskState(original))
  ) {
    AgentTaskState.FAILED
  } else if (original != null) {
    original
  } else {
    terminalTaskState(result)
  }

  internal fun isInterruptedOnRestoreResult(result: ExecutionResult?): Boolean =
    result?.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  internal fun isTerminalLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
    QueueTaskLifecycleState.COMPLETED,
    QueueTaskLifecycleState.FAILED,
    QueueTaskLifecycleState.CANCELLED,
    -> true

    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.RETRY_PENDING,
    QueueTaskLifecycleState.SUSPENDED,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> false
  }

  private fun isTerminalTaskState(state: AgentTaskState): Boolean = when (state) {
    AgentTaskState.COMPLETED,
    AgentTaskState.CANCELLED,
    AgentTaskState.FAILED,
    -> true

    AgentTaskState.QUEUED,
    AgentTaskState.RUNNING,
    AgentTaskState.SUSPENDED,
    -> false
  }

  private fun terminalLifecycleState(result: ExecutionResult?): QueueTaskLifecycleState? = when {
    result == null -> null
    isAwaitingExplicitResumeResult(result) -> null
    result.status == ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
    result.status == ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
    result.status == ExecutionStatus.FAILED ||
      result.status == ExecutionStatus.TIMEOUT ||
      result.status == ExecutionStatus.DENIED
    -> QueueTaskLifecycleState.FAILED

    else -> null
  }

  private fun terminalTaskState(result: ExecutionResult?): AgentTaskState? = when {
    result == null -> null
    isAwaitingExplicitResumeResult(result) -> null
    result.status == ExecutionStatus.SUCCESS -> AgentTaskState.COMPLETED
    result.status == ExecutionStatus.CANCELLED -> AgentTaskState.CANCELLED
    result.status == ExecutionStatus.FAILED ||
      result.status == ExecutionStatus.TIMEOUT ||
      result.status == ExecutionStatus.DENIED
    -> AgentTaskState.FAILED

    else -> null
  }

  private fun isAwaitingExplicitResumeResult(result: ExecutionResult): Boolean = when {
    result.status == ExecutionStatus.DENIED &&
      (
        result.errorCode == RUN_ERROR_APPROVAL_REQUIRED ||
          result.errorCode == RUN_ERROR_HIGH_RISK_APPROVAL_REQUIRED
        ) -> true

    result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME -> true
    else -> false
  }

  private fun detachedTaskLifecycleState(state: AgentTaskState): QueueTaskLifecycleState = when (state) {
    AgentTaskState.QUEUED -> QueueTaskLifecycleState.QUEUED
    AgentTaskState.RUNNING -> QueueTaskLifecycleState.RUNNING
    AgentTaskState.SUSPENDED -> QueueTaskLifecycleState.SUSPENDED
    AgentTaskState.COMPLETED -> QueueTaskLifecycleState.COMPLETED
    AgentTaskState.FAILED -> QueueTaskLifecycleState.FAILED
    AgentTaskState.CANCELLED -> QueueTaskLifecycleState.CANCELLED
  }

  private fun visibleRunResult(
    taskSnapshot: SessionQueueTaskSnapshot?,
    result: ExecutionResult?,
  ): ExecutionResult? {
    if (taskSnapshot == null || result == null) {
      return result
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

  private fun shouldShowTaskSnapshotError(taskSnapshot: SessionQueueTaskSnapshot?): Boolean =
    when (taskSnapshot?.lifecycleState) {
      QueueTaskLifecycleState.SUSPENDED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> true

      else -> false
    }

  internal fun ManagedProcessSnapshot.isInterruptedOnRestore(): Boolean =
    errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  internal fun ManagedProcessSnapshot.isTerminalAfterRestore(): Boolean = status.isTerminal

  internal fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private data class VisibleRecoveryTaskContext(
    val agentId: String,
    val parentRunId: String,
    val createdAtEpochMs: Long,
    val submissionSource: String,
  )

  private fun ManagedRunRecord.toSubAgentRecoveryRunState(): SessionSubAgentRecoveryRunState =
    SessionSubAgentRecoveryRunState(
      submission = submission,
      lastResult = lastResult,
    )

  private data class RunExecutionContext(
    val executionOrdinal: Int,
    val executionId: String?,
    val executionKind: String?,
    val pendingExecutionKind: String?,
  )

  private companion object {
    const val RUN_WAIT_POLL_INTERVAL_MS: Long = 50L
    val CLEARED_MANAGED_PROCESS_RECONNECT_METADATA: Map<String, String> = mapOf(
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to "",
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS to "",
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE to "",
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to "",
      RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT to "",
    )
  }
}

private fun encodeAcknowledgedInterruptedProcessIds(processIds: Set<String>): String =
  processIds
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .sorted()
    .joinToString(ACKNOWLEDGED_PROCESS_ID_SEPARATOR)

internal fun decodeAcknowledgedInterruptedProcessIds(encoded: String): Set<String> =
  encoded
    .split(ACKNOWLEDGED_PROCESS_ID_SEPARATOR)
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

private fun OpenCrayAgentRunEvent.withExecutionContext(
  executionId: String?,
  executionOrdinal: Int?,
  executionKind: String?,
): OpenCrayAgentRunEvent = when (this) {
  is com.opencray.runtime.OpenCrayLifecycleEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayAssistantEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCraySupplementEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayApprovalEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCraySubAgentEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayToolCallEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayToolResultEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayMemoryWriteEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayMemoryRetrievalEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
  is com.opencray.runtime.OpenCrayCancellationEvent -> copy(
    executionId = executionId ?: this.executionId,
    executionOrdinal = executionOrdinal ?: this.executionOrdinal,
    executionKind = executionKind ?: this.executionKind,
  )
}
