package com.opencray.app

import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.media.SaveMediaProviderRequest
import com.opencray.app.facade.media.SaveMediaSpeechConfigRequest
import com.opencray.app.facade.media.SaveOnDeviceSttRequest
import com.opencray.app.facade.media.SaveVoiceProviderRequest
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.settings.SettingsRouteId

internal class HostSettingsGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCraySettingsGateway {
  private val fallbackSandboxSettingsRepository: SandboxSettingsRepository by lazy {
    OpenCrayHostRuntime.inMemorySandboxSettingsRepository()
  }

  private val scheduledTaskManager: AppScheduledTaskManager? by lazy {
    host.appContext?.let(AppScheduledTaskManager::fromContext)
  }

  override fun loadSettingsOverview(): Map<String, Any?> =
    synchronized(host.lock) { host.settingsFacade.loadOverview() }.toMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.settingsOverviewListeners,
      initialPayload = loadSettingsOverview(),
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return synchronized(host.lock) { host.settingsFacade.loadDetail(routeId) }.toMap()
  }

  override fun loadNotificationSettings(): Map<String, Any?> =
    synchronized(host.lock) { host.notificationSettingsFacade.load() }.toGatewayMap()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.notificationSettingsFacade.save(payload.toSaveNotificationSettingsRequest())
    }
    return snapshot.toGatewayMap()
  }

  override fun loadScheduledTasks(): Map<String, Any?> =
    requireScheduledTaskManager().loadScheduledTasksGatewayMap()

  override fun loadScheduledTask(scheduleId: String): Map<String, Any?> =
    requireScheduledTaskManager().loadScheduledTaskGatewayMap(scheduleId)

  override fun updateScheduledTaskEnabled(
    scheduleId: String,
    enabled: Boolean,
  ): Map<String, Any?> = requireScheduledTaskManager().updateScheduledTaskEnabledGatewayMap(
    scheduleId = scheduleId,
    enabled = enabled,
  )

  override fun runScheduledTaskNow(scheduleId: String): Map<String, Any?> =
    requireScheduledTaskManager().runScheduledTaskNowGatewayMap(scheduleId)

  override fun snoozeScheduledTask(
    scheduleId: String,
    durationMinutes: Int,
  ): Map<String, Any?> = requireScheduledTaskManager().snoozeScheduledTaskGatewayMap(
    scheduleId = scheduleId,
    durationMinutes = durationMinutes,
  )

  private fun requireScheduledTaskManager(): AppScheduledTaskManager =
    checkNotNull(scheduledTaskManager) { "Scheduled task management is unavailable." }

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = buildMap {
    putAll(host.strongBackgroundSettingsAccess.loadSnapshot())
    put(
      "runtimeServiceConnectionState",
      host.runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider()?.snapshotMap(),
    )
  }

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    host.strongBackgroundSettingsAccess.performAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.networkSearchConfigFacade.load() }.toMap()

  override fun saveNetworkSearchConfig(
    slots: List<Map<String, Any?>>,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.networkSearchConfigFacade.save(
        SaveNetworkSearchConfigRequest(
          slots = slots.map { slot ->
            SaveNetworkSearchSlotRequest(
              id = slot["id"]?.toString().orEmpty(),
              providerId = slot["providerId"]?.toString().orEmpty(),
              label = slot["label"]?.toString().orEmpty(),
              baseUrl = slot["baseUrl"]?.toString().orEmpty(),
              model = slot["model"]?.toString().orEmpty(),
              apiKey = slot["apiKey"]?.toString().orEmpty(),
              enabled = slot["enabled"] as? Boolean ?: true,
            )
          },
        ),
      )
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.mediaSpeechSettingsFacade.load() }.toMap()

  override fun saveMediaSpeechConfig(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val imageGeneration = payload["imageGeneration"] as? Map<String, Any?> ?: emptyMap()
    val videoGeneration = payload["videoGeneration"] as? Map<String, Any?> ?: emptyMap()
    val voiceGeneration = payload["voiceGeneration"] as? Map<String, Any?> ?: emptyMap()
    val externalStt = payload["externalStt"] as? Map<String, Any?> ?: emptyMap()
    val onDeviceModel = payload["onDeviceModel"] as? Map<String, Any?> ?: emptyMap()
    val snapshot = synchronized(host.lock) {
      host.mediaSpeechSettingsFacade.save(
        SaveMediaSpeechConfigRequest(
          imageGeneration = SaveMediaProviderRequest(
            provider = imageGeneration["provider"]?.toString().orEmpty(),
            baseUrl = imageGeneration["baseUrl"]?.toString().orEmpty(),
            endpoint = imageGeneration["endpoint"]?.toString().orEmpty(),
            model = imageGeneration["model"]?.toString().orEmpty(),
            authProtocol = imageGeneration["authProtocol"]?.toString().orEmpty(),
            apiKey = imageGeneration["apiKey"]?.toString().orEmpty(),
          ),
          videoGeneration = SaveMediaProviderRequest(
            provider = videoGeneration["provider"]?.toString().orEmpty(),
            baseUrl = videoGeneration["baseUrl"]?.toString().orEmpty(),
            endpoint = videoGeneration["endpoint"]?.toString().orEmpty(),
            model = videoGeneration["model"]?.toString().orEmpty(),
            authProtocol = videoGeneration["authProtocol"]?.toString().orEmpty(),
            apiKey = videoGeneration["apiKey"]?.toString().orEmpty(),
          ),
          voiceGeneration = SaveVoiceProviderRequest(
            provider = voiceGeneration["provider"]?.toString().orEmpty(),
            baseUrl = voiceGeneration["baseUrl"]?.toString().orEmpty(),
            endpoint = voiceGeneration["endpoint"]?.toString().orEmpty(),
            model = voiceGeneration["model"]?.toString().orEmpty(),
            voicePreset = voiceGeneration["voicePreset"]?.toString().orEmpty(),
            authProtocol = voiceGeneration["authProtocol"]?.toString().orEmpty(),
            apiKey = voiceGeneration["apiKey"]?.toString().orEmpty(),
          ),
          sttRouteId = payload["sttRouteId"]?.toString().orEmpty(),
          externalStt = SaveMediaProviderRequest(
            provider = externalStt["provider"]?.toString().orEmpty(),
            baseUrl = externalStt["baseUrl"]?.toString().orEmpty(),
            endpoint = externalStt["endpoint"]?.toString().orEmpty(),
            model = externalStt["model"]?.toString().orEmpty(),
            authProtocol = externalStt["authProtocol"]?.toString().orEmpty(),
            apiKey = externalStt["apiKey"]?.toString().orEmpty(),
          ),
          onDeviceModel = SaveOnDeviceSttRequest(
            modelPackage = onDeviceModel["modelPackage"]?.toString().orEmpty(),
            downloadStatus = onDeviceModel["downloadStatus"]?.toString().orEmpty(),
          ),
        ),
      )
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadSandboxSettings(): Map<String, Any?> =
    synchronized(host.lock) {
      resolvedSandboxSettingsRepository().load().toGatewayMap(host.strings.localeTag)
    }

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val saved = synchronized(host.lock) {
      val repository = resolvedSandboxSettingsRepository()
      val current = repository.load()
      val parsed = parseSandboxSettingsPayload(
        payload = payload,
        existingState = current.state,
      )
      repository.save(
        state = parsed.state,
        e2bApiKey = parsed.e2bApiKey,
      )
    }
    host.emitSettingsOverview()
    return saved.toGatewayMap(host.strings.localeTag)
  }

  override fun loadLlmConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.llmConfigFacade.load() }.toMap()

  override fun saveLlmConfig(
    enabled: Boolean,
    streamingEnabled: Boolean?,
    providerMode: String,
    providerId: String,
    selectedProviderOptionId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
    selectedOnDeviceModelId: String,
    onDeviceMaxContextWindow: Int,
    onDeviceMaxTokens: Int,
    onDeviceTopK: Int,
    onDeviceTopP: Double,
    onDeviceTemperature: Double,
    onDeviceAccelerator: String,
    onDeviceThinkingEnabled: Boolean,
    onDeviceLiteModeEnabled: Boolean,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.save(
        SaveLlmConfigRequest(
          enabled = enabled,
          streamingEnabled = streamingEnabled,
          providerMode = providerMode,
          providerId = providerId,
          selectedProviderOptionId = selectedProviderOptionId,
          protocol = protocol,
          providerName = providerName,
          providerNotes = providerNotes,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
          systemPrompt = systemPrompt,
          openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
          openAiPromptCacheRetention = openAiPromptCacheRetention,
          anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
          anthropicPromptCacheTtl = anthropicPromptCacheTtl,
          contextBudgetPreset = contextBudgetPreset,
          contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
          contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
          contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
          selectedOnDeviceModelId = selectedOnDeviceModelId,
          onDeviceMaxContextWindow = onDeviceMaxContextWindow,
          onDeviceMaxTokens = onDeviceMaxTokens,
          onDeviceTopK = onDeviceTopK,
          onDeviceTopP = onDeviceTopP,
          onDeviceTemperature = onDeviceTemperature,
          onDeviceAccelerator = onDeviceAccelerator,
          onDeviceThinkingEnabled = onDeviceThinkingEnabled,
          onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
          contextWindowTokensOverride = contextWindowTokensOverride,
        ),
      )
    }
    return snapshot.toMap()
  }

  override fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    streamingEnabled: Boolean?,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.saveCustomProvider(
        SaveCustomLlmProviderRequest(
          selectedProviderOptionId = selectedProviderOptionId,
          streamingEnabled = streamingEnabled,
          protocol = protocol,
          providerName = providerName,
          providerNotes = providerNotes,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
          systemPrompt = systemPrompt,
          openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy
            ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
          openAiPromptCacheRetention = openAiPromptCacheRetention
            ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
          anthropicPromptCachingEnabled = anthropicPromptCachingEnabled
            ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
          anthropicPromptCacheTtl = anthropicPromptCacheTtl
            ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
          contextBudgetPreset = contextBudgetPreset,
          contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
          contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
          contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
          contextWindowTokensOverride = contextWindowTokensOverride,
        ),
      )
    }
    return snapshot.toMap()
  }

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = host.llmConfigFacade.validate(
    ValidateLlmConfigRequest(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
      contextWindowTokensOverride = contextWindowTokensOverride,
    ),
  ).toMap()

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.downloadOnDeviceModel(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.cancelOnDeviceModelDownload(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.deleteOnDeviceModel(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.personalizationFacade.load() }.toMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.personalizationFacade.save(
        SavePersonalizationConfigRequest(
          presetId = presetId,
          customLabel = customLabel,
          customGuidance = customGuidance,
        ),
      )
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      val updated = host.personalizationFacade.setAppLanguage(languageId)
      if (host.appContext == null) {
        updated
      } else {
        host.refreshLocalizedResourcesLocked()
        host.personalizationFacade.load()
      }
    }
    host.emitShellSnapshot()
    host.emitSettingsOverview()
    host.emitSkillsSnapshot()
    host.emitChatSnapshot()
    return snapshot.toMap()
  }

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.personalizationFacade.reset(PersonalizationResetScope.fromWireValue(scopeId))
    }
    host.emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadMcpSettings(): Map<String, Any?> =
    synchronized(host.lock) { host.mcpSettingsFacade.load() }.toMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    synchronized(host.lock) { host.mcpSettingsFacade.setMasterEnabled(enabled) }.toMap()

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = synchronized(host.lock) {
    host.mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled)
  }.toMap()

  override fun loadSafetySettings(): Map<String, Any?> =
    synchronized(host.lock) { host.safetySettingsFacade.load() }.toMap()

  override fun saveSafetySettings(
    automationModeId: String,
    rollbackJournalEnabled: Boolean,
    maxFilesPerBatch: Int,
    maxAgentTurns: Int,
    maxToolCalls: Int,
    undoWindowHours: Int,
    fileChangesPolicyId: String,
    fileDeletesPolicyId: String,
    shellCommandsPolicyId: String,
    externalAccessModeId: String,
    photoLibraryEnabled: Boolean,
    downloadsEnabled: Boolean,
    documentsEnabled: Boolean,
    recordingsEnabled: Boolean,
    workspaceAccessProfileId: String,
    readOnlyOutsideWorkspace: Boolean,
    liveContextModeId: String,
    memoryToolsEnabled: Boolean,
    subAgentContextDefaultModeId: String?,
    subAgentContextProfileOverrides: Map<String, String>,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.safetySettingsFacade.save(
        SaveSafetySettingsRequest(
          automationModeId = automationModeId,
          rollbackJournalEnabled = rollbackJournalEnabled,
          maxFilesPerBatch = maxFilesPerBatch,
          maxAgentTurns = maxAgentTurns,
          maxToolCalls = maxToolCalls,
          undoWindowHours = undoWindowHours,
          fileChangesPolicyId = fileChangesPolicyId,
          fileDeletesPolicyId = fileDeletesPolicyId,
          shellCommandsPolicyId = shellCommandsPolicyId,
          externalAccessModeId = externalAccessModeId,
          photoLibraryEnabled = photoLibraryEnabled,
          downloadsEnabled = downloadsEnabled,
          documentsEnabled = documentsEnabled,
          recordingsEnabled = recordingsEnabled,
          workspaceAccessProfileId = workspaceAccessProfileId,
          readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
          liveContextModeId = liveContextModeId,
          memoryToolsEnabled = memoryToolsEnabled,
          subAgentContextDefaultModeId = subAgentContextDefaultModeId,
          subAgentContextProfileOverrides = subAgentContextProfileOverrides,
        ),
      )
    }
    host.emitChatSnapshot()
    return snapshot.toMap()
  }

  private fun resolvedSandboxSettingsRepository(): SandboxSettingsRepository =
    host.sandboxSettingsRepository ?: fallbackSandboxSettingsRepository
}
