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
    assertEquals("balanced", envelope.selectedPreset)
    assertEquals("balanced", envelope.effectivePreset)
    assertEquals("default", envelope.presetSource)
    assertEquals(false, envelope.presetDiverged)
    assertEquals(124_928, envelope.hardInputBudgetTokens)
    assertEquals(106_188, envelope.targetInputBudgetTokens)
    assertEquals(115_558, envelope.emergencyInputBudgetTokens)
  }

  @Test
  fun resolveUsesExplicitPresetDefaultsWhenNoRawOverridesAreProvided() {
    val envelope = policy.resolve(
      mapOf(
        "context_budget_preset" to "expanded",
        "context_window_tokens" to "128000",
      ),
    )

    assertEquals(1_536, envelope.reservedOutputTokens)
    assertEquals(768, envelope.safetyMarginTokens)
    assertEquals(0.90, envelope.effectiveInputPercent, 0.0)
    assertEquals("expanded", envelope.selectedPreset)
    assertEquals("expanded", envelope.effectivePreset)
    assertEquals("explicit", envelope.presetSource)
    assertEquals(false, envelope.presetDiverged)
  }

  @Test
  fun resolveSurfacesDevWhenRawOverridesDivergeFromKnownPreset() {
    val envelope = policy.resolve(
      mapOf(
        "context_budget_preset" to "balanced",
        "context_window_tokens" to "128000",
        "reserved_output_tokens" to "4096",
        "prompt_safety_margin_tokens" to "512",
        "effective_input_percent" to "0.92",
      ),
    )

    assertEquals(4_096, envelope.reservedOutputTokens)
    assertEquals(512, envelope.safetyMarginTokens)
    assertEquals(0.92, envelope.effectiveInputPercent, 0.0)
    assertEquals("balanced", envelope.selectedPreset)
    assertEquals("dev", envelope.effectivePreset)
    assertEquals("explicit", envelope.presetSource)
    assertEquals(true, envelope.presetDiverged)
  }
}
