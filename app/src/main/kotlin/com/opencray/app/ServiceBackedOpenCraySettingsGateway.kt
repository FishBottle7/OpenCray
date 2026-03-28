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
      observeConnectionState = serviceClient::observeConnectionState,
      observe = { gateway, callback -> gateway.observeSettingsOverview(callback) },
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
    currentReadGateway().loadSettingsDetail(routeIdRaw)

  override fun loadNotificationSettings(): Map<String, Any?> =
    currentReadGateway().loadNotificationSettings()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    currentWriteGateway("saveNotificationSettings").saveNotificationSettings(payload)

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
    currentReadGateway().loadStrongBackgroundSnapshot()

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    currentReadGateway().performStrongBackgroundAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    currentReadGateway().loadNetworkSearchConfig()

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
    currentWriteGateway("saveNetworkSearchConfig").saveNetworkSearchConfig(slots)

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    currentReadGateway().loadMediaSpeechConfig()

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
    currentWriteGateway("saveMediaSpeechConfig").saveMediaSpeechConfig(payload)

  override fun loadSandboxSettings(): Map<String, Any?> =
    currentReadGateway().loadSandboxSettings()

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
    currentWriteGateway("saveSandboxSettings").saveSandboxSettings(payload)

  override fun loadLlmConfig(): Map<String, Any?> =
    currentReadGateway().loadLlmConfig()

  override fun saveLlmConfig(
    enabled: Boolean,
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
  ): Map<String, Any?> = currentWriteGateway("saveLlmConfig").saveLlmConfig(
    enabled = enabled,
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
  ): Map<String, Any?> = currentWriteGateway("saveCustomLlmProvider").saveCustomLlmProvider(
    selectedProviderOptionId = selectedProviderOptionId,
    protocol = protocol,
    providerName = providerName,
    providerNotes = providerNotes,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
    systemPrompt = systemPrompt,
  )

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = currentWriteGateway("validateLlmConfig").validateLlmConfig(
    providerId = providerId,
    protocol = protocol,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
  )

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    currentReadGateway().loadPersonalizationConfig()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> = currentWriteGateway("savePersonalizationConfig").savePersonalizationConfig(
    presetId = presetId,
    customLabel = customLabel,
    customGuidance = customGuidance,
  )

  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    currentWriteGateway("setAppLanguage").setAppLanguage(languageId)

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
    currentWriteGateway("runPersonalizationReset").runPersonalizationReset(scopeId)

  override fun loadMcpSettings(): Map<String, Any?> =
    currentReadGateway().loadMcpSettings()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    currentWriteGateway("setMcpMasterEnabled").setMcpMasterEnabled(enabled)

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = currentWriteGateway("setMcpServerEnabled").setMcpServerEnabled(serverId, enabled)

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
  ): Map<String, Any?> = currentWriteGateway("saveSafetySettings").saveSafetySettings(
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
  )

  private fun currentReadGateway(): OpenCraySettingsGateway =
    serviceClient.loadSettingsGateway() ?: fallbackGateway

  private fun currentWriteGateway(operation: String): OpenCraySettingsGateway =
    requireBinderBackedGateway(
      surface = "Settings",
      operation = operation,
      gateway = serviceClient.loadSettingsGateway(),
      connectionState = serviceClient.loadConnectionState(),
    )
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
