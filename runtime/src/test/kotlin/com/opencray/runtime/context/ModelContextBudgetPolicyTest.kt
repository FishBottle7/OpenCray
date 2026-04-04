package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextBudgetPolicyTest {
  private val policy = ModelContextBudgetPolicy()

  @Test
  fun resolveFallsBackToRaisedDefaultContextWindowWhenMetadataIsMissing() {
    val envelope = policy.resolve(emptyMap())

    assertEquals(128_000, envelope.contextWindowTokens)
    assertEquals(2_048, envelope.reservedOutputTokens)
    assertEquals(1_024, envelope.safetyMarginTokens)
    assertEquals(124_928, envelope.hardInputBudgetTokens)
    assertEquals(106_188, envelope.targetInputBudgetTokens)
    assertEquals(115_558, envelope.emergencyInputBudgetTokens)
  }
}
