package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage

/**
 * Stores the runtime replay transcript used to rebuild model-facing conversation context.
 *
 * This is not the canonical user-visible chat history. Implementations are allowed to rewrite,
 * compact, or rebuild this working copy under replay-pressure maintenance as long as canonical
 * chat/rollout history is preserved elsewhere.
 */
interface SessionTranscriptStore {
  fun snapshot(): List<RuntimeConversationMessage>

  fun seedIfEmpty(messages: List<RuntimeConversationMessage>)

  fun appendIfDistinct(message: RuntimeConversationMessage)

  fun replaceReplayWorkingCopy(messages: List<RuntimeConversationMessage>)

  @Deprecated(
    message = "Use replaceReplayWorkingCopy(...) to make replay-working-copy mutation explicit.",
    replaceWith = ReplaceWith("replaceReplayWorkingCopy(messages)"),
  )
  fun replace(messages: List<RuntimeConversationMessage>) {
    replaceReplayWorkingCopy(messages)
  }

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

  override fun replaceReplayWorkingCopy(messages: List<RuntimeConversationMessage>) {
    val normalized = SessionTranscriptRules.normalize(messages)
    synchronized(lock) {
      this.messages.clear()
      this.messages += normalized
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
