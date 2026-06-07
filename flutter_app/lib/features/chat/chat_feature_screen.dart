import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show SelectedContentRange;
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_chat_draft_attachment.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import '../../core/models/opencray_file_image_preview.dart';
import '../../core/models/opencray_file_text_preview.dart';
import '../../core/models/opencray_file_voice_playback_source.dart';
import '../../core/models/opencray_sandbox_preview_embed_config.dart';
import '../../core/models/opencray_sandbox_settings.dart';
import '../../core/widgets/opencray_image_bytes_view.dart';
import '../../core/widgets/opencray_markdown.dart';
import 'chat_models.dart';
import 'chat_seed_data.dart';
import 'chat_voice_playback.dart';

class _TimedChatRunTraceHistoryEntry {
  const _TimedChatRunTraceHistoryEntry({
    required this.sortEpochMs,
    required this.sourceOrder,
    required this.entry,
  });

  final int sortEpochMs;
  final int sourceOrder;
  final ChatRunTraceHistoryEntry entry;
}

class _RuntimeProjectionPatch {
  const _RuntimeProjectionPatch({
    required this.messages,
    required this.runTraces,
  });

  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
}

class _RuntimeProjectedMessagePatch {
  const _RuntimeProjectedMessagePatch({
    required this.anchorMessageId,
    required this.text,
  });

  final String anchorMessageId;
  final String text;
}

@visibleForTesting
OpenCrayChatRuntimeSnapshot? resolveChatRuntimeSnapshot(
  OpenCrayChatRuntimeSnapshot? embedded,
  OpenCrayChatRuntimeSnapshot? streamed,
) {
  if (embedded == null) {
    return streamed;
  }
  if (streamed == null) {
    return embedded;
  }
  if (!_runtimeSnapshotsShareSession(embedded, streamed)) {
    return _preferRuntimeSnapshot(embedded, streamed);
  }
  return _mergeRuntimeSnapshots(embedded, streamed);
}

@visibleForTesting
OpenCrayChatRuntimeSnapshot? resolveChatRuntimeSnapshotForSession({
  required String expectedSessionId,
  OpenCrayChatRuntimeSnapshot? embedded,
  OpenCrayChatRuntimeSnapshot? streamed,
}) {
  final String normalizedExpectedSessionId = expectedSessionId.trim();
  return resolveChatRuntimeSnapshot(
    _runtimeSnapshotForExpectedSession(
      snapshot: embedded,
      expectedSessionId: normalizedExpectedSessionId,
    ),
    _runtimeSnapshotForExpectedSession(
      snapshot: streamed,
      expectedSessionId: normalizedExpectedSessionId,
    ),
  );
}

bool _runtimeSnapshotsShareSession(
  OpenCrayChatRuntimeSnapshot left,
  OpenCrayChatRuntimeSnapshot right,
) {
  final String leftSessionId = left.sessionId.trim();
  final String rightSessionId = right.sessionId.trim();
  return leftSessionId.isEmpty ||
      rightSessionId.isEmpty ||
      leftSessionId == rightSessionId;
}

OpenCrayChatRuntimeSnapshot? _runtimeSnapshotForExpectedSession({
  required OpenCrayChatRuntimeSnapshot? snapshot,
  required String expectedSessionId,
}) {
  if (snapshot == null || expectedSessionId.isEmpty) {
    return snapshot;
  }
  final String sessionId = snapshot.sessionId.trim();
  if (sessionId.isEmpty || sessionId == expectedSessionId) {
    return snapshot;
  }
  return null;
}

OpenCrayChatRuntimeSnapshot _preferRuntimeSnapshot(
  OpenCrayChatRuntimeSnapshot left,
  OpenCrayChatRuntimeSnapshot right,
) {
  final String leftHostInstanceId = _hostInstanceId(left);
  final String rightHostInstanceId = _hostInstanceId(right);
  if (leftHostInstanceId != rightHostInstanceId &&
      rightHostInstanceId.isNotEmpty) {
    return right;
  }
  final int leftOperationalVersion = _runtimeOperationalVersion(left);
  final int rightOperationalVersion = _runtimeOperationalVersion(right);
  if (rightOperationalVersion != leftOperationalVersion) {
    return rightOperationalVersion > leftOperationalVersion ? right : left;
  }
  final int leftVersion = runtimeSnapshotVersion(left);
  final int rightVersion = runtimeSnapshotVersion(right);
  if (rightVersion != leftVersion) {
    return rightVersion > leftVersion ? right : left;
  }
  final int leftDetailWeight = _runtimeDetailWeight(left);
  final int rightDetailWeight = _runtimeDetailWeight(right);
  if (rightDetailWeight != leftDetailWeight) {
    return rightDetailWeight > leftDetailWeight ? right : left;
  }
  return right;
}

OpenCrayChatRuntimeSnapshot _mergeRuntimeSnapshots(
  OpenCrayChatRuntimeSnapshot left,
  OpenCrayChatRuntimeSnapshot right,
) {
  final List<OpenCrayChatRunSnapshot> runs = _mergeRuntimeRuns(
    _visibleRuns(left),
    _visibleRuns(right),
  );
  final List<OpenCrayChatRunSnapshot> activeRuns = runs
      .where(_isRuntimeRunActiveForProjection)
      .toList(growable: false);
  final List<OpenCrayChatRunSnapshot> retainedRuns = runs
      .where((run) => !_isRuntimeRunActiveForProjection(run))
      .toList(growable: false);
  final List<OpenCrayChatRuntimeEventSnapshot> events = _mergeRuntimeEvents(
    left.events,
    right.events,
  );
  final List<OpenCrayChatSubAgentSnapshot> subAgents = _mergeRuntimeSubAgents(
    left.subAgents,
    right.subAgents,
  );
  final List<OpenCrayChatLiveAssistantDraftSnapshot> drafts =
      _mergeRuntimeDrafts(left.liveAssistantDrafts, right.liveAssistantDrafts);
  final OpenCrayHostLifecycleSnapshot? hostLifecycle = _preferHostLifecycle(
    left.hostLifecycle,
    right.hostLifecycle,
  );
  return OpenCrayChatRuntimeSnapshot(
    sessionId: right.sessionId.trim().isNotEmpty
        ? right.sessionId
        : left.sessionId,
    activeRuns: activeRuns,
    retainedRuns: retainedRuns,
    subAgents: subAgents,
    events: events,
    liveAssistantDrafts: drafts,
    hostLifecycle: hostLifecycle,
    updatedAtEpochMs: math.max(left.updatedAtEpochMs, right.updatedAtEpochMs),
  );
}

OpenCrayChatRuntimeSnapshot _mergeRuntimeDeltaSnapshot(
  OpenCrayChatRuntimeSnapshot current,
  OpenCrayChatRuntimeSnapshot delta,
) {
  final OpenCrayChatRuntimeSnapshot merged = _mergeRuntimeSnapshots(
    current,
    delta,
  );
  return OpenCrayChatRuntimeSnapshot(
    sessionId: merged.sessionId,
    activeRuns: merged.activeRuns,
    retainedRuns: merged.retainedRuns,
    subAgents: merged.subAgents,
    events: merged.events,
    liveAssistantDrafts: delta.liveAssistantDrafts,
    hostLifecycle: merged.hostLifecycle,
    updatedAtEpochMs: merged.updatedAtEpochMs,
  );
}

List<OpenCrayChatRunSnapshot> _mergeRuntimeRuns(
  List<OpenCrayChatRunSnapshot> left,
  List<OpenCrayChatRunSnapshot> right,
) {
  final List<OpenCrayChatRunSnapshot> merged = <OpenCrayChatRunSnapshot>[];
  void addRun(OpenCrayChatRunSnapshot run) {
    final int existingIndex = merged.indexWhere(
      (existing) => _runtimeRunsReferToSameRun(existing, run),
    );
    if (existingIndex < 0) {
      merged.add(run);
      return;
    }
    merged[existingIndex] = _mergeRuntimeRunSnapshots(
      merged[existingIndex],
      run,
    );
  }

  left.forEach(addRun);
  right.forEach(addRun);
  merged.sort((leftRun, rightRun) {
    if (leftRun.acceptedAtEpochMs != rightRun.acceptedAtEpochMs) {
      return leftRun.acceptedAtEpochMs.compareTo(rightRun.acceptedAtEpochMs);
    }
    return _runtimeRunKey(leftRun).compareTo(_runtimeRunKey(rightRun));
  });
  return merged;
}

bool _runtimeRunsReferToSameRun(
  OpenCrayChatRunSnapshot left,
  OpenCrayChatRunSnapshot right,
) {
  final String leftRunId = left.runId.trim();
  final String rightRunId = right.runId.trim();
  if (leftRunId.isNotEmpty && rightRunId.isNotEmpty) {
    return leftRunId == rightRunId;
  }
  final String leftTaskId = left.taskId.trim();
  final String rightTaskId = right.taskId.trim();
  return leftTaskId.isNotEmpty &&
      rightTaskId.isNotEmpty &&
      leftTaskId == rightTaskId;
}

String _runtimeRunKey(OpenCrayChatRunSnapshot run) {
  final String runId = run.runId.trim();
  if (runId.isNotEmpty) {
    return 'run:$runId';
  }
  final String taskId = run.taskId.trim();
  if (taskId.isNotEmpty) {
    return 'task:$taskId';
  }
  return 'accepted:${run.acceptedAtEpochMs}';
}

bool _runtimeRunHasContinuationIntent(OpenCrayChatRunSnapshot run) =>
    _runtimeExecutionKindIsContinuation(run.pendingExecutionKind) ||
    _runtimeExecutionKindIsContinuation(run.executionKind) ||
    _normalizedRuntimeRunValue(run.lifecycleState) == 'retry_pending';

bool _runtimeRunIsNonTerminalContinuation(OpenCrayChatRunSnapshot run) =>
    !run.isTerminal && _runtimeRunHasContinuationIntent(run);

bool _runtimeExecutionKindIsContinuation(String? value) {
  switch (_normalizedRuntimeRunValue(value)) {
    case 'retry':
    case 'approval_resume':
    case 'checkpoint_resume':
      return true;
    default:
      return false;
  }
}

String? _normalizedRuntimeRunValue(String? value) {
  final String normalized = value?.trim().toLowerCase() ?? '';
  return normalized.isEmpty ? null : normalized;
}

OpenCrayChatRunSnapshot _preferRuntimeRunSnapshot(
  OpenCrayChatRunSnapshot left,
  OpenCrayChatRunSnapshot right,
) {
  if (right.isTerminal &&
      !left.isTerminal &&
      right.updatedAtEpochMs >= left.updatedAtEpochMs) {
    if (_runtimeRunIsNonTerminalContinuation(left) &&
        !_runtimeRunHasContinuationIntent(right)) {
      return left;
    }
    return right;
  }
  if (left.isTerminal &&
      !right.isTerminal &&
      left.updatedAtEpochMs >= right.updatedAtEpochMs) {
    if (_runtimeRunIsNonTerminalContinuation(right) &&
        !_runtimeRunHasContinuationIntent(left)) {
      return right;
    }
    return left;
  }
  final int leftVersion = _runtimeRunDetailEpochMs(left);
  final int rightVersion = _runtimeRunDetailEpochMs(right);
  if (rightVersion != leftVersion) {
    return rightVersion > leftVersion ? right : left;
  }
  final int leftWeight = _runtimeRunDetailWeight(left);
  final int rightWeight = _runtimeRunDetailWeight(right);
  if (rightWeight != leftWeight) {
    return rightWeight > leftWeight ? right : left;
  }
  return right.updatedAtEpochMs >= left.updatedAtEpochMs ? right : left;
}

OpenCrayChatRunSnapshot _mergeRuntimeRunSnapshots(
  OpenCrayChatRunSnapshot left,
  OpenCrayChatRunSnapshot right,
) {
  final OpenCrayChatRunSnapshot preferred = _preferRuntimeRunSnapshot(
    left,
    right,
  );
  final OpenCrayChatRunSnapshot supplement = identical(preferred, left)
      ? right
      : left;
  if (!_runtimeRunsReferToSameRun(preferred, supplement)) {
    return preferred;
  }
  final bool clearsTerminalState =
      _runtimeRunIsNonTerminalContinuation(preferred) && supplement.isTerminal;
  final List<OpenCrayChatManagedProcessSnapshot> managedProcesses =
      clearsTerminalState
      ? preferred.managedProcesses
      : _mergeRuntimeManagedProcesses(
          preferred.managedProcesses,
          supplement.managedProcesses,
        );
  final List<String> managedProcessIds = clearsTerminalState
      ? _mergeRuntimeStringList(<String>[
          ...preferred.managedProcessIds,
          for (final process in preferred.managedProcesses) process.processId,
        ], const <String>[])
      : _mergeRuntimeStringList(
          <String>[
            ...preferred.managedProcessIds,
            for (final process in preferred.managedProcesses) process.processId,
          ],
          <String>[
            ...supplement.managedProcessIds,
            for (final process in supplement.managedProcesses)
              process.processId,
          ],
        );
  return OpenCrayChatRunSnapshot(
    sessionId: _preferNonEmpty(preferred.sessionId, supplement.sessionId),
    runId: _preferNonEmpty(preferred.runId, supplement.runId),
    taskId: _preferNonEmpty(preferred.taskId, supplement.taskId),
    acceptedAtEpochMs: preferred.acceptedAtEpochMs != 0
        ? preferred.acceptedAtEpochMs
        : supplement.acceptedAtEpochMs,
    updatedAtEpochMs: math.max(
      preferred.updatedAtEpochMs,
      supplement.updatedAtEpochMs,
    ),
    attempt: preferred.attempt != 0 ? preferred.attempt : supplement.attempt,
    isTerminal: preferred.isTerminal,
    executionOrdinal: clearsTerminalState
        ? preferred.executionOrdinal
        : preferred.executionOrdinal != 0
        ? preferred.executionOrdinal
        : supplement.executionOrdinal,
    executionId: clearsTerminalState
        ? preferred.executionId
        : _preferNonEmptyNullable(
            preferred.executionId,
            supplement.executionId,
          ),
    executionKind: clearsTerminalState
        ? preferred.executionKind
        : _preferNonEmptyNullable(
            preferred.executionKind,
            supplement.executionKind,
          ),
    pendingExecutionKind: _preferNonEmptyNullable(
      preferred.pendingExecutionKind,
      supplement.pendingExecutionKind,
    ),
    lifecycleState: _preferNonEmptyNullable(
      preferred.lifecycleState,
      supplement.lifecycleState,
    ),
    taskState: _preferNonEmptyNullable(
      preferred.taskState,
      supplement.taskState,
    ),
    executionStatus: clearsTerminalState
        ? preferred.executionStatus
        : _preferNonEmptyNullable(
            preferred.executionStatus,
            supplement.executionStatus,
          ),
    errorCode: clearsTerminalState
        ? preferred.errorCode
        : _preferNonEmptyNullable(preferred.errorCode, supplement.errorCode),
    errorMessage: clearsTerminalState
        ? preferred.errorMessage
        : _preferNonEmptyNullable(
            preferred.errorMessage,
            supplement.errorMessage,
          ),
    responseFormat: clearsTerminalState
        ? preferred.responseFormat
        : _preferNonEmptyNullable(
            preferred.responseFormat,
            supplement.responseFormat,
          ),
    pendingMessageId: _preferNonEmptyNullable(
      preferred.pendingMessageId,
      supplement.pendingMessageId,
    ),
    finalAttachments: clearsTerminalState
        ? preferred.finalAttachments
        : _mergeRuntimeAttachments(
            preferred.finalAttachments,
            supplement.finalAttachments,
          ),
    managedProcessIds: managedProcessIds,
    managedProcesses: managedProcesses,
    runningManagedProcessCount: preferred.runningManagedProcessCount,
    hasLiveManagedProcesses: preferred.hasLiveManagedProcesses,
    lastEvent: clearsTerminalState
        ? preferred.lastEvent
        : _preferRuntimeRunLastEvent(preferred.lastEvent, supplement.lastEvent),
    llmDiagnostics: clearsTerminalState
        ? preferred.llmDiagnostics
        : preferred.llmDiagnostics ?? supplement.llmDiagnostics,
    liveContext: clearsTerminalState
        ? preferred.liveContext
        : preferred.liveContext ?? supplement.liveContext,
    contextBudget: clearsTerminalState
        ? preferred.contextBudget
        : preferred.contextBudget ?? supplement.contextBudget,
    memoryTrace: clearsTerminalState
        ? preferred.memoryTrace
        : preferred.memoryTrace ?? supplement.memoryTrace,
    memoryFlush: clearsTerminalState
        ? preferred.memoryFlush
        : preferred.memoryFlush ?? supplement.memoryFlush,
    bootstrap: clearsTerminalState
        ? preferred.bootstrap
        : preferred.bootstrap ?? supplement.bootstrap,
    durableCompaction: clearsTerminalState
        ? preferred.durableCompaction
        : preferred.durableCompaction ?? supplement.durableCompaction,
    skillInventory: clearsTerminalState
        ? preferred.skillInventory
        : preferred.skillInventory ?? supplement.skillInventory,
    activeSkill: clearsTerminalState
        ? preferred.activeSkill
        : preferred.activeSkill ?? supplement.activeSkill,
    diagnostics: clearsTerminalState
        ? preferred.diagnostics
        : preferred.diagnostics ?? supplement.diagnostics,
    recoveryPlan: clearsTerminalState
        ? preferred.recoveryPlan
        : preferred.recoveryPlan ?? supplement.recoveryPlan,
  );
}

String _preferNonEmpty(String preferred, String fallback) =>
    preferred.trim().isNotEmpty ? preferred : fallback;

String? _preferNonEmptyNullable(String? preferred, String? fallback) =>
    preferred?.trim().isNotEmpty == true ? preferred : fallback;

List<String> _mergeRuntimeStringList(
  List<String> preferred,
  List<String> supplement,
) {
  final Set<String> seen = <String>{};
  final List<String> merged = <String>[];
  for (final value in <String>[...preferred, ...supplement]) {
    final String trimmed = value.trim();
    if (trimmed.isEmpty || !seen.add(trimmed)) {
      continue;
    }
    merged.add(value);
  }
  return merged;
}

OpenCrayChatRuntimeEventSnapshot? _preferRuntimeRunLastEvent(
  OpenCrayChatRuntimeEventSnapshot? preferred,
  OpenCrayChatRuntimeEventSnapshot? supplement,
) {
  if (preferred == null || supplement == null) {
    return preferred ?? supplement;
  }
  return preferred.emittedAtEpochMs >= supplement.emittedAtEpochMs
      ? preferred
      : supplement;
}

List<OpenCrayChatManagedProcessSnapshot> _mergeRuntimeManagedProcesses(
  List<OpenCrayChatManagedProcessSnapshot> preferred,
  List<OpenCrayChatManagedProcessSnapshot> supplement,
) {
  final Map<String, OpenCrayChatManagedProcessSnapshot> byKey =
      <String, OpenCrayChatManagedProcessSnapshot>{};
  for (final process in <OpenCrayChatManagedProcessSnapshot>[
    ...supplement,
    ...preferred,
  ]) {
    final String key = _runtimeManagedProcessMergeKey(process);
    final OpenCrayChatManagedProcessSnapshot? existing = byKey[key];
    if (existing == null) {
      byKey[key] = process;
      continue;
    }
    byKey[key] = _preferRuntimeManagedProcessSnapshot(existing, process);
  }
  return byKey.values.toList(growable: false)..sort((left, right) {
    final int leftEpoch = math.max(
      left.startedAtEpochMs,
      left.updatedAtEpochMs,
    );
    final int rightEpoch = math.max(
      right.startedAtEpochMs,
      right.updatedAtEpochMs,
    );
    if (leftEpoch != rightEpoch) {
      return leftEpoch.compareTo(rightEpoch);
    }
    return left.processId.compareTo(right.processId);
  });
}

String _runtimeManagedProcessMergeKey(
  OpenCrayChatManagedProcessSnapshot process,
) {
  final String processId = process.processId.trim();
  if (processId.isNotEmpty) {
    return 'process:$processId';
  }
  return <String>[
    process.command,
    process.args.join('\u0001'),
    process.startedAtEpochMs.toString(),
  ].join('\u0001');
}

OpenCrayChatManagedProcessSnapshot _preferRuntimeManagedProcessSnapshot(
  OpenCrayChatManagedProcessSnapshot left,
  OpenCrayChatManagedProcessSnapshot right,
) {
  final int leftEpoch = _runtimeManagedProcessDetailEpochMs(left);
  final int rightEpoch = _runtimeManagedProcessDetailEpochMs(right);
  if (rightEpoch != leftEpoch) {
    return rightEpoch > leftEpoch ? right : left;
  }
  final int leftWeight = _runtimeManagedProcessDetailWeight(left);
  final int rightWeight = _runtimeManagedProcessDetailWeight(right);
  if (rightWeight != leftWeight) {
    return rightWeight > leftWeight ? right : left;
  }
  return right;
}

int _runtimeManagedProcessDetailEpochMs(
  OpenCrayChatManagedProcessSnapshot process,
) => math.max(
  process.updatedAtEpochMs,
  math.max(process.startedAtEpochMs, process.finishedAtEpochMs ?? 0),
);

List<OpenCrayChatAttachmentSnapshot> _mergeRuntimeAttachments(
  List<OpenCrayChatAttachmentSnapshot> preferred,
  List<OpenCrayChatAttachmentSnapshot> supplement,
) {
  final Map<String, OpenCrayChatAttachmentSnapshot> byKey =
      <String, OpenCrayChatAttachmentSnapshot>{};
  for (final attachment in <OpenCrayChatAttachmentSnapshot>[
    ...preferred,
    ...supplement,
  ]) {
    final String key = _runtimeAttachmentMergeKey(attachment);
    final OpenCrayChatAttachmentSnapshot? existing = byKey[key];
    if (existing == null ||
        _runtimeAttachmentDetailWeight(attachment) >
            _runtimeAttachmentDetailWeight(existing)) {
      byKey[key] = attachment;
    }
  }
  return byKey.values.toList(growable: false);
}

String _runtimeAttachmentMergeKey(OpenCrayChatAttachmentSnapshot attachment) {
  final String attachmentId = attachment.attachmentId.trim();
  if (attachmentId.isNotEmpty) {
    return 'attachment:$attachmentId';
  }
  final String localPath = attachment.localPath.trim();
  if (localPath.isNotEmpty) {
    return 'path:$localPath';
  }
  return <String>[attachment.kind, attachment.displayName].join('\u0001');
}

int _runtimeAttachmentDetailWeight(OpenCrayChatAttachmentSnapshot attachment) =>
    attachment.displayName.length +
    attachment.localPath.length +
    (attachment.mimeType?.length ?? 0) +
    (attachment.sizeBytes == null ? 0 : 4) +
    (attachment.widthPx == null ? 0 : 4) +
    (attachment.heightPx == null ? 0 : 4) +
    (attachment.durationMs == null ? 0 : 4) +
    attachment.waveformBars.length +
    (attachment.transcriptText?.length ?? 0) +
    (attachment.contentSha256?.length ?? 0);

bool _isRuntimeRunActiveForProjection(OpenCrayChatRunSnapshot run) =>
    !run.isTerminal ||
    run.hasLiveManagedProcesses ||
    run.runningManagedProcessCount > 0;

List<OpenCrayChatRuntimeEventSnapshot> _mergeRuntimeEvents(
  List<OpenCrayChatRuntimeEventSnapshot> left,
  List<OpenCrayChatRuntimeEventSnapshot> right,
) {
  final Map<String, OpenCrayChatRuntimeEventSnapshot> byKey =
      <String, OpenCrayChatRuntimeEventSnapshot>{};
  for (final event in <OpenCrayChatRuntimeEventSnapshot>[...left, ...right]) {
    final String key = _runtimeEventMergeKey(event);
    final OpenCrayChatRuntimeEventSnapshot? existing = byKey[key];
    if (existing == null) {
      byKey[key] = event;
      continue;
    }
    byKey[key] = _preferRuntimeEventSnapshot(existing, event);
  }
  return byKey.values.toList(growable: false)..sort(
    (leftEvent, rightEvent) =>
        leftEvent.emittedAtEpochMs.compareTo(rightEvent.emittedAtEpochMs),
  );
}

OpenCrayChatRuntimeEventSnapshot _preferRuntimeEventSnapshot(
  OpenCrayChatRuntimeEventSnapshot left,
  OpenCrayChatRuntimeEventSnapshot right,
) {
  if (right.emittedAtEpochMs != left.emittedAtEpochMs) {
    return right.emittedAtEpochMs > left.emittedAtEpochMs ? right : left;
  }
  final int leftWeight = _runtimeEventDetailWeight(left);
  final int rightWeight = _runtimeEventDetailWeight(right);
  if (rightWeight != leftWeight) {
    return rightWeight > leftWeight ? right : left;
  }
  return right;
}

int _runtimeEventDetailWeight(OpenCrayChatRuntimeEventSnapshot event) =>
    (event.executionId?.length ?? 0) +
    (event.executionOrdinal == null ? 0 : 4) +
    (event.executionKind?.length ?? 0) +
    (event.entryId?.length ?? 0) +
    (event.checkpoint?.length ?? 0) +
    (event.turn == null ? 0 : 4) +
    (event.phase?.length ?? 0) +
    (event.status?.length ?? 0) +
    (event.errorCode?.length ?? 0) +
    (event.errorMessage?.length ?? 0) +
    (event.responseFormat?.length ?? 0) +
    (event.isFinal == null ? 0 : 1) +
    (event.text?.length ?? 0) +
    (event.stage?.length ?? 0) +
    (event.toolName?.length ?? 0) +
    (event.isHighRisk ? 1 : 0) +
    (event.label?.length ?? 0) +
    (event.childRunId?.length ?? 0) +
    (event.childTaskId?.length ?? 0) +
    (event.subagentType?.length ?? 0) +
    (event.contextMode?.length ?? 0) +
    (event.depth == null ? 0 : 4) +
    (event.executionState?.length ?? 0) +
    (event.continuationKind?.length ?? 0) +
    (event.toolReason?.length ?? 0) +
    (event.argumentsJson?.length ?? 0) +
    (event.toolStatus?.length ?? 0) +
    (event.content?.length ?? 0) +
    (event.contentPreview?.length ?? 0) +
    event.resultMetadata.entries.fold<int>(
      0,
      (total, entry) => total + entry.key.length + entry.value.length,
    ) +
    (event.operation?.length ?? 0) +
    (event.query?.length ?? 0) +
    event.queryTerms.fold<int>(0, (total, value) => total + value.length) +
    (event.resultCount == null ? 0 : 4) +
    (event.corpusFileCount == null ? 0 : 4) +
    event.recordIds.fold<int>(0, (total, value) => total + value.length) +
    event.writtenRecordIds.fold<int>(
      0,
      (total, value) => total + value.length,
    ) +
    event.writtenKinds.fold<int>(0, (total, value) => total + value.length) +
    event.resolvedRecordIds.fold<int>(
      0,
      (total, value) => total + value.length,
    ) +
    event.suppressedRecordIds.fold<int>(
      0,
      (total, value) => total + value.length,
    ) +
    event.reaffirmedRecordIds.fold<int>(
      0,
      (total, value) => total + value.length,
    ) +
    event.expiredRecordIds.fold<int>(
      0,
      (total, value) => total + value.length,
    ) +
    event.paths.fold<int>(0, (total, value) => total + value.length) +
    event.lineRanges.fold<int>(0, (total, value) => total + value.length) +
    (event.path?.length ?? 0) +
    (event.fromLine == null ? 0 : 4) +
    (event.returnedLineCount == null ? 0 : 4) +
    (event.totalLineCount == null ? 0 : 4);

String _runtimeEventMergeKey(OpenCrayChatRuntimeEventSnapshot event) =>
    <String>[
      event.kind,
      event.runId,
      event.taskId,
      event.executionId ?? '',
      event.executionOrdinal?.toString() ?? '',
      event.executionKind ?? '',
      event.emittedAtEpochMs.toString(),
      event.phase ?? '',
      event.stage ?? '',
      event.toolName ?? '',
      event.childRunId ?? '',
      event.childTaskId ?? '',
      event.entryId ?? '',
      event.text ?? '',
    ].join('\u0001');

List<OpenCrayChatSubAgentSnapshot> _mergeRuntimeSubAgents(
  List<OpenCrayChatSubAgentSnapshot> left,
  List<OpenCrayChatSubAgentSnapshot> right,
) {
  final Map<String, OpenCrayChatSubAgentSnapshot> byKey =
      <String, OpenCrayChatSubAgentSnapshot>{};
  for (final subAgent in <OpenCrayChatSubAgentSnapshot>[...left, ...right]) {
    final String key = _runtimeSubAgentMergeKey(subAgent);
    final OpenCrayChatSubAgentSnapshot? existing = byKey[key];
    if (existing == null ||
        subAgent.updatedAtEpochMs >= existing.updatedAtEpochMs) {
      byKey[key] = subAgent;
    }
  }
  return byKey.values.toList(growable: false)..sort(
    (leftSubAgent, rightSubAgent) =>
        leftSubAgent.updatedAtEpochMs.compareTo(rightSubAgent.updatedAtEpochMs),
  );
}

String _runtimeSubAgentMergeKey(OpenCrayChatSubAgentSnapshot subAgent) {
  final String childRunId = subAgent.childRunId.trim();
  if (childRunId.isNotEmpty) {
    return 'run:$childRunId';
  }
  final String childTaskId = subAgent.childTaskId.trim();
  if (childTaskId.isNotEmpty) {
    return 'task:$childTaskId';
  }
  return <String>[
    subAgent.parentRunId,
    subAgent.parentTaskId,
    subAgent.label,
    subAgent.subagentType,
    subAgent.depth.toString(),
  ].join('\u0001');
}

List<OpenCrayChatLiveAssistantDraftSnapshot> _mergeRuntimeDrafts(
  List<OpenCrayChatLiveAssistantDraftSnapshot> left,
  List<OpenCrayChatLiveAssistantDraftSnapshot> right,
) {
  final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> byMessageId =
      <String, OpenCrayChatLiveAssistantDraftSnapshot>{};
  for (final draft in <OpenCrayChatLiveAssistantDraftSnapshot>[
    ...left,
    ...right,
  ]) {
    final String pendingMessageId = draft.pendingMessageId.trim();
    if (pendingMessageId.isEmpty) {
      continue;
    }
    final OpenCrayChatLiveAssistantDraftSnapshot? existing =
        byMessageId[pendingMessageId];
    if (existing == null ||
        draft.updatedAtEpochMs >= existing.updatedAtEpochMs) {
      byMessageId[pendingMessageId] = draft;
    }
  }
  return byMessageId.values.toList(growable: false)..sort(
    (leftDraft, rightDraft) =>
        leftDraft.updatedAtEpochMs.compareTo(rightDraft.updatedAtEpochMs),
  );
}

OpenCrayHostLifecycleSnapshot? _preferHostLifecycle(
  OpenCrayHostLifecycleSnapshot? left,
  OpenCrayHostLifecycleSnapshot? right,
) {
  if (left == null || right == null) {
    return right ?? left;
  }
  final int leftEpoch = left.hostCreatedAtEpochMs ?? 0;
  final int rightEpoch = right.hostCreatedAtEpochMs ?? 0;
  if (rightEpoch != leftEpoch) {
    return rightEpoch > leftEpoch ? right : left;
  }
  final String rightHostInstanceId = right.hostInstanceId?.trim() ?? '';
  if (rightHostInstanceId.isNotEmpty &&
      rightHostInstanceId != (left.hostInstanceId?.trim() ?? '')) {
    return right;
  }
  return right;
}

@visibleForTesting
int runtimeSnapshotVersion(OpenCrayChatRuntimeSnapshot snapshot) {
  final int latestHostEpochMs =
      snapshot.hostLifecycle?.hostCreatedAtEpochMs ?? 0;
  return math.max(
    snapshot.updatedAtEpochMs,
    math.max(
      latestHostEpochMs,
      math.max(
        _runtimeOperationalVersion(snapshot),
        _latestRuntimeDraftEpochMs(snapshot),
      ),
    ),
  );
}

int _runtimeOperationalVersion(OpenCrayChatRuntimeSnapshot snapshot) {
  return math.max(
    _latestRuntimeEventEpochMs(snapshot),
    math.max(
      _latestRuntimeRunEpochMs(snapshot),
      _latestRuntimeSubAgentEpochMs(snapshot),
    ),
  );
}

int _latestRuntimeEventEpochMs(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.events.fold<int>(
      0,
      (latest, event) =>
          latest > event.emittedAtEpochMs ? latest : event.emittedAtEpochMs,
    );

int _latestRuntimeRunEpochMs(OpenCrayChatRuntimeSnapshot snapshot) =>
    _visibleRuns(snapshot).fold<int>(0, (latest, run) {
      final int runEpochMs = _runtimeRunDetailEpochMs(run);
      return latest > runEpochMs ? latest : runEpochMs;
    });

int _latestRuntimeSubAgentEpochMs(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.subAgents.fold<int>(
      0,
      (latest, subAgent) => latest > subAgent.updatedAtEpochMs
          ? latest
          : subAgent.updatedAtEpochMs,
    );

int _latestRuntimeDraftEpochMs(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.liveAssistantDrafts.fold<int>(
      0,
      (latest, draft) =>
          latest > draft.updatedAtEpochMs ? latest : draft.updatedAtEpochMs,
    );

int _runtimeRunDetailEpochMs(OpenCrayChatRunSnapshot run) {
  final int latestManagedProcessEpochMs = run.managedProcesses.fold<int>(0, (
    latest,
    process,
  ) {
    final int processEpochMs = math.max(
      process.updatedAtEpochMs,
      process.startedAtEpochMs,
    );
    return latest > processEpochMs ? latest : processEpochMs;
  });
  return math.max(
    run.updatedAtEpochMs,
    math.max(run.lastEvent?.emittedAtEpochMs ?? 0, latestManagedProcessEpochMs),
  );
}

int _runtimeDetailWeight(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.events.length * 100000 +
    snapshot.subAgents.length * 10000 +
    _visibleRuns(
      snapshot,
    ).fold<int>(0, (total, run) => total + _runtimeRunDetailWeight(run)) +
    snapshot.liveAssistantDrafts.length;

int _runtimeRunDetailWeight(OpenCrayChatRunSnapshot run) =>
    1 +
    (run.isTerminal ? 20000 : 0) +
    (run.lastEvent == null ? 0 : 500) +
    run.finalAttachments.length * 250 +
    run.managedProcessIds.length * 100 +
    run.managedProcesses.fold<int>(
      0,
      (total, process) => total + _runtimeManagedProcessDetailWeight(process),
    );

int _runtimeManagedProcessDetailWeight(
  OpenCrayChatManagedProcessSnapshot process,
) {
  final bool terminal = process.status.trim().toLowerCase() != 'running';
  return 1000 +
      (process.processStarted ? 100 : 0) +
      (terminal ? 200 : 0) +
      process.stdout.length +
      process.stderr.length +
      process.stdoutPreview.length +
      process.stderrPreview.length +
      (process.errorMessage?.length ?? 0);
}

@visibleForTesting
int chatSnapshotVersion(OpenCrayChatSnapshot snapshot) {
  final int contentVersion = chatContentSnapshotVersion(snapshot);
  final int runtimeVersion = snapshot.runtimeActivity == null
      ? 0
      : runtimeSnapshotVersion(snapshot.runtimeActivity!);
  return math.max(contentVersion, runtimeVersion);
}

@visibleForTesting
int chatContentSnapshotVersion(OpenCrayChatSnapshot snapshot) {
  final int latestMessageEpochMs = snapshot.messages.fold<int>(0, (
    latest,
    message,
  ) {
    final int messageEpochMs = message.createdAtEpochMs ?? 0;
    return latest > messageEpochMs ? latest : messageEpochMs;
  });
  return math.max(snapshot.updatedAtEpochMs, latestMessageEpochMs);
}

@visibleForTesting
bool shouldReplaceObservedChatSnapshot(
  OpenCrayChatSnapshot? current,
  OpenCrayChatSnapshot incoming,
) {
  if (current == null) {
    return true;
  }
  final bool incomingIsChatOnly = incoming.runtimeActivity == null;
  final int currentVersion = incomingIsChatOnly
      ? chatContentSnapshotVersion(current)
      : chatSnapshotVersion(current);
  final int incomingVersion = incomingIsChatOnly
      ? chatContentSnapshotVersion(incoming)
      : chatSnapshotVersion(incoming);
  if (incomingVersion != currentVersion) {
    return incomingVersion > currentVersion;
  }
  final int currentRuntimeVersion = current.runtimeActivity == null
      ? 0
      : runtimeSnapshotVersion(current.runtimeActivity!);
  final int incomingRuntimeVersion = incoming.runtimeActivity == null
      ? 0
      : runtimeSnapshotVersion(incoming.runtimeActivity!);
  if (incomingRuntimeVersion != currentRuntimeVersion) {
    return incomingRuntimeVersion > currentRuntimeVersion;
  }
  if (incoming.runtimeActivity != null &&
      shouldReplaceObservedRuntimeSnapshot(
        current.runtimeActivity,
        incoming.runtimeActivity!,
      )) {
    return true;
  }
  if (incoming.messages.length != current.messages.length) {
    if (incoming.messages.length > current.messages.length) {
      return true;
    }
    return incoming.updatedAtEpochMs > current.updatedAtEpochMs;
  }
  if (incoming.pendingApprovals.length != current.pendingApprovals.length) {
    return incoming.pendingApprovals.length > current.pendingApprovals.length;
  }
  if (incoming.todos.length != current.todos.length) {
    return incoming.todos.length > current.todos.length;
  }
  return false;
}

@visibleForTesting
bool shouldReplaceObservedRuntimeSnapshot(
  OpenCrayChatRuntimeSnapshot? current,
  OpenCrayChatRuntimeSnapshot incoming,
) {
  if (current == null) {
    return true;
  }
  final String currentSessionId = current.sessionId.trim();
  final String incomingSessionId = incoming.sessionId.trim();
  if (currentSessionId.isNotEmpty &&
      incomingSessionId.isNotEmpty &&
      currentSessionId != incomingSessionId) {
    return true;
  }
  final OpenCrayChatRuntimeSnapshot candidate =
      resolveChatRuntimeSnapshot(current, incoming) ?? incoming;
  final int currentVersion = runtimeSnapshotVersion(current);
  final int candidateVersion = runtimeSnapshotVersion(candidate);
  if (candidateVersion != currentVersion) {
    return candidateVersion > currentVersion;
  }
  final int currentOperationalVersion = _runtimeOperationalVersion(current);
  final int candidateOperationalVersion = _runtimeOperationalVersion(candidate);
  if (candidateOperationalVersion != currentOperationalVersion) {
    return candidateOperationalVersion > currentOperationalVersion;
  }
  if (_runtimeSnapshotContinuesTerminalRun(candidate, current)) {
    return true;
  }
  if (_runtimeSnapshotTerminalizesRun(candidate, current)) {
    return true;
  }
  if (_runtimeSnapshotTerminalizesRun(current, candidate)) {
    return false;
  }
  final int currentDetailWeight = _runtimeDetailWeight(current);
  final int candidateDetailWeight = _runtimeDetailWeight(candidate);
  if (candidateDetailWeight != currentDetailWeight) {
    return candidateDetailWeight > currentDetailWeight;
  }
  final int currentDraftVersion = _latestRuntimeDraftEpochMs(current);
  final int candidateDraftVersion = _latestRuntimeDraftEpochMs(candidate);
  if (candidateDraftVersion != currentDraftVersion) {
    return candidateDraftVersion > currentDraftVersion;
  }
  final int currentVisibleRuns = _visibleRunCount(current);
  final int candidateVisibleRuns = _visibleRunCount(candidate);
  if (candidateVisibleRuns != currentVisibleRuns) {
    return candidateVisibleRuns > currentVisibleRuns;
  }
  final String currentHostInstanceId = _hostInstanceId(current);
  final String candidateHostInstanceId = _hostInstanceId(candidate);
  if (currentHostInstanceId != candidateHostInstanceId &&
      candidateHostInstanceId.isNotEmpty) {
    return true;
  }
  return _runtimeSnapshotDisplaySignature(candidate) !=
      _runtimeSnapshotDisplaySignature(current);
}

bool _runtimeSnapshotContinuesTerminalRun(
  OpenCrayChatRuntimeSnapshot candidate,
  OpenCrayChatRuntimeSnapshot current,
) {
  for (final candidateRun in _visibleRuns(candidate)) {
    if (!_runtimeRunIsNonTerminalContinuation(candidateRun)) {
      continue;
    }
    final OpenCrayChatRunSnapshot? currentRun = _findRuntimeRun(
      _visibleRuns(current),
      candidateRun,
    );
    if (currentRun != null &&
        currentRun.isTerminal &&
        !_runtimeRunHasContinuationIntent(currentRun)) {
      return true;
    }
  }
  return false;
}

bool _runtimeSnapshotTerminalizesRun(
  OpenCrayChatRuntimeSnapshot candidate,
  OpenCrayChatRuntimeSnapshot current,
) {
  for (final candidateRun in _visibleRuns(candidate)) {
    if (!candidateRun.isTerminal) {
      continue;
    }
    final OpenCrayChatRunSnapshot? currentRun = _findRuntimeRun(
      _visibleRuns(current),
      candidateRun,
    );
    if (currentRun != null &&
        !currentRun.isTerminal &&
        candidateRun.updatedAtEpochMs >= currentRun.updatedAtEpochMs) {
      return true;
    }
  }
  return false;
}

OpenCrayChatRunSnapshot? _findRuntimeRun(
  List<OpenCrayChatRunSnapshot> runs,
  OpenCrayChatRunSnapshot target,
) {
  for (final run in runs) {
    if (_runtimeRunsReferToSameRun(run, target)) {
      return run;
    }
  }
  return null;
}

List<OpenCrayChatRunSnapshot> _visibleRuns(
  OpenCrayChatRuntimeSnapshot snapshot,
) => <OpenCrayChatRunSnapshot>[
  ...snapshot.activeRuns,
  ...snapshot.retainedRuns,
];

int _visibleRunCount(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.activeRuns.length + snapshot.retainedRuns.length;

String _hostInstanceId(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.hostLifecycle?.hostInstanceId?.trim() ?? '';

String _runtimeSnapshotDisplaySignature(OpenCrayChatRuntimeSnapshot snapshot) {
  return jsonEncode(<String, Object?>{
    'sessionId': snapshot.sessionId,
    'activeRuns': snapshot.activeRuns
        .map(_runtimeRunDisplaySignature)
        .toList(growable: false),
    'retainedRuns': snapshot.retainedRuns
        .map(_runtimeRunDisplaySignature)
        .toList(growable: false),
    'subAgents': snapshot.subAgents
        .map(_runtimeSubAgentDisplaySignature)
        .toList(growable: false),
    'events': snapshot.events
        .map(_runtimeEventDisplaySignature)
        .toList(growable: false),
    'liveAssistantDrafts': snapshot.liveAssistantDrafts
        .map(_runtimeDraftDisplaySignature)
        .toList(growable: false),
    'hostLifecycle': _runtimeHostLifecycleDisplaySignature(
      snapshot.hostLifecycle,
    ),
  });
}

Map<String, Object?> _runtimeRunDisplaySignature(OpenCrayChatRunSnapshot run) {
  return <String, Object?>{
    'sessionId': run.sessionId,
    'runId': run.runId,
    'taskId': run.taskId,
    'acceptedAtEpochMs': run.acceptedAtEpochMs,
    'updatedAtEpochMs': run.updatedAtEpochMs,
    'lifecycleState': run.lifecycleState,
    'taskState': run.taskState,
    'attempt': run.attempt,
    'executionOrdinal': run.executionOrdinal,
    'executionId': run.executionId,
    'executionKind': run.executionKind,
    'pendingExecutionKind': run.pendingExecutionKind,
    'executionStatus': run.executionStatus,
    'errorCode': run.errorCode,
    'errorMessage': run.errorMessage,
    'responseFormat': run.responseFormat,
    'pendingMessageId': run.pendingMessageId,
    'finalAttachments': run.finalAttachments
        .map(_runtimeAttachmentDisplaySignature)
        .toList(growable: false),
    'managedProcessIds': run.managedProcessIds,
    'managedProcesses': run.managedProcesses
        .map(_runtimeManagedProcessDisplaySignature)
        .toList(growable: false),
    'runningManagedProcessCount': run.runningManagedProcessCount,
    'hasLiveManagedProcesses': run.hasLiveManagedProcesses,
    'isTerminal': run.isTerminal,
    'lastEvent': run.lastEvent == null
        ? null
        : _runtimeEventDisplaySignature(run.lastEvent!),
  };
}

Map<String, Object?> _runtimeManagedProcessDisplaySignature(
  OpenCrayChatManagedProcessSnapshot process,
) {
  return <String, Object?>{
    'processId': process.processId,
    'status': process.status,
    'command': process.command,
    'args': process.args,
    'workingDirectory': process.workingDirectory,
    'processStarted': process.processStarted,
    'timeoutMs': process.timeoutMs,
    'startedAtEpochMs': process.startedAtEpochMs,
    'updatedAtEpochMs': process.updatedAtEpochMs,
    'finishedAtEpochMs': process.finishedAtEpochMs,
    'exitCode': process.exitCode,
    'errorCode': process.errorCode,
    'errorMessage': process.errorMessage,
    'timedOut': process.timedOut,
    'cancelled': process.cancelled,
    'outputLimitExceeded': process.outputLimitExceeded,
    'stdout': process.stdout,
    'stderr': process.stderr,
    'stdoutPreview': process.stdoutPreview,
    'stderrPreview': process.stderrPreview,
    'stdoutTruncated': process.stdoutTruncated,
    'stderrTruncated': process.stderrTruncated,
  };
}

Map<String, Object?> _runtimeEventDisplaySignature(
  OpenCrayChatRuntimeEventSnapshot event,
) {
  return <String, Object?>{
    'kind': event.kind,
    'runId': event.runId,
    'taskId': event.taskId,
    'emittedAtEpochMs': event.emittedAtEpochMs,
    'executionId': event.executionId,
    'executionOrdinal': event.executionOrdinal,
    'executionKind': event.executionKind,
    'entryId': event.entryId,
    'checkpoint': event.checkpoint,
    'turn': event.turn,
    'phase': event.phase,
    'status': event.status,
    'errorCode': event.errorCode,
    'errorMessage': event.errorMessage,
    'responseFormat': event.responseFormat,
    'isFinal': event.isFinal,
    'text': event.text,
    'stage': event.stage,
    'toolName': event.toolName,
    'isHighRisk': event.isHighRisk,
    'label': event.label,
    'childRunId': event.childRunId,
    'childTaskId': event.childTaskId,
    'subagentType': event.subagentType,
    'contextMode': event.contextMode,
    'depth': event.depth,
    'executionState': event.executionState,
    'continuationKind': event.continuationKind,
    'toolReason': event.toolReason,
    'argumentsJson': event.argumentsJson,
    'toolStatus': event.toolStatus,
    'content': event.content,
    'contentPreview': event.contentPreview,
    'resultMetadata': event.resultMetadata,
    'operation': event.operation,
    'query': event.query,
    'queryTerms': event.queryTerms,
    'resultCount': event.resultCount,
    'corpusFileCount': event.corpusFileCount,
    'recordIds': event.recordIds,
    'writtenRecordIds': event.writtenRecordIds,
    'writtenKinds': event.writtenKinds,
    'resolvedRecordIds': event.resolvedRecordIds,
    'suppressedRecordIds': event.suppressedRecordIds,
    'reaffirmedRecordIds': event.reaffirmedRecordIds,
    'expiredRecordIds': event.expiredRecordIds,
    'paths': event.paths,
    'lineRanges': event.lineRanges,
    'path': event.path,
    'fromLine': event.fromLine,
    'returnedLineCount': event.returnedLineCount,
    'totalLineCount': event.totalLineCount,
  };
}

Map<String, Object?> _runtimeAttachmentDisplaySignature(
  OpenCrayChatAttachmentSnapshot attachment,
) {
  return <String, Object?>{
    'attachmentId': attachment.attachmentId,
    'kind': attachment.kind,
    'displayName': attachment.displayName,
    'localPath': attachment.localPath,
    'mimeType': attachment.mimeType,
    'sizeBytes': attachment.sizeBytes,
    'widthPx': attachment.widthPx,
    'heightPx': attachment.heightPx,
    'durationMs': attachment.durationMs,
    'waveformBars': attachment.waveformBars,
    'transcriptText': attachment.transcriptText,
    'contentSha256': attachment.contentSha256,
  };
}

Map<String, Object?> _runtimeSubAgentDisplaySignature(
  OpenCrayChatSubAgentSnapshot subAgent,
) {
  return <String, Object?>{
    'parentRunId': subAgent.parentRunId,
    'parentTaskId': subAgent.parentTaskId,
    'childRunId': subAgent.childRunId,
    'childTaskId': subAgent.childTaskId,
    'label': subAgent.label,
    'subagentType': subAgent.subagentType,
    'contextMode': subAgent.contextMode,
    'depth': subAgent.depth,
    'phase': subAgent.phase,
    'status': subAgent.status,
    'executionState': subAgent.executionState,
    'continuationKind': subAgent.continuationKind,
    'resumable': subAgent.resumable,
    'requiresUserAction': subAgent.requiresUserAction,
    'isHighRisk': subAgent.isHighRisk,
    'summary': subAgent.summary,
    'startedAtEpochMs': subAgent.startedAtEpochMs,
    'updatedAtEpochMs': subAgent.updatedAtEpochMs,
    'eventCount': subAgent.eventCount,
    'mailboxMessageCount': subAgent.mailboxMessageCount,
    'mailboxPendingMessageCount': subAgent.mailboxPendingMessageCount,
    'mailboxLastDeliveredMessageId': subAgent.mailboxLastDeliveredMessageId,
  };
}

Map<String, Object?> _runtimeDraftDisplaySignature(
  OpenCrayChatLiveAssistantDraftSnapshot draft,
) {
  return <String, Object?>{
    'runId': draft.runId,
    'taskId': draft.taskId,
    'pendingMessageId': draft.pendingMessageId,
    'text': draft.text,
    'updatedAtEpochMs': draft.updatedAtEpochMs,
  };
}

Map<String, Object?>? _runtimeHostLifecycleDisplaySignature(
  OpenCrayHostLifecycleSnapshot? hostLifecycle,
) {
  if (hostLifecycle == null) {
    return null;
  }
  return <String, Object?>{
    'processStartId': hostLifecycle.processStartId,
    'processStartedAtEpochMs': hostLifecycle.processStartedAtEpochMs,
    'hostInstanceId': hostLifecycle.hostInstanceId,
    'runtimeOwnerId': hostLifecycle.runtimeOwnerId,
    'hostCreatedAtEpochMs': hostLifecycle.hostCreatedAtEpochMs,
  };
}

void _runTraceDebug(String message) {
  if (!kDebugMode) {
    return;
  }
  debugPrint('[OpenCrayDiagFlutter] $message');
}

@visibleForTesting
int javaStringHashCode(String value) {
  int hash = 0;
  for (final int codeUnit in value.codeUnits) {
    hash = (hash * 31 + codeUnit) & 0xffffffff;
  }
  if ((hash & 0x80000000) != 0) {
    hash -= 0x100000000;
  }
  return hash;
}

@visibleForTesting
bool chatFeatureStatesEquivalent(
  ChatFeatureState left,
  ChatFeatureState right,
) {
  if (identical(left, right)) {
    return true;
  }
  return left.variant == right.variant &&
      left.screenTitle == right.screenTitle &&
      _chatSessionSummariesEquivalent(left.summary, right.summary) &&
      chatMessagesEquivalent(left.messages, right.messages) &&
      _chatRunTracesEquivalent(left.runTraces, right.runTraces) &&
      _chatComposerStatesEquivalent(left.composer, right.composer) &&
      _chatDrawerStatesEquivalent(left.drawer, right.drawer) &&
      _chatPendingApprovalsEquivalent(
        left.pendingApprovals,
        right.pendingApprovals,
      ) &&
      left.modeLabel == right.modeLabel &&
      left.drawerOpen == right.drawerOpen &&
      left.sessionButtonLabel == right.sessionButtonLabel &&
      left.emptyThreadHeight == right.emptyThreadHeight &&
      left.isInputEnabled == right.isInputEnabled;
}

@visibleForTesting
bool chatMessagesEquivalent(
  List<ChatMessageData> left,
  List<ChatMessageData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftMessage, rightMessage) =>
        leftMessage.messageId == rightMessage.messageId &&
        leftMessage.kind == rightMessage.kind &&
        leftMessage.text == rightMessage.text &&
        leftMessage.meta == rightMessage.meta &&
        leftMessage.runtimeAnchorMessageId ==
            rightMessage.runtimeAnchorMessageId &&
        leftMessage.createdAtEpochMs == rightMessage.createdAtEpochMs &&
        leftMessage.isEphemeral == rightMessage.isEphemeral &&
        _listsEquivalent(
          leftMessage.attachments,
          rightMessage.attachments,
          _chatMessageAttachmentsEquivalent,
        ),
  );
}

bool _chatSessionSummariesEquivalent(
  ChatSessionSummary left,
  ChatSessionSummary right,
) {
  return left.title == right.title &&
      left.badge == right.badge &&
      left.body == right.body;
}

bool _chatRunTracesEquivalent(
  List<ChatRunTraceData> left,
  List<ChatRunTraceData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftTrace, rightTrace) =>
        leftTrace.runId == rightTrace.runId &&
        leftTrace.taskId == rightTrace.taskId &&
        leftTrace.anchorMessageId == rightTrace.anchorMessageId &&
        leftTrace.label == rightTrace.label &&
        leftTrace.body == rightTrace.body &&
        leftTrace.isHighRisk == rightTrace.isHighRisk &&
        leftTrace.isTerminal == rightTrace.isTerminal &&
        leftTrace.canInterrupt == rightTrace.canInterrupt &&
        leftTrace.isWritingAssistantDraft ==
            rightTrace.isWritingAssistantDraft &&
        leftTrace.retryLabel == rightTrace.retryLabel &&
        _chatRunTracePreviewCardsEquivalent(
          leftTrace.previewCard,
          rightTrace.previewCard,
        ) &&
        _chatRunTraceSessionCardsEquivalent(
          leftTrace.sessionCard,
          rightTrace.sessionCard,
        ) &&
        _listsEquivalent(
          leftTrace.history,
          rightTrace.history,
          _chatRunTraceHistoryEntriesEquivalent,
        ),
  );
}

bool _chatRunTracePreviewCardsEquivalent(
  ChatRunTracePreviewCardData? left,
  ChatRunTracePreviewCardData? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.url == right.url &&
      left.status == right.status &&
      left.port == right.port &&
      left.path == right.path &&
      left.provider == right.provider &&
      left.httpStatusCode == right.httpStatusCode &&
      left.message == right.message;
}

bool _chatRunTraceSessionCardsEquivalent(
  ChatRunTraceSandboxSessionCardData? left,
  ChatRunTraceSandboxSessionCardData? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.sessionPresent == right.sessionPresent &&
      left.source == right.source &&
      left.lifecycleStatus == right.lifecycleStatus &&
      left.provider == right.provider &&
      left.sandboxId == right.sandboxId &&
      left.sandboxDomain == right.sandboxDomain &&
      left.templateId == right.templateId &&
      left.updatedAtEpochMs == right.updatedAtEpochMs &&
      left.sessionLastActivityAtEpochMs == right.sessionLastActivityAtEpochMs &&
      left.sessionStaleAfterEpochMs == right.sessionStaleAfterEpochMs &&
      left.lastPreviewUrl == right.lastPreviewUrl &&
      left.lastPreviewProbeStatus == right.lastPreviewProbeStatus &&
      left.lastPreviewProbeObservedAtEpochMs ==
          right.lastPreviewProbeObservedAtEpochMs &&
      left.lastPreviewProbeSource == right.lastPreviewProbeSource &&
      left.autoRefreshAfterMs == right.autoRefreshAfterMs &&
      _listsEquivalent(
        left.previewCandidatePorts,
        right.previewCandidatePorts,
        _itemsEquivalent,
      ) &&
      _listsEquivalent(
        left.runningRequestIds,
        right.runningRequestIds,
        _itemsEquivalent,
      );
}

bool _chatRunTraceHistoryEntriesEquivalent(
  ChatRunTraceHistoryEntry left,
  ChatRunTraceHistoryEntry right,
) {
  return left.label == right.label &&
      left.body == right.body &&
      left.compactBody == right.compactBody &&
      left.isHighRisk == right.isHighRisk &&
      left.inspectorActorId == right.inspectorActorId &&
      left.inspectorActorLabel == right.inspectorActorLabel &&
      left.inspectorCallDetail == right.inspectorCallDetail &&
      left.inspectorResultBody == right.inspectorResultBody &&
      _listsEquivalent(
        left.inspectorCallParts,
        right.inspectorCallParts,
        _chatRunTraceInspectorTextPartsEquivalent,
      );
}

bool _chatRunTraceInspectorTextPartsEquivalent(
  ChatRunTraceInspectorTextPart left,
  ChatRunTraceInspectorTextPart right,
) {
  return left.text == right.text && left.semantic == right.semantic;
}

bool _chatComposerStatesEquivalent(
  ChatComposerState left,
  ChatComposerState right,
) {
  return left.placeholder == right.placeholder &&
      left.selectedCommand == right.selectedCommand &&
      left.showAddMenu == right.showAddMenu &&
      _listsEquivalent(left.todos, right.todos, _chatTodoItemsEquivalent) &&
      _listsEquivalent(
        left.attachments,
        right.attachments,
        _chatComposerAttachmentsEquivalent,
      ) &&
      _listsEquivalent(
        left.commandOptions,
        right.commandOptions,
        _chatCommandOptionsEquivalent,
      ) &&
      _listsEquivalent(left.addActions, right.addActions, _chatAddActionsEqual);
}

bool _chatTodoItemsEquivalent(ChatTodoItemData left, ChatTodoItemData right) {
  return left.content == right.content &&
      left.status == right.status &&
      left.activeForm == right.activeForm;
}

bool _chatComposerAttachmentsEquivalent(
  ChatAttachmentData left,
  ChatAttachmentData right,
) {
  return left.id == right.id &&
      left.kind == right.kind &&
      left.label == right.label &&
      left.detail == right.detail &&
      left.accentColor == right.accentColor &&
      _draftAttachmentsEquivalent(left.draftAttachment, right.draftAttachment);
}

bool _draftAttachmentsEquivalent(
  OpenCrayChatDraftAttachment? left,
  OpenCrayChatDraftAttachment? right,
) {
  if (left == null || right == null) {
    return left == right;
  }
  return left.kind == right.kind &&
      left.displayName == right.displayName &&
      left.relativePath == right.relativePath &&
      left.artifactId == right.artifactId &&
      left.chatAttachmentId == right.chatAttachmentId &&
      left.mimeType == right.mimeType &&
      left.sizeBytes == right.sizeBytes;
}

bool _chatCommandOptionsEquivalent(
  ChatCommandOptionData left,
  ChatCommandOptionData right,
) {
  return left.label == right.label && left.description == right.description;
}

bool _chatAddActionsEqual(ChatAddActionData left, ChatAddActionData right) {
  return left.label == right.label && left.icon == right.icon;
}

bool _chatDrawerStatesEquivalent(
  ChatSessionsDrawerState left,
  ChatSessionsDrawerState right,
) {
  return left.eyebrow == right.eyebrow &&
      left.title == right.title &&
      left.ctaLabel == right.ctaLabel &&
      _listsEquivalent(
        left.sessions,
        right.sessions,
        _chatSessionListItemsEquivalent,
      );
}

bool _chatSessionListItemsEquivalent(
  ChatSessionListItemData left,
  ChatSessionListItemData right,
) {
  return left.sessionId == right.sessionId &&
      left.title == right.title &&
      left.preview == right.preview &&
      left.meta == right.meta &&
      left.isSelected == right.isSelected &&
      left.lastMessageAtEpochMs == right.lastMessageAtEpochMs &&
      left.unreadCount == right.unreadCount;
}

bool _chatPendingApprovalsEquivalent(
  List<ChatPendingApprovalData> left,
  List<ChatPendingApprovalData> right,
) {
  return _listsEquivalent(
    left,
    right,
    (leftApproval, rightApproval) =>
        leftApproval.runId == rightApproval.runId &&
        leftApproval.taskId == rightApproval.taskId &&
        leftApproval.title == rightApproval.title &&
        leftApproval.body == rightApproval.body &&
        leftApproval.approveLabel == rightApproval.approveLabel &&
        leftApproval.rejectLabel == rightApproval.rejectLabel &&
        leftApproval.isHighRisk == rightApproval.isHighRisk &&
        leftApproval.supportsSessionApproval ==
            rightApproval.supportsSessionApproval &&
        leftApproval.approveForSessionLabel ==
            rightApproval.approveForSessionLabel &&
        leftApproval.toolName == rightApproval.toolName &&
        leftApproval.requestSummary == rightApproval.requestSummary &&
        leftApproval.primaryDetail == rightApproval.primaryDetail &&
        leftApproval.workingDirectory == rightApproval.workingDirectory &&
        leftApproval.reason == rightApproval.reason &&
        leftApproval.message == rightApproval.message &&
        _listsEquivalent(
          leftApproval.pathDetails,
          rightApproval.pathDetails,
          _itemsEquivalent,
        ),
  );
}

bool _chatMessageAttachmentsEquivalent(
  ChatMessageAttachmentData left,
  ChatMessageAttachmentData right,
) {
  return left.attachmentId == right.attachmentId &&
      left.kind == right.kind &&
      left.displayName == right.displayName &&
      left.localPath == right.localPath &&
      left.mimeType == right.mimeType &&
      left.sizeBytes == right.sizeBytes &&
      left.widthPx == right.widthPx &&
      left.heightPx == right.heightPx &&
      left.durationMs == right.durationMs &&
      left.transcriptText == right.transcriptText &&
      left.contentSha256 == right.contentSha256 &&
      _listsEquivalent(left.waveformBars, right.waveformBars, _itemsEquivalent);
}

bool _itemsEquivalent<T>(T left, T right) => left == right;

bool _listsEquivalent<T>(
  List<T> left,
  List<T> right,
  bool Function(T left, T right) itemEquivalent,
) {
  if (identical(left, right)) {
    return true;
  }
  if (left.length != right.length) {
    return false;
  }
  for (int index = 0; index < left.length; index += 1) {
    if (!itemEquivalent(left[index], right[index])) {
      return false;
    }
  }
  return true;
}

String? _visibleAssistantDraftText(String rawText) {
  final String normalized = rawText.trim();
  if (normalized.isEmpty) {
    return null;
  }
  final bool startsLikeJson =
      normalized.startsWith('{') || normalized.startsWith('[');
  if (!startsLikeJson) {
    return normalized;
  }
  final String lowercase = normalized.toLowerCase();
  final bool hasExplicitTypeField =
      lowercase.contains('"type"') || lowercase.contains('"decision"');
  final bool looksLikeStructuredProtocol =
      hasExplicitTypeField ||
      lowercase.contains('"actions"') ||
      lowercase.contains('"tool_name"') ||
      lowercase.contains('"tool_calls"') ||
      lowercase.contains('"function_call"') ||
      lowercase.contains('"call_id"') ||
      lowercase.contains('"arguments"');
  final bool looksLikeInternalSignal =
      lowercase.contains('"is_task_bearing_request"') ||
      lowercase.contains('"user_affect"') ||
      lowercase.contains('"user_invites_playfulness"') ||
      lowercase.contains('"user_requests_relational_support"') ||
      lowercase.contains('"clarification_needed"');
  if (!looksLikeStructuredProtocol && !looksLikeInternalSignal) {
    return normalized;
  }
  if (looksLikeInternalSignal ||
      lowercase.contains('"tool_name"') ||
      lowercase.contains('"tool_calls"') ||
      lowercase.contains('"function_call"') ||
      lowercase.contains('"call_id"') ||
      lowercase.contains('"arguments"')) {
    return null;
  }
  final String? actionType = _firstNonBlankDraftField(<String?>[
    _partialJsonStringFieldValue(normalized, 'type')?.trim().toLowerCase(),
    _partialJsonStringFieldValue(normalized, 'decision')?.trim().toLowerCase(),
  ]);
  switch (actionType) {
    case 'final':
    case 'answer':
      return _firstNonBlankDraftField(<String?>[
        _partialJsonStringFieldValue(normalized, 'answer'),
        _partialJsonStringFieldValue(normalized, 'text'),
        _partialJsonStringFieldValue(normalized, 'message'),
        _partialJsonStringFieldValue(normalized, 'summary'),
      ])?.trim();
    case null:
    case '':
      return hasExplicitTypeField
          ? _partialJsonStringFieldValue(normalized, 'answer')?.trim()
          : null;
    default:
      return null;
  }
}

String? _firstNonBlankDraftField(List<String?> values) {
  for (final String? value in values) {
    final String trimmed = value?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      return trimmed;
    }
  }
  return null;
}

String? _partialJsonStringFieldValue(String rawText, String fieldName) {
  final String fieldPattern = '"$fieldName"';
  int searchStart = 0;
  while (true) {
    final int keyIndex = rawText.indexOf(fieldPattern, searchStart);
    if (keyIndex < 0) {
      return null;
    }
    int index = keyIndex + fieldPattern.length;
    while (index < rawText.length && rawText[index].trim().isEmpty) {
      index += 1;
    }
    if (index >= rawText.length || rawText[index] != ':') {
      searchStart = keyIndex + fieldPattern.length;
      continue;
    }
    index += 1;
    while (index < rawText.length && rawText[index].trim().isEmpty) {
      index += 1;
    }
    if (index >= rawText.length || rawText[index] != '"') {
      return null;
    }
    index += 1;
    final StringBuffer buffer = StringBuffer();
    bool escaped = false;
    while (index < rawText.length) {
      final String character = rawText[index];
      if (escaped) {
        switch (character) {
          case 'n':
            buffer.write('\n');
            break;
          case 'r':
            buffer.write('\r');
            break;
          case 't':
            buffer.write('\t');
            break;
          default:
            buffer.write(character);
            break;
        }
        escaped = false;
        index += 1;
        continue;
      }
      if (character == '\\') {
        escaped = true;
        index += 1;
        continue;
      }
      if (character == '"') {
        return buffer.toString();
      }
      buffer.write(character);
      index += 1;
    }
    return buffer.toString();
  }
}

@visibleForTesting
TextSelectionThemeData chatBubbleSelectionTheme(ChatMessageKind kind) {
  return switch (kind) {
    ChatMessageKind.outbound => const TextSelectionThemeData(
      // Outbound bubbles already use the app accent, so switch to a bright
      // translucent selection color to preserve contrast.
      selectionColor: Color(0x52FFFFFF),
      selectionHandleColor: Colors.white,
    ),
    _ => const TextSelectionThemeData(
      selectionColor: Color(0x33007AFF),
      selectionHandleColor: Color(0xFF0A84FF),
    ),
  };
}

enum _SessionMenuAction { copy, delete }

enum _ChatRuntimeEnvironment { local, cloud }

enum _ChatMessageMenuAction {
  copy,
  recall,
  redo,
  edit,
  branch,
  delete,
  multiSelect,
  quote,
}

const OpenCraySandboxSettingsSnapshot _defaultSandboxSettingsSnapshot =
    OpenCraySandboxSettingsSnapshot(
      localeTag: 'en',
      enabled: false,
      providerId: 'e2b',
      defaultBackend: 'local',
      sessionMode: 'ephemeral',
      autoResume: false,
      idleTimeoutMinutes: 15,
      startupTimeoutMs: 30000,
      requestTimeoutMs: 300000,
      timeoutAction: 'kill',
      templateId: '',
      e2bApiKey: '',
      apiKeyConfigured: false,
    );

@visibleForTesting
const Duration chatSandboxSessionAutoRefreshDebounce = Duration(
  milliseconds: 900,
);

@immutable
class _ActiveChatMessageMenu {
  const _ActiveChatMessageMenu({
    required this.message,
    required this.bubbleRect,
    this.redoPrompt,
    this.selectedText,
  });

  final ChatMessageData message;
  final Rect bubbleRect;
  final ChatMessageData? redoPrompt;
  final String? selectedText;

  bool get isOutgoing => message.kind == ChatMessageKind.outbound;

  bool get canRecall => isOutgoing && !message.isEphemeral;

  bool get showsRedo => message.kind == ChatMessageKind.inbound;

  bool get canRedo => redoPrompt != null && !message.isEphemeral;

  bool get canEdit => isOutgoing && !message.isEphemeral;

  bool get canBranch =>
      message.kind == ChatMessageKind.inbound && !message.isEphemeral;

  bool get canDelete => !message.isEphemeral;
}

@immutable
class _TodoTraceSummary {
  const _TodoTraceSummary({
    required this.todoCount,
    required this.pendingCount,
    required this.inProgressCount,
    required this.completedCount,
    this.activeTodoContent,
  });

  final int todoCount;
  final int pendingCount;
  final int inProgressCount;
  final int completedCount;
  final String? activeTodoContent;
}

@immutable
class _SandboxSessionLifecycleRefreshSchedule {
  const _SandboxSessionLifecycleRefreshSchedule({
    required this.key,
    required this.delayMs,
  });

  final String key;
  final int delayMs;
}

class ChatFeatureController {
  bool Function()? _backPressHandler;

  bool consumeBackPress() => _backPressHandler?.call() ?? false;
}

class OpenCrayChatFeature extends StatefulWidget {
  const OpenCrayChatFeature({
    super.key,
    required this.copy,
    this.state,
    this.bridge,
    this.voicePlaybackControllerFactory,
    this.isTabActive = true,
    this.controller,
    this.bottomInset = 10,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState? state;
  final OpenCrayHostBridge? bridge;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isTabActive;
  final ChatFeatureController? controller;
  final double bottomInset;

  @override
  State<OpenCrayChatFeature> createState() => _OpenCrayChatFeatureState();
}

class _OpenCrayChatFeatureState extends State<OpenCrayChatFeature> {
  static const AnimationStyle _sessionMenuAnimationStyle = AnimationStyle(
    duration: Duration(milliseconds: 120),
    reverseDuration: Duration(milliseconds: 90),
    curve: Curves.easeOutCubic,
    reverseCurve: Curves.easeInCubic,
  );

  late ChatFeatureState _state =
      widget.state ?? OpenCrayChatSeedData.main(widget.copy);
  late final TextEditingController _composerController =
      TextEditingController();
  late final FocusNode _composerFocusNode = FocusNode();
  final ScrollController _chatScrollController = ScrollController();
  final GlobalKey _chatOverlayKey = GlobalKey();
  final GlobalKey _composerKey = GlobalKey();
  StreamSubscription<OpenCrayChatSnapshot>? _chatSubscription;
  StreamSubscription<OpenCrayChatRuntimeSnapshot>? _chatRuntimeSubscription;
  StreamSubscription<OpenCrayChatLiveAssistantDraftEvent>?
  _liveAssistantDraftSubscription;
  StreamSubscription<OpenCrayChatRuntimeEventDelta>?
  _runtimeEventDeltaSubscription;
  OpenCrayChatSnapshot? _latestChatSnapshot;
  OpenCrayChatRuntimeSnapshot? _latestChatRuntimeSnapshot;
  Timer? _runtimeProjectionFlushTimer;
  OpenCrayChatRuntimeSnapshot? _pendingRuntimeProjectionSnapshot;
  final Map<String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>
  _liveAssistantDraftOverridesBySession =
      <String, Map<String, OpenCrayChatLiveAssistantDraftSnapshot>>{};
  final Map<String, Map<String, int>>
  _liveAssistantDraftEventEpochBySessionAndMessage =
      <String, Map<String, int>>{};
  final Map<String, int> _runtimeEventDeltaSequenceBySession = <String, int>{};
  bool _runtimeEventDeltaResyncInFlight = false;
  OpenCrayChatRuntimeEventDelta? _queuedRuntimeEventDeltaAfterResync;
  final Set<String> _approvalTaskIdsInFlight = <String>{};
  final Set<String> _interruptRunIdsInFlight = <String>{};
  final Set<String> _retryRunIdsInFlight = <String>{};
  final Set<String> _selectedMessageIds = <String>{};
  final Map<String, String> _selectedTextByMessageId = <String, String>{};
  final Map<String, SelectedContentRange> _selectedTextRangeByMessageId =
      <String, SelectedContentRange>{};
  final Map<String, Set<String>> _locallyDeletedMessageIdsBySession =
      <String, Set<String>>{};
  final Set<String> _locallyDeletedSessionIds = <String>{};
  _ActiveChatMessageMenu? _activeMessageMenu;
  bool _suppressNextTransientUiDismiss = false;
  Timer? _todoArchiveHideTimer;
  Timer? _sandboxSessionAutoRefreshTimer;
  Timer? _sandboxSessionLifecycleRefreshTimer;
  String? _hiddenArchivedTodoFingerprint;
  String? _scheduledTodoArchiveFingerprint;
  String? _scheduledSandboxSessionRefreshAnchor;
  final List<String> _queuedSandboxSessionRefreshAnchors = <String>[];
  String? _lastSandboxSessionRefreshAnchor;
  String? _scheduledSandboxSessionLifecycleRefreshKey;
  bool _queuedSandboxSessionLifecycleRefresh = false;
  String? _interruptConfirmRunId;
  double _composerHeight = 0;
  OpenCraySandboxSettingsSnapshot _sandboxSettings =
      _defaultSandboxSettingsSnapshot;
  bool _sandboxSessionRefreshInFlight = false;

  bool get _usesHostBridge => widget.bridge != null;

  _ChatRuntimeEnvironment get _selectedRuntimeEnvironment =>
      _sandboxSettings.defaultBackend == 'sandbox'
      ? _ChatRuntimeEnvironment.cloud
      : _ChatRuntimeEnvironment.local;

  ChatRunTraceData? get _composerInterruptTrace {
    for (final trace in _state.runTraces.reversed) {
      if (trace.canInterrupt && trace.interruptId.trim().isNotEmpty) {
        return trace;
      }
    }
    return null;
  }

  String get _activeSessionId {
    for (final session in _state.drawer.sessions) {
      if (session.isSelected) {
        return session.sessionId;
      }
    }
    if (_state.drawer.sessions.isNotEmpty) {
      return _state.drawer.sessions.first.sessionId;
    }
    final String runtimeSessionId =
        _latestChatRuntimeSnapshot?.sessionId.trim() ??
        _latestChatSnapshot?.runtimeActivity?.sessionId.trim() ??
        '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    return 'chat-session';
  }

  String _sessionIdForState(ChatFeatureState state) {
    for (final session in state.drawer.sessions) {
      if (session.isSelected) {
        return session.sessionId;
      }
    }
    if (state.drawer.sessions.isNotEmpty) {
      return state.drawer.sessions.first.sessionId;
    }
    final String runtimeSessionId =
        _latestChatRuntimeSnapshot?.sessionId.trim() ??
        _latestChatSnapshot?.runtimeActivity?.sessionId.trim() ??
        '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    return 'chat-session';
  }

  void _rememberLocallyDeletedMessages(
    String sessionId,
    Set<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty || messageIds.isEmpty) {
      return;
    }
    _locallyDeletedMessageIdsBySession
        .putIfAbsent(normalizedSessionId, () => <String>{})
        .addAll(messageIds);
  }

  void _forgetLocallyDeletedMessages(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    final Set<String>? deletedIds =
        _locallyDeletedMessageIdsBySession[normalizedSessionId];
    if (deletedIds == null) {
      return;
    }
    for (final String messageId in messageIds) {
      deletedIds.remove(messageId.trim());
    }
    if (deletedIds.isEmpty) {
      _locallyDeletedMessageIdsBySession.remove(normalizedSessionId);
    }
  }

  void _removeSelectionForMessages(Iterable<String> messageIds) {
    for (final String messageId in messageIds) {
      final String normalizedMessageId = messageId.trim();
      if (normalizedMessageId.isEmpty) {
        continue;
      }
      _selectedMessageIds.remove(normalizedMessageId);
      _selectedTextByMessageId.remove(normalizedMessageId);
      _selectedTextRangeByMessageId.remove(normalizedMessageId);
    }
  }

  ChatFeatureState _applyLocalDeletionTombstones(ChatFeatureState state) {
    final String sessionId = _sessionIdForState(state).trim();
    final Set<String> locallyDeletedMessageIds = sessionId.isEmpty
        ? const <String>{}
        : _locallyDeletedMessageIdsBySession[sessionId] ?? const <String>{};
    List<ChatMessageData> messages = state.messages;
    List<ChatRunTraceData> runTraces = state.runTraces;
    if (locallyDeletedMessageIds.isNotEmpty) {
      messages = messages
          .where(
            (message) =>
                !locallyDeletedMessageIds.contains(message.messageId.trim()) &&
                !locallyDeletedMessageIds.contains(
                  message.runtimeAnchorMessageId.trim(),
                ),
          )
          .toList(growable: false);
      runTraces = runTraces
          .where(
            (trace) => !locallyDeletedMessageIds.contains(
              trace.anchorMessageId.trim(),
            ),
          )
          .toList(growable: false);
    }

    ChatSessionsDrawerState drawer = state.drawer;
    if (_locallyDeletedSessionIds.isNotEmpty && drawer.sessions.isNotEmpty) {
      final List<ChatSessionListItemData> remainingSessions = drawer.sessions
          .where(
            (session) =>
                !_locallyDeletedSessionIds.contains(session.sessionId.trim()),
          )
          .toList(growable: false);
      if (remainingSessions.length != drawer.sessions.length) {
        String selectedSessionId = '';
        for (final ChatSessionListItemData session in remainingSessions) {
          if (session.isSelected) {
            selectedSessionId = session.sessionId;
            break;
          }
        }
        if (selectedSessionId.isEmpty && remainingSessions.isNotEmpty) {
          selectedSessionId = remainingSessions.first.sessionId;
        }
        drawer = ChatSessionsDrawerState(
          eyebrow: drawer.eyebrow,
          title: drawer.title,
          ctaLabel: drawer.ctaLabel,
          sessions: remainingSessions
              .map(
                (session) => ChatSessionListItemData(
                  sessionId: session.sessionId,
                  title: session.title,
                  preview: session.preview,
                  meta: session.meta,
                  isSelected: session.sessionId == selectedSessionId,
                  lastMessageAtEpochMs: session.lastMessageAtEpochMs,
                  unreadCount: session.sessionId == selectedSessionId
                      ? 0
                      : session.unreadCount,
                ),
              )
              .toList(growable: false),
        );
      }
    }

    final bool threadEmpty = messages.isEmpty && runTraces.isEmpty;
    return state.copyWith(
      variant:
          threadEmpty &&
              state.composer.todos.isEmpty &&
              state.pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      messages: messages,
      runTraces: runTraces,
      drawer: drawer,
      emptyThreadHeight: threadEmpty ? 260 : 0,
    );
  }

  void _pruneLocalDeletionTombstones(OpenCrayChatSnapshot snapshot) {
    if (_locallyDeletedSessionIds.isNotEmpty) {
      final Set<String> snapshotSessionIds = snapshot.drawer.sessions
          .map((session) => session.sessionId.trim())
          .where((sessionId) => sessionId.isNotEmpty)
          .toSet();
      _locallyDeletedSessionIds.removeWhere(
        (sessionId) => !snapshotSessionIds.contains(sessionId),
      );
    }

    final String sessionId = _snapshotActiveSessionId(snapshot).trim();
    final Set<String>? deletedMessageIds =
        _locallyDeletedMessageIdsBySession[sessionId];
    if (sessionId.isEmpty || deletedMessageIds == null) {
      return;
    }
    final Set<String> snapshotMessageIds = snapshot.messages
        .asMap()
        .entries
        .map((entry) {
          final String messageId = entry.value.messageId.trim();
          return messageId.isNotEmpty
              ? messageId
              : 'message-${entry.key}-${entry.value.kind}';
        })
        .toSet();
    deletedMessageIds.removeWhere(
      (messageId) => !snapshotMessageIds.contains(messageId),
    );
    if (deletedMessageIds.isEmpty) {
      _locallyDeletedMessageIdsBySession.remove(sessionId);
    }
  }

  ChatComposerState _composerStateForHostSnapshot(ChatFeatureState nextState) {
    if (_sessionIdForState(nextState) != _activeSessionId) {
      return nextState.composer;
    }
    return nextState.composer.copyWith(
      attachments: _state.composer.attachments,
      selectedCommand: _state.composer.selectedCommand,
      commandOptions: _state.composer.commandOptions,
      addActions: _state.composer.addActions,
      showAddMenu: _state.composer.showAddMenu,
    );
  }

  bool get _isMessageSelectionMode => _selectedMessageIds.isNotEmpty;

  int get _selectedMessageCount => _selectedMessageIds.length;

  List<ChatMessageData> get _selectedMessagesInOrder => _state.messages
      .where((message) => _selectedMessageIds.contains(message.messageId))
      .toList(growable: false);

  @override
  void initState() {
    super.initState();
    widget.controller?._backPressHandler = _consumeBackPress;
    _chatScrollController.addListener(_handleChatScrollChanged);
    final bridge = widget.bridge;
    if (bridge != null) {
      _hydrateFromHost(bridge);
      _chatSubscription = bridge.watchChatSnapshot().listen(
        _handleChatSnapshot,
      );
      _chatRuntimeSubscription = bridge.watchChatRuntimeSnapshot().listen(
        _handleChatRuntimeSnapshot,
      );
      _liveAssistantDraftSubscription = bridge
          .watchLiveAssistantDraftEvents()
          .listen(_handleLiveAssistantDraftEvent);
      _runtimeEventDeltaSubscription = bridge.watchRuntimeEventDeltas().listen(
        _handleRuntimeEventDelta,
      );
    }
  }

  @override
  void didUpdateWidget(covariant OpenCrayChatFeature oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?._backPressHandler = null;
      widget.controller?._backPressHandler = _consumeBackPress;
    }
    if (oldWidget.isTabActive &&
        !widget.isTabActive &&
        _isMessageSelectionMode) {
      _clearMessageSelection(emitHaptic: false);
    }
    if (oldWidget.isTabActive != widget.isTabActive) {
      if (widget.isTabActive) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _cancelScheduledSandboxSessionAutoRefresh();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
    }
  }

  @override
  void dispose() {
    widget.controller?._backPressHandler = null;
    _chatScrollController.removeListener(_handleChatScrollChanged);
    _chatSubscription?.cancel();
    _chatRuntimeSubscription?.cancel();
    _liveAssistantDraftSubscription?.cancel();
    _runtimeEventDeltaSubscription?.cancel();
    _runtimeProjectionFlushTimer?.cancel();
    _todoArchiveHideTimer?.cancel();
    _sandboxSessionAutoRefreshTimer?.cancel();
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _composerController.dispose();
    _composerFocusNode.dispose();
    _chatScrollController.dispose();
    super.dispose();
  }

  bool _consumeBackPress() {
    if (_isMessageSelectionMode) {
      _clearMessageSelection();
      return true;
    }
    return false;
  }

  void _handleChatScrollChanged() {
    if (_activeMessageMenu == null) {
      return;
    }
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
  }

  void _dismissMessageMenu() {
    if (_activeMessageMenu == null) {
      return;
    }
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
  }

  void _dismissTransientUi() {
    if (_suppressNextTransientUiDismiss) {
      _suppressNextTransientUiDismiss = false;
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    final bool shouldCloseComposerMenus =
        _state.composer.showAddMenu ||
        _state.composer.commandOptions.isNotEmpty;
    if (!shouldCloseComposerMenus && _activeMessageMenu == null) {
      return;
    }
    setState(() {
      if (shouldCloseComposerMenus) {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: const <ChatCommandOptionData>[],
            clearSelectedCommand: true,
          ),
        );
      }
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
  }

  void _showMessageFeedback(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _handleRuntimeEnvironmentSelected(
    _ChatRuntimeEnvironment environment,
  ) async {
    final String nextBackend = switch (environment) {
      _ChatRuntimeEnvironment.cloud => 'sandbox',
      _ChatRuntimeEnvironment.local => 'local',
    };
    if (_sandboxSettings.defaultBackend == nextBackend) {
      return;
    }
    final OpenCraySandboxSettingsSnapshot previousSnapshot = _sandboxSettings;
    final OpenCraySandboxSettingsSnapshot nextSnapshot = _sandboxSettings
        .copyWith(defaultBackend: nextBackend);
    setState(() {
      _sandboxSettings = nextSnapshot;
    });
    final bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    try {
      final savedSnapshot = await bridge.saveSandboxSettings(nextSnapshot);
      if (!mounted) {
        return;
      }
      setState(() {
        _sandboxSettings = savedSnapshot;
      });
      if (_selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _resetSandboxSessionAutoRefreshTracking();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _sandboxSettings = previousSnapshot;
      });
      if (_selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
      } else {
        _resetSandboxSessionAutoRefreshTracking();
        _cancelScheduledSandboxSessionLifecycleRefresh();
      }
      _showMessageFeedback('Unable to update runtime environment.');
    }
  }

  void _emitSelectionHaptic() {
    unawaited(HapticFeedback.selectionClick());
  }

  void _handleMessageTextSelectionChanged(
    ChatMessageData message,
    OpenCrayMarkdownSelectionSnapshot? selection,
  ) {
    if (message.messageId.isEmpty) {
      return;
    }
    final String normalized = selection?.plainText.trim() ?? '';
    if (normalized.isEmpty) {
      _selectedTextByMessageId.remove(message.messageId);
      _selectedTextRangeByMessageId.remove(message.messageId);
      return;
    }
    _selectedTextByMessageId[message.messageId] = normalized;
    final SelectedContentRange? range = selection?.range;
    if (range == null) {
      _selectedTextRangeByMessageId.remove(message.messageId);
    } else {
      _selectedTextRangeByMessageId[message.messageId] = range;
    }
  }

  OpenCrayMarkdownSelectionSnapshot? _resolvedSelectedCopyForMenu(
    _ActiveChatMessageMenu menu,
  ) {
    final String liveSelectedText =
        _selectedTextByMessageId[menu.message.messageId]?.trim() ?? '';
    if (liveSelectedText.isNotEmpty) {
      return OpenCrayMarkdownSelectionSnapshot(
        plainText: liveSelectedText,
        range: _selectedTextRangeByMessageId[menu.message.messageId],
      );
    }
    final String fallbackSelectedText = menu.selectedText?.trim() ?? '';
    if (fallbackSelectedText.isEmpty) {
      return null;
    }
    return OpenCrayMarkdownSelectionSnapshot(plainText: fallbackSelectedText);
  }

  Future<void> _copyMessageFromMenu(_ActiveChatMessageMenu menu) async {
    final OpenCrayMarkdownSelectionSnapshot? selectedCopy =
        _resolvedSelectedCopyForMenu(menu);
    final String selectedText = selectedCopy?.plainText ?? '';
    if (selectedText.isNotEmpty) {
      final OpenCrayMarkdownClipboardPayload? selectionPayload =
          openCrayBuildMarkdownSelectionClipboardPayload(
            menu.message.text,
            selectedText: selectedText,
            selectionStartOffset: selectedCopy?.range?.startOffset,
            selectionEndOffset: selectedCopy?.range?.endOffset,
          );
      if (selectionPayload != null) {
        final OpenCrayHostBridge? bridge = widget.bridge;
        if (bridge == null) {
          await Clipboard.setData(
            ClipboardData(text: selectionPayload.plainText),
          );
        } else {
          await bridge.copyRichTextToClipboard(
            plainText: selectionPayload.plainText,
            htmlText: selectionPayload.htmlText,
          );
        }
        return;
      }
      await Clipboard.setData(ClipboardData(text: selectedText));
      return;
    }
    final OpenCrayMarkdownClipboardPayload? clipboardPayload =
        openCrayBuildMarkdownClipboardPayload(menu.message.text);
    if (clipboardPayload == null) {
      await Clipboard.setData(ClipboardData(text: menu.message.text));
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      await Clipboard.setData(ClipboardData(text: clipboardPayload.plainText));
      return;
    }
    await bridge.copyRichTextToClipboard(
      plainText: clipboardPayload.plainText,
      htmlText: clipboardPayload.htmlText,
    );
  }

  void _enterMessageSelectionMode(ChatMessageData message) {
    if (message.messageId.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    _emitSelectionHaptic();
    setState(() {
      _selectedMessageIds
        ..clear()
        ..add(message.messageId);
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(
        drawerOpen: false,
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _clearMessageSelection({bool emitHaptic = true}) {
    if (_selectedMessageIds.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    if (emitHaptic) {
      _emitSelectionHaptic();
    }
    setState(() {
      _selectedMessageIds.clear();
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
  }

  void _toggleMessageSelection(ChatMessageData message) {
    if (message.kind == ChatMessageKind.timeline || message.messageId.isEmpty) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    _emitSelectionHaptic();
    setState(() {
      if (_selectedMessageIds.contains(message.messageId)) {
        _selectedMessageIds.remove(message.messageId);
      } else {
        _selectedMessageIds.add(message.messageId);
      }
    });
  }

  String _selectedMessagesClipboardText() {
    final List<String> chunks = _selectedMessagesInOrder
        .map((message) {
          final List<String> parts = <String>[];
          final String text = message.text.trim();
          if (text.isNotEmpty) {
            parts.add(text);
          }
          for (final attachment in message.attachments) {
            parts.add('[${attachment.displayName}]');
          }
          return parts.join('\n').trim();
        })
        .where((chunk) => chunk.isNotEmpty)
        .toList(growable: false);
    return chunks.join('\n\n');
  }

  Future<void> _copySelectedMessages() async {
    final String text = _selectedMessagesClipboardText();
    if (text.isEmpty) {
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) {
      return;
    }
    _showMessageFeedback(widget.copy.chatSelectionCopied);
  }

  Future<void> _deleteSelectedMessages() async {
    final List<String> selectedIds = _selectedMessageIds
        .map((messageId) => messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toList(growable: false);
    if (selectedIds.isEmpty) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      final String sessionId = _activeSessionId;
      final Set<String> deletedIds = selectedIds.toSet();
      setState(() {
        _rememberLocallyDeletedMessages(sessionId, deletedIds);
        _removeSelectionForMessages(deletedIds);
        _state = _applyLocalDeletionTombstones(_state);
      });
      final Set<String> pendingIds = <String>{...deletedIds};
      final Set<String> failedOrUnsentIds = <String>{};
      for (final String messageId in selectedIds) {
        try {
          await bridge.deleteChatMessage(
            sessionId: sessionId,
            messageId: messageId,
          );
          pendingIds.remove(messageId);
        } catch (_) {
          failedOrUnsentIds
            ..add(messageId)
            ..addAll(pendingIds);
          break;
        }
      }
      if (failedOrUnsentIds.isNotEmpty) {
        if (!mounted) {
          return;
        }
        _forgetLocallyDeletedMessages(sessionId, failedOrUnsentIds);
        _applyHostState();
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages
            .where((message) => !selectedIds.contains(message.messageId))
            .toList(growable: false),
      );
      _removeSelectionForMessages(selectedIds);
    });
  }

  void _handleMessageLongPress(
    ChatMessageData message,
    Rect globalBubbleRect,
    String? selectedText,
  ) {
    final BuildContext? overlayContext = _chatOverlayKey.currentContext;
    final RenderObject? overlayRenderObject = overlayContext
        ?.findRenderObject();
    if (overlayRenderObject is! RenderBox) {
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    unawaited(HapticFeedback.lightImpact());
    final Rect bubbleRect = Rect.fromPoints(
      overlayRenderObject.globalToLocal(globalBubbleRect.topLeft),
      overlayRenderObject.globalToLocal(globalBubbleRect.bottomRight),
    );
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
      _suppressNextTransientUiDismiss = true;
      _activeMessageMenu = _ActiveChatMessageMenu(
        message: message,
        bubbleRect: bubbleRect,
        redoPrompt: _redoPromptForMessage(message),
        selectedText: selectedText,
      );
    });
  }

  Future<void> _handleMessageMenuAction(_ChatMessageMenuAction action) async {
    final activeMenu = _activeMessageMenu;
    if (activeMenu == null) {
      return;
    }
    _dismissMessageMenu();
    switch (action) {
      case _ChatMessageMenuAction.copy:
        await _copyMessageFromMenu(activeMenu);
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageCopied);
        break;
      case _ChatMessageMenuAction.recall:
        if (!activeMenu.canRecall) {
          return;
        }
        await _recallChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.redo:
        if (!activeMenu.canRedo) {
          return;
        }
        await _redoChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.edit:
        if (!activeMenu.canEdit) {
          return;
        }
        await _editChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.branch:
        if (!activeMenu.canBranch) {
          return;
        }
        await _branchChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.delete:
        if (!activeMenu.canDelete) {
          return;
        }
        await _deleteChatMessage(activeMenu.message);
        break;
      case _ChatMessageMenuAction.multiSelect:
        _enterMessageSelectionMode(activeMenu.message);
        break;
      case _ChatMessageMenuAction.quote:
        _quoteChatMessage(activeMenu.message);
        break;
    }
  }

  Future<void> _deleteChatMessage(ChatMessageData message) async {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      final String sessionId = _activeSessionId;
      setState(() {
        _rememberLocallyDeletedMessages(sessionId, <String>{messageId});
        _removeSelectionForMessages(<String>{messageId});
        _state = _applyLocalDeletionTombstones(_state);
      });
      try {
        await bridge.deleteChatMessage(
          sessionId: sessionId,
          messageId: messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _forgetLocallyDeletedMessages(sessionId, <String>{messageId});
        _applyHostState();
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages
            .where((candidate) => candidate.messageId != messageId)
            .toList(growable: false),
      );
      _removeSelectionForMessages(<String>{messageId});
    });
  }

  Future<void> _recallChatMessage(ChatMessageData message) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int recallIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    if (recallIndex < 0) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages.take(recallIndex).toList(growable: false),
      );
    });
  }

  ChatMessageData? _redoPromptForMessage(ChatMessageData message) {
    if (message.kind != ChatMessageKind.inbound || message.isEphemeral) {
      return null;
    }
    final int messageIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    if (messageIndex <= 0) {
      return null;
    }
    for (int index = messageIndex - 1; index >= 0; index -= 1) {
      final ChatMessageData candidate = _state.messages[index];
      if (candidate.kind == ChatMessageKind.outbound &&
          !candidate.isEphemeral) {
        return candidate;
      }
    }
    return null;
  }

  Future<void> _redoChatMessage(ChatMessageData message) async {
    final ChatMessageData? redoPrompt = _redoPromptForMessage(message);
    if (redoPrompt == null) {
      if (!mounted) {
        return;
      }
      _showMessageFeedback(widget.copy.chatMessageActionFailed);
      return;
    }
    final bridge = widget.bridge;
    final List<OpenCrayChatDraftAttachment> redoAttachments =
        _draftAttachmentsForMessage(redoPrompt);
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: redoPrompt.messageId,
        );
        await bridge.submitChatMessage(
          redoPrompt.text,
          attachments: redoAttachments,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int promptIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == redoPrompt.messageId,
    );
    if (promptIndex < 0) {
      return;
    }
    final int stamp = DateTime.now().microsecondsSinceEpoch;
    setState(() {
      _state = _state.copyWith(
        messages: <ChatMessageData>[
          ..._state.messages.take(promptIndex),
          ChatMessageData(
            messageId: 'redo-outbound-$stamp',
            kind: ChatMessageKind.outbound,
            text: redoPrompt.text,
            attachments: redoPrompt.attachments,
          ),
          ChatMessageData(
            messageId: 'redo-inbound-$stamp',
            kind: ChatMessageKind.inbound,
            text: widget.copy.chatRunThinkingActive,
          ),
        ],
      );
    });
    _scheduleScrollToBottom();
  }

  Future<void> _editChatMessage(ChatMessageData message) async {
    final String draft = message.text;
    final List<ChatAttachmentData> draftAttachments =
        _composerAttachmentsForMessage(message);
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
        return;
      }
    } else {
      final int recallIndex = _state.messages.indexWhere(
        (candidate) => candidate.messageId == message.messageId,
      );
      if (recallIndex < 0) {
        return;
      }
      setState(() {
        _state = _state.copyWith(
          messages: _state.messages.take(recallIndex).toList(growable: false),
        );
      });
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          attachments: draftAttachments,
          commandOptions: const <ChatCommandOptionData>[],
          showAddMenu: false,
          clearSelectedCommand: true,
        ),
      );
    });
    _composerController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
  }

  Future<void> _branchChatMessage(ChatMessageData message) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.branchChatSessionFromMessage(
          sessionId: _activeSessionId,
          messageId: message.messageId,
        );
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    final int branchIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId == message.messageId,
    );
    final List<ChatMessageData> branchMessages =
        (branchIndex >= 0
                ? _state.messages.take(branchIndex + 1)
                : _state.messages)
            .toList(growable: false);
    final ChatSessionListItemData sourceSession = _state.drawer.sessions
        .firstWhere(
          (session) => session.isSelected,
          orElse: () => _state.drawer.sessions.first,
        );
    final String preview = _branchPreviewText(
      branchMessages,
      fallback: sourceSession.preview,
    );
    final ChatSessionListItemData branchSession = ChatSessionListItemData(
      sessionId:
          '${sourceSession.sessionId}-branch-${_state.drawer.sessions.length + 1}',
      title: _branchSessionTitle(sourceSession.title),
      preview: preview,
      meta: sourceSession.meta,
      isSelected: true,
      lastMessageAtEpochMs: sourceSession.lastMessageAtEpochMs,
    );
    final List<ChatSessionListItemData> updatedSessions =
        <ChatSessionListItemData>[
          branchSession,
          ..._state.drawer.sessions.map(
            (session) => ChatSessionListItemData(
              sessionId: session.sessionId,
              title: session.title,
              preview: session.preview,
              meta: session.meta,
              isSelected: false,
              lastMessageAtEpochMs: session.lastMessageAtEpochMs,
              unreadCount: session.unreadCount,
            ),
          ),
        ];
    setState(() {
      _state = _state.copyWith(
        messages: branchMessages,
        summary: ChatSessionSummary(
          title: branchSession.title,
          badge: _state.summary.badge,
          body: preview.isNotEmpty ? preview : _state.summary.body,
        ),
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: updatedSessions,
        ),
      );
    });
  }

  void _quoteChatMessage(ChatMessageData message) {
    final String quoted = message.text
        .trim()
        .split('\n')
        .map((line) => '> $line')
        .join('\n');
    if (quoted.isEmpty) {
      return;
    }
    final String existing = _composerController.text.trimLeft();
    final String nextText = existing.isEmpty
        ? '$quoted\n\n'
        : '$quoted\n\n$existing';
    _composerController.value = TextEditingValue(
      text: nextText,
      selection: TextSelection.collapsed(offset: nextText.length),
    );
    _composerFocusNode.requestFocus();
    _showMessageFeedback(widget.copy.chatMessageQuoted);
  }

  String _branchPreviewText(
    List<ChatMessageData> messages, {
    required String fallback,
  }) {
    for (final ChatMessageData message in messages.reversed) {
      final String trimmed = message.text.trim();
      if (trimmed.isEmpty || message.kind == ChatMessageKind.timeline) {
        continue;
      }
      return trimmed;
    }
    return fallback;
  }

  String _branchSessionTitle(String title) {
    if (title.endsWith(' branch')) {
      return title;
    }
    if (title.length >= 25) {
      return '${title.substring(0, 25)} branch';
    }
    return '$title branch';
  }

  @override
  Widget build(BuildContext context) {
    const double toolbarReserveHeight = 44;
    final double topGlassBarHeight =
        MediaQuery.paddingOf(context).top + toolbarReserveHeight + 4;
    final bool showApprovalSurface =
        !_isMessageSelectionMode && _state.pendingApprovals.isNotEmpty;
    final Widget bottomSurface = _isMessageSelectionMode
        ? _ChatSelectionToolbar(
            copy: widget.copy,
            selectedCount: _selectedMessageCount,
            onCopyPressed: _selectedMessageIds.isEmpty
                ? null
                : () {
                    _copySelectedMessages();
                  },
            onDeletePressed: _selectedMessageIds.isEmpty
                ? null
                : () {
                    _deleteSelectedMessages();
                  },
          )
        : showApprovalSurface
        ? _PendingApprovalOverlaySurface(
            copy: widget.copy,
            approvals: _state.pendingApprovals,
            busyApprovalTaskIds: _approvalTaskIdsInFlight,
            onApproveApproval: _approvePendingApproval,
            onApproveApprovalForSession: _approvePendingApprovalForSession,
            onRejectApproval: _rejectPendingApproval,
          )
        : _ComposerCard(
            copy: widget.copy,
            state: _state,
            bridge: widget.bridge,
            controller: _composerController,
            focusNode: _composerFocusNode,
            onPlusPressed: _togglePlusMenu,
            onSendPressed: () {
              _sendCurrentState();
            },
            interruptTrace: _composerInterruptTrace,
            interruptConfirmRunId: _interruptConfirmRunId,
            busyInterruptRunIds: _interruptRunIdsInFlight,
            onArmInterruptRunTrace: _armRunInterruptTrace,
            onDismissInterruptRunTrace: _dismissRunInterruptTrace,
            onInterruptRunTrace: _interruptRunTrace,
            onAddActionSelected: _handleAddAction,
            onCommandSelected: _showCommandMenu,
            onAttachmentRemoved: _removeAttachment,
          );
    _scheduleComposerHeightSync();

    final Widget page = ColoredBox(
      color: _ChatPalette.background,
      child: Stack(
        key: _chatOverlayKey,
        children: <Widget>[
          SafeArea(
            bottom: false,
            child: Column(
              children: <Widget>[
                const SizedBox(height: toolbarReserveHeight),
                Expanded(
                  child: Stack(
                    children: <Widget>[
                      Positioned.fill(
                        child: GestureDetector(
                          onTap: _dismissTransientUi,
                          behavior: HitTestBehavior.translucent,
                          child: SingleChildScrollView(
                            controller: _chatScrollController,
                            padding: EdgeInsets.fromLTRB(
                              20,
                              4,
                              20,
                              _composerScrollInset(),
                            ),
                            child: _ChatScrollContent(
                              bridge: widget.bridge,
                              copy: widget.copy,
                              state: _state,
                              showSandboxPreviewCards:
                                  _selectedRuntimeEnvironment ==
                                  _ChatRuntimeEnvironment.cloud,
                              voicePlaybackControllerFactory:
                                  widget.voicePlaybackControllerFactory,
                              selectedMessageIds: _selectedMessageIds,
                              interruptConfirmRunId: _interruptConfirmRunId,
                              busyInterruptRunIds: _interruptRunIdsInFlight,
                              busyRetryRunIds: _retryRunIdsInFlight,
                              onArmInterruptRunTrace: _armRunInterruptTrace,
                              onDismissInterruptRunTrace:
                                  _dismissRunInterruptTrace,
                              onInterruptRunTrace: _interruptRunTrace,
                              onRetryRunTrace: _retryRunTrace,
                              onMessageLongPress: _handleMessageLongPress,
                              onMessageSelectionToggle: _toggleMessageSelection,
                              onMessageTextSelectionChanged:
                                  _handleMessageTextSelectionChanged,
                            ),
                          ),
                        ),
                      ),
                      Align(
                        alignment: Alignment.bottomCenter,
                        child: Padding(
                          padding: EdgeInsets.fromLTRB(
                            20,
                            0,
                            20,
                            widget.bottomInset,
                          ),
                          child: KeyedSubtree(
                            key: _composerKey,
                            child: bottomSurface,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          if (_activeMessageMenu != null)
            Positioned.fill(
              child: Stack(
                children: <Widget>[
                  Positioned.fill(
                    child: GestureDetector(
                      key: const ValueKey<String>(
                        'chat-message-menu-dismiss-layer',
                      ),
                      onTap: _dismissMessageMenu,
                      behavior: HitTestBehavior.translucent,
                      child: const SizedBox.expand(),
                    ),
                  ),
                  _ChatMessageMenuOverlay(
                    copy: widget.copy,
                    menu: _activeMessageMenu!,
                    onActionSelected: _handleMessageMenuAction,
                  ),
                ],
              ),
            ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: AnimatedBuilder(
                animation: _chatScrollController,
                builder: (BuildContext context, Widget? child) {
                  return _TopGlassBar(
                    height: topGlassBarHeight,
                    strength: _topGlassStrength(),
                  );
                },
              ),
            ),
          ),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 0),
              child: _ChatToolbar(
                copy: widget.copy,
                sessionButtonLabel: _state.sessionButtonLabel,
                modeLabel: _state.modeLabel,
                runtimeEnvironment: _selectedRuntimeEnvironment,
                onRuntimeEnvironmentSelected: _handleRuntimeEnvironmentSelected,
                onSessionsPressed: _showDrawer,
                isSelectionMode: _isMessageSelectionMode,
                selectedCount: _selectedMessageCount,
                onDonePressed: _clearMessageSelection,
              ),
            ),
          ),
          if (_state.drawerOpen)
            _SessionsDrawerOverlay(
              copy: widget.copy,
              drawer: _state.drawer,
              onDismiss: _closeDrawer,
              onNewSessionPressed: _showEmpty,
              onSessionPressed: _handleSessionSelected,
              onSessionLongPress: _handleSessionLongPress,
            ),
        ],
      ),
    );
    if (widget.controller != null) {
      return page;
    }
    return PopScope<void>(
      canPop: !_isMessageSelectionMode,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) {
          return;
        }
        _consumeBackPress();
      },
      child: page,
    );
  }

  double _composerScrollInset() {
    final double measuredHeight = _composerHeight;
    if (measuredHeight > 0) {
      return widget.bottomInset + measuredHeight + 12;
    }
    return widget.bottomInset + 84;
  }

  double _topGlassStrength() {
    if (!_chatScrollController.hasClients) {
      return 0;
    }
    final double offset = _chatScrollController.position.pixels;
    if (offset <= 0) {
      return 0;
    }
    if (offset >= 56) {
      return 1;
    }
    return offset / 56;
  }

  void _scheduleComposerHeightSync() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      final BuildContext? composerContext = _composerKey.currentContext;
      final double? nextHeight = composerContext?.size?.height;
      if (nextHeight == null || (nextHeight - _composerHeight).abs() < 0.5) {
        return;
      }
      setState(() {
        _composerHeight = nextHeight;
      });
    });
  }

  void _scheduleScrollToBottom({bool animated = true}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_chatScrollController.hasClients) {
        return;
      }
      final ScrollPosition position = _chatScrollController.position;
      final double target = position.maxScrollExtent;
      if ((target - position.pixels).abs() < 1) {
        return;
      }
      if (animated) {
        _chatScrollController.animateTo(
          target,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
        );
        return;
      }
      _chatScrollController.jumpTo(target);
    });
  }

  void _showDrawer() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: true);
    });
  }

  void _closeDrawer() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: false);
    });
  }

  void _showEmpty() {
    final bridge = widget.bridge;
    _dismissMessageMenu();
    if (bridge != null) {
      unawaited(_createSessionFromBridge(bridge));
      return;
    }
    setState(() {
      _state = OpenCrayChatSeedData.empty(
        widget.copy,
      ).copyWith(drawerOpen: false);
    });
  }

  Future<void> _createSessionFromBridge(OpenCrayHostBridge bridge) async {
    try {
      await bridge.createChatSession();
      if (!mounted) {
        return;
      }
      _closeDrawer();
    } catch (_) {
      if (!mounted) {
        return;
      }
      _showSessionActionFailed();
    }
  }

  void _togglePlusMenu() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      final composer = _state.composer;
      _state = _state.copyWith(
        composer: composer.copyWith(
          showAddMenu: !composer.showAddMenu,
          addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _handleAddAction(ChatAddActionData action) {
    if (action.label == widget.copy.chatActionCommand) {
      setState(() {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
              widget.copy,
            ),
            clearSelectedCommand: true,
          ),
        );
      });
      return;
    }

    if (_attachmentKindForAction(action.label) ==
            OpenCrayChatDraftAttachmentKind.image &&
        _currentComposerImageCount >= _chatComposerMaxImageAttachments) {
      _showComposerNotice(_attachmentImageLimitMessage(skippedCount: 0));
      return;
    }

    final bridge = widget.bridge;
    if (bridge == null) {
      setState(() {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        final attachment = _attachmentForAction(action.label);
        final alreadyPresent = currentAttachments.any(
          (ChatAttachmentData item) => item.id == attachment.id,
        );
        if (!alreadyPresent) {
          currentAttachments.add(attachment);
        }
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            attachments: currentAttachments,
            showAddMenu: true,
            addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          ),
        );
      });
      return;
    }

    unawaited(_pickAttachmentsFromBridge(action, bridge));
  }

  Future<void> _pickAttachmentsFromBridge(
    ChatAddActionData action,
    OpenCrayHostBridge bridge,
  ) async {
    try {
      final pickedAttachments = await bridge.pickChatAttachments(
        kind: _attachmentKindForAction(action.label),
      );
      if (!mounted || pickedAttachments.isEmpty) {
        return;
      }
      int duplicateCount = 0;
      int skippedImageCount = 0;
      setState(() {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        int imageCount = currentAttachments.where((ChatAttachmentData item) {
          return item.kind == ChatAttachmentKind.image;
        }).length;
        for (final attachment in pickedAttachments) {
          final draft = _draftAttachmentForComposer(attachment);
          final alreadyPresent = currentAttachments.any(
            (ChatAttachmentData item) => item.id == draft.id,
          );
          if (!alreadyPresent) {
            if (draft.kind == ChatAttachmentKind.image &&
                imageCount >= _chatComposerMaxImageAttachments) {
              skippedImageCount += 1;
              continue;
            }
            currentAttachments.add(draft);
            if (draft.kind == ChatAttachmentKind.image) {
              imageCount += 1;
            }
          } else {
            duplicateCount += 1;
          }
        }
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            attachments: currentAttachments,
            showAddMenu: true,
            addActions: OpenCrayChatSeedData.sampleAddActions(widget.copy),
          ),
        );
      });
      final notices = <String>[
        if (duplicateCount > 0) _attachmentDuplicateMessage(duplicateCount),
        if (skippedImageCount > 0)
          _attachmentImageLimitMessage(skippedCount: skippedImageCount),
      ];
      if (mounted && notices.isNotEmpty) {
        _showComposerNotice(notices.join(' '));
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showComposerNotice(_attachmentPickerFailureMessage(error));
    }
  }

  void _showCommandMenu() {
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
            widget.copy,
          ),
          clearSelectedCommand: true,
        ),
      );
    });
  }

  Future<void> _sendCurrentState() async {
    _dismissMessageMenu();
    if (!_state.isInputEnabled) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      final text = _composerController.text.trim();
      final attachments = _state.composer.attachments
          .map((ChatAttachmentData attachment) => attachment.draftAttachment)
          .whereType<OpenCrayChatDraftAttachment>()
          .toList(growable: false);
      if (text.isEmpty && attachments.isEmpty) {
        return;
      }
      try {
        await bridge.submitChatMessage(text, attachments: attachments);
        if (!mounted) {
          return;
        }
        setState(() {
          _composerController.clear();
          _state = _state.copyWith(
            composer: _state.composer.copyWith(
              attachments: const <ChatAttachmentData>[],
              commandOptions: const <ChatCommandOptionData>[],
              showAddMenu: false,
              clearSelectedCommand: true,
            ),
          );
        });
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showComposerNotice(widget.copy.chatSubmitFailed);
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          attachments: const <ChatAttachmentData>[],
          commandOptions: const <ChatCommandOptionData>[],
          showAddMenu: false,
          clearSelectedCommand: true,
        ),
      );
    });
  }

  void _handleChatSnapshot(OpenCrayChatSnapshot snapshot) {
    if (!shouldReplaceObservedChatSnapshot(_latestChatSnapshot, snapshot)) {
      return;
    }
    _reconcileLiveAssistantDraftOverrides(snapshot.runtimeActivity);
    _latestChatSnapshot = snapshot;
    _applyHostState();
  }

  void _handleChatRuntimeSnapshot(OpenCrayChatRuntimeSnapshot snapshot) {
    final OpenCrayChatRuntimeSnapshot resolvedSnapshot =
        _latestChatRuntimeSnapshot != null &&
            !_runtimeSnapshotsShareSession(
              _latestChatRuntimeSnapshot!,
              snapshot,
            ) &&
            snapshot.sessionId.trim().isNotEmpty
        ? snapshot
        : resolveChatRuntimeSnapshot(_latestChatRuntimeSnapshot, snapshot) ??
              snapshot;
    if (!shouldReplaceObservedRuntimeSnapshot(
      _latestChatRuntimeSnapshot,
      resolvedSnapshot,
    )) {
      return;
    }
    final List<String> activeRunSummaries = resolvedSnapshot.activeRuns
        .map(
          (run) =>
              '${run.runId}:${run.managedProcesses.length}/${run.runningManagedProcessCount}/${run.hasLiveManagedProcesses}',
        )
        .toList(growable: false);
    _runTraceDebug(
      'feature.runtime session=${resolvedSnapshot.sessionId} activeRuns=${resolvedSnapshot.activeRuns.length} retainedRuns=${resolvedSnapshot.retainedRuns.length} events=${resolvedSnapshot.events.length} runs=${activeRunSummaries.join(';')}',
    );
    _reconcileLiveAssistantDraftOverrides(resolvedSnapshot);
    _latestChatRuntimeSnapshot = resolvedSnapshot;
    final String resolvedSessionId = resolvedSnapshot.sessionId.trim();
    if (resolvedSessionId.isNotEmpty) {
      _runtimeEventDeltaSequenceBySession.remove(resolvedSessionId);
    }
    _applyHostState();
  }

  void _handleRuntimeEventDelta(OpenCrayChatRuntimeEventDelta delta) {
    if (!mounted ||
        _latestChatSnapshot == null ||
        !delta.hasRuntimeActivityPatch) {
      return;
    }
    final String sessionId = delta.sessionId.trim();
    if (sessionId.isEmpty || sessionId != _activeSessionId) {
      return;
    }
    if (_runtimeEventDeltaResyncInFlight) {
      _queuedRuntimeEventDeltaAfterResync = delta;
      return;
    }
    final int previousSequence =
        _runtimeEventDeltaSequenceBySession[sessionId] ?? 0;
    if (previousSequence > 0 &&
        delta.sequence > 0 &&
        delta.sequence != previousSequence + 1) {
      _resyncRuntimeSnapshotAfterDeltaMiss();
      return;
    }
    final OpenCrayChatRuntimeSnapshot? currentSnapshot =
        _latestChatRuntimeSnapshot?.sessionId.trim() == sessionId
        ? _latestChatRuntimeSnapshot
        : _latestChatSnapshot?.runtimeActivity?.sessionId.trim() == sessionId
        ? _latestChatSnapshot?.runtimeActivity
        : null;
    if (currentSnapshot == null &&
        delta.activeRuns.isEmpty &&
        delta.retainedRuns.isEmpty &&
        delta.events.isEmpty &&
        delta.subAgents.isEmpty &&
        delta.liveAssistantDrafts.isEmpty &&
        delta.hostLifecycle == null &&
        delta.updatedAtEpochMs <= 0) {
      _resyncRuntimeSnapshotAfterDeltaMiss();
      return;
    }
    final int latestDeltaEpochMs = delta.events.fold<int>(
      0,
      (latest, event) => math.max(latest, event.emittedAtEpochMs),
    );
    final OpenCrayChatRuntimeSnapshot deltaSnapshot =
        OpenCrayChatRuntimeSnapshot(
          sessionId: sessionId,
          activeRuns: delta.activeRuns,
          retainedRuns: delta.retainedRuns,
          subAgents: delta.subAgents,
          events: delta.events,
          liveAssistantDrafts: delta.liveAssistantDrafts,
          hostLifecycle: delta.hostLifecycle,
          updatedAtEpochMs: math.max(
            delta.updatedAtEpochMs,
            latestDeltaEpochMs,
          ),
        );
    final OpenCrayChatRuntimeSnapshot patchedSnapshot = currentSnapshot == null
        ? deltaSnapshot
        : _mergeRuntimeDeltaSnapshot(currentSnapshot, deltaSnapshot);
    if (!shouldReplaceObservedRuntimeSnapshot(
      currentSnapshot,
      patchedSnapshot,
    )) {
      if (delta.sequence > 0) {
        _runtimeEventDeltaSequenceBySession[sessionId] = delta.sequence;
      }
      return;
    }
    _latestChatRuntimeSnapshot = patchedSnapshot;
    if (delta.sequence > 0) {
      _runtimeEventDeltaSequenceBySession[sessionId] = delta.sequence;
    }
    _queueRuntimeActivityPatch(patchedSnapshot);
  }

  Future<void> _resyncRuntimeSnapshotAfterDeltaMiss() async {
    if (_runtimeEventDeltaResyncInFlight) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    _runtimeEventDeltaResyncInFlight = true;
    bool resynced = false;
    try {
      final OpenCrayChatRuntimeSnapshot snapshot = await bridge
          .loadChatRuntimeSnapshot();
      if (!mounted) {
        return;
      }
      _handleChatRuntimeSnapshot(snapshot);
      final String resyncedSessionId = snapshot.sessionId.trim();
      if (resyncedSessionId.isNotEmpty) {
        _runtimeEventDeltaSequenceBySession.remove(resyncedSessionId);
      }
      resynced = true;
    } finally {
      _runtimeEventDeltaResyncInFlight = false;
    }
    final OpenCrayChatRuntimeEventDelta? queuedDelta =
        _queuedRuntimeEventDeltaAfterResync;
    _queuedRuntimeEventDeltaAfterResync = null;
    if (mounted && resynced && queuedDelta != null) {
      _handleRuntimeEventDelta(queuedDelta);
    }
  }

  void _handleLiveAssistantDraftEvent(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    _storeLiveAssistantDraftOverride(event);
    if (!mounted || _latestChatSnapshot == null) {
      return;
    }
    final String sessionId = event.sessionId.trim();
    if (sessionId.isEmpty || sessionId != _activeSessionId) {
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    final OpenCrayChatRuntimeSnapshot
    effectiveRuntime = OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns:
          runtimeSnapshot?.activeRuns ?? const <OpenCrayChatRunSnapshot>[],
      retainedRuns:
          runtimeSnapshot?.retainedRuns ?? const <OpenCrayChatRunSnapshot>[],
      subAgents:
          runtimeSnapshot?.subAgents ?? const <OpenCrayChatSubAgentSnapshot>[],
      events:
          runtimeSnapshot?.events ?? const <OpenCrayChatRuntimeEventSnapshot>[],
      liveAssistantDrafts:
          runtimeSnapshot?.liveAssistantDrafts ??
          const <OpenCrayChatLiveAssistantDraftSnapshot>[],
      hostLifecycle: runtimeSnapshot?.hostLifecycle,
      updatedAtEpochMs: math.max(
        runtimeSnapshot?.updatedAtEpochMs ?? 0,
        event.updatedAtEpochMs,
      ),
    );
    _queueRuntimeActivityPatch(effectiveRuntime);
  }

  void _storeLiveAssistantDraftOverride(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    final String sessionId = event.sessionId.trim();
    final String pendingMessageId = event.pendingMessageId.trim();
    if (sessionId.isEmpty || pendingMessageId.isEmpty) {
      return;
    }
    final Map<String, int> sessionEventEpochs =
        _liveAssistantDraftEventEpochBySessionAndMessage.putIfAbsent(
          sessionId,
          () => <String, int>{},
        );
    final int eventEpochMs = event.updatedAtEpochMs;
    final int previousEventEpochMs = sessionEventEpochs[pendingMessageId] ?? 0;
    if (eventEpochMs > 0 && previousEventEpochMs > eventEpochMs) {
      return;
    }
    if (eventEpochMs > 0) {
      sessionEventEpochs[pendingMessageId] = eventEpochMs;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> sessionDrafts =
        _liveAssistantDraftOverridesBySession.putIfAbsent(
          sessionId,
          () => <String, OpenCrayChatLiveAssistantDraftSnapshot>{},
        );
    if (event.cleared) {
      sessionDrafts.remove(pendingMessageId);
      if (sessionDrafts.isEmpty) {
        _liveAssistantDraftOverridesBySession.remove(sessionId);
      }
      return;
    }
    sessionDrafts[pendingMessageId] = OpenCrayChatLiveAssistantDraftSnapshot(
      runId: event.runId,
      taskId: event.taskId,
      pendingMessageId: pendingMessageId,
      text: event.text,
      updatedAtEpochMs: event.updatedAtEpochMs,
    );
  }

  void _reconcileLiveAssistantDraftOverrides(
    OpenCrayChatRuntimeSnapshot? authoritativeSnapshot,
  ) {
    final String sessionId = authoritativeSnapshot?.sessionId.trim() ?? '';
    if (sessionId.isEmpty) {
      return;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot>? sessionDrafts =
        _liveAssistantDraftOverridesBySession[sessionId];
    if (sessionDrafts == null || sessionDrafts.isEmpty) {
      return;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot>
    authoritativeDraftsByMessageId =
        <String, OpenCrayChatLiveAssistantDraftSnapshot>{
          for (final draft in authoritativeSnapshot!.liveAssistantDrafts)
            if (draft.pendingMessageId.trim().isNotEmpty)
              draft.pendingMessageId.trim(): draft,
        };
    final int authoritativeVersion = runtimeSnapshotVersion(
      authoritativeSnapshot,
    );
    final List<String> keysToRemove = <String>[];
    sessionDrafts.forEach((pendingMessageId, overrideDraft) {
      final OpenCrayChatLiveAssistantDraftSnapshot? authoritativeDraft =
          authoritativeDraftsByMessageId[pendingMessageId];
      if (authoritativeDraft != null &&
          authoritativeDraft.updatedAtEpochMs >=
              overrideDraft.updatedAtEpochMs) {
        keysToRemove.add(pendingMessageId);
        return;
      }
      if (authoritativeDraft == null &&
          authoritativeVersion >= overrideDraft.updatedAtEpochMs) {
        keysToRemove.add(pendingMessageId);
      }
    });
    for (final String key in keysToRemove) {
      sessionDrafts.remove(key);
    }
    if (sessionDrafts.isEmpty) {
      _liveAssistantDraftOverridesBySession.remove(sessionId);
    }
  }

  String? _archivedTodoFingerprint(OpenCrayChatSnapshot snapshot) {
    if (snapshot.todoState != 'archived_completed' || snapshot.todos.isEmpty) {
      return null;
    }
    final int? completedAtEpochMs = snapshot.todoCompletedAtEpochMs;
    if (completedAtEpochMs == null || completedAtEpochMs <= 0) {
      return null;
    }
    final String encodedTodos = snapshot.todos
        .map(
          (todo) => '${todo.content}|${todo.status}|${todo.activeForm ?? ''}',
        )
        .join('||');
    return '$completedAtEpochMs::$encodedTodos';
  }

  void _cancelTodoArchiveHideTimer() {
    _todoArchiveHideTimer?.cancel();
    _todoArchiveHideTimer = null;
    _scheduledTodoArchiveFingerprint = null;
  }

  void _syncTodoArchiveVisibility(OpenCrayChatSnapshot snapshot) {
    final String? fingerprint = _archivedTodoFingerprint(snapshot);
    if (fingerprint == null) {
      _hiddenArchivedTodoFingerprint = null;
      _cancelTodoArchiveHideTimer();
      return;
    }
    if (_hiddenArchivedTodoFingerprint == fingerprint) {
      _cancelTodoArchiveHideTimer();
      return;
    }
    if (_scheduledTodoArchiveFingerprint == fingerprint &&
        _todoArchiveHideTimer != null) {
      return;
    }
    final int hideDelayMs = snapshot.todoHideDelayMs ?? 0;
    if (hideDelayMs <= 0) {
      _hiddenArchivedTodoFingerprint = fingerprint;
      _cancelTodoArchiveHideTimer();
      return;
    }
    _cancelTodoArchiveHideTimer();
    _scheduledTodoArchiveFingerprint = fingerprint;
    _todoArchiveHideTimer = Timer(Duration(milliseconds: hideDelayMs), () {
      if (!mounted) {
        return;
      }
      _scheduledTodoArchiveFingerprint = null;
      _hiddenArchivedTodoFingerprint = fingerprint;
      _applyHostState();
    });
  }

  List<ChatTodoItemData> _mapVisibleTodos(OpenCrayChatSnapshot snapshot) {
    final String? archivedFingerprint = _archivedTodoFingerprint(snapshot);
    if (archivedFingerprint != null &&
        _hiddenArchivedTodoFingerprint == archivedFingerprint) {
      return const <ChatTodoItemData>[];
    }
    return snapshot.todos
        .map(
          (OpenCrayChatTodoSnapshot todo) => ChatTodoItemData(
            content: todo.content,
            status: switch (todo.status) {
              'completed' => ChatTodoStatus.completed,
              'in_progress' ||
              'in-progress' ||
              'inprogress' => ChatTodoStatus.inProgress,
              _ => ChatTodoStatus.pending,
            },
            activeForm: todo.activeForm,
          ),
        )
        .toList(growable: false);
  }

  void _applyHostState() {
    if (!mounted) {
      return;
    }
    final snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    _pruneLocalDeletionTombstones(snapshot);
    _syncTodoArchiveVisibility(snapshot);
    final ChatFeatureState nextState = _mapSnapshot(
      snapshot,
      _latestChatRuntimeSnapshot,
    );
    final ChatFeatureState resolvedNextState = _applyLocalDeletionTombstones(
      nextState.copyWith(
        drawerOpen: _state.drawerOpen,
        composer: _composerStateForHostSnapshot(nextState),
      ),
    );
    if (chatFeatureStatesEquivalent(_state, resolvedNextState)) {
      _syncSandboxSessionAutoRefresh();
      _syncSandboxSessionLifecycleAutoRefresh();
      return;
    }
    final bool shouldScrollToBottom =
        resolvedNextState.messages.length > _state.messages.length ||
        resolvedNextState.runTraces.length > _state.runTraces.length ||
        resolvedNextState.pendingApprovals.length >
            _state.pendingApprovals.length;
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => resolvedNextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _state = resolvedNextState;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom();
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }

  void _queueRuntimeActivityPatch(OpenCrayChatRuntimeSnapshot runtimeSnapshot) {
    _pendingRuntimeProjectionSnapshot = runtimeSnapshot;
    if (_runtimeProjectionFlushTimer != null) {
      return;
    }
    _runtimeProjectionFlushTimer = Timer(
      const Duration(milliseconds: 16),
      _flushQueuedRuntimeActivityPatch,
    );
  }

  void _flushQueuedRuntimeActivityPatch() {
    _runtimeProjectionFlushTimer = null;
    if (!mounted) {
      _pendingRuntimeProjectionSnapshot = null;
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _pendingRuntimeProjectionSnapshot ??
        _latestChatRuntimeSnapshot ??
        _latestChatSnapshot?.runtimeActivity;
    _pendingRuntimeProjectionSnapshot = null;
    if (runtimeSnapshot == null) {
      return;
    }
    _applyRuntimeActivityPatch(runtimeSnapshot);
  }

  void _applyRuntimeActivityPatch(OpenCrayChatRuntimeSnapshot runtimeSnapshot) {
    if (!mounted) {
      return;
    }
    final OpenCrayChatSnapshot? snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(
          expectedSessionId: _snapshotActiveSessionId(snapshot),
          embedded: snapshot.runtimeActivity,
          streamed: runtimeSnapshot,
        );
    final _RuntimeProjectionPatch runtimeProjection = _mapRuntimeProjection(
      snapshot,
      effectiveRuntime,
    );
    final ChatFeatureState nextState = _applyLocalDeletionTombstones(
      _state.copyWith(
        variant:
            runtimeProjection.messages.isEmpty &&
                runtimeProjection.runTraces.isEmpty &&
                _state.composer.todos.isEmpty &&
                _state.pendingApprovals.isEmpty
            ? ChatPrototypeVariant.empty
            : ChatPrototypeVariant.main,
        messages: runtimeProjection.messages,
        runTraces: runtimeProjection.runTraces,
        emptyThreadHeight:
            runtimeProjection.messages.isEmpty &&
                runtimeProjection.runTraces.isEmpty
            ? 260
            : 0,
      ),
    );
    final bool shouldScrollToBottom =
        nextState.messages.length > _state.messages.length ||
        nextState.runTraces.length > _state.runTraces.length;
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    // Runtime deltas must propagate fresh trace objects to any open inspector,
    // even when the visible projection is structurally equivalent.
    setState(() {
      _state = nextState;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom();
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }

  void _cancelScheduledSandboxSessionAutoRefresh() {
    _sandboxSessionAutoRefreshTimer?.cancel();
    _sandboxSessionAutoRefreshTimer = null;
    _scheduledSandboxSessionRefreshAnchor = null;
    _queuedSandboxSessionRefreshAnchors.clear();
  }

  void _cancelScheduledSandboxSessionLifecycleRefresh() {
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _sandboxSessionLifecycleRefreshTimer = null;
    _scheduledSandboxSessionLifecycleRefreshKey = null;
    _queuedSandboxSessionLifecycleRefresh = false;
  }

  void _resetSandboxSessionAutoRefreshTracking() {
    _cancelScheduledSandboxSessionAutoRefresh();
    _lastSandboxSessionRefreshAnchor = null;
  }

  void _syncSandboxSessionAutoRefresh() {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (bridge == null || runtimeSnapshot == null) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    final String? anchor = _sandboxSessionAutoRefreshAnchor(runtimeSnapshot);
    if (anchor == null) {
      _cancelScheduledSandboxSessionAutoRefresh();
      return;
    }
    if (_lastSandboxSessionRefreshAnchor == anchor ||
        _scheduledSandboxSessionRefreshAnchor == anchor) {
      return;
    }
    if (_sandboxSessionRefreshInFlight) {
      _enqueueSandboxSessionRefreshAnchor(anchor);
      return;
    }
    _scheduleSandboxSessionAutoRefresh(anchor);
  }

  void _syncSandboxSessionLifecycleAutoRefresh() {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (widget.bridge == null || runtimeSnapshot == null) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    final _SandboxSessionLifecycleRefreshSchedule? schedule =
        _sandboxSessionLifecycleRefreshSchedule(runtimeSnapshot);
    if (schedule == null) {
      _cancelScheduledSandboxSessionLifecycleRefresh();
      return;
    }
    if (_scheduledSandboxSessionLifecycleRefreshKey == schedule.key) {
      return;
    }
    _scheduleSandboxSessionLifecycleRefresh(schedule);
  }

  void _scheduleSandboxSessionAutoRefresh(String anchor) {
    _sandboxSessionAutoRefreshTimer?.cancel();
    _scheduledSandboxSessionRefreshAnchor = anchor;
    _sandboxSessionAutoRefreshTimer = Timer(
      chatSandboxSessionAutoRefreshDebounce,
      () {
        _sandboxSessionAutoRefreshTimer = null;
        _scheduledSandboxSessionRefreshAnchor = null;
        unawaited(_runSandboxSessionAutoRefresh(anchor));
      },
    );
  }

  void _scheduleSandboxSessionLifecycleRefresh(
    _SandboxSessionLifecycleRefreshSchedule schedule,
  ) {
    _sandboxSessionLifecycleRefreshTimer?.cancel();
    _scheduledSandboxSessionLifecycleRefreshKey = schedule.key;
    _sandboxSessionLifecycleRefreshTimer = Timer(
      Duration(milliseconds: schedule.delayMs),
      () {
        _sandboxSessionLifecycleRefreshTimer = null;
        _scheduledSandboxSessionLifecycleRefreshKey = null;
        unawaited(_runSandboxSessionLifecycleRefresh());
      },
    );
  }

  Future<void> _runSandboxSessionAutoRefresh(String anchor) async {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null || _sandboxSessionRefreshInFlight) {
      return;
    }
    _sandboxSessionRefreshInFlight = true;
    try {
      await bridge.refreshSandboxSessionInfo();
      _lastSandboxSessionRefreshAnchor = anchor;
    } catch (_) {
      _lastSandboxSessionRefreshAnchor = anchor;
    } finally {
      _sandboxSessionRefreshInFlight = false;
      final String? queuedAnchor = _dequeueSandboxSessionRefreshAnchor();
      final bool canContinue = mounted;
      final bool shouldScheduleQueuedAnchor =
          canContinue &&
          queuedAnchor != null &&
          queuedAnchor != _lastSandboxSessionRefreshAnchor &&
          widget.isTabActive &&
          _selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud;
      if (shouldScheduleQueuedAnchor) {
        _scheduleSandboxSessionAutoRefresh(queuedAnchor);
      } else if (canContinue) {
        _syncSandboxSessionAutoRefresh();
      }
      if (canContinue) {
        _syncSandboxSessionLifecycleAutoRefresh();
      }
    }
  }

  void _enqueueSandboxSessionRefreshAnchor(String anchor) {
    final String normalizedAnchor = anchor.trim();
    if (normalizedAnchor.isEmpty ||
        normalizedAnchor == _scheduledSandboxSessionRefreshAnchor ||
        normalizedAnchor == _lastSandboxSessionRefreshAnchor ||
        _queuedSandboxSessionRefreshAnchors.contains(normalizedAnchor)) {
      return;
    }
    _queuedSandboxSessionRefreshAnchors.add(normalizedAnchor);
  }

  String? _dequeueSandboxSessionRefreshAnchor() {
    while (_queuedSandboxSessionRefreshAnchors.isNotEmpty) {
      final String anchor = _queuedSandboxSessionRefreshAnchors.removeAt(0);
      if (anchor == _lastSandboxSessionRefreshAnchor) {
        continue;
      }
      return anchor;
    }
    return null;
  }

  Future<void> _runSandboxSessionLifecycleRefresh() async {
    if (!mounted ||
        !widget.isTabActive ||
        _selectedRuntimeEnvironment != _ChatRuntimeEnvironment.cloud) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    if (_sandboxSessionRefreshInFlight) {
      _queuedSandboxSessionLifecycleRefresh = true;
      return;
    }
    _sandboxSessionRefreshInFlight = true;
    try {
      await bridge.refreshSandboxSessionInfo();
    } catch (_) {
      // Ignore lifecycle refresh failures and wait for the next schedule.
    } finally {
      _sandboxSessionRefreshInFlight = false;
      final bool queuedLifecycleRefresh = _queuedSandboxSessionLifecycleRefresh;
      _queuedSandboxSessionLifecycleRefresh = false;
      final bool canContinue = mounted;
      if (canContinue) {
        _syncSandboxSessionAutoRefresh();
        _syncSandboxSessionLifecycleAutoRefresh();
        if (queuedLifecycleRefresh &&
            _sandboxSessionLifecycleRefreshTimer == null &&
            _selectedRuntimeEnvironment == _ChatRuntimeEnvironment.cloud) {
          _scheduleSandboxSessionLifecycleRefresh(
            const _SandboxSessionLifecycleRefreshSchedule(
              key: 'queued',
              delayMs: 1000,
            ),
          );
        }
      }
    }
  }

  String? _sandboxSessionAutoRefreshAnchor(
    OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  ) {
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              right.emittedAtEpochMs.compareTo(left.emittedAtEpochMs),
        );
    final Set<String> activeRunIds = runtimeSnapshot.activeRuns
        .map((run) => run.runId.trim())
        .where((runId) => runId.isNotEmpty && !runId.startsWith('runtime-'))
        .toSet();
    int? latestSessionInfoEventEpochMs;
    for (final OpenCrayChatRuntimeEventSnapshot event in sortedEvents) {
      if (_isSandboxSessionInfoToolResult(event)) {
        latestSessionInfoEventEpochMs ??= event.emittedAtEpochMs;
        continue;
      }
      if (!_isSandboxExecutionToolResult(event)) {
        continue;
      }
      final String runId = event.runId.trim();
      if (runId.isEmpty || activeRunIds.contains(runId)) {
        return null;
      }
      if (latestSessionInfoEventEpochMs != null &&
          latestSessionInfoEventEpochMs > event.emittedAtEpochMs) {
        return null;
      }
      return '$runId:${event.emittedAtEpochMs}';
    }
    return null;
  }

  bool _isSandboxExecutionToolResult(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.kind != 'tool_result') {
      return false;
    }
    final String toolName = event.toolName?.trim().toLowerCase() ?? '';
    if (toolName.startsWith('sandbox_')) {
      return false;
    }
    return _resultMetadataValue(event, 'sandboxProvider') != null;
  }

  bool _isSandboxSessionInfoToolResult(OpenCrayChatRuntimeEventSnapshot event) {
    final String toolName = event.toolName?.trim().toLowerCase() ?? '';
    return event.kind == 'tool_result' && toolName == 'sandbox_session_info';
  }

  _SandboxSessionLifecycleRefreshSchedule?
  _sandboxSessionLifecycleRefreshSchedule(
    OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  ) {
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              right.emittedAtEpochMs.compareTo(left.emittedAtEpochMs),
        );
    for (final OpenCrayChatRuntimeEventSnapshot event in sortedEvents) {
      if (!_isSandboxSessionInfoToolResult(event)) {
        continue;
      }
      final int? delayMs = _resultMetadataInt(
        event,
        'sandboxSessionAutoRefreshAfterMs',
      );
      if (delayMs == null || delayMs <= 0) {
        return null;
      }
      return _SandboxSessionLifecycleRefreshSchedule(
        key: '${event.runId}:${event.emittedAtEpochMs}:$delayMs',
        delayMs: delayMs,
      );
    }
    return null;
  }

  Future<void> _approvePendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) => bridge.approveChatApproval(approval.approvalId),
    );
  }

  Future<void> _approvePendingApprovalForSession(
    ChatPendingApprovalData approval,
  ) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) =>
          bridge.approveChatApprovalForSession(approval.approvalId),
    );
  }

  Future<void> _rejectPendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) => bridge.rejectChatApproval(approval.approvalId),
    );
  }

  Future<void> _runApprovalAction({
    required String approvalId,
    required Future<void> Function(OpenCrayHostBridge bridge) action,
  }) async {
    final bridge = widget.bridge;
    if (bridge == null || _approvalTaskIdsInFlight.contains(approvalId)) {
      return;
    }
    setState(() {
      _approvalTaskIdsInFlight.add(approvalId);
    });
    try {
      await action(bridge);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(widget.copy.chatApprovalActionFailed)),
      );
    } finally {
      if (!mounted) {
        _approvalTaskIdsInFlight.remove(approvalId);
      } else {
        setState(() {
          _approvalTaskIdsInFlight.remove(approvalId);
        });
      }
    }
  }

  Future<void> _retryRunTrace(ChatRunTraceData trace) async {
    final bridge = widget.bridge;
    final retryId = trace.retryId;
    if (bridge == null ||
        !trace.isRetryable ||
        _retryRunIdsInFlight.contains(retryId)) {
      return;
    }
    setState(() {
      _retryRunIdsInFlight.add(retryId);
    });
    try {
      await bridge.retryChatRun(retryId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.copy.isChinese
                ? '无法重新启动这次运行。'
                : 'Unable to restart this run.',
          ),
        ),
      );
    } finally {
      if (!mounted) {
        _retryRunIdsInFlight.remove(retryId);
      } else {
        setState(() {
          _retryRunIdsInFlight.remove(retryId);
        });
      }
    }
  }

  void _armRunInterruptTrace(ChatRunTraceData trace) {
    final String interruptId = trace.interruptId;
    if (!trace.canInterrupt ||
        interruptId.isEmpty ||
        _interruptRunIdsInFlight.contains(interruptId)) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = interruptId;
    });
  }

  void _dismissRunInterruptTrace(ChatRunTraceData trace) {
    final String interruptId = trace.interruptId;
    if (_interruptConfirmRunId != interruptId) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = null;
    });
  }

  Future<void> _interruptRunTrace(ChatRunTraceData trace) async {
    final bridge = widget.bridge;
    final String interruptId = trace.interruptId;
    if (bridge == null ||
        !trace.canInterrupt ||
        interruptId.isEmpty ||
        _interruptRunIdsInFlight.contains(interruptId)) {
      return;
    }
    setState(() {
      _interruptConfirmRunId = null;
      _interruptRunIdsInFlight.add(interruptId);
    });
    try {
      await bridge.interruptChatRun(interruptId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(widget.copy.chatRunInterruptFailed)),
      );
    } finally {
      if (!mounted) {
        _interruptRunIdsInFlight.remove(interruptId);
      } else {
        setState(() {
          _interruptRunIdsInFlight.remove(interruptId);
        });
      }
    }
  }

  void _removeAttachment(ChatAttachmentData attachment) {
    setState(() {
      final attachments = List<ChatAttachmentData>.of(
        _state.composer.attachments,
      )..removeWhere((ChatAttachmentData item) => item.id == attachment.id);
      _state = _state.copyWith(
        composer: _state.composer.copyWith(attachments: attachments),
      );
    });
  }

  OpenCrayChatDraftAttachmentKind _attachmentKindForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatDraftAttachmentKind.image;
    }
    return OpenCrayChatDraftAttachmentKind.file;
  }

  ChatAttachmentData _attachmentForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
        (ChatAttachmentData item) => item.kind == ChatAttachmentKind.image,
      );
    }
    return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
      (ChatAttachmentData item) => item.kind == ChatAttachmentKind.file,
    );
  }

  ChatAttachmentData _draftAttachmentForComposer(
    OpenCrayChatDraftAttachment attachment,
  ) {
    final bool isImage =
        attachment.kind == OpenCrayChatDraftAttachmentKind.image;
    final bool isVoice =
        attachment.kind == OpenCrayChatDraftAttachmentKind.voice;
    final String detail =
        attachment.sizeBytes != null && attachment.sizeBytes! >= 0
        ? _formatAttachmentBytes(attachment.sizeBytes!)
        : attachment.durationMs != null && attachment.durationMs! > 0
        ? _formatAttachmentDuration(attachment.durationMs!)
        : (widget.copy.isChinese
              ? (isImage ? '图片附件' : (isVoice ? '语音附件' : '文件附件'))
              : (isImage
                    ? 'Image attachment'
                    : (isVoice ? 'Voice attachment' : 'File attachment')));
    return ChatAttachmentData(
      id: attachment.id,
      kind: isImage
          ? ChatAttachmentKind.image
          : (isVoice ? ChatAttachmentKind.voice : ChatAttachmentKind.file),
      label: attachment.displayName,
      detail: detail,
      accentColor: isImage
          ? const Color(0xFFE6F0FF)
          : (isVoice ? const Color(0xFFEAF7F4) : const Color(0xFFF2F3F7)),
      draftAttachment: attachment,
    );
  }

  OpenCrayChatDraftAttachment? _draftAttachmentForMessageAttachment(
    ChatMessageAttachmentData attachment,
  ) {
    final String chatAttachmentId = attachment.attachmentId.trim();
    final String relativePath = attachment.localPath.trim();
    if (chatAttachmentId.isEmpty && relativePath.isEmpty) {
      return null;
    }
    return OpenCrayChatDraftAttachment(
      kind: switch (attachment.kind) {
        ChatAttachmentKind.image => OpenCrayChatDraftAttachmentKind.image,
        ChatAttachmentKind.voice => OpenCrayChatDraftAttachmentKind.voice,
        ChatAttachmentKind.file => OpenCrayChatDraftAttachmentKind.file,
      },
      displayName: attachment.displayName,
      relativePath: relativePath,
      chatAttachmentId: chatAttachmentId.isEmpty ? null : chatAttachmentId,
      mimeType: attachment.mimeType,
      sizeBytes: attachment.sizeBytes,
      durationMs: attachment.durationMs,
      waveformBars: attachment.waveformBars,
      transcriptText: attachment.transcriptText,
    );
  }

  List<OpenCrayChatDraftAttachment> _draftAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return message.attachments
        .map(_draftAttachmentForMessageAttachment)
        .whereType<OpenCrayChatDraftAttachment>()
        .toList(growable: false);
  }

  List<ChatAttachmentData> _composerAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return _draftAttachmentsForMessage(
      message,
    ).map(_draftAttachmentForComposer).toList(growable: false);
  }

  int get _currentComposerImageCount =>
      _state.composer.attachments.where((ChatAttachmentData attachment) {
        return attachment.kind == ChatAttachmentKind.image;
      }).length;

  String _attachmentDuplicateMessage(int duplicateCount) {
    if (widget.copy.isChinese) {
      return '已自动忽略 $duplicateCount 个重复附件。';
    }
    return duplicateCount == 1
        ? 'Ignored 1 duplicate attachment.'
        : 'Ignored $duplicateCount duplicate attachments.';
  }

  String _attachmentImageLimitMessage({required int skippedCount}) {
    if (widget.copy.isChinese) {
      return skippedCount > 0
          ? '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片，已忽略 $skippedCount 张。'
          : '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片。';
    }
    return skippedCount > 0
        ? 'Each message supports up to $_chatComposerMaxImageAttachments images. Skipped $skippedCount.'
        : 'Each message supports up to $_chatComposerMaxImageAttachments images.';
  }

  String _attachmentPickerFailureMessage(Object error) {
    final explicitMessage = switch (error) {
      PlatformException(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      UnsupportedError(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      _ => _normalizeAttachmentErrorMessage(error.toString()),
    };
    if (explicitMessage != null) {
      return explicitMessage;
    }
    return widget.copy.isChinese ? '无法添加附件。' : 'Unable to add attachment.';
  }

  String? _normalizeAttachmentErrorMessage(String rawMessage) {
    var message = rawMessage.trim();
    if (message.isEmpty) {
      return null;
    }
    if (message.startsWith('Bad state: ')) {
      return null;
    }
    if (message.startsWith('Unsupported operation: ')) {
      message = message.substring('Unsupported operation: '.length).trim();
    }
    if (message.startsWith('HttpException: ')) {
      message = message.substring('HttpException: '.length).trim();
    }
    message = message.replaceFirst(RegExp(r', uri = .*$'), '').trim();
    final localRuntimeMatch = RegExp(
      r'^Local runtime returned HTTP \d+: (.+)$',
    ).firstMatch(message);
    if (localRuntimeMatch != null) {
      message = localRuntimeMatch.group(1)?.trim() ?? '';
    }
    if (message.startsWith('{') && message.endsWith('}')) {
      final decoded = _tryDecodeAttachmentErrorMessage(message);
      if (decoded != null) {
        message = decoded;
      }
    }
    return message.isEmpty ? null : message;
  }

  String? _tryDecodeAttachmentErrorMessage(String payload) {
    try {
      final decoded = jsonDecode(payload);
      if (decoded is Map<Object?, Object?>) {
        final error = decoded['error'] as String?;
        return error?.trim().isNotEmpty == true ? error!.trim() : null;
      }
    } catch (_) {}
    return null;
  }

  void _showComposerNotice(String message) {
    final bridge = widget.bridge;
    if (bridge != null) {
      unawaited(bridge.showNativeToast(message));
      return;
    }
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  void _handleSessionSelected(ChatSessionListItemData session) {
    _dismissMessageMenu();
    final bridge = widget.bridge;
    if (bridge != null) {
      bridge.selectChatSession(session.sessionId);
      _closeDrawer();
      return;
    }
    setState(() {
      final sessions = _state.drawer.sessions
          .map(
            (item) => ChatSessionListItemData(
              sessionId: item.sessionId,
              title: item.title,
              preview: item.preview,
              meta: item.meta,
              isSelected: item.sessionId == session.sessionId,
              lastMessageAtEpochMs: item.lastMessageAtEpochMs,
              unreadCount: item.sessionId == session.sessionId
                  ? 0
                  : item.unreadCount,
            ),
          )
          .toList(growable: false);
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: sessions,
        ),
        drawerOpen: false,
      );
    });
  }

  Future<void> _handleSessionLongPress(
    ChatSessionListItemData session,
    Offset globalPosition,
  ) async {
    _dismissMessageMenu();
    FocusManager.instance.primaryFocus?.unfocus();
    unawaited(HapticFeedback.lightImpact());
    final RenderObject? overlayRenderObject = Overlay.of(
      context,
    ).context.findRenderObject();
    final Size overlaySize = overlayRenderObject is RenderBox
        ? overlayRenderObject.size
        : MediaQuery.of(context).size;
    final action = await showMenu<_SessionMenuAction>(
      context: context,
      position: RelativeRect.fromLTRB(
        globalPosition.dx,
        globalPosition.dy,
        overlaySize.width - globalPosition.dx,
        overlaySize.height - globalPosition.dy,
      ),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      popUpAnimationStyle: _sessionMenuAnimationStyle,
      items: <PopupMenuEntry<_SessionMenuAction>>[
        PopupMenuItem<_SessionMenuAction>(
          value: _SessionMenuAction.copy,
          child: Row(
            children: <Widget>[
              const Icon(Icons.copy_rounded, size: 18),
              const SizedBox(width: 10),
              Text(widget.copy.filesCopyAction),
            ],
          ),
        ),
        PopupMenuItem<_SessionMenuAction>(
          value: _SessionMenuAction.delete,
          child: Row(
            children: <Widget>[
              const Icon(
                Icons.delete_outline_rounded,
                size: 18,
                color: Color(0xFFB42318),
              ),
              const SizedBox(width: 10),
              Text(
                widget.copy.filesDeleteAction,
                style: const TextStyle(color: Color(0xFFB42318)),
              ),
            ],
          ),
        ),
      ],
    );
    if (!mounted || action == null) {
      return;
    }
    switch (action) {
      case _SessionMenuAction.copy:
        await _copySession(session);
        break;
      case _SessionMenuAction.delete:
        await _deleteSession(session);
        break;
    }
  }

  Future<void> _copySession(ChatSessionListItemData session) async {
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.copyChatSession(session.sessionId);
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showSessionActionFailed();
      }
      return;
    }
    setState(() {
      final copiedSession = ChatSessionListItemData(
        sessionId: '${session.sessionId}-copy-${_state.drawer.sessions.length}',
        title: _seedCopySessionTitle(session.title),
        preview: session.preview,
        meta: session.meta,
        isSelected: true,
        lastMessageAtEpochMs: session.lastMessageAtEpochMs,
      );
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: <ChatSessionListItemData>[
            copiedSession,
            ..._state.drawer.sessions.map(_copySessionTileUnselected),
          ],
        ),
      );
    });
  }

  Future<void> _deleteSession(ChatSessionListItemData session) async {
    final String sessionId = session.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _locallyDeletedSessionIds.add(sessionId);
        _state = _applyLocalDeletionTombstones(_state);
      });
      try {
        await bridge.deleteChatSession(sessionId);
      } catch (_) {
        if (!mounted) {
          return;
        }
        _locallyDeletedSessionIds.remove(sessionId);
        _applyHostState();
        _showSessionActionFailed();
      }
      return;
    }
    setState(() {
      final remainingSessions = _state.drawer.sessions
          .where((item) => item.sessionId != sessionId)
          .toList(growable: false);
      if (remainingSessions.isEmpty) {
        _state = OpenCrayChatSeedData.empty(widget.copy);
        return;
      }
      final String selectedSessionId = remainingSessions
          .firstWhere(
            (item) => item.isSelected,
            orElse: () => remainingSessions.first,
          )
          .sessionId;
      _state = _state.copyWith(
        drawer: ChatSessionsDrawerState(
          eyebrow: _state.drawer.eyebrow,
          title: _state.drawer.title,
          ctaLabel: _state.drawer.ctaLabel,
          sessions: remainingSessions
              .map(
                (item) => ChatSessionListItemData(
                  sessionId: item.sessionId,
                  title: item.title,
                  preview: item.preview,
                  meta: item.meta,
                  isSelected: item.sessionId == selectedSessionId,
                  lastMessageAtEpochMs: item.lastMessageAtEpochMs,
                  unreadCount: item.sessionId == selectedSessionId
                      ? 0
                      : item.unreadCount,
                ),
              )
              .toList(growable: false),
        ),
      );
    });
  }

  void _showSessionActionFailed() {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(widget.copy.chatSessionActionFailed)),
    );
  }

  ChatSessionListItemData _copySessionTileUnselected(
    ChatSessionListItemData session,
  ) {
    return ChatSessionListItemData(
      sessionId: session.sessionId,
      title: session.title,
      preview: session.preview,
      meta: session.meta,
      isSelected: false,
      lastMessageAtEpochMs: session.lastMessageAtEpochMs,
      unreadCount: session.unreadCount,
    );
  }

  String _seedCopySessionTitle(String title) {
    if (title.endsWith(' copy')) {
      return title;
    }
    if (title.length >= 27) {
      return '${title.substring(0, 27)} copy';
    }
    return '$title copy';
  }

  Future<void> _hydrateFromHost(OpenCrayHostBridge bridge) async {
    final snapshotFuture = bridge.loadChatSnapshot();
    final runtimeSnapshotFuture = bridge.loadChatRuntimeSnapshot();
    final sandboxSettingsFuture = bridge.loadSandboxSettings();
    final snapshot = await snapshotFuture;
    final runtimeSnapshot = await runtimeSnapshotFuture;
    OpenCraySandboxSettingsSnapshot sandboxSettings = _sandboxSettings;
    try {
      sandboxSettings = await sandboxSettingsFuture;
    } catch (_) {}
    if (!mounted) {
      return;
    }
    _latestChatSnapshot = snapshot;
    _latestChatRuntimeSnapshot = runtimeSnapshot;
    _pruneLocalDeletionTombstones(snapshot);
    _syncTodoArchiveVisibility(snapshot);
    final ChatFeatureState nextState = _applyLocalDeletionTombstones(
      _mapSnapshot(snapshot, runtimeSnapshot),
    );
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _state = nextState.copyWith(
        drawerOpen: _state.drawerOpen,
        composer: _composerStateForHostSnapshot(nextState),
      );
      _sandboxSettings = sandboxSettings;
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (snapshot.messages.isNotEmpty || nextState.runTraces.isNotEmpty) {
      _scheduleScrollToBottom(animated: false);
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }

  ChatFeatureState _mapSnapshot(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(
          expectedSessionId: _snapshotActiveSessionId(snapshot),
          embedded: snapshot.runtimeActivity,
          streamed: runtimeSnapshot,
        );
    final _RuntimeProjectionPatch runtimeProjection = _mapRuntimeProjection(
      snapshot,
      effectiveRuntime,
    );
    final List<ChatTodoItemData> visibleTodos = _mapVisibleTodos(snapshot);
    return ChatFeatureState(
      variant:
          runtimeProjection.messages.isEmpty &&
              runtimeProjection.runTraces.isEmpty &&
              visibleTodos.isEmpty &&
              snapshot.pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      screenTitle: snapshot.screenTitle,
      summary: ChatSessionSummary(
        title: snapshot.summary.title,
        badge: snapshot.summary.badge,
        body: snapshot.summary.body,
      ),
      messages: runtimeProjection.messages,
      runTraces: runtimeProjection.runTraces,
      composer: ChatComposerState(
        placeholder: snapshot.composerPlaceholder,
        todos: visibleTodos,
        attachments: const <ChatAttachmentData>[],
        commandOptions: const <ChatCommandOptionData>[],
        addActions: _usesHostBridge
            ? const <ChatAddActionData>[]
            : OpenCrayChatSeedData.sampleAddActions(widget.copy),
      ),
      drawer: ChatSessionsDrawerState(
        eyebrow: snapshot.drawer.eyebrow,
        title: snapshot.drawer.title,
        ctaLabel: snapshot.drawer.ctaLabel,
        sessions: snapshot.drawer.sessions
            .map(
              (session) => ChatSessionListItemData(
                sessionId: session.sessionId,
                title: session.title,
                preview: session.preview,
                meta: session.meta,
                isSelected: session.isSelected,
                lastMessageAtEpochMs: session.lastMessageAtEpochMs,
                unreadCount: session.unreadCount,
              ),
            )
            .toList(growable: false),
      ),
      pendingApprovals: snapshot.pendingApprovals
          .map(
            (approval) => ChatPendingApprovalData(
              runId: approval.runId,
              taskId: approval.taskId,
              title: approval.title,
              body: approval.body,
              approveLabel: approval.approveLabel,
              rejectLabel: approval.rejectLabel,
              isHighRisk: approval.isHighRisk,
              supportsSessionApproval: approval.supportsSessionApproval,
              approveForSessionLabel: approval.approveForSessionLabel,
              toolName: approval.toolName,
              requestSummary: approval.requestSummary,
              primaryDetail: approval.primaryDetail,
              pathDetails: approval.pathDetails,
              workingDirectory: approval.workingDirectory,
              reason: approval.reason,
              message: approval.message,
            ),
          )
          .toList(growable: false),
      modeLabel: snapshot.modeLabel,
      sessionButtonLabel: snapshot.sessionButtonLabel,
      emptyThreadHeight:
          runtimeProjection.messages.isEmpty &&
              runtimeProjection.runTraces.isEmpty
          ? 260
          : 0,
      isInputEnabled: snapshot.isInputEnabled,
    );
  }

  _RuntimeProjectionPatch _mapRuntimeProjection(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? effectiveRuntime,
  ) {
    final List<ChatRunTraceData> runTraces = _mapRunTraces(
      effectiveRuntime,
      snapshot.pendingApprovals,
    );
    final Map<String, String> liveDraftTextByMessageId =
        _liveDraftTextByMessageId(effectiveRuntime);
    final Map<String, _RuntimeProjectedMessagePatch>
    runtimeProjectedMessagePatches = _runtimeProjectedMessagePatchesByMessageId(
      effectiveRuntime,
    );
    final List<ChatMessageData> anchoredMessages = _mapMessages(
      snapshot.messages,
      hideThinkingPlaceholder: false,
      draftTextByMessageId: liveDraftTextByMessageId,
      runtimeProjectedMessagePatches: runtimeProjectedMessagePatches,
    );
    final Set<String> existingMessageIds = anchoredMessages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final List<ChatMessageData> projectedLiveDraftMessages =
        _mapUnanchoredLiveDraftMessages(effectiveRuntime, existingMessageIds);
    final List<ChatMessageData> anchoredVisibleMessages = <ChatMessageData>[
      ...anchoredMessages,
      ...projectedLiveDraftMessages,
    ];
    final List<ChatMessageData> messages = _trimHiddenThinkingPlaceholder(
      _mergeProjectedAssistantPhaseMessages(
        messages: anchoredVisibleMessages,
        runtimeSnapshot: effectiveRuntime,
      ),
      hideThinkingPlaceholder: runTraces.isNotEmpty,
    );
    return _RuntimeProjectionPatch(messages: messages, runTraces: runTraces);
  }

  Map<String, String> _liveDraftTextByMessageId(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null) {
      return const <String, String>{};
    }
    final Map<String, String> visibleDrafts = <String, String>{};
    for (final draft in runtimeSnapshot.liveAssistantDrafts) {
      final String pendingMessageId = draft.pendingMessageId.trim();
      final String? visibleDraftText = _visibleAssistantDraftText(draft.text);
      if (pendingMessageId.isEmpty ||
          visibleDraftText == null ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            runtimeSnapshot: runtimeSnapshot,
          )) {
        continue;
      }
      visibleDrafts[pendingMessageId] = visibleDraftText;
    }
    return visibleDrafts;
  }

  Map<String, _RuntimeProjectedMessagePatch>
  _runtimeProjectedMessagePatchesByMessageId(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null) {
      return const <String, _RuntimeProjectedMessagePatch>{};
    }
    final Map<String, _RuntimeProjectedMessagePatch> patchesByMessageId =
        <String, _RuntimeProjectedMessagePatch>{};
    final List<OpenCrayChatRunSnapshot> visibleRuns = _visibleRuns(
      runtimeSnapshot,
    );
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.runId.trim().isNotEmpty) run.runId.trim(): run,
        };
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.taskId.trim().isNotEmpty) run.taskId.trim(): run,
        };
    for (final event in runtimeSnapshot.events) {
      if (event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[event.runId.trim()] ??
          visibleRunsByTaskId[event.taskId.trim()];
      final String anchorMessageId = run?.pendingMessageId?.trim() ?? '';
      if (run == null ||
          anchorMessageId.isEmpty ||
          !run.matchesRuntimeEvent(event)) {
        continue;
      }
      final String text = _projectedAssistantPhaseMessageText(event);
      if (text.trim().isEmpty) {
        continue;
      }
      patchesByMessageId[_assistantPhaseMessageId(
        event,
      )] = _RuntimeProjectedMessagePatch(
        anchorMessageId: anchorMessageId,
        text: text,
      );
    }
    for (final run in visibleRuns) {
      final String anchorMessageId = run.pendingMessageId?.trim() ?? '';
      if (anchorMessageId.isEmpty) {
        continue;
      }
      for (final process in run.managedProcesses) {
        final String text = _projectedManagedProcessMessageText(process);
        if (text.trim().isEmpty) {
          continue;
        }
        patchesByMessageId[_projectedManagedProcessMessageId(
          run: run,
          process: process,
        )] = _RuntimeProjectedMessagePatch(
          anchorMessageId: anchorMessageId,
          text: text,
        );
      }
    }
    return patchesByMessageId;
  }

  List<ChatMessageData> _mapMessages(
    List<OpenCrayChatMessageSnapshot> messages, {
    required bool hideThinkingPlaceholder,
    required Map<String, String> draftTextByMessageId,
    Map<String, _RuntimeProjectedMessagePatch> runtimeProjectedMessagePatches =
        const <String, _RuntimeProjectedMessagePatch>{},
  }) {
    final mapped = messages
        .asMap()
        .entries
        .map((entry) {
          final String messageId = entry.value.messageId.trim().isNotEmpty
              ? entry.value.messageId
              : 'message-${entry.key}-${entry.value.kind}';
          final _RuntimeProjectedMessagePatch? runtimePatch =
              runtimeProjectedMessagePatches[messageId];
          return ChatMessageData(
            messageId: messageId,
            kind: switch (entry.value.kind) {
              'timeline' => ChatMessageKind.timeline,
              'outbound' => ChatMessageKind.outbound,
              _ => ChatMessageKind.inbound,
            },
            text:
                runtimePatch?.text ??
                _resolvedChatMessageText(
                  message: entry.value,
                  draftTextByMessageId: draftTextByMessageId,
                ),
            meta: entry.value.meta,
            runtimeAnchorMessageId: runtimePatch?.anchorMessageId ?? '',
            createdAtEpochMs: entry.value.createdAtEpochMs,
            isEphemeral: entry.value.isEphemeral,
            attachments: entry.value.attachments
                .map(
                  (attachment) => ChatMessageAttachmentData(
                    attachmentId: attachment.attachmentId.trim().isNotEmpty
                        ? attachment.attachmentId
                        : '${entry.value.messageId}-${attachment.localPath}',
                    kind: switch (attachment.kind) {
                      'image' => ChatAttachmentKind.image,
                      'voice' || 'audio' => ChatAttachmentKind.voice,
                      _ => ChatAttachmentKind.file,
                    },
                    displayName: attachment.displayName,
                    localPath: attachment.localPath,
                    mimeType: attachment.mimeType,
                    sizeBytes: attachment.sizeBytes,
                    widthPx: attachment.widthPx,
                    heightPx: attachment.heightPx,
                    durationMs: attachment.durationMs,
                    waveformBars: attachment.waveformBars,
                    transcriptText: attachment.transcriptText,
                    contentSha256: attachment.contentSha256,
                  ),
                )
                .toList(growable: false),
          );
        })
        .toList(growable: true);
    if (hideThinkingPlaceholder && mapped.isNotEmpty) {
      return _trimHiddenThinkingPlaceholder(
        mapped,
        hideThinkingPlaceholder: true,
      );
    }
    return mapped;
  }

  List<ChatMessageData> _trimHiddenThinkingPlaceholder(
    List<ChatMessageData> messages, {
    required bool hideThinkingPlaceholder,
  }) {
    if (!hideThinkingPlaceholder || messages.isEmpty) {
      return messages;
    }
    final ChatMessageData lastMessage = messages.last;
    if (lastMessage.kind != ChatMessageKind.inbound ||
        !_thinkingPlaceholders.contains(lastMessage.text.trim())) {
      return messages;
    }
    return messages.take(messages.length - 1).toList(growable: false);
  }

  String _resolvedChatMessageText({
    required OpenCrayChatMessageSnapshot message,
    required Map<String, String> draftTextByMessageId,
  }) {
    final String baseText = message.text;
    final String messageId = message.messageId.trim();
    final String? liveDraftText = draftTextByMessageId[messageId];
    if (liveDraftText == null ||
        !_shouldReplacePendingThinkingBubble(
          messageKind: message.kind,
          text: baseText,
        )) {
      return baseText;
    }
    return liveDraftText;
  }

  bool _shouldReplacePendingThinkingBubble({
    required String messageKind,
    required String text,
  }) {
    if (messageKind == 'outbound' || messageKind == 'timeline') {
      return false;
    }
    return _thinkingPlaceholders.contains(text.trim());
  }

  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
  ) {
    if (runtimeSnapshot == null) {
      return const <ChatRunTraceData>[];
    }
    final activeRuns = _visibleRuns(runtimeSnapshot).toList(growable: false)
      ..sort(
        (left, right) =>
            left.acceptedAtEpochMs.compareTo(right.acceptedAtEpochMs),
      );
    final List<ChatRunTraceData> runTraces = activeRuns
        .map(
          (run) => _mapRunTrace(
            run: run,
            runtimeSnapshot: runtimeSnapshot,
            pendingApprovals: pendingApprovals,
          ),
        )
        .toList(growable: false);
    final Set<String> visibleParentRunIds = activeRuns
        .map((run) => run.runId.trim())
        .where((runId) => runId.isNotEmpty)
        .toSet();
    final Set<String> visibleParentTaskIds = activeRuns
        .map((run) => run.taskId.trim())
        .where((taskId) => taskId.isNotEmpty)
        .toSet();
    final List<ChatRunTraceData> detachedSubAgentTraces =
        _detachedSubAgentSnapshots(
          runtimeSnapshot: runtimeSnapshot,
          visibleParentRunIds: visibleParentRunIds,
          visibleParentTaskIds: visibleParentTaskIds,
        ).map(_mapDetachedSubAgentTrace).toList(growable: false);
    if (runTraces.isEmpty && detachedSubAgentTraces.isEmpty) {
      return const <ChatRunTraceData>[];
    }
    return <ChatRunTraceData>[...runTraces, ...detachedSubAgentTraces];
  }

  List<OpenCrayChatSubAgentSnapshot> _subAgentSnapshotsForRun({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) =>
      runtimeSnapshot.subAgents
          .where((subAgent) {
            final String parentRunId = subAgent.parentRunId.trim();
            if (parentRunId.isNotEmpty && parentRunId == run.runId.trim()) {
              return true;
            }
            final String parentTaskId = subAgent.parentTaskId.trim();
            return parentTaskId.isNotEmpty && parentTaskId == run.taskId.trim();
          })
          .toList(growable: false)
        ..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );

  List<OpenCrayChatRuntimeEventSnapshot> _durableSubAgentEventsForRun({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  }) {
    final Map<String, OpenCrayChatRuntimeEventSnapshot> latestEventByKey =
        <String, OpenCrayChatRuntimeEventSnapshot>{};
    for (final event in runEvents) {
      if (event.kind != 'subagent') {
        continue;
      }
      final String key = _subAgentRegistryKeyForEvent(event);
      final OpenCrayChatRuntimeEventSnapshot? existing = latestEventByKey[key];
      if (existing == null ||
          event.emittedAtEpochMs >= existing.emittedAtEpochMs) {
        latestEventByKey[key] = event;
      }
    }
    return _subAgentSnapshotsForRun(run: run, runtimeSnapshot: runtimeSnapshot)
        .map(_syntheticSubAgentEvent)
        .where((event) {
          final OpenCrayChatRuntimeEventSnapshot? existing =
              latestEventByKey[_subAgentRegistryKeyForEvent(event)];
          if (existing == null) {
            return true;
          }
          return _subAgentStateSignature(event) !=
                  _subAgentStateSignature(existing) ||
              event.emittedAtEpochMs > existing.emittedAtEpochMs;
        })
        .toList(growable: false)
      ..sort(
        (left, right) =>
            left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
      );
  }

  List<OpenCrayChatSubAgentSnapshot> _detachedSubAgentSnapshots({
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required Set<String> visibleParentRunIds,
    required Set<String> visibleParentTaskIds,
  }) =>
      runtimeSnapshot.subAgents
          .where((subAgent) {
            final String parentRunId = subAgent.parentRunId.trim();
            if (parentRunId.isNotEmpty &&
                visibleParentRunIds.contains(parentRunId)) {
              return false;
            }
            final String parentTaskId = subAgent.parentTaskId.trim();
            return parentTaskId.isEmpty ||
                !visibleParentTaskIds.contains(parentTaskId);
          })
          .toList(growable: false)
        ..sort((left, right) {
          final int startedComparison = left.startedAtEpochMs.compareTo(
            right.startedAtEpochMs,
          );
          if (startedComparison != 0) {
            return startedComparison;
          }
          return left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs);
        });

  ChatRunTraceData _mapDetachedSubAgentTrace(
    OpenCrayChatSubAgentSnapshot snapshot,
  ) {
    final OpenCrayChatRuntimeEventSnapshot event = _syntheticSubAgentEvent(
      snapshot,
    );
    final String historyBody = _buildSubagentHistoryBody(event);
    final ChatRunTraceHistoryEntry historyEntry = _subagentHistoryEntry(
      event: event,
      label: _subagentTraceLabel(event),
      body: historyBody,
      isHighRisk: event.isHighRisk,
    );
    final String previewBody = _buildSubagentPreviewBody(event);
    return ChatRunTraceData(
      runId: _detachedSubAgentTraceId(snapshot),
      taskId: snapshot.childTaskId.trim().isNotEmpty
          ? snapshot.childTaskId
          : snapshot.parentTaskId,
      label: _subagentTraceLabel(event),
      body: _buildCompactTraceBody(
        history: <ChatRunTraceHistoryEntry>[historyEntry],
        fallbackBody: previewBody,
        preferredBody: historyBody,
      ),
      history: <ChatRunTraceHistoryEntry>[historyEntry],
      isHighRisk: snapshot.isHighRisk,
      canInterrupt: false,
    );
  }

  OpenCrayChatRuntimeEventSnapshot _syntheticSubAgentEvent(
    OpenCrayChatSubAgentSnapshot snapshot,
  ) => OpenCrayChatRuntimeEventSnapshot(
    kind: 'subagent',
    runId: snapshot.parentRunId,
    taskId: snapshot.parentTaskId,
    emittedAtEpochMs: snapshot.updatedAtEpochMs,
    phase: snapshot.phase,
    status: snapshot.status,
    text: snapshot.summary,
    isHighRisk: snapshot.isHighRisk,
    label: snapshot.label,
    childRunId: snapshot.childRunId,
    childTaskId: snapshot.childTaskId,
    subagentType: snapshot.subagentType,
    contextMode: snapshot.contextMode,
    depth: snapshot.depth,
    executionState: snapshot.executionState,
    continuationKind: snapshot.continuationKind,
    resultMetadata: <String, String>{
      'mailboxMessageCount': '${snapshot.mailboxMessageCount}',
      'mailboxPendingMessageCount': '${snapshot.mailboxPendingMessageCount}',
      if (snapshot.mailboxLastDeliveredMessageId?.trim().isNotEmpty == true)
        'mailboxLastDeliveredMessageId': snapshot.mailboxLastDeliveredMessageId!
            .trim(),
    },
  );

  OpenCrayChatRuntimeEventSnapshot? _effectiveRunTraceEvent({
    required OpenCrayChatRuntimeEventSnapshot? lastEvent,
    required List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents,
  }) {
    final OpenCrayChatRuntimeEventSnapshot? durableEvent =
        durableSubAgentEvents.isEmpty ? null : durableSubAgentEvents.last;
    if (durableEvent == null) {
      return lastEvent;
    }
    if (lastEvent == null) {
      return durableEvent;
    }
    if (durableEvent.emittedAtEpochMs >= lastEvent.emittedAtEpochMs) {
      return durableEvent;
    }
    return lastEvent;
  }

  String _subAgentRegistryKeyForEvent(OpenCrayChatRuntimeEventSnapshot event) {
    final String childRunId = event.childRunId?.trim() ?? '';
    final String childTaskId = event.childTaskId?.trim() ?? '';
    final String label = event.label?.trim() ?? '';
    final String childKey = childRunId.isNotEmpty
        ? childRunId
        : (childTaskId.isNotEmpty ? childTaskId : label);
    return '${event.runId.trim()}|$childKey';
  }

  String _subAgentStateSignature(OpenCrayChatRuntimeEventSnapshot event) =>
      <String>[
        _subAgentRegistryKeyForEvent(event),
        event.phase?.trim().toLowerCase() ?? '',
        event.status?.trim().toLowerCase() ?? '',
        event.executionState?.trim().toLowerCase() ?? '',
        event.continuationKind?.trim().toLowerCase() ?? '',
        event.text?.trim() ?? '',
        event.resultMetadata['mailboxMessageCount']?.trim() ?? '',
        event.resultMetadata['mailboxPendingMessageCount']?.trim() ?? '',
        event.resultMetadata['mailboxLastDeliveredMessageId']?.trim() ?? '',
        event.isHighRisk.toString(),
      ].join('|');

  String _detachedSubAgentTraceId(OpenCrayChatSubAgentSnapshot snapshot) {
    final String childRunId = snapshot.childRunId.trim();
    if (childRunId.isNotEmpty) {
      return childRunId;
    }
    final String childTaskId = snapshot.childTaskId.trim();
    if (childTaskId.isNotEmpty) {
      return childTaskId;
    }
    final String parentKey = snapshot.parentRunId.trim().isNotEmpty
        ? snapshot.parentRunId.trim()
        : snapshot.parentTaskId.trim();
    final String label = snapshot.label.trim().replaceAll(RegExp(r'\s+'), '-');
    return 'subagent-$parentKey-$label';
  }

  List<ChatMessageData> _mergeProjectedAssistantPhaseMessages({
    required List<ChatMessageData> messages,
    required OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  }) {
    if (runtimeSnapshot == null) {
      return messages;
    }
    final List<OpenCrayChatRunSnapshot> visibleRuns = _visibleRuns(
      runtimeSnapshot,
    );
    if (visibleRuns.isEmpty) {
      return messages;
    }
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByRunId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.runId.trim().isNotEmpty) run.runId.trim(): run,
        };
    final Map<String, OpenCrayChatRunSnapshot> visibleRunsByTaskId =
        <String, OpenCrayChatRunSnapshot>{
          for (final run in visibleRuns)
            if (run.taskId.trim().isNotEmpty) run.taskId.trim(): run,
        };
    if (visibleRunsByRunId.isEmpty && visibleRunsByTaskId.isEmpty) {
      return messages;
    }
    final Set<String> visibleMessageIds = messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
        );
    final Map<String, List<ChatMessageData>> projectedByAnchorMessageId =
        <String, List<ChatMessageData>>{};
    final Set<String> seenMessageIds = <String>{...visibleMessageIds};
    for (final event in sortedEvents) {
      final String runId = event.runId.trim();
      final String taskId = event.taskId.trim();
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[runId] ?? visibleRunsByTaskId[taskId];
      final String anchorMessageId = run?.pendingMessageId?.trim() ?? '';
      if (run == null ||
          anchorMessageId.isEmpty ||
          !visibleMessageIds.contains(anchorMessageId) ||
          !run.matchesRuntimeEvent(event) ||
          event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final String messageId = _assistantPhaseMessageId(event);
      if (!seenMessageIds.add(messageId)) {
        continue;
      }
      final String text = _projectedAssistantPhaseMessageText(event);
      if (text.trim().isEmpty) {
        continue;
      }
      projectedByAnchorMessageId
          .putIfAbsent(anchorMessageId, () => <ChatMessageData>[])
          .add(
            ChatMessageData(
              messageId: messageId,
              kind: ChatMessageKind.inbound,
              text: text,
              runtimeAnchorMessageId: anchorMessageId,
              createdAtEpochMs: event.emittedAtEpochMs,
              isEphemeral: true,
            ),
          );
    }
    for (final run in visibleRuns) {
      final String anchorMessageId = run.pendingMessageId?.trim() ?? '';
      if (anchorMessageId.isEmpty ||
          !visibleMessageIds.contains(anchorMessageId)) {
        continue;
      }
      final List<OpenCrayChatManagedProcessSnapshot> processes =
          run.managedProcesses.toList(growable: false)..sort((left, right) {
            final int leftSortEpochMs = _managedProcessSortEpochMs(left);
            final int rightSortEpochMs = _managedProcessSortEpochMs(right);
            if (leftSortEpochMs != rightSortEpochMs) {
              return leftSortEpochMs.compareTo(rightSortEpochMs);
            }
            return left.processId.compareTo(right.processId);
          });
      for (final process in processes) {
        final String messageId = _projectedManagedProcessMessageId(
          run: run,
          process: process,
        );
        if (!seenMessageIds.add(messageId)) {
          continue;
        }
        final String text = _projectedManagedProcessMessageText(process);
        if (text.trim().isEmpty) {
          continue;
        }
        projectedByAnchorMessageId
            .putIfAbsent(anchorMessageId, () => <ChatMessageData>[])
            .add(
              ChatMessageData(
                messageId: messageId,
                kind: ChatMessageKind.inbound,
                text: text,
                runtimeAnchorMessageId: anchorMessageId,
                createdAtEpochMs: _managedProcessSortEpochMs(process),
                isEphemeral: true,
              ),
            );
      }
    }
    if (projectedByAnchorMessageId.isEmpty) {
      return messages;
    }
    final List<ChatMessageData> mergedMessages = <ChatMessageData>[];
    for (final message in messages) {
      final List<ChatMessageData>? projections =
          projectedByAnchorMessageId[message.messageId];
      if (projections != null) {
        mergedMessages.addAll(
          projections.toList(growable: false)..sort((left, right) {
            final int leftEpochMs = left.createdAtEpochMs ?? 0;
            final int rightEpochMs = right.createdAtEpochMs ?? 0;
            if (leftEpochMs != rightEpochMs) {
              return leftEpochMs.compareTo(rightEpochMs);
            }
            return left.messageId.compareTo(right.messageId);
          }),
        );
      }
      mergedMessages.add(message);
    }
    return mergedMessages;
  }

  List<ChatMessageData> _mapUnanchoredLiveDraftMessages(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    Set<String> existingMessageIds,
  ) {
    if (runtimeSnapshot == null ||
        runtimeSnapshot.liveAssistantDrafts.isEmpty) {
      return const <ChatMessageData>[];
    }
    final List<ChatMessageData> projected = <ChatMessageData>[];
    final Set<String> seenMessageIds = <String>{...existingMessageIds};
    final List<OpenCrayChatLiveAssistantDraftSnapshot> sortedDrafts =
        runtimeSnapshot.liveAssistantDrafts.toList(growable: false)..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );
    for (final draft in sortedDrafts) {
      final String messageId = draft.pendingMessageId.trim();
      final String text = _visibleAssistantDraftText(draft.text) ?? '';
      if (messageId.isEmpty ||
          text.isEmpty ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            runtimeSnapshot: runtimeSnapshot,
          ) ||
          !seenMessageIds.add(messageId)) {
        continue;
      }
      projected.add(
        ChatMessageData(
          messageId: messageId,
          kind: ChatMessageKind.inbound,
          text: text,
          isEphemeral: true,
        ),
      );
    }
    return projected;
  }

  bool _shouldDisplayLiveAssistantDraft({
    required String runId,
    required String taskId,
    required OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  }) {
    if (runtimeSnapshot == null) {
      return true;
    }
    final String normalizedRunId = runId.trim();
    final String normalizedTaskId = taskId.trim();
    if (normalizedRunId.isEmpty && normalizedTaskId.isEmpty) {
      return true;
    }
    OpenCrayChatRunSnapshot? matchedRun;
    for (final run in _visibleRuns(runtimeSnapshot)) {
      if (normalizedRunId.isNotEmpty && run.runId.trim() == normalizedRunId) {
        matchedRun = run;
        break;
      }
      if (normalizedTaskId.isNotEmpty &&
          run.taskId.trim() == normalizedTaskId) {
        matchedRun = run;
        break;
      }
    }
    if (matchedRun == null) {
      return true;
    }
    final OpenCrayChatRuntimeEventSnapshot? latestEvent = _latestRunTraceEvent(
      _runEventsFor(run: matchedRun, runtimeSnapshot: runtimeSnapshot),
    );
    if (latestEvent == null) {
      return true;
    }
    if (latestEvent.kind == 'interrupted') {
      return false;
    }
    return latestEvent.kind != 'assistant_phase' ||
        latestEvent.isFinal == true ||
        _hideAssistantPhaseBubble(latestEvent);
  }

  bool _runHasVisibleLiveAssistantDraft({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) {
    if (run.isTerminal) {
      return false;
    }
    final String pendingMessageId = run.pendingMessageId?.trim() ?? '';
    if (pendingMessageId.isEmpty) {
      return false;
    }
    for (final draft in runtimeSnapshot.liveAssistantDrafts) {
      if (draft.pendingMessageId.trim() != pendingMessageId ||
          _visibleAssistantDraftText(draft.text)?.isNotEmpty != true ||
          !_shouldDisplayLiveAssistantDraft(
            runId: draft.runId,
            taskId: draft.taskId,
            runtimeSnapshot: runtimeSnapshot,
          )) {
        continue;
      }
      final String draftRunId = draft.runId.trim();
      final String draftTaskId = draft.taskId.trim();
      if ((draftRunId.isNotEmpty && draftRunId == run.runId.trim()) ||
          (draftTaskId.isNotEmpty && draftTaskId == run.taskId.trim()) ||
          (draftRunId.isEmpty && draftTaskId.isEmpty)) {
        return true;
      }
    }
    return false;
  }

  bool _hideAssistantPhaseBubble(OpenCrayChatRuntimeEventSnapshot event) {
    final String stage = event.stage?.trim().toLowerCase() ?? '';
    return stage == 'llm_retry' || stage == 'responses_recovery';
  }

  String _projectedManagedProcessMessageId({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatManagedProcessSnapshot process,
  }) {
    final String runId = run.runId.trim().isNotEmpty
        ? run.runId.trim()
        : run.taskId.trim();
    final String processId = process.processId.trim();
    if (processId.isNotEmpty) {
      return 'runtime-process-$runId-$processId';
    }
    final String fingerprint = <String>[
      process.command.trim(),
      process.args.join('\u0001'),
      process.workingDirectory?.trim() ?? '',
      process.startedAtEpochMs.toString(),
    ].join('\u0002');
    return 'runtime-process-$runId-fp-${javaStringHashCode(fingerprint).abs()}';
  }

  String _projectedManagedProcessMessageText(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String command = <String>[
      process.command,
      ...process.args,
    ].map((part) => part.trim()).where((part) => part.isNotEmpty).join(' ');
    final String output =
        (process.stdout.isNotEmpty ? process.stdout : process.stdoutPreview)
            .trim();
    return _joinTraceSections(<String?>[
      'Process ${process.processId}',
      '${_managedProcessStatusSummary(process)}: $command',
      output.isEmpty ? null : output,
    ]);
  }

  ChatRunTraceData _mapRunTrace({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
    required List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
  }) {
    final List<OpenCrayChatRuntimeEventSnapshot> runEvents = _runEventsFor(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    final List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents =
        _durableSubAgentEventsForRun(
          run: run,
          runtimeSnapshot: runtimeSnapshot,
          runEvents: runEvents,
        );
    final OpenCrayChatRuntimeEventSnapshot? event = _effectiveRunTraceEvent(
      lastEvent: _latestRunTraceEvent(runEvents) ?? run.lastEvent,
      durableSubAgentEvents: durableSubAgentEvents,
    );
    final OpenCrayChatPendingApprovalSnapshot? pendingApproval =
        _pendingApprovalForRun(run: run, pendingApprovals: pendingApprovals);
    final OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent =
        _latestApprovalEvent(runEvents);
    final ChatRunTracePreviewCardData? previewCard = _latestRunTracePreviewCard(
      runEvents,
    );
    final ChatRunTraceSandboxSessionCardData? sessionCard =
        _latestRunTraceSandboxSessionCard(runEvents);
    final toolName = event?.toolName?.trim();
    final bool waitingApproval = _isWaitingApproval(
      run: run,
      runEvents: runEvents,
      pendingApproval: pendingApproval,
    );
    final bool canInterrupt =
        !run.isTerminal &&
        (run.runId.trim().isNotEmpty || run.taskId.trim().isNotEmpty);
    List<ChatRunTraceHistoryEntry> history = _buildRunTraceHistory(
      run: run,
      runEvents: runEvents,
      pendingApproval: pendingApproval,
      durableSubAgentEvents: durableSubAgentEvents,
    );
    final bool isWritingAssistantDraft = _runHasVisibleLiveAssistantDraft(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    _runTraceDebug(
      'feature.mapRunTrace run=${run.runId} task=${run.taskId} events=${runEvents.length} history=${history.length} managedProcesses=${run.managedProcesses.length} runningManagedProcesses=${run.runningManagedProcessCount} liveManagedProcesses=${run.hasLiveManagedProcesses}',
    );
    ChatRunTraceData buildTrace({
      required String label,
      required String body,
      List<ChatRunTraceHistoryEntry>? historyOverride,
      bool isHighRisk = false,
      bool? canInterruptOverride,
      String? retryLabel,
    }) => ChatRunTraceData(
      runId: run.runId,
      taskId: run.taskId,
      anchorMessageId: run.pendingMessageId?.trim() ?? '',
      label: label,
      body: body,
      history: historyOverride ?? history,
      isHighRisk: isHighRisk,
      isTerminal: run.isTerminal,
      canInterrupt: canInterruptOverride ?? canInterrupt,
      isWritingAssistantDraft: isWritingAssistantDraft,
      retryLabel: retryLabel,
      previewCard: previewCard,
      sessionCard: sessionCard,
    );
    if (_isInterruptedOnRestoreRun(run)) {
      final interruptedEntry = _mainHistoryEntry(
        label: _interruptedRunLabel(),
        body: _interruptedRunBody(run),
      );
      if (history.isEmpty ||
          history.last.label != interruptedEntry.label ||
          history.last.body != interruptedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, interruptedEntry];
      }
      return buildTrace(
        label: interruptedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: interruptedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: _retryInterruptedRunLabel(),
      );
    }
    if (_isLlmRetryPausedRun(run)) {
      final pausedEntry = _mainHistoryEntry(
        label: _pausedRunLabel(),
        body: _pausedRunBody(),
      );
      if (history.isEmpty ||
          history.last.label != pausedEntry.label ||
          history.last.body != pausedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, pausedEntry];
      }
      return buildTrace(
        label: pausedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: pausedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: widget.copy.chatRunResumeAction,
      );
    }
    if (_isDeferredApprovalDecisionRun(run)) {
      final pausedEntry = _mainHistoryEntry(
        label: _pausedRunLabel(),
        body: _deferredApprovalDecisionBody(),
      );
      if (history.isEmpty ||
          history.last.label != pausedEntry.label ||
          history.last.body != pausedEntry.body) {
        history = <ChatRunTraceHistoryEntry>[...history, pausedEntry];
      }
      return buildTrace(
        label: pausedEntry.label,
        body: _buildCompactTraceBody(
          history: history,
          fallbackBody: pausedEntry.body,
        ),
        historyOverride: history,
        canInterruptOverride: false,
        retryLabel: widget.copy.chatRunResumeAction,
      );
    }
    final _ResolvedApprovalPreview? resolvedApprovalPreview =
        _shouldShowResolvedApprovalPreview(
          run: run,
          event: event,
          latestApprovalEvent: latestApprovalEvent,
          pendingApproval: pendingApproval,
        )
        ? _buildResolvedApprovalPreview(
            run: run,
            runEvents: runEvents,
            latestApprovalEvent: latestApprovalEvent,
          )
        : null;
    if (resolvedApprovalPreview != null) {
      final ChatRunTraceHistoryEntry resolvedEntry = _mainHistoryEntry(
        label: resolvedApprovalPreview.label,
        body: resolvedApprovalPreview.body,
        compactBody: resolvedApprovalPreview.body,
      );
      final List<ChatRunTraceHistoryEntry> baseHistory = history.isEmpty
          ? history
          : history.sublist(0, history.length - 1);
      final List<ChatRunTraceHistoryEntry> resolvedHistory =
          baseHistory.isNotEmpty &&
              baseHistory.last.label == resolvedEntry.label &&
              baseHistory.last.body == resolvedEntry.body
          ? baseHistory
          : <ChatRunTraceHistoryEntry>[...baseHistory, resolvedEntry];
      return buildTrace(
        label: resolvedApprovalPreview.label,
        body: resolvedApprovalPreview.body,
        historyOverride: resolvedHistory,
      );
    }
    String compactBody(String fallbackBody, {String? preferredBody}) =>
        _buildCompactTraceBody(
          history: history,
          fallbackBody: fallbackBody,
          preferredBody: preferredBody ?? fallbackBody,
        );
    final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
        event?.kind == 'tool_result'
        ? _findPreviousToolCall(
            runEvents,
            beforeIndex: runEvents.length,
            toolName: toolName,
          )
        : null;
    switch (event?.kind) {
      case 'approval_wait':
        return buildTrace(
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          isHighRisk:
              event.isHighRisk ||
              (waitingApproval &&
                  run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED'),
        );
      case 'approval_result':
        return buildTrace(
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          isHighRisk: event.isHighRisk,
        );
      case 'interrupted':
        return buildTrace(
          label: _cancellationTraceLabel(event!),
          body: compactBody(_buildCancellationPreviewBody(event)),
        );
      case 'subagent':
        final OpenCrayChatRuntimeEventSnapshot subagentEvent = event!;
        final String previewBody = _buildSubagentPreviewBody(subagentEvent);
        return buildTrace(
          label: _subagentTraceLabel(subagentEvent),
          body: compactBody(
            previewBody,
            preferredBody: _buildSubagentHistoryBody(subagentEvent),
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'supplement':
        return buildTrace(
          label: _supplementTraceLabel(),
          body: compactBody(_buildSupplementPreviewBody(event!)),
        );
      case 'tool_call':
        return buildTrace(
          label: toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(_buildToolCallPreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_result':
        return buildTrace(
          label: waitingApproval
              ? widget.copy.chatRunWaitingApprovalLabel
              : toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(
            _buildToolResultPreviewBody(
              event: event!,
              pairedToolCall: pairedToolCall,
              waitingApproval: waitingApproval,
              runErrorMessage: run.errorMessage,
            ),
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_retrieval':
        return buildTrace(
          label: toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(_buildMemoryRetrievalPreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_write':
        return buildTrace(
          label: _memoryMaintenanceLabel(),
          body: compactBody(_buildMemoryWritePreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant':
        final text = event?.text?.trim();
        return buildTrace(
          label: widget.copy.chatRunWorkingLabel,
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : widget.copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant_phase':
        final text = event?.text?.trim();
        return buildTrace(
          label: _assistantPhaseEntryLabel(event!),
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : widget.copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      default:
        return buildTrace(
          label: waitingApproval
              ? widget.copy.chatRunWaitingApprovalLabel
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(
            waitingApproval
                ? run.errorMessage?.trim().isNotEmpty == true
                      ? run.errorMessage!.trim()
                      : widget.copy.chatRunWaitingApprovalLabel
                : widget.copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunTraceHistory({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
    List<OpenCrayChatRuntimeEventSnapshot> durableSubAgentEvents =
        const <OpenCrayChatRuntimeEventSnapshot>[],
  }) {
    final timedHistory = <_TimedChatRunTraceHistoryEntry>[];
    final consumedIndexes = <int>{};
    int nextSourceOrder = 0;
    for (int index = 0; index < runEvents.length; index += 1) {
      if (consumedIndexes.contains(index)) {
        continue;
      }
      final mapped = _mapRunTraceHistoryEntry(
        event: runEvents[index],
        runEvents: runEvents,
        index: index,
        consumedIndexes: consumedIndexes,
      );
      if (mapped != null) {
        timedHistory.add(
          _TimedChatRunTraceHistoryEntry(
            sortEpochMs: runEvents[index].emittedAtEpochMs,
            sourceOrder: nextSourceOrder++,
            entry: mapped,
          ),
        );
      }
    }
    final Set<String> appendedSubAgentStates = runEvents
        .where((event) => event.kind == 'subagent')
        .map(_subAgentStateSignature)
        .toSet();
    for (final event in durableSubAgentEvents) {
      final String signature = _subAgentStateSignature(event);
      if (!appendedSubAgentStates.add(signature)) {
        continue;
      }
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: event.emittedAtEpochMs,
          sourceOrder: nextSourceOrder++,
          entry: _subagentHistoryEntry(
            event: event,
            label: _subagentTraceLabel(event),
            body: _buildSubagentHistoryBody(event),
            isHighRisk: event.isHighRisk,
          ),
        ),
      );
    }
    for (final process in _orderedManagedProcesses(run)) {
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: _managedProcessSortEpochMs(process),
          sourceOrder: nextSourceOrder++,
          entry: _managedProcessHistoryEntry(process),
        ),
      );
    }
    final ChatRunTraceHistoryEntry? finalAttachmentHistory =
        _buildRunFinalAttachmentHistoryEntry(run);
    if (finalAttachmentHistory != null) {
      timedHistory.add(
        _TimedChatRunTraceHistoryEntry(
          sortEpochMs: run.updatedAtEpochMs,
          sourceOrder: nextSourceOrder++,
          entry: finalAttachmentHistory,
        ),
      );
    }
    timedHistory.sort((
      _TimedChatRunTraceHistoryEntry left,
      _TimedChatRunTraceHistoryEntry right,
    ) {
      if (left.sortEpochMs != right.sortEpochMs) {
        return left.sortEpochMs.compareTo(right.sortEpochMs);
      }
      return left.sourceOrder.compareTo(right.sourceOrder);
    });
    final history = timedHistory
        .map((_TimedChatRunTraceHistoryEntry timedEntry) => timedEntry.entry)
        .toList(growable: true);
    final List<ChatRunTraceHistoryEntry> contextHistory =
        _buildRunContextHistory(run);
    if (contextHistory.isNotEmpty) {
      final int insertionIndex =
          history.isNotEmpty &&
              history.first.label == widget.copy.chatRunWorkingLabel &&
              history.first.body == widget.copy.chatRunThinkingActive
          ? 1
          : 0;
      history.insertAll(insertionIndex, contextHistory);
    }
    final bool hasApprovalWaitEvent = runEvents.any(
      (event) => event.kind == 'approval_wait',
    );
    if (pendingApproval != null && !hasApprovalWaitEvent) {
      final String? approvalBody =
          _nonEmpty(pendingApproval.body) ?? _nonEmpty(run.errorMessage);
      final waitingEntry = _mainHistoryEntry(
        label: widget.copy.chatRunWaitingApprovalLabel,
        body: approvalBody?.isNotEmpty == true
            ? approvalBody!
            : widget.copy.chatRunWaitingApprovalLabel,
        isHighRisk: pendingApproval.isHighRisk,
      );
      if (history.isEmpty ||
          history.last.label != waitingEntry.label ||
          history.last.body != waitingEntry.body) {
        history.add(waitingEntry);
      }
    }
    if (history.isEmpty) {
      history.add(
        _mainHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: widget.copy.chatRunThinkingActive,
        ),
      );
    }
    return history;
  }

  String get _mainInspectorActorId => _runTraceMainActorId;

  String _mainInspectorActorLabel() =>
      widget.copy.isChinese ? '主代理' : 'Main agent';

  String _subagentInspectorActorId(OpenCrayChatRuntimeEventSnapshot event) {
    final String? explicitId =
        _nonEmpty(event.childRunId) ?? _nonEmpty(event.childTaskId);
    if (explicitId != null) {
      return explicitId;
    }
    return [
      _nonEmpty(event.subagentType),
      _nonEmpty(event.label),
      event.emittedAtEpochMs.toString(),
    ].whereType<String>().join(':');
  }

  String _subagentInspectorActorLabel(OpenCrayChatRuntimeEventSnapshot event) =>
      _subagentTraceLabel(event);

  ChatRunTraceHistoryEntry _mainHistoryEntry({
    required String label,
    required String body,
    String? compactBody,
    bool isHighRisk = false,
    List<ChatRunTraceInspectorTextPart> inspectorCallParts =
        const <ChatRunTraceInspectorTextPart>[],
    String inspectorCallDetail = '',
    String inspectorResultBody = '',
  }) {
    return ChatRunTraceHistoryEntry(
      label: label,
      body: body,
      compactBody: compactBody,
      isHighRisk: isHighRisk,
      inspectorActorId: _mainInspectorActorId,
      inspectorActorLabel: _mainInspectorActorLabel(),
      inspectorCallParts: inspectorCallParts,
      inspectorCallDetail: inspectorCallDetail,
      inspectorResultBody: inspectorResultBody,
    );
  }

  ChatRunTraceHistoryEntry _subagentHistoryEntry({
    required OpenCrayChatRuntimeEventSnapshot event,
    required String label,
    required String body,
    String? compactBody,
    bool isHighRisk = false,
  }) {
    return ChatRunTraceHistoryEntry(
      label: label,
      body: body,
      compactBody: compactBody,
      isHighRisk: isHighRisk,
      inspectorActorId: _subagentInspectorActorId(event),
      inspectorActorLabel: _subagentInspectorActorLabel(event),
    );
  }

  List<OpenCrayChatManagedProcessSnapshot> _orderedManagedProcesses(
    OpenCrayChatRunSnapshot run,
  ) => run.managedProcesses.toList(growable: false)
    ..sort((left, right) {
      final int leftSortEpochMs = _managedProcessSortEpochMs(left);
      final int rightSortEpochMs = _managedProcessSortEpochMs(right);
      if (leftSortEpochMs != rightSortEpochMs) {
        return leftSortEpochMs.compareTo(rightSortEpochMs);
      }
      if (left.updatedAtEpochMs != right.updatedAtEpochMs) {
        return left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs);
      }
      return left.processId.compareTo(right.processId);
    });

  int _managedProcessSortEpochMs(OpenCrayChatManagedProcessSnapshot process) {
    return process.startedAtEpochMs > 0
        ? process.startedAtEpochMs
        : process.updatedAtEpochMs;
  }

  ChatRunTraceHistoryEntry? _buildRunFinalAttachmentHistoryEntry(
    OpenCrayChatRunSnapshot run,
  ) {
    if (run.finalAttachments.isEmpty) {
      return null;
    }
    final String label = widget.copy.isChinese ? '最终附件' : 'Final attachments';
    final String compactBody = run.finalAttachments
        .map(_finalAttachmentTitle)
        .where((title) => title.trim().isNotEmpty)
        .join(', ');
    final String inspectorResultBody = run.finalAttachments
        .map(_finalAttachmentInspectorSection)
        .where((section) => section.trim().isNotEmpty)
        .join('\n\n');
    return _mainHistoryEntry(
      label: label,
      body: compactBody.isNotEmpty ? compactBody : label,
      compactBody: compactBody.isNotEmpty ? compactBody : null,
      inspectorCallParts: <ChatRunTraceInspectorTextPart>[
        _inspectorAction(label),
      ],
      inspectorResultBody: inspectorResultBody,
    );
  }

  String _finalAttachmentTitle(OpenCrayChatAttachmentSnapshot attachment) {
    final String displayName = attachment.displayName.trim();
    if (displayName.isNotEmpty) {
      return displayName;
    }
    final String localPath = attachment.localPath.trim();
    if (localPath.isNotEmpty) {
      return localPath.split('/').last;
    }
    final String attachmentId = attachment.attachmentId.trim();
    if (attachmentId.isNotEmpty) {
      return attachmentId;
    }
    return widget.copy.isChinese ? '未命名附件' : 'Unnamed attachment';
  }

  String _finalAttachmentInspectorSection(
    OpenCrayChatAttachmentSnapshot attachment,
  ) {
    final List<String> sections = <String>[
      _joinTraceSections(<String?>[
        widget.copy.isChinese
            ? '名称：${_finalAttachmentTitle(attachment)}'
            : 'Name: ${_finalAttachmentTitle(attachment)}',
        widget.copy.isChinese
            ? '类型：${attachment.kind}'
            : 'Kind: ${attachment.kind}',
        _nonEmpty(attachment.mimeType) != null
            ? (widget.copy.isChinese
                  ? 'MIME：${attachment.mimeType!}'
                  : 'MIME: ${attachment.mimeType!}')
            : null,
        _nonEmpty(attachment.localPath) != null
            ? (widget.copy.isChinese
                  ? '路径：${attachment.localPath}'
                  : 'Path: ${attachment.localPath}')
            : null,
        attachment.sizeBytes != null
            ? (widget.copy.isChinese
                  ? '大小：${attachment.sizeBytes} bytes'
                  : 'Size: ${attachment.sizeBytes} bytes')
            : null,
        attachment.widthPx != null && attachment.heightPx != null
            ? (widget.copy.isChinese
                  ? '尺寸：${attachment.widthPx} x ${attachment.heightPx}'
                  : 'Dimensions: ${attachment.widthPx} x ${attachment.heightPx}')
            : null,
        attachment.durationMs != null
            ? (widget.copy.isChinese
                  ? '时长：${attachment.durationMs} ms'
                  : 'Duration: ${attachment.durationMs} ms')
            : null,
        attachment.waveformBars.isNotEmpty
            ? (widget.copy.isChinese
                  ? '波形：${attachment.waveformBars.join(', ')}'
                  : 'Waveform: ${attachment.waveformBars.join(', ')}')
            : null,
        _nonEmpty(attachment.transcriptText) != null
            ? (widget.copy.isChinese
                  ? '转写：${attachment.transcriptText!}'
                  : 'Transcript: ${attachment.transcriptText!}')
            : null,
        _nonEmpty(attachment.contentSha256) != null
            ? (widget.copy.isChinese
                  ? 'SHA256：${attachment.contentSha256!}'
                  : 'SHA256: ${attachment.contentSha256!}')
            : null,
      ]),
    ];
    return sections.where((section) => section.trim().isNotEmpty).join('\n\n');
  }

  ChatRunTraceHistoryEntry _managedProcessHistoryEntry(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String label = _managedProcessHistoryLabel(process);
    final String commandSummary = _managedProcessCommandSummary(process);
    final String compactBody =
        '${_managedProcessStatusSummary(process)}: $commandSummary';
    final String inspectorDetail = _joinTraceSections(<String?>[
      commandSummary,
      process.workingDirectory?.trim().isNotEmpty == true
          ? (widget.copy.isChinese
                ? '目录：${process.workingDirectory!}'
                : 'cwd: ${process.workingDirectory!}')
          : null,
    ]);
    final String inspectorResultBody = _managedProcessInspectorResultBody(
      process,
    );
    return _mainHistoryEntry(
      label: label,
      body: inspectorResultBody.isNotEmpty ? inspectorResultBody : compactBody,
      compactBody: compactBody,
      inspectorCallParts: <ChatRunTraceInspectorTextPart>[
        _inspectorAction(widget.copy.isChinese ? '进程 ' : 'Process '),
        _inspectorTarget(process.processId),
      ],
      inspectorCallDetail: inspectorDetail,
      inspectorResultBody: inspectorResultBody,
    );
  }

  String _managedProcessHistoryLabel(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    return widget.copy.isChinese
        ? '进程 ${process.processId}'
        : 'Process ${process.processId}';
  }

  String _managedProcessCommandSummary(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final List<String> parts = <String>[process.command, ...process.args];
    return parts
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .join(' ');
  }

  String _managedProcessStatusSummary(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String status = process.status.trim().toLowerCase();
    switch (status) {
      case 'running':
        return widget.copy.isChinese ? '运行中' : 'running';
      case 'success':
        return widget.copy.isChinese ? '已完成' : 'finished';
      case 'failed':
      case 'spawn_error':
        return widget.copy.isChinese ? '失败' : 'failed';
      case 'cancelled':
        return widget.copy.isChinese ? '已取消' : 'cancelled';
      case 'timeout':
        return widget.copy.isChinese ? '已超时' : 'timed out';
      default:
        return status.isEmpty
            ? (widget.copy.isChinese ? '未知' : 'unknown')
            : status;
    }
  }

  String _managedProcessInspectorResultBody(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final String statusLine = _managedProcessInspectorStatusLine(process);
    final String stdoutContent = process.stdout.isNotEmpty
        ? process.stdout
        : process.stdoutPreview;
    final String stderrContent = process.stderr.isNotEmpty
        ? process.stderr
        : process.stderrPreview;
    final bool stdoutUsesPreviewFallback =
        stdoutContent == process.stdoutPreview;
    final bool stderrUsesPreviewFallback =
        stderrContent == process.stderrPreview;
    final String stdoutSection = _managedProcessOutputSection(
      label: 'stdout',
      content: stdoutContent,
      truncated:
          process.outputLimitExceeded ||
          (stdoutUsesPreviewFallback && process.stdoutTruncated),
    );
    final String stderrSection = _managedProcessOutputSection(
      label: 'stderr',
      content: stderrContent,
      truncated: stderrUsesPreviewFallback && process.stderrTruncated,
    );
    return _joinTraceSections(<String?>[
      statusLine,
      stdoutSection,
      stderrSection,
      _nonEmpty(process.errorMessage),
    ]);
  }

  String _managedProcessInspectorStatusLine(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    final List<String> suffixes = <String>[
      if (process.exitCode != null) 'exit ${process.exitCode}',
      if (_nonEmpty(process.errorCode) != null) process.errorCode!,
    ];
    final String suffix = suffixes.isEmpty ? '' : ' (${suffixes.join(', ')})';
    return widget.copy.isChinese
        ? '状态：${_managedProcessStatusSummary(process)}$suffix'
        : 'status: ${_managedProcessStatusSummary(process)}$suffix';
  }

  String _managedProcessOutputSection({
    required String label,
    required String content,
    required bool truncated,
  }) {
    final String normalized = content.trim();
    if (normalized.isEmpty) {
      return '';
    }
    final String suffix = truncated
        ? (widget.copy.isChinese ? '\n[输出已截断]' : '\n[output truncated]')
        : '';
    return '$label\n$normalized$suffix';
  }

  ChatRunTraceHistoryEntry? _mapRunTraceHistoryEntry({
    required OpenCrayChatRuntimeEventSnapshot event,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required int index,
    required Set<int> consumedIndexes,
  }) {
    final toolName = _canonicalToolName(event.toolName);
    switch (event.kind) {
      case 'lifecycle':
        if (event.phase?.toLowerCase() == 'start') {
          return _mainHistoryEntry(
            label: widget.copy.chatRunWorkingLabel,
            body: widget.copy.chatRunThinkingActive,
          );
        }
        return null;
      case 'tool_call':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        final int? pairedResultIndex = _findNextToolResultIndex(
          runEvents,
          afterIndex: index,
          toolName: resolvedToolName,
        );
        final OpenCrayChatRuntimeEventSnapshot? pairedResult =
            pairedResultIndex == null ? null : runEvents[pairedResultIndex];
        if (pairedResultIndex != null) {
          consumedIndexes.add(pairedResultIndex);
        }
        return _buildGroupedToolHistoryEntry(
          toolName: resolvedToolName,
          toolCallEvent: event,
          toolResultEvent: pairedResult,
        );
      case 'tool_result':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
            _findPreviousToolCall(
              runEvents,
              beforeIndex: index,
              toolName: resolvedToolName,
            );
        return _buildGroupedToolHistoryEntry(
          toolName: resolvedToolName,
          toolCallEvent: pairedToolCall,
          toolResultEvent: event,
        );
      case 'approval_wait':
      case 'approval_result':
        return _mainHistoryEntry(
          label: _approvalTraceLabel(event),
          body: _buildApprovalHistoryBody(event),
          isHighRisk: event.isHighRisk,
        );
      case 'interrupted':
        return _mainHistoryEntry(
          label: _cancellationTraceLabel(event),
          body: _buildCancellationHistoryBody(event),
        );
      case 'subagent':
        return _subagentHistoryEntry(
          event: event,
          label: _subagentTraceLabel(event),
          body: _buildSubagentHistoryBody(event),
        );
      case 'supplement':
        return _mainHistoryEntry(
          label: _supplementTraceLabel(),
          body: _buildSupplementHistoryBody(event),
        );
      case 'memory_retrieval':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        return _mainHistoryEntry(
          label: resolvedToolName,
          body: _buildMemoryRetrievalHistoryBody(event),
        );
      case 'memory_write':
        return _mainHistoryEntry(
          label: _memoryMaintenanceLabel(),
          body: _buildMemoryWriteHistoryBody(event),
        );
      case 'assistant':
        final text = event.text?.trim();
        return _mainHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : widget.copy.chatRunThinkingActive,
        );
      case 'assistant_phase':
        final text = event.text?.trim();
        return _mainHistoryEntry(
          label: _assistantPhaseEntryLabel(event),
          body: text?.isNotEmpty == true
              ? text!
              : widget.copy.chatRunThinkingActive,
        );
      default:
        return null;
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunContextHistory(
    OpenCrayChatRunSnapshot run,
  ) {
    final history = <ChatRunTraceHistoryEntry>[];
    final String? liveContextBody = _buildRunLiveContextHistoryBody(
      run.liveContext,
    );
    final String? memoryTraceBody = _buildRunMemoryTraceHistoryBody(
      run.memoryTrace,
    );
    final String? memoryFlushBody = _buildRunMemoryFlushHistoryBody(
      run.memoryFlush,
    );
    final String? bootstrapBody = _buildRunBootstrapHistoryBody(run.bootstrap);
    final String? durableCompactionBody = _buildRunDurableCompactionHistoryBody(
      run.durableCompaction,
    );
    final String? skillInventoryBody = _buildRunSkillInventoryHistoryBody(
      run.skillInventory,
    );
    final String? activeSkillBody = _buildRunActiveSkillHistoryBody(
      run.activeSkill,
    );
    if (liveContextBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Live Context', chinese: '实时上下文'),
          body: liveContextBody,
        ),
      );
    }
    if (bootstrapBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Bootstrap', chinese: '启动上下文'),
          body: bootstrapBody,
        ),
      );
    }
    if (memoryTraceBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Retrieved Memory',
            chinese: '记忆召回',
          ),
          body: memoryTraceBody,
        ),
      );
    }
    if (memoryFlushBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Memory Flush', chinese: '记忆刷新'),
          body: memoryFlushBody,
        ),
      );
    }
    if (durableCompactionBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Durable Compaction',
            chinese: '持久压缩',
          ),
          body: durableCompactionBody,
        ),
      );
    }
    if (skillInventoryBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(
            english: 'Skill Inventory',
            chinese: '技能清单',
          ),
          body: skillInventoryBody,
        ),
      );
    }
    if (activeSkillBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Active Skill', chinese: '活动技能'),
          body: activeSkillBody,
        ),
      );
    }
    return history;
  }

  String? _buildRunLiveContextHistoryBody(
    OpenCrayChatRunLiveContextSnapshot? liveContext,
  ) {
    if (liveContext == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (_nonEmpty(liveContext.mode) != null)
        widget.copy.isChinese
            ? '模式 ${liveContext.mode}'
            : 'Mode: ${liveContext.mode}',
      if (liveContext.soulEnabled != null)
        widget.copy.isChinese
            ? (liveContext.soulEnabled! ? 'Soul 已启用' : 'Soul 已关闭')
            : (liveContext.soulEnabled! ? 'Soul enabled' : 'Soul disabled'),
      if (liveContext.memoryRecallEnabled != null)
        widget.copy.isChinese
            ? (liveContext.memoryRecallEnabled! ? '自动记忆召回已启用' : '自动记忆召回已关闭')
            : (liveContext.memoryRecallEnabled!
                  ? 'Automatic memory recall enabled'
                  : 'Automatic memory recall disabled'),
    ];
    return summary.isEmpty
        ? null
        : summary.join(widget.copy.isChinese ? '，' : ', ');
  }

  String? _buildRunMemoryTraceHistoryBody(
    OpenCrayChatRunMemoryTraceSnapshot? trace,
  ) {
    if (trace == null) {
      return null;
    }
    final List<String> countParts = <String>[
      if (trace.matchedRecordCount != null)
        widget.copy.isChinese
            ? '命中 ${trace.matchedRecordCount} 条'
            : '${trace.matchedRecordCount} matched',
      if (trace.injectedRecordCount != null)
        widget.copy.isChinese
            ? '注入 ${trace.injectedRecordCount} 条'
            : '${trace.injectedRecordCount} injected',
      if (trace.omittedRecordCount != null)
        widget.copy.isChinese
            ? '省略 ${trace.omittedRecordCount} 条'
            : '${trace.omittedRecordCount} omitted',
    ];
    final String? queryTerms = trace.queryTerms.isEmpty
        ? null
        : widget.copy.isChinese
        ? '关键词：${trace.queryTerms.join(', ')}'
        : 'Query terms: ${trace.queryTerms.join(', ')}';
    final String? selected = trace.selected.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Selected',
            chineseLabel: '已注入',
            values: trace.selected
                .map(_formatRunMemorySelectedSummary)
                .toList(),
          );
    final String? omitted = trace.omitted.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Omitted',
            chineseLabel: '已省略',
            values: trace.omitted.map(_formatRunMemoryOmittedSummary).toList(),
          );
    final String? filteredCounts = trace.filteredCounts.isEmpty
        ? null
        : widget.copy.isChinese
        ? '过滤统计：${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join('，')}'
        : 'Filtered counts: ${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join(', ')}';
    return _joinTraceSections(<String?>[
      countParts.isEmpty
          ? null
          : countParts.join(widget.copy.isChinese ? '，' : ', '),
      queryTerms,
      selected,
      omitted,
      filteredCounts,
    ]);
  }

  String _formatRunMemorySelectedSummary(
    OpenCrayChatRunMemorySelectedSnapshot selected,
  ) {
    final List<String> parts = <String>[selected.id];
    if (selected.score != null) {
      parts.add(
        widget.copy.isChinese
            ? '分数 ${selected.score}'
            : 'score ${selected.score}',
      );
    }
    if (selected.matchedTerms.isNotEmpty) {
      parts.add(
        widget.copy.isChinese
            ? '匹配 ${selected.matchedTerms.join(', ')}'
            : 'matched ${selected.matchedTerms.join(', ')}',
      );
    }
    return parts.join(widget.copy.isChinese ? '，' : ', ');
  }

  String _formatRunMemoryOmittedSummary(
    OpenCrayChatRunMemoryOmittedSnapshot omitted,
  ) {
    if (omitted.reason.trim().isEmpty) {
      return omitted.id;
    }
    return widget.copy.isChinese
        ? '${omitted.id}，原因 ${omitted.reason}'
        : '${omitted.id}, reason ${omitted.reason}';
  }

  String? _buildRunMemoryFlushHistoryBody(
    OpenCrayChatRunMemoryFlushSnapshot? flush,
  ) {
    if (flush == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (flush.outcome != null)
        widget.copy.isChinese
            ? '结果 ${flush.outcome}'
            : 'Outcome: ${flush.outcome}',
      if (flush.candidateCount != null)
        widget.copy.isChinese
            ? '候选 ${flush.candidateCount} 条'
            : '${flush.candidateCount} candidate(s)',
      if (flush.writtenRecordCount != null)
        widget.copy.isChinese
            ? '写入 ${flush.writtenRecordCount} 条'
            : '${flush.writtenRecordCount} written',
    ];
    final List<String> omitted = <String>[
      if (flush.omittedMessageCount != null)
        widget.copy.isChinese
            ? '省略消息 ${flush.omittedMessageCount} 条'
            : '${flush.omittedMessageCount} omitted message(s)',
      if (flush.omittedCharCount != null)
        widget.copy.isChinese
            ? '省略字符 ${flush.omittedCharCount}'
            : '${flush.omittedCharCount} omitted char(s)',
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(widget.copy.isChinese ? '，' : ', '),
      omitted.isEmpty ? null : omitted.join(widget.copy.isChinese ? '，' : ', '),
      flush.signature == null
          ? null
          : widget.copy.isChinese
          ? '签名：${flush.signature}'
          : 'Signature: ${flush.signature}',
      _labeledInlineSection(
        englishLabel: 'Kinds',
        chineseLabel: '类型',
        values: flush.writtenKinds,
      ),
      _labeledInlineSection(
        englishLabel: 'Written',
        chineseLabel: '写入',
        values: flush.writtenRecordIds,
      ),
    ]);
  }

  String? _buildRunBootstrapHistoryBody(
    OpenCrayChatRunBootstrapSnapshot? bootstrap,
  ) {
    if (bootstrap == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (bootstrap.mode != null)
        widget.copy.isChinese
            ? '模式 ${bootstrap.mode}'
            : 'Mode: ${bootstrap.mode}',
      if (bootstrap.visibleFileCount != null)
        widget.copy.isChinese
            ? '可见 ${bootstrap.visibleFileCount} 个文件'
            : '${bootstrap.visibleFileCount} visible file(s)',
      if (bootstrap.injectedFileCount != null)
        widget.copy.isChinese
            ? '注入 ${bootstrap.injectedFileCount} 个'
            : '${bootstrap.injectedFileCount} injected',
      if (bootstrap.omittedFileCount != null)
        widget.copy.isChinese
            ? '省略 ${bootstrap.omittedFileCount} 个'
            : '${bootstrap.omittedFileCount} omitted',
      if (bootstrap.truncatedFileCount != null)
        widget.copy.isChinese
            ? '截断 ${bootstrap.truncatedFileCount} 个'
            : '${bootstrap.truncatedFileCount} truncated',
    ];
    final List<String> files = bootstrap.files
        .map((file) {
          final List<String> suffix = <String>[
            if (file.injectedCharCount != null)
              widget.copy.isChinese
                  ? '注入 ${file.injectedCharCount}'
                  : 'injected ${file.injectedCharCount}',
            if (file.sourceCharCount != null)
              widget.copy.isChinese
                  ? '原始 ${file.sourceCharCount}'
                  : 'source ${file.sourceCharCount}',
            if (file.truncated == true)
              widget.copy.isChinese ? '已截断' : 'truncated',
          ];
          final String detail = suffix.isEmpty
              ? ''
              : widget.copy.isChinese
              ? '，${suffix.join('，')}'
              : ' (${suffix.join(', ')})';
          return '${file.name} (${file.relativePath})$detail';
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(widget.copy.isChinese ? '，' : ', '),
      _labeledMultilineSection(
        englishLabel: 'Files',
        chineseLabel: '文件',
        values: files,
      ),
    ]);
  }

  String? _buildRunDurableCompactionHistoryBody(
    OpenCrayChatRunDurableCompactionSnapshot? durableCompaction,
  ) {
    if (durableCompaction == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (durableCompaction.compactedThisRun != null)
        widget.copy.isChinese
            ? (durableCompaction.compactedThisRun! ? '本轮已压缩' : '本轮未压缩')
            : (durableCompaction.compactedThisRun!
                  ? 'Compacted this run'
                  : 'No compaction this run'),
      if (durableCompaction.sourceTranscriptMessageCount != null &&
          durableCompaction.retainedTranscriptMessageCount != null)
        widget.copy.isChinese
            ? '保留 ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} 条消息'
            : 'Retained ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} transcript messages',
      if (durableCompaction.latestCompactedMessageCount != null)
        widget.copy.isChinese
            ? '最近压缩 ${durableCompaction.latestCompactedMessageCount} 条'
            : 'Latest compacted ${durableCompaction.latestCompactedMessageCount} message(s)',
    ];
    final List<String> summaryCounts = <String>[
      if (durableCompaction.includedSummaryCount != null)
        widget.copy.isChinese
            ? '纳入摘要 ${durableCompaction.includedSummaryCount} 个'
            : '${durableCompaction.includedSummaryCount} included summary(s)',
      if (durableCompaction.totalSummaryCount != null)
        widget.copy.isChinese
            ? '总摘要 ${durableCompaction.totalSummaryCount} 个'
            : '${durableCompaction.totalSummaryCount} total summary(ies)',
      if (durableCompaction.totalCompactedMessageCount != null)
        widget.copy.isChinese
            ? '累计压缩 ${durableCompaction.totalCompactedMessageCount} 条'
            : '${durableCompaction.totalCompactedMessageCount} total compacted message(s)',
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(widget.copy.isChinese ? '，' : ', '),
      summaryCounts.isEmpty
          ? null
          : summaryCounts.join(widget.copy.isChinese ? '，' : ', '),
      durableCompaction.latestCompactedAtEpochMs == null
          ? null
          : widget.copy.isChinese
          ? '最近压缩时间：${durableCompaction.latestCompactedAtEpochMs}'
          : 'Latest compaction at ${durableCompaction.latestCompactedAtEpochMs}',
    ]);
  }

  String? _buildRunSkillInventoryHistoryBody(
    OpenCrayChatRunSkillInventorySnapshot? skillInventory,
  ) {
    if (skillInventory == null) {
      return null;
    }
    final List<String> counts = <String>[
      if (skillInventory.visibleSkillCount != null)
        widget.copy.isChinese
            ? '可见 ${skillInventory.visibleSkillCount} 个'
            : '${skillInventory.visibleSkillCount} visible',
      if (skillInventory.injectedSkillCount != null)
        widget.copy.isChinese
            ? '注入 ${skillInventory.injectedSkillCount} 个'
            : '${skillInventory.injectedSkillCount} injected',
      if (skillInventory.omittedSkillCount != null)
        widget.copy.isChinese
            ? '省略 ${skillInventory.omittedSkillCount} 个'
            : '${skillInventory.omittedSkillCount} omitted',
      if (skillInventory.implicitSkillCount != null)
        widget.copy.isChinese
            ? '隐式 ${skillInventory.implicitSkillCount} 个'
            : '${skillInventory.implicitSkillCount} implicit',
      if (skillInventory.invalidSkillCount != null)
        widget.copy.isChinese
            ? '无效 ${skillInventory.invalidSkillCount} 个'
            : '${skillInventory.invalidSkillCount} invalid',
    ];
    final String? omittedTrace = skillInventory.omittedTraceSkillCount == null
        ? null
        : widget.copy.isChinese
        ? '省略轨迹 ${skillInventory.omittedTraceSkillCount} 个'
        : 'Omitted trace skills: ${skillInventory.omittedTraceSkillCount}';
    final List<String> skills = skillInventory.skills
        .map((skill) {
          final List<String> parts = <String>[skill.name];
          final String? relativePath = _nonEmpty(skill.relativePath);
          if (relativePath != null) {
            parts.add(relativePath);
          }
          final String? invocationControl = _nonEmpty(skill.invocationControl);
          if (invocationControl != null) {
            parts.add(invocationControl);
          }
          final String? executionContext = _nonEmpty(skill.executionContext);
          if (executionContext != null) {
            parts.add(executionContext);
          }
          return parts.join(widget.copy.isChinese ? '，' : ' · ');
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      counts.isEmpty ? null : counts.join(widget.copy.isChinese ? '，' : ', '),
      omittedTrace,
      _labeledMultilineSection(
        englishLabel: 'Skills',
        chineseLabel: '技能',
        values: skills,
      ),
    ]);
  }

  String? _buildRunActiveSkillHistoryBody(
    OpenCrayChatRunActiveSkillSnapshot? activeSkill,
  ) {
    if (activeSkill == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (_nonEmpty(activeSkill.name) != null)
        widget.copy.isChinese
            ? '名称 ${activeSkill.name}'
            : 'Name: ${activeSkill.name}',
      if (_nonEmpty(activeSkill.relativePath) != null)
        widget.copy.isChinese
            ? '路径 ${activeSkill.relativePath}'
            : 'Path: ${activeSkill.relativePath}',
      if (_nonEmpty(activeSkill.activationSource) != null)
        widget.copy.isChinese
            ? '来源 ${activeSkill.activationSource}'
            : 'Activation: ${activeSkill.activationSource}',
      if (_nonEmpty(activeSkill.executionContext) != null)
        widget.copy.isChinese
            ? '上下文 ${activeSkill.executionContext}'
            : 'Context: ${activeSkill.executionContext}',
      if (activeSkill.toolRestrictionEnabled != null)
        widget.copy.isChinese
            ? (activeSkill.toolRestrictionEnabled! ? '已启用工具限制' : '未启用工具限制')
            : (activeSkill.toolRestrictionEnabled!
                  ? 'Tool restriction enabled'
                  : 'Tool restriction disabled'),
      if (activeSkill.truncated != null)
        widget.copy.isChinese
            ? (activeSkill.truncated! ? '胶囊已截断' : '胶囊未截断')
            : (activeSkill.truncated! ? 'Capsule truncated' : 'Capsule intact'),
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(widget.copy.isChinese ? '，' : ', '),
      _labeledInlineSection(
        englishLabel: 'Allowed tools',
        chineseLabel: '允许工具',
        values: activeSkill.allowedToolKeys,
      ),
    ]);
  }

  OpenCrayChatPendingApprovalSnapshot? _pendingApprovalForRun({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
  }) {
    final String runId = run.runId.trim();
    final String taskId = run.taskId.trim();
    for (final approval in pendingApprovals) {
      if (runId.isNotEmpty && approval.runId.trim() == runId) {
        return approval;
      }
      if (taskId.isNotEmpty && approval.taskId.trim() == taskId) {
        return approval;
      }
    }
    return null;
  }

  OpenCrayChatRuntimeEventSnapshot? _latestApprovalEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final event = runEvents[index];
      if (event.kind == 'approval_wait' || event.kind == 'approval_result') {
        return event;
      }
    }
    return null;
  }

  bool _isApprovalRequiredErrorCode(String? errorCode) =>
      errorCode == 'APPROVAL_REQUIRED' ||
      errorCode == 'HIGH_RISK_APPROVAL_REQUIRED';

  bool _isApprovalApprovedEvent(OpenCrayChatRuntimeEventSnapshot? event) =>
      event?.kind == 'approval_result' &&
      _nonEmpty(event?.status)?.toLowerCase() == 'approved';

  bool _isApprovalRejectedEvent(OpenCrayChatRuntimeEventSnapshot? event) =>
      event?.kind == 'approval_result' &&
      _nonEmpty(event?.status)?.toLowerCase() == 'rejected';

  bool _isWaitingApproval({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
  }) {
    if (pendingApproval != null) {
      return true;
    }
    if (_latestApprovalEvent(runEvents) != null) {
      return false;
    }
    return _isApprovalRequiredErrorCode(run.errorCode);
  }

  bool _shouldShowResolvedApprovalPreview({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeEventSnapshot? event,
    required OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent,
    required OpenCrayChatPendingApprovalSnapshot? pendingApproval,
  }) {
    if (pendingApproval != null || run.isTerminal) {
      return false;
    }
    if (event != null &&
        event.kind != 'approval_wait' &&
        event.kind != 'approval_result' &&
        event.kind != 'tool_result') {
      return false;
    }
    if (_isApprovalRejectedEvent(event) ||
        _isApprovalRejectedEvent(latestApprovalEvent)) {
      return false;
    }
    if (_isApprovalApprovedEvent(event) ||
        _isApprovalApprovedEvent(latestApprovalEvent)) {
      return true;
    }
    if (event?.kind == 'approval_wait') {
      return true;
    }
    if (event?.kind == 'tool_result' &&
        _isApprovalRequiredErrorCode(event?.errorCode)) {
      return true;
    }
    return false;
  }

  OpenCrayChatRuntimeEventSnapshot? _latestToolContextEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    String? preferredToolName,
  }) {
    final String? normalizedToolName = _nonEmpty(preferredToolName);
    OpenCrayChatRuntimeEventSnapshot? fallback;
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call' && candidate.kind != 'tool_result') {
        continue;
      }
      fallback ??= candidate;
      if (normalizedToolName == null) {
        continue;
      }
      final String? candidateToolName = _nonEmpty(candidate.toolName);
      if (candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return fallback;
  }

  _ResolvedApprovalPreview _buildResolvedApprovalPreview({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required OpenCrayChatRuntimeEventSnapshot? latestApprovalEvent,
  }) {
    final String? preferredToolName =
        _nonEmpty(latestApprovalEvent?.toolName) ??
        _nonEmpty(run.lastEvent?.toolName);
    final OpenCrayChatRuntimeEventSnapshot? toolContext =
        _latestToolContextEvent(
          runEvents,
          preferredToolName: preferredToolName,
        );
    final String label =
        _nonEmpty(toolContext?.toolName) ??
        preferredToolName ??
        widget.copy.chatRunWorkingLabel;
    String? actionBody;
    if (toolContext != null) {
      switch (toolContext.kind) {
        case 'tool_call':
          actionBody = _buildToolCallPreviewBody(toolContext);
          break;
        case 'tool_result':
          final int beforeIndex = runEvents.indexOf(toolContext);
          final OpenCrayChatRuntimeEventSnapshot? pairedToolCall =
              beforeIndex < 0
              ? null
              : _findPreviousToolCall(
                  runEvents,
                  beforeIndex: beforeIndex,
                  toolName: toolContext.toolName?.trim(),
                );
          actionBody = pairedToolCall != null
              ? _buildToolCallPreviewBody(pairedToolCall)
              : _toolResultActionSummary(
                  toolName: label,
                  event: toolContext,
                  pairedToolCall: pairedToolCall,
                );
          break;
      }
    }
    final String statusBody = _isApprovalApprovedEvent(latestApprovalEvent)
        ? (widget.copy.isChinese
              ? '审批已通过，正在继续执行 $label。'
              : 'Approval granted. Resuming $label.')
        : (widget.copy.isChinese
              ? '$label 的审批状态已更新，正在恢复执行。'
              : 'Approval updated. Resuming $label.');
    return _ResolvedApprovalPreview(
      label: label,
      body: _joinTraceSections(<String?>[actionBody, statusBody]),
    );
  }

  bool _isInterruptedOnRestoreRun(OpenCrayChatRunSnapshot run) =>
      run.errorCode == 'RESTART_REQUIRES_EXPLICIT_RETRY' ||
      run.errorCode == 'PROCESS_INTERRUPTED_ON_RESTORE';

  bool _isLlmRetryPausedRun(OpenCrayChatRunSnapshot run) =>
      run.lifecycleState?.trim().toLowerCase() == 'suspended' &&
      run.errorCode == 'LLM_RETRY_EXHAUSTED_AWAITING_RESUME';

  bool _isDeferredApprovalDecisionRun(OpenCrayChatRunSnapshot run) {
    final String? checkpointKind = run.recoveryPlan?.checkpointKind
        ?.trim()
        .toLowerCase();
    return run.lifecycleState?.trim().toLowerCase() == 'suspended' &&
        (checkpointKind == 'approved_pending_resume' ||
            checkpointKind == 'rejected_pending_resume') &&
        run.recoveryPlan?.action == 'resume_waiting_for_user';
  }

  String _interruptedRunLabel() =>
      widget.copy.isChinese ? '运行已中断' : 'Run interrupted';

  String _retryInterruptedRunLabel() =>
      widget.copy.isChinese ? '重新启动' : 'Restart run';

  String _pausedRunLabel() => widget.copy.chatRunAwaitingDirectionLabel;

  String _interruptedRunBody(OpenCrayChatRunSnapshot run) {
    final body = run.errorMessage?.trim();
    if (body != null && body.isNotEmpty) {
      return body;
    }
    return widget.copy.isChinese
        ? '宿主重建时这次运行被显式中断，避免从头静默重跑。需要你明确触发后才会继续。'
        : 'This run was interrupted during host recovery to avoid silently rerunning from the beginning. Restart it explicitly when you want to continue.';
  }

  String _pausedRunBody() => widget.copy.chatRunLlmRetryPausedBody;

  String _deferredApprovalDecisionBody() =>
      widget.copy.chatRunApprovalDecisionDeferredBody;

  List<OpenCrayChatRuntimeEventSnapshot> _runEventsFor({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) => run.scopeRuntimeEvents(runtimeSnapshot.events).toList(growable: false)
    ..sort(
      (left, right) => left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
    );

  OpenCrayChatRuntimeEventSnapshot? _latestRunTraceEvent(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      if (event.kind == 'assistant_phase' && _hideAssistantPhaseBubble(event)) {
        continue;
      }
      return event;
    }
    return null;
  }

  ChatRunTracePreviewCardData? _latestRunTracePreviewCard(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      final String toolName = event.toolName?.trim().toLowerCase() ?? '';
      if (event.kind != 'tool_result' || toolName != 'sandbox_preview_open') {
        continue;
      }
      final String? url = _resultMetadataValue(event, 'previewUrl');
      if (url == null) {
        continue;
      }
      return ChatRunTracePreviewCardData(
        url: url,
        status: _previewStatusFromWire(
          _resultMetadataValue(event, 'previewProbeStatus'),
        ),
        port: _resultMetadataInt(event, 'previewPort'),
        path: _resultMetadataValue(event, 'previewPath'),
        provider: _resultMetadataValue(event, 'sandboxProvider'),
        httpStatusCode: _resultMetadataInt(event, 'previewProbeHttpStatus'),
        message: _resultMetadataValue(event, 'previewProbeMessage'),
      );
    }
    return null;
  }

  ChatRunTraceSandboxSessionCardData? _latestRunTraceSandboxSessionCard(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  ) {
    for (int index = runEvents.length - 1; index >= 0; index -= 1) {
      final OpenCrayChatRuntimeEventSnapshot event = runEvents[index];
      final String toolName = event.toolName?.trim().toLowerCase() ?? '';
      if (event.kind != 'tool_result' || toolName != 'sandbox_session_info') {
        continue;
      }
      final bool sessionPresent =
          _resultMetadataBool(event, 'sandboxSessionPresent') == true;
      return ChatRunTraceSandboxSessionCardData(
        sessionPresent: sessionPresent,
        source: _sandboxSessionSourceFromWire(
          _resultMetadataValue(event, 'sandboxSessionSource'),
        ),
        lifecycleStatus: _sandboxSessionLifecycleStatusFromWire(
          _resultMetadataValue(event, 'sandboxSessionLifecycleStatus'),
          sessionPresent: sessionPresent,
        ),
        provider: _resultMetadataValue(event, 'sandboxProvider'),
        sandboxId: _resultMetadataValue(event, 'sandboxId'),
        sandboxDomain: _resultMetadataValue(event, 'sandboxDomain'),
        templateId: _resultMetadataValue(event, 'sandboxTemplateId'),
        updatedAtEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionUpdatedAtEpochMs',
        ),
        sessionLastActivityAtEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionLastActivityAtEpochMs',
        ),
        sessionStaleAfterEpochMs: _resultMetadataInt(
          event,
          'sandboxSessionStaleAfterEpochMs',
        ),
        lastPreviewUrl: _resultMetadataValue(event, 'sandboxLastPreviewUrl'),
        lastPreviewProbeStatus:
            _resultMetadataValue(event, 'sandboxLastPreviewProbeStatus') == null
            ? null
            : _previewStatusFromWire(
                _resultMetadataValue(event, 'sandboxLastPreviewProbeStatus'),
              ),
        lastPreviewProbeObservedAtEpochMs: _resultMetadataInt(
          event,
          'sandboxLastPreviewProbeObservedAtEpochMs',
        ),
        lastPreviewProbeSource: _resultMetadataValue(
          event,
          'sandboxLastPreviewProbeSource',
        ),
        autoRefreshAfterMs: _resultMetadataInt(
          event,
          'sandboxSessionAutoRefreshAfterMs',
        ),
        previewCandidatePorts: _resultMetadataCsvInts(
          event,
          'sandboxPreviewCandidatePorts',
        ),
        runningRequestIds: _resultMetadataCsvStrings(
          event,
          'sandboxRunningRequestIds',
        ),
      );
    }
    return null;
  }

  ChatRunTracePreviewStatus _previewStatusFromWire(String? rawValue) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'ready':
        return ChatRunTracePreviewStatus.ready;
      case 'reachable':
        return ChatRunTracePreviewStatus.reachable;
      case 'unreachable':
        return ChatRunTracePreviewStatus.unreachable;
      case 'skipped':
      default:
        return ChatRunTracePreviewStatus.skipped;
    }
  }

  ChatRunTraceSandboxSessionSource _sandboxSessionSourceFromWire(
    String? rawValue,
  ) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'active_memory':
        return ChatRunTraceSandboxSessionSource.activeMemory;
      case 'persisted':
        return ChatRunTraceSandboxSessionSource.persisted;
      case 'active_memory_and_persisted':
        return ChatRunTraceSandboxSessionSource.activeAndPersisted;
      case 'none':
      default:
        return ChatRunTraceSandboxSessionSource.none;
    }
  }

  ChatRunTraceSandboxSessionLifecycleStatus
  _sandboxSessionLifecycleStatusFromWire(
    String? rawValue, {
    required bool sessionPresent,
  }) {
    switch (rawValue?.trim().toLowerCase()) {
      case 'active':
        return ChatRunTraceSandboxSessionLifecycleStatus.active;
      case 'stale':
        return ChatRunTraceSandboxSessionLifecycleStatus.stale;
      case 'reclaimed':
        return ChatRunTraceSandboxSessionLifecycleStatus.reclaimed;
      case 'none':
        return ChatRunTraceSandboxSessionLifecycleStatus.none;
      default:
        return sessionPresent
            ? ChatRunTraceSandboxSessionLifecycleStatus.active
            : ChatRunTraceSandboxSessionLifecycleStatus.none;
    }
  }

  static const Map<String, String> _displayToolAliases = <String, String>{
    'workspace_read_file': 'Read',
    'workspace_list_files': 'LS',
    'workspace_write_file': 'Write',
    'workspace_import_file': 'ImportFile',
    'bash': 'Bash',
    'list': 'LS',
    'ls': 'LS',
    'read': 'Read',
    'write': 'Write',
    'grep': 'Grep',
    'glob': 'Glob',
    'websearch': 'WebSearch',
    'webfetch': 'WebFetch',
    'generateimage': 'GenerateImage',
    'imagegenerate': 'GenerateImage',
    'synthesizespeech': 'SynthesizeSpeech',
    'texttospeech': 'SynthesizeSpeech',
    'tts': 'SynthesizeSpeech',
    'edit': 'Edit',
    'multiedit': 'MultiEdit',
    'importfile': 'ImportFile',
    'import': 'ImportFile',
    'importchatattachment': 'import_chat_attachment',
    'searchworkspacedocument': 'search_workspace_document',
    'inspectworkspacepackage': 'inspect_workspace_package',
    'extractworkspacepackage': 'extract_workspace_package',
    'viewworkspacedocument': 'view_workspace_document',
    'viewworkspaceimage': 'view_workspace_image',
    'viewworkspacepdf': 'view_workspace_pdf',
    'todowrite': 'TodoWrite',
    'scheduledtaskcreate': 'ScheduledTaskCreate',
    'scheduled_task_create': 'ScheduledTaskCreate',
    'scheduledtasklist': 'ScheduledTaskList',
    'scheduled_task_list': 'ScheduledTaskList',
    'scheduledtaskget': 'ScheduledTaskGet',
    'scheduled_task_get': 'ScheduledTaskGet',
    'scheduledtaskupdate': 'ScheduledTaskUpdate',
    'scheduled_task_update': 'ScheduledTaskUpdate',
    'scheduledtaskdelete': 'ScheduledTaskDelete',
    'scheduled_task_delete': 'ScheduledTaskDelete',
    'task': 'Task',
    'spawnagent': 'spawn_agent',
    'waitagent': 'wait_agent',
    'sendinput': 'send_input',
    'closeagent': 'close_agent',
    'listsubagents': 'list_subagents',
    'list_handles': 'list_subagents',
    'listhandles': 'list_subagents',
    'processstart': 'ProcessStart',
    'processlist': 'ProcessList',
    'processread': 'ProcessRead',
    'processwait': 'ProcessWait',
    'processterminate': 'ProcessTerminate',
  };

  String? _canonicalToolName(String? toolName) {
    final String? normalizedToolName = toolName?.trim();
    if (normalizedToolName == null || normalizedToolName.isEmpty) {
      return normalizedToolName;
    }
    return _displayToolAliases[normalizedToolName] ?? normalizedToolName;
  }

  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = beforeIndex - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call') {
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null ||
          normalizedToolName.isEmpty ||
          candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return null;
  }

  int? _findNextToolResultIndex(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int afterIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = _canonicalToolName(toolName);
    for (int index = afterIndex + 1; index < runEvents.length; index += 1) {
      final candidate = runEvents[index];
      if (candidate.kind == 'tool_call') {
        return null;
      }
      if (candidate.kind != 'tool_result') {
        if (!_isSkippableToolGroupingInterveningEvent(candidate)) {
          return null;
        }
        continue;
      }
      final String? candidateToolName = _canonicalToolName(candidate.toolName);
      if (normalizedToolName == null || normalizedToolName.isEmpty) {
        return index;
      }
      if (candidateToolName == normalizedToolName) {
        return index;
      }
    }
    return null;
  }

  bool _isSkippableToolGroupingInterveningEvent(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    if (event.kind == 'lifecycle') {
      final String phase = event.phase?.trim().toLowerCase() ?? '';
      return phase.isNotEmpty;
    }
    return event.kind == 'subagent';
  }

  ChatRunTraceHistoryEntry _buildGroupedToolHistoryEntry({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? toolCallEvent,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final _ToolInspectorCallDisplay callDisplay =
        _buildToolInspectorCallDisplay(
          toolName: toolName,
          event: toolCallEvent,
          toolResultEvent: toolResultEvent,
        );
    final String callBody = _joinTraceSections(<String?>[
      callDisplay.text,
      callDisplay.detail,
    ]);
    final String? resultBody = toolResultEvent == null
        ? null
        : _buildGroupedToolResultBody(
            toolName: toolName,
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
          );
    final String inspectorBody = resultBody == null
        ? callBody
        : '$callBody\n${_indentGroupedToolBlock(resultBody, connector: true)}';
    final String compactBody = toolResultEvent == null
        ? _buildToolCallPreviewBody(
            toolCallEvent ??
                OpenCrayChatRuntimeEventSnapshot(
                  kind: 'tool_call',
                  runId: '',
                  taskId: '',
                  emittedAtEpochMs: 0,
                  toolName: toolName,
                ),
          )
        : _buildToolResultPreviewBody(
            event: toolResultEvent,
            pairedToolCall: toolCallEvent,
            waitingApproval: false,
            runErrorMessage: null,
          );
    return _mainHistoryEntry(
      label: toolName,
      body: inspectorBody,
      compactBody: compactBody,
      isHighRisk:
          toolResultEvent?.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED' ||
          toolResultEvent?.isHighRisk == true,
      inspectorCallParts: callDisplay.parts,
      inspectorCallDetail: callDisplay.detail ?? '',
      inspectorResultBody: resultBody ?? '',
    );
  }

  _ToolInspectorCallDisplay _buildToolInspectorCallDisplay({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot? event,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(_nonEmpty(event?.argumentsJson)) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    final List<ChatRunTraceInspectorTextPart> parts =
        _toolInspectorCallParts(toolName: toolName, arguments: arguments) ??
        <ChatRunTraceInspectorTextPart>[
          ChatRunTraceInspectorTextPart(
            text: _toolActionSummaryFromArguments(
              toolName: toolName,
              arguments: arguments,
            ),
          ),
        ];
    final String? reason = _nonEmpty(event?.toolReason);
    final String? detail = _toolInspectorCallDetailBody(
      toolName: toolName,
      argumentsJson: _nonEmpty(event?.argumentsJson),
      toolResultEvent: toolResultEvent,
    );
    final String? combinedDetail =
        _joinTraceSections(<String?>[
          reason == null
              ? null
              : widget.copy.isChinese
              ? '理由：$reason'
              : 'Reason: $reason',
          detail,
        ]).trim().isEmpty
        ? null
        : _joinTraceSections(<String?>[
            reason == null
                ? null
                : widget.copy.isChinese
                ? '理由：$reason'
                : 'Reason: $reason',
            detail,
          ]);
    return _ToolInspectorCallDisplay(
      text: _joinInspectorPartText(parts),
      parts: parts,
      detail: combinedDetail,
    );
  }

  List<ChatRunTraceInspectorTextPart>? _toolInspectorCallParts({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final String range = _readRangeSummary(
          offset: _argumentInt(arguments, 'offset'),
          limit: _argumentInt(arguments, 'limit'),
        );
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '读取' : 'Read'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (range.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? '，' : ' '),
            _inspectorScope(range),
          ],
        ];
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '列出' : 'List'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '搜索' : 'Search'),
          _inspectorNeutral(' '),
          _inspectorTarget('"$pattern"'),
          _inspectorNeutral(widget.copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
          if (glob != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? '，glob ' : ' (glob: '),
            _inspectorScope(glob),
            if (!widget.copy.isChinese) _inspectorNeutral(')'),
          ],
        ];
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return null;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '匹配' : 'Match'),
          _inspectorNeutral(' '),
          _inspectorTarget(pattern),
          _inspectorNeutral(widget.copy.isChinese ? ' 于 ' : ' in '),
          _inspectorScope(path),
        ];
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '写入' : 'Write'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '导入' : 'Import'),
          _inspectorNeutral(' '),
          _inspectorTarget(sourcePath),
          _inspectorNeutral(widget.copy.isChinese ? ' 到 ' : ' to '),
          _inspectorTarget(destinationPath),
        ];
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
        ];
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return null;
        }
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '编辑' : 'Edit'),
          _inspectorNeutral(' '),
          _inspectorTarget(path),
          if (editCount > 0) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? '，共 ' : ' with '),
            _inspectorScope(
              widget.copy.isChinese
                  ? '$editCount 处修改'
                  : '$editCount change${editCount == 1 ? '' : 's'}',
            ),
          ],
        ];
      case 'WebSearch':
        return _webSearchInspectorParts(arguments);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(widget.copy.isChinese ? '读取' : 'Read'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              widget.copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        if (summary == null || summary.todoCount <= 0) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(widget.copy.isChinese ? '清空' : 'Clear'),
            _inspectorNeutral(' '),
            _inspectorTarget(
              widget.copy.isChinese ? '当前待办列表' : 'current todo list',
            ),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '更新' : 'Update'),
          _inspectorNeutral(' '),
          _inspectorScope(
            widget.copy.isChinese
                ? '${summary.todoCount} 条待办'
                : '${summary.todoCount} todo${summary.todoCount == 1 ? '' : 's'}',
          ),
          if (summary.activeTodoContent !=
              null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? '，当前进行中：' : ', active: '),
            _inspectorTarget(summary.activeTodoContent!),
          ],
        ];
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String actor = _subagentTypeDisplay(
          _argumentString(arguments, 'subagent_type'),
        );
        if (description == null) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(widget.copy.isChinese ? '委派' : 'Delegate'),
            _inspectorNeutral(widget.copy.isChinese ? '给 ' : ' to '),
            _inspectorScope(actor),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '委派' : 'Delegate'),
          _inspectorNeutral(widget.copy.isChinese ? '给 ' : ' to '),
          _inspectorScope(actor),
          _inspectorNeutral(widget.copy.isChinese ? '：' : ': '),
          _inspectorTarget(description),
        ];
      default:
        return null;
    }
  }

  ChatRunTraceInspectorTextPart _inspectorNeutral(String text) =>
      ChatRunTraceInspectorTextPart(text: text);

  ChatRunTraceInspectorTextPart _inspectorAction(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.action,
      );

  ChatRunTraceInspectorTextPart _inspectorTarget(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.target,
      );

  ChatRunTraceInspectorTextPart _inspectorScope(String text) =>
      ChatRunTraceInspectorTextPart(
        text: text,
        semantic: ChatRunTraceInspectorTextSemantic.scope,
      );

  String _joinInspectorPartText(List<ChatRunTraceInspectorTextPart> parts) =>
      parts.map((part) => part.text).join();

  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolActionSummary(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    final String? reason = _nonEmpty(event.toolReason);
    final String? detail = _toolCallDetailBody(
      toolName: resolvedToolName,
      argumentsJson: event.argumentsJson,
    );
    return _joinTraceSections(<String?>[summary, reason, detail]);
  }

  String _buildToolResultPreviewBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
    required bool waitingApproval,
    required String? runErrorMessage,
  }) {
    final String resolvedToolName =
        _canonicalToolName(_nonEmpty(event.toolName)) ??
        widget.copy.chatRunWorkingLabel;
    final String summary = _toolResultActionSummary(
      toolName: resolvedToolName,
      event: event,
      pairedToolCall: pairedToolCall,
    );
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: resolvedToolName,
      event: event,
    );
    final String? message =
        (waitingApproval
            ? _nonEmpty(runErrorMessage)
            : _nonEmpty(event.errorMessage)) ??
        _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      resultSummary,
      message ?? widget.copy.chatRunToolFollowUp(resolvedToolName),
    ]);
  }

  String _buildCompactTraceBody({
    required List<ChatRunTraceHistoryEntry> history,
    required String fallbackBody,
    String? preferredBody,
  }) {
    final List<ChatRunTraceHistoryEntry> entries = history
        .where(_shouldIncludeCompactHistoryEntry)
        .toList(growable: false);
    if (entries.isEmpty) {
      return fallbackBody;
    }
    final String? preferred = (() {
      final String trimmed = preferredBody?.trim() ?? '';
      return trimmed.isEmpty ? null : trimmed;
    })();
    final int endExclusive = preferred == null
        ? entries.length
        : entries.lastIndexWhere(
                (entry) => _historyCompactBody(entry) == preferred,
              ) +
              1;
    final int boundedEndExclusive = endExclusive > 0
        ? endExclusive
        : entries.length;
    final int startIndex = boundedEndExclusive > 3
        ? boundedEndExclusive - 3
        : 0;
    final String compactBody = entries
        .sublist(startIndex, boundedEndExclusive)
        .map((entry) => _historyCompactBody(entry))
        .where((body) => body.isNotEmpty)
        .join('\n\n');
    return compactBody.trim().isNotEmpty ? compactBody.trim() : fallbackBody;
  }

  bool _shouldIncludeCompactHistoryEntry(ChatRunTraceHistoryEntry entry) {
    final String body = _historyCompactBody(entry);
    if (body.isEmpty) {
      return false;
    }
    return !_thinkingPlaceholders.contains(body);
  }

  String _historyCompactBody(ChatRunTraceHistoryEntry entry) =>
      (entry.compactBody?.trim().isNotEmpty == true
              ? entry.compactBody!
              : entry.body)
          .trim();

  String _assistantPhaseEntryLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? stage = _nonEmpty(event.stage);
    if (stage != null) {
      return stage;
    }
    return widget.copy.chatRunWorkingLabel;
  }

  String _projectedAssistantPhaseMessageText(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final String? stage = _nonEmpty(event.stage);
    final String body =
        _nonEmpty(event.text) ?? widget.copy.chatRunThinkingActive;
    if (stage == null) {
      return body;
    }
    return '$stage\n\n$body';
  }

  String _assistantPhaseTag(OpenCrayChatRuntimeEventSnapshot event) {
    final String phase = event.phase?.trim().toLowerCase() ?? '';
    return phase.isEmpty ? 'commentary' : phase;
  }

  String _assistantPhaseMessageId(OpenCrayChatRuntimeEventSnapshot event) {
    final String runId = event.runId.trim();
    final String stage = event.stage?.trim().isNotEmpty == true
        ? event.stage!.trim()
        : '-';
    final int textHash = javaStringHashCode(event.text?.trim() ?? '');
    final int turn = event.turn ?? -1;
    return 'runtime-assistant-${_assistantPhaseTag(event)}-$runId-$turn-$stage-${event.emittedAtEpochMs}-$textHash';
  }

  String _buildSupplementPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSupplementHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    final String? checkpoint = _supplementCheckpointSummary(event);
    return _joinTraceSections(<String?>[
      text ?? checkpoint,
      if (text != null) checkpoint,
    ]);
  }

  String _buildSubagentPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentSummarySection(event),
      _subagentMailboxSection(event),
      _subagentContextSection(event),
    ]);
  }

  String _buildSubagentHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentContextSection(event),
      _subagentMailboxSection(event),
      _subagentSummarySection(event),
    ]);
  }

  String _subagentTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? type = _nonEmpty(event.subagentType);
    if (type != null) {
      return _subagentTypeDisplay(type);
    }
    return _nonEmpty(event.label) ??
        (widget.copy.isChinese ? '子代理' : 'Subagent');
  }

  String _supplementTraceLabel() =>
      widget.copy.isChinese ? '补充输入' : 'Follow-up';

  String? _supplementCheckpointSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.checkpoint)?.toLowerCase()) {
      'turn_start' =>
        widget.copy.isChinese ? '在轮次开始时应用' : 'Applied at turn start',
      'post_tool_pre_model' =>
        widget.copy.isChinese ? '在工具结果之后应用' : 'Applied after tool result',
      null => null,
      final String checkpoint =>
        widget.copy.isChinese
            ? '应用检查点: ${checkpoint.replaceAll('_', ' ')}'
            : 'Applied at ${checkpoint.replaceAll('_', ' ')}',
    };
  }

  String _subagentPhaseSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String actor = _subagentTraceLabel(event);
    final String? description = _nonEmpty(event.label);
    final String suffix = description == null || description == actor
        ? ''
        : widget.copy.isChinese
        ? '：$description'
        : ': $description';
    final String? executionStateSummary = _subagentPhaseStateOverrideSummary(
      actor: actor,
      executionState: _subagentExecutionState(event),
    );
    if (executionStateSummary != null) {
      return '$executionStateSummary$suffix';
    }
    switch (_nonEmpty(event.phase)?.toLowerCase()) {
      case 'started':
        return widget.copy.isChinese
            ? '$actor 已启动$suffix'
            : '$actor started$suffix';
      case 'resumed':
        return widget.copy.isChinese
            ? '$actor 已继续$suffix'
            : '$actor resumed$suffix';
      case 'completed':
        return widget.copy.isChinese
            ? '$actor 已完成$suffix'
            : '$actor completed$suffix';
      case 'failed':
        return widget.copy.isChinese
            ? '$actor 失败$suffix'
            : '$actor failed$suffix';
      case 'cancelled':
        return widget.copy.isChinese
            ? '$actor 已取消$suffix'
            : '$actor cancelled$suffix';
      default:
        return widget.copy.isChinese
            ? '$actor 已更新$suffix'
            : '$actor updated$suffix';
    }
  }

  String? _subagentContextSection(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> lines = <String>[
      if (_nonEmpty(event.contextMode) != null)
        '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(event.contextMode!)}',
      if (event.depth != null)
        '${_traceSectionLabel(english: 'Depth', chinese: '深度')}: ${event.depth}',
      if (_subagentContinuationSummary(event) != null)
        '${_traceSectionLabel(english: 'Continuation', chinese: '继续方式')}: ${_subagentContinuationSummary(event)!}',
    ];
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _subagentSummarySection(OpenCrayChatRuntimeEventSnapshot event) {
    final String? summary = _nonEmpty(event.text);
    if (summary == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Summary', chinese: '摘要');
    return summary.contains('\n') ? '$label:\n$summary' : '$label: $summary';
  }

  String? _subagentMailboxSection(OpenCrayChatRuntimeEventSnapshot event) {
    final int? total = _resultMetadataInt(event, 'mailboxMessageCount');
    final int? pending = _resultMetadataInt(
      event,
      'mailboxPendingMessageCount',
    );
    final String? lastDelivered = _resultMetadataValue(
      event,
      'mailboxLastDeliveredMessageId',
    );
    if ((total ?? 0) <= 0 && (pending ?? 0) <= 0 && lastDelivered == null) {
      return null;
    }
    final String label = _traceSectionLabel(english: 'Mailbox', chinese: '邮箱');
    final List<String> lines = <String>[
      if (total != null || pending != null)
        widget.copy.isChinese
            ? '$label: ${pending ?? 0} 待投递 / ${total ?? 0} 总计'
            : '$label: ${pending ?? 0} pending / ${total ?? 0} total',
      if (lastDelivered != null)
        widget.copy.isChinese
            ? '最近已投递: $lastDelivered'
            : 'Last delivered: $lastDelivered',
    ];
    return lines.join('\n');
  }

  String _buildGroupedToolResultBody({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    final String? errorMessage = _nonEmpty(event.errorMessage);
    final String? content =
        _nonEmpty(event.content) ?? _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      resultSummary,
      if (errorMessage != null && errorMessage != resultSummary) errorMessage,
      if (content != null &&
          content != resultSummary &&
          content != errorMessage)
        content,
      if (resultSummary == null && errorMessage == null && content == null)
        _toolResultFallbackSummary(
          toolName: toolName,
          event: event,
          pairedToolCall: pairedToolCall,
        ),
    ]);
  }

  String? _toolInspectorCallDetailBody({
    required String toolName,
    required String? argumentsJson,
    required OpenCrayChatRuntimeEventSnapshot? toolResultEvent,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments =
        _decodeJsonObject(argumentsJson) ??
        (toolResultEvent == null
            ? null
            : _toolResultArgumentsFallback(
                toolName: toolName,
                event: toolResultEvent,
              ));
    if (arguments == null || arguments.isEmpty) {
      return null;
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return null;
    }
  }

  String? _toolResultFallbackSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String previewBody = _buildToolResultPreviewBody(
      event: event,
      pairedToolCall: pairedToolCall,
      waitingApproval: false,
      runErrorMessage: null,
    ).trim();
    if (previewBody.isEmpty) {
      return null;
    }
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    if (resultSummary != null && previewBody == resultSummary) {
      return null;
    }
    return previewBody;
  }

  String _indentGroupedToolBlock(String body, {required bool connector}) {
    final List<String> lines = body
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    return lines
        .asMap()
        .entries
        .map((entry) {
          final String prefix = entry.key == 0
              ? (connector ? '  └ ' : '    ')
              : '    ';
          return '$prefix${entry.value}';
        })
        .join('\n');
  }

  String _buildApprovalPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _buildApprovalHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _approvalEventBody(event);
  }

  String _approvalEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String? text = _nonEmpty(event.text);
    if (text != null) {
      return text;
    }
    switch (_nonEmpty(event.status)?.toLowerCase()) {
      case 'approved':
        return widget.copy.isChinese
            ? '审批已通过，继续执行。'
            : 'Approval granted. The run is resuming.';
      case 'rejected':
        return widget.copy.isChinese
            ? '审批已拒绝，等待下一步指示。'
            : 'Approval rejected. Waiting for the next instruction.';
      default:
        return widget.copy.chatRunWaitingApprovalLabel;
    }
  }

  String _approvalTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.kind == 'approval_wait') {
      return widget.copy.chatRunWaitingApprovalLabel;
    }
    if (_nonEmpty(event.status)?.toLowerCase() == 'rejected') {
      return widget.copy.chatRunAwaitingDirectionLabel;
    }
    return _canonicalToolName(_nonEmpty(event.toolName)) ??
        widget.copy.chatRunWorkingLabel;
  }

  String _buildCancellationPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _buildCancellationHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _cancellationEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _nonEmpty(event.text) ??
        (widget.copy.isChinese ? '本次运行已中断。' : 'Run interrupted.');
  }

  String _cancellationTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    return widget.copy.chatRunAwaitingDirectionLabel;
  }

  String _buildMemoryRetrievalPreviewBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: false),
    ]);
  }

  String _buildMemoryRetrievalHistoryBody(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    return _joinTraceSections(<String?>[
      _memoryRetrievalSummary(event),
      _memoryRetrievalResultBody(event, includeQueryTerms: true),
    ]);
  }

  String _buildMemoryWritePreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: false),
    ]);
  }

  String _buildMemoryWriteHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _memoryWriteSummary(event),
      _memoryWriteResultBody(event, includeKinds: true),
    ]);
  }

  String _memoryRetrievalSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? query = _nonEmpty(event.query);
        if (query != null) {
          return widget.copy.isChinese
              ? '检索记忆：“$query”'
              : 'Search memory for "$query"';
        }
        return widget.copy.isChinese ? '检索记忆' : 'Search memory';
      case 'get':
        final String? path = _nonEmpty(event.path);
        final String range = _memoryGetRangeSummary(event);
        if (path != null) {
          return widget.copy.isChinese
              ? '读取记忆 $path${range.isNotEmpty ? '，$range' : ''}'
              : 'Read memory $path${range.isNotEmpty ? ' $range' : ''}';
        }
        return widget.copy.isChinese ? '读取记忆片段' : 'Read memory snippet';
      default:
        return widget.copy.isChinese ? '访问记忆' : 'Access memory';
    }
  }

  String _memoryMaintenanceLabel() => widget.copy.isChinese ? '记忆' : 'Memory';

  String _memoryWriteSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final List<String> parts = <String?>[
      _memoryWriteCountLabel(
        count: event.writtenRecordIds.length,
        singular: 'wrote',
        plural: 'wrote',
        chinese: '写入 ${event.writtenRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.resolvedRecordIds.length,
        singular: 'resolved',
        plural: 'resolved',
        chinese: '解决 ${event.resolvedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.suppressedRecordIds.length,
        singular: 'suppressed',
        plural: 'suppressed',
        chinese: '抑制 ${event.suppressedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.reaffirmedRecordIds.length,
        singular: 'reaffirmed',
        plural: 'reaffirmed',
        chinese: '续期 ${event.reaffirmedRecordIds.length} 条',
      ),
      _memoryWriteCountLabel(
        count: event.expiredRecordIds.length,
        singular: 'expired',
        plural: 'expired',
        chinese: '过期 ${event.expiredRecordIds.length} 条',
      ),
    ].whereType<String>().toList(growable: false);
    if (parts.isEmpty) {
      return widget.copy.isChinese
          ? '本轮没有记忆变更。'
          : 'No memory changes recorded for this turn.';
    }
    if (widget.copy.isChinese) {
      return '记忆维护：${parts.join('，')}';
    }
    return 'Memory maintenance: ${parts.join(', ')}';
  }

  String _memoryWriteResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeKinds,
  }) {
    return _joinTraceSections(<String?>[
      _memoryWriteListSection(
        englishLabel: 'Written',
        chineseLabel: '写入',
        values: event.writtenRecordIds,
      ),
      includeKinds
          ? _memoryWriteListSection(
              englishLabel: 'Kinds',
              chineseLabel: '类型',
              values: event.writtenKinds,
            )
          : null,
      _memoryWriteListSection(
        englishLabel: 'Resolved',
        chineseLabel: '解决',
        values: event.resolvedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Suppressed',
        chineseLabel: '抑制',
        values: event.suppressedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Reaffirmed',
        chineseLabel: '续期',
        values: event.reaffirmedRecordIds,
      ),
      _memoryWriteListSection(
        englishLabel: 'Expired',
        chineseLabel: '过期',
        values: event.expiredRecordIds,
      ),
    ]);
  }

  String? _memoryWriteCountLabel({
    required int count,
    required String singular,
    required String plural,
    required String chinese,
  }) {
    if (count <= 0) {
      return null;
    }
    if (widget.copy.isChinese) {
      return chinese;
    }
    final String noun = count == 1 ? 'record' : 'records';
    final String verb = count == 1 ? singular : plural;
    return '$verb $count $noun';
  }

  String? _memoryWriteListSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = widget.copy.isChinese ? chineseLabel : englishLabel;
    return '$label: ${values.join(', ')}';
  }

  String _memoryRetrievalResultBody(
    OpenCrayChatRuntimeEventSnapshot event, {
    required bool includeQueryTerms,
  }) {
    final String operation = event.operation?.trim().toLowerCase() ?? '';
    switch (operation) {
      case 'search':
        final String? resultSummary = _memorySearchResultSummary(event);
        final String? matchLocations = _memorySearchMatchLocations(event);
        final String? queryTerms =
            includeQueryTerms && event.queryTerms.isNotEmpty
            ? (widget.copy.isChinese
                  ? '关键词：${event.queryTerms.join(', ')}'
                  : 'Query terms: ${event.queryTerms.join(', ')}')
            : null;
        return _joinTraceSections(<String?>[
          resultSummary,
          matchLocations,
          queryTerms,
        ]);
      case 'get':
        return _joinTraceSections(<String?>[
          _memoryGetResultSummary(event),
          includeQueryTerms ? _memoryGetLocationSummary(event) : null,
        ]);
      default:
        return widget.copy.chatRunThinkingActive;
    }
  }

  String? _memorySearchResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? resultCount = event.resultCount;
    final int? corpusFileCount = event.corpusFileCount;
    if (resultCount == null && corpusFileCount == null) {
      return null;
    }
    if (widget.copy.isChinese) {
      final String resultPart = resultCount == null ? '' : '命中 $resultCount 条';
      final String corpusPart = corpusFileCount == null
          ? ''
          : '覆盖 $corpusFileCount 个记忆文件';
      return <String>[
        resultPart,
        corpusPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String resultPart = resultCount == null
        ? ''
        : resultCount == 1
        ? '1 match'
        : '$resultCount matches';
    final String corpusPart = corpusFileCount == null
        ? ''
        : corpusFileCount == 1
        ? 'across 1 projected file'
        : 'across $corpusFileCount projected files';
    return <String>[
      resultPart,
      corpusPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memorySearchMatchLocations(OpenCrayChatRuntimeEventSnapshot event) {
    if (event.paths.isEmpty && event.lineRanges.isEmpty) {
      return null;
    }
    final int count = event.paths.length > event.lineRanges.length
        ? event.paths.length
        : event.lineRanges.length;
    final List<String> entries = <String>[];
    for (int index = 0; index < count; index += 1) {
      final String? path = index < event.paths.length
          ? _nonEmpty(event.paths[index])
          : null;
      final String? lineRange = index < event.lineRanges.length
          ? _nonEmpty(event.lineRanges[index])
          : null;
      final String entry = switch ((path, lineRange)) {
        (final String p?, final String r?) => '$p#$r',
        (final String p?, null) => p,
        (null, final String r?) => r,
        _ => '',
      };
      if (entry.isNotEmpty) {
        entries.add(entry);
      }
    }
    if (entries.isEmpty) {
      return null;
    }
    return widget.copy.isChinese
        ? '命中位置：\n${entries.join('\n')}'
        : 'Matches:\n${entries.join('\n')}';
  }

  String _memoryGetRangeSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? fromLine = event.fromLine;
    final int? returnedLineCount = event.returnedLineCount;
    if (fromLine == null && returnedLineCount == null) {
      return '';
    }
    if (returnedLineCount == null || returnedLineCount <= 0) {
      return widget.copy.isChinese ? '从第 $fromLine 行开始' : 'from line $fromLine';
    }
    final int endLine = fromLine == null
        ? returnedLineCount
        : fromLine + returnedLineCount - 1;
    if (widget.copy.isChinese) {
      return fromLine == null
          ? '共 $returnedLineCount 行'
          : '第 $fromLine-$endLine 行';
    }
    return fromLine == null
        ? '$returnedLineCount lines'
        : 'lines $fromLine-$endLine';
  }

  String? _memoryGetResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final int? returnedLineCount = event.returnedLineCount;
    final int? totalLineCount = event.totalLineCount;
    if (returnedLineCount == null && totalLineCount == null) {
      return null;
    }
    if (widget.copy.isChinese) {
      final String returnedPart = returnedLineCount == null
          ? ''
          : '返回 $returnedLineCount 行';
      final String totalPart = totalLineCount == null
          ? ''
          : '文件总计 $totalLineCount 行';
      return <String>[
        returnedPart,
        totalPart,
      ].where((part) => part.isNotEmpty).join('，');
    }
    final String returnedPart = returnedLineCount == null
        ? ''
        : returnedLineCount == 1
        ? 'Returned 1 line'
        : 'Returned $returnedLineCount lines';
    final String totalPart = totalLineCount == null
        ? ''
        : totalLineCount == 1
        ? 'from a 1-line file'
        : 'from a $totalLineCount-line file';
    return <String>[
      returnedPart,
      totalPart,
    ].where((part) => part.isNotEmpty).join(' ');
  }

  String? _memoryGetLocationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String? path = _nonEmpty(event.path);
    final String range = _memoryGetRangeSummary(event);
    if (path == null) {
      return null;
    }
    if (range.isEmpty) {
      return path;
    }
    return widget.copy.isChinese ? '$path，$range' : '$path $range';
  }

  String _toolActionSummary({
    required String toolName,
    required String? argumentsJson,
  }) => _toolActionSummaryFromArguments(
    toolName: toolName,
    arguments: _decodeJsonObject(argumentsJson),
  );

  String _toolActionSummaryFromArguments({
    required String toolName,
    required Map<String, dynamic>? arguments,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final String fallback = widget.copy.chatRunCallingTool(canonicalToolName);
    switch (canonicalToolName) {
      case 'Read':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        if (path == null) {
          return fallback;
        }
        final int? offset = _argumentInt(arguments, 'offset');
        final int? limit = _argumentInt(arguments, 'limit');
        final String range = _readRangeSummary(offset: offset, limit: limit);
        return widget.copy.isChinese
            ? '读取 $path${range.isNotEmpty ? '，$range' : ''}'
            : 'Read $path${range.isNotEmpty ? ' $range' : ''}';
      case 'Grep':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        final String? glob = _argumentString(arguments, 'glob');
        final String globSuffix = glob == null
            ? ''
            : widget.copy.isChinese
            ? '，glob: $glob'
            : ' (glob: $glob)';
        return widget.copy.isChinese
            ? '在 $path 中搜索 "$pattern"$globSuffix'
            : 'Search "$pattern" in $path$globSuffix';
      case 'Glob':
        final String? pattern = _argumentString(arguments, 'pattern');
        if (pattern == null) {
          return fallback;
        }
        final String path = _argumentString(arguments, 'path') ?? '.';
        return widget.copy.isChinese
            ? '在 $path 中匹配 $pattern'
            : 'Match $pattern in $path';
      case 'LS':
        final String path =
            _argumentString(arguments, 'path') ??
            _argumentString(arguments, 'file_path') ??
            '.';
        return widget.copy.isChinese ? '列出 $path' : 'List $path';
      case 'Write':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : widget.copy.isChinese
            ? '写入 $path'
            : 'Write $path';
      case 'ImportFile':
        final String? sourcePath = _argumentString(arguments, 'source_path');
        final String? destinationPath = _argumentString(
          arguments,
          'destination_path',
        );
        if (sourcePath == null || destinationPath == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Import $sourcePath to $destinationPath';
      case 'Edit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        return path == null
            ? fallback
            : widget.copy.isChinese
            ? '编辑 $path'
            : 'Edit $path';
      case 'MultiEdit':
        final String? path = _argumentString(
          arguments,
          'file_path',
          fallbackKey: 'path',
        );
        final int editCount = _argumentList(arguments, 'edits')?.length ?? 0;
        if (path == null) {
          return fallback;
        }
        if (editCount <= 0) {
          return widget.copy.isChinese ? '批量编辑 $path' : 'MultiEdit $path';
        }
        return widget.copy.isChinese
            ? '对 $path 应用 $editCount 处编辑'
            : 'Apply $editCount edit(s) to $path';
      case 'WebSearch':
        return _webSearchActionSummary(arguments, fallback: fallback);
      case 'TodoWrite':
        final _TodoTraceSummary? summary = _todoSummaryFromArguments(arguments);
        if (arguments?.containsKey('todos') != true) {
          return widget.copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
        }
        return _todoWriteActionSummary(summary: summary, mutated: true);
      case 'Bash':
      case 'command_exec':
        final String? command = _argumentString(arguments, 'command');
        if (command == null) {
          return fallback;
        }
        return widget.copy.isChinese ? '运行命令 $command' : 'Run command $command';
      case 'python_exec':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        if (scriptPath == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '运行 Python 脚本 $scriptPath'
            : 'Run Python script $scriptPath';
      case 'ProcessStart':
        final String? scriptPath = _argumentString(arguments, 'script_path');
        final String? command = _argumentString(arguments, 'command');
        if (scriptPath != null) {
          return widget.copy.isChinese
              ? '启动后台 Python 进程 $scriptPath'
              : 'Start background Python process $scriptPath';
        }
        if (command == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '启动后台进程 $command'
            : 'Start background process $command';
      case 'ProcessRead':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '读取进程 $processId 的输出'
            : 'Read output for process $processId';
      case 'ProcessWait':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '等待进程 $processId'
            : 'Wait for process $processId';
      case 'ProcessTerminate':
        final String? processId = _argumentString(arguments, 'process_id');
        if (processId == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '终止进程 $processId'
            : 'Terminate process $processId';
      case 'WebFetch':
        final String? url = _argumentString(arguments, 'url');
        if (url == null) {
          return fallback;
        }
        return widget.copy.isChinese ? '抓取网页 $url' : 'Fetch $url';
      case 'Task':
        final String? description = _argumentString(arguments, 'description');
        final String? subagentType = _argumentString(
          arguments,
          'subagent_type',
        );
        final String target = subagentType == null
            ? (widget.copy.isChinese ? '子代理' : 'subagent')
            : _subagentTypeDisplay(subagentType);
        if (description == null) {
          return widget.copy.isChinese ? '委派给 $target' : 'Delegate to $target';
        }
        return widget.copy.isChinese
            ? '委派给 $target：$description'
            : 'Delegate to $target: $description';
      default:
        return fallback;
    }
  }

  String _toolResultActionSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String? argumentsJson = _nonEmpty(pairedToolCall?.argumentsJson);
    if (argumentsJson != null) {
      return _toolActionSummary(
        toolName: toolName,
        argumentsJson: argumentsJson,
      );
    }
    if (toolName == 'TodoWrite') {
      return _todoWriteActionSummary(
        summary: _todoSummaryFromResultMetadata(event),
        mutated: _resultMetadataBool(event, 'mutated') == true,
      );
    }
    return _toolActionSummaryFromArguments(
      toolName: toolName,
      arguments: _toolResultArgumentsFallback(toolName: toolName, event: event),
    );
  }

  String? _toolCallDetailBody({
    required String toolName,
    required String? argumentsJson,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    if (arguments == null || arguments.isEmpty) {
      return _nonEmpty(argumentsJson);
    }
    switch (canonicalToolName) {
      case 'TodoWrite':
        return _todoWriteDetailBody(arguments);
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      case 'WebSearch':
        return _webSearchDetailBody(arguments);
      default:
        return _prettyJson(arguments);
    }
  }

  String _webSearchActionSummary(
    Map<String, dynamic>? arguments, {
    required String fallback,
  }) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final String domainSuffix = domains.isEmpty
        ? ''
        : widget.copy.isChinese
        ? '，范围 ${domains.join(', ')}'
        : ' within ${domains.join(', ')}';
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return fallback;
        }
        return widget.copy.isChinese
            ? '打开搜索结果页面 $url'
            : 'Open search result page $url';
      case 'find_in_page':
        if (url == null && text == null) {
          return fallback;
        }
        if (widget.copy.isChinese) {
          final String target = text == null ? '' : ' "$text"';
          final String location = url == null ? '' : ' 于 $url';
          return '在页面内搜索$target$location';
        }
        final String target = text == null ? '' : ' "$text"';
        final String location = url == null ? '' : ' in $url';
        return 'Find in page$target$location';
      default:
        if (query == null) {
          return widget.copy.isChinese
              ? '搜索网络$domainSuffix'
              : 'Search the web$domainSuffix';
        }
        return widget.copy.isChinese
            ? '搜索网络 "$query"$domainSuffix'
            : 'Search the web for "$query"$domainSuffix';
    }
  }

  List<ChatRunTraceInspectorTextPart>? _webSearchInspectorParts(
    Map<String, dynamic>? arguments,
  ) {
    final String operation = _webSearchOperation(arguments);
    final String? query = _webSearchPrimaryQuery(arguments);
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    switch (operation) {
      case 'open_page':
        if (url == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(
            widget.copy.isChinese ? '打开搜索结果页面' : 'Open search result page',
          ),
          _inspectorNeutral(' '),
          _inspectorTarget(url),
        ];
      case 'find_in_page':
        if (url == null && text == null) {
          return null;
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '页内搜索' : 'Find in page'),
          if (text != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(' '),
            _inspectorTarget('"$text"'),
          ],
          if (url != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? ' 于 ' : ' in '),
            _inspectorScope(url),
          ],
        ];
      default:
        if (query == null && domains.isEmpty) {
          return <ChatRunTraceInspectorTextPart>[
            _inspectorAction(widget.copy.isChinese ? '搜索网络' : 'Search the web'),
          ];
        }
        return <ChatRunTraceInspectorTextPart>[
          _inspectorAction(widget.copy.isChinese ? '搜索网络' : 'Search the web'),
          if (query != null) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? ' ' : ' for '),
            _inspectorTarget('"$query"'),
          ],
          if (domains.isNotEmpty) ...<ChatRunTraceInspectorTextPart>[
            _inspectorNeutral(widget.copy.isChinese ? '，范围 ' : ' within '),
            _inspectorScope(domains.join(', ')),
          ],
        ];
    }
  }

  String? _webSearchDetailBody(Map<String, dynamic> arguments) {
    final String? query = _argumentString(arguments, 'query');
    final List<String> queries = <String>{
      if (query != null) query,
      ..._argumentStringList(arguments, 'queries'),
    }.toList(growable: false);
    final List<String> domains = _argumentStringList(arguments, 'domains');
    final List<String> sourceUrls = _argumentStringList(
      arguments,
      'sourceUrls',
    );
    final String? url = _argumentString(arguments, 'url');
    final String? text = _webSearchFindText(arguments);
    final String detail = _joinTraceSections(<String?>[
      _labeledInlineSection(
        englishLabel: 'Queries',
        chineseLabel: '查询',
        values: queries,
      ),
      url == null
          ? null
          : '${_traceSectionLabel(english: 'URL', chinese: '链接')}: $url',
      text == null
          ? null
          : '${_traceSectionLabel(english: 'Text', chinese: '文本')}: $text',
      _labeledInlineSection(
        englishLabel: 'Domains',
        chineseLabel: '域名',
        values: domains,
      ),
      _labeledInlineSection(
        englishLabel: 'Sources',
        chineseLabel: '来源',
        values: sourceUrls,
      ),
    ]).trim();
    return detail.isEmpty ? null : detail;
  }

  String _webSearchOperation(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'operation')?.trim().toLowerCase() ?? '';

  String? _webSearchPrimaryQuery(Map<String, dynamic>? arguments) {
    final String? query = _argumentString(arguments, 'query');
    if (query != null) {
      return query;
    }
    final List<String> queries = _argumentStringList(arguments, 'queries');
    return queries.isEmpty ? null : queries.first;
  }

  String? _webSearchFindText(Map<String, dynamic>? arguments) =>
      _argumentString(arguments, 'text') ??
      _argumentString(arguments, 'pattern');

  String? _taskDetailBody(Map<String, dynamic> arguments) {
    final String? prompt = _argumentString(arguments, 'prompt');
    final String? contextMode = _argumentString(arguments, 'context_mode');
    final List<String> allowedTools = _argumentStringList(
      arguments,
      'allowed_tools',
    );
    return _joinTraceSections(<String?>[
      prompt == null
          ? null
          : '${_traceSectionLabel(english: 'Prompt', chinese: '提示')}: $prompt',
      contextMode == null
          ? null
          : '${_traceSectionLabel(english: 'Context', chinese: '上下文')}: ${_contextModeDisplay(contextMode)}',
      _labeledInlineSection(
        englishLabel: 'Allowed tools',
        chineseLabel: '允许工具',
        values: allowedTools,
      ),
    ]);
  }

  String? _todoWriteDetailBody(Map<String, dynamic> arguments) {
    if (!arguments.containsKey('todos')) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    if (todos == null || todos.isEmpty) {
      return null;
    }
    final List<String> lines = <String>[];
    for (final dynamic rawTodo in todos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? content = _argumentString(todo, 'content');
      if (content == null) {
        continue;
      }
      final String statusLabel = switch (_argumentString(
        todo,
        'status',
      )?.toLowerCase()) {
        'completed' ||
        'complete' ||
        'done' => widget.copy.isChinese ? '[已完成]' : '[completed]',
        'in_progress' ||
        'in-progress' ||
        'inprogress' => widget.copy.isChinese ? '[进行中]' : '[in_progress]',
        _ => widget.copy.isChinese ? '[待处理]' : '[pending]',
      };
      final String? activeForm =
          _argumentString(todo, 'activeForm') ??
          _argumentString(todo, 'active_form');
      lines.add(
        activeForm == null
            ? '$statusLabel $content'
            : widget.copy.isChinese
            ? '$statusLabel $content | 当前动作：$activeForm'
            : '$statusLabel $content | active: $activeForm',
      );
    }
    return lines.isEmpty ? null : lines.join('\n');
  }

  String? _editDetailBody(Map<String, dynamic> arguments) {
    final String? oldString = _argumentString(arguments, 'old_string');
    final String? newString = _argumentString(arguments, 'new_string');
    if (oldString == null || newString == null) {
      return _prettyJson(arguments);
    }
    return _diffBlock(oldString: oldString, newString: newString);
  }

  String? _multiEditDetailBody(Map<String, dynamic> arguments) {
    final List<dynamic>? edits = _argumentList(arguments, 'edits');
    if (edits == null || edits.isEmpty) {
      return _prettyJson(arguments);
    }
    final List<String> blocks = <String>[];
    for (int index = 0; index < edits.length; index += 1) {
      final dynamic rawEdit = edits[index];
      if (rawEdit is! Map) {
        continue;
      }
      final Map<String, dynamic> edit = Map<String, dynamic>.from(
        rawEdit.map((key, value) => MapEntry(key.toString(), value)),
      );
      final String? oldString = _argumentString(edit, 'old_string');
      final String? newString = _argumentString(edit, 'new_string');
      if (oldString == null || newString == null) {
        blocks.add(_prettyJson(edit));
        continue;
      }
      blocks.add(
        _joinTraceSections(<String>[
          widget.copy.isChinese ? '编辑 ${index + 1}' : 'Edit ${index + 1}',
          _diffBlock(oldString: oldString, newString: newString),
        ]),
      );
    }
    return blocks.isEmpty ? _prettyJson(arguments) : blocks.join('\n\n');
  }

  String? _writeDetailBody(Map<String, dynamic> arguments) {
    final String? content = _argumentString(arguments, 'content');
    if (content == null) {
      return _prettyJson(arguments);
    }
    return content;
  }

  String _diffBlock({required String oldString, required String newString}) {
    final List<String> removed = _diffLines(prefix: '-', text: oldString);
    final List<String> added = _diffLines(prefix: '+', text: newString);
    return <String>[...removed, ...added].join('\n');
  }

  List<String> _diffLines({required String prefix, required String text}) {
    final List<String> lines = text
        .replaceAll('\r\n', '\n')
        .replaceAll('\r', '\n')
        .split('\n');
    if (lines.isEmpty) {
      return <String>['$prefix '];
    }
    return lines.map((line) => '$prefix $line').toList(growable: false);
  }

  String _readRangeSummary({required int? offset, required int? limit}) {
    if (offset == null && limit == null) {
      return '';
    }
    if (widget.copy.isChinese) {
      if (offset != null && limit != null) {
        final int endLine = offset + limit - 1;
        return '第 $offset-$endLine 行';
      }
      if (offset != null) {
        return '从第 $offset 行开始';
      }
      return '前 $limit 行';
    }
    if (offset != null && limit != null) {
      final int endLine = offset + limit - 1;
      return 'lines $offset-$endLine';
    }
    if (offset != null) {
      return 'from line $offset';
    }
    return 'first $limit lines';
  }

  Map<String, dynamic>? _toolResultArgumentsFallback({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'Read':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{
          'file_path': filePath,
          if (_resultMetadataInt(event, 'offset') != null)
            'offset': _resultMetadataInt(event, 'offset'),
          if (_resultMetadataInt(event, 'limit') != null)
            'limit': _resultMetadataInt(event, 'limit'),
        };
      case 'LS':
        return <String, dynamic>{
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'Grep':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
          if (_resultMetadataValue(event, 'glob') != null)
            'glob': _resultMetadataValue(event, 'glob'),
        };
      case 'Glob':
        final String? pattern = _resultMetadataValue(event, 'pattern');
        if (pattern == null) {
          return null;
        }
        return <String, dynamic>{
          'pattern': pattern,
          if (_resultMetadataValue(event, 'path') != null)
            'path': _resultMetadataValue(event, 'path'),
        };
      case 'WebSearch':
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final List<String> sourceUrls = _csvValues(
          _resultMetadataValue(event, 'sourceUrls'),
        );
        if (operation == null &&
            query == null &&
            url == null &&
            text == null &&
            sourceUrls.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (operation != null) 'operation': operation,
          if (query != null) 'query': query,
          if (url != null) 'url': url,
          if (text != null) 'text': text,
          if (sourceUrls.isNotEmpty) 'sourceUrls': sourceUrls,
        };
      case 'Write':
      case 'Edit':
      case 'MultiEdit':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{'file_path': filePath};
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return <String, dynamic>{
          'source_path': sourcePath,
          'destination_path': destinationPath,
        };
      case 'Task':
        final String? description = _resultMetadataValue(
          event,
          'delegationDescription',
        );
        final String? prompt = _resultMetadataValue(
          event,
          'delegationPromptPreview',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        if (description == null &&
            prompt == null &&
            subagentType == null &&
            contextMode == null &&
            allowedTools.isEmpty) {
          return null;
        }
        return <String, dynamic>{
          if (description != null) 'description': description,
          if (prompt != null) 'prompt': prompt,
          if (subagentType != null) 'subagent_type': subagentType,
          if (contextMode != null) 'context_mode': contextMode,
          if (allowedTools.isNotEmpty) 'allowed_tools': allowedTools,
        };
      case 'Bash':
      case 'command_exec':
        final String? command =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (command == null) {
          return null;
        }
        return <String, dynamic>{'command': command};
      case 'python_exec':
        final String? scriptPath = _resultMetadataValue(event, 'scriptPath');
        if (scriptPath == null) {
          return null;
        }
        return <String, dynamic>{'script_path': scriptPath};
      case 'ProcessStart':
        final String? processScriptPath = _resultMetadataValue(
          event,
          'scriptPath',
        );
        final String? processCommand =
            _resultMetadataValue(event, 'commandSummary') ??
            _resultMetadataValue(event, 'command');
        if (processScriptPath == null && processCommand == null) {
          return null;
        }
        return <String, dynamic>{
          if (processScriptPath != null) 'script_path': processScriptPath,
          if (processCommand != null) 'command': processCommand,
        };
      case 'ProcessRead':
      case 'ProcessWait':
      case 'ProcessTerminate':
        final String? processId = _resultMetadataValue(event, 'processId');
        if (processId == null) {
          return null;
        }
        return <String, dynamic>{'process_id': processId};
      case 'WebFetch':
        final String? url =
            _resultMetadataValue(event, 'requestedUrl') ??
            _resultMetadataValue(event, 'finalUrl') ??
            _resultMetadataValue(event, 'url');
        if (url == null) {
          return null;
        }
        return <String, dynamic>{'url': url};
      default:
        return null;
    }
  }

  String? _toolResultMetadataSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    final String canonicalToolName = _canonicalToolName(toolName) ?? toolName;
    switch (canonicalToolName) {
      case 'LS':
        final int? entryCount = _resultMetadataInt(event, 'entryCount');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (entryCount == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          final String summary = path == null
              ? '列出了 $entryCount 项'
              : '在 $path 中列出了 $entryCount 项';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String summary = path == null
            ? 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'}'
            : 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'} in $path';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Read':
        final int? returnedLineCount = _resultMetadataInt(
          event,
          'returnedLineCount',
        );
        final int? totalLineCount = _resultMetadataInt(event, 'totalLineCount');
        final bool truncated = _resultMetadataTruncated(event);
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (returnedLineCount == null &&
            totalLineCount == null &&
            !truncated &&
            filePath == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (returnedLineCount != null) '返回 $returnedLineCount 行',
            if (totalLineCount != null) '文件总计 $totalLineCount 行',
            if (truncated) '结果已按读取预算截断',
          ];
          return parts.join('，');
        }
        final List<String> parts = <String>[
          if (returnedLineCount != null)
            returnedLineCount == 1
                ? 'Returned 1 line'
                : 'Returned $returnedLineCount lines',
          if (filePath != null) 'from $filePath',
          if (totalLineCount != null)
            totalLineCount == 1
                ? '(1-line file)'
                : '($totalLineCount-line file)',
          if (truncated) 'Output truncated to the read budget.',
        ];
        return parts.join(' ');
      case 'Grep':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          final String target = path ?? '.';
          if (pattern == null) {
            final String summary = '在 $target 中找到 $matchCount 处匹配';
            return truncated ? '$summary，结果已按结果上限截断' : summary;
          }
          final String summary = '在 $target 中为 "$pattern" 找到 $matchCount 处匹配';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        if (pattern == null) {
          final String summary = matchCount == 1
              ? 'Found 1 match in $target'
              : 'Found $matchCount matches in $target';
          return truncated
              ? '$summary. Output truncated at the tool result limit.'
              : summary;
        }
        final String summary = matchCount == 1
            ? 'Found 1 match for "$pattern" in $target'
            : 'Found $matchCount matches for "$pattern" in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'Glob':
        final int? matchCount = _resultMetadataInt(event, 'matchCount');
        final String? pattern = _resultMetadataValue(event, 'pattern');
        final String? path = _resultMetadataValue(event, 'path');
        final bool truncated = _resultMetadataTruncated(event);
        if (matchCount == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          final String target = path ?? '.';
          final String summary = pattern == null
              ? '在 $target 中匹配到 $matchCount 个路径'
              : '在 $target 中为 $pattern 匹配到 $matchCount 个路径';
          return truncated ? '$summary，结果已按结果上限截断' : summary;
        }
        final String target = path ?? '.';
        final String summary = pattern == null
            ? 'Matched $matchCount path(s) in $target'
            : 'Matched $matchCount path(s) for $pattern in $target';
        return truncated
            ? '$summary. Output truncated at the tool result limit.'
            : summary;
      case 'WebSearch':
        final int? sourceCount = _resultMetadataInt(event, 'sourceCount');
        final String? operation = _resultMetadataValue(
          event,
          'providerManagedOperation',
        )?.trim().toLowerCase();
        final String? status = _resultMetadataValue(
          event,
          'providerManagedStatus',
        );
        final String? query = _resultMetadataValue(event, 'query');
        final String? url = _resultMetadataValue(event, 'url');
        final String? text = _resultMetadataValue(event, 'text');
        final bool managed =
            _resultMetadataValue(event, 'providerManaged') == 'true';
        if (sourceCount == null &&
            operation == null &&
            status == null &&
            query == null &&
            url == null &&
            text == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          return <String>[
            if (managed) '原生搜索',
            switch (operation) {
              'open_page' => url == null ? '' : '打开页面 $url',
              'find_in_page' => <String>[
                if (text != null) '页内搜索 "$text"',
                if (url != null) url,
              ].where((part) => part.isNotEmpty).join('，'),
              _ => query == null ? '' : '搜索 "$query"',
            },
            if (sourceCount != null) '来源 $sourceCount 个',
            if (status != null) '状态 $status',
          ].where((part) => part.isNotEmpty).join('，');
        }
        return <String>[
          if (managed) 'Provider-managed search',
          switch (operation) {
            'open_page' => url == null ? '' : 'opened $url',
            'find_in_page' => <String>[
              if (text != null) 'find "$text"',
              if (url != null) 'in $url',
            ].where((part) => part.isNotEmpty).join(' '),
            _ => query == null ? '' : 'search "$query"',
          },
          if (sourceCount != null)
            sourceCount == 1 ? '1 source' : '$sourceCount sources',
          if (status != null) 'status $status',
        ].where((part) => part.isNotEmpty).join(' ');
      case 'Edit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          return filePath == null
              ? '应用了 $replacementCount 处替换'
              : '在 $filePath 中应用了 $replacementCount 处替换';
        }
        return filePath == null
            ? 'Applied $replacementCount replacement(s)'
            : 'Applied $replacementCount replacement(s) in $filePath';
      case 'MultiEdit':
        final int? replacementCount = _resultMetadataInt(
          event,
          'replacementCount',
        );
        final int? editCount = _resultMetadataInt(event, 'editCount');
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (replacementCount == null && editCount == null && filePath == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          final List<String> parts = <String>[
            if (filePath != null) filePath,
            if (replacementCount != null) '$replacementCount 处替换',
            if (editCount != null) '$editCount 个编辑块',
          ];
          return parts.isEmpty ? null : '应用了 ${parts.join('，')}';
        }
        final List<String> parts = <String>[
          if (replacementCount != null) '$replacementCount replacement(s)',
          if (editCount != null) 'across $editCount edit(s)',
          if (filePath != null) 'in $filePath',
        ];
        return parts.isEmpty ? null : 'Applied ${parts.join(' ')}';
      case 'ImportFile':
        final String? sourcePath = _resultMetadataValue(event, 'sourcePath');
        final String? destinationPath = _resultMetadataValue(
          event,
          'destinationPath',
        );
        if (sourcePath == null || destinationPath == null) {
          return null;
        }
        return widget.copy.isChinese
            ? '导入 $sourcePath 到 $destinationPath'
            : 'Imported $sourcePath to $destinationPath';
      case 'TodoWrite':
        return _todoWriteResultSummary(event);
      case 'Task':
        final String? executionState = _resultMetadataValue(
          event,
          'childExecutionState',
        );
        final String? status = _resultMetadataValue(
          event,
          'childExecutionStatus',
        );
        final String? subagentType =
            _resultMetadataValue(event, 'delegationSubagentType') ??
            _resultMetadataValue(event, 'subagentType');
        final String? contextMode =
            _resultMetadataValue(event, 'delegationContextMode') ??
            _resultMetadataValue(event, 'subagentContextMode');
        final int? turnCount = _resultMetadataInt(event, 'childTurnCount');
        final int? toolCallCount = _resultMetadataInt(
          event,
          'childToolCallCount',
        );
        final List<String> allowedTools = _csvValues(
          _resultMetadataValue(event, 'delegationAllowedTools'),
        );
        final String actor = _subagentTypeDisplay(subagentType);
        final String statusSummary =
            _subagentExecutionStateSummary(
              actor: actor,
              executionState: executionState,
            ) ??
            switch (status?.toLowerCase()) {
              'success' || 'completed' =>
                widget.copy.isChinese ? '$actor 已完成' : '$actor completed',
              'cancelled' =>
                widget.copy.isChinese ? '$actor 已取消' : '$actor cancelled',
              'approval_required' =>
                widget.copy.isChinese
                    ? '$actor 等待审批'
                    : '$actor waiting for approval',
              'high_risk_approval_required' =>
                widget.copy.isChinese
                    ? '$actor 等待高风险审批'
                    : '$actor waiting for high-risk approval',
              'failed' || 'denied' || 'timeout' =>
                widget.copy.isChinese ? '$actor 失败' : '$actor failed',
              _ =>
                widget.copy.isChinese
                    ? '$actor 已返回结果'
                    : '$actor returned a result',
            };
        final List<String> details = <String>[
          if (contextMode != null)
            widget.copy.isChinese
                ? '上下文 ${_contextModeDisplay(contextMode)}'
                : '${_contextModeDisplay(contextMode)} context',
          if (turnCount != null)
            widget.copy.isChinese
                ? '$turnCount 轮'
                : turnCount == 1
                ? '1 turn'
                : '$turnCount turns',
          if (toolCallCount != null)
            widget.copy.isChinese
                ? '$toolCallCount 次工具调用'
                : toolCallCount == 1
                ? '1 tool call'
                : '$toolCallCount tool calls',
        ];
        final String? allowedToolsSummary = _labeledInlineSection(
          englishLabel: 'Allowed tools',
          chineseLabel: '允许工具',
          values: allowedTools,
        );
        if (details.isEmpty) {
          return _joinTraceSections(<String?>[
            statusSummary,
            allowedToolsSummary,
          ]);
        }
        final String summary = widget.copy.isChinese
            ? '$statusSummary，${details.join('，')}'
            : '$statusSummary. ${details.join(', ')}.';
        return _joinTraceSections(<String?>[summary, allowedToolsSummary]);
      default:
        return null;
    }
  }

  String _todoWriteActionSummary({
    required _TodoTraceSummary? summary,
    required bool mutated,
  }) {
    if (!mutated) {
      return widget.copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
    }
    if (summary == null || summary.todoCount <= 0) {
      return widget.copy.isChinese ? '清空待办列表' : 'Clear the todo list';
    }
    final String? breakdown = _todoBreakdownSummary(summary);
    if (widget.copy.isChinese) {
      final String base = breakdown == null
          ? '更新 ${summary.todoCount} 条待办'
          : '更新 ${summary.todoCount} 条待办（$breakdown）';
      return summary.activeTodoContent == null
          ? base
          : '$base，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = breakdown == null
        ? 'Update ${summary.todoCount} todo(s)'
        : 'Update ${summary.todoCount} todo(s) ($breakdown)';
    return summary.activeTodoContent == null
        ? base
        : '$base, active: ${summary.activeTodoContent!}';
  }

  String? _todoWriteResultSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final _TodoTraceSummary? summary = _todoSummaryFromResultMetadata(event);
    if (summary == null) {
      return null;
    }
    final bool mutated = _resultMetadataBool(event, 'mutated') == true;
    final bool? planChanged = _resultMetadataBool(event, 'planChanged');
    if (!mutated) {
      if (summary.todoCount <= 0) {
        return widget.copy.isChinese
            ? '当前待办列表为空'
            : 'Current todo list is empty';
      }
      final String? breakdown = _todoBreakdownSummary(summary);
      if (widget.copy.isChinese) {
        final String base = breakdown == null
            ? '当前待办列表共 ${summary.todoCount} 项'
            : '当前待办列表共 ${summary.todoCount} 项，$breakdown';
        return summary.activeTodoContent == null
            ? base
            : '$base，当前进行中：${summary.activeTodoContent!}';
      }
      final String base = breakdown == null
          ? 'Current todo list has ${summary.todoCount} item(s)'
          : 'Current todo list has ${summary.todoCount} item(s): $breakdown';
      return summary.activeTodoContent == null
          ? base
          : '$base. Active: ${summary.activeTodoContent!}';
    }
    if (summary.todoCount <= 0) {
      if (widget.copy.isChinese) {
        return planChanged == false ? '待办列表未变化，当前为空' : '待办列表已清空';
      }
      return planChanged == false
          ? 'Plan unchanged. Todo list is empty.'
          : 'Cleared the todo list';
    }
    final int completedDeltaCount =
        _resultMetadataInt(event, 'completedTodoDeltaCount') ?? 0;
    final int addedTodoCount = _resultMetadataInt(event, 'addedTodoCount') ?? 0;
    final int removedTodoCount =
        _resultMetadataInt(event, 'removedTodoCount') ?? 0;
    final int statusChangedTodoCount =
        _resultMetadataInt(event, 'statusChangedTodoCount') ?? 0;
    final int extraStatusChangeCount = math.max(
      0,
      statusChangedTodoCount - completedDeltaCount,
    );
    final List<String> details = <String>[
      if (completedDeltaCount > 0)
        widget.copy.isChinese
            ? '完成 $completedDeltaCount 项'
            : 'completed $completedDeltaCount',
      if (addedTodoCount > 0)
        widget.copy.isChinese
            ? '新增 $addedTodoCount 项'
            : 'added $addedTodoCount',
      if (removedTodoCount > 0)
        widget.copy.isChinese
            ? '移除 $removedTodoCount 项'
            : 'removed $removedTodoCount',
      if (extraStatusChangeCount > 0)
        widget.copy.isChinese
            ? '更新 $extraStatusChangeCount 项状态'
            : 'updated $extraStatusChangeCount status${extraStatusChangeCount == 1 ? '' : 'es'}',
    ];
    if (details.isEmpty) {
      final String? breakdown = _todoBreakdownSummary(summary);
      if (breakdown != null) {
        details.add(breakdown);
      }
    }
    if (widget.copy.isChinese) {
      final String base = planChanged == false ? '待办计划未变化' : '待办计划已更新';
      final String detailText = details.isEmpty
          ? base
          : '$base：${details.join('，')}';
      return summary.activeTodoContent == null
          ? detailText
          : '$detailText，当前进行中：${summary.activeTodoContent!}';
    }
    final String base = planChanged == false
        ? 'Plan unchanged'
        : 'Plan updated';
    final String detailText = details.isEmpty
        ? base
        : '$base: ${details.join(', ')}';
    return summary.activeTodoContent == null
        ? detailText
        : '$detailText. Active now: ${summary.activeTodoContent!}';
  }

  String? _todoBreakdownSummary(_TodoTraceSummary summary) {
    if (summary.todoCount <= 0) {
      return null;
    }
    if (widget.copy.isChinese) {
      return '${summary.pendingCount} 待处理，${summary.inProgressCount} 进行中，${summary.completedCount} 已完成';
    }
    return '${summary.pendingCount} pending, ${summary.inProgressCount} in progress, ${summary.completedCount} completed';
  }

  _TodoTraceSummary? _todoSummaryFromArguments(
    Map<String, dynamic>? arguments,
  ) {
    if (arguments == null || arguments.containsKey('todos') != true) {
      return null;
    }
    final List<dynamic>? todos = _argumentList(arguments, 'todos');
    return _todoSummaryFromTodoList(todos);
  }

  _TodoTraceSummary? _todoSummaryFromResultMetadata(
    OpenCrayChatRuntimeEventSnapshot event,
  ) {
    final int? todoCount = _resultMetadataInt(event, 'todoCount');
    if (todoCount == null) {
      return null;
    }
    return _TodoTraceSummary(
      todoCount: todoCount,
      pendingCount: _resultMetadataInt(event, 'pendingTodoCount') ?? 0,
      inProgressCount: _resultMetadataInt(event, 'inProgressTodoCount') ?? 0,
      completedCount: _resultMetadataInt(event, 'completedTodoCount') ?? 0,
      activeTodoContent: _resultMetadataValue(event, 'activeTodoContent'),
    );
  }

  _TodoTraceSummary _todoSummaryFromTodoList(List<dynamic>? todos) {
    int pendingCount = 0;
    int inProgressCount = 0;
    int completedCount = 0;
    String? activeTodoContent;
    final List<dynamic> normalizedTodos = todos ?? const <dynamic>[];
    for (final dynamic rawTodo in normalizedTodos) {
      if (rawTodo is! Map) {
        continue;
      }
      final Map<String, dynamic> todo = Map<String, dynamic>.from(
        rawTodo.map((key, value) => MapEntry(key.toString(), value)),
      );
      switch ((_argumentString(todo, 'status') ?? '').trim().toLowerCase()) {
        case 'completed':
        case 'complete':
        case 'done':
          completedCount += 1;
          break;
        case 'in_progress':
        case 'in-progress':
        case 'inprogress':
          inProgressCount += 1;
          activeTodoContent ??= _argumentString(todo, 'content');
          break;
        default:
          pendingCount += 1;
          break;
      }
    }
    return _TodoTraceSummary(
      todoCount: normalizedTodos.length,
      pendingCount: pendingCount,
      inProgressCount: inProgressCount,
      completedCount: completedCount,
      activeTodoContent: activeTodoContent,
    );
  }

  Map<String, dynamic>? _decodeJsonObject(String? rawJson) {
    final String? normalized = _nonEmpty(rawJson);
    if (normalized == null) {
      return null;
    }
    try {
      final dynamic decoded = jsonDecode(normalized);
      if (decoded is! Map) {
        return null;
      }
      return Map<String, dynamic>.from(
        decoded.map((key, value) => MapEntry(key.toString(), value)),
      );
    } catch (_) {
      return null;
    }
  }

  String? _subagentExecutionState(OpenCrayChatRuntimeEventSnapshot event) =>
      _nonEmpty(event.executionState) ?? _nonEmpty(event.status);

  String? _subagentExecutionStateSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        widget.copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        widget.copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        widget.copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        widget.copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      'running' => widget.copy.isChinese ? '$actor 正在运行' : '$actor running',
      'completed' => widget.copy.isChinese ? '$actor 已完成' : '$actor completed',
      'failed' => widget.copy.isChinese ? '$actor 失败' : '$actor failed',
      'cancelled' => widget.copy.isChinese ? '$actor 已取消' : '$actor cancelled',
      _ => null,
    };
  }

  String? _subagentPhaseStateOverrideSummary({
    required String actor,
    required String? executionState,
  }) {
    return switch (executionState?.toLowerCase()) {
      'background_queued' =>
        widget.copy.isChinese ? '$actor 已在后台排队' : '$actor queued in background',
      'background_running' =>
        widget.copy.isChinese
            ? '$actor 正在后台运行'
            : '$actor running in background',
      'waiting_approval' =>
        widget.copy.isChinese ? '$actor 等待审批' : '$actor waiting for approval',
      'waiting_high_risk_approval' =>
        widget.copy.isChinese
            ? '$actor 等待高风险审批'
            : '$actor waiting for high-risk approval',
      _ => null,
    };
  }

  String? _subagentContinuationSummary(OpenCrayChatRuntimeEventSnapshot event) {
    return switch (_nonEmpty(event.continuationKind)?.toLowerCase()) {
      'background_resume' =>
        widget.copy.isChinese ? '后台继续' : 'Resumes in background',
      'prompt_resume' =>
        widget.copy.isChinese ? '审批后继续' : 'Resumes after approval',
      'none' || null => null,
      final String rawValue => rawValue.replaceAll('_', ' '),
    };
  }

  String? _argumentString(
    Map<String, dynamic>? arguments,
    String key, {
    String? fallbackKey,
  }) {
    if (arguments == null) {
      return null;
    }
    final dynamic value =
        arguments[key] ?? (fallbackKey == null ? null : arguments[fallbackKey]);
    final String normalized = switch (value) {
      null => '',
      String stringValue => stringValue.trim(),
      _ => value.toString().trim(),
    };
    return normalized.isEmpty ? null : normalized;
  }

  int? _argumentInt(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return switch (value) {
      int intValue => intValue,
      num numValue => numValue.toInt(),
      String stringValue => int.tryParse(stringValue.trim()),
      _ => null,
    };
  }

  List<dynamic>? _argumentList(Map<String, dynamic>? arguments, String key) {
    if (arguments == null) {
      return null;
    }
    final dynamic value = arguments[key];
    return value is List<dynamic> ? value : null;
  }

  List<String> _argumentStringList(
    Map<String, dynamic>? arguments,
    String key,
  ) {
    final List<dynamic>? values = _argumentList(arguments, key);
    if (values == null) {
      final String? singleValue = _argumentString(arguments, key);
      return singleValue == null ? const <String>[] : _csvValues(singleValue);
    }
    return values
        .map((value) => value.toString().trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
  }

  List<String> _csvValues(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return const <String>[];
    }
    return normalized
        .split(',')
        .map((entry) => entry.trim())
        .where((entry) => entry.isNotEmpty)
        .toList(growable: false);
  }

  String _prettyJson(Map<String, dynamic> value) =>
      const JsonEncoder.withIndent('  ').convert(value);

  String _joinTraceSections(List<String?> sections) => sections
      .map((section) => section?.trim() ?? '')
      .where((section) => section.isNotEmpty)
      .join('\n\n');

  String _traceSectionLabel({
    required String english,
    required String chinese,
  }) => widget.copy.isChinese ? chinese : english;

  String? _labeledInlineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    if (values.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label: ${values.join(', ')}';
  }

  String? _labeledMultilineSection({
    required String englishLabel,
    required String chineseLabel,
    required List<String> values,
  }) {
    final List<String> normalized = values
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
    if (normalized.isEmpty) {
      return null;
    }
    final String label = _traceSectionLabel(
      english: englishLabel,
      chinese: chineseLabel,
    );
    return '$label:\n${normalized.join('\n')}';
  }

  String? _nonEmpty(String? value) {
    final String normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  String _subagentTypeDisplay(String? value) {
    final String? normalized = _nonEmpty(value);
    if (normalized == null) {
      return widget.copy.isChinese ? '子代理' : 'Subagent';
    }
    return _humanizeIdentifier(normalized, titleCase: true);
  }

  String _contextModeDisplay(String value) =>
      _humanizeIdentifier(value, titleCase: false);

  String _humanizeIdentifier(String value, {required bool titleCase}) {
    final List<String> parts = value
        .trim()
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .split(RegExp(r'\s+'))
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    if (parts.isEmpty) {
      return value.trim();
    }
    if (!titleCase) {
      return parts.join(' ');
    }
    return parts
        .map((part) => part[0].toUpperCase() + part.substring(1).toLowerCase())
        .join(' ');
  }

  String? _resultMetadataValue(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _nonEmpty(event.resultMetadata[key]);

  List<String> _resultMetadataCsvStrings(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => (event.resultMetadata[key] ?? '')
      .split(',')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);

  List<int> _resultMetadataCsvInts(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) => _resultMetadataCsvStrings(
    event,
    key,
  ).map(int.tryParse).whereType<int>().toList(growable: false);

  int? _resultMetadataInt(OpenCrayChatRuntimeEventSnapshot event, String key) =>
      int.tryParse(event.resultMetadata[key]?.trim() ?? '');

  bool? _resultMetadataBool(
    OpenCrayChatRuntimeEventSnapshot event,
    String key,
  ) {
    final String value = event.resultMetadata[key]?.trim().toLowerCase() ?? '';
    if (value == 'true') {
      return true;
    }
    if (value == 'false') {
      return false;
    }
    return null;
  }

  bool _resultMetadataTruncated(OpenCrayChatRuntimeEventSnapshot event) {
    return _resultMetadataBool(event, 'resultTruncated') == true ||
        _resultMetadataBool(event, 'truncated') == true;
  }

  static const Set<String> _thinkingPlaceholders = <String>{
    'Thinking',
    'Thinking…',
    'Thinking...',
    'OpenCray is thinking...',
    '思考中',
    '思考中…',
    '思考中...',
  };

  String _snapshotActiveSessionId(OpenCrayChatSnapshot snapshot) {
    for (final session in snapshot.drawer.sessions) {
      final String sessionId = session.sessionId.trim();
      if (session.isSelected && sessionId.isNotEmpty) {
        return sessionId;
      }
    }
    final String runtimeSessionId =
        snapshot.runtimeActivity?.sessionId.trim() ?? '';
    if (runtimeSessionId.isNotEmpty) {
      return runtimeSessionId;
    }
    for (final session in snapshot.drawer.sessions) {
      final String sessionId = session.sessionId.trim();
      if (sessionId.isNotEmpty) {
        return sessionId;
      }
    }
    return '';
  }

  OpenCrayChatRuntimeSnapshot? _resolveRuntimeSnapshot({
    required String expectedSessionId,
    OpenCrayChatRuntimeSnapshot? embedded,
    OpenCrayChatRuntimeSnapshot? streamed,
  }) {
    final String normalizedExpectedSessionId = expectedSessionId.trim();
    final OpenCrayChatRuntimeSnapshot? resolved =
        normalizedExpectedSessionId.isEmpty
        ? resolveChatRuntimeSnapshot(embedded, streamed)
        : resolveChatRuntimeSnapshotForSession(
            expectedSessionId: normalizedExpectedSessionId,
            embedded: embedded,
            streamed: streamed,
          );
    final String sessionId = normalizedExpectedSessionId.isNotEmpty
        ? normalizedExpectedSessionId
        : resolved?.sessionId.trim() ?? _activeSessionId;
    final List<OpenCrayChatLiveAssistantDraftSnapshot> overrideDrafts =
        _liveAssistantDraftOverridesBySession[sessionId]?.values.toList(
          growable: false,
        ) ??
        const <OpenCrayChatLiveAssistantDraftSnapshot>[];
    if (overrideDrafts.isEmpty) {
      return resolved;
    }
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> mergedDrafts =
        <String, OpenCrayChatLiveAssistantDraftSnapshot>{
          for (final draft
              in resolved?.liveAssistantDrafts ??
                  const <OpenCrayChatLiveAssistantDraftSnapshot>[])
            if (draft.pendingMessageId.trim().isNotEmpty)
              draft.pendingMessageId.trim(): draft,
        };
    for (final draft in overrideDrafts) {
      final String pendingMessageId = draft.pendingMessageId.trim();
      final OpenCrayChatLiveAssistantDraftSnapshot? existing =
          mergedDrafts[pendingMessageId];
      if (existing == null ||
          draft.updatedAtEpochMs >= existing.updatedAtEpochMs) {
        mergedDrafts[pendingMessageId] = draft;
      }
    }
    final List<OpenCrayChatLiveAssistantDraftSnapshot> sortedDrafts =
        mergedDrafts.values.toList(growable: false)..sort(
          (left, right) =>
              left.updatedAtEpochMs.compareTo(right.updatedAtEpochMs),
        );
    return OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns: resolved?.activeRuns ?? const <OpenCrayChatRunSnapshot>[],
      retainedRuns: resolved?.retainedRuns ?? const <OpenCrayChatRunSnapshot>[],
      subAgents: resolved?.subAgents ?? const <OpenCrayChatSubAgentSnapshot>[],
      events: resolved?.events ?? const <OpenCrayChatRuntimeEventSnapshot>[],
      liveAssistantDrafts: sortedDrafts,
      hostLifecycle: resolved?.hostLifecycle,
      updatedAtEpochMs: resolved?.updatedAtEpochMs ?? 0,
    );
  }
}

class _ChatScrollContent extends StatelessWidget {
  const _ChatScrollContent({
    required this.bridge,
    required this.copy,
    required this.state,
    required this.showSandboxPreviewCards,
    required this.voicePlaybackControllerFactory,
    required this.selectedMessageIds,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.busyRetryRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onRetryRunTrace,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final bool showSandboxPreviewCards;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Set<String> selectedMessageIds;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onRetryRunTrace;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, OpenCrayMarkdownSelectionSnapshot?)
  onMessageTextSelectionChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text(state.screenTitle, style: _ChatTextStyles.pageTitle),
        const SizedBox(height: 20),
        _SummaryCard(copy: copy, summary: state.summary, bridge: bridge),
        const SizedBox(height: 20),
        if (state.messages.isEmpty &&
            state.runTraces.isEmpty &&
            state.pendingApprovals.isEmpty)
          SizedBox(height: state.emptyThreadHeight)
        else
          _MessageList(
            bridge: bridge,
            copy: copy,
            showSandboxPreviewCards: showSandboxPreviewCards,
            voicePlaybackControllerFactory: voicePlaybackControllerFactory,
            messages: state.messages,
            runTraces: state.runTraces,
            selectedMessageIds: selectedMessageIds,
            interruptConfirmRunId: interruptConfirmRunId,
            busyInterruptRunIds: busyInterruptRunIds,
            busyRetryRunIds: busyRetryRunIds,
            onArmInterruptRunTrace: onArmInterruptRunTrace,
            onDismissInterruptRunTrace: onDismissInterruptRunTrace,
            onInterruptRunTrace: onInterruptRunTrace,
            onRetryRunTrace: onRetryRunTrace,
            onMessageLongPress: onMessageLongPress,
            onMessageSelectionToggle: onMessageSelectionToggle,
            onMessageTextSelectionChanged: onMessageTextSelectionChanged,
          ),
      ],
    );
  }
}

class _TopGlassBar extends StatelessWidget {
  const _TopGlassBar({required this.height, required this.strength});

  final double height;
  final double strength;

  @override
  Widget build(BuildContext context) {
    final double blurSigma = lerpDouble(0, 14, strength)!;
    final Color borderColor = Color.lerp(
      const Color(0x00FFFFFF),
      const Color(0x24DCE7F6),
      strength,
    )!;
    final Color shadowColor = Color.lerp(
      const Color(0x00000000),
      const Color(0x0A000000),
      strength,
    )!;
    final double shadowBlur = lerpDouble(0, 16, strength)!;
    final double shadowOffset = lerpDouble(0, 6, strength)!;

    return SizedBox(
      height: height,
      child: ClipRect(
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: <Color>[
                  Color.lerp(
                    const Color(0xA8FFFFFF),
                    const Color(0xE8FFFFFF),
                    strength,
                  )!,
                  Color.lerp(
                    const Color(0x70FFFFFF),
                    const Color(0xC2FFFFFF),
                    strength,
                  )!,
                  Color.lerp(
                    const Color(0x14F8FAFE),
                    const Color(0x54F8FAFE),
                    strength,
                  )!,
                  const Color(0x00F8FAFE),
                ],
                stops: const <double>[0, 0.32, 0.72, 1],
              ),
              border: Border(bottom: BorderSide(color: borderColor)),
              boxShadow: <BoxShadow>[
                BoxShadow(
                  color: shadowColor,
                  blurRadius: shadowBlur,
                  offset: Offset(0, shadowOffset),
                ),
              ],
            ),
            child: const SizedBox.expand(),
          ),
        ),
      ),
    );
  }
}

class _ChatToolbar extends StatelessWidget {
  const _ChatToolbar({
    required this.copy,
    required this.sessionButtonLabel,
    required this.modeLabel,
    required this.runtimeEnvironment,
    required this.onRuntimeEnvironmentSelected,
    required this.onSessionsPressed,
    this.isSelectionMode = false,
    this.selectedCount = 0,
    this.onDonePressed,
  });

  final OpenCrayUiCopy copy;
  final String sessionButtonLabel;
  final String modeLabel;
  final _ChatRuntimeEnvironment runtimeEnvironment;
  final ValueChanged<_ChatRuntimeEnvironment> onRuntimeEnvironmentSelected;
  final VoidCallback onSessionsPressed;
  final bool isSelectionMode;
  final int selectedCount;
  final VoidCallback? onDonePressed;

  @override
  Widget build(BuildContext context) {
    if (isSelectionMode) {
      return SizedBox(
        height: 40,
        child: Row(
          children: <Widget>[
            GestureDetector(
              key: const ValueKey<String>('chat-selection-done'),
              onTap: onDonePressed,
              behavior: HitTestBehavior.opaque,
              child: SizedBox(
                width: 56,
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    copy.chatSelectionDoneAction,
                    style: _ChatTextStyles.selectionToolbarAction,
                  ),
                ),
              ),
            ),
            Expanded(
              child: Center(
                child: Text(
                  copy.chatSelectionCount(selectedCount),
                  style: _ChatTextStyles.selectionToolbarTitle,
                ),
              ),
            ),
            const SizedBox(width: 56),
          ],
        ),
      );
    }
    return Row(
      children: <Widget>[
        GestureDetector(
          onTap: onSessionsPressed,
          behavior: HitTestBehavior.opaque,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  const Icon(
                    Icons.menu_rounded,
                    size: 14,
                    color: _ChatPalette.textSecondary,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    sessionButtonLabel,
                    style: _ChatTextStyles.toolbarButton,
                  ),
                  const SizedBox(width: 2),
                  const Icon(
                    Icons.chevron_right_rounded,
                    size: 16,
                    color: _ChatPalette.textSecondary,
                  ),
                ],
              ),
            ),
          ),
        ),
        const Spacer(),
        _ChatRuntimeEnvironmentSelector(
          environment: runtimeEnvironment,
          onSelected: onRuntimeEnvironmentSelected,
        ),
        const SizedBox(width: 8),
        Text(modeLabel, style: _ChatTextStyles.modeLabel),
      ],
    );
  }
}

class _ChatRuntimeEnvironmentSelector extends StatelessWidget {
  const _ChatRuntimeEnvironmentSelector({
    required this.environment,
    required this.onSelected,
  });

  final _ChatRuntimeEnvironment environment;
  final ValueChanged<_ChatRuntimeEnvironment> onSelected;

  @override
  Widget build(BuildContext context) {
    final String label = _selectorLabel(environment);
    final IconData icon = _selectorIcon(environment);
    return PopupMenuButton<_ChatRuntimeEnvironment>(
      key: const ValueKey<String>('chat-runtime-environment-selector'),
      tooltip: 'Runtime environment',
      padding: EdgeInsets.zero,
      offset: const Offset(0, 42),
      elevation: 10,
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: _ChatPalette.border),
      ),
      onSelected: onSelected,
      itemBuilder: (BuildContext context) =>
          <PopupMenuEntry<_ChatRuntimeEnvironment>>[
            PopupMenuItem<_ChatRuntimeEnvironment>(
              key: const ValueKey<String>('chat-runtime-menu-local'),
              value: _ChatRuntimeEnvironment.local,
              child: _ChatRuntimeEnvironmentMenuRow(
                icon: Icons.laptop_mac_outlined,
                iconKey: const ValueKey<String>('chat-runtime-menu-icon-local'),
                label: 'Run locally',
                isSelected: environment == _ChatRuntimeEnvironment.local,
              ),
            ),
            PopupMenuItem<_ChatRuntimeEnvironment>(
              key: const ValueKey<String>('chat-runtime-menu-cloud'),
              value: _ChatRuntimeEnvironment.cloud,
              child: _ChatRuntimeEnvironmentMenuRow(
                icon: Icons.cloud_queue_rounded,
                iconKey: const ValueKey<String>('chat-runtime-menu-icon-cloud'),
                label: 'Run in cloud',
                isSelected: environment == _ChatRuntimeEnvironment.cloud,
              ),
            ),
          ],
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Icon(
                icon,
                key: const ValueKey<String>('chat-runtime-selector-icon'),
                size: 14,
                color: _ChatPalette.textSecondary,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                key: const ValueKey<String>('chat-runtime-selector-label'),
                style: _ChatTextStyles.toolbarButton,
              ),
              const SizedBox(width: 2),
              const Icon(
                Icons.expand_more_rounded,
                size: 16,
                color: _ChatPalette.textSecondary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _selectorLabel(_ChatRuntimeEnvironment environment) =>
      environment == _ChatRuntimeEnvironment.cloud ? 'Cloud' : 'Local';

  IconData _selectorIcon(_ChatRuntimeEnvironment environment) =>
      environment == _ChatRuntimeEnvironment.cloud
      ? Icons.cloud_queue_rounded
      : Icons.laptop_mac_outlined;
}

class _ChatRuntimeEnvironmentMenuRow extends StatelessWidget {
  const _ChatRuntimeEnvironmentMenuRow({
    required this.icon,
    required this.iconKey,
    required this.label,
    required this.isSelected,
  });

  final IconData icon;
  final Key iconKey;
  final String label;
  final bool isSelected;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: <Widget>[
        Icon(icon, key: iconKey, size: 18, color: _ChatPalette.textSecondary),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            label,
            style: _ChatTextStyles.toolbarButton.copyWith(
              color: _ChatPalette.textPrimary,
            ),
          ),
        ),
        if (isSelected)
          const Icon(Icons.check_rounded, size: 16, color: _ChatPalette.accent),
      ],
    );
  }
}

class _ChatSelectionToolbar extends StatelessWidget {
  const _ChatSelectionToolbar({
    required this.copy,
    required this.selectedCount,
    required this.onCopyPressed,
    required this.onDeletePressed,
  });

  final OpenCrayUiCopy copy;
  final int selectedCount;
  final VoidCallback? onCopyPressed;
  final VoidCallback? onDeletePressed;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      key: const ValueKey<String>('chat-selection-toolbar'),
      decoration: _ChatDecorations.card(),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          children: <Widget>[
            Expanded(
              child: _ChatSelectionActionButton(
                key: const ValueKey<String>('chat-selection-copy'),
                icon: CupertinoIcons.doc_on_doc,
                label: copy.chatMessageCopyAction,
                onPressed: selectedCount > 0 ? onCopyPressed : null,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: _ChatSelectionActionButton(
                key: const ValueKey<String>('chat-selection-delete'),
                icon: CupertinoIcons.delete,
                label: copy.chatMessageDeleteAction,
                isDestructive: true,
                onPressed: selectedCount > 0 ? onDeletePressed : null,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ChatSelectionActionButton extends StatelessWidget {
  const _ChatSelectionActionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    this.isDestructive = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool isDestructive;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isDestructive
        ? const Color(0xFFFF3B30)
        : _ChatPalette.textPrimary;
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: onPressed == null ? 0.38 : 1,
        child: Container(
          height: 44,
          decoration: BoxDecoration(
            color: isDestructive
                ? const Color(0xFFFFF2F1)
                : _ChatPalette.subtleSurface,
            borderRadius: BorderRadius.circular(14),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Icon(icon, size: 16, color: foregroundColor),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: _ChatTextStyles.selectionAction.copyWith(
                    color: foregroundColor,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({required this.copy, required this.summary, this.bridge});

  final OpenCrayUiCopy copy;
  final ChatSessionSummary summary;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                Expanded(
                  child: Text(summary.title, style: _ChatTextStyles.cardTitle),
                ),
                const SizedBox(width: 12),
                Text(summary.badge, style: _ChatTextStyles.summaryBadge),
              ],
            ),
            const SizedBox(height: 8),
            _OpenCrayMarkdownTextBlock(
              copy: copy,
              data: summary.body,
              bodyStyle: _ChatTextStyles.bodyMuted,
              surfaceColor: Colors.white,
              bridge: bridge,
            ),
          ],
        ),
      ),
    );
  }
}

class _PendingApprovalOverlaySurface extends StatelessWidget {
  const _PendingApprovalOverlaySurface({
    required this.copy,
    required this.approvals,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onApproveApprovalForSession,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final List<ChatPendingApprovalData> approvals;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onApproveApprovalForSession;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    final ChatPendingApprovalData activeApproval = approvals.first;
    final List<ChatPendingApprovalData> queuedApprovals = approvals.length <= 1
        ? const <ChatPendingApprovalData>[]
        : approvals.sublist(1);

    return _ApprovalGlassSurface(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          key: const ValueKey<String>('chat-approval-surface-content'),
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            _PendingApprovalCardStack(
              copy: copy,
              activeApproval: activeApproval,
              queuedApprovals: queuedApprovals,
            ),
            const SizedBox(height: 8),
            _ApprovalActionRow(
              approval: activeApproval,
              isBusy: busyApprovalTaskIds.contains(activeApproval.approvalId),
              onApprove: () => onApproveApproval(activeApproval),
              onApproveForSession: () =>
                  onApproveApprovalForSession(activeApproval),
              onReject: () => onRejectApproval(activeApproval),
            ),
          ],
        ),
      ),
    );
  }
}

class _ApprovalGlassSurface extends StatelessWidget {
  const _ApprovalGlassSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(28),
      child: Stack(
        children: <Widget>[
          Positioned.fill(
            child: IgnorePointer(
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
                child: DecoratedBox(
                  key: const ValueKey<String>('chat-approval-surface'),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(28),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.52),
                    ),
                    boxShadow: <BoxShadow>[
                      BoxShadow(
                        color: const Color(0x12000000),
                        blurRadius: 22,
                        offset: const Offset(0, 10),
                      ),
                    ],
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[
                        Colors.white.withValues(alpha: 0.42),
                        Colors.white.withValues(alpha: 0.32),
                        const Color(0xFFF3F7FF).withValues(alpha: 0.26),
                        const Color(0xFFD7E5FF).withValues(alpha: 0.18),
                      ],
                      stops: const <double>[0, 0.28, 0.72, 1],
                    ),
                  ),
                  child: const SizedBox.expand(),
                ),
              ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}

class _PendingApprovalCardStack extends StatelessWidget {
  const _PendingApprovalCardStack({
    required this.copy,
    required this.activeApproval,
    required this.queuedApprovals,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData activeApproval;
  final List<ChatPendingApprovalData> queuedApprovals;

  @override
  Widget build(BuildContext context) {
    final List<ChatPendingApprovalData> previewApprovals = queuedApprovals
        .take(2)
        .toList(growable: false);
    if (previewApprovals.isEmpty) {
      return _PendingApprovalCard(copy: copy, approval: activeApproval);
    }
    final int previewCount = previewApprovals.length;
    return Padding(
      padding: EdgeInsets.only(bottom: 12.0 * previewCount),
      child: Stack(
        key: const ValueKey<String>('chat-approval-stack'),
        clipBehavior: Clip.none,
        children: <Widget>[
          for (int index = previewApprovals.length - 1; index >= 0; index -= 1)
            Positioned(
              left: 6.0 * (index + 1),
              right: 6.0 * (index + 1),
              top: 12.0 * (index + 1),
              child: IgnorePointer(
                child: Opacity(
                  opacity: 0.9 - (index * 0.16),
                  child: _PendingApprovalCard(
                    copy: copy,
                    approval: previewApprovals[index],
                    isPreview: true,
                  ),
                ),
              ),
            ),
          _PendingApprovalCard(copy: copy, approval: activeApproval),
        ],
      ),
    );
  }
}

class _PendingApprovalCard extends StatelessWidget {
  const _PendingApprovalCard({
    required this.copy,
    required this.approval,
    this.isPreview = false,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData approval;
  final bool isPreview;

  @override
  Widget build(BuildContext context) {
    final _PendingApprovalPresentation presentation =
        _PendingApprovalPresentation.fromApproval(approval);
    final Color surfaceColor = approval.isHighRisk
        ? const Color(0xFFFFF8F5)
        : const Color(0xFFF7F9FC);
    final Color borderColor = approval.isHighRisk
        ? const Color(0xFFF0D6C5)
        : const Color(0xFFDCE3ED);
    final Color reasonColor = approval.isHighRisk
        ? const Color(0xFF7B5B47)
        : const Color(0xFF5B6675);
    final TextStyle detailStyle = _ChatTextStyles.approvalRequest.copyWith(
      color: const Color(0xFF1E2430),
    );
    final List<String> previewLines = <String>[
      presentation.primaryLine,
      if (presentation.secondaryLines.isNotEmpty)
        presentation.secondaryLines.first,
      if (presentation.reasonLine != null) presentation.reasonLine!,
    ];
    final List<String> activeLines = <String>[
      presentation.primaryLine,
      ...presentation.secondaryLines,
      if (presentation.reasonLine != null) presentation.reasonLine!,
      if (presentation.messageLine != null) presentation.messageLine!,
    ];
    final List<String> visibleLines = isPreview ? previewLines : activeLines;

    return DecoratedBox(
      key: ValueKey<String>('chat-approval-card-${approval.approvalId}'),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: borderColor),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(approval.title, style: _ChatTextStyles.cardTitle),
                    ],
                  ),
                ),
                if (approval.isHighRisk && !isPreview) ...<Widget>[
                  const SizedBox(width: 12),
                  DecoratedBox(
                    decoration: BoxDecoration(
                      color: _ChatPalette.highRiskBadgeSurface,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      child: Text(
                        copy.chatHighRiskApproval,
                        style: _ChatTextStyles.highRiskBadge,
                      ),
                    ),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 10),
            for (
              int index = 0;
              index < visibleLines.length;
              index += 1
            ) ...<Widget>[
              if (index > 0) const SizedBox(height: 8),
              Text(
                visibleLines[index],
                maxLines: isPreview && index > 0 ? 1 : null,
                overflow: isPreview && index > 0
                    ? TextOverflow.ellipsis
                    : TextOverflow.visible,
                style: index == 0
                    ? detailStyle
                    : _ChatTextStyles.approvalReason.copyWith(
                        color:
                            index == visibleLines.length - 1 &&
                                presentation.messageLine != null &&
                                visibleLines[index] == presentation.messageLine
                            ? _ChatPalette.textSecondary
                            : reasonColor,
                      ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ApprovalActionRow extends StatelessWidget {
  const _ApprovalActionRow({
    required this.approval,
    required this.isBusy,
    required this.onApprove,
    required this.onApproveForSession,
    required this.onReject,
  });

  final ChatPendingApprovalData approval;
  final bool isBusy;
  final VoidCallback onApprove;
  final VoidCallback onApproveForSession;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
    final Color accentColor = approval.isHighRisk
        ? const Color(0xFFF97316)
        : _ChatPalette.accent;
    return Row(
      children: <Widget>[
        Expanded(
          child: _ApprovalActionButton(
            label: approval.rejectLabel,
            foregroundColor: const Color(0xFF526071),
            backgroundColor: Colors.white,
            borderColor: const Color(0xFFD9DEE8),
            onPressed: isBusy ? null : onReject,
          ),
        ),
        const SizedBox(width: 10),
        if (approval.supportsSessionApproval) ...<Widget>[
          Expanded(
            child: _ApprovalActionButton(
              label: approval.approveForSessionLabel,
              foregroundColor: accentColor,
              backgroundColor: Colors.white,
              borderColor: accentColor.withValues(alpha: 0.65),
              onPressed: isBusy ? null : onApproveForSession,
            ),
          ),
          const SizedBox(width: 10),
        ],
        Expanded(
          child: _ApprovalActionButton(
            label: approval.approveLabel,
            foregroundColor: Colors.white,
            backgroundColor: accentColor,
            borderColor: accentColor,
            onPressed: isBusy ? null : onApprove,
          ),
        ),
      ],
    );
  }
}

class _PendingApprovalPresentation {
  const _PendingApprovalPresentation({
    required this.primaryLine,
    required this.secondaryLines,
    required this.reasonLine,
    required this.messageLine,
  });

  final String primaryLine;
  final List<String> secondaryLines;
  final String? reasonLine;
  final String? messageLine;

  factory _PendingApprovalPresentation.fromApproval(
    ChatPendingApprovalData approval,
  ) {
    final String requestSummary = approval.requestSummary.trim();
    final String primaryDetail = approval.primaryDetail.trim();
    final List<String> pathDetails = approval.pathDetails
        .map((path) => path.trim())
        .where((path) => path.isNotEmpty)
        .toList(growable: false);
    final String workingDirectory = approval.workingDirectory.trim();
    final String reason = approval.reason.trim();
    final String message = approval.message.trim();
    final String body = approval.body.trim();

    final String primaryLine = requestSummary.isNotEmpty
        ? requestSummary
        : primaryDetail.isNotEmpty
        ? primaryDetail
        : pathDetails.isNotEmpty
        ? pathDetails.first
        : body;
    final List<String> secondaryLines = <String>[
      if (primaryDetail.isNotEmpty &&
          primaryDetail != primaryLine &&
          !pathDetails.contains(primaryDetail))
        primaryDetail,
      ...pathDetails.where((path) => path != primaryLine),
      if (workingDirectory.isNotEmpty) 'Working directory  $workingDirectory',
    ];
    final String? reasonLine = reason.isEmpty ? null : 'Reason  $reason';
    final String? messageLine =
        message.isNotEmpty &&
            message != reason &&
            !_runTraceTextContains(body, message)
        ? message
        : null;
    return _PendingApprovalPresentation(
      primaryLine: primaryLine,
      secondaryLines: secondaryLines,
      reasonLine: reasonLine,
      messageLine: messageLine,
    );
  }
}

class _ApprovalActionButton extends StatelessWidget {
  const _ApprovalActionButton({
    required this.label,
    required this.foregroundColor,
    required this.backgroundColor,
    required this.borderColor,
    required this.onPressed,
  });

  final String label;
  final Color foregroundColor;
  final Color backgroundColor;
  final Color borderColor;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onPressed != null;
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 38,
        decoration: BoxDecoration(
          color: enabled
              ? backgroundColor
              : backgroundColor.withValues(alpha: 0.55),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: enabled ? borderColor : borderColor.withValues(alpha: 0.55),
          ),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: _ChatTextStyles.approvalAction.copyWith(
            color: enabled
                ? foregroundColor
                : foregroundColor.withValues(alpha: 0.6),
          ),
        ),
      ),
    );
  }
}

const Duration _chatTimestampDividerThreshold = Duration(minutes: 8);
const List<String> _englishMonthAbbreviations = <String>[
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];
const List<String> _englishWeekdayAbbreviations = <String>[
  'Mon',
  'Tue',
  'Wed',
  'Thu',
  'Fri',
  'Sat',
  'Sun',
];
const List<String> _chineseWeekdayLabels = <String>[
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
  '周日',
];

String _twoDigitChatNumber(int value) => value.toString().padLeft(2, '0');

String _formatChatClockLabel(OpenCrayUiCopy copy, DateTime dateTime) {
  final String clock =
      '${_twoDigitChatNumber(dateTime.hour)}:${_twoDigitChatNumber(dateTime.minute)}';
  return clock;
}

String _formatChatDateLabel(
  OpenCrayUiCopy copy,
  DateTime dateTime, {
  required bool includeYear,
}) {
  if (copy.isChinese) {
    return includeYear
        ? '${dateTime.year}年${dateTime.month}月${dateTime.day}日'
        : '${dateTime.month}月${dateTime.day}日';
  }
  final int monthIndex = (dateTime.month - 1).clamp(0, 11).toInt();
  final String month = _englishMonthAbbreviations[monthIndex];
  if (includeYear) {
    return '$month ${dateTime.day}, ${dateTime.year}';
  }
  return '$month ${dateTime.day}';
}

bool _isSameChatDay(DateTime left, DateTime right) =>
    left.year == right.year &&
    left.month == right.month &&
    left.day == right.day;

DateTime _chatDayStart(DateTime dateTime) =>
    DateTime(dateTime.year, dateTime.month, dateTime.day);

String _formatChatWeekdayLabel(OpenCrayUiCopy copy, DateTime dateTime) {
  final int weekdayIndex = (dateTime.weekday - 1).clamp(0, 6).toInt();
  return copy.isChinese
      ? _chineseWeekdayLabels[weekdayIndex]
      : _englishWeekdayAbbreviations[weekdayIndex];
}

String _formatChatDividerLabel(OpenCrayUiCopy copy, DateTime dateTime) {
  final String clock = _formatChatClockLabel(copy, dateTime);
  final DateTime now = DateTime.now().toLocal();
  final bool includeYear = now.year != dateTime.year;
  final String datePart = _formatChatDateLabel(
    copy,
    dateTime,
    includeYear: includeYear,
  );
  return '$datePart $clock';
}

String _formatChatSessionTimestampLabel(
  OpenCrayUiCopy copy,
  int? epochMs,
  String fallback,
) {
  if (epochMs == null || epochMs <= 0) {
    return fallback;
  }
  final DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(
    epochMs,
  ).toLocal();
  final DateTime now = DateTime.now().toLocal();
  if (_isSameChatDay(dateTime, now)) {
    return _formatChatClockLabel(copy, dateTime);
  }
  final DateTime yesterday = now.subtract(const Duration(days: 1));
  if (_isSameChatDay(dateTime, yesterday)) {
    return copy.chatYesterday;
  }
  final int dayDistance = _chatDayStart(
    now,
  ).difference(_chatDayStart(dateTime)).inDays;
  if (dayDistance >= 2 && dayDistance < 7) {
    return _formatChatWeekdayLabel(copy, dateTime);
  }
  return _formatChatDateLabel(
    copy,
    dateTime,
    includeYear: now.year != dateTime.year,
  );
}

bool _shouldInsertChatTimestampDivider(
  int currentEpochMs,
  int? previousEpochMs,
) {
  if (previousEpochMs == null || previousEpochMs <= 0) {
    return true;
  }
  final DateTime current = DateTime.fromMillisecondsSinceEpoch(
    currentEpochMs,
  ).toLocal();
  final DateTime previous = DateTime.fromMillisecondsSinceEpoch(
    previousEpochMs,
  ).toLocal();
  final bool crossedDay =
      current.year != previous.year ||
      current.month != previous.month ||
      current.day != previous.day;
  if (crossedDay) {
    return true;
  }
  return current.difference(previous).abs() >= _chatTimestampDividerThreshold;
}

class _ChatTimestampDivider extends StatelessWidget {
  const _ChatTimestampDivider({required this.label, this.messageId});

  final String label;
  final String? messageId;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Center(
        child: DecoratedBox(
          key: messageId == null
              ? null
              : ValueKey<String>('chat-message-divider-$messageId'),
          decoration: BoxDecoration(
            color: const Color(0xFFE9E9ED),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            child: Text(label, style: _ChatTextStyles.timeline),
          ),
        ),
      ),
    );
  }
}

class _ChatMessageWithTimestamp extends StatelessWidget {
  const _ChatMessageWithTimestamp({
    super.key,
    required this.bridge,
    required this.copy,
    required this.message,
    required this.voicePlaybackControllerFactory,
    required this.alignment,
    required this.backgroundColor,
    required this.textColor,
    required this.maxWidth,
    required this.selectionMode,
    required this.isSelected,
    required this.onLongPress,
    required this.onSelectionToggle,
    required this.onTextSelectionChanged,
    this.attachedRunTraces = const <ChatRunTraceData>[],
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageData message;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Alignment alignment;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;
  final bool selectionMode;
  final bool isSelected;
  final void Function(ChatMessageData, Rect, String?) onLongPress;
  final VoidCallback onSelectionToggle;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onTextSelectionChanged;
  final List<ChatRunTraceData> attachedRunTraces;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  static const double _threadHorizontalInset = 20;
  static const double _selectionControlGutter = 42;

  @override
  Widget build(BuildContext context) {
    final Widget bubble = _ChatMessageBubble(
      bridge: bridge,
      copy: copy,
      message: message,
      voicePlaybackControllerFactory: voicePlaybackControllerFactory,
      backgroundColor: backgroundColor,
      textColor: textColor,
      maxWidth: maxWidth,
      selectionMode: selectionMode,
      onLongPress: onLongPress,
      onTextSelectionChanged: onTextSelectionChanged,
      attachedRunTraces: attachedRunTraces,
      interruptConfirmRunId: interruptConfirmRunId,
      busyInterruptRunIds: busyInterruptRunIds,
      busyRetryRunIds: busyRetryRunIds,
      onArmInterruptRunTrace: onArmInterruptRunTrace,
      onDismissInterruptRunTrace: onDismissInterruptRunTrace,
      onInterruptRunTrace: onInterruptRunTrace,
      onRetryRunTrace: onRetryRunTrace,
    );
    if (!selectionMode) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Align(alignment: alignment, child: bubble),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          return SizedBox(
            width: constraints.maxWidth,
            child: GestureDetector(
              key: ValueKey<String>('chat-message-row-${message.messageId}'),
              onTap: onSelectionToggle,
              behavior: HitTestBehavior.opaque,
              child: Stack(
                clipBehavior: Clip.none,
                children: <Widget>[
                  Positioned(
                    left: -_threadHorizontalInset,
                    right: -_threadHorizontalInset,
                    top: 0,
                    bottom: 0,
                    child: AnimatedContainer(
                      key: ValueKey<String>(
                        'chat-message-row-bg-${message.messageId}',
                      ),
                      duration: const Duration(milliseconds: 160),
                      curve: Curves.easeOutCubic,
                      color: isSelected
                          ? _ChatPalette.selectionRowHighlight
                          : Colors.transparent,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: <Widget>[
                        SizedBox(
                          width: _selectionControlGutter,
                          child: Center(
                            child: _ChatSelectionControl(
                              messageId: message.messageId,
                              isSelected: isSelected,
                            ),
                          ),
                        ),
                        Expanded(
                          child: Align(alignment: alignment, child: bubble),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _ChatSelectionControl extends StatelessWidget {
  const _ChatSelectionControl({
    required this.messageId,
    required this.isSelected,
  });

  final String messageId;
  final bool isSelected;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      key: ValueKey<String>('chat-message-select-control-$messageId'),
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOutCubic,
      width: 22,
      height: 22,
      decoration: BoxDecoration(
        color: isSelected ? _ChatPalette.accent : Colors.white,
        shape: BoxShape.circle,
        border: Border.all(
          color: isSelected
              ? _ChatPalette.accent
              : _ChatPalette.selectionControlBorder,
          width: isSelected ? 0 : 1.5,
        ),
      ),
      alignment: Alignment.center,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 120),
        opacity: isSelected ? 1 : 0,
        child: const Icon(
          CupertinoIcons.check_mark,
          size: 12,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _MessageList extends StatelessWidget {
  const _MessageList({
    required this.bridge,
    required this.copy,
    required this.showSandboxPreviewCards,
    required this.voicePlaybackControllerFactory,
    required this.messages,
    required this.runTraces,
    required this.selectedMessageIds,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.busyRetryRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onRetryRunTrace,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final bool showSandboxPreviewCards;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
  final Set<String> selectedMessageIds;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onRetryRunTrace;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, OpenCrayMarkdownSelectionSnapshot?)
  onMessageTextSelectionChanged;

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    int? previousTimestampEpochMs;
    final Map<String, List<ChatRunTraceData>> anchoredRunTracesByMessageId =
        <String, List<ChatRunTraceData>>{};
    final Set<ChatRunTraceData> renderedRunTraces = <ChatRunTraceData>{};
    final Set<String> visibleMessageIds = messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();

    for (final trace in runTraces) {
      final String anchorMessageId = trace.anchorMessageId.trim();
      if (anchorMessageId.isEmpty) {
        continue;
      }
      anchoredRunTracesByMessageId
          .putIfAbsent(anchorMessageId, () => <ChatRunTraceData>[])
          .add(trace);
    }

    void addRunTrace(
      ChatRunTraceData trace, {
      required bool showRetryAction,
      required bool showInterruptAction,
    }) {
      if (!renderedRunTraces.add(trace)) {
        return;
      }
      final bool canShowInterrupt = showInterruptAction && trace.canInterrupt;
      final bool canShowRetry = showRetryAction && trace.isRetryable;
      children.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Align(
            alignment: Alignment.centerLeft,
            child: _RunTraceBubble(
              key: ValueKey<String>('chat-run-trace-${trace.runId}'),
              bridge: bridge,
              copy: copy,
              trace: trace,
              showSandboxPreviewCard: showSandboxPreviewCards,
              showRetryAction: canShowRetry,
              showInterruptAction: canShowInterrupt,
              showInterruptConfirm:
                  canShowInterrupt &&
                  interruptConfirmRunId == trace.interruptId,
              isInterruptBusy:
                  canShowInterrupt &&
                  busyInterruptRunIds.contains(trace.interruptId),
              onInterruptRequest: canShowInterrupt
                  ? () => onArmInterruptRunTrace(trace)
                  : null,
              onInterruptDismiss:
                  canShowInterrupt && interruptConfirmRunId == trace.interruptId
                  ? () => onDismissInterruptRunTrace(trace)
                  : null,
              onInterruptConfirm: canShowInterrupt
                  ? () => onInterruptRunTrace(trace)
                  : null,
              isRetryBusy:
                  canShowRetry && busyRetryRunIds.contains(trace.retryId),
              onRetry: canShowRetry ? () => onRetryRunTrace(trace) : null,
            ),
          ),
        ),
      );
    }

    for (final message in messages) {
      final String leadingTraceAnchorMessageId =
          _leadingTraceAnchorMessageIdForMessage(
            message,
            anchoredRunTracesByMessageId,
          );
      final List<ChatRunTraceData> leadingTraces =
          leadingTraceAnchorMessageId.isEmpty
          ? const <ChatRunTraceData>[]
          : anchoredRunTracesByMessageId[leadingTraceAnchorMessageId] ??
                const <ChatRunTraceData>[];
      for (final trace in leadingTraces) {
        addRunTrace(trace, showRetryAction: false, showInterruptAction: false);
      }
      final List<ChatRunTraceData> anchoredTraces =
          anchoredRunTracesByMessageId[message.messageId.trim()] ??
          const <ChatRunTraceData>[];
      final int? currentTimestampEpochMs = message.createdAtEpochMs;
      if (currentTimestampEpochMs != null &&
          currentTimestampEpochMs > 0 &&
          message.kind != ChatMessageKind.timeline &&
          _shouldInsertChatTimestampDivider(
            currentTimestampEpochMs,
            previousTimestampEpochMs,
          )) {
        children.add(
          _ChatTimestampDivider(
            messageId: message.messageId,
            label: _formatChatDividerLabel(
              copy,
              DateTime.fromMillisecondsSinceEpoch(
                currentTimestampEpochMs,
              ).toLocal(),
            ),
          ),
        );
      }
      switch (message.kind) {
        case ChatMessageKind.timeline:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFE9E9ED),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 5,
                    ),
                    child: Text(message.text, style: _ChatTextStyles.timeline),
                  ),
                ),
              ),
            ),
          );
        case ChatMessageKind.inbound:
          children.add(
            _ChatMessageWithTimestamp(
              key: ValueKey<String>(_chatMessageListItemKey(message)),
              bridge: bridge,
              copy: copy,
              message: message,
              voicePlaybackControllerFactory: voicePlaybackControllerFactory,
              alignment: Alignment.centerLeft,
              backgroundColor: Colors.white,
              textColor: _ChatPalette.textPrimary,
              maxWidth: 252,
              selectionMode: selectedMessageIds.isNotEmpty,
              isSelected: selectedMessageIds.contains(message.messageId),
              onLongPress: onMessageLongPress,
              onSelectionToggle: () => onMessageSelectionToggle(message),
              onTextSelectionChanged: (selectedText) =>
                  onMessageTextSelectionChanged(message, selectedText),
              attachedRunTraces: anchoredTraces,
              interruptConfirmRunId: interruptConfirmRunId,
              busyInterruptRunIds: busyInterruptRunIds,
              busyRetryRunIds: busyRetryRunIds,
              onArmInterruptRunTrace: onArmInterruptRunTrace,
              onDismissInterruptRunTrace: onDismissInterruptRunTrace,
              onInterruptRunTrace: onInterruptRunTrace,
              onRetryRunTrace: onRetryRunTrace,
            ),
          );
        case ChatMessageKind.outbound:
          children.add(
            _ChatMessageWithTimestamp(
              key: ValueKey<String>(_chatMessageListItemKey(message)),
              bridge: bridge,
              copy: copy,
              message: message,
              voicePlaybackControllerFactory: voicePlaybackControllerFactory,
              alignment: Alignment.centerRight,
              backgroundColor: _ChatPalette.accent,
              textColor: Colors.white,
              maxWidth: 236,
              selectionMode: selectedMessageIds.isNotEmpty,
              isSelected: selectedMessageIds.contains(message.messageId),
              onLongPress: onMessageLongPress,
              onSelectionToggle: () => onMessageSelectionToggle(message),
              onTextSelectionChanged: (selectedText) =>
                  onMessageTextSelectionChanged(message, selectedText),
              attachedRunTraces: anchoredTraces,
              interruptConfirmRunId: interruptConfirmRunId,
              busyInterruptRunIds: busyInterruptRunIds,
              busyRetryRunIds: busyRetryRunIds,
              onArmInterruptRunTrace: onArmInterruptRunTrace,
              onDismissInterruptRunTrace: onDismissInterruptRunTrace,
              onInterruptRunTrace: onInterruptRunTrace,
              onRetryRunTrace: onRetryRunTrace,
            ),
          );
      }
      if (currentTimestampEpochMs != null && currentTimestampEpochMs > 0) {
        previousTimestampEpochMs = currentTimestampEpochMs;
      }
    }

    for (final trace in runTraces) {
      final String anchorMessageId = trace.anchorMessageId.trim();
      final bool showActions =
          anchorMessageId.isEmpty ||
          !visibleMessageIds.contains(anchorMessageId);
      addRunTrace(
        trace,
        showRetryAction: showActions,
        showInterruptAction: false,
      );
    }

    return Column(children: children);
  }
}

String _leadingTraceAnchorMessageIdForMessage(
  ChatMessageData message,
  Map<String, List<ChatRunTraceData>> anchoredRunTracesByMessageId,
) {
  final String messageId = message.messageId.trim();
  if (messageId.isNotEmpty &&
      anchoredRunTracesByMessageId.containsKey(messageId)) {
    return messageId;
  }
  final String runtimeAnchorMessageId = message.runtimeAnchorMessageId.trim();
  if (runtimeAnchorMessageId.isNotEmpty &&
      anchoredRunTracesByMessageId.containsKey(runtimeAnchorMessageId)) {
    return runtimeAnchorMessageId;
  }
  return '';
}

String _chatMessageListItemKey(ChatMessageData message) {
  final String messageId = message.messageId.trim();
  if (messageId.isNotEmpty) {
    return 'chat-message-item-$messageId';
  }
  return 'chat-message-item-${message.kind.name}-${message.createdAtEpochMs ?? 0}-${javaStringHashCode(message.text)}';
}

class _RunTraceBubble extends StatefulWidget {
  const _RunTraceBubble({
    super.key,
    required this.bridge,
    required this.copy,
    required this.trace,
    this.showSandboxPreviewCard = false,
    this.showRetryAction = false,
    this.showInterruptAction = false,
    this.showInterruptConfirm = false,
    this.onInterruptRequest,
    this.onInterruptDismiss,
    this.onInterruptConfirm,
    this.isInterruptBusy = false,
    this.onRetry,
    this.isRetryBusy = false,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatRunTraceData trace;
  final bool showSandboxPreviewCard;
  final bool showRetryAction;
  final bool showInterruptAction;
  final bool showInterruptConfirm;
  final VoidCallback? onInterruptRequest;
  final VoidCallback? onInterruptDismiss;
  final VoidCallback? onInterruptConfirm;
  final bool isInterruptBusy;
  final VoidCallback? onRetry;
  final bool isRetryBusy;

  @override
  State<_RunTraceBubble> createState() => _RunTraceBubbleState();
}

class _RunTraceBubbleState extends State<_RunTraceBubble> {
  static final Map<String, Set<ValueNotifier<ChatRunTraceData>>>
  _openTraceNotifiersByRunKey =
      <String, Set<ValueNotifier<ChatRunTraceData>>>{};

  @override
  void initState() {
    super.initState();
    _publishTraceUpdate();
  }

  @override
  void didUpdateWidget(covariant _RunTraceBubble oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.trace, widget.trace)) {
      _runTraceDebug(
        'bubble.traceUpdate run=${widget.trace.runId} openInspectors=${_openNotifierCount(widget.trace)} history=${widget.trace.history.length}',
      );
    }
    _publishTraceUpdate();
  }

  static String _traceNotifierKey(ChatRunTraceData trace) {
    final String runId = trace.runId.trim();
    if (runId.isNotEmpty) {
      return runId;
    }
    final String taskId = trace.taskId.trim();
    return taskId.isNotEmpty ? taskId : trace.label.trim();
  }

  static int _openNotifierCount(ChatRunTraceData trace) =>
      _openTraceNotifiersByRunKey[_traceNotifierKey(trace)]?.length ?? 0;

  void _publishTraceUpdate() {
    final Set<ValueNotifier<ChatRunTraceData>>? notifiers =
        _openTraceNotifiersByRunKey[_traceNotifierKey(widget.trace)];
    if (notifiers == null || notifiers.isEmpty) {
      return;
    }
    for (final notifier in notifiers.toList(growable: false)) {
      notifier.value = widget.trace;
    }
  }

  Future<void> _openFullscreen() {
    final ValueNotifier<ChatRunTraceData> traceNotifier =
        ValueNotifier<ChatRunTraceData>(widget.trace);
    final String traceKey = _traceNotifierKey(widget.trace);
    _openTraceNotifiersByRunKey
        .putIfAbsent(traceKey, () => <ValueNotifier<ChatRunTraceData>>{})
        .add(traceNotifier);
    return showDialog<void>(
      context: context,
      barrierColor: const Color(0x8A0B0E14),
      builder: (dialogContext) => _RunTraceFullscreenSheet(
        copy: widget.copy,
        traceListenable: traceNotifier,
        showSandboxPreviewCard: widget.showSandboxPreviewCard,
        bridge: widget.bridge,
        onRetry: widget.onRetry,
        isRetryBusy: widget.isRetryBusy,
      ),
    ).whenComplete(() {
      final Set<ValueNotifier<ChatRunTraceData>>? notifiers =
          _openTraceNotifiersByRunKey[traceKey];
      notifiers?.remove(traceNotifier);
      if (notifiers != null && notifiers.isEmpty) {
        _openTraceNotifiersByRunKey.remove(traceKey);
      }
      traceNotifier.dispose();
    });
  }

  Future<void> _openPreviewCard(ChatRunTracePreviewCardData preview) async {
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    try {
      await bridge.openExternalUri(preview.url);
    } catch (_) {
      await bridge.showNativeToast(widget.copy.markdownLinkOpenFailed);
    }
  }

  Future<void> _copyPreviewUrl(ChatRunTracePreviewCardData preview) async {
    await Clipboard.setData(ClipboardData(text: preview.url));
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge != null) {
      await bridge.showNativeToast(widget.copy.chatRunPreviewCopied);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ChatRunTraceData trace = widget.trace;
    final ChatRunTraceSandboxSessionCardData? sessionCard =
        widget.showSandboxPreviewCard ? trace.sessionCard : null;
    final ChatRunTracePreviewCardData? previewCard =
        widget.showSandboxPreviewCard ? trace.previewCard : null;
    final _RunTraceCompactPresentation presentation =
        _buildRunTraceCompactPresentation(trace: trace, copy: widget.copy);
    final bool showInlineActions =
        widget.showRetryAction || widget.showInterruptAction;
    final double bubbleWidth = math.min(
      MediaQuery.sizeOf(context).width - 76,
      314,
    );
    return GestureDetector(
      onDoubleTap: _openFullscreen,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: bubbleWidth,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            _RunTraceStatusLine(
              trace: trace,
              presentation: presentation,
              copy: widget.copy,
              onTap: _openFullscreen,
            ),
            if (showInlineActions) ...<Widget>[
              const SizedBox(height: 8),
              _ChatRunTraceInlineActions(
                copy: widget.copy,
                traces: <ChatRunTraceData>[trace],
                showRetryActions: widget.showRetryAction,
                showInterruptActions: widget.showInterruptAction,
                interruptConfirmRunId: widget.showInterruptConfirm
                    ? trace.interruptId
                    : null,
                busyInterruptRunIds: widget.isInterruptBusy
                    ? <String>{trace.interruptId}
                    : const <String>{},
                busyRetryRunIds: widget.isRetryBusy
                    ? <String>{trace.retryId}
                    : const <String>{},
                onArmInterruptRunTrace: widget.onInterruptRequest == null
                    ? null
                    : (_) => widget.onInterruptRequest!(),
                onDismissInterruptRunTrace: widget.onInterruptDismiss == null
                    ? null
                    : (_) => widget.onInterruptDismiss!(),
                onInterruptRunTrace: widget.onInterruptConfirm == null
                    ? null
                    : (_) => widget.onInterruptConfirm!(),
                onRetryRunTrace: widget.onRetry == null
                    ? null
                    : (_) => widget.onRetry!(),
              ),
            ],
            if (sessionCard != null) ...<Widget>[
              const SizedBox(height: 10),
              _RunTraceSandboxSessionCard(
                key: ValueKey<String>(
                  'chat-run-trace-session-card-${trace.runId}',
                ),
                copy: widget.copy,
                runId: trace.runId,
                session: sessionCard,
                bridge: widget.bridge,
              ),
            ],
            if (previewCard != null) ...<Widget>[
              const SizedBox(height: 10),
              _RunTracePreviewCard(
                key: ValueKey<String>(
                  'chat-run-trace-preview-card-${trace.runId}',
                ),
                copy: widget.copy,
                runId: trace.runId,
                preview: previewCard,
                bridge: widget.bridge,
                onOpen: widget.bridge == null
                    ? null
                    : () => _openPreviewCard(previewCard),
                onCopy: () => _copyPreviewUrl(previewCard),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _RunTraceStatusLine extends StatefulWidget {
  const _RunTraceStatusLine({
    required this.trace,
    required this.presentation,
    required this.copy,
    required this.onTap,
  });

  final ChatRunTraceData trace;
  final _RunTraceCompactPresentation presentation;
  final OpenCrayUiCopy copy;
  final VoidCallback onTap;

  @override
  State<_RunTraceStatusLine> createState() => _RunTraceStatusLineState();
}

class _RunTraceStatusLineState extends State<_RunTraceStatusLine>
    with SingleTickerProviderStateMixin {
  late final AnimationController _shimmerController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1450),
  );

  bool get _shouldAnimate {
    final MediaQueryData? mediaQuery = MediaQuery.maybeOf(context);
    return !widget.trace.isTerminal &&
        !widget.trace.isRetryable &&
        mediaQuery?.disableAnimations != true &&
        !_isAutomatedWidgetTest;
  }

  bool get _isAutomatedWidgetTest {
    bool result = false;
    assert(() {
      result = WidgetsBinding.instance.runtimeType.toString().contains(
        'TestWidgetsFlutterBinding',
      );
      return true;
    }());
    return result;
  }

  @override
  void initState() {
    super.initState();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncAnimation();
  }

  @override
  void didUpdateWidget(covariant _RunTraceStatusLine oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncAnimation();
  }

  void _syncAnimation() {
    if (_shouldAnimate) {
      if (!_shimmerController.isAnimating) {
        _shimmerController.repeat();
      }
    } else {
      _shimmerController.stop();
      _shimmerController.value = 0;
    }
  }

  @override
  void dispose() {
    _shimmerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bool animate = _shouldAnimate;
    final Color baseColor = widget.trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.runTraceActivityText;
    final Color mutedColor = widget.trace.isTerminal || widget.trace.isRetryable
        ? _ChatPalette.textSecondary
        : baseColor;
    final String lead = _runTraceStatusLineLead(
      trace: widget.trace,
      presentation: widget.presentation,
      copy: widget.copy,
    );
    final String? detail = _runTraceStatusLineDetail(
      lead: lead,
      presentation: widget.presentation,
    );
    final String label = detail == null ? lead : '$lead · $detail';
    final TextStyle lineStyle = _ChatTextStyles.timeline.copyWith(
      color: mutedColor,
      fontWeight: FontWeight.w700,
    );
    final Widget text = Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Text(
          lead,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: lineStyle,
        ),
        if (detail != null) ...<Widget>[
          Text(' · ', style: lineStyle),
          Flexible(
            child: Text(
              detail,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: lineStyle,
            ),
          ),
        ],
      ],
    );
    final Widget animatedText = animate
        ? AnimatedBuilder(
            animation: _shimmerController,
            child: text,
            builder: (context, child) {
              final double position = _shimmerController.value;
              return ShaderMask(
                shaderCallback: (bounds) {
                  final double width = math.max(bounds.width, 1);
                  final double center = (position * 2.4 - 0.7) * width;
                  return LinearGradient(
                    begin: Alignment.centerLeft,
                    end: Alignment.centerRight,
                    colors: <Color>[
                      mutedColor,
                      mutedColor.withValues(alpha: 0.54),
                      mutedColor,
                    ],
                    stops: const <double>[0.0, 0.5, 1.0],
                  ).createShader(
                    Rect.fromLTWH(center - width, 0, width * 2, bounds.height),
                  );
                },
                child: child,
              );
            },
          )
        : text;
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: widget.onTap,
        onDoubleTap: widget.onTap,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: double.infinity,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 3),
            child: Row(
              children: <Widget>[
                SizedBox(
                  width: 8,
                  height: 8,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: mutedColor.withValues(
                        alpha: animate ? 0.88 : 0.52,
                      ),
                      shape: BoxShape.circle,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Flexible(child: animatedText),
                const SizedBox(width: 4),
                Icon(
                  Icons.chevron_right_rounded,
                  size: 16,
                  color: mutedColor.withValues(alpha: 0.78),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _runTraceStatusLineLead({
  required ChatRunTraceData trace,
  required _RunTraceCompactPresentation presentation,
  required OpenCrayUiCopy copy,
}) {
  final String statusLabel = presentation.statusLabel.trim();
  if (trace.isTerminal || trace.isRetryable) {
    return statusLabel;
  }
  final String genericRunningLabel = copy.isChinese ? '运行中' : 'RUNNING';
  if (!_runTraceTextsMatch(statusLabel, genericRunningLabel)) {
    return statusLabel;
  }
  return presentation.activityLabel ?? (copy.isChinese ? '思考中' : 'Thinking');
}

String? _runTraceStatusLineDetail({
  required String lead,
  required _RunTraceCompactPresentation presentation,
}) {
  final List<String> candidates = <String>[
    presentation.headline,
    if (presentation.description != null) presentation.description!,
    ...presentation.detailLines.map((line) => line.value),
  ];
  final List<String> details = <String>[];
  for (final candidate in candidates) {
    final String text = candidate.trim();
    final bool placeholder = _runTraceThinkingPlaceholders.contains(text);
    if (text.isEmpty ||
        placeholder ||
        _runTraceTextsMatch(lead, text) ||
        _runTraceTextContains(text, lead) ||
        details.any(
          (detail) =>
              _runTraceTextsMatch(detail, text) ||
              _runTraceTextContains(detail, text) ||
              _runTraceTextContains(text, detail),
        )) {
      continue;
    }
    details.add(text);
  }
  if (details.isEmpty) {
    return null;
  }
  return details.join(' · ');
}

class _RunTracePreviewCard extends StatelessWidget {
  const _RunTracePreviewCard({
    super.key,
    required this.copy,
    required this.runId,
    required this.preview,
    this.bridge,
    this.expanded = false,
    this.keyNamespace = 'chat-run-trace-preview',
    this.onOpen,
    this.onCopy,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTracePreviewCardData preview;
  final OpenCrayHostBridge? bridge;
  final bool expanded;
  final String keyNamespace;
  final VoidCallback? onOpen;
  final VoidCallback? onCopy;

  @override
  Widget build(BuildContext context) {
    final _RunTracePreviewStatusStyle statusStyle = _runTracePreviewStatusStyle(
      preview.status,
    );
    final List<String> detailParts = <String>[
      if (preview.provider?.trim().isNotEmpty == true)
        preview.provider!.trim().toUpperCase(),
      if (preview.port != null) 'Port ${preview.port}',
      if (preview.path?.trim().isNotEmpty == true) preview.path!.trim(),
      if (preview.httpStatusCode != null) 'HTTP ${preview.httpStatusCode}',
    ];
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.runTracePreviewSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                const Icon(
                  Icons.cloud_outlined,
                  size: 16,
                  color: _ChatPalette.runTraceActivityText,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    copy.chatRunPreviewTitle,
                    style: _ChatTextStyles.cardTitle.copyWith(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 12),
                _RunTracePill(
                  label: _runTracePreviewStatusLabel(preview.status, copy),
                  foregroundColor: statusStyle.foregroundColor,
                  backgroundColor: statusStyle.backgroundColor,
                ),
              ],
            ),
            if (bridge != null) ...<Widget>[
              const SizedBox(height: 10),
              _EmbeddedSandboxPreviewSurface(
                key: ValueKey<String>('$keyNamespace-embedded-$runId'),
                copy: copy,
                runId: runId,
                preview: preview,
                bridge: bridge!,
                expanded: expanded,
                keyNamespace: keyNamespace,
              ),
            ],
            const SizedBox(height: 10),
            KeyedSubtree(
              key: ValueKey<String>('$keyNamespace-url-$runId'),
              child: _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: preview.url,
                bodyStyle: _ChatTextStyles.runTraceDetailValue.copyWith(
                  color: _ChatPalette.runTraceUrlText,
                  fontWeight: FontWeight.w600,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
                preferAccentForStrong: true,
              ),
            ),
            if (detailParts.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              if (expanded)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: detailParts
                      .map((detail) => _RunTracePreviewFactChip(label: detail))
                      .toList(growable: false),
                )
              else
                Text(
                  detailParts.join(' • '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: _ChatPalette.textSecondary,
                  ),
                ),
            ],
            if (preview.message?.trim().isNotEmpty == true) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: preview.message!.trim(),
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                _RunTracePreviewActionButton(
                  key: ValueKey<String>('$keyNamespace-open-$runId'),
                  label: copy.chatRunPreviewOpenAction,
                  icon: Icons.open_in_new_rounded,
                  emphasized: true,
                  onTap: onOpen,
                ),
                _RunTracePreviewActionButton(
                  key: ValueKey<String>('$keyNamespace-copy-$runId'),
                  label: copy.chatRunPreviewCopyUrlAction,
                  icon: Icons.content_copy_rounded,
                  onTap: onCopy,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _EmbeddedSandboxPreviewSurface extends StatefulWidget {
  const _EmbeddedSandboxPreviewSurface({
    super.key,
    required this.copy,
    required this.runId,
    required this.preview,
    required this.bridge,
    required this.expanded,
    required this.keyNamespace,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTracePreviewCardData preview;
  final OpenCrayHostBridge bridge;
  final bool expanded;
  final String keyNamespace;

  @override
  State<_EmbeddedSandboxPreviewSurface> createState() =>
      _EmbeddedSandboxPreviewSurfaceState();
}

class _EmbeddedSandboxPreviewSurfaceState
    extends State<_EmbeddedSandboxPreviewSurface> {
  WebViewController? _controller;
  String? _statusMessage;
  String? _activePreviewUrl;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _resolvePreview();
  }

  @override
  void didUpdateWidget(covariant _EmbeddedSandboxPreviewSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.preview.url != widget.preview.url ||
        oldWidget.bridge != widget.bridge) {
      _resolvePreview();
    }
  }

  Future<void> _resolvePreview() async {
    final String previewUrl = widget.preview.url.trim();
    if (previewUrl.isEmpty) {
      if (!mounted) {
        return;
      }
      setState(() {
        _controller = null;
        _statusMessage = widget.copy.chatRunPreviewEmbedUnavailable;
        _isLoading = false;
      });
      return;
    }
    setState(() {
      _activePreviewUrl = previewUrl;
      _controller = null;
      _statusMessage = null;
      _isLoading = true;
    });
    try {
      final OpenCraySandboxPreviewEmbedConfig config = await widget.bridge
          .resolveSandboxPreviewEmbedConfig(previewUrl);
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      if (!config.sessionMatched) {
        setState(() {
          _controller = null;
          _statusMessage =
              config.unavailableReason ??
              widget.copy.chatRunPreviewEmbedUnavailable;
          _isLoading = false;
        });
        return;
      }
      final WebViewController controller = WebViewController()
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..setBackgroundColor(_ChatPalette.runTracePreviewSurface)
        ..setNavigationDelegate(
          NavigationDelegate(
            onWebResourceError: (WebResourceError error) {
              if (!mounted || _activePreviewUrl != previewUrl) {
                return;
              }
              final String message = error.description.trim().isNotEmpty
                  ? error.description.trim()
                  : widget.copy.chatRunPreviewEmbedUnavailable;
              setState(() {
                _statusMessage = message;
              });
            },
          ),
        );
      await controller.loadRequest(
        Uri.parse(config.previewUrl),
        headers: config.headers,
      );
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      setState(() {
        _controller = controller;
        _statusMessage = null;
        _isLoading = false;
      });
    } catch (_) {
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      setState(() {
        _controller = null;
        _statusMessage = widget.copy.chatRunPreviewEmbedUnsupported;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final double aspectRatio = widget.expanded ? 1.45 : 1.65;
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: AspectRatio(
        aspectRatio: aspectRatio,
        child: DecoratedBox(
          decoration: const BoxDecoration(
            color: _ChatPalette.runTraceDetailSurface,
          ),
          child: _buildBody(),
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_controller != null && _statusMessage == null) {
      return KeyedSubtree(
        key: ValueKey<String>(
          '${widget.keyNamespace}-embedded-webview-${widget.runId}',
        ),
        child: WebViewWidget(controller: _controller!),
      );
    }
    final bool isLoading = _isLoading;
    final IconData icon = isLoading
        ? Icons.hourglass_bottom_rounded
        : Icons.public_off_outlined;
    final String message = isLoading
        ? widget.copy.chatRunPreviewEmbedLoading
        : (_statusMessage ?? widget.copy.chatRunPreviewEmbedUnavailable);
    return Container(
      key: ValueKey<String>(
        '${widget.keyNamespace}-embedded-unavailable-${widget.runId}',
      ),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      alignment: Alignment.center,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          if (isLoading)
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.2),
            )
          else
            Icon(icon, size: 22, color: _ChatPalette.textSecondary),
          const SizedBox(height: 10),
          Text(
            message,
            textAlign: TextAlign.center,
            maxLines: widget.expanded ? 3 : 2,
            overflow: TextOverflow.ellipsis,
            style: _ChatTextStyles.bodyMuted.copyWith(
              color: _ChatPalette.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _RunTraceSandboxSessionCard extends StatelessWidget {
  const _RunTraceSandboxSessionCard({
    super.key,
    required this.copy,
    required this.runId,
    required this.session,
    this.bridge,
    this.expanded = false,
    this.keyNamespace = 'chat-run-trace-session',
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTraceSandboxSessionCardData session;
  final OpenCrayHostBridge? bridge;
  final bool expanded;
  final String keyNamespace;

  @override
  Widget build(BuildContext context) {
    final _RunTraceSandboxSessionStatusStyle statusStyle =
        _runTraceSandboxSessionStatusStyle(session.lifecycleStatus);
    final List<String> detailParts = <String>[
      _runTraceSandboxSessionSourceLabel(session.source, copy),
      if (session.provider?.trim().isNotEmpty == true)
        session.provider!.trim().toUpperCase(),
      if (session.templateId?.trim().isNotEmpty == true)
        copy.chatRunSandboxSessionTemplate(session.templateId!.trim()),
      if (session.previewCandidatePorts.isNotEmpty)
        copy.chatRunSandboxSessionPorts(
          session.previewCandidatePorts.join(', '),
        ),
      if (session.runningRequestIds.isNotEmpty)
        copy.chatRunSandboxSessionRunningCount(
          session.runningRequestIds.length,
        ),
      if (session.lastPreviewProbeStatus != null)
        copy.chatRunSandboxSessionPreviewStatus(
          _runTracePreviewStatusLabel(session.lastPreviewProbeStatus!, copy),
        ),
    ];
    final String summary = session.sessionPresent
        ? (session.sandboxId?.trim().isNotEmpty == true
              ? session.sandboxId!.trim()
              : copy.chatRunSandboxSessionTitle)
        : copy.chatRunSandboxSessionMissing;
    final String? subtitle = session.sandboxDomain?.trim().isNotEmpty == true
        ? session.sandboxDomain!.trim()
        : null;
    final String? updatedLabel = _formatRunTraceSandboxSessionUpdated(
      copy,
      session.updatedAtEpochMs,
    );
    final String? lastActiveLabel = _formatRunTraceSandboxSessionLastActive(
      copy,
      session.sessionLastActivityAtEpochMs,
    );
    final String? staleAfterLabel = _formatRunTraceSandboxSessionStaleAfter(
      copy,
      session.sessionStaleAfterEpochMs,
    );
    final String? previewCheckedLabel =
        _formatRunTraceSandboxSessionPreviewChecked(
          copy,
          session.lastPreviewProbeObservedAtEpochMs,
        );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.runTracePreviewSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                const Icon(
                  Icons.cloud_sync_outlined,
                  size: 16,
                  color: _ChatPalette.runTraceActivityText,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    copy.chatRunSandboxSessionTitle,
                    style: _ChatTextStyles.cardTitle.copyWith(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 12),
                _RunTracePill(
                  label: _runTraceSandboxSessionLifecycleLabel(
                    session.lifecycleStatus,
                    copy,
                  ),
                  foregroundColor: statusStyle.foregroundColor,
                  backgroundColor: statusStyle.backgroundColor,
                ),
              ],
            ),
            const SizedBox(height: 10),
            KeyedSubtree(
              key: ValueKey<String>('$keyNamespace-summary-$runId'),
              child: _OpenCrayMarkdownTextBlock(
                key: ValueKey<String>('$keyNamespace-summary-markdown-$runId'),
                copy: copy,
                data: summary,
                bodyStyle: _ChatTextStyles.runTraceDetailValue.copyWith(
                  color: session.sessionPresent
                      ? _ChatPalette.textPrimary
                      : _ChatPalette.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
                preferAccentForStrong: true,
              ),
            ),
            if (subtitle != null) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: subtitle,
                bodyStyle: _ChatTextStyles.runTraceFooter.copyWith(
                  color: _ChatPalette.textSecondary,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (detailParts.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              if (expanded)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: detailParts
                      .map((detail) => _RunTracePreviewFactChip(label: detail))
                      .toList(growable: false),
                )
              else
                Text(
                  detailParts.join(' • '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: _ChatPalette.textSecondary,
                  ),
                ),
            ],
            if (updatedLabel != null) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: updatedLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (lastActiveLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: lastActiveLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (staleAfterLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: staleAfterLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (previewCheckedLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: previewCheckedLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (expanded && session.runningRequestIds.isNotEmpty) ...<Widget>[
              const SizedBox(height: 10),
              Text(
                copy.chatRunSandboxSessionRunningRequestsTitle,
                style: _ChatTextStyles.runTraceDetailLabel,
              ),
              const SizedBox(height: 6),
              Text(
                session.runningRequestIds.join(', '),
                style: _ChatTextStyles.runTraceFooter.copyWith(
                  color: _ChatPalette.textPrimary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _RunTracePreviewFactChip extends StatelessWidget {
  const _RunTracePreviewFactChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _ChatTextStyles.runTraceFooter.copyWith(
            color: _ChatPalette.textSecondary,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _RunTracePreviewActionButton extends StatelessWidget {
  const _RunTracePreviewActionButton({
    super.key,
    required this.label,
    required this.icon,
    this.emphasized = false,
    this.onTap,
  });

  final String label;
  final IconData icon;
  final bool emphasized;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    final Color foregroundColor = emphasized
        ? Colors.white
        : enabled
        ? _ChatPalette.runTraceActivityText
        : _ChatPalette.textSecondary;
    final Color backgroundColor = emphasized
        ? (enabled
              ? _ChatPalette.runTraceActivityText
              : _ChatPalette.runTraceActivityText.withValues(alpha: 0.32))
        : _ChatPalette.background;
    return Semantics(
      button: true,
      enabled: enabled,
      child: Listener(
        onPointerUp: enabled ? (_) => onTap!() : null,
        behavior: HitTestBehavior.opaque,
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: backgroundColor,
            shape: StadiumBorder(
              side: BorderSide(
                color: emphasized
                    ? Colors.transparent
                    : _ChatPalette.runTracePreviewBorder,
              ),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Icon(icon, size: 15, color: foregroundColor),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: foregroundColor,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _runTracePreviewStatusLabel(
  ChatRunTracePreviewStatus status,
  OpenCrayUiCopy copy,
) {
  switch (status) {
    case ChatRunTracePreviewStatus.ready:
      return copy.chatRunPreviewStatusReady;
    case ChatRunTracePreviewStatus.reachable:
      return copy.chatRunPreviewStatusReachable;
    case ChatRunTracePreviewStatus.unreachable:
      return copy.chatRunPreviewStatusUnreachable;
    case ChatRunTracePreviewStatus.skipped:
      return copy.chatRunPreviewStatusSkipped;
  }
}

String _runTraceSandboxSessionSourceLabel(
  ChatRunTraceSandboxSessionSource source,
  OpenCrayUiCopy copy,
) {
  switch (source) {
    case ChatRunTraceSandboxSessionSource.activeMemory:
      return copy.chatRunSandboxSessionSourceActive;
    case ChatRunTraceSandboxSessionSource.persisted:
      return copy.chatRunSandboxSessionSourcePersisted;
    case ChatRunTraceSandboxSessionSource.activeAndPersisted:
      return copy.chatRunSandboxSessionSourceActiveAndPersisted;
    case ChatRunTraceSandboxSessionSource.none:
      return copy.chatRunSandboxSessionSourceNone;
  }
}

String? _formatRunTraceSandboxSessionUpdated(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  if (epochMs == null || epochMs <= 0) {
    return null;
  }
  final DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(
    epochMs,
  ).toLocal();
  final DateTime now = DateTime.now().toLocal();
  final String clock = _formatChatClockLabel(copy, dateTime);
  final String label = _isSameChatDay(dateTime, now)
      ? clock
      : '${_formatChatDateLabel(copy, dateTime, includeYear: now.year != dateTime.year)} $clock';
  return copy.chatRunSandboxSessionUpdated(label);
}

String? _formatRunTraceSandboxSessionLastActive(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionLastActive(label);
}

String? _formatRunTraceSandboxSessionStaleAfter(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionStaleAfter(label);
}

String? _formatRunTraceSandboxSessionPreviewChecked(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionPreviewChecked(label);
}

String? _formatRunTraceSandboxSessionTimestamp(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  if (epochMs == null || epochMs <= 0) {
    return null;
  }
  final DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(
    epochMs,
  ).toLocal();
  final DateTime now = DateTime.now().toLocal();
  final String clock = _formatChatClockLabel(copy, dateTime);
  return _isSameChatDay(dateTime, now)
      ? clock
      : '${_formatChatDateLabel(copy, dateTime, includeYear: now.year != dateTime.year)} $clock';
}

String _runTraceSandboxSessionLifecycleLabel(
  ChatRunTraceSandboxSessionLifecycleStatus lifecycleStatus,
  OpenCrayUiCopy copy,
) {
  switch (lifecycleStatus) {
    case ChatRunTraceSandboxSessionLifecycleStatus.active:
      return copy.chatRunSandboxSessionLifecycleActive;
    case ChatRunTraceSandboxSessionLifecycleStatus.stale:
      return copy.chatRunSandboxSessionLifecycleStale;
    case ChatRunTraceSandboxSessionLifecycleStatus.reclaimed:
      return copy.chatRunSandboxSessionLifecycleReclaimed;
    case ChatRunTraceSandboxSessionLifecycleStatus.none:
      return copy.chatRunSandboxSessionLifecycleNone;
  }
}

_RunTracePreviewStatusStyle _runTracePreviewStatusStyle(
  ChatRunTracePreviewStatus status,
) {
  switch (status) {
    case ChatRunTracePreviewStatus.ready:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: Color(0xFF166534),
        backgroundColor: Color(0xFFE9F9EE),
      );
    case ChatRunTracePreviewStatus.reachable:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: Color(0xFF0F4C81),
        backgroundColor: Color(0xFFE8F3FF),
      );
    case ChatRunTracePreviewStatus.unreachable:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: Color(0xFF9A3412),
        backgroundColor: Color(0xFFFFF1E8),
      );
    case ChatRunTracePreviewStatus.skipped:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: Color(0xFF475569),
        backgroundColor: Color(0xFFF1F5F9),
      );
  }
}

_RunTraceSandboxSessionStatusStyle _runTraceSandboxSessionStatusStyle(
  ChatRunTraceSandboxSessionLifecycleStatus lifecycleStatus,
) {
  switch (lifecycleStatus) {
    case ChatRunTraceSandboxSessionLifecycleStatus.active:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: Color(0xFF166534),
        backgroundColor: Color(0xFFE9F9EE),
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.stale:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: Color(0xFF9A3412),
        backgroundColor: Color(0xFFFFF1E8),
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.reclaimed:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: Color(0xFF475569),
        backgroundColor: Color(0xFFF1F5F9),
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.none:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: Color(0xFF475569),
        backgroundColor: Color(0xFFF1F5F9),
      );
  }
}

class _RunTracePreviewStatusStyle {
  const _RunTracePreviewStatusStyle({
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final Color foregroundColor;
  final Color backgroundColor;
}

class _RunTraceSandboxSessionStatusStyle {
  const _RunTraceSandboxSessionStatusStyle({
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final Color foregroundColor;
  final Color backgroundColor;
}

const String _runTraceMainActorId = 'main';

class _RunTraceInspectorActorSection {
  const _RunTraceInspectorActorSection({
    required this.id,
    required this.label,
    required this.entries,
  });

  final String id;
  final String label;
  final List<ChatRunTraceHistoryEntry> entries;
}

class _RunTraceFullscreenSheet extends StatefulWidget {
  const _RunTraceFullscreenSheet({
    required this.copy,
    required this.traceListenable,
    required this.showSandboxPreviewCard,
    this.bridge,
    this.onRetry,
    this.isRetryBusy = false,
  });

  final OpenCrayUiCopy copy;
  final ValueListenable<ChatRunTraceData> traceListenable;
  final bool showSandboxPreviewCard;
  final OpenCrayHostBridge? bridge;
  final VoidCallback? onRetry;
  final bool isRetryBusy;

  @override
  State<_RunTraceFullscreenSheet> createState() =>
      _RunTraceFullscreenSheetState();
}

class _RunTraceFullscreenSheetState extends State<_RunTraceFullscreenSheet> {
  late final ScrollController _scrollController = ScrollController();
  late ChatRunTraceData _trace = widget.traceListenable.value;
  String? _selectedActorId;

  @override
  void initState() {
    super.initState();
    widget.traceListenable.addListener(_handleTraceChanged);
    _scheduleScrollToBottom();
  }

  @override
  void didUpdateWidget(covariant _RunTraceFullscreenSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.traceListenable != widget.traceListenable) {
      oldWidget.traceListenable.removeListener(_handleTraceChanged);
      _trace = widget.traceListenable.value;
      widget.traceListenable.addListener(_handleTraceChanged);
      _scheduleScrollToBottom();
    }
  }

  @override
  void dispose() {
    widget.traceListenable.removeListener(_handleTraceChanged);
    _scrollController.dispose();
    super.dispose();
  }

  void _handleTraceChanged() {
    final ChatRunTraceData nextTrace = widget.traceListenable.value;
    if (identical(_trace, nextTrace)) {
      return;
    }
    final int previousProcessEntryCount = _trace.history
        .where(_isRunTraceProcessEntry)
        .length;
    final int nextProcessEntryCount = nextTrace.history
        .where(_isRunTraceProcessEntry)
        .length;
    _runTraceDebug(
      'fullscreen.traceChanged run=${nextTrace.runId} history=${_trace.history.length}->${nextTrace.history.length} processEntries=$previousProcessEntryCount->$nextProcessEntryCount',
    );
    final bool shouldStickToBottom =
        !_scrollController.hasClients ||
        (_scrollController.position.maxScrollExtent -
                    _scrollController.position.pixels)
                .abs() <
            24;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || identical(_trace, nextTrace)) {
        return;
      }
      setState(() {
        _trace = nextTrace;
      });
      if (shouldStickToBottom) {
        _scheduleScrollToBottom();
      }
    });
  }

  Future<void> _openPreviewCard(ChatRunTracePreviewCardData preview) async {
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    try {
      await bridge.openExternalUri(preview.url);
    } catch (_) {
      await bridge.showNativeToast(widget.copy.markdownLinkOpenFailed);
    }
  }

  Future<void> _copyPreviewUrl(ChatRunTracePreviewCardData preview) async {
    await Clipboard.setData(ClipboardData(text: preview.url));
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge != null) {
      await bridge.showNativeToast(widget.copy.chatRunPreviewCopied);
    }
  }

  void _scheduleScrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) {
        return;
      }
      final ScrollPosition position = _scrollController.position;
      final double target = position.maxScrollExtent;
      if ((position.pixels - target).abs() < 0.5) {
        return;
      }
      _scrollController.jumpTo(target);
    });
  }

  List<_RunTraceInspectorActorSection> _buildActorSections(
    List<ChatRunTraceHistoryEntry> history,
  ) {
    final Map<String, _RunTraceInspectorActorSection> sections =
        <String, _RunTraceInspectorActorSection>{};
    for (final entry in history) {
      final String actorId = entry.inspectorActorId.trim().isNotEmpty
          ? entry.inspectorActorId.trim()
          : _runTraceMainActorId;
      final String actorLabel = entry.inspectorActorLabel.trim().isNotEmpty
          ? entry.inspectorActorLabel.trim()
          : entry.label;
      final _RunTraceInspectorActorSection? existing = sections[actorId];
      if (existing == null) {
        sections[actorId] = _RunTraceInspectorActorSection(
          id: actorId,
          label: actorLabel,
          entries: <ChatRunTraceHistoryEntry>[entry],
        );
        continue;
      }
      sections[actorId] = _RunTraceInspectorActorSection(
        id: existing.id,
        label: existing.label,
        entries: <ChatRunTraceHistoryEntry>[...existing.entries, entry],
      );
    }
    final List<_RunTraceInspectorActorSection> resolved = sections.values
        .toList(growable: false);
    final Map<String, int> totalsByLabel = <String, int>{};
    for (final section in resolved) {
      totalsByLabel.update(
        section.label,
        (count) => count + 1,
        ifAbsent: () => 1,
      );
    }
    final Map<String, int> seenByLabel = <String, int>{};
    return resolved
        .map((section) {
          final int total = totalsByLabel[section.label] ?? 1;
          if (total <= 1) {
            return section;
          }
          final int seen = seenByLabel.update(
            section.label,
            (count) => count + 1,
            ifAbsent: () => 1,
          );
          return _RunTraceInspectorActorSection(
            id: section.id,
            label: '${section.label} $seen',
            entries: section.entries,
          );
        })
        .toList(growable: false);
  }

  Widget _buildActorTabs(List<_RunTraceInspectorActorSection> sections) {
    final List<Widget> tabChildren = sections
        .map((section) {
          final bool selected =
              (_selectedActorId ?? sections.first.id) == section.id;
          return Padding(
            padding: const EdgeInsets.only(right: 16),
            child: GestureDetector(
              onTap: () {
                setState(() => _selectedActorId = section.id);
                _scheduleScrollToBottom();
              },
              behavior: HitTestBehavior.opaque,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text(
                  section.label,
                  style: _ChatTextStyles.timeline.copyWith(
                    color: selected
                        ? _ChatPalette.inspectorAction
                        : _ChatPalette.textSecondary,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ),
            ),
          );
        })
        .toList(growable: false);
    if (sections.length <= 1) {
      return Row(children: tabChildren);
    }
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: tabChildren),
    );
  }

  @override
  Widget build(BuildContext context) {
    final OpenCrayUiCopy copy = widget.copy;
    final ChatRunTraceData trace = _trace;
    final ChatRunTraceSandboxSessionCardData? session =
        widget.showSandboxPreviewCard ? trace.sessionCard : null;
    final ChatRunTracePreviewCardData? preview = widget.showSandboxPreviewCard
        ? trace.previewCard
        : null;
    final List<ChatRunTraceHistoryEntry> history = trace.history.isNotEmpty
        ? trace.history
        : <ChatRunTraceHistoryEntry>[
            ChatRunTraceHistoryEntry(
              label: trace.label,
              body: trace.body,
              isHighRisk: trace.isHighRisk,
            ),
          ];
    final List<_RunTraceInspectorActorSection> actorSections =
        _buildActorSections(history);
    final String selectedActorId =
        actorSections.any((section) => section.id == _selectedActorId)
        ? _selectedActorId!
        : actorSections.first.id;
    final List<ChatRunTraceHistoryEntry> visibleHistory = actorSections
        .firstWhere((section) => section.id == selectedActorId)
        .entries;
    final ChatRunTraceHistoryEntry? compactEntry = _resolveCompactRunTraceEntry(
      trace: trace,
      visibleHistory: history
          .where(
            (entry) =>
                !_runTraceThinkingPlaceholders.contains(entry.body.trim()),
          )
          .toList(growable: false),
    );
    final _RunTraceCompactPresentation compactPresentation =
        _buildRunTraceCompactPresentation(trace: trace, copy: copy);
    final bool showCompactSummaryCard =
        selectedActorId == _runTraceMainActorId &&
        compactEntry != null &&
        compactEntry.inspectorActorId != selectedActorId;
    final String? supplementalBody = _supplementalRunTraceBody(
      trace: trace,
      history: history,
    );
    final String inspectorTitle = copy.isChinese ? '运行检查' : 'Run inspector';
    final String summaryTitle = copy.isChinese
        ? (actorSections.length > 1 ? '代理检查器' : '代理检查')
        : (actorSections.length > 1 ? 'Agent inspectors' : 'Agent inspector');
    final String summaryBody = actorSections.length > 1
        ? (copy.isChinese
              ? '用顶部标签切换不同代理的检查记录，不要把子代理细节混在同一条滚动里。'
              : 'Use tabs to switch the current inspector instead of mixing child details into one scroll.')
        : (copy.isChinese
              ? '当前运行细节会按工具调用和结果分组展示。'
              : 'Current run details are grouped by tool call and result.');
    final Color containerBorderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : _ChatPalette.runTraceBorder;
    return Dialog.fullscreen(
      key: ValueKey<String>('chat-run-trace-fullscreen-${trace.runId}'),
      backgroundColor: _ChatPalette.background,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Expanded(
                    child: Text(
                      inspectorTitle,
                      style: _ChatTextStyles.runInspectorTitle,
                    ),
                  ),
                  if (trace.isRetryable && widget.onRetry != null) ...<Widget>[
                    const SizedBox(width: 12),
                    _RunTraceActionButton(
                      label: widget.isRetryBusy
                          ? '${trace.retryLabel!}...'
                          : trace.retryLabel!,
                      onTap: widget.isRetryBusy ? null : widget.onRetry,
                    ),
                  ],
                  const SizedBox(width: 4),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    behavior: HitTestBehavior.opaque,
                    child: const Padding(
                      padding: EdgeInsets.all(4),
                      child: Icon(
                        Icons.close_rounded,
                        size: 20,
                        color: _ChatPalette.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: containerBorderColor),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Row(
                              children: <Widget>[
                                Expanded(
                                  child: Text(
                                    summaryTitle,
                                    style: _ChatTextStyles.cardTitle,
                                  ),
                                ),
                                const SizedBox(width: 12),
                                _RunTracePill(
                                  label: compactPresentation.statusLabel,
                                  foregroundColor: trace.isHighRisk
                                      ? _ChatPalette.highRiskAccent
                                      : _ChatPalette.runTraceStatusText,
                                  backgroundColor: trace.isHighRisk
                                      ? _ChatPalette.highRiskBadgeSurface
                                      : _ChatPalette.runTraceStatusSurface,
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Text(summaryBody, style: _ChatTextStyles.bodyMuted),
                            if (showCompactSummaryCard) ...<Widget>[
                              const SizedBox(height: 12),
                              Text(
                                compactPresentation.headline,
                                style: _ChatTextStyles.runTraceHeadline
                                    .copyWith(fontSize: 18),
                              ),
                              if (compactPresentation.description !=
                                  null) ...<Widget>[
                                const SizedBox(height: 8),
                                _OpenCrayMarkdownTextBlock(
                                  copy: copy,
                                  data: compactPresentation.description!,
                                  bodyStyle: _ChatTextStyles.bodyMuted.copyWith(
                                    color: _ChatPalette.textPrimary,
                                  ),
                                  surfaceColor: Colors.white,
                                  bridge: widget.bridge,
                                ),
                              ],
                              if (compactPresentation
                                  .detailLines
                                  .isNotEmpty) ...<Widget>[
                                const SizedBox(height: 10),
                                ...compactPresentation.detailLines.map(
                                  (line) => Padding(
                                    padding: const EdgeInsets.only(bottom: 6),
                                    child: RichText(
                                      text: TextSpan(
                                        children: <InlineSpan>[
                                          TextSpan(
                                            text:
                                                '${line.label}${copy.isChinese ? '  ' : '  '}',
                                            style: _ChatTextStyles
                                                .runTraceDetailLabel,
                                          ),
                                          WidgetSpan(
                                            alignment:
                                                PlaceholderAlignment.baseline,
                                            baseline: TextBaseline.alphabetic,
                                            child: _OpenCrayMarkdownTextBlock(
                                              copy: copy,
                                              data: line.value,
                                              bodyStyle: _ChatTextStyles
                                                  .runTraceDetailValue,
                                              surfaceColor: Colors.white,
                                              bridge: widget.bridge,
                                              preferAccentForStrong: true,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: containerBorderColor),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
                        child: _buildActorTabs(actorSections),
                      ),
                      const Divider(
                        height: 1,
                        thickness: 1,
                        color: _ChatPalette.runTraceTabDivider,
                      ),
                      Expanded(
                        child: Scrollbar(
                          controller: _scrollController,
                          thumbVisibility: true,
                          child: SingleChildScrollView(
                            key: ValueKey<String>(
                              'chat-run-trace-fullscreen-scroll-${trace.runId}',
                            ),
                            controller: _scrollController,
                            primary: false,
                            padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
                            physics: const ClampingScrollPhysics(),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: <Widget>[
                                if (session != null) ...<Widget>[
                                  _RunTraceSandboxSessionCard(
                                    key: ValueKey<String>(
                                      'chat-run-trace-fullscreen-session-card-${trace.runId}',
                                    ),
                                    copy: copy,
                                    runId: trace.runId,
                                    session: session,
                                    bridge: widget.bridge,
                                    expanded: true,
                                    keyNamespace:
                                        'chat-run-trace-fullscreen-session',
                                  ),
                                  const SizedBox(height: 14),
                                ],
                                if (preview != null) ...<Widget>[
                                  _RunTracePreviewCard(
                                    key: ValueKey<String>(
                                      'chat-run-trace-fullscreen-preview-card-${trace.runId}',
                                    ),
                                    copy: copy,
                                    runId: trace.runId,
                                    preview: preview,
                                    bridge: widget.bridge,
                                    expanded: true,
                                    keyNamespace:
                                        'chat-run-trace-fullscreen-preview',
                                    onOpen: widget.bridge == null
                                        ? null
                                        : () => _openPreviewCard(preview),
                                    onCopy: () => _copyPreviewUrl(preview),
                                  ),
                                  const SizedBox(height: 14),
                                ],
                                ...visibleHistory.map(
                                  (entry) => Padding(
                                    padding: const EdgeInsets.only(bottom: 14),
                                    child: _RunTraceHistoryCard(
                                      copy: copy,
                                      entry: entry,
                                      bridge: widget.bridge,
                                    ),
                                  ),
                                ),
                                if (supplementalBody != null)
                                  DecoratedBox(
                                    decoration: BoxDecoration(
                                      color: _ChatPalette.runTraceDetailSurface,
                                      borderRadius: BorderRadius.circular(18),
                                    ),
                                    child: Padding(
                                      padding: const EdgeInsets.all(14),
                                      child: _OpenCrayMarkdownTextBlock(
                                        copy: copy,
                                        data: supplementalBody,
                                        bodyStyle: _ChatTextStyles.bodyMuted
                                            .copyWith(
                                              color: _ChatPalette.textPrimary,
                                            ),
                                        surfaceColor:
                                            _ChatPalette.runTraceDetailSurface,
                                        bridge: widget.bridge,
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RunTraceInlineInterruptAction extends StatelessWidget {
  const _RunTraceInlineInterruptAction({
    super.key,
    required this.label,
    required this.enabled,
    this.onTap,
  });

  final String label;
  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerUp: onTap == null ? null : (_) => onTap!(),
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.72,
        child: Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 4),
          child: Text(
            label,
            style: _ChatTextStyles.timeline.copyWith(
              color: _ChatPalette.runTraceInterruptAction,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

class _RunTraceInterruptConfirmRow extends StatefulWidget {
  const _RunTraceInterruptConfirmRow({
    super.key,
    required this.copy,
    required this.runId,
    required this.isBusy,
    this.onConfirmed,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final bool isBusy;
  final VoidCallback? onConfirmed;

  @override
  State<_RunTraceInterruptConfirmRow> createState() =>
      _RunTraceInterruptConfirmRowState();
}

class _RunTraceInterruptConfirmRowState
    extends State<_RunTraceInterruptConfirmRow> {
  static const double _horizontalInset = 6;
  static const double _thumbWidth = 42;
  static const double _confirmThreshold = 0.82;

  double _progress = 0;

  void _reset() {
    if (_progress == 0) {
      return;
    }
    setState(() {
      _progress = 0;
    });
  }

  void _updateProgress(DragUpdateDetails details, double travelDistance) {
    if (travelDistance <= 0) {
      return;
    }
    setState(() {
      _progress = (_progress - (details.delta.dx / travelDistance)).clamp(
        0.0,
        1.0,
      );
    });
  }

  void _finishGesture() {
    final bool confirmed = _progress >= _confirmThreshold;
    setState(() {
      _progress = 0;
    });
    if (confirmed) {
      widget.onConfirmed?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>('chat-run-trace-interrupt-slider-${widget.runId}'),
      height: 42,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final double travelDistance = math.max(
            0,
            constraints.maxWidth - (_horizontalInset * 2) - _thumbWidth,
          );
          final double thumbLeft =
              _horizontalInset + (1 - _progress) * travelDistance;
          return DecoratedBox(
            decoration: BoxDecoration(
              color: _ChatPalette.runTraceInterruptSurface,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: _ChatPalette.runTraceInterruptBorder),
            ),
            child: GestureDetector(
              onHorizontalDragUpdate: widget.isBusy
                  ? null
                  : (details) => _updateProgress(details, travelDistance),
              onHorizontalDragEnd: widget.isBusy
                  ? null
                  : (_) => _finishGesture(),
              onHorizontalDragCancel: widget.isBusy ? null : _reset,
              behavior: HitTestBehavior.opaque,
              child: Stack(
                children: <Widget>[
                  Positioned.fill(
                    child: Center(
                      child: AnimatedOpacity(
                        duration: const Duration(milliseconds: 120),
                        opacity: widget.isBusy
                            ? 1
                            : math.max(0.24, 1 - _progress),
                        child: Text(
                          widget.isBusy
                              ? widget.copy.chatRunInterruptBusy
                              : widget.copy.chatRunInterruptConfirmLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: _ChatTextStyles.timeline.copyWith(
                            color: widget.isBusy
                                ? _ChatPalette.runTraceInterruptAction
                                : _ChatPalette.textSecondary,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  ),
                  if (!widget.isBusy)
                    Positioned(
                      left: thumbLeft,
                      top: 4,
                      bottom: 4,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: _ChatPalette.runTraceInterruptAction,
                          borderRadius: BorderRadius.circular(999),
                          boxShadow: const <BoxShadow>[
                            BoxShadow(
                              color: Color(0x1E0F172A),
                              blurRadius: 10,
                              offset: Offset(0, 4),
                            ),
                          ],
                        ),
                        child: SizedBox(
                          width: _thumbWidth,
                          child: Center(
                            child: Icon(
                              widget.copy.isChinese
                                  ? Icons.keyboard_double_arrow_left_rounded
                                  : Icons.keyboard_double_arrow_left_rounded,
                              size: 18,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _RunTraceActionButton extends StatelessWidget {
  const _RunTraceActionButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.56,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0xFFE7EBF4),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFFD2D8E4)),
          ),
          child: Text(
            label,
            style: _ChatTextStyles.timeline.copyWith(
              color: _ChatPalette.textPrimary,
            ),
          ),
        ),
      ),
    );
  }
}

class _ToolInspectorCallDisplay {
  const _ToolInspectorCallDisplay({
    required this.text,
    required this.parts,
    this.detail,
  });

  final String text;
  final List<ChatRunTraceInspectorTextPart> parts;
  final String? detail;
}

class _ResolvedApprovalPreview {
  const _ResolvedApprovalPreview({required this.label, required this.body});

  final String label;
  final String body;
}

class _RunTraceCompactPresentation {
  const _RunTraceCompactPresentation({
    required this.statusLabel,
    required this.headline,
    required this.detailLines,
    this.activityLabel,
    this.description,
    this.footer,
  });

  final String statusLabel;
  final String? activityLabel;
  final String headline;
  final String? description;
  final List<_RunTraceCompactDetailLine> detailLines;
  final String? footer;
}

class _RunTraceCompactDetailLine {
  const _RunTraceCompactDetailLine({required this.label, required this.value});

  final String label;
  final String value;
}

class _RunTracePill extends StatelessWidget {
  const _RunTracePill({
    required this.label,
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final String label;
  final Color foregroundColor;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _ChatTextStyles.timeline.copyWith(
            color: foregroundColor,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

const Set<String> _runTraceThinkingPlaceholders = <String>{
  'Thinking',
  'Thinking…',
  'Thinking...',
  '思考中',
  '思考中…',
  '思考中...',
  'Analyzing the request and deciding the next step.',
  '正在分析请求，并决定下一步要做什么。',
};

ChatRunTraceHistoryEntry? _resolveCompactRunTraceEntry({
  required ChatRunTraceData trace,
  required List<ChatRunTraceHistoryEntry> visibleHistory,
}) {
  final String traceBody = trace.body.trim();
  if (traceBody.isNotEmpty) {
    for (int index = visibleHistory.length - 1; index >= 0; index -= 1) {
      final ChatRunTraceHistoryEntry candidate = visibleHistory[index];
      final String compactBody = _compactBodyForHistoryEntry(candidate);
      if (compactBody.isEmpty) {
        continue;
      }
      if (_runTraceTextsMatch(traceBody, compactBody) ||
          _runTraceTextContains(traceBody, compactBody)) {
        return candidate;
      }
    }
  }
  return visibleHistory.isEmpty ? null : visibleHistory.last;
}

_RunTraceCompactPresentation _buildRunTraceCompactPresentation({
  required ChatRunTraceData trace,
  required OpenCrayUiCopy copy,
}) {
  final List<ChatRunTraceHistoryEntry> visibleHistory = trace.history
      .where(
        (entry) => !_runTraceThinkingPlaceholders.contains(entry.body.trim()),
      )
      .toList(growable: false);
  if (trace.isWritingAssistantDraft && !trace.isTerminal) {
    return _RunTraceCompactPresentation(
      statusLabel: copy.isChinese ? '正在写回复' : 'WRITING REPLY',
      activityLabel: copy.isChinese ? '写回复' : 'Writing reply',
      headline: copy.isChinese ? '生成最终回答' : 'Writing final answer',
      description: null,
      detailLines: const <_RunTraceCompactDetailLine>[],
      footer: trace.history.isNotEmpty || trace.isRetryable
          ? (copy.isChinese
                ? '双击查看完整历史。'
                : 'Double tap to inspect the full history.')
          : null,
    );
  }
  final ChatRunTraceHistoryEntry? currentEntry = _resolveCompactRunTraceEntry(
    trace: trace,
    visibleHistory: visibleHistory,
  );
  final String? structuredDetail = currentEntry == null
      ? null
      : _compactStructuredInspectorDetail(currentEntry);
  final String? supplementalBody = _supplementalRunTraceBody(
    trace: trace,
    history: visibleHistory,
  );
  final List<String> currentSections = _splitRunTraceSections(
    currentEntry == null
        ? trace.body
        : _compactBodyForHistoryEntry(currentEntry),
  );
  final String? activityLabel = _runTraceActivityLabel(
    trace: trace,
    currentEntry: currentEntry,
    currentSections: currentSections,
    copy: copy,
  );
  final String statusLabel = _runTraceStatusLabel(
    trace: trace,
    activityLabel: activityLabel,
    copy: copy,
  );
  final String? resultSummary = _firstRunTraceLine(
    currentEntry?.inspectorResultBody ?? '',
  );
  final String headline =
      _firstNonEmptyRunTraceText(<String?>[
        currentSections.isEmpty ? null : currentSections.first,
        currentEntry?.hasStructuredInspectorContent == true
            ? _joinRunTraceInspectorParts(currentEntry!.inspectorCallParts)
            : null,
        _firstRunTraceLine(trace.body),
        trace.label,
      ]) ??
      trace.label;
  final String? processSummary = _recentRunTraceProcessSummary(
    history: visibleHistory,
    currentEntry: currentEntry,
    copy: copy,
  );
  final String? description = _firstDistinctRunTraceText(
    candidates: <String?>[
      processSummary,
      resultSummary,
      currentSections.length > 1 ? currentSections[1] : null,
      structuredDetail,
      supplementalBody,
    ],
    existing: <String>[headline],
  );
  final String? inputSummary = currentEntry == null
      ? null
      : _firstDistinctRunTraceText(
          candidates: <String?>[
            structuredDetail,
            _firstRunTraceLine(_compactBodyForHistoryEntry(currentEntry)),
          ],
          existing: <String>[headline, if (description != null) description],
        );

  final List<_RunTraceCompactDetailLine> detailLines =
      <_RunTraceCompactDetailLine>[
        if (processSummary != null &&
            !_runTraceTextsMatch(processSummary, description) &&
            !_runTraceTextsMatch(processSummary, headline) &&
            !_runTraceTextContains(processSummary, headline) &&
            !_runTraceTextContains(headline, processSummary))
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '过程' : 'Process',
            value: processSummary,
          ),
        if (inputSummary != null)
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '输入' : 'Input',
            value: inputSummary,
          ),
        if (resultSummary != null &&
            !_runTraceTextsMatch(resultSummary, description) &&
            !_runTraceTextsMatch(resultSummary, headline))
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '结果' : 'Result',
            value: resultSummary,
          ),
      ];

  if (detailLines.isEmpty) {
    final String? resultRemainder = _remainingRunTraceLines(
      currentEntry?.inspectorResultBody,
    );
    if (resultRemainder != null &&
        !_runTraceTextsMatch(resultRemainder, headline) &&
        !_runTraceTextsMatch(resultRemainder, description)) {
      detailLines.add(
        _RunTraceCompactDetailLine(
          label: copy.isChinese ? '预览' : 'Preview',
          value: resultRemainder,
        ),
      );
    }
    final List<String> fallbackSections = currentSections
        .where(
          (section) =>
              !_runTraceTextsMatch(section, headline) &&
              !_runTraceTextsMatch(section, description) &&
              !_runTraceTextContains(section, headline) &&
              !_runTraceTextContains(section, description) &&
              !_runTraceTextsMatch(section, resultRemainder) &&
              !_runTraceTextContains(section, resultSummary),
        )
        .toList(growable: false);
    for (
      int index = 0;
      index < fallbackSections.length && index < 2;
      index += 1
    ) {
      detailLines.add(
        _RunTraceCompactDetailLine(
          label: switch (detailLines.isEmpty ? index : index + 1) {
            0 => copy.isChinese ? '预览' : 'Preview',
            _ => copy.isChinese ? '说明' : 'Note',
          },
          value: fallbackSections[index],
        ),
      );
    }
  }

  return _RunTraceCompactPresentation(
    statusLabel: statusLabel,
    activityLabel: activityLabel,
    headline: headline,
    description: description,
    detailLines: detailLines.take(3).toList(growable: false),
    footer: trace.history.isNotEmpty || trace.isRetryable
        ? (copy.isChinese
              ? '双击查看完整历史。'
              : 'Double tap to inspect the full history.')
        : null,
  );
}

String? _recentRunTraceProcessSummary({
  required List<ChatRunTraceHistoryEntry> history,
  required ChatRunTraceHistoryEntry? currentEntry,
  required OpenCrayUiCopy copy,
}) {
  final bool hasProcessEntries = history.any(_isRunTraceProcessEntry);
  if (currentEntry != null && _isRunTraceProcessEntry(currentEntry)) {
    return _compactBodyForHistoryEntry(currentEntry);
  }
  final String? currentCompactBody = currentEntry == null
      ? null
      : _compactBodyForHistoryEntry(currentEntry);
  final int currentIndex = currentEntry == null
      ? -1
      : history.indexOf(currentEntry);
  for (
    int index = (currentIndex <= 0 ? history.length : currentIndex) - 1;
    index >= 0;
    index -= 1
  ) {
    final ChatRunTraceHistoryEntry candidate = history[index];
    if (identical(candidate, currentEntry)) {
      continue;
    }
    if (currentEntry != null &&
        currentEntry.inspectorActorId != _runTraceMainActorId &&
        candidate.inspectorActorId != currentEntry.inspectorActorId) {
      continue;
    }
    if (hasProcessEntries && !_isRunTraceProcessEntry(candidate)) {
      continue;
    }
    final String compactBody = _compactBodyForHistoryEntry(candidate);
    if (_runTraceThinkingPlaceholders.contains(compactBody)) {
      continue;
    }
    if (currentCompactBody != null &&
        (_runTraceTextsMatch(compactBody, currentCompactBody) ||
            _runTraceTextContains(compactBody, currentCompactBody) ||
            _runTraceTextContains(currentCompactBody, compactBody))) {
      continue;
    }
    return compactBody;
  }
  return null;
}

bool _isRunTraceProcessEntry(ChatRunTraceHistoryEntry entry) {
  final String label = entry.label.trim();
  return label.startsWith('Process ') || label.startsWith('进程 ');
}

String? _compactStructuredInspectorDetail(ChatRunTraceHistoryEntry entry) {
  if (!entry.hasStructuredInspectorContent) {
    return null;
  }
  final String detail = entry.inspectorCallDetail.trim();
  if (detail.isEmpty) {
    return null;
  }
  final List<String> lines = detail
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toList(growable: false);
  if (lines.length <= 1) {
    return detail;
  }
  for (final String line in lines) {
    final String normalized = line.toLowerCase();
    if (normalized.contains('[in_progress]') ||
        normalized.contains('active:') ||
        normalized.contains('当前动作')) {
      return detail;
    }
  }
  return detail;
}

String? _supplementalRunTraceBody({
  required ChatRunTraceData trace,
  required List<ChatRunTraceHistoryEntry> history,
}) {
  final String rawBody = trace.body.trim();
  if (rawBody.isEmpty || _runTraceThinkingPlaceholders.contains(rawBody)) {
    return null;
  }
  final List<String> rawSections = _splitRunTraceSections(rawBody);
  if (rawSections.isEmpty) {
    return null;
  }
  final List<String> representedTexts = history
      .expand(
        (entry) => <String>[
          entry.body,
          _compactBodyForHistoryEntry(entry),
          if (entry.inspectorCallParts.isNotEmpty)
            _joinRunTraceInspectorParts(entry.inspectorCallParts),
          entry.inspectorCallDetail,
          entry.inspectorResultBody,
        ],
      )
      .map((text) => text.trim())
      .where((text) => text.isNotEmpty)
      .toList(growable: false);
  final bool allSectionsCovered = rawSections.every(
    (section) => representedTexts.any(
      (text) =>
          _runTraceTextsMatch(text, section) ||
          _runTraceTextContains(text, section),
    ),
  );
  return allSectionsCovered ? null : rawBody;
}

String _runTraceStatusLabel({
  required ChatRunTraceData trace,
  required String? activityLabel,
  required OpenCrayUiCopy copy,
}) {
  final String normalizedLabel = trace.label.trim().toLowerCase();
  if (normalizedLabel == copy.chatRunWaitingApprovalLabel.toLowerCase() ||
      normalizedLabel == 'waiting for approval' ||
      normalizedLabel == 'approval required') {
    return copy.isChinese ? '等待中' : 'WAITING';
  }
  if (normalizedLabel == copy.chatRunAwaitingDirectionLabel.toLowerCase() ||
      normalizedLabel == 'awaiting direction') {
    return copy.isChinese ? '等待指示' : 'AWAITING';
  }
  if (trace.isRetryable || normalizedLabel.contains('interrupt')) {
    return copy.isChinese ? '已中断' : 'INTERRUPTED';
  }
  if (normalizedLabel.contains('cancel')) {
    return copy.isChinese ? '已取消' : 'CANCELLED';
  }
  final String? mappedActivity = _runTraceActivityStatusFromLabel(
    activityLabel,
    copy,
  );
  if (trace.isTerminal) {
    final String terminalActivity = mappedActivity?.trim().toLowerCase() ?? '';
    if (terminalActivity == 'failed' ||
        terminalActivity == '失败' ||
        terminalActivity == 'cancelled' ||
        terminalActivity == '已取消' ||
        terminalActivity == 'timed out' ||
        terminalActivity == '已超时') {
      return mappedActivity!;
    }
    return copy.isChinese ? '已完成' : 'FINISHED';
  }
  return mappedActivity ?? (copy.isChinese ? '运行中' : 'RUNNING');
}

String? _runTraceActivityLabel({
  required ChatRunTraceData trace,
  required ChatRunTraceHistoryEntry? currentEntry,
  required List<String> currentSections,
  required OpenCrayUiCopy copy,
}) {
  final String? candidateFromLabel = _normalizedRunTraceActivityLabel(
    trace.label,
  );
  if (candidateFromLabel != null &&
      !_runTraceTextsMatch(candidateFromLabel, copy.chatRunWorkingLabel) &&
      !_runTraceTextsMatch(
        candidateFromLabel,
        copy.chatRunWaitingApprovalLabel,
      ) &&
      !_runTraceTextsMatch(
        candidateFromLabel,
        copy.chatRunAwaitingDirectionLabel,
      )) {
    return candidateFromLabel;
  }
  final String? entryLabel = currentEntry == null
      ? null
      : _normalizedRunTraceActivityLabel(currentEntry.label);
  if (entryLabel != null &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunWorkingLabel) &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunWaitingApprovalLabel) &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunAwaitingDirectionLabel)) {
    return entryLabel;
  }
  final String? headline = currentSections.isEmpty
      ? null
      : currentSections.first;
  final String? firstWord = headline == null
      ? null
      : _firstRunTraceWord(headline);
  final String? normalizedFirstWord = _normalizedRunTraceActivityLabel(
    firstWord,
  );
  return _runTraceActivityStatusFromLabel(normalizedFirstWord, copy) == null
      ? null
      : normalizedFirstWord;
}

String? _runTraceActivityStatusFromLabel(String? label, OpenCrayUiCopy copy) {
  final String normalized = label?.trim().toLowerCase() ?? '';
  switch (normalized) {
    case 'finished':
    case 'finish':
    case 'success':
    case 'completed':
    case 'complete':
    case 'done':
      return copy.isChinese ? '已完成' : 'FINISHED';
    case 'failed':
    case 'failure':
    case 'error':
    case 'spawn_error':
      return copy.isChinese ? '失败' : 'FAILED';
    case 'cancelled':
    case 'canceled':
      return copy.isChinese ? '已取消' : 'CANCELLED';
    case 'timeout':
    case 'timed':
    case 'timedout':
      return copy.isChinese ? '已超时' : 'TIMED OUT';
    case 'read':
      return copy.isChinese ? '读取中' : 'READING';
    case 'write':
      return copy.isChinese ? '写入中' : 'WRITING';
    case 'edit':
    case 'multiedit':
      return copy.isChinese ? '编辑中' : 'EDITING';
    case 'ls':
      return copy.isChinese ? '查看中' : 'LISTING';
    case 'grep':
      return copy.isChinese ? '搜索中' : 'SEARCHING';
    case 'glob':
      return copy.isChinese ? '匹配中' : 'MATCHING';
    case 'todowrite':
      return copy.isChinese ? '整理中' : 'UPDATING';
    case 'task':
      return copy.isChinese ? '委派中' : 'DELEGATING';
    case 'memory':
      return copy.isChinese ? '记忆处理中' : 'MEMORY';
    case 'bash':
      return copy.isChinese ? '执行中' : 'RUNNING';
    default:
      return null;
  }
}

String? _normalizedRunTraceActivityLabel(String? label) {
  final String normalized = label?.trim() ?? '';
  if (normalized.isEmpty) {
    return null;
  }
  if (!RegExp(r'^[A-Za-z][A-Za-z0-9]+$').hasMatch(normalized)) {
    return null;
  }
  if (normalized.toUpperCase() == normalized) {
    return normalized;
  }
  return normalized[0].toUpperCase() + normalized.substring(1);
}

List<String> _splitRunTraceSections(String text) {
  return text
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split(RegExp(r'\n\s*\n'))
      .map((section) => section.trim())
      .where((section) => section.isNotEmpty)
      .toList(growable: false);
}

String _compactBodyForHistoryEntry(ChatRunTraceHistoryEntry entry) =>
    (entry.compactBody?.trim().isNotEmpty == true
            ? entry.compactBody!
            : entry.body)
        .trim();

String _joinRunTraceInspectorParts(List<ChatRunTraceInspectorTextPart> parts) =>
    parts.map((part) => part.text).join();

String? _firstRunTraceLine(String text) {
  final String normalized = text
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .trim();
  if (normalized.isEmpty) {
    return null;
  }
  return normalized.split('\n').first.trim();
}

String? _firstRunTraceWord(String text) {
  final Match? match = RegExp(r'[A-Za-z][A-Za-z0-9]+').firstMatch(text);
  return match?.group(0);
}

String? _remainingRunTraceLines(String? text) {
  final String normalized =
      text?.replaceAll('\r\n', '\n').replaceAll('\r', '\n').trim() ?? '';
  if (normalized.isEmpty) {
    return null;
  }
  final List<String> lines = normalized.split('\n');
  if (lines.length <= 1) {
    return null;
  }
  final String remainder = lines.sublist(1).join('\n').trim();
  return remainder.isEmpty ? null : remainder;
}

String? _firstNonEmptyRunTraceText(List<String?> values) {
  for (final String? value in values) {
    final String trimmed = value?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      return trimmed;
    }
  }
  return null;
}

String? _firstDistinctRunTraceText({
  required List<String?> candidates,
  required List<String> existing,
}) {
  for (final String? candidate in candidates) {
    final String trimmed = candidate?.trim() ?? '';
    if (trimmed.isEmpty) {
      continue;
    }
    final bool duplicate = existing.any(
      (existingText) =>
          _runTraceTextsMatch(existingText, trimmed) ||
          _runTraceTextContains(existingText, trimmed) ||
          _runTraceTextContains(trimmed, existingText),
    );
    if (!duplicate) {
      return trimmed;
    }
  }
  return null;
}

bool _runTraceTextsMatch(String? left, String? right) {
  String normalize(String? value) =>
      (value ?? '').toLowerCase().replaceAll(RegExp(r'\s+'), ' ').trim();
  return normalize(left).isNotEmpty && normalize(left) == normalize(right);
}

bool _runTraceTextContains(String? source, String? fragment) {
  String normalize(String? value) =>
      (value ?? '').toLowerCase().replaceAll(RegExp(r'\s+'), ' ').trim();
  final String normalizedSource = normalize(source);
  final String normalizedFragment = normalize(fragment);
  if (normalizedSource.isEmpty || normalizedFragment.isEmpty) {
    return false;
  }
  return normalizedSource.contains(normalizedFragment);
}

class _RunTraceHistoryCard extends StatelessWidget {
  const _RunTraceHistoryCard({
    required this.copy,
    required this.entry,
    this.bridge,
  });

  final OpenCrayUiCopy copy;
  final ChatRunTraceHistoryEntry entry;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (entry.hasStructuredInspectorContent)
          _buildStructuredInspectorBody()
        else ...<Widget>[
          if (entry.label.trim().isNotEmpty) ...<Widget>[
            Text(
              entry.label,
              style: _ChatTextStyles.timeline.copyWith(
                color: entry.isHighRisk
                    ? _ChatPalette.highRiskAccent
                    : _ChatPalette.textSecondary,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 4),
          ],
          _OpenCrayMarkdownTextBlock(
            copy: copy,
            data: entry.body,
            bodyStyle: _ChatTextStyles.bubble.copyWith(
              color: _ChatPalette.textPrimary,
            ),
            surfaceColor: Colors.white,
            bridge: bridge,
          ),
        ],
      ],
    );
  }

  Widget _buildStructuredInspectorBody() {
    final List<Widget> children = <Widget>[
      RichText(
        text: TextSpan(
          children: entry.inspectorCallParts
              .map(
                (part) => TextSpan(
                  text: part.text,
                  style: _ChatTextStyles.runInspectorLog.copyWith(
                    color: _inspectorSemanticColor(part.semantic),
                  ),
                ),
              )
              .toList(growable: false),
        ),
      ),
    ];
    final String inspectorCallDetail = entry.inspectorCallDetail.trim();
    if (inspectorCallDetail.isNotEmpty) {
      children.add(const SizedBox(height: 4));
      children.add(
        _OpenCrayMarkdownTextBlock(
          copy: copy,
          data: inspectorCallDetail,
          bodyStyle: _ChatTextStyles.runInspectorDetail.copyWith(
            color: _ChatPalette.textSecondary,
          ),
          surfaceColor: Colors.white,
          bridge: bridge,
        ),
      );
    }
    final String inspectorResultBody = entry.inspectorResultBody.trim();
    if (inspectorResultBody.isNotEmpty) {
      children.add(const SizedBox(height: 6));
      children.add(_buildInspectorResultText(inspectorResultBody));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }

  Widget _buildInspectorResultText(String body) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text('└', style: _ChatTextStyles.runInspectorResultBranch),
        const SizedBox(width: 8),
        Expanded(
          child: _OpenCrayMarkdownTextBlock(
            copy: copy,
            data: body,
            bodyStyle: _ChatTextStyles.runInspectorResult,
            surfaceColor: Colors.white,
            bridge: bridge,
          ),
        ),
      ],
    );
  }

  Color _inspectorSemanticColor(ChatRunTraceInspectorTextSemantic semantic) {
    return switch (semantic) {
      ChatRunTraceInspectorTextSemantic.action => _ChatPalette.inspectorAction,
      ChatRunTraceInspectorTextSemantic.target => _ChatPalette.inspectorTarget,
      ChatRunTraceInspectorTextSemantic.scope => _ChatPalette.inspectorScope,
      ChatRunTraceInspectorTextSemantic.connector => _ChatPalette.textPrimary,
      ChatRunTraceInspectorTextSemantic.result => _ChatPalette.inspectorResult,
      ChatRunTraceInspectorTextSemantic.neutral => _ChatPalette.textPrimary,
    };
  }
}

class _ChatMessageMenuOverlay extends StatelessWidget {
  const _ChatMessageMenuOverlay({
    required this.copy,
    required this.menu,
    required this.onActionSelected,
  });

  final OpenCrayUiCopy copy;
  final _ActiveChatMessageMenu menu;
  final ValueChanged<_ChatMessageMenuAction> onActionSelected;

  @override
  Widget build(BuildContext context) {
    final bool useRedoAction = menu.showsRedo;
    final bool secondaryEnabled = useRedoAction ? menu.canRedo : menu.canRecall;
    final IconData secondaryIcon = useRedoAction
        ? Icons.redo_rounded
        : Icons.undo_rounded;
    final String secondaryLabel = useRedoAction
        ? copy.chatMessageRedoAction
        : copy.chatMessageRecallAction;
    final _ChatMessageMenuAction secondaryAction = useRedoAction
        ? _ChatMessageMenuAction.redo
        : _ChatMessageMenuAction.recall;
    final bool useBranchAction = !menu.isOutgoing;
    final bool tertiaryEnabled = useBranchAction
        ? menu.canBranch
        : menu.canEdit;
    final IconData tertiaryIcon = useBranchAction
        ? Icons.call_split_rounded
        : Icons.edit_rounded;
    final String tertiaryLabel = useBranchAction
        ? copy.chatMessageBranchAction
        : copy.chatMessageEditAction;
    final _ChatMessageMenuAction tertiaryAction = useBranchAction
        ? _ChatMessageMenuAction.branch
        : _ChatMessageMenuAction.edit;
    const double menuWidth = 202;
    const double menuHeight = 118;
    final Size screenSize = MediaQuery.sizeOf(context);
    final double minTop = MediaQuery.paddingOf(context).top + 44 + 12;
    final double unclampedLeft = menu.isOutgoing
        ? menu.bubbleRect.right - menuWidth
        : menu.bubbleRect.left;
    final double left = unclampedLeft.clamp(
      20.0,
      screenSize.width - menuWidth - 20,
    );
    final double top = (menu.bubbleRect.top - menuHeight - 8).clamp(
      minTop,
      screenSize.height - menuHeight - 12,
    );

    return Stack(
      children: <Widget>[
        Positioned(
          left: left,
          top: top,
          child: TweenAnimationBuilder<double>(
            duration: const Duration(milliseconds: 140),
            curve: Curves.easeOutCubic,
            tween: Tween<double>(begin: 0.94, end: 1),
            builder: (BuildContext context, double value, Widget? child) {
              return Opacity(
                opacity: value.clamp(0, 1),
                child: Transform.scale(scale: value, child: child),
              );
            },
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: Container(
                  key: ValueKey<String>(
                    'chat-message-menu-${menu.message.messageId}',
                  ),
                  width: menuWidth,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.92),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: const Color(0xCCFFFFFF)),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-copy',
                            ),
                            icon: CupertinoIcons.doc_on_doc,
                            label: copy.chatMessageCopyAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.copy),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${secondaryAction.name}',
                            ),
                            icon: secondaryIcon,
                            label: secondaryLabel,
                            enabled: secondaryEnabled,
                            onTap: secondaryEnabled
                                ? () => onActionSelected(secondaryAction)
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${tertiaryAction.name}',
                            ),
                            icon: tertiaryIcon,
                            label: tertiaryLabel,
                            enabled: tertiaryEnabled,
                            onTap: tertiaryEnabled
                                ? () => onActionSelected(tertiaryAction)
                                : null,
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: <Widget>[
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-delete',
                            ),
                            icon: CupertinoIcons.delete_left,
                            label: copy.chatMessageDeleteAction,
                            isDestructive: true,
                            enabled: menu.canDelete,
                            onTap: menu.canDelete
                                ? () => onActionSelected(
                                    _ChatMessageMenuAction.delete,
                                  )
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-multiSelect',
                            ),
                            icon: CupertinoIcons.check_mark_circled,
                            label: copy.chatMessageSelectAction,
                            onTap: () => onActionSelected(
                              _ChatMessageMenuAction.multiSelect,
                            ),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-quote',
                            ),
                            icon: CupertinoIcons.reply,
                            label: copy.chatMessageQuoteAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.quote),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ChatMessageMenuItem extends StatelessWidget {
  const _ChatMessageMenuItem({
    this.itemKey,
    required this.icon,
    required this.label,
    required this.onTap,
    this.enabled = true,
    this.isDestructive = false,
  });

  final Key? itemKey;
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool enabled;
  final bool isDestructive;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isDestructive
        ? const Color(0xFFFF3B30)
        : const Color(0xFF1C1C1E);
    final Color labelColor = isDestructive
        ? const Color(0xFFFF3B30)
        : const Color(0xFF636366);
    return GestureDetector(
      key: itemKey,
      onTap: enabled ? onTap : null,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.34,
        child: SizedBox(
          width: 52,
          height: 46,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Icon(icon, size: 17, color: foregroundColor),
              const SizedBox(height: 3),
              Text(
                label,
                style: _ChatTextStyles.messageMenuLabel.copyWith(
                  color: labelColor,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ChatRunTraceInlineActions extends StatefulWidget {
  const _ChatRunTraceInlineActions({
    required this.copy,
    required this.traces,
    this.showRetryActions = true,
    this.showInterruptActions = true,
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayUiCopy copy;
  final List<ChatRunTraceData> traces;
  final bool showRetryActions;
  final bool showInterruptActions;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  @override
  State<_ChatRunTraceInlineActions> createState() =>
      _ChatRunTraceInlineActionsState();
}

class _ChatRunTraceInlineActionsState
    extends State<_ChatRunTraceInlineActions> {
  bool _outsideDismissReady = false;

  ChatRunTraceData? get _confirmTrace {
    if (!widget.showInterruptActions) {
      return null;
    }
    final String confirmRunId = widget.interruptConfirmRunId?.trim() ?? '';
    if (confirmRunId.isEmpty) {
      return null;
    }
    for (final trace in widget.traces) {
      if (trace.interruptId == confirmRunId &&
          trace.canInterrupt &&
          !trace.isTerminal) {
        return trace;
      }
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ChatRunTraceInlineActions oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.interruptConfirmRunId != widget.interruptConfirmRunId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    if (_confirmTrace == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _confirmTrace == null) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final List<Widget> rows = <Widget>[];
    for (final trace in widget.traces) {
      final bool showConfirm = _confirmTrace == trace;
      final bool interruptBusy = widget.busyInterruptRunIds.contains(
        trace.interruptId,
      );
      final bool retryBusy = widget.busyRetryRunIds.contains(trace.retryId);
      final bool canShowInterrupt =
          widget.showInterruptActions &&
          !trace.isTerminal &&
          (trace.canInterrupt || showConfirm || interruptBusy) &&
          (widget.onArmInterruptRunTrace != null ||
              showConfirm ||
              interruptBusy);
      final bool canShowRetry =
          widget.showRetryActions &&
          trace.isRetryable &&
          widget.onRetryRunTrace != null;
      if (!canShowInterrupt && !canShowRetry) {
        continue;
      }
      if (showConfirm) {
        rows.add(
          _RunTraceInterruptConfirmRow(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-confirm-${trace.interruptId}',
            ),
            copy: widget.copy,
            runId: trace.interruptId,
            isBusy: interruptBusy,
            onConfirmed: widget.onInterruptRunTrace == null
                ? null
                : () => widget.onInterruptRunTrace!(trace),
          ),
        );
        continue;
      }
      final List<Widget> actions = <Widget>[];
      if (canShowRetry) {
        actions.add(
          _RunTraceActionButton(
            label: retryBusy ? '${trace.retryLabel!}...' : trace.retryLabel!,
            onTap: retryBusy ? null : () => widget.onRetryRunTrace!(trace),
          ),
        );
      }
      if (canShowInterrupt) {
        actions.add(
          _RunTraceInlineInterruptAction(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-${trace.interruptId}',
            ),
            label: interruptBusy
                ? widget.copy.chatRunInterruptBusy
                : widget.copy.chatRunInterruptAction,
            enabled: !interruptBusy,
            onTap: interruptBusy || widget.onArmInterruptRunTrace == null
                ? null
                : () => widget.onArmInterruptRunTrace!(trace),
          ),
        );
      }
      if (actions.isNotEmpty) {
        rows.add(Wrap(spacing: 10, runSpacing: 8, children: actions));
      }
    }
    if (rows.isEmpty) {
      return const SizedBox.shrink();
    }
    return TapRegion(
      onTapOutside: _confirmTrace != null && _outsideDismissReady
          ? (_) {
              final ChatRunTraceData? trace = _confirmTrace;
              if (trace != null) {
                widget.onDismissInterruptRunTrace?.call(trace);
              }
            }
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: rows
            .asMap()
            .entries
            .map(
              (entry) => Padding(
                padding: EdgeInsets.only(
                  bottom: entry.key == rows.length - 1 ? 0 : 8,
                ),
                child: entry.value,
              ),
            )
            .toList(growable: false),
      ),
    );
  }
}

class _ChatMessageBubble extends StatefulWidget {
  const _ChatMessageBubble({
    required this.bridge,
    required this.copy,
    required this.message,
    required this.voicePlaybackControllerFactory,
    required this.backgroundColor,
    required this.textColor,
    required this.maxWidth,
    required this.selectionMode,
    required this.onLongPress,
    required this.onTextSelectionChanged,
    this.attachedRunTraces = const <ChatRunTraceData>[],
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageData message;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;
  final bool selectionMode;
  final void Function(ChatMessageData, Rect, String?) onLongPress;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onTextSelectionChanged;
  final List<ChatRunTraceData> attachedRunTraces;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  @override
  State<_ChatMessageBubble> createState() => _ChatMessageBubbleState();
}

class _ChatMessageBubbleState extends State<_ChatMessageBubble> {
  static const Duration _menuDelay = Duration(milliseconds: 220);
  static const double _cancelDistance = 18;

  Timer? _longPressTimer;
  Offset? _pointerDownGlobalPosition;
  bool _didOpenMenu = false;
  String? _selectedText;
  String? _selectedTextAtPointerDown;

  @override
  void dispose() {
    _longPressTimer?.cancel();
    super.dispose();
  }

  void _startLongPressTimer(PointerDownEvent event) {
    _longPressTimer?.cancel();
    _pointerDownGlobalPosition = event.position;
    _didOpenMenu = false;
    _selectedTextAtPointerDown = _selectedText;
    _longPressTimer = Timer(_menuDelay, _openMenuFromCurrentBounds);
  }

  void _handlePointerMove(PointerMoveEvent event) {
    final Offset? origin = _pointerDownGlobalPosition;
    if (origin == null) {
      return;
    }
    if ((event.position - origin).distance > _cancelDistance) {
      _longPressTimer?.cancel();
    }
  }

  void _cancelLongPressTimer() {
    _longPressTimer?.cancel();
    _longPressTimer = null;
    _pointerDownGlobalPosition = null;
    _selectedTextAtPointerDown = null;
  }

  void _openMenuFromCurrentBounds() {
    if (!mounted || _didOpenMenu) {
      return;
    }
    final RenderObject? renderObject = context.findRenderObject();
    if (renderObject is! RenderBox) {
      return;
    }
    _didOpenMenu = true;
    widget.onLongPress(
      widget.message,
      renderObject.localToGlobal(Offset.zero) & renderObject.size,
      _selectedTextAtPointerDown ?? _selectedText,
    );
  }

  @override
  Widget build(BuildContext context) {
    final selectionTheme = chatBubbleSelectionTheme(widget.message.kind);
    final _ChatInlineAttachmentContent inlineBody =
        _buildChatInlineAttachmentContent(
          widget.message.text.trim(),
          widget.message.attachments,
        );
    final Set<String> inlineAttachmentIds = inlineBody.referencedAttachmentIds;
    final List<ChatMessageAttachmentData> imageAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind == ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final List<ChatMessageAttachmentData> otherAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind != ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final bool hasText = inlineBody.segments.isNotEmpty;
    final bool hasImages = imageAttachments.isNotEmpty;
    final bool hasOtherAttachments = otherAttachments.isNotEmpty;
    final Widget bubble = ConstrainedBox(
      key: ValueKey<String>('chat-bubble-${widget.message.messageId}'),
      constraints: BoxConstraints(maxWidth: widget.maxWidth),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: widget.backgroundColor,
          shape: const RoundedSuperellipseBorder(
            borderRadius: BorderRadius.all(Radius.circular(18)),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              if (hasText)
                _ChatBubbleMarkdownBody(
                  bridge: widget.bridge,
                  copy: widget.copy,
                  content: inlineBody,
                  textColor: widget.textColor,
                  backgroundColor: widget.backgroundColor,
                  messageId: widget.message.messageId,
                  contentMaxWidth: widget.maxWidth - 28,
                  selectionTheme: selectionTheme,
                  onSelectionChanged: (selection) {
                    _selectedText = selection?.plainText;
                    widget.onTextSelectionChanged(selection);
                  },
                  contextMenuBuilder:
                      (
                        BuildContext context,
                        SelectableRegionState selectableRegionState,
                        OpenCrayMarkdownSelectionSnapshot? selection,
                      ) => const SizedBox.shrink(),
                  voicePlaybackControllerFactory:
                      widget.voicePlaybackControllerFactory,
                  isOutgoing: widget.message.kind == ChatMessageKind.outbound,
                ),
              if (hasImages) ...<Widget>[
                if (hasText) const SizedBox(height: 10),
                _ChatImageAttachmentGroup(
                  bridge: widget.bridge,
                  messageId: widget.message.messageId,
                  attachments: imageAttachments,
                  maxWidth: widget.maxWidth - 28,
                  isOutgoing: widget.message.kind == ChatMessageKind.outbound,
                ),
              ],
              if (hasOtherAttachments) ...<Widget>[
                if (hasText || hasImages) const SizedBox(height: 10),
                ...otherAttachments.asMap().entries.map((entry) {
                  return Padding(
                    padding: EdgeInsets.only(
                      bottom: entry.key == otherAttachments.length - 1 ? 0 : 8,
                    ),
                    child: _ChatAttachmentTile(
                      bridge: widget.bridge,
                      copy: widget.copy,
                      attachment: entry.value,
                      voicePlaybackControllerFactory:
                          widget.voicePlaybackControllerFactory,
                      isOutgoing:
                          widget.message.kind == ChatMessageKind.outbound,
                    ),
                  );
                }),
              ],
              if (widget.attachedRunTraces.isNotEmpty) ...<Widget>[
                if (hasText || hasImages || hasOtherAttachments)
                  const SizedBox(height: 10),
                _ChatRunTraceInlineActions(
                  copy: widget.copy,
                  traces: widget.attachedRunTraces,
                  showInterruptActions: false,
                  interruptConfirmRunId: widget.interruptConfirmRunId,
                  busyInterruptRunIds: widget.busyInterruptRunIds,
                  busyRetryRunIds: widget.busyRetryRunIds,
                  onArmInterruptRunTrace: widget.onArmInterruptRunTrace,
                  onDismissInterruptRunTrace: widget.onDismissInterruptRunTrace,
                  onInterruptRunTrace: widget.onInterruptRunTrace,
                  onRetryRunTrace: widget.onRetryRunTrace,
                ),
              ],
            ],
          ),
        ),
      ),
    );
    if (widget.selectionMode) {
      return IgnorePointer(child: bubble);
    }
    return Listener(
      onPointerDown: _startLongPressTimer,
      onPointerMove: _handlePointerMove,
      onPointerUp: (_) => _cancelLongPressTimer(),
      onPointerCancel: (_) => _cancelLongPressTimer(),
      child: bubble,
    );
  }
}

final RegExp _chatAttachmentMarkdownReferencePattern = RegExp(
  r'(!?)\[([^\]]*)\]\((attachment:[^)]+)\)',
);

@immutable
class _ChatInlineAttachmentContent {
  const _ChatInlineAttachmentContent({
    required this.segments,
    required this.referencedAttachmentIds,
  });

  final List<_ChatInlineAttachmentSegment> segments;
  final Set<String> referencedAttachmentIds;
}

abstract class _ChatInlineAttachmentSegment {
  const _ChatInlineAttachmentSegment();
}

class _ChatInlineMarkdownSegment extends _ChatInlineAttachmentSegment {
  const _ChatInlineMarkdownSegment(this.markdown);

  final String markdown;
}

class _ChatInlineResolvedAttachmentSegment
    extends _ChatInlineAttachmentSegment {
  const _ChatInlineResolvedAttachmentSegment({
    required this.reference,
    required this.attachment,
  });

  final _ChatInlineAttachmentReference reference;
  final ChatMessageAttachmentData attachment;
}

@immutable
class _ChatInlineAttachmentReference {
  const _ChatInlineAttachmentReference({
    required this.raw,
    required this.label,
    required this.targetToken,
    required this.isImage,
  });

  final String raw;
  final String label;
  final String targetToken;
  final bool isImage;

  String get fallbackLabel {
    final String trimmedLabel = label.trim();
    if (trimmedLabel.isNotEmpty) {
      return trimmedLabel;
    }
    final String normalizedToken = targetToken.trim();
    if (normalizedToken.isEmpty) {
      return '';
    }
    final String stripped = normalizedToken
        .replaceAll('\\', '/')
        .split('/')
        .last
        .trim();
    return stripped;
  }
}

_ChatInlineAttachmentContent _buildChatInlineAttachmentContent(
  String text,
  List<ChatMessageAttachmentData> attachments,
) {
  final String normalizedText = text.trim();
  if (normalizedText.isEmpty) {
    return const _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[],
      referencedAttachmentIds: <String>{},
    );
  }
  final List<RegExpMatch> matches = _chatAttachmentMarkdownReferencePattern
      .allMatches(normalizedText)
      .toList();
  if (matches.isEmpty) {
    return _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[
        _ChatInlineMarkdownSegment(normalizedText),
      ],
      referencedAttachmentIds: <String>{},
    );
  }

  final List<_ChatInlineAttachmentSegment> segments =
      <_ChatInlineAttachmentSegment>[];
  final Set<String> referencedAttachmentIds = <String>{};
  int cursor = 0;

  void addMarkdownSegment(String chunk) {
    final String normalizedChunk = chunk.trim();
    if (normalizedChunk.isEmpty) {
      return;
    }
    segments.add(_ChatInlineMarkdownSegment(normalizedChunk));
  }

  for (final RegExpMatch match in matches) {
    addMarkdownSegment(normalizedText.substring(cursor, match.start));
    final _ChatInlineAttachmentReference reference =
        _chatInlineAttachmentReferenceFromMatch(match);
    final ChatMessageAttachmentData? attachment =
        _resolveChatInlineAttachmentReference(reference, attachments);
    if (attachment != null) {
      segments.add(
        _ChatInlineResolvedAttachmentSegment(
          reference: reference,
          attachment: attachment,
        ),
      );
      final String attachmentId = attachment.attachmentId.trim();
      if (attachmentId.isNotEmpty) {
        referencedAttachmentIds.add(attachmentId);
      }
    } else {
      addMarkdownSegment(reference.fallbackLabel);
    }
    cursor = match.end;
  }
  addMarkdownSegment(normalizedText.substring(cursor));

  return _ChatInlineAttachmentContent(
    segments: segments,
    referencedAttachmentIds: referencedAttachmentIds,
  );
}

_ChatInlineAttachmentReference _chatInlineAttachmentReferenceFromMatch(
  RegExpMatch match,
) {
  final String href = (match.group(3) ?? '')
      .trim()
      .split(' ')
      .first
      .trim()
      .replaceFirst(RegExp(r'^attachment:'), '')
      .replaceFirst(RegExp(r'^//'), '')
      .trim();
  return _ChatInlineAttachmentReference(
    raw: match.group(0) ?? '',
    label: (match.group(2) ?? '').trim(),
    targetToken: _normalizeChatAttachmentMarkdownToken(href),
    isImage: (match.group(1) ?? '') == '!',
  );
}

ChatMessageAttachmentData? _resolveChatInlineAttachmentReference(
  _ChatInlineAttachmentReference reference,
  List<ChatMessageAttachmentData> attachments,
) {
  if (attachments.isEmpty) {
    return null;
  }
  final List<ChatMessageAttachmentData> compatibleAttachments =
      reference.isImage
      ? attachments
            .where((attachment) => attachment.kind == ChatAttachmentKind.image)
            .toList(growable: false)
      : attachments;
  if (compatibleAttachments.isEmpty) {
    return null;
  }

  if (reference.targetToken.isNotEmpty && reference.targetToken != 'artifact') {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, reference.targetToken)) {
        return attachment;
      }
    }
  }

  final String labelToken = _normalizeChatAttachmentMarkdownToken(
    reference.label,
  );
  if (labelToken.isNotEmpty) {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, labelToken)) {
        return attachment;
      }
    }
  }

  if ((reference.targetToken.isEmpty || reference.targetToken == 'artifact') &&
      compatibleAttachments.length == 1) {
    return compatibleAttachments.single;
  }
  return null;
}

bool _chatAttachmentMatchesToken(
  ChatMessageAttachmentData attachment,
  String token,
) {
  final String normalizedToken = _normalizeChatAttachmentMarkdownToken(token);
  if (normalizedToken.isEmpty) {
    return false;
  }
  final String normalizedAttachmentId = _normalizeChatAttachmentMarkdownToken(
    attachment.attachmentId,
  );
  final String normalizedLocalPath = _normalizeChatAttachmentMarkdownToken(
    attachment.localPath,
  );
  final String normalizedDisplayName = _normalizeChatAttachmentMarkdownToken(
    attachment.displayName,
  );
  final String normalizedBaseName = normalizedLocalPath.contains('/')
      ? normalizedLocalPath.split('/').last
      : normalizedLocalPath;
  return normalizedToken == normalizedAttachmentId ||
      normalizedToken == normalizedLocalPath ||
      normalizedToken == normalizedDisplayName ||
      normalizedToken == normalizedBaseName;
}

String _normalizeChatAttachmentMarkdownToken(String value) => value
    .trim()
    .replaceAll('\\', '/')
    .replaceFirst(RegExp(r'^/'), '')
    .toLowerCase();

Future<void> _handleOpenCrayMarkdownLinkTap(
  BuildContext context, {
  required String? href,
  required OpenCrayUiCopy copy,
  OpenCrayHostBridge? bridge,
}) async {
  final String target = href?.trim() ?? '';
  if (target.isEmpty) {
    return;
  }
  final ScaffoldMessengerState? messenger = ScaffoldMessenger.maybeOf(context);
  try {
    final String? routeName = openCrayResolveMarkdownInternalRoute(target);
    if (routeName != null) {
      await Navigator.of(context).pushNamed(routeName);
      return;
    }
    final OpenCrayHostBridge? hostBridge = bridge;
    if (hostBridge == null) {
      throw StateError('Missing host bridge for markdown link target.');
    }
    final Uri? externalUri = openCrayResolveMarkdownExternalUri(target);
    if (externalUri != null) {
      await hostBridge.openExternalUri(externalUri.toString());
      return;
    }
    final Uri? uri = Uri.tryParse(target);
    if (_isWorkspaceRelativeChatLink(uri, target)) {
      final String relativePath = _normalizeChatWorkspaceRelativePath(target);
      if (_isPreviewableTextRelativePath(relativePath)) {
        final OpenCrayFileTextPreview preview = await hostBridge
            .loadWorkspaceTextPreview(relativePath);
        if (!context.mounted) {
          return;
        }
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
        return;
      }
      await hostBridge.openWorkspaceEntry(relativePath);
      return;
    }
    throw StateError('Unsupported markdown link target.');
  } catch (error) {
    if (!context.mounted) {
      return;
    }
    final String message = openCrayMarkdownLocalizedErrorMessage(
      error,
      copy,
      fallback: copy.chatMessageActionFailed,
    );
    messenger
      ?..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

bool _fontWeightIsEmphasized(FontWeight? fontWeight) {
  return fontWeight != null && fontWeight.index >= FontWeight.w600.index;
}

MarkdownStyleSheet _openCrayMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  required Color textColor,
  Color? linkColor,
  bool preferAccentForStrong = false,
  Color? strongAccentColor,
}) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final bool darkSurface =
      ThemeData.estimateBrightnessForColor(surfaceColor) == Brightness.dark;
  final Color resolvedLinkColor =
      linkColor ??
      (darkSurface
          ? const Color(0xFFDCEBFF)
          : Theme.of(context).colorScheme.primary);
  final Color resolvedStrongAccentColor =
      strongAccentColor ?? Theme.of(context).colorScheme.primary;
  final Color chromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.18)
      : Colors.black.withValues(alpha: 0.08);
  final Color subtleChromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.12)
      : Colors.black.withValues(alpha: 0.05);
  final bool accentStrong =
      preferAccentForStrong || _fontWeightIsEmphasized(bodyStyle.fontWeight);
  final TextStyle headingStyle = bodyStyle.copyWith(
    fontWeight: FontWeight.w700,
    height: 1.3,
  );
  return base.copyWith(
    a: bodyStyle.copyWith(
      color: resolvedLinkColor,
      fontWeight: FontWeight.w600,
      decoration: TextDecoration.underline,
      decorationThickness: 1.2,
      decorationColor: resolvedLinkColor.withValues(alpha: 0.75),
    ),
    p: bodyStyle,
    pPadding: EdgeInsets.zero,
    strong: accentStrong
        ? bodyStyle.copyWith(color: resolvedStrongAccentColor)
        : bodyStyle.copyWith(fontWeight: FontWeight.w700),
    em: bodyStyle.copyWith(fontStyle: FontStyle.italic),
    del: bodyStyle.copyWith(decoration: TextDecoration.lineThrough),
    h1: headingStyle.copyWith(fontSize: 20),
    h2: headingStyle.copyWith(fontSize: 18),
    h3: headingStyle.copyWith(fontSize: 16),
    h4: headingStyle.copyWith(fontSize: 15),
    h5: headingStyle.copyWith(fontSize: 14),
    h6: headingStyle.copyWith(fontSize: 14),
    listBullet: bodyStyle,
    code: TextStyle(
      fontSize: 13,
      height: 1.45,
      fontFamily: 'monospace',
      color: textColor,
    ),
    codeblockPadding: const EdgeInsets.all(10),
    codeblockDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: chromeColor),
    ),
    blockquote: bodyStyle.copyWith(
      color: textColor.withValues(alpha: darkSurface ? 0.88 : 0.82),
    ),
    blockquotePadding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
    blockquoteDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border(left: BorderSide(color: chromeColor, width: 3)),
    ),
    tableHead: bodyStyle.copyWith(fontWeight: FontWeight.w700),
    tableBody: bodyStyle,
    tableHeadAlign: TextAlign.left,
    tablePadding: const EdgeInsets.only(top: 2, bottom: 4),
    tableBorder: TableBorder.all(color: chromeColor),
    tableColumnWidth: const IntrinsicColumnWidth(),
    tableCellsPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
    tableCellsDecoration: BoxDecoration(color: subtleChromeColor),
    tableHeadCellsDecoration: BoxDecoration(color: chromeColor),
    horizontalRuleDecoration: BoxDecoration(
      border: Border(top: BorderSide(color: chromeColor)),
    ),
    blockSpacing: 10,
  );
}

MarkdownStyleSheet _runTraceMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  bool preferAccentForStrong = false,
}) {
  final Color textColor = bodyStyle.color ?? _ChatPalette.textPrimary;
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: surfaceColor,
    textColor: textColor,
    linkColor: _ChatPalette.inspectorAction,
    preferAccentForStrong: preferAccentForStrong,
    strongAccentColor: _ChatPalette.inspectorAction,
  );
}

class _OpenCrayMarkdownTextBlock extends StatelessWidget {
  const _OpenCrayMarkdownTextBlock({
    super.key,
    required this.copy,
    required this.data,
    required this.bodyStyle,
    required this.surfaceColor,
    this.bridge,
    this.preferAccentForStrong = false,
  });

  final OpenCrayUiCopy copy;
  final String data;
  final TextStyle bodyStyle;
  final Color surfaceColor;
  final OpenCrayHostBridge? bridge;
  final bool preferAccentForStrong;

  @override
  Widget build(BuildContext context) {
    final String markdown = data.trim();
    if (markdown.isEmpty) {
      return const SizedBox.shrink();
    }
    return OpenCrayMarkdownBody(
      data: markdown,
      hostBridge: bridge,
      onTapLink: (_, href, __) {
        unawaited(
          _handleOpenCrayMarkdownLinkTap(
            context,
            href: href,
            copy: copy,
            bridge: bridge,
          ),
        );
      },
      latexTextStyle: bodyStyle,
      styleSheet: _runTraceMarkdownStyleSheet(
        context,
        bodyStyle: bodyStyle,
        surfaceColor: surfaceColor,
        preferAccentForStrong: preferAccentForStrong,
      ),
      imageBackgroundColor: surfaceColor.withValues(alpha: 0.45),
      imageBorderColor: (bodyStyle.color ?? _ChatPalette.textPrimary)
          .withValues(alpha: 0.16),
    );
  }
}

class _ChatBubbleMarkdownBody extends StatelessWidget {
  const _ChatBubbleMarkdownBody({
    required this.bridge,
    required this.copy,
    required this.content,
    required this.textColor,
    required this.backgroundColor,
    required this.messageId,
    required this.contentMaxWidth,
    required this.selectionTheme,
    required this.onSelectionChanged,
    required this.contextMenuBuilder,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final _ChatInlineAttachmentContent content;
  final Color textColor;
  final Color backgroundColor;
  final String messageId;
  final double contentMaxWidth;
  final TextSelectionThemeData selectionTheme;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onSelectionChanged;
  final OpenCrayMarkdownContextMenuBuilder contextMenuBuilder;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  Future<void> _handleLinkTap(BuildContext context, String? href) async {
    await _handleOpenCrayMarkdownLinkTap(
      context,
      href: href,
      copy: copy,
      bridge: bridge,
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(
      color: textColor,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: content.segments
          .asMap()
          .entries
          .map((entry) {
            final _ChatInlineAttachmentSegment segment = entry.value;
            final Widget child;
            if (segment is _ChatInlineMarkdownSegment) {
              child = OpenCraySelectableMarkdownBody(
                key: ValueKey<String>(
                  'chat-bubble-markdown-$messageId-${entry.key}',
                ),
                data: segment.markdown,
                hostBridge: bridge,
                selectionTheme: selectionTheme,
                onSelectionChanged: onSelectionChanged,
                contextMenuBuilder: contextMenuBuilder,
                onTapLink: (_, href, __) {
                  unawaited(_handleLinkTap(context, href));
                },
                latexTextStyle: bodyStyle,
                styleSheet: _chatMarkdownStyleSheet(
                  context,
                  textColor: textColor,
                  backgroundColor: backgroundColor,
                ),
                imageBackgroundColor: backgroundColor.withValues(alpha: 0.12),
                imageBorderColor: textColor.withValues(alpha: 0.18),
              );
            } else if (segment is _ChatInlineResolvedAttachmentSegment) {
              if (segment.reference.isImage &&
                  segment.attachment.kind == ChatAttachmentKind.image) {
                child = _ChatImageAttachmentPreview(
                  bridge: bridge,
                  attachment: segment.attachment,
                  maxWidth: contentMaxWidth,
                  isOutgoing: isOutgoing,
                );
              } else {
                child = _ChatAttachmentTile(
                  bridge: bridge,
                  copy: copy,
                  attachment: segment.attachment,
                  voicePlaybackControllerFactory:
                      voicePlaybackControllerFactory,
                  isOutgoing: isOutgoing,
                );
              }
            } else {
              child = const SizedBox.shrink();
            }
            return Padding(
              padding: EdgeInsets.only(
                bottom: entry.key == content.segments.length - 1 ? 0 : 10,
              ),
              child: child,
            );
          })
          .toList(growable: false),
    );
  }
}

MarkdownStyleSheet _chatMarkdownStyleSheet(
  BuildContext context, {
  required Color textColor,
  required Color backgroundColor,
}) {
  final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(color: textColor);
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: backgroundColor,
    textColor: textColor,
  );
}

class _ChatImageAttachmentGroup extends StatelessWidget {
  const _ChatImageAttachmentGroup({
    required this.bridge,
    required this.messageId,
    required this.attachments,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final String messageId;
  final List<ChatMessageAttachmentData> attachments;
  final double maxWidth;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    if (attachments.isEmpty) {
      return const SizedBox.shrink();
    }
    final int columnCount = switch (attachments.length) {
      1 => 1,
      <= 4 => 2,
      _ => 3,
    };
    const double spacing = 6;
    final double contentWidth =
        (maxWidth - spacing * (columnCount - 1)) / columnCount;

    return Wrap(
      key: ValueKey<String>('chat-message-image-group-$messageId'),
      spacing: spacing,
      runSpacing: spacing,
      children: attachments
          .map((attachment) {
            return SizedBox(
              width: columnCount == 1 ? maxWidth : contentWidth,
              child: _ChatImageAttachmentPreview(
                bridge: bridge,
                attachment: attachment,
                maxWidth: columnCount == 1 ? maxWidth : contentWidth,
                isOutgoing: isOutgoing,
              ),
            );
          })
          .toList(growable: false),
    );
  }
}

class _ChatImageAttachmentPreview extends StatefulWidget {
  const _ChatImageAttachmentPreview({
    required this.bridge,
    required this.attachment,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final ChatMessageAttachmentData attachment;
  final double maxWidth;
  final bool isOutgoing;

  @override
  State<_ChatImageAttachmentPreview> createState() =>
      _ChatImageAttachmentPreviewState();
}

class _ChatImageAttachmentPreviewState
    extends State<_ChatImageAttachmentPreview> {
  Future<OpenCrayFileImagePreview>? _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _ChatImageAttachmentPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.attachment.localPath != widget.attachment.localPath ||
        oldWidget.bridge != widget.bridge) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OpenCrayFileImagePreview>? _loadPreview() {
    final bridge = widget.bridge;
    final localPath = widget.attachment.localPath.trim();
    if (bridge == null || localPath.isEmpty) {
      return null;
    }
    return bridge.loadWorkspaceImagePreview(localPath);
  }

  void _showFullscreenPreview(
    BuildContext context,
    OpenCrayFileImagePreview preview,
  ) {
    showDialog<void>(
      context: context,
      barrierColor: const Color(0xB3000000),
      builder: (dialogContext) {
        return Dialog(
          backgroundColor: Colors.transparent,
          insetPadding: const EdgeInsets.all(16),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: InteractiveViewer(
              child: ColoredBox(
                color: Colors.black,
                child: OpenCrayImageBytesView(
                  bytes: preview.bytes,
                  mimeType: preview.mimeType,
                  fit: BoxFit.contain,
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final Color borderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color placeholderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.14)
        : const Color(0xFFF3F4F7);
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    if (previewFuture == null) {
      return _ChatImageAttachmentPlaceholder(
        attachment: widget.attachment,
        maxWidth: widget.maxWidth,
        isOutgoing: widget.isOutgoing,
      );
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: previewFuture,
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        final double aspectRatio = ready
            ? preview.aspectRatio.clamp(0.65, 1.65).toDouble()
            : 1;
        return GestureDetector(
          key: ValueKey<String>(
            'chat-message-image-attachment-${widget.attachment.attachmentId}',
          ),
          onTap: ready ? () => _showFullscreenPreview(context, preview) : null,
          child: AspectRatio(
            aspectRatio: aspectRatio,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: placeholderColor,
                  border: Border.all(color: borderColor),
                ),
                child: ready
                    ? OpenCrayImageBytesView(
                        bytes: preview.bytes,
                        mimeType: preview.mimeType,
                        fit: BoxFit.cover,
                      )
                    : _ChatImageAttachmentPlaceholderBody(
                        attachment: widget.attachment,
                        isOutgoing: widget.isOutgoing,
                      ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatImageAttachmentPlaceholder extends StatelessWidget {
  const _ChatImageAttachmentPlaceholder({
    required this.attachment,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final ChatMessageAttachmentData attachment;
  final double maxWidth;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>(
        'chat-message-image-attachment-${attachment.attachmentId}',
      ),
      height: maxWidth.clamp(92, 180).toDouble(),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(14),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: isOutgoing
                ? Colors.white.withValues(alpha: 0.14)
                : const Color(0xFFF3F4F7),
            border: Border.all(
              color: isOutgoing
                  ? Colors.white.withValues(alpha: 0.22)
                  : _ChatPalette.border,
            ),
          ),
          child: _ChatImageAttachmentPlaceholderBody(
            attachment: attachment,
            isOutgoing: isOutgoing,
          ),
        ),
      ),
    );
  }
}

class _ChatImageAttachmentPlaceholderBody extends StatelessWidget {
  const _ChatImageAttachmentPlaceholderBody({
    required this.attachment,
    required this.isOutgoing,
  });

  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.9)
        : _ChatPalette.textSecondary;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(Icons.image_outlined, size: 22, color: foregroundColor),
            const SizedBox(height: 8),
            Text(
              attachment.displayName,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: _ChatTextStyles.attachmentLabel.copyWith(
                color: foregroundColor,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ChatAttachmentTile extends StatelessWidget {
  const _ChatAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  @override
  Widget build(BuildContext context) {
    if (attachment.kind == ChatAttachmentKind.voice) {
      return _ChatVoiceAttachmentTile(
        bridge: bridge,
        copy: copy,
        attachment: attachment,
        voicePlaybackControllerFactory: voicePlaybackControllerFactory,
        isOutgoing: isOutgoing,
      );
    }
    return _ChatFileAttachmentTile(
      bridge: bridge,
      copy: copy,
      attachment: attachment,
      isOutgoing: isOutgoing,
    );
  }
}

class _ChatFileAttachmentTile extends StatelessWidget {
  const _ChatFileAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;

  Future<void> _openAttachment(BuildContext context) async {
    final hostBridge = bridge;
    final localPath = attachment.localPath.trim();
    final messenger = ScaffoldMessenger.maybeOf(context);
    if (hostBridge == null || localPath.isEmpty) {
      return;
    }
    try {
      if (_isPreviewableTextAttachment(attachment)) {
        final preview = await hostBridge.loadWorkspaceTextPreview(localPath);
        if (!context.mounted) {
          return;
        }
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
        return;
      }
      await hostBridge.openWorkspaceEntry(localPath);
    } catch (_) {
      messenger
        ?..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(copy.chatMessageActionFailed)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool canOpen =
        bridge != null && attachment.localPath.trim().isNotEmpty;
    final Color surfaceColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.12)
        : const Color(0xFFF3F4F7);
    final Color borderColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color titleColor = isOutgoing
        ? Colors.white
        : _ChatPalette.textPrimary;
    final Color detailColor = isOutgoing
        ? Colors.white.withValues(alpha: 0.82)
        : _ChatPalette.textSecondary;

    return Ink(
      key: ValueKey<String>(
        'chat-message-attachment-${attachment.attachmentId}',
      ),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: borderColor),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: canOpen ? () => _openAttachment(context) : null,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: <Widget>[
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: isOutgoing
                        ? Colors.white.withValues(alpha: 0.18)
                        : Colors.white,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _isPreviewableTextAttachment(attachment)
                        ? Icons.article_outlined
                        : Icons.description_outlined,
                    size: 18,
                    color: titleColor,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        attachment.displayName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentLabel.copyWith(
                          color: titleColor,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        _chatAttachmentDetailText(attachment),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentDetail.copyWith(
                          color: detailColor,
                        ),
                      ),
                    ],
                  ),
                ),
                if (canOpen) ...<Widget>[
                  const SizedBox(width: 10),
                  Icon(
                    _isPreviewableTextAttachment(attachment)
                        ? Icons.visibility_outlined
                        : Icons.open_in_new_rounded,
                    size: 16,
                    color: detailColor,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatVoiceAttachmentTile extends StatefulWidget {
  const _ChatVoiceAttachmentTile({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  @override
  State<_ChatVoiceAttachmentTile> createState() =>
      _ChatVoiceAttachmentTileState();
}

class _ChatVoiceAttachmentTileState extends State<_ChatVoiceAttachmentTile> {
  late final ChatVoicePlaybackController _playbackController =
      (widget.voicePlaybackControllerFactory ??
      createDefaultChatVoicePlaybackController)();
  OpenCrayFileVoicePlaybackSource? _voiceSource;
  bool _isResolvingSource = false;
  bool _isTranscriptExpanded = false;
  double? _dragProgress;
  int _lastChildInteractionAtEpochMs = 0;

  @override
  void dispose() {
    unawaited(_playbackController.dispose());
    super.dispose();
  }

  Future<bool> _ensureVoiceSourceLoaded() async {
    final hostBridge = widget.bridge;
    final localPath = widget.attachment.localPath.trim();
    if (hostBridge == null || localPath.isEmpty || _isResolvingSource) {
      return false;
    }
    if (_voiceSource != null) {
      return true;
    }
    try {
      setState(() {
        _isResolvingSource = true;
      });
      final source = await hostBridge.loadWorkspaceVoicePlaybackSource(
        localPath,
      );
      if (!mounted) {
        return false;
      }
      await _playbackController.setSource(filePath: source.localFilePath);
      if (!mounted) {
        return false;
      }
      setState(() {
        _voiceSource = source;
        _isResolvingSource = false;
      });
      return true;
    } catch (_) {
      if (mounted) {
        setState(() {
          _isResolvingSource = false;
        });
      }
      _showPlaybackError();
      return false;
    }
  }

  Future<void> _togglePlayback() async {
    if (_shouldSuppressParentToggle()) {
      return;
    }
    if (!await _ensureVoiceSourceLoaded()) {
      return;
    }
    try {
      if (_playbackController.currentState.isPlaying) {
        await _playbackController.pause();
      } else {
        await _playbackController.play();
      }
    } catch (_) {
      _showPlaybackError();
    }
  }

  Future<void> _seekToFraction(double fraction, Duration totalDuration) async {
    if (totalDuration <= Duration.zero) {
      return;
    }
    if (!await _ensureVoiceSourceLoaded()) {
      return;
    }
    final Duration target = Duration(
      milliseconds: (totalDuration.inMilliseconds * fraction.clamp(0.0, 1.0))
          .round(),
    );
    try {
      await _playbackController.seek(target);
    } catch (_) {
      _showPlaybackError();
    }
  }

  void _startWaveformDrag(double fraction) {
    _recordChildInteraction();
    setState(() {
      _dragProgress = fraction.clamp(0.0, 1.0);
    });
  }

  void _updateWaveformDrag(double fraction) {
    setState(() {
      _dragProgress = fraction.clamp(0.0, 1.0);
    });
  }

  Future<void> _finishWaveformDrag(Duration totalDuration) async {
    final double? dragProgress = _dragProgress;
    setState(() {
      _dragProgress = null;
    });
    if (dragProgress == null) {
      return;
    }
    await _seekToFraction(dragProgress, totalDuration);
  }

  void _toggleTranscript() {
    _recordChildInteraction();
    setState(() {
      _isTranscriptExpanded = !_isTranscriptExpanded;
    });
  }

  void _recordChildInteraction() {
    _lastChildInteractionAtEpochMs = DateTime.now().millisecondsSinceEpoch;
  }

  bool _shouldSuppressParentToggle() {
    final int now = DateTime.now().millisecondsSinceEpoch;
    return now - _lastChildInteractionAtEpochMs <
        _chatVoiceChildInteractionSuppressionWindowMs;
  }

  void _showPlaybackError() {
    if (!mounted) {
      return;
    }
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(widget.copy.chatMessageActionFailed)),
      );
  }

  List<int> _effectiveWaveformBars() {
    if (widget.attachment.waveformBars.isNotEmpty) {
      return widget.attachment.waveformBars;
    }
    return _chatFallbackVoiceWaveformBars;
  }

  @override
  Widget build(BuildContext context) {
    final bool canPlay =
        widget.bridge != null && widget.attachment.localPath.trim().isNotEmpty;
    final Color surfaceColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.12)
        : const Color(0xFFF3F4F7);
    final Color borderColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.22)
        : _ChatPalette.border;
    final Color titleColor = widget.isOutgoing
        ? Colors.white
        : _ChatPalette.textPrimary;
    final Color detailColor = widget.isOutgoing
        ? Colors.white.withValues(alpha: 0.82)
        : _ChatPalette.textSecondary;

    return StreamBuilder<ChatVoicePlaybackSnapshot>(
      stream: _playbackController.snapshots,
      initialData: _playbackController.currentState,
      builder: (context, snapshot) {
        final ChatVoicePlaybackSnapshot playback =
            snapshot.data ?? const ChatVoicePlaybackSnapshot();
        final bool isBusy = _isResolvingSource || playback.isLoading;
        final Duration fallbackDuration = Duration(
          milliseconds:
              widget.attachment.durationMs ?? _voiceSource?.durationMs ?? 0,
        );
        final Duration totalDuration = playback.duration > Duration.zero
            ? playback.duration
            : fallbackDuration;
        final Duration currentPosition =
            totalDuration > Duration.zero && playback.position > totalDuration
            ? totalDuration
            : playback.position;
        final double currentProgress = totalDuration.inMilliseconds > 0
            ? currentPosition.inMilliseconds / totalDuration.inMilliseconds
            : 0;
        final bool showPlaybackClock =
            totalDuration > Duration.zero &&
            (currentPosition > Duration.zero || playback.isPlaying);
        final double progress = (_dragProgress ?? currentProgress)
            .clamp(0.0, 1.0)
            .toDouble();
        final String detailText =
            showPlaybackClock && totalDuration > Duration.zero
            ? '${_formatAttachmentDuration(currentPosition.inMilliseconds)} / ${_formatAttachmentDuration(totalDuration.inMilliseconds)}'
            : _chatAttachmentDetailText(widget.attachment);
        final String? transcriptText = (() {
          final String candidate =
              widget.attachment.transcriptText?.trim() ?? '';
          return candidate.isEmpty ? null : candidate;
        })();
        final bool shouldCollapseTranscript =
            transcriptText != null &&
            _shouldCollapseVoiceTranscript(transcriptText);
        final List<int> waveformBars = _effectiveWaveformBars();

        return Ink(
          key: ValueKey<String>(
            'chat-message-attachment-${widget.attachment.attachmentId}',
          ),
          decoration: BoxDecoration(
            color: surfaceColor,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: borderColor),
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: canPlay ? _togglePlayback : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                child: Row(
                  children: <Widget>[
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: widget.isOutgoing
                            ? Colors.white.withValues(alpha: 0.18)
                            : Colors.white,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Center(
                        child: isBusy
                            ? SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 1.8,
                                  color: titleColor,
                                ),
                              )
                            : Icon(
                                playback.isPlaying
                                    ? Icons.pause_rounded
                                    : Icons.play_arrow_rounded,
                                size: 18,
                                color: titleColor,
                              ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            widget.attachment.displayName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: _ChatTextStyles.attachmentLabel.copyWith(
                              color: titleColor,
                            ),
                          ),
                          const SizedBox(height: 3),
                          Text(
                            detailText,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: _ChatTextStyles.attachmentDetail.copyWith(
                              color: detailColor,
                            ),
                          ),
                          const SizedBox(height: 10),
                          _ChatVoiceWaveform(
                            key: ValueKey<String>(
                              'chat-message-attachment-waveform-${widget.attachment.attachmentId}',
                            ),
                            bars: waveformBars,
                            progress: progress,
                            playedColor: widget.isOutgoing
                                ? Colors.white
                                : _ChatPalette.accent,
                            unplayedColor: widget.isOutgoing
                                ? Colors.white.withValues(alpha: 0.18)
                                : const Color(0xFFDCE0E7),
                            onTapSeek: canPlay && totalDuration > Duration.zero
                                ? (fraction) {
                                    _recordChildInteraction();
                                    unawaited(
                                      _seekToFraction(fraction, totalDuration),
                                    );
                                  }
                                : null,
                            onDragStart:
                                canPlay && totalDuration > Duration.zero
                                ? _startWaveformDrag
                                : null,
                            onDragUpdate:
                                canPlay && totalDuration > Duration.zero
                                ? _updateWaveformDrag
                                : null,
                            onDragEnd: canPlay && totalDuration > Duration.zero
                                ? () {
                                    unawaited(
                                      _finishWaveformDrag(totalDuration),
                                    );
                                  }
                                : null,
                          ),
                          if (transcriptText != null) ...<Widget>[
                            const SizedBox(height: 8),
                            Text(
                              transcriptText,
                              key: ValueKey<String>(
                                'chat-message-attachment-transcript-${widget.attachment.attachmentId}',
                              ),
                              maxLines:
                                  shouldCollapseTranscript &&
                                      !_isTranscriptExpanded
                                  ? 2
                                  : null,
                              overflow:
                                  shouldCollapseTranscript &&
                                      !_isTranscriptExpanded
                                  ? TextOverflow.ellipsis
                                  : TextOverflow.visible,
                              style: _ChatTextStyles.attachmentDetail.copyWith(
                                color: detailColor,
                                height: 1.35,
                              ),
                            ),
                            if (shouldCollapseTranscript)
                              Padding(
                                padding: const EdgeInsets.only(top: 4),
                                child: GestureDetector(
                                  behavior: HitTestBehavior.opaque,
                                  onTap: _toggleTranscript,
                                  child: Text(
                                    _isTranscriptExpanded
                                        ? 'Hide transcript'
                                        : 'Show transcript',
                                    key: ValueKey<String>(
                                      'chat-message-attachment-transcript-toggle-${widget.attachment.attachmentId}',
                                    ),
                                    style: _ChatTextStyles.attachmentDetail
                                        .copyWith(
                                          color: titleColor,
                                          fontWeight: FontWeight.w600,
                                        ),
                                  ),
                                ),
                              ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatVoiceWaveform extends StatelessWidget {
  const _ChatVoiceWaveform({
    super.key,
    required this.bars,
    required this.progress,
    required this.playedColor,
    required this.unplayedColor,
    this.onTapSeek,
    this.onDragStart,
    this.onDragUpdate,
    this.onDragEnd,
  });

  final List<int> bars;
  final double progress;
  final Color playedColor;
  final Color unplayedColor;
  final ValueChanged<double>? onTapSeek;
  final ValueChanged<double>? onDragStart;
  final ValueChanged<double>? onDragUpdate;
  final VoidCallback? onDragEnd;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final double width = constraints.maxWidth.isFinite
            ? constraints.maxWidth
            : 0;
        double fractionFor(Offset localPosition) {
          if (width <= 0) {
            return 0;
          }
          return (localPosition.dx / width).clamp(0.0, 1.0);
        }

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: onTapSeek == null
              ? null
              : (details) => onTapSeek!(fractionFor(details.localPosition)),
          onHorizontalDragStart: onDragStart == null
              ? null
              : (details) => onDragStart!(fractionFor(details.localPosition)),
          onHorizontalDragUpdate: onDragUpdate == null
              ? null
              : (details) => onDragUpdate!(fractionFor(details.localPosition)),
          onHorizontalDragEnd: onDragEnd == null ? null : (_) => onDragEnd!(),
          child: SizedBox(
            height: 32,
            width: double.infinity,
            child: CustomPaint(
              painter: _ChatVoiceWaveformPainter(
                bars: bars,
                progress: progress,
                playedColor: playedColor,
                unplayedColor: unplayedColor,
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatVoiceWaveformPainter extends CustomPainter {
  const _ChatVoiceWaveformPainter({
    required this.bars,
    required this.progress,
    required this.playedColor,
    required this.unplayedColor,
  });

  final List<int> bars;
  final double progress;
  final Color playedColor;
  final Color unplayedColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (bars.isEmpty || size.width <= 0 || size.height <= 0) {
      return;
    }
    final double spacing = bars.length >= 40 ? 1.5 : 2.0;
    final double totalSpacing =
        spacing * math.max(0, bars.length - 1).toDouble();
    final double barWidth = (size.width - totalSpacing) / bars.length;
    if (barWidth <= 0) {
      return;
    }
    final Paint playedPaint = Paint()..color = playedColor;
    final Paint unplayedPaint = Paint()..color = unplayedColor;
    final double playedCutoff = (size.width * progress.clamp(0.0, 1.0))
        .clamp(0.0, size.width)
        .toDouble();
    double x = 0;
    for (final int value in bars) {
      final double normalized = (value.clamp(0, 100)) / 100.0;
      final double barHeight = math
          .max(4.0, size.height * math.max(0.16, normalized))
          .toDouble();
      final double top = (size.height - barHeight) / 2;
      final RRect barRect = RRect.fromRectAndRadius(
        Rect.fromLTWH(x, top, barWidth, barHeight),
        Radius.circular(barWidth / 2),
      );
      final double barCenter = x + (barWidth / 2);
      canvas.drawRRect(
        barRect,
        barCenter <= playedCutoff ? playedPaint : unplayedPaint,
      );
      x += barWidth + spacing;
    }
    final double handleX = playedCutoff.clamp(0.0, size.width).toDouble();
    canvas.drawCircle(
      Offset(handleX, size.height / 2),
      3.5,
      Paint()..color = playedColor,
    );
  }

  @override
  bool shouldRepaint(covariant _ChatVoiceWaveformPainter oldDelegate) =>
      oldDelegate.bars != bars ||
      oldDelegate.progress != progress ||
      oldDelegate.playedColor != playedColor ||
      oldDelegate.unplayedColor != unplayedColor;
}

bool _shouldCollapseVoiceTranscript(String text) =>
    text.length > 72 || text.contains('\n');

const List<int> _chatFallbackVoiceWaveformBars = <int>[
  20,
  34,
  28,
  44,
  36,
  58,
  42,
  52,
  38,
  48,
  34,
  46,
  30,
  40,
  26,
  36,
  24,
  32,
];

const int _chatVoiceChildInteractionSuppressionWindowMs = 250;

String _chatAttachmentDetailText(ChatMessageAttachmentData attachment) {
  final String kindLabel = switch (attachment.kind) {
    ChatAttachmentKind.image => 'Image',
    ChatAttachmentKind.voice => 'Voice',
    ChatAttachmentKind.file => 'File',
  };
  final List<String> parts = <String>[kindLabel];
  final String extension = attachment.displayName.contains('.')
      ? attachment.displayName.split('.').last.toUpperCase()
      : '';
  if (extension.isNotEmpty) {
    parts.add(extension);
  }
  if (attachment.sizeBytes != null && attachment.sizeBytes! >= 0) {
    parts.add(_formatAttachmentBytes(attachment.sizeBytes!));
  }
  if (attachment.durationMs != null && attachment.durationMs! > 0) {
    parts.add(_formatAttachmentDuration(attachment.durationMs!));
  }
  return parts.join(' · ');
}

String _formatAttachmentBytes(int sizeBytes) {
  const List<String> units = <String>['B', 'KB', 'MB', 'GB', 'TB'];
  double value = sizeBytes.toDouble();
  int unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  final String text = value >= 10 || unitIndex == 0
      ? value.toStringAsFixed(0)
      : value.toStringAsFixed(1);
  return '$text ${units[unitIndex]}';
}

String _formatAttachmentDuration(int durationMs) {
  final Duration duration = Duration(milliseconds: durationMs);
  final int minutes = duration.inMinutes;
  final int seconds = duration.inSeconds.remainder(60);
  return '${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
}

Future<void> _showChatTextPreviewDialog(
  BuildContext context,
  OpenCrayFileTextPreview preview, {
  OpenCrayHostBridge? bridge,
}) {
  return showDialog<void>(
    context: context,
    builder: (dialogContext) =>
        _ChatTextPreviewDialog(preview: preview, bridge: bridge),
  );
}

bool _isPreviewableTextAttachment(ChatMessageAttachmentData attachment) {
  final String normalizedMimeType =
      attachment.mimeType?.trim().toLowerCase() ?? '';
  if (normalizedMimeType.startsWith('text/') ||
      _chatPreviewableTextMimeTypes.contains(normalizedMimeType)) {
    return true;
  }
  return _isPreviewableTextFileName(attachment.displayName);
}

bool _isPreviewableTextRelativePath(String relativePath) =>
    _isPreviewableTextFileName(_chatRelativePathFileName(relativePath));

bool _isPreviewableTextFileName(String fileName) {
  final String normalizedName = fileName.trim().toLowerCase();
  if (normalizedName.isEmpty) {
    return false;
  }
  if (_chatPreviewableTextFileNames.contains(normalizedName)) {
    return true;
  }
  final String extension = normalizedName.contains('.')
      ? normalizedName.split('.').last
      : '';
  return _chatPreviewableTextExtensions.contains(extension);
}

bool _isWorkspaceRelativeChatLink(Uri? uri, String href) {
  if (href.trim().isEmpty) {
    return false;
  }
  if (uri != null && uri.hasScheme) {
    return false;
  }
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(href);
  return normalizedPath.isNotEmpty && !normalizedPath.startsWith('/');
}

String _normalizeChatWorkspaceRelativePath(String href) {
  final String trimmed = href.trim();
  if (trimmed.isEmpty) {
    return '';
  }
  final Uri? uri = Uri.tryParse(trimmed);
  final String path = uri != null && !uri.hasScheme ? uri.path : trimmed;
  return _safeDecodeChatLinkPath(path).replaceAll('\\', '/').trim();
}

String _chatRelativePathFileName(String relativePath) {
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(
    relativePath,
  );
  if (normalizedPath.isEmpty) {
    return '';
  }
  final int slashIndex = normalizedPath.lastIndexOf('/');
  if (slashIndex < 0) {
    return normalizedPath;
  }
  return normalizedPath.substring(slashIndex + 1);
}

String _safeDecodeChatLinkPath(String value) {
  try {
    return Uri.decodeFull(value);
  } on FormatException {
    return value;
  }
}

MarkdownStyleSheet _chatTextPreviewMarkdownStyleSheet(BuildContext context) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final Color linkColor = Theme.of(context).colorScheme.primary;
  return base.copyWith(
    a: TextStyle(
      fontSize: 13,
      height: 1.5,
      fontWeight: FontWeight.w600,
      color: linkColor,
      decoration: TextDecoration.underline,
      decorationColor: linkColor.withValues(alpha: 0.75),
    ),
    p: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    h1: const TextStyle(
      fontSize: 23,
      height: 1.2,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h2: const TextStyle(
      fontSize: 19,
      height: 1.24,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h3: const TextStyle(
      fontSize: 16,
      height: 1.3,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    listBullet: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    code: const TextStyle(
      fontSize: 12.5,
      height: 1.45,
      fontFamily: 'monospace',
      color: _ChatPalette.textPrimary,
    ),
    codeblockPadding: const EdgeInsets.all(12),
    codeblockDecoration: BoxDecoration(
      color: const Color(0xFFF0F1F5),
      borderRadius: BorderRadius.circular(12),
    ),
    blockSpacing: 14,
    blockquote: const TextStyle(
      fontSize: 12.5,
      height: 1.5,
      color: _ChatPalette.textSecondary,
    ),
    blockquoteDecoration: BoxDecoration(
      color: const Color(0xFFF3F4F8),
      borderRadius: BorderRadius.circular(12),
      border: const Border(
        left: BorderSide(color: _ChatPalette.border, width: 3),
      ),
    ),
    horizontalRuleDecoration: const BoxDecoration(
      border: Border(top: BorderSide(color: _ChatPalette.border, width: 1)),
    ),
  );
}

class _ChatTextPreviewDialog extends StatelessWidget {
  const _ChatTextPreviewDialog({required this.preview, this.bridge});

  final OpenCrayFileTextPreview preview;
  final OpenCrayHostBridge? bridge;

  Widget _buildMarkdownSelectionContextMenu(
    BuildContext context,
    SelectableRegionState selectableRegionState,
    OpenCrayMarkdownSelectionSnapshot? selection,
    String markdown,
  ) {
    final List<ContextMenuButtonItem> buttonItems = selectableRegionState
        .contextMenuButtonItems
        .map((item) {
          if (item.type != ContextMenuButtonType.copy) {
            return item;
          }
          return item.copyWith(
            onPressed: () async {
              final OpenCrayMarkdownClipboardPayload? payload =
                  openCrayBuildMarkdownSelectionClipboardPayload(
                    markdown,
                    selectedText: selection?.plainText ?? '',
                    selectionStartOffset: selection?.range?.startOffset,
                    selectionEndOffset: selection?.range?.endOffset,
                  );
              if (payload == null) {
                item.onPressed?.call();
                return;
              }
              final OpenCrayHostBridge? hostBridge = bridge;
              if (hostBridge == null) {
                await Clipboard.setData(ClipboardData(text: payload.plainText));
              } else {
                await hostBridge.copyRichTextToClipboard(
                  plainText: payload.plainText,
                  htmlText: payload.htmlText,
                );
              }
              openCrayFinalizeSelectionCopyUi(selectableRegionState);
            },
          );
        })
        .toList(growable: false);
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: selectableRegionState.contextMenuAnchors,
      buttonItems: buttonItems,
    );
  }

  @override
  Widget build(BuildContext context) {
    final String content = preview.isTruncated
        ? '${preview.content}\n\n...'
        : preview.content;
    return Dialog(
      key: const ValueKey<String>('chat-text-preview-dialog'),
      insetPadding: const EdgeInsets.all(20),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560, maxHeight: 560),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      preview.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: _ChatPalette.textPrimary,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close_rounded),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFF6F7FA),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: _ChatPalette.border),
                  ),
                  child: Scrollbar(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(14),
                      child: openCrayIsMarkdownFileName(preview.name)
                          ? OpenCraySelectableMarkdownBody(
                              key: const ValueKey<String>(
                                'chat-text-preview-markdown',
                              ),
                              data: content,
                              hostBridge: bridge,
                              documentRelativePath: preview.relativePath,
                              latexTextStyle: const TextStyle(
                                fontSize: 13,
                                height: 1.5,
                                color: _ChatPalette.textPrimary,
                              ),
                              styleSheet: _chatTextPreviewMarkdownStyleSheet(
                                context,
                              ),
                              imageBackgroundColor: const Color(0xFFEDEFF4),
                              imageBorderColor: _ChatPalette.border,
                              contextMenuBuilder:
                                  (
                                    BuildContext context,
                                    SelectableRegionState selectableRegionState,
                                    OpenCrayMarkdownSelectionSnapshot?
                                    selection,
                                  ) => _buildMarkdownSelectionContextMenu(
                                    context,
                                    selectableRegionState,
                                    selection,
                                    content,
                                  ),
                            )
                          : SelectionArea(
                              child: Text(
                                content,
                                style: const TextStyle(
                                  fontSize: 13,
                                  height: 1.5,
                                  color: _ChatPalette.textPrimary,
                                ),
                              ),
                            ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

const Set<String> _chatPreviewableTextFileNames = <String>{
  '.env',
  '.gitattributes',
  '.gitignore',
  'gradlew',
  'gradlew.bat',
  'license',
  'makefile',
  'readme',
  'readme.md',
};

const Set<String> _chatPreviewableTextExtensions = <String>{
  'bash',
  'conf',
  'config',
  'css',
  'csv',
  'dart',
  'htm',
  'html',
  'ini',
  'java',
  'js',
  'json',
  'jsx',
  'kt',
  'kts',
  'log',
  'markdown',
  'md',
  'properties',
  'py',
  'scss',
  'sh',
  'sql',
  'toml',
  'ts',
  'tsx',
  'txt',
  'xml',
  'yaml',
  'yml',
  'zsh',
};

const Set<String> _chatPreviewableTextMimeTypes = <String>{
  'application/json',
  'application/xml',
  'application/x-yaml',
};

const int _chatComposerMaxImageAttachments = 9;

class _ComposerCard extends StatelessWidget {
  const _ComposerCard({
    required this.copy,
    required this.state,
    required this.bridge,
    required this.controller,
    required this.focusNode,
    required this.onPlusPressed,
    required this.onSendPressed,
    required this.interruptTrace,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onAddActionSelected,
    required this.onCommandSelected,
    required this.onAttachmentRemoved,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final OpenCrayHostBridge? bridge;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;
  final ChatRunTraceData? interruptTrace;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatAddActionData> onAddActionSelected;
  final VoidCallback onCommandSelected;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    final bool hasTodos = state.composer.todos.isNotEmpty;
    final bool hasIntegratedSurface =
        state.composer.commandOptions.isNotEmpty ||
        state.composer.attachments.isNotEmpty ||
        state.composer.showAddMenu;

    final Widget content = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (hasTodos) ...<Widget>[
          _TodoListPanel(todos: state.composer.todos),
          const SizedBox(height: 12),
        ],
        if (state.composer.commandOptions.isNotEmpty) ...<Widget>[
          Container(
            decoration: BoxDecoration(
              color: _ChatPalette.subtleSurface,
              borderRadius: BorderRadius.circular(14),
            ),
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(copy.chatCommands, style: _ChatTextStyles.commandsLabel),
                const SizedBox(height: 8),
                ...state.composer.commandOptions.map(
                  (ChatCommandOptionData option) => _CommandOptionTile(
                    option: option,
                    onPressed: onCommandSelected,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (state.composer.attachments.isNotEmpty) ...<Widget>[
          SizedBox(
            height: 68,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemBuilder: (BuildContext context, int index) {
                return _AttachmentCard(
                  attachment: state.composer.attachments[index],
                  bridge: bridge,
                  onRemove: () =>
                      onAttachmentRemoved(state.composer.attachments[index]),
                );
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(width: 8);
              },
              itemCount: state.composer.attachments.length,
            ),
          ),
          const SizedBox(height: 10),
        ],
        AnimatedBuilder(
          animation: controller,
          builder: (BuildContext context, Widget? child) {
            final bool hasSendableContent =
                controller.text.trim().isNotEmpty ||
                state.composer.attachments.isNotEmpty;
            final ChatRunTraceData? effectiveInterruptTrace =
                state.isInputEnabled && !hasSendableContent
                ? interruptTrace
                : null;
            final bool showInterruptConfirm =
                effectiveInterruptTrace != null &&
                interruptConfirmRunId == effectiveInterruptTrace.interruptId;
            if (showInterruptConfirm) {
              return _ComposerInterruptConfirmSurface(
                copy: copy,
                trace: effectiveInterruptTrace,
                isBusy: busyInterruptRunIds.contains(
                  effectiveInterruptTrace.interruptId,
                ),
                onDismiss: () =>
                    onDismissInterruptRunTrace(effectiveInterruptTrace),
                onConfirmed: () => onInterruptRunTrace(effectiveInterruptTrace),
              );
            }
            return _InputRow(
              placeholder: state.composer.placeholder,
              controller: controller,
              focusNode: focusNode,
              enabled: state.isInputEnabled,
              hasIntegratedSurface: hasIntegratedSurface,
              showDefaultGlass: !hasIntegratedSurface && !hasTodos,
              plusHighlighted: hasIntegratedSurface,
              interruptTrace: effectiveInterruptTrace,
              isInterruptBusy:
                  effectiveInterruptTrace != null &&
                  busyInterruptRunIds.contains(
                    effectiveInterruptTrace.interruptId,
                  ),
              onInterruptPressed: effectiveInterruptTrace == null
                  ? null
                  : () => onArmInterruptRunTrace(effectiveInterruptTrace),
              onPlusPressed: onPlusPressed,
              onSendPressed: onSendPressed,
            );
          },
        ),
        if (state.composer.showAddMenu) ...<Widget>[
          const SizedBox(height: 10),
          Text(copy.chatAddToMessage, style: _ChatTextStyles.sectionLabel),
          const SizedBox(height: 10),
          Row(
            children: state.composer.addActions
                .map(
                  (ChatAddActionData action) => Expanded(
                    flex: action.label == copy.chatActionCommand ? 12 : 9,
                    child: Padding(
                      padding: EdgeInsets.only(
                        right: action == state.composer.addActions.last ? 0 : 8,
                      ),
                      child: _AddActionPill(
                        action: action,
                        onPressed: () => onAddActionSelected(action),
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
        ],
      ],
    );

    if (hasTodos) {
      return _ComposerGlassSurface(
        child: Padding(padding: const EdgeInsets.all(12), child: content),
      );
    }

    if (!hasIntegratedSurface) {
      return content;
    }

    return DecoratedBox(
      decoration: _ChatDecorations.card(),
      child: Padding(padding: const EdgeInsets.all(10), child: content),
    );
  }
}

class _ComposerInterruptConfirmSurface extends StatefulWidget {
  const _ComposerInterruptConfirmSurface({
    required this.copy,
    required this.trace,
    required this.isBusy,
    required this.onDismiss,
    required this.onConfirmed,
  });

  final OpenCrayUiCopy copy;
  final ChatRunTraceData trace;
  final bool isBusy;
  final VoidCallback onDismiss;
  final VoidCallback onConfirmed;

  @override
  State<_ComposerInterruptConfirmSurface> createState() =>
      _ComposerInterruptConfirmSurfaceState();
}

class _ComposerInterruptConfirmSurfaceState
    extends State<_ComposerInterruptConfirmSurface> {
  bool _outsideDismissReady = false;

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ComposerInterruptConfirmSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.trace.interruptId != widget.trace.interruptId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return TapRegion(
      onTapOutside: _outsideDismissReady ? (_) => widget.onDismiss() : null,
      child: _RunTraceInterruptConfirmRow(
        key: ValueKey<String>(
          'chat-composer-interrupt-confirm-${widget.trace.interruptId}',
        ),
        copy: widget.copy,
        runId: widget.trace.interruptId,
        isBusy: widget.isBusy,
        onConfirmed: widget.onConfirmed,
      ),
    );
  }
}

class _ComposerGlassSurface extends StatelessWidget {
  const _ComposerGlassSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: Stack(
        children: <Widget>[
          Positioned.fill(
            child: IgnorePointer(
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: DecoratedBox(
                  key: const ValueKey<String>('chat-composer-todo-surface'),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.42),
                    ),
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[
                        Colors.white.withValues(alpha: 0.34),
                        Colors.white.withValues(alpha: 0.22),
                        const Color(0xFFEAF2FF).withValues(alpha: 0.18),
                        const Color(0xFFCFE1FF).withValues(alpha: 0.12),
                      ],
                      stops: const <double>[0, 0.28, 0.72, 1],
                    ),
                  ),
                  child: const SizedBox.expand(),
                ),
              ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}

class _TodoListPanel extends StatefulWidget {
  const _TodoListPanel({required this.todos});

  static const int _maxVisibleTodoCount = 4;
  static const double _itemHeight = 28;
  static const double _itemGap = 6;

  final List<ChatTodoItemData> todos;

  @override
  State<_TodoListPanel> createState() => _TodoListPanelState();
}

class _TodoListPanelState extends State<_TodoListPanel> {
  bool _isExpanded = true;

  @override
  Widget build(BuildContext context) {
    final int visibleCount = math.min(
      widget.todos.length,
      _TodoListPanel._maxVisibleTodoCount,
    );
    final double listHeight =
        (visibleCount * _TodoListPanel._itemHeight) +
        (visibleCount > 0 ? (visibleCount - 1) * _TodoListPanel._itemGap : 0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Row(
          children: <Widget>[
            Text('TODO', style: _ChatTextStyles.todoLabel),
            const Spacer(),
            GestureDetector(
              key: const ValueKey<String>('chat-composer-todo-chevron'),
              behavior: HitTestBehavior.opaque,
              onTap: () {
                setState(() {
                  _isExpanded = !_isExpanded;
                });
              },
              child: SizedBox.square(
                dimension: 24,
                child: Center(
                  child: AnimatedRotation(
                    duration: const Duration(milliseconds: 180),
                    turns: _isExpanded ? 0.5 : 0,
                    child: const Icon(
                      CupertinoIcons.chevron_up,
                      size: 13,
                      color: _ChatPalette.textTertiary,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        if (_isExpanded) ...<Widget>[
          const SizedBox(height: 10),
          SizedBox(
            key: const ValueKey<String>('chat-composer-todo-list'),
            height: listHeight,
            child: ListView.separated(
              padding: EdgeInsets.zero,
              physics: widget.todos.length > _TodoListPanel._maxVisibleTodoCount
                  ? const ClampingScrollPhysics()
                  : const NeverScrollableScrollPhysics(),
              itemCount: widget.todos.length,
              itemBuilder: (BuildContext context, int index) {
                return _TodoRow(todo: widget.todos[index], index: index);
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(height: _TodoListPanel._itemGap);
              },
            ),
          ),
        ],
      ],
    );
  }
}

class _TodoRow extends StatelessWidget {
  const _TodoRow({required this.todo, required this.index});

  final ChatTodoItemData todo;
  final int index;

  @override
  Widget build(BuildContext context) {
    final TextStyle style = switch (todo.status) {
      ChatTodoStatus.pending => _ChatTextStyles.todoItem,
      ChatTodoStatus.inProgress => _ChatTextStyles.todoItem.copyWith(
        color: _ChatPalette.accent,
        fontWeight: FontWeight.w600,
      ),
      ChatTodoStatus.completed => _ChatTextStyles.todoItem.copyWith(
        color: _ChatPalette.textSecondary,
        decoration: TextDecoration.lineThrough,
        decorationColor: _ChatPalette.textSecondary,
      ),
    };

    return SizedBox(
      key: ValueKey<String>('chat-composer-todo-row-$index'),
      height: _TodoListPanel._itemHeight,
      child: Row(
        children: <Widget>[
          _TodoStatusIndicator(status: todo.status, index: index),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              todo.displayText,
              key: ValueKey<String>('chat-composer-todo-text-$index'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: style,
            ),
          ),
        ],
      ),
    );
  }
}

class _TodoStatusIndicator extends StatelessWidget {
  const _TodoStatusIndicator({required this.status, required this.index});

  final ChatTodoStatus status;
  final int index;

  @override
  Widget build(BuildContext context) {
    final BoxDecoration decoration = switch (status) {
      ChatTodoStatus.pending => BoxDecoration(
        shape: BoxShape.circle,
        color: Colors.transparent,
        border: Border.all(color: _ChatPalette.composerStroke, width: 1.3),
      ),
      ChatTodoStatus.inProgress => BoxDecoration(
        shape: BoxShape.circle,
        color: Colors.transparent,
        border: Border.all(color: _ChatPalette.accent, width: 1.3),
      ),
      ChatTodoStatus.completed => BoxDecoration(
        shape: BoxShape.circle,
        color: _ChatPalette.todoCompletedFill,
      ),
    };

    return Container(
      key: ValueKey<String>('chat-composer-todo-indicator-$index'),
      width: 10,
      height: 10,
      decoration: decoration,
    );
  }
}

class _InputRow extends StatelessWidget {
  const _InputRow({
    required this.placeholder,
    required this.controller,
    required this.focusNode,
    required this.enabled,
    required this.hasIntegratedSurface,
    required this.showDefaultGlass,
    required this.plusHighlighted,
    required this.interruptTrace,
    required this.isInterruptBusy,
    required this.onInterruptPressed,
    required this.onPlusPressed,
    required this.onSendPressed,
  });

  final String placeholder;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool enabled;
  final bool hasIntegratedSurface;
  final bool showDefaultGlass;
  final bool plusHighlighted;
  final ChatRunTraceData? interruptTrace;
  final bool isInterruptBusy;
  final VoidCallback? onInterruptPressed;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;

  @override
  Widget build(BuildContext context) {
    const BorderRadius messageFieldRadius = BorderRadius.all(
      Radius.circular(18),
    );
    const BorderRadius messageFieldInnerRadius = BorderRadius.all(
      Radius.circular(17),
    );
    const double messageFieldMinHeight = 40;
    const double messageFieldMaxHeight = 92;

    final Widget inputRow = Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: <Widget>[
        Expanded(
          child: GestureDetector(
            onTap: enabled ? () => focusNode.requestFocus() : null,
            behavior: HitTestBehavior.opaque,
            child: AnimatedBuilder(
              animation: focusNode,
              builder: (BuildContext context, Widget? child) {
                final bool showOutline =
                    hasIntegratedSurface || focusNode.hasFocus;
                final Color fieldOutlineColor = focusNode.hasFocus
                    ? _ChatPalette.accent
                    : _ChatPalette.composerStroke;

                return AnimatedSize(
                  duration: const Duration(milliseconds: 160),
                  curve: Curves.easeOutCubic,
                  alignment: Alignment.bottomCenter,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(
                      minHeight: messageFieldMinHeight,
                      maxHeight: messageFieldMaxHeight,
                    ),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: showOutline ? fieldOutlineColor : Colors.white,
                        borderRadius: messageFieldRadius,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(showOutline ? 1 : 0),
                        child: ClipRRect(
                          borderRadius: showOutline
                              ? messageFieldInnerRadius
                              : messageFieldRadius,
                          child: ColoredBox(
                            color: Colors.white,
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(
                                14,
                                10,
                                14,
                                10,
                              ),
                              child: TextField(
                                controller: controller,
                                focusNode: focusNode,
                                enabled: enabled,
                                onTapOutside: enabled
                                    ? (_) => focusNode.unfocus()
                                    : null,
                                minLines: 1,
                                maxLines: 4,
                                textAlignVertical: TextAlignVertical.center,
                                decoration: InputDecoration(
                                  border: InputBorder.none,
                                  enabledBorder: InputBorder.none,
                                  focusedBorder: InputBorder.none,
                                  disabledBorder: InputBorder.none,
                                  errorBorder: InputBorder.none,
                                  focusedErrorBorder: InputBorder.none,
                                  isCollapsed: true,
                                  hintText: placeholder,
                                  hintStyle: _ChatTextStyles.placeholder,
                                  contentPadding: EdgeInsets.zero,
                                ),
                                textInputAction: TextInputAction.newline,
                                onSubmitted: enabled
                                    ? (_) => onSendPressed()
                                    : null,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        const SizedBox(width: 10),
        _CircleButton(
          key: const ValueKey<String>('chat-composer-plus-button'),
          backgroundColor: plusHighlighted
              ? _ChatPalette.plusActiveSurface
              : Colors.white,
          foregroundColor: plusHighlighted
              ? _ChatPalette.accent
              : _ChatPalette.textSecondary,
          icon: Icons.add_rounded,
          onPressed: enabled ? onPlusPressed : null,
        ),
        const SizedBox(width: 8),
        AnimatedBuilder(
          animation: controller,
          builder: (BuildContext context, Widget? child) {
            final bool showInterruptButton =
                enabled &&
                interruptTrace != null &&
                controller.text.trim().isEmpty;
            if (showInterruptButton) {
              return _CircleButton(
                key: const ValueKey<String>('chat-composer-interrupt-button'),
                backgroundColor: _ChatPalette.runTraceInterruptAction,
                foregroundColor: Colors.white,
                icon: Icons.stop_rounded,
                onPressed: isInterruptBusy ? null : onInterruptPressed,
              );
            }
            return _CircleButton(
              key: const ValueKey<String>('chat-composer-send-button'),
              backgroundColor: _ChatPalette.accent,
              foregroundColor: Colors.white,
              icon: Icons.arrow_upward_rounded,
              onPressed: enabled ? onSendPressed : null,
            );
          },
        ),
      ],
    );

    if (!showDefaultGlass) {
      return inputRow;
    }

    return Stack(
      clipBehavior: Clip.none,
      children: <Widget>[
        Positioned(
          left: -8,
          right: -8,
          top: -6,
          bottom: -6,
          child: IgnorePointer(
            child: ClipRRect(
              borderRadius: messageFieldRadius,
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[
                        Color(0x00FFFFFF),
                        Color(0x73FFFFFF),
                        Color(0x61F0F5FF),
                        Color(0x00F0F5FF),
                      ],
                      stops: <double>[0, 0.32, 0.72, 1],
                    ),
                    borderRadius: messageFieldRadius,
                  ),
                  child: SizedBox.expand(),
                ),
              ),
            ),
          ),
        ),
        inputRow,
      ],
    );
  }
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    super.key,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.icon,
    required this.onPressed,
  });

  final Color backgroundColor;
  final Color foregroundColor;
  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: onPressed == null
              ? backgroundColor.withValues(alpha: 0.4)
              : backgroundColor,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(
          icon,
          color: onPressed == null
              ? foregroundColor.withValues(alpha: 0.5)
              : foregroundColor,
          size: 18,
        ),
      ),
    );
  }
}

class _AddActionPill extends StatelessWidget {
  const _AddActionPill({required this.action, required this.onPressed});

  final ChatAddActionData action;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Container(
        height: 40,
        decoration: BoxDecoration(
          color: const Color(0xFFF7F7F9),
          borderRadius: BorderRadius.circular(12),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(action.icon, size: 16, color: _ChatPalette.textPrimary),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                action.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: _ChatTextStyles.addAction,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AttachmentCard extends StatelessWidget {
  const _AttachmentCard({
    required this.attachment,
    required this.onRemove,
    this.bridge,
  });

  final ChatAttachmentData attachment;
  final VoidCallback onRemove;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>('chat-composer-attachment-${attachment.id}'),
      width: 168,
      child: Stack(
        children: <Widget>[
          Container(
            margin: const EdgeInsets.only(top: 2, right: 2),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: attachment.accentColor,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: <Widget>[
                _ComposerAttachmentLeadingVisual(
                  attachment: attachment,
                  bridge: bridge,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      Text(
                        attachment.label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentLabel,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        attachment.detail,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.attachmentDetail,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            top: 0,
            right: 0,
            child: GestureDetector(
              onTap: onRemove,
              behavior: HitTestBehavior.opaque,
              child: Container(
                width: 16,
                height: 16,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.96),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: _ChatPalette.border),
                ),
                alignment: Alignment.center,
                child: const Icon(
                  Icons.close_rounded,
                  size: 10,
                  color: _ChatPalette.textSecondary,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ComposerAttachmentLeadingVisual extends StatefulWidget {
  const _ComposerAttachmentLeadingVisual({
    required this.attachment,
    required this.bridge,
  });

  final ChatAttachmentData attachment;
  final OpenCrayHostBridge? bridge;

  @override
  State<_ComposerAttachmentLeadingVisual> createState() =>
      _ComposerAttachmentLeadingVisualState();
}

class _ComposerAttachmentLeadingVisualState
    extends State<_ComposerAttachmentLeadingVisual> {
  Future<OpenCrayFileImagePreview>? _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _ComposerAttachmentLeadingVisual oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.bridge != widget.bridge ||
        oldWidget.attachment.id != widget.attachment.id ||
        oldWidget.attachment.draftAttachment?.relativePath !=
            widget.attachment.draftAttachment?.relativePath) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OpenCrayFileImagePreview>? _loadPreview() {
    if (widget.attachment.kind != ChatAttachmentKind.image) {
      return null;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    final String relativePath =
        widget.attachment.draftAttachment?.relativePath.trim() ?? '';
    if (bridge == null || relativePath.isEmpty) {
      return null;
    }
    return bridge.loadWorkspaceImagePreview(relativePath);
  }

  @override
  Widget build(BuildContext context) {
    final ChatAttachmentData attachment = widget.attachment;
    final IconData icon = attachment.kind == ChatAttachmentKind.image
        ? Icons.image_outlined
        : Icons.description_outlined;
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    if (previewFuture == null) {
      return _buildIconPlaceholder(icon, attachment.id);
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: previewFuture,
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        if (!ready) {
          return _buildIconPlaceholder(icon, attachment.id);
        }
        return ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: Container(
            key: ValueKey<String>(
              'chat-composer-image-preview-${attachment.id}',
            ),
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.75),
              borderRadius: BorderRadius.circular(10),
            ),
            child: OpenCrayImageBytesView(
              bytes: preview.bytes,
              mimeType: preview.mimeType,
              fit: BoxFit.cover,
            ),
          ),
        );
      },
    );
  }

  Widget _buildIconPlaceholder(IconData icon, String attachmentId) {
    return Container(
      key: ValueKey<String>('chat-composer-attachment-icon-$attachmentId'),
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(icon, size: 16, color: _ChatPalette.textPrimary),
    );
  }
}

class _CommandOptionTile extends StatelessWidget {
  const _CommandOptionTile({required this.option, required this.onPressed});

  final ChatCommandOptionData option;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: const Color(0xFFF7F7F9),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(option.label, style: _ChatTextStyles.commandTitle),
                      const SizedBox(height: 3),
                      Text(
                        option.description,
                        style: _ChatTextStyles.commandDescription,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                const Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: _ChatPalette.textSecondary,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SessionsDrawerOverlay extends StatelessWidget {
  const _SessionsDrawerOverlay({
    required this.copy,
    required this.drawer,
    required this.onDismiss,
    required this.onNewSessionPressed,
    required this.onSessionPressed,
    required this.onSessionLongPress,
  });

  final OpenCrayUiCopy copy;
  final ChatSessionsDrawerState drawer;
  final VoidCallback onDismiss;
  final VoidCallback onNewSessionPressed;
  final ValueChanged<ChatSessionListItemData> onSessionPressed;
  final void Function(ChatSessionListItemData, Offset) onSessionLongPress;

  @override
  Widget build(BuildContext context) {
    final EdgeInsets safePadding = MediaQuery.of(context).padding;

    return Positioned.fill(
      child: Row(
        children: <Widget>[
          Container(
            width: 286,
            color: Colors.white,
            padding: EdgeInsets.fromLTRB(
              16,
              safePadding.top + 18,
              16,
              safePadding.bottom + 20,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(drawer.eyebrow, style: _ChatTextStyles.drawerEyebrow),
                const SizedBox(height: 10),
                Text(drawer.title, style: _ChatTextStyles.drawerTitle),
                const SizedBox(height: 16),
                GestureDetector(
                  onTap: onNewSessionPressed,
                  behavior: HitTestBehavior.opaque,
                  child: Container(
                    height: 40,
                    width: 132,
                    decoration: BoxDecoration(
                      color: _ChatPalette.accent,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      drawer.ctaLabel,
                      style: _ChatTextStyles.drawerCta,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Expanded(
                  child: ListView.separated(
                    itemBuilder: (BuildContext context, int index) {
                      return _SessionListTile(
                        copy: copy,
                        session: drawer.sessions[index],
                        onPressed: () =>
                            onSessionPressed(drawer.sessions[index]),
                        onLongPressStart: (details) => onSessionLongPress(
                          drawer.sessions[index],
                          details.globalPosition,
                        ),
                      );
                    },
                    separatorBuilder: (BuildContext context, int index) {
                      return const SizedBox(height: 10);
                    },
                    itemCount: drawer.sessions.length,
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: GestureDetector(
              onTap: onDismiss,
              behavior: HitTestBehavior.opaque,
              child: ColoredBox(
                color: const Color(0x26111111),
                child: const SizedBox.expand(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SessionListTile extends StatelessWidget {
  const _SessionListTile({
    required this.copy,
    required this.session,
    required this.onPressed,
    required this.onLongPressStart,
  });

  final OpenCrayUiCopy copy;
  final ChatSessionListItemData session;
  final VoidCallback onPressed;
  final ValueChanged<LongPressStartDetails> onLongPressStart;

  @override
  Widget build(BuildContext context) {
    final String sessionMetaLabel = _formatChatSessionTimestampLabel(
      copy,
      session.lastMessageAtEpochMs,
      session.meta,
    );
    return GestureDetector(
      onTap: onPressed,
      onLongPressStart: onLongPressStart,
      behavior: HitTestBehavior.opaque,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: session.isSelected
              ? const Color(0xFFF4F7FF)
              : const Color(0xFFF7F7F9),
          borderRadius: BorderRadius.circular(14),
          border: session.isSelected
              ? Border.all(color: const Color(0xFFD8E5FF))
              : null,
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      session.title,
                      style: _ChatTextStyles.sessionTitle,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(sessionMetaLabel, style: _ChatTextStyles.sessionMeta),
                  if (session.unreadCount > 0) ...<Widget>[
                    const SizedBox(width: 8),
                    _SessionUnreadBadge(
                      sessionId: session.sessionId,
                      count: session.unreadCount,
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 6),
              Text(
                session.preview,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: _ChatTextStyles.sessionPreview,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SessionUnreadBadge extends StatelessWidget {
  const _SessionUnreadBadge({required this.sessionId, required this.count});

  final String sessionId;
  final int count;

  @override
  Widget build(BuildContext context) {
    if (count <= 1) {
      return Container(
        key: ValueKey<String>('chat-session-unread-$sessionId'),
        width: 10,
        height: 10,
        decoration: const BoxDecoration(
          color: Color(0xFFFF3B30),
          shape: BoxShape.circle,
        ),
      );
    }
    final String label = count > 99 ? '99+' : '$count';
    return Container(
      key: ValueKey<String>('chat-session-unread-$sessionId'),
      constraints: const BoxConstraints(minWidth: 20, minHeight: 20),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: const Color(0xFFFF3B30),
        borderRadius: BorderRadius.circular(999),
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: _ChatTextStyles.timeline.copyWith(
          color: Colors.white,
          fontSize: 11,
        ),
      ),
    );
  }
}

class _ChatDecorations {
  const _ChatDecorations._();

  static BoxDecoration card() {
    return BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: _ChatPalette.border),
    );
  }
}

class _ChatPalette {
  const _ChatPalette._();

  static const Color background = Color(0xFFF5F5F7);
  static const Color accent = Color(0xFF007AFF);
  static const Color highRiskAccent = Color(0xFFC84B31);
  static const Color highRiskBorder = Color(0xFFF2C6BA);
  static const Color highRiskBadgeSurface = Color(0xFFFFE0D7);
  static const Color textPrimary = Color(0xFF111111);
  static const Color textSecondary = Color(0xFF6E6E73);
  static const Color textTertiary = Color(0xFF8E8E93);
  static const Color border = Color(0xFFE8E8ED);
  static const Color runTraceBorder = Color(0xFFDCE3ED);
  static const Color runTraceStatusSurface = Color(0xFFEAF2FF);
  static const Color runTraceStatusText = Color(0xFF1B67D9);
  static const Color runTraceActivityText = Color(0xFF526071);
  static const Color runTraceDetailSurface = Color(0xFFF5F7FC);
  static const Color runTracePreviewSurface = Color(0xFFF7FAFF);
  static const Color runTracePreviewBorder = Color(0xFFD9E4F5);
  static const Color runTraceUrlText = Color(0xFF165FC2);
  static const Color runTraceInterruptSurface = Color(0xFFFFF1ED);
  static const Color runTraceInterruptBorder = Color(0xFFF2CFC4);
  static const Color runTraceInterruptAction = Color(0xFFC84B31);
  static const Color runTraceTabDivider = Color(0xFFE4E8F0);
  static const Color inspectorAction = Color(0xFF007AFF);
  static const Color inspectorTarget = Color(0xFF7C3AED);
  static const Color inspectorScope = Color(0xFF16A34A);
  static const Color inspectorResult = Color(0xFF64748B);
  static const Color inspectorConnector = Color(0xFFCDD6F4);
  static const Color composerStroke = Color(0xFFD7D7DC);
  static const Color plusActiveSurface = Color(0xFFEEF5FF);
  static const Color subtleSurface = Color(0xFFF7F7FA);
  static const Color todoCompletedFill = Color(0xFFB8BDC7);
  static const Color selectionRowHighlight = Color(0xFFE5E5EA);
  static const Color selectionControlBorder = Color(0xFFC7C7CC);
}

class _ChatTextStyles {
  const _ChatTextStyles._();

  static const TextStyle pageTitle = TextStyle(
    fontSize: 30,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.6,
  );

  static const TextStyle cardTitle = TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle bodyMuted = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle bubble = TextStyle(
    fontSize: 14,
    height: 1.35,
    fontWeight: FontWeight.w500,
  );

  static const TextStyle runTraceHeadline = TextStyle(
    fontSize: 15,
    height: 1.3,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle runTraceDetailLabel = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: Color(0xFF243248),
  );

  static const TextStyle runTraceDetailValue = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: Color(0xFF243248),
  );

  static const TextStyle runTraceFooter = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: Color(0xFF7A8494),
  );

  static const TextStyle runInspectorTitle = TextStyle(
    fontSize: 28,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.4,
  );

  static const TextStyle runInspectorLog = TextStyle(
    fontSize: 13,
    height: 1.4,
    fontWeight: FontWeight.w600,
  );

  static const TextStyle runInspectorDetail = TextStyle(
    fontSize: 12,
    height: 1.45,
    fontWeight: FontWeight.w500,
  );

  static const TextStyle runInspectorResult = TextStyle(
    fontSize: 12,
    height: 1.45,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.inspectorResult,
  );

  static const TextStyle runInspectorResultBranch = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.inspectorConnector,
  );

  static const TextStyle messageMenuLabel = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    letterSpacing: -0.1,
  );

  static const TextStyle timeline = TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle todoLabel = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w600,
    letterSpacing: 0.5,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle todoItem = TextStyle(
    fontSize: 14,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle toolbarButton = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle modeLabel = TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.accent,
  );

  static const TextStyle selectionToolbarAction = TextStyle(
    fontSize: 16,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.accent,
  );

  static const TextStyle selectionToolbarTitle = TextStyle(
    fontSize: 16,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.2,
  );

  static const TextStyle selectionAction = TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
  );

  static const TextStyle summaryBadge = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle highRiskBadge = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.highRiskAccent,
  );

  static const TextStyle placeholder = TextStyle(
    fontSize: 15,
    height: 1.2,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textTertiary,
  );

  static const TextStyle sectionLabel = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle commandsLabel = TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle addAction = TextStyle(
    fontSize: 13,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle approvalAction = TextStyle(
    fontSize: 13,
    height: 1.1,
    fontWeight: FontWeight.w700,
  );

  static const TextStyle approvalRequest = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle approvalReason = TextStyle(
    fontSize: 12,
    height: 1.4,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle attachmentLabel = TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle attachmentDetail = TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle commandTitle = TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle commandDescription = TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle drawerEyebrow = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle drawerTitle = TextStyle(
    fontSize: 24,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle drawerCta = TextStyle(
    fontSize: 14,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: Colors.white,
  );

  static const TextStyle sessionTitle = TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle sessionMeta = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle sessionPreview = TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );
}
