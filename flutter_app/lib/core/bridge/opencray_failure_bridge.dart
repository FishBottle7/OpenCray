import '../../app/opencray_tabs.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import 'opencray_host_bridge.dart';

class OpenCrayFailureBridge implements OpenCrayHostBridge {
  OpenCrayFailureBridge({required String failureMessage})
    : _failureMessage = failureMessage;

  final String _failureMessage;

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async =>
      OpenCrayShellSnapshot(
        initialTab: OpenCrayTab.chat,
        localeTag: 'en',
        hostLabel: 'HOST ERROR',
        hostSummary: _failureMessage,
        isHostConnected: false,
      );

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() async* {
    yield await loadShellSnapshot();
  }

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      throw StateError(_failureMessage);

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async => throw StateError(_failureMessage);

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) async =>
      throw StateError(_failureMessage);

  @override
  Future<void> showNativeToast(String message) async {}

  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      OpenCraySettingsOverviewSnapshot(
        eyebrow: 'HOST ERROR',
        title: 'Settings unavailable',
        subtitle: _failureMessage,
        deviceTitle: 'Native host bridge',
        deviceSummary: 'The Android host runtime failed to initialize.',
        entries: const <OpenCraySettingsHomeEntrySnapshot>[],
      );

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() async* {
    yield await loadSettingsOverview();
  }

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => OpenCraySettingsDetailSnapshot(
    routeId: routeId,
    title: 'Settings unavailable',
    subtitle: _failureMessage,
    sections: const <OpenCraySettingsSectionSnapshot>[],
  );

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      const OpenCrayLlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'custom',
        protocol: 'openai',
        providerOptions: <OpenCrayLlmProviderOptionSnapshot>[
          OpenCrayLlmProviderOptionSnapshot(
            id: 'custom',
            title: 'Custom provider',
            subtitle: 'The Android host bridge failed to initialize.',
            defaultBaseUrl: '',
            defaultModel: '',
            isCustom: true,
          ),
        ],
        providerName: 'Custom provider',
        providerNotes: '',
        baseUrl: '',
        apiKey: '',
        model: '',
        reasoningEffort: 'medium',
        systemPrompt: '',
        helperText: 'The Android host bridge failed to initialize.',
      );

  @override
  Future<OpenCrayLlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    required String providerId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  }) async =>
      OpenCrayLlmValidationResult(isSuccess: false, message: _failureMessage);

  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async => OpenCrayPersonalizationConfigSnapshot(
    title: 'Personalization',
    subtitle: _failureMessage,
    introTitle: 'Host unavailable',
    introBody: _failureMessage,
    introHelper: '',
    presetsTitle: 'Presets unavailable',
    presetsHelper: 'The Android host bridge failed to initialize.',
    presets: const <OpenCrayPersonalizationPresetOptionSnapshot>[
      OpenCrayPersonalizationPresetOptionSnapshot(
        id: 'steady',
        title: 'Steady guide',
        summary: 'The Android host bridge failed to initialize.',
        voice: 'Base voice unavailable.',
        status: 'Unavailable',
        isSelected: true,
      ),
    ],
    selectedPresetId: 'steady',
    customOverlayTitle: 'Custom personality overlay',
    customOverlayHelper: _failureMessage,
    customLabelHint: 'Optional custom personality label',
    customLabelHelper: '',
    customGuidanceHint: 'Add custom tone, goals, and boundaries',
    customGuidanceHelper: '',
    customLabel: '',
    customGuidance: '',
    behaviorDefaultsTitle: 'Behavior defaults',
    appLanguageTitle: 'App language',
    appLanguageOptions: const <OpenCrayPersonalizationLanguageOptionSnapshot>[
      OpenCrayPersonalizationLanguageOptionSnapshot(
        id: 'en',
        title: 'English',
        isSelected: true,
      ),
      OpenCrayPersonalizationLanguageOptionSnapshot(
        id: 'zh-CN',
        title: '中文',
        isSelected: false,
      ),
    ],
    selectedAppLanguageId: 'en',
    livePreviewTitle: 'Live profile preview',
    livePreviewName: 'Host unavailable',
    livePreviewSummary: _failureMessage,
    queueTitle: 'Host unavailable',
    queueBody: _failureMessage,
    queueIsIdle: false,
    lastResetTitle: 'Latest reset result',
    lastResetMessage: '',
    resetActions: const <OpenCrayPersonalizationResetActionSnapshot>[
      OpenCrayPersonalizationResetActionSnapshot(
        scopeId: 'memory',
        title: 'Reset memory',
        scopeBody: 'Unavailable while the Android host bridge is offline.',
        retainBody: '',
        confirmationToken: 'RESET MEMORY',
        inputHint: 'Type RESET MEMORY',
        disabledGuidance:
            'Unavailable while the Android host bridge is offline.',
        typeExactGuidance: '',
        armedGuidance: '',
        isInputEnabled: false,
      ),
      OpenCrayPersonalizationResetActionSnapshot(
        scopeId: 'soul',
        title: 'Reset soul',
        scopeBody: 'Unavailable while the Android host bridge is offline.',
        retainBody: '',
        confirmationToken: 'RESET SOUL',
        inputHint: 'Type RESET SOUL',
        disabledGuidance:
            'Unavailable while the Android host bridge is offline.',
        typeExactGuidance: '',
        armedGuidance: '',
        isInputEnabled: false,
      ),
    ],
  );

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings() async =>
      OpenCrayMcpSettingsSnapshot(
        title: 'MCP',
        subtitle: _failureMessage,
        masterEnabled: false,
        masterTitle: 'Enable MCP integrations',
        masterSummary: _failureMessage,
        summaryLine: 'Host unavailable',
        serversTitle: 'Servers unavailable',
        serversHelper: _failureMessage,
        masterDisabledTitle: 'Host unavailable',
        masterDisabledBody: _failureMessage,
        servers: const <OpenCrayMcpServerSnapshot>[],
      );

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      throw StateError(_failureMessage);

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot() async =>
      const OpenCraySkillsSnapshot(
        installedSkills: <OpenCrayInstalledSkillSnapshot>[],
        installSources: <OpenCraySkillInstallSourceSnapshot>[
          OpenCraySkillInstallSourceSnapshot(
            id: 'curated-library',
            title: 'Curated skills',
            subtitle: 'The Android host bridge failed to initialize.',
            ctaLabel: 'Unavailable',
            isAvailable: false,
          ),
        ],
        suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
      );

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() async* {
    yield await loadSkillsSnapshot();
  }

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) async {}

  @override
  Future<String?> refreshSkills() async => _failureMessage;

  @override
  Future<String?> installSuggestedSkill(String skillId) async =>
      _failureMessage;

  @override
  Future<String?> deleteInstalledSkill(String skillId) async => _failureMessage;

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async => null;

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async => OpenCrayChatSnapshot(
    screenTitle: 'Chat',
    modeLabel: 'ERROR',
    sessionButtonLabel: 'Sessions',
    composerPlaceholder: 'Host runtime unavailable',
    summary: OpenCrayChatSummarySnapshot(
      title: 'Host runtime unavailable',
      badge: 'Error',
      body: _failureMessage,
    ),
    messages: const <OpenCrayChatMessageSnapshot>[
      OpenCrayChatMessageSnapshot(
        kind: 'timeline',
        text: 'Android host runtime failed to initialize.',
      ),
    ],
    drawer: const OpenCrayChatDrawerSnapshot(
      eyebrow: 'Recent sessions',
      title: 'Recent sessions',
      ctaLabel: 'New session',
      sessions: <OpenCrayChatSessionItemSnapshot>[],
    ),
    isInputEnabled: false,
  );

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() async* {
    yield await loadChatSnapshot();
  }

  @override
  Future<OpenCrayChatRuntimeSnapshot> loadChatRuntimeSnapshot() async =>
      const OpenCrayChatRuntimeSnapshot(
        sessionId: '',
        activeRuns: <OpenCrayChatRunSnapshot>[],
        events: <OpenCrayChatRuntimeEventSnapshot>[],
      );

  @override
  Stream<OpenCrayChatRuntimeSnapshot> watchChatRuntimeSnapshot() async* {
    yield await loadChatRuntimeSnapshot();
  }

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      null;

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async => null;

  @override
  Future<void> createChatSession() async {}

  @override
  Future<void> selectChatSession(String sessionId) async {}

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(String text) async =>
      null;

  @override
  Future<void> approveChatApproval(String approvalId) async {}

  @override
  Future<void> rejectChatApproval(String approvalId) async {}
}
