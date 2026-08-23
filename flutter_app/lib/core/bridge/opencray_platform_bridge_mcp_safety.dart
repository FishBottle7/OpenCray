part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeMcpSafetyDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCrayMcpSettingsSnapshot> loadMcpSettings() async =>
      OpenCrayMcpSettingsSnapshot.fromMap(await _invokeMap('loadMcpSettings'));

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      OpenCrayMcpSettingsSnapshot.fromMap(
        await _invokeMap(
          'setMcpMasterEnabled',
          arguments: <String, Object?>{'enabled': enabled},
        ),
      );

  @override
  Future<OpenCrayMcpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => OpenCrayMcpSettingsSnapshot.fromMap(
    await _invokeMap(
      'setMcpServerEnabled',
      arguments: <String, Object?>{'serverId': serverId, 'enabled': enabled},
    ),
  );

  @override
  Future<OpenCraySafetySettingsSnapshot> loadSafetySettings() async =>
      OpenCraySafetySettingsSnapshot.fromMap(
        await _invokeMap('loadSafetySettings'),
      );

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) async =>
      await _methodChannel.invokeMethod<bool>(
        'authorizeExternalAccessLocation',
        <String, Object?>{'locationId': locationId},
      ) ??
      false;

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
  }) async => OpenCraySafetySettingsSnapshot.fromMap(
    await _invokeMap(
      'saveSafetySettings',
      arguments: <String, Object?>{
        'automationModeId': automationModeId,
        'rollbackJournalEnabled': rollbackJournalEnabled,
        'maxFilesPerBatch': maxFilesPerBatch,
        'maxAgentTurns': maxAgentTurns,
        'maxToolCalls': maxToolCalls,
        'undoWindowHours': undoWindowHours,
        'fileChangesPolicyId': fileChangesPolicyId,
        'fileDeletesPolicyId': fileDeletesPolicyId,
        'shellCommandsPolicyId': shellCommandsPolicyId,
        'externalAccessModeId': externalAccessModeId,
        'photoLibraryEnabled': photoLibraryEnabled,
        'downloadsEnabled': downloadsEnabled,
        'documentsEnabled': documentsEnabled,
        'recordingsEnabled': recordingsEnabled,
        'workspaceAccessProfileId': workspaceAccessProfileId,
        'readOnlyOutsideWorkspace': readOnlyOutsideWorkspace,
        'liveContextModeId': liveContextModeId,
        'memoryToolsEnabled': memoryToolsEnabled,
        'subAgentContextDefaultModeId': subAgentContextDefaultModeId,
        'subAgentContextProfileOverrides': subAgentContextProfileOverrides,
      },
    ),
  );
}
