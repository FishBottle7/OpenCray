package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPressureEvaluatorTest {
  private val evaluator = ReplayPressureEvaluator()

  @Test
  fun evaluateDefaultsAutoCompactLimitToNinetyPercentOfContextWindow() {
    val snapshot = evaluator.evaluate(
      conversation = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "a".repeat(1_000),
        ),
      ),
      llmMetadata = mapOf("context_window_tokens" to "1000"),
    )

    assertEquals(1_000, snapshot.contextWindowTokens)
    assertEquals(900, snapshot.autoCompactTokenLimit)
    assertTrue(snapshot.estimatedReplayTokens >= 250)
    assertFalse(snapshot.tokenThresholdTriggered)
  }

  @Test
  fun evaluateClampsExplicitAutoCompactLimitToNinetyPercentOfContextWindow() {
    val snapshot = evaluator.evaluate(
      conversation = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "b".repeat(4_000),
        ),
      ),
      llmMetadata = mapOf(
        "context_window_tokens" to "1000",
        "auto_compact_token_limit" to "950",
      ),
    )

    assertEquals(900, snapshot.autoCompactTokenLimit)
    assertTrue(snapshot.tokenThresholdTriggered)
  }

  @Test
  fun evaluateTightensAutoCompactLimitWhenCurrentModelWindowShrinks() {
    val snapshot = evaluator.evaluate(
      conversation = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "c".repeat(3_420),
        ),
      ),
      llmMetadata = mapOf(
        "context_window_tokens" to "1000",
        "previous_context_window_tokens" to "4000",
      ),
    )

    assertEquals(1_000, snapshot.contextWindowTokens)
    assertEquals(4_000, snapshot.previousContextWindowTokens)
    assertTrue(snapshot.smallerWindowModelSwitchDetected)
    assertEquals(850, snapshot.autoCompactTokenLimit)
    assertTrue(snapshot.estimatedReplayTokens in 850 until 900)
    assertTrue(snapshot.tokenThresholdTriggered)
  }

  @Test
  fun evaluateDoesNotTightenAutoCompactLimitWhenPreviousWindowWasNotLarger() {
    val snapshot = evaluator.evaluate(
      conversation = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "d".repeat(3_420),
        ),
      ),
      llmMetadata = mapOf(
        "context_window_tokens" to "1000",
        "previous_context_window_tokens" to "800",
      ),
    )

    assertEquals(1_000, snapshot.contextWindowTokens)
    assertEquals(800, snapshot.previousContextWindowTokens)
    assertFalse(snapshot.smallerWindowModelSwitchDetected)
    assertEquals(900, snapshot.autoCompactTokenLimit)
    assertTrue(snapshot.estimatedReplayTokens in 850 until 900)
    assertFalse(snapshot.tokenThresholdTriggered)
  }
}
