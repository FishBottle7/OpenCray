package com.opencray.runtime.compaction

import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.context.TranscriptWindowConfig
import com.opencray.runtime.session.InMemorySessionTranscriptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableCompactionCoordinatorTest {
  @Test
  fun compactIfNeededStoresSummaryAndTrimsTranscriptTail() {
    val transcriptStore = InMemorySessionTranscriptStore()
    transcriptStore.seedIfEmpty(
      listOf(
        user("User request 1"),
        assistant("Assistant reply 1"),
        user("User request 2"),
        assistant("Assistant reply 2"),
        user("User request 3"),
        assistant("Assistant reply 3"),
        user("User request 4"),
        assistant("Assistant reply 4"),
      ),
    )
    val compactionStore = InMemorySessionCompactionStore()
    val coordinator = DurableCompactionCoordinator(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 4,
          maxCharsPerMessage = 200,
        ),
      ),
      clock = { 5_000L },
    )

    val context = coordinator.compactIfNeeded(
      transcriptStore = transcriptStore,
      compactionStore = compactionStore,
      llmMetadata = mapOf("context_window_tokens" to "64"),
    )

    assertEquals(4, transcriptStore.snapshot().size)
    assertEquals(4, compactionStore.load().entries.single().compactedMessageCount)
    assertTrue(context.text.contains("Older session history has been durably compacted into summaries."))
    assertTrue(context.text.contains("[Compacted History]"))
    assertTrue(context.trace.compactedThisRun)
    assertEquals(8, context.trace.sourceTranscriptMessageCount)
    assertEquals(4, context.trace.retainedTranscriptMessageCount)
    assertEquals(4, context.trace.latestCompactedMessageCount)
    assertEquals(1, context.trace.includedSummaryCount)
    assertEquals(4, context.trace.totalCompactedMessageCount)
    assertEquals(5_000L, context.trace.latestCompactedAtEpochMs)
  }

  @Test
  fun compactIfNeededReturnsStoredContextWhenNoNewRewriteIsNeeded() {
    val transcriptStore = InMemorySessionTranscriptStore()
    transcriptStore.seedIfEmpty(
      listOf(
        user("Current request"),
        assistant("Current reply"),
        user("Latest user input"),
      ),
    )
    val compactionStore = InMemorySessionCompactionStore().apply {
      save(
        DurableCompactionState(
          entries = listOf(
            DurableCompactionEntry(
              text = "Compacted 6 older message(s) outside the active transcript window.",
              compactedMessageCount = 6,
              compactedAtEpochMs = 1_234L,
            ),
          ),
        ),
      )
    }
    val coordinator = DurableCompactionCoordinator(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 6,
          maxCharsPerMessage = 200,
        ),
      ),
    )

    val context = coordinator.compactIfNeeded(
      transcriptStore = transcriptStore,
      compactionStore = compactionStore,
    )

    assertEquals(3, transcriptStore.snapshot().size)
    assertFalse(context.trace.compactedThisRun)
    assertEquals(3, context.trace.sourceTranscriptMessageCount)
    assertEquals(3, context.trace.retainedTranscriptMessageCount)
    assertEquals(0, context.trace.latestCompactedMessageCount)
    assertEquals(1, context.trace.includedSummaryCount)
    assertEquals(0, context.trace.omittedSummaryCount)
    assertEquals(6, context.trace.totalCompactedMessageCount)
    assertEquals(1_234L, context.trace.latestCompactedAtEpochMs)
    assertTrue(context.text.contains("Older session history has been durably compacted into summaries."))
  }

  @Test
  fun compactIfNeededWaitsForReplayTokenPressure() {
    val transcriptStore = InMemorySessionTranscriptStore()
    transcriptStore.seedIfEmpty(
      listOf(
        user("User request 1"),
        assistant("Assistant reply 1"),
        user("User request 2"),
        assistant("Assistant reply 2"),
        user("User request 3"),
        assistant("Assistant reply 3"),
        user("User request 4"),
        assistant("Assistant reply 4"),
      ),
    )
    val compactionStore = InMemorySessionCompactionStore()
    val coordinator = DurableCompactionCoordinator(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 4,
          maxCharsPerMessage = 200,
        ),
      ),
      clock = { 5_000L },
    )

    val context = coordinator.compactIfNeeded(
      transcriptStore = transcriptStore,
      compactionStore = compactionStore,
      llmMetadata = mapOf("context_window_tokens" to "4096"),
    )

    assertEquals(8, transcriptStore.snapshot().size)
    assertTrue(compactionStore.load().entries.isEmpty())
    assertFalse(context.trace.compactedThisRun)
    assertEquals(8, context.trace.sourceTranscriptMessageCount)
    assertEquals(8, context.trace.retainedTranscriptMessageCount)
    assertEquals(0, context.trace.latestCompactedMessageCount)
    assertFalse(context.included)
  }

  @Test
  fun compactIfNeededCouplesTranscriptWindowToExpandedSourcePreset() {
    val balancedTranscriptStore = InMemorySessionTranscriptStore()
    balancedTranscriptStore.seedIfEmpty(
      (1..16).map { index ->
        if (index % 2 == 0) {
          assistant("Assistant reply $index with enough content to keep replay pressure high.")
        } else {
          user("User request $index with enough content to keep replay pressure high.")
        }
      },
    )
    val expandedTranscriptStore = InMemorySessionTranscriptStore()
    expandedTranscriptStore.seedIfEmpty(balancedTranscriptStore.snapshot())
    val balancedCoordinator = DurableCompactionCoordinator(
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
      clock = { 5_000L },
    )
    val expandedCoordinator = DurableCompactionCoordinator(
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
      clock = { 5_000L },
    )

    val balancedContext = balancedCoordinator.compactIfNeeded(
      transcriptStore = balancedTranscriptStore,
      compactionStore = InMemorySessionCompactionStore(),
      llmMetadata = mapOf("context_window_tokens" to "64"),
    )
    val expandedContext = expandedCoordinator.compactIfNeeded(
      transcriptStore = expandedTranscriptStore,
      compactionStore = InMemorySessionCompactionStore(),
      llmMetadata = mapOf(
        "context_window_tokens" to "64",
        "context_budget_preset" to "expanded",
      ),
    )

    assertTrue(balancedContext.trace.compactedThisRun)
    assertEquals(12, balancedContext.trace.retainedTranscriptMessageCount)
    assertEquals(12, balancedTranscriptStore.snapshot().size)
    assertFalse(expandedContext.trace.compactedThisRun)
    assertEquals(16, expandedContext.trace.retainedTranscriptMessageCount)
    assertEquals(16, expandedTranscriptStore.snapshot().size)
  }

  private fun user(content: String): RuntimeConversationMessage =
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = content,
    )

  private fun assistant(content: String): RuntimeConversationMessage =
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = content,
    )
}
