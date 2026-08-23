package com.opencray.app.projection

import com.opencray.app.AgentRunSnapshot
import com.opencray.app.OpenCrayRuntimeSessionAccess
import com.opencray.app.PersistedPromptCheckpoint
import com.opencray.app.PromptCheckpointStore
import com.opencray.app.SubAgentSessionLinkStoreFactory
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentSessionLink
import java.util.Locale

internal data class SubAgentActivitySnapshot(
  val parentRunId: String,
  val parentTaskId: String,
  val agentId: String?,
  val childSessionId: String?,
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val contextModeSource: String? = null,
  val depth: Int,
  val phase: String,
  val status: String?,
  val executionState: String?,
  val continuationKind: String?,
  val resumable: Boolean,
  val requiresUserAction: Boolean,
  val isHighRisk: Boolean,
  val closed: Boolean,
  val summary: String?,
  val startedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val eventCount: Int,
  val hasActiveExecution: Boolean,
  val mailboxMessageCount: Int,
  val mailboxPendingMessageCount: Int,
  val mailboxLastDeliveredMessageId: String?,
  val hasPendingApprovalResume: Boolean,
  val pendingApprovalToolName: String?,
  val pendingApprovalIsHighRisk: Boolean,
  val pendingApprovalChildRunId: String?,
  val pendingApprovalChildTaskId: String?,
  val liveContext: Map<String, Any?>? = null,
)

private data class SubAgentActivityAccumulator(
  val firstEvent: OpenCraySubAgentEvent,
  val latestEvent: OpenCraySubAgentEvent,
  val eventCount: Int,
)

private data class DurableSubAgentSnapshot(
  val snapshot: SubAgentActivitySnapshot,
  val sourcePriority: Int,
)

private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_CHECKPOINT: Int = 1
private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_LINK: Int = 2
private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_HANDLE: Int = 3

internal fun subAgentSnapshotsForActivity(
  sessionId: String,
  displayedRuns: List<AgentRunSnapshot>,
  recentEvents: List<OpenCrayAgentRunEvent>,
  sessionAccessor: (String) -> OpenCrayRuntimeSessionAccess,
  subAgentLinkStoreFactory: SubAgentSessionLinkStoreFactory,
  promptCheckpointStoreFor: (String) -> PromptCheckpointStore,
): List<SubAgentActivitySnapshot> {
  val registrySnapshots = subAgentSnapshotsFromDurableSources(
    sessionId = sessionId,
    sessionAccessor = sessionAccessor,
    subAgentLinkStoreFactory = subAgentLinkStoreFactory,
    promptCheckpointStoreFor = promptCheckpointStoreFor,
  )
  val visibleRunIds = displayedRuns
    .mapTo(linkedSetOf(), AgentRunSnapshot::runId)
    .ifEmpty {
      recentEvents
        .mapNotNullTo(linkedSetOf()) { event ->
          event.runId.trim().takeIf(String::isNotBlank)
        }
    }
    .ifEmpty {
      registrySnapshots.mapNotNullTo(linkedSetOf()) { snapshot ->
        snapshot.parentRunId.trim().takeIf(String::isNotBlank)
      }
    }
  if (visibleRunIds.isEmpty()) {
    return emptyList()
  }
  val eventSnapshotsByKey = linkedMapOf<String, SubAgentActivitySnapshot>()
  val grouped = linkedMapOf<String, SubAgentActivityAccumulator>()
  recentEvents.forEach { event ->
    val subAgentEvent = event as? OpenCraySubAgentEvent ?: return@forEach
    if (subAgentEvent.runId !in visibleRunIds) {
      return@forEach
    }
    val keys = subAgentRegistryKeys(subAgentEvent)
    val key = grouped.aliasSubAgentRegistryKey(keys)
      ?: keys.firstOrNull()
      ?: return@forEach
    val existing = grouped[key]
    grouped[key] = if (existing == null) {
      SubAgentActivityAccumulator(
        firstEvent = subAgentEvent,
        latestEvent = subAgentEvent,
        eventCount = 1,
      )
    } else {
      existing.copy(
        latestEvent = subAgentEvent,
        eventCount = existing.eventCount + 1,
      )
    }
  }
  grouped.values.forEach { accumulator ->
    val firstEvent = accumulator.firstEvent
    val latestEvent = accumulator.latestEvent
    val snapshot = SubAgentActivitySnapshot(
      parentRunId = latestEvent.runId,
      parentTaskId = latestEvent.taskId,
      agentId = latestEvent.agentId,
      childSessionId = null,
      childRunId = latestEvent.childRunId,
      childTaskId = latestEvent.childTaskId,
      label = latestEvent.label,
      subagentType = latestEvent.subagentType,
      contextMode = latestEvent.contextMode,
      depth = latestEvent.depth,
      phase = latestEvent.phase.name.lowercase(),
      status = latestEvent.executionState?.wireValue,
      executionState = latestEvent.executionState?.wireValue,
      continuationKind = latestEvent.continuationKind?.wireValue,
      resumable = latestEvent.resumable,
      requiresUserAction = latestEvent.requiresUserAction,
      isHighRisk = latestEvent.isHighRisk,
      closed = latestEvent.closed,
      summary = latestEvent.summary,
      startedAtEpochMs = firstEvent.emittedAtEpochMs,
      updatedAtEpochMs = latestEvent.emittedAtEpochMs,
      eventCount = accumulator.eventCount,
      hasActiveExecution = false,
      mailboxMessageCount = 0,
      mailboxPendingMessageCount = 0,
      mailboxLastDeliveredMessageId = null,
      hasPendingApprovalResume = false,
      pendingApprovalToolName = null,
      pendingApprovalIsHighRisk = false,
      pendingApprovalChildRunId = null,
      pendingApprovalChildTaskId = null,
      liveContext = latestEvent.liveContext?.toMap(),
    )
    val snapshotKeys = subAgentRegistryKeys(snapshot)
    val snapshotKey = eventSnapshotsByKey.aliasSubAgentRegistryKey(snapshotKeys)
      ?: snapshotKeys.firstOrNull()
      ?: return@forEach
    eventSnapshotsByKey[snapshotKey] = snapshot
  }
  registrySnapshots
    .filter { snapshot -> snapshot.parentRunId in visibleRunIds }
    .forEach { snapshot ->
      val keys = subAgentRegistryKeys(snapshot)
      val key = eventSnapshotsByKey.aliasSubAgentRegistryKey(keys)
        ?: keys.firstOrNull()
        ?: return@forEach
      val existing = eventSnapshotsByKey[key]
      eventSnapshotsByKey[key] = if (existing == null) {
        snapshot
      } else {
        snapshot.copy(
          startedAtEpochMs = minOf(existing.startedAtEpochMs, snapshot.startedAtEpochMs),
          updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, snapshot.updatedAtEpochMs),
          eventCount = maxOf(existing.eventCount, snapshot.eventCount),
          closed = existing.closed || snapshot.closed,
          hasActiveExecution = snapshot.hasActiveExecution,
          mailboxMessageCount = snapshot.mailboxMessageCount,
          mailboxPendingMessageCount = snapshot.mailboxPendingMessageCount,
          mailboxLastDeliveredMessageId = snapshot.mailboxLastDeliveredMessageId,
          hasPendingApprovalResume = snapshot.hasPendingApprovalResume,
          pendingApprovalToolName = snapshot.pendingApprovalToolName,
          pendingApprovalIsHighRisk = snapshot.pendingApprovalIsHighRisk,
          pendingApprovalChildRunId = snapshot.pendingApprovalChildRunId,
          pendingApprovalChildTaskId = snapshot.pendingApprovalChildTaskId,
          liveContext = snapshot.liveContext ?: existing.liveContext,
          contextModeSource = snapshot.contextModeSource ?: existing.contextModeSource,
        )
      }
    }
  return eventSnapshotsByKey.values.toList()
}

internal fun subAgentRegistryKeys(
  event: OpenCraySubAgentEvent,
): List<String> = subAgentRegistryKeys(
  parentRunId = event.runId,
  agentId = event.agentId,
  childRunId = event.childRunId,
  childTaskId = event.childTaskId,
  label = event.label,
)

internal fun subAgentRegistryKeys(
  snapshot: SubAgentActivitySnapshot,
): List<String> = subAgentRegistryKeys(
  parentRunId = snapshot.parentRunId,
  agentId = snapshot.agentId,
  childRunId = snapshot.childRunId,
  childTaskId = snapshot.childTaskId,
  label = snapshot.label,
)

internal fun subAgentRegistryKeys(
  parentRunId: String,
  agentId: String?,
  childRunId: String,
  childTaskId: String,
  label: String,
): List<String> = listOfNotNull(
  agentId?.trim()?.takeIf(String::isNotBlank),
  childRunId.trim().takeIf(String::isNotBlank),
  childTaskId.trim().takeIf(String::isNotBlank),
  label.trim().takeIf(String::isNotBlank),
).distinct().map { identity ->
  listOf(parentRunId, identity).joinToString(separator = "|")
}

internal fun <T> Map<String, T>.aliasSubAgentRegistryKey(
  keys: List<String>,
): String? = keys.firstOrNull(::containsKey)

internal fun subAgentSnapshotsFromDurableSources(
  sessionId: String,
  sessionAccessor: (String) -> OpenCrayRuntimeSessionAccess,
  subAgentLinkStoreFactory: SubAgentSessionLinkStoreFactory,
  promptCheckpointStoreFor: (String) -> PromptCheckpointStore,
): List<SubAgentActivitySnapshot> {
  val latestByKey = linkedMapOf<String, DurableSubAgentSnapshot>()
  fun mergeSnapshot(
    snapshot: SubAgentActivitySnapshot,
    sourcePriority: Int,
  ) {
    val keys = subAgentRegistryKeys(snapshot)
    val key = latestByKey.aliasSubAgentRegistryKey(keys)
      ?: keys.firstOrNull()
      ?: return
    val existing = latestByKey[key]
    if (
      existing == null ||
      snapshot.updatedAtEpochMs > existing.snapshot.updatedAtEpochMs ||
      (
        snapshot.updatedAtEpochMs == existing.snapshot.updatedAtEpochMs &&
          sourcePriority > existing.sourcePriority
        )
    ) {
      latestByKey[key] = DurableSubAgentSnapshot(
        snapshot = snapshot,
        sourcePriority = sourcePriority,
      )
    }
  }
  val session = sessionAccessor(sessionId)
  val closedHandleKeys = session.listClosedSubAgentHandles()
    .asSequence()
    .map { handle -> handle.parentRunId to handle.agentId }
    .toSet()
  session
    .listSubAgentHandles()
    .forEach { handle ->
      mergeSnapshot(
        snapshot = subAgentActivitySnapshot(
          handle = handle,
          hasActiveExecution = session.hasActiveSubAgentExecution(
            agentId = handle.agentId,
            parentRunId = handle.parentRunId,
          ),
          closed = (handle.parentRunId to handle.agentId) in closedHandleKeys,
        ),
        sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_HANDLE,
      )
    }
  subAgentLinkStoreFactory.forChatSession(sessionId)
    .list()
    .forEach { link ->
      mergeSnapshot(
        snapshot = subAgentActivitySnapshot(link),
        sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_LINK,
      )
    }
  promptCheckpointStoreFor(sessionId)
    .list()
    .asReversed()
    .forEach { checkpoint ->
      checkpointSubAgentHandles(checkpoint).forEach { handle ->
        mergeSnapshot(
          snapshot = subAgentActivitySnapshot(
            handle = handle,
            hasActiveExecution = false,
            closed = false,
          ),
          sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_CHECKPOINT,
        )
      }
    }
  return latestByKey.values.map(DurableSubAgentSnapshot::snapshot)
}

internal fun checkpointSubAgentHandles(
  checkpoint: PersistedPromptCheckpoint,
): Sequence<SubAgentHandleState> = sequenceOf(
  checkpoint.promptResumeState,
  checkpoint.subAgentPromptResumeState,
).filterNotNull().flatMap { state -> state.subAgentHandles.asSequence() }

internal fun subAgentActivitySnapshot(
  link: SubAgentSessionLink,
): SubAgentActivitySnapshot {
  val state = SubAgentExecutionState.fromWireValue(link.status) ?: SubAgentExecutionState.RUNNING
  return SubAgentActivitySnapshot(
    parentRunId = link.parentRunId,
    parentTaskId = "",
    agentId = link.agentId,
    childSessionId = link.childSessionId,
    childRunId = link.childRootRunId.orEmpty(),
    childTaskId = link.childRootTaskId.orEmpty(),
    label = link.label,
    subagentType = link.subagentType,
    contextMode = link.contextMode,
    depth = link.depth,
    phase = subAgentPhaseFor(state),
    status = link.status,
    executionState = link.status,
    continuationKind = when (state) {
      SubAgentExecutionState.BACKGROUND_RUNNING ->
        SubAgentContinuationKind.BACKGROUND_RESUME.wireValue

      else -> SubAgentContinuationKind.NONE.wireValue
    },
    resumable = false,
    requiresUserAction = false,
    isHighRisk = false,
    closed = link.closed,
    summary = null,
    startedAtEpochMs = link.createdAtEpochMs,
    updatedAtEpochMs = link.updatedAtEpochMs,
    eventCount = 0,
    hasActiveExecution = !link.closed && (
      state == SubAgentExecutionState.RUNNING ||
        state == SubAgentExecutionState.BACKGROUND_RUNNING
      ),
    mailboxMessageCount = 0,
    mailboxPendingMessageCount = 0,
    mailboxLastDeliveredMessageId = null,
    hasPendingApprovalResume = false,
    pendingApprovalToolName = null,
    pendingApprovalIsHighRisk = false,
    pendingApprovalChildRunId = null,
    pendingApprovalChildTaskId = null,
  )
}

internal fun subAgentActivitySnapshot(
  handle: SubAgentHandleState,
  hasActiveExecution: Boolean,
  closed: Boolean,
): SubAgentActivitySnapshot {
  val mailbox = handle.normalizedMailbox()
  val pendingApprovalResume = handle.pendingApprovalResume
  return SubAgentActivitySnapshot(
    parentRunId = handle.parentRunId,
    parentTaskId = handle.parentTaskId,
    agentId = handle.agentId,
    childSessionId = handle.childSessionId,
    childRunId = handle.childRunId,
    childTaskId = handle.childTaskId,
    label = handle.description,
    subagentType = handle.subagentType,
    contextMode = handle.contextMode,
    contextModeSource = handle.contextModeSource,
    depth = handle.depth,
    phase = subAgentPhaseFor(handle.snapshot.state),
    status = handle.snapshot.state.wireValue,
    executionState = handle.snapshot.state.wireValue,
    continuationKind = handle.snapshot.continuationKind.wireValue,
    resumable = handle.snapshot.resumable,
    requiresUserAction = handle.snapshot.requiresUserAction,
    isHighRisk = handle.snapshot.isHighRisk,
    closed = closed,
    summary = handle.snapshot.headline,
    startedAtEpochMs = handle.createdAtEpochMs,
    updatedAtEpochMs = handle.updatedAtEpochMs,
    eventCount = 0,
    hasActiveExecution = hasActiveExecution,
    mailboxMessageCount = mailbox.messages.size,
    mailboxPendingMessageCount = mailbox.pendingMessages().size,
    mailboxLastDeliveredMessageId = mailbox.lastDeliveredMessageId,
    hasPendingApprovalResume = pendingApprovalResume != null,
    pendingApprovalToolName = pendingApprovalResume?.approvedToolName,
    pendingApprovalIsHighRisk = pendingApprovalResume?.isHighRisk == true,
    pendingApprovalChildRunId = pendingApprovalResume?.childRunId,
    pendingApprovalChildTaskId = pendingApprovalResume?.childTaskId,
    liveContext = handle.childLiveContext.toMap(),
  )
}

internal fun subAgentPhaseFor(
  state: SubAgentExecutionState,
): String = when (state) {
  SubAgentExecutionState.RUNNING,
  SubAgentExecutionState.BACKGROUND_QUEUED,
  -> OpenCraySubAgentPhase.STARTED.name.lowercase(Locale.US)

  SubAgentExecutionState.BACKGROUND_RUNNING ->
    OpenCraySubAgentPhase.RESUMED.name.lowercase(Locale.US)

  SubAgentExecutionState.WAITING_APPROVAL,
  SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
  SubAgentExecutionState.FAILED,
  -> OpenCraySubAgentPhase.FAILED.name.lowercase(Locale.US)

  SubAgentExecutionState.COMPLETED ->
    OpenCraySubAgentPhase.COMPLETED.name.lowercase(Locale.US)

  SubAgentExecutionState.CANCELLED ->
    OpenCraySubAgentPhase.CANCELLED.name.lowercase(Locale.US)
}

internal fun subAgentSnapshotToMap(snapshot: SubAgentActivitySnapshot): Map<String, Any?> = mapOf(
  "parentRunId" to snapshot.parentRunId,
  "parentTaskId" to snapshot.parentTaskId,
  "agentId" to snapshot.agentId,
  "childSessionId" to snapshot.childSessionId,
  "childRunId" to snapshot.childRunId,
  "childTaskId" to snapshot.childTaskId,
  "label" to snapshot.label,
  "subagentType" to snapshot.subagentType,
  "contextMode" to snapshot.contextMode,
  "contextModeSource" to snapshot.contextModeSource,
  "depth" to snapshot.depth,
  "phase" to snapshot.phase,
  "status" to snapshot.status,
  "executionState" to snapshot.executionState,
  "continuationKind" to snapshot.continuationKind,
  "resumable" to snapshot.resumable,
  "requiresUserAction" to snapshot.requiresUserAction,
  "isHighRisk" to snapshot.isHighRisk,
  "closed" to snapshot.closed,
  "summary" to snapshot.summary,
  "startedAtEpochMs" to snapshot.startedAtEpochMs,
  "updatedAtEpochMs" to snapshot.updatedAtEpochMs,
  "eventCount" to snapshot.eventCount,
  "hasActiveExecution" to snapshot.hasActiveExecution,
  "mailboxMessageCount" to snapshot.mailboxMessageCount,
  "mailboxPendingMessageCount" to snapshot.mailboxPendingMessageCount,
  "mailboxLastDeliveredMessageId" to snapshot.mailboxLastDeliveredMessageId,
  "hasPendingApprovalResume" to snapshot.hasPendingApprovalResume,
  "pendingApprovalToolName" to snapshot.pendingApprovalToolName,
  "pendingApprovalIsHighRisk" to snapshot.pendingApprovalIsHighRisk,
  "pendingApprovalChildRunId" to snapshot.pendingApprovalChildRunId,
  "pendingApprovalChildTaskId" to snapshot.pendingApprovalChildTaskId,
  "liveContext" to snapshot.liveContext,
)

internal fun runtimeActivityUpdatedAtEpochMs(
  displayedRuns: List<AgentRunSnapshot>,
  recentEvents: List<OpenCrayAgentRunEvent>,
  subAgentSnapshots: List<SubAgentActivitySnapshot>,
  liveAssistantDrafts: List<LiveAssistantDraftSnapshot>,
  hostCreatedAtEpochMs: Long,
): Long {
  val latestRunEpochMs = displayedRuns.maxOfOrNull(AgentRunSnapshot::updatedAtEpochMs) ?: 0L
  val latestEventEpochMs = recentEvents.maxOfOrNull(OpenCrayAgentRunEvent::emittedAtEpochMs) ?: 0L
  val latestSubAgentEpochMs =
    subAgentSnapshots.maxOfOrNull(SubAgentActivitySnapshot::updatedAtEpochMs) ?: 0L
  val latestDraftEpochMs =
    liveAssistantDrafts.maxOfOrNull(LiveAssistantDraftSnapshot::updatedAtEpochMs) ?: 0L
  return maxOf(
    hostCreatedAtEpochMs,
    latestRunEpochMs,
    latestEventEpochMs,
    latestSubAgentEpochMs,
    latestDraftEpochMs,
  )
}
