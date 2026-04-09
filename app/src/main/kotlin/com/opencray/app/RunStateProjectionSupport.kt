package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.process.ManagedProcessSnapshot

internal fun associatedManagedProcessesForProjection(
  taskId: String,
  existingIds: List<String>,
  managedProcessesById: Map<String, ManagedProcessSnapshot>,
): List<ManagedProcessSnapshot> = (
  existingIds +
    managedProcessesById.values
      .asSequence()
      .filter { snapshot -> snapshot.taskId == taskId }
      .map(ManagedProcessSnapshot::processId)
      .toList()
  ).distinct().mapNotNull(managedProcessesById::get)

internal fun projectedLifecycleStateForRestoreResult(
  original: QueueTaskLifecycleState?,
  result: ExecutionResult?,
): QueueTaskLifecycleState? = if (
  isInterruptedOnRestoreProjectionResult(result) &&
  (original == null || !isTerminalProjectionLifecycle(original))
) {
  QueueTaskLifecycleState.FAILED
} else {
  original
}

internal fun projectedTaskStateForRestoreResult(
  original: AgentTaskState?,
  result: ExecutionResult?,
): AgentTaskState? = if (
  isInterruptedOnRestoreProjectionResult(result) &&
  (original == null || !isTerminalProjectionTaskState(original))
) {
  AgentTaskState.FAILED
} else {
  original
}

internal fun visibleProjectedRunResult(
  taskSnapshot: SessionQueueTaskSnapshot?,
  result: ExecutionResult?,
): ExecutionResult? {
  if (taskSnapshot == null || result == null) {
    return result
  }
  if (isInterruptedOnRestoreProjectionResult(result)) {
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

internal fun shouldShowProjectedTaskSnapshotError(
  taskSnapshot: SessionQueueTaskSnapshot?,
): Boolean = when (taskSnapshot?.lifecycleState) {
  QueueTaskLifecycleState.SUSPENDED,
  QueueTaskLifecycleState.FAILED,
  QueueTaskLifecycleState.CANCELLED,
  -> true

  else -> false
}

internal fun isInterruptedOnRestoreProjectionResult(
  result: ExecutionResult?,
): Boolean =
  result?.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
    result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

internal fun ManagedProcessSnapshot.isProjectionInterruptedOnRestoreState(): Boolean =
  errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
    errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
    metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

internal fun ManagedProcessSnapshot.isProjectionTerminalAfterRestoreState(): Boolean = status.isTerminal

internal fun isTerminalProjectionLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
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

private fun isTerminalProjectionTaskState(state: AgentTaskState): Boolean = when (state) {
  AgentTaskState.COMPLETED,
  AgentTaskState.CANCELLED,
  AgentTaskState.FAILED,
  -> true

  AgentTaskState.QUEUED,
  AgentTaskState.RUNNING,
  AgentTaskState.SUSPENDED,
  -> false
}
