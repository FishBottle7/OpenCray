package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecision

internal interface AgentSessionRuntimeManager {
  fun forSession(sessionId: String): AgentSessionHandle

  fun existingSession(sessionId: String): AgentSessionHandle? = forSession(sessionId)

  fun sessionOwnerTarget(sessionId: String): RuntimeServiceTarget? = null

  fun ownsSession(sessionId: String): Boolean = true

  fun observe(listener: AgentSessionRuntimeListener): () -> Unit

  fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()

  fun release(sessionId: String)

  fun releaseAllSessions() = Unit

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

  fun terminateManagedProcesses(processIds: Set<String>): List<ManagedProcessSnapshot> = emptyList()

  fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

  fun listSubAgentHandles(): List<SubAgentHandleState> = emptyList()

  fun listClosedSubAgentHandles(): List<SubAgentHandleState> = emptyList()

  fun hasActiveSubAgentExecution(
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun hasLiveSubAgentWork(): Boolean =
    listSubAgentHandles().any(SubAgentHandleState::hasLiveBackgroundExecution)

  fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) = Unit

  fun setSubAgentPendingApprovalDecision(
    agentId: String,
    parentRunId: String,
    pendingApprovalDecision: SubAgentPendingApprovalDecision?,
  ): Boolean = false

  fun listVisibleSubAgentTasks(): List<AgentTask> = emptyList()

  fun submitSubAgentRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission = submitTask(
    syntheticSubAgentRecoveryWaitTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      taskId = taskId,
      createdAtEpochMs = createdAtEpochMs,
      metadata = submissionSourceTaskMetadata(submissionSource),
    ),
  )
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

  fun executeSubAgentRecoveryTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult? = null

  fun executeSubAgentActorTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult? = null

  fun ensureSubAgentRecoveryExecution(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun ensureBackgroundSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun waitForSubAgentRecoveryExecution(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult? = null

  fun cancelActiveSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun hasActiveSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean = false

  fun listSubAgentHandles(sessionId: String): List<SubAgentHandleState> = emptyList()

  fun listClosedSubAgentHandles(sessionId: String): List<SubAgentHandleState> = emptyList()

  fun updateSubAgentHandlePendingApprovalDecision(
    sessionId: String,
    agentId: String,
    parentRunId: String,
    pendingApprovalDecision: SubAgentPendingApprovalDecision?,
  ): SubAgentHandleState? = null

  fun retainKnownSubAgentParentRuns(sessionId: String, parentRunIds: Set<String>) = Unit
}
