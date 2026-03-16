enum SettingsPage {
  home,
  workspaceAccess,
  llm,
  mcp,
  privacyTelemetry,
  safetyLimits,
  aboutVersion,
  personalization,
}

extension SettingsPageRouteId on SettingsPage {
  String get routeId {
    switch (this) {
      case SettingsPage.home:
        return 'home';
      case SettingsPage.workspaceAccess:
        return 'workspace_access';
      case SettingsPage.llm:
        return 'llm';
      case SettingsPage.mcp:
        return 'mcp';
      case SettingsPage.privacyTelemetry:
        return 'privacy_telemetry';
      case SettingsPage.safetyLimits:
        return 'safety_limits';
      case SettingsPage.aboutVersion:
        return 'about_version';
      case SettingsPage.personalization:
        return 'personalization';
    }
  }
}

SettingsPage settingsPageFromRouteId(String routeId) {
  switch (routeId) {
    case 'workspace_access':
      return SettingsPage.workspaceAccess;
    case 'llm':
      return SettingsPage.llm;
    case 'mcp':
      return SettingsPage.mcp;
    case 'privacy_telemetry':
      return SettingsPage.privacyTelemetry;
    case 'safety_limits':
      return SettingsPage.safetyLimits;
    case 'about_version':
      return SettingsPage.aboutVersion;
    case 'personalization':
      return SettingsPage.personalization;
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

class LlmConfigSnapshot {
  const LlmConfigSnapshot({
    required this.localeTag,
    required this.enabled,
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
  });

  final String localeTag;
  final bool enabled;
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
