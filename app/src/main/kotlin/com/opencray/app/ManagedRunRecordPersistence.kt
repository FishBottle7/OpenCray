package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.runtime.OpenCrayAgentRunEvent

internal data class ManagedRunRecord(
  val submission: AgentRunSubmission,
  val pendingMessageId: String? = null,
  val managedProcessIds: List<String> = emptyList(),
  val detachedTask: AgentTask? = null,
  val lastEvent: OpenCrayAgentRunEvent? = null,
  val lastResult: ExecutionResult? = null,
)

internal fun ManagedAgentSessionHandle.restorePersistedRunRecordsLocked() {
  runRecordStore.list().forEach { persisted ->
    runRecordsById[persisted.runId] = ManagedRunRecord(
      submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = persisted.runId,
        taskId = persisted.taskId,
        acceptedAtEpochMs = persisted.acceptedAtEpochMs,
      ),
      pendingMessageId = persisted.pendingMessageId,
      managedProcessIds = persisted.managedProcessIds,
      detachedTask = persisted.detachedTask,
      lastEvent = persisted.lastEvent?.toRuntimeEventOrNull(),
      lastResult = persisted.lastResult,
    )
  }
}

internal fun ManagedAgentSessionHandle.seedMissingRunRecordsLocked(queueSnapshot: SessionQueueSnapshot) {
  queueSnapshot.tasks.forEach { taskSnapshot ->
    val runId = runIdFor(taskSnapshot.task)
    val existing = runRecordsById[runId]
    if (existing == null) {
      val seeded = ManagedRunRecord(
        submission = AgentRunSubmission(
          sessionId = sessionId,
          runId = runId,
          taskId = taskSnapshot.task.id,
          acceptedAtEpochMs = taskSnapshot.task.createdAtEpochMs,
        ),
        pendingMessageId = taskSnapshot.task.metadata[
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
        ],
      )
      runRecordsById[runId] = seeded
      persistRunRecordLocked(seeded)
    } else if (existing.pendingMessageId == null) {
      val updated = existing.copy(
        pendingMessageId = taskSnapshot.task.metadata[
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID
        ],
      )
      runRecordsById[runId] = updated
      persistRunRecordLocked(updated)
    }
  }
}

internal fun ManagedAgentSessionHandle.persistRunRecordLocked(record: ManagedRunRecord) {
  runRecordStore.upsert(
    PersistedAgentRunRecord(
      runId = record.submission.runId,
      taskId = record.submission.taskId,
      acceptedAtEpochMs = record.submission.acceptedAtEpochMs,
      pendingMessageId = record.pendingMessageId,
      managedProcessIds = record.managedProcessIds,
      detachedTask = record.detachedTask,
      lastResult = record.lastResult,
      lastEvent = record.lastEvent?.toPersistedRecord(),
    ),
  )
}
