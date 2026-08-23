part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeLlmDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _invokeMap('loadLlmConfig'));

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
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'saveLlmConfig',
      arguments: <String, Object?>{
        'enabled': enabled,
        if (streamingEnabled != null) 'streamingEnabled': streamingEnabled,
        'providerMode': providerMode,
        'providerId': providerId,
        'selectedProviderOptionId': selectedProviderOptionId,
        'protocol': protocol,
        'providerName': providerName,
        'providerNotes': providerNotes,
        'baseUrl': baseUrl,
        'apiKey': apiKey,
        'model': model,
        'reasoningEffort': reasoningEffort,
        'systemPrompt': systemPrompt,
        if (openAiPromptCacheKeyStrategy != null)
          'openAiPromptCacheKeyStrategy': openAiPromptCacheKeyStrategy,
        if (openAiPromptCacheRetention != null)
          'openAiPromptCacheRetention': openAiPromptCacheRetention,
        if (anthropicPromptCachingEnabled != null)
          'anthropicPromptCachingEnabled': anthropicPromptCachingEnabled,
        if (anthropicPromptCacheTtl != null)
          'anthropicPromptCacheTtl': anthropicPromptCacheTtl,
        'selectedOnDeviceModelId': selectedOnDeviceModelId,
        'onDeviceMaxContextWindow': onDeviceMaxContextWindow,
        'onDeviceMaxTokens': onDeviceMaxTokens,
        'onDeviceTopK': onDeviceTopK,
        'onDeviceTopP': onDeviceTopP,
        'onDeviceTemperature': onDeviceTemperature,
        'onDeviceAccelerator': onDeviceAccelerator,
        'onDeviceThinkingEnabled': onDeviceThinkingEnabled,
        'onDeviceLiteModeEnabled': onDeviceLiteModeEnabled,
        'contextBudgetPreset': contextBudgetPreset,
        'contextBudgetReservedOutputTokens': contextBudgetReservedOutputTokens,
        'contextBudgetSafetyMarginTokens': contextBudgetSafetyMarginTokens,
        'contextBudgetEffectiveInputPercent':
            contextBudgetEffectiveInputPercent,
        if (contextWindowTokensOverride != null)
          'contextWindowTokensOverride': contextWindowTokensOverride,
      },
    ),
  );

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
  }) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'saveCustomLlmProvider',
      arguments: <String, Object?>{
        'selectedProviderOptionId': selectedProviderOptionId,
        if (streamingEnabled != null) 'streamingEnabled': streamingEnabled,
        'protocol': protocol,
        'providerName': providerName,
        'providerNotes': providerNotes,
        'baseUrl': baseUrl,
        'apiKey': apiKey,
        'model': model,
        'reasoningEffort': reasoningEffort,
        'systemPrompt': systemPrompt,
        if (openAiPromptCacheKeyStrategy != null)
          'openAiPromptCacheKeyStrategy': openAiPromptCacheKeyStrategy,
        if (openAiPromptCacheRetention != null)
          'openAiPromptCacheRetention': openAiPromptCacheRetention,
        if (anthropicPromptCachingEnabled != null)
          'anthropicPromptCachingEnabled': anthropicPromptCachingEnabled,
        if (anthropicPromptCacheTtl != null)
          'anthropicPromptCacheTtl': anthropicPromptCacheTtl,
        if (contextWindowTokensOverride != null)
          'contextWindowTokensOverride': contextWindowTokensOverride,
      },
    ),
  );

  @override
  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    int? contextWindowTokensOverride,
  }) async => OpenCrayLlmValidationResult.fromMap(
    await _invokeMap(
      'validateLlmConfig',
      arguments: <String, Object?>{
        'providerId': providerId,
        'protocol': protocol,
        'baseUrl': baseUrl,
        'apiKey': apiKey,
        'model': model,
        'reasoningEffort': reasoningEffort,
        if (contextWindowTokensOverride != null)
          'contextWindowTokensOverride': contextWindowTokensOverride,
      },
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'downloadOnDeviceLlmModel',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'cancelOnDeviceLlmModelDownload',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _invokeMap(
      'deleteOnDeviceLlmModel',
      arguments: <String, Object?>{'modelId': modelId},
    ),
  );
}
