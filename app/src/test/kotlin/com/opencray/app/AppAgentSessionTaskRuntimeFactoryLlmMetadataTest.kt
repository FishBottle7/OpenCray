package com.opencray.app

import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.runtime.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryLlmMetadataTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun buildRuntimeLlmMetadataIncludesContextBudgetOverridesWhenLlmIsRequired() {
    val factory = createFactory()

    val metadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = mapOf("source" to "chat"),
      sessionId = "session-budget",
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = true,
      llmSettings = LlmSettingsState(
        providerId = "openai",
        baseUrl = "https://api.openai.com/v1",
        contextBudgetPreset = "expanded",
        contextBudgetReservedOutputTokens = 3072,
        contextBudgetSafetyMarginTokens = 1536,
        contextBudgetEffectiveInputPercent = 0.92,
      ),
      routeMetadata = mapOf("context_window_tokens" to "200000"),
    )

    assertEquals("chat", metadata["source"])
    assertEquals("session-budget", metadata["sessionId"])
    assertEquals("openai", metadata["_host.providerId"])
    assertEquals("https://api.openai.com/v1", metadata["_host.baseUrl"])
    assertEquals("expanded", metadata["context_budget_preset"])
    assertEquals("3072", metadata["reserved_output_tokens"])
    assertEquals("1536", metadata["prompt_safety_margin_tokens"])
    assertEquals("0.92", metadata["effective_input_percent"])
  }

  @Test
  fun buildRuntimeLlmMetadataStaysMinimalWhenLlmIsNotRequired() {
    val factory = createFactory()

    val metadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = false,
      taskMetadata = mapOf("source" to "chat"),
      sessionId = "session-no-llm",
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = false,
      llmSettings = LlmSettingsState(
        contextBudgetPreset = "expanded",
        contextBudgetReservedOutputTokens = 3072,
      ),
      routeMetadata = mapOf("context_window_tokens" to "200000"),
    )

    assertEquals(mapOf("sessionId" to "session-no-llm"), metadata)
    assertNull(metadata["context_budget_preset"])
    assertFalse(metadata.containsKey("reserved_output_tokens"))
  }

  @Test
  fun builtinToolsForWarmupDefaultsOfficialResponsesRouteToNativeWebSearch() {
    val factory = createFactory()

    val builtinTools = builtinToolsForWarmupForTest(
      factory = factory,
      visibleToolDefinitions = listOf(
        AgentToolDefinition(
          name = "WebSearch",
          description = "Search the web.",
        ),
      ),
      llmMetadata = mapOf(
        "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
        "_host.baseUrl" to "https://api.openai.com/v1",
      ),
    )

    assertEquals(LiteLlmBuiltinToolType.WEB_SEARCH, builtinTools.single().type)
  }

  @Test
  fun builtinToolsForWarmupKeepsHostWebSearchForCustomResponsesRouteByDefault() {
    val factory = createFactory()

    val builtinTools = builtinToolsForWarmupForTest(
      factory = factory,
      visibleToolDefinitions = listOf(
        AgentToolDefinition(
          name = "WebSearch",
          description = "Search the web.",
        ),
      ),
      llmMetadata = mapOf(
        "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
        "_host.baseUrl" to "https://third-party.example/v1",
      ),
    )

    assertTrue(builtinTools.isEmpty())
  }

  @Test
  fun builtinToolsForWarmupKeepsHostWebSearchForResponsesRouteWithoutOfficialHostMetadata() {
    val factory = createFactory()
    val visibleTools = listOf(
      AgentToolDefinition(
        name = "WebSearch",
        description = "Search the web.",
      ),
    )

    val missingHostBuiltinTools = builtinToolsForWarmupForTest(
      factory = factory,
      visibleToolDefinitions = visibleTools,
      llmMetadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
    )
    val blankHostBuiltinTools = builtinToolsForWarmupForTest(
      factory = factory,
      visibleToolDefinitions = visibleTools,
      llmMetadata = mapOf(
        "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
        "_host.baseUrl" to " ",
      ),
    )

    assertTrue(missingHostBuiltinTools.isEmpty())
    assertTrue(blankHostBuiltinTools.isEmpty())
  }

  @Test
  fun builtinToolsForWarmupKeepsExplicitNativeWebSearchDisable() {
    val factory = createFactory()

    val builtinTools = builtinToolsForWarmupForTest(
      factory = factory,
      visibleToolDefinitions = listOf(
        AgentToolDefinition(
          name = "WebSearch",
          description = "Search the web.",
        ),
      ),
      llmMetadata = mapOf(
        "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
        "nativeWebSearchEnabled" to "false",
      ),
    )

    assertTrue(builtinTools.isEmpty())
  }

  private fun createFactory(): AppAgentSessionTaskRuntimeFactory {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-llm-metadata"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-llm-metadata").toPath()
    return AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
  }

  private fun builtinToolsForWarmupForTest(
    factory: AppAgentSessionTaskRuntimeFactory,
    visibleToolDefinitions: List<AgentToolDefinition>,
    llmMetadata: Map<String, String>,
  ): List<LiteLlmBuiltinToolDefinition> {
    val method = AppAgentSessionTaskRuntimeFactory::class.java.getDeclaredMethod(
      "builtinToolsForWarmup",
      List::class.java,
      Map::class.java,
    )
    method.isAccessible = true
    return method.invoke(
      factory,
      visibleToolDefinitions,
      llmMetadata,
    ) as List<LiteLlmBuiltinToolDefinition>
  }
}
