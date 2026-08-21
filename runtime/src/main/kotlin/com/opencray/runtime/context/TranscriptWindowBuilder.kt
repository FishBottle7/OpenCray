package com.opencray.runtime.context

data class TranscriptWindowConfig(
  val maxMessages: Int = 12,
  val maxCharsPerMessage: Int = 2_400,
) {
  init {
    require(maxMessages >= 1) { "TranscriptWindowConfig maxMessages must be >= 1." }
    require(maxCharsPerMessage >= 32) { "TranscriptWindowConfig maxCharsPerMessage must be >= 32." }
  }
}

class TranscriptWindowBuilder(
  val config: TranscriptWindowConfig = TranscriptWindowConfig(),
) {
  fun build(
    messages: List<RuntimeConversationMessage>,
    windowConfig: TranscriptWindowConfig = config,
  ): TranscriptWindow = buildSelection(messages, windowConfig).window

  fun buildSelection(
    messages: List<RuntimeConversationMessage>,
    windowConfig: TranscriptWindowConfig = config,
  ): TranscriptWindowSelection {
    val normalized = messages.mapNotNull { entry ->
      entry.content.trim().takeIf(String::isNotBlank)?.let { content ->
        entry.copy(content = content)
      }
    }
    val selectedIndexes = selectWindowIndexes(normalized, windowConfig)
    val selectedIndexSet = selectedIndexes.toSet()
    val omittedMessageCount = normalized.size - selectedIndexes.size
    val omittedMessages = normalized.filterIndexed { index, _ -> index !in selectedIndexSet }
    var truncatedMessageCount = 0
    val windowedMessages = selectedIndexes.map { index ->
      val entry = normalized[index]
      val boundedContent = boundContent(entry, windowConfig).also { content ->
        if (content != entry.content) {
          truncatedMessageCount += 1
        }
      }
      entry.copy(content = boundedContent)
    }

    return TranscriptWindowSelection(
      window = TranscriptWindow(
        messages = windowedMessages,
        omittedMessageCount = omittedMessageCount,
        truncatedMessageCount = truncatedMessageCount,
      ),
      normalizedMessages = normalized,
      omittedMessages = omittedMessages,
    )
  }

  private fun selectWindowIndexes(
    messages: List<RuntimeConversationMessage>,
    windowConfig: TranscriptWindowConfig,
  ): List<Int> {
    if (messages.size <= windowConfig.maxMessages) {
      return messages.indices.toList()
    }

    val maxBackgroundMessages = when {
      windowConfig.maxMessages <= 2 -> 0
      else -> maxOf(1, windowConfig.maxMessages / 3)
    }
    val selected = linkedSetOf<Int>()
    var backgroundCount = 0

    for (index in messages.lastIndex downTo 0) {
      if (selected.size >= windowConfig.maxMessages) {
        break
      }
      if (isBackgroundMessage(messages[index])) {
        if (backgroundCount >= maxBackgroundMessages) {
          continue
        }
        backgroundCount += 1
      }
      selected += index
    }

    if (selected.size < windowConfig.maxMessages) {
      for (index in messages.lastIndex downTo 0) {
        if (selected.size >= windowConfig.maxMessages) {
          break
        }
        selected += index
      }
    }

    return selected.sorted()
  }

  private fun isBackgroundMessage(message: RuntimeConversationMessage): Boolean = when (message.role) {
    RuntimeConversationRole.USER -> false
    RuntimeConversationRole.ASSISTANT -> isToolCallMarker(message)
    RuntimeConversationRole.TOOL,
    RuntimeConversationRole.SYSTEM,
    -> true
  }

  private fun boundContent(
    message: RuntimeConversationMessage,
    windowConfig: TranscriptWindowConfig,
  ): String {
    val compacted = when {
      isToolCallMarker(message) -> collapseWhitespace(message.content)
      message.role == RuntimeConversationRole.TOOL -> collapseWhitespace(message.content)
      else -> message.content
    }
    val limit = when {
      isToolCallMarker(message) -> minOf(windowConfig.maxCharsPerMessage, 480)
      message.role == RuntimeConversationRole.TOOL -> minOf(windowConfig.maxCharsPerMessage, 1_600)
      else -> windowConfig.maxCharsPerMessage
    }
    return if (compacted.length > limit) {
      compacted.take(limit - 1).trimEnd() + "…"
    } else {
      compacted
    }
  }

  private fun collapseWhitespace(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

  private fun isToolCallMarker(message: RuntimeConversationMessage): Boolean =
    message.isAssistantToolCallMessage()
}
