class OpenCrayMemoryDebugSnapshot {
  const OpenCrayMemoryDebugSnapshot({
    required this.sessionId,
    required this.observedAtEpochMs,
    this.workspaceId = '',
    this.records = const <OpenCrayMemoryDebugRecordSnapshot>[],
  });

  final String sessionId;
  final String workspaceId;
  final int observedAtEpochMs;
  final List<OpenCrayMemoryDebugRecordSnapshot> records;

  factory OpenCrayMemoryDebugSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawRecords = map['records'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayMemoryDebugSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      workspaceId: map['workspaceId'] as String? ?? '',
      observedAtEpochMs: map['observedAtEpochMs'] as int? ?? 0,
      records: rawRecords
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryDebugRecordSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayMemoryDebugRecordSnapshot {
  const OpenCrayMemoryDebugRecordSnapshot({
    required this.id,
    required this.content,
    this.kind = '',
    this.scope = '',
    this.status = '',
    this.source = '',
    this.sourceSessionId = '',
    this.sourceTaskId = '',
    this.workspaceId = '',
    this.preferenceKey = '',
    this.preferenceValue = '',
    this.preferenceTemporality = '',
    this.createdAtEpochMs = 0,
    this.updatedAtEpochMs = 0,
    this.lastConfirmedAtEpochMs,
    this.resolvedAtEpochMs,
    this.ttlMs,
    this.isExpired = false,
    this.recordVersion = 0,
    this.resolutionReason = '',
    this.supersededBy = '',
    this.tags = const <String>[],
    this.extensions = const <String, String>{},
  });

  final String id;
  final String content;
  final String kind;
  final String scope;
  final String status;
  final String source;
  final String sourceSessionId;
  final String sourceTaskId;
  final String workspaceId;
  final String preferenceKey;
  final String preferenceValue;
  final String preferenceTemporality;
  final int createdAtEpochMs;
  final int updatedAtEpochMs;
  final int? lastConfirmedAtEpochMs;
  final int? resolvedAtEpochMs;
  final int? ttlMs;
  final bool isExpired;
  final int recordVersion;
  final String resolutionReason;
  final String supersededBy;
  final List<String> tags;
  final Map<String, String> extensions;

  factory OpenCrayMemoryDebugRecordSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayMemoryDebugRecordSnapshot(
      id: map['id'] as String? ?? '',
      content: map['content'] as String? ?? '',
      kind: map['kind'] as String? ?? '',
      scope: map['scope'] as String? ?? '',
      status: map['status'] as String? ?? '',
      source: map['source'] as String? ?? '',
      sourceSessionId: map['sourceSessionId'] as String? ?? '',
      sourceTaskId: map['sourceTaskId'] as String? ?? '',
      workspaceId: map['workspaceId'] as String? ?? '',
      preferenceKey: map['preferenceKey'] as String? ?? '',
      preferenceValue: map['preferenceValue'] as String? ?? '',
      preferenceTemporality: map['preferenceTemporality'] as String? ?? '',
      createdAtEpochMs: map['createdAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      lastConfirmedAtEpochMs: map['lastConfirmedAtEpochMs'] as int?,
      resolvedAtEpochMs: map['resolvedAtEpochMs'] as int?,
      ttlMs: map['ttlMs'] as int?,
      isExpired: map['isExpired'] as bool? ?? false,
      recordVersion: map['recordVersion'] as int? ?? 0,
      resolutionReason: map['resolutionReason'] as String? ?? '',
      supersededBy: map['supersededBy'] as String? ?? '',
      tags: _readStringList(map['tags']),
      extensions: _readStringMap(map['extensions']),
    );
  }
}

class OpenCraySoulDebugSnapshot {
  const OpenCraySoulDebugSnapshot({
    required this.sessionId,
    required this.observedAtEpochMs,
    this.workspaceId = '',
    this.storedSoul,
    this.baseSoul,
    this.effectiveSoul,
    this.overlayRecords = const <OpenCrayMemoryDebugRecordSnapshot>[],
    this.fieldSources = const <OpenCraySoulFieldSourceSnapshot>[],
  });

  final String sessionId;
  final String workspaceId;
  final int observedAtEpochMs;
  final OpenCrayStoredSoulRecordSnapshot? storedSoul;
  final OpenCraySoulProfileDebugSnapshot? baseSoul;
  final OpenCraySoulProfileDebugSnapshot? effectiveSoul;
  final List<OpenCrayMemoryDebugRecordSnapshot> overlayRecords;
  final List<OpenCraySoulFieldSourceSnapshot> fieldSources;

  factory OpenCraySoulDebugSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawOverlayRecords =
        map['overlayRecords'] as List<Object?>? ?? const <Object?>[];
    final rawFieldSources =
        map['fieldSources'] as List<Object?>? ?? const <Object?>[];
    final rawStoredSoul = map['storedSoul'] as Map<Object?, Object?>?;
    final rawBaseSoul = map['baseSoul'] as Map<Object?, Object?>?;
    final rawEffectiveSoul = map['effectiveSoul'] as Map<Object?, Object?>?;
    return OpenCraySoulDebugSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      workspaceId: map['workspaceId'] as String? ?? '',
      observedAtEpochMs: map['observedAtEpochMs'] as int? ?? 0,
      storedSoul: rawStoredSoul == null
          ? null
          : OpenCrayStoredSoulRecordSnapshot.fromMap(rawStoredSoul),
      baseSoul: rawBaseSoul == null
          ? null
          : OpenCraySoulProfileDebugSnapshot.fromMap(rawBaseSoul),
      effectiveSoul: rawEffectiveSoul == null
          ? null
          : OpenCraySoulProfileDebugSnapshot.fromMap(rawEffectiveSoul),
      overlayRecords: rawOverlayRecords
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryDebugRecordSnapshot.fromMap)
          .toList(growable: false),
      fieldSources: rawFieldSources
          .whereType<Map<Object?, Object?>>()
          .map(OpenCraySoulFieldSourceSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayStoredSoulRecordSnapshot {
  const OpenCrayStoredSoulRecordSnapshot({
    required this.agentId,
    this.displayName = '',
    this.presetName = '',
    this.customGuidance = '',
    this.recordVersion = 0,
    this.createdAtEpochMs = 0,
    this.updatedAtEpochMs = 0,
    this.extensions = const <String, String>{},
  });

  final String agentId;
  final String displayName;
  final String presetName;
  final String customGuidance;
  final int recordVersion;
  final int createdAtEpochMs;
  final int updatedAtEpochMs;
  final Map<String, String> extensions;

  factory OpenCrayStoredSoulRecordSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayStoredSoulRecordSnapshot(
      agentId: map['agentId'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      presetName: map['presetName'] as String? ?? '',
      customGuidance: map['customGuidance'] as String? ?? '',
      recordVersion: map['recordVersion'] as int? ?? 0,
      createdAtEpochMs: map['createdAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      extensions: _readStringMap(map['extensions']),
    );
  }
}

class OpenCraySoulProfileDebugSnapshot {
  const OpenCraySoulProfileDebugSnapshot({
    this.presetName = '',
    this.displayName = '',
    this.voice = '',
    this.customGuidance = '',
    this.tone = '',
    this.verbosity = '',
    this.userRelationshipStyle = '',
    this.riskTolerance = '',
    this.toolUseBias = '',
    this.escalationRules = const <String>[],
    this.forbiddenBehaviors = const <String>[],
    this.collaborationPreferences = const <String>[],
    this.extensions = const <String, String>{},
  });

  final String presetName;
  final String displayName;
  final String voice;
  final String customGuidance;
  final String tone;
  final String verbosity;
  final String userRelationshipStyle;
  final String riskTolerance;
  final String toolUseBias;
  final List<String> escalationRules;
  final List<String> forbiddenBehaviors;
  final List<String> collaborationPreferences;
  final Map<String, String> extensions;

  factory OpenCraySoulProfileDebugSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCraySoulProfileDebugSnapshot(
      presetName: map['presetName'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      voice: map['voice'] as String? ?? '',
      customGuidance: map['customGuidance'] as String? ?? '',
      tone: map['tone'] as String? ?? '',
      verbosity: map['verbosity'] as String? ?? '',
      userRelationshipStyle: map['userRelationshipStyle'] as String? ?? '',
      riskTolerance: map['riskTolerance'] as String? ?? '',
      toolUseBias: map['toolUseBias'] as String? ?? '',
      escalationRules: _readStringList(map['escalationRules']),
      forbiddenBehaviors: _readStringList(map['forbiddenBehaviors']),
      collaborationPreferences: _readStringList(
        map['collaborationPreferences'],
      ),
      extensions: _readStringMap(map['extensions']),
    );
  }
}

class OpenCraySoulFieldSourceSnapshot {
  const OpenCraySoulFieldSourceSnapshot({
    required this.field,
    this.value = '',
    this.sourceType = '',
    this.sourceLabel = '',
    this.recordId = '',
    this.preferenceKey = '',
  });

  final String field;
  final String value;
  final String sourceType;
  final String sourceLabel;
  final String recordId;
  final String preferenceKey;

  factory OpenCraySoulFieldSourceSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCraySoulFieldSourceSnapshot(
      field: map['field'] as String? ?? '',
      value: map['value'] as String? ?? '',
      sourceType: map['sourceType'] as String? ?? '',
      sourceLabel: map['sourceLabel'] as String? ?? '',
      recordId: map['recordId'] as String? ?? '',
      preferenceKey: map['preferenceKey'] as String? ?? '',
    );
  }
}

List<String> _readStringList(Object? raw) {
  final values = raw as List<Object?>? ?? const <Object?>[];
  return values
      .whereType<String>()
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

Map<String, String> _readStringMap(Object? raw) {
  final values = raw as Map<Object?, Object?>? ?? const <Object?, Object?>{};
  final result = <String, String>{};
  for (final entry in values.entries) {
    final key = entry.key?.toString().trim() ?? '';
    final value = entry.value?.toString().trim() ?? '';
    if (key.isEmpty || value.isEmpty) {
      continue;
    }
    result[key] = value;
  }
  return result;
}
