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
    this.interactionPreferenceDebug,
    this.relationshipStateDebug,
  });

  final String sessionId;
  final String workspaceId;
  final int observedAtEpochMs;
  final OpenCrayStoredSoulRecordSnapshot? storedSoul;
  final OpenCraySoulProfileDebugSnapshot? baseSoul;
  final OpenCraySoulProfileDebugSnapshot? effectiveSoul;
  final List<OpenCrayMemoryDebugRecordSnapshot> overlayRecords;
  final List<OpenCraySoulFieldSourceSnapshot> fieldSources;
  final OpenCrayInteractionPreferenceDebugSnapshot? interactionPreferenceDebug;
  final OpenCrayRelationshipStateDebugSnapshot? relationshipStateDebug;

  factory OpenCraySoulDebugSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawOverlayRecords =
        map['overlayRecords'] as List<Object?>? ?? const <Object?>[];
    final rawFieldSources =
        map['fieldSources'] as List<Object?>? ?? const <Object?>[];
    final rawStoredSoul = map['storedSoul'] as Map<Object?, Object?>?;
    final rawBaseSoul = map['baseSoul'] as Map<Object?, Object?>?;
    final rawEffectiveSoul = map['effectiveSoul'] as Map<Object?, Object?>?;
    final rawInteractionPreferenceDebug =
        map['interactionPreferenceDebug'] as Map<Object?, Object?>?;
    final rawRelationshipStateDebug =
        map['relationshipStateDebug'] as Map<Object?, Object?>?;
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
      interactionPreferenceDebug: rawInteractionPreferenceDebug == null
          ? null
          : OpenCrayInteractionPreferenceDebugSnapshot.fromMap(
              rawInteractionPreferenceDebug,
            ),
      relationshipStateDebug: rawRelationshipStateDebug == null
          ? null
          : OpenCrayRelationshipStateDebugSnapshot.fromMap(
              rawRelationshipStateDebug,
            ),
    );
  }
}

class OpenCrayInteractionPreferenceDebugSnapshot {
  const OpenCrayInteractionPreferenceDebugSnapshot({
    this.scope = '',
    this.snapshotRecordId = '',
    this.preferredNaming = '',
    this.preferredAddressStyle = '',
    this.derivedRelationshipStyle = '',
    this.state = const OpenCrayInteractionPreferenceStateSnapshot(),
  });

  final String scope;
  final String snapshotRecordId;
  final String preferredNaming;
  final String preferredAddressStyle;
  final String derivedRelationshipStyle;
  final OpenCrayInteractionPreferenceStateSnapshot state;

  factory OpenCrayInteractionPreferenceDebugSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawState =
        map['state'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    return OpenCrayInteractionPreferenceDebugSnapshot(
      scope: map['scope'] as String? ?? '',
      snapshotRecordId: map['snapshotRecordId'] as String? ?? '',
      preferredNaming: map['preferredNaming'] as String? ?? '',
      preferredAddressStyle: map['preferredAddressStyle'] as String? ?? '',
      derivedRelationshipStyle:
          map['derivedRelationshipStyle'] as String? ?? '',
      state: OpenCrayInteractionPreferenceStateSnapshot.fromMap(rawState),
    );
  }
}

class OpenCrayInteractionPreferenceStateSnapshot {
  const OpenCrayInteractionPreferenceStateSnapshot({
    this.warmth = const OpenCrayPreferenceAxisStateSnapshot(),
    this.formality = const OpenCrayPreferenceAxisStateSnapshot(),
    this.initiative = const OpenCrayPreferenceAxisStateSnapshot(),
    this.addressStyle = const OpenCrayPreferredAddressStateSnapshot(),
    this.preferredNaming = '',
    this.preferredNamingSupport = 0,
    this.lastUpdatedAtEpochMs,
  });

  final OpenCrayPreferenceAxisStateSnapshot warmth;
  final OpenCrayPreferenceAxisStateSnapshot formality;
  final OpenCrayPreferenceAxisStateSnapshot initiative;
  final OpenCrayPreferredAddressStateSnapshot addressStyle;
  final String preferredNaming;
  final int preferredNamingSupport;
  final int? lastUpdatedAtEpochMs;

  factory OpenCrayInteractionPreferenceStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawWarmth =
        map['warmth'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    final rawFormality =
        map['formality'] as Map<Object?, Object?>? ??
        const <Object?, Object?>{};
    final rawInitiative =
        map['initiative'] as Map<Object?, Object?>? ??
        const <Object?, Object?>{};
    final rawAddressStyle =
        map['addressStyle'] as Map<Object?, Object?>? ??
        const <Object?, Object?>{};
    return OpenCrayInteractionPreferenceStateSnapshot(
      warmth: OpenCrayPreferenceAxisStateSnapshot.fromMap(rawWarmth),
      formality: OpenCrayPreferenceAxisStateSnapshot.fromMap(rawFormality),
      initiative: OpenCrayPreferenceAxisStateSnapshot.fromMap(rawInitiative),
      addressStyle: OpenCrayPreferredAddressStateSnapshot.fromMap(
        rawAddressStyle,
      ),
      preferredNaming: map['preferredNaming'] as String? ?? '',
      preferredNamingSupport: map['preferredNamingSupport'] as int? ?? 0,
      lastUpdatedAtEpochMs: map['lastUpdatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayPreferenceAxisStateSnapshot {
  const OpenCrayPreferenceAxisStateSnapshot({
    this.offset = 0,
    this.higherSupport = 0,
    this.lowerSupport = 0,
    this.lastUpdatedAtEpochMs,
  });

  final int offset;
  final int higherSupport;
  final int lowerSupport;
  final int? lastUpdatedAtEpochMs;

  factory OpenCrayPreferenceAxisStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayPreferenceAxisStateSnapshot(
      offset: map['offset'] as int? ?? 0,
      higherSupport: map['higherSupport'] as int? ?? 0,
      lowerSupport: map['lowerSupport'] as int? ?? 0,
      lastUpdatedAtEpochMs: map['lastUpdatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayPreferredAddressStateSnapshot {
  const OpenCrayPreferredAddressStateSnapshot({
    this.selectedStyle = '',
    this.neutralSupport = 0,
    this.friendlySupport = 0,
    this.intimateSupport = 0,
    this.lastUpdatedAtEpochMs,
  });

  final String selectedStyle;
  final int neutralSupport;
  final int friendlySupport;
  final int intimateSupport;
  final int? lastUpdatedAtEpochMs;

  factory OpenCrayPreferredAddressStateSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayPreferredAddressStateSnapshot(
      selectedStyle: map['selectedStyle'] as String? ?? '',
      neutralSupport: map['neutralSupport'] as int? ?? 0,
      friendlySupport: map['friendlySupport'] as int? ?? 0,
      intimateSupport: map['intimateSupport'] as int? ?? 0,
      lastUpdatedAtEpochMs: map['lastUpdatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayRelationshipStateDebugSnapshot {
  const OpenCrayRelationshipStateDebugSnapshot({
    this.scope = '',
    this.snapshotRecordId = '',
    this.appliedEventRecordIds = const <String>[],
    this.state = const OpenCrayRelationshipStateSnapshot(),
    this.recentNegativeGuardActive = false,
    this.supportiveStyleUnlocked = false,
    this.supportiveStyleChecks = const <OpenCraySoulGateCheckSnapshot>[],
    this.warmToneUnlocked = false,
    this.warmToneChecks = const <OpenCraySoulGateCheckSnapshot>[],
    this.derivedAddressStyle = '',
    this.friendlyAddressChecks = const <OpenCraySoulGateCheckSnapshot>[],
    this.intimateAddressChecks = const <OpenCraySoulGateCheckSnapshot>[],
    this.intimacyPermissionBand = '',
    this.playfulnessPermissionBand = '',
    this.highIntimacyBehaviorAllowed = false,
    this.highIntimacyChecks = const <OpenCraySoulGateCheckSnapshot>[],
    this.playfulAffectionAllowed = false,
    this.playfulAffectionChecks = const <OpenCraySoulGateCheckSnapshot>[],
  });

  final String scope;
  final String snapshotRecordId;
  final List<String> appliedEventRecordIds;
  final OpenCrayRelationshipStateSnapshot state;
  final bool recentNegativeGuardActive;
  final bool supportiveStyleUnlocked;
  final List<OpenCraySoulGateCheckSnapshot> supportiveStyleChecks;
  final bool warmToneUnlocked;
  final List<OpenCraySoulGateCheckSnapshot> warmToneChecks;
  final String derivedAddressStyle;
  final List<OpenCraySoulGateCheckSnapshot> friendlyAddressChecks;
  final List<OpenCraySoulGateCheckSnapshot> intimateAddressChecks;
  final String intimacyPermissionBand;
  final String playfulnessPermissionBand;
  final bool highIntimacyBehaviorAllowed;
  final List<OpenCraySoulGateCheckSnapshot> highIntimacyChecks;
  final bool playfulAffectionAllowed;
  final List<OpenCraySoulGateCheckSnapshot> playfulAffectionChecks;

  factory OpenCrayRelationshipStateDebugSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawState =
        map['state'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    return OpenCrayRelationshipStateDebugSnapshot(
      scope: map['scope'] as String? ?? '',
      snapshotRecordId: map['snapshotRecordId'] as String? ?? '',
      appliedEventRecordIds: _readStringList(map['appliedEventRecordIds']),
      state: OpenCrayRelationshipStateSnapshot.fromMap(rawState),
      recentNegativeGuardActive:
          map['recentNegativeGuardActive'] as bool? ?? false,
      supportiveStyleUnlocked: map['supportiveStyleUnlocked'] as bool? ?? false,
      supportiveStyleChecks: _readSoulGateChecks(map['supportiveStyleChecks']),
      warmToneUnlocked: map['warmToneUnlocked'] as bool? ?? false,
      warmToneChecks: _readSoulGateChecks(map['warmToneChecks']),
      derivedAddressStyle: map['derivedAddressStyle'] as String? ?? '',
      friendlyAddressChecks: _readSoulGateChecks(map['friendlyAddressChecks']),
      intimateAddressChecks: _readSoulGateChecks(map['intimateAddressChecks']),
      intimacyPermissionBand: map['intimacyPermissionBand'] as String? ?? '',
      playfulnessPermissionBand:
          map['playfulnessPermissionBand'] as String? ?? '',
      highIntimacyBehaviorAllowed:
          map['highIntimacyBehaviorAllowed'] as bool? ?? false,
      highIntimacyChecks: _readSoulGateChecks(map['highIntimacyChecks']),
      playfulAffectionAllowed: map['playfulAffectionAllowed'] as bool? ?? false,
      playfulAffectionChecks: _readSoulGateChecks(
        map['playfulAffectionChecks'],
      ),
    );
  }
}

class OpenCrayRelationshipStateSnapshot {
  const OpenCrayRelationshipStateSnapshot({
    this.familiarity = 0,
    this.trust = 0,
    this.safety = 0,
    this.intimacyPermission = 0,
    this.playfulnessPermission = 0,
    this.affectionTendency = 0,
    this.reciprocity = 0,
    this.lastPositiveEventAtEpochMs,
    this.lastNegativeEventAtEpochMs,
    this.lastUpdatedAtEpochMs,
  });

  final int familiarity;
  final int trust;
  final int safety;
  final int intimacyPermission;
  final int playfulnessPermission;
  final int affectionTendency;
  final int reciprocity;
  final int? lastPositiveEventAtEpochMs;
  final int? lastNegativeEventAtEpochMs;
  final int? lastUpdatedAtEpochMs;

  factory OpenCrayRelationshipStateSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayRelationshipStateSnapshot(
      familiarity: map['familiarity'] as int? ?? 0,
      trust: map['trust'] as int? ?? 0,
      safety: map['safety'] as int? ?? 0,
      intimacyPermission: map['intimacyPermission'] as int? ?? 0,
      playfulnessPermission: map['playfulnessPermission'] as int? ?? 0,
      affectionTendency: map['affectionTendency'] as int? ?? 0,
      reciprocity: map['reciprocity'] as int? ?? 0,
      lastPositiveEventAtEpochMs: map['lastPositiveEventAtEpochMs'] as int?,
      lastNegativeEventAtEpochMs: map['lastNegativeEventAtEpochMs'] as int?,
      lastUpdatedAtEpochMs: map['lastUpdatedAtEpochMs'] as int?,
    );
  }
}

class OpenCraySoulGateCheckSnapshot {
  const OpenCraySoulGateCheckSnapshot({
    this.key = '',
    this.passed = false,
    this.currentValue,
    this.threshold,
    this.actualBoolean,
    this.expectedBoolean,
  });

  final String key;
  final bool passed;
  final int? currentValue;
  final int? threshold;
  final bool? actualBoolean;
  final bool? expectedBoolean;

  factory OpenCraySoulGateCheckSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCraySoulGateCheckSnapshot(
      key: map['key'] as String? ?? '',
      passed: map['passed'] as bool? ?? false,
      currentValue: map['currentValue'] as int?,
      threshold: map['threshold'] as int?,
      actualBoolean: map['actualBoolean'] as bool?,
      expectedBoolean: map['expectedBoolean'] as bool?,
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
    this.preferredNaming = '',
    this.preferredAddressStyle = '',
    this.intimacyPermissionBand = '',
    this.playfulnessPermissionBand = '',
    this.highIntimacyBehaviorAllowed = '',
    this.playfulAffectionAllowed = '',
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
  final String preferredNaming;
  final String preferredAddressStyle;
  final String intimacyPermissionBand;
  final String playfulnessPermissionBand;
  final String highIntimacyBehaviorAllowed;
  final String playfulAffectionAllowed;
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
      preferredNaming: map['preferredNaming'] as String? ?? '',
      preferredAddressStyle: map['preferredAddressStyle'] as String? ?? '',
      intimacyPermissionBand: map['intimacyPermissionBand'] as String? ?? '',
      playfulnessPermissionBand:
          map['playfulnessPermissionBand'] as String? ?? '',
      highIntimacyBehaviorAllowed:
          map['highIntimacyBehaviorAllowed']?.toString() ?? '',
      playfulAffectionAllowed: map['playfulAffectionAllowed']?.toString() ?? '',
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
    this.sourceScope = '',
    this.sourceDetail = '',
  });

  final String field;
  final String value;
  final String sourceType;
  final String sourceLabel;
  final String recordId;
  final String preferenceKey;
  final String sourceScope;
  final String sourceDetail;

  factory OpenCraySoulFieldSourceSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCraySoulFieldSourceSnapshot(
      field: map['field'] as String? ?? '',
      value: map['value'] as String? ?? '',
      sourceType: map['sourceType'] as String? ?? '',
      sourceLabel: map['sourceLabel'] as String? ?? '',
      recordId: map['recordId'] as String? ?? '',
      preferenceKey: map['preferenceKey'] as String? ?? '',
      sourceScope: map['sourceScope'] as String? ?? '',
      sourceDetail: map['sourceDetail'] as String? ?? '',
    );
  }
}

class OpenCrayMemoryDebugLinksSnapshot {
  const OpenCrayMemoryDebugLinksSnapshot({
    required this.sessionId,
    required this.observedAtEpochMs,
    this.workspaceId = '',
    this.records = const <OpenCrayMemoryDebugLinksEntrySnapshot>[],
  });

  final String sessionId;
  final String workspaceId;
  final int observedAtEpochMs;
  final List<OpenCrayMemoryDebugLinksEntrySnapshot> records;

  factory OpenCrayMemoryDebugLinksSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawRecords = map['records'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayMemoryDebugLinksSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      workspaceId: map['workspaceId'] as String? ?? '',
      observedAtEpochMs: map['observedAtEpochMs'] as int? ?? 0,
      records: rawRecords
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryDebugLinksEntrySnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayMemoryDebugLinksEntrySnapshot {
  const OpenCrayMemoryDebugLinksEntrySnapshot({
    required this.recordId,
    this.sourceSessionId = '',
    this.sourceTaskId = '',
    this.sourceRun,
    this.promptRecalls = const <OpenCrayMemoryPromptRecallLinkSnapshot>[],
    this.toolRetrievals = const <OpenCrayMemoryToolRetrievalLinkSnapshot>[],
    this.maintenanceActions =
        const <OpenCrayMemoryMaintenanceActionLinkSnapshot>[],
  });

  final String recordId;
  final String sourceSessionId;
  final String sourceTaskId;
  final OpenCrayDebugRunLinkSnapshot? sourceRun;
  final List<OpenCrayMemoryPromptRecallLinkSnapshot> promptRecalls;
  final List<OpenCrayMemoryToolRetrievalLinkSnapshot> toolRetrievals;
  final List<OpenCrayMemoryMaintenanceActionLinkSnapshot> maintenanceActions;

  factory OpenCrayMemoryDebugLinksEntrySnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawPromptRecalls =
        map['promptRecalls'] as List<Object?>? ?? const <Object?>[];
    final rawToolRetrievals =
        map['toolRetrievals'] as List<Object?>? ?? const <Object?>[];
    final rawMaintenanceActions =
        map['maintenanceActions'] as List<Object?>? ?? const <Object?>[];
    final rawSourceRun = map['sourceRun'];
    return OpenCrayMemoryDebugLinksEntrySnapshot(
      recordId: map['recordId'] as String? ?? '',
      sourceSessionId: map['sourceSessionId'] as String? ?? '',
      sourceTaskId: map['sourceTaskId'] as String? ?? '',
      sourceRun: rawSourceRun is Map<Object?, Object?>
          ? OpenCrayDebugRunLinkSnapshot.fromMap(rawSourceRun)
          : null,
      promptRecalls: rawPromptRecalls
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryPromptRecallLinkSnapshot.fromMap)
          .toList(growable: false),
      toolRetrievals: rawToolRetrievals
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryToolRetrievalLinkSnapshot.fromMap)
          .toList(growable: false),
      maintenanceActions: rawMaintenanceActions
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayMemoryMaintenanceActionLinkSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayDebugRunLinkSnapshot {
  const OpenCrayDebugRunLinkSnapshot({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
    required this.updatedAtEpochMs,
    this.lifecycleState,
    this.executionStatus,
    this.errorCode,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;
  final int updatedAtEpochMs;
  final String? lifecycleState;
  final String? executionStatus;
  final String? errorCode;

  factory OpenCrayDebugRunLinkSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayDebugRunLinkSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      lifecycleState: map['lifecycleState'] as String?,
      executionStatus: map['executionStatus'] as String?,
      errorCode: map['errorCode'] as String?,
    );
  }
}

class OpenCrayMemoryPromptRecallLinkSnapshot {
  const OpenCrayMemoryPromptRecallLinkSnapshot({
    required this.occurredAtEpochMs,
    required this.run,
    this.score,
    this.matchedTerms = const <String>[],
  });

  final int occurredAtEpochMs;
  final OpenCrayDebugRunLinkSnapshot run;
  final int? score;
  final List<String> matchedTerms;

  factory OpenCrayMemoryPromptRecallLinkSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawMatchedTerms =
        map['matchedTerms'] as List<Object?>? ?? const <Object?>[];
    final rawRun =
        map['run'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    return OpenCrayMemoryPromptRecallLinkSnapshot(
      occurredAtEpochMs: map['occurredAtEpochMs'] as int? ?? 0,
      run: OpenCrayDebugRunLinkSnapshot.fromMap(rawRun),
      score: map['score'] as int?,
      matchedTerms: rawMatchedTerms
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
    );
  }
}

class OpenCrayMemoryToolRetrievalLinkSnapshot {
  const OpenCrayMemoryToolRetrievalLinkSnapshot({
    required this.occurredAtEpochMs,
    required this.run,
    this.toolName = '',
    this.operation = '',
    this.query,
    this.queryTerms = const <String>[],
    this.paths = const <String>[],
    this.lineRanges = const <String>[],
    this.path,
    this.fromLine,
    this.returnedLineCount,
  });

  final int occurredAtEpochMs;
  final OpenCrayDebugRunLinkSnapshot run;
  final String toolName;
  final String operation;
  final String? query;
  final List<String> queryTerms;
  final List<String> paths;
  final List<String> lineRanges;
  final String? path;
  final int? fromLine;
  final int? returnedLineCount;

  factory OpenCrayMemoryToolRetrievalLinkSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawQueryTerms =
        map['queryTerms'] as List<Object?>? ?? const <Object?>[];
    final rawPaths = map['paths'] as List<Object?>? ?? const <Object?>[];
    final rawLineRanges =
        map['lineRanges'] as List<Object?>? ?? const <Object?>[];
    final rawRun =
        map['run'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    return OpenCrayMemoryToolRetrievalLinkSnapshot(
      occurredAtEpochMs: map['occurredAtEpochMs'] as int? ?? 0,
      run: OpenCrayDebugRunLinkSnapshot.fromMap(rawRun),
      toolName: map['toolName'] as String? ?? '',
      operation: map['operation'] as String? ?? '',
      query: map['query'] as String?,
      queryTerms: rawQueryTerms
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      paths: rawPaths
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      lineRanges: rawLineRanges
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      path: map['path'] as String?,
      fromLine: map['fromLine'] as int?,
      returnedLineCount: map['returnedLineCount'] as int?,
    );
  }
}

class OpenCrayMemoryMaintenanceActionLinkSnapshot {
  const OpenCrayMemoryMaintenanceActionLinkSnapshot({
    required this.action,
    required this.occurredAtEpochMs,
    required this.run,
  });

  final String action;
  final int occurredAtEpochMs;
  final OpenCrayDebugRunLinkSnapshot run;

  factory OpenCrayMemoryMaintenanceActionLinkSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawRun =
        map['run'] as Map<Object?, Object?>? ?? const <Object?, Object?>{};
    return OpenCrayMemoryMaintenanceActionLinkSnapshot(
      action: map['action'] as String? ?? '',
      occurredAtEpochMs: map['occurredAtEpochMs'] as int? ?? 0,
      run: OpenCrayDebugRunLinkSnapshot.fromMap(rawRun),
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

List<OpenCraySoulGateCheckSnapshot> _readSoulGateChecks(Object? raw) {
  final values = raw as List<Object?>? ?? const <Object?>[];
  return values
      .whereType<Map<Object?, Object?>>()
      .map(OpenCraySoulGateCheckSnapshot.fromMap)
      .toList(growable: false);
}
