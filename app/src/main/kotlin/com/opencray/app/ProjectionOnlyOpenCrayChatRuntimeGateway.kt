package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.METADATA_PENDING_EXECUTION_KIND
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.memory.MemoryOperator
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryOperatorRequest
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentSessionLink
import java.nio.file.Path
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import org.opencray.app.R

internal data class ProjectionOnlyChatStrings(
  val localeTag: String,
  val screenTitle: String,
  val modeLabel: String,
  val sessionButtonLabel: String,
  val recentSessionsEyebrow: String,
  val recentSessionsTitle: String,
  val newSessionLabel: String,
  val defaultSessionTitle: String,
  val messagesBadge: (Int) -> String,
  val summaryReplyInProgress: String,
  val summaryStartNewSession: String,
  val summaryRestored: String,
  val summaryApprovalRequired: String,
  val approvalRequiredTitle: String,
  val highRiskApprovalRequiredTitle: String,
  val highRiskApprovalRequiredBody: String,
  val approvalApproveLabel: String,
  val approvalApproveForSessionLabel: String,
  val approvalRejectLabel: String,
  val summaryAwaitingDirection: String = "Waiting for your next instruction.",
  val composerPlaceholder: String,
  val composerRejectedPlaceholder: String,
  val agentThinking: String = "Thinking",
  val agentCancelled: String = "Interrupted",
  val agentMissingLlm: String = "Missing LLM",
  val agentEmptyAnswer: String = "The model returned an empty answer.",
  val agentInternalPayloadHidden: String =
    "The agent returned internal tool payload that was hidden.",
  val agentFailed: (String) -> String = { detail -> "Failed: $detail" },
)

internal data class ProjectionOnlyChatRuntimeDiagnosticsSource(
  val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val connectionStateProvider: () -> RuntimeServiceConnectionState,
  val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  val runtimeControllerLifecycleProvider: () -> RuntimeControllerLifecycleDescriptor? = { null },
  val runtimeOwnerLifecycleProvider: () -> HostRuntimeLifecycleDescriptor? = { null },
  val runtimeOwnerWorkSummaryProvider: () -> RuntimeOwnerWorkSummary? = { null },
  val serviceLifecycleProvider: () -> RuntimeServiceLifecycleDescriptor? = { null },
  val serviceWorkStateProvider: () -> RuntimeServiceWorkState? = { null },
  val serviceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  val ownerLeaseProvider: () -> RuntimeServiceOwnerLease? = { null },
  val interruptedRunRepairProvider: () -> RuntimeServiceInterruptedRunRepairProjection? = { null },
)

internal class ProjectionOnlyOpenCrayChatRuntimeGateway(
  private val chatSessionStore: ChatSessionLocalStore,
  private val queueSnapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStoreFactory: AgentRunRecordStoreFactory,
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  private val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  private val processRegistryFactory: AgentProcessRegistryFactory,
  private val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  private val subAgentHandleStoreFactory: SubAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory(),
  private val subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
    inMemorySubAgentSessionLinkStoreFactory(),
  private val strings: ProjectionOnlyChatStrings,
  private val stringsProvider: (() -> ProjectionOnlyChatStrings)? = null,
  private val connectionStateProvider: () -> RuntimeServiceConnectionState,
  private val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  private val personalizationLocalStore: PersonalizationLocalStore? = null,
  private val workspaceRootProvider: (() -> Path?)? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  private val runtimeControllerLifecycleProvider: () -> RuntimeControllerLifecycleDescriptor? =
    { null },
  private val runtimeOwnerLifecycleProvider: () -> HostRuntimeLifecycleDescriptor? = { null },
  private val runtimeOwnerWorkSummaryProvider: () -> RuntimeOwnerWorkSummary? = { null },
  private val serviceLifecycleProvider: () -> RuntimeServiceLifecycleDescriptor? = { null },
  private val serviceWorkStateProvider: () -> RuntimeServiceWorkState? = { null },
  private val serviceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  private val ownerLeaseProvider: () -> RuntimeServiceOwnerLease? = { null },
  private val interruptedRunRepairProvider: () -> RuntimeServiceInterruptedRunRepairProjection? = {
    null
  },
  private val sessionUnreadCountProvider: ((String, String) -> Int)? = null,
  private val clock: () -> Long = System::currentTimeMillis,
  private val pollIntervalMs: Long = DEFAULT_PROJECTION_POLL_INTERVAL_MS,
) : OpenCrayChatRuntimeGateway {
  private val runtimeEventStreamLock = Any()
  private val runtimeEventStreamInstanceId: String = lifecycleId(prefix = "projection-runtime-stream")
  private val runtimeEventSequencesBySession = linkedMapOf<String, Long>()
  private val resolvedStrings: ProjectionOnlyChatStrings
    get() = stringsProvider?.invoke() ?: strings

  private val debugProjector = ProjectionOnlyChatDebugProjector(
    personalizationLocalStore = personalizationLocalStore,
    workspaceRootProvider = workspaceRootProvider,
    workspaceSoulProfileStore = workspaceSoulProfileStore,
    clock = clock,
  )

  override fun loadChatSnapshot(): Map<String, Any?> =
    chatPayloadWithoutEmbeddedRuntimeActivity(loadProjectionChatSnapshot())

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPolling(
      payloadProvider = ::loadChatSnapshot,
      listener = listener,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> = loadProjectionRuntimeSnapshot()

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    findProjectionRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = waitForProjectionRun(runId = runId, timeoutMs = timeoutMs)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPolling(
      payloadProvider = ::loadChatRuntimeSnapshot,
      listener = listener,
    )

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeLiveAssistantDraftsWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      runtimePayloadProvider = ::loadChatRuntimeSnapshot,
      listener = { payload -> listener(assignRuntimeRealtimeEnvelope(payload)) },
      pollIntervalMs = pollIntervalMs,
    )

  override fun refreshSandboxSessionInfo() =
    throw unavailable("refreshSandboxSessionInfo")

  private fun activeSessionId(): String = chatSessionStore.loadState().activeSession.sessionId

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    debugProjector.loadMemoryDebugSnapshot(activeSessionId())

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> {
    val sessions = chatSessionStore.loadState().sessions
    val activeSessionId = activeSessionId()
    val allRuns = sessions.flatMap { session -> loadRunSnapshots(session.sessionId) }
    val runtimeEventsBySession = sessions.associate { session ->
      session.sessionId to sessionJournalRuntimeEvents(session.sessionId)
    }
    return debugProjector.loadMemoryDebugLinksSnapshot(
      activeSessionId = sessions.firstOrNull { session ->
        session.sessionId == activeSessionId
      }?.sessionId ?: activeSessionId,
      allRuns = allRuns,
      runtimeEventsBySession = runtimeEventsBySession,
    )
  }

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    debugProjector.loadSoulDebugSnapshot(activeSessionId())

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = debugProjector.searchMemoryDebug(
    sessionId = activeSessionId(),
    query = query,
    maxResults = maxResults,
    minScore = minScore,
  )

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = debugProjector.getMemoryDebugSlice(
    sessionId = activeSessionId(),
    path = path,
    fromLine = fromLine,
    lines = lines,
  )

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> {
    val store = personalizationLocalStore
      ?: error("Memory debug actions require a personalization memory store.")
    val sessionId = activeSessionId()
    val action = MemoryOperatorAction.fromWireValue(actionId)
      ?: throw IllegalArgumentException("Unsupported memory debug action '$actionId'.")
    val result = MemoryOperator(
      store = store.asMemoryStore(),
    ).apply(
      MemoryOperatorRequest(
        recordId = recordId,
        action = action,
        actorSessionId = sessionId,
      ),
    )
    if (result.applied) {
      val occurredAtEpochMs = System.currentTimeMillis()
      store.appendMemoryDebugActionAudit(
        MemoryDebugActionAuditEntry(
          entryId = "memory-debug-action-$occurredAtEpochMs-${UUID.randomUUID().toString().take(8)}",
          recordId = recordId,
          action = action.wireValue,
          sessionId = sessionId,
          runId = "$MEMORY_DEBUG_RUN_ID_PREFIX-${UUID.randomUUID().toString().take(8)}",
          taskId = "$MEMORY_DEBUG_TASK_ID_PREFIX-${action.wireValue}-${UUID.randomUUID().toString().take(8)}",
          occurredAtEpochMs = occurredAtEpochMs,
        ),
      )
    }
    return mapOf(
      "recordId" to recordId,
      "action" to action.wireValue,
      "applied" to result.applied,
    )
  }

  override fun createChatSession() = throw unavailable("createChatSession")

  override fun copyChatSession(sessionId: String) = throw unavailable("copyChatSession")

  override fun deleteChatSession(sessionId: String) = throw unavailable("deleteChatSession")

  override fun selectChatSession(sessionId: String) = throw unavailable("selectChatSession")

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) = throw unavailable("branchChatSessionFromMessage")

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) = throw unavailable("deleteChatMessage")

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) = throw unavailable("recallChatMessage")

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? = throw unavailable("submitChatMessage")

  override fun approveChatApproval(taskIdOrRunId: String) =
    throw unavailable("approveChatApproval")

  override fun approveChatApprovalForSession(taskIdOrRunId: String) =
    throw unavailable("approveChatApprovalForSession")

  override fun rejectChatApproval(taskIdOrRunId: String) =
    throw unavailable("rejectChatApproval")

  override fun interruptChatRun(taskIdOrRunId: String) =
    throw unavailable("interruptChatRun")

  override fun retryChatRun(taskIdOrRunId: String) =
    throw unavailable("retryChatRun")

  private val recoveryPlanner = RunRecoveryPlanner()

  private fun loadProjectionChatSnapshot(): Map<String, Any?> {
    val state = chatSessionStore.loadState()
    val activeSession = state.activeSession
    val runtimeProjection = runtimeProjectionForSession(activeSession.sessionId)
    val pendingApprovals = projectedPendingApprovals(
      sessionId = activeSession.sessionId,
      runs = runtimeProjection.runs,
    )
    val visibleMessages = activeSession.messages.filter { message ->
      message.role != ChatTranscriptRole.SYSTEM
    }
    val renderedMessages = renderedProjectionMessages(
      sessionId = activeSession.sessionId,
      visibleMessages = visibleMessages,
      runs = runtimeProjection.runs,
      runtimeEvents = runtimeProjection.recentEvents,
    )
    val latestDrawerEpochMs =
      state.sessions.maxOfOrNull(ChatSessionLocalStore.SessionSummary::updatedAtEpochMs) ?: 0L
    val latestRenderedMessageEpochMs = renderedMessages.maxOfOrNull { message ->
      (message["createdAtEpochMs"] as? Number)?.toLong() ?: 0L
    } ?: 0L
    val chatSnapshotUpdatedAtEpochMs = maxOf(
      activeSession.updatedAtEpochMs,
      runtimeProjection.updatedAtEpochMs,
      latestDrawerEpochMs,
      latestRenderedMessageEpochMs,
    )
    val messageCount = renderedMessages.size
    return mapOf(
      "screenTitle" to resolvedStrings.screenTitle,
      "modeLabel" to resolvedStrings.modeLabel,
      "sessionButtonLabel" to resolvedStrings.sessionButtonLabel,
      "composerPlaceholder" to composerPlaceholderFor(
        runs = runtimeProjection.runs,
        hasPendingApprovals = pendingApprovals.isNotEmpty(),
      ),
      "summary" to mapOf(
        "title" to displaySessionTitle(activeSession.title),
        "badge" to resolvedStrings.messagesBadge(messageCount),
        "body" to summaryBody(
          messageCount = messageCount,
          runs = runtimeProjection.runs,
          hasPendingApprovals = pendingApprovals.isNotEmpty(),
        ),
      ),
      "messages" to (
        renderedMessages +
          chatSessionStore.loadPendingUserInputs(activeSession.sessionId).map(::pendingUserInputToMap) +
          supplementStoreFactory.forChatSession(activeSession.sessionId).snapshot().map(::pendingSupplementToMap)
        ),
      "drawer" to mapOf(
        "eyebrow" to resolvedStrings.recentSessionsEyebrow,
        "title" to resolvedStrings.recentSessionsTitle,
        "ctaLabel" to resolvedStrings.newSessionLabel,
        "sessions" to state.sessions.map { session ->
          val unreadCount = sessionUnreadCountProvider?.invoke(
            session.sessionId,
            activeSession.sessionId,
          ) ?: 0
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to session.lastMessagePreview,
            "meta" to resolvedStrings.messagesBadge(session.messageCount),
            "isSelected" to (session.sessionId == activeSession.sessionId),
            "lastMessageAtEpochMs" to session.lastMessageAtEpochMs,
            "unreadCount" to unreadCount,
          )
        },
      ),
      "isInputEnabled" to true,
      "updatedAtEpochMs" to chatSnapshotUpdatedAtEpochMs,
      "todos" to chatSessionStore.loadTodos(activeSession.sessionId).map { todo ->
        mapOf(
          "content" to todo.content,
          "status" to todo.status.name.lowercase(),
          "activeForm" to todo.activeForm,
        )
      },
      "pendingApprovals" to pendingApprovals,
      "runtimeActivity" to runtimeProjection.snapshot,
    )
  }

  private fun chatPayloadWithoutEmbeddedRuntimeActivity(
    payload: Map<String, Any?>,
  ): Map<String, Any?> =
    if (payload["runtimeActivity"] == null) {
      payload
    } else {
      payload.toMutableMap().apply {
        this["runtimeActivity"] = null
      }
    }

  private fun renderedProjectionMessages(
    sessionId: String,
    visibleMessages: List<ChatTranscriptMessageEntry>,
    runs: List<AgentRunSnapshot>,
    runtimeEvents: List<OpenCrayAgentRunEvent>,
  ): List<Map<String, Any?>> {
    if (visibleMessages.isEmpty()) {
      return emptyList()
    }
    val projectedMessages = projectedRuntimeMessagesForChat(
      runs = runs,
      runtimeEvents = runtimeEvents,
    )
    val projectedByMessageId = projectedSettledAssistantTextByMessageId(
      sessionId = sessionId,
      runs = runs,
    )
    if (projectedByMessageId.isEmpty() && projectedMessages.isEmpty()) {
      return visibleMessages.map(::chatMessageToMap)
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
          projection.copy(snapshot = chatMessageToMap(persistedVisibleMessage))
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
      val replacementText = projectedByMessageId[message.messageId]
      merged += if (!shouldReplaceThinkingPlaceholder(message, replacementText)) {
        chatMessageToMap(message)
      } else {
        chatMessageToMap(message, textOverride = replacementText)
      }
    }
    return merged
  }

  private fun projectedSettledAssistantTextByMessageId(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ): Map<String, String> {
    if (runs.isEmpty()) {
      return emptyMap()
    }
    val recordsByRunId = runRecordStoreFactory.forChatSession(sessionId)
      .list()
      .associateBy(PersistedAgentRunRecord::runId)
    if (recordsByRunId.isEmpty()) {
      return emptyMap()
    }
    val projected = linkedMapOf<String, String>()
    runs
      .asSequence()
      .filter(AgentRunSnapshot::isTerminal)
      .sortedBy(AgentRunSnapshot::updatedAtEpochMs)
      .forEach { run ->
        val pendingMessageId = run.pendingMessageId
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: return@forEach
        val result = recordsByRunId[run.runId]?.lastResult ?: return@forEach
        val projectedText = projectedTerminalAssistantText(result) ?: return@forEach
        projected[pendingMessageId] = projectedText
    }
    return projected
  }

  private fun projectedRuntimeMessagesForChat(
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
        val text = projectedRuntimeMessageText(event) ?: continue
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

  private fun projectedRuntimeMessageText(
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

  private fun projectedRuntimeMessageKind(event: OpenCrayAgentRunEvent): String = when (event) {
    is OpenCraySupplementEvent -> "outbound"
    else -> "inbound"
  }

  private fun isPersistedDraftAssistantPhase(
    event: OpenCrayAssistantPhaseEvent,
  ): Boolean = event.stage
    ?.trim()
    ?.equals(AppAgentSessionTaskRuntimeFactory.PERSISTED_DRAFT_ASSISTANT_STAGE, ignoreCase = true) == true

  private fun hideAssistantPhaseFromChatBubble(event: OpenCrayAssistantPhaseEvent): Boolean =
    event.stage
      ?.trim()
      ?.lowercase(Locale.US)
      ?.let(HIDDEN_ASSISTANT_CHAT_STAGES::contains) == true

  private fun projectedRuntimeSupplementBubbleText(
    event: OpenCraySupplementEvent,
  ): String? = if (hideSupplementFromChatBubble(event)) {
    null
  } else {
    event.text.trim().takeIf(String::isNotBlank)
  }

  private fun hideSupplementFromChatBubble(event: OpenCraySupplementEvent): Boolean =
    event.entryId.startsWith(TOOL_GENERATED_SUPPLEMENT_ENTRY_ID_PREFIX)

  private fun chatProgressText(event: OpenCrayAssistantPhaseEvent): String {
    val stage = event.stage?.trim().orEmpty()
    val text = event.text.trim()
    return when {
      stage.isEmpty() -> text
      text.isEmpty() -> stage
      else -> "$stage\n\n$text"
    }
  }

  private fun runtimeProjectedManagedProcessMessageId(
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

  private fun projectedManagedProcessMessageText(
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

  private fun managedProcessStatusLabelForChat(
    process: ManagedProcessSnapshot,
  ): String = when (process.status) {
    ManagedProcessStatus.RUNNING -> "running"
    ManagedProcessStatus.SUCCESS -> "finished"
    ManagedProcessStatus.FAILED,
    ManagedProcessStatus.SPAWN_ERROR,
    -> "failed"
    ManagedProcessStatus.CANCELLED -> "cancelled"
    ManagedProcessStatus.TIMEOUT -> "timed out"
    else -> process.status.name.lowercase()
  }

  private fun projectedTerminalAssistantText(
    result: ExecutionResult,
  ): String? {
    val rawText = when (result.status) {
      ExecutionStatus.SUCCESS -> result.stdout.ifBlank {
        resolvedStrings.agentEmptyAnswer
      }

      ExecutionStatus.CANCELLED -> resolvedStrings.agentCancelled
      ExecutionStatus.DENIED -> result.errorMessage ?: resolvedStrings.agentFailed(
        result.errorCode ?: result.status.name,
      )
      ExecutionStatus.FAILED -> if (
        result.errorCode == AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG
      ) {
        resolvedStrings.agentMissingLlm
      } else {
        resolvedStrings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
      }

      else -> resolvedStrings.agentFailed(
        result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    return sanitizeProjectedAssistantText(
      text = rawText,
      fallback = when (result.status) {
        ExecutionStatus.DENIED -> projectionApprovalFallbackBody(result.errorCode)
        else -> resolvedStrings.agentInternalPayloadHidden
      },
    )
  }

  private fun sanitizeProjectedAssistantText(
    text: String,
    fallback: String,
  ): String = approvalSupportSanitizePotentialInternalAgentText(
    text = text,
    fallback = fallback,
  )

  private fun projectionApprovalFallbackBody(errorCode: String?): String = if (
    errorCode == PROJECTION_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE
  ) {
    resolvedStrings.highRiskApprovalRequiredBody
  } else {
    resolvedStrings.summaryApprovalRequired
  }

  private fun shouldReplaceThinkingPlaceholder(
    message: ChatTranscriptMessageEntry,
    replacementText: String?,
  ): Boolean {
    if (message.role != ChatTranscriptRole.ASSISTANT || replacementText == null) {
      return false
    }
    val normalized = message.text?.trim().orEmpty()
    return normalized.isNotEmpty() &&
      normalized.equals(resolvedStrings.agentThinking.trim(), ignoreCase = false)
  }

  private fun loadProjectionRuntimeSnapshot(): Map<String, Any?> {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    return runtimeProjectionForSession(activeSessionId).snapshot
  }

  private fun findProjectionRunSnapshot(runId: String): Map<String, Any?>? =
    chatSessionStore.loadState().sessions
      .map(ChatSessionLocalStore.SessionSummary::sessionId)
      .firstNotNullOfOrNull { sessionId ->
        loadRunSnapshots(sessionId)
          .firstOrNull { snapshot -> snapshot.runId == runId }
          ?.takeIf(::isUserVisibleRun)
      }
      ?.let(::runSnapshotToMap)

  private fun waitForProjectionRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? {
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val deadline = clock() + boundedTimeoutMs
    while (true) {
      val snapshot = findProjectionRunSnapshot(runId)
      val isTerminal = snapshot?.get("isTerminal") == true
      if (isTerminal) {
        return snapshot
      }
      val now = clock()
      if (now >= deadline) {
        return snapshot
      }
      val sleepMs = minOf(PROJECTION_RUN_WAIT_POLL_INTERVAL_MS, deadline - now).coerceAtLeast(1L)
      try {
        Thread.sleep(sleepMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return snapshot
      }
    }
  }

  private fun liveAssistantDraftsForSnapshot(
    displayedRuns: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<ProjectionLiveAssistantDraftSnapshot> {
    val activeRuns = displayedRuns.filter(AgentRunSnapshot::isActive)
    if (activeRuns.isEmpty()) {
      return emptyList()
    }
    return activeRuns.mapNotNull { run ->
      val pendingMessageId = run.pendingMessageId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      persistedAssistantDraftForRun(
        run = run,
        pendingMessageId = pendingMessageId,
        recentEvents = recentEvents,
      )
    }
  }

  private fun persistedAssistantDraftForRun(
    run: AgentRunSnapshot,
    pendingMessageId: String,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): ProjectionLiveAssistantDraftSnapshot? {
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
        (event !is OpenCrayAssistantPhaseEvent || !isPersistedDraftAssistantPhase(event))
    }
    if (newerVisibleEventExists) {
      return null
    }
    val text = latestDraftEvent.text.trim().takeIf(String::isNotBlank) ?: return null
    return ProjectionLiveAssistantDraftSnapshot(
      runId = run.runId,
      taskId = run.taskId,
      executionId = latestDraftEvent.executionId ?: run.executionId,
      pendingMessageId = pendingMessageId,
      text = text,
      updatedAtEpochMs = latestDraftEvent.emittedAtEpochMs,
    )
  }

  private fun runtimeActivityUpdatedAtEpochMs(
    displayedRuns: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
    subAgentSnapshots: List<SubAgentActivitySnapshot>,
    liveAssistantDrafts: List<ProjectionLiveAssistantDraftSnapshot>,
  ): Long {
    val latestRunEpochMs = displayedRuns.maxOfOrNull(AgentRunSnapshot::updatedAtEpochMs) ?: 0L
    val latestEventEpochMs = recentEvents.maxOfOrNull(OpenCrayAgentRunEvent::emittedAtEpochMs) ?: 0L
    val latestSubAgentEpochMs =
      subAgentSnapshots.maxOfOrNull(SubAgentActivitySnapshot::updatedAtEpochMs) ?: 0L
    val latestDraftEpochMs =
      liveAssistantDrafts.maxOfOrNull(ProjectionLiveAssistantDraftSnapshot::updatedAtEpochMs) ?: 0L
    return maxOf(
      hostLifecycleDescriptor.hostCreatedAtEpochMs,
      latestRunEpochMs,
      latestEventEpochMs,
      latestSubAgentEpochMs,
      latestDraftEpochMs,
    )
  }

  private fun runtimeProjectionForSession(sessionId: String): ProjectionRuntimeSnapshot {
    val runs = loadRunSnapshots(sessionId).map { run ->
      run.copy(lastEvent = displayedLastEvent(run, sessionId))
    }
    val visibleRuns = userVisibleRuns(runs)
    val recentEvents = userVisibleRuntimeEvents(
      runs = runs,
      events = sessionJournalRuntimeEvents(sessionId),
    )
      .filterNot(::isInternalPromptCheckpointEvent)
      .filterNot(::isDebugOnlyRuntimeEvent)
    val subAgentSnapshots = subAgentSnapshotsForActivity(
      sessionId = sessionId,
      displayedRuns = visibleRuns,
      recentEvents = recentEvents,
    )
    val liveAssistantDrafts = liveAssistantDraftsForSnapshot(
      displayedRuns = visibleRuns,
      recentEvents = recentEvents,
    )
    val updatedAtEpochMs = runtimeActivityUpdatedAtEpochMs(
      displayedRuns = visibleRuns,
      recentEvents = recentEvents,
      subAgentSnapshots = subAgentSnapshots,
      liveAssistantDrafts = liveAssistantDrafts,
    )
    return ProjectionRuntimeSnapshot(
      runs = visibleRuns,
      recentEvents = recentEvents,
      liveAssistantDrafts = liveAssistantDrafts,
      updatedAtEpochMs = updatedAtEpochMs,
      snapshot = buildMap {
        put("sessionId", sessionId)
        put("streamInstanceId", runtimeEventStreamInstanceId)
        put("lastSequence", synchronized(runtimeEventStreamLock) {
          runtimeEventSequencesBySession[sessionId] ?: 0L
        })
        put("updatedAtEpochMs", updatedAtEpochMs)
        putRuntimeServiceDiagnosticsSnapshot(
          localRuntimeServerState = localRuntimeServerStateProvider(),
          hostLifecycle = hostLifecycleDescriptor,
          runtimeControllerLifecycle = runtimeControllerLifecycleProvider(),
          runtimeOwnerLifecycle = runtimeOwnerLifecycleProvider(),
          runtimeOwnerWorkSummary = runtimeOwnerWorkSummaryProvider(),
          runtimeServiceLifecycle = serviceLifecycleProvider(),
          runtimeServiceWorkState = serviceWorkStateProvider(),
          runtimeServiceKeepAliveState = serviceKeepAliveStateProvider(),
          runtimeServiceOwnerLease = ownerLeaseProvider(),
          runtimeServiceInterruptedRunRepair = interruptedRunRepairProvider(),
          runtimeServiceConnectionState = connectionStateProvider(),
        )
        put("activeRuns", visibleRuns.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap))
        put("retainedRuns", retainedRunsFor(visibleRuns).map(::runSnapshotToMap))
        put("subAgents", subAgentSnapshots.map(::subAgentSnapshotToMap))
        put("events", recentEvents.map(::runtimeEventToMap))
        put("liveAssistantDrafts", liveAssistantDrafts.map(::liveAssistantDraftToMap))
      },
    )
  }

  private fun loadRunSnapshots(sessionId: String): List<AgentRunSnapshot> {
    val runRecordStore = runRecordStoreFactory.forChatSession(sessionId)
    val journalStore = runEventJournalStoreFactory.forChatSession(sessionId)
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    val processRegistry = processRegistryFactory.forChatSession(sessionId)
    val queueSnapshotStore = queueSnapshotStoreFactory.forChatSession(sessionId)
    val queueRestorer = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = queueSnapshotStore,
      runRecordStore = runRecordStore,
      runEventJournalStore = journalStore,
      promptCheckpointStore = checkpointStore,
      managedProcessesProvider = processRegistry::list,
      clock = clock,
    )
    val taskSnapshotsByRunId = queueRestorer.restore(
      snapshot = queueSnapshotStore.load(),
      restoreEpochMs = clock(),
    )
      ?.tasks
      .orEmpty()
      .associateBy { taskSnapshot -> runIdFor(taskSnapshot.task) }
    val managedProcessesById = processRegistry.list().associateBy(ManagedProcessSnapshot::processId)
    val recordsByRunId = runRecordStore.list().associateBy(PersistedAgentRunRecord::runId)
    val runIds = linkedSetOf<String>().apply {
      addAll(recordsByRunId.keys)
      addAll(taskSnapshotsByRunId.keys)
    }
    return runIds.map { runId ->
      val taskSnapshot = taskSnapshotsByRunId[runId]
      val record = recordsByRunId[runId]
      val taskId = taskSnapshot?.task?.id ?: record?.taskId ?: runId
      val managedProcessIds = associatedManagedProcessIds(
        taskId = taskId,
        existingIds = record?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
      )
      val associatedProcesses = associatedManagedProcesses(
        taskId = taskId,
        existingIds = managedProcessIds,
        managedProcessesById = managedProcessesById,
        managedProcessReader = processRegistry::read,
      )
      val result = visibleRunResult(
        taskSnapshot = taskSnapshot,
        result = effectiveResult(
          taskSnapshot = taskSnapshot,
          record = record,
          associatedProcesses = associatedProcesses,
        ),
      )
      val acceptedAtEpochMs = record?.acceptedAtEpochMs ?: taskSnapshot?.task?.createdAtEpochMs ?: 0L
      val lastEvent = runJournalRuntimeEvents(sessionId = sessionId, runId = runId).lastOrNull()
        ?: record?.lastEvent?.toRuntimeEventOrNull()
      val taskMetadata = taskSnapshot?.task?.metadata.orEmpty()
      val resultMetadata = result?.metadata.orEmpty()
      AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = acceptedAtEpochMs,
        updatedAtEpochMs = maxOf(
          taskSnapshot?.task?.updatedAtEpochMs ?: 0L,
          result?.finishedAtEpochMs ?: 0L,
          lastEvent?.emittedAtEpochMs ?: 0L,
          associatedProcesses.maxOfOrNull { snapshot ->
            snapshot.finishedAtEpochMs ?: snapshot.updatedAtEpochMs
          } ?: 0L,
          acceptedAtEpochMs,
        ),
        lifecycleState = projectedLifecycleState(taskSnapshot?.lifecycleState, result),
        taskState = projectedTaskState(taskSnapshot?.task?.state, result),
        attempt = taskSnapshot?.attempt ?: 0,
        executionOrdinal = taskSnapshot?.executionOrdinal
          ?: taskMetadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull()
          ?: resultMetadata[METADATA_EXECUTION_ORDINAL]?.toIntOrNull()
          ?: lastEvent?.executionOrdinal
          ?: 0,
        executionId = taskSnapshot?.executionId
          ?: taskMetadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
          ?: resultMetadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)
          ?: lastEvent?.executionId?.trim()?.takeIf(String::isNotBlank),
        executionKind = taskSnapshot?.executionKind
          ?: taskMetadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
          ?: resultMetadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
          ?: lastEvent?.executionKind?.trim()?.takeIf(String::isNotBlank),
        pendingExecutionKind = taskMetadata[METADATA_PENDING_EXECUTION_KIND]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        executionStatus = result?.status,
        errorCode = if (result != null) {
          result.errorCode
        } else if (shouldShowTaskSnapshotError(taskSnapshot)) {
          taskSnapshot?.lastErrorCode
        } else {
          null
        },
        errorMessage = if (result != null) {
          result.errorMessage
        } else if (shouldShowTaskSnapshotError(taskSnapshot)) {
          taskSnapshot?.lastErrorMessage
        } else {
          null
        },
        responseFormat = result?.metadata?.get("responseFormat"),
        resultMetadata = result?.metadata.orEmpty(),
        pendingMessageId = taskSnapshot?.task?.metadata
          ?.get(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID)
          ?: record?.pendingMessageId,
        managedProcessIds = managedProcessIds,
        managedProcesses = associatedProcesses,
        runningManagedProcessCount = associatedProcesses.count { snapshot ->
          snapshot.status == ManagedProcessStatus.RUNNING
        },
        hasLiveManagedProcesses = associatedProcesses.any { snapshot ->
          snapshot.status == ManagedProcessStatus.RUNNING
        },
        hasAutoResumeEligibleManagedProcesses = associatedProcesses.any { snapshot ->
          snapshot.isAutoResumeEligibleManagedProcess()
        },
        lastEvent = lastEvent,
        lifecycleDiagnostics = runLifecycleDiagnosticsFrom(
          taskMetadata = taskSnapshot?.task?.metadata.orEmpty(),
          resultMetadata = result?.metadata.orEmpty(),
          resultErrorCode = result?.errorCode,
        ),
      )
    }.sortedByDescending(AgentRunSnapshot::acceptedAtEpochMs)
  }

  private fun effectiveResult(
    taskSnapshot: SessionQueueTaskSnapshot?,
    record: PersistedAgentRunRecord?,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): ExecutionResult? {
    val persistedResult = record?.lastResult
    if (persistedResult != null) {
      return persistedResult
    }
    if (
      record == null ||
      taskSnapshot == null ||
      isTerminalProjectionLifecycle(taskSnapshot.lifecycleState)
    ) {
      return null
    }
    if (associatedProcesses.isEmpty() || associatedProcesses.any { it.status == ManagedProcessStatus.RUNNING }) {
      return null
    }
    if (
      associatedProcesses.all { snapshot -> snapshot.isProjectionTerminalAfterRestore() } &&
      associatedProcesses.any { snapshot -> snapshot.isProjectionInterruptedOnRestore() }
    ) {
      return repairedInterruptedRestoreResult(record, associatedProcesses)
    }
    return null
  }

  private fun displayedLastEvent(
    run: AgentRunSnapshot,
    sessionId: String,
  ): OpenCrayAgentRunEvent? {
    val runEvents = executionScopedRunEvents(
      run = run,
      sessionId = sessionId,
    ).filterNot(::isInternalPromptCheckpointEvent)
    val latest = runEvents.lastOrNull() ?: return run.lastEvent?.takeIf { event ->
      !isInternalPromptCheckpointEvent(event) &&
        eventMatchesRunExecution(run = run, event = event)
    }
    if (latest is OpenCrayApprovalEvent && latest.phase != OpenCrayApprovalPhase.REQUIRED) {
      val previousMeaningful = runEvents
        .dropLast(1)
        .asReversed()
        .firstOrNull { event ->
          event !is OpenCrayApprovalEvent || event.phase == OpenCrayApprovalPhase.REQUIRED
        }
      return previousMeaningful ?: latest
    }
    return latest
  }

  private fun executionScopedRunEvents(
    run: AgentRunSnapshot,
    sessionId: String,
  ): List<OpenCrayAgentRunEvent> {
    val runEvents = sessionJournalRuntimeEvents(sessionId)
      .filter { event -> event.runId == run.runId }
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
    val matching = runEvents.filter { event -> event.executionId?.trim() == currentExecutionId }
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

  private fun sessionJournalRuntimeEvents(sessionId: String): List<OpenCrayAgentRunEvent> =
    dedupeRuntimeEventsPreservingOrder(
      runEventJournalStoreFactory.forChatSession(sessionId).listRuntimeEvents(),
    )

  private fun runJournalRuntimeEvents(
    sessionId: String,
    runId: String,
  ): List<OpenCrayAgentRunEvent> = dedupeRuntimeEventsPreservingOrder(
    runEventJournalStoreFactory.forChatSession(sessionId)
      .listForRun(runId)
      .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() },
  )

  private fun eventMatchesRunExecution(
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

  private fun isUntaggedExecutionEvent(event: OpenCrayAgentRunEvent): Boolean =
    event.executionId?.trim().isNullOrEmpty() &&
      event.executionOrdinal == null &&
      event.executionKind?.trim().isNullOrEmpty()

  private fun userVisibleRuns(
    runs: List<AgentRunSnapshot>,
  ): List<AgentRunSnapshot> = runs.filter(::isUserVisibleRun)

  private fun userVisibleRuntimeEvents(
    runs: List<AgentRunSnapshot>,
    events: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    if (events.isEmpty()) {
      return emptyList()
    }
    val runsByRunId = runs.associateBy(AgentRunSnapshot::runId)
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return events.filter { event ->
      if (isInternalPromptCheckpointEvent(event)) {
        return@filter false
      }
      val matchingRun = runsByRunId[event.runId] ?: runsByTaskId[event.taskId]
      matchingRun?.let(::isUserVisibleRun) != false
    }
  }

  private fun isUserVisibleRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleDiagnostics.submissionSource != RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY

  private fun subAgentSnapshotsForActivity(
    sessionId: String,
    displayedRuns: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<SubAgentActivitySnapshot> {
    val registrySnapshots = subAgentSnapshotsFromDurableSources(sessionId)
    val visibleRunIds = displayedRuns
      .mapTo(linkedSetOf(), AgentRunSnapshot::runId)
      .ifEmpty {
        recentEvents.mapNotNullTo(linkedSetOf()) { event ->
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
      val key = subAgentRegistryKey(subAgentEvent)
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
      )
      eventSnapshotsByKey[subAgentRegistryKey(snapshot)] = snapshot
    }
    registrySnapshots
      .filter { snapshot -> snapshot.parentRunId in visibleRunIds }
      .forEach { snapshot ->
        val key = subAgentRegistryKey(snapshot)
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
          )
        }
      }
    return eventSnapshotsByKey.values.toList()
  }

  private fun subAgentRegistryKey(
    event: OpenCraySubAgentEvent,
  ): String = listOf(
    event.runId,
    event.agentId?.trim()?.takeIf(String::isNotBlank)
      ?: event.childRunId.trim().takeIf(String::isNotBlank)
      ?: event.childTaskId.trim().takeIf(String::isNotBlank)
      ?: event.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentRegistryKey(
    snapshot: SubAgentActivitySnapshot,
  ): String = listOf(
    snapshot.parentRunId,
    snapshot.agentId?.trim()?.takeIf(String::isNotBlank)
      ?: snapshot.childRunId.trim().takeIf(String::isNotBlank)
      ?: snapshot.childTaskId.trim().takeIf(String::isNotBlank)
      ?: snapshot.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentSnapshotsFromDurableSources(
    sessionId: String,
  ): List<SubAgentActivitySnapshot> {
    val latestByKey = linkedMapOf<String, DurableSubAgentSnapshot>()
    fun mergeSnapshot(
      snapshot: SubAgentActivitySnapshot,
      sourcePriority: Int,
    ) {
      val key = subAgentRegistryKey(snapshot)
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
    val handleStore = subAgentHandleStoreFactory.forChatSession(sessionId)
    mergeSubAgentHandlesByLatestState(
      liveHandles = handleStore.list(),
      closedHandles = handleStore.listClosed(),
    ).forEach { entry ->
      mergeSnapshot(
        snapshot = subAgentActivitySnapshot(
          handle = entry.handle,
          closed = entry.closed,
        ),
        sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_HANDLE,
      )
    }
    subAgentSessionLinkStoreFactory.forChatSession(sessionId)
      .list()
      .forEach { link ->
        mergeSnapshot(
          snapshot = subAgentActivitySnapshot(link),
          sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_LINK,
        )
      }
    promptCheckpointStoreFactory.forChatSession(sessionId)
      .list()
      .asReversed()
      .forEach { checkpoint ->
        checkpointSubAgentHandles(checkpoint).forEach { handle ->
          mergeSnapshot(
            snapshot = subAgentActivitySnapshot(
              handle = handle,
              closed = false,
            ),
            sourcePriority = DURABLE_SUBAGENT_SOURCE_PRIORITY_CHECKPOINT,
          )
        }
      }
    return latestByKey.values.map(DurableSubAgentSnapshot::snapshot)
  }

  private fun checkpointSubAgentHandles(
    checkpoint: PersistedPromptCheckpoint,
  ): Sequence<SubAgentHandleState> = sequenceOf(
    checkpoint.promptResumeState,
    checkpoint.subAgentPromptResumeState,
  ).filterNotNull().flatMap { state -> state.subAgentHandles.asSequence() }

  private fun subAgentActivitySnapshot(
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

  private fun subAgentActivitySnapshot(
    handle: SubAgentHandleState,
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
      hasActiveExecution = handle.snapshot.state == SubAgentExecutionState.RUNNING ||
        handle.snapshot.state == SubAgentExecutionState.BACKGROUND_RUNNING,
      mailboxMessageCount = mailbox.messages.size,
      mailboxPendingMessageCount = mailbox.pendingMessages().size,
      mailboxLastDeliveredMessageId = mailbox.lastDeliveredMessageId,
      hasPendingApprovalResume = pendingApprovalResume != null,
      pendingApprovalToolName = pendingApprovalResume?.approvedToolName,
      pendingApprovalIsHighRisk = pendingApprovalResume?.isHighRisk == true,
      pendingApprovalChildRunId = pendingApprovalResume?.childRunId,
      pendingApprovalChildTaskId = pendingApprovalResume?.childTaskId,
    )
  }

  private fun subAgentPhaseFor(
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

  private fun subAgentSnapshotToMap(snapshot: SubAgentActivitySnapshot): Map<String, Any?> = mapOf(
    "parentRunId" to snapshot.parentRunId,
    "parentTaskId" to snapshot.parentTaskId,
    "agentId" to snapshot.agentId,
    "childSessionId" to snapshot.childSessionId,
    "childRunId" to snapshot.childRunId,
    "childTaskId" to snapshot.childTaskId,
    "label" to snapshot.label,
    "subagentType" to snapshot.subagentType,
    "contextMode" to snapshot.contextMode,
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
  )

  private fun retainedRunsFor(runs: List<AgentRunSnapshot>): List<AgentRunSnapshot> {
    return runs.filter { run ->
      !run.isActive &&
        (
          run.isTerminal ||
            isAwaitingDirectionRun(run) ||
            isInterruptedOnRestoreRun(run)
          )
    }
  }

  private fun latestRunFor(runs: List<AgentRunSnapshot>): AgentRunSnapshot? =
    runs.maxWithOrNull(
      compareBy<AgentRunSnapshot>({ it.acceptedAtEpochMs }, { it.updatedAtEpochMs }),
    )

  private fun summaryBody(
    messageCount: Int,
    runs: List<AgentRunSnapshot>,
    hasPendingApprovals: Boolean,
  ): String = when {
    hasPendingApprovals -> resolvedStrings.summaryApprovalRequired
    latestRunFor(runs)?.let { run ->
      isAwaitingDirectionRun(run) || isDeferredApprovalDecisionAwaitingResumeRun(run)
    } == true -> resolvedStrings.summaryAwaitingDirection
    runs.any(AgentRunSnapshot::isActive) -> resolvedStrings.summaryReplyInProgress
    messageCount == 0 -> resolvedStrings.summaryStartNewSession
    else -> resolvedStrings.summaryRestored
  }

  private fun composerPlaceholderFor(
    runs: List<AgentRunSnapshot>,
    hasPendingApprovals: Boolean,
  ): String {
    if (hasPendingApprovals) {
      return resolvedStrings.composerPlaceholder
    }
    val latestRun = latestRunFor(runs) ?: return resolvedStrings.composerPlaceholder
    if (isDeferredApprovalDecisionAwaitingResumeRun(latestRun)) {
      return resolvedStrings.composerPlaceholder
    }
    return if (isAwaitingDirectionRun(latestRun)) {
      resolvedStrings.composerRejectedPlaceholder
    } else {
      resolvedStrings.composerPlaceholder
    }
  }

  private fun isAwaitingDirectionRun(run: AgentRunSnapshot): Boolean =
    (run.lastEvent as? OpenCrayApprovalEvent)?.phase == OpenCrayApprovalPhase.REJECTED ||
      (run.lastEvent as? OpenCrayCancellationEvent)?.outcome == "user_interrupted" ||
      (
        run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
          run.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
        )

  private fun isDeferredApprovalDecisionAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      promptCheckpointStoreFactory.forChatSession(run.sessionId)
        .get(run.taskId)
        ?.checkpointKind in setOf(
        PromptCheckpointKind.APPROVED_PENDING_RESUME,
        PromptCheckpointKind.REJECTED_PENDING_RESUME,
      )

  private fun isInterruptedOnRestoreRun(run: AgentRunSnapshot): Boolean =
    run.errorCode == com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      run.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE

  private fun displaySessionTitle(rawTitle: String): String =
    if (rawTitle == ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
      resolvedStrings.defaultSessionTitle
    } else {
      rawTitle
    }

  private fun projectedPendingApprovals(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ): List<Map<String, Any?>> {
    val runRecordStore = runRecordStoreFactory.forChatSession(sessionId)
    val journalStore = runEventJournalStoreFactory.forChatSession(sessionId)
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    val processRegistry = processRegistryFactory.forChatSession(sessionId)
    val queueSnapshotStore = queueSnapshotStoreFactory.forChatSession(sessionId)
    val queueRestorer = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = queueSnapshotStore,
      runRecordStore = runRecordStore,
      runEventJournalStore = journalStore,
      promptCheckpointStore = checkpointStore,
      managedProcessesProvider = processRegistry::list,
      clock = clock,
    )
    val isChinese = resolvedStrings.localeTag.startsWith("zh", ignoreCase = true)
    return approvalRequiredTaskProjections(
      sessionId = sessionId,
      queueTaskSnapshots = queueRestorer.restore(
        snapshot = queueSnapshotStore.load(),
        restoreEpochMs = clock(),
      )?.tasks.orEmpty(),
      runSnapshots = runs,
      checkpoints = checkpointStore.list(),
      approvalRequiredErrorCode = PROJECTION_APPROVAL_REQUIRED_ERROR_CODE,
      highRiskApprovalRequiredErrorCode = PROJECTION_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
    )
      .asSequence()
      .filter { projection ->
        projection.isVisibleApprovalLifecycle() &&
          approvalDecisionState(
            approved = false,
            rejected = false,
            checkpoint = projection.checkpoint,
          ) == null
      }
      .map { projection ->
        val metadata = projection.metadata
        val isHighRisk = projection.checkpoint?.isHighRisk == true || approvalMetadataIsHighRisk(
          errorCode = projection.errorCode,
          highRiskErrorCode = PROJECTION_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
          metadata = metadata,
        )
        val toolReason = projection.toolReason
        val supportsSessionApproval = approvalMetadataSupportsSessionScope(metadata)
        val title = if (isHighRisk) {
          resolvedStrings.highRiskApprovalRequiredTitle
        } else {
          resolvedStrings.approvalRequiredTitle
        }
        val message = projectionSanitizeApprovalBody(
          body = projection.errorBody,
          isHighRisk = isHighRisk,
        )
        val body = composeProjectionApprovalBody(
          body = message,
          toolReason = toolReason,
          metadata = metadata,
          isChinese = isChinese,
        )
        mapOf(
          "runId" to projection.runId,
          "taskId" to projection.taskId,
          "pendingMessageId" to projection.pendingMessageId,
          "toolName" to (
            projection.checkpoint?.toolName
              ?: approvalMetadataToolName(metadata)
          ),
          "requestSummary" to approvalSupportRequestSummary(metadata),
          "primaryDetail" to approvalSupportPrimaryDetailValue(metadata),
          "pathDetails" to approvalSupportPathDetailLines(metadata, isChinese),
          "workingDirectory" to approvalSupportWorkingDirectoryValue(metadata),
          "reason" to approvalSupportReasonValue(toolReason),
          "message" to message,
          "risk" to if (isHighRisk) "high_risk" else "standard",
          "isHighRisk" to isHighRisk,
          "title" to title,
          "body" to body,
          "approveLabel" to resolvedStrings.approvalApproveLabel,
          "supportsSessionApproval" to supportsSessionApproval,
          "approveForSessionLabel" to if (supportsSessionApproval) {
            resolvedStrings.approvalApproveForSessionLabel
          } else {
            ""
          },
          "rejectLabel" to resolvedStrings.approvalRejectLabel,
        )
      }
      .toList()
  }

  private fun composeProjectionApprovalBody(
    body: String,
    toolReason: String?,
    metadata: Map<String, String>,
    isChinese: Boolean,
  ): String = approvalSupportComposeBody(
    body = body,
    toolReason = toolReason,
    metadata = metadata,
    isChinese = isChinese,
  )

  private fun projectionSanitizeApprovalBody(body: String?, isHighRisk: Boolean): String {
    val fallback = if (isHighRisk) {
      resolvedStrings.highRiskApprovalRequiredBody
    } else {
      resolvedStrings.summaryApprovalRequired
    }
    val resolved = body?.takeIf(String::isNotBlank) ?: return fallback
    return approvalSupportSanitizePotentialInternalAgentText(
      text = resolved,
      fallback = fallback,
    )
  }

  private fun observeProjectionWithPolling(
    payloadProvider: () -> Map<String, Any?>,
    listener: (Map<String, Any?>) -> Unit,
  ): () -> Unit {
    val lock = Any()
    var disposed = false
    var latestPayload: Map<String, Any?>? = runCatching(payloadProvider).getOrNull()
    latestPayload?.let { initialPayload ->
      mainThreadPoster.post {
        synchronized(lock) {
          if (disposed) {
            return@post
          }
        }
        listener(initialPayload)
      }
    }
    val timer = Timer("projection-chat-gateway-observer", true)
    timer.scheduleAtFixedRate(
      object : TimerTask() {
        override fun run() {
          val nextPayload = runCatching(payloadProvider).getOrNull() ?: return
          val shouldEmit = synchronized(lock) {
            if (disposed || nextPayload == latestPayload) {
              false
            } else {
              latestPayload = nextPayload
              true
            }
          }
          if (shouldEmit) {
            mainThreadPoster.post {
              synchronized(lock) {
                if (disposed) {
                  return@post
                }
              }
              listener(nextPayload)
            }
          }
        }
      },
      0L,
      pollIntervalMs.coerceAtLeast(1L),
    )
    return {
      synchronized(lock) {
        disposed = true
      }
      timer.cancel()
    }
  }

  private fun associatedManagedProcesses(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
    managedProcessReader: (String) -> ManagedProcessSnapshot?,
  ): List<ManagedProcessSnapshot> = associatedManagedProcessesForProjection(
    taskId = taskId,
    existingIds = existingIds,
    managedProcessesById = managedProcessesById,
    managedProcessReader = managedProcessReader,
  )

  private fun associatedManagedProcessIds(
    taskId: String,
    existingIds: List<String>,
    managedProcessesById: Map<String, ManagedProcessSnapshot>,
  ): List<String> = (
    existingIds +
      managedProcessesById.values
        .asSequence()
        .filter { snapshot -> snapshot.taskId == taskId }
        .map(ManagedProcessSnapshot::processId)
        .toList()
    ).distinct()

  private fun projectedLifecycleState(
    original: QueueTaskLifecycleState?,
    result: ExecutionResult?,
  ): QueueTaskLifecycleState? {
    val projected = projectedLifecycleStateForRestoreResult(
      original = original,
      result = result,
    )
    if (projected != null || original != null) {
      return projected
    }
    return terminalLifecycleState(result)
  }

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? {
    val projected = projectedTaskStateForRestoreResult(
      original = original,
      result = result,
    )
    if (projected != null || original != null) {
      return projected
    }
    return terminalTaskState(result)
  }

  private fun terminalLifecycleState(result: ExecutionResult?): QueueTaskLifecycleState? = when {
    result == null -> null
    isAwaitingExplicitResumeResult(result) -> null
    result.status == ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
    result.status == ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
    result.status == ExecutionStatus.FAILED ||
      result.status == ExecutionStatus.TIMEOUT ||
      result.status == ExecutionStatus.DENIED
    -> QueueTaskLifecycleState.FAILED

    else -> null
  }

  private fun terminalTaskState(result: ExecutionResult?): AgentTaskState? = when {
    result == null -> null
    isAwaitingExplicitResumeResult(result) -> null
    result.status == ExecutionStatus.SUCCESS -> AgentTaskState.COMPLETED
    result.status == ExecutionStatus.CANCELLED -> AgentTaskState.CANCELLED
    result.status == ExecutionStatus.FAILED ||
      result.status == ExecutionStatus.TIMEOUT ||
      result.status == ExecutionStatus.DENIED
    -> AgentTaskState.FAILED

    else -> null
  }

  private fun isAwaitingExplicitResumeResult(result: ExecutionResult): Boolean = when {
    result.status == ExecutionStatus.DENIED &&
      (
        result.errorCode == "APPROVAL_REQUIRED" ||
          result.errorCode == "HIGH_RISK_APPROVAL_REQUIRED"
        ) -> true

    result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME -> true
    else -> false
  }

  private fun visibleRunResult(
    taskSnapshot: SessionQueueTaskSnapshot?,
    result: ExecutionResult?,
  ): ExecutionResult? = visibleProjectedRunResult(
    taskSnapshot = taskSnapshot,
    result = result,
  )

  private fun shouldShowTaskSnapshotError(taskSnapshot: SessionQueueTaskSnapshot?): Boolean =
    shouldShowProjectedTaskSnapshotError(taskSnapshot)

  private fun isInterruptedOnRestoreResult(result: ExecutionResult?): Boolean =
    isInterruptedOnRestoreProjectionResult(result)

  private fun repairedInterruptedRestoreResult(
    record: PersistedAgentRunRecord,
    associatedProcesses: List<ManagedProcessSnapshot>,
  ): ExecutionResult {
    val orderedProcessIds = associatedProcesses
      .map(ManagedProcessSnapshot::processId)
      .distinct()
      .sorted()
    val latestUpdateEpochMs = associatedProcesses.maxOf { snapshot ->
      snapshot.finishedAtEpochMs ?: snapshot.updatedAtEpochMs
    }
    return ExecutionResult(
      taskId = record.taskId,
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
      startedAtEpochMs = record.acceptedAtEpochMs,
      finishedAtEpochMs = maxOf(record.acceptedAtEpochMs, latestUpdateEpochMs),
      metadata = mapOf(
        METADATA_RESTORED_TERMINAL_STATE to RESTORED_TERMINAL_STATE_INTERRUPTED,
        METADATA_RESTORED_FROM_DURABLE_STORE to "true",
        METADATA_RUN_REPAIR_SOURCE to RUN_REPAIR_SOURCE_MANAGED_PROCESS_RESTORE,
      ),
    )
  }

  private fun ManagedProcessSnapshot.isProjectionInterruptedOnRestore(): Boolean =
    isProjectionInterruptedOnRestoreState()

  private fun ManagedProcessSnapshot.isProjectionTerminalAfterRestore(): Boolean =
    isProjectionTerminalAfterRestoreState()

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun runtimeProjectedMessageId(event: OpenCrayAgentRunEvent): String = when (event) {
    // Mirrors OpenCrayHostRuntime.runtimeProjectedMessageId: all consumers must
    // derive one id per event regardless of eventId stamping state.
    is OpenCrayAssistantPhaseEvent -> "runtime-assistant-event-${runtimeEventStableId(event)}"
    is OpenCraySupplementEvent -> "runtime-supplement-${event.entryId}"
    is OpenCrayApprovalEvent ->
      "runtime-approval-${event.phase.name.lowercase(Locale.US)}-${event.runId}-${event.emittedAtEpochMs}"
    is OpenCrayToolCallEvent -> "runtime-tool-call-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayToolResultEvent -> "runtime-tool-result-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayCancellationEvent -> "runtime-interrupted-${event.runId}-${event.emittedAtEpochMs}"
    else -> "runtime-event-${event.runId}-${event.emittedAtEpochMs}"
  }

  private fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> = mapOf(
    "sessionId" to run.sessionId,
    "runId" to run.runId,
    "taskId" to run.taskId,
    "acceptedAtEpochMs" to run.acceptedAtEpochMs,
    "updatedAtEpochMs" to run.updatedAtEpochMs,
    "lifecycleState" to run.lifecycleState?.name?.lowercase(),
    "taskState" to run.taskState?.name?.lowercase(),
    "attempt" to run.attempt,
    "executionOrdinal" to run.executionOrdinal,
    "executionId" to run.executionId,
    "executionKind" to run.executionKind,
    "pendingExecutionKind" to run.pendingExecutionKind,
    "executionStatus" to run.executionStatus?.name?.lowercase(),
    "errorCode" to run.errorCode,
    "errorMessage" to run.errorMessage,
    "responseFormat" to run.responseFormat,
    "llmDiagnostics" to runSnapshotLlmDiagnosticsFromMetadata(run.resultMetadata),
    "liveContext" to runSnapshotLiveContextFromMetadata(run.resultMetadata),
    "contextBudget" to runSnapshotContextBudgetFromMetadata(run.resultMetadata),
    "memoryTrace" to runSnapshotMemoryTraceFromMetadata(run.resultMetadata),
    "stickyMemory" to runSnapshotStickyMemoryFromMetadata(run.resultMetadata),
    "memoryFlush" to runSnapshotMemoryFlushFromMetadata(run.resultMetadata),
    "bootstrap" to runSnapshotBootstrapFromMetadata(run.resultMetadata),
    "durableCompaction" to runSnapshotDurableCompactionFromMetadata(run.resultMetadata),
    "skillInventory" to runSnapshotSkillInventoryFromMetadata(run.resultMetadata),
    "activeSkill" to runSnapshotActiveSkillFromMetadata(run.resultMetadata),
    "pendingMessageId" to run.pendingMessageId,
    "managedProcessIds" to run.managedProcessIds,
    "managedProcesses" to run.managedProcesses.map(::managedProcessSnapshotToMap),
    "finalAttachments" to finalAttachmentsForRun(run).map(::chatAttachmentSnapshotMap),
    "runningManagedProcessCount" to run.runningManagedProcessCount,
    "hasLiveManagedProcesses" to run.hasLiveManagedProcesses,
    "isActive" to run.isActive,
    "isTerminal" to run.isTerminal,
    "lastEvent" to run.lastEvent?.let(::runtimeEventToMap),
    "diagnostics" to run.lifecycleDiagnostics.toMap(),
    "recoveryPlan" to recoveryPlanForRun(run)?.toMap(),
  )

  private fun managedProcessSnapshotToMap(
    snapshot: ManagedProcessSnapshot,
  ): Map<String, Any?> = buildMap {
    val stdoutTruncated = snapshot.stdout.length > MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS
    val stderrTruncated = snapshot.stderr.length > MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS
    put("processId", snapshot.processId)
    put("status", snapshot.status.name.lowercase())
    put("command", snapshot.command)
    put("args", snapshot.args)
    put("workingDirectory", snapshot.workingDirectory)
    put("processStarted", snapshot.processStarted)
    put("timeoutMs", snapshot.timeoutMs)
    put("startedAtEpochMs", snapshot.startedAtEpochMs)
    put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
    put("finishedAtEpochMs", snapshot.finishedAtEpochMs)
    put("exitCode", snapshot.exitCode)
    put("errorCode", snapshot.errorCode)
    put("errorMessage", snapshot.errorMessage)
    put("timedOut", snapshot.timedOut)
    put("cancelled", snapshot.cancelled)
    put("outputLimitExceeded", snapshot.outputLimitExceeded)
    put("stdout", snapshot.stdout)
    put("stderr", snapshot.stderr)
    put("stdoutPreview", managedProcessOutputPreview(snapshot.stdout))
    put("stderrPreview", managedProcessOutputPreview(snapshot.stderr))
    put("stdoutTruncated", stdoutTruncated)
    put("stderrTruncated", stderrTruncated)
  }

  private fun managedProcessOutputPreview(text: String): String =
    text.takeLast(MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS)

  private fun recoveryPlanForRun(run: AgentRunSnapshot): RunRecoveryPlan? =
    loadStoredRunRecoveryPlan(
      run = run,
      checkpointStore = promptCheckpointStoreFactory.forChatSession(run.sessionId),
      journalStore = runEventJournalStoreFactory.forChatSession(run.sessionId),
      planner = recoveryPlanner,
    )

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> =
    runtimeEventPayload(event).toMutableMap().apply {
      put("eventId", runtimeEventStableId(event))
    }

  private fun assignRuntimeRealtimeEnvelope(payload: Map<String, Any?>): Map<String, Any?> {
    val sessionId = (payload["sessionId"] as? String)?.trim().orEmpty()
    if (sessionId.isBlank()) {
      return payload
    }
    val sequence = synchronized(runtimeEventStreamLock) {
      val next = (runtimeEventSequencesBySession[sessionId] ?: 0L) + 1L
      runtimeEventSequencesBySession[sessionId] = next
      next
    }
    return payload.toMutableMap().apply {
      put("streamInstanceId", runtimeEventStreamInstanceId)
      put("sequence", sequence)
      put("lastSequence", sequence)
      put("eventId", "runtime-stream-$runtimeEventStreamInstanceId-$sessionId-$sequence")
      put("executionId", payload["executionId"] ?: payload["runId"])
    }
  }

  private fun runtimeEventPayload(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
    is OpenCrayLifecycleEvent -> mapOf(
      "kind" to "lifecycle",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.status?.name?.lowercase(),
      "errorCode" to event.errorCode,
      "errorMessage" to event.errorMessage,
    )
    is OpenCrayAssistantPhaseEvent -> buildMap<String, Any?> {
      put("kind", "assistant_phase")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("executionId", event.executionId)
      put("executionOrdinal", event.executionOrdinal)
      put("executionKind", event.executionKind)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("phase", event.phase.name.lowercase())
      put("responseFormat", event.responseFormat)
      put("isFinal", event.isFinal)
      put("stage", event.stage)
      put("text", event.text)
      if (
        event.metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON]
          ?.trim()
          ?.isNotBlank() == true
      ) {
        put("hasResumeCheckpointMetadata", true)
      }
    }
    is OpenCraySupplementEvent -> buildMap<String, Any?> {
      put("kind", "supplement")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("executionId", event.executionId)
      put("executionOrdinal", event.executionOrdinal)
      put("executionKind", event.executionKind)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("entryId", event.entryId)
      put("text", event.text)
      put("checkpoint", event.checkpoint)
      val metadataSnapshot = toolResultMetadataSnapshot(event.metadata)
      if (metadataSnapshot.isNotEmpty()) {
        put("metadata", metadataSnapshot)
      }
      if (
        event.metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON]
          ?.trim()
          ?.isNotBlank() == true
      ) {
        put("hasResumeCheckpointMetadata", true)
      }
    }
    is OpenCrayApprovalEvent -> mapOf(
      "kind" to if (event.phase == OpenCrayApprovalPhase.REQUIRED) "approval_wait" else "approval_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.phase.name.lowercase(),
      "status" to event.phase.name.lowercase(),
      "isHighRisk" to event.isHighRisk,
    )
    is OpenCraySubAgentEvent -> mapOf(
      "kind" to "subagent",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "agentId" to event.agentId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.executionState?.wireValue,
      "childRunId" to event.childRunId,
      "childTaskId" to event.childTaskId,
      "label" to event.label,
      "subagentType" to event.subagentType,
      "contextMode" to event.contextMode,
      "depth" to event.depth,
      "executionState" to event.executionState?.wireValue,
      "continuationKind" to event.continuationKind?.wireValue,
      "closed" to event.closed,
      "text" to event.summary,
    )
    is OpenCrayToolCallEvent -> mapOf(
      "kind" to "tool_call",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolReason" to event.call.reason,
      "argumentsJson" to event.call.arguments.toString(),
    )
    is OpenCrayToolResultEvent -> mapOf(
      "kind" to "tool_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolStatus" to event.result.status.name.lowercase(),
      "errorCode" to event.result.errorCode,
      "errorMessage" to event.result.errorMessage,
      "content" to toolResultDetailedContentSnapshot(event.result),
      "contentPreview" to event.result.content.take(MAX_PROJECTION_RUNTIME_EVENT_PREVIEW_CHARS),
      "resultMetadata" to toolResultMetadataSnapshot(event.result.metadata),
    )
    is OpenCrayMemoryRetrievalEvent -> mapOf(
      "kind" to "memory_retrieval",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "operation" to event.operation,
      "query" to event.query,
      "queryTerms" to event.queryTerms,
      "resultCount" to event.resultCount,
      "corpusFileCount" to event.corpusFileCount,
      "recordIds" to event.recordIds,
      "paths" to event.paths,
      "lineRanges" to event.lineRanges,
      "path" to event.path,
      "fromLine" to event.fromLine,
      "returnedLineCount" to event.returnedLineCount,
      "totalLineCount" to event.totalLineCount,
    )
    is OpenCrayMemoryWriteEvent -> mapOf(
      "kind" to "memory_write",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "writtenRecordIds" to event.writtenRecordIds,
      "writtenKinds" to event.writtenKinds,
      "resolvedRecordIds" to event.resolvedRecordIds,
      "suppressedRecordIds" to event.suppressedRecordIds,
      "reopenedRecordIds" to event.reopenedRecordIds,
      "reaffirmedRecordIds" to event.reaffirmedRecordIds,
      "expiredRecordIds" to event.expiredRecordIds,
      "stewardshipPlanSteps" to event.stewardshipPlanSteps.map(::stewardshipPlanStepToMap),
      "stewardshipPlanGraph" to stewardshipPlanGraphToMap(event.stewardshipPlanGraph),
    )
    is OpenCrayCancellationEvent -> mapOf(
      "kind" to "interrupted",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.outcome,
      "status" to event.outcome,
    )
  }

  private fun chatMessageToMap(
    message: ChatTranscriptMessageEntry,
    textOverride: String? = null,
  ): Map<String, Any?> = buildMap {
    put("messageId", message.messageId)
    put(
      "kind",
      when (message.role) {
        ChatTranscriptRole.USER -> "outbound"
        ChatTranscriptRole.ASSISTANT -> "inbound"
        ChatTranscriptRole.TOOL -> "timeline"
        ChatTranscriptRole.SYSTEM -> "timeline"
      },
    )
    put(
      "text",
      textOverride ?: message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty(),
    )
    put("meta", "")
    put("createdAtEpochMs", message.createdAtEpochMs)
    if (message.attachments.isNotEmpty()) {
      put("attachments", message.attachments.map(::chatAttachmentToMap))
    }
  }

  private fun chatMessageSnapshotMap(
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

  private fun pendingUserInputToMap(entry: PendingUserInputEntry): Map<String, Any?> = buildMap {
    put("messageId", entry.queueId)
    put("kind", "outbound")
    put("text", entry.text)
    put("meta", "")
    put("createdAtEpochMs", entry.createdAtEpochMs)
    put("isEphemeral", true)
    if (entry.attachments.isNotEmpty()) {
      put("attachments", entry.attachments.map(::chatAttachmentToMap))
    }
  }

  private fun pendingSupplementToMap(entry: MidLoopSupplementEntry): Map<String, Any?> = mapOf(
    "messageId" to entry.entryId,
    "kind" to "outbound",
    "text" to entry.text,
    "meta" to "",
    "createdAtEpochMs" to entry.createdAtEpochMs,
    "isEphemeral" to true,
  )

  private fun chatAttachmentToMap(attachment: ChatAttachmentEntry): Map<String, Any?> = buildMap {
    put("attachmentId", attachment.attachmentId)
    put("displayName", attachment.displayName)
    put("localPath", attachment.localPath)
    put("kind", attachment.kind.name.lowercase())
    attachment.mimeType?.let { mimeType -> put("mimeType", mimeType) }
    attachment.sizeBytes?.let { sizeBytes -> put("sizeBytes", sizeBytes.toInt()) }
    attachment.widthPx?.let { widthPx -> put("widthPx", widthPx) }
    attachment.heightPx?.let { heightPx -> put("heightPx", heightPx) }
    attachment.durationMs?.let { durationMs -> put("durationMs", durationMs.toInt()) }
    if (attachment.waveformBars.isNotEmpty()) {
      put("waveformBars", attachment.waveformBars)
    }
    attachment.transcriptText?.let { transcriptText -> put("transcriptText", transcriptText) }
    attachment.contentSha256?.let { contentSha256 -> put("contentSha256", contentSha256) }
  }

  private fun chatAttachmentSnapshotMap(
    attachment: ChatAttachmentEntry,
  ): Map<String, Any?> = buildMap {
    put("attachmentId", attachment.attachmentId)
    put(
      "kind",
      when (attachment.kind) {
        ChatAttachmentKind.IMAGE -> "image"
        ChatAttachmentKind.VOICE,
        ChatAttachmentKind.AUDIO,
        -> "voice"
        ChatAttachmentKind.FILE -> "file"
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

  private fun finalAttachmentsForRun(
    run: AgentRunSnapshot,
  ): List<ChatAttachmentEntry> {
    val pendingMessageId = run.pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return chatSessionStore.loadSession(run.sessionId)
      ?.messages
      ?.firstOrNull { message ->
        message.messageId == pendingMessageId && message.role == ChatTranscriptRole.ASSISTANT
      }
      ?.attachments
      .orEmpty()
  }

  private fun liveAssistantDraftToMap(
    draft: ProjectionLiveAssistantDraftSnapshot,
  ): Map<String, Any?> = mapOf(
    "runId" to draft.runId,
    "taskId" to draft.taskId,
    "executionId" to draft.executionId,
    "pendingMessageId" to draft.pendingMessageId,
    "text" to draft.text,
    "updatedAtEpochMs" to draft.updatedAtEpochMs,
  )

  private fun toolResultMetadataSnapshot(metadata: Map<String, String>): Map<String, String> {
    val hiddenKeys = setOf(
      "checkpointId",
      OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON,
      OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY,
      SubAgentApprovalResumeMetadata.KEY_PROMPT_RESUME_JSON,
    )
    return metadata.mapNotNull { (key, value) ->
      val normalizedKey = key.trim()
      val normalizedValue = value.trim()
      if (
        normalizedKey.isBlank() ||
        normalizedValue.isBlank() ||
        normalizedKey in hiddenKeys
      ) {
        null
      } else {
        normalizedKey to normalizedValue
      }
    }.toMap(linkedMapOf())
  }

  private fun toolResultDetailedContentSnapshot(result: AgentToolResult): String? {
    val content = result.content.trim().takeIf(String::isNotBlank) ?: return null
    return if (result.status == AgentToolResultStatus.SUCCESS) {
      content
    } else {
      content.take(MAX_PROJECTION_RUNTIME_EVENT_FAILURE_CONTENT_CHARS)
    }
  }

  private fun isDebugOnlyRuntimeEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCrayMemoryWriteEvent &&
      event.runId.startsWith(MEMORY_DEBUG_RUN_ID_PREFIX) &&
      event.taskId.startsWith(MEMORY_DEBUG_TASK_ID_PREFIX)

  private fun isInternalPromptCheckpointEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCraySupplementEvent &&
      event.checkpoint == INTERNAL_PROMPT_CHECKPOINT_MARKER

  private data class ProjectedRuntimeChatMessage(
    val anchorMessageId: String?,
    val sortEpochMs: Long,
    val sourceOrder: Int,
    val snapshot: Map<String, Any?>,
  ) {
    fun effectiveSortEpochMs(): Long =
      (snapshot["createdAtEpochMs"] as? Number)?.toLong()?.takeIf { createdAt -> createdAt > 0L }
        ?: sortEpochMs
  }

  private data class OrderedProjectedRuntimeChatMessage(
    val sortEpochMs: Long,
    val sourceOrder: Int,
    val message: ProjectedRuntimeChatMessage,
  ) {
    fun effectiveSortEpochMs(): Long = message.effectiveSortEpochMs()
  }

  private data class ProjectionLiveAssistantDraftSnapshot(
    val runId: String,
    val taskId: String,
    val executionId: String?,
    val pendingMessageId: String,
    val text: String,
    val updatedAtEpochMs: Long,
  )

  private data class ProjectionRuntimeSnapshot(
    val runs: List<AgentRunSnapshot>,
    val recentEvents: List<OpenCrayAgentRunEvent>,
    val liveAssistantDrafts: List<ProjectionLiveAssistantDraftSnapshot>,
    val updatedAtEpochMs: Long,
    val snapshot: Map<String, Any?>,
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

  private data class SubAgentActivitySnapshot(
    val parentRunId: String,
    val parentTaskId: String,
    val agentId: String?,
    val childSessionId: String?,
    val childRunId: String,
    val childTaskId: String,
    val label: String,
    val subagentType: String,
    val contextMode: String,
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
  )

  private companion object {
    private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_CHECKPOINT: Int = 1
    private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_LINK: Int = 2
    private const val DURABLE_SUBAGENT_SOURCE_PRIORITY_HANDLE: Int = 3
    private const val TOOL_GENERATED_SUPPLEMENT_ENTRY_ID_PREFIX: String = "tool-supplement-"
    private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "memory-debug-run"
    private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-task"
    private const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
    private const val PROJECTION_APPROVAL_REQUIRED_ERROR_CODE: String = "APPROVAL_REQUIRED"
    private const val PROJECTION_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE: String =
      "HIGH_RISK_APPROVAL_REQUIRED"
    private const val MAX_PROJECTION_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
    private const val MAX_PROJECTION_RUNTIME_EVENT_FAILURE_CONTENT_CHARS: Int = 16_384
    private const val MAX_MANAGED_PROCESS_OUTPUT_PREVIEW_CHARS: Int = 4_096
    private const val PROJECTION_RUN_WAIT_POLL_INTERVAL_MS: Long = 50L
    private const val DEFAULT_PROJECTION_POLL_INTERVAL_MS: Long = 350L
    private val HIDDEN_ASSISTANT_CHAT_STAGES: Set<String> = setOf(
      "draft",
      "llm_retry",
      "responses_recovery",
    )
  }

  private fun unavailable(operation: String): IllegalStateException = IllegalStateException(
    serviceOwnedGatewayUnavailableMessage(
      surface = "Chat runtime",
      operation = operation,
      connectionState = connectionStateProvider(),
    ),
  )
}

internal fun projectionOnlyOpenCrayChatRuntimeGateway(
  context: Context,
  connectionStateProvider: () -> RuntimeServiceConnectionState,
  projectionSnapshotProvider: () -> RuntimeServiceProjectionSnapshot? = { null },
  hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
): OpenCrayChatRuntimeGateway {
  val diagnosticsSource = ProjectionOnlyChatRuntimeDiagnosticsSource(
    hostLifecycleDescriptor = hostLifecycleDescriptor,
    connectionStateProvider = connectionStateProvider,
    localRuntimeServerStateProvider = {
      projectionSnapshotProvider()?.localRuntimeServerState
    },
    runtimeControllerLifecycleProvider = {
      projectionSnapshotProvider()?.runtimeControllerLifecycle
    },
    runtimeOwnerLifecycleProvider = { projectionSnapshotProvider()?.runtimeOwnerLifecycle },
    runtimeOwnerWorkSummaryProvider = {
      projectionSnapshotProvider()?.runtimeOwnerWorkSummary
    },
    serviceLifecycleProvider = { projectionSnapshotProvider()?.serviceLifecycle },
    serviceWorkStateProvider = { projectionSnapshotProvider()?.serviceWorkState },
    serviceKeepAliveStateProvider = {
      projectionSnapshotProvider()?.serviceKeepAliveState
    },
    ownerLeaseProvider = {
      projectionSnapshotProvider()?.runtimeServiceOwnerLease
    },
    interruptedRunRepairProvider = {
      projectionSnapshotProvider()?.lastInterruptedRunRepair
    },
  )
  return projectionOnlyOpenCrayChatRuntimeGateway(
    context = context,
    diagnosticsSource = diagnosticsSource,
  )
}

internal fun projectionOnlyOpenCrayChatRuntimeGateway(
  context: Context,
  diagnosticsSource: ProjectionOnlyChatRuntimeDiagnosticsSource,
  stringsProvider: (() -> ProjectionOnlyChatStrings)? = null,
  sessionUnreadCountProvider: ((String, String) -> Int)? = null,
): OpenCrayChatRuntimeGateway {
  val appContext = context.applicationContext
  val strings = stringsProvider?.invoke() ?: projectionOnlyChatStrings(appContext)
  return ProjectionOnlyOpenCrayChatRuntimeGateway(
    chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
    queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
    runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
    promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext),
    processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(
      context = appContext,
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    ),
    supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory.fromContext(appContext),
    subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(appContext),
    subAgentSessionLinkStoreFactory = FileBackedSubAgentSessionLinkStoreFactory.fromContext(
      appContext,
    ),
    strings = strings,
    stringsProvider = stringsProvider,
    connectionStateProvider = diagnosticsSource.connectionStateProvider,
    localRuntimeServerStateProvider = diagnosticsSource.localRuntimeServerStateProvider,
    personalizationLocalStore = PersonalizationLocalStore.fromContext(appContext),
    workspaceRootProvider = {
      AppAgentWorkspace.directoryForContext(appContext)
        .takeIf { directory -> directory.isDirectory }
        ?.toPath()
    },
    mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
    hostLifecycleDescriptor = diagnosticsSource.hostLifecycleDescriptor,
    runtimeControllerLifecycleProvider = diagnosticsSource.runtimeControllerLifecycleProvider,
    runtimeOwnerLifecycleProvider = diagnosticsSource.runtimeOwnerLifecycleProvider,
    runtimeOwnerWorkSummaryProvider = diagnosticsSource.runtimeOwnerWorkSummaryProvider,
    serviceLifecycleProvider = diagnosticsSource.serviceLifecycleProvider,
    serviceWorkStateProvider = diagnosticsSource.serviceWorkStateProvider,
    serviceKeepAliveStateProvider = diagnosticsSource.serviceKeepAliveStateProvider,
    ownerLeaseProvider = diagnosticsSource.ownerLeaseProvider,
    interruptedRunRepairProvider = diagnosticsSource.interruptedRunRepairProvider,
    sessionUnreadCountProvider = sessionUnreadCountProvider,
  )
}

internal fun projectionOnlyChatStrings(context: Context): ProjectionOnlyChatStrings {
  val appContext = context.applicationContext
  val localizedContext = OpenCrayLocaleManager.wrap(appContext)
  val hostStrings = localizedHostRuntimeStrings(localizedContext)
  return ProjectionOnlyChatStrings(
    localeTag = LocaleSettingsStore.fromContext(appContext).loadLanguage().tag,
    screenTitle = localizedContext.getString(R.string.shell_tab_chat),
    modeLabel = localizedContext.getString(R.string.chat_mode_auto),
    sessionButtonLabel = localizedContext.getString(R.string.chat_sessions_button),
    recentSessionsEyebrow = localizedContext.getString(R.string.chat_recent_sessions_eyebrow),
    recentSessionsTitle = localizedContext.getString(R.string.chat_recent_sessions_title),
    newSessionLabel = localizedContext.getString(R.string.chat_new_session),
    defaultSessionTitle = localizedContext.getString(R.string.chat_default_session_title),
    messagesBadge = { count -> localizedContext.getString(R.string.chat_messages_badge, count) },
    summaryReplyInProgress = localizedContext.getString(R.string.chat_summary_reply_in_progress),
    summaryStartNewSession = localizedContext.getString(R.string.chat_summary_start_new_session),
    summaryRestored = localizedContext.getString(R.string.chat_summary_restored),
    summaryApprovalRequired = localizedContext.getString(
      R.string.chat_summary_approval_required,
    ),
    approvalRequiredTitle = localizedContext.getString(
      R.string.chat_approval_required_title,
    ),
    highRiskApprovalRequiredTitle = localizedContext.getString(
      R.string.chat_high_risk_approval_required_title,
    ),
    highRiskApprovalRequiredBody = localizedContext.getString(
      R.string.chat_high_risk_approval_required_body,
    ),
    approvalApproveLabel = localizedContext.getString(
      R.string.chat_approval_approve_label,
    ),
    approvalApproveForSessionLabel = localizedContext.getString(
      R.string.chat_approval_approve_for_session_label,
    ),
    approvalRejectLabel = localizedContext.getString(
      R.string.chat_approval_reject_label,
    ),
    summaryAwaitingDirection = localizedContext.getString(
      R.string.chat_summary_awaiting_direction,
    ),
    composerPlaceholder = localizedContext.getString(R.string.chat_message_opencray),
    composerRejectedPlaceholder = localizedContext.getString(
      R.string.chat_message_opencray_do_differently,
    ),
    agentThinking = hostStrings.agentThinking,
    agentCancelled = hostStrings.agentCancelled,
    agentMissingLlm = hostStrings.agentMissingLlm,
    agentEmptyAnswer = hostStrings.agentEmptyAnswer,
    agentInternalPayloadHidden = hostStrings.agentInternalPayloadHidden,
    agentFailed = hostStrings.agentFailed,
  )
}
