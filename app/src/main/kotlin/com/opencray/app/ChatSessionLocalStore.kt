package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.ChatWorkspaceStoreUpdate
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.workingstate.WorkingState
import java.io.File
import java.util.UUID

internal open class ChatSessionLocalStore(
  private val directory: File,
  internal val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  internal val workspaceStore = JsonFileChatWorkspaceStore(directory)

  fun loadState(): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = checkNotNull(activeSessionFrom(workspace)) { "Expected chat workspace to have an active session." }
    return ChatSessionsState(
      sessions = sessionsForUi(workspaceStore.load() ?: workspace),
      activeSession = activeSession,
    )
  }

  fun createSession(): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: workspaceWithNewSession(
        workspace = seedWorkspaceRecord(now),
        now = now,
      ).workspace
      val reusableSession = reusableEmptySessionFrom(currentWorkspace)
      val updated = if (reusableSession == null) {
        workspaceWithNewSession(
          workspace = currentWorkspace,
          now = now,
        )
      } else {
        workspaceWithReusedEmptySession(
          workspace = currentWorkspace,
          session = reusableSession,
          now = now,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = updated.workspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updated.workspace),
          activeSession = updated.session,
        ),
      )
    }
  }

  fun loadSession(sessionId: String): ChatTranscriptSessionEntry? =
    loadWorkspaceOrCreate().sessions.firstOrNull { session -> session.sessionId == sessionId }

  fun referencedAttachmentLocalPaths(): Set<String> {
    val workspace = loadWorkspaceOrCreate()
    val referencedPaths = linkedSetOf<String>()
    workspace.sessions.forEach { session ->
      session.messages.forEach { message ->
        message.attachments.forEach { attachment ->
          normalizedAttachmentLocalPath(attachment.localPath)?.let(referencedPaths::add)
        }
      }
      pendingUserInputsFrom(workspace = workspace, sessionId = session.sessionId).forEach { pendingInput ->
        pendingInput.attachments.forEach { attachment ->
          normalizedAttachmentLocalPath(attachment.localPath)?.let(referencedPaths::add)
        }
      }
    }
    return referencedPaths
  }

  internal fun isReusableEmptySession(sessionId: String): Boolean {
    if (sessionId.isBlank()) {
      return false
    }
    val workspace = loadWorkspaceOrCreate()
    val session = workspace.sessions.firstOrNull { entry -> entry.sessionId == sessionId } ?: return false
    return isReusableEmptySession(
      workspace = workspace,
      session = session,
    )
  }

  fun selectSession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val activeSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId }
        ?: current.session
      val updatedWorkspace = currentWorkspace.copy(
        activeSessionId = activeSession.sessionId,
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(now, currentWorkspace.updatedAtEpochMs),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = activeSession,
        ),
      )
    }
  }

  fun copySession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val sourceSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId }
        ?: return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = current.session,
          ),
          write = false,
        )
      val copiedAt = maxOf(now, currentWorkspace.updatedAtEpochMs)
      val copiedSessionId = "session-${copiedAt}-${UUID.randomUUID().toString().take(8)}"
      val copiedSession = sourceSession.copy(
        sessionId = copiedSessionId,
        title = copyTitleFor(sourceSession.title),
        createdAtEpochMs = copiedAt,
        updatedAtEpochMs = copiedAt,
      )
      val updatedWorkspace = currentWorkspace.copy(
        sessions = (currentWorkspace.sessions.filterNot { it.sessionId == copiedSessionId } + copiedSession)
          .sortedByDescending { it.updatedAtEpochMs },
        activeSessionId = copiedSessionId,
        extensions = copySessionExtensions(
          extensions = currentWorkspace.extensions,
          sourceSessionId = sourceSession.sessionId,
          targetSessionId = copiedSessionId,
          includeWorkingState = true,
        ),
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = copiedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = copiedSession,
        ),
      )
    }
  }

  fun deleteSession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      if (currentWorkspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = current.session,
          ),
          write = false,
        )
      }
      val remainingSessions = currentWorkspace.sessions.filterNot { session -> session.sessionId == sessionId }
      if (remainingSessions.isEmpty()) {
        val replacement = workspaceWithNewSession(
          workspace = currentWorkspace.copy(
            sessions = emptyList(),
            activeSessionId = null,
            updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, now),
          ),
          now = now,
        )
        return@update ChatWorkspaceStoreUpdate(
          record = replacement.workspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(replacement.workspace),
            activeSession = replacement.session,
          ),
        )
      }
      val nextActiveSession = remainingSessions.firstOrNull { session ->
        session.sessionId == currentWorkspace.activeSessionId
      } ?: remainingSessions.maxByOrNull { it.updatedAtEpochMs }
        ?: remainingSessions.first()
      val updatedWorkspace = currentWorkspace.copy(
        sessions = remainingSessions.sortedByDescending { it.updatedAtEpochMs },
        activeSessionId = nextActiveSession.sessionId,
        extensions = currentWorkspace.extensions -
          pendingUserInputExtensionKey(sessionId) -
          todoExtensionKey(sessionId) -
          archivedTodoExtensionKey(sessionId) -
          workingStateExtensionKey(sessionId) -
          maintainedContextWindowTokensExtensionKey(sessionId) -
          nativeWebSearchApprovalExtensionKey(sessionId) -
          sessionScopedStateExtensionKey(sessionId),
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, now),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = nextActiveSession,
        ),
      )
    }
  }

  fun appendUserMessage(sessionId: String, text: String): ChatSessionsState {
    return appendUserMessage(
      sessionId = sessionId,
      text = text,
      commandLabel = null,
      attachments = emptyList(),
    )
  }

  fun appendUserMessage(
    sessionId: String,
    text: String,
    commandLabel: String?,
    attachments: List<ChatAttachmentEntry>,
  ): ChatSessionsState {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty() || !commandLabel.isNullOrBlank() || attachments.isNotEmpty()) {
      "appendUserMessage requires text, commandLabel, or attachments."
    }
    return appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.USER,
      text = trimmed,
      commandLabel = commandLabel?.trim()?.ifBlank { null },
      attachments = attachments,
      updateTitle = true,
    ).state
  }

  fun appendMessage(
    sessionId: String,
    role: ChatTranscriptRole,
    text: String,
  ): AppendMessageResult = appendMessage(
    sessionId = sessionId,
    role = role,
    text = text,
    commandLabel = null,
    attachments = emptyList(),
    updateTitle = false,
  )

  fun insertMessageBefore(
    sessionId: String,
    anchorMessageId: String,
    role: ChatTranscriptRole,
    text: String,
    messageId: String? = null,
    attachments: List<ChatAttachmentEntry> = emptyList(),
    createdAtEpochMs: Long? = null,
  ): ChatSessionsState {
    val normalizedAnchorMessageId = anchorMessageId.trim()
    val trimmedText = text.trim()
    require(normalizedAnchorMessageId.isNotEmpty()) {
      "insertMessageBefore anchorMessageId must not be blank."
    }
    require(trimmedText.isNotEmpty() || attachments.isNotEmpty()) {
      "insertMessageBefore requires text or attachments."
    }

    val normalizedMessageId = messageId?.trim()?.takeIf(String::isNotBlank)
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val insertedMessage = ChatTranscriptMessageEntry(
      messageId = normalizedMessageId ?: messageId(role.name.lowercase()),
      role = role,
      text = trimmedText.ifBlank { null },
      attachments = attachments,
      createdAtEpochMs = createdAtEpochMs ?: now,
    )
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      val anchorIndex = currentSession.messages.indexOfFirst { message ->
        message.messageId == normalizedAnchorMessageId
      }
      if (
        anchorIndex < 0 ||
        normalizedMessageId != null &&
        currentSession.messages.any { message -> message.messageId == normalizedMessageId }
      ) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = currentSession,
          ),
          write = false,
        )
      }
      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = buildList(currentSession.messages.size + 1) {
        addAll(currentSession.messages.take(anchorIndex))
        add(insertedMessage)
        addAll(currentSession.messages.drop(anchorIndex))
      }
      val updatedSession = currentSession.copy(
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun replaceMessage(
    sessionId: String,
    messageId: String,
    role: ChatTranscriptRole,
    text: String?,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ): ChatSessionsState {
    val trimmed = text?.trim().orEmpty()
    require(trimmed.isNotEmpty() || attachments.isNotEmpty()) {
      "replaceMessage requires text or attachments."
    }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      if (currentSession.messages.none { message -> message.messageId == messageId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = currentSession,
          ),
          write = false,
        )
      }
      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = currentSession.messages.map { message ->
        if (message.messageId == messageId) {
          message.copy(
            role = role,
            text = trimmed.ifBlank { null },
            promptTemplateRefId = null,
            commandLabel = null,
            attachments = attachments,
          )
        } else {
          message
        }
      }
      val updatedSession = currentSession.copy(
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun replaceMessageAndPruneTail(
    sessionId: String,
    messageId: String,
    role: ChatTranscriptRole,
    text: String,
  ): ChatSessionsState {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "replaceMessageAndPruneTail text must not be blank." }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      val messageIndex = currentSession.messages.indexOfFirst { message -> message.messageId == messageId }
      if (messageIndex < 0) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = currentSession,
          ),
          write = false,
        )
      }

      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = currentSession.messages.take(messageIndex) + currentSession.messages[messageIndex].copy(
        role = role,
        text = trimmed,
        promptTemplateRefId = null,
      )
      val updatedSession = currentSession.copy(
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun deleteMessage(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState = deleteMessages(
    sessionId = sessionId,
    messageIds = setOf(messageId),
  )

  fun deleteMessages(
    sessionId: String,
    messageIds: Set<String>,
  ): ChatSessionsState {
    val normalizedMessageIds = messageIds
      .mapTo(linkedSetOf()) { messageId -> messageId.trim() }
      .filterTo(linkedSetOf(), String::isNotBlank)
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = currentSession.messages.filterNot { it.messageId in normalizedMessageIds }
      val updatedSession = currentSession.copy(
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun recallMessageCascade(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      val recallIndex = currentSession.messages.indexOfFirst { message -> message.messageId == messageId }
      val recalledMessage = currentSession.messages.getOrNull(recallIndex)
      if (recallIndex < 0 || recalledMessage?.role != ChatTranscriptRole.USER) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = currentSession,
          ),
          write = false,
        )
      }

      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedSession = currentSession.copy(
        messages = currentSession.messages.take(recallIndex),
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun branchSessionFromMessage(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val current = workspaceAndActiveSessionForUpdate(
        workspace = workspace,
        now = now,
      )
      val currentWorkspace = current.workspace
      val sourceSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId } ?: current.session
      val branchUntilIndex = sourceSession.messages.indexOfFirst { it.messageId == messageId }
      val branchMessages = if (branchUntilIndex >= 0) {
        sourceSession.messages.take(branchUntilIndex + 1)
      } else {
        sourceSession.messages
      }
      val branchedAt = maxOf(now, currentWorkspace.updatedAtEpochMs)
      val branchSessionId = "session-${branchedAt}-${UUID.randomUUID().toString().take(8)}"
      val branchSession = ChatTranscriptSessionEntry(
        sessionId = branchSessionId,
        title = branchTitleFor(sourceSession.title),
        createdAtEpochMs = branchedAt,
        updatedAtEpochMs = branchedAt,
        messages = branchMessages,
      )
      val updatedWorkspace = currentWorkspace.copy(
        sessions = (currentWorkspace.sessions.filterNot { it.sessionId == branchSessionId } + branchSession)
          .sortedByDescending { it.updatedAtEpochMs },
        activeSessionId = branchSessionId,
        extensions = copySessionExtensions(
          extensions = currentWorkspace.extensions,
          sourceSessionId = sourceSession.sessionId,
          targetSessionId = branchSessionId,
          includeWorkingState = false,
        ),
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = branchedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = branchSession,
        ),
      )
    }
  }

  fun appendAssistantPlaceholder(sessionId: String, text: String): ChatSessionsState {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "appendAssistantPlaceholder text must not be blank." }
    return appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = trimmed,
      commandLabel = null,
      attachments = emptyList(),
      updateTitle = false,
    ).state
  }

  fun reserveMessageId(role: ChatTranscriptRole): String = messageId(role.name.lowercase())

  open fun appendSubmittedTurn(
    sessionId: String,
    userText: String,
    assistantMessageId: String,
    assistantPlaceholderText: String,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ): ChatSessionsState {
    val normalizedUserText = userText.trim()
    val normalizedAssistantText = assistantPlaceholderText.trim()
    require(normalizedUserText.isNotEmpty() || attachments.isNotEmpty()) {
      "appendSubmittedTurn userText or attachments must not be blank."
    }
    require(normalizedAssistantText.isNotEmpty()) { "appendSubmittedTurn assistantPlaceholderText must not be blank." }
    require(assistantMessageId.isNotBlank()) { "appendSubmittedTurn assistantMessageId must not be blank." }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val userMessage = ChatTranscriptMessageEntry(
      messageId = messageId(ChatTranscriptRole.USER.name.lowercase()),
      role = ChatTranscriptRole.USER,
      text = normalizedUserText.ifBlank { null },
      attachments = attachments,
      createdAtEpochMs = now,
    )
    val assistantMessage = ChatTranscriptMessageEntry(
      messageId = assistantMessageId,
      role = ChatTranscriptRole.ASSISTANT,
      text = normalizedAssistantText,
      createdAtEpochMs = now,
    )
    return workspaceStore.update { workspace ->
      val current = workspaceAndSessionForAppend(
        workspace = workspace,
        sessionId = sessionId,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = current.session
      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = currentSession.messages + listOf(userMessage, assistantMessage)
      val updatedSession = currentSession.copy(
        title = titleForSession(currentSession.title, updatedMessages),
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = updatedSession,
        ),
      )
    }
  }

  fun loadPendingUserInputs(sessionId: String): List<PendingUserInputEntry> {
    if (sessionId.isBlank()) {
      return emptyList()
    }
    val workspace = loadWorkspaceOrCreate()
    return pendingUserInputsFrom(workspace = workspace, sessionId = sessionId)
  }

  fun loadTodos(sessionId: String): List<AgentTodoEntry> {
    if (sessionId.isBlank()) {
      return emptyList()
    }
    val workspace = loadWorkspaceOrCreate()
    return activeTodosFrom(workspace = workspace, sessionId = sessionId)
  }

  fun loadTodoSnapshot(sessionId: String): ChatSessionTodoSnapshot {
    if (sessionId.isBlank()) {
      return ChatSessionTodoSnapshot.empty()
    }
    val workspace = loadWorkspaceOrCreate()
    return todoSnapshotFrom(workspace = workspace, sessionId = sessionId)
  }

  fun loadWorkingState(sessionId: String): WorkingState {
    if (sessionId.isBlank()) {
      return WorkingState()
    }
    val workspace = loadWorkspaceOrCreate()
    return workingStateFrom(workspace = workspace, sessionId = sessionId)
  }

  fun loadMaintainedContextWindowTokens(sessionId: String): Int? {
    if (sessionId.isBlank()) {
      return null
    }
    val workspace = loadWorkspaceOrCreate()
    return maintainedContextWindowTokensFrom(workspace = workspace, sessionId = sessionId)
  }

  fun loadTodoPresentation(
    sessionId: String,
    archivedVisibilityDurationMs: Long,
  ): ChatSessionTodoPresentation {
    val snapshot = loadTodoSnapshot(sessionId)
    return when (snapshot.state) {
      ChatSessionTodoState.ACTIVE -> ChatSessionTodoPresentation(
        todos = snapshot.todos,
        state = ChatSessionTodoPresentationState.ACTIVE,
      )

      ChatSessionTodoState.ARCHIVED_COMPLETED -> {
        val completedAtEpochMs = snapshot.completedAtEpochMs
        if (completedAtEpochMs == null) {
          ChatSessionTodoPresentation.empty()
        } else {
          val elapsedMs = (nowEpochMs() - completedAtEpochMs).coerceAtLeast(0L)
          val remainingMs = archivedVisibilityDurationMs - elapsedMs
          if (remainingMs > 0L) {
            ChatSessionTodoPresentation(
              todos = snapshot.todos,
              state = ChatSessionTodoPresentationState.ARCHIVED_COMPLETED,
              hideDelayMs = remainingMs,
              completedAtEpochMs = completedAtEpochMs,
            )
          } else {
            ChatSessionTodoPresentation.empty()
          }
        }
      }

      ChatSessionTodoState.EMPTY -> ChatSessionTodoPresentation.empty()
    }
  }

  fun isNativeWebSearchSessionApproved(sessionId: String): Boolean {
    if (sessionId.isBlank()) {
      return false
    }
    val workspace = loadWorkspaceOrCreate()
    return nativeWebSearchApprovalFrom(workspace = workspace, sessionId = sessionId)
  }

  fun replaceMaintainedContextWindowTokens(
    sessionId: String,
    contextWindowTokens: Int?,
  ) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null || workspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val key = maintainedContextWindowTokensExtensionKey(sessionId)
      val updatedExtensions = contextWindowTokens
        ?.takeIf { value -> value > 0 }
        ?.let { resolvedTokens -> workspace.extensions + (key to resolvedTokens.toString()) }
        ?: (workspace.extensions - key)
      if (updatedExtensions == workspace.extensions) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          extensions = updatedExtensions,
          recordVersion = workspace.recordVersion + 1,
          updatedAtEpochMs = now,
        ),
        result = Unit,
      )
    }
  }

  fun setNativeWebSearchSessionApproved(
    sessionId: String,
    approved: Boolean,
  ) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null || workspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val key = nativeWebSearchApprovalExtensionKey(sessionId)
      val updatedExtensions = if (approved) {
        workspace.extensions + (key to "true")
      } else {
        workspace.extensions - key
      }
      if (updatedExtensions == workspace.extensions) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          extensions = updatedExtensions,
          recordVersion = workspace.recordVersion + 1,
          updatedAtEpochMs = now,
        ),
        result = Unit,
      )
    }
  }

  fun setSessionScopedStatePresent(
    sessionId: String,
    present: Boolean,
  ) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null || workspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val key = sessionScopedStateExtensionKey(sessionId)
      val updatedExtensions = if (present) {
        workspace.extensions + (key to "true")
      } else {
        workspace.extensions - key
      }
      if (updatedExtensions == workspace.extensions) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          extensions = updatedExtensions,
          recordVersion = workspace.recordVersion + 1,
          updatedAtEpochMs = now,
        ),
        result = Unit,
      )
    }
  }

  fun replaceTodos(
    sessionId: String,
    todos: List<AgentTodoEntry>,
  ) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val normalizedTodos = normalizeTodos(todos)
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null || workspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val updatedWorkspace = workspaceWithTodos(
        workspace = workspace,
        sessionId = sessionId,
        todos = normalizedTodos,
        updatedAtEpochMs = now,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = Unit,
        write = updatedWorkspace != workspace,
      )
    }
  }

  fun replaceWorkingState(
    sessionId: String,
    workingState: WorkingState,
  ) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null || workspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val updatedWorkspace = workspaceWithWorkingState(
        workspace = workspace,
        sessionId = sessionId,
        workingState = workingState,
        updatedAtEpochMs = now,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = Unit,
        write = updatedWorkspace != workspace,
      )
    }
  }

  fun enqueuePendingUserInput(
    sessionId: String,
    text: String,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ): PendingUserInputEntry {
    val normalizedText = text.trim()
    require(normalizedText.isNotEmpty() || attachments.isNotEmpty()) {
      "enqueuePendingUserInput requires text or attachments."
    }
    require(sessionId.isNotBlank()) { "enqueuePendingUserInput sessionId must not be blank." }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val entry = PendingUserInputEntry(
      queueId = "queued-user-$now-${UUID.randomUUID().toString().take(8)}",
      text = normalizedText,
      attachments = attachments,
      createdAtEpochMs = now,
    )
    workspaceStore.update { workspace ->
      if (workspace == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = entry,
          write = false,
        )
      }
      val updatedWorkspace = workspaceWithPendingUserInputs(
        workspace = workspace,
        sessionId = sessionId,
        inputs = pendingUserInputsFrom(workspace = workspace, sessionId = sessionId) + entry,
        updatedAtEpochMs = now,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = entry,
      )
    }
    return entry
  }

  fun appendPendingUserInputAsSubmittedTurn(
    sessionId: String,
    queueId: String,
    assistantMessageId: String,
    assistantPlaceholderText: String,
  ): PendingUserInputEntry? {
    require(sessionId.isNotBlank()) { "appendPendingUserInputAsSubmittedTurn sessionId must not be blank." }
    require(queueId.isNotBlank()) { "appendPendingUserInputAsSubmittedTurn queueId must not be blank." }
    require(assistantMessageId.isNotBlank()) {
      "appendPendingUserInputAsSubmittedTurn assistantMessageId must not be blank."
    }
    val normalizedAssistantText = assistantPlaceholderText.trim()
    require(normalizedAssistantText.isNotEmpty()) {
      "appendPendingUserInputAsSubmittedTurn assistantPlaceholderText must not be blank."
    }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      if (workspace == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = null,
          write = false,
        )
      }
      val currentPending = pendingUserInputsFrom(workspace = workspace, sessionId = sessionId)
      val consumed = currentPending.firstOrNull { entry -> entry.queueId == queueId }
        ?: return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = null,
          write = false,
        )
      val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId }
        ?: return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = null,
          write = false,
        )
      val userMessage = ChatTranscriptMessageEntry(
        messageId = messageId(ChatTranscriptRole.USER.name.lowercase()),
        role = ChatTranscriptRole.USER,
        text = consumed.text.ifBlank { null },
        attachments = consumed.attachments,
        createdAtEpochMs = now,
      )
      val assistantMessage = ChatTranscriptMessageEntry(
        messageId = assistantMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = normalizedAssistantText,
        createdAtEpochMs = now,
      )
      val updatedMessages = currentSession.messages + listOf(userMessage, assistantMessage)
      val updatedSession = currentSession.copy(
        title = titleForSession(currentSession.title, updatedMessages),
        messages = updatedMessages,
        updatedAtEpochMs = now,
      )
      val updatedWorkspace = workspaceWithPendingUserInputs(
        workspace = replaceSession(
          workspace = workspace,
          updatedSession = updatedSession,
          activeSessionId = preservedActiveSessionId(
            workspace = workspace,
            fallbackSessionId = updatedSession.sessionId,
          ),
          updatedAtEpochMs = now,
        ),
        sessionId = sessionId,
        inputs = currentPending.filterNot { entry -> entry.queueId == queueId },
        updatedAtEpochMs = now,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = consumed,
      )
    }
  }

  fun clearPendingUserInputs(sessionId: String) {
    if (sessionId.isBlank()) {
      return
    }
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    workspaceStore.update { workspace ->
      if (workspace == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      val key = pendingUserInputExtensionKey(sessionId)
      if (key !in workspace.extensions) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = Unit,
          write = false,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          extensions = workspace.extensions - key,
          recordVersion = workspace.recordVersion + 1,
          updatedAtEpochMs = now,
        ),
        result = Unit,
      )
    }
  }

  fun mergeVoiceAttachmentMetadata(
    contentSha256: String,
    metadata: AppAgentWorkspaceVoiceMetadata,
  ): Boolean {
    val normalizedSha = contentSha256.trim().lowercase()
    if (normalizedSha.isEmpty()) {
      return false
    }
    val normalizedMetadata = AppAgentWorkspaceVoiceMetadata(
      durationMs = metadata.durationMs?.takeIf { value -> value >= 0L },
      waveformBars = metadata.waveformBars.map { value -> value.coerceIn(0, 100) },
      transcriptText = metadata.transcriptText?.trim()?.takeIf(String::isNotBlank),
    )
    if (
      normalizedMetadata.durationMs == null &&
      normalizedMetadata.waveformBars.isEmpty() &&
      normalizedMetadata.transcriptText.isNullOrBlank()
    ) {
      return false
    }

    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      if (workspace == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = false,
          write = false,
        )
      }
      var changed = false
      val updatedSessions = workspace.sessions.map { session ->
        var sessionChanged = false
        val updatedMessages = session.messages.map { message ->
          var messageChanged = false
          val updatedAttachments = message.attachments.map attachmentLoop@ { attachment ->
            if (
              attachment.kind != com.opencray.persistence.model.ChatAttachmentKind.VOICE ||
              attachment.contentSha256?.trim()?.lowercase() != normalizedSha
            ) {
              return@attachmentLoop attachment
            }
            val mergedDuration = attachment.durationMs ?: normalizedMetadata.durationMs
            val mergedWaveformBars = if (attachment.waveformBars.isEmpty()) {
              normalizedMetadata.waveformBars
            } else {
              attachment.waveformBars
            }
            val mergedTranscript = attachment.transcriptText ?: normalizedMetadata.transcriptText
            if (
              mergedDuration == attachment.durationMs &&
              mergedWaveformBars == attachment.waveformBars &&
              mergedTranscript == attachment.transcriptText
            ) {
              return@attachmentLoop attachment
            }
            changed = true
            messageChanged = true
            attachment.copy(
              durationMs = mergedDuration,
              waveformBars = mergedWaveformBars,
              transcriptText = mergedTranscript,
            )
          }
          if (messageChanged) {
            sessionChanged = true
            message.copy(attachments = updatedAttachments)
          } else {
            message
          }
        }
        if (sessionChanged) {
          session.copy(
            messages = updatedMessages,
            updatedAtEpochMs = maxOf(session.updatedAtEpochMs, now),
          )
        } else {
          session
        }
      }
      if (!changed) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = false,
          write = false,
        )
      }
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          sessions = updatedSessions.sortedByDescending { session -> session.updatedAtEpochMs },
          recordVersion = workspace.recordVersion + 1,
          updatedAtEpochMs = maxOf(workspace.updatedAtEpochMs, now),
        ),
        result = true,
      )
    }
  }

  private fun appendMessage(
    sessionId: String,
    role: ChatTranscriptRole,
    text: String,
    commandLabel: String?,
    attachments: List<ChatAttachmentEntry>,
    updateTitle: Boolean,
  ): AppendMessageResult {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val appendedMessage = ChatTranscriptMessageEntry(
      messageId = messageId(role.name.lowercase()),
      role = role,
      text = text.ifBlank { null },
      commandLabel = commandLabel,
      attachments = attachments,
      createdAtEpochMs = now,
    )
    return workspaceStore.update { workspace ->
      val current = workspaceAndSessionForAppend(
        workspace = workspace,
        sessionId = sessionId,
        now = now,
      )
      val currentWorkspace = current.workspace
      val currentSession = current.session
      val sessionUpdatedAt = maxOf(currentSession.updatedAtEpochMs, now)
      val workspaceUpdatedAt = maxOf(currentWorkspace.updatedAtEpochMs, sessionUpdatedAt)
      val updatedMessages = currentSession.messages + appendedMessage
      val updatedSession = currentSession.copy(
        title = if (updateTitle) titleForSession(currentSession.title, updatedMessages) else currentSession.title,
        messages = updatedMessages,
        updatedAtEpochMs = sessionUpdatedAt,
      )
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = updatedSession,
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = updatedSession.sessionId,
        ),
        updatedAtEpochMs = workspaceUpdatedAt,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = AppendMessageResult(
          state = ChatSessionsState(
            sessions = sessionsForUi(updatedWorkspace),
            activeSession = updatedSession,
          ),
          messageId = appendedMessage.messageId,
        ),
      )
    }
  }

  fun promptTemplateBody(templateId: String?): String? {
    if (templateId.isNullOrBlank()) return null
    return loadWorkspaceOrCreate().promptTemplates.firstOrNull { template -> template.templateId == templateId }?.body
  }

  private fun normalizedAttachmentLocalPath(localPath: String): String? =
    localPath
      .trim()
      .replace('\\', '/')
      .trim('/')
      .takeIf(String::isNotBlank)

  private fun normalizeTodos(todos: List<AgentTodoEntry>): List<AgentTodoEntry> =
    todos.mapIndexed { index, entry ->
      val normalizedContent = entry.content.trim()
      require(normalizedContent.isNotBlank()) {
        "Todo entry ${index + 1} content must not be blank."
      }
      val normalizedActiveForm = entry.activeForm?.trim()?.takeIf(String::isNotBlank)
      require(entry.status == AgentTodoStatus.IN_PROGRESS || normalizedActiveForm == null) {
        "Todo entry ${index + 1} can only set activeForm when status is in_progress."
      }
      AgentTodoEntry(
        content = normalizedContent,
        status = entry.status,
        activeForm = normalizedActiveForm,
      )
    }.also { normalized ->
      val firstIndexByContent = linkedMapOf<String, Int>()
      normalized.forEachIndexed { index, entry ->
        val duplicateIndex = firstIndexByContent.putIfAbsent(entry.content, index)
        val duplicateOrdinal = (duplicateIndex ?: 0) + 1
        require(duplicateIndex == null) {
          "Todo entry ${index + 1} duplicates todo $duplicateOrdinal content."
        }
      }
      require(normalized.count { entry -> entry.status == AgentTodoStatus.IN_PROGRESS } <= 1) {
        "Todo entries allow at most one in_progress todo at a time."
      }
    }

  private fun sessionsForUi(workspace: ChatWorkspaceRecord): List<SessionSummary> = workspace.sessions
    .sortedByDescending { it.updatedAtEpochMs }
    .map { session ->
      val lastVisibleMessage = session.messages
        .asReversed()
        .firstOrNull { message -> message.role != ChatTranscriptRole.SYSTEM }
      val preview = lastVisibleMessage
        ?.let(::previewForMessage)
        .orEmpty()
        .trim()

      SessionSummary(
        sessionId = session.sessionId,
        title = session.title,
        lastMessagePreview = preview.take(52),
        messageCount = session.messages.count { message ->
          message.role != ChatTranscriptRole.SYSTEM ||
            message.promptTemplateRefId != DEFAULT_SYSTEM_TEMPLATE_ID
        },
        lastMessageAtEpochMs = lastVisibleMessage?.createdAtEpochMs,
        createdAtEpochMs = session.createdAtEpochMs,
        updatedAtEpochMs = session.updatedAtEpochMs,
      )
    }

  private fun titleForSession(
    currentTitle: String,
    messages: List<ChatTranscriptMessageEntry>,
  ): String {
    if (currentTitle != DEFAULT_SESSION_TITLE) return currentTitle
    val firstUserText = messages.firstOrNull { it.role == ChatTranscriptRole.USER }
      ?.let(::previewForMessage)
      .orEmpty()
      .trim()
    if (firstUserText.isBlank()) return currentTitle
    return firstUserText.take(26)
  }

  private fun previewForMessage(message: ChatTranscriptMessageEntry): String {
    val text = message.text.orEmpty().trim()
    if (text.isNotBlank()) {
      return text
    }
    if (!message.commandLabel.isNullOrBlank()) {
      return message.commandLabel.orEmpty().trim()
    }
    if (message.attachments.isNotEmpty()) {
      return message.attachments.first().displayName
    }
    return ""
  }

  private fun branchTitleFor(sourceTitle: String): String = when {
    sourceTitle.endsWith(" branch") -> sourceTitle
    sourceTitle.length >= 25 -> sourceTitle.take(25) + " branch"
    else -> "$sourceTitle branch"
  }

  private fun copyTitleFor(sourceTitle: String): String = when {
    sourceTitle.endsWith(" copy") -> sourceTitle
    sourceTitle.length >= 27 -> sourceTitle.take(27) + " copy"
    else -> "$sourceTitle copy"
  }

  internal fun messageId(prefix: String): String = "$prefix-${nowEpochMs()}-${UUID.randomUUID().toString().take(8)}"

  internal data class ChatSessionsState(
    val sessions: List<SessionSummary>,
    val activeSession: ChatTranscriptSessionEntry,
  )

  internal data class AppendMessageResult(
    val state: ChatSessionsState,
    val messageId: String,
  )

  internal data class SessionSummary(
    val sessionId: String,
    val title: String,
    val lastMessagePreview: String,
    val messageCount: Int,
    val lastMessageAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
  )

  companion object {
    internal const val DEFAULT_SESSION_TITLE = "New chat"
    internal const val DIRECTORY_NAME = "chat-local-state"
    internal const val DEFAULT_SYSTEM_TEMPLATE_ID = "system.default.v1"
    internal const val DEFAULT_SYSTEM_TEMPLATE_VALUE =
      "You are OpenCray. Keep the session transcript complete and preserve user-visible context."

    fun fromContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): ChatSessionLocalStore = ChatSessionLocalStore(directoryForContext(context, directoryName))

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): ChatSessionLocalStore = fromAgent(pathResolver, agentId)

    fun directoryForContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): File = File(context.filesDir, directoryName)

    internal fun directoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).chatLocalStateRoot.toFile()

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): ChatSessionLocalStore = ChatSessionLocalStore(directoryForAgent(pathResolver, agentId))
  }
}

