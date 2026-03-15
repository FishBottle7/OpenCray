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
    required this.kind,
    required this.text,
    this.meta = '',
  });

  final String kind;
  final String text;
  final String meta;

  factory OpenCrayChatMessageSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatMessageSnapshot(
      kind: map['kind'] as String? ?? 'inbound',
      text: map['text'] as String? ?? '',
      meta: map['meta'] as String? ?? '',
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
  });

  final String sessionId;
  final String title;
  final String preview;
  final String meta;
  final bool isSelected;

  factory OpenCrayChatSessionItemSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatSessionItemSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      title: map['title'] as String? ?? '',
      preview: map['preview'] as String? ?? '',
      meta: map['meta'] as String? ?? '',
      isSelected: map['isSelected'] as bool? ?? false,
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
  });

  final String runId;
  final String taskId;
  final String title;
  final String body;
  final String approveLabel;
  final String rejectLabel;
  final bool isHighRisk;

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
    );
  }
}

class OpenCrayChatRuntimeEventSnapshot {
  const OpenCrayChatRuntimeEventSnapshot({
    required this.kind,
    required this.runId,
    required this.taskId,
    required this.emittedAtEpochMs,
    this.turn,
    this.phase,
    this.status,
    this.errorCode,
    this.errorMessage,
    this.responseFormat,
    this.isFinal,
    this.text,
    this.toolName,
    this.toolReason,
    this.argumentsJson,
    this.toolStatus,
    this.contentPreview,
  });

  final String kind;
  final String runId;
  final String taskId;
  final int emittedAtEpochMs;
  final int? turn;
  final String? phase;
  final String? status;
  final String? errorCode;
  final String? errorMessage;
  final String? responseFormat;
  final bool? isFinal;
  final String? text;
  final String? toolName;
  final String? toolReason;
  final String? argumentsJson;
  final String? toolStatus;
  final String? contentPreview;

  factory OpenCrayChatRuntimeEventSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatRuntimeEventSnapshot(
      kind: map['kind'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      emittedAtEpochMs: map['emittedAtEpochMs'] as int? ?? 0,
      turn: map['turn'] as int?,
      phase: map['phase'] as String?,
      status: map['status'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
      responseFormat: map['responseFormat'] as String?,
      isFinal: map['isFinal'] as bool?,
      text: map['text'] as String?,
      toolName: map['toolName'] as String?,
      toolReason: map['toolReason'] as String?,
      argumentsJson: map['argumentsJson'] as String?,
      toolStatus: map['toolStatus'] as String?,
      contentPreview: map['contentPreview'] as String?,
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

  factory OpenCrayChatRunSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawLastEvent = map['lastEvent'];
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
    );
  }
}

class OpenCrayChatRuntimeSnapshot {
  const OpenCrayChatRuntimeSnapshot({
    required this.sessionId,
    required this.activeRuns,
    required this.events,
  });

  final String sessionId;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRuntimeEventSnapshot> events;

  factory OpenCrayChatRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawActiveRuns = map['activeRuns'] as List<Object?>? ?? const <Object?>[];
    final rawEvents = map['events'] as List<Object?>? ?? const <Object?>[];
    return OpenCrayChatRuntimeSnapshot(
      sessionId: map['sessionId'] as String? ?? '',
      activeRuns: rawActiveRuns
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRunSnapshot.fromMap)
          .toList(growable: false),
      events: rawEvents
          .whereType<Map<Object?, Object?>>()
          .map(OpenCrayChatRuntimeEventSnapshot.fromMap)
          .toList(growable: false),
    );
  }
}

class OpenCrayChatRunSubmission {
  const OpenCrayChatRunSubmission({
    required this.sessionId,
    required this.runId,
    required this.taskId,
    required this.acceptedAtEpochMs,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final int acceptedAtEpochMs;

  factory OpenCrayChatRunSubmission.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatRunSubmission(
      sessionId: map['sessionId'] as String? ?? '',
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      acceptedAtEpochMs: map['acceptedAtEpochMs'] as int? ?? 0,
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
  final List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals;
  final OpenCrayChatRuntimeSnapshot? runtimeActivity;

  factory OpenCrayChatSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawMessages = map['messages'] as List<Object?>? ?? const <Object?>[];
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
