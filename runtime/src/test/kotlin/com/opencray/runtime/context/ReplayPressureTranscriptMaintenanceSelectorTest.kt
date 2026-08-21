package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPressureTranscriptMaintenanceSelectorTest {
  private val evaluator = ReplayPressureEvaluator()
  private val pruner = ContextPruner()

  @Test
  fun selectCreatesPressureDrivenOmittedPrefixWhenBaseWindowStillTripsThreshold() {
    val selector = ReplayPressureTranscriptMaintenanceSelector(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 6,
          maxCharsPerMessage = 240,
        ),
      ),
      contextPruner = pruner,
      replayPressureEvaluator = evaluator,
      minRetainedMessages = 4,
    )
    val llmMetadata = mapOf(
      "context_window_tokens" to "4096",
      "auto_compact_token_limit" to "80",
    )
    val conversation = listOf(
      user("Older user replay should still pressure budget."),
      assistant("Older assistant replay should still pressure budget."),
      user("Middle user replay should still pressure budget."),
      assistant("Middle assistant replay should still pressure budget."),
      user("Recent user replay should still pressure budget."),
      assistant("Recent assistant replay should still pressure budget."),
    )
    val baseSelection = TranscriptWindowBuilder(
      TranscriptWindowConfig(
        maxMessages = 6,
        maxCharsPerMessage = 240,
      ),
    ).buildSelection(conversation)
    val baseRetainedPressure = evaluator.evaluate(
      conversation = pruner.prune(baseSelection.window.messages).messages,
      llmMetadata = llmMetadata,
    )

    val selection = selector.select(
      conversation = conversation,
      llmMetadata = llmMetadata,
    )
    val retainedPressure = evaluator.evaluate(
      conversation = pruner.prune(selection.window.messages).messages,
      llmMetadata = llmMetadata,
    )

    assertTrue(selection.omittedMessages.isNotEmpty())
    assertEquals(4, selection.window.messages.size)
    assertTrue(baseRetainedPressure.tokenThresholdTriggered)
    assertFalse(retainedPressure.tokenThresholdTriggered)
  }

  @Test
  fun selectKeepsBaseSelectionWhenRetainedWindowAlreadyFitsThreshold() {
    val builder = TranscriptWindowBuilder(
      TranscriptWindowConfig(
        maxMessages = 6,
        maxCharsPerMessage = 240,
      ),
    )
    val selector = ReplayPressureTranscriptMaintenanceSelector(
      transcriptWindowBuilder = builder,
      contextPruner = pruner,
      replayPressureEvaluator = evaluator,
      minRetainedMessages = 4,
    )
    val llmMetadata = mapOf(
      "context_window_tokens" to "4096",
      "auto_compact_token_limit" to "80",
    )
    val conversation = listOf(
      user("older-one"),
      assistant("older-two"),
      user("older-three"),
      assistant("older-four"),
      user("older-five"),
      assistant("older-six"),
      user("latest-seven"),
    )
    val baseSelection = builder.buildSelection(conversation)

    val selection = selector.select(
      conversation = conversation,
      llmMetadata = llmMetadata,
    )

    assertEquals(baseSelection.window.messages, selection.window.messages)
    assertEquals(baseSelection.omittedMessages, selection.omittedMessages)
  }

  @Test
  fun selectDoesNotShrinkBelowOneRetainedMessage() {
    val selector = ReplayPressureTranscriptMaintenanceSelector(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 4,
          maxCharsPerMessage = 240,
        ),
      ),
      contextPruner = pruner,
      replayPressureEvaluator = evaluator,
      minRetainedMessages = 4,
    )
    val conversation = listOf(
      user("x".repeat(256)),
    )

    val selection = selector.select(
      conversation = conversation,
      llmMetadata = mapOf(
        "context_window_tokens" to "64",
        "auto_compact_token_limit" to "10",
      ),
    )

    assertEquals(1, selection.window.messages.size)
    assertTrue(selection.omittedMessages.isEmpty())
    assertEquals(240, selection.window.messages.single().content.length)
    assertTrue(selection.window.messages.single().content.endsWith("…"))
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
