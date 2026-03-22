package com.opencray.app

import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStore

internal class ChatSessionBackedAgentTodoStore(
  private val chatSessionStore: ChatSessionLocalStore,
  private val sessionId: String,
) : AgentTodoStore {
  override fun snapshot(): List<AgentTodoEntry> = chatSessionStore.loadTodos(sessionId)

  override fun replaceAll(entries: List<AgentTodoEntry>) {
    chatSessionStore.replaceTodos(sessionId, entries)
  }
}
