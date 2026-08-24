package com.opencray.app

import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostServiceOwnedGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeDeltaWhenManagedProcessOutputChangesWithoutNewEvents() {
    val readGateway = RecordingChatRuntimeGateway("projection")
    readGateway.chatRuntimePayload = mapOf(
      "sessionId" to "session-1",
      "updatedAtEpochMs" to 1100L,
      "activeRuns" to listOf(
        mapOf(
          "sessionId" to "session-1",
          "runId" to "run-1",
          "taskId" to "task-1",
          "acceptedAtEpochMs" to 1000L,
          "updatedAtEpochMs" to 1100L,
          "attempt" to 1,
          "pendingMessageId" to "pending-1",
          "isTerminal" to false,
          "managedProcessIds" to listOf("proc-1"),
          "managedProcesses" to listOf(
            mapOf(
              "processId" to "proc-1",
              "status" to "running",
              "command" to "npm",
              "args" to listOf("run", "dev"),
              "workingDirectory" to ".",
              "processStarted" to true,
              "startedAtEpochMs" to 1050L,
              "updatedAtEpochMs" to 1100L,
              "stdoutPreview" to "starting dev server",
            ),
          ),
          "runningManagedProcessCount" to 1,
          "hasLiveManagedProcesses" to true,
        ),
      ),
      "retainedRuns" to emptyList<Map<String, Any?>>(),
      "subAgents" to emptyList<Map<String, Any?>>(),
      "events" to listOf(
        mapOf(
          "kind" to "lifecycle",
          "runId" to "run-1",
          "taskId" to "task-1",
          "emittedAtEpochMs" to 1000L,
          "phase" to "start",
        ),
      ),
    )
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val dispose = gateway.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }

    readGateway.chatRuntimePayload = mapOf(
      "sessionId" to "session-1",
      "updatedAtEpochMs" to 1400L,
      "activeRuns" to listOf(
        mapOf(
          "sessionId" to "session-1",
          "runId" to "run-1",
          "taskId" to "task-1",
          "acceptedAtEpochMs" to 1000L,
          "updatedAtEpochMs" to 1400L,
          "attempt" to 1,
          "pendingMessageId" to "pending-1",
          "isTerminal" to false,
          "managedProcessIds" to listOf("proc-1"),
          "managedProcesses" to listOf(
            mapOf(
              "processId" to "proc-1",
              "status" to "running",
              "command" to "npm",
              "args" to listOf("run", "dev"),
              "workingDirectory" to ".",
              "processStarted" to true,
              "startedAtEpochMs" to 1050L,
              "updatedAtEpochMs" to 1400L,
              "stdoutPreview" to "ready on http://localhost:3000",
            ),
          ),
          "runningManagedProcessCount" to 1,
          "hasLiveManagedProcesses" to true,
        ),
      ),
      "retainedRuns" to emptyList<Map<String, Any?>>(),
      "subAgents" to emptyList<Map<String, Any?>>(),
      "events" to listOf(
        mapOf(
          "kind" to "lifecycle",
          "runId" to "run-1",
          "taskId" to "task-1",
          "emittedAtEpochMs" to 1000L,
          "phase" to "start",
        ),
      ),
    )

    gateway.notifyChatSnapshotsChanged()

    assertEquals(1, observedDeltas.size)
    assertEquals("session-1", observedDeltas.single()["sessionId"])
    assertEquals(1L, observedDeltas.single()["sequence"])
    assertEquals(1, (observedDeltas.single()["totalLength"] as Int))
    assertTrue(((observedDeltas.single()["events"] as List<*>)).isEmpty())
    val activeRuns = observedDeltas.single()["activeRuns"] as List<Map<String, Any?>>
    val managedProcesses =
      activeRuns.single()["managedProcesses"] as List<Map<String, Any?>>
    assertEquals(
      "ready on http://localhost:3000",
      managedProcesses.single()["stdoutPreview"],
    )
    dispose()
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesNotificationAndStrongBackgroundWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val strongBackgroundAccess = RecordingStrongBackgroundSettingsAccess()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(
          RuntimeNotificationSettingsStore(
            com.opencray.persistence.store.file.DirectoryDurableTextStorage(
              temporaryFolder.newFolder("service-owned-notification-settings"),
            ),
          ),
        ),
      strongBackgroundSettingsAccess = strongBackgroundAccess,
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      runtimeServiceConnectionStateProvider = { RuntimeServiceConnectionState.binderConnected() },
    )

    val initial = gateway.loadNotificationSettings()
    val strongBackground = gateway.loadStrongBackgroundSnapshot()
    val saved = gateway.saveNotificationSettings(
      mapOf(
        "masterEnabled" to false,
        "taskFinishedEnabled" to true,
        "serviceRecoveredEnabled" to true,
      ),
    )
    gateway.performStrongBackgroundAction(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS)

    assertEquals(true, initial["masterEnabled"])
    assertEquals("service-strong-background", strongBackground["source"])
    assertEquals(
      "bound",
      ((strongBackground["runtimeServiceConnectionState"] as Map<String, Any?>)["phase"]),
    )
    assertEquals(false, saved["masterEnabled"])
    assertEquals(true, saved["taskFinishedEnabled"])
    assertEquals(true, saved["serviceRecoveredEnabled"])
    assertNull(delegate.lastNotificationSettingsPayload)
    assertNull(delegate.lastStrongBackgroundActionId)
    assertEquals(
      StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
      strongBackgroundAccess.lastActionId,
    )
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesNetworkSearchAndMediaSpeechWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val networkFacade = RecordingNetworkSearchConfigFacade()
    val mediaFacade = RecordingMediaSpeechSettingsFacade()
    var settingsOverviewNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = networkFacade,
      mediaSpeechSettingsFacade = mediaFacade,
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
    )

    val network = gateway.loadNetworkSearchConfig()
    val savedNetwork = gateway.saveNetworkSearchConfig(
      listOf(
        mapOf(
          "id" to "secondary",
          "providerId" to "perplexity",
          "label" to "Perplexity",
          "baseUrl" to "https://search.example.com",
          "model" to "sonar-pro",
          "apiKey" to "search-key",
          "enabled" to true,
        ),
      ),
    )
    val media = gateway.loadMediaSpeechConfig()
    val savedMedia = gateway.saveMediaSpeechConfig(
      mapOf(
        "imageGeneration" to mapOf(
          "provider" to "openrouter",
          "baseUrl" to "https://image.example.com",
          "endpoint" to "/images",
          "model" to "gpt-image-1",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "image-key",
        ),
        "videoGeneration" to mapOf(
          "provider" to "runway",
          "baseUrl" to "https://video.example.com",
          "endpoint" to "/videos",
          "model" to "gen4",
          "authProtocol" to ProviderAuthProtocols.ANTHROPIC,
          "apiKey" to "video-key",
        ),
        "voiceGeneration" to mapOf(
          "provider" to "elevenlabs",
          "baseUrl" to "https://voice.example.com",
          "endpoint" to "/speech",
          "model" to "tts-omni",
          "voicePreset" to "alloy",
          "authProtocol" to ProviderAuthProtocols.NONE,
          "apiKey" to "",
        ),
        "sttRouteId" to "external",
        "externalStt" to mapOf(
          "provider" to "deepgram",
          "baseUrl" to "https://stt.example.com",
          "endpoint" to "/listen",
          "model" to "nova-3",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "stt-key",
        ),
        "onDeviceModel" to mapOf(
          "modelPackage" to "tiny.en",
          "downloadStatus" to "ready",
        ),
      ),
    )

    assertEquals("Network & Search", network["title"])
    assertEquals("perplexity", networkFacade.lastSavedRequest?.slots?.single()?.providerId)
    assertEquals(
      "perplexity",
      (savedNetwork["slots"] as List<Map<String, Any?>>).single()["providerId"],
    )
    assertEquals("Media & Speech", media["title"])
    assertEquals("external", mediaFacade.lastSavedRequest?.sttRouteId)
    assertEquals(
      "openrouter",
      (savedMedia["imageGeneration"] as Map<String, Any?>)["provider"],
    )
    assertEquals(
      "runway",
      (savedMedia["videoGeneration"] as Map<String, Any?>)["provider"],
    )
    assertEquals("tts-omni", mediaFacade.lastSavedRequest?.voiceGeneration?.model)
    assertEquals(
      ProviderAuthProtocols.ANTHROPIC,
      mediaFacade.lastSavedRequest?.videoGeneration?.authProtocol,
    )
    assertEquals(2, settingsOverviewNotificationCount)
    assertNull(delegate.lastNetworkSearchSlots)
    assertNull(delegate.lastMediaSpeechPayload)
    assertEquals("delegate-network-search", delegate.loadNetworkSearchConfig()["source"])
    assertEquals("delegate-media-speech", delegate.loadMediaSpeechConfig()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesOverviewDetailAndObserveWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val settingsFacade = RecordingServiceOwnedSettingsFacade()
    val observedTitles = mutableListOf<String>()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = settingsFacade,
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { },
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val disposer = gateway.observeSettingsOverview { snapshot ->
      observedTitles += snapshot["title"] as String
    }
    try {
      val overview = gateway.loadSettingsOverview()
      val detail = gateway.loadSettingsDetail("personalization")

      assertEquals("Settings", overview["title"])
      assertEquals("personalization", detail["routeId"])
      assertEquals(listOf("Settings"), observedTitles)
      assertEquals("delegate-settings", delegate.loadSettingsOverview()["source"])
      assertEquals("delegate-settings-detail", delegate.loadSettingsDetail("personalization")["source"])
      assertEquals(
        com.opencray.app.facade.settings.SettingsRouteId.PERSONALIZATION,
        settingsFacade.lastLoadedDetailRouteId,
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesSandboxWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val sandboxAccess = RecordingSandboxSettingsGatewayAccess()
    var settingsOverviewNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = sandboxAccess,
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
    )

    val sandbox = gateway.loadSandboxSettings()
    val savedSandbox = gateway.saveSandboxSettings(
      mapOf(
        "enabled" to true,
        "providerId" to "e2b",
        "defaultBackend" to "envd",
        "sessionMode" to "ephemeral",
        "autoResume" to true,
        "idleTimeoutMinutes" to 45,
        "startupTimeoutMs" to 120000L,
        "requestTimeoutMs" to 240000L,
        "timeoutAction" to "restart",
        "templateId" to "python",
        "e2bApiKey" to "sandbox-secret",
      ),
    )

    assertEquals(false, sandbox["enabled"])
    assertEquals("e2b", sandboxAccess.lastSavedState?.providerId)
    assertEquals("sandbox-secret", sandboxAccess.lastSavedApiKey)
    assertEquals("python", savedSandbox["templateId"])
    assertEquals(true, savedSandbox["apiKeyConfigured"])
    assertEquals(1, settingsOverviewNotificationCount)
    assertNull(delegate.lastSandboxSettingsPayload)
    assertEquals("delegate-sandbox-settings", delegate.loadSandboxSettings()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesPersonalizationSaveAndResetWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val personalizationFacade = RecordingPersonalizationFacade()
    val appLanguageAccess = RecordingAppLanguageSettingsGatewayAccess()
    var settingsOverviewNotificationCount = 0
    var shellSnapshotNotificationCount = 0
    var chatSnapshotNotificationCount = 0
    var localizedRefreshCount = 0
    var skillsSnapshotNotificationCount = 0
    var skillsProjectionNotificationCount = 0
    var localSettingsOverviewEmissionCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      appLanguageSettingsAccess = appLanguageAccess,
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = personalizationFacade,
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      shellSnapshotNotifier = { shellSnapshotNotificationCount += 1 },
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
      settingsOverviewNotifier = { settingsOverviewNotificationCount += 1 },
      skillsSnapshotNotifier = { skillsSnapshotNotificationCount += 1 },
      skillsProjectionNotifier = { skillsProjectionNotificationCount += 1 },
      localizedResourcesRefresh = { localizedRefreshCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val disposer = gateway.observeSettingsOverview {
      localSettingsOverviewEmissionCount += 1
    }
    try {
      val personalization = gateway.loadPersonalizationConfig()
      val savedPersonalization = gateway.savePersonalizationConfig(
        presetId = "warm",
        customLabel = "Custom Coach",
        customGuidance = "Push harder on follow-through.",
      )
      val resetPersonalization = gateway.runPersonalizationReset("memory")
      val delegatedLanguage = gateway.setAppLanguage("zh-CN")

      assertEquals("Personalization", personalization["title"])
      assertEquals("warm", personalizationFacade.lastSavedRequest?.presetId)
      assertEquals("Custom Coach", savedPersonalization["customLabel"])
      assertEquals("memory", personalizationFacade.lastResetScope?.wireValue)
      assertEquals("Memory reset staged.", resetPersonalization["lastResetMessage"])
      assertEquals(3, settingsOverviewNotificationCount)
      assertEquals(1, shellSnapshotNotificationCount)
      assertEquals(1, chatSnapshotNotificationCount)
      assertEquals(1, localizedRefreshCount)
      assertEquals(1, skillsSnapshotNotificationCount)
      assertEquals(1, skillsProjectionNotificationCount)
      assertEquals(4, localSettingsOverviewEmissionCount)
      assertEquals("zh-CN", appLanguageAccess.lastLanguageId)
      assertNull(delegate.lastPersonalizationPresetId)
      assertNull(delegate.lastPersonalizationResetScopeId)
      assertNull(delegate.lastAppLanguageId)
      assertEquals("service-language", delegatedLanguage["source"])
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesSafetyWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val safetyFacade = RecordingSafetySettingsFacade()
    var chatSnapshotNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = safetyFacade,
      llmConfigFacade = RecordingLlmConfigFacade(),
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
    )

    val safety = gateway.loadSafetySettings()
    val savedSafety = gateway.saveSafetySettings(
      automationModeId = "confirm",
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 8,
      maxAgentTurns = 16,
      maxToolCalls = 24,
      undoWindowHours = 12,
      fileChangesPolicyId = "ask",
      fileDeletesPolicyId = "deny",
      shellCommandsPolicyId = "ask",
      externalAccessModeId = "allow",
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = "workspace_write",
      readOnlyOutsideWorkspace = true,
      liveContextModeId = "lightweight",
      memoryToolsEnabled = false,
    )

    assertEquals("auto", safety["automationModeId"])
    assertEquals("confirm", safetyFacade.lastSavedRequest?.automationModeId)
    assertEquals("lightweight", savedSafety["liveContextModeId"])
    assertEquals(false, savedSafety["memoryToolsEnabled"])
    assertEquals(1, chatSnapshotNotificationCount)
    assertNull(delegate.lastSafetyAutomationModeId)
    assertEquals("delegate-safety", delegate.loadSafetySettings()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayHandlesLlmAndMcpWithoutDelegateRoundTrip() {
    val delegate = RecordingSettingsGateway("delegate")
    val llmFacade = RecordingLlmConfigFacade()
    val mcpFacade = RecordingMcpSettingsFacade()
    val gateway = ServiceOwnedSettingsGateway(
      delegate = delegate,
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = llmFacade,
      mcpSettingsFacade = mcpFacade,
    )

    val llm = gateway.loadLlmConfig()
    val savedLlm = gateway.saveLlmConfig(
      enabled = true,
      providerMode = LlmProviderModes.CLOUD,
      providerId = "custom",
      selectedProviderOptionId = "custom-provider",
      protocol = "anthropic",
      providerName = "Custom",
      providerNotes = "Notes",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
      contextBudgetPreset = "expanded",
      contextBudgetReservedOutputTokens = 3072,
      contextBudgetSafetyMarginTokens = 1536,
      contextBudgetEffectiveInputPercent = 0.92,
    )
    val validatedLlm = gateway.validateLlmConfig(
      providerId = "custom",
      protocol = "anthropic",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
    )
    val savedCustomProvider = gateway.saveCustomLlmProvider(
      selectedProviderOptionId = "custom-provider-2",
      protocol = "anthropic",
      providerName = "Custom 2",
      providerNotes = "More notes",
      baseUrl = "https://provider.example.com",
      apiKey = "secret-2",
      model = "claude-kimi-hybrid",
      reasoningEffort = "high",
      systemPrompt = "Prompt 2",
      contextBudgetPreset = "compact",
      contextBudgetReservedOutputTokens = 2048,
    )
    val mcp = gateway.loadMcpSettings()
    val mcpMaster = gateway.setMcpMasterEnabled(false)
    val mcpServer = gateway.setMcpServerEnabled(serverId = "filesystem", enabled = true)

    assertEquals("kimi-k2.5", llm["model"])
    assertEquals("anthropic", savedLlm["protocol"])
    assertEquals("custom-provider", llmFacade.lastSavedRequest?.selectedProviderOptionId)
    assertEquals("expanded", llmFacade.lastSavedRequest?.contextBudgetPreset)
    assertEquals(3072, llmFacade.lastSavedRequest?.contextBudgetReservedOutputTokens)
    assertEquals(1536, llmFacade.lastSavedRequest?.contextBudgetSafetyMarginTokens)
    assertEquals(0.92, llmFacade.lastSavedRequest?.contextBudgetEffectiveInputPercent)
    assertEquals("custom-provider-2", llmFacade.lastCustomProviderRequest?.selectedProviderOptionId)
    assertEquals("compact", llmFacade.lastCustomProviderRequest?.contextBudgetPreset)
    assertEquals(2048, llmFacade.lastCustomProviderRequest?.contextBudgetReservedOutputTokens)
    assertEquals("expanded", savedLlm["contextBudgetPreset"])
    assertEquals(3072, savedLlm["contextBudgetReservedOutputTokens"])
    assertEquals("claude-kimi-hybrid", savedCustomProvider["model"])
    assertEquals("kimi-k2.5", llmFacade.lastValidatedRequest?.model)
    assertEquals(true, (validatedLlm["agentCapability"] as Map<String, Any?>)["nativeToolCallingAvailable"])
    assertEquals("MCP", mcp["title"])
    assertEquals(false, mcpMaster["masterEnabled"])
    assertEquals("filesystem", mcpFacade.lastServerEnabledId)
    assertEquals(true, mcpFacade.lastServerEnabledValue)
    assertEquals(true, (mcpServer["servers"] as List<Map<String, Any?>>).first()["isActionEnabled"])
    assertEquals(null, delegate.lastMcpMasterEnabled)
    assertEquals("delegate-llm", delegate.loadLlmConfig()["source"])
  }

  @Test
  fun serviceOwnedSettingsGatewayRefreshesChatAndWarmupAfterSavingLlmConfig() {
    val llmFacade = RecordingLlmConfigFacade()
    val warmupAccess = RecordingOnDeviceWarmupAccess()
    var chatSnapshotNotificationCount = 0
    val gateway = ServiceOwnedSettingsGateway(
      localeTag = "en",
      settingsFacade = RecordingServiceOwnedSettingsFacade(),
      notificationSettingsFacade = com.opencray.app.facade.notifications
        .LocalNotificationSettingsFacade
        .create(),
      strongBackgroundSettingsAccess = RecordingStrongBackgroundSettingsAccess(),
      appLanguageSettingsAccess = RecordingAppLanguageSettingsGatewayAccess(),
      sandboxSettingsAccess = RecordingSandboxSettingsGatewayAccess(),
      networkSearchConfigFacade = RecordingNetworkSearchConfigFacade(),
      mediaSpeechSettingsFacade = RecordingMediaSpeechSettingsFacade(),
      personalizationFacade = RecordingPersonalizationFacade(),
      safetySettingsFacade = RecordingSafetySettingsFacade(),
      llmConfigFacade = llmFacade,
      mcpSettingsFacade = RecordingMcpSettingsFacade(),
      chatSnapshotNotifier = { chatSnapshotNotificationCount += 1 },
      onDeviceWarmupAccess = warmupAccess,
    )

    gateway.saveLlmConfig(
      enabled = true,
      providerMode = LlmProviderModes.ON_DEVICE_MODEL,
      providerId = "on-device",
      selectedProviderOptionId = "on-device",
      protocol = "openai",
      providerName = "On device",
      providerNotes = "",
      baseUrl = "",
      apiKey = "",
      model = "",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
      selectedOnDeviceModelId = "gemma3n-e2b-it-int4",
      onDeviceAccelerator = OnDeviceLlmAccelerators.GPU,
    )

    assertEquals(1, warmupAccess.ensureWarmForActiveSessionCallCount)
    assertEquals(1, chatSnapshotNotificationCount)
  }

  @Test
  fun serviceOwnedSkillsGatewayHandlesSimpleFacadeBackedFlowsWithoutDelegateRoundTrip() {
    val delegate = RecordingSkillsGateway("delegate")
    val facade = RecordingServiceOwnedSkillsFacade()
    var notifiedSnapshotCount = 0
    val observedSources = mutableListOf<List<String>>()
    val gateway = ServiceOwnedSkillsGateway(
      delegate = delegate,
      skillsFacade = facade,
      localeTag = "en",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills.",
      snapshotNotifier = { notifiedSnapshotCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val disposer = gateway.observeSkills { snapshot ->
      observedSources += (snapshot["installedSkills"] as List<Map<String, Any?>>).mapNotNull { skill ->
        skill["id"] as String?
      }
    }

    val snapshot = gateway.loadSkillsSnapshot(query = "voice", suggestedLimit = 6)
    gateway.setSkillEnabled("voice-notes", enabled = false)
    val inspect = gateway.inspectSkillSource("github:opencray/skills")
    val instructions = gateway.loadSkillInstructions("voice-notes")
    val suggestedInstructions = gateway.loadSuggestedSkillInstructions(
      sourceRef = "github:opencray/skills",
      selectedSkillName = "voice-notes",
    )
    val installed = gateway.installSkillSource(
      sourceRef = "github:opencray/skills",
      selectedSkillName = "voice-notes",
    )
    val batchInstalled = gateway.installSkillSourceBatch(
      sourceRef = "github:opencray/skills",
      selectedSkillNames = listOf("voice-notes", "git-sync"),
    )
    val checked = gateway.checkInstalledSkillUpdates("voice-notes")
    val updated = gateway.updateInstalledSkill("voice-notes")
    val removed = gateway.deleteInstalledSkill("voice-notes")
    val refreshed = gateway.refreshSkills()
    val activation = gateway.activateSkillsInstallSource("github-url")

    val installedSkills = snapshot["installedSkills"] as List<Map<String, Any?>>
    val installSources = snapshot["installSources"] as List<Map<String, Any?>>
    assertEquals("voice-notes", installedSkills.first()["id"])
    assertEquals("github-url", installSources.first()["id"])
    assertTrue(facade.loadQueries.contains("voice"))
    assertTrue(facade.loadSuggestedLimits.contains(6))
    assertEquals("", facade.lastLoadQuery)
    assertEquals(0, facade.lastLoadSuggestedLimit)
    assertEquals("voice-notes", facade.lastSetSkillEnabledSkillId)
    assertEquals(false, facade.lastSetSkillEnabledValue)
    assertEquals("github:opencray/skills", facade.lastInstalledSourceRef)
    assertEquals("voice-notes", facade.lastInstalledSelectedSkillName)
    assertEquals("github:opencray/skills", facade.lastBatchInstalledSourceRef)
    assertEquals(listOf("voice-notes", "git-sync"), facade.lastBatchInstalledSkillNames)
    assertEquals("voice-notes", facade.lastCheckedSkillId)
    assertEquals("voice-notes", facade.lastUpdatedSkillId)
    assertEquals("github:opencray/skills", inspect["sourceRef"])
    assertEquals("voice-notes", instructions["id"])
    assertEquals("github:opencray/skills", suggestedInstructions["sourceDirectoryPath"])
    assertEquals("Installed voice-notes.", installed)
    assertEquals("Installed 2 skills.", batchInstalled)
    assertEquals("Update available for 'voice-notes'.", checked)
    assertEquals("Updated 'voice-notes'.", updated)
    assertEquals("Removed voice-notes.", removed)
    assertEquals("Reloaded skills.", refreshed)
    assertEquals("activated:github-url", activation)
    assertEquals(6, notifiedSnapshotCount)
    assertEquals(null, delegate.lastInstalledSourceRef)
    assertEquals(null, delegate.lastSetSkillEnabledSkillId)
    assertEquals(null, delegate.lastDeletedSkillId)
    assertEquals(0, delegate.refreshCount)
    assertEquals(null, delegate.lastActivatedSourceId)
    assertEquals(0, delegate.observeSkillsCount)
    assertEquals(7, observedSources.size)
    disposer()
  }

  @Test
  fun serviceOwnedShellGatewayLoadsAndObservesWithoutHostRuntimeDelegate() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("service-owned-shell"))
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var keepAliveListener: (() -> Unit)? = null
    val hostLifecycleDescriptor = serviceShellHostLifecycleDescriptor(
      runtimeControllerLifecycle = expected.runtimeControllerLifecycle,
      runtimeOwnerLifecycle = expected.runtimeOwnerLifecycle,
      runtimeServiceLifecycle = expected.serviceLifecycle,
    )
    val gateway = ServiceOwnedShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTag = "en",
      hostLabel = "HOST",
      hostSummary = "summary",
      runtimeHostAccess = noOpRuntimeHostAccess(expected.runtimeOwnerLifecycle),
      runtimeControllerLifecycle = expected.runtimeControllerLifecycle,
      runtimeServiceLifecycle = expected.serviceLifecycle,
      runtimeServiceWorkStateProvider = { expected.serviceWorkState },
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveListener = listener
        { if (keepAliveListener === listener) keepAliveListener = null }
      },
      runtimeServiceConnectionStateProvider = { RuntimeServiceConnectionState.binderConnected() },
      localRuntimeServerStateProvider = { expected.localRuntimeServerState },
      mainThreadPoster = ImmediateMainThreadPoster,
      hostLifecycleDescriptor = hostLifecycleDescriptor,
    )
    val observedStates = mutableListOf<Pair<String?, String?>>()

    val disposer = gateway.observeShell { snapshot ->
      val keepAlive = snapshot["runtimeServiceKeepAliveState"] as? Map<*, *>
      observedStates += (snapshot["localeTag"] as String?) to (keepAlive?.get("phase") as String?)
    }

    try {
      val initial = gateway.loadShellSnapshot()
      val hostLifecycle = initial["hostLifecycle"] as Map<*, *>
      @Suppress("UNCHECKED_CAST")
      val localRuntimeServerState = initial["localRuntimeServerState"] as Map<String, Any?>

      assertEquals("chat", initial["initialTab"])
      assertEquals("en", initial["localeTag"])
      assertNotNull(initial["runtimeOwnerLifecycle"])
      assertNotNull(initial["runtimeOwnerWorkSummary"])
      assertNotNull(initial["runtimeControllerLifecycle"])
      assertNotNull(initial["runtimeServiceLifecycle"])
      assertEquals(expected.serviceLifecycle.serviceInstanceId, hostLifecycle["hostInstanceId"])
      assertEquals(expected.runtimeOwnerLifecycle.runtimeOwnerId, hostLifecycle["runtimeOwnerId"])
      assertEquals(
        expected.runtimeControllerLifecycle?.controllerInstanceId,
        hostLifecycle["runtimeControllerId"],
      )
      assertEquals(
        expected.runtimeControllerLifecycle?.durableControllerId,
        hostLifecycle["durableRuntimeControllerId"],
      )
      assertEquals(LocalRuntimeServerState.PHASE_LISTENING, localRuntimeServerState["phase"])
      assertEquals(42_617, localRuntimeServerState["listeningPort"])
      assertEquals(listOf("en" to RuntimeServiceKeepAliveState.PHASE_CREATED), observedStates)

      gateway.updateLocalizedResources(
        localeTag = "zh-CN",
        hostLabel = "HOST-ZH",
        hostSummary = "summary-zh",
      )
      gateway.emitLocalizedSnapshotChanged()

      keepAliveState = keepAliveState.copy(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        changedAtEpochMs = 1_500L,
      )
      keepAliveListener?.invoke()

      assertEquals(
        listOf(
          "en" to RuntimeServiceKeepAliveState.PHASE_CREATED,
          "zh-CN" to RuntimeServiceKeepAliveState.PHASE_CREATED,
          "zh-CN" to RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        ),
        observedStates,
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedShellGatewayDisposeUnregistersExternalObservers() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("service-owned-shell-dispose"))
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var registeredHostListener: AgentSessionRuntimeListener? = null
    var hostObserverDisposeCount = 0
    var keepAliveListener: (() -> Unit)? = null
    var keepAliveObserverDisposeCount = 0
    val hostLifecycleDescriptor = serviceShellHostLifecycleDescriptor(
      runtimeControllerLifecycle = expected.runtimeControllerLifecycle,
      runtimeOwnerLifecycle = expected.runtimeOwnerLifecycle,
      runtimeServiceLifecycle = expected.serviceLifecycle,
    )
    val gateway = ServiceOwnedShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTag = "en",
      hostLabel = "HOST",
      hostSummary = "summary",
      runtimeHostAccess = object : RuntimeOwnerObservationAccess {
        override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
          expected.runtimeOwnerLifecycle

        override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
          registeredHostListener = listener
          return {
            if (registeredHostListener === listener) {
              registeredHostListener = null
            }
            hostObserverDisposeCount += 1
          }
        }

        override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()
      },
      runtimeServiceLifecycle = expected.serviceLifecycle,
      runtimeServiceWorkStateProvider = { expected.serviceWorkState },
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveListener = listener
        {
          if (keepAliveListener === listener) {
            keepAliveListener = null
          }
          keepAliveObserverDisposeCount += 1
        }
      },
      runtimeServiceConnectionStateProvider = { RuntimeServiceConnectionState.binderConnected() },
      mainThreadPoster = ImmediateMainThreadPoster,
      hostLifecycleDescriptor = hostLifecycleDescriptor,
    )
    val observedPhases = mutableListOf<String?>()
    val disposer = gateway.observeShell { snapshot ->
      val keepAlive = snapshot["runtimeServiceKeepAliveState"] as? Map<*, *>
      observedPhases += keepAlive?.get("phase") as String?
    }

    try {
      assertNotNull(registeredHostListener)
      assertNotNull(keepAliveListener)
      assertEquals(listOf(RuntimeServiceKeepAliveState.PHASE_CREATED), observedPhases)

      gateway.dispose()
      gateway.dispose()

      assertEquals(1, hostObserverDisposeCount)
      assertEquals(1, keepAliveObserverDisposeCount)
      assertNull(registeredHostListener)
      assertNull(keepAliveListener)

      keepAliveState = keepAliveState.copy(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        changedAtEpochMs = 1_500L,
      )
      gateway.emitLocalizedSnapshotChanged()

      assertEquals(listOf(RuntimeServiceKeepAliveState.PHASE_CREATED), observedPhases)
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayKeepsHostChatAndRuntimeSnapshotsAndUsesProjectionForDebugReads() {
    val delegate = RecordingChatRuntimeGateway("delegate")
    val readGateway = RecordingChatRuntimeGateway("projection")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = readGateway,
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedChatSources = mutableListOf<String?>()
    val observedRuntimeSources = mutableListOf<String?>()

    val chatDisposer = gateway.observeChat { snapshot ->
      observedChatSources += snapshot["source"] as String?
    }
    val runtimeDisposer = gateway.observeChatRuntime { snapshot ->
      observedRuntimeSources += snapshot["source"] as String?
    }

    try {
      assertEquals("delegate-chat", gateway.loadChatSnapshot()["source"])
      assertEquals("delegate-runtime", gateway.loadChatRuntimeSnapshot()["source"])
      assertEquals("delegate-run", gateway.loadChatRunSnapshot("run-alpha")?.get("source"))
      assertEquals(
        "projection-memory-action",
        gateway.applyMemoryDebugAction("memory-1", "suppress")["source"],
      )

      gateway.submitChatMessage("hello", emptyList())
      gateway.notifyChatSnapshotsChanged()

      assertEquals(listOf("delegate-chat", "delegate-chat"), observedChatSources)
      assertEquals(listOf("delegate-runtime", "delegate-runtime"), observedRuntimeSources)
      assertEquals("hello", delegate.submittedText)
      assertNull(delegate.memoryDebugActionRecordId)
      assertEquals("memory-1", readGateway.memoryDebugActionRecordId)
      assertEquals("suppress", readGateway.memoryDebugActionId)
      assertNull(readGateway.submittedText)
      assertEquals(1, delegate.notifiedChatSnapshotCount)
    } finally {
      chatDisposer()
      runtimeDisposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesSessionMutationsThroughServiceAccess() {
    val chatRoot = temporaryFolder.newFolder("service-owned-chat-mutation-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val originalSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(originalSessionId, "Keep this transcript")
    val sourceMessageId = requireNotNull(
      chatSessionStore.loadSession(originalSessionId),
    ).messages.last().messageId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val delegate = RecordingChatRuntimeGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatRuntimeGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
        chatSessionStore = chatSessionStore,
        runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
          lifecycleDescriptor = lifecycleDescriptor,
          sessionRuntimeManager = runtimeManager,
          runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
          promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
          supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
            private val stores = linkedMapOf<String, SessionSupplementStore>()

            override fun forChatSession(sessionId: String): SessionSupplementStore =
              stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
          },
          approvalRegistry = AgentTaskApprovalRegistry(),
        ),
        chatUnreadMessageState = ChatUnreadMessageState(),
        terminalReplayRepairer = { sessionId, runs ->
          repairCalls += sessionId to runs
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.createChatSession()
    val createdSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.copyChatSession(originalSessionId)
    val copiedSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.branchChatSessionFromMessage(originalSessionId, sourceMessageId)
    val branchedSessionId = chatSessionStore.loadState().activeSession.sessionId

    gateway.branchChatSessionFromMessage(originalSessionId, "")

    assertTrue(createdSessionId != originalSessionId)
    assertTrue(copiedSessionId != originalSessionId)
    assertTrue(copiedSessionId != createdSessionId)
    assertTrue(branchedSessionId != originalSessionId)
    assertTrue(branchedSessionId != copiedSessionId)
    assertEquals(
      sourceMessageId,
      requireNotNull(chatSessionStore.loadSession(branchedSessionId)).messages.last().messageId,
    )
    assertEquals(0, delegate.createChatSessionCallCount)
    assertTrue(delegate.copiedSessionIds.isEmpty())
    assertTrue(delegate.branchedSessionRequests.isEmpty())
    assertEquals(3, delegate.notifiedChatSnapshotCount)
    assertEquals(
      listOf(createdSessionId, copiedSessionId, branchedSessionId),
      runtimeManager.resumedSessionIds,
    )
    assertEquals(
      listOf(
        createdSessionId to emptyList<AgentRunSnapshot>(),
        copiedSessionId to emptyList(),
        branchedSessionId to emptyList(),
      ),
      repairCalls,
    )
  }

  @Test
  fun serviceOwnedChatSessionMutationAccessClearsSharedUnreadStateWhenReusingEmptySession() {
    val chatRoot = temporaryFolder.newFolder("service-owned-chat-unread-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val chatUnreadMessageState = ChatUnreadMessageState().apply {
      incrementIfBackgroundUpdate(
        sessionId = activeSessionId,
        activeSessionId = "other-session",
        text = "background update",
      )
    }
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val access = ServiceOwnedChatSessionMutationAccess(
      chatSessionStore = chatSessionStore,
      runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      chatUnreadMessageState = chatUnreadMessageState,
      terminalReplayRepairer = { sessionId, runs ->
        repairCalls += sessionId to runs
      },
    )

    assertEquals(1, chatUnreadMessageState.rawCount(activeSessionId))

    access.createChatSession()

    assertEquals(activeSessionId, chatSessionStore.loadState().activeSession.sessionId)
    assertEquals(0, chatUnreadMessageState.rawCount(activeSessionId))
    assertEquals(listOf(activeSessionId), runtimeManager.resumedSessionIds)
    assertEquals(
      listOf(activeSessionId to emptyList<AgentRunSnapshot>()),
      repairCalls,
    )
  }
}
