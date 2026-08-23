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
part 'opencray_seed_bridge_shell.dart';
part 'opencray_seed_bridge_files.dart';
part 'opencray_seed_bridge_agents.dart';
part 'opencray_seed_bridge_settings.dart';
part 'opencray_seed_bridge_llm.dart';
part 'opencray_seed_bridge_personalization.dart';
part 'opencray_seed_bridge_mcp_safety.dart';
part 'opencray_seed_bridge_skills.dart';
part 'opencray_seed_bridge_chat.dart';

class OpenCraySeedBridge extends _SeedBridgeDeps
    with
        _SeedBridgeShellDomain,
        _SeedBridgeFilesDomain,
        _SeedBridgeAgentsDomain,
        _SeedBridgeSettingsDomain,
        _SeedBridgeLlmDomain,
        _SeedBridgePersonalizationDomain,
        _SeedBridgeMcpSafetyDomain,
        _SeedBridgeSkillsDomain,
        _SeedBridgeChatDomain
    implements OpenCrayHostBridge {
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

  @override
  String? _lastCopiedPlainText;
  @override
  String? _lastCopiedHtmlText;

  @visibleForTesting
  String? get lastCopiedPlainText => _lastCopiedPlainText;

  @visibleForTesting
  String? get lastCopiedHtmlText => _lastCopiedHtmlText;

  @override
  final StreamController<OpenCrayShellSnapshot> _controller =
      StreamController<OpenCrayShellSnapshot>.broadcast();
  @override
  final StreamController<OpenCraySettingsOverviewSnapshot> _settingsController =
      StreamController<OpenCraySettingsOverviewSnapshot>.broadcast();
  @override
  final StreamController<OpenCraySkillsSnapshot> _skillsController =
      StreamController<OpenCraySkillsSnapshot>.broadcast();
  @override
  final StreamController<OpenCrayChatSnapshot> _chatController =
      StreamController<OpenCrayChatSnapshot>.broadcast();
  @override
  final List<String> _shownNativeToasts = <String>[];
  @override
  OpenCrayShellSnapshot _snapshot;
  @override
  OpenCrayFilesSnapshot _filesSnapshot;
  @override
  Map<String, String> _textDocumentsByPath;
  @override
  OpenCraySettingsOverviewSnapshot _settingsOverview;
  @override
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
  @override
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
  @override
  OpenCrayLlmConfigSnapshot _llmConfig;
  @override
  OpenCrayPersonalizationConfigSnapshot _personalizationConfig;
  @override
  OpenCrayMcpSettingsSnapshot _mcpSettings;
  @override
  OpenCrayNotificationSettingsSnapshot _notificationSettings;
  @override
  final OpenCrayScheduledTasksSnapshot _scheduledTasks =
      const OpenCrayScheduledTasksSnapshot(
        tasks: <OpenCrayScheduledTaskSummary>[],
        totalCount: 0,
        enabledCount: 0,
      );
  @override
  OpenCraySafetySettingsSnapshot _safetySettings;
  @override
  final OpenCrayStrongBackgroundSnapshot _strongBackgroundSnapshot;
  @override
  OpenCraySkillsSnapshot _skillsSnapshot;
  @override
  OpenCrayChatSnapshot _chatSnapshot;
  @override
  OpenCraySandboxSettingsSnapshot _sandboxSettings;
  @override
  final List<OpenCrayAgentSnapshot> _agents;
  @override
  String? _activeAgentId;
  int _seedMessageCounter = 1;
  int _seedAgentCounter = 1;

  List<String> get shownNativeToasts =>
      List<String>.unmodifiable(_shownNativeToasts);


  @override
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

  @override
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

  @override
  List<OpenCrayAgentSnapshot> _materializeAgents() {
    return _agents.map(_materializeAgent).toList(growable: false);
  }

  @override
  OpenCrayAgentSnapshot _materializeAgent(OpenCrayAgentSnapshot snapshot) {
    return snapshot.copyWith(isActive: snapshot.agentId == _activeAgentId);
  }

  @override
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

  @override
  void _emitSkillsSnapshot() {
    if (!_skillsController.isClosed) {
      _skillsController.add(_skillsSnapshot);
    }
  }

  @override
  void _emitChatSnapshot() {
    if (!_chatController.isClosed) {
      _chatController.add(_chatSnapshot);
    }
  }

  @override
  String get _activeChatSessionId => _chatSnapshot.drawer.sessions
      .firstWhere(
        (session) => session.isSelected,
        orElse: () => _chatSnapshot.drawer.sessions.first,
      )
      .sessionId;

  @override
  String _seedMessageId(String prefix) {
    _seedMessageCounter += 1;
    return '$prefix-$_seedMessageCounter';
  }

  @override
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

  @override
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

  @override
  int _nextChatSnapshotUpdatedAt() {
    final int now = DateTime.now().millisecondsSinceEpoch;
    final int previous = _chatSnapshot.updatedAtEpochMs;
    return now > previous ? now : previous + 1;
  }
}

abstract class _SeedBridgeDeps implements OpenCrayHostBridge {
  const _SeedBridgeDeps();

  set _lastCopiedPlainText(String? value);

  set _lastCopiedHtmlText(String? value);

  StreamController<OpenCrayShellSnapshot> get _controller;

  StreamController<OpenCraySettingsOverviewSnapshot> get _settingsController;

  StreamController<OpenCraySkillsSnapshot> get _skillsController;

  StreamController<OpenCrayChatSnapshot> get _chatController;

  List<String> get _shownNativeToasts;

  OpenCrayShellSnapshot get _snapshot;
  set _snapshot(OpenCrayShellSnapshot value);

  OpenCrayFilesSnapshot get _filesSnapshot;
  set _filesSnapshot(OpenCrayFilesSnapshot value);

  Map<String, String> get _textDocumentsByPath;
  set _textDocumentsByPath(Map<String, String> value);

  OpenCraySettingsOverviewSnapshot get _settingsOverview;

  OpenCrayNetworkSearchConfigSnapshot get _networkSearchConfig;
  set _networkSearchConfig(OpenCrayNetworkSearchConfigSnapshot value);

  OpenCrayMediaSpeechConfigSnapshot get _mediaSpeechConfig;
  set _mediaSpeechConfig(OpenCrayMediaSpeechConfigSnapshot value);

  OpenCrayLlmConfigSnapshot get _llmConfig;
  set _llmConfig(OpenCrayLlmConfigSnapshot value);

  OpenCrayPersonalizationConfigSnapshot get _personalizationConfig;
  set _personalizationConfig(OpenCrayPersonalizationConfigSnapshot value);

  OpenCrayMcpSettingsSnapshot get _mcpSettings;
  set _mcpSettings(OpenCrayMcpSettingsSnapshot value);

  OpenCrayNotificationSettingsSnapshot get _notificationSettings;
  set _notificationSettings(OpenCrayNotificationSettingsSnapshot value);

  OpenCrayScheduledTasksSnapshot get _scheduledTasks;

  OpenCraySafetySettingsSnapshot get _safetySettings;
  set _safetySettings(OpenCraySafetySettingsSnapshot value);

  OpenCrayStrongBackgroundSnapshot get _strongBackgroundSnapshot;

  OpenCraySkillsSnapshot get _skillsSnapshot;
  set _skillsSnapshot(OpenCraySkillsSnapshot value);

  OpenCrayChatSnapshot get _chatSnapshot;
  set _chatSnapshot(OpenCrayChatSnapshot value);

  OpenCraySandboxSettingsSnapshot get _sandboxSettings;
  set _sandboxSettings(OpenCraySandboxSettingsSnapshot value);

  List<OpenCrayAgentSnapshot> get _agents;

  String? get _activeAgentId;
  set _activeAgentId(String? value);

  void update(OpenCrayShellSnapshot snapshot);

  void _refreshSettingsOverview();

  List<OpenCrayAgentSnapshot> _materializeAgents();

  OpenCrayAgentSnapshot _materializeAgent(OpenCrayAgentSnapshot snapshot);

  String _allocateSeedAgentId();

  void _emitSkillsSnapshot();

  void _emitChatSnapshot();

  String get _activeChatSessionId;

  String _seedMessageId(String prefix);

  OpenCrayChatSnapshot _copyChatSnapshotWith({
    required OpenCrayChatDrawerSnapshot drawer,
    OpenCrayChatSummarySnapshot? summary,
  });

  void _resolveChatApproval(String taskId);

  int _nextChatSnapshotUpdatedAt();
}
