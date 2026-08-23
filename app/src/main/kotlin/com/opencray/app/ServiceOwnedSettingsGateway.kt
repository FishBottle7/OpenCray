package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsRouteId

internal class ServiceOwnedSettingsGateway(
  @Suppress("unused")
  private val delegate: OpenCraySettingsGateway? = null,
  private var localeTag: String,
  private var settingsFacade: SettingsFacade,
  private var notificationSettingsFacade: NotificationSettingsFacade,
  private val scheduledTaskManager: AppScheduledTaskManager? = null,
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  appLanguageSettingsAccess: AppLanguageSettingsGatewayAccess? = null,
  private val sandboxSettingsAccess: SandboxSettingsGatewayAccess,
  private var networkSearchConfigFacade: NetworkSearchConfigFacade,
  private var mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private var personalizationFacade: PersonalizationFacade,
  private var safetySettingsFacade: SafetySettingsFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var mcpSettingsFacade: McpSettingsFacade,
  private val shellSnapshotNotifier: () -> Unit = {},
  private val chatSnapshotNotifier: () -> Unit = {},
  private val onDeviceWarmupAccess: OnDeviceLlmWarmupAccess = NoOpOnDeviceLlmWarmupAccess,
  private val settingsOverviewNotifier: () -> Unit = {},
  private val skillsSnapshotNotifier: () -> Unit = {},
  private val skillsProjectionNotifier: () -> Unit = {},
  private val localizedResourcesRefresh: () -> Unit = {},
  private val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySettingsGateway {
  private val lock = Any()
  private val resolvedAppLanguageSettingsAccess: AppLanguageSettingsGatewayAccess =
    appLanguageSettingsAccess
      ?: delegate?.let(::GatewayBackedAppLanguageSettingsGatewayAccess)
      ?: error(
        "Service-owned settings gateway requires an app-language access implementation when no delegate is provided.",
      )
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  override fun loadSettingsOverview(): Map<String, Any?> =
    settingsFacade.loadOverview().toSettingsOverviewGatewayMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      settingsOverviewListeners += listener
    }
    mainThreadPoster.post {
      listener(loadSettingsOverview())
    }
    return {
      synchronized(lock) {
        settingsOverviewListeners -= listener
      }
    }
  }

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return settingsFacade.loadDetail(routeId).toSettingsDetailGatewayMap()
  }

  override fun loadNotificationSettings(): Map<String, Any?> =
    notificationSettingsFacade.load().toGatewayMap()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    notificationSettingsFacade
      .save(payload.toSaveNotificationSettingsRequest())
      .toGatewayMap()

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
    putAll(strongBackgroundSettingsAccess.loadSnapshot())
    runtimeServiceConnectionStateProvider()?.let { state ->
      put("runtimeServiceConnectionState", state.snapshotMap())
    }
  }

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    strongBackgroundSettingsAccess.performAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    networkSearchConfigFacade.load().toGatewayMap()

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> {
    val snapshot = networkSearchConfigFacade.save(slots.toSaveNetworkSearchConfigRequest())
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap()
  }

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    mediaSpeechSettingsFacade.load().toGatewayMap()

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> {
    val snapshot = mediaSpeechSettingsFacade.save(payload.toSaveMediaSpeechConfigRequest())
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap()
  }

  override fun loadSandboxSettings(): Map<String, Any?> =
    sandboxSettingsAccess.load().toGatewayMap(localeTag)

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val current = sandboxSettingsAccess.load()
    val parsed = parseSandboxSettingsPayload(
      payload = payload,
      existingState = current.state,
    )
    val snapshot = sandboxSettingsAccess.save(
      state = parsed.state,
      e2bApiKey = parsed.e2bApiKey,
    )
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap(localeTag)
  }

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    personalizationFacade.load().toPersonalizationGatewayMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = personalizationFacade.save(
      com.opencray.app.facade.personalization.SavePersonalizationConfigRequest(
        presetId = presetId,
        customLabel = customLabel,
        customGuidance = customGuidance,
      ),
    )
    notifySettingsOverviewChanged()
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = personalizationFacade.reset(
      com.opencray.app.facade.personalization.PersonalizationResetScope.fromWireValue(scopeId),
    )
    notifySettingsOverviewChanged()
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = resolvedAppLanguageSettingsAccess.setAppLanguage(languageId)
    localizedResourcesRefresh()
    shellSnapshotNotifier()
    chatSnapshotNotifier()
    emitSettingsOverview()
    settingsOverviewNotifier()
    skillsSnapshotNotifier()
    skillsProjectionNotifier()
    return snapshot
  }

  override fun loadSafetySettings(): Map<String, Any?> =
    safetySettingsFacade.load().toSafetyGatewayMap()

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
    val snapshot = safetySettingsFacade.save(
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
    chatSnapshotNotifier()
    return snapshot.toSafetyGatewayMap()
  }

  private fun notifySettingsOverviewChanged() {
    emitSettingsOverview()
    settingsOverviewNotifier()
  }

  private fun emitSettingsOverview() {
    val currentListeners = synchronized(lock) { settingsOverviewListeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadSettingsOverview()
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  internal fun updateLocalizedResources(
    localeTag: String,
    settingsFacade: SettingsFacade,
    notificationSettingsFacade: NotificationSettingsFacade,
    networkSearchConfigFacade: NetworkSearchConfigFacade,
    mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
    personalizationFacade: PersonalizationFacade,
    safetySettingsFacade: SafetySettingsFacade,
    llmConfigFacade: LlmConfigFacade,
    mcpSettingsFacade: McpSettingsFacade,
  ) {
    synchronized(lock) {
      this.localeTag = localeTag
      this.settingsFacade = settingsFacade
      this.notificationSettingsFacade = notificationSettingsFacade
      this.networkSearchConfigFacade = networkSearchConfigFacade
      this.mediaSpeechSettingsFacade = mediaSpeechSettingsFacade
      this.personalizationFacade = personalizationFacade
      this.safetySettingsFacade = safetySettingsFacade
      this.llmConfigFacade = llmConfigFacade
      this.mcpSettingsFacade = mcpSettingsFacade
    }
  }

  override fun loadLlmConfig(): Map<String, Any?> =
    llmConfigFacade.load().toGatewayMap()

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
  ): Map<String, Any?> = llmConfigFacade.save(
    com.opencray.app.facade.llm.SaveLlmConfigRequest(
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
  ).toGatewayMap().also {
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    chatSnapshotNotifier()
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
  ): Map<String, Any?> = llmConfigFacade.saveCustomProvider(
    com.opencray.app.facade.llm.SaveCustomLlmProviderRequest(
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
  ).toGatewayMap()

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = llmConfigFacade.validate(
    com.opencray.app.facade.llm.ValidateLlmConfigRequest(
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
    val snapshot = llmConfigFacade.downloadOnDeviceModel(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> {
    val snapshot = llmConfigFacade.cancelOnDeviceModelDownload(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = llmConfigFacade.deleteOnDeviceModel(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun loadMcpSettings(): Map<String, Any?> =
    mcpSettingsFacade.load().toGatewayMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    mcpSettingsFacade.setMasterEnabled(enabled).toGatewayMap()

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> =
    mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled).toGatewayMap()
}
