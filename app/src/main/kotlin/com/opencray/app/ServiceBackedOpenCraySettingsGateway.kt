package com.opencray.app

import android.content.Context

internal class ServiceBackedOpenCraySettingsGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  private val fallbackGateway: OpenCraySettingsGateway,
) : OpenCraySettingsGateway {
  override fun loadSettingsOverview(): Map<String, Any?> =
    currentReadGateway().loadSettingsOverview()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentReadGateway,
      observeConnectionState = serviceClient::observePassiveConnectionState,
      observe = { gateway, callback -> gateway.observeSettingsOverview(callback) },
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
    currentReadGateway().loadSettingsDetail(routeIdRaw)

  override fun loadNotificationSettings(): Map<String, Any?> =
    currentReadGateway().loadNotificationSettings()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "saveNotificationSettings",
      command = OpenCraySettingsWriteCommand.SaveNotificationSettings(payload),
    )

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
    currentReadGateway().loadStrongBackgroundSnapshot()

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "performStrongBackgroundAction",
      command = OpenCraySettingsWriteCommand.PerformStrongBackgroundAction(actionId),
    )

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    currentReadGateway().loadNetworkSearchConfig()

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "saveNetworkSearchConfig",
      command = OpenCraySettingsWriteCommand.SaveNetworkSearchConfig(slots),
    )

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    currentReadGateway().loadMediaSpeechConfig()

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "saveMediaSpeechConfig",
      command = OpenCraySettingsWriteCommand.SaveMediaSpeechConfig(payload),
    )

  override fun loadSandboxSettings(): Map<String, Any?> =
    currentReadGateway().loadSandboxSettings()

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "saveSandboxSettings",
      command = OpenCraySettingsWriteCommand.SaveSandboxSettings(payload),
    )

  override fun loadLlmConfig(): Map<String, Any?> =
    currentReadGateway().loadLlmConfig()

  override fun saveLlmConfig(
    enabled: Boolean,
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
    selectedOnDeviceModelId: String,
    onDeviceMaxContextWindow: Int,
    onDeviceMaxTokens: Int,
    onDeviceTopK: Int,
    onDeviceTopP: Double,
    onDeviceTemperature: Double,
    onDeviceAccelerator: String,
    onDeviceThinkingEnabled: Boolean,
    onDeviceLiteModeEnabled: Boolean,
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "saveLlmConfig",
    command = OpenCraySettingsWriteCommand.SaveLlmConfig(
      enabled = enabled,
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
      selectedOnDeviceModelId = selectedOnDeviceModelId,
      onDeviceMaxContextWindow = onDeviceMaxContextWindow,
      onDeviceMaxTokens = onDeviceMaxTokens,
      onDeviceTopK = onDeviceTopK,
      onDeviceTopP = onDeviceTopP,
      onDeviceTemperature = onDeviceTemperature,
      onDeviceAccelerator = onDeviceAccelerator,
      onDeviceThinkingEnabled = onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
    ),
  )

  override fun saveCustomLlmProvider(
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
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "saveCustomLlmProvider",
    command = OpenCraySettingsWriteCommand.SaveCustomLlmProvider(
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
    ),
  )

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "validateLlmConfig",
    command = OpenCraySettingsWriteCommand.ValidateLlmConfig(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
    ),
  )

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "downloadOnDeviceLlmModel",
      command = OpenCraySettingsWriteCommand.DownloadOnDeviceLlmModel(modelId),
    )

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "cancelOnDeviceLlmModelDownload",
      command = OpenCraySettingsWriteCommand.CancelOnDeviceLlmModelDownload(modelId),
    )

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "deleteOnDeviceLlmModel",
      command = OpenCraySettingsWriteCommand.DeleteOnDeviceLlmModel(modelId),
    )

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    currentReadGateway().loadPersonalizationConfig()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "savePersonalizationConfig",
    command = OpenCraySettingsWriteCommand.SavePersonalizationConfig(
      presetId = presetId,
      customLabel = customLabel,
      customGuidance = customGuidance,
    ),
  )

  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "setAppLanguage",
      command = OpenCraySettingsWriteCommand.SetAppLanguage(languageId),
    )

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "runPersonalizationReset",
      command = OpenCraySettingsWriteCommand.RunPersonalizationReset(scopeId),
    )

  override fun loadMcpSettings(): Map<String, Any?> =
    currentReadGateway().loadMcpSettings()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    dispatchPayloadWriteCommand(
      operation = "setMcpMasterEnabled",
      command = OpenCraySettingsWriteCommand.SetMcpMasterEnabled(enabled),
    )

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "setMcpServerEnabled",
    command = OpenCraySettingsWriteCommand.SetMcpServerEnabled(
      serverId = serverId,
      enabled = enabled,
    ),
  )

  override fun loadSafetySettings(): Map<String, Any?> =
    currentReadGateway().loadSafetySettings()

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
  ): Map<String, Any?> = dispatchPayloadWriteCommand(
    operation = "saveSafetySettings",
    command = OpenCraySettingsWriteCommand.SaveSafetySettings(
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
    ),
  )

  private fun currentReadGateway(): OpenCraySettingsGateway =
    serviceClient.peekSettingsGateway() ?: fallbackGateway

  private fun dispatchWriteCommand(
    operation: String,
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult =
    requireBinderBackedGateway(
      surface = "Settings",
      operation = operation,
      gateway = serviceClient.dispatchSettingsWriteCommand(command),
      connectionState = serviceClient.loadConnectionState(),
    )

  private fun dispatchPayloadWriteCommand(
    operation: String,
    command: OpenCraySettingsWriteCommand,
  ): Map<String, Any?> = dispatchWriteCommand(
    operation = operation,
    command = command,
  ).payloadOrNull()
}

private fun OpenCraySettingsWriteDispatchResult.payloadOrNull(): Map<String, Any?> = when (this) {
  is OpenCraySettingsWriteDispatchResult.Payload -> value
}

internal fun serviceBackedOpenCraySettingsGateway(
  context: Context,
): OpenCraySettingsGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayAgentRuntimeService.ensureClient(appContext)
  return ServiceBackedOpenCraySettingsGateway(
    serviceClient = serviceClient,
    fallbackGateway = projectionOnlyOpenCraySettingsGateway(
      context = appContext,
      connectionStateProvider = serviceClient::loadConnectionState,
    ),
  )
}

internal fun serviceBackedOpenCraySettingsGateway(
  context: Context,
  fallbackGateway: OpenCraySettingsGateway,
): OpenCraySettingsGateway = ServiceBackedOpenCraySettingsGateway(
  serviceClient = OpenCrayAgentRuntimeService.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
