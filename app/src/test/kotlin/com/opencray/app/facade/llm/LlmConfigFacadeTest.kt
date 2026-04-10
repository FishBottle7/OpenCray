package com.opencray.app.facade.llm

import com.opencray.app.InMemoryLlmSettingsKeyValueStore
import com.opencray.app.InMemoryLiteRtOnDeviceModelInstallStore
import com.opencray.app.LiteRtOnDeviceModelInstallRecord
import com.opencray.app.LlmProviderModes
import com.opencray.app.LlmProviderProtocols
import com.opencray.app.LlmSettingsState
import com.opencray.app.LlmSettingsStore
import com.opencray.app.OnDeviceLlmCatalog
import com.opencray.app.OnDeviceLlmAccelerators
import com.opencray.app.OnDeviceLlmDownloadStates
import com.opencray.app.runtimeMetadataOverrides
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
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
  private companion object {
    const val OPENAI_PROMPT_CACHE_KEY_STRATEGY_SESSION: String = "session"
    const val OPENAI_PROMPT_CACHE_KEY_STRATEGY_ROUTE: String = "route"
    const val OPENAI_PROMPT_CACHE_RETENTION_24H: String = "24h"
    const val OPENAI_PROMPT_CACHE_RETENTION_IN_MEMORY: String = "in_memory"
    const val ANTHROPIC_PROMPT_CACHE_TTL_1H: String = "1h"
  }

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
      parallelCapabilityProbeResult(),
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
    assertTrue(result.agentCapability?.visionInputSupported == true)
    assertTrue(result.agentCapability?.pdfInputSupported == true)
    assertTrue(result.agentCapability?.toolChoiceSupported == true)
    assertTrue(result.agentCapability?.strictToolSchemaSupported == true)
    assertTrue(result.agentCapability?.parallelToolCallsSupported == true)
    assertEquals("true", result.agentCapability?.runtimeMetadataOverrides()?.get("parallelToolCalls"))
    assertEquals(6, providerClient.requests.size)
    assertEquals("https://api.openai.com/v1", providerClient.requests[0].route.baseUrl)
    assertEquals("gpt-4o-mini", providerClient.requests[0].route.model)
    assertEquals("high", providerClient.requests[0].route.metadata["reasoning_effort"])
    assertEquals("true", providerClient.requests[0].route.metadata["pdfInputSupported"])
    assertEquals("Bearer test-key", providerClient.requests[0].request.authHeaders["Authorization"])
    assertEquals("Reply with OK.", providerClient.requests[0].request.prompt)
    assertEquals("capability_probe", providerClient.requests[1].request.tools.single().name)
    assertNull(providerClient.requests[1].request.toolChoice)
    assertEquals(LiteLlmToolChoiceMode.TOOL, providerClient.requests[2].request.toolChoice?.mode)
    assertEquals("capability_probe", providerClient.requests[2].request.toolChoice?.toolName)
    assertEquals(false, providerClient.requests[2].request.parallelToolCalls)
    assertEquals(true, providerClient.requests[3].request.tools.single().strict)
    assertEquals(true, providerClient.requests[4].request.parallelToolCalls)
    assertEquals(2, providerClient.requests[4].request.tools.size)
    assertEquals(
      LiteLlmBuiltinToolType.WEB_SEARCH,
      providerClient.requests[5].request.builtinTools.single().type,
    )
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
  fun validateMarksEmbeddingModelsAsTextOnlyForVisionInput() {
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
        model = "text-embedding-3-large",
        reasoningEffort = "medium",
      ),
    )

    assertFalse(result.isSuccess)
    assertFalse(result.agentCapability?.visionInputSupported == true)
    assertFalse(result.agentCapability?.pdfInputSupported == true)
    assertFalse(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://example.com/v1",
          apiKey = "test-key",
          model = "text-embedding-3-large",
        ),
      ).agentCapability.visionInputSupported,
    )
    assertFalse(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://example.com/v1",
          apiKey = "test-key",
          model = "text-embedding-3-large",
        ),
      ).agentCapability.pdfInputSupported,
    )
  }

  @Test
  fun validateMarksQwenVlModelsAsVisionCapableForCustomOpenAiRoutes() {
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
        model = "qwen2.5-vl-72b-instruct",
        reasoningEffort = "medium",
      ),
    )

    assertFalse(result.isSuccess)
    assertTrue(result.agentCapability?.visionInputSupported == true)
    assertEquals("true", providerClient.requests[0].route.metadata["visionInputSupported"])
    assertTrue(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://example.com/v1",
          apiKey = "test-key",
          model = "qwen2.5-vl-72b-instruct",
        ),
      ).agentCapability.visionInputSupported,
    )
  }

  @Test
  fun validateMarksKimiK25AsVisionCapableForCustomAnthropicRoutes() {
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
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://third-party.example/anthropic",
        apiKey = "test-key",
        model = "kimi-k2.5",
        reasoningEffort = "medium",
      ),
    )

    assertFalse(result.isSuccess)
    assertTrue(result.agentCapability?.visionInputSupported == true)
    assertEquals("true", providerClient.requests[0].route.metadata["visionInputSupported"])
    assertEquals(60_000L, providerClient.requests[0].route.timeoutMs)
    assertFalse(result.agentCapability?.pdfInputSupported == true)
    assertTrue(
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.ANTHROPIC,
          baseUrl = "https://third-party.example/anthropic",
          apiKey = "test-key",
          model = "kimi-k2.5",
        ),
      ).agentCapability.visionInputSupported,
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
      parallelCapabilityProbeResult(),
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

  @Test
  fun validateAnthropicProtocolCanDisableThinking() {
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
      parallelCapabilityProbeResult(),
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
        reasoningEffort = "off",
      ),
    )

    assertTrue(result.isSuccess)
    assertTrue(result.agentCapability?.nativeToolCallingAvailable == true)
    assertEquals("anthropic", providerClient.requests[0].route.metadata["protocol"])
    assertNull(providerClient.requests[0].route.metadata["thinking_budget_tokens"])
    assertEquals("anthropic-secret", providerClient.requests[0].request.authHeaders["x-api-key"])
    assertEquals("2023-06-01", providerClient.requests[0].request.authHeaders["anthropic-version"])
  }

  @Test
  fun validateOpenAiProtocolCapturesBuiltinWebSearchCapability() {
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
      parallelCapabilityProbeResult(),
      LiteLlmProviderResult.Success(
        outputText = "https://example.com",
        metadata = mapOf(
          LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED to "true",
          LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT to "openai_chat_web_search",
        ),
      ),
    )
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = providerClient,
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "custom",
        protocol = LlmProviderProtocols.OPENAI,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "test-key",
        model = "glm-4.6",
        reasoningEffort = "medium",
      ),
    )

    assertTrue(result.isSuccess)
    assertTrue(result.agentCapability?.nativeToolCallingAvailable == true)
    assertTrue(result.agentCapability?.builtinWebSearchSupported == true)
    assertEquals(
      LiteLlmBuiltinToolType.WEB_SEARCH,
      providerClient.requests[5].request.builtinTools.single().type,
    )
    assertEquals(
      true,
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://open.bigmodel.cn/api/paas/v4",
          model = "glm-4.6",
        ),
      ).agentCapability.builtinWebSearchSupported,
    )
  }

  @Test
  fun validateAnthropicProtocolCapturesBuiltinWebSearchCapability() {
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
      parallelCapabilityProbeResult(),
      LiteLlmProviderResult.Success(
        outputText = "https://example.com",
        metadata = mapOf(
          LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED to "true",
          LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT to "1",
        ),
      ),
    )
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
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
    assertTrue(result.agentCapability?.builtinWebSearchSupported == true)
    assertTrue(result.agentCapability?.citationIncludeSupported == true)
    assertEquals(
      LiteLlmBuiltinToolType.WEB_SEARCH,
      providerClient.requests[5].request.builtinTools.single().type,
    )
    assertEquals(
      true,
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.ANTHROPIC,
          baseUrl = "https://api.anthropic.com",
          model = "claude-3-7-sonnet",
        ),
      ).agentCapability.builtinWebSearchSupported,
    )
  }

  @Test
  fun validateOpenAiResponsesCapturesResponsesSpecificCapabilities() {
    val providerClient = RecordingProviderClient(
      LiteLlmProviderResult.Success(outputText = "OK"),
      capabilityProbeResult(expectedEcho = "native_tool_probe"),
      capabilityProbeResult(expectedEcho = "tool_choice_probe"),
      capabilityProbeResult(expectedEcho = "strict_schema_probe"),
      parallelCapabilityProbeResult(),
      LiteLlmProviderResult.Success(
        outputText = "READY",
        providerResponseId = "resp_seed",
      ),
      LiteLlmProviderResult.Success(
        outputText = "responses_continuation_probe_token",
      ),
      LiteLlmProviderResult.Success(
        outputText = "https://example.com",
        metadata = mapOf(
          LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED to "true",
          LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT to "1",
        ),
      ),
      LiteLlmProviderResult.Success(
        outputText = "OK",
        metadata = mapOf(
          LiteLlmMetadataKeys.RESPONSES_COMMENTARY_PHASE_OBSERVED to "true",
          LiteLlmMetadataKeys.RESPONSES_FINAL_PHASE_OBSERVED to "true",
        ),
      ),
    )
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = providerClient,
    )

    val result = facade.validate(
      ValidateLlmConfigRequest(
        providerId = "openai",
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "test-key",
        model = "gpt-5",
        reasoningEffort = "medium",
      ),
    )

    assertTrue(result.isSuccess)
    assertTrue(result.agentCapability?.responsesContinuationSupported == true)
    assertTrue(result.agentCapability?.builtinWebSearchSupported == true)
    assertTrue(result.agentCapability?.assistantPhaseSupported == true)
    assertTrue(result.agentCapability?.citationIncludeSupported == true)
    assertTrue(result.agentCapability?.parallelToolCallsSupported == true)
    assertEquals(true, providerClient.requests[4].request.parallelToolCalls)
    assertEquals(2, providerClient.requests[4].request.tools.size)
    assertEquals("resp_seed", providerClient.requests[6].request.previousResponseId)
    assertEquals(
      LiteLlmBuiltinToolType.WEB_SEARCH,
      providerClient.requests[7].request.builtinTools.single().type,
    )
    assertEquals(
      "true",
      providerClient.requests[6].request.metadata[LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CONTINUATION],
    )
    assertEquals(
      "true",
      providerClient.requests[7].request.metadata[LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_CITATION_INCLUDE],
    )
    assertEquals(
      "true",
      providerClient.requests[8].request.metadata[LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_ASSISTANT_PHASES],
    )
    assertEquals(
      true,
      store.load(
        defaults = LlmSettingsState(
          protocol = LlmProviderProtocols.OPENAI_RESPONSES,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-5",
        ),
      ).agentCapability.responsesContinuationSupported,
    )
  }

  @Test
  fun savePersistsPromptCachingSettings() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
      ),
    )

    val snapshot = facade.save(
      SaveLlmConfigRequest(
        enabled = true,
        providerId = "openai",
        selectedProviderOptionId = "openai",
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        providerName = "OpenAI",
        providerNotes = "",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
        reasoningEffort = "medium",
        systemPrompt = "Be concise.",
        openAiPromptCacheKeyStrategy = OPENAI_PROMPT_CACHE_KEY_STRATEGY_SESSION,
        openAiPromptCacheRetention = OPENAI_PROMPT_CACHE_RETENTION_24H,
        anthropicPromptCachingEnabled = true,
        anthropicPromptCacheTtl = ANTHROPIC_PROMPT_CACHE_TTL_1H,
      ),
    )

    assertEquals(OPENAI_PROMPT_CACHE_KEY_STRATEGY_SESSION, snapshot.openAiPromptCacheKeyStrategy)
    assertEquals(OPENAI_PROMPT_CACHE_RETENTION_24H, snapshot.openAiPromptCacheRetention)
    assertTrue(snapshot.anthropicPromptCachingEnabled == true)
    assertEquals(ANTHROPIC_PROMPT_CACHE_TTL_1H, snapshot.anthropicPromptCacheTtl)

    val stored = store.load(
      defaults = LlmSettingsState(
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
      ),
    )
    assertEquals(OPENAI_PROMPT_CACHE_KEY_STRATEGY_SESSION, stored.openAiPromptCacheKeyStrategy)
    assertEquals(OPENAI_PROMPT_CACHE_RETENTION_24H, stored.openAiPromptCacheRetention)
    assertTrue(stored.anthropicPromptCachingEnabled)
    assertEquals(ANTHROPIC_PROMPT_CACHE_TTL_1H, stored.anthropicPromptCacheTtl)
  }

  @Test
  fun savePreservesPromptCachingSettingsWhenRequestOmitsThem() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    store.save(
      LlmSettingsState(
        enabled = true,
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
        openAiPromptCacheKeyStrategy = OPENAI_PROMPT_CACHE_KEY_STRATEGY_ROUTE,
        openAiPromptCacheRetention = OPENAI_PROMPT_CACHE_RETENTION_IN_MEMORY,
        anthropicPromptCachingEnabled = true,
        anthropicPromptCacheTtl = ANTHROPIC_PROMPT_CACHE_TTL_1H,
      ),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
      ),
    )

    val snapshot = facade.save(
      SaveLlmConfigRequest(
        enabled = true,
        providerId = "openai",
        selectedProviderOptionId = "openai",
        protocol = LlmProviderProtocols.OPENAI_RESPONSES,
        providerName = "OpenAI",
        providerNotes = "",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "token",
        model = "gpt-5-mini",
        reasoningEffort = "medium",
        systemPrompt = "Stay concise.",
      ),
    )

    assertEquals(OPENAI_PROMPT_CACHE_KEY_STRATEGY_ROUTE, snapshot.openAiPromptCacheKeyStrategy)
    assertEquals(OPENAI_PROMPT_CACHE_RETENTION_IN_MEMORY, snapshot.openAiPromptCacheRetention)
    assertTrue(snapshot.anthropicPromptCachingEnabled == true)
    assertEquals(ANTHROPIC_PROMPT_CACHE_TTL_1H, snapshot.anthropicPromptCacheTtl)
  }

  @Test
  fun loadIncludesOnDeviceCatalogDefaults() {
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
      ),
    )

    val snapshot = facade.load()

    assertEquals(LlmProviderModes.CLOUD, snapshot.providerMode)
    assertEquals("gemma-4-e2b-it", snapshot.selectedOnDeviceModelId)
    assertEquals(32768, snapshot.onDeviceMaxContextWindow)
    assertEquals(4096, snapshot.onDeviceMaxTokens)
    assertEquals(40, snapshot.onDeviceTopK)
    assertEquals(0.95, snapshot.onDeviceTopP, 0.0)
    assertEquals(0.70, snapshot.onDeviceTemperature, 0.0)
    assertEquals(OnDeviceLlmAccelerators.GPU, snapshot.onDeviceAccelerator)
    assertFalse(snapshot.onDeviceThinkingEnabled)
    assertFalse(snapshot.onDeviceLiteModeEnabled)
    assertEquals(2, snapshot.onDeviceModels.size)
    assertEquals("gemma-4-e2b-it", snapshot.onDeviceModels[0].id)
    assertEquals(OnDeviceLlmDownloadStates.NOT_DOWNLOADED, snapshot.onDeviceModels[0].installState)
    assertEquals("gemma-4-e4b-it", snapshot.onDeviceModels[1].id)
    assertEquals(OnDeviceLlmDownloadStates.NOT_DOWNLOADED, snapshot.onDeviceModels[1].installState)
  }

  @Test
  fun loadIncludesPersistedOnDeviceInstallState() {
    val gemmaE2b = checkNotNull(OnDeviceLlmCatalog.entry(OnDeviceLlmCatalog.GEMMA_4_E2B_IT))
    val installedModelFile = java.nio.file.Files.createTempFile("gemma-e2b-", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val installStore = InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        LiteRtOnDeviceModelInstallRecord(
          modelId = gemmaE2b.id,
          versionTag = gemmaE2b.versionTag,
          sourceUrl = gemmaE2b.sourceUrl,
          localFilePath = installedModelFile.absolutePath,
          fileSizeBytes = gemmaE2b.fileSizeBytes,
          sha256 = gemmaE2b.sha256,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = gemmaE2b.fileSizeBytes,
          installedAtEpochMs = 123L,
          sha256Verified = true,
        ),
      ),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
      ),
      onDeviceModelInstallStore = installStore,
    )

    val snapshot = facade.load()
    val readyModel = snapshot.onDeviceModels.first { option ->
      option.id == OnDeviceLlmCatalog.GEMMA_4_E2B_IT
    }

    assertEquals(OnDeviceLlmDownloadStates.READY, readyModel.installState)
    assertEquals(gemmaE2b.fileSizeBytes, readyModel.downloadedBytes)
    assertTrue(readyModel.sha256Verified)
  }

  @Test
  fun saveOnDeviceModeDoesNotRequireCloudUrlOrModel() {
    val store = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore())
    val gemmaE4b = checkNotNull(OnDeviceLlmCatalog.entry(OnDeviceLlmCatalog.GEMMA_4_E4B_IT))
    val installedModelFile = java.nio.file.Files.createTempFile("gemma-e4b-", ".litertlm")
      .toFile()
      .apply { deleteOnExit() }
    val installStore = InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        LiteRtOnDeviceModelInstallRecord(
          modelId = gemmaE4b.id,
          versionTag = gemmaE4b.versionTag,
          sourceUrl = gemmaE4b.sourceUrl,
          localFilePath = installedModelFile.absolutePath,
          fileSizeBytes = gemmaE4b.fileSizeBytes,
          sha256 = gemmaE4b.sha256,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = gemmaE4b.fileSizeBytes,
          installedAtEpochMs = 456L,
          sha256Verified = true,
        ),
      ),
    )
    val facade = LocalLlmConfigFacade.createForTest(
      llmSettingsStore = store,
      providerClient = RecordingProviderClient(
        LiteLlmProviderResult.Success(outputText = "OK"),
      ),
      onDeviceModelInstallStore = installStore,
    )

    val snapshot = facade.save(
      SaveLlmConfigRequest(
        enabled = true,
        providerMode = LlmProviderModes.ON_DEVICE_MODEL,
        providerId = "openai",
        selectedProviderOptionId = "openai",
        protocol = LlmProviderProtocols.OPENAI,
        providerName = "OpenAI",
        providerNotes = "",
        baseUrl = "",
        apiKey = "",
        model = "",
        reasoningEffort = "medium",
        systemPrompt = "Stay concise.",
        selectedOnDeviceModelId = "gemma-4-e4b-it",
        onDeviceMaxContextWindow = 16384,
        onDeviceMaxTokens = 2048,
        onDeviceTopK = 24,
        onDeviceTopP = 0.9,
        onDeviceTemperature = 0.4,
        onDeviceAccelerator = OnDeviceLlmAccelerators.CPU,
        onDeviceThinkingEnabled = true,
        onDeviceLiteModeEnabled = true,
      ),
    )

    assertTrue(snapshot.enabled)
    assertEquals(LlmProviderModes.ON_DEVICE_MODEL, snapshot.providerMode)
    assertEquals("", snapshot.baseUrl)
    assertEquals("", snapshot.model)
    assertEquals("gemma-4-e4b-it", snapshot.selectedOnDeviceModelId)
    assertEquals(16384, snapshot.onDeviceMaxContextWindow)
    assertEquals(2048, snapshot.onDeviceMaxTokens)
    assertEquals(24, snapshot.onDeviceTopK)
    assertEquals(0.9, snapshot.onDeviceTopP, 0.0)
    assertEquals(0.4, snapshot.onDeviceTemperature, 0.0)
    assertEquals(OnDeviceLlmAccelerators.CPU, snapshot.onDeviceAccelerator)
    assertTrue(snapshot.onDeviceThinkingEnabled)
    assertTrue(snapshot.onDeviceLiteModeEnabled)

    val stored = store.load()
    assertTrue(stored.enabled)
    assertEquals(LlmProviderModes.ON_DEVICE_MODEL, stored.providerMode)
    assertEquals("", stored.baseUrl)
    assertEquals("", stored.model)
    assertEquals("gemma-4-e4b-it", stored.selectedOnDeviceModelId)
    assertTrue(stored.onDeviceLiteModeEnabled)
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

  private fun parallelCapabilityProbeResult(): LiteLlmProviderResult.Success =
    LiteLlmProviderResult.Success(
      outputText = "",
      completion = LiteLlmStructuredCompletion(
        toolCalls = listOf(
          LiteLlmStructuredToolCall(
            toolName = "parallel_probe_one",
            arguments = buildJsonObject {
              put("echo", "parallel_tool_probe_one")
            },
          ),
          LiteLlmStructuredToolCall(
            toolName = "parallel_probe_two",
            arguments = buildJsonObject {
              put("echo", "parallel_tool_probe_two")
            },
          ),
        ),
      ),
    )
}
