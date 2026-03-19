class OpenCraySafetySettingsSnapshot {
  const OpenCraySafetySettingsSnapshot({
    required this.automationModeId,
    required this.rollbackJournalEnabled,
    required this.maxFilesPerBatch,
    this.maxAgentTurns = 0,
    this.maxToolCalls = 0,
    required this.undoWindowHours,
    required this.fileChangesPolicyId,
    required this.fileDeletesPolicyId,
    required this.shellCommandsPolicyId,
    required this.externalAccessModeId,
    required this.locations,
    required this.workspaceAccessProfileId,
    required this.readOnlyOutsideWorkspace,
    this.liveContextModeId = 'full',
    this.memoryToolsEnabled = true,
  });

  final String automationModeId;
  final bool rollbackJournalEnabled;
  final int maxFilesPerBatch;
  final int maxAgentTurns;
  final int maxToolCalls;
  final int undoWindowHours;
  final String fileChangesPolicyId;
  final String fileDeletesPolicyId;
  final String shellCommandsPolicyId;
  final String externalAccessModeId;
  final List<OpenCraySafetySettingsLocationSnapshot> locations;
  final String workspaceAccessProfileId;
  final bool readOnlyOutsideWorkspace;
  final String liveContextModeId;
  final bool memoryToolsEnabled;

  factory OpenCraySafetySettingsSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySafetySettingsSnapshot(
      automationModeId: payload['automationModeId'] as String? ?? 'auto',
      rollbackJournalEnabled:
          payload['rollbackJournalEnabled'] as bool? ?? true,
      maxFilesPerBatch: payload['maxFilesPerBatch'] as int? ?? 20,
      maxAgentTurns: payload['maxAgentTurns'] as int? ?? 0,
      maxToolCalls: payload['maxToolCalls'] as int? ?? 0,
      undoWindowHours: payload['undoWindowHours'] as int? ?? 24,
      fileChangesPolicyId:
          payload['fileChangesPolicyId'] as String? ?? 'inherit',
      fileDeletesPolicyId:
          payload['fileDeletesPolicyId'] as String? ?? 'inherit',
      shellCommandsPolicyId:
          payload['shellCommandsPolicyId'] as String? ?? 'inherit',
      externalAccessModeId:
          payload['externalAccessModeId'] as String? ?? 'select_paths',
      locations: _requireList(payload['locations'])
          .map(_requireMap)
          .map(OpenCraySafetySettingsLocationSnapshot.fromMap)
          .toList(growable: false),
      workspaceAccessProfileId:
          payload['workspaceAccessProfileId'] as String? ?? 'work',
      readOnlyOutsideWorkspace:
          payload['readOnlyOutsideWorkspace'] as bool? ?? true,
      liveContextModeId: payload['liveContextModeId'] as String? ?? 'full',
      memoryToolsEnabled: payload['memoryToolsEnabled'] as bool? ?? true,
    );
  }
}

class OpenCraySafetySettingsLocationSnapshot {
  const OpenCraySafetySettingsLocationSnapshot({
    required this.id,
    required this.enabled,
  });

  final String id;
  final bool enabled;

  factory OpenCraySafetySettingsLocationSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCraySafetySettingsLocationSnapshot(
      id: payload['id'] as String? ?? '',
      enabled: payload['enabled'] as bool? ?? false,
    );
  }
}

List<Object?> _requireList(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Object?>[];
  }
  return list;
}

Map<Object?, Object?> _requireMap(Object? payload) {
  final map = payload as Map<Object?, Object?>?;
  if (map == null) {
    throw const FormatException('Expected a map payload from host bridge.');
  }
  return map;
}
