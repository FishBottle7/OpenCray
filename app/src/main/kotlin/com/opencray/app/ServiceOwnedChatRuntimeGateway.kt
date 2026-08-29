package com.opencray.app

import android.util.Log
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID

private const val SERVICE_CHAT_DEBUG_TAG: String = "OpenCrayDiag"
private const val SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS: Long = 350L

private fun serviceChatDebug(message: String) {
  runCatching { Log.d(SERVICE_CHAT_DEBUG_TAG, message) }
}

internal class ServiceOwnedChatRuntimeGateway(
  private val delegate: OpenCrayChatRuntimeGateway? = null,
  private val readGateway: OpenCrayChatRuntimeGateway,
  private val snapshotNotifier: () -> Unit = {},
  private val runtimeHostAccess: RuntimeOwnerObservationAccess? = null,
  private val onDeviceWarmupAccess: OnDeviceLlmWarmupAccess = NoOpOnDeviceLlmWarmupAccess,
  private val onDevicePreparingPlaceholder: String = "Preparing on-device model",
  private val chatSessionMutationAccess: ServiceOwnedChatSessionMutationAccess? = null,
  private val chatRunControlAccess: ServiceOwnedChatRunControlAccess? = null,
  private val chatApprovalAccess: ServiceOwnedChatApprovalAccess? = null,
  private val chatSubmissionAccess: ServiceOwnedChatSubmissionAccess? = null,
  private val refreshSandboxSessionInfoHandler: (() -> Unit)? = null,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCrayRuntimeServiceChatGateway {
  private val lock = Any()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private var disposed: Boolean = false
  private val liveAssistantDraftEventListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val runtimeEventDeltaListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val runtimeEventStreamInstanceId: String = lifecycleId(prefix = "service-runtime-stream")
  private val runtimeEventDeltaSequencesBySession = linkedMapOf<String, Long>()
  private var latestChatPayload: Map<String, Any?> = emptyMap()
  private val liveAssistantDraftsBySession =
    linkedMapOf<String, LinkedHashMap<String, ServiceOwnedLiveAssistantDraftSnapshot>>()
  private var latestChatRuntimePayload: Map<String, Any?> = emptyMap()

  init {
    latestChatPayload = currentChatPayload()
    latestChatRuntimePayload =
      decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())
  }

  @Suppress("unused")
  private val chatObservationDisposer = if (delegate == null) {
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { readGateway.loadChatSnapshot() },
      listener = { payload ->
        emitChatPayload(chatPayloadForEmission(decorateChatPayload(payload)))
      },
      pollIntervalMs = SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS,
    )
  } else {
    chatSnapshotGateway().observeChat { payload ->
      emitChatPayload(chatPayloadForEmission(decorateChatPayload(payload)))
    }
  }

  @Suppress("unused")
  private val chatRuntimeObservationDisposer = if (delegate == null) {
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { readGateway.loadChatRuntimeSnapshot() },
      listener = { payload ->
        emitChatRuntimePayload(decorateChatRuntimePayload(payload))
      },
      pollIntervalMs = SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS,
    )
  } else {
    runtimeSnapshotGateway().observeChatRuntime { payload ->
      emitChatRuntimePayload(decorateChatRuntimePayload(payload))
    }
  }

  @Suppress("unused")
  private val runtimeObservationDisposer = runtimeHostAccess?.observe(
    object : AgentSessionRuntimeListener {
      override fun onTaskStarted(sessionId: String, task: com.opencray.core.contracts.AgentTask) {
        emitChatSnapshot()
        if (!emitServiceOwnedRuntimeEventDelta()) {
          emitChatRuntimeSnapshot()
        }
      }

      override fun onRunEvent(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        event: com.opencray.runtime.OpenCrayAgentRunEvent,
      ) {
        emitChatSnapshot()
        if (!emitServiceOwnedRuntimeEventDelta()) {
          emitChatRuntimeSnapshot()
        }
      }

      override fun onAssistantDraftUpdated(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        text: String,
        emittedAtEpochMs: Long,
      ) {
        val draftEventPayload = synchronized(lock) {
          if (
            !updateLiveAssistantDraftLocked(
              sessionId = sessionId,
              task = task,
              text = text,
              emittedAtEpochMs = emittedAtEpochMs,
            )
          ) {
            return@synchronized null
          }
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          liveAssistantDraftsBySession[sessionId]
            ?.get(pendingMessageId)
            ?.toLiveAssistantDraftEventPayload(sessionId = sessionId, cleared = false)
            ?.let { payload -> assignRuntimeRealtimeEnvelopeLocked(sessionId, payload) }
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.draftUpdated session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} len=${text.length}",
          )
          val hasRuntimeEventDeltaListeners = synchronized(lock) {
            runtimeEventDeltaListeners.isNotEmpty()
          }
          val emittedRuntimeDelta = if (hasRuntimeEventDeltaListeners) {
            emitServiceOwnedRuntimeEventDelta(realtimeEnvelope = draftEventPayload)
          } else {
            false
          }
          if (!hasRuntimeEventDeltaListeners && !emittedRuntimeDelta) {
            emitChatRuntimeSnapshot()
          }
          emitLiveAssistantDraftEvent(draftEventPayload)
        }
      }

      override fun onAssistantDraftCleared(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        emittedAtEpochMs: Long,
      ) {
        val draftEventPayload = synchronized(lock) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          if (
            !clearLiveAssistantDraftLocked(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
          ) {
            return@synchronized null
          }
          liveAssistantDraftEventPayload(
            sessionId = sessionId,
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: "",
            taskId = task.id,
            executionId = task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank),
            pendingMessageId = pendingMessageId,
            text = "",
            updatedAtEpochMs = emittedAtEpochMs,
            cleared = true,
          ).let { payload -> assignRuntimeRealtimeEnvelopeLocked(sessionId, payload) }
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.draftCleared session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"}",
          )
          val hasRuntimeEventDeltaListeners = synchronized(lock) {
            runtimeEventDeltaListeners.isNotEmpty()
          }
          val emittedRuntimeDelta = if (hasRuntimeEventDeltaListeners) {
            emitServiceOwnedRuntimeEventDelta(realtimeEnvelope = draftEventPayload)
          } else {
            false
          }
          if (!hasRuntimeEventDeltaListeners && !emittedRuntimeDelta) {
            emitChatRuntimeSnapshot()
          }
          emitLiveAssistantDraftEvent(draftEventPayload)
        }
      }

      override fun onTaskFinished(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        result: com.opencray.core.contracts.ExecutionResult,
      ) {
        val draftEventPayload = synchronized(lock) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          if (
            !clearLiveAssistantDraftLocked(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
          ) {
            return@synchronized null
          }
          liveAssistantDraftEventPayload(
            sessionId = sessionId,
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: "",
            taskId = task.id,
            executionId = task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank),
            pendingMessageId = pendingMessageId,
            text = "",
            updatedAtEpochMs = result.finishedAtEpochMs,
            cleared = true,
          ).let { payload -> assignRuntimeRealtimeEnvelopeLocked(sessionId, payload) }
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.taskFinishedClearedDraft session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} status=${result.status} error=${result.errorCode ?: "-"}",
          )
        }
        emitChatSnapshot()
        val hasRuntimeEventDeltaListeners = synchronized(lock) {
          runtimeEventDeltaListeners.isNotEmpty()
        }
        if (hasRuntimeEventDeltaListeners) {
          if (!emitServiceOwnedRuntimeEventDelta(realtimeEnvelope = draftEventPayload)) {
            emitChatRuntimeSnapshot()
          }
        } else {
          draftEventPayload?.let(::emitLiveAssistantDraftEvent)
          emitChatRuntimeSnapshot()
        }
        snapshotNotifier()
      }
    },
  )

  private data class ServiceOwnedLiveAssistantDraftSnapshot(
    val runId: String,
    val taskId: String,
    val executionId: String?,
    val pendingMessageId: String,
    val text: String,
    val updatedAtEpochMs: Long,
  )

  private fun updateLiveAssistantDraftLocked(
    sessionId: String,
    task: com.opencray.core.contracts.AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ): Boolean {
    if (delegate != null) {
      return false
    }
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return false
    val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
    val updatedDraft = ServiceOwnedLiveAssistantDraftSnapshot(
      runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "",
      taskId = task.id,
      executionId = task.metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank),
      pendingMessageId = pendingMessageId,
      text = normalizedText,
      updatedAtEpochMs = emittedAtEpochMs,
    )
    val existing = sessionDrafts[pendingMessageId]
    if (existing == updatedDraft) {
      return false
    }
    sessionDrafts[pendingMessageId] = updatedDraft
    return true
  }

  private fun clearLiveAssistantDraftLocked(
    sessionId: String,
    pendingMessageId: String?,
  ): Boolean {
    if (delegate != null) {
      return false
    }
    val normalizedPendingMessageId = pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val sessionDrafts = liveAssistantDraftsBySession[sessionId] ?: return false
    val removed = sessionDrafts.remove(normalizedPendingMessageId) != null
    if (sessionDrafts.isEmpty()) {
      liveAssistantDraftsBySession.remove(sessionId)
    }
    return removed
  }

  private fun decorateChatRuntimePayload(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    if (delegate != null) {
      return payload
    }
    val sessionId = (payload["sessionId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return payload
    val liveDrafts = synchronized(lock) {
      liveAssistantDraftsBySession[sessionId]
        ?.values
        ?.sortedBy(ServiceOwnedLiveAssistantDraftSnapshot::updatedAtEpochMs)
        .orEmpty()
    }
    val decoratedPayload = if (liveDrafts.isEmpty()) {
      payload
    } else {
      payload.toMutableMap().apply {
        this["liveAssistantDrafts"] = liveDrafts.map { draft ->
          mapOf(
            "runId" to draft.runId,
            "taskId" to draft.taskId,
            "executionId" to draft.executionId,
            "pendingMessageId" to draft.pendingMessageId,
            "text" to draft.text,
            "updatedAtEpochMs" to draft.updatedAtEpochMs,
          )
        }
      }
    }
    return ensureDecoratedRuntimePayloadVersionSignal(decoratedPayload).toMutableMap().apply {
      put("streamInstanceId", runtimeEventStreamInstanceId)
      put("lastSequence", synchronized(lock) { currentRuntimeEventSequenceLocked(sessionId) })
    }
  }

  override fun loadChatSnapshot(): Map<String, Any?> =
    currentChatPayload().also { payload ->
      synchronized(lock) {
        latestChatPayload = payload
      }
    }

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    val initialPayload = synchronized(lock) {
      chatListeners += listener
      latestChatPayload
    }
    mainThreadPoster.post {
      listener(initialPayload)
    }
    return {
      synchronized(lock) {
        chatListeners -= listener
      }
    }
  }

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    currentDecoratedChatRuntimePayload().also { payload ->
      synchronized(lock) {
        latestChatRuntimePayload = payload
      }
    }

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    chatSnapshotGateway().loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = chatSnapshotGateway().waitForChatRun(runId, timeoutMs)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    val initialPayload = synchronized(lock) {
      chatRuntimeListeners += listener
      latestChatRuntimePayload
    }
    mainThreadPoster.post {
      listener(initialPayload)
    }
    return {
      synchronized(lock) {
        chatRuntimeListeners -= listener
      }
    }
  }

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    delegate?.let { return it.observeLiveAssistantDraftEvents(listener) }
    synchronized(lock) {
      liveAssistantDraftEventListeners += listener
    }
    return {
      synchronized(lock) {
        liveAssistantDraftEventListeners -= listener
      }
    }
  }

  override fun observeRuntimeEventDeltas(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    delegate?.let { return it.observeRuntimeEventDeltas(listener) }
    synchronized(lock) {
      runtimeEventDeltaListeners += listener
    }
    return {
      synchronized(lock) {
        runtimeEventDeltaListeners -= listener
      }
    }
  }

  override fun refreshSandboxSessionInfo() {
    refreshSandboxSessionInfoHandler?.let { handler ->
      handler()
      notifyChatSnapshotsChanged()
      return
    }
    delegateFor("refreshSandboxSessionInfo").refreshSandboxSessionInfo()
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    readGateway.loadMemoryDebugSnapshot()

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    readGateway.loadMemoryDebugLinksSnapshot()

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    readGateway.loadSoulDebugSnapshot()

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = readGateway.searchMemoryDebug(query, maxResults, minScore)

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = readGateway.getMemoryDebugSlice(path, fromLine, lines)

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = readGateway.applyMemoryDebugAction(recordId, actionId)

  override fun createChatSession() {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("createChatSession").createChatSession()
      return
    }
    access.createChatSession()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun copyChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("copyChatSession").copyChatSession(sessionId)
      return
    }
    access.copyChatSession(sessionId)
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun deleteChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("deleteChatSession").deleteChatSession(sessionId)
      return
    }
    if (access.deleteChatSession(sessionId)) {
      onDeviceWarmupAccess.ensureWarmForActiveSession()
      notifyChatSnapshotsChanged()
    }
  }

  override fun selectChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("selectChatSession").selectChatSession(sessionId)
      return
    }
    access.selectChatSession(sessionId)
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("branchChatSessionFromMessage").branchChatSessionFromMessage(sessionId, messageId)
      return
    }
    if (access.branchChatSessionFromMessage(sessionId, messageId)) {
      onDeviceWarmupAccess.ensureWarmForActiveSession()
      notifyChatSnapshotsChanged()
    }
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("deleteChatMessage").deleteChatMessage(sessionId, messageId)
      return
    }
    if (access.deleteChatMessage(sessionId, messageId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("recallChatMessage").recallChatMessage(sessionId, messageId)
      return
    }
    if (access.recallChatMessage(sessionId, messageId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
  ): Map<String, Any?>? {
    val access = chatSubmissionAccess
    if (access == null) {
      return delegateFor("submitChatMessage").submitChatMessage(text, attachments)
    }
    if (onDeviceWarmupAccess.ensureWarmForActiveSession().blocksChatInput()) {
      notifyChatSnapshotsChanged()
      return null
    }
    serviceChatDebug(
      "service.submitChatMessage textLen=${text.trim().length} attachments=${attachments.size}",
    )
    val result = access.submitChatMessage(text, attachments)
    if (result.didMutate) {
      notifyChatSnapshotsChanged()
    }
    return result.payload
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("approveChatApproval").approveChatApproval(taskIdOrRunId)
      return
    }
    access.approveChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("approveChatApprovalForSession").approveChatApprovalForSession(taskIdOrRunId)
      return
    }
    access.approveChatApprovalForSession(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun approveChatApprovalAsBatch(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("approveChatApprovalAsBatch").approveChatApprovalAsBatch(taskIdOrRunId)
      return
    }
    access.approveChatApprovalAsBatch(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("rejectChatApproval").rejectChatApproval(taskIdOrRunId)
      return
    }
    access.rejectChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegateFor("interruptChatRun").interruptChatRun(taskIdOrRunId)
      return
    }
    access.interruptChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegateFor("retryChatRun").retryChatRun(taskIdOrRunId)
      return
    }
    access.retryChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun notifyChatSnapshotsChanged() {
    emitChatSnapshot()
    if (!emitServiceOwnedRuntimeEventDelta()) {
      emitChatRuntimeSnapshot()
    }
    snapshotNotifier()
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitChatSnapshot()
  }

  internal fun dispose() {
    val disposers = synchronized(lock) {
      if (disposed) {
        null
      } else {
        disposed = true
        chatListeners.clear()
        chatRuntimeListeners.clear()
        liveAssistantDraftEventListeners.clear()
        runtimeEventDeltaListeners.clear()
        Triple(chatObservationDisposer, chatRuntimeObservationDisposer, runtimeObservationDisposer)
      }
    } ?: return
    disposers.third?.invoke()
    disposers.second.invoke()
    disposers.first.invoke()
  }

  private fun emitChatSnapshot() {
    emitChatPayload(loadChatSnapshot())
  }

  private fun emitChatRuntimeSnapshot() {
    emitChatRuntimePayload(currentDecoratedChatRuntimePayload())
  }

  private fun emitServiceOwnedRuntimeEventDelta(
    realtimeEnvelope: Map<String, Any?>? = null,
  ): Boolean {
    if (delegate != null) {
      return false
    }
    val hasListeners = synchronized(lock) { runtimeEventDeltaListeners.isNotEmpty() }
    if (!hasListeners) {
      return false
    }
    val nextPayload = decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())
    val deltaPayload = synchronized(lock) {
      runtimeEventDeltaPayloadFromRuntimePayloads(
        previousPayload = latestChatRuntimePayload,
        nextPayload = nextPayload,
        realtimeEnvelope = realtimeEnvelope,
      ).also {
        latestChatRuntimePayload = nextPayload
      }
    }
    if (deltaPayload != null) {
      emitRuntimeEventDelta(deltaPayload)
      return true
    }
    return false
  }

  private fun emitServiceOwnedChatAndRuntimeSnapshots() {
    if (delegate != null) {
      return
    }
    emitChatPayload(loadChatSnapshot())
    emitChatRuntimePayload(currentDecoratedChatRuntimePayload())
  }

  private fun currentChatPayload(): Map<String, Any?> =
    chatPayloadForEmission(decorateChatPayload(chatSnapshotGateway().loadChatSnapshot()))

  private fun currentDecoratedChatRuntimePayload(): Map<String, Any?> =
    decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())

  private fun emitLiveAssistantDraftEvent(payload: Map<String, Any?>) {
    if (payload.isEmpty()) {
      return
    }
    val listeners = synchronized(lock) { liveAssistantDraftEventListeners.toList() }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitRuntimeEventDelta(payload: Map<String, Any?>) {
    if (payload.isEmpty()) {
      return
    }
    val listeners = synchronized(lock) { runtimeEventDeltaListeners.toList() }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitChatPayload(payload: Map<String, Any?>) {
    val listeners = synchronized(lock) {
      latestChatPayload = payload
      chatListeners.toList()
    }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitChatRuntimePayload(payload: Map<String, Any?>) {
    val listeners = synchronized(lock) {
      if (delegate == null && payload == latestChatRuntimePayload) {
        return
      }
      latestChatRuntimePayload = payload
      chatRuntimeListeners.toList()
    }
    serviceChatDebug(
      "service.emitChatRuntimePayload listeners=${listeners.size} ${chatRuntimePayloadDebugSummary(payload)}",
    )
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun runtimeEventDeltaPayloadFromRuntimePayloads(
    previousPayload: Map<String, Any?>,
    nextPayload: Map<String, Any?>,
    realtimeEnvelope: Map<String, Any?>? = null,
  ): Map<String, Any?>? {
    val sessionId = (nextPayload["sessionId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val previousSignaturesByKey = payloadRuntimeEvents(previousPayload)
      .associateBy(
        keySelector = ::runtimeEventPayloadMergeKey,
        valueTransform = ::runtimeEventPayloadContentSignature,
      )
    val nextEvents = payloadRuntimeEvents(nextPayload)
    val deltaEvents = nextEvents.filter { event ->
      previousSignaturesByKey[runtimeEventPayloadMergeKey(event)] !=
        runtimeEventPayloadContentSignature(event)
    }
    if (
      realtimeEnvelope == null &&
      deltaEvents.isEmpty() &&
      payloadUpdatedAtEpochMs(nextPayload) <= payloadUpdatedAtEpochMs(previousPayload)
    ) {
      return null
    }
    val sequence = (realtimeEnvelope?.get("sequence") as? Number)?.toLong()
      ?: nextRuntimeEventDeltaSequenceLocked(sessionId)
    val streamInstanceId = (realtimeEnvelope?.get("streamInstanceId") as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: runtimeEventStreamInstanceId
    val eventId = (realtimeEnvelope?.get("eventId") as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: runtimeRealtimeEnvelopeEventId(sessionId = sessionId, sequence = sequence)
    val executionId = (realtimeEnvelope?.get("executionId") as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: deltaEvents.firstNotNullOfOrNull { event ->
        (event["executionId"] as? String)?.trim()?.takeIf(String::isNotBlank)
      }
    return mapOf(
      "sessionId" to sessionId,
      "streamInstanceId" to streamInstanceId,
      "sequence" to sequence,
      "lastSequence" to sequence,
      "eventId" to eventId,
      "executionId" to executionId,
      "events" to deltaEvents,
      "totalLength" to nextEvents.size,
      "runPatchMode" to "replace",
      "activeRuns" to (nextPayload["activeRuns"] ?: emptyList<Any?>()),
      "retainedRuns" to (nextPayload["retainedRuns"] ?: emptyList<Any?>()),
      "subAgents" to (nextPayload["subAgents"] ?: emptyList<Any?>()),
      "liveAssistantDrafts" to (nextPayload["liveAssistantDrafts"] ?: emptyList<Any?>()),
      "updatedAtEpochMs" to (nextPayload["updatedAtEpochMs"] ?: 0L),
    )
  }

  private fun nextRuntimeEventDeltaSequenceLocked(sessionId: String): Long {
    val next = (runtimeEventDeltaSequencesBySession[sessionId] ?: 0L) + 1L
    runtimeEventDeltaSequencesBySession[sessionId] = next
    return next
  }

  private fun currentRuntimeEventSequenceLocked(sessionId: String): Long =
    runtimeEventDeltaSequencesBySession[sessionId] ?: 0L

  private fun assignRuntimeRealtimeEnvelopeLocked(
    sessionId: String,
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val sequence = nextRuntimeEventDeltaSequenceLocked(sessionId)
    return payload.toMutableMap().apply {
      put("streamInstanceId", runtimeEventStreamInstanceId)
      put("sequence", sequence)
      put("lastSequence", sequence)
      put("eventId", runtimeRealtimeEnvelopeEventId(sessionId = sessionId, sequence = sequence))
    }
  }

  private fun runtimeRealtimeEnvelopeEventId(sessionId: String, sequence: Long): String =
    "runtime-stream-$runtimeEventStreamInstanceId-$sessionId-$sequence"

  private fun payloadRuntimeEvents(
    payload: Map<String, Any?>,
  ): List<Map<String, Any?>> = (payload["events"] as? List<*>)
    ?.mapNotNull { item ->
      @Suppress("UNCHECKED_CAST")
      item as? Map<String, Any?>
    }
    .orEmpty()

  private fun runtimeEventPayloadMergeKey(event: Map<String, Any?>): String =
    (event["eventId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { eventId -> "event|$eventId" }
      ?: listOf(
        event["kind"],
        event["runId"],
        event["taskId"],
        event["executionId"],
        event["executionOrdinal"],
        event["executionKind"],
        event["turn"],
        event["phase"],
        event["stage"],
        event["toolName"],
        event["entryId"],
        event["childRunId"],
        event["childTaskId"],
        event["emittedAtEpochMs"],
      ).joinToString(separator = "|") { value -> value?.toString().orEmpty() }

  private fun runtimeEventPayloadContentSignature(event: Map<String, Any?>): String =
    event.entries
      .sortedBy(Map.Entry<String, Any?>::key)
      .joinToString(separator = "|") { (key, value) ->
        "$key=${runtimeEventPayloadSignatureValue(value)}"
      }

  private fun runtimeEventPayloadSignatureValue(value: Any?): String = when (value) {
    null -> ""
    is Map<*, *> -> value.entries
      .sortedBy { entry -> entry.key?.toString().orEmpty() }
      .joinToString(separator = ",", prefix = "{", postfix = "}") { entry ->
        "${entry.key}=${runtimeEventPayloadSignatureValue(entry.value)}"
      }
    is Iterable<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
      runtimeEventPayloadSignatureValue(item)
    }
    is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
      runtimeEventPayloadSignatureValue(item)
    }
    else -> value.toString()
  }

  private fun chatRuntimePayloadDebugSummary(payload: Map<String, Any?>): String {
    val activeRuns = (payload["activeRuns"] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
    val runSummary = activeRuns.joinToString(separator = ";") { run ->
      val runId = (run["runId"] as? String).orEmpty()
      val taskId = (run["taskId"] as? String).orEmpty()
      val managedProcessCount = (run["managedProcesses"] as? List<*>)?.size ?: 0
      val managedProcessIds = (run["managedProcessIds"] as? List<*>)?.joinToString(",") ?: ""
      val runningManagedProcessCount = run["runningManagedProcessCount"] ?: 0
      val hasLiveManagedProcesses = run["hasLiveManagedProcesses"] ?: false
      val lastEvent = run["lastEvent"] as? Map<*, *>
      val lastKind = lastEvent?.get("kind") as? String ?: "-"
      val lastTool = lastEvent?.get("toolName") as? String ?: "-"
      "${runId.takeLast(12)} task=${taskId.takeLast(12)} mp=$managedProcessCount[$managedProcessIds] running=$runningManagedProcessCount live=$hasLiveManagedProcesses last=$lastKind/$lastTool"
    }
    return "session=${payload["sessionId"] ?: "-"} liveDrafts=${(payload["liveAssistantDrafts"] as? List<*>)?.size ?: 0} activeRuns=${activeRuns.size} retainedRuns=${(payload["retainedRuns"] as? List<*>)?.size ?: 0} events=${(payload["events"] as? List<*>)?.size ?: 0} runs=[$runSummary]"
  }

  private fun payloadUpdatedAtEpochMs(payload: Map<String, Any?>): Long =
    (payload["updatedAtEpochMs"] as? Number)?.toLong() ?: 0L

  private fun payloadSessionId(payload: Map<String, Any?>): String = (payload["sessionId"] as? String)
    ?.trim()
    .orEmpty()

  private fun withPayloadUpdatedAtEpochMs(
    payload: Map<String, Any?>,
    updatedAtEpochMs: Long,
  ): Map<String, Any?> {
    if (payloadUpdatedAtEpochMs(payload) == updatedAtEpochMs) {
      return payload
    }
    return payload.toMutableMap().apply {
      this["updatedAtEpochMs"] = updatedAtEpochMs
    }
  }

  private fun ensureDecoratedChatPayloadVersionSignal(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val previousPayload = synchronized(lock) { latestChatPayload }
    if (previousPayload.isEmpty()) {
      return payload
    }
    val previousInputEnabled = previousPayload["isInputEnabled"] as? Boolean ?: true
    val currentInputEnabled = payload["isInputEnabled"] as? Boolean ?: true
    val previousPlaceholder = previousPayload["composerPlaceholder"] as? String ?: ""
    val currentPlaceholder = payload["composerPlaceholder"] as? String ?: ""
    if (
      previousInputEnabled == currentInputEnabled &&
      previousPlaceholder == currentPlaceholder
    ) {
      return withPayloadUpdatedAtEpochMs(
        payload,
        maxOf(
          payloadUpdatedAtEpochMs(payload),
          payloadUpdatedAtEpochMs(previousPayload),
        ),
      )
    }
    val updatedAtEpochMs = maxOf(
      payloadUpdatedAtEpochMs(payload),
      payloadUpdatedAtEpochMs(previousPayload) + 1L,
      System.currentTimeMillis(),
    )
    return withPayloadUpdatedAtEpochMs(payload, updatedAtEpochMs)
  }

  private fun ensureDecoratedRuntimePayloadVersionSignal(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val sessionId = payloadSessionId(payload)
    if (sessionId.isEmpty()) {
      return payload
    }
    val previousPayload = synchronized(lock) { latestChatRuntimePayload }
    if (previousPayload.isEmpty() || payloadSessionId(previousPayload) != sessionId) {
      return payload
    }
    val currentDisplayState = payloadRuntimeDisplayState(payload)
    val previousDisplayState = payloadRuntimeDisplayState(previousPayload)
    if (currentDisplayState == previousDisplayState) {
      return withPayloadUpdatedAtEpochMs(
        payload,
        maxOf(
          payloadUpdatedAtEpochMs(payload),
          payloadUpdatedAtEpochMs(previousPayload),
        ),
      )
    }
    val updatedAtEpochMs = maxOf(
      payloadUpdatedAtEpochMs(payload),
      payloadUpdatedAtEpochMs(previousPayload) + 1L,
      latestRuntimeDisplayEpochMs(payload),
      System.currentTimeMillis(),
    )
    return withPayloadUpdatedAtEpochMs(payload, updatedAtEpochMs)
  }

  private fun payloadRuntimeDisplayState(payload: Map<String, Any?>): List<Any?> = listOf(
    payload["activeRuns"],
    payload["retainedRuns"],
    payload["subAgents"],
    payload["liveAssistantDrafts"],
    payload["hostLifecycle"],
  )

  private fun latestRuntimeDisplayEpochMs(payload: Map<String, Any?>): Long {
    fun collect(value: Any?): Long = when (value) {
      is Map<*, *> -> {
        val ownEpoch = (value["updatedAtEpochMs"] as? Number)?.toLong() ?: 0L
        value.values.fold(ownEpoch) { latest, item -> maxOf(latest, collect(item)) }
      }
      is Iterable<*> -> value.fold(0L) { latest, item -> maxOf(latest, collect(item)) }
      else -> 0L
    }
    return payloadRuntimeDisplayState(payload).fold(0L) { latest, item ->
      maxOf(latest, collect(item))
    }
  }

  private fun decorateChatPayload(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val warmupState = onDeviceWarmupAccess.ensureWarmForActiveSession()
    if (warmupState.phase == OnDeviceLlmWarmupPhase.FAILED) {
      val failureMessage = warmupState.failureMessage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: onDevicePreparingPlaceholder
      return ensureDecoratedChatPayloadVersionSignal(payload.toMutableMap().apply {
        this["composerPlaceholder"] = failureMessage
      })
    }
    if (!warmupState.blocksChatInput()) {
      return ensureDecoratedChatPayloadVersionSignal(payload)
    }
    return ensureDecoratedChatPayloadVersionSignal(payload.toMutableMap().apply {
      this["isInputEnabled"] = false
      this["composerPlaceholder"] = onDevicePreparingPlaceholder
    })
  }

  private fun chatPayloadForEmission(payload: Map<String, Any?>): Map<String, Any?> =
    if (payload["runtimeActivity"] == null) {
      payload
    } else {
      payload.toMutableMap().apply {
        this["runtimeActivity"] = null
      }
    }

  private fun delegateFor(operation: String): OpenCrayChatRuntimeGateway =
    requireNotNull(delegate) {
      "Service-owned chat gateway cannot '$operation' without a fallback delegate or service-owned access."
    }

  private fun liveAssistantDraftEventPayload(
    sessionId: String,
    runId: String,
    taskId: String,
    executionId: String?,
    pendingMessageId: String,
    text: String,
    updatedAtEpochMs: Long,
    cleared: Boolean,
  ): Map<String, Any?> = mapOf(
    "sessionId" to sessionId,
    "runId" to runId,
    "taskId" to taskId,
    "executionId" to executionId,
    "pendingMessageId" to pendingMessageId,
    "text" to text,
    "updatedAtEpochMs" to updatedAtEpochMs,
    "cleared" to cleared,
  )

  private fun ServiceOwnedLiveAssistantDraftSnapshot.toLiveAssistantDraftEventPayload(
    sessionId: String,
    cleared: Boolean,
  ): Map<String, Any?> = liveAssistantDraftEventPayload(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    pendingMessageId = pendingMessageId,
    text = text,
    updatedAtEpochMs = updatedAtEpochMs,
    cleared = cleared,
  )

  private fun chatSnapshotGateway(): OpenCrayChatRuntimeGateway = delegate ?: readGateway

  private fun runtimeSnapshotGateway(): OpenCrayChatRuntimeGateway = delegate ?: readGateway
}
