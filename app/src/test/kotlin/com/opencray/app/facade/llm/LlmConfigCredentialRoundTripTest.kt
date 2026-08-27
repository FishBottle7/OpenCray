package com.opencray.app.facade.llm

import com.opencray.app.InMemoryLlmSettingsKeyValueStore
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsStore
import com.opencray.app.toGatewayMap
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigCredentialRoundTripTest {
  private companion object {
    const val PERSISTED_API_KEY = "sk-test-1234567890"
    const val MASKED_API_KEY = "••••7890"
    const val BASE_URL = "https://proxy.example/v1"
    const val MODEL = "gpt-4.1"
  }

  private fun store(): LlmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())

  private fun facade(store: LlmSettingsStore): LlmConfigFacade =
    LocalLlmConfigFacade.create(
      llmSettingsStore = store,
      providerClient = RepeatingSuccessProviderClient(),
    )

  private fun saveRequest(apiKey: String?): SaveLlmConfigRequest = SaveLlmConfigRequest(
    enabled = true,
    providerId = "custom",
    selectedProviderOptionId = "custom",
    protocol = LlmProviderProtocols.OPENAI,
    providerName = "Proxy",
    providerNotes = "",
    baseUrl = BASE_URL,
    apiKey = requireNotNull(apiKey),
    model = MODEL,
    reasoningEffort = "medium",
    systemPrompt = "",
  )

  @Test
  fun loadGatewayMapDoesNotExposePlaintextApiKey() {
    val store = store()
    val facade = facade(store)
    facade.save(saveRequest(PERSISTED_API_KEY))

    val payload = facade.load().toGatewayMap()

    assertEquals(MASKED_API_KEY, payload["apiKey"])
    assertEquals(true, payload["hasCredential"])
    assertEquals("7890", payload["credentialHint"])
    assertFalse(payload.toString().contains(PERSISTED_API_KEY))
  }

  @Test
  fun emptyApiKeyReportsHasCredentialFalseWithBlankHint() {
    val store = store()
    val facade = facade(store)

    val payload = facade.load().toGatewayMap()

    assertEquals("", payload["apiKey"])
    assertEquals(false, payload["hasCredential"])
    assertEquals("", payload["credentialHint"])
  }

  @Test
  fun providerOptionGatewayMapDoesNotExposePlaintextApiKey() {
    val store = store()
    val facade = facade(store)
    val snapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        providerName = "Proxy",
        providerNotes = "",
        baseUrl = BASE_URL,
        apiKey = PERSISTED_API_KEY,
        model = MODEL,
        reasoningEffort = "medium",
        systemPrompt = "",
      ),
    )
    val savedOption = snapshot.providerOptions.first { option ->
      option.isCustom && option.id != "custom"
    }

    val payload = snapshot.toGatewayMap()
    val optionPayload = (payload["providerOptions"] as List<*>)
      .map { it as Map<*, *> }
      .first { option -> option["id"] == savedOption.id }

    assertEquals(MASKED_API_KEY, optionPayload["apiKey"])
    assertEquals(true, optionPayload["hasCredential"])
    assertEquals("7890", optionPayload["credentialHint"])
    assertFalse(payload.toString().contains(PERSISTED_API_KEY))

    val reloadedPayload = facade.load().toGatewayMap()
    assertFalse(reloadedPayload.toString().contains(PERSISTED_API_KEY))
  }

  @Test
  fun saveWithEchoedMaskedApiKeyPreservesStoredCredential() {
    val store = store()
    val facade = facade(store)
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(saveRequest(MASKED_API_KEY))

    assertEquals(PERSISTED_API_KEY, saved.apiKey)
    assertEquals(PERSISTED_API_KEY, store.load().apiKey)
  }

  @Test
  fun saveWithExplicitEmptyApiKeyClearsStoredCredential() {
    val store = store()
    val facade = facade(store)
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(saveRequest(""))

    assertEquals("", saved.apiKey)
  }

  @Test
  fun saveCustomProviderWithEchoedMaskedApiKeyPreservesStoredCredential() {
    val store = store()
    val facade = facade(store)
    val initial = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        providerName = "Proxy",
        providerNotes = "",
        baseUrl = BASE_URL,
        apiKey = PERSISTED_API_KEY,
        model = MODEL,
        reasoningEffort = "medium",
        systemPrompt = "",
      ),
    )

    val saved = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = initial.selectedProviderOptionId,
        protocol = LlmProviderProtocols.OPENAI,
        providerName = "Proxy Renamed",
        providerNotes = "",
        baseUrl = BASE_URL,
        apiKey = MASKED_API_KEY,
        model = MODEL,
        reasoningEffort = "medium",
        systemPrompt = "",
      ),
    )

    assertEquals("Proxy Renamed", saved.providerName)
    assertEquals(
      PERSISTED_API_KEY,
      store.loadSavedCustomProviders().single().apiKey,
    )
  }

  @Test
  fun validateWithEchoedMaskedApiKeyUsesPersistedCredential() {
    val store = store()
    val providerClient = RepeatingSuccessProviderClient()
    val facade = LocalLlmConfigFacade.create(
      llmSettingsStore = store,
      providerClient = providerClient,
    )
    facade.save(saveRequest(PERSISTED_API_KEY))

    facade.validate(
      ValidateLlmConfigRequest(
        providerId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = BASE_URL,
        apiKey = MASKED_API_KEY,
        model = MODEL,
        reasoningEffort = "medium",
      ),
    )

    assertTrue(providerClient.requests.isNotEmpty())
    assertTrue(
      providerClient.requests.first().request.authHeaders.values.any { header ->
        header.contains(PERSISTED_API_KEY)
      },
    )
  }

  private class RepeatingSuccessProviderClient : LiteLlmProviderClient {
    val requests: MutableList<LiteLlmProviderRequest> = mutableListOf()

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      requests += request
      return LiteLlmProviderResult.Success(outputText = "OK")
    }
  }
}
