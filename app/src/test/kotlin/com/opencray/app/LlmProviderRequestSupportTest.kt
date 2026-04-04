package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @Test
  fun openAiResponsesRouteMetadataIncludesPromptCacheHintsWhenConfigured() {
    val metadata = LlmProviderProtocols.routeMetadata(
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5",
      reasoningEffort = "medium",
      openAiPromptCacheKeyStrategy = LlmPromptCacheKeyStrategies.SESSION,
      openAiPromptCacheRetention = LlmPromptCacheRetentionPolicies.HOURS_24,
    )

    assertEquals("openai_responses", metadata["protocol"])
    assertEquals(
      LlmPromptCacheKeyStrategies.SESSION,
      metadata[LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY],
    )
    assertEquals(
      LlmPromptCacheRetentionPolicies.HOURS_24,
      metadata[LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION],
    )
  }

  @Test
  fun anthropicRouteMetadataIncludesPromptCachingWhenEnabled() {
    val metadata = LlmProviderProtocols.routeMetadata(
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "claude-3-7-sonnet",
      reasoningEffort = "high",
      anthropicPromptCachingEnabled = true,
      anthropicPromptCacheTtl = AnthropicPromptCacheTtlPolicies.HOUR_1,
    )

    assertEquals("anthropic", metadata["protocol"])
    assertEquals("8192", metadata["thinking_budget_tokens"])
    assertEquals(
      "true",
      metadata[LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHING_ENABLED],
    )
    assertEquals(
      AnthropicPromptCacheTtlPolicies.HOUR_1,
      metadata[LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHE_TTL],
    )
    assertTrue(metadata.containsKey(LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHE_TTL))
  }
}
