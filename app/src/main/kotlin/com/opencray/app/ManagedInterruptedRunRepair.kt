package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY

internal fun ManagedAgentSessionHandle.repairRestoredInterruptedRunsLocked(
  queueSnapshot: SessionQueueSnapshot,
  managedProcesses: List<ManagedProcessSnapshot>,
): Boolean {
  var repairedAny = false
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
    val acknowledgedProcessIds = taskSnapshot
      ?.task
      ?.metadata
      ?.get(METADATA_ACKNOWLEDGED_INTERRUPTED_PROCESS_IDS)
      ?.let(::decodeAcknowledgedInterruptedProcessIds)
      .orEmpty()
    val repairCandidates = associatedProcesses.filterNot { process ->
      process.processId in acknowledgedProcessIds
    }
    if (!shouldRepairRestoredInterruptedRun(taskSnapshot, record, repairCandidates)) {
      return@forEach
    }
    val repairedResult = record.lastResult
      ?.takeIf { isInterruptedOnRestoreResult(it) }
      ?: repairedInterruptedRestoreResult(
        record = record,
        associatedProcesses = repairCandidates,
      )
    if (
      !loop.reconcileFailure(
        taskId = taskId,
        errorCode = repairedResult.errorCode
          ?: ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE,
        errorMessage = repairedResult.errorMessage
          ?: "Managed process execution was interrupted during restore.",
      )
    ) {
      return@forEach
    }
    repairedAny = true
    val updated = record.copy(
      managedProcessIds = (
        record.managedProcessIds +
          associatedProcesses.map(ManagedProcessSnapshot::processId)
        ).distinct(),
      lastResult = repairedResult,
    )
    if (updated != record) {
      runRecordsById[runId] = updated
      persistRunRecordLocked(updated)
    }
  }
  return repairedAny
}

internal fun ManagedAgentSessionHandle.shouldRepairRestoredInterruptedRun(
  taskSnapshot: com.opencray.core.orchestrator.SessionQueueTaskSnapshot?,
  record: ManagedRunRecord,
  associatedProcesses: List<ManagedProcessSnapshot>,
): Boolean {
  if (record.lastResult != null && !isInterruptedOnRestoreResult(record.lastResult)) {
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

internal fun ManagedAgentSessionHandle.repairedInterruptedRestoreResult(
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
  val restoreMetadata = associatedManagedProcessRestoreMetadata(associatedProcesses)
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
    ) + restoreMetadata,
  )
}

internal fun ManagedAgentSessionHandle.associatedManagedProcessRestoreMetadata(
  associatedProcesses: List<ManagedProcessSnapshot>,
): Map<String, String> {
  val restoreMetadata = associatedProcesses
    .asSequence()
    .map(ManagedProcessSnapshot::metadata)
    .firstOrNull { metadata ->
      metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY]
        ?.trim()
        ?.takeIf(String::isNotBlank) != null
    }
    ?: return emptyMap()
  return buildMap {
    restoreMetadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { scope -> put(MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY, scope) }
    restoreMetadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { processStartId ->
        put(MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY, processStartId)
      }
    restoreMetadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { runtimeControllerId ->
        put(MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY, runtimeControllerId)
      }
    restoreMetadata[MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { durableRuntimeControllerId ->
        put(
          MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY,
          durableRuntimeControllerId,
        )
      }
  }
}
