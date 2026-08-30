import 'package:flutter/services.dart';

import '../../app/opencray_tabs.dart';
import '../models/opencray_chat_draft_attachment.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_agent_snapshot.dart';
import '../models/opencray_debug_snapshot.dart';
import '../models/opencray_file_image_preview.dart';
import '../models/opencray_file_text_preview.dart';
import '../models/opencray_file_voice_playback_source.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_image_reference.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_media_speech_config.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_network_search_config.dart';
import '../models/opencray_notification_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_sandbox_preview_embed_config.dart';
import '../models/opencray_sandbox_settings.dart';
import '../models/opencray_safety_settings.dart';
import '../models/opencray_scheduled_tasks.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
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
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {}

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async =>
      throw StateError(_failureMessage);

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async => throw StateError(_failureMessage);

  @override
  Future<void> openWorkspaceEntry(String relativePath) async =>
      throw StateError(_failureMessage);

  @override
  Future<void> openExternalUri(String uri) async =>
      throw StateError(_failureMessage);

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) async => Clipboard.setData(ClipboardData(text: plainText));

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
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
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async => throw StateError(_failureMessage);

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
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async => throw StateError(_failureMessage);

  @override
  Future<void> showNativeToast(String message) async {}

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async => throw StateError(_failureMessage);

  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      const <OpenCrayAgentSnapshot>[];

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async => null;

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async =>
      throw StateError(_failureMessage);

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async => null;

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async => throw StateError(_failureMessage);

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async => const <OpenCrayImageReference>[];

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async => throw StateError(_failureMessage);

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
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async =>
      const OpenCrayNotificationSettingsSnapshot(
        masterEnabled: false,
        defaultDeliveryModeId: 'all',
        quietHoursEnabled: true,
        quietHoursStartMinutes: 1380,
        quietHoursEndMinutes: 480,
        approvalRequestsEnabled: true,
        approvalReminderEnabled: true,
        taskFinishedEnabled: true,
        taskFailedEnabled: true,
        scheduledWakeEnabled: true,
        backgroundTaskPausedEnabled: true,
        serviceRecoveredEnabled: true,
      );

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      throw StateError(_failureMessage);

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      const OpenCrayStrongBackgroundSnapshot(
        source: 'strong-background',
        available: false,
        tierId: 'baseline',
        setupComplete: false,
        recommendedActionIds: <String>[],
        notifications: OpenCrayStrongBackgroundNotificationsSnapshot(),
        exactAlarms: OpenCrayStrongBackgroundExactAlarmSnapshot(),
        batteryOptimization:
            OpenCrayStrongBackgroundBatteryOptimizationSnapshot(),
        actions: <OpenCrayStrongBackgroundActionSnapshot>[
          OpenCrayStrongBackgroundActionSnapshot(
            id: 'open_notification_settings',
            available: false,
            recommended: false,
          ),
          OpenCrayStrongBackgroundActionSnapshot(
            id: 'open_exact_alarm_settings',
            available: false,
            recommended: false,
          ),
          OpenCrayStrongBackgroundActionSnapshot(
            id: 'open_battery_optimization_settings',
            available: false,
            recommended: false,
          ),
          OpenCrayStrongBackgroundActionSnapshot(
            id: 'request_ignore_battery_optimizations',
            available: false,
            recommended: false,
          ),
        ],
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult(
    source: 'strong-background-action',
    actionId: actionId,
    available: false,
    launched: false,
    reason: _failureMessage,
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      OpenCrayNetworkSearchConfigSnapshot(
        localeTag: 'en',
        title: 'Network & Search',
        subtitle: _failureMessage,
        slots: const <OpenCrayNetworkSearchSlotSnapshot>[],
      );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      const OpenCrayMediaSpeechConfigSnapshot(
        localeTag: 'en',
        title: 'Media & Speech',
        subtitle: 'The Android host bridge failed to initialize.',
        imageGeneration: OpenCrayMediaProviderConfigSnapshot(
          provider: 'Fal AI',
          baseUrl: '',
          endpoint: '',
          model: '',
        ),
        voiceGeneration: OpenCrayVoiceProviderConfigSnapshot(
          provider: 'OpenAI TTS',
          baseUrl: '',
          endpoint: '',
          voicePreset: '',
        ),
        sttRouteId: 'on_device_model',
        externalStt: OpenCrayMediaProviderConfigSnapshot(
          provider: 'OpenAI Whisper',
          baseUrl: '',
          endpoint: '',
          model: '',
        ),
        onDeviceModel: OpenCrayOnDeviceSttConfigSnapshot(
          modelPackage: 'Whisper Small',
          downloadStatus: 'Host unavailable',
        ),
      );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      const OpenCraySandboxSettingsSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'e2b',
        defaultBackend: 'local',
        sessionMode: 'ephemeral',
        autoResume: false,
        idleTimeoutMinutes: 15,
        startupTimeoutMs: 30000,
        requestTimeoutMs: 300000,
        timeoutAction: 'kill',
        templateId: '',
        e2bApiKey: '',
        apiKeyConfigured: false,
      );

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async =>
      const OpenCrayLlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        streamingEnabled: true,
        providerId: 'custom',
        selectedProviderOptionId: 'custom',
        protocol: 'openai',
        providerOptions: <OpenCrayLlmProviderOptionSnapshot>[
          OpenCrayLlmProviderOptionSnapshot(
            id: 'custom',
            providerId: 'custom',
            title: 'Custom provider',
            subtitle: 'The Android host bridge failed to initialize.',
            defaultBaseUrl: '',
            defaultModel: '',
            protocol: 'openai',
            apiKey: '',
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
  }) async => throw StateError(_failureMessage);

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
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayLlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    int? contextWindowTokensOverride,
  }) async =>
      OpenCrayLlmValidationResult(isSuccess: false, message: _failureMessage);

  @override
  Future<OpenCrayLlmConfigSnapshot> downloadOnDeviceLlmModel(
    String modelId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayLlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayLlmConfigSnapshot> deleteOnDeviceLlmModel(
    String modelId,
  ) async => throw StateError(_failureMessage);

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
    formatKey: 'host_unavailable',
    formatLabel: 'Host unavailable',
    confidence: 'low',
    usesExistingImporter: false,
    needsManualSelection: true,
    notes: <String>[_failureMessage],
  );

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
  Future<OpenCraySafetySettingsSnapshot>
  loadSafetySettings() async => const OpenCraySafetySettingsSnapshot(
    automationModeId: 'auto',
    rollbackJournalEnabled: true,
    maxFilesPerBatch: 20,
    maxAgentTurns: 0,
    maxToolCalls: 0,
    undoWindowHours: 24,
    fileChangesPolicyId: 'inherit',
    fileDeletesPolicyId: 'inherit',
    shellCommandsPolicyId: 'inherit',
    externalAccessModeId: 'select_paths',
    locations: <OpenCraySafetySettingsLocationSnapshot>[
      OpenCraySafetySettingsLocationSnapshot(
        id: 'photo_library',
        enabled: true,
      ),
      OpenCraySafetySettingsLocationSnapshot(id: 'downloads', enabled: true),
      OpenCraySafetySettingsLocationSnapshot(id: 'documents', enabled: false),
      OpenCraySafetySettingsLocationSnapshot(id: 'recordings', enabled: false),
    ],
    workspaceAccessProfileId: 'work',
    readOnlyOutsideWorkspace: true,
    memoryToolsEnabled: true,
  );

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async => true;

  @override
  Future<OpenCraySafetySettingsSnapshot> saveSafetySettings({
    required String automationModeId,
    required bool rollbackJournalEnabled,
    required int maxFilesPerBatch,
    int maxAgentTurns = 0,
    int maxToolCalls = 0,
    required int undoWindowHours,
    required String fileChangesPolicyId,
    required String fileDeletesPolicyId,
    required String shellCommandsPolicyId,
    required String externalAccessModeId,
    required bool photoLibraryEnabled,
    required bool downloadsEnabled,
    required bool documentsEnabled,
    required bool recordingsEnabled,
    required String workspaceAccessProfileId,
    required bool readOnlyOutsideWorkspace,
    String liveContextModeId = 'full',
    bool memoryToolsEnabled = true,
    String? subAgentContextDefaultModeId,
    Map<String, String> subAgentContextProfileOverrides = const <String, String>{},
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => const OpenCraySkillsSnapshot(
    installedSkills: <OpenCrayInstalledSkillSnapshot>[],
    installSources: <OpenCraySkillInstallSourceSnapshot>[
      OpenCraySkillInstallSourceSnapshot(
        id: 'curated-library',
        title: 'Curated skills',
        subtitle: 'The Android host bridge failed to initialize.',
        ctaLabel: 'Unavailable',
        isAvailable: false,
      ),
      OpenCraySkillInstallSourceSnapshot(
        id: 'local-path',
        title: 'Local path',
        subtitle: 'The Android host bridge failed to initialize.',
        ctaLabel: 'Unavailable',
        isAvailable: false,
      ),
      OpenCraySkillInstallSourceSnapshot(
        id: 'github-url',
        title: 'GitHub URL',
        subtitle: 'The Android host bridge failed to initialize.',
        ctaLabel: 'Unavailable',
        isAvailable: false,
      ),
      OpenCraySkillInstallSourceSnapshot(
        id: 'gitlab-url',
        title: 'GitLab URL',
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
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) async =>
      _failureMessage;

  @override
  Future<String?> updateInstalledSkill(String skillId) async => _failureMessage;

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async => throw StateError(_failureMessage);

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => _failureMessage;

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async => _failureMessage;

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
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => null;

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
  Stream<OpenCrayChatLiveAssistantDraftEvent> watchLiveAssistantDraftEvents() =>
      const Stream<OpenCrayChatLiveAssistantDraftEvent>.empty();

  @override
  Stream<OpenCrayChatRuntimeEventDelta> watchRuntimeEventDeltas() =>
      const Stream<OpenCrayChatRuntimeEventDelta>.empty();

  @override
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async =>
      null;

  @override
  Future<OpenCrayMemoryDebugSnapshot> loadMemoryDebugSnapshot() async =>
      const OpenCrayMemoryDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugRecordSnapshot>[],
      );

  @override
  Future<OpenCrayMemoryDebugLinksSnapshot>
  loadMemoryDebugLinksSnapshot() async =>
      const OpenCrayMemoryDebugLinksSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        records: <OpenCrayMemoryDebugLinksEntrySnapshot>[],
      );

  @override
  Future<OpenCraySoulDebugSnapshot> loadSoulDebugSnapshot() async =>
      const OpenCraySoulDebugSnapshot(
        sessionId: '',
        observedAtEpochMs: 0,
        overlayRecords: <OpenCrayMemoryDebugRecordSnapshot>[],
        fieldSources: <OpenCraySoulFieldSourceSnapshot>[],
      );

  @override
  Future<OpenCrayDebugPythonRunResult> runDebugPythonScript({
    required String fileName,
    required String scriptText,
  }) async => throw StateError(_failureMessage);

  @override
  Future<OpenCrayMemoryDebugSearchSnapshot> searchMemoryDebug({
    required String query,
    int maxResults = 4,
    int minScore = 1,
  }) async => OpenCrayMemoryDebugSearchSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    query: query,
  );

  @override
  Future<OpenCrayMemoryDebugSliceSnapshot> getMemoryDebugSlice({
    required String path,
    int? fromLine,
    int lines = 12,
  }) async => OpenCrayMemoryDebugSliceSnapshot(
    sessionId: '',
    observedAtEpochMs: 0,
    path: path,
    startLine: fromLine ?? 1,
    endLine: (fromLine ?? 1) + lines - 1,
  );

  @override
  Future<void> applyMemoryDebugAction({
    required String recordId,
    required String actionId,
  }) async {}

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async => null;

  @override
  Future<void> refreshSandboxSessionInfo() async {}

  @override
  Future<void> createChatSession() async {}

  @override
  Future<void> copyChatSession(String sessionId) async {}

  @override
  Future<void> deleteChatSession(String sessionId) async {}

  @override
  Future<void> selectChatSession(String sessionId) async {}

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) async {}

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) async {}

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) async {}

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async => const <OpenCrayChatDraftAttachment>[];

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async => null;

  @override
  Future<void> approveChatApproval(String approvalId) async {}

  @override
  Future<void> approveChatApprovalForSession(String approvalId) async {}

  @override
  Future<void> approveChatApprovalAsBatch(String approvalId) async {}

  @override
  Future<void> rejectChatApproval(String approvalId) async {}

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) async {}

  @override
  Future<void> retryChatRun(String runIdOrTaskId) async {}
}
