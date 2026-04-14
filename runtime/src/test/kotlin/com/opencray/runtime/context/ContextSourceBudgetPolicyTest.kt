package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSourceBudgetPolicyTest {
  private val policy = ContextSourceBudgetPolicy()

  @Test
  fun resolveFallsBackToBalancedSourceCapsWhenMetadataIsMissing() {
    val profile = policy.resolve(emptyMap())

    assertEquals(ModelContextBudgetPreset.BALANCED, profile.sourcePreset)
    assertEquals(12, profile.transcriptWindowConfig.maxMessages)
    assertEquals(4, profile.contextManagerConfig.maxInjectedMemoryRecords)
    assertEquals(6, profile.memoryPolicy.recallBudget.maxRecords)
    assertEquals(3_200, profile.bootstrapContextResolverConfig.maxTotalChars)
    assertEquals(8, profile.skillInventoryPromptLayerConfig.maxSkills)
    assertEquals(3_200, profile.activeSkillPromptLayerConfig.maxBodyChars)
    assertEquals(4, profile.recentToolObservationConfig.maxEntries)
    assertEquals(8, profile.memoryFlushPolicy.maxToolObservations)
  }

  @Test
  fun resolveExpandsSourceCapsForExpandedPreset() {
    val profile = policy.resolve(
      mapOf(
        "context_budget_preset" to "expanded",
        "context_window_tokens" to "128000",
      ),
    )

    assertEquals(ModelContextBudgetPreset.EXPANDED, profile.sourcePreset)
    assertEquals(16, profile.transcriptWindowConfig.maxMessages)
    assertEquals(6, profile.contextManagerConfig.maxInjectedMemoryRecords)
    assertEquals(8, profile.memoryPolicy.recallBudget.maxRecords)
    assertEquals(4_800, profile.bootstrapContextResolverConfig.maxTotalChars)
    assertEquals(12, profile.skillInventoryPromptLayerConfig.maxSkills)
    assertEquals(4_800, profile.activeSkillPromptLayerConfig.maxBodyChars)
    assertEquals(6, profile.recentToolObservationConfig.maxEntries)
    assertEquals(12, profile.memoryFlushPolicy.maxToolObservations)
  }

  @Test
  fun resolveMapsDivergedDevEnvelopeToNearestStableSourcePreset() {
    val profile = policy.resolve(
      mapOf(
        "context_budget_preset" to "balanced",
        "context_window_tokens" to "128000",
        "reserved_output_tokens" to "4096",
        "prompt_safety_margin_tokens" to "512",
        "effective_input_percent" to "0.92",
      ),
    )

    assertTrue(profile.envelope.presetDiverged)
    assertEquals(ModelContextBudgetPreset.EXPANDED, profile.sourcePreset)
    assertEquals("dev", profile.envelope.effectivePreset)
    assertEquals(16, profile.transcriptWindowConfig.maxMessages)
    assertEquals(4_800, profile.bootstrapContextResolverConfig.maxTotalChars)
  }
}
