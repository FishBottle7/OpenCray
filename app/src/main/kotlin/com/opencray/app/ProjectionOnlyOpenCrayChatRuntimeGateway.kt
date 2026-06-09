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
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
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
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.memory.MemoryOperator
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryOperatorRequest
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
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
  private val resolvedStrings: ProjectionOnlyChatStrings
    get() = stringsProvider?.invoke() ?: strings

  private val debugProjector = ProjectionOnlyChatDebugProjector(
    personalizationLocalStore = personalizationLocalStore,
    workspaceRootProvider = workspaceRootProvider,
    workspaceSoulProfileStore = workspaceSoulProfileStore,
    clock = clock,
  )

  override fun loadChatSnapshot(): Map<String, Any?> = loadProjectionChatSnapshot()

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
    val messageCount = visibleMessages.size
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
        visibleMessages.map(::chatMessageToMap) +
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
      .takeLast(MAX_PROJECTION_RUNTIME_EVENT_HISTORY)
    val subAgentSnapshots = subAgentSnapshotsForActivity(
      sessionId = sessionId,
      displayedRuns = visibleRuns,
      recentEvents = recentEvents,
    )
    return ProjectionRuntimeSnapshot(
      runs = visibleRuns,
      snapshot = buildMap {
        put("sessionId", sessionId)
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
      val associatedProcesses = associatedManagedProcesses(
        taskId = taskId,
        existingIds = record?.managedProcessIds.orEmpty(),
        managedProcessesById = managedProcessesById,
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
        managedProcessIds = associatedProcesses.map(ManagedProcessSnapshot::processId).distinct(),
        runningManagedProcessCount = associatedProcesses.count { snapshot ->
          snapshot.status == ManagedProcessStatus.RUNNING
        },
        hasLiveManagedProcesses = associatedProcesses.any { snapshot ->
          snapshot.status == ManagedProcessStatus.RUNNING
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
      return if (
        run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) != null &&
        run.isActive
      ) {
        emptyList()
      } else {
        runEvents
      }
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
      return run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) == null || !run.isActive
    }
    return event.executionId?.trim() == currentExecutionId
  }

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
        summary = latestEvent.summary,
        startedAtEpochMs = firstEvent.emittedAtEpochMs,
        updatedAtEpochMs = latestEvent.emittedAtEpochMs,
        eventCount = accumulator.eventCount,
        mailboxMessageCount = 0,
        mailboxPendingMessageCount = 0,
        mailboxLastDeliveredMessageId = null,
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
            mailboxMessageCount = snapshot.mailboxMessageCount,
            mailboxPendingMessageCount = snapshot.mailboxPendingMessageCount,
            mailboxLastDeliveredMessageId = snapshot.mailboxLastDeliveredMessageId,
          )
        }
      }
    return eventSnapshotsByKey.values.toList()
  }

  private fun subAgentRegistryKey(
    event: OpenCraySubAgentEvent,
  ): String = listOf(
    event.runId,
    event.childRunId.trim().takeIf(String::isNotBlank)
      ?: event.childTaskId.trim().takeIf(String::isNotBlank)
      ?: event.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentRegistryKey(
    snapshot: SubAgentActivitySnapshot,
  ): String = listOf(
    snapshot.parentRunId,
    snapshot.childRunId.trim().takeIf(String::isNotBlank)
      ?: snapshot.childTaskId.trim().takeIf(String::isNotBlank)
      ?: snapshot.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentSnapshotsFromDurableSources(
    sessionId: String,
  ): List<SubAgentActivitySnapshot> {
    val latestByKey = linkedMapOf<String, SubAgentActivitySnapshot>()
    subAgentHandleStoreFactory.forChatSession(sessionId)
      .list()
      .forEach { handle ->
        val snapshot = subAgentActivitySnapshot(handle)
        val key = subAgentRegistryKey(snapshot)
        val existing = latestByKey[key]
        if (existing == null || snapshot.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          latestByKey[key] = snapshot
        }
      }
    promptCheckpointStoreFactory.forChatSession(sessionId)
      .list()
      .asReversed()
      .forEach { checkpoint ->
        checkpointSubAgentHandles(checkpoint).forEach { handle ->
          val snapshot = subAgentActivitySnapshot(handle)
          val key = subAgentRegistryKey(snapshot)
          val existing = latestByKey[key]
          if (existing == null || snapshot.updatedAtEpochMs >= existing.updatedAtEpochMs) {
            latestByKey[key] = snapshot
          }
        }
      }
    return latestByKey.values.toList()
  }

  private fun checkpointSubAgentHandles(
    checkpoint: PersistedPromptCheckpoint,
  ): Sequence<SubAgentHandleState> = sequenceOf(
    checkpoint.promptResumeState,
    checkpoint.subAgentPromptResumeState,
  ).filterNotNull().flatMap { state -> state.subAgentHandles.asSequence() }

  private fun subAgentActivitySnapshot(
    handle: SubAgentHandleState,
  ): SubAgentActivitySnapshot {
    val mailbox = handle.normalizedMailbox()
    return SubAgentActivitySnapshot(
      parentRunId = handle.parentRunId,
      parentTaskId = handle.parentTaskId,
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
      summary = handle.snapshot.headline,
      startedAtEpochMs = handle.createdAtEpochMs,
      updatedAtEpochMs = handle.updatedAtEpochMs,
      eventCount = 0,
      mailboxMessageCount = mailbox.messages.size,
      mailboxPendingMessageCount = mailbox.pendingMessages().size,
      mailboxLastDeliveredMessageId = mailbox.lastDeliveredMessageId,
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
    "summary" to snapshot.summary,
    "startedAtEpochMs" to snapshot.startedAtEpochMs,
    "updatedAtEpochMs" to snapshot.updatedAtEpochMs,
    "eventCount" to snapshot.eventCount,
    "mailboxMessageCount" to snapshot.mailboxMessageCount,
    "mailboxPendingMessageCount" to snapshot.mailboxPendingMessageCount,
    "mailboxLastDeliveredMessageId" to snapshot.mailboxLastDeliveredMessageId,
  )

  private fun retainedRunsFor(runs: List<AgentRunSnapshot>): List<AgentRunSnapshot> {
    val latest = latestRunFor(runs) ?: return emptyList()
    return if (
      latest.isTerminal &&
      !latest.hasLiveManagedProcesses &&
      (isAwaitingDirectionRun(latest) || isInterruptedOnRestoreRun(latest))
    ) {
      listOf(latest)
    } else {
      emptyList()
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
    var latestPayload = payloadProvider()
    mainThreadPoster.post {
      listener(latestPayload)
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
      pollIntervalMs.coerceAtLeast(1L),
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
  ): List<ManagedProcessSnapshot> = associatedManagedProcessesForProjection(
    taskId = taskId,
    existingIds = existingIds,
    managedProcessesById = managedProcessesById,
  )

  private fun projectedLifecycleState(
    original: QueueTaskLifecycleState?,
    result: ExecutionResult?,
  ): QueueTaskLifecycleState? = projectedLifecycleStateForRestoreResult(
    original = original,
    result = result,
  )

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? = projectedTaskStateForRestoreResult(
    original = original,
    result = result,
  )

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

  private fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> = buildMap {
    put("sessionId", run.sessionId)
    put("runId", run.runId)
    put("taskId", run.taskId)
    put("acceptedAtEpochMs", run.acceptedAtEpochMs)
    put("updatedAtEpochMs", run.updatedAtEpochMs)
    put("lifecycleState", run.lifecycleState?.name?.lowercase())
    put("taskState", run.taskState?.name?.lowercase())
    put("attempt", run.attempt)
    put("executionOrdinal", run.executionOrdinal)
    put("executionId", run.executionId)
    put("executionKind", run.executionKind)
    put("pendingExecutionKind", run.pendingExecutionKind)
    put("executionStatus", run.executionStatus?.name?.lowercase())
    put("errorCode", run.errorCode)
    put("errorMessage", run.errorMessage)
    put("responseFormat", run.responseFormat)
    put("pendingMessageId", run.pendingMessageId)
    put("isTerminal", run.isTerminal)
    run.lastEvent?.let { event ->
      put("lastEvent", runtimeEventToMap(event))
    }
    if (!run.lifecycleDiagnostics.isEmpty) {
      put("diagnostics", run.lifecycleDiagnostics.toMap())
    }
    recoveryPlanForRun(run)?.let { recoveryPlan ->
      put("recoveryPlan", recoveryPlan.toMap())
    }
  }

  private fun recoveryPlanForRun(run: AgentRunSnapshot): RunRecoveryPlan? =
    loadStoredRunRecoveryPlan(
      run = run,
      checkpointStore = promptCheckpointStoreFactory.forChatSession(run.sessionId),
      journalStore = runEventJournalStoreFactory.forChatSession(run.sessionId),
      planner = recoveryPlanner,
    )

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
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
      "content" to if (event.result.status == AgentToolResultStatus.SUCCESS) {
        null
      } else {
        event.result.content.trim().takeIf(String::isNotBlank)?.take(MAX_PROJECTION_RUNTIME_EVENT_FAILURE_CONTENT_CHARS)
      },
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
      "reaffirmedRecordIds" to event.reaffirmedRecordIds,
      "expiredRecordIds" to event.expiredRecordIds,
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

  private fun chatMessageToMap(message: ChatTranscriptMessageEntry): Map<String, Any?> = buildMap {
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
      message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty(),
    )
    put("meta", "")
    put("createdAtEpochMs", message.createdAtEpochMs)
    if (message.attachments.isNotEmpty()) {
      put("attachments", message.attachments.map(::chatAttachmentToMap))
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

  private fun isDebugOnlyRuntimeEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCrayMemoryWriteEvent &&
      event.runId.startsWith(MEMORY_DEBUG_RUN_ID_PREFIX) &&
      event.taskId.startsWith(MEMORY_DEBUG_TASK_ID_PREFIX)

  private fun isInternalPromptCheckpointEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCraySupplementEvent &&
      event.checkpoint == INTERNAL_PROMPT_CHECKPOINT_MARKER

  private data class ProjectionRuntimeSnapshot(
    val runs: List<AgentRunSnapshot>,
    val snapshot: Map<String, Any?>,
  )

  private data class SubAgentActivityAccumulator(
    val firstEvent: OpenCraySubAgentEvent,
    val latestEvent: OpenCraySubAgentEvent,
    val eventCount: Int,
  )

  private data class SubAgentActivitySnapshot(
    val parentRunId: String,
    val parentTaskId: String,
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
    val summary: String?,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val eventCount: Int,
    val mailboxMessageCount: Int,
    val mailboxPendingMessageCount: Int,
    val mailboxLastDeliveredMessageId: String?,
  )

  private companion object {
    private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "memory-debug-run"
    private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-task"
    private const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
    private const val PROJECTION_APPROVAL_REQUIRED_ERROR_CODE: String = "APPROVAL_REQUIRED"
    private const val PROJECTION_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE: String =
      "HIGH_RISK_APPROVAL_REQUIRED"
    private const val MAX_PROJECTION_RUNTIME_EVENT_HISTORY: Int = 24
    private const val MAX_PROJECTION_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
    private const val MAX_PROJECTION_RUNTIME_EVENT_FAILURE_CONTENT_CHARS: Int = 16_384
    private const val PROJECTION_RUN_WAIT_POLL_INTERVAL_MS: Long = 50L
    private const val DEFAULT_PROJECTION_POLL_INTERVAL_MS: Long = 350L
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
  )
}
