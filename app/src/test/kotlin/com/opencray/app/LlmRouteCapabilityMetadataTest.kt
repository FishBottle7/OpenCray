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
    assertEquals("128000", metadata["context_window_tokens"])
    assertEquals("true", metadata["visionInputSupported"])
    assertEquals(null, metadata["pdfInputSupported"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }

  @Test
  fun effectiveLlmRouteMetadataUsesModelVendorForThirdPartyRoutes() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "openai/gpt-4.1-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://third-party.example/v1",
        model = "openai/gpt-4.1-mini",
      ),
    )

    assertEquals("1047576", metadata["context_window_tokens"])
    assertEquals("true", metadata["visionInputSupported"])
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
        contextWindowTokens = 262_144,
        visionInputSupported = false,
        pdfInputSupported = true,
        nativeToolCallingAvailable = true,
      ),
    )

    assertEquals("false", metadata["visionInputSupported"])
    assertEquals("true", metadata["pdfInputSupported"])
    assertEquals("262144", metadata["context_window_tokens"])
    assertEquals("true", metadata["nativeToolCallingAvailable"])
  }

  @Test
  fun effectiveLlmRouteMetadataDoesNotDisableResponsesNativeSearchFromUnobservedProbe() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI_RESPONSES,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-5-mini",
        ),
        verifiedAtEpochMs = 123L,
        nativeToolCallingAvailable = true,
        builtinWebSearchSupported = false,
      ),
    )

    assertEquals("openai_responses", metadata["protocol"])
    assertEquals(null, metadata["nativeWebSearchEnabled"])
  }

  @Test
  fun effectiveLlmRouteMetadataEnablesRemoteCompactionOnlyForOpenAiResponsesByDefault() {
    val responsesMetadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-5-mini",
      ),
    )
    val chatMetadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
      ),
    )
    val anthropicMetadata = effectiveLlmRouteMetadata(
      providerId = "anthropic",
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "claude-sonnet-4-5",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
      ),
    )

    assertEquals("true", responsesMetadata["responsesRemoteCompactionSupported"])
    assertEquals(null, chatMetadata["responsesRemoteCompactionSupported"])
    assertEquals(null, anthropicMetadata["responsesRemoteCompactionSupported"])
  }

  @Test
  fun effectiveLlmRouteMetadataKeepsOpenAiResponsesRemoteCompactionWhenVerifiedSnapshotDidNotProbeIt() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI_RESPONSES,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-5-mini",
        ),
        verifiedAtEpochMs = 123L,
        nativeToolCallingAvailable = true,
        responsesRemoteCompactionSupported = false,
      ),
    )

    assertEquals("true", metadata["responsesRemoteCompactionSupported"])
  }

  @Test
  fun effectiveLlmRouteMetadataEnablesNativeSearchWhenVerifiedSupported() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI_RESPONSES,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-5-mini",
        ),
        verifiedAtEpochMs = 123L,
        nativeToolCallingAvailable = true,
        builtinWebSearchSupported = true,
      ),
    )

    assertEquals("true", metadata["nativeWebSearchEnabled"])
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

  @Test
  fun effectiveLlmRouteMetadataFallsBackToDefaultContextWindowForUnknownModels() {
    val metadata = effectiveLlmRouteMetadata(
      providerId = "custom",
      protocol = LlmProviderProtocols.OPENAI,
      model = "demo-model",
      reasoningEffort = "medium",
      agentCapability = LlmAgentCapabilitySnapshot.unknown(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://example.com/v1",
        model = "demo-model",
      ),
    )

    assertEquals("128000", metadata["context_window_tokens"])
  }

  @Test
  fun effectiveLlmRouteMetadataFromSettingsIncludesPromptCacheConfiguration() {
    val metadata = effectiveLlmRouteMetadata(
      settings = LlmSettingsState(
        providerId = "openai",
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
        openAiPromptCacheKeyStrategy = LlmPromptCacheKeyStrategies.ROUTE,
        openAiPromptCacheRetention = LlmPromptCacheRetentionPolicies.IN_MEMORY,
      ),
    )

    assertEquals(
      LlmPromptCacheKeyStrategies.ROUTE,
      metadata[LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY],
    )
    assertEquals(
      LlmPromptCacheRetentionPolicies.IN_MEMORY,
      metadata[LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION],
    )
    assertEquals("true", metadata["stream"])
  }

  @Test
  fun effectiveLlmRouteMetadataFromSettingsIncludesStreamingDisabledFlag() {
    val metadata = effectiveLlmRouteMetadata(
      settings = LlmSettingsState(
        providerId = "openai",
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-4o-mini",
        streamingEnabled = false,
      ),
    )

    assertEquals("false", metadata["stream"])
  }
}
