package com.opencray.app

import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.memory.MemoryOperator
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryOperatorRequest

internal class HostChatRuntimeGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCrayChatRuntimeGateway {
  override fun loadChatSnapshot(): Map<String, Any?> =
    host.loadChatSnapshot(includeRuntimeActivity = true)

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.chatListeners,
      initialPayload = host.loadChatSnapshotForEmission(),
      listener = listener,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> = synchronized(host.lock) {
    val activeSessionId = host.chatSessionStore.loadState().activeSession.sessionId
    host.runtimeActivitySnapshotLocked(activeSessionId)
  }

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = synchronized(host.lock) {
    host.findRunSnapshotLocked(runId)
      ?.takeIf(host::isUserVisibleRun)
      ?.let(host::runSnapshotToMap)
  }

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = host.waitForRunSnapshot(runId, timeoutMs)
    ?.takeIf(host::isUserVisibleRun)
    ?.let(host::runSnapshotToMap)

  fun waitForChatRun(runId: String): Map<String, Any?>? =
    waitForChatRun(runId = runId, timeoutMs = OpenCrayHostRuntime.DEFAULT_RUN_WAIT_TIMEOUT_MS)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.chatRuntimeListeners,
      initialPayload = loadChatRuntimeSnapshot(),
      listener = listener,
    )

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(host.lock) {
      host.liveAssistantDraftEventListeners += listener
    }
    return {
      synchronized(host.lock) {
        host.liveAssistantDraftEventListeners -= listener
      }
    }
  }

  override fun observeRuntimeEventDeltas(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(host.lock) {
      host.runtimeEventDeltaListeners += listener
    }
    return {
      synchronized(host.lock) {
        host.runtimeEventDeltaListeners -= listener
      }
    }
  }

  override fun refreshSandboxSessionInfo() {
    synchronized(host.lock) {
      val sessionId = host.chatSessionStore.loadState().activeSession.sessionId
      submitSandboxSessionInfoRefreshTask(
        sessionId = sessionId,
        runtimeHostAccess = host.runtimeHostAccess,
        taskSafetyMetadata = host.safetyMetadataForTask(host.safetySettingsFacade.load()),
        lifecycleDescriptor = host.lifecycleDescriptor,
      )
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> = synchronized(host.lock) {
    host.chatDebugProjector.loadMemoryDebugSnapshot(
      sessionId = host.chatSessionStore.loadState().activeSession.sessionId,
    )
  }

  override fun loadSoulDebugSnapshot(): Map<String, Any?> = synchronized(host.lock) {
    host.chatDebugProjector.loadSoulDebugSnapshot(
      sessionId = host.chatSessionStore.loadState().activeSession.sessionId,
    )
  }

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = synchronized(host.lock) {
    host.chatDebugProjector.searchMemoryDebug(
      sessionId = host.chatSessionStore.loadState().activeSession.sessionId,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
  }

  fun searchMemoryDebug(query: String): Map<String, Any?> =
    searchMemoryDebug(
      query = query,
      maxResults = 4,
      minScore = 1,
    )

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = synchronized(host.lock) {
    host.chatDebugProjector.getMemoryDebugSlice(
      sessionId = host.chatSessionStore.loadState().activeSession.sessionId,
      path = path,
      fromLine = fromLine,
      lines = lines,
    )
  }

  fun getMemoryDebugSlice(path: String): Map<String, Any?> =
    getMemoryDebugSlice(
      path = path,
      fromLine = null,
      lines = 12,
    )

  fun getMemoryDebugSlice(path: String, fromLine: Int?): Map<String, Any?> =
    getMemoryDebugSlice(
      path = path,
      fromLine = fromLine,
      lines = 12,
    )

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> {
    return synchronized(host.lock) {
      val store = host.personalizationLocalStore
        ?: error("Memory debug actions require a personalization memory store.")
      val sessionId = host.chatSessionStore.loadState().activeSession.sessionId
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
        store.appendMemoryDebugActionAudit(
          host.newMemoryDebugActionAuditEntry(
            recordId = recordId,
            action = action,
            sessionId = sessionId,
          ),
        )
      }
      mapOf(
        "recordId" to recordId,
        "action" to action.wireValue,
        "applied" to result.applied,
      )
    }
  }

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = synchronized(host.lock) {
    val activeSessionId = host.chatSessionStore.loadState().activeSession.sessionId
    val allRuns = host.chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
      .flatMap { sessionId ->
        host.runtimeSession(sessionId).listRuns()
      }
    host.chatDebugProjector.loadMemoryDebugLinksSnapshot(
      activeSessionId = activeSessionId,
      allRuns = allRuns,
      runtimeEventsBySession = host.chatRuntimeEventState.snapshotBySession(),
    )
  }

  override fun createChatSession() {
    val sessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.createChatSession()
    }
    host.chatSessionMutationCoordinator.repairTerminalReplay(sessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun selectChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.selectChatSession(sessionId)
    }
    host.chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun copyChatSession(sessionId: String) {
    val copiedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.copyChatSession(sessionId)
    }
    host.chatSessionMutationCoordinator.repairTerminalReplay(copiedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val branchedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.branchChatSessionFromMessage(sessionId, messageId)
    } ?: return
    host.chatSessionMutationCoordinator.repairTerminalReplay(branchedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun deleteChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.deleteChatSession(sessionId)
    } ?: return
    host.chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.deleteChatMessage(sessionId, messageId)
    } ?: return
    host.chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(host.lock) {
      host.chatSessionMutationCoordinator.recallChatMessage(sessionId, messageId)
    } ?: return
    host.chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    synchronized(host.lock) {
      host.chatApprovalDecisionCoordinator.approve(taskIdOrRunId)
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    synchronized(host.lock) {
      host.chatApprovalDecisionCoordinator.approveForSession(taskIdOrRunId)
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  fun approveChatApprovalBatch(taskIdOrRunId: String) {
    synchronized(host.lock) {
      host.chatApprovalDecisionCoordinator.approve(
        taskIdOrRunId,
        ApprovalDecisionScope.BATCH,
      )
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    synchronized(host.lock) {
      host.chatApprovalDecisionCoordinator.reject(taskIdOrRunId)
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    host.chatRunControlCoordinator.interruptChatRun(taskIdOrRunId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    host.chatRunControlCoordinator.retryChatRun(taskIdOrRunId)
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? {
    val result = synchronized(host.lock) {
      host.chatSubmissionCoordinator.submitChatMessage(
        text = text,
        attachments = attachments,
      )
    }
    if (!result.didMutate) {
      return null
    }
    host.emitChatSnapshot()
    host.emitChatRuntimeSnapshot()
    return result.submission?.let(host::runSubmissionToMap)
  }

  fun submitChatMessage(text: String): Map<String, Any?>? =
    submitChatMessage(
      text = text,
      attachments = emptyList(),
    )
}
