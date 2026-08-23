package com.opencray.app

import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SESSION_TITLE
import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SYSTEM_TEMPLATE_ID
import com.opencray.app.ChatSessionLocalStore.Companion.DEFAULT_SYSTEM_TEMPLATE_VALUE
import com.opencray.persistence.model.ChatPromptTemplateEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.ChatWorkspaceStoreUpdate
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.workingstate.WorkingState
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val pendingUserInputJson = Json { ignoreUnknownKeys = true }
private val todoJson = Json { ignoreUnknownKeys = true }
private val workingStateJson = Json { ignoreUnknownKeys = true }

internal fun ChatSessionLocalStore.loadWorkspaceOrCreate(): ChatWorkspaceRecord = workspaceStore.update { workspace ->
  val existingActiveSession = workspace?.let(::activeSessionFrom)
  if (workspace != null && existingActiveSession != null) {
    return@update ChatWorkspaceStoreUpdate(
      record = workspace,
      result = workspace,
      write = false,
    )
  }
  val now = nowEpochMs()
  val currentWorkspace = workspace ?: seedWorkspaceRecord(now)
  val created = workspaceWithNewSession(
    workspace = currentWorkspace,
    now = now,
  )
  ChatWorkspaceStoreUpdate(
    record = created.workspace,
    result = created.workspace,
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

internal fun ChatSessionLocalStore.workspaceAndSessionForAppend(
  workspace: ChatWorkspaceRecord?,
  sessionId: String,
  now: Long,
): CreatedChatSessionWorkspace {
  val currentWorkspace = workspace ?: seedWorkspaceRecord(now)
  val currentSession = currentWorkspace.sessions.firstOrNull { it.sessionId == sessionId }
    ?: activeSessionFrom(currentWorkspace)
  return if (currentSession == null) {
    workspaceWithNewSession(
      workspace = currentWorkspace,
      now = now,
    )
  } else {
    CreatedChatSessionWorkspace(
      workspace = currentWorkspace,
      session = currentSession,
    )
  }
}

internal fun ChatSessionLocalStore.workspaceAndActiveSessionForUpdate(
  workspace: ChatWorkspaceRecord?,
  now: Long,
): CreatedChatSessionWorkspace {
  val currentWorkspace = workspace ?: seedWorkspaceRecord(now)
  val activeSession = activeSessionFrom(currentWorkspace)
  return if (activeSession == null) {
    workspaceWithNewSession(
      workspace = currentWorkspace,
      now = now,
    )
  } else {
    CreatedChatSessionWorkspace(
      workspace = currentWorkspace,
      session = activeSession,
    )
  }
}

internal fun ChatSessionLocalStore.workspaceWithNewSession(
  workspace: ChatWorkspaceRecord,
  now: Long,
): CreatedChatSessionWorkspace {
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
  return CreatedChatSessionWorkspace(
    workspace = workspace.copy(
      sessions = (workspace.sessions.filterNot { it.sessionId == sessionId } + session)
        .sortedByDescending { it.updatedAtEpochMs },
      activeSessionId = sessionId,
      recordVersion = workspace.recordVersion + 1,
      updatedAtEpochMs = maxOf(workspace.updatedAtEpochMs, now),
    ),
    session = session,
  )
}

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
  )
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

internal data class CreatedChatSessionWorkspace(
  val workspace: ChatWorkspaceRecord,
  val session: ChatTranscriptSessionEntry,
)

internal fun activeSessionFrom(workspace: ChatWorkspaceRecord): ChatTranscriptSessionEntry? =
  workspace.activeSessionId?.let { activeId -> workspace.sessions.firstOrNull { it.sessionId == activeId } }
    ?: workspace.sessions.maxByOrNull { it.updatedAtEpochMs }

internal fun reusableEmptySessionFrom(workspace: ChatWorkspaceRecord): ChatTranscriptSessionEntry? =
  workspace.sessions
    .sortedByDescending(ChatTranscriptSessionEntry::updatedAtEpochMs)
    .firstOrNull { session ->
      isReusableEmptySession(
        workspace = workspace,
        session = session,
      )
    }

internal fun isReusableEmptySession(
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
  if (session.messages.size != 1) {
    return false
  }
  return isDefaultSeedSystemMessage(session.messages.single())
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
): ChatWorkspaceRecord = workspace.copy(
  sessions = (workspace.sessions.filterNot { it.sessionId == updatedSession.sessionId } + updatedSession)
    .sortedByDescending { it.updatedAtEpochMs },
  activeSessionId = activeSessionId,
  recordVersion = workspace.recordVersion + 1,
  updatedAtEpochMs = updatedAtEpochMs,
)

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
