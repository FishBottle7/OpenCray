package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentHandleState

internal fun awaitingManualResumeSubAgentResult(
  taskId: String,
): ExecutionResult = ExecutionResult(
  taskId = taskId,
  status = ExecutionStatus.DENIED,
  errorCode = "APPROVAL_REQUIRED",
  errorMessage = "Approval is required.",
  startedAtEpochMs = 3_000L,
  finishedAtEpochMs = 4_000L,
  metadata = emptyMap(),
)

internal fun queuedSubAgentHandle(
  agentId: String,
  parentRunId: String,
): SubAgentHandleState = SubAgentHandleState.queued(
  agentId = agentId,
  childRunId = "child-run-$agentId",
  childTaskId = "child-task-$agentId",
  description = "Queued child $agentId",
  prompt = "Continue delegated work.",
  subagentType = "worker",
  contextMode = "delegated",
  parentRunId = parentRunId,
  parentTaskId = "parent-task-1",
  parentTurn = 0,
  depth = 1,
  activeSkillName = null,
  activeSkillActivationSource = null,
  createdAtEpochMs = 1_000L,
)

internal class RecordingSubAgentRecoveryOperations(
  private val sessionId: String,
) : SessionSubAgentRecoveryOperations {
  val submittedTaskIds = mutableListOf<String>()
  val restoredTaskIds = mutableListOf<String>()
  val restoredTaskStates = mutableListOf<AgentTaskState>()
  val wokenTaskIds = mutableListOf<String>()
  val cancelledTaskIds = mutableListOf<String>()
  private val tasksById = linkedMapOf<String, AgentTask>()
  private val submissionsByTaskId = linkedMapOf<String, AgentRunSubmission>()

  override fun submitRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission {
    submissionsByTaskId[taskId]?.let { existingSubmission ->
      wokenTaskIds += taskId
      return existingSubmission
    }
    submittedTaskIds += taskId
    val submission = AgentRunSubmission(
      sessionId = sessionId,
      runId = syntheticSubAgentRecoveryRunId(taskId),
      taskId = taskId,
      acceptedAtEpochMs = createdAtEpochMs,
    )
    submissionsByTaskId[taskId] = submission
    tasksById[taskId] = syntheticSubAgentRecoveryWaitTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      taskId = taskId,
      createdAtEpochMs = createdAtEpochMs,
      metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
        submissionSource = submissionSource,
      ),
    )
    return submission
  }

  override fun requestCancel(taskId: String): Boolean {
    val removed = tasksById.remove(taskId) != null
    if (removed) {
      submissionsByTaskId.remove(taskId)
      cancelledTaskIds += taskId
    }
    return removed
  }

  override fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = tasksById.containsKey(taskId)

  override fun listTasks(): List<AgentTask> = tasksById.values.toList()

  override fun restorePersistedTask(
    submission: AgentRunSubmission,
    task: AgentTask,
  ) {
    restoredTaskIds += submission.taskId
    restoredTaskStates += task.state
    submissionsByTaskId[submission.taskId] = submission
    tasksById[submission.taskId] = task
  }
}

internal class RecordingSubAgentBackgroundExecution {
  val submittedKeys = mutableListOf<SubAgentExecutionKey>()
  val resumedKeys = mutableListOf<SubAgentExecutionKey>()
  val cancelledKeys = mutableListOf<SubAgentExecutionKey>()
  private val knownKeys = linkedSetOf<SubAgentExecutionKey>()
  private val suspendedKeys = linkedSetOf<SubAgentExecutionKey>()

  fun submit(handle: SubAgentHandleState): Boolean {
    val key = SubAgentExecutionKey.from(handle)
    if (!knownKeys.add(key)) {
      return false
    }
    submittedKeys += key
    if (handle.pendingApprovalDecision != null) {
      suspendedKeys += key
      return false
    }
    return true
  }

  fun resume(handle: SubAgentHandleState): Boolean {
    val key = SubAgentExecutionKey.from(handle)
    if (key !in knownKeys || !suspendedKeys.remove(key)) {
      return false
    }
    resumedKeys += key
    return true
  }

  fun cancel(handle: SubAgentHandleState): Boolean {
    val key = SubAgentExecutionKey.from(handle)
    val known = knownKeys.remove(key)
    val suspended = suspendedKeys.remove(key)
    if (!known && !suspended) {
      return false
    }
    cancelledKeys += key
    return true
  }
}
