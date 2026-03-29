package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LlmProviderRequestSupportTest {
  @Test
  fun recommendedInteractiveProviderRouteTimeoutUsesLongerBudgetForKimiK2Family() {
    assertEquals(120_000L, recommendedInteractiveProviderRouteTimeoutMs("kimi-k2.5"))
    assertEquals(120_000L, recommendedInteractiveProviderRouteTimeoutMs("moonshotai/kimi-k2:online"))
    assertEquals(30_000L, recommendedInteractiveProviderRouteTimeoutMs("gpt-4o-mini"))
  }

  @Test
  fun recommendedShortProviderRouteTimeoutUsesLongerBudgetForKimiK2Family() {
    assertEquals(60_000L, recommendedInterpreterProviderRouteTimeoutMs("kimi-k2.5"))
    assertEquals(60_000L, recommendedValidationProviderRouteTimeoutMs("moonshotai/kimi-k2:online"))
    assertEquals(15_000L, recommendedValidationProviderRouteTimeoutMs("claude-3-7-sonnet"))
  }

  @Test
  fun anthropicRouteMetadataOmitsThinkingBudgetForKimiModels() {
    val metadata = LlmProviderProtocols.routeMetadata(
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "kimi-k2.5",
      reasoningEffort = "xhigh",
    )

    assertEquals("anthropic", metadata["protocol"])
    assertFalse(metadata.containsKey("thinking_budget_tokens"))
  }
}
