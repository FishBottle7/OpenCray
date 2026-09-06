package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.model.PersistedChatSessionTranscript
import com.opencray.persistence.store.ChatWorkspaceStoreUpdate
import com.opencray.persistence.store.file.JsonFileChatSessionTranscriptStore
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.workingstate.WorkingState
import java.io.File
import java.util.UUID

/**
 * File-backed chat session store.
 *
 * Layout (v2, split storage):
 *  - `chat-workspace.json` holds session metadata only: ids, titles, timestamps, the
 *    denormalized `messageCount`/`lastMessagePreview`/`lastMessageAtEpochMs` drawer
 *    fields, `activeSessionId`, prompt templates, and the per-session `extensions`
 *    entries (pending inputs, todos, working state, ...). `sessions[].messages` is
 *    serialized as an empty array.
 *  - `chat-sessions/<encoded-session-id>/transcript.json` holds each session's message
 *    list under that session's own process file lock, sharing the same durable storage
 *    and cross-process lock protocol as the workspace record.
 *
 * Message mutations follow a strict two-phase, lock-ordered protocol so no path ever
 * holds two different store files' OS locks:
 *  1. transcript phase: read-modify-write of one session's transcript under that
 *     session's lock (produces the final message list plus denormalized metadata),
 *  2. workspace phase: metadata refresh under the workspace lock, re-reading the record
 *     so concurrent workspace edits (extensions, other sessions, selections) survive;
 *     a strictly newer workspace entry is never regressed by our computed metadata.
 *
 * Migrations from the legacy single-file layout run inside [loadWorkspaceOrCreate]
 * (the choke point every read/write path goes through); see
 * [loadWorkspaceOrCreate] for the phase ordering.
 */
internal open class ChatSessionLocalStore(
  private val directory: File,
  internal val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  internal val workspaceStore = JsonFileChatWorkspaceStore(directory)
  internal val transcriptStore = createTranscriptStore(directory)

  protected open fun createTranscriptStore(directory: File): JsonFileChatSessionTranscriptStore =
    JsonFileChatSessionTranscriptStore(directory)

  fun loadState(): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = checkNotNull(activeSessionFrom(workspace)) {
      "Expected chat workspace to have an active session."
    }
    return ChatSessionsState(
      sessions = sessionsForUi(workspace),
      activeSession = hydrateSession(activeSession),
    )
  }

  /** Read-only workspace access for probe chains; never seeds a missing record. */
  internal fun loadWorkspaceRecord(): ChatWorkspaceRecord = workspaceStore.load()
    ?: seedWorkspaceRecord(nowEpochMs())

  fun createSession(): ChatSessionsState {
    loadWorkspaceOrCreate()
    var reuseAttempts = 0
    while (true) {
      val now = nowEpochMs()
      val snapshot = workspaceStore.load()
      val reusableSession = snapshot?.let { reusableEmptySessionFrom(it) }
      if (reusableSession != null && reuseAttempts < MAX_CREATE_SESSION_REUSE_ATTEMPTS) {
        reuseAttempts += 1
        val reused = tryReuseEmptySession(reusableSession, now)
        if (reused != null) {
          return reused
        }
        continue
      }
      val seededSession = newSeededSession(now)
      return workspaceStore.update { workspace ->
        val currentWorkspace = workspace ?: seedWorkspaceRecord(now)
        if (currentWorkspace.sessions.any { it.sessionId == seededSession.sessionId }) {
          return@update ChatWorkspaceStoreUpdate(
            record = currentWorkspace,
            result = ChatSessionsState(
              sessions = sessionsForUi(currentWorkspace),
              activeSession = hydratedActiveSessionFor(currentWorkspace, seededSession),
            ),
            write = false,
          )
        }
        val updatedWorkspace = replaceSession(
          workspace = currentWorkspace,
          updatedSession = seededSession.asMetadataOnly(),
          activeSessionId = seededSession.sessionId,
          updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, now),
        )
        ChatWorkspaceStoreUpdate(
          record = updatedWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(updatedWorkspace),
            activeSession = seededSession,
          ),
        )
      }
    }
  }

  private fun tryReuseEmptySession(
    candidate: ChatTranscriptSessionEntry,
    now: Long,
  ): ChatSessionsState? = workspaceStore.update { workspace ->
    val currentWorkspace = workspace ?: return@update ChatWorkspaceStoreUpdate(
      record = workspace,
      result = null,
      write = false,
    )
    val freshSession = currentWorkspace.sessions.firstOrNull { it.sessionId == candidate.sessionId }
    if (
      freshSession == null ||
      !isMetadataReusableEmptySession(currentWorkspace, freshSession) ||
      freshSession.messages.isNotEmpty()
    ) {
      // A concurrent writer either removed the candidate or filled it in between our
      // snapshot and this lock. Retry the decision with a fresh read.
      return@update ChatWorkspaceStoreUpdate(
        record = currentWorkspace,
        result = null,
        write = false,
      )
    }
    val updated = workspaceWithReusedEmptySession(
      workspace = currentWorkspace,
      session = freshSession,
      now = now,
    )
    ChatWorkspaceStoreUpdate(
      record = updated.workspace,
      result = ChatSessionsState(
        sessions = sessionsForUi(updated.workspace),
        activeSession = hydrateSession(updated.session),
      ),
    )
  }

  fun loadSession(sessionId: String): ChatTranscriptSessionEntry? {
    val workspace = loadWorkspaceOrCreate()
    val session = workspace.sessions.firstOrNull { session -> session.sessionId == sessionId }
    return session?.let(::hydrateSession)
  }

  fun referencedAttachmentLocalPaths(): Set<String> {
    val workspace = loadWorkspaceOrCreate()
    val referencedPaths = linkedSetOf<String>()
    workspace.sessions.forEach { session ->
      transcriptMessagesFor(session).forEach { message ->
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
    return workspace.sessions
      .firstOrNull { entry -> entry.sessionId == sessionId }
      ?.let { session ->
        isReusableEmptySession(
          workspace = workspace,
          session = session,
        )
      }
      ?: false
  }

  /**
   * Single-read variant for callers that already hold the workspace record.
   *
   * Every [loadWorkspaceOrCreate] re-reads and re-deserializes the whole
   * workspace file under a file lock, so probe chains that consult several
   * store helpers must share one record instead of paying the read per call.
   */
  internal fun isReusableEmptySession(
    workspace: ChatWorkspaceRecord,
    sessionId: String,
  ): Boolean {
    if (sessionId.isBlank()) {
      return false
    }
    return workspace.sessions
      .firstOrNull { entry -> entry.sessionId == sessionId }
      ?.let { session ->
        isReusableEmptySession(
          workspace = workspace,
          session = session,
        )
      }
      ?: false
  }

  fun selectSession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: seedWorkspaceRecord(now)
      val activeSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId }
        ?: activeSessionFrom(currentWorkspace)
      if (activeSession == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(currentWorkspace),
            activeSession = hydratedActiveSessionFor(currentWorkspace, null),
          ),
          write = false,
        )
      }
      val updatedWorkspace = currentWorkspace.copy(
        activeSessionId = activeSession.sessionId,
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(now, currentWorkspace.updatedAtEpochMs),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = hydrateSession(activeSession),
        ),
      )
    }
  }

  fun copySession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val sourceMetadata = workspaceStore.load()?.sessions?.firstOrNull { it.sessionId == sessionId }
    if (sourceMetadata == null) {
      return unchangedState()
    }
    val copiedAt = maxOf(now, sourceMetadata.updatedAtEpochMs)
    val copiedSessionId = "session-${copiedAt}-${UUID.randomUUID().toString().take(8)}"
    val copiedMessages = transcriptMessagesFor(sourceMetadata)
    transcriptStore.save(
      PersistedChatSessionTranscript(
        sessionId = copiedSessionId,
        messages = copiedMessages,
        createdAtEpochMs = copiedAt,
        updatedAtEpochMs = copiedAt,
      ),
    )
    return workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: return@update ChatWorkspaceStoreUpdate(
        record = workspace,
        result = unchangedStateFor(workspace),
        write = false,
      )
      val currentSource = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId }
      if (currentSource == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = unchangedStateFor(currentWorkspace),
          write = false,
        )
      }
      val copiedSession = currentSource
        .withMessagesAndMetadata(copiedMessages)
        .copy(
          sessionId = copiedSessionId,
          title = copyTitleFor(currentSource.title),
          createdAtEpochMs = copiedAt,
          updatedAtEpochMs = copiedAt,
        )
        .asMetadataOnly()
      val updatedWorkspace = currentWorkspace.copy(
        sessions = (currentWorkspace.sessions.filterNot { it.sessionId == copiedSessionId } + copiedSession)
          .sortedByDescending { it.updatedAtEpochMs },
        activeSessionId = copiedSessionId,
        extensions = copySessionExtensions(
          extensions = currentWorkspace.extensions,
          sourceSessionId = currentSource.sessionId,
          targetSessionId = copiedSessionId,
          includeWorkingState = true,
        ),
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, copiedAt),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = hydrateSession(copiedSession),
        ),
      )
    }
  }

  fun deleteSession(sessionId: String): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    // When this deletion would empty the workspace, seed the replacement transcript
    // before the workspace update so the new session never exists without one.
    val snapshot = workspaceStore.load()
    val willEmptyWorkspace = snapshot != null &&
      snapshot.sessions.map(ChatTranscriptSessionEntry::sessionId).toSet() == setOf(sessionId)
    val replacementSession = if (willEmptyWorkspace) newSeededSession(now) else null
    val result = workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: return@update ChatWorkspaceStoreUpdate(
        record = workspace,
        result = unchangedStateFor(workspace),
        write = false,
      )
      if (currentWorkspace.sessions.none { session -> session.sessionId == sessionId }) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = unchangedStateFor(currentWorkspace),
          write = false,
        )
      }
      val remainingSessions = currentWorkspace.sessions.filterNot { session -> session.sessionId == sessionId }
      if (remainingSessions.isEmpty()) {
        val replacement = (replacementSession ?: newSessionEntry(now)).asMetadataOnly()
        val updatedWorkspace = currentWorkspace.copy(
          sessions = listOf(replacement),
          activeSessionId = replacement.sessionId,
          extensions = currentWorkspace.extensions - pendingUserInputExtensionKey(sessionId) -
            todoExtensionKey(sessionId) - archivedTodoExtensionKey(sessionId) -
            workingStateExtensionKey(sessionId) - maintainedContextWindowTokensExtensionKey(sessionId) -
            nativeWebSearchApprovalExtensionKey(sessionId) - sessionScopedStateExtensionKey(sessionId),
          recordVersion = currentWorkspace.recordVersion + 1,
          updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, now),
        )
        return@update ChatWorkspaceStoreUpdate(
          record = updatedWorkspace,
          result = ChatSessionsState(
            sessions = sessionsForUi(updatedWorkspace),
            activeSession = hydrateSession(replacement),
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
          activeSession = hydrateSession(nextActiveSession),
        ),
      )
    }
    transcriptStore.deleteSessionTranscript(sessionId)
    return result
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
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      val anchorIndex = messages.indexOfFirst { message -> message.messageId == normalizedAnchorMessageId }
      if (
        anchorIndex < 0 ||
        normalizedMessageId != null &&
        messages.any { message -> message.messageId == normalizedMessageId }
      ) {
        SessionTranscriptMutation.unchanged(session, messages)
      } else {
        SessionTranscriptMutation.changed(
          session = session,
          messages = buildList(messages.size + 1) {
            addAll(messages.take(anchorIndex))
            add(insertedMessage)
            addAll(messages.drop(anchorIndex))
          },
        )
      }
    }.asState()
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
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      if (messages.none { message -> message.messageId == messageId }) {
        SessionTranscriptMutation.unchanged(session, messages)
      } else {
        SessionTranscriptMutation.changed(
          session = session,
          messages = messages.map { message ->
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
          },
        )
      }
    }.asState()
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
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      val messageIndex = messages.indexOfFirst { message -> message.messageId == messageId }
      if (messageIndex < 0) {
        SessionTranscriptMutation.unchanged(session, messages)
      } else {
        SessionTranscriptMutation.changed(
          session = session,
          messages = messages.take(messageIndex) + messages[messageIndex].copy(
            role = role,
            text = trimmed,
            promptTemplateRefId = null,
          ),
        )
      }
    }.asState()
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
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      val updatedMessages = messages.filterNot { it.messageId in normalizedMessageIds }
      if (updatedMessages == messages) {
        SessionTranscriptMutation.unchanged(session, messages)
      } else {
        SessionTranscriptMutation.changed(session, updatedMessages)
      }
    }.asState()
  }

  fun recallMessageCascade(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      val recallIndex = messages.indexOfFirst { message -> message.messageId == messageId }
      val recalledMessage = messages.getOrNull(recallIndex)
      if (recallIndex < 0 || recalledMessage?.role != ChatTranscriptRole.USER) {
        SessionTranscriptMutation.unchanged(session, messages)
      } else {
        SessionTranscriptMutation.changed(session, messages.take(recallIndex))
      }
    }.asState()
  }

  fun branchSessionFromMessage(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val snapshot = workspaceStore.load()
    val sourceMetadata = snapshot?.sessions?.firstOrNull { it.sessionId == sessionId }
      ?: snapshot?.let(::activeSessionFrom)
    if (sourceMetadata == null) {
      return unchangedState()
    }
    val branchedAt = maxOf(now, sourceMetadata.updatedAtEpochMs)
    val branchSessionId = "session-${branchedAt}-${UUID.randomUUID().toString().take(8)}"
    val sourceMessages = transcriptMessagesFor(sourceMetadata)
    val branchUntilIndex = sourceMessages.indexOfFirst { it.messageId == messageId }
    val branchMessages = if (branchUntilIndex >= 0) {
      sourceMessages.take(branchUntilIndex + 1)
    } else {
      sourceMessages
    }
    transcriptStore.save(
      PersistedChatSessionTranscript(
        sessionId = branchSessionId,
        messages = branchMessages,
        createdAtEpochMs = branchedAt,
        updatedAtEpochMs = branchedAt,
      ),
    )
    return workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: return@update ChatWorkspaceStoreUpdate(
        record = workspace,
        result = unchangedStateFor(workspace),
        write = false,
      )
      val currentSource = currentWorkspace.sessions.firstOrNull { it.sessionId == sourceMetadata.sessionId }
        ?: return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = unchangedStateFor(currentWorkspace),
          write = false,
        )
      val branchSession = ChatTranscriptSessionEntry(
        sessionId = branchSessionId,
        title = branchTitleFor(currentSource.title),
        createdAtEpochMs = branchedAt,
        updatedAtEpochMs = branchedAt,
      ).withMessagesAndMetadata(branchMessages).asMetadataOnly()
      val updatedWorkspace = currentWorkspace.copy(
        sessions = (currentWorkspace.sessions.filterNot { it.sessionId == branchSessionId } + branchSession)
          .sortedByDescending { it.updatedAtEpochMs },
        activeSessionId = branchSessionId,
        extensions = copySessionExtensions(
          extensions = currentWorkspace.extensions,
          sourceSessionId = currentSource.sessionId,
          targetSessionId = branchSessionId,
          includeWorkingState = false,
        ),
        recordVersion = currentWorkspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, branchedAt),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = ChatSessionsState(
          sessions = sessionsForUi(updatedWorkspace),
          activeSession = hydrateSession(branchSession),
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
    return mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = false,
    ) { session, messages ->
      val updatedMessages = messages + listOf(userMessage, assistantMessage)
      SessionTranscriptMutation.changed(
        session = session,
        messages = updatedMessages,
        title = titleForSession(session.title, updatedMessages),
      )
    }.asState()
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
    // Phase 0: atomically consume the queue entry under the workspace lock, so two
    // concurrent consumers can never both append the same queued turn.
    val consumed = workspaceStore.update { workspace ->
      if (workspace == null ||
        workspace.sessions.none { session -> session.sessionId == sessionId }
      ) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = null,
          write = false,
        )
      }
      val currentPending = pendingUserInputsFrom(workspace = workspace, sessionId = sessionId)
      val entry = currentPending.firstOrNull { it.queueId == queueId }
        ?: return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = null,
          write = false,
        )
      val updatedWorkspace = workspaceWithPendingUserInputs(
        workspace = workspace,
        sessionId = sessionId,
        inputs = currentPending.filterNot { it.queueId == queueId },
        updatedAtEpochMs = now,
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = entry,
      )
    } ?: return null

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
    mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = false,
    ) { session, messages ->
      val updatedMessages = messages + listOf(userMessage, assistantMessage)
      SessionTranscriptMutation.changed(
        session = session,
        messages = updatedMessages,
        title = titleForSession(session.title, updatedMessages),
      )
    }
    return consumed
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
    val snapshot = workspaceStore.load()
    // Transcript phase: merge matching voice attachments with an atomic read-merge-write
    // under each affected session's own lock, one session at a time.
    val updatedSessions = linkedMapOf<String, ChatTranscriptSessionEntry>()
    snapshot?.sessions?.forEach { metadataEntry ->
      val mergedSession = transcriptStore.update(metadataEntry.sessionId) { current ->
        val base = metadataEntry.withMessagesAndMetadata(current?.messages.orEmpty())
        var sessionChanged = false
        val updatedMessages = current?.messages.orEmpty().map { message ->
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
            sessionChanged = true
            messageChanged = true
            attachment.copy(
              durationMs = mergedDuration,
              waveformBars = mergedWaveformBars,
              transcriptText = mergedTranscript,
            )
          }
          if (messageChanged) {
            message.copy(attachments = updatedAttachments)
          } else {
            message
          }
        }
        if (!sessionChanged) {
          RecordStorageUpdate(
            value = current,
            result = null,
            write = false,
          )
        } else {
          val sessionUpdatedAt = maxOf(
            base.updatedAtEpochMs,
            current?.updatedAtEpochMs ?: 0L,
            now,
          )
          val updatedSession = base
            .withMessagesAndMetadata(updatedMessages)
            .copy(updatedAtEpochMs = sessionUpdatedAt)
          RecordStorageUpdate(
            value = PersistedChatSessionTranscript(
              sessionId = metadataEntry.sessionId,
              messages = updatedMessages,
              recordVersion = (current?.recordVersion ?: 0L) + 1L,
              createdAtEpochMs = current?.createdAtEpochMs ?: base.createdAtEpochMs,
              updatedAtEpochMs = sessionUpdatedAt,
            ),
            result = updatedSession,
          )
        }
      }
      if (mergedSession != null) {
        updatedSessions[metadataEntry.sessionId] = mergedSession
      }
    }
    if (updatedSessions.isEmpty()) {
      return false
    }
    // Workspace phase: refresh the affected sessions' metadata under the workspace lock.
    return workspaceStore.update { workspace ->
      if (workspace == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = workspace,
          result = false,
          write = false,
        )
      }
      var changed = false
      val updatedEntries = workspace.sessions.map { session ->
        val updated = updatedSessions[session.sessionId] ?: return@map session
        changed = true
        mergeSessionMetadata(
          existing = session,
          computed = updated.asMetadataOnly(),
        )
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
          sessions = updatedEntries.sortedByDescending { session -> session.updatedAtEpochMs },
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
    val mutation = mutateSessionTranscript(
      sessionId = sessionId,
      now = now,
      fallbackToActiveSession = true,
    ) { session, messages ->
      val updatedMessages = messages + appendedMessage
      SessionTranscriptMutation.changed(
        session = session,
        messages = updatedMessages,
        title = if (updateTitle) titleForSession(session.title, updatedMessages) else session.title,
      )
    }
    return AppendMessageResult(
      state = mutation.asState(),
      messageId = appendedMessage.messageId,
    )
  }

  fun promptTemplateBody(templateId: String?): String? {
    if (templateId.isNullOrBlank()) return null
    return loadWorkspaceOrCreate().promptTemplates.firstOrNull { template -> template.templateId == templateId }?.body
  }

  /**
   * Two-phase, lock-ordered message mutation (see class docs). The transform runs in
   * phase 1 under the session's transcript lock and receives the hydrated base entry
   * plus its current messages; returning an unchanged mutation skips both writes.
   *
   * `fallbackToActiveSession` mirrors the legacy behavior of several mutators, which
   * operated on the active session when the requested id was missing; append paths
   * instead require the session to exist and throw the original error.
   */
  private fun mutateSessionTranscript(
    sessionId: String,
    now: Long,
    fallbackToActiveSession: Boolean,
    transform: (ChatTranscriptSessionEntry, List<ChatTranscriptMessageEntry>) -> SessionTranscriptMutation,
  ): SessionMutationOutcome {
    val snapshot = workspaceStore.load()
    val requestedEntry = snapshot?.sessions?.firstOrNull { it.sessionId == sessionId }
    val baseEntry = requestedEntry
      ?: if (fallbackToActiveSession) snapshot?.let(::activeSessionFrom) else null
    if (baseEntry == null) {
      if (fallbackToActiveSession) {
        return SessionMutationOutcome.Unchanged(
          session = snapshot?.let(::activeSessionFrom).let { active ->
            active?.copy(messages = emptyList())
          } ?: newSessionEntry(now),
          workspace = snapshot ?: seedWorkspaceRecord(now),
        )
      }
      throwMissingChatSession(sessionId)
    }
    val effectiveSessionId = requireNotNull(baseEntry).sessionId
    val committed = transcriptStore.update(effectiveSessionId) { current ->
      val base = baseEntry.withMessagesAndMetadata(current?.messages.orEmpty())
      val mutation = transform(base, current?.messages.orEmpty())
      if (!mutation.changed) {
        RecordStorageUpdate(
          value = current,
          result = SessionTranscriptMutation.unchanged(base, current?.messages.orEmpty()),
          write = false,
        )
      } else {
        val sessionUpdatedAt = maxOf(
          base.updatedAtEpochMs,
          current?.updatedAtEpochMs ?: 0L,
          now,
        )
        val updatedEntry = base
          .copy(title = mutation.title ?: base.title)
          .withMessagesAndMetadata(mutation.messages)
          .copy(updatedAtEpochMs = sessionUpdatedAt)
        RecordStorageUpdate(
          value = PersistedChatSessionTranscript(
            sessionId = effectiveSessionId,
            messages = mutation.messages,
            recordVersion = (current?.recordVersion ?: 0L) + 1L,
            createdAtEpochMs = current?.createdAtEpochMs ?: base.createdAtEpochMs,
            updatedAtEpochMs = sessionUpdatedAt,
          ),
          result = SessionTranscriptMutation.changed(
            session = updatedEntry,
            messages = mutation.messages,
            title = updatedEntry.title,
          ),
          write = true,
        )
      }
    }
    // Workspace phase: refresh the session's denormalized metadata.
    return workspaceStore.update { workspace ->
      val currentWorkspace = workspace ?: return@update ChatWorkspaceStoreUpdate(
        record = workspace,
        result = SessionMutationOutcome.Unchanged(
          session = committed.session,
          workspace = snapshot ?: seedWorkspaceRecord(now),
        ),
        write = false,
      )
      val existing = currentWorkspace.sessions.firstOrNull { it.sessionId == effectiveSessionId }
      if (existing == null) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = SessionMutationOutcome.Unchanged(
            session = committed.session,
            workspace = currentWorkspace,
          ),
          write = false,
        )
      }
      if (!committed.changed) {
        return@update ChatWorkspaceStoreUpdate(
          record = currentWorkspace,
          result = SessionMutationOutcome.Unchanged(
            session = committed.session,
            workspace = currentWorkspace,
          ),
          write = false,
        )
      }
      val merged = mergeSessionMetadata(existing = existing, computed = committed.session)
      val updatedWorkspace = replaceSession(
        workspace = currentWorkspace,
        updatedSession = merged.asMetadataOnly(),
        activeSessionId = preservedActiveSessionId(
          workspace = currentWorkspace,
          fallbackSessionId = merged.sessionId,
        ),
        updatedAtEpochMs = maxOf(currentWorkspace.updatedAtEpochMs, merged.updatedAtEpochMs),
      )
      ChatWorkspaceStoreUpdate(
        record = updatedWorkspace,
        result = SessionMutationOutcome.Committed(
          session = merged,
          workspace = updatedWorkspace,
        ),
      )
    }
  }

  private fun throwMissingChatSession(sessionId: String): Nothing =
    throw IllegalStateException(
      "Chat session '$sessionId' no longer exists; rejecting transcript append.",
    )

  private fun unchangedState(): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    return unchangedStateFor(workspace)
  }

  private fun unchangedStateFor(workspace: ChatWorkspaceRecord?): ChatSessionsState {
    val resolved = workspace ?: seedWorkspaceRecord(nowEpochMs())
    val active = activeSessionFrom(resolved) ?: newSessionEntry(nowEpochMs())
    return ChatSessionsState(
      sessions = sessionsForUi(resolved),
      activeSession = hydrateSession(active),
    )
  }

  private fun hydratedActiveSessionFor(
    workspace: ChatWorkspaceRecord,
    seededSession: ChatTranscriptSessionEntry?,
  ): ChatTranscriptSessionEntry {
    val active = activeSessionFrom(workspace) ?: seededSession ?: newSessionEntry(nowEpochMs())
    return hydrateSession(active)
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
      SessionSummary(
        sessionId = session.sessionId,
        title = session.title,
        lastMessagePreview = session.lastMessagePreview,
        messageCount = session.messageCount,
        lastMessageAtEpochMs = session.lastMessageAtEpochMs,
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

  /** Hydrates a metadata entry with its transcript messages (one session-scoped read). */
  internal fun hydrateSession(session: ChatTranscriptSessionEntry): ChatTranscriptSessionEntry =
    if (session.messages.isNotEmpty()) {
      session
    } else {
      session.copy(messages = transcriptMessagesFor(session))
    }

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

  /** Denormalized drawer metadata derived from a session's message list. */
  internal data class SessionTranscriptMetadata(
    val messageCount: Int,
    val lastMessagePreview: String,
    val lastMessageAtEpochMs: Long?,
  )

  private data class SessionTranscriptMutation(
    val session: ChatTranscriptSessionEntry,
    val messages: List<ChatTranscriptMessageEntry>,
    val title: String?,
    val changed: Boolean,
  ) {
    companion object {
      fun unchanged(
        session: ChatTranscriptSessionEntry,
        messages: List<ChatTranscriptMessageEntry>,
      ): SessionTranscriptMutation = SessionTranscriptMutation(
        session = session,
        messages = messages,
        title = null,
        changed = false,
      )

      fun changed(
        session: ChatTranscriptSessionEntry,
        messages: List<ChatTranscriptMessageEntry>,
        title: String? = null,
      ): SessionTranscriptMutation = SessionTranscriptMutation(
        session = session,
        messages = messages,
        title = title,
        changed = true,
      )
    }
  }

  private sealed interface SessionMutationOutcome {
    val session: ChatTranscriptSessionEntry
    val workspace: ChatWorkspaceRecord

    data class Committed(
      override val session: ChatTranscriptSessionEntry,
      override val workspace: ChatWorkspaceRecord,
    ) : SessionMutationOutcome

    data class Unchanged(
      override val session: ChatTranscriptSessionEntry,
      override val workspace: ChatWorkspaceRecord,
    ) : SessionMutationOutcome
  }

  private fun SessionMutationOutcome.asState(): ChatSessionsState = ChatSessionsState(
    sessions = sessionsForUi(workspace),
    activeSession = session,
  )

  companion object {
    internal const val DEFAULT_SESSION_TITLE = "New chat"
    internal const val DIRECTORY_NAME = "chat-local-state"
    internal const val DEFAULT_SYSTEM_TEMPLATE_ID = "system.default.v1"
    internal const val DEFAULT_SYSTEM_TEMPLATE_VALUE =
      "You are OpenCray. Keep the session transcript complete and preserve user-visible context."
    private const val MAX_CREATE_SESSION_REUSE_ATTEMPTS = 16

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

/** Reads a session's message list: inline for legacy records, else its transcript file. */
internal fun ChatSessionLocalStore.transcriptMessagesFor(
  session: ChatTranscriptSessionEntry,
): List<ChatTranscriptMessageEntry> =
  if (session.messages.isNotEmpty()) {
    session.messages
  } else {
    transcriptStore.load(session.sessionId)?.messages.orEmpty()
  }

/** Metadata-only reusable-empty check that never reads transcript files. */
internal fun isMetadataReusableEmptySession(
  workspace: ChatWorkspaceRecord,
  session: ChatTranscriptSessionEntry,
): Boolean {
  if (session.title != ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
    return false
  }
  if (pendingUserInputsFrom(workspace = workspace, sessionId = session.sessionId).isNotEmpty()) {
    return false
  }
  if (todoSnapshotFrom(workspace = workspace, sessionId = session.sessionId).state != ChatSessionTodoState.EMPTY) {
    return false
  }
  if (!workingStateFrom(workspace = workspace, sessionId = session.sessionId).isEmpty) {
    return false
  }
  if (nativeWebSearchApprovalFrom(workspace = workspace, sessionId = session.sessionId)) {
    return false
  }
  if (sessionScopedStatePresentFrom(workspace = workspace, sessionId = session.sessionId)) {
    return false
  }
  return session.messageCount == 0
}
