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
    var rewrittenMessageCount = 0
    var attachmentLikeMessageCount = 0

    normalized.forEach { message ->
      val rewrite = rewriteMessage(message)
      if (rewrite.changed) {
        rewrittenMessageCount += 1
      }
      if (rewrite.attachmentLike) {
        attachmentLikeMessageCount += 1
      }
      keptMessages += rewrite.message
    }

    val summary = buildSummary(
      rewrittenMessageCount = rewrittenMessageCount,
      attachmentLikeMessageCount = attachmentLikeMessageCount,
    )
    return PrunedTranscript(
      messages = keptMessages,
      summary = summary,
    )
  }

  private fun rewriteMessage(message: RuntimeConversationMessage): MessageRewrite {
    if (message.role != RuntimeConversationRole.TOOL) {
      return MessageRewrite(message = message)
    }
    if (!isAttachmentLike(message.content)) {
      return MessageRewrite(message = message)
    }
    return MessageRewrite(
      message = message.copy(content = buildAttachmentSummary(message.content)),
      changed = true,
      attachmentLike = true,
    )
  }

  private fun buildSummary(
    rewrittenMessageCount: Int,
    attachmentLikeMessageCount: Int,
  ): TranscriptPruningSummary? {
    if (rewrittenMessageCount == 0) {
      return null
    }
    val lines = mutableListOf<String>()
    lines += "Applied prompt-local guardrail pruning before windowing: removed=0, rewritten=$rewrittenMessageCount."
    if (attachmentLikeMessageCount > 0) {
      lines += "Rewritten payloads: tool_output=0, attachment_like=$attachmentLikeMessageCount."
    }
    return TranscriptPruningSummary(
      text = boundSummary(lines),
      removedMessageCount = 0,
      rewrittenMessageCount = rewrittenMessageCount,
      duplicateBackgroundMessageCount = 0,
      bulkyToolMessageCount = 0,
      attachmentLikeMessageCount = attachmentLikeMessageCount,
    )
  }

  private fun buildAttachmentSummary(content: String): String = buildString {
    appendLine("Attachment-like payload pruned by prompt guardrail.")
    appendLine("original_chars=${content.length}")
    appendLine("original_lines=${lineCount(content)}")
    append("Preview: ${preview(content)}")
  }.trim()

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

  private fun lineCount(content: String): Int =
    content.lineSequence().count().coerceAtLeast(1)

  private data class MessageRewrite(
    val message: RuntimeConversationMessage,
    val changed: Boolean = false,
    val attachmentLike: Boolean = false,
  )

  private companion object {
    const val ATTACHMENT_LIKE_CHARS: String = "+/=_-:,.;"
  }
}
