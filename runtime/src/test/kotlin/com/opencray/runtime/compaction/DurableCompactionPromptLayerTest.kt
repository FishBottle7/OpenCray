package com.opencray.runtime.compaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableCompactionPromptLayerTest {
  @Test
  fun renderMinimalKeepsArchiveCountsWithoutFullBody() {
    val layer = DurableCompactionPromptLayer(
      config = DurableCompactionPromptLayerConfig(
        maxCompactChars = 160,
      ),
    )
    val context = DurableCompactionContext(
      text = """
        Older session history has been durably compacted into summaries.
        [Compacted History]
        ${"Compacted workstream summary. ".repeat(12).trim()}
      """.trimIndent(),
      trace = DurableCompactionTrace(
        compactedThisRun = true,
        sourceTranscriptMessageCount = 18,
        retainedTranscriptMessageCount = 12,
        latestCompactedMessageCount = 6,
        includedSummaryCount = 2,
        omittedSummaryCount = 1,
        totalCompactedMessageCount = 12,
        latestCompactedAtEpochMs = 1_234L,
      ),
    )

    val rendered = layer.render(
      context = context,
      detailMode = DurableCompactionPromptDetailMode.MINIMAL,
    )

    assertTrue(rendered.contains("Durable compaction archive is available."))
    assertTrue(rendered.contains("included_summaries=2"))
    assertTrue(rendered.contains("omitted_summaries=1"))
    assertTrue(rendered.contains("total_compacted_messages=12"))
    assertTrue(rendered.contains("latest_compacted_message_count=6"))
    assertTrue(rendered.contains("latest_compacted_at_epoch_ms=1234"))
    assertFalse(rendered.contains("[Compacted History]"))
    assertFalse(rendered.contains("Compacted workstream summary."))
  }
}
