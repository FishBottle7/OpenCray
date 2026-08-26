package com.opencray.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayRecoveryPolicyRetryBackoffTest {
  @Test
  fun backoffDoublesPerConsecutiveTransientRetryAndCapsAtThirtySeconds() {
    val delays = (1..6).map { attempt ->
      transientGatewayRetryBackoffDelayMs(baseDelayMs = 2_000L, retryAttempt = attempt, jitterDraw = 0.5)
    }
    assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
  }

  @Test
  fun jitterStaysWithinTwentyPercentBounds() {
    assertEquals(1_600L, transientGatewayRetryBackoffDelayMs(2_000L, 1, jitterDraw = 0.0))
    assertEquals(2_400L, transientGatewayRetryBackoffDelayMs(2_000L, 1, jitterDraw = 1.0))
    assertEquals(24_000L, transientGatewayRetryBackoffDelayMs(30_000L, 3, jitterDraw = 0.0))
    assertEquals(36_000L, transientGatewayRetryBackoffDelayMs(30_000L, 3, jitterDraw = 1.0))
  }

  @Test
  fun nonPositiveAttemptsFallBackToBaseDelay() {
    assertEquals(2_000L, transientGatewayRetryBackoffDelayMs(2_000L, 0, jitterDraw = 0.5))
    assertEquals(2_000L, transientGatewayRetryBackoffDelayMs(2_000L, -3, jitterDraw = 0.5))
  }

  @Test
  fun zeroOrNegativeBaseDelaysStayZero() {
    assertEquals(0L, transientGatewayRetryBackoffDelayMs(0L, 5, jitterDraw = 1.0))
    assertEquals(0L, transientGatewayRetryBackoffDelayMs(-1_000L, 2, jitterDraw = 0.0))
  }
}
