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

    assertEquals(saved, store.load())
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
}
