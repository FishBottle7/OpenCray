package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsStoreTest {
  @Test
  fun loadInfersProviderFromLegacyBaseUrlWhenProviderIdMissing() {
    val store = LlmSettingsStore(
      InMemoryLlmSettingsKeyValueStore(
        linkedMapOf(
          LlmSettingsStoreKeys.BASE_URL to "https://api.deepseek.com/v1",
          LlmSettingsStoreKeys.MODEL to "deepseek-chat",
        ),
      ),
    )

    val state = store.load()

    assertEquals("deepseek", state.providerId)
  }

  @Test
  fun configuredStateRequiresApiKey() {
    val state = LlmSettingsState(
      enabled = false,
      providerId = "custom",
      baseUrl = "http://10.0.2.2:11434/v1",
      apiKey = "",
      model = "qwen2.5",
    )

    assertFalse(state.isConfigured())
  }

  @Test
  fun configuredStateDoesNotRequireModel() {
    val state = LlmSettingsState(
      enabled = false,
      providerId = "custom",
      baseUrl = "http://10.0.2.2:11434/v1",
      apiKey = "token",
      model = "",
    )

    assertTrue(state.isConfigured())
  }

  @Test
  fun configuredStateUsesSelectedOnDeviceModelInOnDeviceMode() {
    val state = LlmSettingsState(
      enabled = true,
      providerMode = LlmProviderModes.ON_DEVICE_MODEL,
      selectedOnDeviceModelId = "gemma-4-e2b-it",
    )

    assertTrue(state.isConfigured())
  }

  @Test
  fun loadDerivesEnabledFromResolvedConfigFields() {
    val store = LlmSettingsStore(
      InMemoryLlmSettingsKeyValueStore(
        linkedMapOf(
          LlmSettingsStoreKeys.ENABLED to "false",
          LlmSettingsStoreKeys.BASE_URL to "https://proxy.example/v1",
          LlmSettingsStoreKeys.API_KEY to "token",
        ),
      ),
    )

    val state = store.load()

    assertTrue(state.enabled)
  }

  @Test
  fun saveAndLoadPersistsProviderId() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val saved = LlmSettingsState(
      enabled = true,
      providerId = "custom",
      baseUrl = "https://proxy.example/v1",
      apiKey = "token",
      model = "model-x",
      systemPrompt = "Be concise.",
    )

    store.save(saved)

    assertEquals(saved.sanitized(), store.load())
  }

  @Test
  fun saveAndLoadPersistsOnDeviceSettings() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val saved = LlmSettingsState(
      enabled = true,
      providerMode = LlmProviderModes.ON_DEVICE_MODEL,
      providerId = "openai",
      baseUrl = "https://api.openai.com/v1",
      apiKey = "token",
      model = "gpt-4o-mini",
      selectedOnDeviceModelId = "gemma-4-e4b-it",
      onDeviceMaxContextWindow = 16384,
      onDeviceMaxTokens = 2048,
      onDeviceTopK = 24,
      onDeviceTopP = 0.9,
      onDeviceTemperature = 0.4,
      onDeviceAccelerator = OnDeviceLlmAccelerators.CPU,
      onDeviceThinkingEnabled = true,
      onDeviceLiteModeEnabled = true,
    )

    store.save(saved)

    val loaded = store.load()
    assertEquals(LlmProviderModes.ON_DEVICE_MODEL, loaded.providerMode)
    assertEquals("gemma-4-e4b-it", loaded.selectedOnDeviceModelId)
    assertEquals(16384, loaded.onDeviceMaxContextWindow)
    assertEquals(2048, loaded.onDeviceMaxTokens)
    assertEquals(24, loaded.onDeviceTopK)
    assertEquals(0.9, loaded.onDeviceTopP, 0.0)
    assertEquals(0.4, loaded.onDeviceTemperature, 0.0)
    assertEquals(OnDeviceLlmAccelerators.CPU, loaded.onDeviceAccelerator)
    assertTrue(loaded.onDeviceThinkingEnabled)
    assertTrue(loaded.onDeviceLiteModeEnabled)
  }

  @Test
  fun saveAndLoadPersistSavedCustomProviders() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())

    store.saveSavedCustomProviders(
      listOf(
        SavedCustomLlmProvider.create(
          existingId = "saved-custom-1",
          protocol = LlmProviderProtocols.ANTHROPIC,
          providerName = "Acme",
          providerNotes = "Regional fallback",
          baseUrl = "https://api.acme.example/v1",
          apiKey = "secret",
          model = "claude-3-7-sonnet",
        ),
      ),
    )

    val providers = store.loadSavedCustomProviders()

    assertEquals(1, providers.size)
    assertEquals("saved-custom-1", providers.single().id)
    assertEquals("Acme", providers.single().providerName)
    assertEquals("anthropic", providers.single().protocol)
  }

  @Test
  fun sanitizedStatePreservesOpenAiResponsesProtocol() {
    val state = LlmSettingsState(
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      baseUrl = "https://api.openai.com/v1",
      apiKey = "token",
      model = "gpt-5-mini",
    )

    val sanitized = state.sanitized()

    assertEquals(LlmProviderProtocols.OPENAI_RESPONSES, sanitized.protocol)
    assertEquals(
      llmRouteFingerprint(
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-5-mini",
      ),
      sanitized.agentCapability.routeFingerprint,
    )
  }

  @Test
  fun loadReturnsCachedAgentCapabilityForMatchingRoute() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val capability = LlmAgentCapabilitySnapshot(
      routeFingerprint = llmRouteFingerprint(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://proxy.example/v1",
        model = "model-x",
      ),
      verifiedAtEpochMs = 1234L,
      nativeToolCallingAvailable = false,
    )

    store.saveAgentCapability(capability)

    val state = store.load(
      defaults = LlmSettingsState(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://proxy.example/v1",
        apiKey = "token",
        model = "model-x",
      ),
    )

    assertEquals(capability, state.agentCapability)
    assertTrue(state.agentCapability.wasVerified)
  }

  @Test
  fun loadDoesNotLeakCachedAgentCapabilityAcrossRoutes() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    store.saveAgentCapability(
      LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://proxy.example/v1",
          model = "model-x",
        ),
        verifiedAtEpochMs = 1234L,
        nativeToolCallingAvailable = false,
      ),
    )

    val state = store.load(
      defaults = LlmSettingsState(
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-7-sonnet",
      ),
    )

    assertFalse(state.agentCapability.wasVerified)
    assertEquals(
      llmRouteFingerprint(
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-7-sonnet",
      ),
      state.agentCapability.routeFingerprint,
    )
  }

  @Test
  fun saveAndLoadPersistsVisionInputSupportInCapabilityCache() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val saved = LlmSettingsState(
      enabled = true,
      protocol = LlmProviderProtocols.OPENAI,
      baseUrl = "https://api.openai.com/v1",
      apiKey = "token",
      model = "gpt-4o-mini",
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = llmRouteFingerprint(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
        ),
        verifiedAtEpochMs = 1234L,
        contextWindowTokens = 200_000,
        visionInputSupported = true,
        pdfInputSupported = true,
        nativeToolCallingAvailable = true,
      ),
    )

    store.save(saved)

    val loaded = store.load(
      defaults = LlmSettingsState(
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-4o-mini",
      ),
    )

    assertEquals(200_000, loaded.agentCapability.contextWindowTokens)
    assertTrue(loaded.agentCapability.visionInputSupported)
    assertTrue(loaded.agentCapability.pdfInputSupported)
    assertTrue(loaded.agentCapability.nativeToolCallingAvailable)
  }

  @Test
  fun saveAndLoadPersistsPromptCachingSettings() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val saved = LlmSettingsState(
      enabled = true,
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      baseUrl = "https://api.openai.com/v1",
      apiKey = "token",
      model = "gpt-5-mini",
      openAiPromptCacheKeyStrategy = LlmPromptCacheKeyStrategies.SESSION,
      openAiPromptCacheRetention = LlmPromptCacheRetentionPolicies.HOURS_24,
      anthropicPromptCachingEnabled = true,
      anthropicPromptCacheTtl = AnthropicPromptCacheTtlPolicies.HOUR_1,
    )

    store.save(saved)

    val loaded = store.load(
      defaults = LlmSettingsState(
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
      ),
    )

    assertEquals(LlmPromptCacheKeyStrategies.SESSION, loaded.openAiPromptCacheKeyStrategy)
    assertEquals(LlmPromptCacheRetentionPolicies.HOURS_24, loaded.openAiPromptCacheRetention)
    assertTrue(loaded.anthropicPromptCachingEnabled)
    assertEquals(AnthropicPromptCacheTtlPolicies.HOUR_1, loaded.anthropicPromptCacheTtl)
  }
}
