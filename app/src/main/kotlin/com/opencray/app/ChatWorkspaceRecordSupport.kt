package com.opencray.app

import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SESSION_TITLE
import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SYSTEM_TEMPLATE_ID
import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SYSTEM_TEMPLATE_VALUE
import com.opencray.persistence.model.ChatPromptTemplateEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.model.PersistedChatSessionTranscript
import com.opencray.persistence.store.ChatWorkspaceStoreUpdate
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.workingstate.WorkingState
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val pendingUserInputJson = Json { ignoreUnknownKeys = true }
private val todoJson = Json { ignoreUnknownKeys = true }
private val workingStateJson = Json { ignoreUnknownKeys = true }

/**
 * Loads (and if needed creates or migrates) the chat workspace record.
 *
 * The returned record carries session metadata only: `messages` lists are always empty
 * and the full message list for a session lives in its per-session transcript file.
 * Hydrate a session with [ChatSessionLocalStore.transcriptMessagesFor].
 *
 * Migration from the legacy single-file layout is a two-phase, lock-ordered split:
 *  a. every session's inline messages are written to
 *     `chat-sessions/<sid>/transcript.json` under that session's own lock, one session
 *     at a time, with no workspace lock held (existing transcripts win, so re-running
 *     after a crash never clobbers concurrent progress),
 *  b. the workspace is rewritten with empty `messages` plus denormalized preview/count
 *     metadata under the workspace lock; when a concurrent process already completed
 *     the migration, their record is kept as-is.
 * A crash between the phases only causes the migration to re-run on next start.
 */
internal fun ChatSessionLocalStore.loadWorkspaceOrCreate(): ChatWorkspaceRecord {
  var workspace = workspaceStore.load()
  if (workspace != null && hasInlineMessages(workspace)) {
    migrateInlineMessagesToTranscripts(workspace)
    workspace = workspaceStore.load()
  }
  if (workspace != null && activeSessionFrom(workspace) != null) {
    return workspace
  }
  return ensureActiveSessionInWorkspace(workspace)
}

internal fun hasInlineMessages(workspace: ChatWorkspaceRecord): Boolean =
  workspace.sessions.any { session -> session.messages.isNotEmpty() }

internal fun ChatSessionLocalStore.migrateInlineMessagesToTranscripts(
  workspace: ChatWorkspaceRecord,
) {
  // Phase a: per-session transcript writes, each under that session's own lock, with no
  // workspace lock held. The check-and-create is atomic per session, so re-running
  // after a crash or a concurrent migration never clobbers existing transcripts.
  workspace.sessions
    .filter { session -> session.messages.isNotEmpty() }
    .forEach { session ->
      transcriptStore.update(session.sessionId) { current ->
        if (current != null) {
          RecordStorageUpdate(
            value = current,
            result = Unit,
            write = false,
          )
        } else {
          RecordStorageUpdate(
            value = PersistedChatSessionTranscript(
              sessionId = session.sessionId,
              messages = session.messages,
              createdAtEpochMs = session.createdAtEpochMs,
              updatedAtEpochMs = session.updatedAtEpochMs,
            ),
            result = Unit,
          )
        }
      }
    }
  // Phase b: under the workspace lock, re-read; only rewrite when the record is still
  // legacy. A concurrent process that finished first keeps its result.
  workspaceStore.update { current ->
    if (current == null || !hasInlineMessages(current)) {
      return@update ChatWorkspaceStoreUpdate(
        record = current,
        result = Unit,
        write = false,
      )
    }
    val now = nowEpochMs()
    ChatWorkspaceStoreUpdate(
      record = current.copy(
        sessions = current.sessions.map { session ->
          if (session.messages.isEmpty()) {
            session
          } else {
            session.withMessagesAndMetadata(session.messages).asMetadataOnly()
          }
        },
        recordVersion = current.recordVersion + 1,
        updatedAtEpochMs = maxOf(current.updatedAtEpochMs, now),
      ),
      result = Unit,
    )
  }
}

/**
 * Guarantees the workspace has an active session, seeding a fresh one when the record is
 * missing or session-less. The seed transcript file is written before the workspace
 * record so a listed session always has its transcript on disk; when a concurrent process
 * wins the workspace creation, our seed file stays behind as a harmless orphan.
 */
internal fun ChatSessionLocalStore.ensureActiveSessionInWorkspace(
  workspace: ChatWorkspaceRecord?,
): ChatWorkspaceRecord {
  val now = nowEpochMs()
  val seededSession = newSeededSession(now)
  return workspaceStore.update { current ->
    val existingActiveSession = current?.let(::activeSessionFrom)
    if (current != null && existingActiveSession != null) {
      return@update ChatWorkspaceStoreUpdate(
        record = current,
        result = current,
        write = false,
      )
    }
    val created = replaceSession(
      workspace = current ?: seedWorkspaceRecord(now),
      updatedSession = seededSession.asMetadataOnly(),
      activeSessionId = seededSession.sessionId,
      updatedAtEpochMs = maxOf(seedWorkspaceRecordUpdatedAt(current, now), now),
    )
    ChatWorkspaceStoreUpdate(
      record = created,
      result = created,
    )
  }
}

private fun seedWorkspaceRecordUpdatedAt(
  workspace: ChatWorkspaceRecord?,
  now: Long,
): Long = workspace?.updatedAtEpochMs ?: now

/** Metadata-only session entry for a brand-new session plus its seed transcript file. */
internal fun ChatSessionLocalStore.newSeededSession(now: Long): ChatTranscriptSessionEntry {
  val session = newSessionEntry(now)
  transcriptStore.save(
    PersistedChatSessionTranscript(
      sessionId = session.sessionId,
      messages = session.messages,
      createdAtEpochMs = session.createdAtEpochMs,
      updatedAtEpochMs = session.updatedAtEpochMs,
    ),
  )
  return session
}

internal fun newSessionEntry(now: Long): ChatTranscriptSessionEntry {
  val sessionId = "session-${now}-${UUID.randomUUID().toString().take(8)}"
  return ChatTranscriptSessionEntry(
    sessionId = sessionId,
    title = DEFAULT_SESSION_TITLE,
    createdAtEpochMs = now,
    updatedAtEpochMs = now,
    messages = listOf(
      ChatTranscriptMessageEntry(
        messageId = "system-$now-${UUID.randomUUID().toString().take(8)}",
        role = ChatTranscriptRole.SYSTEM,
        promptTemplateRefId = DEFAULT_SYSTEM_TEMPLATE_ID,
        createdAtEpochMs = now,
      ),
    ),
  )
}

internal fun seedWorkspaceRecord(now: Long): ChatWorkspaceRecord = ChatWorkspaceRecord(
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

internal data class CreatedChatSessionWorkspace(
  val workspace: ChatWorkspaceRecord,
  val session: ChatTranscriptSessionEntry,
)

internal fun workspaceWithReusedEmptySession(
  workspace: ChatWorkspaceRecord,
  session: ChatTranscriptSessionEntry,
  now: Long,
): CreatedChatSessionWorkspace {
  val reusedAt = maxOf(now, workspace.updatedAtEpochMs)
  val reusedSession = session.copy(
    title = DEFAULT_SESSION_TITLE,
    createdAtEpochMs = reusedAt,
    updatedAtEpochMs = reusedAt,
  ).asMetadataOnly()
  val updatedWorkspace = replaceSession(
    workspace = workspace,
    updatedSession = reusedSession,
    activeSessionId = reusedSession.sessionId,
    updatedAtEpochMs = reusedAt,
  )
  return CreatedChatSessionWorkspace(
    workspace = updatedWorkspace,
    session = reusedSession,
  )
}

internal fun activeSessionFrom(workspace: ChatWorkspaceRecord): ChatTranscriptSessionEntry? =
  workspace.activeSessionId?.let { activeId -> workspace.sessions.firstOrNull { it.sessionId == activeId } }
    ?: workspace.sessions.maxByOrNull { it.updatedAtEpochMs }

internal fun ChatSessionLocalStore.reusableEmptySessionFrom(workspace: ChatWorkspaceRecord): ChatTranscriptSessionEntry? =
  workspace.sessions
    .sortedByDescending(ChatTranscriptSessionEntry::updatedAtEpochMs)
    .firstOrNull { session ->
      isReusableEmptySession(
        workspace = workspace,
        session = session,
      )
    }

internal fun ChatSessionLocalStore.isReusableEmptySession(
  workspace: ChatWorkspaceRecord,
  session: ChatTranscriptSessionEntry,
): Boolean {
  if (session.title != DEFAULT_SESSION_TITLE) {
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
  if (session.messages.isNotEmpty()) {
    // Legacy inline layout: verify directly.
    return session.messages.size == 1 && isDefaultSeedSystemMessage(session.messages.single())
  }
  if (session.messageCount != 0) {
    return false
  }
  val messages = transcriptMessagesFor(session)
  return messages.size == 1 && isDefaultSeedSystemMessage(messages.single())
}

internal fun isDefaultSeedSystemMessage(message: ChatTranscriptMessageEntry): Boolean =
  message.role == ChatTranscriptRole.SYSTEM &&
    message.promptTemplateRefId == DEFAULT_SYSTEM_TEMPLATE_ID &&
    message.text.isNullOrBlank() &&
    message.commandLabel.isNullOrBlank() &&
    message.attachments.isEmpty()

internal fun preservedActiveSessionId(
  workspace: ChatWorkspaceRecord,
  fallbackSessionId: String,
): String = workspace.activeSessionId
  ?.takeIf { activeId -> workspace.sessions.any { session -> session.sessionId == activeId } }
  ?: fallbackSessionId

internal fun replaceSession(
  workspace: ChatWorkspaceRecord,
  updatedSession: ChatTranscriptSessionEntry,
  activeSessionId: String,
  updatedAtEpochMs: Long,
): ChatWorkspaceRecord {
  require(updatedSession.messages.isEmpty()) {
    "Workspace records must not carry inline transcript messages for '${updatedSession.sessionId}'."
  }
  return workspace.copy(
    sessions = (workspace.sessions.filterNot { it.sessionId == updatedSession.sessionId } + updatedSession)
      .sortedByDescending { it.updatedAtEpochMs },
    activeSessionId = activeSessionId,
    recordVersion = workspace.recordVersion + 1,
    updatedAtEpochMs = updatedAtEpochMs,
  )
}

internal fun pendingUserInputsFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): List<PendingUserInputEntry> = workspace.extensions[pendingUserInputExtensionKey(sessionId)]
  ?.let(::decodePendingUserInputs)
  .orEmpty()

internal fun activeTodosFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): List<AgentTodoEntry> = workspace.extensions[todoExtensionKey(sessionId)]
  ?.let(::decodePersistedTodos)
  .orEmpty()

internal fun archivedTodosFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): PersistedArchivedTodoSnapshot? = workspace.extensions[archivedTodoExtensionKey(sessionId)]
  ?.let(::decodePersistedArchivedTodos)

internal fun workingStateFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): WorkingState = workspace.extensions[workingStateExtensionKey(sessionId)]
  ?.let(::decodePersistedWorkingState)
  ?: WorkingState()

internal fun maintainedContextWindowTokensFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): Int? = workspace.extensions[maintainedContextWindowTokensExtensionKey(sessionId)]
  ?.trim()
  ?.toIntOrNull()
  ?.takeIf { value -> value > 0 }

internal fun nativeWebSearchApprovalFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): Boolean = workspace.extensions[nativeWebSearchApprovalExtensionKey(sessionId)]
  ?.trim()
  ?.lowercase() == "true"

internal fun sessionScopedStatePresentFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): Boolean = workspace.extensions[sessionScopedStateExtensionKey(sessionId)]
  ?.trim()
  ?.lowercase() == "true"

internal fun todoSnapshotFrom(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
): ChatSessionTodoSnapshot {
  val activeTodos = activeTodosFrom(workspace = workspace, sessionId = sessionId)
  if (activeTodos.isNotEmpty()) {
    return ChatSessionTodoSnapshot(
      todos = activeTodos,
      state = ChatSessionTodoState.ACTIVE,
    )
  }
  val archivedTodos = archivedTodosFrom(workspace = workspace, sessionId = sessionId)
    ?: return ChatSessionTodoSnapshot.empty()
  return ChatSessionTodoSnapshot(
    todos = archivedTodos.todos.mapNotNull(PersistedTodoEntry::toAgentTodoEntryOrNull),
    state = ChatSessionTodoState.ARCHIVED_COMPLETED,
    completedAtEpochMs = archivedTodos.completedAtEpochMs,
  )
}

internal fun workspaceWithPendingUserInputs(
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

internal fun pendingUserInputExtensionKey(sessionId: String): String = "pendingUserInputs.$sessionId"

internal fun workspaceWithTodos(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
  todos: List<AgentTodoEntry>,
  updatedAtEpochMs: Long,
): ChatWorkspaceRecord {
  val activeKey = todoExtensionKey(sessionId)
  val archivedKey = archivedTodoExtensionKey(sessionId)
  val updatedExtensions = if (todos.isEmpty()) {
    workspace.extensions - activeKey - archivedKey
  } else if (todos.all { entry -> entry.status == AgentTodoStatus.COMPLETED }) {
    val encodedArchive = todoJson.encodeToString(
      serializer = PersistedArchivedTodoSnapshot.serializer(),
      value = PersistedArchivedTodoSnapshot.fromAgentTodoEntries(
        entries = todos,
        completedAtEpochMs = updatedAtEpochMs,
      ),
    )
    (workspace.extensions - activeKey) + (archivedKey to encodedArchive)
  } else {
    val encodedTodos = todoJson.encodeToString(
      serializer = ListSerializer(PersistedTodoEntry.serializer()),
      value = todos.map(PersistedTodoEntry::fromAgentTodoEntry),
    )
    (workspace.extensions - archivedKey) + (activeKey to encodedTodos)
  }
  return if (updatedExtensions == workspace.extensions) {
    workspace
  } else {
    workspace.copy(
      extensions = updatedExtensions,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }
}

internal fun workspaceWithWorkingState(
  workspace: ChatWorkspaceRecord,
  sessionId: String,
  workingState: WorkingState,
  updatedAtEpochMs: Long,
): ChatWorkspaceRecord {
  val key = workingStateExtensionKey(sessionId)
  val updatedExtensions = if (workingState.isEmpty) {
    workspace.extensions - key
  } else {
    workspace.extensions + (
      key to workingStateJson.encodeToString(
        serializer = WorkingState.serializer(),
        value = workingState,
      )
    )
  }
  return if (updatedExtensions == workspace.extensions) {
    workspace
  } else {
    workspace.copy(
      extensions = updatedExtensions,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }
}

internal fun todoExtensionKey(sessionId: String): String = "todos.$sessionId"
internal fun archivedTodoExtensionKey(sessionId: String): String = "todosArchived.$sessionId"
internal fun workingStateExtensionKey(sessionId: String): String = "workingState.$sessionId"
internal fun maintainedContextWindowTokensExtensionKey(sessionId: String): String =
  "maintainedContextWindowTokens.$sessionId"
internal fun nativeWebSearchApprovalExtensionKey(sessionId: String): String =
  "nativeWebSearchApproval.$sessionId"
internal fun sessionScopedStateExtensionKey(sessionId: String): String =
  "sessionScopedState.$sessionId"

internal fun copySessionExtensions(
  extensions: Map<String, String>,
  sourceSessionId: String,
  targetSessionId: String,
  includeWorkingState: Boolean,
): Map<String, String> {
  var updatedExtensions = extensions
  extensions[todoExtensionKey(sourceSessionId)]?.let { sourceValue ->
    updatedExtensions = updatedExtensions + (todoExtensionKey(targetSessionId) to sourceValue)
  }
  extensions[archivedTodoExtensionKey(sourceSessionId)]?.let { sourceValue ->
    updatedExtensions =
      updatedExtensions + (archivedTodoExtensionKey(targetSessionId) to sourceValue)
  }
  if (includeWorkingState) {
    extensions[workingStateExtensionKey(sourceSessionId)]?.let { sourceValue ->
      updatedExtensions = updatedExtensions + (workingStateExtensionKey(targetSessionId) to sourceValue)
    }
  }
  extensions[maintainedContextWindowTokensExtensionKey(sourceSessionId)]?.let { sourceValue ->
    updatedExtensions =
      updatedExtensions + (maintainedContextWindowTokensExtensionKey(targetSessionId) to sourceValue)
  }
  return updatedExtensions
}

internal fun previewForMessage(message: ChatTranscriptMessageEntry): String {
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

/**
 * Derives the denormalized drawer metadata (visible message count, last preview, last
 * message timestamp) from a session's message list using the same semantics the drawer
 * previously computed on the fly.
 */
internal fun transcriptSummaryFor(messages: List<ChatTranscriptMessageEntry>): ChatSessionLocalStore.SessionTranscriptMetadata {
  val lastVisibleMessage = messages
    .asReversed()
    .firstOrNull { message -> message.role != ChatTranscriptRole.SYSTEM }
  return ChatSessionLocalStore.SessionTranscriptMetadata(
    messageCount = messages.count { message ->
      message.role != ChatTranscriptRole.SYSTEM ||
        message.promptTemplateRefId != DEFAULT_SYSTEM_TEMPLATE_ID
    },
    lastMessagePreview = lastVisibleMessage
      ?.let(::previewForMessage)
      .orEmpty()
      .trim()
      .take(52),
    lastMessageAtEpochMs = lastVisibleMessage?.createdAtEpochMs,
  )
}

/** Sets the message list together with its denormalized metadata. */
internal fun ChatTranscriptSessionEntry.withMessagesAndMetadata(
  messages: List<ChatTranscriptMessageEntry>,
): ChatTranscriptSessionEntry {
  val summary = transcriptSummaryFor(messages)
  return copy(
    messages = messages,
    messageCount = summary.messageCount,
    lastMessagePreview = summary.lastMessagePreview,
    lastMessageAtEpochMs = summary.lastMessageAtEpochMs,
  )
}

/** Strips inline messages while keeping the denormalized metadata; the shape persisted in the workspace record. */
internal fun ChatTranscriptSessionEntry.asMetadataOnly(): ChatTranscriptSessionEntry =
  copy(messages = emptyList())

/**
 * Merges freshly-read workspace metadata with metadata computed from a transcript
 * update. When the workspace entry is strictly newer than the transcript commit, a
 * concurrent writer already published fresher content and their fields are kept
 * (timestamps still move forward) so we never regress the drawer metadata.
 */
internal fun mergeSessionMetadata(
  existing: ChatTranscriptSessionEntry,
  computed: ChatTranscriptSessionEntry,
): ChatTranscriptSessionEntry {
  val mergedUpdatedAt = maxOf(existing.updatedAtEpochMs, computed.updatedAtEpochMs)
  return if (existing.updatedAtEpochMs > computed.updatedAtEpochMs) {
    existing.copy(updatedAtEpochMs = mergedUpdatedAt)
  } else {
    computed.copy(updatedAtEpochMs = mergedUpdatedAt)
  }
}

internal fun decodePendingUserInputs(raw: String): List<PendingUserInputEntry> = runCatching {
  pendingUserInputJson.decodeFromString(
    deserializer = ListSerializer(PendingUserInputEntry.serializer()),
    string = raw,
  )
}.getOrDefault(emptyList())

internal fun decodePersistedTodos(raw: String): List<AgentTodoEntry> = runCatching {
  todoJson.decodeFromString(
    deserializer = ListSerializer(PersistedTodoEntry.serializer()),
    string = raw,
  )
}.getOrDefault(emptyList())
  .mapNotNull(PersistedTodoEntry::toAgentTodoEntryOrNull)

internal fun decodePersistedArchivedTodos(raw: String): PersistedArchivedTodoSnapshot? =
  runCatching {
    todoJson.decodeFromString(
      deserializer = PersistedArchivedTodoSnapshot.serializer(),
      string = raw,
    )
  }.getOrNull()
    ?.takeIf { archivedTodos -> archivedTodos.completedAtEpochMs >= 0L }
    ?.takeIf { archivedTodos -> archivedTodos.todos.isNotEmpty() }

internal fun decodePersistedWorkingState(raw: String): WorkingState = runCatching {
  workingStateJson.decodeFromString(
    deserializer = WorkingState.serializer(),
    string = raw,
  )
}.getOrDefault(WorkingState())
