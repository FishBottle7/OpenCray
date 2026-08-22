import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../app/opencray_tabs.dart';
import '../../features/settings/settings_models.dart';
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

part 'opencray_seed_settings_data.dart';
part 'opencray_seed_files_data.dart';

class OpenCraySeedBridge implements OpenCrayHostBridge {
  OpenCraySeedBridge({
    OpenCrayShellSnapshot? initialSnapshot,
    OpenCrayFilesSnapshot? initialFilesSnapshot,
    OpenCraySettingsOverviewSnapshot? initialSettingsOverview,
    OpenCrayLlmConfigSnapshot? initialLlmConfig,
    OpenCrayPersonalizationConfigSnapshot? initialPersonalizationConfig,
    OpenCrayMcpSettingsSnapshot? initialMcpSettings,
    OpenCrayNotificationSettingsSnapshot? initialNotificationSettings,
    OpenCraySafetySettingsSnapshot? initialSafetySettings,
    OpenCrayStrongBackgroundSnapshot? initialStrongBackgroundSnapshot,
    OpenCraySkillsSnapshot? initialSkillsSnapshot,
    OpenCrayChatSnapshot? initialChatSnapshot,
    OpenCraySandboxSettingsSnapshot? initialSandboxSettings,
    List<OpenCrayAgentSnapshot> initialAgents = const <OpenCrayAgentSnapshot>[],
    String? initialActiveAgentId,
  }) : _snapshot =
           initialSnapshot ??
           const OpenCrayShellSnapshot(
             initialTab: OpenCrayTab.chat,
             localeTag: 'en',
             hostLabel: 'HOST READY',
             hostSummary: 'Flutter shell is attached to a seed bridge.',
             isHostConnected: false,
           ),
       _filesSnapshot = initialFilesSnapshot ?? _buildSeedFilesSnapshot(),
       _textDocumentsByPath = Map<String, String>.from(
         _seedPreviewContentByPath,
       ),
       _settingsOverview =
           initialSettingsOverview ??
           const OpenCraySettingsOverviewSnapshot(
             eyebrow: 'APP SHELL',
             title: 'Settings',
             subtitle: 'Access, providers,\nand personal defaults.',
             deviceTitle: 'OpenCray on this device',
             deviceSummary: 'API routes: Search + Media',
             entries: <OpenCraySettingsHomeEntrySnapshot>[
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'notifications_background',
                 title: 'Notifications & Background',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'workspace_access',
                 title: 'Workspace Access',
               ),
               OpenCraySettingsHomeEntrySnapshot(routeId: 'llm', title: 'LLM'),
               OpenCraySettingsHomeEntrySnapshot(routeId: 'mcp', title: 'MCP'),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'api_integrations',
                 title: 'API Integrations',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'privacy_telemetry',
                 title: 'Privacy & Telemetry',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'safety_limits',
                 title: 'Safety & Limits',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'personalization',
                 title: 'Personalization',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'agents',
                 title: 'Agent',
               ),
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'about_version',
                 title: 'About & Version',
               ),
             ],
           ),
       _llmConfig =
           initialLlmConfig ??
           const OpenCrayLlmConfigSnapshot(
             localeTag: 'en',
             enabled: false,
             providerMode: 'cloud',
             providerId: 'openai',
             selectedProviderOptionId: 'openai',
             providerOptions: <OpenCrayLlmProviderOptionSnapshot>[
               OpenCrayLlmProviderOptionSnapshot(
                 id: 'openai',
                 providerId: 'openai',
                 title: 'OpenAI',
                 subtitle: 'Official OpenAI-compatible endpoint.',
                 defaultBaseUrl: 'https://api.openai.com/v1',
                 defaultModel: 'gpt-4o-mini',
                 protocol: 'openai',
                 apiKey: '',
                 isCustom: false,
               ),
               OpenCrayLlmProviderOptionSnapshot(
                 id: 'custom',
                 providerId: 'custom',
                 title: 'Custom provider',
                 subtitle:
                     'Any OpenAI-compatible, OpenAI Responses, or Anthropic endpoint.',
                 defaultBaseUrl: '',
                 defaultModel: '',
                 protocol: 'openai',
                 apiKey: '',
                 isCustom: true,
               ),
             ],
             protocol: 'openai',
             providerName: 'OpenAI',
             providerNotes: '',
             baseUrl: 'https://api.openai.com/v1',
             apiKey: '',
             model: 'gpt-4o-mini',
             reasoningEffort: 'medium',
             systemPrompt: '',
             helperText:
                 'Seed bridge stores LLM settings locally. Base URL and API key must be ready before chat can call a provider.',
             onDeviceModels: <OpenCrayOnDeviceLlmModelOptionSnapshot>[
               OpenCrayOnDeviceLlmModelOptionSnapshot(
                 id: 'gemma-4-e2b-it',
                 title: 'Gemma 4 E2B',
                 subtitle: 'Instruction-tuned Gemma 4 E2B for LiteRT-LM.',
                 sizeLabel: '2.58 GB',
                 fileSizeBytes: 2583085056,
                 installState: 'ready',
                 downloadedBytes: 2583085056,
                 downloadBytesPerSecond: 0,
                 sha256Verified: true,
               ),
               OpenCrayOnDeviceLlmModelOptionSnapshot(
                 id: 'gemma-4-e4b-it',
                 title: 'Gemma 4 E4B',
                 subtitle: 'Instruction-tuned Gemma 4 E4B for LiteRT-LM.',
                 sizeLabel: '3.65 GB',
                 fileSizeBytes: 3654467584,
                 installState: 'not_downloaded',
               ),
             ],
             selectedOnDeviceModelId: 'gemma-4-e2b-it',
             onDeviceMaxContextWindow: 32768,
             onDeviceMaxTokens: 4096,
             onDeviceTopK: 40,
             onDeviceTopP: 0.95,
             onDeviceTemperature: 0.70,
             onDeviceAccelerator: 'gpu',
             onDeviceThinkingEnabled: false,
           ),
       _personalizationConfig =
           initialPersonalizationConfig ?? _buildSeedPersonalizationConfig(),
       _mcpSettings = initialMcpSettings ?? _buildSeedMcpSettings(),
       _notificationSettings =
           initialNotificationSettings ??
           const OpenCrayNotificationSettingsSnapshot(
             masterEnabled: true,
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
           ),
       _safetySettings =
           initialSafetySettings ??
           const OpenCraySafetySettingsSnapshot(
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
               OpenCraySafetySettingsLocationSnapshot(
                 id: 'downloads',
                 enabled: true,
               ),
               OpenCraySafetySettingsLocationSnapshot(
                 id: 'documents',
                 enabled: false,
               ),
               OpenCraySafetySettingsLocationSnapshot(
                 id: 'recordings',
                 enabled: false,
               ),
             ],
             workspaceAccessProfileId: 'work',
             readOnlyOutsideWorkspace: true,
           ),
       _strongBackgroundSnapshot =
           initialStrongBackgroundSnapshot ??
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
           ),
       _skillsSnapshot =
           initialSkillsSnapshot ??
           const OpenCraySkillsSnapshot(
             installedSkills: <OpenCrayInstalledSkillSnapshot>[
               OpenCrayInstalledSkillSnapshot(
                 id: 'find-skills',
                 name: 'find-skills',
                 description: 'Discover and install additional skill packages.',
                 isEnabled: true,
                 canDelete: false,
                 sourceDirectoryPath: '/seed/skills/find-skills',
               ),
               OpenCrayInstalledSkillSnapshot(
                 id: 'skill-creator',
                 name: 'skill-creator',
                 description:
                     'Create or update a skill package from reusable templates.',
                 isEnabled: true,
                 canDelete: false,
                 sourceDirectoryPath: '/seed/skills/skill-creator',
               ),
             ],
             installSources: <OpenCraySkillInstallSourceSnapshot>[
               OpenCraySkillInstallSourceSnapshot(
                 id: 'curated-library',
                 title: 'Curated skills',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
               OpenCraySkillInstallSourceSnapshot(
                 id: 'local-path',
                 title: 'Local path',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
               OpenCraySkillInstallSourceSnapshot(
                 id: 'github-url',
                 title: 'GitHub URL',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
               OpenCraySkillInstallSourceSnapshot(
                 id: 'gitlab-url',
                 title: 'GitLab URL',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
             ],
             suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
           ),
       _agents = initialAgents
           .map((OpenCrayAgentSnapshot agent) => agent.copyWith())
           .toList(growable: true),
       _activeAgentId = _seedInitialActiveAgentId(
         initialAgents,
         initialActiveAgentId,
       ),
       _chatSnapshot =
           initialChatSnapshot ??
           const OpenCrayChatSnapshot(
             screenTitle: 'Chat',
             modeLabel: 'SEED',
             sessionButtonLabel: 'Sessions',
             composerPlaceholder: 'Message OpenCray',
             summary: OpenCrayChatSummarySnapshot(
               title: 'Seed session',
               badge: 'Demo',
               body: 'Flutter chat is attached to a seed bridge.',
             ),
             messages: <OpenCrayChatMessageSnapshot>[
               OpenCrayChatMessageSnapshot(
                 messageId: 'seed-message-1',
                 kind: 'inbound',
                 text: 'Seed bridge ready.',
               ),
             ],
             drawer: OpenCrayChatDrawerSnapshot(
               eyebrow: 'Recent sessions',
               title: 'Recent sessions',
               ctaLabel: 'New session',
               sessions: <OpenCrayChatSessionItemSnapshot>[
                 OpenCrayChatSessionItemSnapshot(
                   sessionId: 'seed-session',
                   title: 'Seed session',
                   preview: 'Flutter chat is attached to a seed bridge.',
                   meta: '1 message',
                   isSelected: true,
                 ),
               ],
             ),
             isInputEnabled: true,
           ),
       _sandboxSettings =
           initialSandboxSettings ??
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

  String? _lastCopiedPlainText;
  String? _lastCopiedHtmlText;

  @visibleForTesting
  String? get lastCopiedPlainText => _lastCopiedPlainText;

  @visibleForTesting
  String? get lastCopiedHtmlText => _lastCopiedHtmlText;

  final StreamController<OpenCrayShellSnapshot> _controller =
      StreamController<OpenCrayShellSnapshot>.broadcast();
  final StreamController<OpenCraySettingsOverviewSnapshot> _settingsController =
      StreamController<OpenCraySettingsOverviewSnapshot>.broadcast();
  final StreamController<OpenCraySkillsSnapshot> _skillsController =
      StreamController<OpenCraySkillsSnapshot>.broadcast();
  final StreamController<OpenCrayChatSnapshot> _chatController =
      StreamController<OpenCrayChatSnapshot>.broadcast();
  final List<String> _shownNativeToasts = <String>[];
  OpenCrayShellSnapshot _snapshot;
  OpenCrayFilesSnapshot _filesSnapshot;
  Map<String, String> _textDocumentsByPath;
  OpenCraySettingsOverviewSnapshot _settingsOverview;
  OpenCrayNetworkSearchConfigSnapshot _networkSearchConfig =
      const OpenCrayNetworkSearchConfigSnapshot(
        localeTag: 'en',
        title: 'Network & Search',
        subtitle: 'Add API keys here. Enabled slots run top to bottom.',
        slots: <OpenCrayNetworkSearchSlotSnapshot>[
          OpenCrayNetworkSearchSlotSnapshot(
            id: 'seed-search-1',
            providerId: 'exa',
            label: 'Primary Exa',
            baseUrl: '',
            model: '',
            apiKey: 'sk_live_demo_7Q9K',
            enabled: true,
          ),
          OpenCrayNetworkSearchSlotSnapshot(
            id: 'seed-search-2',
            providerId: 'tavily',
            label: 'Tavily Backup',
            baseUrl: '',
            model: '',
            apiKey: 'tvly-demo-BK24',
            enabled: true,
          ),
        ],
      );
  OpenCrayMediaSpeechConfigSnapshot _mediaSpeechConfig =
      const OpenCrayMediaSpeechConfigSnapshot(
        localeTag: 'en',
        title: 'Media & Speech',
        subtitle: 'Configure media APIs and STT routing.',
        imageGeneration: OpenCrayMediaProviderConfigSnapshot(
          provider: 'Fal AI',
          baseUrl: 'https://api.fal.ai',
          endpoint: '/v1/images',
          model: 'flux-pro',
        ),
        voiceGeneration: OpenCrayVoiceProviderConfigSnapshot(
          provider: 'OpenAI TTS',
          baseUrl: 'https://api.openai.com',
          endpoint: '/v1/audio/speech',
          voicePreset: 'alloy · calm',
        ),
        sttRouteId: 'on_device_model',
        externalStt: OpenCrayMediaProviderConfigSnapshot(
          provider: 'OpenAI Whisper',
          baseUrl: 'https://api.openai.com',
          endpoint: '/v1/audio/transcriptions',
          model: 'whisper-1',
        ),
        onDeviceModel: OpenCrayOnDeviceSttConfigSnapshot(
          modelPackage: 'Whisper Small',
          downloadStatus: 'Not downloaded · 1.4 GB',
        ),
      );
  OpenCrayLlmConfigSnapshot _llmConfig;
  OpenCrayPersonalizationConfigSnapshot _personalizationConfig;
  OpenCrayMcpSettingsSnapshot _mcpSettings;
  OpenCrayNotificationSettingsSnapshot _notificationSettings;
  final OpenCrayScheduledTasksSnapshot _scheduledTasks =
      const OpenCrayScheduledTasksSnapshot(
        tasks: <OpenCrayScheduledTaskSummary>[],
        totalCount: 0,
        enabledCount: 0,
      );
  OpenCraySafetySettingsSnapshot _safetySettings;
  final OpenCrayStrongBackgroundSnapshot _strongBackgroundSnapshot;
  OpenCraySkillsSnapshot _skillsSnapshot;
  OpenCrayChatSnapshot _chatSnapshot;
  OpenCraySandboxSettingsSnapshot _sandboxSettings;
  final List<OpenCrayAgentSnapshot> _agents;
  String? _activeAgentId;
  int _seedMessageCounter = 1;
  int _seedAgentCounter = 1;

  List<String> get shownNativeToasts =>
      List<String>.unmodifiable(_shownNativeToasts);

  @override
  Future<OpenCrayShellSnapshot> loadShellSnapshot() async => _snapshot;

  @override
  Stream<OpenCrayShellSnapshot> watchShellSnapshot() async* {
    yield _snapshot;
    yield* _controller.stream;
  }

  @override
  Future<void> saveShellDestination({
    required String selectedTab,
    String? settingsSubpage,
  }) async {
    final tab = _parseTab(selectedTab);
    final settingsPage = tab == OpenCrayTab.settings
        ? settingsPageFromRouteId(settingsSubpage ?? 'home')
        : SettingsPage.home;
    _snapshot = _snapshot.copyWith(
      initialTab: tab,
      initialSettingsPage: settingsPage,
    );
    _controller.add(_snapshot);
  }

  @override
  Future<OpenCrayFilesSnapshot> loadFilesSnapshot() async => _filesSnapshot;

  @override
  Future<OpenCraySandboxPreviewEmbedConfig> resolveSandboxPreviewEmbedConfig(
    String previewUrl,
  ) async => OpenCraySandboxPreviewEmbedConfig(
    previewUrl: previewUrl,
    providerId: '',
    headers: const <String, String>{},
    sessionMatched: false,
    accessTokenConfigured: false,
    unavailableReason:
        'Sandbox preview embedding is unavailable in the seed bridge.',
  );

  @override
  Future<OpenCrayFileImagePreview> loadWorkspaceImagePreview(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedImagePreview(node.name)) {
      throw StateError('Image preview is available for image files only.');
    }
    return OpenCrayFileImagePreview(
      name: node.name,
      relativePath: node.relativePath,
      bytes: base64Decode(_seedImagePreviewBase64),
      mimeType: _seedImagePreviewMimeTypeFor(node.name),
      width: 1,
      height: 1,
    );
  }

  @override
  Future<OpenCrayFileTextPreview> loadWorkspaceTextPreview(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text preview is available for text files only.');
    }
    return OpenCrayFileTextPreview(
      name: node.name,
      relativePath: node.relativePath,
      content: _seedTextPreviewContentFor(
        relativePath: node.relativePath,
        name: node.name,
        documentsByPath: _textDocumentsByPath,
      ),
      isTruncated: false,
    );
  }

  @override
  Future<OpenCrayFileVoicePlaybackSource> loadWorkspaceVoicePlaybackSource(
    String relativePath,
  ) async {
    throw StateError('Seed bridge does not support voice playback.');
  }

  @override
  Future<OpenCrayWorkspaceTextDocument> loadWorkspaceTextDocument(
    String relativePath,
  ) async {
    final normalizedPath = relativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedPath);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be previewed here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text editing is available for text files only.');
    }
    return OpenCrayWorkspaceTextDocument(
      name: node.name,
      relativePath: node.relativePath,
      content: _seedTextPreviewContentFor(
        relativePath: node.relativePath,
        name: node.name,
        documentsByPath: _textDocumentsByPath,
      ),
    );
  }

  @override
  Future<void> openWorkspaceEntry(String relativePath) async {
    throw StateError('Seed bridge does not support opening workspace files.');
  }

  @override
  Future<void> openExternalUri(String uri) async {
    throw StateError('Seed bridge does not support opening external links.');
  }

  @override
  Future<void> copyRichTextToClipboard({
    required String plainText,
    String? htmlText,
  }) async {
    _lastCopiedPlainText = plainText;
    _lastCopiedHtmlText = htmlText;
    await Clipboard.setData(ClipboardData(text: plainText));
  }

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceFolder({
    required String parentRelativePath,
    required String name,
  }) async {
    _filesSnapshot = _seedCreateWorkspaceFolder(
      _filesSnapshot,
      parentRelativePath: parentRelativePath,
      name: name,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> createWorkspaceTextFile({
    required String parentRelativePath,
    required String name,
  }) async {
    _filesSnapshot = _seedCreateWorkspaceTextFile(
      _filesSnapshot,
      parentRelativePath: parentRelativePath,
      name: name,
    );
    _textDocumentsByPath[_joinSeedPath(
          parentRelativePath.trim(),
          name.trim(),
        )] =
        '';
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> renameWorkspaceEntry({
    required String targetRelativePath,
    required String newName,
  }) async {
    final normalizedTarget = targetRelativePath.trim();
    final renamedPath = _joinSeedPath(
      _seedParentPath(normalizedTarget),
      newName.trim(),
    );
    _filesSnapshot = _seedRenameWorkspaceEntry(
      _filesSnapshot,
      targetRelativePath: normalizedTarget,
      newName: newName,
    );
    _textDocumentsByPath = _seedRenameTextDocuments(
      _textDocumentsByPath,
      fromRelativePath: normalizedTarget,
      toRelativePath: renamedPath,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> deleteWorkspaceEntries(
    List<String> relativePaths,
  ) async {
    final targets = relativePaths
        .map((path) => path.trim())
        .where((path) => path.isNotEmpty)
        .toSet();
    _filesSnapshot = _seedDeleteWorkspaceEntries(_filesSnapshot, relativePaths);
    _textDocumentsByPath = _seedDeleteTextDocuments(
      _textDocumentsByPath,
      targets,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> pasteWorkspaceEntries({
    required List<String> sourceRelativePaths,
    required String destinationRelativePath,
    required bool move,
  }) async {
    final normalizedDestination = destinationRelativePath.trim();
    final normalizedSources = sourceRelativePaths
        .map((path) => path.trim())
        .where((path) => path.isNotEmpty)
        .toList(growable: false);
    final activeSourcePaths = _seedActiveTransferSourcePaths(
      snapshot: _filesSnapshot,
      sourceRelativePaths: normalizedSources,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    _filesSnapshot = _seedPasteWorkspaceEntries(
      _filesSnapshot,
      sourceRelativePaths: normalizedSources,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    _textDocumentsByPath = _seedPasteTextDocuments(
      _textDocumentsByPath,
      sourceRelativePaths: activeSourcePaths,
      destinationRelativePath: normalizedDestination,
      move: move,
    );
    return _filesSnapshot;
  }

  @override
  Future<OpenCrayFilesSnapshot> saveWorkspaceTextDocument({
    required String targetRelativePath,
    required String content,
  }) async {
    final normalizedTarget = targetRelativePath.trim();
    final node = _findSeedNode(_filesSnapshot.children, normalizedTarget);
    if (node == null) {
      throw StateError('The selected file no longer exists.');
    }
    if (node.isDirectory) {
      throw StateError("Folders can't be edited here.");
    }
    if (!_supportsSeedTextPreview(node.name)) {
      throw StateError('Text editing is available for text files only.');
    }
    _textDocumentsByPath[normalizedTarget] = content;
    _filesSnapshot = _seedUpdateWorkspaceFileSize(
      _filesSnapshot,
      targetRelativePath: normalizedTarget,
      sizeBytes: utf8.encode(content).length,
    );
    return _filesSnapshot;
  }

  @override
  Future<void> shareWorkspaceEntries(List<String> relativePaths) async {
    throw StateError('Seed bridge does not support file sharing.');
  }

  @override
  Future<OpenCraySavedWorkspaceMediaAttachment> saveWorkspaceMediaAttachment({
    required String relativePath,
    required String kind,
  }) async {
    throw StateError('Seed bridge does not support media saving.');
  }

  @override
  Future<void> showNativeToast(String message) async {
    final normalized = message.trim();
    if (normalized.isEmpty) {
      return;
    }
    _shownNativeToasts.add(normalized);
  }

  @override
  Future<List<OpenCraySettingsImageAsset>> listSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> pickSettingsImageAssets() async =>
      const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCraySettingsImageAsset>> importSettingsImageAssets(
    List<String> uriStrings,
  ) async => const <OpenCraySettingsImageAsset>[];

  @override
  Future<List<OpenCrayAgentSnapshot>> listAgents() async =>
      _materializeAgents();

  @override
  Future<OpenCrayAgentSnapshot?> loadActiveAgent() async {
    final activeAgentId = _activeAgentId;
    if (activeAgentId == null) {
      return null;
    }
    for (final agent in _materializeAgents()) {
      if (agent.agentId == activeAgentId) {
        return agent;
      }
    }
    return null;
  }

  @override
  Future<OpenCrayAgentSnapshot> createAgent(
    OpenCrayAgentCreateRequest request,
  ) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final agentId = _allocateSeedAgentId();
    final snapshot = OpenCrayAgentSnapshot(
      agentId: agentId,
      displayName: request.displayName.trim().isEmpty
          ? 'Untitled agent'
          : request.displayName.trim(),
      presetName: request.presetName.trim().isEmpty
          ? 'steady'
          : request.presetName.trim(),
      plasticity: request.plasticity.trim().isEmpty
          ? 'medium'
          : request.plasticity.trim(),
      mode: request.mode.trim().isEmpty ? 'full' : request.mode.trim(),
      callsYou: request.callsYou.trim(),
      addressStyle: request.addressStyle.trim(),
      voiceSummary: request.voiceSummary.trim(),
      verbosity: request.verbosity.trim(),
      relationshipStyle: request.relationshipStyle.trim(),
      riskTolerance: request.riskTolerance.trim(),
      toolUseBias: request.toolUseBias.trim(),
      baseDescription: request.baseDescription.trim(),
      collaborationGuidance: request.collaborationGuidance.trim(),
      escalationRules: request.escalationRules.trim(),
      forbiddenBehaviors: request.forbiddenBehaviors.trim(),
      llm: request.llm,
      avatar: request.avatar,
      imageReferences: List<OpenCrayAgentImageReferenceConfig>.from(
        request.imageReferences,
        growable: false,
      ),
      activeSessionId: 'seed-session-$agentId',
      avatarSeed: request.avatar?.settingsAssetId ?? request.displayName.trim(),
      createdAtEpochMs: now,
      updatedAtEpochMs: now,
    );
    _agents.insert(0, snapshot);
    if (request.activateOnCreate || _activeAgentId == null) {
      _activeAgentId = agentId;
    }
    return _materializeAgent(snapshot);
  }

  @override
  Future<OpenCrayAgentSnapshot?> selectAgent(String agentId) async {
    final normalizedAgentId = agentId.trim();
    for (final agent in _agents) {
      if (agent.agentId == normalizedAgentId) {
        _activeAgentId = normalizedAgentId;
        return _materializeAgent(agent);
      }
    }
    throw StateError('Unknown seed agent: $normalizedAgentId');
  }

  @override
  Future<OpenCraySoulVisualIdentity?> loadSoulVisualIdentity() async => null;

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulPrimaryPortrait(
    OpenCrayImageReferenceSource source,
  ) async => null;

  @override
  Future<OpenCraySoulVisualIdentity?> saveSoulReferenceImage({
    required String refId,
    required OpenCrayImageReferenceSource source,
  }) async => null;

  @override
  Future<List<OpenCrayImageReference>> listMemoryImageReferences(
    String memoryId,
  ) async => const <OpenCrayImageReference>[];

  @override
  Future<OpenCrayMemoryImageReferenceResult?> attachMemoryImageReference({
    required String memoryId,
    required OpenCrayImageReferenceSource source,
    String? preferredMode,
  }) async => null;

  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _settingsOverview;

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() async* {
    yield _settingsOverview;
    yield* _settingsController.stream;
  }

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _seedSettingsDetailFor(routeId);

  @override
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async => _notificationSettings;

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async {
    _notificationSettings = snapshot;
    return _notificationSettings;
  }

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      _scheduledTasks;

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async => _strongBackgroundSnapshot;

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult(
    source: 'strong-background-action',
    actionId: actionId,
    available: false,
    launched: false,
    reason: 'Seed bridge does not support Android strong-background actions.',
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      _networkSearchConfig;

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async {
    _networkSearchConfig = OpenCrayNetworkSearchConfigSnapshot(
      localeTag: _networkSearchConfig.localeTag,
      title: _networkSearchConfig.title,
      subtitle: _networkSearchConfig.subtitle,
      slots: slots,
    );
    _refreshSettingsOverview();
    return _networkSearchConfig;
  }

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      _mediaSpeechConfig;

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async {
    _mediaSpeechConfig = snapshot;
    return _mediaSpeechConfig;
  }

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      _sandboxSettings;

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async {
    _sandboxSettings = snapshot;
    return _sandboxSettings;
  }

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

  @override
  Future<OpenCrayPersonalizationConfigSnapshot>
  loadPersonalizationConfig() async => _personalizationConfig;

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async {
    _personalizationConfig = _copySeedPersonalizationConfig(
      _personalizationConfig,
      presetId: presetId,
      customLabel: customLabel,
      customGuidance: customGuidance,
      lastResetMessage: '',
    );
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async {
    _personalizationConfig = _copySeedPersonalizationConfig(
      _personalizationConfig,
      selectedAppLanguageId: languageId,
    );
    _llmConfig = OpenCrayLlmConfigSnapshot(
      localeTag: languageId,
      enabled: _llmConfig.enabled,
      streamingEnabled: _llmConfig.streamingEnabled,
      providerId: _llmConfig.providerId,
      selectedProviderOptionId: _llmConfig.selectedProviderOptionId,
      protocol: _llmConfig.protocol,
      providerOptions: _llmConfig.providerOptions,
      providerName: _llmConfig.providerName,
      providerNotes: _llmConfig.providerNotes,
      baseUrl: _llmConfig.baseUrl,
      apiKey: _llmConfig.apiKey,
      model: _llmConfig.model,
      reasoningEffort: _llmConfig.reasoningEffort,
      systemPrompt: _llmConfig.systemPrompt,
      helperText: _llmConfig.helperText,
      openAiPromptCacheKeyStrategy: _llmConfig.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: _llmConfig.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: _llmConfig.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: _llmConfig.anthropicPromptCacheTtl,
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
    _snapshot = _snapshot.copyWith(localeTag: languageId);
    update(_snapshot);
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

  @override
  Future<OpenCrayPersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async {
    switch (scopeId) {
      case 'memory':
        _personalizationConfig = _copySeedPersonalizationConfig(
          _personalizationConfig,
          queueBody:
              'Seed mode cleared local memory cues and left the rest of the device state intact.',
          queueIsIdle: true,
          lastResetTitle: 'Last reset',
          lastResetMessage:
              'Cleared the app-local memory and history stores. The soul profile, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata were left untouched.',
        );
        break;
      case 'soul':
        _personalizationConfig = _copySeedPersonalizationConfig(
          _personalizationConfig,
          presetId: 'steady',
          customLabel: '',
          customGuidance: '',
          queueBody:
              'Seed mode restored the default voice and cleared the custom overlay.',
          queueIsIdle: true,
          lastResetTitle: 'Last reset',
          lastResetMessage:
              'Cleared the local personality and soul profile and reset the editor to defaults. Memory and history stores, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata were left untouched.',
        );
        break;
      default:
        throw ArgumentError.value(
          scopeId,
          'scopeId',
          'Unsupported personalization reset id.',
        );
    }
    _refreshSettingsOverview();
    return _personalizationConfig;
  }

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
    formatKey: 'seed_mode',
    formatLabel: 'Seed mode probe',
    confidence: 'low',
    usesExistingImporter: false,
    needsManualSelection: true,
    notes: const <String>[
      'Seed bridge does not inspect local corpus files. Wire this call to the Android host or local runtime bridge.',
    ],
  );

  @override
  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings() async => _mcpSettings;

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async {
    _mcpSettings = _copySeedMcpSettings(_mcpSettings, masterEnabled: enabled);
    return _mcpSettings;
  }

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async {
    _mcpSettings = _copySeedMcpSettings(
      _mcpSettings,
      serverOverrides: <String, bool>{serverId: enabled},
    );
    return _mcpSettings;
  }

  @override
  Future<OpenCraySafetySettingsSnapshot> loadSafetySettings() async =>
      _safetySettings;

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
  }) async {
    _safetySettings = OpenCraySafetySettingsSnapshot(
      automationModeId: automationModeId,
      rollbackJournalEnabled: rollbackJournalEnabled,
      maxFilesPerBatch: maxFilesPerBatch,
      maxAgentTurns: maxAgentTurns,
      maxToolCalls: maxToolCalls,
      undoWindowHours: undoWindowHours,
      fileChangesPolicyId: fileChangesPolicyId,
      fileDeletesPolicyId: fileDeletesPolicyId,
      shellCommandsPolicyId: shellCommandsPolicyId,
      externalAccessModeId: externalAccessModeId,
      locations: <OpenCraySafetySettingsLocationSnapshot>[
        OpenCraySafetySettingsLocationSnapshot(
          id: 'photo_library',
          enabled: photoLibraryEnabled,
        ),
        OpenCraySafetySettingsLocationSnapshot(
          id: 'downloads',
          enabled: downloadsEnabled,
        ),
        OpenCraySafetySettingsLocationSnapshot(
          id: 'documents',
          enabled: documentsEnabled,
        ),
        OpenCraySafetySettingsLocationSnapshot(
          id: 'recordings',
          enabled: recordingsEnabled,
        ),
      ],
      workspaceAccessProfileId: workspaceAccessProfileId,
      readOnlyOutsideWorkspace: readOnlyOutsideWorkspace,
      liveContextModeId: liveContextModeId,
      memoryToolsEnabled: memoryToolsEnabled,
      subAgentContextDefaultModeId: subAgentContextDefaultModeId,
      subAgentContextProfileOverrides:
          Map<String, String>.unmodifiable(subAgentContextProfileOverrides),
    );
    return _safetySettings;
  }

  @override
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot({
    String query = '',
    int? suggestedLimit,
  }) async => _skillsSnapshot;

  @override
  Stream<OpenCraySkillsSnapshot> watchSkillsSnapshot() async* {
    yield _skillsSnapshot;
    yield* _skillsController.stream;
  }

  @override
  Future<void> setSkillEnabled(String skillId, bool enabled) async {
    _skillsSnapshot = OpenCraySkillsSnapshot(
      installedSkills: _skillsSnapshot.installedSkills
          .map(
            (skill) => skill.id == skillId
                ? OpenCrayInstalledSkillSnapshot(
                    id: skill.id,
                    name: skill.name,
                    description: skill.description,
                    isEnabled: enabled,
                    canDelete: skill.canDelete,
                    sourceDirectoryPath: skill.sourceDirectoryPath,
                  )
                : skill,
          )
          .toList(growable: false),
      installSources: _skillsSnapshot.installSources,
      suggestedSkills: _skillsSnapshot.suggestedSkills,
      suggestedSkillsMayHaveMore: _skillsSnapshot.suggestedSkillsMayHaveMore,
    );
    _emitSkillsSnapshot();
  }

  @override
  Future<String?> refreshSkills() async =>
      'Seed bridge refreshed local skills.';

  @override
  Future<String?> checkInstalledSkillUpdates({String skillId = ''}) async =>
      'Seed bridge does not support skill update checks.';

  @override
  Future<String?> updateInstalledSkill(String skillId) async =>
      'Seed bridge does not support updating installed skills.';

  @override
  Future<OpenCraySkillSourceInspectionSnapshot> inspectSkillSource(
    String sourceRef,
  ) async =>
      throw StateError('Seed bridge does not support skill source inspection.');

  @override
  Future<String?> installSkillSource(
    String sourceRef, {
    String selectedSkillName = '',
  }) async => 'Seed bridge does not support skill installation.';

  @override
  Future<String?> installSkillSourceBatch(
    String sourceRef, {
    List<String> selectedSkillNames = const <String>[],
  }) async => 'Seed bridge does not support skill installation.';

  @override
  Future<String?> installSuggestedSkill(String skillId) async =>
      'Seed bridge does not support skill installation.';

  @override
  Future<String?> deleteInstalledSkill(String skillId) async =>
      'Seed bridge does not support deleting installed skills.';

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSkillInstructions(
    String skillId,
  ) async {
    final skill = _skillsSnapshot.installedSkills
        .cast<OpenCrayInstalledSkillSnapshot?>()
        .firstWhere(
          (candidate) => candidate?.id == skillId,
          orElse: () => null,
        );
    if (skill == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot(
      id: skill.id,
      name: skill.name,
      description: skill.description,
      markdownBody: 'Seed bridge preview is not backed by the Android host.',
      sourceDirectoryPath: skill.sourceDirectoryPath,
      isEnabled: skill.isEnabled,
      canDelete: skill.canDelete,
    );
  }

  @override
  Future<OpenCraySkillInstructionsSnapshot?> loadSuggestedSkillInstructions(
    String sourceRef, {
    String selectedSkillName = '',
  }) async {
    final suggestion = _skillsSnapshot.suggestedSkills
        .cast<OpenCraySuggestedSkillSnapshot?>()
        .firstWhere(
          (candidate) =>
              candidate?.sourceRef == sourceRef &&
              (selectedSkillName.trim().isEmpty ||
                  candidate?.name == selectedSkillName),
          orElse: () => null,
        );
    if (suggestion == null) {
      return null;
    }
    return OpenCraySkillInstructionsSnapshot(
      id: suggestion.id,
      name: suggestion.name,
      description: suggestion.description,
      markdownBody: 'Seed bridge preview is not backed by the Android host.',
      sourceDirectoryPath: suggestion.detailUrl.isNotEmpty
          ? suggestion.detailUrl
          : suggestion.sourceRef,
      isEnabled: false,
      canDelete: false,
    );
  }

  @override
  Future<OpenCrayChatSnapshot> loadChatSnapshot() async => _chatSnapshot;

  @override
  Stream<OpenCrayChatSnapshot> watchChatSnapshot() async* {
    yield _chatSnapshot;
    yield* _chatController.stream;
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
  }) async => throw UnsupportedError(
    'Seed bridge does not support embedded Python execution.',
  );

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
  Future<void> createChatSession() async {
    _chatSnapshot = const OpenCrayChatSnapshot(
      screenTitle: 'Chat',
      modeLabel: 'SEED',
      sessionButtonLabel: 'Sessions',
      composerPlaceholder: 'Message OpenCray',
      summary: OpenCrayChatSummarySnapshot(
        title: 'New seed session',
        badge: 'Empty',
        body: 'This seed bridge session is empty.',
      ),
      messages: <OpenCrayChatMessageSnapshot>[],
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: 'Recent sessions',
        title: 'Recent sessions',
        ctaLabel: 'New session',
        sessions: <OpenCrayChatSessionItemSnapshot>[
          OpenCrayChatSessionItemSnapshot(
            sessionId: 'seed-session-new',
            title: 'New seed session',
            preview: '',
            meta: '0 messages',
            isSelected: true,
          ),
        ],
      ),
      isInputEnabled: true,
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> copyChatSession(String sessionId) async {
    OpenCrayChatSessionItemSnapshot? sourceSession;
    for (final session in _chatSnapshot.drawer.sessions) {
      if (session.sessionId == sessionId) {
        sourceSession = session;
        break;
      }
    }
    if (sourceSession == null) {
      return;
    }
    final copiedSession = OpenCrayChatSessionItemSnapshot(
      sessionId: '$sessionId-copy-${_chatSnapshot.drawer.sessions.length + 1}',
      title: _seedCopySessionTitle(sourceSession.title),
      preview: sourceSession.preview,
      meta: sourceSession.meta,
      isSelected: true,
    );
    final updatedSessions = <OpenCrayChatSessionItemSnapshot>[
      copiedSession,
      ..._chatSnapshot.drawer.sessions.map(
        (session) => OpenCrayChatSessionItemSnapshot(
          sessionId: session.sessionId,
          title: session.title,
          preview: session.preview,
          meta: session.meta,
          isSelected: false,
          unreadCount: session.unreadCount,
        ),
      ),
    ];
    _chatSnapshot = _copyChatSnapshotWith(
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      summary: OpenCrayChatSummarySnapshot(
        title: copiedSession.title,
        badge: _chatSnapshot.summary.badge,
        body: sourceSession.preview.isNotEmpty
            ? sourceSession.preview
            : _chatSnapshot.summary.body,
      ),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> deleteChatSession(String sessionId) async {
    final remainingSessions = _chatSnapshot.drawer.sessions
        .where((session) => session.sessionId != sessionId)
        .toList(growable: false);
    if (remainingSessions.length == _chatSnapshot.drawer.sessions.length) {
      return;
    }
    if (remainingSessions.isEmpty) {
      await createChatSession();
      return;
    }
    final selectedSession = remainingSessions.firstWhere(
      (session) => session.isSelected,
      orElse: () => remainingSessions.first,
    );
    final updatedSessions = remainingSessions
        .map(
          (session) => OpenCrayChatSessionItemSnapshot(
            sessionId: session.sessionId,
            title: session.title,
            preview: session.preview,
            meta: session.meta,
            isSelected: session.sessionId == selectedSession.sessionId,
            unreadCount: session.sessionId == selectedSession.sessionId
                ? 0
                : session.unreadCount,
          ),
        )
        .toList(growable: false);
    _chatSnapshot = _copyChatSnapshotWith(
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      summary: OpenCrayChatSummarySnapshot(
        title: selectedSession.title,
        badge: _chatSnapshot.summary.badge,
        body: selectedSession.preview.isNotEmpty
            ? selectedSession.preview
            : _chatSnapshot.summary.body,
      ),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> selectChatSession(String sessionId) async {}

  @override
  Future<void> branchChatSessionFromMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    OpenCrayChatSessionItemSnapshot? sourceSession;
    for (final session in _chatSnapshot.drawer.sessions) {
      if (session.sessionId == sessionId) {
        sourceSession = session;
        break;
      }
    }
    if (sourceSession == null) {
      return;
    }
    final int branchUntilIndex = _chatSnapshot.messages.indexWhere(
      (message) => message.messageId == messageId,
    );
    final List<OpenCrayChatMessageSnapshot> branchMessages =
        (branchUntilIndex >= 0
                ? _chatSnapshot.messages.take(branchUntilIndex + 1)
                : _chatSnapshot.messages)
            .toList(growable: false);
    final String preview = _seedChatSessionPreviewFromMessages(
      branchMessages,
      fallback: sourceSession.preview,
    );
    final branchSession = OpenCrayChatSessionItemSnapshot(
      sessionId:
          '$sessionId-branch-${_chatSnapshot.drawer.sessions.length + 1}',
      title: _seedBranchSessionTitle(sourceSession.title),
      preview: preview,
      meta: sourceSession.meta,
      isSelected: true,
    );
    final updatedSessions = <OpenCrayChatSessionItemSnapshot>[
      branchSession,
      ..._chatSnapshot.drawer.sessions.map(
        (session) => OpenCrayChatSessionItemSnapshot(
          sessionId: session.sessionId,
          title: session.title,
          preview: session.preview,
          meta: session.meta,
          isSelected: false,
          unreadCount: session.unreadCount,
        ),
      ),
    ];
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: OpenCrayChatSummarySnapshot(
        title: branchSession.title,
        badge: _chatSnapshot.summary.badge,
        body: preview.isNotEmpty ? preview : _chatSnapshot.summary.body,
      ),
      messages: branchMessages,
      drawer: OpenCrayChatDrawerSnapshot(
        eyebrow: _chatSnapshot.drawer.eyebrow,
        title: _chatSnapshot.drawer.title,
        ctaLabel: _chatSnapshot.drawer.ctaLabel,
        sessions: updatedSessions,
      ),
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> deleteChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    final updatedMessages = _chatSnapshot.messages
        .where((message) => message.messageId != messageId)
        .toList(growable: false);
    if (updatedMessages.length == _chatSnapshot.messages.length) {
      return;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: updatedMessages,
      drawer: _chatSnapshot.drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<void> recallChatMessage({
    required String sessionId,
    required String messageId,
  }) async {
    if (sessionId != _activeChatSessionId || messageId.trim().isEmpty) {
      return;
    }
    final recallIndex = _chatSnapshot.messages.indexWhere(
      (message) => message.messageId == messageId,
    );
    if (recallIndex < 0) {
      return;
    }
    final recalledMessage = _chatSnapshot.messages[recallIndex];
    if (recalledMessage.kind != 'outbound') {
      return;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: _chatSnapshot.messages
          .take(recallIndex)
          .toList(growable: false),
      drawer: _chatSnapshot.drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final String fileName = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'workspace-shot.png',
      OpenCrayChatDraftAttachmentKind.voice => 'voice-note.m4a',
      OpenCrayChatDraftAttachmentKind.file => 'mobile-ui-layout-spec.md',
    };
    final String mimeType = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'image/png',
      OpenCrayChatDraftAttachmentKind.voice => 'audio/mp4',
      OpenCrayChatDraftAttachmentKind.file => 'text/markdown',
    };
    return <OpenCrayChatDraftAttachment>[
      OpenCrayChatDraftAttachment(
        kind: kind,
        displayName: fileName,
        relativePath: '.opencray/seed/$fileName',
        mimeType: mimeType,
      ),
    ];
  }

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(
    String text, {
    List<OpenCrayChatDraftAttachment> attachments =
        const <OpenCrayChatDraftAttachment>[],
  }) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty && attachments.isEmpty) {
      return null;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: <OpenCrayChatMessageSnapshot>[
        ..._chatSnapshot.messages,
        OpenCrayChatMessageSnapshot(
          messageId: _seedMessageId('seed-outbound'),
          kind: 'outbound',
          text: trimmed,
          attachments: attachments
              .map(
                (OpenCrayChatDraftAttachment attachment) =>
                    OpenCrayChatAttachmentSnapshot(
                      attachmentId: attachment.id,
                      kind: attachment.kind.wireValue,
                      displayName: attachment.displayName,
                      localPath: attachment.relativePath,
                      mimeType: attachment.mimeType,
                      sizeBytes: attachment.sizeBytes,
                    ),
              )
              .toList(growable: false),
        ),
        OpenCrayChatMessageSnapshot(
          messageId: _seedMessageId('seed-inbound'),
          kind: 'inbound',
          text: 'Seed bridge stored your message locally.',
        ),
      ],
      drawer: _chatSnapshot.drawer,
      isInputEnabled: true,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
    return const OpenCrayChatRunSubmission(
      sessionId: 'seed-session',
      runId: 'seed-run',
      taskId: 'seed-task',
      acceptedAtEpochMs: 0,
    );
  }

  @override
  Future<void> approveChatApproval(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> approveChatApprovalForSession(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> rejectChatApproval(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

  @override
  Future<void> interruptChatRun(String runIdOrTaskId) async {}

  @override
  Future<void> retryChatRun(String runIdOrTaskId) async {}

  void update(OpenCrayShellSnapshot snapshot) {
    _snapshot = snapshot;
    if (!_controller.isClosed) {
      _controller.add(snapshot);
    }
  }

  void updateSettingsOverview(OpenCraySettingsOverviewSnapshot overview) {
    _settingsOverview = overview;
    if (!_settingsController.isClosed) {
      _settingsController.add(overview);
    }
  }

  void _refreshSettingsOverview() {
    final updatedOverview = OpenCraySettingsOverviewSnapshot(
      eyebrow: _settingsOverview.eyebrow,
      title: _settingsOverview.title,
      subtitle: _settingsOverview.subtitle,
      deviceTitle: _settingsOverview.deviceTitle,
      deviceSummary: 'API routes: Search + Media',
      entries: _settingsOverview.entries,
    );
    updateSettingsOverview(updatedOverview);
  }

  List<OpenCrayAgentSnapshot> _materializeAgents() {
    return _agents.map(_materializeAgent).toList(growable: false);
  }

  OpenCrayAgentSnapshot _materializeAgent(OpenCrayAgentSnapshot snapshot) {
    return snapshot.copyWith(isActive: snapshot.agentId == _activeAgentId);
  }

  String _allocateSeedAgentId() {
    while (true) {
      final candidate = 'seed-agent-${_seedAgentCounter++}';
      if (_agents.every((agent) => agent.agentId != candidate)) {
        return candidate;
      }
    }
  }

  Future<void> dispose() async {
    await _controller.close();
    await _settingsController.close();
    await _skillsController.close();
    await _chatController.close();
  }

  void _emitSkillsSnapshot() {
    if (!_skillsController.isClosed) {
      _skillsController.add(_skillsSnapshot);
    }
  }

  void _emitChatSnapshot() {
    if (!_chatController.isClosed) {
      _chatController.add(_chatSnapshot);
    }
  }

  String get _activeChatSessionId => _chatSnapshot.drawer.sessions
      .firstWhere(
        (session) => session.isSelected,
        orElse: () => _chatSnapshot.drawer.sessions.first,
      )
      .sessionId;

  String _seedMessageId(String prefix) {
    _seedMessageCounter += 1;
    return '$prefix-$_seedMessageCounter';
  }

  OpenCrayChatSnapshot _copyChatSnapshotWith({
    required OpenCrayChatDrawerSnapshot drawer,
    OpenCrayChatSummarySnapshot? summary,
  }) {
    return OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: summary ?? _chatSnapshot.summary,
      messages: _chatSnapshot.messages,
      drawer: drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: _chatSnapshot.pendingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
  }

  void _resolveChatApproval(String taskId) {
    final remainingApprovals = _chatSnapshot.pendingApprovals
        .where((approval) => approval.taskId != taskId)
        .toList(growable: false);
    if (remainingApprovals.length == _chatSnapshot.pendingApprovals.length) {
      return;
    }
    _chatSnapshot = OpenCrayChatSnapshot(
      screenTitle: _chatSnapshot.screenTitle,
      modeLabel: _chatSnapshot.modeLabel,
      sessionButtonLabel: _chatSnapshot.sessionButtonLabel,
      composerPlaceholder: _chatSnapshot.composerPlaceholder,
      summary: _chatSnapshot.summary,
      messages: _chatSnapshot.messages,
      drawer: _chatSnapshot.drawer,
      isInputEnabled: _chatSnapshot.isInputEnabled,
      todos: _chatSnapshot.todos,
      todoState: _chatSnapshot.todoState,
      todoHideDelayMs: _chatSnapshot.todoHideDelayMs,
      todoCompletedAtEpochMs: _chatSnapshot.todoCompletedAtEpochMs,
      pendingApprovals: remainingApprovals,
      runtimeActivity: _chatSnapshot.runtimeActivity,
      updatedAtEpochMs: _nextChatSnapshotUpdatedAt(),
    );
    _emitChatSnapshot();
  }

  int _nextChatSnapshotUpdatedAt() {
    final int now = DateTime.now().millisecondsSinceEpoch;
    final int previous = _chatSnapshot.updatedAtEpochMs;
    return now > previous ? now : previous + 1;
  }
}
