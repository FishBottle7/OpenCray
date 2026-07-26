package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState

internal data class OpenCrayRuntimeReplayAccess(
  val approvalRejectionRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit,
  val approvalApprovedRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit,
  val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit,
  val runCancellationRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit,
  val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit,
)

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

  fun terminateManagedProcesses(
    processIds: Set<String>,
  ): List<com.opencray.runtime.process.ManagedProcessSnapshot> = emptyList()

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
      metadata = submissionSourceTaskMetadata(submissionSource),
    ),
  )

  fun ensureRecoverableDetachedSubAgentTasks(): Int = 0
}

internal interface RuntimeOwnerObservationAccess {
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor

  fun observe(listener: AgentSessionRuntimeListener): () -> Unit

  fun activeWorkSummary(): RuntimeOwnerWorkSummary
}

internal interface RuntimeSessionDirectoryAccess {

  fun session(sessionId: String): OpenCrayRuntimeSessionAccess

  fun existingSession(sessionId: String): OpenCrayRuntimeSessionAccess? = session(sessionId)

  fun sessionOwnerTarget(sessionId: String): RuntimeServiceTarget? = null

  fun ownsSession(sessionId: String): Boolean = true

  fun releaseSession(sessionId: String)

  fun releaseIdleSessions()
}

internal interface RuntimeSessionPersistenceAccess {
  fun runEventJournalStore(sessionId: String): RunEventJournalStore

  fun promptCheckpointStore(sessionId: String): PromptCheckpointStore

  fun supplementStore(sessionId: String): SessionSupplementStore
}

internal interface RuntimeApprovalRegistryAccess {
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

internal interface RuntimeRunLookupAccess :
  RuntimeSessionDirectoryAccess,
  RuntimeSessionPersistenceAccess

internal interface RuntimeNotificationAccess :
  RuntimeOwnerObservationAccess,
  RuntimeSessionDirectoryAccess

internal interface RuntimeNotificationHostAccess :
  RuntimeNotificationAccess,
  RuntimeRunLookupAccess

internal interface RuntimeApprovalDecisionHostAccess :
  RuntimeRunLookupAccess,
  RuntimeChatMutationAccess

internal interface RuntimeChatMutationAccess :
  RuntimeSessionDirectoryAccess,
  RuntimeSessionPersistenceAccess,
  RuntimeApprovalRegistryAccess

internal interface RuntimeChatSubmissionHostAccess :
  RuntimeOwnerObservationAccess,
  RuntimeSessionDirectoryAccess,
  RuntimeSessionPersistenceAccess

internal interface OpenCrayRuntimeHostAccess :
  RuntimeNotificationHostAccess,
  RuntimeApprovalDecisionHostAccess,
  RuntimeChatSubmissionHostAccess

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

  override fun terminateManagedProcesses(
    processIds: Set<String>,
  ): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
    delegate.terminateManagedProcesses(processIds)

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

  override fun existingSession(sessionId: String): OpenCrayRuntimeSessionAccess? =
    sessionRuntimeManager.existingSession(sessionId)?.let(::AgentSessionHandleRuntimeSessionAccess)

  override fun sessionOwnerTarget(sessionId: String): RuntimeServiceTarget? =
    sessionRuntimeManager.sessionOwnerTarget(sessionId)

  override fun ownsSession(sessionId: String): Boolean =
    sessionRuntimeManager.ownsSession(sessionId)

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
  val onDeviceWarmupPlanner: (String) -> OnDeviceLlmWarmupSpec? = { null },
)

internal fun RetainedInProcessOpenCrayRuntimeOwnerCore.toRuntimeOwnerAccess(): OpenCrayRuntimeOwnerAccess =
  OpenCrayRuntimeOwnerAccess(
    lifecycleDescriptor = currentLifecycleDescriptor(),
    hostAccess = DefaultOpenCrayRuntimeHostAccess(
      lifecycleDescriptor = currentLifecycleDescriptor(),
      sessionRuntimeManager = sessionRuntimeManager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      supplementStoreFactory = supplementStoreFactory,
      approvalRegistry = approvalRegistry,
    ),
    transcriptMessagesProvider = transcriptMessagesProvider,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    replayAccess = replayAccess,
    onDeviceWarmupPlanner = onDeviceWarmupPlanner,
  )
