part of 'opencray_seed_bridge.dart';

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
    case 'event_alerts':
    case 'notification_channels':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'event_alerts',
        title: 'Event Alerts',
        subtitle: 'Choose which app events can publish a new alert.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Event alerts',
            helperText:
                'Event alert controls are rendered by the Flutter settings page.',
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
    case 'privacy_telemetry':
      return const OpenCraySettingsDetailSnapshot(
        routeId: 'privacy_telemetry',
        title: 'Privacy & Telemetry',
        subtitle:
            'Review what stays on device, what can leave it, and how diagnostic signals are handled.',
        sections: <OpenCraySettingsSectionSnapshot>[
          OpenCraySettingsSectionSnapshot(
            title: 'Diagnostics',
            helperText:
                'Crash details and lightweight diagnostics can help explain failures without exporting workspace content by default.',
          ),
          OpenCraySettingsSectionSnapshot(
            title: 'Data handling',
            rows: <OpenCraySettingsRowSnapshot>[
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Share crash diagnostics',
                subtitle: 'Include app and runtime failure summaries only.',
                toggleValue: false,
              ),
              OpenCraySettingsRowSnapshot.toggle(
                title: 'Share product telemetry',
                subtitle:
                    'Send anonymous usage counters for shell and settings flows.',
                toggleValue: false,
              ),
            ],
          ),
        ],
      );
    case 'network_search':
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
