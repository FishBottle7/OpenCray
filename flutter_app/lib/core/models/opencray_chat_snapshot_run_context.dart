class OpenCrayChatRunLiveContextSnapshot {
  const OpenCrayChatRunLiveContextSnapshot({
    this.mode,
    this.soulEnabled,
    this.memoryRecallEnabled,
  });

  final String? mode;
  final bool? soulEnabled;
  final bool? memoryRecallEnabled;

  factory OpenCrayChatRunLiveContextSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunLiveContextSnapshot(
      mode: map['mode'] as String?,
      soulEnabled: map['soulEnabled'] as bool?,
      memoryRecallEnabled: map['memoryRecallEnabled'] as bool?,
    );
  }
}

class OpenCrayChatRunContextBudgetSnapshot {
  const OpenCrayChatRunContextBudgetSnapshot({
    this.applied,
    this.pressureMode,
    this.selectedPreset,
    this.effectivePreset,
    this.presetSource,
    this.presetDiverged,
    this.sourcePreset,
    this.sourceTranscriptMaxMessages,
    this.sourceInjectedMemoryMaxRecords,
    this.sourceMemoryRecallMaxRecords,
    this.sourceBootstrapMaxChars,
    this.sourceSkillInventoryMaxSkills,
    this.sourceActiveSkillMaxChars,
    this.sourceRecentObservationMaxEntries,
    this.sourceMemoryFlushMaxToolObservations,
    this.contextWindowTokens,
    this.reservedOutputTokens,
    this.safetyMarginTokens,
    this.hardInputTokens,
    this.targetInputTokens,
    this.emergencyInputTokens,
    this.unresolvedOverflow,
    this.fullLayerCount,
    this.compactLayerCount,
    this.minimalLayerCount,
    this.omittedLayerCount,
    this.reducedLayerNames = const <String>[],
    this.omittedLayerNames = const <String>[],
    this.layers = const <OpenCrayChatRunContextBudgetLayerSnapshot>[],
    this.layerSummary,
  });

  final bool? applied;
  final String? pressureMode;
  final String? selectedPreset;
  final String? effectivePreset;
  final String? presetSource;
  final bool? presetDiverged;
  final String? sourcePreset;
  final int? sourceTranscriptMaxMessages;
  final int? sourceInjectedMemoryMaxRecords;
  final int? sourceMemoryRecallMaxRecords;
  final int? sourceBootstrapMaxChars;
  final int? sourceSkillInventoryMaxSkills;
  final int? sourceActiveSkillMaxChars;
  final int? sourceRecentObservationMaxEntries;
  final int? sourceMemoryFlushMaxToolObservations;
  final int? contextWindowTokens;
  final int? reservedOutputTokens;
  final int? safetyMarginTokens;
  final int? hardInputTokens;
  final int? targetInputTokens;
  final int? emergencyInputTokens;
  final bool? unresolvedOverflow;
  final int? fullLayerCount;
  final int? compactLayerCount;
  final int? minimalLayerCount;
  final int? omittedLayerCount;
  final List<String> reducedLayerNames;
  final List<String> omittedLayerNames;
  final List<OpenCrayChatRunContextBudgetLayerSnapshot> layers;
  final String? layerSummary;

  factory OpenCrayChatRunContextBudgetSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunContextBudgetSnapshot(
      applied: map['applied'] as bool?,
      pressureMode: map['pressureMode'] as String?,
      selectedPreset: map['selectedPreset'] as String?,
      effectivePreset: map['effectivePreset'] as String?,
      presetSource: map['presetSource'] as String?,
      presetDiverged: map['presetDiverged'] as bool?,
      sourcePreset: map['sourcePreset'] as String?,
      sourceTranscriptMaxMessages: map['sourceTranscriptMaxMessages'] as int?,
      sourceInjectedMemoryMaxRecords:
          map['sourceInjectedMemoryMaxRecords'] as int?,
      sourceMemoryRecallMaxRecords: map['sourceMemoryRecallMaxRecords'] as int?,
      sourceBootstrapMaxChars: map['sourceBootstrapMaxChars'] as int?,
      sourceSkillInventoryMaxSkills:
          map['sourceSkillInventoryMaxSkills'] as int?,
      sourceActiveSkillMaxChars: map['sourceActiveSkillMaxChars'] as int?,
      sourceRecentObservationMaxEntries:
          map['sourceRecentObservationMaxEntries'] as int?,
      sourceMemoryFlushMaxToolObservations:
          map['sourceMemoryFlushMaxToolObservations'] as int?,
      contextWindowTokens: map['contextWindowTokens'] as int?,
      reservedOutputTokens: map['reservedOutputTokens'] as int?,
      safetyMarginTokens: map['safetyMarginTokens'] as int?,
      hardInputTokens: map['hardInputTokens'] as int?,
      targetInputTokens: map['targetInputTokens'] as int?,
      emergencyInputTokens: map['emergencyInputTokens'] as int?,
      unresolvedOverflow: map['unresolvedOverflow'] as bool?,
      fullLayerCount: map['fullLayerCount'] as int?,
      compactLayerCount: map['compactLayerCount'] as int?,
      minimalLayerCount: map['minimalLayerCount'] as int?,
      omittedLayerCount: map['omittedLayerCount'] as int?,
      reducedLayerNames:
          (map['reducedLayerNames'] as List<Object?>?)
              ?.whereType<String>()
              .map((value) => value.trim())
              .where((value) => value.isNotEmpty)
              .toList(growable: false) ??
          const <String>[],
      omittedLayerNames:
          (map['omittedLayerNames'] as List<Object?>?)
              ?.whereType<String>()
              .map((value) => value.trim())
              .where((value) => value.isNotEmpty)
              .toList(growable: false) ??
          const <String>[],
      layers:
          (map['layers'] as List<Object?>?)
              ?.whereType<Map<Object?, Object?>>()
              .map(OpenCrayChatRunContextBudgetLayerSnapshot.fromMap)
              .toList(growable: false) ??
          const <OpenCrayChatRunContextBudgetLayerSnapshot>[],
      layerSummary: map['layerSummary'] as String?,
    );
  }
}

class OpenCrayChatRunContextBudgetLayerSnapshot {
  const OpenCrayChatRunContextBudgetLayerSnapshot({
    required this.id,
    required this.name,
    this.priorityClass,
    this.retentionPriority,
    this.estimatedTokensBefore,
    this.estimatedTokensAfter,
    this.finalState,
    this.omitted,
    this.reduced,
    this.appliedOperators = const <String>[],
  });

  final String id;
  final String name;
  final String? priorityClass;
  final int? retentionPriority;
  final int? estimatedTokensBefore;
  final int? estimatedTokensAfter;
  final String? finalState;
  final bool? omitted;
  final bool? reduced;
  final List<String> appliedOperators;

  factory OpenCrayChatRunContextBudgetLayerSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final id = (map['id'] as String?)?.trim() ?? '';
    final name = (map['name'] as String?)?.trim() ?? id;
    return OpenCrayChatRunContextBudgetLayerSnapshot(
      id: id,
      name: name.isEmpty ? id : name,
      priorityClass: map['priorityClass'] as String?,
      retentionPriority: map['retentionPriority'] as int?,
      estimatedTokensBefore: map['estimatedTokensBefore'] as int?,
      estimatedTokensAfter: map['estimatedTokensAfter'] as int?,
      finalState: map['finalState'] as String?,
      omitted: map['omitted'] as bool?,
      reduced: map['reduced'] as bool?,
      appliedOperators:
          (map['appliedOperators'] as List<Object?>?)
              ?.whereType<String>()
              .map((value) => value.trim())
              .where((value) => value.isNotEmpty)
              .toList(growable: false) ??
          const <String>[],
    );
  }
}

class OpenCrayChatRunBootstrapFileSnapshot {
  const OpenCrayChatRunBootstrapFileSnapshot({
    required this.name,
    required this.relativePath,
    this.sourceCharCount,
    this.injectedCharCount,
    this.truncated,
  });

  final String name;
  final String relativePath;
  final int? sourceCharCount;
  final int? injectedCharCount;
  final bool? truncated;

  factory OpenCrayChatRunBootstrapFileSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunBootstrapFileSnapshot(
      name: map['name'] as String? ?? '',
      relativePath: map['relativePath'] as String? ?? '',
      sourceCharCount: map['sourceCharCount'] as int?,
      injectedCharCount: map['injectedCharCount'] as int?,
      truncated: map['truncated'] as bool?,
    );
  }
}

class OpenCrayChatRunBootstrapSnapshot {
  const OpenCrayChatRunBootstrapSnapshot({
    this.mode,
    this.visibleFileCount,
    this.injectedFileCount,
    this.omittedFileCount,
    this.truncatedFileCount,
    this.files = const <OpenCrayChatRunBootstrapFileSnapshot>[],
  });

  final String? mode;
  final int? visibleFileCount;
  final int? injectedFileCount;
  final int? omittedFileCount;
  final int? truncatedFileCount;
  final List<OpenCrayChatRunBootstrapFileSnapshot> files;

  factory OpenCrayChatRunBootstrapSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawFiles = map['files'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunBootstrapSnapshot(
      mode: map['mode'] as String?,
      visibleFileCount: map['visibleFileCount'] as int?,
      injectedFileCount: map['injectedFileCount'] as int?,
      omittedFileCount: map['omittedFileCount'] as int?,
      truncatedFileCount: map['truncatedFileCount'] as int?,
      files: rawFiles
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunBootstrapFileSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatRunRemoteCompactionSnapshot {
  const OpenCrayChatRunRemoteCompactionSnapshot({
    this.requested,
    this.supported,
    this.used,
    this.triggerStage,
    this.fallbackReason,
    this.outputItemCount,
    this.compactionItemCount,
    this.encryptedContentCount,
  });

  final bool? requested;
  final bool? supported;
  final bool? used;
  final String? triggerStage;
  final String? fallbackReason;
  final int? outputItemCount;
  final int? compactionItemCount;
  final int? encryptedContentCount;

  factory OpenCrayChatRunRemoteCompactionSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunRemoteCompactionSnapshot(
      requested: map['requested'] as bool?,
      supported: map['supported'] as bool?,
      used: map['used'] as bool?,
      triggerStage: map['triggerStage'] as String?,
      fallbackReason: map['fallbackReason'] as String?,
      outputItemCount: map['outputItemCount'] as int?,
      compactionItemCount: map['compactionItemCount'] as int?,
      encryptedContentCount: map['encryptedContentCount'] as int?,
    );
  }
}

class OpenCrayChatRunDurableCompactionSnapshot {
  const OpenCrayChatRunDurableCompactionSnapshot({
    this.compactedThisRun,
    this.triggerStage,
    this.executionMode,
    this.contextWindowTokens,
    this.previousContextWindowTokens,
    this.autoCompactTokenLimit,
    this.estimatedReplayTokens,
    this.tokenThresholdTriggered,
    this.smallerWindowModelSwitchDetected,
    this.sourceTranscriptMessageCount,
    this.retainedTranscriptMessageCount,
    this.latestCompactedMessageCount,
    this.includedSummaryCount,
    this.omittedSummaryCount,
    this.totalSummaryCount,
    this.totalCompactedMessageCount,
    this.latestCompactedAtEpochMs,
    this.remoteCompaction,
  });

  final bool? compactedThisRun;
  final String? triggerStage;
  final String? executionMode;
  final int? contextWindowTokens;
  final int? previousContextWindowTokens;
  final int? autoCompactTokenLimit;
  final int? estimatedReplayTokens;
  final bool? tokenThresholdTriggered;
  final bool? smallerWindowModelSwitchDetected;
  final int? sourceTranscriptMessageCount;
  final int? retainedTranscriptMessageCount;
  final int? latestCompactedMessageCount;
  final int? includedSummaryCount;
  final int? omittedSummaryCount;
  final int? totalSummaryCount;
  final int? totalCompactedMessageCount;
  final int? latestCompactedAtEpochMs;
  final OpenCrayChatRunRemoteCompactionSnapshot? remoteCompaction;

  factory OpenCrayChatRunDurableCompactionSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawRemoteCompaction = map['remoteCompaction'];
    return OpenCrayChatRunDurableCompactionSnapshot(
      compactedThisRun: map['compactedThisRun'] as bool?,
      triggerStage: map['triggerStage'] as String?,
      executionMode: map['executionMode'] as String?,
      contextWindowTokens: map['contextWindowTokens'] as int?,
      previousContextWindowTokens: map['previousContextWindowTokens'] as int?,
      autoCompactTokenLimit: map['autoCompactTokenLimit'] as int?,
      estimatedReplayTokens: map['estimatedReplayTokens'] as int?,
      tokenThresholdTriggered: map['tokenThresholdTriggered'] as bool?,
      smallerWindowModelSwitchDetected:
          map['smallerWindowModelSwitchDetected'] as bool?,
      sourceTranscriptMessageCount: map['sourceTranscriptMessageCount'] as int?,
      retainedTranscriptMessageCount:
          map['retainedTranscriptMessageCount'] as int?,
      latestCompactedMessageCount: map['latestCompactedMessageCount'] as int?,
      includedSummaryCount: map['includedSummaryCount'] as int?,
      omittedSummaryCount: map['omittedSummaryCount'] as int?,
      totalSummaryCount: map['totalSummaryCount'] as int?,
      totalCompactedMessageCount: map['totalCompactedMessageCount'] as int?,
      latestCompactedAtEpochMs: map['latestCompactedAtEpochMs'] as int?,
      remoteCompaction: rawRemoteCompaction is Map<Object?, Object?>
          ? OpenCrayChatRunRemoteCompactionSnapshot.fromMap(rawRemoteCompaction)
          : null,
    );
  }
}

class OpenCrayChatRunVisibleSkillSnapshot {
  const OpenCrayChatRunVisibleSkillSnapshot({
    required this.name,
    required this.relativePath,
    this.invocationControl,
    this.userInvocable,
    this.executionContext,
  });

  final String name;
  final String relativePath;
  final String? invocationControl;
  final bool? userInvocable;
  final String? executionContext;

  factory OpenCrayChatRunVisibleSkillSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunVisibleSkillSnapshot(
      name: map['name'] as String? ?? '',
      relativePath: map['relativePath'] as String? ?? '',
      invocationControl: map['invocationControl'] as String?,
      userInvocable: map['userInvocable'] as bool?,
      executionContext: map['executionContext'] as String?,
    );
  }
}

class OpenCrayChatRunSkillInventorySnapshot {
  const OpenCrayChatRunSkillInventorySnapshot({
    this.visibleSkillCount,
    this.injectedSkillCount,
    this.omittedSkillCount,
    this.implicitSkillCount,
    this.invalidSkillCount,
    this.omittedTraceSkillCount,
    this.skills = const <OpenCrayChatRunVisibleSkillSnapshot>[],
  });

  final int? visibleSkillCount;
  final int? injectedSkillCount;
  final int? omittedSkillCount;
  final int? implicitSkillCount;
  final int? invalidSkillCount;
  final int? omittedTraceSkillCount;
  final List<OpenCrayChatRunVisibleSkillSnapshot> skills;

  factory OpenCrayChatRunSkillInventorySnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawSkills = map['skills'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunSkillInventorySnapshot(
      visibleSkillCount: map['visibleSkillCount'] as int?,
      injectedSkillCount: map['injectedSkillCount'] as int?,
      omittedSkillCount: map['omittedSkillCount'] as int?,
      implicitSkillCount: map['implicitSkillCount'] as int?,
      invalidSkillCount: map['invalidSkillCount'] as int?,
      omittedTraceSkillCount: map['omittedTraceSkillCount'] as int?,
      skills: rawSkills
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunVisibleSkillSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatRunActiveSkillSnapshot {
  const OpenCrayChatRunActiveSkillSnapshot({
    this.name,
    this.relativePath,
    this.invocationControl,
    this.executionContext,
    this.activationSource,
    this.pinned,
    this.toolRestrictionEnabled,
    this.truncated,
    this.allowedToolKeys = const <String>[],
  });

  final String? name;
  final String? relativePath;
  final String? invocationControl;
  final String? executionContext;
  final String? activationSource;
  final bool? pinned;
  final bool? toolRestrictionEnabled;
  final bool? truncated;
  final List<String> allowedToolKeys;

  factory OpenCrayChatRunActiveSkillSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawAllowedToolKeys =
        map['allowedToolKeys'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunActiveSkillSnapshot(
      name: map['name'] as String?,
      relativePath: map['relativePath'] as String?,
      invocationControl: map['invocationControl'] as String?,
      executionContext: map['executionContext'] as String?,
      activationSource: map['activationSource'] as String?,
      pinned: map['pinned'] as bool?,
      toolRestrictionEnabled: map['toolRestrictionEnabled'] as bool?,
      truncated: map['truncated'] as bool?,
      allowedToolKeys: rawAllowedToolKeys
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
    );
  }
}
