package com.opencray.app

import com.opencray.app.projection.replayBoolean
import com.opencray.app.projection.replayInt
import com.opencray.app.projection.replayObject
import com.opencray.app.projection.replayString
import com.opencray.app.projection.replayStringMap
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class ReplayedRuntimeEvent(
  val sourceIndex: Int,
  val event: OpenCrayAgentRunEvent,
)

internal fun OpenCrayHostRuntime.mergedRuntimeEventsLocked(
  sessionId: String,
  runs: List<AgentRunSnapshot>,
): List<OpenCrayAgentRunEvent> {
  val hasDurableJournal = runCatching {
    runEventJournalStoreForSession(sessionId).hasEntries()
  }.getOrDefault(false)
  val liveEvents = chatRuntimeEventState.eventsForSession(sessionId)
  val replayedEvents = replayedRuntimeEventsLocked(
    sessionId = sessionId,
    runs = runs,
    liveEvents = liveEvents,
  )
  val merged = ArrayList<OpenCrayAgentRunEvent>(replayedEvents.size + liveEvents.size)
  val seen = linkedSetOf<String>()
  (replayedEvents + liveEvents).forEach { event ->
    if (seen.add(runtimeEventDedupKey(event))) {
      merged += event
    }
  }
  supplementalApprovalEventsLocked(
    sessionId = sessionId,
    runs = runs,
    existingEvents = merged,
  ).forEach { event ->
    if (seen.add(runtimeEventDedupKey(event))) {
      merged += event
    }
  }
  return merged
    .filterNot(::isDebugOnlyRuntimeEvent)
    .let { filtered ->
      if (hasDurableJournal) {
        filtered
      } else {
        filtered.takeLast(OpenCrayHostRuntime.MAX_RUNTIME_EVENT_HISTORY)
      }
    }
}

internal fun OpenCrayHostRuntime.supplementalApprovalEventsLocked(
  sessionId: String,
  runs: List<AgentRunSnapshot>,
  existingEvents: List<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val approvals = pendingApprovalsForSession(
    sessionId = sessionId,
    pruneCheckpointState = false,
  )
  if (approvals.isEmpty()) {
    return emptyList()
  }
  val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
  return approvals.mapNotNull { approval ->
    val alreadyPresent = existingEvents.any { event ->
      event is OpenCrayApprovalEvent &&
        event.phase == OpenCrayApprovalPhase.REQUIRED &&
        (event.taskId == approval.taskId || event.runId == approval.runId)
    }
    if (alreadyPresent) {
      return@mapNotNull null
    }
    val run = runsByTaskId[approval.taskId]
    approvalRequiredRuntimeEvent(
      approval = approval,
      emittedAtEpochMs = run?.updatedAtEpochMs ?: System.currentTimeMillis(),
    )
  }
}

internal fun OpenCrayHostRuntime.replayedRuntimeEventsLocked(
  sessionId: String,
  runs: List<AgentRunSnapshot>,
  liveEvents: List<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val journalEvents = runCatching {
    runEventJournalStoreForSession(sessionId).listRuntimeEvents()
  }.getOrDefault(emptyList())
  val transcriptMessages = runCatching {
    transcriptMessagesProvider(sessionId)
  }.getOrDefault(emptyList())
  if (transcriptMessages.isEmpty()) {
    return journalEvents
  }
  val replayedEvents = transcriptMessages.mapIndexedNotNull { index, message ->
    parseReplayedRuntimeEvent(
      message = message,
      sourceIndex = index,
    )
  }
  if (replayedEvents.isEmpty()) {
    return journalEvents
  }
  val replayBackfill = assignReplayEmissionTimes(
    replayedEvents = replayedEvents,
    runs = runs,
    liveEvents = journalEvents + liveEvents,
  )
  if (journalEvents.isEmpty()) {
    return replayBackfill
  }
  val merged = ArrayList<OpenCrayAgentRunEvent>(replayBackfill.size + journalEvents.size)
  val seen = linkedSetOf<String>()
  (replayBackfill + journalEvents).forEach { event ->
    if (seen.add(runtimeEventDedupKey(event))) {
      merged += event
    }
  }
  return merged
}

internal fun OpenCrayHostRuntime.parseReplayedRuntimeEvent(
  message: RuntimeConversationMessage,
  sourceIndex: Int,
): ReplayedRuntimeEvent? {
  val content = message.content.trim()
  val replayedPayload = message.replayedRuntimePayloadOrNull()
  val event = replayedPayload?.let { replay ->
    when (replay.kind) {
      ReplayedRuntimeEventKind.TOOL_CALL -> parseReplayedToolCallEvent(replay.payload)
      ReplayedRuntimeEventKind.TOOL_RESULT -> parseReplayedToolResultEvent(replay.payload)
      ReplayedRuntimeEventKind.ASSISTANT_PHASE -> parseReplayedAssistantPhaseEvent(replay.payload)
      ReplayedRuntimeEventKind.SUPPLEMENT -> parseReplayedSupplementEvent(replay.payload)
      ReplayedRuntimeEventKind.SUBAGENT -> parseReplayedSubAgentEvent(replay.payload)
    }
  } ?: when {
    content.startsWith("approval_approved") -> parseReplayedApprovalEvent(
      content = content,
      phase = OpenCrayApprovalPhase.APPROVED,
    )

    content.startsWith("approval_rejected") -> parseReplayedApprovalEvent(
      content = content,
      phase = OpenCrayApprovalPhase.REJECTED,
    )

    content.startsWith("run_interrupted") -> parseReplayedCancellationEvent(
      content = content,
    )

    else -> null
  } ?: return null
  return ReplayedRuntimeEvent(
    sourceIndex = sourceIndex,
    event = event,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedToolCallEvent(payload: String): OpenCrayToolCallEvent? {
  val decoded = decodeReplayPayload(payload) ?: return null
  val identifiers = replayIdentifiers(decoded) ?: return null
  val toolName = decoded.replayString("tool_name") ?: return null
  return OpenCrayToolCallEvent(
    runId = identifiers.first,
    taskId = identifiers.second,
    executionId = decoded.replayString("execution_id"),
    executionOrdinal = decoded.replayInt("execution_ordinal"),
    executionKind = decoded.replayString("execution_kind"),
    turn = decoded.replayInt("turn") ?: 0,
    call = AgentToolCall(
      id = decoded.replayString("tool_call_id"),
      toolName = toolName,
      arguments = decoded.replayObject("arguments") ?: JsonObject(emptyMap()),
      reason = decoded.replayString("reason"),
    ),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedToolResultEvent(payload: String): OpenCrayToolResultEvent? {
  val decoded = decodeReplayPayload(payload) ?: return null
  val identifiers = replayIdentifiers(decoded) ?: return null
  val toolName = decoded.replayString("tool_name") ?: return null
  val status = decoded.replayString("status")
    ?.let(::parseReplayToolResultStatus)
    ?: AgentToolResultStatus.SUCCESS
  return OpenCrayToolResultEvent(
    runId = identifiers.first,
    taskId = identifiers.second,
    executionId = decoded.replayString("execution_id"),
    executionOrdinal = decoded.replayInt("execution_ordinal"),
    executionKind = decoded.replayString("execution_kind"),
    turn = decoded.replayInt("turn") ?: 0,
    call = AgentToolCall(
      id = decoded.replayString("tool_call_id"),
      toolName = toolName,
    ),
    result = AgentToolResult(
      toolName = toolName,
      status = status,
      content = decoded.replayString("content")
        ?.takeIf(String::isNotBlank)
        ?: "Tool finished.",
      exitCode = decoded.replayInt("exit_code"),
      stdout = decoded.replayString("stdout").orEmpty(),
      stderr = decoded.replayString("stderr").orEmpty(),
      errorCode = decoded.replayString("error_code"),
      errorMessage = decoded.replayString("error_message"),
      metadata = decoded.replayStringMap("metadata"),
    ),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedAssistantPhaseEvent(payload: String): OpenCrayAssistantEvent? {
  val decoded = decodeReplayPayload(payload) ?: return null
  val identifiers = replayIdentifiers(decoded) ?: return null
  val isFinal = decoded.replayString("phase")
    ?.trim()
    ?.lowercase(Locale.US) == "final_answer"
  return OpenCrayAssistantEvent(
    runId = identifiers.first,
    taskId = identifiers.second,
    executionId = decoded.replayString("execution_id"),
    executionOrdinal = decoded.replayInt("execution_ordinal"),
    executionKind = decoded.replayString("execution_kind"),
    turn = decoded.replayInt("turn") ?: 0,
    text = decoded.replayString("text") ?: return null,
    responseFormat = decoded.replayString("response_format"),
    isFinal = isFinal,
    stage = decoded.replayString("stage"),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedSupplementEvent(payload: String): OpenCraySupplementEvent? {
  val decoded = decodeReplayPayload(payload) ?: return null
  val identifiers = replayIdentifiers(decoded) ?: return null
  return OpenCraySupplementEvent(
    runId = identifiers.first,
    taskId = identifiers.second,
    executionId = decoded.replayString("execution_id"),
    executionOrdinal = decoded.replayInt("execution_ordinal"),
    executionKind = decoded.replayString("execution_kind"),
    turn = decoded.replayInt("turn") ?: 0,
    entryId = decoded.replayString("entry_id") ?: return null,
    text = decoded.replayString("text") ?: return null,
    checkpoint = decoded.replayString("checkpoint") ?: "turn_start",
    metadata = decoded.replayStringMap("metadata"),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedSubAgentEvent(payload: String): OpenCraySubAgentEvent? {
  val decoded = decodeReplayPayload(payload) ?: return null
  val identifiers = replayIdentifiers(decoded) ?: return null
  val phase = decoded.replayString("phase")
    ?.let { rawValue ->
      OpenCraySubAgentPhase.entries.firstOrNull { phase ->
        phase.name.equals(rawValue, ignoreCase = true)
      }
    }
    ?: OpenCraySubAgentPhase.STARTED
  return OpenCraySubAgentEvent(
    runId = identifiers.first,
    taskId = identifiers.second,
    executionId = decoded.replayString("execution_id"),
    executionOrdinal = decoded.replayInt("execution_ordinal"),
    executionKind = decoded.replayString("execution_kind"),
    agentId = decoded.replayString("agent_id"),
    phase = phase,
    childRunId = decoded.replayString("child_run_id") ?: identifiers.first,
    childTaskId = decoded.replayString("child_task_id") ?: identifiers.second,
    label = decoded.replayString("label") ?: "Task",
    subagentType = decoded.replayString("subagent_type") ?: "general-purpose",
    contextMode = decoded.replayString("context_mode") ?: "delegated",
    depth = decoded.replayInt("depth") ?: 1,
    summary = decoded.replayString("summary"),
    executionState = SubAgentExecutionState.fromWireValue(
      decoded.replayString("execution_state"),
    ) ?: SubAgentExecutionState.RUNNING,
    continuationKind = SubAgentContinuationKind.fromWireValue(
      decoded.replayString("continuation_kind"),
    ) ?: SubAgentContinuationKind.NONE,
    resumable = decoded.replayBoolean("resumable") ?: false,
    requiresUserAction = decoded.replayBoolean("requires_user_action") ?: false,
    isHighRisk = decoded.replayBoolean("is_high_risk") ?: false,
    closed = decoded.replayBoolean("closed") ?: false,
    turn = decoded.replayInt("turn"),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedApprovalEvent(
  content: String,
  phase: OpenCrayApprovalPhase,
): OpenCrayApprovalEvent? {
  val fields = replayTokenFields(content)
  val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
    ?: return null
  val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
    ?: runId
  val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
  val isHighRisk = fields["risk"]?.trim()?.equals("high_risk", ignoreCase = true) == true
  return OpenCrayApprovalEvent(
    runId = runId,
    taskId = taskId,
    executionId = replayExecutionId(fields),
    executionOrdinal = replayExecutionOrdinal(fields),
    executionKind = replayExecutionKind(fields),
    phase = phase,
    toolName = toolName,
    text = when (phase) {
      OpenCrayApprovalPhase.REQUIRED -> strings.chatSummaryApprovalRequired
      OpenCrayApprovalPhase.APPROVED -> strings.chatApprovalApproved
      OpenCrayApprovalPhase.REJECTED -> strings.chatApprovalRejected
    },
    isHighRisk = isHighRisk,
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.parseReplayedCancellationEvent(content: String): OpenCrayCancellationEvent? {
  val fields = replayTokenFields(content)
  val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
    ?: return null
  val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
    ?: runId
  val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
  val outcome = fields["outcome"]?.trim()?.takeIf(String::isNotBlank)
  return OpenCrayCancellationEvent(
    runId = runId,
    taskId = taskId,
    executionId = replayExecutionId(fields),
    executionOrdinal = replayExecutionOrdinal(fields),
    executionKind = replayExecutionKind(fields),
    toolName = toolName,
    outcome = outcome,
    text = cancellationTimelineText(
      toolName = toolName,
      localeIsChinese = isChineseHostLocale(),
    ),
    emittedAtEpochMs = 0L,
  )
}

internal fun OpenCrayHostRuntime.decodeReplayPayload(payload: String): JsonObject? =
  runCatching {
    OpenCrayHostRuntime.replayJson.parseToJsonElement(payload).jsonObject
  }.getOrNull()

internal fun replayTokenFields(content: String): Map<String, String> =
  content
    .trim()
    .split(' ')
    .drop(1)
    .mapNotNull { token ->
      val separatorIndex = token.indexOf('=')
      if (separatorIndex <= 0 || separatorIndex >= token.lastIndex) {
        return@mapNotNull null
      }
      val key = token.substring(0, separatorIndex).trim()
      val value = token.substring(separatorIndex + 1).trim()
      if (key.isEmpty() || value.isEmpty()) {
        null
      } else {
        key to value
      }
    }
    .toMap(linkedMapOf())

internal fun replayIdentifiers(payload: JsonObject): Pair<String, String>? {
  val runId = payload.replayString("run_id")
    ?: payload.replayString("task_id")
    ?: return null
  val taskId = payload.replayString("task_id") ?: runId
  return runId to taskId
}

internal fun replayExecutionId(fields: Map<String, String>): String? =
  fields["execution_id"]?.trim()?.takeIf(String::isNotBlank)

internal fun replayExecutionOrdinal(fields: Map<String, String>): Int? =
  fields["execution_ordinal"]?.trim()?.toIntOrNull()

internal fun replayExecutionKind(fields: Map<String, String>): String? =
  fields["execution_kind"]?.trim()?.takeIf(String::isNotBlank)

internal fun parseReplayToolResultStatus(raw: String): AgentToolResultStatus? =
  AgentToolResultStatus.entries.firstOrNull { status ->
    status.name.equals(raw, ignoreCase = true)
  }

internal fun OpenCrayHostRuntime.assignReplayEmissionTimes(
  replayedEvents: List<ReplayedRuntimeEvent>,
  runs: List<AgentRunSnapshot>,
  liveEvents: List<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val replayCountByRun = replayedEvents.groupingBy { replay ->
    replayRunGroupKey(
      event = replay.event,
      sourceIndex = replay.sourceIndex,
    )
  }.eachCount()
  val emittedCountByRun = linkedMapOf<String, Int>()
  val runsByRunId = runs.associateBy(AgentRunSnapshot::runId)
  val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
  return replayedEvents.map { replay ->
    val groupKey = replayRunGroupKey(
      event = replay.event,
      sourceIndex = replay.sourceIndex,
    )
    val emittedCount = emittedCountByRun[groupKey] ?: 0
    emittedCountByRun[groupKey] = emittedCount + 1
    val liveEventsForRun = liveEvents.filter { liveEvent ->
      liveEvent.runId == replay.event.runId ||
        (replay.event.runId.isBlank() && liveEvent.taskId == replay.event.taskId)
    }
    val lifecycleStartAt = liveEventsForRun
      .mapNotNull { liveEvent ->
        (liveEvent as? OpenCrayLifecycleEvent)
          ?.takeIf { event -> event.phase == OpenCrayRunLifecyclePhase.START }
          ?.emittedAtEpochMs
      }
      .minOrNull()
    val firstLiveAt = liveEventsForRun.minOfOrNull(OpenCrayAgentRunEvent::emittedAtEpochMs)
    val runSnapshot = runsByRunId[replay.event.runId] ?: runsByTaskId[replay.event.taskId]
    val replayBaseTime = when {
      lifecycleStartAt != null -> lifecycleStartAt + 1L
      firstLiveAt != null -> (firstLiveAt - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
      runSnapshot != null -> (runSnapshot.updatedAtEpochMs - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
      else -> (replay.sourceIndex + 1).toLong()
    }
    replay.event.withEmittedAtEpochMs(replayBaseTime + emittedCount.toLong())
  }
}

internal fun replayRunGroupKey(
  event: OpenCrayAgentRunEvent,
  sourceIndex: Int,
): String = event.runId
  .takeIf(String::isNotBlank)
  ?: event.taskId
    .takeIf(String::isNotBlank)
    ?: "replay-$sourceIndex"

private fun OpenCrayAgentRunEvent.withEmittedAtEpochMs(emittedAtEpochMs: Long): OpenCrayAgentRunEvent =
  when (this) {
    is OpenCrayLifecycleEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayAssistantEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCraySupplementEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayApprovalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCraySubAgentEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolCallEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolResultEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryWriteEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryRetrievalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayCancellationEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
  }
