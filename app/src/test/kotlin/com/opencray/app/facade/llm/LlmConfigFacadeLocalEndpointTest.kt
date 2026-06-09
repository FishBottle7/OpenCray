package com.opencray.app.facade.llm

import com.opencray.app.InMemoryLlmSettingsKeyValueStore
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsStore
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigFacadeLocalEndpointTest {
  @Test
  fun saveCustomProviderAllowsBlankApiKeyForLocalOpenAiCompatibleEndpoint() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.create(
      llmSettingsStore = store,
      providerClient = UnusedProviderClient,
    )

    val snapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        providerName = "LM Studio",
        providerNotes = "Local desktop endpoint",
        baseUrl = "http://10.0.2.2:1234/v1",
        apiKey = "",
        model = "qwen2.5-7b-instruct",
        reasoningEffort = "medium",
        systemPrompt = "",
      ),
    )

    assertTrue(snapshot.enabled)
    assertTrue(store.load().enabled)
    assertEquals("", snapshot.apiKey)
    assertEquals(snapshot.selectedProviderOptionId, store.loadSelectedProviderOptionId("custom"))
  }

  @Test
  fun saveCustomProviderAllowsBlankApiKeyForIpv6LoopbackOpenAiCompatibleEndpoint() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.create(
      llmSettingsStore = store,
      providerClient = UnusedProviderClient,
    )

    val snapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        providerName = "Local IPv6",
        providerNotes = "Loopback endpoint",
        baseUrl = "http://[::1]:1234/v1",
        apiKey = "",
        model = "qwen2.5-7b-instruct",
        reasoningEffort = "medium",
        systemPrompt = "",
      ),
    )

    assertTrue(snapshot.enabled)
    assertTrue(store.load().enabled)
    assertEquals("", snapshot.apiKey)
    assertEquals(snapshot.selectedProviderOptionId, store.loadSelectedProviderOptionId("custom"))
  }

  private object UnusedProviderClient : LiteLlmProviderClient {
    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult =
      error("Provider validation was not expected for this saveCustomProvider test.")
  }
}
