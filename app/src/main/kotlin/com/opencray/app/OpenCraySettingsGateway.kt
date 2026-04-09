package com.opencray.app

internal interface OpenCraySettingsGateway {
  fun loadSettingsOverview(): Map<String, Any?>

  fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?>

  fun loadNotificationSettings(): Map<String, Any?>

  fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?>

  fun loadStrongBackgroundSnapshot(): Map<String, Any?>

  fun performStrongBackgroundAction(actionId: String): Map<String, Any?>

  fun loadNetworkSearchConfig(): Map<String, Any?>

  fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?>

  fun loadMediaSpeechConfig(): Map<String, Any?>

  fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?>

  fun loadSandboxSettings(): Map<String, Any?>

  fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?>

  fun loadLlmConfig(): Map<String, Any?>

  fun saveLlmConfig(
    enabled: Boolean,
    streamingEnabled: Boolean? = null,
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
    openAiPromptCacheKeyStrategy: String? = null,
    openAiPromptCacheRetention: String? = null,
    anthropicPromptCachingEnabled: Boolean? = null,
    anthropicPromptCacheTtl: String? = null,
  ): Map<String, Any?>

  fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    streamingEnabled: Boolean? = null,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String? = null,
    openAiPromptCacheRetention: String? = null,
    anthropicPromptCachingEnabled: Boolean? = null,
    anthropicPromptCacheTtl: String? = null,
  ): Map<String, Any?>

  fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?>

  fun loadPersonalizationConfig(): Map<String, Any?>

  fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?>

  fun setAppLanguage(languageId: String): Map<String, Any?>

  fun runPersonalizationReset(scopeId: String): Map<String, Any?>

  fun loadMcpSettings(): Map<String, Any?>

  fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?>

  fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?>

  fun loadSafetySettings(): Map<String, Any?>

  fun saveSafetySettings(
    automationModeId: String,
    rollbackJournalEnabled: Boolean,
    maxFilesPerBatch: Int,
    maxAgentTurns: Int = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
    maxToolCalls: Int = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
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
    liveContextModeId: String = LiveContextMode.FULL.wireValue,
    memoryToolsEnabled: Boolean = true,
  ): Map<String, Any?>
}

internal sealed interface OpenCraySettingsWriteCommand {
  data class SaveNotificationSettings(
    val payload: Map<String, Any?>,
  ) : OpenCraySettingsWriteCommand

  data class PerformStrongBackgroundAction(
    val actionId: String,
  ) : OpenCraySettingsWriteCommand

  data class SaveNetworkSearchConfig(
    val slots: List<Map<String, Any?>>,
  ) : OpenCraySettingsWriteCommand

  data class SaveMediaSpeechConfig(
    val payload: Map<String, Any?>,
  ) : OpenCraySettingsWriteCommand

  data class SaveSandboxSettings(
    val payload: Map<String, Any?>,
  ) : OpenCraySettingsWriteCommand

  data class SaveLlmConfig(
    val enabled: Boolean,
    val streamingEnabled: Boolean? = null,
    val providerId: String,
    val selectedProviderOptionId: String,
    val protocol: String,
    val providerName: String,
    val providerNotes: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String,
    val systemPrompt: String,
    val openAiPromptCacheKeyStrategy: String? = null,
    val openAiPromptCacheRetention: String? = null,
    val anthropicPromptCachingEnabled: Boolean? = null,
    val anthropicPromptCacheTtl: String? = null,
  ) : OpenCraySettingsWriteCommand

  data class SaveCustomLlmProvider(
    val selectedProviderOptionId: String,
    val streamingEnabled: Boolean? = null,
    val protocol: String,
    val providerName: String,
    val providerNotes: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String,
    val systemPrompt: String,
    val openAiPromptCacheKeyStrategy: String? = null,
    val openAiPromptCacheRetention: String? = null,
    val anthropicPromptCachingEnabled: Boolean? = null,
    val anthropicPromptCacheTtl: String? = null,
  ) : OpenCraySettingsWriteCommand

  data class ValidateLlmConfig(
    val providerId: String,
    val protocol: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String,
  ) : OpenCraySettingsWriteCommand

  data class SavePersonalizationConfig(
    val presetId: String,
    val customLabel: String,
    val customGuidance: String,
  ) : OpenCraySettingsWriteCommand

  data class SetAppLanguage(
    val languageId: String,
  ) : OpenCraySettingsWriteCommand

  data class RunPersonalizationReset(
    val scopeId: String,
  ) : OpenCraySettingsWriteCommand

  data class SetMcpMasterEnabled(
    val enabled: Boolean,
  ) : OpenCraySettingsWriteCommand

  data class SetMcpServerEnabled(
    val serverId: String,
    val enabled: Boolean,
  ) : OpenCraySettingsWriteCommand

  data class SaveSafetySettings(
    val automationModeId: String,
    val rollbackJournalEnabled: Boolean,
    val maxFilesPerBatch: Int,
    val maxAgentTurns: Int,
    val maxToolCalls: Int,
    val undoWindowHours: Int,
    val fileChangesPolicyId: String,
    val fileDeletesPolicyId: String,
    val shellCommandsPolicyId: String,
    val externalAccessModeId: String,
    val photoLibraryEnabled: Boolean,
    val downloadsEnabled: Boolean,
    val documentsEnabled: Boolean,
    val recordingsEnabled: Boolean,
    val workspaceAccessProfileId: String,
    val readOnlyOutsideWorkspace: Boolean,
    val liveContextModeId: String,
    val memoryToolsEnabled: Boolean,
  ) : OpenCraySettingsWriteCommand
}

internal sealed interface OpenCraySettingsWriteDispatchResult {
  data class Payload(
    val value: Map<String, Any?>,
  ) : OpenCraySettingsWriteDispatchResult
}

internal fun OpenCraySettingsGateway.dispatchSettingsWriteCommand(
  command: OpenCraySettingsWriteCommand,
): OpenCraySettingsWriteDispatchResult = when (command) {
  is OpenCraySettingsWriteCommand.SaveNotificationSettings -> OpenCraySettingsWriteDispatchResult.Payload(
    saveNotificationSettings(command.payload),
  )

  is OpenCraySettingsWriteCommand.PerformStrongBackgroundAction -> OpenCraySettingsWriteDispatchResult.Payload(
    performStrongBackgroundAction(command.actionId),
  )

  is OpenCraySettingsWriteCommand.SaveNetworkSearchConfig -> OpenCraySettingsWriteDispatchResult.Payload(
    saveNetworkSearchConfig(command.slots),
  )

  is OpenCraySettingsWriteCommand.SaveMediaSpeechConfig -> OpenCraySettingsWriteDispatchResult.Payload(
    saveMediaSpeechConfig(command.payload),
  )

  is OpenCraySettingsWriteCommand.SaveSandboxSettings -> OpenCraySettingsWriteDispatchResult.Payload(
    saveSandboxSettings(command.payload),
  )

  is OpenCraySettingsWriteCommand.SaveLlmConfig -> OpenCraySettingsWriteDispatchResult.Payload(
    saveLlmConfig(
      enabled = command.enabled,
      streamingEnabled = command.streamingEnabled,
      providerId = command.providerId,
      selectedProviderOptionId = command.selectedProviderOptionId,
      protocol = command.protocol,
      providerName = command.providerName,
      providerNotes = command.providerNotes,
      baseUrl = command.baseUrl,
      apiKey = command.apiKey,
      model = command.model,
      reasoningEffort = command.reasoningEffort,
      systemPrompt = command.systemPrompt,
      openAiPromptCacheKeyStrategy = command.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = command.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = command.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = command.anthropicPromptCacheTtl,
    ),
  )

  is OpenCraySettingsWriteCommand.SaveCustomLlmProvider -> OpenCraySettingsWriteDispatchResult.Payload(
    saveCustomLlmProvider(
      selectedProviderOptionId = command.selectedProviderOptionId,
      streamingEnabled = command.streamingEnabled,
      protocol = command.protocol,
      providerName = command.providerName,
      providerNotes = command.providerNotes,
      baseUrl = command.baseUrl,
      apiKey = command.apiKey,
      model = command.model,
      reasoningEffort = command.reasoningEffort,
      systemPrompt = command.systemPrompt,
      openAiPromptCacheKeyStrategy = command.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = command.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = command.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = command.anthropicPromptCacheTtl,
    ),
  )

  is OpenCraySettingsWriteCommand.ValidateLlmConfig -> OpenCraySettingsWriteDispatchResult.Payload(
    validateLlmConfig(
      providerId = command.providerId,
      protocol = command.protocol,
      baseUrl = command.baseUrl,
      apiKey = command.apiKey,
      model = command.model,
      reasoningEffort = command.reasoningEffort,
    ),
  )

  is OpenCraySettingsWriteCommand.SavePersonalizationConfig -> OpenCraySettingsWriteDispatchResult.Payload(
    savePersonalizationConfig(
      presetId = command.presetId,
      customLabel = command.customLabel,
      customGuidance = command.customGuidance,
    ),
  )

  is OpenCraySettingsWriteCommand.SetAppLanguage -> OpenCraySettingsWriteDispatchResult.Payload(
    setAppLanguage(command.languageId),
  )

  is OpenCraySettingsWriteCommand.RunPersonalizationReset -> OpenCraySettingsWriteDispatchResult.Payload(
    runPersonalizationReset(command.scopeId),
  )

  is OpenCraySettingsWriteCommand.SetMcpMasterEnabled -> OpenCraySettingsWriteDispatchResult.Payload(
    setMcpMasterEnabled(command.enabled),
  )

  is OpenCraySettingsWriteCommand.SetMcpServerEnabled -> OpenCraySettingsWriteDispatchResult.Payload(
    setMcpServerEnabled(
      serverId = command.serverId,
      enabled = command.enabled,
    ),
  )

  is OpenCraySettingsWriteCommand.SaveSafetySettings -> OpenCraySettingsWriteDispatchResult.Payload(
    saveSafetySettings(
      automationModeId = command.automationModeId,
      rollbackJournalEnabled = command.rollbackJournalEnabled,
      maxFilesPerBatch = command.maxFilesPerBatch,
      maxAgentTurns = command.maxAgentTurns,
      maxToolCalls = command.maxToolCalls,
      undoWindowHours = command.undoWindowHours,
      fileChangesPolicyId = command.fileChangesPolicyId,
      fileDeletesPolicyId = command.fileDeletesPolicyId,
      shellCommandsPolicyId = command.shellCommandsPolicyId,
      externalAccessModeId = command.externalAccessModeId,
      photoLibraryEnabled = command.photoLibraryEnabled,
      downloadsEnabled = command.downloadsEnabled,
      documentsEnabled = command.documentsEnabled,
      recordingsEnabled = command.recordingsEnabled,
      workspaceAccessProfileId = command.workspaceAccessProfileId,
      readOnlyOutsideWorkspace = command.readOnlyOutsideWorkspace,
      liveContextModeId = command.liveContextModeId,
      memoryToolsEnabled = command.memoryToolsEnabled,
    ),
  )
}
