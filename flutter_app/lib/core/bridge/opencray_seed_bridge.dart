import 'dart:async';

import '../../app/opencray_tabs.dart';
import '../models/opencray_chat_snapshot.dart';
import '../models/opencray_files_snapshot.dart';
import '../models/opencray_llm_config.dart';
import '../models/opencray_llm_validation.dart';
import '../models/opencray_mcp_settings.dart';
import '../models/opencray_personalization_config.dart';
import '../models/opencray_settings_snapshot.dart';
import '../models/opencray_shell_snapshot.dart';
import '../models/opencray_skills_snapshot.dart';
import 'opencray_host_bridge.dart';

class OpenCraySeedBridge implements OpenCrayHostBridge {
  OpenCraySeedBridge({
    OpenCrayShellSnapshot? initialSnapshot,
    OpenCrayFilesSnapshot? initialFilesSnapshot,
    OpenCraySettingsOverviewSnapshot? initialSettingsOverview,
    OpenCrayLlmConfigSnapshot? initialLlmConfig,
    OpenCrayPersonalizationConfigSnapshot? initialPersonalizationConfig,
    OpenCrayMcpSettingsSnapshot? initialMcpSettings,
    OpenCraySkillsSnapshot? initialSkillsSnapshot,
    OpenCrayChatSnapshot? initialChatSnapshot,
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
       _settingsOverview =
           initialSettingsOverview ??
           const OpenCraySettingsOverviewSnapshot(
             eyebrow: 'APP SHELL',
             title: 'Settings',
             subtitle: 'Access, providers,\nand personal defaults.',
             deviceTitle: 'OpenCray on this device',
             deviceSummary: 'Personalization: Quiet\nTelemetry: Minimal',
             entries: <OpenCraySettingsHomeEntrySnapshot>[
               OpenCraySettingsHomeEntrySnapshot(
                 routeId: 'workspace_access',
                 title: 'Workspace Access',
               ),
               OpenCraySettingsHomeEntrySnapshot(routeId: 'llm', title: 'LLM'),
               OpenCraySettingsHomeEntrySnapshot(routeId: 'mcp', title: 'MCP'),
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
             providerId: 'openai',
             providerOptions: <OpenCrayLlmProviderOptionSnapshot>[
               OpenCrayLlmProviderOptionSnapshot(
                 id: 'openai',
                 title: 'OpenAI',
                 subtitle: 'Official OpenAI-compatible endpoint.',
                 defaultBaseUrl: 'https://api.openai.com/v1',
                 defaultModel: 'gpt-4o-mini',
                 isCustom: false,
               ),
               OpenCrayLlmProviderOptionSnapshot(
                 id: 'custom',
                 title: 'Custom provider',
                 subtitle: 'Any OpenAI-compatible or Anthropic endpoint.',
                 defaultBaseUrl: '',
                 defaultModel: '',
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
           ),
       _personalizationConfig =
           initialPersonalizationConfig ?? _buildSeedPersonalizationConfig(),
       _mcpSettings = initialMcpSettings ?? _buildSeedMcpSettings(),
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
                 id: 'local-folder',
                 title: 'Local path',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
               OpenCraySkillInstallSourceSnapshot(
                 id: 'git-repository',
                 title: 'GitHub repository',
                 subtitle: 'Unavailable while the host bridge is disconnected.',
                 ctaLabel: 'Unavailable',
                 isAvailable: false,
               ),
             ],
             suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
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
           );

  final StreamController<OpenCrayShellSnapshot> _controller =
      StreamController<OpenCrayShellSnapshot>.broadcast();
  final StreamController<OpenCraySettingsOverviewSnapshot> _settingsController =
      StreamController<OpenCraySettingsOverviewSnapshot>.broadcast();
  final StreamController<OpenCraySkillsSnapshot> _skillsController =
      StreamController<OpenCraySkillsSnapshot>.broadcast();
  final StreamController<OpenCrayChatSnapshot> _chatController =
      StreamController<OpenCrayChatSnapshot>.broadcast();
  OpenCrayShellSnapshot _snapshot;
  final OpenCrayFilesSnapshot _filesSnapshot;
  OpenCraySettingsOverviewSnapshot _settingsOverview;
  OpenCrayLlmConfigSnapshot _llmConfig;
  OpenCrayPersonalizationConfigSnapshot _personalizationConfig;
  OpenCrayMcpSettingsSnapshot _mcpSettings;
  OpenCraySkillsSnapshot _skillsSnapshot;
  OpenCrayChatSnapshot _chatSnapshot;

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
  Future<OpenCrayLlmConfigSnapshot> loadLlmConfig() async => _llmConfig;

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
  }) async {
    final isConfigured =
        baseUrl.trim().isNotEmpty &&
        apiKey.trim().isNotEmpty &&
        model.trim().isNotEmpty;
    _llmConfig = OpenCrayLlmConfigSnapshot(
      localeTag: _llmConfig.localeTag,
      enabled: isConfigured,
      providerId: providerId,
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
      providerId: _llmConfig.providerId,
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
  Future<OpenCraySkillsSnapshot> loadSkillsSnapshot() async => _skillsSnapshot;

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
    );
    _emitSkillsSnapshot();
  }

  @override
  Future<String?> refreshSkills() async =>
      'Seed bridge refreshed local skills.';

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
  Future<OpenCrayChatRunSnapshot?> loadChatRunSnapshot(String runId) async => null;

  @override
  Future<OpenCrayChatRunSnapshot?> waitForChatRun(
    String runId, {
    Duration timeout = const Duration(seconds: 15),
  }) async => null;

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
  Future<void> selectChatSession(String sessionId) async {}

  @override
  Future<OpenCrayChatRunSubmission?> submitChatMessage(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty) {
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
        OpenCrayChatMessageSnapshot(kind: 'outbound', text: trimmed),
        const OpenCrayChatMessageSnapshot(
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
  Future<void> rejectChatApproval(String approvalId) async {
    _resolveChatApproval(approvalId);
  }

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
      deviceSummary:
          'Personalization: ${_personalizationConfig.livePreviewName}\nTelemetry: Minimal',
      entries: _settingsOverview.entries,
    );
    updateSettingsOverview(updatedOverview);
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

OpenCraySettingsDetailSnapshot _seedSettingsDetailFor(String routeId) {
  switch (routeId) {
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
    case 'privacy_telemetry':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'privacy_telemetry',
        title: 'Privacy & Telemetry',
        subtitle: 'Decide what leaves the device and how long it stays.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Telemetry profile',
            segmentedOptions: <String>['QUIET', 'BAL', 'FULL'],
            segmentedIndex: 0,
            helperText: 'Quieter profiles send fewer diagnostics.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Data controls',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.chevron(
                title: 'Review collected signals',
                subtitle: 'Give clear exports before asking for more.',
              ),
            ],
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Safeguards',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Crash reports',
                subtitle: 'Share anonymous failures for stability.',
                toggleValue: true,
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Retention',
                valueLabel: '7 days',
              ),
              OpenCraySettingsRowSnapshot.value(
                title: 'Data export',
                valueLabel: 'Ready',
              ),
            ],
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
