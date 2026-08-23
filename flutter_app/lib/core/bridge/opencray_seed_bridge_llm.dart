part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeLlmDomain on _SeedBridgeDeps {
  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async => _llmConfig;

  @override
  Future<OpenCrayLlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    bool? streamingEnabled,
    String providerMode = 'cloud',
    required String providerId,
    required String selectedProviderOptionId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    String selectedOnDeviceModelId = 'gemma-4-e2b-it',
    int onDeviceMaxContextWindow = 32768,
    int onDeviceMaxTokens = 4096,
    int onDeviceTopK = 40,
    double onDeviceTopP = 0.95,
    double onDeviceTemperature = 0.70,
    String onDeviceAccelerator = 'gpu',
    bool onDeviceThinkingEnabled = false,
    bool onDeviceLiteModeEnabled = false,
    String? contextBudgetPreset,
    int? contextBudgetReservedOutputTokens,
    int? contextBudgetSafetyMarginTokens,
    double? contextBudgetEffectiveInputPercent,
    int? contextWindowTokensOverride,
  }) async {
    final isConfigured = providerMode == 'on_device_model'
        ? _llmConfig.onDeviceModels.any(
            (option) =>
                option.id == selectedOnDeviceModelId &&
                option.installState.trim().toLowerCase() == 'ready',
          )
        : (baseUrl.trim().isNotEmpty &&
              apiKey.trim().isNotEmpty &&
              model.trim().isNotEmpty);
    final hasExplicitContextBudgetPayload = contextBudgetPreset != null;
    _llmConfig = OpenCrayLlmConfigSnapshot(
      localeTag: _llmConfig.localeTag,
      enabled: isConfigured,
      streamingEnabled: streamingEnabled ?? _llmConfig.streamingEnabled,
      providerMode: providerMode,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerOptions: _llmConfig.providerOptions,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: _llmConfig.helperText,
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          _llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? _llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          _llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? _llmConfig.anthropicPromptCacheTtl,
      manualContextWindowTokens:
          contextWindowTokensOverride ?? _llmConfig.manualContextWindowTokens,
      resolvedContextWindowTokens:
          contextWindowTokensOverride ??
          _llmConfig.resolvedContextWindowTokens,
      onDeviceModels: _llmConfig.onDeviceModels,
      selectedOnDeviceModelId: selectedOnDeviceModelId,
      onDeviceMaxContextWindow: onDeviceMaxContextWindow,
      onDeviceMaxTokens: onDeviceMaxTokens,
      onDeviceTopK: onDeviceTopK,
      onDeviceTopP: onDeviceTopP,
      onDeviceTemperature: onDeviceTemperature,
      onDeviceAccelerator: onDeviceAccelerator,
      onDeviceThinkingEnabled: onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: onDeviceLiteModeEnabled,
      contextBudgetPreset:
          contextBudgetPreset ?? _llmConfig.contextBudgetPreset,
      contextBudgetReservedOutputTokens: hasExplicitContextBudgetPayload
          ? contextBudgetReservedOutputTokens
          : _llmConfig.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: hasExplicitContextBudgetPayload
          ? contextBudgetSafetyMarginTokens
          : _llmConfig.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent: hasExplicitContextBudgetPayload
          ? contextBudgetEffectiveInputPercent
          : _llmConfig.contextBudgetEffectiveInputPercent,
    );
    return _llmConfig;
  }

  @override
  Future<OpenCrayLlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    bool? streamingEnabled,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    int? contextWindowTokensOverride,
  }) async {
    final providerOptionId =
        selectedProviderOptionId.trim().isEmpty ||
            selectedProviderOptionId == 'custom'
        ? 'saved-custom-${DateTime.now().millisecondsSinceEpoch}'
        : selectedProviderOptionId;
    final savedOption = OpenCrayLlmProviderOptionSnapshot(
      id: providerOptionId,
      providerId: 'custom',
      title: providerName.trim().isEmpty
          ? 'Custom provider'
          : providerName.trim(),
      subtitle: providerNotes.trim(),
      defaultBaseUrl: baseUrl.trim(),
      defaultModel: model.trim(),
      protocol: protocol.trim().isEmpty ? 'openai' : protocol.trim(),
      apiKey: apiKey.trim(),
      isCustom: true,
    );
    final providerOptions = <OpenCrayLlmProviderOptionSnapshot>[
      for (final option in _llmConfig.providerOptions)
        if (option.id != providerOptionId) option,
      savedOption,
    ];
    _llmConfig = OpenCrayLlmConfigSnapshot(
      localeTag: _llmConfig.localeTag,
      enabled:
          baseUrl.trim().isNotEmpty &&
          apiKey.trim().isNotEmpty &&
          model.trim().isNotEmpty,
      streamingEnabled: streamingEnabled ?? _llmConfig.streamingEnabled,
      providerMode: 'cloud',
      providerId: 'custom',
      selectedProviderOptionId: providerOptionId,
      protocol: savedOption.protocol,
      providerOptions: providerOptions,
      providerName: savedOption.title,
      providerNotes: savedOption.subtitle,
      baseUrl: savedOption.defaultBaseUrl,
      apiKey: savedOption.apiKey,
      model: savedOption.defaultModel,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: _llmConfig.helperText,
      openAiPromptCacheKeyStrategy:
          openAiPromptCacheKeyStrategy ??
          _llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention:
          openAiPromptCacheRetention ?? _llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled:
          anthropicPromptCachingEnabled ??
          _llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl:
          anthropicPromptCacheTtl ?? _llmConfig.anthropicPromptCacheTtl,
      manualContextWindowTokens:
          contextWindowTokensOverride ?? _llmConfig.manualContextWindowTokens,
      resolvedContextWindowTokens:
          contextWindowTokensOverride ??
          _llmConfig.resolvedContextWindowTokens,
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
    return _llmConfig;
  }

  @override
  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    int? contextWindowTokensOverride,
  }) async => const OpenCrayLlmValidationResult(
    isSuccess: false,
    message: 'Seed bridge does not support live model validation.',
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async {
    _llmConfig = _llmConfig.copyWith(
      onDeviceModels: _llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? OpenCrayOnDeviceLlmModelOptionSnapshot(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'ready',
                    downloadedBytes: option.fileSizeBytes,
                    downloadBytesPerSecond: 0,
                    sha256Verified: true,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return _llmConfig;
  }

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => _llmConfig;

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async {
    _llmConfig = _llmConfig.copyWith(
      onDeviceModels: _llmConfig.onDeviceModels
          .map(
            (option) => option.id == modelId
                ? OpenCrayOnDeviceLlmModelOptionSnapshot(
                    id: option.id,
                    title: option.title,
                    subtitle: option.subtitle,
                    sizeLabel: option.sizeLabel,
                    fileSizeBytes: option.fileSizeBytes,
                    installState: 'not_downloaded',
                    downloadedBytes: 0,
                    downloadBytesPerSecond: 0,
                    sha256Verified: false,
                    isSelected: option.isSelected,
                    lastError: null,
                  )
                : option,
          )
          .toList(growable: false),
    );
    return _llmConfig;
  }
}
