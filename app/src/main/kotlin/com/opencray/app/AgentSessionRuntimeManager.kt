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
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.METADATA_PENDING_EXECUTION_KIND
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayAgentEngine
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

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

private fun SubAgentHandleState.hasLiveBackgroundExecution(): Boolean = when (snapshot.state) {
  SubAgentExecutionState.BACKGROUND_QUEUED,
  SubAgentExecutionState.BACKGROUND_RUNNING,
  -> true

  else -> false
}

private const val RUN_ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val RUN_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
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

internal interface AgentSessionRuntimeManager {
  fun forSession(sessionId: String): AgentSessionHandle

  fun observe(listener: AgentSessionRuntimeListener): () -> Unit

  fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()

  fun release(sessionId: String)

  fun releaseIdleSessions()
}

internal interface AgentSessionHandle {
  val sessionId: String

  fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: PolicyDecision,
    metadata: Map<String, String> = emptyMap(),
  ): AgentRunSubmission

  fun submitTask(task: AgentTask): AgentRunSubmission =
    throw UnsupportedOperationException("submitTask is not supported by this runtime handle.")

  fun submitDetachedControlTask(task: AgentTask): AgentRunSubmission = submitTask(task)

  fun ensureProcessing()

  fun requestCancel(taskId: String): Boolean

  fun requestRetry(taskId: String): Boolean

  fun requestResumeTask(taskId: String): Boolean

  fun requestResumeTask(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = requestResumeTask(taskId)

  fun listRuns(): List<AgentRunSnapshot>

  fun findRun(runId: String): AgentRunSnapshot?

  fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot?

  fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int

  fun resume(): SessionLifecycleState

  fun snapshot(): SessionQueueSnapshot

  fun hasPendingWork(): Boolean

  fun listManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

  fun hasLiveManagedProcesses(): Boolean =
    listManagedProcesses().any { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }

  fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

  fun listSubAgentHandles(): List<SubAgentHandleState> = emptyList()

  fun hasLiveSubAgentWork(): Boolean =
    listSubAgentHandles().any { handle -> handle.hasLiveBackgroundExecution() }

  fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) = Unit

  fun listDetachedControlTasks(): List<AgentTask> = emptyList()

  fun submitDetachedSubAgentRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission = submitDetachedControlTask(
    detachedSubAgentRecoveryWaitTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      taskId = taskId,
      createdAtEpochMs = createdAtEpochMs,
      metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
        submissionSource = submissionSource,
      ),
    ),
  )

  fun ensureRecoverableDetachedSubAgentTasks(): Int = 0
}

internal interface AgentSessionRuntimeListener {
  fun onTaskStarted(sessionId: String, task: AgentTask) = Unit

  fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) = Unit

  fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) = Unit

  fun onAssistantDraftUpdated(
    sessionId: String,
    task: AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ) = Unit

  fun onAssistantDraftCleared(
    sessionId: String,
    task: AgentTask,
    emittedAtEpochMs: Long,
  ) = Unit

  fun onToolCall(sessionId: String, task: AgentTask, turn: Int, call: AgentToolCall) = Unit

  fun onToolResult(sessionId: String, task: AgentTask, turn: Int, call: AgentToolCall, result: AgentToolResult) = Unit
}

internal interface AgentSessionTaskRuntimeFactory {
  fun create(
    sessionId: String,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): SessionTaskRuntime

  fun releaseSession(sessionId: String) = Unit

  fun listManagedProcesses(sessionId: String): List<ManagedProcessSnapshot> = emptyList()

  fun readManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = listManagedProcesses(sessionId)
    .firstOrNull { snapshot -> snapshot.processId == processId }

  fun terminateManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = null

  fun executeDetachedControlTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): ExecutionResult? = null

  fun executeDetachedSubAgentRecoveryTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult? = executeDetachedControlTask(
    sessionId = sessionId,
    task = task,
    hooks = hooks,
    eventSink = eventSink,
  )

  fun cancelActiveSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun listSubAgentHandles(sessionId: String): List<SubAgentHandleState> = emptyList()

  fun retainKnownSubAgentParentRuns(sessionId: String, parentRunIds: Set<String>) = Unit
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
  private val runtimeLifecycle: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
) : AgentSessionRuntimeManager {
  private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
  private val sessions = linkedMapOf<String, ManagedAgentSessionHandle>()
  private val lock = Any()

  override fun forSession(sessionId: String): AgentSessionHandle = synchronized(lock) {
    sessions.getOrPut(sessionId) {
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
        runtimeLifecycle = runtimeLifecycle,
        listenerProvider = { synchronized(lock) { listeners.toList() } },
      )
    }.also { it.touch() }
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
      sessions.remove(sessionId)
    }
    if (removed != null) {
      runtimeFactory.releaseSession(sessionId)
    }
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
        }
      }
    }
    releasedSessionIds.forEach(runtimeFactory::releaseSession)
  }
}

private class ManagedAgentSessionHandle(
  override val sessionId: String,
  private val agentId: String,
  private val runtimeFactory: AgentSessionTaskRuntimeFactory,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStore: AgentRunRecordStore,
  private val runEventJournalStore: RunEventJournalStore,
  private val promptCheckpointStore: PromptCheckpointStore,
  private val executor: ExecutorService,
  private val subAgentRecoveryExecutor: ExecutorService,
  private val runtimeLifecycle: HostRuntimeLifecycleDescriptor,
  private val listenerProvider: () -> List<AgentSessionRuntimeListener>,
) : AgentSessionHandle {
  private val runLock = Any()
  private val runRecordsById = linkedMapOf<String, ManagedRunRecord>()
  private val detachedControlLock = Any()
  private val detachedControlTasksByTaskId = linkedMapOf<String, DetachedControlTaskState>()
  private val processingLock = Any()
  private var processing: Boolean = false
  private var processingThread: Thread? = null
  private var lastAccessEpochMs: Long = System.currentTimeMillis()
  private val snapshotStore: SessionQueueSnapshotStore = RecoveryAwareQueueSnapshotStore(
    sessionId = sessionId,
    delegate = snapshotStoreFactory.forChatSession(sessionId),
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
      listenerProvider().forEach { listener ->
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
        "runtime.draftUpdated session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} len=${text.length} preview=${text.take(80).replace('\n', ' ')}",
      )
      listenerProvider().forEach { listener ->
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
      listenerProvider().forEach { listener ->
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
    runtimeLifecycle = runtimeLifecycle,
    runtimeEventSink = runtimeEventSink,
    callbacks = SessionSubAgentRecoveryDriverCallbacks(
      recordSubmission = ::recordDetachedRecoverySubmission,
      runStateByTaskId = ::subAgentRecoveryRunStateByTaskId,
      runStateByRunId = ::subAgentRecoveryRunStateByRunId,
      replaceLastResult = ::replaceRunLastResult,
      replaceDetachedTask = ::replaceRunDetachedTask,
      notifyTaskStarted = { task ->
        listenerProvider().forEach { listener ->
          listener.onTaskStarted(sessionId = sessionId, task = task)
        }
      },
      finalizeTaskResult = { task, result ->
        val enrichedResult = enrichResultExecutionContext(task = task, result = result)
        recordRunResult(task = task, result = enrichedResult)
        listenerProvider().forEach { listener ->
          listener.onTaskFinished(sessionId = sessionId, task = task, result = enrichedResult)
        }
        enrichedResult
      },
      prepareExecutionTask = ::taskWithDetachedExecutionMetadata,
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
      isAwaitingManualResume = ::isDetachedControlAwaitingManualResume,
    ),
  )
  private val baseRuntime = runtimeFactory.create(
    sessionId = sessionId,
    eventSink = runtimeEventSink,
  )
  private val loop = OpenCrayAgentEngine(
    runtime = SessionTaskRuntime { task, hooks ->
      runtimeFlowDebug(
        "runtime.taskStarted session=$sessionId task=${task.id} run=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "-"} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} type=${task.type}",
      )
      listenerProvider().forEach { listener ->
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
      listenerProvider().forEach { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      result
    },
  ).create(
    sessionId = sessionId,
    agentId = agentId,
    snapshotStore = snapshotStore,
  )

  init {
    synchronized(runLock) {
      restorePersistedRunRecordsLocked()
      restoreDetachedControlTasksLocked()
      seedMissingRunRecordsLocked(loop.snapshot())
    }
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
      metadata = runtimeLifecycle.taskMetadata() + metadata + mapOf(
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
    val normalizedMetadata = runtimeLifecycle.taskMetadata() + task.metadata
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

  override fun submitDetachedControlTask(task: AgentTask): AgentRunSubmission {
    touch()
    val acceptedAtEpochMs = maxOf(System.currentTimeMillis(), task.createdAtEpochMs)
    val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: "run-$sessionId-${UUID.randomUUID().toString().take(8)}"
    val normalizedMetadata = runtimeLifecycle.taskMetadata() + task.metadata + mapOf(
      AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
      AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
    )
    val normalizedTask = task.copy(metadata = normalizedMetadata)
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = runId,
      taskId = normalizedTask.id,
      acceptedAtEpochMs = acceptedAtEpochMs,
      lifecycleDiagnostics = runLifecycleDiagnosticsFrom(normalizedTask.metadata),
    )
    synchronized(runLock) {
      val record = ManagedRunRecord(
        submission = submission,
        pendingMessageId = normalizedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        detachedTask = normalizedTask,
      )
      runRecordsById[runId] = record
      persistRunRecordLocked(record)
    }
    launchDetachedControlExecution(
      submission = submission,
      task = normalizedTask,
      executionKind = normalizedTask.metadata[METADATA_EXECUTION_KIND]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: EXECUTION_KIND_INITIAL,
      clearPreviousResult = false,
    )
    return submission
  }

  override fun submitDetachedSubAgentRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission {
    touch()
    return subAgentRecoveryDriver.submit(
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

  private fun requestDetachedControlCancel(taskId: String): Boolean {
    val state = synchronized(detachedControlLock) {
      detachedControlTasksByTaskId[taskId]
    } ?: return false
    state.cancelRequested.set(true)
    val future = synchronized(detachedControlLock) {
      detachedControlTasksByTaskId[taskId]?.future
    }
    if (future != null) {
      future.cancel(true)
      return true
    }
    val record = synchronized(runLock) {
      runRecordsById.values.firstOrNull { persisted -> persisted.submission.taskId == taskId }
    } ?: return false
    val lastResult = record.lastResult ?: return false
    if (!isDetachedControlAwaitingManualResume(lastResult)) {
      return false
    }
    val cancelledResult = ExecutionResult(
      taskId = taskId,
      status = ExecutionStatus.CANCELLED,
      errorCode = "DETACHED_CONTROL_CANCELLED",
      errorMessage = "Detached control execution was cancelled before it resumed.",
      startedAtEpochMs = lastResult.startedAtEpochMs,
      finishedAtEpochMs = System.currentTimeMillis(),
      metadata = lastResult.metadata,
    )
    synchronized(runLock) {
      val updated = record.copy(lastResult = cancelledResult)
      runRecordsById[record.submission.runId] = updated
      persistRunRecordLocked(updated)
    }
    synchronized(detachedControlLock) {
      detachedControlTasksByTaskId.remove(taskId)
    }
    return true
  }

  private fun requestSubAgentRecoveryCancel(taskId: String): Boolean =
    subAgentRecoveryDriver.requestCancel(taskId)

  private fun requestDetachedControlResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean {
    require(
      executionKind == EXECUTION_KIND_APPROVAL_RESUME ||
        executionKind == EXECUTION_KIND_CHECKPOINT_RESUME,
    ) {
      "Unsupported detached resume execution kind: $executionKind"
    }
    val state = synchronized(detachedControlLock) {
      detachedControlTasksByTaskId[taskId]
    } ?: return false
    if (state.future != null) {
      return false
    }
    val record = synchronized(runLock) {
      runRecordsById[state.submission.runId]
    } ?: return false
    val lastResult = record.lastResult ?: return false
    if (!isDetachedControlAwaitingManualResume(lastResult)) {
      return false
    }
    val resumedTask = state.task.copy(
      metadata = state.task.metadata + taskMetadataUpdates,
    )
    launchDetachedControlExecution(
      submission = state.submission,
      task = resumedTask,
      executionKind = executionKind,
      clearPreviousResult = true,
    )
    return true
  }

  private fun requestSubAgentRecoveryResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = subAgentRecoveryDriver.requestResume(
    taskId = taskId,
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  )

  private fun launchDetachedControlExecution(
    submission: AgentRunSubmission,
    task: AgentTask,
    executionKind: String,
    clearPreviousResult: Boolean,
  ) {
    val executionTask = taskWithDetachedExecutionMetadata(
      task = task,
      executionKind = executionKind,
    )
    val cancelRequested = AtomicBoolean(false)
    val future = FutureTask<Unit> {
      runtimeFlowDebug(
        "runtime.detachedTaskStarted session=$sessionId task=${executionTask.id} run=${executionTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "-"} kind=$executionKind",
      )
      listenerProvider().forEach { listener ->
        listener.onTaskStarted(sessionId = sessionId, task = executionTask)
      }
      val executionHooks = RuntimeExecutionHooks(
        isCancellationRequested = cancelRequested::get,
        requestRetry = { _: RetryRequest -> Unit },
      )
      val result = try {
        enrichResultExecutionContext(
          task = executionTask,
          result = runtimeFactory.executeDetachedControlTask(
            sessionId = sessionId,
            task = executionTask,
            hooks = executionHooks,
            eventSink = runtimeEventSink,
          ) ?: baseRuntime.execute(
            executionTask,
            executionHooks,
          ),
        )
      } catch (_: InterruptedException) {
        ExecutionResult(
          taskId = executionTask.id,
          status = ExecutionStatus.CANCELLED,
          errorCode = "DETACHED_CONTROL_INTERRUPTED",
          errorMessage = "Detached control execution was interrupted.",
          startedAtEpochMs = executionTask.createdAtEpochMs,
          finishedAtEpochMs = System.currentTimeMillis(),
          metadata = executionMetadataFrom(executionTask.metadata),
        )
      }
      runtimeFlowDebug(
        "runtime.detachedTaskFinished session=$sessionId task=${executionTask.id} run=${executionTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: "-"} status=${result.status} error=${result.errorCode ?: "-"}",
      )
      completeDetachedControlExecution(
        submission = submission,
        task = executionTask,
        result = result,
      )
    }
    synchronized(detachedControlLock) {
      detachedControlTasksByTaskId[submission.taskId] = DetachedControlTaskState(
        submission = submission,
        task = executionTask,
        cancelRequested = cancelRequested,
        future = future,
      )
    }
    if (clearPreviousResult) {
      synchronized(runLock) {
        val existing = runRecordsById[submission.runId]
        if (existing != null) {
          val updated = existing.copy(lastResult = null)
          runRecordsById[submission.runId] = updated
          persistRunRecordLocked(updated)
        }
      }
    }
    executor.execute(future)
  }

  private fun completeDetachedControlExecution(
    submission: AgentRunSubmission,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val enrichedResult = enrichResultExecutionContext(task = task, result = result)
    recordRunResult(task = task, result = enrichedResult)
    listenerProvider().forEach { listener ->
      listener.onTaskFinished(sessionId = sessionId, task = task, result = enrichedResult)
    }
    synchronized(detachedControlLock) {
      val latest = detachedControlTasksByTaskId[submission.taskId] ?: return
      detachedControlTasksByTaskId[submission.taskId] = latest.copy(future = null)
      if (!isDetachedControlAwaitingManualResume(enrichedResult)) {
        detachedControlTasksByTaskId.remove(submission.taskId)
      }
    }
  }

  private fun detachedControlRecoveryKey(task: AgentTask): Pair<String, String>? {
    val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    return parentRunId to agentId
  }

  private fun taskWithDetachedExecutionMetadata(
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

  private fun isDetachedControlAwaitingManualResume(
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
    if (requestDetachedControlCancel(taskId)) {
      return true
    }
    return requestSubAgentRecoveryCancel(taskId)
  }

  override fun requestRetry(taskId: String): Boolean {
    touch()
    val retried = loop.requestRetry(taskId)
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
    if (
      requestDetachedControlResume(
        taskId = taskId,
        executionKind = executionKind,
        taskMetadataUpdates = taskMetadataUpdates,
      )
    ) {
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
      listDetachedControlTasks()
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
    val state = loop.resume()
    if (hasRunnableWork()) {
      ensureProcessing()
    }
    return state
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

  override fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) {
    runtimeFactory.retainKnownSubAgentParentRuns(
      sessionId = sessionId,
      parentRunIds = parentRunIds,
    )
  }

  override fun listDetachedControlTasks(): List<AgentTask> = synchronized(detachedControlLock) {
    detachedControlTasksByTaskId.values.map(DetachedControlTaskState::task)
  } + subAgentRecoveryDriver.listTasks()

  override fun ensureRecoverableDetachedSubAgentTasks(): Int {
    touch()
    val activeParentRunIds = listRuns()
      .filter(AgentRunSnapshot::isActive)
      .map(AgentRunSnapshot::runId)
      .toSet()
    val pendingRecoveryKeys = snapshot().tasks
      .asSequence()
      .filter { taskSnapshot ->
        taskSnapshot.lifecycleState != QueueTaskLifecycleState.COMPLETED &&
          taskSnapshot.lifecycleState != QueueTaskLifecycleState.CANCELLED &&
          taskSnapshot.lifecycleState != QueueTaskLifecycleState.FAILED &&
          taskSnapshot.task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE] ==
          RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY
      }
      .mapNotNull { taskSnapshot -> detachedControlRecoveryKey(taskSnapshot.task) }
      .toSet()
    val pendingDetachedRecoveryKeys = listDetachedControlTasks()
      .asSequence()
      .filter { task ->
        task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE] ==
          RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY
      }
      .mapNotNull(::detachedControlRecoveryKey)
      .toSet()
    val resumableHandles = listSubAgentHandles().filter { handle ->
      handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED &&
        handle.pendingApprovalResume == null &&
        handle.parentRunId !in activeParentRunIds &&
        (handle.parentRunId to handle.agentId) !in pendingRecoveryKeys &&
        (handle.parentRunId to handle.agentId) !in pendingDetachedRecoveryKeys
    }
    resumableHandles.forEach { handle ->
      val taskId = detachedSubAgentRecoveryTaskId(
        sessionId = sessionId,
        agentId = handle.agentId,
        parentRunId = handle.parentRunId,
      )
      submitDetachedSubAgentRecoveryTask(
        agentId = handle.agentId,
        parentRunId = handle.parentRunId,
        taskId = taskId,
        createdAtEpochMs = System.currentTimeMillis(),
        submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      )
    }
    return resumableHandles.size
  }

  override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> =
    listManagedProcesses()
      .filter { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }
      .mapNotNull { snapshot ->
        runtimeFactory.terminateManagedProcess(
          sessionId = sessionId,
          processId = snapshot.processId,
        )
      }

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
        detachedTask = task,
      )
      runRecordsById[submission.runId] = record
      persistRunRecordLocked(record)
    }
  }

  private fun subAgentRecoveryRunStateByTaskId(taskId: String): SessionSubAgentRecoveryRunState? =
    synchronized(runLock) {
      runRecordsById[detachedSubAgentRecoveryRunId(taskId)]
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

  private fun replaceRunDetachedTask(
    runId: String,
    task: AgentTask?,
  ) {
    synchronized(runLock) {
      val existing = runRecordsById[runId] ?: return
      val updated = existing.copy(detachedTask = task)
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
  }

  private fun currentRunSnapshots(): List<AgentRunSnapshot> {
    val queueSnapshot = loop.snapshot()
    val managedProcesses = listManagedProcesses()
    synchronized(runLock) {
      seedMissingRunRecordsLocked(queueSnapshot)
      repairRestoredInterruptedRunsLocked(
        queueSnapshot = queueSnapshot,
        managedProcesses = managedProcesses,
      )
    }
    val managedProcessesById = managedProcesses.associateBy(ManagedProcessSnapshot::processId)
    val taskSnapshotsByRunId = queueSnapshot.tasks.associateBy { taskSnapshot ->
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
        lastEvent = record?.lastEvent,
        lifecycleDiagnostics = runLifecycleDiagnosticsFrom(
          taskMetadata = taskMetadata,
          resultMetadata = result?.metadata.orEmpty(),
          resultErrorCode = result?.errorCode,
        ),
      )
    }.sortedByDescending { snapshot -> snapshot.acceptedAtEpochMs }
  }

  private fun repairRestoredInterruptedRunsLocked(
    queueSnapshot: SessionQueueSnapshot,
    managedProcesses: List<ManagedProcessSnapshot>,
  ) {
    val managedProcessesById = managedProcesses.associateBy(ManagedProcessSnapshot::processId)
    val taskSnapshotsByRunId = queueSnapshot.tasks.associateBy { taskSnapshot ->
      runIdFor(taskSnapshot.task)
    }
    runRecordsById.entries.toList().forEach { (runId, record) ->
      val taskSnapshot = taskSnapshotsByRunId[runId]
      val taskId = taskSnapshot?.task?.id ?: record.submission.taskId
      val associatedProcesses = associatedManagedProcesses(
        taskId = taskId,
        existingIds = record.managedProcessIds,
        managedProcessesById = managedProcessesById,
        managedProcessReader = { processId -> runtimeFactory.readManagedProcess(sessionId, processId) },
      )
      if (!shouldRepairRestoredInterruptedRun(taskSnapshot, record, associatedProcesses)) {
        return@forEach
      }
      val updated = record.copy(
        managedProcessIds = (
          record.managedProcessIds +
            associatedProcesses.map(ManagedProcessSnapshot::processId)
          ).distinct(),
        lastResult = repairedInterruptedRestoreResult(
          record = record,
          associatedProcesses = associatedProcesses,
        ),
      )
      if (updated != record) {
        runRecordsById[runId] = updated
        persistRunRecordLocked(updated)
      }
    }
  }

  private fun restorePersistedRunRecordsLocked() {
    runRecordStore.list().forEach { persisted ->
      runRecordsById[persisted.runId] = ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = persisted.runId,
          taskId = persisted.taskId,
          acceptedAtEpochMs = persisted.acceptedAtEpochMs,
        ),
        pendingMessageId = persisted.pendingMessageId,
        managedProcessIds = persisted.managedProcessIds,
        detachedTask = persisted.detachedTask,
        lastEvent = persisted.lastEvent?.toRuntimeEventOrNull(),
        lastResult = persisted.lastResult,
      )
    }
  }

  private fun restoreDetachedControlTasksLocked() {
    runRecordsById.values.forEach { record ->
      val task = record.detachedTask ?: return@forEach
      val lastResult = record.lastResult ?: return@forEach
      if (!isDetachedControlAwaitingManualResume(lastResult)) {
        return@forEach
      }
      if (detachedControlTaskSpec(task) is DetachedSubAgentRecoveryWaitTaskSpec) {
        subAgentRecoveryDriver.restorePendingTask(
          submission = record.submission,
          task = task,
          lastResult = lastResult,
        )
      } else {
        synchronized(detachedControlLock) {
          detachedControlTasksByTaskId[record.submission.taskId] = DetachedControlTaskState(
            submission = record.submission,
            task = task,
            cancelRequested = AtomicBoolean(false),
            future = null,
          )
        }
      }
    }
  }

  private fun seedMissingRunRecordsLocked(queueSnapshot: SessionQueueSnapshot) {
    queueSnapshot.tasks.forEach { taskSnapshot ->
      val runId = runIdFor(taskSnapshot.task)
      val existing = runRecordsById[runId]
      if (existing == null) {
        val seeded = ManagedRunRecord(
          submission = AgentRunSubmission(
            sessionId = sessionId,
            runId = runId,
            taskId = taskSnapshot.task.id,
            acceptedAtEpochMs = taskSnapshot.task.createdAtEpochMs,
          ),
          pendingMessageId = taskSnapshot.task.metadata[
            AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
          ],
        )
        runRecordsById[runId] = seeded
        persistRunRecordLocked(seeded)
      } else if (existing.pendingMessageId == null) {
        val updated = existing.copy(
          pendingMessageId = taskSnapshot.task.metadata[
            AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
          ],
        )
        runRecordsById[runId] = updated
        persistRunRecordLocked(updated)
      }
    }
  }

  private fun persistRunRecordLocked(record: ManagedRunRecord) {
    runRecordStore.upsert(
      PersistedAgentRunRecord(
        runId = record.submission.runId,
        taskId = record.submission.taskId,
        acceptedAtEpochMs = record.submission.acceptedAtEpochMs,
        pendingMessageId = record.pendingMessageId,
        managedProcessIds = record.managedProcessIds,
        detachedTask = record.detachedTask,
        lastResult = record.lastResult,
        lastEvent = record.lastEvent?.toPersistedRecord(),
      ),
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

  private fun shouldRepairRestoredInterruptedRun(
    taskSnapshot: com.opencray.core.orchestrator.SessionQueueTaskSnapshot?,
    record: ManagedRunRecord,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): Boolean {
    if (record.lastResult != null) {
      return false
    }
    if (taskSnapshot == null || isTerminalLifecycle(taskSnapshot.lifecycleState)) {
      return false
    }
    if (associatedProcesses.isEmpty()) {
      return false
    }
    if (associatedProcesses.any { snapshot -> snapshot.status == ManagedProcessStatus.RUNNING }) {
      return false
    }
    return associatedProcesses.all { snapshot -> snapshot.isTerminalAfterRestore() } &&
      associatedProcesses.any { snapshot -> snapshot.isInterruptedOnRestore() }
  }

  private fun repairedInterruptedRestoreResult(
    record: ManagedRunRecord,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): ExecutionResult {
    val orderedProcessIds = associatedProcesses
      .map(ManagedProcessSnapshot::processId)
      .distinct()
      .sorted()
    val latestUpdateEpochMs = associatedProcesses.maxOf { snapshot ->
      snapshot.finishedAtEpochMs ?: snapshot.updatedAtEpochMs
    }
    val startedAtEpochMs = record.submission.acceptedAtEpochMs
    val finishedAtEpochMs = maxOf(startedAtEpochMs, latestUpdateEpochMs)
    return ExecutionResult(
      taskId = record.submission.taskId,
      status = ExecutionStatus.FAILED,
      errorCode = ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE,
      errorMessage = buildString {
        append("Managed process state was restored in an interrupted terminal state")
        if (orderedProcessIds.isNotEmpty()) {
          append(" for ")
          append(orderedProcessIds.joinToString(", "))
        }
        append("; marking the run interrupted until the user decides how to continue.")
      },
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = mapOf(
        METADATA_RESTORED_TERMINAL_STATE to RESTORED_TERMINAL_STATE_INTERRUPTED,
        METADATA_RESTORED_FROM_DURABLE_STORE to "true",
        METADATA_RUN_REPAIR_SOURCE to RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE,
        "managedProcessIds" to orderedProcessIds.joinToString(","),
      ),
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

  private fun associatedManagedProcesses(
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
  } else {
    original
  }

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalTaskState(original))
  ) {
    AgentTaskState.FAILED
  } else {
    original
  }

  private fun isInterruptedOnRestoreResult(result: ExecutionResult?): Boolean =
    result?.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun isTerminalLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
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

  private fun ManagedProcessSnapshot.isInterruptedOnRestore(): Boolean =
    errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun ManagedProcessSnapshot.isTerminalAfterRestore(): Boolean = status.isTerminal

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private data class ManagedRunRecord(
    val submission: AgentRunSubmission,
    val pendingMessageId: String? = null,
    val managedProcessIds: List<String> = emptyList(),
    val detachedTask: AgentTask? = null,
    val lastEvent: OpenCrayAgentRunEvent? = null,
    val lastResult: ExecutionResult? = null,
  )

  private data class DetachedControlTaskState(
    val submission: AgentRunSubmission,
    val task: AgentTask,
    val cancelRequested: AtomicBoolean,
    val future: FutureTask<Unit>?,
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
  }
}

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
