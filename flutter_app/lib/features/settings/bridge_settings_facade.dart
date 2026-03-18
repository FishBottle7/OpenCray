import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/models/opencray_llm_config.dart';
import '../../core/models/opencray_llm_validation.dart';
import '../../core/models/opencray_mcp_settings.dart';
import '../../core/models/opencray_network_search_config.dart';
import '../../core/models/opencray_personalization_config.dart';
import '../../core/models/opencray_safety_settings.dart';
import '../../core/models/opencray_settings_snapshot.dart';
import 'safety_settings_models.dart';
import 'settings_facade.dart';
import 'settings_models.dart';

class BridgeSettingsFacade implements SettingsFacade {
  const BridgeSettingsFacade({required OpenCrayHostBridge bridge})
    : _bridge = bridge;

  final OpenCrayHostBridge _bridge;

  @override
  Future<SettingsOverviewSnapshot> loadOverview() async =>
      _mapOverview(await _bridge.loadSettingsOverview());

  @override
  Stream<SettingsOverviewSnapshot> watchOverview() =>
      _bridge.watchSettingsOverview().map(_mapOverview);

  @override
  Future<SettingsDetailSnapshot> loadDetail(SettingsPage page) async =>
      _mapDetail(await _bridge.loadSettingsDetail(page.routeId));

  @override
  Future<NetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      _mapNetworkSearch(await _bridge.loadNetworkSearchConfig());

  @override
  Future<NetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<NetworkSearchSlotSnapshot> slots,
  ) async => _mapNetworkSearch(
    await _bridge.saveNetworkSearchConfig(
      slots
          .map(
            (slot) => OpenCrayNetworkSearchSlotSnapshot(
              id: slot.id,
              providerId: slot.providerId,
              label: slot.label,
              apiKey: slot.apiKey,
              enabled: slot.enabled,
            ),
          )
          .toList(growable: false),
    ),
  );

  @override
  Future<LlmConfigSnapshot> loadLlmConfig() async =>
      _mapLlmConfig(await _bridge.loadLlmConfig());

  @override
  Future<LlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
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
  }) async => _mapLlmConfig(
    await _bridge.saveLlmConfig(
      enabled: enabled,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
    ),
  );

  @override
  Future<LlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
  }) async => _mapLlmConfig(
    await _bridge.saveCustomLlmProvider(
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
    ),
  );

  @override
  Future<LlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  }) async => _mapLlmValidation(
    await _bridge.validateLlmConfig(
      providerId: providerId,
      protocol: protocol,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
    ),
  );

  @override
  Future<PersonalizationConfigSnapshot> loadPersonalizationConfig() async =>
      _mapPersonalization(await _bridge.loadPersonalizationConfig());

  @override
  Future<PersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => _mapPersonalization(
    await _bridge.savePersonalizationConfig(
      presetId: presetId,
      customLabel: customLabel,
      customGuidance: customGuidance,
    ),
  );

  @override
  Future<PersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => _mapPersonalization(await _bridge.setAppLanguage(languageId));

  @override
  Future<PersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async =>
      _mapPersonalization(await _bridge.runPersonalizationReset(scopeId));

  @override
  Future<McpSettingsSnapshot> loadMcpSettings() async =>
      _mapMcpSettings(await _bridge.loadMcpSettings());

  @override
  Future<McpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      _mapMcpSettings(await _bridge.setMcpMasterEnabled(enabled));

  @override
  Future<McpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => _mapMcpSettings(
    await _bridge.setMcpServerEnabled(serverId: serverId, enabled: enabled),
  );

  @override
  Future<SafetySettingsSnapshot> loadSafetySettings() async =>
      _mapSafetySettings(await _bridge.loadSafetySettings());

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) =>
      _bridge.authorizeExternalAccessLocation(locationId);

  @override
  Future<SafetySettingsSnapshot> saveSafetySettings(
    SafetySettingsSnapshot snapshot,
  ) async => _mapSafetySettings(
    await _bridge.saveSafetySettings(
      automationModeId: snapshot.automationMode.id,
      rollbackJournalEnabled: snapshot.rollbackJournalEnabled,
      maxFilesPerBatch: snapshot.maxFilesPerBatch,
      maxAgentTurns: snapshot.maxAgentTurns,
      maxToolCalls: snapshot.maxToolCalls,
      undoWindowHours: snapshot.undoWindowHours,
      fileChangesPolicyId: snapshot.fileChangesPolicy.id,
      fileDeletesPolicyId: snapshot.fileDeletesPolicy.id,
      shellCommandsPolicyId: snapshot.shellCommandsPolicy.id,
      externalAccessModeId: snapshot.externalAccessMode.id,
      photoLibraryEnabled: snapshot.isLocationEnabled('photo_library'),
      downloadsEnabled: snapshot.isLocationEnabled('downloads'),
      documentsEnabled: snapshot.isLocationEnabled('documents'),
      recordingsEnabled: snapshot.isLocationEnabled('recordings'),
      workspaceAccessProfileId: snapshot.workspaceAccessProfile.id,
      readOnlyOutsideWorkspace: snapshot.readOnlyOutsideWorkspace,
      liveContextModeId: snapshot.liveContextMode.id,
    ),
  );

  static SettingsOverviewSnapshot _mapOverview(
    OpenCraySettingsOverviewSnapshot snapshot,
  ) {
    return SettingsOverviewSnapshot(
      eyebrow: snapshot.eyebrow,
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      deviceTitle: snapshot.deviceTitle,
      deviceSummary: snapshot.deviceSummary,
      entries: snapshot.entries
          .map(
            (entry) => SettingsHomeEntrySnapshot(
              page: settingsPageFromRouteId(entry.routeId),
              title: entry.title,
            ),
          )
          .toList(growable: false),
    );
  }

  static SettingsDetailSnapshot _mapDetail(
    OpenCraySettingsDetailSnapshot snapshot,
  ) {
    return SettingsDetailSnapshot(
      page: settingsPageFromRouteId(snapshot.routeId),
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      sections: snapshot.sections
          .map(
            (section) => SettingsSectionSnapshot(
              title: section.title,
              helperText: section.helperText,
              rows: section.rows
                  .map(
                    (row) => switch (row.trailingKind) {
                      OpenCraySettingsRowTrailingKind.chevron =>
                        SettingsRowSnapshot.chevron(
                          title: row.title,
                          subtitle: row.subtitle,
                        ),
                      OpenCraySettingsRowTrailingKind.toggle =>
                        SettingsRowSnapshot.toggle(
                          title: row.title,
                          subtitle: row.subtitle,
                          toggleValue: row.toggleValue ?? false,
                        ),
                      OpenCraySettingsRowTrailingKind.value =>
                        SettingsRowSnapshot.value(
                          title: row.title,
                          valueLabel: row.valueLabel ?? '',
                        ),
                    },
                  )
                  .toList(growable: false),
              segmentedOptions: section.segmentedOptions,
              segmentedIndex: section.segmentedIndex,
              inlinePanelText: section.inlinePanelText,
              backgroundTone:
                  section.backgroundTone ==
                      OpenCraySettingsSectionBackgroundTone.danger
                  ? SettingsSectionBackgroundTone.danger
                  : SettingsSectionBackgroundTone.surface,
            ),
          )
          .toList(growable: false),
    );
  }

  static NetworkSearchConfigSnapshot _mapNetworkSearch(
    OpenCrayNetworkSearchConfigSnapshot snapshot,
  ) {
    return NetworkSearchConfigSnapshot(
      localeTag: snapshot.localeTag,
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      slots: snapshot.slots
          .map(
            (slot) => NetworkSearchSlotSnapshot(
              id: slot.id,
              providerId: slot.providerId,
              label: slot.label,
              apiKey: slot.apiKey,
              enabled: slot.enabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static LlmConfigSnapshot _mapLlmConfig(OpenCrayLlmConfigSnapshot snapshot) {
    return LlmConfigSnapshot(
      localeTag: snapshot.localeTag,
      enabled: snapshot.enabled,
      providerId: snapshot.providerId,
      selectedProviderOptionId: snapshot.selectedProviderOptionId,
      protocol: snapshot.protocol,
      providerOptions: snapshot.providerOptions
          .map(
            (option) => LlmProviderOption(
              id: option.id,
              providerId: option.providerId,
              title: option.title,
              subtitle: option.subtitle,
              defaultBaseUrl: option.defaultBaseUrl,
              defaultModel: option.defaultModel,
              protocol: option.protocol,
              apiKey: option.apiKey,
              isCustom: option.isCustom,
            ),
          )
          .toList(growable: false),
      providerName: snapshot.providerName,
      providerNotes: snapshot.providerNotes,
      baseUrl: snapshot.baseUrl,
      apiKey: snapshot.apiKey,
      model: snapshot.model,
      reasoningEffort: snapshot.reasoningEffort,
      systemPrompt: snapshot.systemPrompt,
      helperText: snapshot.helperText,
    );
  }

  static LlmValidationResult _mapLlmValidation(
    OpenCrayLlmValidationResult result,
  ) {
    return LlmValidationResult(
      isSuccess: result.isSuccess,
      message: result.message,
    );
  }

  static PersonalizationConfigSnapshot _mapPersonalization(
    OpenCrayPersonalizationConfigSnapshot snapshot,
  ) {
    return PersonalizationConfigSnapshot(
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      introTitle: snapshot.introTitle,
      introBody: snapshot.introBody,
      introHelper: snapshot.introHelper,
      presetsTitle: snapshot.presetsTitle,
      presetsHelper: snapshot.presetsHelper,
      presets: snapshot.presets
          .map(
            (preset) => PersonalizationPresetOption(
              id: preset.id,
              title: preset.title,
              summary: preset.summary,
              voice: preset.voice,
              status: preset.status,
              isSelected: preset.isSelected,
            ),
          )
          .toList(growable: false),
      selectedPresetId: snapshot.selectedPresetId,
      customOverlayTitle: snapshot.customOverlayTitle,
      customOverlayHelper: snapshot.customOverlayHelper,
      customLabelHint: snapshot.customLabelHint,
      customLabelHelper: snapshot.customLabelHelper,
      customGuidanceHint: snapshot.customGuidanceHint,
      customGuidanceHelper: snapshot.customGuidanceHelper,
      customLabel: snapshot.customLabel,
      customGuidance: snapshot.customGuidance,
      behaviorDefaultsTitle: snapshot.behaviorDefaultsTitle,
      appLanguageTitle: snapshot.appLanguageTitle,
      appLanguageOptions: snapshot.appLanguageOptions
          .map(
            (option) => PersonalizationLanguageOption(
              id: option.id,
              title: option.title,
              isSelected: option.isSelected,
            ),
          )
          .toList(growable: false),
      selectedAppLanguageId: snapshot.selectedAppLanguageId,
      livePreviewTitle: snapshot.livePreviewTitle,
      livePreviewName: snapshot.livePreviewName,
      livePreviewSummary: snapshot.livePreviewSummary,
      queueTitle: snapshot.queueTitle,
      queueBody: snapshot.queueBody,
      queueIsIdle: snapshot.queueIsIdle,
      lastResetTitle: snapshot.lastResetTitle,
      lastResetMessage: snapshot.lastResetMessage,
      resetActions: snapshot.resetActions
          .map(
            (action) => PersonalizationResetAction(
              scopeId: action.scopeId,
              title: action.title,
              scopeBody: action.scopeBody,
              retainBody: action.retainBody,
              confirmationToken: action.confirmationToken,
              inputHint: action.inputHint,
              disabledGuidance: action.disabledGuidance,
              typeExactGuidance: action.typeExactGuidance,
              armedGuidance: action.armedGuidance,
              isInputEnabled: action.isInputEnabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static McpSettingsSnapshot _mapMcpSettings(
    OpenCrayMcpSettingsSnapshot snapshot,
  ) {
    return McpSettingsSnapshot(
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      masterTitle: snapshot.masterTitle,
      masterSummary: snapshot.masterSummary,
      masterEnabled: snapshot.masterEnabled,
      summaryLine: snapshot.summaryLine,
      serversTitle: snapshot.serversTitle,
      serversHelper: snapshot.serversHelper,
      masterDisabledTitle: snapshot.masterDisabledTitle,
      masterDisabledBody: snapshot.masterDisabledBody,
      servers: snapshot.servers
          .map(
            (server) => McpServerSnapshot(
              id: server.id,
              title: server.title,
              statusLabel: server.statusLabel,
              statusTone: server.statusTone,
              trustLine: server.trustLine,
              authLine: server.authLine,
              readinessLine: server.readinessLine,
              transportLine: server.transportLine,
              exposureLine: server.exposureLine,
              guidance: server.guidance,
              actionLabel: server.actionLabel,
              actionTurnsOn: server.actionTurnsOn,
              isActionEnabled: server.isActionEnabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static SafetySettingsSnapshot _mapSafetySettings(
    OpenCraySafetySettingsSnapshot snapshot,
  ) {
    return SafetySettingsSnapshot(
      automationMode: safetyAutomationModeFromId(snapshot.automationModeId),
      rollbackJournalEnabled: snapshot.rollbackJournalEnabled,
      maxFilesPerBatch: snapshot.maxFilesPerBatch,
      maxAgentTurns: snapshot.maxAgentTurns,
      maxToolCalls: snapshot.maxToolCalls,
      undoWindowHours: snapshot.undoWindowHours,
      fileChangesPolicy: toolPolicyOverrideFromId(snapshot.fileChangesPolicyId),
      fileDeletesPolicy: toolPolicyOverrideFromId(snapshot.fileDeletesPolicyId),
      shellCommandsPolicy: toolPolicyOverrideFromId(
        snapshot.shellCommandsPolicyId,
      ),
      externalAccessMode: externalAccessModeFromId(
        snapshot.externalAccessModeId,
      ),
      locations: snapshot.locations
          .map(
            (location) => SafetyLocationSetting(
              id: location.id,
              enabled: location.enabled,
            ),
          )
          .toList(growable: false),
      workspaceAccessProfile: workspaceAccessProfileFromId(
        snapshot.workspaceAccessProfileId,
      ),
      readOnlyOutsideWorkspace: snapshot.readOnlyOutsideWorkspace,
      liveContextMode: liveContextModeFromId(snapshot.liveContextModeId),
    );
  }
}
