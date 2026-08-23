package com.opencray.app.projection

import com.opencray.app.AgentRunSnapshot
import com.opencray.app.AppAgentSessionTaskRuntimeFactory
import com.opencray.app.MidLoopSupplementEntry
import com.opencray.app.PendingUserInputEntry
import com.opencray.app.runtimeEventStableId
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import java.util.Locale

internal data class LiveAssistantDraftSnapshot(
  val runId: String,
  val taskId: String,
  val executionId: String?,
  val pendingMessageId: String,
  val text: String,
  val updatedAtEpochMs: Long,
)

internal data class ProjectedRuntimeChatMessage(
  val anchorMessageId: String?,
  val sortEpochMs: Long,
  val sourceOrder: Int,
  val snapshot: Map<String, Any?>,
) {
  fun effectiveSortEpochMs(): Long =
    (snapshot["createdAtEpochMs"] as? Number)?.toLong()?.takeIf { createdAt -> createdAt > 0L }
      ?: sortEpochMs
}

internal data class OrderedProjectedRuntimeChatMessage(
  val sortEpochMs: Long,
  val sourceOrder: Int,
  val message: ProjectedRuntimeChatMessage,
) {
  fun effectiveSortEpochMs(): Long = message.effectiveSortEpochMs()
}

private val HIDDEN_ASSISTANT_CHAT_STAGES: Set<String> = setOf(
  "draft",
  "llm_retry",
  "responses_recovery",
)

private const val TOOL_GENERATED_SUPPLEMENT_ENTRY_ID_PREFIX: String = "tool-supplement-"

internal fun renderedChatMessages(
  visibleMessages: List<ChatTranscriptMessageEntry>,
  runs: List<AgentRunSnapshot>,
  runtimeEvents: List<OpenCrayAgentRunEvent>,
  pendingUserInputs: List<PendingUserInputEntry>,
  pendingSupplements: List<MidLoopSupplementEntry>,
  transcriptMessageToMap: (ChatTranscriptMessageEntry) -> Map<String, Any?>,
): List<Map<String, Any?>> {
  val projectedMessages = projectedRuntimeMessagesForChat(
    runs = runs,
    runtimeEvents = runtimeEvents,
  )
  val projectedPendingUserMessages = projectedPendingUserMessages(pendingUserInputs)
  val projectedPendingSupplementMessages = projectedPendingSupplementMessages(pendingSupplements)
  if (
    projectedMessages.isEmpty() &&
    projectedPendingUserMessages.isEmpty() &&
    projectedPendingSupplementMessages.isEmpty()
  ) {
    return visibleMessages.map(transcriptMessageToMap)
  }
  val visibleMessagesById = visibleMessages.associateBy(ChatTranscriptMessageEntry::messageId)
  val visibleMessageIds = visibleMessagesById.keys
  val persistedProjectedMessageIds = linkedSetOf<String>()
  val projectedByAnchor = projectedMessages
    .mapNotNull { projection ->
      val projectedMessageId = (projection.snapshot["messageId"] as? String)
        ?.trim()
        ?.takeIf(String::isNotBlank)
      val anchorMessageId = projection.anchorMessageId ?: return@mapNotNull null
      if (anchorMessageId !in visibleMessageIds) {
        return@mapNotNull null
      }
      val persistedVisibleMessage = projectedMessageId?.let(visibleMessagesById::get)
        ?: projectedMessageId
          ?.takeIf { messageId -> messageId.startsWith("runtime-assistant-") }
          ?.let { stableMessageId ->
            visibleMessages.firstOrNull { message ->
              message.messageId.startsWith("$stableMessageId-")
            }
          }
      val effectiveProjection = if (persistedVisibleMessage != null) {
        persistedProjectedMessageIds += persistedVisibleMessage.messageId
        projection.copy(snapshot = transcriptMessageToMap(persistedVisibleMessage))
      } else {
        projection
      }
      anchorMessageId to effectiveProjection
    }
    .groupBy(
      keySelector = Pair<String, ProjectedRuntimeChatMessage>::first,
      valueTransform = Pair<String, ProjectedRuntimeChatMessage>::second,
    )
  val baseVisibleMessages = if (persistedProjectedMessageIds.isEmpty()) {
    visibleMessages
  } else {
    visibleMessages.filterNot { message -> message.messageId in persistedProjectedMessageIds }
  }
  val merged = ArrayList<Map<String, Any?>>(baseVisibleMessages.size + projectedMessages.size)
  baseVisibleMessages.forEach { message ->
    projectedByAnchor[message.messageId]
      ?.sortedWith(
        compareBy<ProjectedRuntimeChatMessage>(ProjectedRuntimeChatMessage::effectiveSortEpochMs)
          .thenBy(ProjectedRuntimeChatMessage::sourceOrder),
      )
      ?.forEach { projection ->
        merged += projection.snapshot
      }
    merged += transcriptMessageToMap(message)
  }
  merged += projectedPendingUserMessages
  merged += projectedPendingSupplementMessages
  return merged
}

internal fun projectedPendingUserMessages(
  pendingUserInputs: List<PendingUserInputEntry>,
): List<Map<String, Any?>> = pendingUserInputs.map { pendingInput ->
  chatMessageSnapshotMap(
    messageId = pendingInput.queueId,
    kind = "outbound",
    text = pendingInput.text,
    createdAtEpochMs = pendingInput.createdAtEpochMs.takeIf { createdAt -> createdAt > 0L },
    isEphemeral = true,
    attachments = pendingInput.attachments.map(::chatAttachmentSnapshotMap),
  )
}

internal fun projectedPendingSupplementMessages(
  pendingSupplements: List<MidLoopSupplementEntry>,
): List<Map<String, Any?>> = pendingSupplements.map { supplement ->
  chatMessageSnapshotMap(
    messageId = supplement.entryId,
    kind = "outbound",
    text = supplement.text,
    createdAtEpochMs = supplement.createdAtEpochMs.takeIf { createdAt -> createdAt > 0L },
    isEphemeral = true,
  )
}

internal fun projectedRuntimeMessagesForChat(
  runs: List<AgentRunSnapshot>,
  runtimeEvents: List<OpenCrayAgentRunEvent>,
): List<ProjectedRuntimeChatMessage> {
  if (runtimeEvents.isEmpty() && runs.none { run -> run.managedProcesses.isNotEmpty() }) {
    return emptyList()
  }
  val pendingMessageIdByRunId = linkedMapOf<String, String>()
  val pendingMessageIdByTaskId = linkedMapOf<String, String>()
  val runsByRunId = linkedMapOf<String, AgentRunSnapshot>()
  val runsByTaskId = linkedMapOf<String, AgentRunSnapshot>()
  runs.forEach { run ->
    val pendingMessageId = run.pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return@forEach
    pendingMessageIdByRunId[run.runId] = pendingMessageId
    pendingMessageIdByTaskId[run.taskId] = pendingMessageId
    runsByRunId[run.runId] = run
    runsByTaskId[run.taskId] = run
  }
  val orderedEvents = runtimeEvents
    .withIndex()
    .sortedWith(
      compareBy<IndexedValue<OpenCrayAgentRunEvent>> { indexed ->
        indexed.value.emittedAtEpochMs
      }.thenBy(IndexedValue<OpenCrayAgentRunEvent>::index),
    )
    .map(IndexedValue<OpenCrayAgentRunEvent>::value)
  val projectedRuntimeEvents = buildList<OrderedProjectedRuntimeChatMessage> {
    for ((index, event) in orderedEvents.withIndex()) {
      val run = runsByRunId[event.runId] ?: runsByTaskId[event.taskId] ?: continue
      if (!eventMatchesRunExecution(run = run, event = event)) {
        continue
      }
      val anchorMessageId = pendingMessageIdByRunId[event.runId]
        ?: pendingMessageIdByTaskId[event.taskId]
        ?: continue
      val text = projectedRuntimeMessageText(
        event = event,
      ) ?: continue
      if (text.isBlank()) {
        continue
      }
      add(
        OrderedProjectedRuntimeChatMessage(
          sortEpochMs = event.emittedAtEpochMs,
          sourceOrder = index,
          message = ProjectedRuntimeChatMessage(
            anchorMessageId = anchorMessageId,
            sortEpochMs = event.emittedAtEpochMs,
            sourceOrder = index,
            snapshot = chatMessageSnapshotMap(
              messageId = runtimeProjectedMessageId(event),
              kind = projectedRuntimeMessageKind(event),
              text = text,
              createdAtEpochMs = event.emittedAtEpochMs.takeIf { emittedAt -> emittedAt > 0L },
              isEphemeral = true,
            ),
          ),
        ),
      )
    }
  }
  var nextSourceOrder = projectedRuntimeEvents.size
  val projectedManagedProcesses = runs.flatMap { run ->
    if (run.isTerminal) {
      return@flatMap emptyList()
    }
    val anchorMessageId = pendingMessageIdByRunId[run.runId]
      ?: pendingMessageIdByTaskId[run.taskId]
      ?: return@flatMap emptyList()
    run.managedProcesses
      .sortedWith(
        compareBy<ManagedProcessSnapshot> { process ->
          process.startedAtEpochMs
        }.thenBy(ManagedProcessSnapshot::updatedAtEpochMs)
          .thenBy(ManagedProcessSnapshot::processId),
      )
      .map { process ->
        val sortEpochMs = process.startedAtEpochMs.takeIf { startedAt -> startedAt > 0L }
          ?: process.updatedAtEpochMs.takeIf { updatedAt -> updatedAt > 0L }
          ?: 0L
        val sourceOrder = nextSourceOrder++
        OrderedProjectedRuntimeChatMessage(
          sortEpochMs = sortEpochMs,
          sourceOrder = sourceOrder,
          message = ProjectedRuntimeChatMessage(
            anchorMessageId = anchorMessageId,
            sortEpochMs = sortEpochMs,
            sourceOrder = sourceOrder,
            snapshot = chatMessageSnapshotMap(
              messageId = runtimeProjectedManagedProcessMessageId(
                run = run,
                process = process,
              ),
              kind = "inbound",
              text = projectedManagedProcessMessageText(process),
              createdAtEpochMs = process.startedAtEpochMs.takeIf { startedAt -> startedAt > 0L }
                ?: process.updatedAtEpochMs.takeIf { updatedAt -> updatedAt > 0L },
              isEphemeral = true,
            ),
          ),
        )
      }
  }
  return (projectedRuntimeEvents + projectedManagedProcesses)
    .sortedWith(
      compareBy<OrderedProjectedRuntimeChatMessage>(OrderedProjectedRuntimeChatMessage::effectiveSortEpochMs)
        .thenBy(OrderedProjectedRuntimeChatMessage::sourceOrder),
    )
    .map(OrderedProjectedRuntimeChatMessage::message)
}

internal fun projectedRuntimeMessageText(
  event: OpenCrayAgentRunEvent,
): String? = when (event) {
  is OpenCrayAssistantPhaseEvent -> if (event.isFinal || hideAssistantPhaseFromChatBubble(event)) {
    null
  } else {
    chatProgressText(event)
  }
  is OpenCraySupplementEvent -> projectedRuntimeSupplementBubbleText(event)
  is OpenCrayApprovalEvent -> null
  is OpenCrayToolCallEvent -> null
  is OpenCrayToolResultEvent -> null
  is OpenCrayCancellationEvent -> null
  else -> null
}

internal fun projectedRuntimeMessageKind(event: OpenCrayAgentRunEvent): String = when (event) {
  is OpenCraySupplementEvent -> "outbound"
  else -> "inbound"
}

internal fun isPersistedDraftAssistantPhase(
  event: OpenCrayAssistantPhaseEvent,
): Boolean = event.stage
  ?.trim()
  ?.equals(AppAgentSessionTaskRuntimeFactory.PERSISTED_DRAFT_ASSISTANT_STAGE, ignoreCase = true) == true

internal fun hideAssistantPhaseFromChatBubble(event: OpenCrayAssistantPhaseEvent): Boolean =
  (
    event.stage
      ?.trim()
      ?.lowercase(Locale.US)
      ?.let(HIDDEN_ASSISTANT_CHAT_STAGES::contains)
    ) == true

internal fun projectedRuntimeSupplementBubbleText(
  event: OpenCraySupplementEvent,
): String? = if (hideSupplementFromChatBubble(event)) {
  null
} else {
  event.text.trim().takeIf(String::isNotBlank)
}

internal fun hideSupplementFromChatBubble(event: OpenCraySupplementEvent): Boolean =
  event.entryId.startsWith(TOOL_GENERATED_SUPPLEMENT_ENTRY_ID_PREFIX)

internal fun chatProgressText(event: OpenCrayAssistantPhaseEvent): String {
  val stage = event.stage?.trim().orEmpty()
  val text = event.text.trim()
  return when {
    stage.isEmpty() -> text
    text.isEmpty() -> stage
    else -> "$stage\n\n$text"
  }
}

internal fun runtimeProjectedManagedProcessMessageId(
  run: AgentRunSnapshot,
  process: ManagedProcessSnapshot,
): String {
  val ownerKey = listOf(run.taskId, run.runId)
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
    ?: run.acceptedAtEpochMs.toString()
  val processId = process.processId.trim()
  if (processId.isNotEmpty()) {
    return "runtime-process-$ownerKey-$processId"
  }
  val fingerprint = listOf(
    process.command.trim(),
    process.args.joinToString(separator = "\u0001"),
    process.workingDirectory?.trim().orEmpty(),
    process.startedAtEpochMs.toString(),
  ).joinToString(separator = "\u0002")
  return "runtime-process-$ownerKey-fp-${Integer.toUnsignedString(fingerprint.hashCode(), 16)}"
}

internal fun projectedManagedProcessMessageText(
  process: ManagedProcessSnapshot,
): String {
  val status = managedProcessStatusLabelForChat(process)
  val command = (listOf(process.command) + process.args)
    .map(String::trim)
    .filter(String::isNotBlank)
    .joinToString(separator = " ")
  val output = managedProcessOutputPreview(process.stdout)
    .trim()
    .takeIf(String::isNotBlank)
  return buildString {
    append("Process ")
    append(process.processId)
    append("\n\n")
    append(status)
    append(": ")
    append(command)
    output?.let { stdoutPreview ->
      append("\n\n")
      append(stdoutPreview)
    }
  }
}

internal fun managedProcessStatusLabelForChat(
  process: ManagedProcessSnapshot,
): String = when (process.status) {
  com.opencray.runtime.process.ManagedProcessStatus.RUNNING -> "running"
  com.opencray.runtime.process.ManagedProcessStatus.SUCCESS -> "finished"
  com.opencray.runtime.process.ManagedProcessStatus.FAILED,
  com.opencray.runtime.process.ManagedProcessStatus.SPAWN_ERROR,
  -> "failed"
  com.opencray.runtime.process.ManagedProcessStatus.CANCELLED -> "cancelled"
  com.opencray.runtime.process.ManagedProcessStatus.TIMEOUT -> "timed out"
  else -> process.status.name.lowercase()
}

internal fun chatToolCallText(
  event: OpenCrayToolCallEvent,
  localeIsChinese: Boolean,
): String {
  val toolName = event.call.toolName.trim().takeIf(String::isNotBlank) ?: "Tool"
  val summary = toolActionSummary(
    toolName = toolName,
    arguments = event.call.arguments,
    localeIsChinese = localeIsChinese,
  )
  val reason = event.call.reason
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { toolReasonText(reason = it, localeIsChinese = localeIsChinese) }
  return joinProjectedChatSections(
    summary,
    reason,
  )
}

internal fun chatToolResultText(
  event: OpenCrayToolResultEvent,
  pairedToolCall: OpenCrayToolCallEvent?,
  localeIsChinese: Boolean,
): String {
  val toolName = event.result.toolName.trim().takeIf(String::isNotBlank)
    ?: event.call.toolName.trim().takeIf(String::isNotBlank)
    ?: "Tool"
  val summary = toolResultActionSummary(
    toolName = toolName,
    event = event,
    pairedToolCall = pairedToolCall,
    localeIsChinese = localeIsChinese,
  )
  val resultSummary = toolResultMetadataSummary(
    toolName = toolName,
    metadata = event.result.metadata,
    localeIsChinese = localeIsChinese,
  )
  val body = toolResultBodyText(event.result, localeIsChinese)
  return joinProjectedChatSections(
    summary,
    resultSummary,
    body,
  )
}

internal fun previousToolCallEvent(
  orderedEvents: List<OpenCrayAgentRunEvent>,
  beforeIndex: Int,
  resultEvent: OpenCrayToolResultEvent,
): OpenCrayToolCallEvent? {
  val normalizedToolName = resultEvent.result.toolName.trim().takeIf(String::isNotBlank)
    ?: resultEvent.call.toolName.trim().takeIf(String::isNotBlank)
    ?: return null
  for (index in beforeIndex - 1 downTo 0) {
    val candidate = orderedEvents[index] as? OpenCrayToolCallEvent ?: continue
    if (candidate.runId != resultEvent.runId && candidate.taskId != resultEvent.taskId) {
      continue
    }
    if (candidate.call.toolName.trim().equals(normalizedToolName, ignoreCase = true)) {
      return candidate
    }
  }
  return null
}

internal fun joinProjectedChatSections(vararg sections: String?): String =
  sections
    .mapNotNull { section -> section?.trim()?.takeIf(String::isNotBlank) }
    .joinToString(separator = "\n\n")

internal fun runtimeProjectedMessageId(event: OpenCrayAgentRunEvent): String = when (event) {
  // Persist, projection, and wire snapshots must derive the same id for the
  // same event whether or not a durable eventId has been stamped yet;
  // runtimeEventStableId reuses the stamped id or the semantic hash the
  // journal/record stores stamp on replayed events.
  is OpenCrayAssistantPhaseEvent -> "runtime-assistant-event-${runtimeEventStableId(event)}"
  is OpenCraySupplementEvent -> "runtime-supplement-${event.entryId}"
  is OpenCrayApprovalEvent -> "runtime-approval-${event.phase.name.lowercase(Locale.US)}-${event.runId}-${event.emittedAtEpochMs}"
  is OpenCrayToolCallEvent -> "runtime-tool-call-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
  is OpenCrayToolResultEvent -> "runtime-tool-result-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
  is OpenCrayCancellationEvent -> "runtime-interrupted-${event.runId}-${event.emittedAtEpochMs}"
  else -> "runtime-event-${event.runId}-${event.emittedAtEpochMs}"
}

internal fun displayedRunsForSnapshot(
  runs: List<AgentRunSnapshot>,
  recentEvents: List<OpenCrayAgentRunEvent>,
  userVisibleRuns: (List<AgentRunSnapshot>) -> List<AgentRunSnapshot>,
  isInternalCheckpointEvent: (OpenCrayAgentRunEvent) -> Boolean,
): List<AgentRunSnapshot> = userVisibleRuns(runs).map { run ->
  displayRunSnapshot(
    run = run,
    recentEvents = recentEvents,
    isInternalCheckpointEvent = isInternalCheckpointEvent,
  )
}

internal fun displayRunSnapshot(
  run: AgentRunSnapshot,
  recentEvents: List<OpenCrayAgentRunEvent>,
  isInternalCheckpointEvent: (OpenCrayAgentRunEvent) -> Boolean,
): AgentRunSnapshot = run.copy(
  lastEvent = displayedLastEvent(
    run = run,
    recentEvents = recentEvents,
    isInternalCheckpointEvent = isInternalCheckpointEvent,
  ),
)

internal fun displayedLastEvent(
  run: AgentRunSnapshot,
  recentEvents: List<OpenCrayAgentRunEvent>,
  isInternalCheckpointEvent: (OpenCrayAgentRunEvent) -> Boolean,
): OpenCrayAgentRunEvent? {
  val runEvents = executionScopedRunEvents(
    run = run,
    recentEvents = recentEvents,
  )
  val latest = runEvents.lastOrNull() ?: return run.lastEvent?.takeIf { event ->
    !isInternalCheckpointEvent(event) &&
      eventMatchesRunExecution(run = run, event = event)
  }
  if (latest is OpenCrayApprovalEvent && latest.phase != OpenCrayApprovalPhase.REQUIRED) {
    val previousMeaningful = runEvents
      .dropLast(1)
      .asReversed()
      .firstOrNull { event ->
        event !is OpenCrayApprovalEvent || event.phase == OpenCrayApprovalPhase.REQUIRED
      }
    if (
      previousMeaningful is OpenCraySubAgentEvent &&
      previousMeaningful.phase == OpenCraySubAgentPhase.RESUMED
    ) {
      return previousMeaningful
    }
  }
  return latest
}

internal fun executionScopedRunEvents(
  run: AgentRunSnapshot,
  recentEvents: List<OpenCrayAgentRunEvent>,
): List<OpenCrayAgentRunEvent> {
  val runEvents = recentEvents.filter { event -> event.runId == run.runId }
  val currentExecutionId = run.executionId?.trim()?.takeIf(String::isNotBlank)
  if (currentExecutionId == null) {
    if (
      run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) != null &&
      run.isActive
    ) {
      val untaggedEvents = runEvents.filter(::isUntaggedExecutionEvent)
      if (untaggedEvents.isNotEmpty()) {
        return untaggedEvents
      }
      return if (runEvents.any { event -> !isUntaggedExecutionEvent(event) }) {
        emptyList()
      } else {
        runEvents
      }
    }
    return runEvents
  }
  val matching = runEvents.filter { event ->
    event.executionId?.trim() == currentExecutionId
  }
  if (matching.isNotEmpty()) {
    return matching
  }
  val hasTaggedEvents = runEvents.any { event ->
    !event.executionId?.trim().isNullOrEmpty() ||
      event.executionOrdinal != null ||
      !event.executionKind?.trim().isNullOrEmpty()
  }
  if (hasTaggedEvents || run.executionOrdinal > 0) {
    return emptyList()
  }
  return runEvents.filter { event ->
    event.executionId?.trim().isNullOrEmpty() &&
      event.executionOrdinal == null &&
      event.executionKind?.trim().isNullOrEmpty()
  }
}

internal fun eventMatchesRunExecution(
  run: AgentRunSnapshot,
  event: OpenCrayAgentRunEvent,
): Boolean {
  if (event.runId != run.runId) {
    return false
  }
  val currentExecutionId = run.executionId?.trim()?.takeIf(String::isNotBlank)
  if (currentExecutionId == null) {
    return if (
      run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) != null &&
      run.isActive
    ) {
      isUntaggedExecutionEvent(event)
    } else {
      true
    }
  }
  return event.executionId?.trim() == currentExecutionId
}

internal fun isUntaggedExecutionEvent(event: OpenCrayAgentRunEvent): Boolean =
  event.executionId?.trim().isNullOrEmpty() &&
    event.executionOrdinal == null &&
    event.executionKind?.trim().isNullOrEmpty()

internal fun retainedRunsForSnapshot(
  runs: List<AgentRunSnapshot>,
  isAwaitingDirectionRun: (AgentRunSnapshot) -> Boolean,
  isInterruptedOnRestoreRun: (AgentRunSnapshot) -> Boolean,
): List<AgentRunSnapshot> = runs.filter { run ->
  !run.isActive &&
    (
      run.isTerminal ||
        isAwaitingDirectionRun(run) ||
        isInterruptedOnRestoreRun(run)
      )
}

internal fun liveAssistantDraftsForSnapshot(
  sessionId: String,
  displayedRuns: List<AgentRunSnapshot>,
  recentEvents: List<OpenCrayAgentRunEvent>,
  sessionDraftsProvider: (String) -> Map<String, LiveAssistantDraftSnapshot>,
): List<LiveAssistantDraftSnapshot> {
  val sessionDrafts = sessionDraftsProvider(sessionId)
  val activeRuns = displayedRuns.filter(AgentRunSnapshot::isActive)
  if (activeRuns.isEmpty()) {
    return emptyList()
  }
  return activeRuns.mapNotNull { run ->
    val pendingMessageId = run.pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return@mapNotNull null
    sessionDrafts[pendingMessageId]
      ?: persistedAssistantDraftForRun(
        run = run,
        pendingMessageId = pendingMessageId,
        recentEvents = recentEvents,
      )
  }
}

internal fun persistedAssistantDraftForRun(
  run: AgentRunSnapshot,
  pendingMessageId: String,
  recentEvents: List<OpenCrayAgentRunEvent>,
): LiveAssistantDraftSnapshot? {
  val runEvents = recentEvents.filter { event ->
    (event.runId == run.runId || event.taskId == run.taskId) &&
      eventMatchesRunExecution(run = run, event = event)
  }
  val latestDraftEvent = runEvents
    .filterIsInstance<OpenCrayAssistantPhaseEvent>()
    .lastOrNull(::isPersistedDraftAssistantPhase)
    ?: return null
  val newerVisibleEventExists = runEvents.any { event ->
    event.emittedAtEpochMs > latestDraftEvent.emittedAtEpochMs &&
      (
        event !is OpenCrayAssistantPhaseEvent ||
          !isPersistedDraftAssistantPhase(event)
        )
  }
  if (newerVisibleEventExists) {
    return null
  }
  val text = latestDraftEvent.text.trim().takeIf(String::isNotBlank) ?: return null
  return LiveAssistantDraftSnapshot(
    runId = run.runId,
    taskId = run.taskId,
    executionId = latestDraftEvent.executionId ?: run.executionId,
    pendingMessageId = pendingMessageId,
    text = text,
    updatedAtEpochMs = latestDraftEvent.emittedAtEpochMs,
  )
}

internal fun chatMessageSnapshotMap(
  messageId: String,
  kind: String,
  text: String,
  meta: String = "",
  createdAtEpochMs: Long? = null,
  isEphemeral: Boolean = false,
  attachments: List<Map<String, Any?>> = emptyList(),
): Map<String, Any?> = buildMap {
  put("messageId", messageId)
  put("kind", kind)
  put("text", text)
  put("meta", meta)
  createdAtEpochMs?.let { timestamp ->
    put("createdAtEpochMs", timestamp)
  }
  put("isEphemeral", isEphemeral)
  if (attachments.isNotEmpty()) {
    put("attachments", attachments)
  }
}

internal fun chatAttachmentSnapshotMap(
  attachment: ChatAttachmentEntry,
): Map<String, Any?> = buildMap {
  put("attachmentId", attachment.attachmentId)
  put(
    "kind",
    when (attachment.kind) {
      com.opencray.persistence.model.ChatAttachmentKind.IMAGE -> "image"
      com.opencray.persistence.model.ChatAttachmentKind.VOICE,
      com.opencray.persistence.model.ChatAttachmentKind.AUDIO -> "voice"
      com.opencray.persistence.model.ChatAttachmentKind.FILE -> "file"
    },
  )
  put("displayName", attachment.displayName)
  put("localPath", attachment.localPath)
  attachment.mimeType?.let { mimeType -> put("mimeType", mimeType) }
  attachment.sizeBytes?.let { sizeBytes -> put("sizeBytes", sizeBytes) }
  attachment.widthPx?.let { widthPx -> put("widthPx", widthPx) }
  attachment.heightPx?.let { heightPx -> put("heightPx", heightPx) }
  attachment.durationMs?.let { durationMs -> put("durationMs", durationMs) }
  if (attachment.waveformBars.isNotEmpty()) {
    put("waveformBars", attachment.waveformBars)
  }
  attachment.transcriptText?.let { transcriptText -> put("transcriptText", transcriptText) }
  attachment.contentSha256?.let { contentSha256 -> put("contentSha256", contentSha256) }
}

internal fun liveAssistantDraftToMap(
  draft: LiveAssistantDraftSnapshot,
): Map<String, Any?> = mapOf(
  "runId" to draft.runId,
  "taskId" to draft.taskId,
  "executionId" to draft.executionId,
  "pendingMessageId" to draft.pendingMessageId,
  "text" to draft.text,
  "updatedAtEpochMs" to draft.updatedAtEpochMs,
)
