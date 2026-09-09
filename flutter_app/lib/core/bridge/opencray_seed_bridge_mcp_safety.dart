part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeMcpSafetyDomain on _SeedBridgeDeps {
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
  Future<OpenCrayMcpSettingsSnapshot> addMcpServer({
    required String serverId,
    required String displayName,
    required String url,
    String? authHeaderName,
    String? authToken,
  }) async {
    if (_mcpSettings.servers.any((server) => server.id == serverId)) {
      throw Exception("MCP server id '$serverId' is already registered.");
    }
    final next = OpenCrayMcpServerSnapshot(
      id: serverId,
      title: displayName,
      statusLabel: 'Blocked',
      statusTone: 'neutral',
      trustLine: 'Trust: requires manual enable',
      authLine: authToken == null || authToken.trim().isEmpty
          ? 'Auth: not required'
          : 'Auth: configured',
      readinessLine: 'Readiness: needs attention',
      transportLine: 'Transport: remote HTTP',
      exposureLine: 'Exposure: blocked',
      guidance:
          'Demo bridge: this server stays blocked until you enable it from its card.',
      actionLabel: 'Enable server',
      actionTurnsOn: true,
      isActionEnabled: _mcpSettings.masterEnabled,
    );
    _mcpSettings = OpenCrayMcpSettingsSnapshot(
      title: _mcpSettings.title,
      subtitle: _mcpSettings.subtitle,
      masterEnabled: _mcpSettings.masterEnabled,
      masterTitle: _mcpSettings.masterTitle,
      masterSummary: _mcpSettings.masterSummary,
      summaryLine: _mcpSettings.summaryLine,
      serversTitle: _mcpSettings.serversTitle,
      serversHelper: _mcpSettings.serversHelper,
      masterDisabledTitle: _mcpSettings.masterDisabledTitle,
      masterDisabledBody: _mcpSettings.masterDisabledBody,
      servers: <OpenCrayMcpServerSnapshot>[..._mcpSettings.servers, next],
    );
    return _mcpSettings;
  }

  @override
  Future<OpenCrayMcpSettingsSnapshot> removeMcpServer({
    required String serverId,
  }) async {
    if (!_mcpSettings.servers.any((server) => server.id == serverId)) {
      throw Exception("Unknown MCP server '$serverId'.");
    }
    _mcpSettings = OpenCrayMcpSettingsSnapshot(
      title: _mcpSettings.title,
      subtitle: _mcpSettings.subtitle,
      masterEnabled: _mcpSettings.masterEnabled,
      masterTitle: _mcpSettings.masterTitle,
      masterSummary: _mcpSettings.masterSummary,
      summaryLine: _mcpSettings.summaryLine,
      serversTitle: _mcpSettings.serversTitle,
      serversHelper: _mcpSettings.serversHelper,
      masterDisabledTitle: _mcpSettings.masterDisabledTitle,
      masterDisabledBody: _mcpSettings.masterDisabledBody,
      servers: _mcpSettings.servers
          .where((server) => server.id != serverId)
          .toList(growable: false),
    );
    return _mcpSettings;
  }

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerCredential({
    required String serverId,
    required String authHeaderName,
    String? authToken,
  }) async {
    if (!_mcpSettings.servers.any((server) => server.id == serverId)) {
      throw Exception("Unknown MCP server '$serverId'.");
    }
    final servers = _mcpSettings.servers
        .map(
          (server) => server.id == serverId
              ? OpenCrayMcpServerSnapshot(
                  id: server.id,
                  title: server.title,
                  statusLabel: server.statusLabel,
                  statusTone: server.statusTone,
                  trustLine: server.trustLine,
                  authLine: authToken == null || authToken.trim().isEmpty
                      ? 'Auth: not required'
                      : 'Auth: configured',
                  readinessLine: server.readinessLine,
                  transportLine: server.transportLine,
                  exposureLine: server.exposureLine,
                  guidance: server.guidance,
                  actionLabel: server.actionLabel,
                  actionTurnsOn: server.actionTurnsOn,
                  isActionEnabled: server.isActionEnabled,
                )
              : server,
        )
        .toList(growable: false);
    _mcpSettings = OpenCrayMcpSettingsSnapshot(
      title: _mcpSettings.title,
      subtitle: _mcpSettings.subtitle,
      masterEnabled: _mcpSettings.masterEnabled,
      masterTitle: _mcpSettings.masterTitle,
      masterSummary: _mcpSettings.masterSummary,
      summaryLine: _mcpSettings.summaryLine,
      serversTitle: _mcpSettings.serversTitle,
      serversHelper: _mcpSettings.serversHelper,
      masterDisabledTitle: _mcpSettings.masterDisabledTitle,
      masterDisabledBody: _mcpSettings.masterDisabledBody,
      servers: servers,
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
}
