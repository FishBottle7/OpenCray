class OpenCrayChatRunMemorySelectedSnapshot {
  const OpenCrayChatRunMemorySelectedSnapshot({
    required this.id,
    this.score,
    this.matchedTerms = const <String>[],
  });

  final String id;
  final int? score;
  final List<String> matchedTerms;

  factory OpenCrayChatRunMemorySelectedSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawMatchedTerms =
        map['matchedTerms'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunMemorySelectedSnapshot(
      id: map['id'] as String? ?? '',
      score: map['score'] as int?,
      matchedTerms: rawMatchedTerms
          .whereType<String>()
          .map((term) => term.trim())
          .where((term) => term.isNotEmpty)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatRunMemoryOmittedSnapshot {
  const OpenCrayChatRunMemoryOmittedSnapshot({
    required this.id,
    required this.reason,
  });

  final String id;
  final String reason;

  factory OpenCrayChatRunMemoryOmittedSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunMemoryOmittedSnapshot(
      id: map['id'] as String? ?? '',
      reason: map['reason'] as String? ?? '',
    );
  }
}

class OpenCrayChatRunMemoryTraceSnapshot {
  const OpenCrayChatRunMemoryTraceSnapshot({
    this.matchedRecordCount,
    this.injectedRecordCount,
    this.omittedRecordCount,
    this.queryTerms = const <String>[],
    this.selected = const <OpenCrayChatRunMemorySelectedSnapshot>[],
    this.omitted = const <OpenCrayChatRunMemoryOmittedSnapshot>[],
    this.filteredCounts = const <String, int>{},
  });

  final int? matchedRecordCount;
  final int? injectedRecordCount;
  final int? omittedRecordCount;
  final List<String> queryTerms;
  final List<OpenCrayChatRunMemorySelectedSnapshot> selected;
  final List<OpenCrayChatRunMemoryOmittedSnapshot> omitted;
  final Map<String, int> filteredCounts;

  factory OpenCrayChatRunMemoryTraceSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawQueryTerms =
        map['queryTerms'] as List<Object?>? ?? const <Object?>[];
    final rawSelected = map['selected'] as List<Object?>? ?? const <Object?>[];
    final rawOmitted = map['omitted'] as List<Object?>? ?? const <Object?>[];
    final rawFilteredCounts =
        map['filteredCounts'] as Map<Object?, Object?>? ??
        const <Object?, Object?>{};
    return OpenCrayChatRunMemoryTraceSnapshot(
      matchedRecordCount: map['matchedRecordCount'] as int?,
      injectedRecordCount: map['injectedRecordCount'] as int?,
      omittedRecordCount: map['omittedRecordCount'] as int?,
      queryTerms: rawQueryTerms
          .whereType<String>()
          .map((term) => term.trim())
          .where((term) => term.isNotEmpty)
          .toList(growable: false),
      selected: rawSelected
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunMemorySelectedSnapshot.fromMap)
          .toList(growable: false),
      omitted: rawOmitted
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunMemoryOmittedSnapshot.fromMap)
          .toList(growable: false),
      filteredCounts: rawFilteredCounts.map<String, int>((key, value) {
        final parsedCount = switch (value) {
          int count => count,
          num count => count.toInt(),
          _ => int.tryParse(value?.toString() ?? '') ?? 0,
        };
        return MapEntry(key?.toString() ?? '', parsedCount);
      })..removeWhere((key, value) => key.trim().isEmpty),
    );
  }
}

class OpenCrayChatRunMemoryFlushSnapshot {
  const OpenCrayChatRunMemoryFlushSnapshot({
    this.outcome,
    this.triggerStage,
    this.executionMode,
    this.contextWindowTokens,
    this.previousContextWindowTokens,
    this.autoCompactTokenLimit,
    this.estimatedReplayTokens,
    this.tokenThresholdTriggered,
    this.smallerWindowModelSwitchDetected,
    this.omittedMessageCount,
    this.omittedCharCount,
    this.signature,
    this.candidateCount,
    this.writtenRecordCount,
    this.writtenKinds = const <String>[],
    this.writtenRecordIds = const <String>[],
  });

  final String? outcome;
  final String? triggerStage;
  final String? executionMode;
  final int? contextWindowTokens;
  final int? previousContextWindowTokens;
  final int? autoCompactTokenLimit;
  final int? estimatedReplayTokens;
  final bool? tokenThresholdTriggered;
  final bool? smallerWindowModelSwitchDetected;
  final int? omittedMessageCount;
  final int? omittedCharCount;
  final String? signature;
  final int? candidateCount;
  final int? writtenRecordCount;
  final List<String> writtenKinds;
  final List<String> writtenRecordIds;

  factory OpenCrayChatRunMemoryFlushSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawWrittenKinds =
        map['writtenKinds'] as List<Object?>? ?? const <Object?>[];
    final rawWrittenRecordIds =
        map['writtenRecordIds'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunMemoryFlushSnapshot(
      outcome: map['outcome'] as String?,
      triggerStage: map['triggerStage'] as String?,
      executionMode: map['executionMode'] as String?,
      contextWindowTokens: map['contextWindowTokens'] as int?,
      previousContextWindowTokens: map['previousContextWindowTokens'] as int?,
      autoCompactTokenLimit: map['autoCompactTokenLimit'] as int?,
      estimatedReplayTokens: map['estimatedReplayTokens'] as int?,
      tokenThresholdTriggered: map['tokenThresholdTriggered'] as bool?,
      smallerWindowModelSwitchDetected:
          map['smallerWindowModelSwitchDetected'] as bool?,
      omittedMessageCount: map['omittedMessageCount'] as int?,
      omittedCharCount: map['omittedCharCount'] as int?,
      signature: map['signature'] as String?,
      candidateCount: map['candidateCount'] as int?,
      writtenRecordCount: map['writtenRecordCount'] as int?,
      writtenKinds: rawWrittenKinds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      writtenRecordIds: rawWrittenRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatRunStickyMemorySnapshot {
  const OpenCrayChatRunStickyMemorySnapshot({
    this.injectedRecordCount,
    this.omittedRecordCount,
    this.recordIds = const <String>[],
  });

  final int? injectedRecordCount;
  final int? omittedRecordCount;
  final List<String> recordIds;

  factory OpenCrayChatRunStickyMemorySnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawRecordIds =
        map['recordIds'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunStickyMemorySnapshot(
      injectedRecordCount: map['injectedRecordCount'] as int?,
      omittedRecordCount: map['omittedRecordCount'] as int?,
      recordIds: rawRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
    );
  }
}
