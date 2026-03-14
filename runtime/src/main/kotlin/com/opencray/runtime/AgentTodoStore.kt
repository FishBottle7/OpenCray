package com.opencray.runtime

import java.util.concurrent.atomic.AtomicReference

data class AgentTodoEntry(
  val content: String,
  val status: AgentTodoStatus,
  val activeForm: String? = null,
) {
  init {
    require(content.isNotBlank()) { "AgentTodoEntry content must not be blank." }
  }
}

enum class AgentTodoStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  ;

  companion object {
    fun fromLabelOrNull(label: String?): AgentTodoStatus? = when (label?.trim()?.lowercase()) {
      "pending" -> PENDING
      "in_progress", "in-progress", "inprogress" -> IN_PROGRESS
      "completed", "complete", "done" -> COMPLETED
      else -> null
    }
  }
}

interface AgentTodoStore {
  fun snapshot(): List<AgentTodoEntry>

  fun replaceAll(entries: List<AgentTodoEntry>)
}

class InMemoryAgentTodoStore(
  initialEntries: List<AgentTodoEntry> = emptyList(),
) : AgentTodoStore {
  private val entriesRef: AtomicReference<List<AgentTodoEntry>> = AtomicReference(initialEntries.toList())

  override fun snapshot(): List<AgentTodoEntry> = entriesRef.get().toList()

  override fun replaceAll(entries: List<AgentTodoEntry>) {
    entriesRef.set(entries.toList())
  }
}
