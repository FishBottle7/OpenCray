package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryLlmMetadataTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun buildRuntimeLlmMetadataIncludesContextBudgetOverridesWhenLlmIsRequired() {
    val factory = createFactory()

    val metadata = buildRuntimeLlmMetadataForTest(
      factory = factory,
      requiresLlmConfig = true,
      taskMetadata = mapOf("source" to "chat"),
      sessionId = "session-budget",
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = true,
      llmSettings = LlmSettingsState(
        providerId = "openai",
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
    assertEquals("expanded", metadata["context_budget_preset"])
    assertEquals("3072", metadata["reserved_output_tokens"])
    assertEquals("1536", metadata["prompt_safety_margin_tokens"])
    assertEquals("0.92", metadata["effective_input_percent"])
  }

  @Test
  fun buildRuntimeLlmMetadataStaysMinimalWhenLlmIsNotRequired() {
    val factory = createFactory()

    val metadata = buildRuntimeLlmMetadataForTest(
      factory = factory,
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

  @Suppress("UNCHECKED_CAST")
  private fun buildRuntimeLlmMetadataForTest(
    factory: AppAgentSessionTaskRuntimeFactory,
    requiresLlmConfig: Boolean,
    taskMetadata: Map<String, String>,
    sessionId: String,
    nativeWebSearchRunApproved: Boolean,
    nativeWebSearchSessionApproved: Boolean,
    llmSettings: LlmSettingsState,
    routeMetadata: Map<String, String>,
  ): Map<String, String> {
    val method = AppAgentSessionTaskRuntimeFactory::class.java.getDeclaredMethod(
      "buildRuntimeLlmMetadata",
      Boolean::class.javaPrimitiveType,
      Map::class.java,
      String::class.java,
      Boolean::class.javaPrimitiveType,
      Boolean::class.javaPrimitiveType,
      LlmSettingsState::class.java,
      Map::class.java,
    )
    method.isAccessible = true
    return method.invoke(
      factory,
      requiresLlmConfig,
      taskMetadata,
      sessionId,
      nativeWebSearchRunApproved,
      nativeWebSearchSessionApproved,
      llmSettings,
      routeMetadata,
    ) as Map<String, String>
  }
}
