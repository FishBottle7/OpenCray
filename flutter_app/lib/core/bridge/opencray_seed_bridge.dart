import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
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
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import '../models/opencray_strong_background.dart';
import '../models/opencray_twin_import_source_probe.dart';
import '../models/opencray_workspace_text_document.dart';
import 'opencray_host_bridge.dart';

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
      onDeviceModels: _llmConfig.onDeviceModels,
      selectedOnDeviceModelId: _llmConfig.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: _llmConfig.onDeviceMaxContextWindow,
      onDeviceMaxTokens: _llmConfig.onDeviceMaxTokens,
      onDeviceTopK: _llmConfig.onDeviceTopK,
      onDeviceTopP: _llmConfig.onDeviceTopP,
      onDeviceTemperature: _llmConfig.onDeviceTemperature,
      onDeviceAccelerator: _llmConfig.onDeviceAccelerator,
      onDeviceThinkingEnabled: _llmConfig.onDeviceThinkingEnabled,
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
      pendingApprovals: _chatSnapshot.pendingApprovals,
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
      pendingApprovals: _chatSnapshot.pendingApprovals,
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
      pendingApprovals: _chatSnapshot.pendingApprovals,
    );
    _emitChatSnapshot();
  }

  @override
  Future<List<OpenCrayChatDraftAttachment>> pickChatAttachments({
    required OpenCrayChatDraftAttachmentKind kind,
  }) async {
    final String fileName = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'workspace-shot.png',
      OpenCrayChatDraftAttachmentKind.file => 'mobile-ui-layout-spec.md',
    };
    final String mimeType = switch (kind) {
      OpenCrayChatDraftAttachmentKind.image => 'image/png',
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
      pendingApprovals: _chatSnapshot.pendingApprovals,
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
      pendingApprovals: _chatSnapshot.pendingApprovals,
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
      pendingApprovals: remainingApprovals,
    );
    _emitChatSnapshot();
  }
}

String? _seedInitialActiveAgentId(
  List<OpenCrayAgentSnapshot> initialAgents,
  String? initialActiveAgentId,
) {
  final normalizedActiveAgentId = initialActiveAgentId?.trim();
  if (normalizedActiveAgentId != null && normalizedActiveAgentId.isNotEmpty) {
    return normalizedActiveAgentId;
  }
  for (final agent in initialAgents) {
    if (agent.isActive) {
      return agent.agentId;
    }
  }
  return null;
}

String _seedCopySessionTitle(String title) {
  if (title.endsWith(' copy')) {
    return title;
  }
  if (title.length >= 27) {
    return '${title.substring(0, 27)} copy';
  }
  return '$title copy';
}

String _seedBranchSessionTitle(String title) {
  if (title.endsWith(' branch')) {
    return title;
  }
  if (title.length >= 25) {
    return '${title.substring(0, 25)} branch';
  }
  return '$title branch';
}

String _seedChatSessionPreviewFromMessages(
  List<OpenCrayChatMessageSnapshot> messages, {
  required String fallback,
}) {
  for (final OpenCrayChatMessageSnapshot message in messages.reversed) {
    final String trimmed = message.text.trim();
    if (trimmed.isEmpty || message.kind == 'timeline') {
      continue;
    }
    return trimmed;
  }
  return fallback;
}

OpenCraySettingsDetailSnapshot _seedSettingsDetailFor(String routeId) {
  switch (routeId) {
    case 'notifications_background':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'notifications_background',
        title: 'Notifications & Background',
        subtitle: 'Control alerts, service visibility, and wakeups.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Notifications',
            helperText:
                'Notification and background controls are rendered by the Flutter settings page.',
          ),
        ],
      );
    case 'notification_channels':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'notification_channels',
        title: 'Notification Channels',
        subtitle: 'Choose which events can interrupt you.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Notification channels',
            helperText:
                'Notification channel controls are rendered by the Flutter settings page.',
          ),
        ],
      );
    case 'workspace_access':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'workspace_access',
        title: 'Workspace Access',
        subtitle: 'Choose where the agent can read, write, and ask first.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Access profile',
            segmentedOptions: <String>['WORK', 'ASK', 'OPEN'],
            segmentedIndex: 0,
            helperText: 'Profiles decide read and write scope.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Allowed roots',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'Review approved paths',
                subtitle: 'Keep file work inside approved folders.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Write behavior',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Read-only outside workspace',
                subtitle: 'Writes stay inside approved roots.',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Approved roots',
                valueLabel: '3',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Ask before edit',
                valueLabel: 'Always',
              ),
            ],
          ),
        ],
      );
    case 'llm':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'llm',
        title: 'LLM',
        subtitle: 'Select providers, routing, and response defaults.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Primary provider',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'OpenAI',
                subtitle: 'Best when you have many providers configured.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Model routing',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'Customize provider defaults',
                subtitle: 'Use stable defaults before per-chat overrides.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Runtime defaults',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Stream responses',
                subtitle: 'Show tokens as they arrive in chat.',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Fallback model',
                valueLabel: 'gpt-5',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Reasoning',
                valueLabel: 'Medium',
              ),
            ],
          ),
        ],
      );
    case 'mcp':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'mcp',
        title: 'MCP',
        subtitle: 'Control server discovery, approvals, and health checks.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Server policy',
            segmentedOptions: <String>['AUTO', 'ASK', 'OFF'],
            segmentedIndex: 0,
            helperText: 'Auto keeps common tools available.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Server registry',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'Review active servers',
                subtitle: 'Approve only the tools users can explain.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Runtime checks',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Registry health checks',
                subtitle: 'Warn when a server stops responding.',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Approved servers',
                valueLabel: '12',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Discovery mode',
                valueLabel: 'Auto',
              ),
            ],
          ),
        ],
      );
    case 'api_integrations':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'api_integrations',
        title: 'API Integrations',
        subtitle:
            'Choose where OpenCray connects for search, media generation, and speech services.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Routing rules',
            helperText:
                'Search keeps ordered slots. Media uses external APIs, while STT can switch between a hosted API and an on-device model package.',
          ),
        ],
      );
    case 'network_search':
    case 'privacy_telemetry':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'network_search',
        title: 'Network & Search',
        subtitle: 'Configure search slots, provider order, and retrieval keys.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Search slots',
            helperText:
                'Enabled provider keys run in order until one succeeds.',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.value(
                title: 'Configured slots',
                valueLabel: '2',
              ),
            ],
          ),
        ],
      );
    case 'media_speech':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'media_speech',
        title: 'Media & Speech',
        subtitle: 'Configure media APIs and STT routing.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Generation services',
            helperText:
                'Image and voice generation both use external API routes.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Speech-to-text',
            helperText:
                'Switch between a hosted API route and an on-device model package.',
          ),
        ],
      );
    case 'safety_limits':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'safety_limits',
        title: 'Safety & Limits',
        subtitle: 'Choose how much freedom the agent gets.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Automation mode',
            segmentedOptions: <String>['SAFE', 'AUTO', 'DEV'],
            segmentedIndex: 0,
            helperText:
                'Mode presets already control approvals and protected actions.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Advanced overrides',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'Customize sensitive actions',
                subtitle:
                    'Optional. Most people can stay with the mode preset.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Rollback & limits',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Rollback journal',
                subtitle: 'Keep reversible actions recoverable.',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Max files per batch',
                valueLabel: '20',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Undo window',
                valueLabel: '24 hours',
              ),
            ],
          ),
        ],
      );
    case 'about_version':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'about_version',
        title: 'About & Version',
        subtitle: 'See build details, release notes, and diagnostics.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Release channel',
            segmentedOptions: <String>['STABLE', 'BETA', 'EDGE'],
            segmentedIndex: 0,
            helperText: 'Channel sets how quickly updates appear.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Build details',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'View release notes',
                subtitle: 'Keep release notes and support info one tap away.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'App info',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Send compatibility info',
                subtitle: 'Include device details in diagnostics exports.',
                toggleValue: false,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Version',
                valueLabel: '0.8.4',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Commit',
                valueLabel: '4f2a',
              ),
            ],
          ),
        ],
      );
    case 'personalization':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'personalization',
        title: 'Personalization',
        subtitle: 'Tune voice, memory, and default tone for this device.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Tone preset',
            segmentedOptions: <String>['QUIET', 'FOCUS', 'WARM'],
            segmentedIndex: 0,
            helperText: 'Tone presets shape reply style and pacing.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Free editing',
            helperText: 'Write your own lasting guidance.',
            inlinePanelText:
                'Be direct, calm, and explicit about tradeoffs.\nPrefer concise plans and clear checkpoints.\nUse reset tokens for destructive changes.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Behavior defaults',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Personal memory',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Prompt overlay',
                valueLabel: 'On',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'App language',
                valueLabel: 'English',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Danger zone',
            helperText:
                'Typed confirmation is required before either reset runs.',
            backgroundTone: OpenCraySettingsSectionBackgroundTone.danger,
          ),
        ],
      );
    case 'agents':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'agents',
        title: 'Agent',
        subtitle: 'Browse saved agents and create a new one.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Dedicated editor',
            helperText:
                'Agent configuration is rendered by the Flutter prototype page.',
          ),
        ],
      );
    case 'home':
    default:
      throw ArgumentError.value(
        routeId,
        'routeId',
        'Unsupported settings route',
      );
  }
}

OpenCrayPersonalizationConfigSnapshot _buildSeedPersonalizationConfig() {
  return const OpenCrayPersonalizationConfigSnapshot(
    title: 'Personalization',
    subtitle: 'Tune voice, memory, and default tone for this device.',
    introTitle: 'How this profile works',
    introBody:
        'Seed mode keeps a local personalization profile so the Flutter settings flow can be exercised without the Android host.',
    introHelper: 'Changes here are preview-only and stay on this device.',
    presetsTitle: 'Tone presets',
    presetsHelper: 'Choose a base voice, then layer custom guidance on top.',
    presets: <OpenCrayPersonalizationPresetOptionSnapshot>[
      OpenCrayPersonalizationPresetOptionSnapshot(
        id: 'steady',
        title: 'Steady guide',
        summary: 'Direct, calm, and explicit about tradeoffs.',
        voice: 'Quiet, pragmatic, and low-drama.',
        status: 'Default',
        isSelected: true,
      ),
      OpenCrayPersonalizationPresetOptionSnapshot(
        id: 'focus',
        title: 'Focus operator',
        summary: 'Short plans, checkpoints, and minimal detours.',
        voice: 'Crisp and task-first.',
        status: 'Available',
        isSelected: false,
      ),
      OpenCrayPersonalizationPresetOptionSnapshot(
        id: 'warm',
        title: 'Warm guide',
        summary: 'A softer tone without losing technical precision.',
        voice: 'Calm and supportive.',
        status: 'Available',
        isSelected: false,
      ),
    ],
    selectedPresetId: 'steady',
    customOverlayTitle: 'Custom overlay',
    customOverlayHelper:
        'Add lasting guidance that should sit on top of the selected preset.',
    customLabelHint: 'Overlay label',
    customLabelHelper: 'Use a short label so the profile reads clearly later.',
    customGuidanceHint: 'Custom guidance',
    customGuidanceHelper:
        'Keep it concrete. Focus on tone, structure, and non-negotiables.',
    customLabel: '',
    customGuidance: '',
    behaviorDefaultsTitle: 'Behavior defaults',
    appLanguageTitle: 'App language',
    appLanguageOptions: <OpenCrayPersonalizationLanguageOptionSnapshot>[
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
    livePreviewTitle: 'Live preview',
    livePreviewName: 'Steady guide',
    livePreviewSummary: 'Direct, calm, and explicit about tradeoffs.',
    queueTitle: 'Reset queue',
    queueBody:
        'No reset is running. Seed mode can preview memory and soul resets locally.',
    queueIsIdle: true,
    lastResetTitle: 'Last reset',
    lastResetMessage:
        'Seed mode supports local reset previews for memory and soul.',
    resetActions: <OpenCrayPersonalizationResetActionSnapshot>[
      OpenCrayPersonalizationResetActionSnapshot(
        scopeId: 'memory',
        title: 'Reset memory',
        scopeBody: 'Clears app-local memory and history stores.',
        retainBody:
            'Leaves the soul profile, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata untouched.',
        confirmationToken: 'RESET MEMORY',
        inputHint: 'Type RESET MEMORY',
        disabledGuidance: 'Type the exact token to unlock this action.',
        typeExactGuidance: 'Type the exact token to arm the memory reset.',
        armedGuidance: 'Ready to clear local memory and history stores.',
        isInputEnabled: true,
      ),
      OpenCrayPersonalizationResetActionSnapshot(
        scopeId: 'soul',
        title: 'Reset soul',
        scopeBody: 'Restores the default local personality profile.',
        retainBody:
            'Leaves memory and history stores, workspace grants, MCP state, telemetry or privacy preferences, and About and Version metadata untouched.',
        confirmationToken: 'RESET SOUL',
        inputHint: 'Type RESET SOUL',
        disabledGuidance: 'Type the exact token to unlock this action.',
        typeExactGuidance: 'Type the exact token to arm the soul reset.',
        armedGuidance:
            'Ready to restore the default preset and clear custom overlay guidance.',
        isInputEnabled: true,
      ),
    ],
  );
}

OpenCrayFilesSnapshot _buildSeedFilesSnapshot() {
  return const OpenCrayFilesSnapshot(
    rootName: 'agent-workspace',
    rootPath: '/seed/agent-workspace',
    availableBytes: 4100000000,
    directoryCount: 3,
    fileCount: 4,
    entryCount: 7,
    isTruncated: false,
    children: <OpenCrayFileTreeNodeSnapshot>[
      OpenCrayFileTreeNodeSnapshot(
        name: 'app',
        relativePath: 'app',
        isDirectory: true,
        childCount: 2,
        sizeBytes: null,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'shell',
            relativePath: 'app/shell',
            isDirectory: true,
            childCount: 1,
            sizeBytes: null,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[
              OpenCrayFileTreeNodeSnapshot(
                name: 'opencray_shell.dart',
                relativePath: 'app/shell/opencray_shell.dart',
                isDirectory: false,
                childCount: 0,
                sizeBytes: 18432,
                isTruncated: false,
                children: <OpenCrayFileTreeNodeSnapshot>[],
              ),
            ],
          ),
          OpenCrayFileTreeNodeSnapshot(
            name: 'README.md',
            relativePath: 'app/README.md',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 4096,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
      OpenCrayFileTreeNodeSnapshot(
        name: 'docs',
        relativePath: 'docs',
        isDirectory: true,
        childCount: 1,
        sizeBytes: null,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[
          OpenCrayFileTreeNodeSnapshot(
            name: 'mobile-ui-layout-spec.md',
            relativePath: 'docs/mobile-ui-layout-spec.md',
            isDirectory: false,
            childCount: 0,
            sizeBytes: 12288,
            isTruncated: false,
            children: <OpenCrayFileTreeNodeSnapshot>[],
          ),
        ],
      ),
      OpenCrayFileTreeNodeSnapshot(
        name: 'workspace-notes.txt',
        relativePath: 'workspace-notes.txt',
        isDirectory: false,
        childCount: 0,
        sizeBytes: 1536,
        isTruncated: false,
        children: <OpenCrayFileTreeNodeSnapshot>[],
      ),
    ],
  );
}

OpenCrayFilesSnapshot _seedCreateWorkspaceFolder(
  OpenCrayFilesSnapshot snapshot, {
  required String parentRelativePath,
  required String name,
}) {
  final normalizedName = _validateSeedEntryName(name);
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentRelativePath.trim(),
    (children) {
      if (children.any((child) => child.name == normalizedName)) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        OpenCrayFileTreeNodeSnapshot(
          name: normalizedName,
          relativePath: _joinSeedPath(
            parentRelativePath.trim(),
            normalizedName,
          ),
          isDirectory: true,
          childCount: 0,
          sizeBytes: null,
          isTruncated: false,
          children: const <OpenCrayFileTreeNodeSnapshot>[],
        ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedCreateWorkspaceTextFile(
  OpenCrayFilesSnapshot snapshot, {
  required String parentRelativePath,
  required String name,
}) {
  final normalizedName = _validateSeedEntryName(name);
  if (!_supportsSeedTextPreview(normalizedName)) {
    throw StateError('Only supported text files can be created here.');
  }
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentRelativePath.trim(),
    (children) {
      if (children.any((child) => child.name == normalizedName)) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        OpenCrayFileTreeNodeSnapshot(
          name: normalizedName,
          relativePath: _joinSeedPath(
            parentRelativePath.trim(),
            normalizedName,
          ),
          isDirectory: false,
          childCount: 0,
          sizeBytes: 0,
          isTruncated: false,
          children: const <OpenCrayFileTreeNodeSnapshot>[],
        ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedRenameWorkspaceEntry(
  OpenCrayFilesSnapshot snapshot, {
  required String targetRelativePath,
  required String newName,
}) {
  final normalizedTarget = targetRelativePath.trim();
  final normalizedName = _validateSeedEntryName(newName);
  final parentPath = _seedParentPath(normalizedTarget);
  final updatedChildren = _updateSeedDirectoryChildren(
    snapshot.children,
    parentPath,
    (children) {
      final target = children.where(
        (child) => child.relativePath == normalizedTarget,
      );
      if (target.isEmpty) {
        throw StateError('The selected item no longer exists.');
      }
      if (children.any(
        (child) =>
            child.relativePath != normalizedTarget &&
            child.name == normalizedName,
      )) {
        throw StateError("An item named '$normalizedName' already exists.");
      }
      return _sortSeedNodes(
        children
            .map((child) {
              if (child.relativePath != normalizedTarget) {
                return child;
              }
              return _rebaseSeedNode(
                child,
                newRelativePath: _joinSeedPath(parentPath, normalizedName),
                newName: normalizedName,
              );
            })
            .toList(growable: false),
      );
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedDeleteWorkspaceEntries(
  OpenCrayFilesSnapshot snapshot,
  List<String> relativePaths,
) {
  final targets = relativePaths
      .map((path) => path.trim())
      .where((path) => path.isNotEmpty)
      .toSet();
  if (targets.isEmpty) {
    return snapshot;
  }
  final updatedChildren = _removeSeedNodes(snapshot.children, targets);
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _seedPasteWorkspaceEntries(
  OpenCrayFilesSnapshot snapshot, {
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final destinationPath = destinationRelativePath.trim();
  final sourcePaths = sourceRelativePaths
      .map((path) => path.trim())
      .where((path) => path.isNotEmpty)
      .toList(growable: false);
  if (sourcePaths.isEmpty) {
    throw StateError('Nothing is selected to paste.');
  }
  final sourceNodes = sourcePaths
      .map((path) => _findSeedNode(snapshot.children, path))
      .toList(growable: false);
  if (sourceNodes.any((node) => node == null)) {
    throw StateError('One or more selected items no longer exist.');
  }
  final resolvedSources = sourceNodes
      .whereType<OpenCrayFileTreeNodeSnapshot>()
      .toList(growable: false);
  for (final source in resolvedSources) {
    if (source.isDirectory &&
        (destinationPath == source.relativePath ||
            destinationPath.startsWith('${source.relativePath}/'))) {
      throw StateError('A folder cannot be moved into itself.');
    }
  }

  final activeSources = <OpenCrayFileTreeNodeSnapshot>[
    for (final source in resolvedSources)
      if (!(move && _seedParentPath(source.relativePath) == destinationPath))
        source,
  ];
  if (activeSources.isEmpty) {
    return snapshot;
  }

  final duplicateNames = <String>{};
  for (final source in activeSources) {
    if (!duplicateNames.add(source.name)) {
      throw StateError("An item named '${source.name}' already exists here.");
    }
  }

  final treeAfterMoveRemoval = move
      ? _removeSeedNodes(
          snapshot.children,
          activeSources.map((source) => source.relativePath).toSet(),
        )
      : snapshot.children;

  final updatedChildren = _updateSeedDirectoryChildren(
    treeAfterMoveRemoval,
    destinationPath,
    (children) {
      for (final source in activeSources) {
        if (children.any((child) => child.name == source.name)) {
          throw StateError(
            "An item named '${source.name}' already exists here.",
          );
        }
      }
      return _sortSeedNodes(<OpenCrayFileTreeNodeSnapshot>[
        ...children,
        for (final source in activeSources)
          _rebaseSeedNode(
            source,
            newRelativePath: _joinSeedPath(destinationPath, source.name),
          ),
      ]);
    },
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

OpenCrayFilesSnapshot _rebuildSeedSnapshot(
  OpenCrayFilesSnapshot snapshot,
  List<OpenCrayFileTreeNodeSnapshot> children,
) {
  final counts = _countSeedChildren(children);
  return OpenCrayFilesSnapshot(
    rootName: snapshot.rootName,
    rootPath: snapshot.rootPath,
    availableBytes: snapshot.availableBytes,
    directoryCount: counts.$1,
    fileCount: counts.$2,
    entryCount: counts.$1 + counts.$2,
    isTruncated: snapshot.isTruncated,
    children: children,
  );
}

OpenCrayFilesSnapshot _seedUpdateWorkspaceFileSize(
  OpenCrayFilesSnapshot snapshot, {
  required String targetRelativePath,
  required int sizeBytes,
}) {
  final updatedChildren = _replaceSeedNode(
    snapshot.children,
    targetRelativePath.trim(),
    (node) => OpenCrayFileTreeNodeSnapshot(
      name: node.name,
      relativePath: node.relativePath,
      isDirectory: node.isDirectory,
      childCount: node.childCount,
      sizeBytes: sizeBytes,
      isTruncated: node.isTruncated,
      children: node.children,
    ),
  );
  return _rebuildSeedSnapshot(snapshot, updatedChildren);
}

List<OpenCrayFileTreeNodeSnapshot> _updateSeedDirectoryChildren(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String directoryPath,
  List<OpenCrayFileTreeNodeSnapshot> Function(
    List<OpenCrayFileTreeNodeSnapshot> children,
  )
  transform,
) {
  final normalizedDirectory = directoryPath.trim();
  if (normalizedDirectory.isEmpty) {
    return transform(nodes);
  }
  var found = false;
  final updatedNodes = nodes
      .map((node) {
        if (node.relativePath == normalizedDirectory) {
          if (!node.isDirectory) {
            throw StateError('Destination directory is unavailable.');
          }
          found = true;
          final updatedChildren = transform(node.children);
          return _copySeedNode(node, children: updatedChildren);
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _updateSeedDirectoryChildrenOrNull(
          node.children,
          normalizedDirectory,
          transform,
          onFound: () => found = true,
        );
        if (updatedChildren == null) {
          return node;
        }
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
  if (!found) {
    throw StateError('Destination directory is unavailable.');
  }
  return updatedNodes;
}

List<OpenCrayFileTreeNodeSnapshot>? _updateSeedDirectoryChildrenOrNull(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String directoryPath,
  List<OpenCrayFileTreeNodeSnapshot> Function(
    List<OpenCrayFileTreeNodeSnapshot> children,
  )
  transform, {
  required void Function() onFound,
}) {
  var changed = false;
  final updatedNodes = nodes
      .map((node) {
        if (node.relativePath == directoryPath) {
          if (!node.isDirectory) {
            throw StateError('Destination directory is unavailable.');
          }
          changed = true;
          onFound();
          return _copySeedNode(node, children: transform(node.children));
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _updateSeedDirectoryChildrenOrNull(
          node.children,
          directoryPath,
          transform,
          onFound: onFound,
        );
        if (updatedChildren == null) {
          return node;
        }
        changed = true;
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
  return changed ? updatedNodes : null;
}

List<OpenCrayFileTreeNodeSnapshot> _removeSeedNodes(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  Set<String> targetPaths,
) {
  return nodes
      .where((node) => !targetPaths.contains(node.relativePath))
      .map((node) {
        if (!node.isDirectory) {
          return node;
        }
        return _copySeedNode(
          node,
          children: _removeSeedNodes(node.children, targetPaths),
        );
      })
      .toList(growable: false);
}

List<OpenCrayFileTreeNodeSnapshot> _replaceSeedNode(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String targetPath,
  OpenCrayFileTreeNodeSnapshot Function(OpenCrayFileTreeNodeSnapshot node)
  transform,
) {
  return nodes
      .map((node) {
        if (node.relativePath == targetPath) {
          return transform(node);
        }
        if (!node.isDirectory) {
          return node;
        }
        final updatedChildren = _replaceSeedNode(
          node.children,
          targetPath,
          transform,
        );
        if (identical(updatedChildren, node.children)) {
          return node;
        }
        return _copySeedNode(node, children: updatedChildren);
      })
      .toList(growable: false);
}

OpenCrayFileTreeNodeSnapshot? _findSeedNode(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
  String targetPath,
) {
  for (final node in nodes) {
    if (node.relativePath == targetPath) {
      return node;
    }
    final nested = _findSeedNode(node.children, targetPath);
    if (nested != null) {
      return nested;
    }
  }
  return null;
}

List<String> _seedActiveTransferSourcePaths({
  required OpenCrayFilesSnapshot snapshot,
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final sourceNodes = sourceRelativePaths
      .map((path) => _findSeedNode(snapshot.children, path))
      .toList(growable: false);
  if (sourceNodes.any((node) => node == null)) {
    throw StateError('One or more selected items no longer exist.');
  }
  final resolvedSources = sourceNodes
      .whereType<OpenCrayFileTreeNodeSnapshot>()
      .toList(growable: false);
  return <String>[
    for (final source in resolvedSources)
      if (!(move &&
          _seedParentPath(source.relativePath) == destinationRelativePath))
        source.relativePath,
  ];
}

Map<String, String> _seedRenameTextDocuments(
  Map<String, String> documentsByPath, {
  required String fromRelativePath,
  required String toRelativePath,
}) {
  return <String, String>{
    for (final entry in documentsByPath.entries)
      _rebaseSeedDocumentPath(
        entry.key,
        fromRelativePath: fromRelativePath,
        toRelativePath: toRelativePath,
      ): entry.value,
  };
}

Map<String, String> _seedDeleteTextDocuments(
  Map<String, String> documentsByPath,
  Set<String> targetPaths,
) {
  return <String, String>{
    for (final entry in documentsByPath.entries)
      if (!_matchesSeedPathPrefix(entry.key, targetPaths))
        entry.key: entry.value,
  };
}

Map<String, String> _seedPasteTextDocuments(
  Map<String, String> documentsByPath, {
  required List<String> sourceRelativePaths,
  required String destinationRelativePath,
  required bool move,
}) {
  final updated = move
      ? _seedDeleteTextDocuments(documentsByPath, sourceRelativePaths.toSet())
      : Map<String, String>.from(documentsByPath);
  for (final sourceRelativePath in sourceRelativePaths) {
    final destinationPath = _joinSeedPath(
      destinationRelativePath,
      _seedBaseName(sourceRelativePath),
    );
    for (final entry in documentsByPath.entries) {
      if (!_matchesSeedPathPrefix(entry.key, <String>{sourceRelativePath})) {
        continue;
      }
      updated[_rebaseSeedDocumentPath(
            entry.key,
            fromRelativePath: sourceRelativePath,
            toRelativePath: destinationPath,
          )] =
          entry.value;
    }
  }
  return updated;
}

String _rebaseSeedDocumentPath(
  String path, {
  required String fromRelativePath,
  required String toRelativePath,
}) {
  if (path == fromRelativePath) {
    return toRelativePath;
  }
  if (path.startsWith('$fromRelativePath/')) {
    return '$toRelativePath/${path.substring(fromRelativePath.length + 1)}';
  }
  return path;
}

bool _matchesSeedPathPrefix(String path, Set<String> prefixes) {
  for (final prefix in prefixes) {
    if (path == prefix || path.startsWith('$prefix/')) {
      return true;
    }
  }
  return false;
}

String _seedBaseName(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return normalized;
  }
  return normalized.substring(normalized.lastIndexOf('/') + 1);
}

OpenCrayFileTreeNodeSnapshot _rebaseSeedNode(
  OpenCrayFileTreeNodeSnapshot node, {
  required String newRelativePath,
  String? newName,
}) {
  final resolvedName = newName ?? node.name;
  return OpenCrayFileTreeNodeSnapshot(
    name: resolvedName,
    relativePath: newRelativePath,
    isDirectory: node.isDirectory,
    childCount: node.children.length,
    sizeBytes: node.sizeBytes,
    isTruncated: node.isTruncated,
    children: node.children
        .map(
          (child) => _rebaseSeedNode(
            child,
            newRelativePath: _joinSeedPath(newRelativePath, child.name),
          ),
        )
        .toList(growable: false),
  );
}

OpenCrayFileTreeNodeSnapshot _copySeedNode(
  OpenCrayFileTreeNodeSnapshot node, {
  required List<OpenCrayFileTreeNodeSnapshot> children,
}) {
  return OpenCrayFileTreeNodeSnapshot(
    name: node.name,
    relativePath: node.relativePath,
    isDirectory: node.isDirectory,
    childCount: children.length,
    sizeBytes: node.sizeBytes,
    isTruncated: node.isTruncated,
    children: children,
  );
}

List<OpenCrayFileTreeNodeSnapshot> _sortSeedNodes(
  List<OpenCrayFileTreeNodeSnapshot> nodes,
) {
  final sorted = [...nodes];
  sorted.sort((left, right) {
    if (left.isDirectory != right.isDirectory) {
      return left.isDirectory ? -1 : 1;
    }
    return left.name.toLowerCase().compareTo(right.name.toLowerCase());
  });
  return sorted;
}

(int, int) _countSeedChildren(List<OpenCrayFileTreeNodeSnapshot> nodes) {
  var directoryCount = 0;
  var fileCount = 0;
  for (final node in nodes) {
    if (node.isDirectory) {
      directoryCount += 1;
    } else {
      fileCount += 1;
    }
    final nested = _countSeedChildren(node.children);
    directoryCount += nested.$1;
    fileCount += nested.$2;
  }
  return (directoryCount, fileCount);
}

String _seedParentPath(String relativePath) {
  final normalized = relativePath.trim();
  if (normalized.isEmpty || !normalized.contains('/')) {
    return '';
  }
  return normalized.substring(0, normalized.lastIndexOf('/'));
}

String _joinSeedPath(String parentRelativePath, String name) {
  final normalizedParent = parentRelativePath.trim();
  if (normalizedParent.isEmpty) {
    return name;
  }
  return '$normalizedParent/$name';
}

String _validateSeedEntryName(String rawName) {
  final normalized = rawName.trim();
  if (normalized.isEmpty) {
    throw StateError('A name is required.');
  }
  if (normalized == '.' || normalized == '..') {
    throw StateError('That name is not allowed.');
  }
  if (normalized.contains('/') || normalized.contains('\\')) {
    throw StateError('Names cannot contain path separators.');
  }
  return normalized;
}

bool _supportsSeedTextPreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  if (_seedTextPreviewNames.contains(normalizedName)) {
    return true;
  }
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _seedTextPreviewExtensions.contains(extension);
}

bool _supportsSeedImagePreview(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  return _seedImagePreviewExtensions.contains(extension);
}

String _seedImagePreviewMimeTypeFor(String name) {
  final normalizedName = name.trim().toLowerCase();
  final extension = normalizedName.contains('.')
      ? normalizedName.substring(normalizedName.lastIndexOf('.') + 1)
      : '';
  switch (extension) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'bmp':
      return 'image/bmp';
    default:
      return 'image/png';
  }
}

String _seedTextPreviewContentFor({
  required String relativePath,
  required String name,
  required Map<String, String> documentsByPath,
}) {
  return documentsByPath[relativePath] ??
      _seedPreviewContentByPath[relativePath] ??
      'Preview for $relativePath\n\n'
          'This is seeded text content generated by the local preview bridge.\n';
}

const Set<String> _seedTextPreviewNames = <String>{
  '.env',
  '.gitignore',
  '.gitattributes',
  'makefile',
  'readme',
  'readme.md',
  'license',
  'gradlew',
  'gradlew.bat',
};

const Set<String> _seedTextPreviewExtensions = <String>{
  'txt',
  'md',
  'markdown',
  'json',
  'yaml',
  'yml',
  'xml',
  'csv',
  'log',
  'ini',
  'conf',
  'config',
  'properties',
  'toml',
  'dart',
  'kt',
  'kts',
  'java',
  'js',
  'ts',
  'tsx',
  'jsx',
  'css',
  'scss',
  'html',
  'htm',
  'sh',
  'bash',
  'zsh',
  'py',
  'sql',
};

const Set<String> _seedImagePreviewExtensions = <String>{
  'png',
  'jpg',
  'jpeg',
  'webp',
  'gif',
  'bmp',
  'heic',
  'heif',
  'svg',
};

const String _seedImagePreviewBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn1yt4AAAAASUVORK5CYII=';

const Map<String, String> _seedPreviewContentByPath = <String, String>{
  'workspace-notes.txt':
      'Workspace notes\n\n'
      '- Keep the Files surface grounded in the current directory.\n'
      '- Text files should open in a lightweight preview sheet.\n'
      '- Dangerous actions stay inside explicit edit states.\n',
  'app/README.md':
      '# OpenCray Shell\n\n'
      'The Flutter shell keeps Chat, Skills, Files, and Settings in one predictable frame.\n',
  'app/shell/opencray_shell.dart':
      'class OpenCrayShell {\n'
      '  const OpenCrayShell();\n'
      '}\n',
  'docs/mobile-ui-layout-spec.md':
      '# Mobile UI Layout Spec\n\n'
      'Files should feel compact, direct, and native on phone-sized screens.\n',
};

OpenCrayPersonalizationConfigSnapshot _copySeedPersonalizationConfig(
  OpenCrayPersonalizationConfigSnapshot source, {
  String? presetId,
  String? customLabel,
  String? customGuidance,
  String? selectedAppLanguageId,
  String? queueBody,
  bool? queueIsIdle,
  String? lastResetTitle,
  String? lastResetMessage,
}) {
  final nextPresetId = presetId ?? source.selectedPresetId;
  final preset = source.presets.firstWhere(
    (candidate) => candidate.id == nextPresetId,
    orElse: () => source.presets.first,
  );
  final nextCustomLabel = customLabel ?? source.customLabel;
  final nextCustomGuidance = customGuidance ?? source.customGuidance;
  final nextLanguageId = selectedAppLanguageId ?? source.selectedAppLanguageId;
  return OpenCrayPersonalizationConfigSnapshot(
    title: source.title,
    subtitle: source.subtitle,
    introTitle: source.introTitle,
    introBody: source.introBody,
    introHelper: source.introHelper,
    presetsTitle: source.presetsTitle,
    presetsHelper: source.presetsHelper,
    presets: source.presets
        .map(
          (option) => OpenCrayPersonalizationPresetOptionSnapshot(
            id: option.id,
            title: option.title,
            summary: option.summary,
            voice: option.voice,
            status: option.id == preset.id ? 'Selected' : 'Available',
            isSelected: option.id == preset.id,
          ),
        )
        .toList(growable: false),
    selectedPresetId: preset.id,
    customOverlayTitle: source.customOverlayTitle,
    customOverlayHelper: source.customOverlayHelper,
    customLabelHint: source.customLabelHint,
    customLabelHelper: source.customLabelHelper,
    customGuidanceHint: source.customGuidanceHint,
    customGuidanceHelper: source.customGuidanceHelper,
    customLabel: nextCustomLabel,
    customGuidance: nextCustomGuidance,
    behaviorDefaultsTitle: source.behaviorDefaultsTitle,
    appLanguageTitle: source.appLanguageTitle,
    appLanguageOptions: source.appLanguageOptions
        .map(
          (option) => OpenCrayPersonalizationLanguageOptionSnapshot(
            id: option.id,
            title: option.title,
            isSelected: option.id == nextLanguageId,
          ),
        )
        .toList(growable: false),
    selectedAppLanguageId: nextLanguageId,
    livePreviewTitle: source.livePreviewTitle,
    livePreviewName: nextCustomLabel.trim().isEmpty
        ? preset.title
        : nextCustomLabel.trim(),
    livePreviewSummary: nextCustomGuidance.trim().isEmpty
        ? preset.summary
        : nextCustomGuidance.trim(),
    queueTitle: source.queueTitle,
    queueBody: queueBody ?? source.queueBody,
    queueIsIdle: queueIsIdle ?? source.queueIsIdle,
    lastResetTitle: lastResetTitle ?? source.lastResetTitle,
    lastResetMessage: lastResetMessage ?? source.lastResetMessage,
    resetActions: source.resetActions,
  );
}

OpenCrayMcpSettingsSnapshot _buildSeedMcpSettings() {
  return const OpenCrayMcpSettingsSnapshot(
    title: 'MCP',
    subtitle: 'Control server discovery, approvals, and health checks.',
    masterEnabled: true,
    masterTitle: 'Enable MCP integrations',
    masterSummary: 'Seed mode exposes a local preview registry.',
    summaryLine: '1 active demo server',
    serversTitle: 'Registered servers',
    serversHelper:
        'These are local preview cards, not live Android host connections.',
    masterDisabledTitle: 'MCP is turned off',
    masterDisabledBody:
        'Enable the master switch to restore individual server controls.',
    servers: <OpenCrayMcpServerSnapshot>[
      OpenCrayMcpServerSnapshot(
        id: 'filesystem',
        title: 'Filesystem',
        statusLabel: 'Active',
        statusTone: 'success',
        trustLine: 'Trust: approved roots only',
        authLine: 'Auth: no extra authentication',
        readinessLine: 'Readiness: healthy',
        transportLine: 'Transport: native bridge',
        exposureLine: 'Exposure: local workspace only',
        guidance: 'Use for file reads, patches, and directory scans.',
        actionLabel: 'Disable server',
        actionTurnsOn: false,
        isActionEnabled: true,
      ),
      OpenCrayMcpServerSnapshot(
        id: 'browser',
        title: 'Browser',
        statusLabel: 'Blocked',
        statusTone: 'neutral',
        trustLine: 'Trust: prompted for risky actions',
        authLine: 'Auth: no session',
        readinessLine: 'Readiness: healthy',
        transportLine: 'Transport: native bridge',
        exposureLine: 'Exposure: restricted web actions',
        guidance: 'Use for documentation lookup and page inspection.',
        actionLabel: 'Enable server',
        actionTurnsOn: true,
        isActionEnabled: true,
      ),
    ],
  );
}

OpenCrayMcpSettingsSnapshot _copySeedMcpSettings(
  OpenCrayMcpSettingsSnapshot source, {
  bool? masterEnabled,
  Map<String, bool> serverOverrides = const <String, bool>{},
}) {
  final nextMasterEnabled = masterEnabled ?? source.masterEnabled;
  final servers = source.servers
      .map((server) {
        final currentEnabled = server.actionLabel == 'Disable server';
        final nextEnabled =
            nextMasterEnabled && (serverOverrides[server.id] ?? currentEnabled);
        return OpenCrayMcpServerSnapshot(
          id: server.id,
          title: server.title,
          statusLabel: nextEnabled ? 'Active' : 'Blocked',
          statusTone: nextEnabled ? 'success' : 'neutral',
          trustLine: server.trustLine,
          authLine: server.authLine,
          readinessLine: server.readinessLine,
          transportLine: server.transportLine,
          exposureLine: server.exposureLine,
          guidance: server.guidance,
          actionLabel: nextEnabled ? 'Disable server' : 'Enable server',
          actionTurnsOn: !nextEnabled,
          isActionEnabled: nextMasterEnabled,
        );
      })
      .toList(growable: false);
  final enabledCount = servers
      .where((server) => server.statusLabel == 'Active')
      .length;
  return OpenCrayMcpSettingsSnapshot(
    title: source.title,
    subtitle: source.subtitle,
    masterEnabled: nextMasterEnabled,
    masterTitle: source.masterTitle,
    masterSummary: source.masterSummary,
    summaryLine: nextMasterEnabled
        ? '$enabledCount active demo server${enabledCount == 1 ? '' : 's'}'
        : 'MCP integrations disabled',
    serversTitle: source.serversTitle,
    serversHelper: source.serversHelper,
    masterDisabledTitle: source.masterDisabledTitle,
    masterDisabledBody: source.masterDisabledBody,
    servers: servers,
  );
}
