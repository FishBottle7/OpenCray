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

fun normalizeAndValidateAgentTodoEntries(entries: List<AgentTodoEntry>): List<AgentTodoEntry> {
  val firstIndexByContent = linkedMapOf<String, Int>()
  val normalized = entries.mapIndexed { index, entry ->
    val normalizedContent = entry.content.trim()
    val duplicateIndex = firstIndexByContent.putIfAbsent(normalizedContent, index)
    if (duplicateIndex != null) {
      throw IllegalArgumentException(
        "TodoWrite todo ${index + 1} duplicates todo ${duplicateIndex + 1} content.",
      )
    }
    val normalizedActiveForm = entry.activeForm?.trim()?.takeIf(String::isNotBlank)
    if (entry.status != AgentTodoStatus.IN_PROGRESS && normalizedActiveForm != null) {
      throw IllegalArgumentException(
        "TodoWrite todo ${index + 1} can only set activeForm when status is in_progress.",
      )
    }
    AgentTodoEntry(
      content = normalizedContent,
      status = entry.status,
      activeForm = normalizedActiveForm,
    )
  }
  val inProgressCount = normalized.count { entry -> entry.status == AgentTodoStatus.IN_PROGRESS }
  if (inProgressCount > 1) {
    throw IllegalArgumentException("TodoWrite allows at most one in_progress todo at a time.")
  }
  return normalized
}

class InMemoryAgentTodoStore(
  initialEntries: List<AgentTodoEntry> = emptyList(),
) : AgentTodoStore {
  private val entriesRef: AtomicReference<List<AgentTodoEntry>> = AtomicReference(
    normalizeAndValidateAgentTodoEntries(initialEntries),
  )

  override fun snapshot(): List<AgentTodoEntry> = entriesRef.get().toList()

  override fun replaceAll(entries: List<AgentTodoEntry>) {
    entriesRef.set(normalizeAndValidateAgentTodoEntries(entries))
  }
}
