class OpenCrayChatSummarySnapshot {
  const OpenCrayChatSummarySnapshot({
    required this.title,
    required this.badge,
    required this.body,
  });

  final String title;
  final String badge;
  final String body;

  factory OpenCrayChatSummarySnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatSummarySnapshot(
      title: map['title'] as String? ?? '',
      badge: map['badge'] as String? ?? '',
      body: map['body'] as String? ?? '',
    );
  }
}

class OpenCrayChatMessageSnapshot {
  const OpenCrayChatMessageSnapshot({
    this.messageId = '',
    required this.kind,
    required this.text,
    this.meta = '',
    this.createdAtEpochMs,
    this.isEphemeral = false,
    this.attachments = const <OpenCrayChatAttachmentSnapshot>[],
  });

  final String messageId;
  final String kind;
  final String text;
  final String meta;
  final int? createdAtEpochMs;
  final bool isEphemeral;
  final List<OpenCrayChatAttachmentSnapshot> attachments;

  factory OpenCrayChatMessageSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawAttachments =
        map['attachments'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatMessageSnapshot(
      messageId: map['messageId'] as String? ?? '',
      kind: map['kind'] as String? ?? 'inbound',
      text: map['text'] as String? ?? '',
      meta: map['meta'] as String? ?? '',
      createdAtEpochMs: map['createdAtEpochMs'] as int?,
      isEphemeral: map['isEphemeral'] as bool? ?? false,
      attachments: rawAttachments
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatAttachmentSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatAttachmentSnapshot {
  const OpenCrayChatAttachmentSnapshot({
    this.attachmentId = '',
    this.kind = 'file',
    this.displayName = '',
    this.localPath = '',
    this.mimeType,
    this.sizeBytes,
    this.widthPx,
    this.heightPx,
    this.durationMs,
    this.waveformBars = const <int>[],
    this.transcriptText,
    this.contentSha256,
  });

  final String attachmentId;
  final String kind;
  final String displayName;
  final String localPath;
  final String? mimeType;
  final int? sizeBytes;
  final int? widthPx;
  final int? heightPx;
  final int? durationMs;
  final List<int> waveformBars;
  final String? transcriptText;
  final String? contentSha256;

  factory OpenCrayChatAttachmentSnapshot.fromMap(Map<Object?, Object?> map) {
    int? parseInt(Object? value) => switch (value) {
      int intValue => intValue,
      num numValue => numValue.toInt(),
      _ => int.tryParse(value?.toString() ?? ''),
    };

    return OpenCrayChatAttachmentSnapshot(
      attachmentId: map['attachmentId'] as String? ?? '',
      kind: map['kind'] as String? ?? 'file',
      displayName: map['displayName'] as String? ?? '',
      localPath: map['localPath'] as String? ?? '',
      mimeType: map['mimeType'] as String?,
      sizeBytes: parseInt(map['sizeBytes']),
      widthPx: parseInt(map['widthPx']),
      heightPx: parseInt(map['heightPx']),
      durationMs: parseInt(map['durationMs']),
      waveformBars: (map['waveformBars'] as List<Object?>? ?? const <Object?>[])
          .map(parseInt)
          .whereType<int>()
          .toList(growable: false),
      transcriptText: map['transcriptText'] as String?,
      contentSha256: map['contentSha256'] as String?,
    );
  }
}

class OpenCrayChatSessionItemSnapshot {
  const OpenCrayChatSessionItemSnapshot({
    required this.sessionId,
    required this.title,
    required this.preview,
    required this.meta,
    required this.isSelected,
    this.lastMessageAtEpochMs,
    this.unreadCount = 0,
  });

  final String sessionId;
  final String title;
  final String preview;
  final String meta;
  final bool isSelected;
  final int? lastMessageAtEpochMs;
  final int unreadCount;

  factory OpenCrayChatSessionItemSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatSessionItemSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      title: map['title'] as String? ?? '',
      preview: map['preview'] as String? ?? '',
      meta: map['meta'] as String? ?? '',
      isSelected: map['isSelected'] as bool? ?? false,
      lastMessageAtEpochMs: map['lastMessageAtEpochMs'] as int?,
      unreadCount: map['unreadCount'] as int? ?? 0,
    );
  }
}

class OpenCrayChatDrawerSnapshot {
  const OpenCrayChatDrawerSnapshot({
    required this.eyebrow,
    required this.title,
    required this.ctaLabel,
    required this.sessions,
  });

  final String eyebrow;
  final String title;
  final String ctaLabel;
  final List<OpenCrayChatSessionItemSnapshot> sessions;

  factory OpenCrayChatDrawerSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawSessions = map['sessions'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatDrawerSnapshot(
      eyebrow: map['eyebrow'] as String? ?? '',
      title: map['title'] as String? ?? '',
      ctaLabel: map['ctaLabel'] as String? ?? '',
      sessions: rawSessions
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatSessionItemSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatPendingApprovalSnapshot {
  const OpenCrayChatPendingApprovalSnapshot({
    required this.runId,
    required this.taskId,
    required this.title,
    required this.body,
    required this.approveLabel,
    required this.rejectLabel,
    required this.isHighRisk,
    this.supportsSessionApproval = false,
    this.approveForSessionLabel = '',
    this.toolName = '',
    this.requestSummary = '',
    this.primaryDetail = '',
    this.pathDetails = const <String>[],
    this.workingDirectory = '',
    this.reason = '',
    this.message = '',
  });

  final String runId;
  final String taskId;
  final String title;
  final String body;
  final String approveLabel;
  final String rejectLabel;
  final bool isHighRisk;
  final bool supportsSessionApproval;
  final String approveForSessionLabel;
  final String toolName;
  final String requestSummary;
  final String primaryDetail;
  final List<String> pathDetails;
  final String workingDirectory;
  final String reason;
  final String message;

  String get approvalId => runId.isNotEmpty ? runId : taskId;

  factory OpenCrayChatPendingApprovalSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatPendingApprovalSnapshot(
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      title: map['title'] as String? ?? '',
      body: map['body'] as String? ?? '',
      approveLabel: map['approveLabel'] as String? ?? 'Approve',
      rejectLabel: map['rejectLabel'] as String? ?? 'Reject',
      isHighRisk: map['isHighRisk'] as bool? ?? false,
      supportsSessionApproval: map['supportsSessionApproval'] as bool? ?? false,
      approveForSessionLabel: map['approveForSessionLabel'] as String? ?? '',
      toolName: map['toolName'] as String? ?? '',
      requestSummary: map['requestSummary'] as String? ?? '',
      primaryDetail: map['primaryDetail'] as String? ?? '',
      pathDetails: (map['pathDetails'] as List<Object?>? ?? const <Object?>[])
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      workingDirectory: map['workingDirectory'] as String? ?? '',
      reason: map['reason'] as String? ?? '',
      message: map['message'] as String? ?? '',
    );
  }
}

class OpenCrayChatRuntimeEventSnapshot {
  const OpenCrayChatRuntimeEventSnapshot({
    required this.kind,
    required this.runId,
    required this.taskId,
    required this.emittedAtEpochMs,
    this.executionId,
    this.executionOrdinal,
    this.executionKind,
    this.entryId,
    this.checkpoint,
    this.turn,
    this.phase,
    this.status,
    this.errorCode,
    this.errorMessage,
    this.responseFormat,
    this.isFinal,
    this.text,
    this.stage,
    this.toolName,
    this.isHighRisk = false,
    this.label,
    this.childRunId,
    this.childTaskId,
    this.subagentType,
    this.contextMode,
    this.depth,
    this.executionState,
    this.continuationKind,
    this.toolReason,
    this.argumentsJson,
    this.toolStatus,
    this.content,
    this.contentPreview,
    this.resultMetadata = const <String, String>{},
    this.operation,
    this.query,
    this.queryTerms = const <String>[],
    this.resultCount,
    this.corpusFileCount,
    this.recordIds = const <String>[],
    this.writtenRecordIds = const <String>[],
    this.writtenKinds = const <String>[],
    this.resolvedRecordIds = const <String>[],
    this.suppressedRecordIds = const <String>[],
    this.reaffirmedRecordIds = const <String>[],
    this.expiredRecordIds = const <String>[],
    this.paths = const <String>[],
    this.lineRanges = const <String>[],
    this.path,
    this.fromLine,
    this.returnedLineCount,
    this.totalLineCount,
  });

  final String kind;
  final String runId;
  final String taskId;
  final int emittedAtEpochMs;
  final String? executionId;
  final int? executionOrdinal;
  final String? executionKind;
  final String? entryId;
  final String? checkpoint;
  final int? turn;
  final String? phase;
  final String? status;
  final String? errorCode;
  final String? errorMessage;
  final String? responseFormat;
  final bool? isFinal;
  final String? text;
  final String? stage;
  final String? toolName;
  final bool isHighRisk;
  final String? label;
  final String? childRunId;
  final String? childTaskId;
  final String? subagentType;
  final String? contextMode;
  final int? depth;
  final String? executionState;
  final String? continuationKind;
  final String? toolReason;
  final String? argumentsJson;
  final String? toolStatus;
  final String? content;
  final String? contentPreview;
  final Map<String, String> resultMetadata;
  final String? operation;
  final String? query;
  final List<String> queryTerms;
  final int? resultCount;
  final int? corpusFileCount;
  final List<String> recordIds;
  final List<String> writtenRecordIds;
  final List<String> writtenKinds;
  final List<String> resolvedRecordIds;
  final List<String> suppressedRecordIds;
  final List<String> reaffirmedRecordIds;
  final List<String> expiredRecordIds;
  final List<String> paths;
  final List<String> lineRanges;
  final String? path;
  final int? fromLine;
  final int? returnedLineCount;
  final int? totalLineCount;

  factory OpenCrayChatRuntimeEventSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawQueryTerms =
        map['queryTerms'] as List<Object?>? ?? const <Object?>[];
    final rawWrittenRecordIds =
        map['writtenRecordIds'] as List<Object?>? ?? const <Object?>[];
    final rawWrittenKinds =
        map['writtenKinds'] as List<Object?>? ?? const <Object?>[];
    final rawResolvedRecordIds =
        map['resolvedRecordIds'] as List<Object?>? ?? const <Object?>[];
    final rawSuppressedRecordIds =
        map['suppressedRecordIds'] as List<Object?>? ?? const <Object?>[];
    final rawReaffirmedRecordIds =
        map['reaffirmedRecordIds'] as List<Object?>? ?? const <Object?>[];
    final rawExpiredRecordIds =
        map['expiredRecordIds'] as List<Object?>? ?? const <Object?>[];
    final rawRecordIds =
        map['recordIds'] as List<Object?>? ?? const <Object?>[];
    final rawPaths = map['paths'] as List<Object?>? ?? const <Object?>[];
    final rawLineRanges =
        map['lineRanges'] as List<Object?>? ?? const <Object?>[];
    final rawResultMetadata =
        map['resultMetadata'] as Map<Object?, Object?>? ??
        const <Object?, Object?>{};
    return OpenCrayChatRuntimeEventSnapshot(
      kind: map['kind'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      emittedAtEpochMs: map['emittedAtEpochMs'] as int? ?? 0,
      executionId: map['executionId'] as String?,
      executionOrdinal: map['executionOrdinal'] as int?,
      executionKind: map['executionKind'] as String?,
      entryId: map['entryId'] as String?,
      checkpoint: map['checkpoint'] as String?,
      turn: map['turn'] as int?,
      phase: map['phase'] as String?,
      status: map['status'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      responseFormat: map['responseFormat'] as String?,
      isFinal: map['isFinal'] as bool?,
      text: map['text'] as String?,
      stage: map['stage'] as String?,
      toolName: map['toolName'] as String?,
      isHighRisk: map['isHighRisk'] as bool? ?? false,
      label: map['label'] as String?,
      childRunId: map['childRunId'] as String?,
      childTaskId: map['childTaskId'] as String?,
      subagentType: map['subagentType'] as String?,
      contextMode: map['contextMode'] as String?,
      depth: map['depth'] as int?,
      executionState: map['executionState'] as String?,
      continuationKind: map['continuationKind'] as String?,
      toolReason: map['toolReason'] as String?,
      argumentsJson: map['argumentsJson'] as String?,
      toolStatus: map['toolStatus'] as String?,
      content: map['content'] as String?,
      contentPreview: map['contentPreview'] as String?,
      resultMetadata:
          rawResultMetadata.map(
            (key, value) => MapEntry(key.toString(), value?.toString() ?? ''),
          )..removeWhere(
            (key, value) => key.trim().isEmpty || value.trim().isEmpty,
          ),
      operation: map['operation'] as String?,
      query: map['query'] as String?,
      queryTerms: rawQueryTerms
          .whereType<String>()
          .map((term) => term.trim())
          .where((term) => term.isNotEmpty)
          .toList(growable: false),
      resultCount: map['resultCount'] as int?,
      corpusFileCount: map['corpusFileCount'] as int?,
      recordIds: rawRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      writtenRecordIds: rawWrittenRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      writtenKinds: rawWrittenKinds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      resolvedRecordIds: rawResolvedRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      suppressedRecordIds: rawSuppressedRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      reaffirmedRecordIds: rawReaffirmedRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      expiredRecordIds: rawExpiredRecordIds
          .whereType<String>()
          .map((value) => value.trim())
          .where((value) => value.isNotEmpty)
          .toList(growable: false),
      paths: rawPaths
          .whereType<String>()
          .map((path) => path.trim())
          .where((path) => path.isNotEmpty)
          .toList(growable: false),
      lineRanges: rawLineRanges
          .whereType<String>()
          .map((lineRange) => lineRange.trim())
          .where((lineRange) => lineRange.isNotEmpty)
          .toList(growable: false),
      path: map['path'] as String?,
      fromLine: map['fromLine'] as int?,
      returnedLineCount: map['returnedLineCount'] as int?,
      totalLineCount: map['totalLineCount'] as int?,
    );
  }
}

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
    this.contextWindowTokens,
    this.autoCompactTokenLimit,
    this.estimatedReplayTokens,
    this.tokenThresholdTriggered,
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
  final int? contextWindowTokens;
  final int? autoCompactTokenLimit;
  final int? estimatedReplayTokens;
  final bool? tokenThresholdTriggered;
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
      contextWindowTokens: map['contextWindowTokens'] as int?,
      autoCompactTokenLimit: map['autoCompactTokenLimit'] as int?,
      estimatedReplayTokens: map['estimatedReplayTokens'] as int?,
      tokenThresholdTriggered: map['tokenThresholdTriggered'] as bool?,
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

class OpenCrayChatRunDurableCompactionSnapshot {
  const OpenCrayChatRunDurableCompactionSnapshot({
    this.compactedThisRun,
    this.triggerStage,
    this.contextWindowTokens,
    this.autoCompactTokenLimit,
    this.estimatedReplayTokens,
    this.tokenThresholdTriggered,
    this.sourceTranscriptMessageCount,
    this.retainedTranscriptMessageCount,
    this.latestCompactedMessageCount,
    this.includedSummaryCount,
    this.omittedSummaryCount,
    this.totalSummaryCount,
    this.totalCompactedMessageCount,
    this.latestCompactedAtEpochMs,
  });

  final bool? compactedThisRun;
  final String? triggerStage;
  final int? contextWindowTokens;
  final int? autoCompactTokenLimit;
  final int? estimatedReplayTokens;
  final bool? tokenThresholdTriggered;
  final int? sourceTranscriptMessageCount;
  final int? retainedTranscriptMessageCount;
  final int? latestCompactedMessageCount;
  final int? includedSummaryCount;
  final int? omittedSummaryCount;
  final int? totalSummaryCount;
  final int? totalCompactedMessageCount;
  final int? latestCompactedAtEpochMs;

  factory OpenCrayChatRunDurableCompactionSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunDurableCompactionSnapshot(
      compactedThisRun: map['compactedThisRun'] as bool?,
      triggerStage: map['triggerStage'] as String?,
      contextWindowTokens: map['contextWindowTokens'] as int?,
      autoCompactTokenLimit: map['autoCompactTokenLimit'] as int?,
      estimatedReplayTokens: map['estimatedReplayTokens'] as int?,
      tokenThresholdTriggered: map['tokenThresholdTriggered'] as bool?,
      sourceTranscriptMessageCount: map['sourceTranscriptMessageCount'] as int?,
      retainedTranscriptMessageCount:
          map['retainedTranscriptMessageCount'] as int?,
      latestCompactedMessageCount: map['latestCompactedMessageCount'] as int?,
      includedSummaryCount: map['includedSummaryCount'] as int?,
      omittedSummaryCount: map['omittedSummaryCount'] as int?,
      totalSummaryCount: map['totalSummaryCount'] as int?,
      totalCompactedMessageCount: map['totalCompactedMessageCount'] as int?,
      latestCompactedAtEpochMs: map['latestCompactedAtEpochMs'] as int?,
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

class OpenCrayHostLifecycleSnapshot {
  const OpenCrayHostLifecycleSnapshot({
    this.processStartId,
    this.processStartedAtEpochMs,
    this.hostInstanceId,
    this.runtimeOwnerId,
    this.hostCreatedAtEpochMs,
  });

  final String? processStartId;
  final int? processStartedAtEpochMs;
  final String? hostInstanceId;
  final String? runtimeOwnerId;
  final int? hostCreatedAtEpochMs;

  factory OpenCrayHostLifecycleSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayHostLifecycleSnapshot(
      processStartId: map['processStartId'] as String?,
      processStartedAtEpochMs: map['processStartedAtEpochMs'] as int?,
      hostInstanceId: map['hostInstanceId'] as String?,
      runtimeOwnerId: map['runtimeOwnerId'] as String?,
      hostCreatedAtEpochMs: map['hostCreatedAtEpochMs'] as int?,
    );
  }
}

class OpenCrayChatRunDiagnosticsSnapshot {
  const OpenCrayChatRunDiagnosticsSnapshot({
    this.processStartId,
    this.hostInstanceId,
    this.runtimeOwnerId,
    this.submissionSource,
    this.recoveryReason,
    this.queueRestoreEpochMs,
    this.previousLifecycleState,
    this.restoredFromDurableStore,
  });

  final String? processStartId;
  final String? hostInstanceId;
  final String? runtimeOwnerId;
  final String? submissionSource;
  final String? recoveryReason;
  final int? queueRestoreEpochMs;
  final String? previousLifecycleState;
  final bool? restoredFromDurableStore;

  factory OpenCrayChatRunDiagnosticsSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunDiagnosticsSnapshot(
      processStartId: map['processStartId'] as String?,
      hostInstanceId: map['hostInstanceId'] as String?,
      runtimeOwnerId: map['runtimeOwnerId'] as String?,
      submissionSource: map['submissionSource'] as String?,
      recoveryReason: map['recoveryReason'] as String?,
      queueRestoreEpochMs: map['queueRestoreEpochMs'] as int?,
      previousLifecycleState: map['previousLifecycleState'] as String?,
      restoredFromDurableStore: map['restoredFromDurableStore'] as bool?,
    );
  }
}

class OpenCrayChatRunRecoveryPlanSnapshot {
  const OpenCrayChatRunRecoveryPlanSnapshot({
    this.action,
    this.reasonCode,
    this.summary,
    this.safeToAutoResume,
    this.requiresUserAction,
    this.checkpointKind,
    this.approvalState,
    this.journalTailKind,
  });

  final String? action;
  final String? reasonCode;
  final String? summary;
  final bool? safeToAutoResume;
  final bool? requiresUserAction;
  final String? checkpointKind;
  final String? approvalState;
  final String? journalTailKind;

  factory OpenCrayChatRunRecoveryPlanSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunRecoveryPlanSnapshot(
      action: map['action'] as String?,
      reasonCode: map['reasonCode'] as String?,
      summary: map['summary'] as String?,
      safeToAutoResume: map['safeToAutoResume'] as bool?,
      requiresUserAction: map['requiresUserAction'] as bool?,
      checkpointKind: map['checkpointKind'] as String?,
      approvalState: map['approvalState'] as String?,
      journalTailKind: map['journalTailKind'] as String?,
    );
  }
}

class OpenCrayChatRunLlmDiagnosticsSnapshot {
  const OpenCrayChatRunLlmDiagnosticsSnapshot({
    this.nativeToolCallRequested,
    this.providerResponseShape,
    this.nativeToolCallObserved,
    this.parsedToolCallObserved,
    this.fallbackParserAttempted,
    this.fallbackParserSucceeded,
    this.responsesContinuationRecoveryCount,
    this.responsesContinuationRecoveryLastReason,
    this.localContinuationUsedCount,
    this.localContinuationFallbackCount,
    this.localContinuationLastMode,
    this.localContinuationLastReason,
    this.toolCallEventEmitted,
    this.toolResultEventEmitted,
    this.contextCacheBreakReason,
    this.lastSuccessfulToolName,
  });

  final bool? nativeToolCallRequested;
  final String? providerResponseShape;
  final bool? nativeToolCallObserved;
  final bool? parsedToolCallObserved;
  final bool? fallbackParserAttempted;
  final bool? fallbackParserSucceeded;
  final int? responsesContinuationRecoveryCount;
  final String? responsesContinuationRecoveryLastReason;
  final int? localContinuationUsedCount;
  final int? localContinuationFallbackCount;
  final String? localContinuationLastMode;
  final String? localContinuationLastReason;
  final bool? toolCallEventEmitted;
  final bool? toolResultEventEmitted;
  final String? contextCacheBreakReason;
  final String? lastSuccessfulToolName;

  factory OpenCrayChatRunLlmDiagnosticsSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRunLlmDiagnosticsSnapshot(
      nativeToolCallRequested: map['nativeToolCallRequested'] as bool?,
      providerResponseShape: map['providerResponseShape'] as String?,
      nativeToolCallObserved: map['nativeToolCallObserved'] as bool?,
      parsedToolCallObserved: map['parsedToolCallObserved'] as bool?,
      fallbackParserAttempted: map['fallbackParserAttempted'] as bool?,
      fallbackParserSucceeded: map['fallbackParserSucceeded'] as bool?,
      responsesContinuationRecoveryCount:
          map['responsesContinuationRecoveryCount'] as int?,
      responsesContinuationRecoveryLastReason:
          map['responsesContinuationRecoveryLastReason'] as String?,
      localContinuationUsedCount: map['localContinuationUsedCount'] as int?,
      localContinuationFallbackCount:
          map['localContinuationFallbackCount'] as int?,
      localContinuationLastMode: map['localContinuationLastMode'] as String?,
      localContinuationLastReason:
          map['localContinuationLastReason'] as String?,
      toolCallEventEmitted: map['toolCallEventEmitted'] as bool?,
      toolResultEventEmitted: map['toolResultEventEmitted'] as bool?,
      contextCacheBreakReason: map['contextCacheBreakReason'] as String?,
      lastSuccessfulToolName: map['lastSuccessfulToolName'] as String?,
    );
  }
}

class OpenCrayChatRunSnapshot {
  const OpenCrayChatRunSnapshot({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
    required this.updatedAtEpochMs,
    required this.attempt,
    required this.isTerminal,
    this.executionOrdinal = 0,
    this.executionId,
    this.executionKind,
    this.pendingExecutionKind,
    this.lifecycleState,
    this.taskState,
    this.executionStatus,
    this.errorCode,
    this.errorMessage,
    this.responseFormat,
    this.pendingMessageId,
    this.finalAttachments = const <OpenCrayChatAttachmentSnapshot>[],
    this.managedProcessIds = const <String>[],
    this.managedProcesses = const <OpenCrayChatManagedProcessSnapshot>[],
    this.runningManagedProcessCount = 0,
    this.hasLiveManagedProcesses = false,
    this.lastEvent,
    this.llmDiagnostics,
    this.liveContext,
    this.contextBudget,
    this.memoryTrace,
    this.stickyMemory,
    this.memoryFlush,
    this.bootstrap,
    this.durableCompaction,
    this.skillInventory,
    this.activeSkill,
    this.diagnostics,
    this.recoveryPlan,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;
  final int updatedAtEpochMs;
  final String? lifecycleState;
  final String? taskState;
  final int attempt;
  final int executionOrdinal;
  final String? executionId;
  final String? executionKind;
  final String? pendingExecutionKind;
  final String? executionStatus;
  final String? errorCode;
  final String? errorMessage;
  final String? responseFormat;
  final String? pendingMessageId;
  final List<OpenCrayChatAttachmentSnapshot> finalAttachments;
  final List<String> managedProcessIds;
  final List<OpenCrayChatManagedProcessSnapshot> managedProcesses;
  final int runningManagedProcessCount;
  final bool hasLiveManagedProcesses;
  final bool isTerminal;
  final OpenCrayChatRuntimeEventSnapshot? lastEvent;
  final OpenCrayChatRunLlmDiagnosticsSnapshot? llmDiagnostics;
  final OpenCrayChatRunLiveContextSnapshot? liveContext;
  final OpenCrayChatRunContextBudgetSnapshot? contextBudget;
  final OpenCrayChatRunMemoryTraceSnapshot? memoryTrace;
  final OpenCrayChatRunStickyMemorySnapshot? stickyMemory;
  final OpenCrayChatRunMemoryFlushSnapshot? memoryFlush;
  final OpenCrayChatRunBootstrapSnapshot? bootstrap;
  final OpenCrayChatRunDurableCompactionSnapshot? durableCompaction;
  final OpenCrayChatRunSkillInventorySnapshot? skillInventory;
  final OpenCrayChatRunActiveSkillSnapshot? activeSkill;
  final OpenCrayChatRunDiagnosticsSnapshot? diagnostics;
  final OpenCrayChatRunRecoveryPlanSnapshot? recoveryPlan;

  factory OpenCrayChatRunSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawLastEvent = map['lastEvent'];
    final rawLlmDiagnostics = map['llmDiagnostics'];
    final rawLiveContext = map['liveContext'];
    final rawContextBudget = map['contextBudget'];
    final rawMemoryTrace = map['memoryTrace'];
    final rawStickyMemory = map['stickyMemory'];
    final rawMemoryFlush = map['memoryFlush'];
    final rawBootstrap = map['bootstrap'];
    final rawDurableCompaction = map['durableCompaction'];
    final rawSkillInventory = map['skillInventory'];
    final rawActiveSkill = map['activeSkill'];
    final rawDiagnostics = map['diagnostics'];
    final rawRecoveryPlan = map['recoveryPlan'];
    final rawFinalAttachments =
        map['finalAttachments'] as List<Object?>? ?? const <Object?>[];
    final rawManagedProcessIds =
        map['managedProcessIds'] as List<Object?>? ?? const <Object?>[];
    final rawManagedProcesses =
        map['managedProcesses'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRunSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      lifecycleState: map['lifecycleState'] as String?,
      taskState: map['taskState'] as String?,
      attempt: map['attempt'] as int? ?? 0,
      executionOrdinal: map['executionOrdinal'] as int? ?? 0,
      executionId: map['executionId'] as String?,
      executionKind: map['executionKind'] as String?,
      pendingExecutionKind: map['pendingExecutionKind'] as String?,
      executionStatus: map['executionStatus'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      responseFormat: map['responseFormat'] as String?,
      pendingMessageId: map['pendingMessageId'] as String?,
      finalAttachments: rawFinalAttachments
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatAttachmentSnapshot.fromMap)
          .toList(growable: false),
      managedProcessIds: rawManagedProcessIds.whereType<String>().toList(
        growable: false,
      ),
      managedProcesses: rawManagedProcesses
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatManagedProcessSnapshot.fromMap)
          .toList(growable: false),
      runningManagedProcessCount:
          map['runningManagedProcessCount'] as int? ?? 0,
      hasLiveManagedProcesses: map['hasLiveManagedProcesses'] as bool? ?? false,
      isTerminal: map['isTerminal'] as bool? ?? false,
      lastEvent: rawLastEvent is Map<Object?, Object?>
          ? OpenCrayChatRuntimeEventSnapshot.fromMap(rawLastEvent)
          : null,
      llmDiagnostics: rawLlmDiagnostics is Map<Object?, Object?>
          ? OpenCrayChatRunLlmDiagnosticsSnapshot.fromMap(rawLlmDiagnostics)
          : null,
      liveContext: rawLiveContext is Map<Object?, Object?>
          ? OpenCrayChatRunLiveContextSnapshot.fromMap(rawLiveContext)
          : null,
      contextBudget: rawContextBudget is Map<Object?, Object?>
          ? OpenCrayChatRunContextBudgetSnapshot.fromMap(rawContextBudget)
          : null,
      memoryTrace: rawMemoryTrace is Map<Object?, Object?>
          ? OpenCrayChatRunMemoryTraceSnapshot.fromMap(rawMemoryTrace)
          : null,
      stickyMemory: rawStickyMemory is Map<Object?, Object?>
          ? OpenCrayChatRunStickyMemorySnapshot.fromMap(rawStickyMemory)
          : null,
      memoryFlush: rawMemoryFlush is Map<Object?, Object?>
          ? OpenCrayChatRunMemoryFlushSnapshot.fromMap(rawMemoryFlush)
          : null,
      bootstrap: rawBootstrap is Map<Object?, Object?>
          ? OpenCrayChatRunBootstrapSnapshot.fromMap(rawBootstrap)
          : null,
      durableCompaction: rawDurableCompaction is Map<Object?, Object?>
          ? OpenCrayChatRunDurableCompactionSnapshot.fromMap(
              rawDurableCompaction,
            )
          : null,
      skillInventory: rawSkillInventory is Map<Object?, Object?>
          ? OpenCrayChatRunSkillInventorySnapshot.fromMap(rawSkillInventory)
          : null,
      activeSkill: rawActiveSkill is Map<Object?, Object?>
          ? OpenCrayChatRunActiveSkillSnapshot.fromMap(rawActiveSkill)
          : null,
      diagnostics: rawDiagnostics is Map<Object?, Object?>
          ? OpenCrayChatRunDiagnosticsSnapshot.fromMap(rawDiagnostics)
          : null,
      recoveryPlan: rawRecoveryPlan is Map<Object?, Object?>
          ? OpenCrayChatRunRecoveryPlanSnapshot.fromMap(rawRecoveryPlan)
          : null,
    );
  }
}

class OpenCrayChatManagedProcessSnapshot {
  const OpenCrayChatManagedProcessSnapshot({
    required this.processId,
    required this.status,
    required this.command,
    required this.startedAtEpochMs,
    required this.updatedAtEpochMs,
    this.args = const <String>[],
    this.workingDirectory,
    this.processStarted = false,
    this.timeoutMs = 0,
    this.finishedAtEpochMs,
    this.exitCode,
    this.errorCode,
    this.errorMessage,
    this.timedOut = false,
    this.cancelled = false,
    this.outputLimitExceeded = false,
    this.stdout = '',
    this.stderr = '',
    this.stdoutPreview = '',
    this.stderrPreview = '',
    this.stdoutTruncated = false,
    this.stderrTruncated = false,
  });

  final String processId;
  final String status;
  final String command;
  final List<String> args;
  final String? workingDirectory;
  final bool processStarted;
  final int timeoutMs;
  final int startedAtEpochMs;
  final int updatedAtEpochMs;
  final int? finishedAtEpochMs;
  final int? exitCode;
  final String? errorCode;
  final String? errorMessage;
  final bool timedOut;
  final bool cancelled;
  final bool outputLimitExceeded;
  final String stdout;
  final String stderr;
  final String stdoutPreview;
  final String stderrPreview;
  final bool stdoutTruncated;
  final bool stderrTruncated;

  factory OpenCrayChatManagedProcessSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawArgs = map['args'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatManagedProcessSnapshot(
      processId: map['processId'] as String? ?? '',
      status: map['status'] as String? ?? '',
      command: map['command'] as String? ?? '',
      args: rawArgs.whereType<String>().toList(growable: false),
      workingDirectory: map['workingDirectory'] as String?,
      processStarted: map['processStarted'] as bool? ?? false,
      timeoutMs: map['timeoutMs'] as int? ?? 0,
      startedAtEpochMs: map['startedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      finishedAtEpochMs: map['finishedAtEpochMs'] as int?,
      exitCode: map['exitCode'] as int?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      timedOut: map['timedOut'] as bool? ?? false,
      cancelled: map['cancelled'] as bool? ?? false,
      outputLimitExceeded: map['outputLimitExceeded'] as bool? ?? false,
      stdout:
          map['stdout'] as String? ?? (map['stdoutPreview'] as String? ?? ''),
      stderr:
          map['stderr'] as String? ?? (map['stderrPreview'] as String? ?? ''),
      stdoutPreview: map['stdoutPreview'] as String? ?? '',
      stderrPreview: map['stderrPreview'] as String? ?? '',
      stdoutTruncated: map['stdoutTruncated'] as bool? ?? false,
      stderrTruncated: map['stderrTruncated'] as bool? ?? false,
    );
  }
}

extension OpenCrayChatRunExecutionScope on OpenCrayChatRunSnapshot {
  List<OpenCrayChatRuntimeEventSnapshot> scopeRuntimeEvents(
    Iterable<OpenCrayChatRuntimeEventSnapshot> events,
  ) {
    final List<OpenCrayChatRuntimeEventSnapshot> runEvents = events
        .where(_matchesRuntimeEventIdentity)
        .toList(growable: false);
    final bool preserveHistoryAcrossExecutions =
        _preservesHistoryAcrossExecutions(executionKind) ||
        _preservesHistoryAcrossExecutions(pendingExecutionKind);
    final String? currentExecutionId = _normalizedExecutionValue(executionId);
    if (currentExecutionId == null) {
      if (_normalizedExecutionValue(pendingExecutionKind) != null &&
          !isTerminal) {
        if (preserveHistoryAcrossExecutions) {
          return runEvents;
        }
        final List<OpenCrayChatRuntimeEventSnapshot> untagged = runEvents
            .where(_isRuntimeEventExecutionUntagged)
            .toList(growable: false);
        if (untagged.isNotEmpty) {
          return untagged;
        }
        final bool hasTaggedEvents = runEvents.any(
          (event) => !_isRuntimeEventExecutionUntagged(event),
        );
        return hasTaggedEvents
            ? const <OpenCrayChatRuntimeEventSnapshot>[]
            : runEvents;
      }
      return runEvents;
    }
    final List<OpenCrayChatRuntimeEventSnapshot> matching = runEvents
        .where(
          (event) =>
              _normalizedExecutionValue(event.executionId) ==
              currentExecutionId,
        )
        .toList(growable: false);
    if (matching.isNotEmpty) {
      return preserveHistoryAcrossExecutions ? runEvents : matching;
    }
    if (preserveHistoryAcrossExecutions) {
      return runEvents;
    }
    final bool hasTaggedEvents = runEvents.any(
      (event) =>
          _normalizedExecutionValue(event.executionId) != null ||
          event.executionOrdinal != null ||
          _normalizedExecutionValue(event.executionKind) != null,
    );
    if (hasTaggedEvents || executionOrdinal > 0) {
      return const <OpenCrayChatRuntimeEventSnapshot>[];
    }
    return runEvents
        .where(
          (event) =>
              _normalizedExecutionValue(event.executionId) == null &&
              event.executionOrdinal == null &&
              _normalizedExecutionValue(event.executionKind) == null,
        )
        .toList(growable: false);
  }

  bool matchesRuntimeEvent(OpenCrayChatRuntimeEventSnapshot event) {
    if (!_matchesRuntimeEventIdentity(event)) {
      return false;
    }
    final String? currentExecutionId = _normalizedExecutionValue(executionId);
    if (currentExecutionId == null) {
      if (_normalizedExecutionValue(pendingExecutionKind) != null &&
          !isTerminal) {
        return _isRuntimeEventExecutionUntagged(event);
      }
      return true;
    }
    return _normalizedExecutionValue(event.executionId) == currentExecutionId;
  }

  bool _matchesRuntimeEventIdentity(OpenCrayChatRuntimeEventSnapshot event) {
    final String normalizedRunId = runId.trim();
    final String eventRunId = event.runId.trim();
    if (normalizedRunId.isNotEmpty && eventRunId == normalizedRunId) {
      return true;
    }
    final String normalizedTaskId = taskId.trim();
    final String eventTaskId = event.taskId.trim();
    return normalizedTaskId.isNotEmpty && eventTaskId == normalizedTaskId;
  }
}

String? _normalizedExecutionValue(String? raw) {
  final String value = raw?.trim() ?? '';
  return value.isEmpty ? null : value;
}

bool _isRuntimeEventExecutionUntagged(OpenCrayChatRuntimeEventSnapshot event) {
  return _normalizedExecutionValue(event.executionId) == null &&
      event.executionOrdinal == null &&
      _normalizedExecutionValue(event.executionKind) == null;
}

bool _preservesHistoryAcrossExecutions(String? executionKind) {
  switch (_normalizedExecutionValue(executionKind)) {
    case 'approval_resume':
    case 'checkpoint_resume':
      return true;
    default:
      return false;
  }
}

class OpenCrayChatRuntimeSnapshot {
  const OpenCrayChatRuntimeSnapshot({
    required this.sessionId,
    required this.activeRuns,
    this.retainedRuns = const <OpenCrayChatRunSnapshot>[],
    this.subAgents = const <OpenCrayChatSubAgentSnapshot>[],
    required this.events,
    this.liveAssistantDrafts = const <OpenCrayChatLiveAssistantDraftSnapshot>[],
    this.hostLifecycle,
    this.updatedAtEpochMs = 0,
  });

  final String sessionId;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRunSnapshot> retainedRuns;
  final List<OpenCrayChatSubAgentSnapshot> subAgents;
  final List<OpenCrayChatRuntimeEventSnapshot> events;
  final List<OpenCrayChatLiveAssistantDraftSnapshot> liveAssistantDrafts;
  final OpenCrayHostLifecycleSnapshot? hostLifecycle;
  final int updatedAtEpochMs;

  factory OpenCrayChatRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawActiveRuns =
        map['activeRuns'] as List<Object?>? ?? const <Object?>[];
    final rawRetainedRuns =
        map['retainedRuns'] as List<Object?>? ?? const <Object?>[];
    final rawSubAgents =
        map['subAgents'] as List<Object?>? ?? const <Object?>[];
    final rawEvents = map['events'] as List<Object?>? ?? const <Object?>[];
    final rawLiveAssistantDrafts =
        map['liveAssistantDrafts'] as List<Object?>? ?? const <Object?>[];
    final rawHostLifecycle = map['hostLifecycle'];
    return OpenCrayChatRuntimeSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      activeRuns: rawActiveRuns
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunSnapshot.fromMap)
          .toList(growable: false),
      retainedRuns: rawRetainedRuns
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunSnapshot.fromMap)
          .toList(growable: false),
      subAgents: rawSubAgents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatSubAgentSnapshot.fromMap)
          .toList(growable: false),
      events: rawEvents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRuntimeEventSnapshot.fromMap)
          .toList(growable: false),
      liveAssistantDrafts: rawLiveAssistantDrafts
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatLiveAssistantDraftSnapshot.fromMap)
          .toList(growable: false),
      hostLifecycle: rawHostLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawHostLifecycle)
          : null,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
    );
  }
}

class OpenCrayChatRuntimeEventDelta {
  const OpenCrayChatRuntimeEventDelta({
    required this.sessionId,
    required this.events,
    required this.totalLength,
    this.sequence = 0,
    this.activeRuns = const <OpenCrayChatRunSnapshot>[],
    this.retainedRuns = const <OpenCrayChatRunSnapshot>[],
    this.subAgents = const <OpenCrayChatSubAgentSnapshot>[],
    this.liveAssistantDrafts = const <OpenCrayChatLiveAssistantDraftSnapshot>[],
    this.hasActiveRunsPatch = false,
    this.hasRetainedRunsPatch = false,
    this.hasSubAgentsPatch = false,
    this.hasLiveAssistantDraftsPatch = false,
    this.hostLifecycle,
    this.updatedAtEpochMs = 0,
  });

  final String sessionId;
  final List<OpenCrayChatRuntimeEventSnapshot> events;
  final int totalLength;
  final int sequence;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRunSnapshot> retainedRuns;
  final List<OpenCrayChatSubAgentSnapshot> subAgents;
  final List<OpenCrayChatLiveAssistantDraftSnapshot> liveAssistantDrafts;
  final bool hasActiveRunsPatch;
  final bool hasRetainedRunsPatch;
  final bool hasSubAgentsPatch;
  final bool hasLiveAssistantDraftsPatch;
  final OpenCrayHostLifecycleSnapshot? hostLifecycle;
  final int updatedAtEpochMs;

  factory OpenCrayChatRuntimeEventDelta.fromMap(Map<Object?, Object?> map) {
    final rawEvents = map['events'] as List<Object?>? ?? const <Object?>[];
    final hasActiveRunsPatch = map.containsKey('activeRuns');
    final rawActiveRuns =
        map['activeRuns'] as List<Object?>? ?? const <Object?>[];
    final hasRetainedRunsPatch = map.containsKey('retainedRuns');
    final rawRetainedRuns =
        map['retainedRuns'] as List<Object?>? ?? const <Object?>[];
    final hasSubAgentsPatch = map.containsKey('subAgents');
    final rawSubAgents =
        map['subAgents'] as List<Object?>? ?? const <Object?>[];
    final hasLiveAssistantDraftsPatch = map.containsKey('liveAssistantDrafts');
    final rawLiveAssistantDrafts =
        map['liveAssistantDrafts'] as List<Object?>? ?? const <Object?>[];
    final rawHostLifecycle = map['hostLifecycle'];
    return OpenCrayChatRuntimeEventDelta(
      sessionId: map['sessionId'] as String? ?? '',
      events: rawEvents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRuntimeEventSnapshot.fromMap)
          .toList(growable: false),
      totalLength: map['totalLength'] as int? ?? 0,
      sequence: map['sequence'] as int? ?? 0,
      activeRuns: rawActiveRuns
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunSnapshot.fromMap)
          .toList(growable: false),
      retainedRuns: rawRetainedRuns
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunSnapshot.fromMap)
          .toList(growable: false),
      subAgents: rawSubAgents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatSubAgentSnapshot.fromMap)
          .toList(growable: false),
      liveAssistantDrafts: rawLiveAssistantDrafts
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatLiveAssistantDraftSnapshot.fromMap)
          .toList(growable: false),
      hasActiveRunsPatch: hasActiveRunsPatch,
      hasRetainedRunsPatch: hasRetainedRunsPatch,
      hasSubAgentsPatch: hasSubAgentsPatch,
      hasLiveAssistantDraftsPatch: hasLiveAssistantDraftsPatch,
      hostLifecycle: rawHostLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawHostLifecycle)
          : null,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
    );
  }

  bool get hasRuntimeActivityPatch =>
      events.isNotEmpty ||
      hasActiveRunsPatch ||
      activeRuns.isNotEmpty ||
      hasRetainedRunsPatch ||
      retainedRuns.isNotEmpty ||
      hasSubAgentsPatch ||
      subAgents.isNotEmpty ||
      hasLiveAssistantDraftsPatch ||
      liveAssistantDrafts.isNotEmpty ||
      hostLifecycle != null ||
      updatedAtEpochMs > 0;
}

class OpenCrayChatLiveAssistantDraftSnapshot {
  const OpenCrayChatLiveAssistantDraftSnapshot({
    required this.runId,
    required this.taskId,
    required this.pendingMessageId,
    required this.text,
    required this.updatedAtEpochMs,
  });

  final String runId;
  final String taskId;
  final String pendingMessageId;
  final String text;
  final int updatedAtEpochMs;

  factory OpenCrayChatLiveAssistantDraftSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatLiveAssistantDraftSnapshot(
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      pendingMessageId: map['pendingMessageId'] as String? ?? '',
      text: map['text'] as String? ?? '',
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
    );
  }
}

class OpenCrayChatLiveAssistantDraftEvent {
  const OpenCrayChatLiveAssistantDraftEvent({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.pendingMessageId,
    required this.text,
    required this.updatedAtEpochMs,
    this.cleared = false,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final String pendingMessageId;
  final String text;
  final int updatedAtEpochMs;
  final bool cleared;

  factory OpenCrayChatLiveAssistantDraftEvent.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatLiveAssistantDraftEvent(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      pendingMessageId: map['pendingMessageId'] as String? ?? '',
      text: map['text'] as String? ?? '',
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      cleared: map['cleared'] as bool? ?? false,
    );
  }
}

class OpenCrayChatSubAgentSnapshot {
  const OpenCrayChatSubAgentSnapshot({
    required this.parentRunId,
    required this.parentTaskId,
    required this.childRunId,
    required this.childTaskId,
    required this.label,
    required this.subagentType,
    required this.contextMode,
    required this.depth,
    required this.startedAtEpochMs,
    required this.updatedAtEpochMs,
    required this.eventCount,
    this.mailboxMessageCount = 0,
    this.mailboxPendingMessageCount = 0,
    this.mailboxLastDeliveredMessageId,
    this.phase,
    this.status,
    this.executionState,
    this.continuationKind,
    this.resumable = false,
    this.requiresUserAction = false,
    this.isHighRisk = false,
    this.summary,
  });

  final String parentRunId;
  final String parentTaskId;
  final String childRunId;
  final String childTaskId;
  final String label;
  final String subagentType;
  final String contextMode;
  final int depth;
  final String? phase;
  final String? status;
  final String? executionState;
  final String? continuationKind;
  final bool resumable;
  final bool requiresUserAction;
  final bool isHighRisk;
  final String? summary;
  final int startedAtEpochMs;
  final int updatedAtEpochMs;
  final int eventCount;
  final int mailboxMessageCount;
  final int mailboxPendingMessageCount;
  final String? mailboxLastDeliveredMessageId;

  factory OpenCrayChatSubAgentSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatSubAgentSnapshot(
      parentRunId: map['parentRunId'] as String? ?? '',
      parentTaskId: map['parentTaskId'] as String? ?? '',
      childRunId: map['childRunId'] as String? ?? '',
      childTaskId: map['childTaskId'] as String? ?? '',
      label: map['label'] as String? ?? '',
      subagentType: map['subagentType'] as String? ?? '',
      contextMode: map['contextMode'] as String? ?? '',
      depth: map['depth'] as int? ?? 0,
      phase: map['phase'] as String?,
      status: map['status'] as String?,
      executionState: map['executionState'] as String?,
      continuationKind: map['continuationKind'] as String?,
      resumable: map['resumable'] as bool? ?? false,
      requiresUserAction: map['requiresUserAction'] as bool? ?? false,
      isHighRisk: map['isHighRisk'] as bool? ?? false,
      summary: map['summary'] as String?,
      startedAtEpochMs: map['startedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      eventCount: map['eventCount'] as int? ?? 0,
      mailboxMessageCount: map['mailboxMessageCount'] as int? ?? 0,
      mailboxPendingMessageCount:
          map['mailboxPendingMessageCount'] as int? ?? 0,
      mailboxLastDeliveredMessageId:
          map['mailboxLastDeliveredMessageId'] as String?,
    );
  }
}

class OpenCrayChatRunSubmission {
  const OpenCrayChatRunSubmission({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
    this.diagnostics,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;
  final OpenCrayChatRunDiagnosticsSnapshot? diagnostics;

  factory OpenCrayChatRunSubmission.fromMap(Map<Object?, Object?> map) {
    final rawDiagnostics = map['diagnostics'];
    return OpenCrayChatRunSubmission(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
      diagnostics: rawDiagnostics is Map<Object?, Object?>
          ? OpenCrayChatRunDiagnosticsSnapshot.fromMap(rawDiagnostics)
          : null,
    );
  }
}

class OpenCrayChatTodoSnapshot {
  const OpenCrayChatTodoSnapshot({
    required this.content,
    required this.status,
    this.activeForm,
  });

  final String content;
  final String status;
  final String? activeForm;

  factory OpenCrayChatTodoSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatTodoSnapshot(
      content: map['content'] as String? ?? '',
      status: map['status'] as String? ?? 'pending',
      activeForm: map['activeForm'] as String?,
    );
  }
}

class OpenCrayChatSnapshot {
  const OpenCrayChatSnapshot({
    required this.screenTitle,
    required this.modeLabel,
    required this.sessionButtonLabel,
    required this.composerPlaceholder,
    required this.summary,
    required this.messages,
    required this.drawer,
    required this.isInputEnabled,
    this.todos = const <OpenCrayChatTodoSnapshot>[],
    this.todoState = 'empty',
    this.todoHideDelayMs,
    this.todoCompletedAtEpochMs,
    this.pendingApprovals = const <OpenCrayChatPendingApprovalSnapshot>[],
    this.runtimeActivity,
    this.updatedAtEpochMs = 0,
  });

  final String screenTitle;
  final String modeLabel;
  final String sessionButtonLabel;
  final String composerPlaceholder;
  final OpenCrayChatSummarySnapshot summary;
  final List<OpenCrayChatMessageSnapshot> messages;
  final OpenCrayChatDrawerSnapshot drawer;
  final bool isInputEnabled;
  final List<OpenCrayChatTodoSnapshot> todos;
  final String todoState;
  final int? todoHideDelayMs;
  final int? todoCompletedAtEpochMs;
  final List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals;
  final OpenCrayChatRuntimeSnapshot? runtimeActivity;
  final int updatedAtEpochMs;

  factory OpenCrayChatSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawMessages = map['messages'] as List<Object?>? ?? const <Object?>[];
    final rawTodos = map['todos'] as List<Object?>? ?? const <Object?>[];
    final rawPendingApprovals =
        map['pendingApprovals'] as List<Object?>? ?? const <Object?>[];
    final rawRuntimeActivity = map['runtimeActivity'];
    return OpenCrayChatSnapshot(
      screenTitle: map['screenTitle'] as String? ?? 'Chat',
      modeLabel: map['modeLabel'] as String? ?? 'AUTO',
      sessionButtonLabel: map['sessionButtonLabel'] as String? ?? 'Sessions',
      composerPlaceholder:
          map['composerPlaceholder'] as String? ?? 'Message OpenCray',
      summary: OpenCrayChatSummarySnapshot.fromMap(
        map['summary'] as Map<Object?, Object?>? ?? const <Object?, Object?>{},
      ),
      messages: rawMessages
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatMessageSnapshot.fromMap)
          .toList(growable: false),
      drawer: OpenCrayChatDrawerSnapshot.fromMap(
        map['drawer'] as Map<Object?, Object?>? ?? const <Object?, Object?>{},
      ),
      isInputEnabled: map['isInputEnabled'] as bool? ?? true,
      todos: rawTodos
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatTodoSnapshot.fromMap)
          .toList(growable: false),
      todoState:
          map['todoState'] as String? ??
          (rawTodos.isNotEmpty ? 'active' : 'empty'),
      todoHideDelayMs: map['todoHideDelayMs'] as int?,
      todoCompletedAtEpochMs: map['todoCompletedAtEpochMs'] as int?,
      pendingApprovals: rawPendingApprovals
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatPendingApprovalSnapshot.fromMap)
          .toList(growable: false),
      runtimeActivity: rawRuntimeActivity is Map<Object?, Object?>
          ? OpenCrayChatRuntimeSnapshot.fromMap(rawRuntimeActivity)
          : null,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
    );
  }
}
