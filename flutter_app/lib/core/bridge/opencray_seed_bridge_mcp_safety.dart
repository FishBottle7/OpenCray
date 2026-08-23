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
