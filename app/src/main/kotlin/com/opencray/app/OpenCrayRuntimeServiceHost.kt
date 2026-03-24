package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.context.RuntimeConversationMessage

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

internal data class OpenCrayRuntimeReplayAccess(
  val approvalRejectionRecorder: (String, String, String, String?, Boolean) -> Unit,
  val approvalApprovedRecorder: (String, String, String, String?, Boolean) -> Unit,
  val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit,
  val runCancellationRecorder: (String, String, String, String?) -> Unit,
  val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit,
)

internal data class RuntimeOwnerWorkSummary(
  val trackedSessionCount: Int = 0,
  val activeRunCount: Int = 0,
  val activeSessionIds: List<String> = emptyList(),
  val pendingWorkSessionIds: List<String> = emptyList(),
  val liveManagedProcessSessionIds: List<String> = emptyList(),
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
  )
}

internal data class RuntimeServiceWorkState(
  val phase: String = PHASE_IDLE,
  val hasActiveWork: Boolean = false,
  val keepAliveRequired: Boolean = false,
  val keepAliveReason: String? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
  val activeSinceEpochMs: Long? = null,
  val idleSinceEpochMs: Long? = changedAtEpochMs,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("hasActiveWork", hasActiveWork)
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
        summary.pendingWorkSessionIds.isNotEmpty() || summary.activeRunCount > 0 ->
          RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN
        else -> null
      }
      val previous = currentState
      if (
        previous.phase == nextPhase &&
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

  fun ensureProcessing()

  fun requestCancel(taskId: String): Boolean

  fun requestRetry(taskId: String): Boolean

  fun requestResumeTask(taskId: String): Boolean

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

  override fun ensureProcessing() = delegate.ensureProcessing()

  override fun requestCancel(taskId: String): Boolean = delegate.requestCancel(taskId)

  override fun requestRetry(taskId: String): Boolean = delegate.requestRetry(taskId)

  override fun requestResumeTask(taskId: String): Boolean = delegate.requestResumeTask(taskId)

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
)

internal data class RuntimeServiceBootstrapResult(
  val scannedSessionIds: List<String>,
  val resumedSessionIds: List<String>,
  val repairedSessionIds: List<String>,
)

internal object OpenCrayRuntimeServiceHostRegistry {
  @Volatile
  private var instance: OpenCrayRuntimeServiceHost? = null

  fun peek(): OpenCrayRuntimeServiceHost? = instance

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
  bootstrapSessionsForRuntimeServiceHost(
    chatSessionStore = dependencies.chatSessionStore,
    runtimeAccess = runtimeAccess,
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
  )
}

internal fun bootstrapSessionsForRuntimeServiceHost(
  chatSessionStore: ChatSessionLocalStore,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
): RuntimeServiceBootstrapResult {
  val state = chatSessionStore.loadState()
  val knownSessionIds = buildList {
    add(state.activeSession.sessionId)
    addAll(state.sessions.map(ChatSessionLocalStore.SessionSummary::sessionId))
  }.distinct()
  val resumedSessionIds = mutableListOf<String>()
  val repairedSessionIds = mutableListOf<String>()

  knownSessionIds.forEach { sessionId ->
    val session = runtimeAccess.hostAccess.session(sessionId)
    val shouldResume = sessionId == state.activeSession.sessionId ||
      session.hasPendingWork() ||
      session.hasLiveManagedProcesses()
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
  }

  return RuntimeServiceBootstrapResult(
    scannedSessionIds = knownSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
  )
}
