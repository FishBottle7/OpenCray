package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
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

