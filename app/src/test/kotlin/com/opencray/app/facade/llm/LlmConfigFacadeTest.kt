package com.opencray.app.facade.llm

import com.opencray.app.InMemoryLlmSettingsKeyValueStore
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolChoiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LlmConfigFacadeTest {
  @Test
  fun saveCustomProviderPersistsReusableProviderOption() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
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
        LiteLlmProviderResult.Success(outputText = "OK"),
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
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
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
    assertTrue(result.agentCapability?.nativeToolCallingAvailable == true)
    assertTrue(result.agentCapability?.toolChoiceSupported == true)
    assertTrue(result.agentCapability?.strictToolSchemaSupported == true)
    assertEquals(4, providerClient.requests.size)
    assertEquals("https://api.openai.com/v1", providerClient.requests[0].route.baseUrl)
    assertEquals("gpt-4o-mini", providerClient.requests[0].route.model)
    assertEquals("high", providerClient.requests[0].route.metadata["reasoning_effort"])
    assertEquals("Bearer test-key", providerClient.requests[0].request.authHeaders["Authorization"])
    assertEquals("Reply with OK.", providerClient.requests[0].request.prompt)
    assertEquals("capability_probe", providerClient.requests[1].request.tools.single().name)
    assertNull(providerClient.requests[1].request.toolChoice)
    assertEquals(LiteLlmToolChoiceMode.TOOL, providerClient.requests[2].request.toolChoice?.mode)
    assertEquals("capability_probe", providerClient.requests[2].request.toolChoice?.toolName)
    assertEquals(false, providerClient.requests[2].request.parallelToolCalls)
    assertEquals(true, providerClient.requests[3].request.tools.single().strict)
    assertTrue(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
        ),
      ).agentCapability.strictToolSchemaSupported,
    )
  }

  @Test
  fun validateReturnsProviderFailureMessage() {
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Failure(
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
    assertNull(result.agentCapability)
  }

  @Test
  fun validateFailsWhenNativeToolCallingCannotBeVerified() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      LiteLlmProviderResult.Success(
        outputText = "I cannot call tools here.",
        completion = LiteLlmStructuredCompletion(
          finalText = "I cannot call tools here.",
        ),
      ),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = providerClient,
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://example.com/v1",
        apiKey = "test-key",
        model = "demo-model",
        reasoningEffort = "medium",
      ),
    )

    assertFalse(result.isSuccess)
    assertEquals(
      "Text connection works, but native tool calling could not be verified. This route will use JSON fallback until native tools are verified.",
      result.message,
    )
    assertEquals(2, providerClient.requests.size)
    assertTrue(result.agentCapability?.wasVerified == true)
    assertFalse(result.agentCapability?.nativeToolCallingAvailable == true)
    assertFalse(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://example.com/v1",
          model = "demo-model",
        ),
      ).agentCapability.nativeToolCallingAvailable,
    )
  }

  @Test
  fun validateAnthropicProtocolUsesAnthropicHeadersAndThinkingBudget() {
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
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
    assertTrue(result.agentCapability?.nativeToolCallingAvailable == true)
    assertEquals("anthropic", providerClient.requests[0].route.metadata["protocol"])
    assertEquals("16000", providerClient.requests[0].route.metadata["thinking_budget_tokens"])
    assertEquals("anthropic-secret", providerClient.requests[0].request.authHeaders["x-api-key"])
    assertEquals("2023-06-01", providerClient.requests[0].request.authHeaders["anthropic-version"])
  }

  private class RecordingProviderClient(
    vararg queuedResults: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    private val results: ArrayDeque<LiteLlmProviderResult> = ArrayDeque(queuedResults.toList())
    val requests: MutableList<LiteLlmProviderRequest> = mutableListOf()
    val lastRequest: LiteLlmProviderRequest?
      get() = requests.lastOrNull()

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      requests += request
      if (results.isEmpty()) {
        error("No queued provider result remained for request ${request.request.requestId}.")
      }
      return results.removeFirst()
    }
  }

  private fun capabilityProbeResult(expectedEcho: String): LiteLlmProviderResult.Success =
    LiteLlmProviderResult.Success(
      outputText = "",
      completion = LiteLlmStructuredCompletion(
        toolCalls = listOf(
          LiteLlmStructuredToolCall(
            toolName = "capability_probe",
            arguments = buildJsonObject {
              put("echo", expectedEcho)
            },
          ),
        ),
      ),
    )
}
