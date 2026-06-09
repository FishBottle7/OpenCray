class OpenCrayLlmConfigSnapshot {
  const OpenCrayLlmConfigSnapshot({
    required this.localeTag,
    required this.enabled,
    this.streamingEnabled = true,
    this.providerMode = 'cloud',
    required this.providerId,
    required this.selectedProviderOptionId,
    required this.protocol,
    required this.providerOptions,
    required this.providerName,
    required this.providerNotes,
    required this.baseUrl,
    required this.apiKey,
    required this.model,
    required this.reasoningEffort,
    required this.systemPrompt,
    required this.helperText,
    this.openAiPromptCacheKeyStrategy = 'none',
    this.openAiPromptCacheRetention = '',
    this.anthropicPromptCachingEnabled = false,
    this.anthropicPromptCacheTtl = '5m',
    this.onDeviceModels = const <OpenCrayOnDeviceLlmModelOptionSnapshot>[],
    this.selectedOnDeviceModelId = 'gemma-4-e2b-it',
    this.onDeviceMaxContextWindow = 32768,
    this.onDeviceMaxTokens = 4096,
    this.onDeviceTopK = 40,
    this.onDeviceTopP = 0.95,
    this.onDeviceTemperature = 0.70,
    this.onDeviceAccelerator = 'gpu',
    this.onDeviceThinkingEnabled = false,
    this.onDeviceLiteModeEnabled = false,
    this.contextBudgetPreset = 'balanced',
    this.contextBudgetReservedOutputTokens,
    this.contextBudgetSafetyMarginTokens,
    this.contextBudgetEffectiveInputPercent,
  });

  final String localeTag;
  final bool enabled;
  final bool streamingEnabled;
  final String providerMode;
  final String providerId;
  final String selectedProviderOptionId;
  final String protocol;
  final List<OpenCrayLlmProviderOptionSnapshot> providerOptions;
  final String providerName;
  final String providerNotes;
  final String baseUrl;
  final String apiKey;
  final String model;
  final String reasoningEffort;
  final String systemPrompt;
  final String helperText;
  final String openAiPromptCacheKeyStrategy;
  final String openAiPromptCacheRetention;
  final bool anthropicPromptCachingEnabled;
  final String anthropicPromptCacheTtl;
  final List<OpenCrayOnDeviceLlmModelOptionSnapshot> onDeviceModels;
  final String selectedOnDeviceModelId;
  final int onDeviceMaxContextWindow;
  final int onDeviceMaxTokens;
  final int onDeviceTopK;
  final double onDeviceTopP;
  final double onDeviceTemperature;
  final String onDeviceAccelerator;
  final bool onDeviceThinkingEnabled;
  final bool onDeviceLiteModeEnabled;
  final String contextBudgetPreset;
  final int? contextBudgetReservedOutputTokens;
  final int? contextBudgetSafetyMarginTokens;
  final double? contextBudgetEffectiveInputPercent;

  OpenCrayLlmConfigSnapshot copyWith({
    List<OpenCrayOnDeviceLlmModelOptionSnapshot>? onDeviceModels,
    String? selectedOnDeviceModelId,
  }) {
    return OpenCrayLlmConfigSnapshot(
      localeTag: localeTag,
      enabled: enabled,
      streamingEnabled: streamingEnabled,
      providerMode: providerMode,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerOptions: providerOptions,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      helperText: helperText,
      openAiPromptCacheKeyStrategy: openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: anthropicPromptCacheTtl,
      onDeviceModels: onDeviceModels ?? this.onDeviceModels,
      selectedOnDeviceModelId:
          selectedOnDeviceModelId ?? this.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: onDeviceMaxContextWindow,
      onDeviceMaxTokens: onDeviceMaxTokens,
      onDeviceTopK: onDeviceTopK,
      onDeviceTopP: onDeviceTopP,
      onDeviceTemperature: onDeviceTemperature,
      onDeviceAccelerator: onDeviceAccelerator,
      onDeviceThinkingEnabled: onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: onDeviceLiteModeEnabled,
      contextBudgetPreset: contextBudgetPreset,
      contextBudgetReservedOutputTokens: contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent: contextBudgetEffectiveInputPercent,
    );
  }

  factory OpenCrayLlmConfigSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayLlmConfigSnapshot(
      localeTag: payload['localeTag'] as String? ?? 'en',
      enabled: payload['enabled'] as bool? ?? false,
      streamingEnabled: payload['streamingEnabled'] as bool? ?? true,
      providerMode: payload['providerMode'] as String? ?? 'cloud',
      providerId: payload['providerId'] as String? ?? 'custom',
      selectedProviderOptionId:
          payload['selectedProviderOptionId'] as String? ??
          (payload['providerId'] as String? ?? 'custom'),
      protocol: payload['protocol'] as String? ?? 'openai',
      providerOptions: _requireList(payload['providerOptions'])
          .map(_requireMap)
          .map(OpenCrayLlmProviderOptionSnapshot.fromMap)
          .toList(growable: false),
      providerName: payload['providerName'] as String? ?? '',
      providerNotes: payload['providerNotes'] as String? ?? '',
      baseUrl: payload['baseUrl'] as String? ?? '',
      apiKey: payload['apiKey'] as String? ?? '',
      model: payload['model'] as String? ?? '',
      reasoningEffort: payload['reasoningEffort'] as String? ?? 'medium',
      systemPrompt: payload['systemPrompt'] as String? ?? '',
      helperText: payload['helperText'] as String? ?? '',
      openAiPromptCacheKeyStrategy:
          payload['openAiPromptCacheKeyStrategy'] as String? ?? 'none',
      openAiPromptCacheRetention:
          payload['openAiPromptCacheRetention'] as String? ?? '',
      anthropicPromptCachingEnabled:
          payload['anthropicPromptCachingEnabled'] as bool? ?? false,
      anthropicPromptCacheTtl:
          payload['anthropicPromptCacheTtl'] as String? ?? '5m',
      onDeviceModels: _requireList(payload['onDeviceModels'])
          .map(_requireMap)
          .map(OpenCrayOnDeviceLlmModelOptionSnapshot.fromMap)
          .toList(growable: false),
      selectedOnDeviceModelId:
          payload['selectedOnDeviceModelId'] as String? ?? 'gemma-4-e2b-it',
      onDeviceMaxContextWindow:
          (payload['onDeviceMaxContextWindow'] as num?)?.toInt() ?? 32768,
      onDeviceMaxTokens:
          (payload['onDeviceMaxTokens'] as num?)?.toInt() ?? 4096,
      onDeviceTopK: (payload['onDeviceTopK'] as num?)?.toInt() ?? 40,
      onDeviceTopP: (payload['onDeviceTopP'] as num?)?.toDouble() ?? 0.95,
      onDeviceTemperature:
          (payload['onDeviceTemperature'] as num?)?.toDouble() ?? 0.70,
      onDeviceAccelerator: payload['onDeviceAccelerator'] as String? ?? 'gpu',
      onDeviceThinkingEnabled:
          payload['onDeviceThinkingEnabled'] as bool? ?? false,
      onDeviceLiteModeEnabled:
          payload['onDeviceLiteModeEnabled'] as bool? ?? false,
      contextBudgetPreset:
          payload['contextBudgetPreset'] as String? ?? 'balanced',
      contextBudgetReservedOutputTokens:
          (payload['contextBudgetReservedOutputTokens'] as num?)?.toInt(),
      contextBudgetSafetyMarginTokens:
          (payload['contextBudgetSafetyMarginTokens'] as num?)?.toInt(),
      contextBudgetEffectiveInputPercent:
          (payload['contextBudgetEffectiveInputPercent'] as num?)?.toDouble(),
    );
  }
}

class OpenCrayOnDeviceLlmModelOptionSnapshot {
  const OpenCrayOnDeviceLlmModelOptionSnapshot({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.sizeLabel,
    required this.fileSizeBytes,
    required this.installState,
    this.downloadedBytes = 0,
    this.downloadBytesPerSecond = 0,
    this.sha256Verified = false,
    this.isSelected = false,
    this.lastError,
  });

  final String id;
  final String title;
  final String subtitle;
  final String sizeLabel;
  final int fileSizeBytes;
  final String installState;
  final int downloadedBytes;
  final int downloadBytesPerSecond;
  final bool sha256Verified;
  final bool isSelected;
  final String? lastError;

  factory OpenCrayOnDeviceLlmModelOptionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayOnDeviceLlmModelOptionSnapshot(
      id: payload['id'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      sizeLabel: payload['sizeLabel'] as String? ?? '',
      fileSizeBytes: (payload['fileSizeBytes'] as num?)?.toInt() ?? 0,
      installState:
          payload['installState'] as String? ??
          payload['downloadState'] as String? ??
          'not_downloaded',
      downloadedBytes: (payload['downloadedBytes'] as num?)?.toInt() ?? 0,
      downloadBytesPerSecond:
          (payload['downloadBytesPerSecond'] as num?)?.toInt() ?? 0,
      sha256Verified: payload['sha256Verified'] as bool? ?? false,
      isSelected: payload['isSelected'] as bool? ?? false,
      lastError: payload['lastError'] as String?,
    );
  }
}

class OpenCrayLlmProviderOptionSnapshot {
  const OpenCrayLlmProviderOptionSnapshot({
    required this.id,
    required this.providerId,
    required this.title,
    required this.subtitle,
    required this.defaultBaseUrl,
    required this.defaultModel,
    required this.protocol,
    required this.apiKey,
    required this.isCustom,
  });

  final String id;
  final String providerId;
  final String title;
  final String subtitle;
  final String defaultBaseUrl;
  final String defaultModel;
  final String protocol;
  final String apiKey;
  final bool isCustom;

  factory OpenCrayLlmProviderOptionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayLlmProviderOptionSnapshot(
      id: payload['id'] as String? ?? '',
      providerId: payload['providerId'] as String? ?? '',
      title: payload['title'] as String? ?? '',
      subtitle: payload['subtitle'] as String? ?? '',
      defaultBaseUrl: payload['defaultBaseUrl'] as String? ?? '',
      defaultModel: payload['defaultModel'] as String? ?? '',
      protocol: payload['protocol'] as String? ?? 'openai',
      apiKey: payload['apiKey'] as String? ?? '',
      isCustom: payload['isCustom'] as bool? ?? false,
    );
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}
