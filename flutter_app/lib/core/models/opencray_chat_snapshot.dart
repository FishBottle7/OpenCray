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
    this.omittedMessageCount,
    this.omittedCharCount,
    this.signature,
    this.candidateCount,
    this.writtenRecordCount,
    this.writtenKinds = const <String>[],
    this.writtenRecordIds = const <String>[],
  });

  final String? outcome;
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
    this.toolRestrictionEnabled,
    this.truncated,
    this.allowedToolKeys = const <String>[],
  });

  final String? name;
  final String? relativePath;
  final String? invocationControl;
  final String? executionContext;
  final String? activationSource;
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

class OpenCrayChatRunSnapshot {
  const OpenCrayChatRunSnapshot({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
    required this.updatedAtEpochMs,
    required this.attempt,
    required this.isTerminal,
    this.lifecycleState,
    this.taskState,
    this.executionStatus,
    this.errorCode,
    this.errorMessage,
    this.responseFormat,
    this.pendingMessageId,
    this.lastEvent,
    this.liveContext,
    this.memoryTrace,
    this.memoryFlush,
    this.bootstrap,
    this.durableCompaction,
    this.skillInventory,
    this.activeSkill,
    this.diagnostics,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;
  final int updatedAtEpochMs;
  final String? lifecycleState;
  final String? taskState;
  final int attempt;
  final String? executionStatus;
  final String? errorCode;
  final String? errorMessage;
  final String? responseFormat;
  final String? pendingMessageId;
  final bool isTerminal;
  final OpenCrayChatRuntimeEventSnapshot? lastEvent;
  final OpenCrayChatRunLiveContextSnapshot? liveContext;
  final OpenCrayChatRunMemoryTraceSnapshot? memoryTrace;
  final OpenCrayChatRunMemoryFlushSnapshot? memoryFlush;
  final OpenCrayChatRunBootstrapSnapshot? bootstrap;
  final OpenCrayChatRunDurableCompactionSnapshot? durableCompaction;
  final OpenCrayChatRunSkillInventorySnapshot? skillInventory;
  final OpenCrayChatRunActiveSkillSnapshot? activeSkill;
  final OpenCrayChatRunDiagnosticsSnapshot? diagnostics;

  factory OpenCrayChatRunSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawLastEvent = map['lastEvent'];
    final rawLiveContext = map['liveContext'];
    final rawMemoryTrace = map['memoryTrace'];
    final rawMemoryFlush = map['memoryFlush'];
    final rawBootstrap = map['bootstrap'];
    final rawDurableCompaction = map['durableCompaction'];
    final rawSkillInventory = map['skillInventory'];
    final rawActiveSkill = map['activeSkill'];
    final rawDiagnostics = map['diagnostics'];
    return OpenCrayChatRunSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      lifecycleState: map['lifecycleState'] as String?,
      taskState: map['taskState'] as String?,
      attempt: map['attempt'] as int? ?? 0,
      executionStatus: map['executionStatus'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      responseFormat: map['responseFormat'] as String?,
      pendingMessageId: map['pendingMessageId'] as String?,
      isTerminal: map['isTerminal'] as bool? ?? false,
      lastEvent: rawLastEvent is Map<Object?, Object?>
          ? OpenCrayChatRuntimeEventSnapshot.fromMap(rawLastEvent)
          : null,
      liveContext: rawLiveContext is Map<Object?, Object?>
          ? OpenCrayChatRunLiveContextSnapshot.fromMap(rawLiveContext)
          : null,
      memoryTrace: rawMemoryTrace is Map<Object?, Object?>
          ? OpenCrayChatRunMemoryTraceSnapshot.fromMap(rawMemoryTrace)
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
    );
  }
}

class OpenCrayChatRuntimeSnapshot {
  const OpenCrayChatRuntimeSnapshot({
    required this.sessionId,
    required this.activeRuns,
    this.retainedRuns = const <OpenCrayChatRunSnapshot>[],
    required this.events,
    this.hostLifecycle,
  });

  final String sessionId;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRunSnapshot> retainedRuns;
  final List<OpenCrayChatRuntimeEventSnapshot> events;
  final OpenCrayHostLifecycleSnapshot? hostLifecycle;

  factory OpenCrayChatRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawActiveRuns =
        map['activeRuns'] as List<Object?>? ?? const <Object?>[];
    final rawRetainedRuns =
        map['retainedRuns'] as List<Object?>? ?? const <Object?>[];
    final rawEvents = map['events'] as List<Object?>? ?? const <Object?>[];
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
      events: rawEvents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRuntimeEventSnapshot.fromMap)
          .toList(growable: false),
      hostLifecycle: rawHostLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawHostLifecycle)
          : null,
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
    );
  }
}
