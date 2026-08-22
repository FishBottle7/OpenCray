import 'opencray_chat_snapshot_events.dart';
import 'opencray_chat_snapshot_run_diagnostics.dart';

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
    this.streamInstanceId,
    this.lastSequence,
    this.flutterAppInstanceId,
    this.bridgeInstanceId,
    this.bridgeEpoch,
    this.hostLifecycle,
    this.updatedAtEpochMs = 0,
  });

  final String sessionId;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRunSnapshot> retainedRuns;
  final List<OpenCrayChatSubAgentSnapshot> subAgents;
  final List<OpenCrayChatRuntimeEventSnapshot> events;
  final List<OpenCrayChatLiveAssistantDraftSnapshot> liveAssistantDrafts;
  final String? streamInstanceId;
  final int? lastSequence;
  final String? flutterAppInstanceId;
  final String? bridgeInstanceId;
  final String? bridgeEpoch;
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
      streamInstanceId: map['streamInstanceId'] as String?,
      lastSequence: map['lastSequence'] as int?,
      flutterAppInstanceId: map['flutterAppInstanceId'] as String?,
      bridgeInstanceId: map['bridgeInstanceId'] as String?,
      bridgeEpoch: map['bridgeEpoch'] as String?,
      hostLifecycle: rawHostLifecycle is Map<Object?, Object?>
          ? OpenCrayHostLifecycleSnapshot.fromMap(rawHostLifecycle)
          : null,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
    );
  }
}
class OpenCrayChatLiveAssistantDraftSnapshot {
  const OpenCrayChatLiveAssistantDraftSnapshot({
    required this.runId,
    required this.taskId,
    required this.pendingMessageId,
    required this.text,
    required this.updatedAtEpochMs,
    this.executionId,
    this.streamInstanceId,
    this.sequence,
    this.lastSequence,
    this.eventId,
    this.bridgeInstanceId,
    this.bridgeEpoch,
  });

  final String runId;
  final String taskId;
  final String pendingMessageId;
  final String text;
  final int updatedAtEpochMs;
  final String? executionId;
  final String? streamInstanceId;
  final int? sequence;
  final int? lastSequence;
  final String? eventId;
  final String? bridgeInstanceId;
  final String? bridgeEpoch;

  factory OpenCrayChatLiveAssistantDraftSnapshot.fromMap(
    Map<Object?, Object?> map,
  ) {
    return OpenCrayChatLiveAssistantDraftSnapshot(
      runId: map['runId'] as String? ?? '',
      taskId: map['taskId'] as String? ?? '',
      pendingMessageId: map['pendingMessageId'] as String? ?? '',
      text: map['text'] as String? ?? '',
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      executionId: map['executionId'] as String?,
      streamInstanceId: map['streamInstanceId'] as String?,
      sequence: map['sequence'] as int?,
      lastSequence: map['lastSequence'] as int?,
      eventId: map['eventId'] as String?,
      bridgeInstanceId: map['bridgeInstanceId'] as String?,
      bridgeEpoch: map['bridgeEpoch'] as String?,
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
    this.executionId,
    this.streamInstanceId,
    this.sequence,
    this.lastSequence,
    this.eventId,
    this.bridgeInstanceId,
    this.bridgeEpoch,
    this.cleared = false,
  });

  final String sessionId;
  final String runId;
  final String taskId;
  final String pendingMessageId;
  final String text;
  final int updatedAtEpochMs;
  final String? executionId;
  final String? streamInstanceId;
  final int? sequence;
  final int? lastSequence;
  final String? eventId;
  final String? bridgeInstanceId;
  final String? bridgeEpoch;
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
      executionId: map['executionId'] as String?,
      streamInstanceId: map['streamInstanceId'] as String?,
      sequence: map['sequence'] as int?,
      lastSequence: map['lastSequence'] as int?,
      eventId: map['eventId'] as String?,
      bridgeInstanceId: map['bridgeInstanceId'] as String?,
      bridgeEpoch: map['bridgeEpoch'] as String?,
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
    this.contextModeSource,
    required this.depth,
    required this.startedAtEpochMs,
    required this.updatedAtEpochMs,
    required this.eventCount,
    this.hasActiveExecution = false,
    this.mailboxMessageCount = 0,
    this.mailboxPendingMessageCount = 0,
    this.mailboxLastDeliveredMessageId,
    this.hasPendingApprovalResume = false,
    this.pendingApprovalToolName,
    this.pendingApprovalIsHighRisk = false,
    this.pendingApprovalChildRunId,
    this.pendingApprovalChildTaskId,
    this.childSessionId,
    this.phase,
    this.status,
    this.executionState,
    this.continuationKind,
    this.resumable = false,
    this.requiresUserAction = false,
    this.isHighRisk = false,
    this.summary,
    this.liveContext,
  });

  final String parentRunId;
  final String parentTaskId;
  final String? childSessionId;
  final String childRunId;
  final String childTaskId;
  final String label;
  final String subagentType;
  final String contextMode;
  final String? contextModeSource;
  final int depth;
  final String? phase;
  final String? status;
  final String? executionState;
  final String? continuationKind;
  final bool resumable;
  final bool requiresUserAction;
  final bool isHighRisk;
  final String? summary;
  final Map<String, Object?>? liveContext;
  final int startedAtEpochMs;
  final int updatedAtEpochMs;
  final int eventCount;
  final bool hasActiveExecution;
  final int mailboxMessageCount;
  final int mailboxPendingMessageCount;
  final String? mailboxLastDeliveredMessageId;
  final bool hasPendingApprovalResume;
  final String? pendingApprovalToolName;
  final bool pendingApprovalIsHighRisk;
  final String? pendingApprovalChildRunId;
  final String? pendingApprovalChildTaskId;

  factory OpenCrayChatSubAgentSnapshot.fromMap(Map<Object?, Object?> map) {
    return OpenCrayChatSubAgentSnapshot(
      parentRunId: map['parentRunId'] as String? ?? '',
      parentTaskId: map['parentTaskId'] as String? ?? '',
      childSessionId: map['childSessionId'] as String?,
      childRunId: map['childRunId'] as String? ?? '',
      childTaskId: map['childTaskId'] as String? ?? '',
      label: map['label'] as String? ?? '',
      subagentType: map['subagentType'] as String? ?? '',
      contextMode: map['contextMode'] as String? ?? '',
      contextModeSource: map['contextModeSource'] as String?,
      depth: map['depth'] as int? ?? 0,
      phase: map['phase'] as String?,
      status: map['status'] as String?,
      executionState: map['executionState'] as String?,
      continuationKind: map['continuationKind'] as String?,
      resumable: map['resumable'] as bool? ?? false,
      requiresUserAction: map['requiresUserAction'] as bool? ?? false,
      isHighRisk: map['isHighRisk'] as bool? ?? false,
      summary: map['summary'] as String?,
      liveContext: map['liveContext'] is Map<Object?, Object?>
          ? (map['liveContext']! as Map<Object?, Object?>).cast<String, Object?>()
          : null,
      startedAtEpochMs: map['startedAtEpochMs'] as int? ?? 0,
      updatedAtEpochMs: map['updatedAtEpochMs'] as int? ?? 0,
      eventCount: map['eventCount'] as int? ?? 0,
      hasActiveExecution: map['hasActiveExecution'] as bool? ?? false,
      mailboxMessageCount: map['mailboxMessageCount'] as int? ?? 0,
      mailboxPendingMessageCount:
          map['mailboxPendingMessageCount'] as int? ?? 0,
      mailboxLastDeliveredMessageId:
          map['mailboxLastDeliveredMessageId'] as String?,
      hasPendingApprovalResume:
          map['hasPendingApprovalResume'] as bool? ?? false,
      pendingApprovalToolName: map['pendingApprovalToolName'] as String?,
      pendingApprovalIsHighRisk:
          map['pendingApprovalIsHighRisk'] as bool? ?? false,
      pendingApprovalChildRunId: map['pendingApprovalChildRunId'] as String?,
      pendingApprovalChildTaskId:
          map['pendingApprovalChildTaskId'] as String?,
    );
  }
}
