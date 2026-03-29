package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelCapabilityRegistryTest {
  @Test
  fun resolveVisionInputSupportMatchesExactStaticRule() {
    val resolution = LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.visionInputSupported == true)
    assertEquals(LlmModelCapabilitySource.STATIC_EXACT, resolution?.source)
    assertEquals("openai_vision_exact", resolution?.matchedRuleId)
  }

  @Test
  fun resolveVisionInputSupportMatchesFamilyStaticRule() {
    val resolution = LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "qwen2.5-vl-72b-instruct",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.visionInputSupported == true)
    assertEquals(LlmModelCapabilitySource.STATIC_FAMILY, resolution?.source)
    assertEquals("qwen_vl_family", resolution?.matchedRuleId)
  }

  @Test
  fun resolveVisionInputSupportMatchesKimiK25OnAnthropicRoutes() {
    val resolution = LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = "custom",
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "kimi-k2.5",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.visionInputSupported == true)
    assertEquals(LlmModelCapabilitySource.STATIC_EXACT, resolution?.source)
    assertEquals("kimi_k2_5_vision_exact", resolution?.matchedRuleId)
  }

  @Test
  fun resolveVisionInputSupportPrefersProviderDeclaredOverride() {
    val resolution = LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "text-embedding-3-large",
      metadata = mapOf("providerDeclaredVisionInputSupported" to "true"),
    )

    assertNotNull(resolution)
    assertTrue(resolution?.visionInputSupported == true)
    assertEquals(LlmModelCapabilitySource.PROVIDER_DECLARED, resolution?.source)
    assertEquals(null, resolution?.matchedRuleId)
  }

  @Test
  fun resolveVisionInputSupportFallsBackToRegexForUnknownVisionModel() {
    val resolution = LlmModelCapabilityRegistry.resolveVisionInputSupport(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "acme-vision-preview",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.visionInputSupported == true)
    assertEquals(LlmModelCapabilitySource.REGEX_FALLBACK, resolution?.source)
    assertEquals(null, resolution?.matchedRuleId)
  }

  @Test
  fun resolvePdfInputSupportMatchesExactStaticRule() {
    val resolution = LlmModelCapabilityRegistry.resolvePdfInputSupport(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.pdfInputSupported == true)
    assertEquals(LlmModelCapabilitySource.STATIC_EXACT, resolution?.source)
    assertEquals("openai_pdf_exact", resolution?.matchedRuleId)
  }

  @Test
  fun resolvePdfInputSupportMatchesAnthropicFamilyRule() {
    val resolution = LlmModelCapabilityRegistry.resolvePdfInputSupport(
      providerId = "anthropic",
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "claude-3-7-sonnet-20250219",
    )

    assertNotNull(resolution)
    assertTrue(resolution?.pdfInputSupported == true)
    assertEquals(LlmModelCapabilitySource.STATIC_FAMILY, resolution?.source)
    assertEquals("anthropic_pdf_family", resolution?.matchedRuleId)
  }

  @Test
  fun resolvePdfInputSupportPrefersProviderDeclaredOverride() {
    val resolution = LlmModelCapabilityRegistry.resolvePdfInputSupport(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      metadata = mapOf("providerDeclaredPdfInputSupported" to "false"),
    )

    assertNotNull(resolution)
    assertTrue(resolution?.pdfInputSupported == false)
    assertEquals(LlmModelCapabilitySource.PROVIDER_DECLARED, resolution?.source)
    assertEquals(null, resolution?.matchedRuleId)
  }
}
