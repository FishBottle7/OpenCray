package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentHandleState

internal data class SessionSubAgentActorDriverCallbacks(
  val activeParentRunIds: () -> Set<String>,
  val approvedRecoveryTaskIds: () -> Set<String>,
  val rejectedRecoveryTaskIds: () -> Set<String>,
  val recoveryTaskIdForHandle: (SubAgentHandleState) -> String,
  val submitActorTask: (SubAgentHandleState) -> Boolean,
  val resumeActorTask: (SubAgentHandleState) -> Boolean,
  val cancelActorTask: (SubAgentHandleState) -> Boolean,
)

internal interface SessionSubAgentActorDriver {
  fun onSessionResumed(): Int

  fun scheduleRecoverableSubAgents(): Int
}

internal class SessionOwnedSubAgentActorDriver(
  private val handles: () -> List<SubAgentHandleState>,
  private val recoveryOperations: SessionSubAgentRecoveryOperations,
  private val callbacks: SessionSubAgentActorDriverCallbacks,
) : SessionSubAgentActorDriver {
  override fun onSessionResumed(): Int {
    val scheduled = scheduleRecoverableSubAgents()
    val approvedRecoveryTaskIds = callbacks.approvedRecoveryTaskIds()
    val rejectedRecoveryTaskIds = callbacks.rejectedRecoveryTaskIds()
    recoveryOperations.listTasks()
      .asSequence()
      .filter { task ->
        task.state == AgentTaskState.SUSPENDED &&
          task.id in rejectedRecoveryTaskIds
      }
      .forEach { task ->
        recoveryOperations.requestCancel(task.id)
      }
    recoveryOperations.listTasks()
      .asSequence()
      .filter { task ->
        task.state == AgentTaskState.QUEUED ||
          (task.state == AgentTaskState.SUSPENDED && task.id in approvedRecoveryTaskIds)
      }
      .mapNotNull { task ->
        val handleKey = syntheticSubAgentRecoveryExecutionKey(task) ?: return@mapNotNull null
        QueuedVisibleRecoveryTask(
          taskId = task.id,
          createdAtEpochMs = task.createdAtEpochMs,
          submissionSource = task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE]
            ?: RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
          handleKey = handleKey,
        )
      }
      .forEach { queuedTask ->
        recoveryOperations.submitRecoveryTask(
          agentId = queuedTask.handleKey.agentId,
          parentRunId = queuedTask.handleKey.parentRunId,
          taskId = queuedTask.taskId,
          createdAtEpochMs = queuedTask.createdAtEpochMs,
          submissionSource = queuedTask.submissionSource,
        )
      }
    return scheduled
  }

  override fun scheduleRecoverableSubAgents(): Int {
    val activeParentRunIds = callbacks.activeParentRunIds()
    val approvedRecoveryTaskIds = callbacks.approvedRecoveryTaskIds()
    val rejectedRecoveryTaskIds = callbacks.rejectedRecoveryTaskIds()
    return handles()
      .asSequence()
      .filter { handle -> handle.parentRunId !in activeParentRunIds }
      .count { handle ->
        syncActorTask(
          handle = handle,
          approvedRecoveryTaskIds = approvedRecoveryTaskIds,
          rejectedRecoveryTaskIds = rejectedRecoveryTaskIds,
        )
      }
  }

  private fun syncActorTask(
    handle: SubAgentHandleState,
    approvedRecoveryTaskIds: Set<String>,
    rejectedRecoveryTaskIds: Set<String>,
  ): Boolean {
    val recoveryTaskId = callbacks.recoveryTaskIdForHandle(handle)
    val approvedByCheckpoint =
      handle.pendingApprovalDecision == null &&
        handle.pendingApprovalResume != null &&
        recoveryTaskId in approvedRecoveryTaskIds
    val rejectedByCheckpoint =
      handle.pendingApprovalDecision == null &&
        handle.pendingApprovalResume != null &&
        recoveryTaskId in rejectedRecoveryTaskIds
    return when {
      handle.pendingApprovalDecision?.approved == false || rejectedByCheckpoint -> {
        val submitted = callbacks.submitActorTask(handle)
        val cancelled = callbacks.cancelActorTask(handle)
        submitted || cancelled
      }

      handle.pendingApprovalDecision?.approved == true || approvedByCheckpoint -> {
        val submitted = callbacks.submitActorTask(handle)
        val resumed = callbacks.resumeActorTask(handle)
        submitted || resumed
      }

      handle.shouldEnsureDetachedBackgroundExecution() -> callbacks.submitActorTask(handle)
      else -> false
    }
  }
}

private data class QueuedVisibleRecoveryTask(
  val taskId: String,
  val createdAtEpochMs: Long,
  val submissionSource: String,
  val handleKey: SubAgentExecutionKey,
)
