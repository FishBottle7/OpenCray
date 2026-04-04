package com.opencray.runtime.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryPromptLayerTest {
  @Test
  fun renderPruningSummaryMinimalKeepsOnlyCoreCounts() {
    val layer = TranscriptPruningSummaryPromptLayer()
    val summary = TranscriptPruningSummary(
      text = """
        Applied prompt-local pruning before windowing: removed=2, rewritten=3.
        Dropped consecutive duplicate background messages: 1.
        Rewritten payloads: tool_output=2, attachment_like=1.
      """.trimIndent(),
      removedMessageCount = 2,
      rewrittenMessageCount = 3,
      duplicateBackgroundMessageCount = 1,
      bulkyToolMessageCount = 2,
      attachmentLikeMessageCount = 1,
    )

    val rendered = layer.render(
      summary = summary,
      detailMode = TranscriptPruningSummaryPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.contains("Prompt-local pruning applied: removed=2 rewritten=3."))
    assertFalse(rendered.contains("duplicate background"))
    assertFalse(rendered.contains("tool_output=2"))
  }

  @Test
  fun renderCompactionSummaryMinimalKeepsOnlyCompactedMessageCount() {
    val layer = CompactionSummaryPromptLayer()
    val summary = CompactionSummary(
      text = """
        Compacted 6 older message(s) outside the active transcript window.
        Omitted roles: user=2, assistant=2, tool=2, system=0.
        Omitted tool activity: discovery=2.
      """.trimIndent(),
      compactedMessageCount = 6,
      omittedUserMessageCount = 2,
      omittedAssistantMessageCount = 2,
      omittedToolMessageCount = 2,
      omittedSystemMessageCount = 0,
    )

    val rendered = layer.render(
      summary = summary,
      detailMode = CompactionSummaryPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.contains("Compacted 6 older message(s)."))
    assertFalse(rendered.contains("Omitted roles"))
    assertFalse(rendered.contains("discovery=2"))
  }
}
