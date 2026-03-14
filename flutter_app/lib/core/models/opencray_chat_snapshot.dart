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
    required this.taskId,
    required this.title,
    required this.body,
    required this.approveLabel,
    required this.rejectLabel,
    required this.isHighRisk,
  });

  final String taskId;
  final String title;
  final String body;
  final String approveLabel;
  final String rejectLabel;
  final bool isHighRisk;

  factory OpenCrayChatPendingApprovalSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatPendingApprovalSnapshot(
      taskId: map['taskId'] as String? ?? '',
      title: map['title'] as String? ?? '',
      body: map['body'] as String? ?? '',
      approveLabel: map['approveLabel'] as String? ?? 'Approve',
      rejectLabel: map['rejectLabel'] as String? ?? 'Reject',
      isHighRisk: map['isHighRisk'] as bool? ?? false,
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

  factory OpenCrayChatSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawMessages = map['messages'] as List<Object?>? ?? const <Object?>[];
    final rawPendingApprovals =
        map['pendingApprovals'] as List<Object?>? ?? const <Object?>[];
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
    );
  }
}
