package com.opencray.app

import com.opencray.app.projection.retainedRunsForSnapshot
import com.opencray.app.projection.runIdFor
import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.OpenCrayAgentRunEvent

internal fun OpenCrayHostRuntime.recordRuntimeEventLocked(sessionId: String, event: OpenCrayAgentRunEvent) {
  recordRuntimeEventLocked(
    sessionId = sessionId,
    event = event,
    persistToJournal = true,
  )
}

internal fun OpenCrayHostRuntime.recordRuntimeEventLocked(
  sessionId: String,
  event: OpenCrayAgentRunEvent,
  persistToJournal: Boolean,
) {
  chatRuntimeEventState.append(
    sessionId = sessionId,
    event = event,
    maxHistory = OpenCrayHostRuntime.MAX_RUNTIME_EVENT_HISTORY,
  )
  if (persistToJournal) {
    runEventJournalStoreForSession(sessionId).append(event)
  }
  maybeClearPromptCheckpointAfterRuntimeEventLocked(sessionId = sessionId, event = event)
}

internal fun OpenCrayHostRuntime.shouldEmitRuntimeEventDelta(event: OpenCrayAgentRunEvent): Boolean =
  !isDebugOnlyRuntimeEvent(event) && !isInternalPromptCheckpointEvent(event)

internal fun OpenCrayHostRuntime.nextRuntimeEventDeltaSequenceLocked(sessionId: String): Long {
  val next = (runtimeEventDeltaSequencesBySession[sessionId] ?: 0L) + 1L
  runtimeEventDeltaSequencesBySession[sessionId] = next
  return next
}

internal fun OpenCrayHostRuntime.currentRuntimeEventSequenceLocked(sessionId: String): Long =
  runtimeEventDeltaSequencesBySession[sessionId] ?: 0L

internal fun OpenCrayHostRuntime.assignRuntimeEventDeltaSequence(
  sessionId: String,
  payload: Map<String, Any?>,
): Map<String, Any?> = assignRuntimeRealtimeEnvelope(sessionId = sessionId, payload = payload)

internal fun OpenCrayHostRuntime.buildRuntimeTaskDeltaPayload(
  sessionId: String,
  task: AgentTask,
  sequence: Long,
  event: OpenCrayAgentRunEvent? = null,
): Map<String, Any?>? {
  val visibleEvent = event?.takeIf { shouldEmitRuntimeEventDelta(it) }
  val run = runtimeSession(sessionId).findRun(runIdFor(task))
  val visibleRun = run?.takeIf(::isUserVisibleRun)
  val displayedRuns = visibleRun?.let(::listOf).orEmpty()
  val activeRuns = displayedRuns.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap)
  val retainedRuns = retainedRunsForSnapshot(
    displayedRuns,
    isAwaitingDirectionRun = ::isAwaitingDirectionRun,
    isInterruptedOnRestoreRun = ::isInterruptedOnRestoreRun,
  ).map(::runSnapshotToMap)
  val updatedAtEpochMs = maxOf(
    visibleEvent?.emittedAtEpochMs ?: 0L,
    visibleRun?.updatedAtEpochMs ?: 0L,
    visibleRun?.lastEvent?.emittedAtEpochMs ?: 0L,
  )
  if (activeRuns.isEmpty() && retainedRuns.isEmpty() && visibleEvent == null) {
    return null
  }
  return buildMap {
    put("sessionId", sessionId)
    put("sequence", sequence)
    put("executionId", visibleEvent?.executionId ?: visibleRun?.executionId)
    put("updatedAtEpochMs", updatedAtEpochMs)
    put("runPatchMode", "merge")
    put("events", visibleEvent?.let(::runtimeEventToMap)?.let(::listOf) ?: emptyList<Map<String, Any?>>())
    if (visibleRun != null) {
      put("activeRuns", activeRuns)
      put("retainedRuns", retainedRuns)
    }
  }
}
