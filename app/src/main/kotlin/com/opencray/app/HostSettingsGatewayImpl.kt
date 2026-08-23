package com.opencray.app

import com.opencray.app.facade.personalization.PersonalizationResetScope
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
    synchronized(host.lock) { host.settingsFacade.loadOverview() }.toSettingsOverviewGatewayMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.settingsOverviewListeners,
      initialPayload = loadSettingsOverview(),
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return synchronized(host.lock) { host.settingsFacade.loadDetail(routeId) }.toSettingsDetailGatewayMap()
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
    synchronized(host.lock) { host.networkSearchConfigFacade.load() }.toGatewayMap()

  override fun saveNetworkSearchConfig(
    slots: List<Map<String, Any?>>,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.networkSearchConfigFacade.save(slots.toSaveNetworkSearchConfigRequest())
    }
    host.emitSettingsOverview()
    return snapshot.toGatewayMap()
  }

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.mediaSpeechSettingsFacade.load() }.toGatewayMap()

  override fun saveMediaSpeechConfig(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.mediaSpeechSettingsFacade.save(payload.toSaveMediaSpeechConfigRequest())
    }
    host.emitSettingsOverview()
    return snapshot.toGatewayMap()
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
    synchronized(host.lock) { host.llmConfigFacade.load() }.toGatewayMap()

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
        saveLlmConfigRequest(
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
    return snapshot.toGatewayMap()
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
        saveCustomLlmProviderRequest(
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
          openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
          openAiPromptCacheRetention = openAiPromptCacheRetention,
          anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
          anthropicPromptCacheTtl = anthropicPromptCacheTtl,
          contextBudgetPreset = contextBudgetPreset,
          contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
          contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
          contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
          contextWindowTokensOverride = contextWindowTokensOverride,
        ),
      )
    }
    return snapshot.toGatewayMap()
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
    validateLlmConfigRequest(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
      contextWindowTokensOverride = contextWindowTokensOverride,
    ),
  ).toGatewayMap()

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.downloadOnDeviceModel(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toGatewayMap()
  }

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.cancelOnDeviceModelDownload(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toGatewayMap()
  }

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.llmConfigFacade.deleteOnDeviceModel(modelId)
    }
    host.emitSettingsOverview()
    return snapshot.toGatewayMap()
  }

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    synchronized(host.lock) { host.personalizationFacade.load() }.toPersonalizationGatewayMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.personalizationFacade.save(
        savePersonalizationConfigRequest(
          presetId = presetId,
          customLabel = customLabel,
          customGuidance = customGuidance,
        ),
      )
    }
    host.emitSettingsOverview()
    return snapshot.toPersonalizationGatewayMap()
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
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = synchronized(host.lock) {
      host.personalizationFacade.reset(PersonalizationResetScope.fromWireValue(scopeId))
    }
    host.emitSettingsOverview()
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun loadMcpSettings(): Map<String, Any?> =
    synchronized(host.lock) { host.mcpSettingsFacade.load() }.toGatewayMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    synchronized(host.lock) { host.mcpSettingsFacade.setMasterEnabled(enabled) }.toGatewayMap()

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = synchronized(host.lock) {
    host.mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled)
  }.toGatewayMap()

  override fun loadSafetySettings(): Map<String, Any?> =
    synchronized(host.lock) { host.safetySettingsFacade.load() }.toSafetyGatewayMap()

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
        safetySaveRequest(
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
    return snapshot.toSafetyGatewayMap()
  }

  private fun resolvedSandboxSettingsRepository(): SandboxSettingsRepository =
    host.sandboxSettingsRepository ?: fallbackSandboxSettingsRepository
}
