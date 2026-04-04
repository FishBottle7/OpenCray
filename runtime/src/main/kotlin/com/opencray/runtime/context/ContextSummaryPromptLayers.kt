package com.opencray.runtime.context

enum class TranscriptPruningSummaryPromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

class TranscriptPruningSummaryPromptLayer {
  fun render(
    summary: TranscriptPruningSummary?,
    detailMode: TranscriptPruningSummaryPromptDetailMode = TranscriptPruningSummaryPromptDetailMode.FULL,
  ): String {
    val current = summary ?: return ""
    return when (detailMode) {
      TranscriptPruningSummaryPromptDetailMode.FULL -> current.text.trim()
      TranscriptPruningSummaryPromptDetailMode.COMPACT -> buildString {
        appendLine("Prompt-local pruning was applied before transcript windowing.")
        appendLine("removed=${current.removedMessageCount} rewritten=${current.rewrittenMessageCount}")
        if (current.duplicateBackgroundMessageCount > 0) {
          appendLine("duplicate_background=${current.duplicateBackgroundMessageCount}")
        }
        if (current.bulkyToolMessageCount > 0 || current.attachmentLikeMessageCount > 0) {
          appendLine(
            "rewritten_payloads tool_output=${current.bulkyToolMessageCount} attachment_like=${current.attachmentLikeMessageCount}",
          )
        }
      }.trim()

      TranscriptPruningSummaryPromptDetailMode.MINIMAL ->
        "Prompt-local pruning applied: removed=${current.removedMessageCount} rewritten=${current.rewrittenMessageCount}."
    }
  }
}

enum class CompactionSummaryPromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

class CompactionSummaryPromptLayer {
  fun render(
    summary: CompactionSummary?,
    detailMode: CompactionSummaryPromptDetailMode = CompactionSummaryPromptDetailMode.FULL,
  ): String {
    val current = summary ?: return ""
    return when (detailMode) {
      CompactionSummaryPromptDetailMode.FULL -> current.text.trim()
      CompactionSummaryPromptDetailMode.COMPACT -> buildString {
        appendLine("Older transcript outside the active window was compacted.")
        appendLine("compacted_messages=${current.compactedMessageCount}")
        appendLine(
          "roles user=${current.omittedUserMessageCount} assistant=${current.omittedAssistantMessageCount} tool=${current.omittedToolMessageCount} system=${current.omittedSystemMessageCount}",
        )
      }.trim()

      CompactionSummaryPromptDetailMode.MINIMAL -> "Compacted ${current.compactedMessageCount} older message(s)."
    }
  }
}
