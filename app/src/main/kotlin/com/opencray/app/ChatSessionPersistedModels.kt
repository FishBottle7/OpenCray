package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import kotlinx.serialization.Serializable

@Serializable
internal data class PersistedTodoEntry(
  val content: String,
  val status: String,
  val activeForm: String? = null,
) {
  fun toAgentTodoEntryOrNull(): AgentTodoEntry? {
    val normalizedContent = content.trim()
    val normalizedStatus = AgentTodoStatus.fromLabelOrNull(status) ?: return null
    if (normalizedContent.isBlank()) {
      return null
    }
    return AgentTodoEntry(
      content = normalizedContent,
      status = normalizedStatus,
      activeForm = activeForm?.trim()?.takeIf(String::isNotBlank),
    )
  }

  companion object {
    fun fromAgentTodoEntry(entry: AgentTodoEntry): PersistedTodoEntry = PersistedTodoEntry(
      content = entry.content,
      status = when (entry.status) {
        AgentTodoStatus.PENDING -> "pending"
        AgentTodoStatus.IN_PROGRESS -> "in_progress"
        AgentTodoStatus.COMPLETED -> "completed"
      },
      activeForm = entry.activeForm,
    )
  }
}

internal enum class ChatSessionTodoState {
  EMPTY,
  ACTIVE,
  ARCHIVED_COMPLETED,
}

internal data class ChatSessionTodoSnapshot(
  val todos: List<AgentTodoEntry>,
  val state: ChatSessionTodoState,
  val completedAtEpochMs: Long? = null,
) {
  companion object {
    fun empty(): ChatSessionTodoSnapshot = ChatSessionTodoSnapshot(
      todos = emptyList(),
      state = ChatSessionTodoState.EMPTY,
    )
  }
}

internal enum class ChatSessionTodoPresentationState {
  EMPTY,
  ACTIVE,
  ARCHIVED_COMPLETED,
}

internal data class ChatSessionTodoPresentation(
  val todos: List<AgentTodoEntry>,
  val state: ChatSessionTodoPresentationState,
  val hideDelayMs: Long? = null,
  val completedAtEpochMs: Long? = null,
) {
  companion object {
    fun empty(): ChatSessionTodoPresentation = ChatSessionTodoPresentation(
      todos = emptyList(),
      state = ChatSessionTodoPresentationState.EMPTY,
    )
  }
}

@Serializable
internal data class PersistedArchivedTodoSnapshot(
  val completedAtEpochMs: Long,
  val todos: List<PersistedTodoEntry>,
) {
  companion object {
    fun fromAgentTodoEntries(
      entries: List<AgentTodoEntry>,
      completedAtEpochMs: Long,
    ): PersistedArchivedTodoSnapshot = PersistedArchivedTodoSnapshot(
      completedAtEpochMs = completedAtEpochMs,
      todos = entries.map(PersistedTodoEntry::fromAgentTodoEntry),
    )
  }
}

@Serializable
internal data class PendingUserInputEntry(
  val queueId: String,
  val text: String,
  val attachments: List<ChatAttachmentEntry> = emptyList(),
  val createdAtEpochMs: Long,
) {
  init {
    require(queueId.isNotBlank()) { "PendingUserInputEntry queueId must not be blank." }
    require(text.isNotBlank() || attachments.isNotEmpty()) {
      "PendingUserInputEntry text or attachments must not be blank."
    }
    require(createdAtEpochMs >= 0L) { "PendingUserInputEntry createdAtEpochMs must be >= 0." }
  }
}
