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
    assertEquals("pre_compaction", context.trace.triggerStage)
    assertEquals(64, context.trace.contextWindowTokens)
    assertEquals(57, context.trace.autoCompactTokenLimit)
    assertTrue(context.trace.estimatedReplayTokens >= context.trace.autoCompactTokenLimit)
    assertTrue(context.trace.tokenThresholdTriggered)
    assertEquals(8, context.trace.sourceTranscriptMessageCount)
    assertEquals(4, context.trace.retainedTranscriptMessageCount)
    assertEquals(4, context.trace.latestCompactedMessageCount)
    assertEquals(1, context.trace.includedSummaryCount)
    assertEquals(4, context.trace.totalCompactedMessageCount)
    assertEquals(5_000L, context.trace.latestCompactedAtEpochMs)
  }

  @Test
  fun compactIfNeededAppendsAgainstStoreUpdateCurrentState() {
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
    val compactionStore = UpdatingCompactionStoreWithStaleLoad(
      staleLoadState = DurableCompactionState(),
      currentState = DurableCompactionState(
        entries = listOf(
          DurableCompactionEntry(
            text = "Concurrent summary already persisted.",
            compactedMessageCount = 5,
            compactedAtEpochMs = 4_000L,
          ),
        ),
      ),
    )
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

    assertEquals(1, compactionStore.updateCallCount)
    assertEquals(0, compactionStore.saveCallCount)
    val persistedEntries = compactionStore.current().entries
    assertEquals("Concurrent summary already persisted.", persistedEntries.first().text)
    assertTrue(persistedEntries.last().text.contains("Compacted 4 older message(s)"))
    assertEquals(listOf(5, 4), persistedEntries.map { entry -> entry.compactedMessageCount })
    assertEquals(2, context.trace.includedSummaryCount)
    assertEquals(9, context.trace.totalCompactedMessageCount)
  }

  @Test
  fun compactMidTurnStoresSummaryWithMaintenanceTaskAndEntryTrace() {
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
      clock = { 5_100L },
    )

    val context = coordinator.compactMidTurn(
      transcriptStore = transcriptStore,
      compactionStore = compactionStore,
      llmMetadata = mapOf("context_window_tokens" to "64"),
    )

    assertTrue(context.trace.compactedThisRun)
    assertEquals("mid_turn", context.trace.triggerStage)
    assertEquals("durable_compaction:mid_turn", context.trace.maintenanceTask)
    assertEquals("inline", context.trace.executionMode)
    assertEquals(4, transcriptStore.snapshot().size)
    assertEquals(1, context.trace.entryTraces.size)
    assertEquals(4, context.trace.entryTraces.single().compactedMessageCount)
    assertEquals(2, context.trace.entryTraces.single().omittedUserMessageCount)
    assertEquals(2, context.trace.entryTraces.single().omittedAssistantMessageCount)
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
    assertEquals("pre_compaction", context.trace.triggerStage)
    assertEquals(4096, context.trace.contextWindowTokens)
    assertEquals(3686, context.trace.autoCompactTokenLimit)
    assertTrue(context.trace.estimatedReplayTokens < context.trace.autoCompactTokenLimit)
    assertFalse(context.trace.tokenThresholdTriggered)
    assertEquals(8, context.trace.sourceTranscriptMessageCount)
    assertEquals(8, context.trace.retainedTranscriptMessageCount)
    assertEquals(0, context.trace.latestCompactedMessageCount)
    assertFalse(context.included)
  }

  @Test
  fun compactIfNeededCouplesTranscriptWindowToExpandedSourcePreset() {
    val conversation = (1..20).map { index ->
      if (index % 2 == 0) {
        assistant("Assistant reply $index with enough content to keep replay pressure high.")
      } else {
        user("User request $index with enough content to keep replay pressure high.")
      }
    }
    val compactPresetTranscriptStore = InMemorySessionTranscriptStore()
    compactPresetTranscriptStore.seedIfEmpty(conversation)
    val expandedPresetTranscriptStore = InMemorySessionTranscriptStore()
    expandedPresetTranscriptStore.seedIfEmpty(conversation)
    val compactPresetCoordinator = DurableCompactionCoordinator(
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
      clock = { 5_000L },
    )
    val expandedPresetCoordinator = DurableCompactionCoordinator(
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
      clock = { 5_000L },
    )
    val llmMetadata = mapOf(
      "context_window_tokens" to "4096",
      "auto_compact_token_limit" to "300",
    )

    val compactPresetContext = compactPresetCoordinator.compactIfNeeded(
      transcriptStore = compactPresetTranscriptStore,
      compactionStore = InMemorySessionCompactionStore(),
      llmMetadata = llmMetadata,
    )
    val expandedPresetContext = expandedPresetCoordinator.compactIfNeeded(
      transcriptStore = expandedPresetTranscriptStore,
      compactionStore = InMemorySessionCompactionStore(),
      llmMetadata = llmMetadata + mapOf("context_budget_preset" to "expanded"),
    )

    assertTrue(compactPresetContext.trace.compactedThisRun)
    assertEquals("pre_compaction", compactPresetContext.trace.triggerStage)
    assertEquals(20, compactPresetContext.trace.sourceTranscriptMessageCount)
    assertTrue(compactPresetContext.trace.tokenThresholdTriggered)
    assertEquals(
      compactPresetContext.trace.retainedTranscriptMessageCount,
      compactPresetTranscriptStore.snapshot().size,
    )
    assertTrue(compactPresetContext.trace.latestCompactedMessageCount >= 4)
    assertTrue(expandedPresetContext.trace.compactedThisRun)
    assertEquals("pre_compaction", expandedPresetContext.trace.triggerStage)
    assertEquals(20, expandedPresetContext.trace.sourceTranscriptMessageCount)
    assertTrue(expandedPresetContext.trace.retainedTranscriptMessageCount > 4)
    assertTrue(
      expandedPresetContext.trace.retainedTranscriptMessageCount >
        compactPresetContext.trace.retainedTranscriptMessageCount,
    )
    assertEquals(
      expandedPresetContext.trace.retainedTranscriptMessageCount,
      expandedPresetTranscriptStore.snapshot().size,
    )
    assertTrue(expandedPresetContext.trace.latestCompactedMessageCount >= 4)
  }

  @Test
  fun compactIfNeededHonorsExplicitAutoCompactTokenLimit() {
    val transcriptStore = InMemorySessionTranscriptStore()
    transcriptStore.seedIfEmpty(
      listOf(
        user("User request 1 with enough replay text to cross the explicit compact limit when repeated."),
        assistant("Assistant reply 1 with enough replay text to cross the explicit compact limit when repeated."),
        user("User request 2 with enough replay text to cross the explicit compact limit when repeated."),
        assistant("Assistant reply 2 with enough replay text to cross the explicit compact limit when repeated."),
        user("User request 3 with enough replay text to cross the explicit compact limit when repeated."),
        assistant("Assistant reply 3 with enough replay text to cross the explicit compact limit when repeated."),
        user("User request 4 with enough replay text to cross the explicit compact limit when repeated."),
        assistant("Assistant reply 4 with enough replay text to cross the explicit compact limit when repeated."),
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
      llmMetadata = mapOf(
        "context_window_tokens" to "4096",
        "auto_compact_token_limit" to "100",
      ),
    )

    assertEquals(4, transcriptStore.snapshot().size)
    assertEquals(4, compactionStore.load().entries.single().compactedMessageCount)
    assertTrue(context.trace.compactedThisRun)
    assertEquals(8, context.trace.sourceTranscriptMessageCount)
    assertEquals(4, context.trace.retainedTranscriptMessageCount)
    assertEquals(4, context.trace.latestCompactedMessageCount)
    assertEquals(4096, context.trace.contextWindowTokens)
    assertEquals(100, context.trace.autoCompactTokenLimit)
    assertTrue(context.trace.tokenThresholdTriggered)
  }

  @Test
  fun compactIfNeededCanCreatePressureDrivenOmittedPrefix() {
    val transcriptStore = InMemorySessionTranscriptStore()
    transcriptStore.seedIfEmpty(
      listOf(
        user("User request 1 captures older context before pressure-driven compaction narrows replay."),
        assistant("Assistant reply 1 captures older context before pressure-driven compaction narrows replay."),
        user("User request 2 captures older context before pressure-driven compaction narrows replay."),
        assistant("Assistant reply 2 captures older context before pressure-driven compaction narrows replay."),
        user(
          ("User request 3 keeps the replay heavy enough that pressure-driven compaction must cut an older prefix. ").repeat(6).trim(),
        ),
        assistant(
          ("Assistant reply 3 keeps the replay heavy enough that pressure-driven compaction must cut an older prefix. ").repeat(6).trim(),
        ),
        user(
          ("User request 4 keeps the replay heavy enough that pressure-driven compaction must cut an older prefix. ").repeat(6).trim(),
        ),
        assistant("Assistant reply 4 should remain close to the active replay tail."),
      ),
    )
    val compactionStore = InMemorySessionCompactionStore()
    val coordinator = DurableCompactionCoordinator(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 8,
          maxCharsPerMessage = 200,
        ),
      ),
      clock = { 5_000L },
    )

    val context = coordinator.compactIfNeeded(
      transcriptStore = transcriptStore,
      compactionStore = compactionStore,
      llmMetadata = mapOf(
        "context_window_tokens" to "4096",
        "auto_compact_token_limit" to "100",
      ),
    )

    assertTrue(context.trace.compactedThisRun)
    assertTrue(context.trace.latestCompactedMessageCount >= 1)
    assertTrue(transcriptStore.snapshot().size < 8)
    assertEquals(
      context.trace.latestCompactedMessageCount,
      compactionStore.load().entries.single().compactedMessageCount,
    )
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

  private class UpdatingCompactionStoreWithStaleLoad(
    private val staleLoadState: DurableCompactionState,
    currentState: DurableCompactionState,
  ) : SessionCompactionStore {
    private var state = currentState
    var updateCallCount: Int = 0
      private set
    var saveCallCount: Int = 0
      private set

    override fun load(): DurableCompactionState = staleLoadState

    override fun save(state: DurableCompactionState) {
      saveCallCount += 1
      error("Compaction append should use SessionCompactionStore.update.")
    }

    override fun update(transform: (DurableCompactionState) -> DurableCompactionState): DurableCompactionState {
      updateCallCount += 1
      val updated = transform(state)
      state = updated
      return updated
    }

    override fun clear() {
      state = DurableCompactionState()
    }

    fun current(): DurableCompactionState = state
  }
}
