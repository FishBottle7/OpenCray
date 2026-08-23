part of 'opencray_seed_bridge.dart';

mixin _SeedBridgePersonalizationDomain on _SeedBridgeDeps {
  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async => _personalizationConfig;

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async {
    _personalizationConfig = _copySeedPersonalizationConfig(
      _personalizationConfig,
      presetId: presetId,
      customLabel: customLabel,
      customGuidance: customGuidance,
      lastResetMessage: '',
    );
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async {
    _personalizationConfig = _copySeedPersonalizationConfig(
      _personalizationConfig,
      selectedAppLanguageId: languageId,
    );
    _llmConfig = OpenCrayLlmConfigSnapshot(
      localeTag: languageId,
      enabled: _llmConfig.enabled,
      streamingEnabled: _llmConfig.streamingEnabled,
      providerId: _llmConfig.providerId,
      selectedProviderOptionId: _llmConfig.selectedProviderOptionId,
      protocol: _llmConfig.protocol,
      providerOptions: _llmConfig.providerOptions,
      providerName: _llmConfig.providerName,
      providerNotes: _llmConfig.providerNotes,
      baseUrl: _llmConfig.baseUrl,
      apiKey: _llmConfig.apiKey,
      model: _llmConfig.model,
      reasoningEffort: _llmConfig.reasoningEffort,
      systemPrompt: _llmConfig.systemPrompt,
      helperText: _llmConfig.helperText,
      openAiPromptCacheKeyStrategy: _llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: _llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: _llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: _llmConfig.anthropicPromptCacheTtl,
      onDeviceModels: _llmConfig.onDeviceModels,
      selectedOnDeviceModelId: _llmConfig.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: _llmConfig.onDeviceMaxContextWindow,
      onDeviceMaxTokens: _llmConfig.onDeviceMaxTokens,
      onDeviceTopK: _llmConfig.onDeviceTopK,
      onDeviceTopP: _llmConfig.onDeviceTopP,
      onDeviceTemperature: _llmConfig.onDeviceTemperature,
      onDeviceAccelerator: _llmConfig.onDeviceAccelerator,
      onDeviceThinkingEnabled: _llmConfig.onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: _llmConfig.onDeviceLiteModeEnabled,
      contextBudgetPreset: _llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens:
          _llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens:
          _llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent:
          _llmConfig.contextBudgetEffectiveInputPercent,
    );
    _snapshot = _snapshot.copyWith(localeTag: languageId);
    update(_snapshot);
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async {
    switch (scopeId) {
      case 'memory':
        _personalizationConfig = _copySeedPersonalizationConfig(
          _personalizationConfig,
          queueBody:
              'Seed mode cleared local memory cues and left the rest of the device state intact.',
          queueIsIdle: true,
          lastResetTitle: 'Last reset',
          lastResetMessage:
              'Cleared the app-local memory and history stores. The soul profile, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata were left untouched.',
        );
        break;
      case 'soul':
        _personalizationConfig = _copySeedPersonalizationConfig(
          _personalizationConfig,
          presetId: 'steady',
          customLabel: '',
          customGuidance: '',
          queueBody:
              'Seed mode restored the default voice and cleared the custom overlay.',
          queueIsIdle: true,
          lastResetTitle: 'Last reset',
          lastResetMessage:
              'Cleared the local personality and soul profile and reset the editor to defaults. Memory and history stores, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata were left untouched.',
        );
        break;
      default:
        throw ArgumentError.value(
          scopeId,
          'scopeId',
          'Unsupported personalization reset id.',
        );
    }
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

  @override
  Future<OpenCrayTwinImportSourceProbeSnapshot> probeTwinImportSource(
    String filePath,
  ) async => OpenCrayTwinImportSourceProbeSnapshot(
    filePath: filePath,
    fileName: filePath.split(RegExp(r'[\\\/]+')).isEmpty
        ? filePath
        : filePath.split(RegExp(r'[\\\/]+')).last,
    fileExtension: filePath.contains('.')
        ? filePath.split('.').last.toLowerCase()
        : '',
    sourceMode: null,
    formatKey: 'seed_mode',
    formatLabel: 'Seed mode probe',
    confidence: 'low',
    usesExistingImporter: false,
    needsManualSelection: true,
    notes: const <String>[
      'Seed bridge does not inspect local corpus files. Wire this call to the Android host or local runtime bridge.',
    ],
  );
}
