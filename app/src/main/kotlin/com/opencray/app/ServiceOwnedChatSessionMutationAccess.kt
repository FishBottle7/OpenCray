package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry

internal class ChatSessionMutationCoordinator(
  private val chatSessionStore: ChatSessionLocalStore,
  private val runtimeHostAccess: RuntimeChatMutationAccess,
  private val chatUnreadMessageState: ChatUnreadMessageState,
  private val pendingApprovalState: ChatPendingApprovalState = ChatPendingApprovalState(),
  private val runtimeEventState: ChatRuntimeEventState = ChatRuntimeEventState(),
  private val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
  private val mediaGc: () -> Unit = {},
) {
  fun createChatSession(): String {
    val sessionId = chatSessionStore.createSession().activeSession.sessionId
    activateSession(sessionId)
    return sessionId
  }

  fun copyChatSession(sessionId: String): String {
    val copiedSessionId = chatSessionStore.copySession(sessionId).activeSession.sessionId
    activateSession(copiedSessionId)
    return copiedSessionId
  }

  fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ): String? {
    if (sessionId.isBlank() || messageId.isBlank()) {
      return null
    }
    if (chatSessionStore.loadState().sessions.none { session -> session.sessionId == sessionId }) {
      return null
    }
    val branchedSessionId = chatSessionStore
      .branchSessionFromMessage(sessionId, messageId)
      .activeSession
      .sessionId
    activateSession(branchedSessionId)
    return branchedSessionId
  }

  fun selectChatSession(sessionId: String): String {
    val stateBeforeSelection = chatSessionStore.loadState()
    val currentSessionId = stateBeforeSelection.activeSession.sessionId
    val shouldDiscardCurrentEmptySession =
      sessionId != currentSessionId &&
        stateBeforeSelection.sessions.any { session -> session.sessionId == sessionId } &&
        canDiscardEmptySession(currentSessionId)
    if (shouldDiscardCurrentEmptySession) {
      discardSession(currentSessionId)
      chatSessionStore.deleteSession(currentSessionId)
      runMediaGc()
    }
    val selectedSessionId = chatSessionStore.selectSession(sessionId).activeSession.sessionId
    activateSession(selectedSessionId)
    return selectedSessionId
  }

  fun deleteChatSession(sessionId: String): String? {
    if (chatSessionStore.loadState().sessions.none { session -> session.sessionId == sessionId }) {
      return null
    }
    discardSession(sessionId)
    val nextSessionId = chatSessionStore.deleteSession(sessionId).activeSession.sessionId
    runMediaGc()
    activateSession(nextSessionId)
    return nextSessionId
  }

  fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ): String? {
    if (chatSessionStore.loadState().sessions.none { session -> session.sessionId == sessionId }) {
      return null
    }
    if (messageId.isBlank()) {
      return null
    }
    val session = chatSessionStore.loadSession(sessionId) ?: return null
    val messageIdsToDelete = messageIdsForChatDelete(
      session = session,
      messageId = messageId,
    )
    cancelPendingMessageIds(sessionId = sessionId, pendingMessageIds = messageIdsToDelete)
    chatSessionStore.deleteMessages(sessionId, messageIdsToDelete)
    runMediaGc()
    return sessionId
  }

  fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ): String? {
    if (chatSessionStore.loadState().sessions.none { session -> session.sessionId == sessionId }) {
      return null
    }
    if (messageId.isBlank()) {
      return null
    }
    val session = chatSessionStore.loadSession(sessionId) ?: return null
    val recallIndex = session.messages.indexOfFirst { message -> message.messageId == messageId }
    if (recallIndex < 0 || session.messages[recallIndex].role != ChatTranscriptRole.USER) {
      return null
    }
    cancelPendingMessageIds(
      sessionId = sessionId,
      pendingMessageIds = session.messages
        .drop(recallIndex)
        .mapTo(linkedSetOf()) { message -> message.messageId },
    )
    chatSessionStore.recallMessageCascade(sessionId, messageId)
    runMediaGc()
    return sessionId
  }

  fun repairTerminalReplay(sessionId: String) {
    val runtimeSession = runtimeHostAccess.session(sessionId)
    terminalReplayRepairer(
      sessionId,
      runtimeSession.listRuns(),
    )
  }

  private fun activateSession(sessionId: String) {
    val runtimeSession = runtimeHostAccess.session(sessionId)
    chatUnreadMessageState.clear(sessionId)
    runtimeSession.resume()
  }

  private fun canDiscardEmptySession(sessionId: String): Boolean {
    if (!chatSessionStore.isReusableEmptySession(sessionId)) {
      return false
    }
    if (chatUnreadMessageState.rawCount(sessionId) > 0) {
      return false
    }
    if (pendingApprovalState.hasApprovals(sessionId)) {
      return false
    }
    if (runtimeEventState.hasEvents(sessionId)) {
      return false
    }
    if (runtimeHostAccess.runEventJournalStore(sessionId).hasEntries()) {
      return false
    }
    if (runtimeHostAccess.promptCheckpointStore(sessionId).list().isNotEmpty()) {
      return false
    }
    if (runtimeHostAccess.supplementStore(sessionId).snapshot().isNotEmpty()) {
      return false
    }
    val runtimeSession = runtimeHostAccess.session(sessionId)
    if (runtimeSession.listRuns().isNotEmpty()) {
      return false
    }
    if (runtimeSession.snapshot().tasks.isNotEmpty()) {
      return false
    }
    return true
  }

  private fun discardSession(sessionId: String) {
    val runtimeSession = runtimeHostAccess.session(sessionId)
    runtimeSession.listRuns()
      .filterNot(AgentRunSnapshot::isTerminal)
      .forEach { run ->
        runtimeSession.requestCancel(run.taskId)
      }
    runtimeSession.terminateRunningManagedProcesses()
    runtimeHostAccess.retainKnownApprovalTasks(sessionId, emptySet())
    pendingApprovalState.removeSession(sessionId)
    runtimeEventState.removeSession(sessionId)
    runtimeHostAccess.runEventJournalStore(sessionId).clear()
    runtimeHostAccess.promptCheckpointStore(sessionId).clear()
    chatUnreadMessageState.clear(sessionId)
    runtimeHostAccess.supplementStore(sessionId).clear()
    runtimeHostAccess.releaseSession(sessionId)
  }

  private fun cancelPendingMessageIds(
    sessionId: String,
    pendingMessageIds: Set<String>,
  ) {
    if (pendingMessageIds.isEmpty()) {
      return
    }
    val runtimeSession = runtimeHostAccess.session(sessionId)
    runtimeSession.requestCancelForPendingMessageIds(pendingMessageIds)
    val checkpointStore = runtimeHostAccess.promptCheckpointStore(sessionId)
    pendingApprovalState.removeByPendingMessageIds(
      sessionId = sessionId,
      pendingMessageIds = pendingMessageIds,
    ).forEach { approval ->
      runtimeHostAccess.clearApproval(sessionId = sessionId, taskId = approval.taskId)
      checkpointStore.remove(approval.taskId)
    }
  }

  private fun runMediaGc() {
    runCatching { mediaGc() }
  }

  private fun messageIdsForChatDelete(
    session: ChatTranscriptSessionEntry,
    messageId: String,
  ): Set<String> {
    val normalizedMessageId = messageId.trim()
    if (normalizedMessageId.isBlank()) {
      return emptySet()
    }
    val messageIndex = session.messages.indexOfFirst { message ->
      message.messageId == normalizedMessageId
    }
    if (messageIndex < 0) {
      return setOf(normalizedMessageId)
    }
    if (!shouldCascadeFinalAgentBubbleDelete(session, messageIndex)) {
      return setOf(normalizedMessageId)
    }
    val messageIds = linkedSetOf<String>()
    for (index in messageIndex downTo 0) {
      val message = session.messages[index]
      if (message.role == ChatTranscriptRole.USER) {
        break
      }
      if (message.role == ChatTranscriptRole.ASSISTANT) {
        messageIds += message.messageId
      }
    }
    messageIds += normalizedMessageId
    return messageIds
  }

  private fun shouldCascadeFinalAgentBubbleDelete(
    session: ChatTranscriptSessionEntry,
    messageIndex: Int,
  ): Boolean {
    val message = session.messages.getOrNull(messageIndex) ?: return false
    if (
      message.role != ChatTranscriptRole.ASSISTANT ||
      isRuntimeProjectedAgentMessageId(message.messageId)
    ) {
      return false
    }
    for (index in (messageIndex - 1) downTo 0) {
      val candidate = session.messages[index]
      if (candidate.role == ChatTranscriptRole.USER) {
        break
      }
      if (
        candidate.role == ChatTranscriptRole.ASSISTANT &&
        isRuntimeProjectedAgentMessageId(candidate.messageId)
      ) {
        return true
      }
    }
    return false
  }

  private fun isRuntimeProjectedAgentMessageId(messageId: String): Boolean =
    messageId.startsWith("runtime-assistant-") ||
      messageId.startsWith("runtime-process-")
}

internal class ServiceOwnedChatSessionMutationAccess(
  private val chatSessionStore: ChatSessionLocalStore,
  private val runtimeHostAccess: RuntimeChatMutationAccess,
  private val chatUnreadMessageState: ChatUnreadMessageState,
  private val pendingApprovalState: ChatPendingApprovalState = ChatPendingApprovalState(),
  private val runtimeEventState: ChatRuntimeEventState = ChatRuntimeEventState(),
  private val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
  private val mediaGc: () -> Unit = {},
) {
  private val lock = Any()
  private val coordinator = ChatSessionMutationCoordinator(
    chatSessionStore = chatSessionStore,
    runtimeHostAccess = runtimeHostAccess,
    chatUnreadMessageState = chatUnreadMessageState,
    pendingApprovalState = pendingApprovalState,
    runtimeEventState = runtimeEventState,
    terminalReplayRepairer = terminalReplayRepairer,
    mediaGc = mediaGc,
  )

  fun createChatSession() {
    val sessionId = synchronized(lock) {
      coordinator.createChatSession()
    }
    coordinator.repairTerminalReplay(sessionId)
  }

  fun copyChatSession(sessionId: String) {
    val copiedSessionId = synchronized(lock) {
      coordinator.copyChatSession(sessionId)
    }
    coordinator.repairTerminalReplay(copiedSessionId)
  }

  fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ): Boolean {
    val repairedSessionId = synchronized(lock) {
      coordinator.branchChatSessionFromMessage(sessionId, messageId)
    } ?: return false
    coordinator.repairTerminalReplay(repairedSessionId)
    return true
  }

  fun selectChatSession(sessionId: String) {
    val selectedSessionId = synchronized(lock) {
      coordinator.selectChatSession(sessionId)
    }
    coordinator.repairTerminalReplay(selectedSessionId)
  }

  fun deleteChatSession(sessionId: String): Boolean {
    val repairedSessionId = synchronized(lock) {
      coordinator.deleteChatSession(sessionId)
    } ?: return false
    coordinator.repairTerminalReplay(repairedSessionId)
    return true
  }

  fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ): Boolean {
    val repairedSessionId = synchronized(lock) {
      coordinator.deleteChatMessage(sessionId, messageId)
    } ?: return false
    coordinator.repairTerminalReplay(repairedSessionId)
    return true
  }

  fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ): Boolean {
    val repairedSessionId = synchronized(lock) {
      coordinator.recallChatMessage(sessionId, messageId)
    } ?: return false
    coordinator.repairTerminalReplay(repairedSessionId)
    return true
  }
}
