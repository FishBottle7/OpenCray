package com.opencray.runtime.compaction

data class DurableCompactionPromptLayerConfig(
  val maxCompactChars: Int = 480,
) {
  init {
    require(maxCompactChars >= 128) {
      "DurableCompactionPromptLayerConfig maxCompactChars must be >= 128."
    }
  }
}

enum class DurableCompactionPromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

class DurableCompactionPromptLayer(
  private val config: DurableCompactionPromptLayerConfig = DurableCompactionPromptLayerConfig(),
) {
  fun render(
    context: DurableCompactionContext,
    detailMode: DurableCompactionPromptDetailMode = DurableCompactionPromptDetailMode.FULL,
  ): String {
    if (!context.included) {
      return ""
    }
    return when (detailMode) {
      DurableCompactionPromptDetailMode.FULL -> context.text.trim()
      DurableCompactionPromptDetailMode.COMPACT -> renderCompact(context)
      DurableCompactionPromptDetailMode.MINIMAL -> renderMinimal(context)
    }
  }

  private fun renderCompact(
    context: DurableCompactionContext,
  ): String {
    val compactedPreview = boundContent(context.text.trim(), config.maxCompactChars)
    val promptTruncated = compactedPreview != context.text.trim()
    return buildString {
      appendSummaryLines(context.trace)
      if (promptTruncated) {
        appendLine("prompt_truncated=true")
      }
      appendLine()
      append(compactedPreview)
    }.trim()
  }

  private fun renderMinimal(
    context: DurableCompactionContext,
  ): String = buildString {
    appendSummaryLines(context.trace)
  }.trim()

  private fun StringBuilder.appendSummaryLines(trace: DurableCompactionTrace) {
    appendLine("Durable compaction archive is available.")
    appendLine("included_summaries=${trace.includedSummaryCount}")
    appendLine("omitted_summaries=${trace.omittedSummaryCount}")
    appendLine("total_compacted_messages=${trace.totalCompactedMessageCount}")
    if (trace.latestCompactedMessageCount > 0) {
      appendLine("latest_compacted_message_count=${trace.latestCompactedMessageCount}")
    }
    trace.latestCompactedAtEpochMs?.let { compactedAtEpochMs ->
      appendLine("latest_compacted_at_epoch_ms=$compactedAtEpochMs")
    }
  }

  private fun boundContent(
    text: String,
    maxChars: Int,
  ): String {
    if (text.length <= maxChars) {
      return text
    }
    return text.take(maxChars - 1).trimEnd() + "…"
  }
}
