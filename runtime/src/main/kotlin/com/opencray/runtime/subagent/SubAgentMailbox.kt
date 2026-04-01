package com.opencray.runtime.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubAgentMailbox(
  val messages: List<SubAgentMailboxMessage> = emptyList(),
  val lastDeliveredMessageId: String? = null,
) {
  init {
    require(messages.map(SubAgentMailboxMessage::messageId).distinct().size == messages.size) {
      "SubAgentMailbox message ids must be unique."
    }
  }

  fun pendingMessages(): List<SubAgentMailboxMessage> {
    if (messages.isEmpty()) {
      return emptyList()
    }
    val deliveredId = lastDeliveredMessageId?.trim().takeUnless { it.isNullOrEmpty() }
      ?: return messages
    val deliveredIndex = messages.indexOfFirst { message -> message.messageId == deliveredId }
    if (deliveredIndex < 0) {
      return messages
    }
    if (deliveredIndex >= messages.lastIndex) {
      return emptyList()
    }
    return messages.subList(deliveredIndex + 1, messages.size)
  }

  fun enqueue(message: SubAgentMailboxMessage): SubAgentMailbox = copy(
    messages = messages + message,
  )

  fun markDeliveredThrough(messageId: String): SubAgentMailbox {
    val normalizedMessageId = messageId.trim()
    if (normalizedMessageId.isEmpty() || messages.none { it.messageId == normalizedMessageId }) {
      return this
    }
    return copy(lastDeliveredMessageId = normalizedMessageId)
  }
}

@Serializable
data class SubAgentMailboxMessage(
  val messageId: String,
  val text: String,
  val createdAtEpochMs: Long,
) {
  init {
    require(messageId.isNotBlank()) { "SubAgentMailboxMessage messageId must not be blank." }
    require(text.isNotBlank()) { "SubAgentMailboxMessage text must not be blank." }
    require(createdAtEpochMs >= 0L) {
      "SubAgentMailboxMessage createdAtEpochMs must be >= 0."
    }
  }
}
