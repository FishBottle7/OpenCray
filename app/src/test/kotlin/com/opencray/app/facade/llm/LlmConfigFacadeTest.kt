package com.opencray.app.facade.llm

import com.opencray.app.InMemoryLlmSettingsKeyValueStore
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsStore
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigFacadeTest {
  @Test
  fun saveCustomProviderPersistsReusableProviderOption() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = "OK"),
      ),
    )

    val snapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.ANTHROPIC,
        providerName = "Acme",
        providerNotes = "Regional fallback",
        baseUrl = "https://api.acme.example/v1",
        apiKey = "secret",
        model = "claude-3-7-sonnet",
        reasoningEffort = "high",
        systemPrompt = "Be concise.",
      ),
    )

    val savedOption = snapshot.providerOptions.first { option ->
      option.id == snapshot.selectedProviderOptionId
    }
    assertEquals("custom", snapshot.providerId)
    assertEquals("Acme", savedOption.title)
    assertEquals("Regional fallback", savedOption.subtitle)
    assertEquals("anthropic", savedOption.protocol)
    assertEquals("secret", savedOption.apiKey)
    assertEquals(snapshot.selectedProviderOptionId, store.loadSelectedProviderOptionId("custom"))
  }

  @Test
  fun saveCustomProviderOverwritesSelectedSavedOption() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = "OK"),
      ),
    )

    val firstSnapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = "custom",
        protocol = LlmProviderProtocols.ANTHROPIC,
        providerName = "Acme",
        providerNotes = "Regional fallback",
        baseUrl = "https://api.acme.example/v1",
        apiKey = "secret",
        model = "claude-3-7-sonnet",
        reasoningEffort = "high",
        systemPrompt = "Be concise.",
      ),
    )

    val updatedSnapshot = facade.saveCustomProvider(
      SaveCustomLlmProviderRequest(
        selectedProviderOptionId = firstSnapshot.selectedProviderOptionId,
        protocol = LlmProviderProtocols.ANTHROPIC,
        providerName = "Acme Edge",
        providerNotes = "Regional edge",
        baseUrl = "https://api.acme.example/v2",
        apiKey = "secret-2",
        model = "claude-3-7-sonnet",
        reasoningEffort = "high",
        systemPrompt = "Be concise.",
      ),
    )

    val savedCustomOptions = updatedSnapshot.providerOptions.filter { option ->
      option.providerId == "custom" && option.id != "custom"
    }
    assertEquals(firstSnapshot.selectedProviderOptionId, updatedSnapshot.selectedProviderOptionId)
    assertEquals(1, savedCustomOptions.size)
    assertEquals("Acme Edge", savedCustomOptions.single().title)
    assertEquals("Regional edge", savedCustomOptions.single().subtitle)
    assertEquals("https://api.acme.example/v2", savedCustomOptions.single().defaultBaseUrl)
    assertEquals("secret-2", savedCustomOptions.single().apiKey)
  }

  @Test
  fun validateUsesResolvedPresetDefaultsForLiveRequest() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = "OK"),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = providerClient,
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "openai",
        protocol = "openai",
        baseUrl = "",
        apiKey = "test-key",
        model = "",
        reasoningEffort = "high",
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals("Connection verified for gpt-4o-mini.", result.message)
    assertEquals("https://api.openai.com/v1", providerClient.lastRequest?.route?.baseUrl)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertEquals("high", providerClient.lastRequest?.route?.metadata?.get("reasoning_effort"))
    assertEquals("Bearer test-key", providerClient.lastRequest?.request?.authHeaders?.get("Authorization"))
    assertEquals("Reply with OK.", providerClient.lastRequest?.request?.prompt)
  }

  @Test
  fun validateReturnsProviderFailureMessage() {
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Failure(
          errorCode = "HTTP_401",
          errorMessage = "Invalid API key.",
        ),
      ),
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "custom",
        protocol = "openai",
        baseUrl = "https://example.com/v1",
        apiKey = "",
        model = "demo-model",
        reasoningEffort = "medium",
      ),
    )

    assertFalse(result.isSuccess)
    assertEquals("Invalid API key.", result.message)
  }

  @Test
  fun validateAnthropicProtocolUsesAnthropicHeadersAndThinkingBudget() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(outputText = "OK"),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = providerClient,
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "custom",
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        apiKey = "anthropic-secret",
        model = "claude-3-7-sonnet",
        reasoningEffort = "xhigh",
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals("anthropic", providerClient.lastRequest?.route?.metadata?.get("protocol"))
    assertEquals("16000", providerClient.lastRequest?.route?.metadata?.get("thinking_budget_tokens"))
    assertEquals("anthropic-secret", providerClient.lastRequest?.request?.authHeaders?.get("x-api-key"))
    assertEquals("2023-06-01", providerClient.lastRequest?.request?.authHeaders?.get("anthropic-version"))
  }

  private class RecordingProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var lastRequest: LiteLlmProviderRequest? = null

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      lastRequest = request
      return result
    }
  }
}
