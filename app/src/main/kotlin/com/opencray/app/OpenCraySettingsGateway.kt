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
  ): Map<String, Any?>

  fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
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
