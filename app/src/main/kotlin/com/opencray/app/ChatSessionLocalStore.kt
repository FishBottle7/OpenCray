package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatPromptTemplateEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import java.io.File
import java.util.UUID

internal class ChatSessionLocalStore(
  private val directory: File,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  private val workspaceStore = JsonFileChatWorkspaceStore(directory)

  fun loadState(): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = activeSessionFrom(workspace) ?: createSessionInternal(workspace).activeSession
    return ChatSessionsState(
      sessions = sessionsForUi(workspaceStore.load() ?: workspace),
      activeSession = activeSession,
    )
  }

  fun createSession(): ChatSessionsState = createSessionInternal(loadWorkspaceOrCreate())

  fun selectSession(sessionId: String): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val updatedWorkspace = workspace.copy(
      activeSessionId = activeSession.sessionId,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = maxOf(nowEpochMs(), workspace.updatedAtEpochMs),
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = activeSession,
    )
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

  fun replaceMessage(
    sessionId: String,
    messageId: String,
    role: ChatTranscriptRole,
    text: String,
  ): ChatSessionsState {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "replaceMessage text must not be blank." }

    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    if (currentSession.messages.none { message -> message.messageId == messageId }) {
      return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = currentSession,
      )
    }
    val now = nowEpochMs()
    val updatedMessages = currentSession.messages.map { message ->
      if (message.messageId == messageId) {
        message.copy(
          role = role,
          text = trimmed,
          promptTemplateRefId = null,
        )
      } else {
        message
      }
    }
    val updatedSession = currentSession.copy(
      messages = updatedMessages,
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = updatedSession.sessionId,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = updatedSession,
    )
  }

  fun replaceMessageAndPruneTail(
    sessionId: String,
    messageId: String,
    role: ChatTranscriptRole,
    text: String,
  ): ChatSessionsState {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "replaceMessageAndPruneTail text must not be blank." }

    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val messageIndex = currentSession.messages.indexOfFirst { message -> message.messageId == messageId }
    if (messageIndex < 0) {
      return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = currentSession,
      )
    }

    val updatedMessages = currentSession.messages.take(messageIndex) + currentSession.messages[messageIndex].copy(
      role = role,
      text = trimmed,
      promptTemplateRefId = null,
    )
    val now = nowEpochMs()
    val updatedSession = currentSession.copy(
      messages = updatedMessages,
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = updatedSession.sessionId,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = updatedSession,
    )
  }

  fun deleteMessage(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val now = nowEpochMs()
    val updatedMessages = currentSession.messages.filterNot { it.messageId == messageId }
    val updatedSession = currentSession.copy(
      messages = updatedMessages,
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = updatedSession.sessionId,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = updatedSession,
    )
  }

  fun recallMessageCascade(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val recallIndex = currentSession.messages.indexOfFirst { message -> message.messageId == messageId }
    if (recallIndex < 0) {
      return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = currentSession,
      )
    }

    val recalledMessage = currentSession.messages[recallIndex]
    if (recalledMessage.role != ChatTranscriptRole.USER) {
      return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = currentSession,
      )
    }

    val now = nowEpochMs()
    val updatedSession = currentSession.copy(
      messages = currentSession.messages.take(recallIndex),
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = updatedSession.sessionId,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = updatedSession,
    )
  }

  fun branchSessionFromMessage(
    sessionId: String,
    messageId: String,
  ): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val sourceSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val branchUntilIndex = sourceSession.messages.indexOfFirst { it.messageId == messageId }
    val branchMessages = if (branchUntilIndex >= 0) {
      sourceSession.messages.take(branchUntilIndex + 1)
    } else {
      sourceSession.messages
    }
    val now = nowEpochMs()
    val branchSessionId = "session-${now}-${UUID.randomUUID().toString().take(8)}"
    val branchSession = ChatTranscriptSessionEntry(
      sessionId = branchSessionId,
      title = branchTitleFor(sourceSession.title),
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      messages = branchMessages,
    )
    val updatedWorkspace = workspace.copy(
      sessions = (workspace.sessions.filterNot { it.sessionId == branchSessionId } + branchSession)
        .sortedByDescending { it.updatedAtEpochMs },
      activeSessionId = branchSessionId,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = branchSession,
    )
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

  private fun appendMessage(
    sessionId: String,
    role: ChatTranscriptRole,
    text: String,
    commandLabel: String?,
    attachments: List<ChatAttachmentEntry>,
    updateTitle: Boolean,
  ): AppendMessageResult {
    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val now = nowEpochMs()
    val appendedMessage = ChatTranscriptMessageEntry(
      messageId = messageId(role.name.lowercase()),
      role = role,
      text = text.ifBlank { null },
      commandLabel = commandLabel,
      attachments = attachments,
      createdAtEpochMs = now,
    )
    val updatedMessages = currentSession.messages + appendedMessage
    val updatedSession = currentSession.copy(
      title = if (updateTitle) titleForSession(currentSession.title, updatedMessages) else currentSession.title,
      messages = updatedMessages,
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = updatedSession.sessionId,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return AppendMessageResult(
      state = ChatSessionsState(
        sessions = sessionsForUi(updatedWorkspace),
        activeSession = updatedSession,
      ),
      messageId = appendedMessage.messageId,
    )
  }

  fun promptTemplateBody(templateId: String?): String? {
    if (templateId.isNullOrBlank()) return null
    return loadWorkspaceOrCreate().promptTemplates.firstOrNull { template -> template.templateId == templateId }?.body
  }

  private fun createSessionInternal(workspace: ChatWorkspaceRecord): ChatSessionsState {
    val now = nowEpochMs()
    val sessionId = "session-${now}-${UUID.randomUUID().toString().take(8)}"
    val session = ChatTranscriptSessionEntry(
      sessionId = sessionId,
      title = DEFAULT_SESSION_TITLE,
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      messages = listOf(
        ChatTranscriptMessageEntry(
          messageId = messageId("system"),
          role = ChatTranscriptRole.SYSTEM,
          promptTemplateRefId = DEFAULT_SYSTEM_TEMPLATE_ID,
          createdAtEpochMs = now,
        ),
      ),
    )
    val updatedWorkspace = workspace.copy(
      sessions = (workspace.sessions.filterNot { it.sessionId == sessionId } + session)
        .sortedByDescending { it.updatedAtEpochMs },
      activeSessionId = sessionId,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = session,
    )
  }

  private fun loadWorkspaceOrCreate(): ChatWorkspaceRecord = workspaceStore.load() ?: createWorkspaceWithSeedSession()

  private fun createWorkspaceWithSeedSession(): ChatWorkspaceRecord {
    val now = nowEpochMs()
    val seedWorkspace = ChatWorkspaceRecord(
      sessions = emptyList(),
      promptTemplates = listOf(
        ChatPromptTemplateEntry(
          templateId = DEFAULT_SYSTEM_TEMPLATE_ID,
          label = "Default system prompt",
          body = DEFAULT_SYSTEM_TEMPLATE_VALUE,
          createdAtEpochMs = now,
        ),
      ),
      activeSessionId = null,
      recordVersion = 1,
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
    )
    createSessionInternal(seedWorkspace)
    return checkNotNull(workspaceStore.load()) { "Expected chat workspace to be created." }
  }

  private fun activeSessionFrom(workspace: ChatWorkspaceRecord): ChatTranscriptSessionEntry? =
    workspace.activeSessionId?.let { activeId -> workspace.sessions.firstOrNull { it.sessionId == activeId } }
      ?: workspace.sessions.maxByOrNull { it.updatedAtEpochMs }

  private fun replaceSession(
    workspace: ChatWorkspaceRecord,
    updatedSession: ChatTranscriptSessionEntry,
    activeSessionId: String,
    updatedAtEpochMs: Long,
  ): ChatWorkspaceRecord = workspace.copy(
    sessions = (workspace.sessions.filterNot { it.sessionId == updatedSession.sessionId } + updatedSession)
      .sortedByDescending { it.updatedAtEpochMs },
    activeSessionId = activeSessionId,
    recordVersion = workspace.recordVersion + 1,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private fun sessionsForUi(workspace: ChatWorkspaceRecord): List<SessionSummary> = workspace.sessions
    .sortedByDescending { it.updatedAtEpochMs }
    .map { session ->
      val preview = session.messages
        .asReversed()
        .firstOrNull { message -> message.role != ChatTranscriptRole.SYSTEM }
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

  private fun messageId(prefix: String): String = "$prefix-${nowEpochMs()}-${UUID.randomUUID().toString().take(8)}"

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
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
  )

  companion object {
    private const val DEFAULT_SESSION_TITLE = "New chat"
    internal const val DIRECTORY_NAME = "chat-local-state"
    internal const val DEFAULT_SYSTEM_TEMPLATE_ID = "system.default.v1"
    internal const val DEFAULT_SYSTEM_TEMPLATE_VALUE =
      "You are OpenCray. Keep the session transcript complete and preserve user-visible context."

    fun fromContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): ChatSessionLocalStore = ChatSessionLocalStore(directoryForContext(context, directoryName))

    fun directoryForContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): File = File(context.filesDir, directoryName)
  }
}
