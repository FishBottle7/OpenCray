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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal open class ChatSessionLocalStore(
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

  fun loadSession(sessionId: String): ChatTranscriptSessionEntry? =
    loadWorkspaceOrCreate().sessions.firstOrNull { session -> session.sessionId == sessionId }

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

  fun copySession(sessionId: String): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = activeSessionFrom(workspace) ?: createSessionInternal(workspace).activeSession
    val sourceSession = workspace.sessions.firstOrNull { it.sessionId == sessionId }
      ?: return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = activeSession,
      )
    val now = nowEpochMs()
    val copiedSessionId = "session-${now}-${UUID.randomUUID().toString().take(8)}"
    val copiedSession = sourceSession.copy(
      sessionId = copiedSessionId,
      title = copyTitleFor(sourceSession.title),
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = workspace.copy(
      sessions = (workspace.sessions.filterNot { it.sessionId == copiedSessionId } + copiedSession)
        .sortedByDescending { it.updatedAtEpochMs },
      activeSessionId = copiedSessionId,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = copiedSession,
    )
  }

  fun deleteSession(sessionId: String): ChatSessionsState {
    val workspace = loadWorkspaceOrCreate()
    val activeSession = activeSessionFrom(workspace) ?: createSessionInternal(workspace).activeSession
    if (workspace.sessions.none { session -> session.sessionId == sessionId }) {
      return ChatSessionsState(
        sessions = sessionsForUi(workspace),
        activeSession = activeSession,
      )
    }
    val remainingSessions = workspace.sessions.filterNot { session -> session.sessionId == sessionId }
    if (remainingSessions.isEmpty()) {
      return createSessionInternal(
        workspace.copy(
          sessions = emptyList(),
          activeSessionId = null,
          updatedAtEpochMs = nowEpochMs(),
        ),
      )
    }
    val now = nowEpochMs()
    val nextActiveSession = remainingSessions.firstOrNull { session ->
      session.sessionId == workspace.activeSessionId
    } ?: remainingSessions.maxByOrNull { it.updatedAtEpochMs }
      ?: remainingSessions.first()
    val updatedWorkspace = workspace.copy(
      sessions = remainingSessions.sortedByDescending { it.updatedAtEpochMs },
      activeSessionId = nextActiveSession.sessionId,
      extensions = workspace.extensions - pendingUserInputExtensionKey(sessionId),
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = nextActiveSession,
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
    text: String?,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ): ChatSessionsState {
    val trimmed = text?.trim().orEmpty()
    require(trimmed.isNotEmpty() || attachments.isNotEmpty()) {
      "replaceMessage requires text or attachments."
    }

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
      updatedAtEpochMs = now,
    )
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
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
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
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
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
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
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
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

  fun reserveMessageId(role: ChatTranscriptRole): String = messageId(role.name.lowercase())

  open fun appendSubmittedTurn(
    sessionId: String,
    userText: String,
    assistantMessageId: String,
    assistantPlaceholderText: String,
  ): ChatSessionsState {
    val normalizedUserText = userText.trim()
    val normalizedAssistantText = assistantPlaceholderText.trim()
    require(normalizedUserText.isNotEmpty()) { "appendSubmittedTurn userText must not be blank." }
    require(normalizedAssistantText.isNotEmpty()) { "appendSubmittedTurn assistantPlaceholderText must not be blank." }
    require(assistantMessageId.isNotBlank()) { "appendSubmittedTurn assistantMessageId must not be blank." }

    val workspace = loadWorkspaceOrCreate()
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val now = nowEpochMs()
    val userMessage = ChatTranscriptMessageEntry(
      messageId = messageId(ChatTranscriptRole.USER.name.lowercase()),
      role = ChatTranscriptRole.USER,
      text = normalizedUserText,
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
    val updatedWorkspace = replaceSession(
      workspace = workspace,
      updatedSession = updatedSession,
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
      updatedAtEpochMs = now,
    )
    workspaceStore.save(updatedWorkspace)
    return ChatSessionsState(
      sessions = sessionsForUi(updatedWorkspace),
      activeSession = updatedSession,
    )
  }

  fun loadPendingUserInputs(sessionId: String): List<PendingUserInputEntry> {
    if (sessionId.isBlank()) {
      return emptyList()
    }
    val workspace = loadWorkspaceOrCreate()
    return pendingUserInputsFrom(workspace = workspace, sessionId = sessionId)
  }

  fun enqueuePendingUserInput(
    sessionId: String,
    text: String,
  ): PendingUserInputEntry {
    val normalizedText = text.trim()
    require(normalizedText.isNotEmpty()) { "enqueuePendingUserInput text must not be blank." }
    require(sessionId.isNotBlank()) { "enqueuePendingUserInput sessionId must not be blank." }

    val workspace = loadWorkspaceOrCreate()
    val now = nowEpochMs()
    val entry = PendingUserInputEntry(
      queueId = "queued-user-$now-${UUID.randomUUID().toString().take(8)}",
      text = normalizedText,
      createdAtEpochMs = now,
    )
    workspaceStore.save(
      workspaceWithPendingUserInputs(
        workspace = workspace,
        sessionId = sessionId,
        inputs = pendingUserInputsFrom(workspace = workspace, sessionId = sessionId) + entry,
        updatedAtEpochMs = now,
      ),
    )
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

    val workspace = loadWorkspaceOrCreate()
    val currentPending = pendingUserInputsFrom(workspace = workspace, sessionId = sessionId)
    val consumed = currentPending.firstOrNull { entry -> entry.queueId == queueId } ?: return null
    val currentSession = workspace.sessions.firstOrNull { it.sessionId == sessionId } ?: activeSessionFrom(workspace)
      ?: createSessionInternal(workspace).activeSession
    val now = nowEpochMs()
    val userMessage = ChatTranscriptMessageEntry(
      messageId = messageId(ChatTranscriptRole.USER.name.lowercase()),
      role = ChatTranscriptRole.USER,
      text = consumed.text,
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
    workspaceStore.save(updatedWorkspace)
    return consumed
  }

  fun clearPendingUserInputs(sessionId: String) {
    if (sessionId.isBlank()) {
      return
    }
    val workspace = loadWorkspaceOrCreate()
    val key = pendingUserInputExtensionKey(sessionId)
    if (key !in workspace.extensions) {
      return
    }
    val now = nowEpochMs()
    workspaceStore.save(
      workspace.copy(
        extensions = workspace.extensions - key,
        recordVersion = workspace.recordVersion + 1,
        updatedAtEpochMs = now,
      ),
    )
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

    val workspace = loadWorkspaceOrCreate()
    var changed = false
    val now = nowEpochMs()
    val updatedSessions = workspace.sessions.map { session ->
      var sessionChanged = false
      val updatedMessages = session.messages.map { message ->
        var messageChanged = false
        val updatedAttachments = message.attachments.map { attachment ->
          if (
            attachment.kind != com.opencray.persistence.model.ChatAttachmentKind.VOICE ||
            attachment.contentSha256?.trim()?.lowercase() != normalizedSha
          ) {
            return@map attachment
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
            return@map attachment
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
      return false
    }
    workspaceStore.save(
      workspace.copy(
        sessions = updatedSessions.sortedByDescending { session -> session.updatedAtEpochMs },
        recordVersion = workspace.recordVersion + 1,
        updatedAtEpochMs = maxOf(workspace.updatedAtEpochMs, now),
      ),
    )
    return true
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
      activeSessionId = preservedActiveSessionId(
        workspace = workspace,
        fallbackSessionId = updatedSession.sessionId,
      ),
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

  private fun preservedActiveSessionId(
    workspace: ChatWorkspaceRecord,
    fallbackSessionId: String,
  ): String = workspace.activeSessionId
    ?.takeIf { activeId -> workspace.sessions.any { session -> session.sessionId == activeId } }
    ?: fallbackSessionId

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

  private fun pendingUserInputsFrom(
    workspace: ChatWorkspaceRecord,
    sessionId: String,
  ): List<PendingUserInputEntry> = workspace.extensions[pendingUserInputExtensionKey(sessionId)]
    ?.let(::decodePendingUserInputs)
    .orEmpty()

  private fun workspaceWithPendingUserInputs(
    workspace: ChatWorkspaceRecord,
    sessionId: String,
    inputs: List<PendingUserInputEntry>,
    updatedAtEpochMs: Long,
  ): ChatWorkspaceRecord {
    val key = pendingUserInputExtensionKey(sessionId)
    val updatedExtensions = if (inputs.isEmpty()) {
      workspace.extensions - key
    } else {
      workspace.extensions + (
        key to pendingUserInputJson.encodeToString(
          serializer = ListSerializer(PendingUserInputEntry.serializer()),
          value = inputs,
        )
      )
    }
    return workspace.copy(
      extensions = updatedExtensions,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  private fun pendingUserInputExtensionKey(sessionId: String): String = "pendingUserInputs.$sessionId"

  private fun decodePendingUserInputs(raw: String): List<PendingUserInputEntry> = runCatching {
    pendingUserInputJson.decodeFromString(
      deserializer = ListSerializer(PendingUserInputEntry.serializer()),
      string = raw,
    )
  }.getOrDefault(emptyList())

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
    private val pendingUserInputJson = Json { ignoreUnknownKeys = true }

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

@Serializable
internal data class PendingUserInputEntry(
  val queueId: String,
  val text: String,
  val createdAtEpochMs: Long,
) {
  init {
    require(queueId.isNotBlank()) { "PendingUserInputEntry queueId must not be blank." }
    require(text.isNotBlank()) { "PendingUserInputEntry text must not be blank." }
    require(createdAtEpochMs >= 0L) { "PendingUserInputEntry createdAtEpochMs must be >= 0." }
  }
}
