enum SettingsPage {
  home,
  notificationsBackground,
  notificationChannels,
  workspaceAccess,
  llm,
  mcp,
  apiIntegrations,
  networkSearch,
  mediaSpeech,
  sandboxProviders,
  sandboxE2b,
  safetyLimits,
  personalization,
  agents,
  aboutVersion,
}

extension SettingsPageRouteId on SettingsPage {
  String get routeId {
    switch (this) {
      case SettingsPage.home:
        return 'home';
      case SettingsPage.notificationsBackground:
        return 'notifications_background';
      case SettingsPage.notificationChannels:
        return 'notification_channels';
      case SettingsPage.workspaceAccess:
        return 'workspace_access';
      case SettingsPage.llm:
        return 'llm';
      case SettingsPage.mcp:
        return 'mcp';
      case SettingsPage.apiIntegrations:
        return 'api_integrations';
      case SettingsPage.networkSearch:
        return 'network_search';
      case SettingsPage.mediaSpeech:
        return 'media_speech';
      case SettingsPage.sandboxProviders:
        return 'sandbox_providers';
      case SettingsPage.sandboxE2b:
        return 'sandbox_e2b';
      case SettingsPage.safetyLimits:
        return 'safety_limits';
      case SettingsPage.personalization:
        return 'personalization';
      case SettingsPage.agents:
        return 'agents';
      case SettingsPage.aboutVersion:
        return 'about_version';
    }
  }
}

SettingsPage settingsPageFromRouteId(String routeId) {
  switch (routeId) {
    case 'notifications_background':
      return SettingsPage.notificationsBackground;
    case 'notification_channels':
      return SettingsPage.notificationChannels;
    case 'workspace_access':
      return SettingsPage.workspaceAccess;
    case 'llm':
      return SettingsPage.llm;
    case 'mcp':
      return SettingsPage.mcp;
    case 'privacy_telemetry':
      return SettingsPage.apiIntegrations;
    case 'api_integrations':
      return SettingsPage.apiIntegrations;
    case 'network_search':
      return SettingsPage.networkSearch;
    case 'media_speech':
      return SettingsPage.mediaSpeech;
    case 'sandbox_providers':
      return SettingsPage.sandboxProviders;
    case 'sandbox_e2b':
      return SettingsPage.sandboxE2b;
    case 'safety_limits':
      return SettingsPage.safetyLimits;
    case 'personalization':
      return SettingsPage.personalization;
    case 'agents':
      return SettingsPage.agents;
    case 'about_version':
      return SettingsPage.aboutVersion;
    default:
      return SettingsPage.home;
  }
}

class SettingsHomeEntrySnapshot {
  const SettingsHomeEntrySnapshot({required this.page, required this.title});

  final SettingsPage page;
  final String title;
}

class SettingsOverviewSnapshot {
  const SettingsOverviewSnapshot({
    required this.eyebrow,
    required this.title,
    required this.subtitle,
    required this.deviceTitle,
    required this.deviceSummary,
    required this.entries,
  });

  final String eyebrow;
  final String title;
  final String subtitle;
  final String deviceTitle;
  final String deviceSummary;
  final List<SettingsHomeEntrySnapshot> entries;
}

enum SettingsSectionBackgroundTone { surface, danger }

enum SettingsRowTrailingKind { chevron, toggle, value }

class SettingsRowSnapshot {
  const SettingsRowSnapshot.chevron({required this.title, this.subtitle})
    : trailingKind = SettingsRowTrailingKind.chevron,
      toggleValue = null,
      valueLabel = null;

  const SettingsRowSnapshot.toggle({
    required this.title,
    this.subtitle,
    required this.toggleValue,
  }) : trailingKind = SettingsRowTrailingKind.toggle,
       valueLabel = null;

  const SettingsRowSnapshot.value({
    required this.title,
    required this.valueLabel,
  }) : trailingKind = SettingsRowTrailingKind.value,
       subtitle = null,
       toggleValue = null;

  final String title;
  final String? subtitle;
  final SettingsRowTrailingKind trailingKind;
  final bool? toggleValue;
  final String? valueLabel;
}

class SettingsSectionSnapshot {
  const SettingsSectionSnapshot({
    required this.title,
    this.helperText,
    this.rows = const <SettingsRowSnapshot>[],
    this.segmentedOptions,
    this.segmentedIndex,
    this.inlinePanelText,
    this.backgroundTone = SettingsSectionBackgroundTone.surface,
  });

  final String title;
  final String? helperText;
  final List<SettingsRowSnapshot> rows;
  final List<String>? segmentedOptions;
  final int? segmentedIndex;
  final String? inlinePanelText;
  final SettingsSectionBackgroundTone backgroundTone;
}

class SettingsDetailSnapshot {
  const SettingsDetailSnapshot({
    required this.page,
    required this.title,
    required this.subtitle,
    required this.sections,
  });

  final SettingsPage page;
  final String title;
  final String subtitle;
  final List<SettingsSectionSnapshot> sections;
}

class NetworkSearchSlotSnapshot {
  const NetworkSearchSlotSnapshot({
    required this.id,
    required this.providerId,
    required this.label,
    required this.baseUrl,
    required this.model,
    required this.apiKey,
    required this.enabled,
  });

  final String id;
  final String providerId;
  final String label;
  final String baseUrl;
  final String model;
  final String apiKey;
  final bool enabled;

  NetworkSearchSlotSnapshot copyWith({
    String? id,
    String? providerId,
    String? label,
    String? baseUrl,
    String? model,
    String? apiKey,
    bool? enabled,
  }) {
    return NetworkSearchSlotSnapshot(
      id: id ?? this.id,
      providerId: providerId ?? this.providerId,
      label: label ?? this.label,
      baseUrl: baseUrl ?? this.baseUrl,
      model: model ?? this.model,
      apiKey: apiKey ?? this.apiKey,
      enabled: enabled ?? this.enabled,
    );
  }
}

class NetworkSearchConfigSnapshot {
  const NetworkSearchConfigSnapshot({
    required this.localeTag,
    required this.title,
    required this.subtitle,
    required this.slots,
  });

  final String localeTag;
  final String title;
  final String subtitle;
  final List<NetworkSearchSlotSnapshot> slots;
}

enum MediaSpeechSttRoute { externalApi, onDeviceModel }

extension MediaSpeechSttRouteId on MediaSpeechSttRoute {
  String get id {
    switch (this) {
      case MediaSpeechSttRoute.externalApi:
        return 'external_api';
      case MediaSpeechSttRoute.onDeviceModel:
        return 'on_device_model';
    }
  }
}

MediaSpeechSttRoute mediaSpeechSttRouteFromId(String rawValue) {
  switch (rawValue) {
    case 'external_api':
      return MediaSpeechSttRoute.externalApi;
    case 'on_device_model':
    default:
      return MediaSpeechSttRoute.onDeviceModel;
  }
}

class MediaProviderConfigSnapshot {
  const MediaProviderConfigSnapshot({
    required this.provider,
    required this.baseUrl,
    required this.endpoint,
    required this.model,
    this.authProtocol = 'bearer',
    this.apiKey = '',
  });

  final String provider;
  final String baseUrl;
  final String endpoint;
  final String model;
  final String authProtocol;
  final String apiKey;

  MediaProviderConfigSnapshot copyWith({
    String? provider,
    String? baseUrl,
    String? endpoint,
    String? model,
    String? authProtocol,
    String? apiKey,
  }) {
    return MediaProviderConfigSnapshot(
      provider: provider ?? this.provider,
      baseUrl: baseUrl ?? this.baseUrl,
      endpoint: endpoint ?? this.endpoint,
      model: model ?? this.model,
      authProtocol: authProtocol ?? this.authProtocol,
      apiKey: apiKey ?? this.apiKey,
    );
  }
}

class VoiceProviderConfigSnapshot {
  const VoiceProviderConfigSnapshot({
    required this.provider,
    required this.baseUrl,
    required this.endpoint,
    this.model = 'tts-1',
    required this.voicePreset,
    this.authProtocol = 'bearer',
    this.apiKey = '',
  });

  final String provider;
  final String baseUrl;
  final String endpoint;
  final String model;
  final String voicePreset;
  final String authProtocol;
  final String apiKey;

  VoiceProviderConfigSnapshot copyWith({
    String? provider,
    String? baseUrl,
    String? endpoint,
    String? model,
    String? voicePreset,
    String? authProtocol,
    String? apiKey,
  }) {
    return VoiceProviderConfigSnapshot(
      provider: provider ?? this.provider,
      baseUrl: baseUrl ?? this.baseUrl,
      endpoint: endpoint ?? this.endpoint,
      model: model ?? this.model,
      voicePreset: voicePreset ?? this.voicePreset,
      authProtocol: authProtocol ?? this.authProtocol,
      apiKey: apiKey ?? this.apiKey,
    );
  }
}

class OnDeviceSttConfigSnapshot {
  const OnDeviceSttConfigSnapshot({
    required this.modelPackage,
    required this.downloadStatus,
  });

  final String modelPackage;
  final String downloadStatus;

  OnDeviceSttConfigSnapshot copyWith({
    String? modelPackage,
    String? downloadStatus,
  }) {
    return OnDeviceSttConfigSnapshot(
      modelPackage: modelPackage ?? this.modelPackage,
      downloadStatus: downloadStatus ?? this.downloadStatus,
    );
  }
}

class MediaSpeechConfigSnapshot {
  const MediaSpeechConfigSnapshot({
    required this.localeTag,
    required this.title,
    required this.subtitle,
    required this.imageGeneration,
    this.videoGeneration = const MediaProviderConfigSnapshot(
      provider: '',
      baseUrl: '',
      endpoint: '',
      model: '',
    ),
    required this.voiceGeneration,
    required this.sttRoute,
    required this.externalStt,
    required this.onDeviceModel,
  });

  final String localeTag;
  final String title;
  final String subtitle;
  final MediaProviderConfigSnapshot imageGeneration;
  final MediaProviderConfigSnapshot videoGeneration;
  final VoiceProviderConfigSnapshot voiceGeneration;
  final MediaSpeechSttRoute sttRoute;
  final MediaProviderConfigSnapshot externalStt;
  final OnDeviceSttConfigSnapshot onDeviceModel;

  MediaSpeechConfigSnapshot copyWith({
    String? localeTag,
    String? title,
    String? subtitle,
    MediaProviderConfigSnapshot? imageGeneration,
    MediaProviderConfigSnapshot? videoGeneration,
    VoiceProviderConfigSnapshot? voiceGeneration,
    MediaSpeechSttRoute? sttRoute,
    MediaProviderConfigSnapshot? externalStt,
    OnDeviceSttConfigSnapshot? onDeviceModel,
  }) {
    return MediaSpeechConfigSnapshot(
      localeTag: localeTag ?? this.localeTag,
      title: title ?? this.title,
      subtitle: subtitle ?? this.subtitle,
      imageGeneration: imageGeneration ?? this.imageGeneration,
      videoGeneration: videoGeneration ?? this.videoGeneration,
      voiceGeneration: voiceGeneration ?? this.voiceGeneration,
      sttRoute: sttRoute ?? this.sttRoute,
      externalStt: externalStt ?? this.externalStt,
      onDeviceModel: onDeviceModel ?? this.onDeviceModel,
    );
  }
}

class SandboxSettingsSnapshot {
  const SandboxSettingsSnapshot({
    required this.localeTag,
    required this.enabled,
    required this.providerId,
    required this.defaultBackend,
    required this.sessionMode,
    required this.autoResume,
    required this.idleTimeoutMinutes,
    required this.startupTimeoutMs,
    required this.requestTimeoutMs,
    required this.timeoutAction,
    required this.templateId,
    required this.e2bApiKey,
    required this.apiKeyConfigured,
  });

  final String localeTag;
  final bool enabled;
  final String providerId;
  final String defaultBackend;
  final String sessionMode;
  final bool autoResume;
  final int idleTimeoutMinutes;
  final int startupTimeoutMs;
  final int requestTimeoutMs;
  final String timeoutAction;
  final String templateId;
  final String e2bApiKey;
  final bool apiKeyConfigured;

  SandboxSettingsSnapshot copyWith({
    String? localeTag,
    bool? enabled,
    String? providerId,
    String? defaultBackend,
    String? sessionMode,
    bool? autoResume,
    int? idleTimeoutMinutes,
    int? startupTimeoutMs,
    int? requestTimeoutMs,
    String? timeoutAction,
    String? templateId,
    String? e2bApiKey,
    bool? apiKeyConfigured,
  }) {
    return SandboxSettingsSnapshot(
      localeTag: localeTag ?? this.localeTag,
      enabled: enabled ?? this.enabled,
      providerId: providerId ?? this.providerId,
      defaultBackend: defaultBackend ?? this.defaultBackend,
      sessionMode: sessionMode ?? this.sessionMode,
      autoResume: autoResume ?? this.autoResume,
      idleTimeoutMinutes: idleTimeoutMinutes ?? this.idleTimeoutMinutes,
      startupTimeoutMs: startupTimeoutMs ?? this.startupTimeoutMs,
      requestTimeoutMs: requestTimeoutMs ?? this.requestTimeoutMs,
      timeoutAction: timeoutAction ?? this.timeoutAction,
      templateId: templateId ?? this.templateId,
      e2bApiKey: e2bApiKey ?? this.e2bApiKey,
      apiKeyConfigured: apiKeyConfigured ?? this.apiKeyConfigured,
    );
  }
}

class LlmProviderOption {
  const LlmProviderOption({
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
}

class LlmOnDeviceModelOption {
  const LlmOnDeviceModelOption({
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
}

class LlmConfigSnapshot {
  const LlmConfigSnapshot({
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
    this.providerMode = 'cloud',
    this.openAiPromptCacheKeyStrategy = 'none',
    this.openAiPromptCacheRetention = '',
    this.anthropicPromptCachingEnabled = false,
    this.anthropicPromptCacheTtl = '5m',
    this.onDeviceModels = const <LlmOnDeviceModelOption>[],
    this.selectedOnDeviceModelId = 'gemma-4-e2b-it',
    this.onDeviceMaxContextWindow = 32768,
    this.onDeviceMaxTokens = 4096,
    this.onDeviceTopK = 40,
    this.onDeviceTopP = 0.95,
    this.onDeviceTemperature = 0.70,
    this.onDeviceAccelerator = 'gpu',
    this.onDeviceThinkingEnabled = false,
    this.onDeviceLiteModeEnabled = false,
  });

  final String localeTag;
  final bool enabled;
  final bool streamingEnabled;
  final String providerMode;
  final String providerId;
  final String selectedProviderOptionId;
  final String protocol;
  final List<LlmProviderOption> providerOptions;
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
  final List<LlmOnDeviceModelOption> onDeviceModels;
  final String selectedOnDeviceModelId;
  final int onDeviceMaxContextWindow;
  final int onDeviceMaxTokens;
  final int onDeviceTopK;
  final double onDeviceTopP;
  final double onDeviceTemperature;
  final String onDeviceAccelerator;
  final bool onDeviceThinkingEnabled;
  final bool onDeviceLiteModeEnabled;

  LlmConfigSnapshot copyWith({
    List<LlmOnDeviceModelOption>? onDeviceModels,
    String? selectedOnDeviceModelId,
  }) {
    return LlmConfigSnapshot(
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
    );
  }
}

class LlmValidationResult {
  const LlmValidationResult({required this.isSuccess, required this.message});

  final bool isSuccess;
  final String message;
}

class PersonalizationPresetOption {
  const PersonalizationPresetOption({
    required this.id,
    required this.title,
    required this.summary,
    required this.voice,
    required this.status,
    required this.isSelected,
  });

  final String id;
  final String title;
  final String summary;
  final String voice;
  final String status;
  final bool isSelected;
}

class PersonalizationResetAction {
  const PersonalizationResetAction({
    required this.scopeId,
    required this.title,
    required this.scopeBody,
    required this.retainBody,
    required this.confirmationToken,
    required this.inputHint,
    required this.disabledGuidance,
    required this.typeExactGuidance,
    required this.armedGuidance,
    required this.isInputEnabled,
  });

  final String scopeId;
  final String title;
  final String scopeBody;
  final String retainBody;
  final String confirmationToken;
  final String inputHint;
  final String disabledGuidance;
  final String typeExactGuidance;
  final String armedGuidance;
  final bool isInputEnabled;
}

class PersonalizationLanguageOption {
  const PersonalizationLanguageOption({
    required this.id,
    required this.title,
    required this.isSelected,
  });

  final String id;
  final String title;
  final bool isSelected;
}

class PersonalizationConfigSnapshot {
  const PersonalizationConfigSnapshot({
    required this.title,
    required this.subtitle,
    required this.introTitle,
    required this.introBody,
    required this.introHelper,
    required this.presetsTitle,
    required this.presetsHelper,
    required this.presets,
    required this.selectedPresetId,
    required this.customOverlayTitle,
    required this.customOverlayHelper,
    required this.customLabelHint,
    required this.customLabelHelper,
    required this.customGuidanceHint,
    required this.customGuidanceHelper,
    required this.customLabel,
    required this.customGuidance,
    required this.behaviorDefaultsTitle,
    required this.appLanguageTitle,
    required this.appLanguageOptions,
    required this.selectedAppLanguageId,
    required this.livePreviewTitle,
    required this.livePreviewName,
    required this.livePreviewSummary,
    required this.queueTitle,
    required this.queueBody,
    required this.queueIsIdle,
    required this.lastResetTitle,
    required this.lastResetMessage,
    required this.resetActions,
  });

  final String title;
  final String subtitle;
  final String introTitle;
  final String introBody;
  final String introHelper;
  final String presetsTitle;
  final String presetsHelper;
  final List<PersonalizationPresetOption> presets;
  final String selectedPresetId;
  final String customOverlayTitle;
  final String customOverlayHelper;
  final String customLabelHint;
  final String customLabelHelper;
  final String customGuidanceHint;
  final String customGuidanceHelper;
  final String customLabel;
  final String customGuidance;
  final String behaviorDefaultsTitle;
  final String appLanguageTitle;
  final List<PersonalizationLanguageOption> appLanguageOptions;
  final String selectedAppLanguageId;
  final String livePreviewTitle;
  final String livePreviewName;
  final String livePreviewSummary;
  final String queueTitle;
  final String queueBody;
  final bool queueIsIdle;
  final String lastResetTitle;
  final String lastResetMessage;
  final List<PersonalizationResetAction> resetActions;
}

class McpServerSnapshot {
  const McpServerSnapshot({
    required this.id,
    required this.title,
    required this.statusLabel,
    required this.statusTone,
    required this.trustLine,
    required this.authLine,
    required this.readinessLine,
    required this.transportLine,
    required this.exposureLine,
    required this.guidance,
    required this.actionLabel,
    required this.actionTurnsOn,
    required this.isActionEnabled,
  });

  final String id;
  final String title;
  final String statusLabel;
  final String statusTone;
  final String trustLine;
  final String authLine;
  final String readinessLine;
  final String transportLine;
  final String exposureLine;
  final String guidance;
  final String actionLabel;
  final bool actionTurnsOn;
  final bool isActionEnabled;
}

class McpSettingsSnapshot {
  const McpSettingsSnapshot({
    required this.title,
    required this.subtitle,
    required this.masterTitle,
    required this.masterSummary,
    required this.masterEnabled,
    required this.summaryLine,
    required this.serversTitle,
    required this.serversHelper,
    required this.masterDisabledTitle,
    required this.masterDisabledBody,
    required this.servers,
  });

  final String title;
  final String subtitle;
  final String masterTitle;
  final String masterSummary;
  final bool masterEnabled;
  final String summaryLine;
  final String serversTitle;
  final String serversHelper;
  final String masterDisabledTitle;
  final String masterDisabledBody;
  final List<McpServerSnapshot> servers;
}
