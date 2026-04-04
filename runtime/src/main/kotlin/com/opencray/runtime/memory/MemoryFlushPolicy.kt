package com.opencray.runtime.memory

import com.opencray.runtime.context.RuntimeConversationMessage
import java.security.MessageDigest

data class MemoryFlushPolicy(
  val minOmittedMessages: Int = 4,
  val minOmittedChars: Int = 480,
  val maxMergedUserChars: Int = 720,
  val maxMergedAssistantChars: Int = 720,
  val maxToolObservations: Int = 8,
) {
  init {
    require(minOmittedMessages >= 1) { "MemoryFlushPolicy minOmittedMessages must be >= 1." }
    require(minOmittedChars >= 120) { "MemoryFlushPolicy minOmittedChars must be >= 120." }
    require(maxMergedUserChars >= 120) { "MemoryFlushPolicy maxMergedUserChars must be >= 120." }
    require(maxMergedAssistantChars >= 120) { "MemoryFlushPolicy maxMergedAssistantChars must be >= 120." }
    require(maxToolObservations >= 1) { "MemoryFlushPolicy maxToolObservations must be >= 1." }
  }

  fun shouldFlush(
    omittedMessages: List<RuntimeConversationMessage>,
    replayPressure: com.opencray.runtime.context.ReplayPressureSnapshot,
  ): Boolean {
    if (omittedMessages.isEmpty()) {
      return false
    }
    val omittedChars = omittedMessages.sumOf { message -> message.content.length }
    if (omittedMessages.size < minOmittedMessages && omittedChars < minOmittedChars) {
      return false
    }
    return replayPressure.tokenThresholdTriggered
  }

  fun signatureFor(omittedMessages: List<RuntimeConversationMessage>): String {
    val digestSource = omittedMessages.joinToString(separator = "\n") { message ->
      "${message.role.name}:${message.content.trim()}"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)
  }

  fun mergeUserInput(messages: List<RuntimeConversationMessage>): String = messages
    .joinToString(separator = "\n") { message -> message.content.trim() }
    .trim()
    .take(maxMergedUserChars)
    .ifBlank { FLUSH_PLACEHOLDER_USER_INPUT }

  fun mergeAssistantOutput(messages: List<RuntimeConversationMessage>): String? = messages
    .joinToString(separator = "\n") { message -> message.content.trim() }
    .trim()
    .take(maxMergedAssistantChars)
    .takeIf(String::isNotBlank)

  companion object {
    const val FLUSH_PLACEHOLDER_USER_INPUT: String = "Compaction pressure triggered a memory flush review."
  }
}
