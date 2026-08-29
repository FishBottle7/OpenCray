package com.opencray.app

import android.os.Binder
import com.opencray.runtime.OpenCrayFinalAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceWriteCommandProtocolTest {
  @Test
  fun commandCodecRoundTripsEveryTypedRuntimeWriteCommand() {
    chatCommands().forEach { command ->
      val decoded = decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      )

      assertEquals(command, (decoded as DecodedRuntimeServiceWriteCommand.Chat).command)
    }
    skillsCommands().forEach { command ->
      val decoded = decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      )

      assertEquals(command, (decoded as DecodedRuntimeServiceWriteCommand.Skills).command)
    }
    settingsCommands().forEach { command ->
      val decoded = decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      )

      assertEquals(command, (decoded as DecodedRuntimeServiceWriteCommand.Settings).command)
    }
  }

  @Test
  fun resultCodecPreservesEveryRuntimeWriteResultShape() {
    val chatResults = listOf(
      OpenCrayChatWriteDispatchResult.Completed,
      OpenCrayChatWriteDispatchResult.Payload(null),
      OpenCrayChatWriteDispatchResult.Payload(
        mapOf("runId" to "run-1", "queued" to true, "attempt" to 2),
      ),
    )
    val skillsResults = listOf(
      OpenCraySkillsWriteDispatchResult.Completed,
      OpenCraySkillsWriteDispatchResult.Message("installed"),
      OpenCraySkillsWriteDispatchResult.Payload(mapOf("skillId" to "skill-1")),
    )
    val settingsResults = listOf(
      OpenCraySettingsWriteDispatchResult.Payload(
        mapOf("saved" to true, "nested" to mapOf("count" to 3)),
      ),
    )

    chatResults.forEach { result ->
      assertEquals(result, decodeRuntimeServiceChatWriteResult(encodeRuntimeServiceWriteResult(result)))
    }
    skillsResults.forEach { result ->
      assertEquals(result, decodeRuntimeServiceSkillsWriteResult(encodeRuntimeServiceWriteResult(result)))
    }
    settingsResults.forEach { result ->
      assertEquals(result, decodeRuntimeServiceSettingsWriteResult(encodeRuntimeServiceWriteResult(result)))
    }
  }

  @Test
  fun commandCodecRejectsUnknownSchemaDomainAndRoute() {
    val valid = runtimeServiceWriteCommandEnvelope(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertNull(
      decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(valid.copy(schemaVersion = 2)),
      ),
    )
    assertNull(
      decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(valid.copy(domain = "shell")),
      ),
    )
    assertNull(
      decodeRuntimeServiceWriteCommand(
        encodeRuntimeServiceWriteCommand(valid.copy(route = "v1/unknown")),
      ),
    )
  }

  @Test
  fun encodedDispatcherRoutesCommandsThroughExistingBinderEndpoint() {
    val chatCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val skillsCommands = mutableListOf<OpenCraySkillsWriteCommand>()
    val settingsCommands = mutableListOf<OpenCraySettingsWriteCommand>()
    val endpoint = object : Binder(), RuntimeServiceBinderEndpoint {
      override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = error("unused")

      override fun dispatchChatWriteCommand(
        command: OpenCrayChatWriteCommand,
      ): OpenCrayChatWriteDispatchResult {
        chatCommands += command
        return OpenCrayChatWriteDispatchResult.Payload(mapOf("owner" to "chat"))
      }

      override fun dispatchSkillsWriteCommand(
        command: OpenCraySkillsWriteCommand,
      ): OpenCraySkillsWriteDispatchResult {
        skillsCommands += command
        return OpenCraySkillsWriteDispatchResult.Message("skills-ok")
      }

      override fun dispatchSettingsWriteCommand(
        command: OpenCraySettingsWriteCommand,
      ): OpenCraySettingsWriteDispatchResult {
        settingsCommands += command
        return OpenCraySettingsWriteDispatchResult.Payload(mapOf("owner" to "settings"))
      }
    }
    val chatCommand = OpenCrayChatWriteCommand.SubmitChatMessage("hello", emptyList())
    val skillsCommand = OpenCraySkillsWriteCommand.RefreshSkills
    val settingsCommand = OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair")

    val chatResult = dispatchAndDecode(endpoint, chatCommand)
    val skillsResult = dispatchAndDecode(endpoint, skillsCommand)
    val settingsResult = dispatchAndDecode(endpoint, settingsCommand)

    assertEquals(listOf(chatCommand), chatCommands)
    assertEquals(listOf(skillsCommand), skillsCommands)
    assertEquals(listOf(settingsCommand), settingsCommands)
    assertEquals(
      OpenCrayChatWriteDispatchResult.Payload(mapOf("owner" to "chat")),
      chatResult,
    )
    assertEquals(OpenCraySkillsWriteDispatchResult.Message("skills-ok"), skillsResult)
    assertEquals(
      OpenCraySettingsWriteDispatchResult.Payload(mapOf("owner" to "settings")),
      settingsResult,
    )
  }

  @Test
  fun loopbackResultDecoderRetainsExistingTransportShapes() {
    assertEquals(
      OpenCrayChatWriteDispatchResult.Completed,
      decodeLoopbackRuntimeServiceWriteResult(
        OpenCrayChatWriteCommand.CreateChatSession,
        null,
      ),
    )
    assertEquals(
      OpenCrayChatWriteDispatchResult.Payload(null),
      decodeLoopbackRuntimeServiceWriteResult(
        OpenCrayChatWriteCommand.SubmitChatMessage("hello", emptyList()),
        null,
      ),
    )
    assertEquals(
      OpenCraySkillsWriteDispatchResult.Message("refreshed"),
      decodeLoopbackRuntimeServiceWriteResult(
        OpenCraySkillsWriteCommand.RefreshSkills,
        "refreshed",
      ),
    )
    assertTrue(
      decodeLoopbackRuntimeServiceWriteResult(
        OpenCraySettingsWriteCommand.SetAppLanguage("zh"),
        mapOf("saved" to true),
      ) is OpenCraySettingsWriteDispatchResult.Payload,
    )
  }

  private fun dispatchAndDecode(
    endpoint: RuntimeServiceBinderEndpoint,
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? = decodeRuntimeServiceChatWriteResult(
    dispatchRuntimeServiceWriteCommandJson(
      endpoint,
      encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
    ),
  )

  private fun dispatchAndDecode(
    endpoint: RuntimeServiceBinderEndpoint,
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? = decodeRuntimeServiceSkillsWriteResult(
    dispatchRuntimeServiceWriteCommandJson(
      endpoint,
      encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
    ),
  )

  private fun dispatchAndDecode(
    endpoint: RuntimeServiceBinderEndpoint,
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? = decodeRuntimeServiceSettingsWriteResult(
    dispatchRuntimeServiceWriteCommandJson(
      endpoint,
      encodeRuntimeServiceWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
    ),
  )

  private fun chatCommands(): List<OpenCrayChatWriteCommand> = listOf(
    OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    OpenCrayChatWriteCommand.ApplyMemoryDebugAction("memory-1", "forget"),
    OpenCrayChatWriteCommand.CreateChatSession,
    OpenCrayChatWriteCommand.CopyChatSession("chat-1"),
    OpenCrayChatWriteCommand.DeleteChatSession("chat-2"),
    OpenCrayChatWriteCommand.SelectChatSession("chat-3"),
    OpenCrayChatWriteCommand.BranchChatSessionFromMessage("chat-4", "message-1"),
    OpenCrayChatWriteCommand.DeleteChatMessage("chat-5", "message-2"),
    OpenCrayChatWriteCommand.RecallChatMessage("chat-6", "message-3"),
    OpenCrayChatWriteCommand.SubmitChatMessage(
      text = "hello",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "images/sample.png",
          artifactId = "artifact-1",
          displayName = "sample.png",
          mimeType = "image/png",
          durationMs = 42L,
          waveformBars = listOf(1, 3, 2),
          transcriptText = "sample",
        ),
      ),
    ),
    OpenCrayChatWriteCommand.ApproveChatApproval("run-1"),
    OpenCrayChatWriteCommand.ApproveChatApprovalForSession("run-2"),
    OpenCrayChatWriteCommand.ApproveChatApprovalAsBatch("run-6"),
    OpenCrayChatWriteCommand.RejectChatApproval("run-3"),
    OpenCrayChatWriteCommand.InterruptChatRun("run-4"),
    OpenCrayChatWriteCommand.RetryChatRun("run-5"),
  )

  private fun skillsCommands(): List<OpenCraySkillsWriteCommand> = listOf(
    OpenCraySkillsWriteCommand.SetSkillEnabled("skill-1", true),
    OpenCraySkillsWriteCommand.InstallSuggestedSkill("skill-2"),
    OpenCraySkillsWriteCommand.InstallSkillSource("owner/repo", "skill-a"),
    OpenCraySkillsWriteCommand.InstallSkillSourceBatch("owner/repo", listOf("skill-a", "skill-b")),
    OpenCraySkillsWriteCommand.InspectSkillSource("owner/repo"),
    OpenCraySkillsWriteCommand.DeleteInstalledSkill("skill-3"),
    OpenCraySkillsWriteCommand.RefreshSkills,
    OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates("skill-4"),
    OpenCraySkillsWriteCommand.UpdateInstalledSkill("skill-5"),
    OpenCraySkillsWriteCommand.ActivateSkillsInstallSource("source-1"),
  )

  private fun settingsCommands(): List<OpenCraySettingsWriteCommand> = listOf(
    OpenCraySettingsWriteCommand.SaveNotificationSettings(sampleMap()),
    OpenCraySettingsWriteCommand.UpdateScheduledTaskEnabled("schedule-1", false),
    OpenCraySettingsWriteCommand.RunScheduledTaskNow("schedule-2"),
    OpenCraySettingsWriteCommand.SnoozeScheduledTask("schedule-3", 45),
    OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair"),
    OpenCraySettingsWriteCommand.SaveNetworkSearchConfig(listOf(sampleMap())),
    OpenCraySettingsWriteCommand.SaveMediaSpeechConfig(sampleMap()),
    OpenCraySettingsWriteCommand.SaveSandboxSettings(sampleMap()),
    OpenCraySettingsWriteCommand.SaveLlmConfig(
      enabled = true,
      streamingEnabled = false,
      providerMode = "cloud",
      providerId = "provider-1",
      selectedProviderOptionId = "option-1",
      protocol = "responses",
      providerName = "Provider",
      providerNotes = "Notes",
      baseUrl = "https://example.invalid",
      apiKey = "secret",
      model = "model-1",
      reasoningEffort = "medium",
      systemPrompt = "system",
      openAiPromptCacheKeyStrategy = "session",
      openAiPromptCacheRetention = "24h",
      anthropicPromptCachingEnabled = true,
      anthropicPromptCacheTtl = "5m",
      contextBudgetPreset = "balanced",
      contextBudgetReservedOutputTokens = 2_048,
      contextBudgetSafetyMarginTokens = 512,
      contextBudgetEffectiveInputPercent = 0.8,
      selectedOnDeviceModelId = "local-1",
      onDeviceMaxContextWindow = 8_192,
      onDeviceMaxTokens = 1_024,
      onDeviceTopK = 40,
      onDeviceTopP = 0.9,
      onDeviceTemperature = 0.7,
      onDeviceAccelerator = "gpu",
      onDeviceThinkingEnabled = true,
      onDeviceLiteModeEnabled = false,
    ),
    OpenCraySettingsWriteCommand.SaveCustomLlmProvider(
      selectedProviderOptionId = "custom-1",
      streamingEnabled = true,
      protocol = "chat_completions",
      providerName = "Custom",
      providerNotes = "Custom notes",
      baseUrl = "https://custom.invalid",
      apiKey = "custom-secret",
      model = "custom-model",
      reasoningEffort = "high",
      systemPrompt = "custom system",
      openAiPromptCacheKeyStrategy = "thread",
      openAiPromptCacheRetention = "1h",
      anthropicPromptCachingEnabled = false,
      anthropicPromptCacheTtl = "10m",
      contextBudgetPreset = "large",
      contextBudgetReservedOutputTokens = 4_096,
      contextBudgetSafetyMarginTokens = 1_024,
      contextBudgetEffectiveInputPercent = 0.75,
    ),
    OpenCraySettingsWriteCommand.ValidateLlmConfig(
      providerId = "provider-2",
      protocol = "responses",
      baseUrl = "https://validate.invalid",
      apiKey = "validate-secret",
      model = "validate-model",
      reasoningEffort = "low",
    ),
    OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel("model-2"),
    OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload("model-3"),
    OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel("model-4"),
    OpenCraySettingsWriteCommand.SavePersonalizationConfig("preset-1", "Label", "Guidance"),
    OpenCraySettingsWriteCommand.SetAppLanguage("zh-CN"),
    OpenCraySettingsWriteCommand.RunPersonalizationReset("all"),
    OpenCraySettingsWriteCommand.SetMcpMasterEnabled(true),
    OpenCraySettingsWriteCommand.SetMcpServerEnabled("server-1", false),
    OpenCraySettingsWriteCommand.SaveSafetySettings(
      automationModeId = "balanced",
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = 50,
      maxToolCalls = 100,
      undoWindowHours = 24,
      fileChangesPolicyId = "ask",
      fileDeletesPolicyId = "deny",
      shellCommandsPolicyId = "ask",
      externalAccessModeId = "restricted",
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = "workspace",
      readOnlyOutsideWorkspace = true,
      liveContextModeId = "full",
      memoryToolsEnabled = true,
      subAgentContextDefaultModeId = "focused",
      subAgentContextProfileOverrides = mapOf("research" to "full"),
    ),
  )

  private fun sampleMap(): Map<String, Any?> = mapOf(
    "enabled" to true,
    "count" to 2,
    "ratio" to 0.5,
    "label" to "sample",
    "nullable" to null,
    "nested" to mapOf("items" to listOf("a", "b")),
  )
}
