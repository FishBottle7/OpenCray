part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeLlmDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      OpenCrayLlmConfigSnapshot.fromMap(await _getMap('v1/llm_config'));

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
    await _postMap('v1/save_llm_config', <String, Object?>{
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
      'contextBudgetEffectiveInputPercent': contextBudgetEffectiveInputPercent,
      if (contextWindowTokensOverride != null)
        'contextWindowTokensOverride': contextWindowTokensOverride,
    }),
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
    await _postMap('v1/save_custom_llm_provider', <String, Object?>{
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
    }),
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
    await _postMap('v1/validate_llm_config', <String, Object?>{
      'providerId': providerId,
      'protocol': protocol,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
      'reasoningEffort': reasoningEffort,
      if (contextWindowTokensOverride != null)
        'contextWindowTokensOverride': contextWindowTokensOverride,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/download_on_device_llm_model', <String, Object?>{
      'modelId': modelId,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/cancel_on_device_llm_model_download', <String, Object?>{
      'modelId': modelId,
    }),
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async => OpenCrayLlmConfigSnapshot.fromMap(
    await _postMap('v1/delete_on_device_llm_model', <String, Object?>{
      'modelId': modelId,
    }),
  );
}
