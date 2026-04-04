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
}
