class OpenCrayLlmConfigSnapshot {
  const OpenCrayLlmConfigSnapshot({
    required this.localeTag,
    required this.enabled,
    this.streamingEnabled = true,
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
  });

  final String localeTag;
  final bool enabled;
  final bool streamingEnabled;
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

  factory OpenCrayLlmConfigSnapshot.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayLlmConfigSnapshot(
      localeTag: payload['localeTag'] as String? ?? 'en',
      enabled: payload['enabled'] as bool? ?? false,
      streamingEnabled: payload['streamingEnabled'] as bool? ?? true,
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
