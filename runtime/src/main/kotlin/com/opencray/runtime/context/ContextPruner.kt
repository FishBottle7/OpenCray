package com.opencray.runtime.context

data class ContextPrunerConfig(
  val maxToolChars: Int = 2_400,
  val maxToolLines: Int = 48,
  val maxAttachmentChars: Int = 192,
  val maxPreviewChars: Int = 240,
  val maxSummaryChars: Int = 480,
) {
  init {
    require(maxToolChars >= 128) { "ContextPrunerConfig maxToolChars must be >= 128." }
    require(maxToolLines >= 4) { "ContextPrunerConfig maxToolLines must be >= 4." }
    require(maxAttachmentChars >= 64) { "ContextPrunerConfig maxAttachmentChars must be >= 64." }
    require(maxPreviewChars >= 24) { "ContextPrunerConfig maxPreviewChars must be >= 24." }
    require(maxSummaryChars >= 96) { "ContextPrunerConfig maxSummaryChars must be >= 96." }
  }
}

class ContextPruner(
  private val config: ContextPrunerConfig = ContextPrunerConfig(),
) {
  fun prune(messages: List<RuntimeConversationMessage>): PrunedTranscript {
    val normalized = messages.mapNotNull { message ->
      message.content.trim().takeIf(String::isNotBlank)?.let { content ->
        message.copy(content = content)
      }
    }
    if (normalized.isEmpty()) {
      return PrunedTranscript(messages = emptyList())
    }

    val keptMessages = mutableListOf<RuntimeConversationMessage>()
    var removedMessageCount = 0
    var rewrittenMessageCount = 0
    var duplicateBackgroundMessageCount = 0
    var bulkyToolMessageCount = 0
    var attachmentLikeMessageCount = 0

    normalized.forEach { message ->
      val rewrite = rewriteMessage(message)
      if (rewrite.changed) {
        rewrittenMessageCount += 1
      }
      if (rewrite.bulkyTool) {
        bulkyToolMessageCount += 1
      }
      if (rewrite.attachmentLike) {
        attachmentLikeMessageCount += 1
      }
      if (isDuplicateBackground(keptMessages.lastOrNull(), rewrite.message)) {
        removedMessageCount += 1
        duplicateBackgroundMessageCount += 1
        return@forEach
      }
      keptMessages += rewrite.message
    }

    val summary = buildSummary(
      removedMessageCount = removedMessageCount,
      rewrittenMessageCount = rewrittenMessageCount,
      duplicateBackgroundMessageCount = duplicateBackgroundMessageCount,
      bulkyToolMessageCount = bulkyToolMessageCount,
      attachmentLikeMessageCount = attachmentLikeMessageCount,
    )
    return PrunedTranscript(
      messages = keptMessages,
      summary = summary,
    )
  }

  private fun rewriteMessage(message: RuntimeConversationMessage): MessageRewrite = when {
    message.role != RuntimeConversationRole.TOOL -> MessageRewrite(message = message)
    isAttachmentLike(message.content) -> MessageRewrite(
      message = message.copy(content = buildAttachmentSummary(message.content)),
      changed = true,
      attachmentLike = true,
    )

    exceedsToolBudget(message.content) -> MessageRewrite(
      message = message.copy(content = buildToolBudgetSummary(message.content)),
      changed = true,
      bulkyTool = true,
    )

    else -> MessageRewrite(message = message)
  }

  private fun buildSummary(
    removedMessageCount: Int,
    rewrittenMessageCount: Int,
    duplicateBackgroundMessageCount: Int,
    bulkyToolMessageCount: Int,
    attachmentLikeMessageCount: Int,
  ): TranscriptPruningSummary? {
    if (removedMessageCount == 0 && rewrittenMessageCount == 0) {
      return null
    }
    val lines = mutableListOf<String>()
    lines += "Applied prompt-local pruning before windowing: removed=$removedMessageCount, rewritten=$rewrittenMessageCount."
    if (duplicateBackgroundMessageCount > 0) {
      lines += "Dropped consecutive duplicate background messages: $duplicateBackgroundMessageCount."
    }
    if (bulkyToolMessageCount > 0 || attachmentLikeMessageCount > 0) {
      lines += "Rewritten payloads: tool_output=$bulkyToolMessageCount, attachment_like=$attachmentLikeMessageCount."
    }
    return TranscriptPruningSummary(
      text = boundSummary(lines),
      removedMessageCount = removedMessageCount,
      rewrittenMessageCount = rewrittenMessageCount,
      duplicateBackgroundMessageCount = duplicateBackgroundMessageCount,
      bulkyToolMessageCount = bulkyToolMessageCount,
      attachmentLikeMessageCount = attachmentLikeMessageCount,
    )
  }

  private fun buildAttachmentSummary(content: String): String = buildString {
    appendLine("Attachment-like payload pruned from prompt.")
    appendLine("original_chars=${content.length}")
    appendLine("original_lines=${lineCount(content)}")
    append("Preview: ${preview(content)}")
  }.trim()

  private fun buildToolBudgetSummary(content: String): String = buildString {
    appendLine("Tool output pruned for prompt budget.")
    appendLine("original_chars=${content.length}")
    appendLine("original_lines=${lineCount(content)}")
    append("Preview: ${preview(content)}")
  }.trim()

  private fun exceedsToolBudget(content: String): Boolean =
    content.length > config.maxToolChars || lineCount(content) > config.maxToolLines

  private fun isAttachmentLike(content: String): Boolean {
    val normalized = content.trim()
    if (normalized.startsWith("data:") || normalized.contains(";base64,")) {
      return true
    }
    return normalized.lineSequence().any { line ->
      val candidate = line.trim()
      candidate.length >= config.maxAttachmentChars &&
        candidate.all { character -> character.isLetterOrDigit() || character in ATTACHMENT_LIKE_CHARS }
    }
  }

  private fun isDuplicateBackground(
    previous: RuntimeConversationMessage?,
    current: RuntimeConversationMessage,
  ): Boolean {
    val previousFingerprint = backgroundFingerprint(previous) ?: return false
    val currentFingerprint = backgroundFingerprint(current) ?: return false
    return previousFingerprint == currentFingerprint
  }

  private fun backgroundFingerprint(message: RuntimeConversationMessage?): String? {
    val current = message ?: return null
    return when (current.role) {
      RuntimeConversationRole.USER -> null
      RuntimeConversationRole.ASSISTANT -> current.content
        .takeIf(::isToolCallMarker)
        ?.let { content -> "assistant:${collapseWhitespace(content)}" }

      RuntimeConversationRole.TOOL,
      RuntimeConversationRole.SYSTEM,
      -> "${current.role.name.lowercase()}:${collapseWhitespace(current.content)}"
    }
  }

  private fun preview(content: String): String {
    val collapsed = collapseWhitespace(content)
    return if (collapsed.length <= config.maxPreviewChars) {
      collapsed
    } else {
      collapsed.take(config.maxPreviewChars - 1).trimEnd() + "…"
    }
  }

  private fun boundSummary(lines: List<String>): String {
    val builder = StringBuilder()
    lines.forEach { line ->
      if (line.isBlank()) {
        return@forEach
      }
      val next = if (builder.isEmpty()) line else builder.toString() + "\n" + line
      if (next.length > config.maxSummaryChars) {
        return@forEach
      }
      if (builder.isNotEmpty()) {
        builder.append('\n')
      }
      builder.append(line)
    }
    val summary = builder.toString().trim()
    return if (summary.length <= config.maxSummaryChars) {
      summary
    } else {
      summary.take(config.maxSummaryChars - 1).trimEnd() + "…"
    }
  }

  private fun collapseWhitespace(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

  private fun isToolCallMarker(content: String): Boolean =
    content.trim().startsWith("tool_call ")

  private fun lineCount(content: String): Int =
    content.lineSequence().count().coerceAtLeast(1)

  private data class MessageRewrite(
    val message: RuntimeConversationMessage,
    val changed: Boolean = false,
    val bulkyTool: Boolean = false,
    val attachmentLike: Boolean = false,
  )

  private companion object {
    const val ATTACHMENT_LIKE_CHARS: String = "+/=_-:,.;"
  }
}
