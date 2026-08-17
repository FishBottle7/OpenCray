package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentHandleState

internal interface SessionSubAgentRecoveryOperations {
  fun submitRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission

  fun requestCancel(taskId: String): Boolean

  fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean

  fun listTasks(): List<AgentTask>

  fun restorePersistedTask(
    submission: AgentRunSubmission,
    task: AgentTask,
  )
}

internal data class SessionSubAgentSchedulerCallbacks(
  val persistedRecoveryRunStates: () -> List<SessionSubAgentRecoveryRunState>,
)

internal interface SessionSubAgentScheduler {
  fun submitRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission

  fun requestCancel(taskId: String): Boolean

  fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean

  fun listVisibleTasks(): List<AgentTask>

  fun restorePersistedVisibleTasks()
}

internal class SessionOwnedSubAgentScheduler(
  private val sessionId: String,
  private val handles: () -> List<SubAgentHandleState>,
  private val recoveryOperations: SessionSubAgentRecoveryOperations,
  private val callbacks: SessionSubAgentSchedulerCallbacks,
  private val runtimeLifecycle: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  private val isAwaitingManualResume: (ExecutionResult) -> Boolean,
) : SessionSubAgentScheduler {
  override fun submitRecoveryTask(
    agentId: String,
    parentRunId: String,
    taskId: String,
    createdAtEpochMs: Long,
    submissionSource: String,
  ): AgentRunSubmission = recoveryOperations.submitRecoveryTask(
    agentId = agentId,
    parentRunId = parentRunId,
    taskId = taskId,
    createdAtEpochMs = createdAtEpochMs,
    submissionSource = submissionSource,
  )

  override fun requestCancel(taskId: String): Boolean = recoveryOperations.requestCancel(taskId)

  override fun requestResume(
    taskId: String,
    executionKind: String,
    taskMetadataUpdates: Map<String, String>,
  ): Boolean = recoveryOperations.requestResume(
    taskId = taskId,
    executionKind = executionKind,
    taskMetadataUpdates = taskMetadataUpdates,
  )

  override fun listVisibleTasks(): List<AgentTask> = recoveryOperations.listTasks()

  override fun restorePersistedVisibleTasks() {
    val handlesByTaskId = handles()
      .associateBy { handle ->
        syntheticSubAgentRecoveryTaskId(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
      }
    callbacks.persistedRecoveryRunStates()
      .mapNotNull { runState ->
        val handle = handlesByTaskId[runState.submission.taskId] ?: return@mapNotNull null
        val lastResult = runState.lastResult
        val restoredTaskState = when {
          lastResult == null && handle.isDetachedBackgroundQueued() -> AgentTaskState.QUEUED
          lastResult != null && isAwaitingManualResume(lastResult) -> AgentTaskState.SUSPENDED
          else -> return@mapNotNull null
        }
        val restoredMetadata = runtimeLifecycle.taskMetadata(
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        ) + subAgentRecoveryExecutionMetadata(lastResult?.metadata.orEmpty())
        runState.submission to syntheticSubAgentRecoveryWaitTask(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
          taskId = runState.submission.taskId,
          createdAtEpochMs = runState.submission.acceptedAtEpochMs,
          metadata = restoredMetadata,
        ).copy(
          state = restoredTaskState,
          updatedAtEpochMs = maxOf(
            runState.submission.acceptedAtEpochMs,
            handle.updatedAtEpochMs,
            lastResult?.finishedAtEpochMs ?: 0L,
          ),
        )
      }
      .forEach { (submission, task) ->
        recoveryOperations.restorePersistedTask(
          submission = submission,
          task = task,
        )
      }
  }
}

private fun subAgentRecoveryExecutionMetadata(
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

internal fun queuedSubAgentRecoveryKeys(
  snapshot: SessionQueueSnapshot,
): Set<SubAgentExecutionKey> = snapshot.tasks
  .asSequence()
  .filter { taskSnapshot ->
    taskSnapshot.lifecycleState != QueueTaskLifecycleState.COMPLETED &&
      taskSnapshot.lifecycleState != QueueTaskLifecycleState.CANCELLED &&
      taskSnapshot.lifecycleState != QueueTaskLifecycleState.FAILED &&
      taskSnapshot.task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE] ==
      RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY
  }
  .mapNotNull { taskSnapshot -> syntheticSubAgentRecoveryExecutionKey(taskSnapshot.task) }
  .toSet()
