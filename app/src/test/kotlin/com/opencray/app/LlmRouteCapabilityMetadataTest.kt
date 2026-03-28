package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmRouteCapabilityMetadataTest {
  @Test
  fun effectiveLlmRouteMetadataAddsStaticVisionFlagForKnownVisionModel() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "qwen2.5-vl-72b-instruct",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://example.com/v1",
        model = "qwen2.5-vl-72b-instruct",
      ),
    )

    assertEquals("openai", metadata["protocol"])
    assertEquals("true", metadata["visionInputSupported"])
    assertEquals(null, metadata["pdfInputSupported"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }

  @Test
  fun effectiveLlmRouteMetadataPrefersVerifiedCapabilitySnapshot() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
        ),
        verifiedAtEpochMs = 123L,
        visionInputSupported = false,
        pdfInputSupported = true,
        nativeToolCallingAvailable = true,
      ),
    )

    assertEquals("false", metadata["visionInputSupported"])
    assertEquals("true", metadata["pdfInputSupported"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }

  @Test
  fun effectiveLlmCapabilityMetadataUsesProviderDeclaredOverrideFromRouteMetadata() {
    val metadata = effectiveLlmCapabilityMetadata(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "text-embedding-3-large",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://example.com/v1",
        model = "text-embedding-3-large",
      ),
      baseRouteMetadata = mapOf("providerDeclaredVisionInputSupported" to "true"),
    )

    assertEquals("true", metadata["visionInputSupported"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }

  @Test
  fun effectiveLlmCapabilityMetadataUsesProviderDeclaredPdfOverrideFromRouteMetadata() {
    val metadata = effectiveLlmCapabilityMetadata(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "demo-model",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://example.com/v1",
        model = "demo-model",
      ),
      baseRouteMetadata = mapOf("providerDeclaredPdfInputSupported" to "true"),
    )

    assertEquals("true", metadata["pdfInputSupported"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }
}
