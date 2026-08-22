import 'opencray_chat_snapshot_run_diagnostics.dart';
import 'opencray_chat_snapshot_runtime.dart';

class OpenCrayChatRuntimeEventSnapshot {
  const OpenCrayChatRuntimeEventSnapshot({
    required this.kind,
    required this.runId,
    required this.taskId,
    required this.emittedAtEpochMs,
    this.eventId,
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
  final String? eventId;
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
      eventId: map['eventId'] as String?,
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
class OpenCrayChatRuntimeEventDelta {
  const OpenCrayChatRuntimeEventDelta({
    required this.sessionId,
    required this.events,
    required this.totalLength,
    this.sequence = 0,
    this.streamInstanceId,
    this.lastSequence,
    this.eventId,
    this.executionId,
    this.bridgeInstanceId,
    this.bridgeEpoch,
    this.activeRuns = const <OpenCrayChatRunSnapshot>[],
    this.retainedRuns = const <OpenCrayChatRunSnapshot>[],
    this.subAgents = const <OpenCrayChatSubAgentSnapshot>[],
    this.liveAssistantDrafts = const <OpenCrayChatLiveAssistantDraftSnapshot>[],
    this.hasActiveRunsPatch = false,
    this.hasRetainedRunsPatch = false,
    this.hasSubAgentsPatch = false,
    this.hasLiveAssistantDraftsPatch = false,
    this.hostLifecycle,
    this.runPatchMode = 'replace',
    this.updatedAtEpochMs = 0,
  });

  final String sessionId;
  final List<OpenCrayChatRuntimeEventSnapshot> events;
  final int totalLength;
  final int sequence;
  final String? streamInstanceId;
  final int? lastSequence;
  final String? eventId;
  final String? executionId;
  final String? bridgeInstanceId;
  final String? bridgeEpoch;
  final List<OpenCrayChatRunSnapshot> activeRuns;
  final List<OpenCrayChatRunSnapshot> retainedRuns;
  final List<OpenCrayChatSubAgentSnapshot> subAgents;
  final List<OpenCrayChatLiveAssistantDraftSnapshot> liveAssistantDrafts;
  final bool hasActiveRunsPatch;
  final bool hasRetainedRunsPatch;
  final bool hasSubAgentsPatch;
  final bool hasLiveAssistantDraftsPatch;
  final OpenCrayHostLifecycleSnapshot? hostLifecycle;
  final String runPatchMode;
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
      streamInstanceId: map['streamInstanceId'] as String?,
      lastSequence: map['lastSequence'] as int?,
      eventId: map['eventId'] as String?,
      executionId: map['executionId'] as String?,
      bridgeInstanceId: map['bridgeInstanceId'] as String?,
      bridgeEpoch: map['bridgeEpoch'] as String?,
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
      runPatchMode: map['runPatchMode'] as String? ?? 'replace',
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
