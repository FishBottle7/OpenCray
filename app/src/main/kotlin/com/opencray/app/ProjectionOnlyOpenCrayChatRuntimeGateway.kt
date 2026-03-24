package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.file.Path
import java.util.Timer
import java.util.TimerTask
import org.opencray.app.R

internal data class ProjectionOnlyChatStrings(
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
  val composerPlaceholder: String,
  val composerRejectedPlaceholder: String,
)

internal class ProjectionOnlyOpenCrayChatRuntimeGateway(
  private val chatSessionStore: ChatSessionLocalStore,
  private val queueSnapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val runRecordStoreFactory: AgentRunRecordStoreFactory,
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  private val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  private val processRegistryFactory: AgentProcessRegistryFactory,
  private val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  private val strings: ProjectionOnlyChatStrings,
  private val connectionStateProvider: () -> RuntimeServiceConnectionState,
  private val personalizationLocalStore: PersonalizationLocalStore? = null,
  private val workspaceRootProvider: (() -> Path?)? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  private val runtimeOwnerLifecycleProvider: () -> HostRuntimeLifecycleDescriptor? = { null },
  private val runtimeOwnerWorkSummaryProvider: () -> RuntimeOwnerWorkSummary? = { null },
  private val serviceLifecycleProvider: () -> RuntimeServiceLifecycleDescriptor? = { null },
  private val serviceWorkStateProvider: () -> RuntimeServiceWorkState? = { null },
  private val serviceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  private val clock: () -> Long = System::currentTimeMillis,
  private val pollIntervalMs: Long = DEFAULT_PROJECTION_POLL_INTERVAL_MS,
) : OpenCrayChatRuntimeGateway {
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

  private fun activeSessionId(): String = chatSessionStore.loadState().activeSession.sessionId

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    debugProjector.loadMemoryDebugSnapshot(activeSessionId())

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> {
    val sessions = chatSessionStore.loadState().sessions
    val activeSessionId = activeSessionId()
    val allRuns = sessions.flatMap { session -> loadRunSnapshots(session.sessionId) }
    val runtimeEventsBySession = sessions.associate { session ->
      session.sessionId to runEventJournalStoreFactory.forChatSession(session.sessionId).listRuntimeEvents()
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
  ): Map<String, Any?> = throw unavailable("applyMemoryDebugAction")

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

  override fun rejectChatApproval(taskIdOrRunId: String) =
    throw unavailable("rejectChatApproval")

  override fun cancelChatRun(taskIdOrRunId: String) =
    throw unavailable("cancelChatRun")

  override fun retryChatRun(taskIdOrRunId: String) =
    throw unavailable("retryChatRun")

  private val recoveryPlanner = RunRecoveryPlanner()

  private fun loadProjectionChatSnapshot(): Map<String, Any?> {
    val state = chatSessionStore.loadState()
    val activeSession = state.activeSession
    val runtimeProjection = runtimeProjectionForSession(activeSession.sessionId)
    val visibleMessages = activeSession.messages.filter { message ->
      message.role != ChatTranscriptRole.SYSTEM
    }
    val messageCount = visibleMessages.size
    return mapOf(
      "screenTitle" to strings.screenTitle,
      "modeLabel" to strings.modeLabel,
      "sessionButtonLabel" to strings.sessionButtonLabel,
      "composerPlaceholder" to composerPlaceholderFor(runtimeProjection.runs),
      "summary" to mapOf(
        "title" to displaySessionTitle(activeSession.title),
        "badge" to strings.messagesBadge(messageCount),
        "body" to summaryBody(messageCount, runtimeProjection.runs),
      ),
      "messages" to (
        visibleMessages.map(::chatMessageToMap) +
          chatSessionStore.loadPendingUserInputs(activeSession.sessionId).map(::pendingUserInputToMap) +
          supplementStoreFactory.forChatSession(activeSession.sessionId).snapshot().map(::pendingSupplementToMap)
        ),
      "drawer" to mapOf(
        "eyebrow" to strings.recentSessionsEyebrow,
        "title" to strings.recentSessionsTitle,
        "ctaLabel" to strings.newSessionLabel,
        "sessions" to state.sessions.map { session ->
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to session.lastMessagePreview,
            "meta" to strings.messagesBadge(session.messageCount),
            "isSelected" to (session.sessionId == activeSession.sessionId),
            "lastMessageAtEpochMs" to session.lastMessageAtEpochMs,
            "unreadCount" to 0,
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
        loadRunSnapshots(sessionId).firstOrNull { snapshot -> snapshot.runId == runId }
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
    val events = runEventJournalStoreFactory.forChatSession(sessionId)
      .listRuntimeEvents()
      .filterNot(::isDebugOnlyRuntimeEvent)
      .takeLast(MAX_PROJECTION_RUNTIME_EVENT_HISTORY)
      .map(::runtimeEventToMap)
    return ProjectionRuntimeSnapshot(
      runs = runs,
      snapshot = buildMap {
        put("sessionId", sessionId)
        put("hostLifecycle", hostLifecycleDescriptor.snapshotMap())
        runtimeOwnerLifecycleProvider()?.snapshotMap()?.let { lifecycle ->
          put("runtimeOwnerLifecycle", lifecycle)
        }
        runtimeOwnerWorkSummaryProvider()?.snapshotMap()?.let { summary ->
          put("runtimeOwnerWorkSummary", summary)
        }
        serviceLifecycleProvider()?.snapshotMap()?.let { lifecycle ->
          put("runtimeServiceLifecycle", lifecycle)
        }
        serviceWorkStateProvider()?.snapshotMap()?.let { workState ->
          put("runtimeServiceWorkState", workState)
        }
        serviceKeepAliveStateProvider()?.snapshotMap()?.let { keepAliveState ->
          put("runtimeServiceKeepAliveState", keepAliveState)
        }
        put("runtimeServiceConnectionState", connectionStateProvider().snapshotMap())
        put("activeRuns", runs.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap))
        put("retainedRuns", retainedRunsFor(runs).map(::runSnapshotToMap))
        put("events", events)
      },
    )
  }

  private fun loadRunSnapshots(sessionId: String): List<AgentRunSnapshot> {
    val runRecordStore = runRecordStoreFactory.forChatSession(sessionId)
    val journalStore = runEventJournalStoreFactory.forChatSession(sessionId)
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    val processRegistry = processRegistryFactory.forChatSession(sessionId)
    val queueStore = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = queueSnapshotStoreFactory.forChatSession(sessionId),
      runRecordStore = runRecordStore,
      runEventJournalStore = journalStore,
      promptCheckpointStore = checkpointStore,
      managedProcessesProvider = processRegistry::list,
      clock = clock,
    )
    val taskSnapshotsByRunId = queueStore.load()
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
      val lastEvent = journalStore.listForRun(runId).lastOrNull()?.payload?.toRuntimeEvent()
        ?: record?.lastEvent?.toRuntimeEvent()
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
    if (record == null || taskSnapshot == null || isTerminalLifecycle(taskSnapshot.lifecycleState)) {
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
    val runEvents = runEventJournalStoreFactory.forChatSession(sessionId)
      .listRuntimeEvents()
      .filter { event -> event.runId == run.runId }
    val latest = runEvents.lastOrNull() ?: return run.lastEvent
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
  ): String = when {
    runs.any(AgentRunSnapshot::isActive) -> strings.summaryReplyInProgress
    messageCount == 0 -> strings.summaryStartNewSession
    else -> strings.summaryRestored
  }

  private fun composerPlaceholderFor(runs: List<AgentRunSnapshot>): String = if (
    latestRunFor(runs)?.let(::isAwaitingDirectionRun) == true
  ) {
    strings.composerRejectedPlaceholder
  } else {
    strings.composerPlaceholder
  }

  private fun isAwaitingDirectionRun(run: AgentRunSnapshot): Boolean =
    (run.lastEvent as? OpenCrayApprovalEvent)?.phase == OpenCrayApprovalPhase.REJECTED ||
      (run.lastEvent as? OpenCrayCancellationEvent)?.outcome == "user_cancelled"

  private fun isInterruptedOnRestoreRun(run: AgentRunSnapshot): Boolean =
    run.errorCode == com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      run.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE

  private fun displaySessionTitle(rawTitle: String): String =
    if (rawTitle == ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
      strings.defaultSessionTitle
    } else {
      rawTitle
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
  ): List<ManagedProcessSnapshot> = (
    existingIds +
      managedProcessesById.values
        .asSequence()
        .filter { snapshot -> snapshot.taskId == taskId }
        .map(ManagedProcessSnapshot::processId)
        .toList()
    ).distinct().mapNotNull(managedProcessesById::get)

  private fun projectedLifecycleState(
    original: QueueTaskLifecycleState?,
    result: ExecutionResult?,
  ): QueueTaskLifecycleState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalLifecycle(original))
  ) {
    QueueTaskLifecycleState.FAILED
  } else {
    original
  }

  private fun projectedTaskState(
    original: AgentTaskState?,
    result: ExecutionResult?,
  ): AgentTaskState? = if (
    isInterruptedOnRestoreResult(result) &&
    (original == null || !isTerminalTaskState(original))
  ) {
    AgentTaskState.FAILED
  } else {
    original
  }

  private fun visibleRunResult(
    taskSnapshot: SessionQueueTaskSnapshot?,
    result: ExecutionResult?,
  ): ExecutionResult? {
    if (taskSnapshot == null || result == null) {
      return result
    }
    if (isInterruptedOnRestoreResult(result)) {
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

  private fun shouldShowTaskSnapshotError(taskSnapshot: SessionQueueTaskSnapshot?): Boolean =
    when (taskSnapshot?.lifecycleState) {
      QueueTaskLifecycleState.SUSPENDED,
      QueueTaskLifecycleState.FAILED,
      QueueTaskLifecycleState.CANCELLED,
      -> true

      else -> false
    }

  private fun isInterruptedOnRestoreResult(result: ExecutionResult?): Boolean =
    result?.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun isTerminalLifecycle(state: QueueTaskLifecycleState): Boolean = when (state) {
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

  private fun isTerminalTaskState(state: AgentTaskState): Boolean = when (state) {
    AgentTaskState.COMPLETED,
    AgentTaskState.CANCELLED,
    AgentTaskState.FAILED,
    -> true

    AgentTaskState.QUEUED,
    AgentTaskState.RUNNING,
    AgentTaskState.SUSPENDED,
    -> false
  }

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
    errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      errorCode == com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun ManagedProcessSnapshot.isProjectionTerminalAfterRestore(): Boolean = status.isTerminal

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
  }

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
    is OpenCrayLifecycleEvent -> mapOf(
      "kind" to "lifecycle",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.status?.name?.lowercase(),
      "errorCode" to event.errorCode,
      "errorMessage" to event.errorMessage,
    )
    is OpenCrayAssistantEvent -> mapOf(
      "kind" to "assistant",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "responseFormat" to event.responseFormat,
      "isFinal" to event.isFinal,
      "text" to event.text,
    )
    is OpenCrayProgressEvent -> mapOf(
      "kind" to "progress",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "text" to event.text,
      "stage" to event.stage,
    )
    is OpenCraySupplementEvent -> mapOf(
      "kind" to "supplement",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "entryId" to event.entryId,
      "text" to event.text,
      "checkpoint" to event.checkpoint,
    )
    is OpenCrayApprovalEvent -> mapOf(
      "kind" to if (event.phase == OpenCrayApprovalPhase.REQUIRED) "approval_wait" else "approval_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
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
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolStatus" to event.result.status.name.lowercase(),
      "errorCode" to event.result.errorCode,
      "errorMessage" to event.result.errorMessage,
      "contentPreview" to event.result.content.take(MAX_PROJECTION_RUNTIME_EVENT_PREVIEW_CHARS),
      "resultMetadata" to toolResultMetadataSnapshot(event.result.metadata),
    )
    is OpenCrayMemoryRetrievalEvent -> mapOf(
      "kind" to "memory_retrieval",
      "runId" to event.runId,
      "taskId" to event.taskId,
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
      "kind" to "cancelled",
      "runId" to event.runId,
      "taskId" to event.taskId,
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

  private fun toolResultMetadataSnapshot(metadata: Map<String, String>): Map<String, String> =
    metadata.mapNotNull { (key, value) ->
      val normalizedKey = key.trim()
      val normalizedValue = value.trim()
      if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
        null
      } else {
        normalizedKey to normalizedValue
      }
    }.toMap(linkedMapOf())

  private fun isDebugOnlyRuntimeEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCrayMemoryWriteEvent &&
      event.runId.startsWith(MEMORY_DEBUG_RUN_ID_PREFIX) &&
      event.taskId.startsWith(MEMORY_DEBUG_TASK_ID_PREFIX)

  private data class ProjectionRuntimeSnapshot(
    val runs: List<AgentRunSnapshot>,
    val snapshot: Map<String, Any?>,
  )

  private companion object {
    private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "memory-debug-run"
    private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-task"
    private const val MAX_PROJECTION_RUNTIME_EVENT_HISTORY: Int = 24
    private const val MAX_PROJECTION_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
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
  bridgeSnapshotProvider: () -> OpenCrayRuntimeServiceBridgeSnapshot? = { null },
): OpenCrayChatRuntimeGateway {
  val appContext = context.applicationContext
  val localizedContext = OpenCrayLocaleManager.wrap(appContext)
  return ProjectionOnlyOpenCrayChatRuntimeGateway(
    chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
    queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
    runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
    promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext),
    processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(appContext),
    supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory.fromContext(appContext),
    strings = ProjectionOnlyChatStrings(
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
      composerPlaceholder = localizedContext.getString(R.string.chat_message_opencray),
      composerRejectedPlaceholder = localizedContext.getString(
        R.string.chat_message_opencray_do_differently,
      ),
    ),
    connectionStateProvider = connectionStateProvider,
    personalizationLocalStore = PersonalizationLocalStore.fromContext(appContext),
    workspaceRootProvider = {
      AppAgentWorkspace.directoryForContext(appContext)
        .takeIf { directory -> directory.isDirectory }
        ?.toPath()
    },
    mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
    runtimeOwnerLifecycleProvider = {
      bridgeSnapshotProvider()?.runtimeAccess?.lifecycleDescriptor
        ?: OpenCrayRuntimeServiceHostRegistry.peek()?.runtimeAccess?.lifecycleDescriptor
    },
    runtimeOwnerWorkSummaryProvider = {
      bridgeSnapshotProvider()?.runtimeAccess?.hostAccess?.activeWorkSummary()
        ?: OpenCrayRuntimeServiceHostRegistry.peek()?.runtimeAccess?.hostAccess?.activeWorkSummary()
    },
    serviceLifecycleProvider = {
      bridgeSnapshotProvider()?.serviceLifecycle
        ?: OpenCrayRuntimeServiceHostRegistry.peek()?.serviceLifecycle
    },
    serviceWorkStateProvider = {
      bridgeSnapshotProvider()?.serviceWorkState
        ?: OpenCrayRuntimeServiceHostRegistry.peek()?.serviceWorkStateTracker?.currentState()
    },
    serviceKeepAliveStateProvider = {
      bridgeSnapshotProvider()?.serviceKeepAliveState
    },
  )
}
