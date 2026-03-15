package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage

interface SessionTranscriptStore {
  fun snapshot(): List<RuntimeConversationMessage>

  fun seedIfEmpty(messages: List<RuntimeConversationMessage>)

  fun appendIfDistinct(message: RuntimeConversationMessage)

  fun clear()
}

class InMemorySessionTranscriptStore : SessionTranscriptStore {
  private val lock = Any()
  private val messages = mutableListOf<RuntimeConversationMessage>()

  override fun snapshot(): List<RuntimeConversationMessage> = synchronized(lock) {
    normalizeInPlaceLocked()
    messages.toList()
  }

  override fun seedIfEmpty(messages: List<RuntimeConversationMessage>) {
    val normalized = SessionTranscriptRules.normalize(messages)
    if (normalized.isEmpty()) {
      return
    }
    synchronized(lock) {
      if (this.messages.isNotEmpty()) {
        return
      }
      this.messages += normalized
    }
  }

  override fun appendIfDistinct(message: RuntimeConversationMessage) {
    synchronized(lock) {
      val updated = SessionTranscriptRules.normalize(messages + message)
      if (updated == messages) {
        return
      }
      messages.clear()
      messages += updated
    }
  }

  override fun clear() {
    synchronized(lock) {
      messages.clear()
    }
  }

  private fun normalizeInPlaceLocked() {
    val normalized = SessionTranscriptRules.normalize(messages)
    if (normalized == messages) {
      return
    }
    messages.clear()
    messages += normalized
  }
}
