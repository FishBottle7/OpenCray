package com.opencray.app

import android.content.Context
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import java.util.Locale
import java.util.UUID
import org.opencray.app.R

internal data class RuntimeServiceLifecycleDescriptor(
  val processStartId: String = OpenCrayProcessLifecycle.processStartId,
  val processStartedAtEpochMs: Long = OpenCrayProcessLifecycle.processStartedAtEpochMs,
  val serviceInstanceId: String = lifecycleId(prefix = "runtime-service"),
  val serviceCreatedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = mapOf(
    "processStartId" to processStartId,
    "processStartedAtEpochMs" to processStartedAtEpochMs,
    "serviceInstanceId" to serviceInstanceId,
    "serviceCreatedAtEpochMs" to serviceCreatedAtEpochMs,
  )
}

internal data class RuntimeReplayExecutionContext(
  val executionId: String? = null,
  val executionOrdinal: Int? = null,
  val executionKind: String? = null,
)

internal data class OpenCrayRuntimeReplayAccess(
  val approvalRejectionRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit,
  val approvalApprovedRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit,
  val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit,
  val runCancellationRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit,
  val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit,
)

internal data class RuntimeOwnerWorkSummary(
  val trackedSessionCount: Int = 0,
  val activeRunCount: Int = 0,
  val activeSessionIds: List<String> = emptyList(),
  val pendingWorkSessionIds: List<String> = emptyList(),
  val liveManagedProcessSessionIds: List<String> = emptyList(),
  val liveSubAgentSessionIds: List<String> = emptyList(),
) {
  val hasActiveWork: Boolean
    get() = activeSessionIds.isNotEmpty() || activeRunCount > 0

  fun snapshotMap(): Map<String, Any?> = mapOf(
    "hasActiveWork" to hasActiveWork,
    "trackedSessionCount" to trackedSessionCount,
    "activeRunCount" to activeRunCount,
    "activeSessionCount" to activeSessionIds.size,
    "activeSessionIds" to activeSessionIds,
    "pendingWorkSessionIds" to pendingWorkSessionIds,
    "liveManagedProcessSessionIds" to liveManagedProcessSessionIds,
    "liveSubAgentSessionIds" to liveSubAgentSessionIds,
  )
}

internal data class RuntimeServiceWorkState(
  val phase: String = PHASE_IDLE,
  val hasActiveWork: Boolean = false,
  val activeRunCount: Int = 0,
  val activeSessionCount: Int = 0,
  val pendingWorkSessionCount: Int = 0,
  val liveManagedProcessSessionCount: Int = 0,
  val liveSubAgentSessionCount: Int = 0,
  val keepAliveRequired: Boolean = false,
  val keepAliveReason: String? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
  val activeSinceEpochMs: Long? = null,
  val idleSinceEpochMs: Long? = changedAtEpochMs,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("hasActiveWork", hasActiveWork)
    put("activeRunCount", activeRunCount)
    put("activeSessionCount", activeSessionCount)
    put("pendingWorkSessionCount", pendingWorkSessionCount)
    put("liveManagedProcessSessionCount", liveManagedProcessSessionCount)
    put("liveSubAgentSessionCount", liveSubAgentSessionCount)
    put("keepAliveRequired", keepAliveRequired)
    keepAliveReason?.let { reason ->
      put("keepAliveReason", reason)
    }
    put("changedAtEpochMs", changedAtEpochMs)
    put("activeSinceEpochMs", activeSinceEpochMs)
    put("idleSinceEpochMs", idleSinceEpochMs)
  }

  companion object {
    const val PHASE_IDLE: String = "idle"
    const val PHASE_ACTIVE_WORK: String = "active_work"
    const val KEEP_ALIVE_REASON_ACTIVE_RUN: String = "active_run"
    const val KEEP_ALIVE_REASON_MANAGED_PROCESS: String = "managed_process"
    const val KEEP_ALIVE_REASON_ACTIVE_SUBAGENT: String = "active_subagent"
  }
}

internal class RuntimeServiceWorkStateTracker(
  private val workSummaryProvider: () -> RuntimeOwnerWorkSummary,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val listeners = linkedSetOf<(RuntimeServiceWorkState) -> Unit>()
  private var currentState: RuntimeServiceWorkState = RuntimeServiceWorkState(
    changedAtEpochMs = clock(),
  )

  fun currentState(): RuntimeServiceWorkState = synchronized(lock) { currentState }

  fun observe(listener: (RuntimeServiceWorkState) -> Unit): () -> Unit = synchronized(lock) {
    listeners += listener
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  fun refresh(): RuntimeServiceWorkState {
    val listenersToNotify: List<(RuntimeServiceWorkState) -> Unit>
    val nextState: RuntimeServiceWorkState
    synchronized(lock) {
      val summary = workSummaryProvider()
      val nextPhase = if (summary.hasActiveWork) {
        RuntimeServiceWorkState.PHASE_ACTIVE_WORK
      } else {
        RuntimeServiceWorkState.PHASE_IDLE
      }
      val nextReason = when {
        summary.liveManagedProcessSessionIds.isNotEmpty() ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_MANAGED_PROCESS
        summary.liveSubAgentSessionIds.isNotEmpty() ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_SUBAGENT
        summary.pendingWorkSessionIds.isNotEmpty() || summary.activeRunCount > 0 ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN
        else -> null
      }
      val previous = currentState
      if (
        previous.phase == nextPhase &&
        previous.activeRunCount == summary.activeRunCount &&
        previous.activeSessionCount == summary.activeSessionIds.size &&
        previous.pendingWorkSessionCount == summary.pendingWorkSessionIds.size &&
        previous.liveManagedProcessSessionCount == summary.liveManagedProcessSessionIds.size &&
        previous.liveSubAgentSessionCount == summary.liveSubAgentSessionIds.size &&
        previous.keepAliveRequired == summary.hasActiveWork &&
        previous.keepAliveReason == nextReason
      ) {
        return previous
      }
      val changedAtEpochMs = clock()
      currentState = if (summary.hasActiveWork) {
        RuntimeServiceWorkState(
          phase = nextPhase,
          hasActiveWork = true,
          activeRunCount = summary.activeRunCount,
          activeSessionCount = summary.activeSessionIds.size,
          pendingWorkSessionCount = summary.pendingWorkSessionIds.size,
          liveManagedProcessSessionCount = summary.liveManagedProcessSessionIds.size,
          liveSubAgentSessionCount = summary.liveSubAgentSessionIds.size,
          keepAliveRequired = true,
          keepAliveReason = nextReason,
          changedAtEpochMs = changedAtEpochMs,
          activeSinceEpochMs = if (previous.phase == RuntimeServiceWorkState.PHASE_ACTIVE_WORK) {
            previous.activeSinceEpochMs ?: changedAtEpochMs
          } else {
            changedAtEpochMs
          },
          idleSinceEpochMs = null,
        )
      } else {
        RuntimeServiceWorkState(
          phase = nextPhase,
          hasActiveWork = false,
          activeRunCount = summary.activeRunCount,
          activeSessionCount = summary.activeSessionIds.size,
          pendingWorkSessionCount = summary.pendingWorkSessionIds.size,
          liveManagedProcessSessionCount = summary.liveManagedProcessSessionIds.size,
          liveSubAgentSessionCount = summary.liveSubAgentSessionIds.size,
          keepAliveRequired = false,
          keepAliveReason = null,
          changedAtEpochMs = changedAtEpochMs,
          activeSinceEpochMs = null,
          idleSinceEpochMs = if (previous.phase == RuntimeServiceWorkState.PHASE_IDLE) {
            previous.idleSinceEpochMs ?: changedAtEpochMs
          } else {
            changedAtEpochMs
          },
        )
      }
      nextState = currentState
      listenersToNotify = listeners.toList()
    }
    notifyListeners(listenersToNotify, nextState)
    return nextState
  }

  private fun notifyListeners(
    listeners: List<(RuntimeServiceWorkState) -> Unit>,
    state: RuntimeServiceWorkState,
  ) {
    if (listeners.isEmpty()) {
      return
    }
    listeners.forEach { listener -> listener(state) }
  }
}

internal interface OpenCrayRuntimeSessionAccess {
  val sessionId: String

  fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: com.opencray.core.contracts.PolicyDecision,
    metadata: Map<String, String> = emptyMap(),
  ): AgentRunSubmission

  fun submitTask(task: com.opencray.core.contracts.AgentTask): AgentRunSubmission

  fun submitDetachedControlTask(task: com.opencray.core.contracts.AgentTask): AgentRunSubmission =
    submitTask(task)

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

  fun resume(): com.opencray.core.orchestrator.SessionLifecycleState

  fun snapshot(): com.opencray.core.orchestrator.SessionQueueSnapshot

  fun hasPendingWork(): Boolean

  fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot>

  fun hasLiveManagedProcesses(): Boolean

  fun terminateRunningManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot>

  fun listSubAgentHandles(): List<SubAgentHandleState> = emptyList()

  fun hasLiveSubAgentWork(): Boolean = listSubAgentHandles().any { handle ->
    when (handle.snapshot.state) {
      SubAgentExecutionState.BACKGROUND_QUEUED,
      SubAgentExecutionState.BACKGROUND_RUNNING,
      -> true

      else -> false
    }
  }

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

internal interface OpenCrayRuntimeHostAccess {
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor

  fun observe(listener: AgentSessionRuntimeListener): () -> Unit

  fun activeWorkSummary(): RuntimeOwnerWorkSummary

  fun session(sessionId: String): OpenCrayRuntimeSessionAccess

  fun releaseSession(sessionId: String)

  fun releaseIdleSessions()

  fun runEventJournalStore(sessionId: String): RunEventJournalStore

  fun promptCheckpointStore(sessionId: String): PromptCheckpointStore

  fun supplementStore(sessionId: String): SessionSupplementStore

  fun markApprovalApproved(
    sessionId: String,
    taskId: String,
    toolName: String?,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  )

  fun markApprovalRejected(
    sessionId: String,
    taskId: String,
    toolName: String?,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  )

  fun clearApproval(sessionId: String, taskId: String)

  fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>)

  fun isApprovalApproved(sessionId: String, taskId: String): Boolean

  fun isApprovalRejected(sessionId: String, taskId: String): Boolean
}

private class AgentSessionHandleRuntimeSessionAccess(
  private val delegate: AgentSessionHandle,
) : OpenCrayRuntimeSessionAccess {
  override val sessionId: String
    get() = delegate.sessionId

  override fun submitPrompt(
    userText: String,
    pendingMessageId: String,
    visibleThroughMessageId: String,
    policyDecision: com.opencray.core.contracts.PolicyDecision,
    metadata: Map<String, String>,
  ): AgentRunSubmission = delegate.submitPrompt(
    userText = userText,
    pendingMessageId = pendingMessageId,
    visibleThroughMessageId = visibleThroughMessageId,
    policyDecision = policyDecision,
    metadata = metadata,
  )

  override fun submitTask(task: com.opencray.core.contracts.AgentTask): AgentRunSubmission =
    delegate.submitTask(task)

  override fun submitDetachedControlTask(task: com.opencray.core.contracts.AgentTask): AgentRunSubmission =
    delegate.submitDetachedControlTask(task)

  override fun ensureProcessing() = delegate.ensureProcessing()

  override fun requestCancel(taskId: String): Boolean = delegate.requestCancel(taskId)

  override fun requestRetry(taskId: String): Boolean = delegate.requestRetry(taskId)

  override fun requestResumeTask(taskId: String): Boolean = delegate.requestResumeTask(taskId)

  override fun requestResumeTask(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = delegate.requestResumeTask(
    taskId = taskId,
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  )

  override fun listRuns(): List<AgentRunSnapshot> = delegate.listRuns()

  override fun findRun(runId: String): AgentRunSnapshot? = delegate.findRun(runId)

  override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? =
    delegate.waitForRun(runId, timeoutMs)

  override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int =
    delegate.requestCancelForPendingMessageIds(pendingMessageIds)

  override fun resume(): com.opencray.core.orchestrator.SessionLifecycleState = delegate.resume()

  override fun snapshot(): com.opencray.core.orchestrator.SessionQueueSnapshot = delegate.snapshot()

  override fun hasPendingWork(): Boolean = delegate.hasPendingWork()

  override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
    delegate.listManagedProcesses()

  override fun hasLiveManagedProcesses(): Boolean = delegate.hasLiveManagedProcesses()

  override fun terminateRunningManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
    delegate.terminateRunningManagedProcesses()

  override fun listSubAgentHandles(): List<SubAgentHandleState> =
    delegate.listSubAgentHandles()

  override fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) {
    delegate.retainKnownSubAgentParentRuns(parentRunIds)
  }

  override fun listDetachedControlTasks(): List<AgentTask> =
    delegate.listDetachedControlTasks()

  override fun submitDetachedSubAgentRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission = delegate.submitDetachedSubAgentRecoveryTask(
    agentId = agentId,
    parentRunId = parentRunId,
    taskId = taskId,
    createdAtEpochMs = createdAtEpochMs,
    submissionSource = submissionSource,
  )

  override fun ensureRecoverableDetachedSubAgentTasks(): Int =
    delegate.ensureRecoverableDetachedSubAgentTasks()
}

internal class DefaultOpenCrayRuntimeHostAccess(
  override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  private val sessionRuntimeManager: AgentSessionRuntimeManager,
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  private val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  private val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  private val approvalRegistry: AgentTaskApprovalRegistry,
) : OpenCrayRuntimeHostAccess {
  override fun observe(listener: AgentSessionRuntimeListener): () -> Unit =
    sessionRuntimeManager.observe(listener)

  override fun activeWorkSummary(): RuntimeOwnerWorkSummary =
    sessionRuntimeManager.activeWorkSummary()

  override fun session(sessionId: String): OpenCrayRuntimeSessionAccess =
    AgentSessionHandleRuntimeSessionAccess(sessionRuntimeManager.forSession(sessionId))

  override fun releaseSession(sessionId: String) {
    sessionRuntimeManager.release(sessionId)
  }

  override fun releaseIdleSessions() {
    sessionRuntimeManager.releaseIdleSessions()
  }

  override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
    runEventJournalStoreFactory.forChatSession(sessionId)

  override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
    promptCheckpointStoreFactory.forChatSession(sessionId)

  override fun supplementStore(sessionId: String): SessionSupplementStore =
    supplementStoreFactory.forChatSession(sessionId)

  override fun markApprovalApproved(
    sessionId: String,
    taskId: String,
    toolName: String?,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  ) {
    approvalRegistry.markApproved(
      sessionId = sessionId,
      taskId = taskId,
      toolName = toolName,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
    )
  }

  override fun markApprovalRejected(
    sessionId: String,
    taskId: String,
    toolName: String?,
    promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
    subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  ) {
    approvalRegistry.markRejected(
      sessionId = sessionId,
      taskId = taskId,
      toolName = toolName,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
    )
  }

  override fun clearApproval(sessionId: String, taskId: String) {
    approvalRegistry.clear(sessionId = sessionId, taskId = taskId)
  }

  override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) {
    approvalRegistry.retainKnownTasks(sessionId = sessionId, taskIds = taskIds)
  }

  override fun isApprovalApproved(sessionId: String, taskId: String): Boolean =
    approvalRegistry.isApproved(sessionId, taskId)

  override fun isApprovalRejected(sessionId: String, taskId: String): Boolean =
    approvalRegistry.isRejected(sessionId, taskId)
}

internal data class OpenCrayRuntimeOwnerAccess(
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val hostAccess: OpenCrayRuntimeHostAccess,
  val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
  val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator,
  val replayAccess: OpenCrayRuntimeReplayAccess,
)

internal fun InProcessOpenCrayRuntimeOwner.toRuntimeOwnerAccess(): OpenCrayRuntimeOwnerAccess =
  OpenCrayRuntimeOwnerAccess(
    lifecycleDescriptor = lifecycleDescriptor,
    hostAccess = DefaultOpenCrayRuntimeHostAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      sessionRuntimeManager = sessionRuntimeManager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      supplementStoreFactory = supplementStoreFactory,
      approvalRegistry = approvalRegistry,
    ),
    transcriptMessagesProvider = transcriptMessagesProvider,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    replayAccess = replayAccess,
  )

internal data class OpenCrayRuntimeServiceHost(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateTracker: RuntimeServiceWorkStateTracker,
  val scheduledTaskSpecStore: ScheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
  val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore =
    inMemoryScheduledTaskRunRecordStoreFactory().create(),
  val scheduledTaskTriggerSyncStateStore: ScheduledTaskTriggerSyncStateStore =
    inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
  val scheduledTriggerRegistrar: ScheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
)

internal data class RuntimeServiceBootstrapResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
)

internal data class RuntimeServiceInterruptedRunRepairResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
)

private val EXPLICIT_SUBAGENT_HANDLE_CONTROL_TOOLS: Set<String> = setOf(
  "spawn_agent",
  "wait_agent",
)

internal object OpenCrayRuntimeServiceHostRegistry {
  @Volatile
  private var instance: OpenCrayRuntimeServiceHost? = null

  fun peek(): OpenCrayRuntimeServiceHost? = instance

  fun clearForTest() {
    synchronized(this) {
      instance = null
    }
  }

  fun getOrCreate(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    },
  ): OpenCrayRuntimeServiceHost {
    val appContext = context.applicationContext
    return instance ?: synchronized(this) {
      instance ?: createOpenCrayRuntimeServiceHost(
        appContext = appContext,
        serviceLifecycle = serviceLifecycleFactory(),
      ).also { created ->
        instance = created
      }
    }
  }
}

private fun createOpenCrayRuntimeServiceHost(
  appContext: Context,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
): OpenCrayRuntimeServiceHost {
  val dependencies = loadOpenCrayRuntimeContextDependencies(appContext)
  val owner = ensureInProcessRuntimeOwner(dependencies)
  val runtimeAccess = owner.toRuntimeOwnerAccess()
  val scheduledTaskSpecStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(appContext).create()
  val scheduledTaskRunRecordStore = FileBackedScheduledTaskRunRecordStoreFactory.fromContext(appContext).create()
  val scheduledTaskTriggerSyncStateStore = FileBackedScheduledTaskTriggerSyncStateStoreFactory
    .fromContext(appContext)
    .create()
  val scheduledWorkScheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext)
  val scheduledTriggerRegistrar = DefaultScheduledTriggerRegistrar(
    alarmScheduler = AlarmManagerScheduledAlarmScheduler.fromContext(appContext),
    workScheduler = scheduledWorkScheduler,
  )
  bootstrapSessionsForRuntimeServiceHost(
    chatSessionStore = dependencies.chatSessionStore,
    runtimeAccess = runtimeAccess,
  )
  resyncEnabledScheduledTasks(
    specStore = scheduledTaskSpecStore,
    triggerRegistrar = scheduledTriggerRegistrar,
    triggerSyncStateStore = scheduledTaskTriggerSyncStateStore,
  )
  val serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
    workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
  )
  runtimeAccess.hostAccess.observe(
    object : AgentSessionRuntimeListener {
      override fun onTaskStarted(sessionId: String, task: com.opencray.core.contracts.AgentTask) {
        serviceWorkStateTracker.refresh()
      }

      override fun onTaskFinished(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        result: com.opencray.core.contracts.ExecutionResult,
      ) {
        serviceWorkStateTracker.refresh()
      }

      override fun onRunEvent(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        event: com.opencray.runtime.OpenCrayAgentRunEvent,
      ) {
        serviceWorkStateTracker.refresh()
      }
    },
  )
  serviceWorkStateTracker.refresh()
  return OpenCrayRuntimeServiceHost(
    dependencies = dependencies,
    runtimeAccess = runtimeAccess,
    serviceLifecycle = serviceLifecycle,
    serviceWorkStateTracker = serviceWorkStateTracker,
    scheduledTaskSpecStore = scheduledTaskSpecStore,
    scheduledTaskRunRecordStore = scheduledTaskRunRecordStore,
    scheduledTaskTriggerSyncStateStore = scheduledTaskTriggerSyncStateStore,
    scheduledTriggerRegistrar = scheduledTriggerRegistrar,
  )
}

private fun submitRecoverableSubAgentTasksForSession(
  session: OpenCrayRuntimeSessionAccess,
) {
  session.ensureRecoverableDetachedSubAgentTasks()
}

internal fun bootstrapSessionsForRuntimeServiceHost(
  chatSessionStore: ChatSessionLocalStore,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
): RuntimeServiceBootstrapResult {
  val state = chatSessionStore.loadState()
  val knownSessionIds = knownRuntimeServiceSessionIds(chatSessionStore)
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeAccess.hostAccess.session(sessionId)
    val shouldResume = sessionId == state.activeSession.sessionId ||
      session.hasPendingWork() ||
      session.hasLiveManagedProcesses() ||
      session.hasLiveSubAgentWork()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val runs = session.listRuns()
    if (runs.isNotEmpty()) {
      runtimeAccess.replayAccess.terminalReplayRepairer(sessionId, runs)
      repairedSessionIds += sessionId
    }
    submitRecoverableSubAgentTasksForSession(
      session = session,
    )
  }

  return RuntimeServiceBootstrapResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
  )
}

internal fun resumeInterruptedRunsForRuntimeServiceHost(
  chatSessionStore: ChatSessionLocalStore,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
): RuntimeServiceInterruptedRunRepairResult {
  val knownSessionIds = knownRuntimeServiceSessionIds(chatSessionStore)
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeAccess.hostAccess.session(sessionId)
    val runs = session.listRuns()
    val shouldResume = runs.any(AgentRunSnapshot::isActive) || session.hasLiveSubAgentWork()
    if (!shouldResume) {
      return@forEach
    }
    session.resume()
    resumedSessionIds += sessionId
    val repairedRuns = session.listRuns()
    if (repairedRuns.isNotEmpty()) {
      runtimeAccess.replayAccess.terminalReplayRepairer(sessionId, repairedRuns)
      repairedSessionIds += sessionId
    }
    submitRecoverableSubAgentTasksForSession(
      session = session,
    )
  }

  return RuntimeServiceInterruptedRunRepairResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
  )
}

internal fun OpenCrayRuntimeServiceHost.resumeInterruptedRuns():
  RuntimeServiceInterruptedRunRepairResult =
  resumeInterruptedRunsForRuntimeServiceHost(
    chatSessionStore = dependencies.chatSessionStore,
    runtimeAccess = runtimeAccess,
  )

internal fun OpenCrayRuntimeServiceHost.approvePendingApproval(
  taskIdOrRunId: String,
  nowEpochMs: Long = System.currentTimeMillis(),
) {
  val resolution = resolvePendingApproval(taskIdOrRunId)
    ?: error("Pending approval '$taskIdOrRunId' is unavailable.")
  val checkpointStore = runtimeAccess.hostAccess.promptCheckpointStore(resolution.sessionId)
  val deferUntilManualResume = shouldDeferApprovalDecisionUntilManualResume(resolution)
  val detachedChildResumed = if (!deferUntilManualResume) {
    submitDetachedSubAgentRecoveryTaskForApprovedResolution(
      resolution = resolution,
      nowEpochMs = nowEpochMs,
    )
  } else {
    false
  }
  if (!deferUntilManualResume && !detachedChildResumed) {
    runtimeAccess.hostAccess.markApprovalApproved(
      sessionId = resolution.sessionId,
      taskId = resolution.taskId,
      toolName = resolution.resumeToolName ?: resolution.toolName,
      promptResumeState = resolution.promptResumeState,
      subAgentApprovalResume = resolution.subAgentApprovalResume,
    )
  }
  checkpointStore.upsert(
    resolution.decisionCheckpoint(
      checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
      nowEpochMs = nowEpochMs,
    ),
  )
  if (!deferUntilManualResume && !detachedChildResumed) {
    val resumed = runtimeAccess.hostAccess.session(resolution.sessionId).requestResumeTask(resolution.taskId)
    if (!resumed) {
      runtimeAccess.hostAccess.clearApproval(
        sessionId = resolution.sessionId,
        taskId = resolution.taskId,
      )
      checkpointStore.remove(resolution.taskId)
      error("Unable to resume pending approval '$taskIdOrRunId'.")
    }
  }
  runtimeAccess.replayAccess.approvalApprovedRecorder(
    resolution.sessionId,
    resolution.taskId,
    resolution.runId,
    resolution.toolName,
    resolution.isHighRisk,
    resolution.replayExecutionContext(),
  )
  if (!deferUntilManualResume) {
    resolution.subAgentResumedEvent(
      summary = delegatedChildApprovalApprovedSummary(
        context = dependencies.localizedContext,
      ),
      emittedAtEpochMs = nowEpochMs,
    )?.let { event ->
      runtimeAccess.replayAccess.subAgentReplayRecorder(resolution.sessionId, event)
    }
  }
  runtimeAccess.hostAccess.runEventJournalStore(resolution.sessionId).append(
    resolution.resultEvent(
      phase = OpenCrayApprovalPhase.APPROVED,
      emittedAtEpochMs = nowEpochMs,
      text = if (deferUntilManualResume) {
        approvalRecordedText(
          context = dependencies.localizedContext,
        )
      } else if (detachedChildResumed) {
        delegatedChildApprovalApprovedText(
          context = dependencies.localizedContext,
        )
      } else {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).approvalApproved
      },
    ),
  )
  if (!deferUntilManualResume && !detachedChildResumed) {
    resolution.pendingMessageId?.let { pendingMessageId ->
      dependencies.chatSessionStore.replaceMessage(
        sessionId = resolution.sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).agentThinking,
      )
    }
  }
  dependencies.chatSessionStore.appendMessage(
    sessionId = resolution.sessionId,
    role = ChatTranscriptRole.TOOL,
      text = if (deferUntilManualResume) {
        approvalRecordedText(
          context = dependencies.localizedContext,
        )
      } else if (detachedChildResumed) {
        delegatedChildApprovalApprovedText(
          context = dependencies.localizedContext,
        )
      } else {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).approvalApproved
      },
  )
}

internal fun OpenCrayRuntimeServiceHost.approvePendingApprovalForSession(
  taskIdOrRunId: String,
  nowEpochMs: Long = System.currentTimeMillis(),
) {
  val resolution = resolvePendingApproval(taskIdOrRunId)
    ?: error("Pending approval '$taskIdOrRunId' is unavailable.")
  require(resolution.supportsSessionApproval) {
    "Pending approval '$taskIdOrRunId' does not support session approval."
  }
  val checkpointStore = runtimeAccess.hostAccess.promptCheckpointStore(resolution.sessionId)
  val deferUntilManualResume = shouldDeferApprovalDecisionUntilManualResume(resolution)
  dependencies.chatSessionStore.setNativeWebSearchSessionApproved(
    sessionId = resolution.sessionId,
    approved = true,
  )
  val detachedChildResumed = if (!deferUntilManualResume) {
    submitDetachedSubAgentRecoveryTaskForApprovedResolution(
      resolution = resolution,
      nowEpochMs = nowEpochMs,
    )
  } else {
    false
  }
  if (!deferUntilManualResume && !detachedChildResumed) {
    runtimeAccess.hostAccess.markApprovalApproved(
      sessionId = resolution.sessionId,
      taskId = resolution.taskId,
      toolName = resolution.resumeToolName ?: resolution.toolName,
      promptResumeState = resolution.promptResumeState,
      subAgentApprovalResume = resolution.subAgentApprovalResume,
    )
  }
  checkpointStore.upsert(
    resolution.decisionCheckpoint(
      checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
      nowEpochMs = nowEpochMs,
    ),
  )
  if (!deferUntilManualResume && !detachedChildResumed) {
    val resumed = runtimeAccess.hostAccess.session(resolution.sessionId).requestResumeTask(resolution.taskId)
    if (!resumed) {
      dependencies.chatSessionStore.setNativeWebSearchSessionApproved(
        sessionId = resolution.sessionId,
        approved = false,
      )
      runtimeAccess.hostAccess.clearApproval(
        sessionId = resolution.sessionId,
        taskId = resolution.taskId,
      )
      checkpointStore.remove(resolution.taskId)
      error("Unable to resume pending approval '$taskIdOrRunId'.")
    }
  }
  runtimeAccess.replayAccess.approvalApprovedRecorder(
    resolution.sessionId,
    resolution.taskId,
    resolution.runId,
    resolution.toolName,
    resolution.isHighRisk,
    resolution.replayExecutionContext(),
  )
  if (!deferUntilManualResume) {
    resolution.subAgentResumedEvent(
      summary = delegatedChildApprovalApprovedSummary(
        context = dependencies.localizedContext,
      ),
      emittedAtEpochMs = nowEpochMs,
    )?.let { event ->
      runtimeAccess.replayAccess.subAgentReplayRecorder(resolution.sessionId, event)
    }
  }
  runtimeAccess.hostAccess.runEventJournalStore(resolution.sessionId).append(
    resolution.resultEvent(
      phase = OpenCrayApprovalPhase.APPROVED,
      emittedAtEpochMs = nowEpochMs,
      text = if (deferUntilManualResume) {
        approvalRecordedForSessionText(
          context = dependencies.localizedContext,
        )
      } else if (detachedChildResumed) {
        delegatedChildApprovalApprovedText(
          context = dependencies.localizedContext,
        )
      } else {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).approvalApprovedForSession
      },
    ),
  )
  if (!deferUntilManualResume && !detachedChildResumed) {
    resolution.pendingMessageId?.let { pendingMessageId ->
      dependencies.chatSessionStore.replaceMessage(
        sessionId = resolution.sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).agentThinking,
      )
    }
  }
  dependencies.chatSessionStore.appendMessage(
    sessionId = resolution.sessionId,
    role = ChatTranscriptRole.TOOL,
      text = if (deferUntilManualResume) {
        approvalRecordedForSessionText(
          context = dependencies.localizedContext,
        )
      } else if (detachedChildResumed) {
        delegatedChildApprovalApprovedText(
          context = dependencies.localizedContext,
        )
      } else {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).approvalApprovedForSession
      },
  )
}

internal fun OpenCrayRuntimeServiceHost.rejectPendingApproval(
  taskIdOrRunId: String,
  nowEpochMs: Long = System.currentTimeMillis(),
) {
  val resolution = resolvePendingApproval(taskIdOrRunId)
    ?: error("Pending approval '$taskIdOrRunId' is unavailable.")
  val checkpointStore = runtimeAccess.hostAccess.promptCheckpointStore(resolution.sessionId)
  val deferUntilManualResume = shouldDeferApprovalDecisionUntilManualResume(resolution)
  if (!deferUntilManualResume) {
    val cancelled = runtimeAccess.hostAccess.session(resolution.sessionId).requestCancel(resolution.taskId)
    if (!cancelled) {
      error("Unable to stop pending approval '$taskIdOrRunId' after rejection.")
    }
  }
  runtimeAccess.replayAccess.approvalRejectionRecorder(
    resolution.sessionId,
    resolution.taskId,
    resolution.runId,
    resolution.toolName,
    resolution.isHighRisk,
    resolution.replayExecutionContext(),
  )
  if (!deferUntilManualResume) {
    runtimeAccess.hostAccess.markApprovalRejected(
      sessionId = resolution.sessionId,
      taskId = resolution.taskId,
      toolName = resolution.resumeToolName ?: resolution.toolName,
      promptResumeState = resolution.promptResumeState,
      subAgentApprovalResume = resolution.subAgentApprovalResume,
    )
    runtimeAccess.hostAccess.clearApproval(
      sessionId = resolution.sessionId,
      taskId = resolution.taskId,
    )
    checkpointStore.remove(resolution.taskId)
    resolution.subAgentTerminalEvent(
      summary = delegatedChildApprovalRejectedStopSummary(
        context = dependencies.localizedContext,
      ),
      emittedAtEpochMs = nowEpochMs,
    )?.let { event ->
      runtimeAccess.replayAccess.subAgentReplayRecorder(resolution.sessionId, event)
    }
  } else {
    checkpointStore.upsert(
      resolution.decisionCheckpoint(
        checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
        nowEpochMs = nowEpochMs,
      ),
    )
  }
  runtimeAccess.hostAccess.runEventJournalStore(resolution.sessionId).append(
    resolution.resultEvent(
      phase = OpenCrayApprovalPhase.REJECTED,
      emittedAtEpochMs = nowEpochMs,
      text = if (deferUntilManualResume) {
        approvalRejectedRecordedText(
          context = dependencies.localizedContext,
        )
      } else {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).approvalRejected
      },
    ),
  )
  if (!deferUntilManualResume) {
    resolution.pendingMessageId?.let { pendingMessageId ->
      dependencies.chatSessionStore.replaceMessage(
        sessionId = resolution.sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).agentThinking,
      )
    }
  }
  dependencies.chatSessionStore.appendMessage(
    sessionId = resolution.sessionId,
    role = ChatTranscriptRole.TOOL,
    text = if (deferUntilManualResume) {
      approvalRejectedRecordedText(
        context = dependencies.localizedContext,
      )
    } else {
      runtimeApprovalCommandStrings(
        context = dependencies.localizedContext,
      ).approvalRejected
    },
  )
}

private fun knownRuntimeServiceSessionIds(
  chatSessionStore: ChatSessionLocalStore,
): List<String> {
  val state = chatSessionStore.loadState()
  return buildList {
    add(state.activeSession.sessionId)
    addAll(state.sessions.map(ChatSessionLocalStore.SessionSummary::sessionId))
  }.distinct()
}

private data class RuntimeServiceApprovalCommandStrings(
  val agentThinking: String,
  val approvalApproved: String,
  val approvalApprovedForSession: String,
  val approvalRejected: String,
)

private data class RuntimeServicePendingApprovalSubAgentLifecycle(
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
)

private data class RuntimeServicePendingApprovalResolution(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val toolName: String?,
  val resumeToolName: String?,
  val promptCheckpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary?,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
  val subAgentApprovalResume: SubAgentApprovalResume?,
  val isHighRisk: Boolean,
  val supportsSessionApproval: Boolean,
  val subAgentLifecycle: RuntimeServicePendingApprovalSubAgentLifecycle?,
  val subAgentControlTool: String?,
  val executionId: String?,
  val executionOrdinal: Int?,
  val executionKind: String?,
) {
  fun replayExecutionContext(): RuntimeReplayExecutionContext =
    RuntimeReplayExecutionContext(
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
    )

  fun decisionCheckpoint(
    checkpointKind: PromptCheckpointKind,
    nowEpochMs: Long,
  ): PersistedPromptCheckpoint = PersistedPromptCheckpoint(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
    checkpointId = "checkpoint-$nowEpochMs-${UUID.randomUUID().toString().take(8)}",
    checkpointKind = checkpointKind,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
    toolName = resumeToolName ?: toolName,
    pendingMessageId = pendingMessageId,
    isHighRisk = isHighRisk,
    promptCheckpointBoundary = promptCheckpointBoundary,
    promptResumeState = promptResumeState,
    subAgentApprovedToolName = subAgentApprovalResume?.approvedToolName,
    subAgentPromptResumeState = subAgentApprovalResume?.promptResumeState,
    subAgentIsHighRisk = subAgentApprovalResume?.isHighRisk,
    subAgentAgentId = subAgentApprovalResume?.agentId,
    subAgentChildRunId = subAgentApprovalResume?.childRunId,
    subAgentChildTaskId = subAgentApprovalResume?.childTaskId,
  )

  fun resultEvent(
    phase: OpenCrayApprovalPhase,
    emittedAtEpochMs: Long,
    text: String,
  ): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    phase = phase,
    toolName = toolName,
    text = text,
    isHighRisk = isHighRisk,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  fun subAgentResumedEvent(
    summary: String,
    emittedAtEpochMs: Long,
  ): OpenCraySubAgentEvent? = subAgentLifecycle?.let { lifecycle ->
    OpenCraySubAgentEvent(
      runId = runId,
      taskId = taskId,
      phase = OpenCraySubAgentPhase.RESUMED,
      childRunId = lifecycle.childRunId,
      childTaskId = lifecycle.childTaskId,
      label = lifecycle.label,
      subagentType = lifecycle.subagentType,
      contextMode = lifecycle.contextMode,
      depth = lifecycle.depth,
      summary = summary,
      executionState = SubAgentExecutionState.RUNNING,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = isHighRisk,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }

  fun subAgentTerminalEvent(
    summary: String,
    emittedAtEpochMs: Long,
  ): OpenCraySubAgentEvent? = subAgentLifecycle?.let { lifecycle ->
    OpenCraySubAgentEvent(
      runId = runId,
      taskId = taskId,
      phase = OpenCraySubAgentPhase.CANCELLED,
      childRunId = lifecycle.childRunId,
      childTaskId = lifecycle.childTaskId,
      label = lifecycle.label,
      subagentType = lifecycle.subagentType,
      contextMode = lifecycle.contextMode,
      depth = lifecycle.depth,
      summary = summary,
      executionState = SubAgentExecutionState.CANCELLED,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = isHighRisk,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }

  fun detachedRecoveryCheckpoint(
    detachedTaskId: String,
    detachedRunId: String,
    checkpointKind: PromptCheckpointKind,
    nowEpochMs: Long,
  ): PersistedPromptCheckpoint = PersistedPromptCheckpoint(
    sessionId = sessionId,
    runId = detachedRunId,
    taskId = detachedTaskId,
    checkpointId = "checkpoint-$nowEpochMs-${UUID.randomUUID().toString().take(8)}",
    checkpointKind = checkpointKind,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
    toolName = resumeToolName ?: toolName,
    pendingMessageId = null,
    isHighRisk = isHighRisk,
    promptCheckpointBoundary = promptCheckpointBoundary,
    promptResumeState = promptResumeState,
    subAgentApprovedToolName = subAgentApprovalResume?.approvedToolName,
    subAgentPromptResumeState = subAgentApprovalResume?.promptResumeState,
    subAgentIsHighRisk = subAgentApprovalResume?.isHighRisk,
    subAgentAgentId = subAgentApprovalResume?.agentId,
    subAgentChildRunId = subAgentApprovalResume?.childRunId,
    subAgentChildTaskId = subAgentApprovalResume?.childTaskId,
  )

  fun usesExplicitSubAgentHandleControlPlane(): Boolean =
    subAgentApprovalResume != null &&
      subAgentControlTool in EXPLICIT_SUBAGENT_HANDLE_CONTROL_TOOLS
}

private fun OpenCrayRuntimeServiceHost.resolvePendingApproval(
  taskIdOrRunId: String,
): RuntimeServicePendingApprovalResolution? {
  val hostAccess = runtimeAccess.hostAccess
  return knownRuntimeServiceSessionIds(dependencies.chatSessionStore).firstNotNullOfOrNull { sessionId ->
    val session = hostAccess.session(sessionId)
    val runs = session.listRuns()
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    val taskSnapshot = session.snapshot().tasks.firstOrNull { snapshot ->
      val taskRunId = snapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: snapshot.task.id
      isApprovalRequiredError(snapshot.lastErrorCode) &&
        (snapshot.task.id == taskIdOrRunId || taskRunId == taskIdOrRunId)
    }
    val runSnapshot = when {
      taskSnapshot != null -> runsByTaskId[taskSnapshot.task.id]
      else -> runs.firstOrNull { run ->
        isApprovalRequiredError(run.errorCode) &&
          (run.taskId == taskIdOrRunId || run.runId == taskIdOrRunId)
      }
    } ?: return@firstNotNullOfOrNull null
    val resolvedTaskId = taskSnapshot?.task?.id ?: runSnapshot.taskId
    val approvalErrorCode = runSnapshot.errorCode ?: taskSnapshot?.lastErrorCode
    val checkpoint = hostAccess.promptCheckpointStore(sessionId).get(resolvedTaskId)
    if (
      hostAccess.isApprovalApproved(sessionId, resolvedTaskId) ||
      hostAccess.isApprovalRejected(sessionId, resolvedTaskId) ||
      checkpoint?.checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME ||
      checkpoint?.checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME
    ) {
      return@firstNotNullOfOrNull null
    }
    val metadata = runSnapshot.resultMetadata
    RuntimeServicePendingApprovalResolution(
      sessionId = sessionId,
      runId = runSnapshot.runId,
      taskId = resolvedTaskId,
      pendingMessageId = runSnapshot.pendingMessageId
        ?: checkpoint?.pendingMessageId
        ?: taskSnapshot?.task?.metadata?.get(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID)
          ?.trim()
          ?.takeIf(String::isNotBlank),
      toolName = approvalToolName(metadata),
      resumeToolName = checkpoint?.toolName ?: approvalResumeToolName(metadata),
      promptCheckpointBoundary = checkpoint?.runtimeCheckpointBoundaryOrNull()
        ?: OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata),
      promptResumeState = checkpoint?.promptResumeState
        ?: OpenCrayPromptResumeMetadata.decodeFromMetadata(
          metadata = metadata,
          json = PersistenceJson.instance,
        ),
      subAgentApprovalResume = checkpoint?.restoredSubAgentApprovalResume()
        ?: SubAgentApprovalResumeMetadata.decodeFromMetadata(
          metadata = metadata,
          json = PersistenceJson.instance,
        ),
      isHighRisk = checkpoint?.isHighRisk == true || isHighRiskApproval(
        errorCode = approvalErrorCode,
        metadata = metadata,
      ),
      supportsSessionApproval = approvalSupportsSessionScope(metadata),
      subAgentLifecycle = pendingApprovalSubAgentLifecycle(metadata),
      subAgentControlTool = metadata[SubAgentMetadataKeys.CONTROL_TOOL]
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank),
      executionId = runSnapshot.executionId,
      executionOrdinal = runSnapshot.executionOrdinal.takeIf { ordinal -> ordinal > 0 },
      executionKind = runSnapshot.executionKind,
    )
  }
}

private fun OpenCrayRuntimeServiceHost.shouldDeferApprovalDecisionUntilManualResume(
  resolution: RuntimeServicePendingApprovalResolution,
): Boolean {
  val runtimeEvents = runtimeAccess.hostAccess
    .runEventJournalStore(resolution.sessionId)
    .listForRun(resolution.runId)
    .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }
  val latestEvent = runtimeEvents.lastOrNull() ?: return false
  return latestEvent is com.opencray.runtime.OpenCrayCancellationEvent &&
    latestEvent.outcome == "user_interrupted"
}

private fun runtimeApprovalCommandStrings(
  context: Context,
): RuntimeServiceApprovalCommandStrings = RuntimeServiceApprovalCommandStrings(
  agentThinking = runCatching {
    context.getString(R.string.chat_agent_thinking)
  }.getOrDefault("OpenCray is thinking..."),
  approvalApproved = runCatching {
    context.getString(R.string.chat_approval_approved)
  }.getOrDefault("Approval granted. The agent is resuming."),
  approvalApprovedForSession = runCatching {
    context.getString(R.string.chat_approval_approved_for_session)
  }.getOrDefault("Approval granted for this session. The agent is resuming."),
  approvalRejected = runCatching {
    context.getString(R.string.chat_approval_rejected)
  }.getOrDefault("Approval rejected. The requested action was not run."),
)

private fun approvalRecordedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已通过。此决定已记录；手动继续运行后才会生效。"
} else {
  "Approval granted. The decision is recorded and will apply when you manually resume the run."
}

private fun approvalRecordedForSessionText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "本会话审批已通过。此决定已记录；手动继续运行后才会生效。"
} else {
  "Session approval granted. The decision is recorded and will apply when you manually resume the run."
}

private fun approvalRejectedRecordedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已拒绝。此决定已记录；手动继续运行后才会生效。"
} else {
  "Approval rejected. The decision is recorded and will apply when you manually resume the run."
}

private fun delegatedChildApprovalApprovedSummary(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "子任务审批已通过，将继续执行。"
} else {
  "Delegated child approval granted. The child will continue."
}

private fun delegatedChildApprovalApprovedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已通过，子任务正在后台继续执行。"
} else {
  "Approval granted. The delegated child is resuming in the background."
}

private fun delegatedChildApprovalRejectedStopSummary(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "子任务审批被拒绝，子任务已停止。"
} else {
  "Delegated child approval rejected. The child run was stopped."
}

private fun isApprovalRequiredError(errorCode: String?): Boolean =
  errorCode == SERVICE_ERROR_APPROVAL_REQUIRED ||
    errorCode == SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED

private fun isHighRiskApproval(
  errorCode: String?,
  metadata: Map<String, String>,
): Boolean =
  errorCode == SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED ||
    metadata[SubAgentApprovalResumeMetadata.KEY_IS_HIGH_RISK]
      ?.trim()
      ?.equals("true", ignoreCase = true) == true

private fun approvalSupportsSessionScope(
  metadata: Map<String, String>,
): Boolean =
  metadata[ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND]
    ?.trim()
    ?.equals(ProviderNativeWebSearchSupport.APPROVAL_KIND, ignoreCase = true) == true &&
    metadata[ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL]
      ?.trim()
      ?.equals("true", ignoreCase = true) == true

private fun pendingApprovalSubAgentLifecycle(
  metadata: Map<String, String>,
): RuntimeServicePendingApprovalSubAgentLifecycle? {
  val childRunId = metadata["childRunId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
  val childTaskId = metadata["childTaskId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
  val subagentType = metadata["subagentType"]?.trim()?.takeIf(String::isNotBlank)
    ?: return null
  return RuntimeServicePendingApprovalSubAgentLifecycle(
    childRunId = childRunId,
    childTaskId = childTaskId,
    label = metadata["delegationDescription"]?.trim()?.takeIf(String::isNotBlank) ?: "Task",
    subagentType = subagentType,
    contextMode = metadata["subagentContextMode"]?.trim()?.takeIf(String::isNotBlank)
      ?: "delegated",
    depth = metadata["subagentDepth"]?.trim()?.toIntOrNull() ?: 1,
  )
}

private fun isChineseHostLocale(context: Context): Boolean =
  runCatching {
    context.resources.configuration.locales.toLanguageTags()
  }.getOrElse {
    Locale.getDefault().toLanguageTag()
  }.trim().lowercase(Locale.US).startsWith("zh")

private fun approvalToolName(metadata: Map<String, String>): String? =
  metadata["normalizedToolName"]
    ?.takeIf(String::isNotBlank)
    ?: metadata[SubAgentApprovalResumeMetadata.KEY_APPROVED_TOOL_NAME]
      ?.takeIf(String::isNotBlank)
    ?: metadata["canonicalToolName"]
      ?.takeIf(String::isNotBlank)
    ?: metadata["toolName"]
      ?.takeIf(String::isNotBlank)

private fun approvalResumeToolName(metadata: Map<String, String>): String? =
  metadata[com.opencray.runtime.OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME]
    ?.takeIf(String::isNotBlank)
    ?: metadata["canonicalToolName"]
      ?.takeIf(String::isNotBlank)
    ?: metadata["toolName"]
      ?.takeIf(String::isNotBlank)

private fun OpenCrayRuntimeServiceHost.submitDetachedSubAgentRecoveryTaskForApprovedResolution(
  resolution: RuntimeServicePendingApprovalResolution,
  nowEpochMs: Long,
): Boolean {
  if (!resolution.usesExplicitSubAgentHandleControlPlane()) {
    return false
  }
  val session = runtimeAccess.hostAccess.session(resolution.sessionId)
  val handle = session.listSubAgentHandles().firstOrNull { candidate ->
    subAgentApprovalResumeMatchesHandle(
      resume = resolution.subAgentApprovalResume,
      handle = candidate,
    )
  } ?: return false
  val taskId = detachedSubAgentRecoveryTaskId(
    sessionId = session.sessionId,
    agentId = handle.agentId,
    parentRunId = handle.parentRunId,
  )
  val runId = detachedSubAgentRecoveryRunId(taskId)
  runtimeAccess.hostAccess.markApprovalApproved(
    sessionId = resolution.sessionId,
    taskId = taskId,
    toolName = resolution.resumeToolName ?: resolution.toolName,
    promptResumeState = resolution.promptResumeState,
    subAgentApprovalResume = resolution.subAgentApprovalResume,
  )
  runtimeAccess.hostAccess.promptCheckpointStore(resolution.sessionId).upsert(
    resolution.detachedRecoveryCheckpoint(
      detachedTaskId = taskId,
      detachedRunId = runId,
      checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
      nowEpochMs = nowEpochMs,
    ),
  )
  session.submitDetachedSubAgentRecoveryTask(
    agentId = handle.agentId,
    parentRunId = handle.parentRunId,
    taskId = taskId,
    createdAtEpochMs = nowEpochMs,
    submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
  )
  return true
}

private fun subAgentApprovalResumeMatchesHandle(
  resume: SubAgentApprovalResume?,
  handle: SubAgentHandleState,
): Boolean {
  val candidate = resume ?: return false
  return when {
    !candidate.agentId.isNullOrBlank() -> candidate.agentId == handle.agentId
    !candidate.childTaskId.isNullOrBlank() -> candidate.childTaskId == handle.childTaskId
    !candidate.childRunId.isNullOrBlank() -> candidate.childRunId == handle.childRunId
    else -> false
  }
}

private fun PersistedPromptCheckpoint?.restoredSubAgentApprovalResume(): SubAgentApprovalResume? {
  val checkpoint = this ?: return null
  val approvedToolName = checkpoint.subAgentApprovedToolName
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val promptResumeState = checkpoint.subAgentPromptResumeState ?: return null
  return SubAgentApprovalResume(
    approvedToolName = approvedToolName,
    promptResumeState = promptResumeState,
    isHighRisk = checkpoint.subAgentIsHighRisk == true,
    agentId = checkpoint.subAgentAgentId?.trim()?.takeIf(String::isNotBlank),
    childRunId = checkpoint.subAgentChildRunId?.trim()?.takeIf(String::isNotBlank),
    childTaskId = checkpoint.subAgentChildTaskId?.trim()?.takeIf(String::isNotBlank),
  )
}

private const val SERVICE_ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String =
  "HIGH_RISK_APPROVAL_REQUIRED"
