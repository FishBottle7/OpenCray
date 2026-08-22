import 'opencray_chat_snapshot_run_diagnostics.dart';
import 'opencray_chat_snapshot_runtime.dart';

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
