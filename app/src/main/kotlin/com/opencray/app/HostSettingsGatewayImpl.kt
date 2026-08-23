package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.OnDeviceLlmModelOptionSnapshot
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.media.MediaProviderSnapshot
import com.opencray.app.facade.media.MediaSpeechConfigSnapshot
import com.opencray.app.facade.media.OnDeviceSttSnapshot
import com.opencray.app.facade.media.SaveMediaProviderRequest
import com.opencray.app.facade.media.SaveMediaSpeechConfigRequest
import com.opencray.app.facade.media.SaveOnDeviceSttRequest
import com.opencray.app.facade.media.SaveVoiceProviderRequest
import com.opencray.app.facade.media.VoiceProviderSnapshot
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.facade.settings.SettingsRowSnapshot

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

private fun SettingsOverviewSnapshot.toMap(): Map<String, Any?> = mapOf(
  "eyebrow" to eyebrow,
  "title" to title,
  "subtitle" to subtitle,
  "deviceTitle" to deviceTitle,
  "deviceSummary" to deviceSummary,
  "entries" to entries.map { entry ->
    mapOf(
      "routeId" to entry.routeId.wireValue,
      "title" to entry.title,
    )
  },
)

private fun SettingsDetailSnapshot.toMap(): Map<String, Any?> = mapOf(
  "routeId" to routeId.wireValue,
  "title" to title,
  "subtitle" to subtitle,
  "sections" to sections.map { section -> section.toMap() },
)

private fun NetworkSearchConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "slots" to slots.map { slot -> slot.toMap() },
)

private fun NetworkSearchSlotSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "label" to label,
  "baseUrl" to baseUrl,
  "model" to model,
  "apiKey" to apiKey,
  "enabled" to enabled,
)

private fun MediaSpeechConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "imageGeneration" to imageGeneration.toMap(),
  "videoGeneration" to videoGeneration.toMap(),
  "voiceGeneration" to voiceGeneration.toMap(),
  "sttRouteId" to sttRouteId,
  "externalStt" to externalStt.toMap(),
  "onDeviceModel" to onDeviceModel.toMap(),
)

private fun MediaProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

private fun VoiceProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "voicePreset" to voicePreset,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

private fun OnDeviceSttSnapshot.toMap(): Map<String, Any?> = mapOf(
  "modelPackage" to modelPackage,
  "downloadStatus" to downloadStatus,
)

private fun SettingsSectionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "helperText" to helperText,
  "rows" to rows.map { row -> row.toMap() },
  "segmentedOptions" to segmentedOptions,
  "segmentedIndex" to segmentedIndex,
  "inlinePanelText" to inlinePanelText,
  "backgroundTone" to backgroundTone.wireValue,
)

private fun SettingsRowSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "trailingKind" to trailingKind.wireValue,
  "toggleValue" to toggleValue,
  "valueLabel" to valueLabel,
)

private fun LlmConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "enabled" to enabled,
  "streamingEnabled" to streamingEnabled,
  "providerMode" to providerMode,
  "providerId" to providerId,
  "selectedProviderOptionId" to selectedProviderOptionId,
  "protocol" to protocol,
  "providerOptions" to providerOptions.map { option -> option.toMap() },
  "providerName" to providerName,
  "providerNotes" to providerNotes,
  "baseUrl" to baseUrl,
  "apiKey" to apiKey,
  "model" to model,
  "reasoningEffort" to reasoningEffort,
  "systemPrompt" to systemPrompt,
  "openAiPromptCacheKeyStrategy" to openAiPromptCacheKeyStrategy,
  "openAiPromptCacheRetention" to openAiPromptCacheRetention,
  "anthropicPromptCachingEnabled" to anthropicPromptCachingEnabled,
  "anthropicPromptCacheTtl" to anthropicPromptCacheTtl,
  "contextBudgetPreset" to contextBudgetPreset,
  "contextBudgetReservedOutputTokens" to contextBudgetReservedOutputTokens,
  "contextBudgetSafetyMarginTokens" to contextBudgetSafetyMarginTokens,
  "contextBudgetEffectiveInputPercent" to contextBudgetEffectiveInputPercent,
  "onDeviceModels" to onDeviceModels.map { option -> option.toMap() },
  "selectedOnDeviceModelId" to selectedOnDeviceModelId,
  "onDeviceMaxContextWindow" to onDeviceMaxContextWindow,
  "onDeviceMaxTokens" to onDeviceMaxTokens,
  "onDeviceTopK" to onDeviceTopK,
  "onDeviceTopP" to onDeviceTopP,
  "onDeviceTemperature" to onDeviceTemperature,
  "onDeviceAccelerator" to onDeviceAccelerator,
  "onDeviceThinkingEnabled" to onDeviceThinkingEnabled,
  "onDeviceLiteModeEnabled" to onDeviceLiteModeEnabled,
  "helperText" to helperText,
  "agentCapability" to agentCapability.toMap(),
)

private fun LlmProviderOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "title" to title,
  "subtitle" to subtitle,
  "defaultBaseUrl" to defaultBaseUrl,
  "defaultModel" to defaultModel,
  "protocol" to protocol,
  "apiKey" to apiKey,
  "isCustom" to isCustom,
)

private fun OnDeviceLlmModelOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "subtitle" to subtitle,
  "sizeLabel" to sizeLabel,
  "fileSizeBytes" to fileSizeBytes,
  "installState" to installState,
  "downloadState" to installState,
  "downloadedBytes" to downloadedBytes,
  "downloadBytesPerSecond" to downloadBytesPerSecond,
  "sha256Verified" to sha256Verified,
  "isSelected" to isSelected,
  "lastError" to lastError,
)

private fun LlmValidationResult.toMap(): Map<String, Any?> = mapOf(
  "isSuccess" to isSuccess,
  "message" to message,
  "agentCapability" to agentCapability?.toMap(),
)

private fun LlmAgentCapabilitySnapshot.toMap(): Map<String, Any?> = mapOf(
  "routeFingerprint" to routeFingerprint,
  "verifiedAtEpochMs" to verifiedAtEpochMs,
  "wasVerified" to wasVerified,
  "contextWindowTokens" to contextWindowTokens,
  "visionInputSupported" to visionInputSupported,
  "nativeToolCallingAvailable" to nativeToolCallingAvailable,
  "toolChoiceSupported" to toolChoiceSupported,
  "parallelToolCallsSupported" to parallelToolCallsSupported,
  "strictToolSchemaSupported" to strictToolSchemaSupported,
  "responsesContinuationSupported" to responsesContinuationSupported,
  "builtinWebSearchSupported" to builtinWebSearchSupported,
  "assistantPhaseSupported" to assistantPhaseSupported,
  "citationIncludeSupported" to citationIncludeSupported,
)

private fun PersonalizationConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "introTitle" to introTitle,
  "introBody" to introBody,
  "introHelper" to introHelper,
  "presetsTitle" to presetsTitle,
  "presetsHelper" to presetsHelper,
  "presets" to presets.map { preset -> preset.toMap() },
  "selectedPresetId" to selectedPresetId,
  "customOverlayTitle" to customOverlayTitle,
  "customOverlayHelper" to customOverlayHelper,
  "customLabelHint" to customLabelHint,
  "customLabelHelper" to customLabelHelper,
  "customGuidanceHint" to customGuidanceHint,
  "customGuidanceHelper" to customGuidanceHelper,
  "customLabel" to customLabel,
  "customGuidance" to customGuidance,
  "behaviorDefaultsTitle" to behaviorDefaultsTitle,
  "appLanguageTitle" to appLanguageTitle,
  "appLanguageOptions" to appLanguageOptions.map { option -> option.toMap() },
  "selectedAppLanguageId" to selectedAppLanguageId,
  "livePreviewTitle" to livePreviewTitle,
  "livePreviewName" to livePreviewName,
  "livePreviewSummary" to livePreviewSummary,
  "queueTitle" to queueTitle,
  "queueBody" to queueBody,
  "queueIsIdle" to queueIsIdle,
  "lastResetTitle" to lastResetTitle,
  "lastResetMessage" to lastResetMessage,
  "resetActions" to resetActions.map { action -> action.toMap() },
)

private fun PersonalizationPresetSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "summary" to summary,
  "voice" to voice,
  "status" to status,
  "isSelected" to isSelected,
)

private fun PersonalizationLanguageOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "isSelected" to isSelected,
)

private fun PersonalizationResetActionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "scopeId" to scope.wireValue,
  "title" to title,
  "scopeBody" to scopeBody,
  "retainBody" to retainBody,
  "confirmationToken" to confirmationToken,
  "inputHint" to inputHint,
  "disabledGuidance" to disabledGuidance,
  "typeExactGuidance" to typeExactGuidance,
  "armedGuidance" to armedGuidance,
  "isInputEnabled" to isInputEnabled,
)

private fun McpSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "masterTitle" to masterTitle,
  "masterSummary" to masterSummary,
  "masterEnabled" to masterEnabled,
  "summaryLine" to summaryLine,
  "serversTitle" to serversTitle,
  "serversHelper" to serversHelper,
  "masterDisabledTitle" to masterDisabledTitle,
  "masterDisabledBody" to masterDisabledBody,
  "servers" to servers.map { server -> server.toMap() },
)

private fun McpServerSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "statusLabel" to statusLabel,
  "statusTone" to statusTone,
  "trustLine" to trustLine,
  "authLine" to authLine,
  "readinessLine" to readinessLine,
  "transportLine" to transportLine,
  "exposureLine" to exposureLine,
  "guidance" to guidance,
  "actionLabel" to actionLabel,
  "actionTurnsOn" to actionTurnsOn,
  "isActionEnabled" to isActionEnabled,
)

private fun SafetySettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "automationModeId" to automationMode.wireValue,
  "rollbackJournalEnabled" to rollbackJournalEnabled,
  "maxFilesPerBatch" to maxFilesPerBatch,
  "maxAgentTurns" to maxAgentTurns,
  "maxToolCalls" to maxToolCalls,
  "undoWindowHours" to undoWindowHours,
  "fileChangesPolicyId" to fileChangesPolicy.wireValue,
  "fileDeletesPolicyId" to fileDeletesPolicy.wireValue,
  "shellCommandsPolicyId" to shellCommandsPolicy.wireValue,
  "externalAccessModeId" to externalAccessMode.wireValue,
  "locations" to locations.map { location -> location.toMap() },
  "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
  "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
  "liveContextModeId" to liveContextMode.wireValue,
  "memoryToolsEnabled" to memoryToolsEnabled,
)

private fun SafetySettingsLocationSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "enabled" to enabled,
)
