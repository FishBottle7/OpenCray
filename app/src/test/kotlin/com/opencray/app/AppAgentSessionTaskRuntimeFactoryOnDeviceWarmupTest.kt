package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryOnDeviceWarmupTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun buildOnDeviceWarmupSpecIncludesSystemAndDurableContextWithoutDynamicOrReplayContext() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-warmup-spec"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-on-device-warmup-spec").toPath()
    chatStore.appendMessage(sessionId, ChatTranscriptRole.USER, "Need transcript replay context")
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { readyOnDeviceSettings() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      onDeviceModelReadyProvider = { modelId ->
        modelId == OnDeviceLlmCatalog.GEMMA_4_E2B_IT
      },
    )
    factory.workingStateStoreForSession(sessionId).replace(
      WorkingState(
        objective = WorkingStateObjective(primaryGoal = "Dynamic objective"),
        nextActions = listOf(
          WorkingStateEntry(text = "Inspect the dynamic warmup layer"),
        ),
      ),
    )

    val spec = factory.buildOnDeviceWarmupSpec(sessionId)

    assertNotNull(spec)
    val warmupSpec = checkNotNull(spec)
    assertTrue(warmupSpec.systemPrompt.orEmpty().contains("Custom warmup system prompt"))
    assertEquals(1, warmupSpec.messages.size)
    assertTrue(warmupSpec.tools.isNotEmpty())
    val durablePrompt = warmupSpec.messages.single().content.orEmpty()
    assertTrue(durablePrompt.contains("[Tool Protocol]"))
    assertFalse(durablePrompt.contains("[Working State]"))
    assertFalse(durablePrompt.contains("Dynamic objective"))
    assertFalse(durablePrompt.contains("[Conversation]"))
    assertFalse(durablePrompt.contains("Need transcript replay context"))
  }

  private fun readyOnDeviceSettings(): LlmSettingsState = LlmSettingsState(
    enabled = true,
    providerMode = LlmProviderModes.ON_DEVICE_MODEL,
    providerId = "on-device",
    selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
    systemPrompt = "Custom warmup system prompt",
    onDeviceAccelerator = OnDeviceLlmAccelerators.GPU,
    onDeviceMaxContextWindow = 32_768,
    onDeviceMaxTokens = 4_096,
    onDeviceTopK = 40,
    onDeviceTopP = 0.95,
    onDeviceTemperature = 0.7,
    onDeviceThinkingEnabled = true,
  )
}
