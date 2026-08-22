part of 'chat_feature_screen.dart';

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
  if (!_runtimeSnapshotsShareStreamInstance(embedded, streamed)) {
    return streamed;
  }
  if (_runtimeSnapshotIsAuthoritativeClear(streamed, embedded)) {
    return streamed;
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

bool _runtimeSnapshotsShareStreamInstance(
  OpenCrayChatRuntimeSnapshot left,
  OpenCrayChatRuntimeSnapshot right,
) {
  final String leftBridgeEpoch = left.bridgeEpoch?.trim() ?? '';
  final String rightBridgeEpoch = right.bridgeEpoch?.trim() ?? '';
  if (leftBridgeEpoch.isNotEmpty &&
      rightBridgeEpoch.isNotEmpty &&
      leftBridgeEpoch != rightBridgeEpoch) {
    return false;
  }
  final String leftStreamInstanceId = left.streamInstanceId?.trim() ?? '';
  final String rightStreamInstanceId = right.streamInstanceId?.trim() ?? '';
  return leftStreamInstanceId.isEmpty ||
      rightStreamInstanceId.isEmpty ||
      leftStreamInstanceId == rightStreamInstanceId;
}

bool _runtimeSnapshotIsAuthoritativeClear(
  OpenCrayChatRuntimeSnapshot incoming,
  OpenCrayChatRuntimeSnapshot current,
) {
  if (_runtimeSnapshotHasVisibleActivity(incoming) ||
      !_runtimeSnapshotHasVisibleActivity(current)) {
    return false;
  }
  return incoming.updatedAtEpochMs >= current.updatedAtEpochMs;
}

bool _runtimeSnapshotHasVisibleActivity(OpenCrayChatRuntimeSnapshot snapshot) {
  return snapshot.activeRuns.isNotEmpty ||
      snapshot.retainedRuns.isNotEmpty ||
      snapshot.subAgents.isNotEmpty ||
      snapshot.liveAssistantDrafts.isNotEmpty ||
      snapshot.events.isNotEmpty;
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
  if (!_runtimeSnapshotsShareStreamInstance(left, right) &&
      (right.streamInstanceId?.trim().isNotEmpty ?? false)) {
    return right;
  }
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
    streamInstanceId: right.streamInstanceId?.trim().isNotEmpty == true
        ? right.streamInstanceId
        : left.streamInstanceId,
    lastSequence: _maxNullableInt(left.lastSequence, right.lastSequence),
    flutterAppInstanceId:
        right.flutterAppInstanceId ?? left.flutterAppInstanceId,
    bridgeInstanceId: right.bridgeInstanceId ?? left.bridgeInstanceId,
    bridgeEpoch: right.bridgeEpoch ?? left.bridgeEpoch,
    hostLifecycle: hostLifecycle,
    updatedAtEpochMs: math.max(left.updatedAtEpochMs, right.updatedAtEpochMs),
  );
}

OpenCrayChatRuntimeSnapshot _mergeRuntimeDeltaSnapshot(
  OpenCrayChatRuntimeSnapshot current,
  OpenCrayChatRuntimeSnapshot delta, {
  required bool hasActiveRunsPatch,
  required bool hasRetainedRunsPatch,
  required bool hasSubAgentsPatch,
  required bool hasLiveAssistantDraftsPatch,
}) {
  final OpenCrayChatRuntimeSnapshot merged = _mergeRuntimeSnapshots(
    current,
    delta,
  );
  return OpenCrayChatRuntimeSnapshot(
    sessionId: merged.sessionId,
    activeRuns: hasActiveRunsPatch ? delta.activeRuns : merged.activeRuns,
    retainedRuns: hasRetainedRunsPatch
        ? delta.retainedRuns
        : merged.retainedRuns,
    subAgents: hasSubAgentsPatch ? delta.subAgents : merged.subAgents,
    events: merged.events,
    liveAssistantDrafts: hasLiveAssistantDraftsPatch
        ? delta.liveAssistantDrafts
        : merged.liveAssistantDrafts,
    streamInstanceId: merged.streamInstanceId,
    lastSequence: merged.lastSequence,
    flutterAppInstanceId: merged.flutterAppInstanceId,
    bridgeInstanceId: merged.bridgeInstanceId,
    bridgeEpoch: merged.bridgeEpoch,
    hostLifecycle: merged.hostLifecycle,
    updatedAtEpochMs: merged.updatedAtEpochMs,
  );
}

bool _runtimeRunListPatchCanReplace(
  List<OpenCrayChatRunSnapshot> currentRuns,
  List<OpenCrayChatRunSnapshot> patchRuns,
) {
  for (final currentRun in currentRuns) {
    if (!_isRuntimeRunActiveForProjection(currentRun)) {
      continue;
    }
    if (_findRuntimeRun(patchRuns, currentRun) == null) {
      return false;
    }
  }
  for (final patchRun in patchRuns) {
    final OpenCrayChatRunSnapshot? currentRun = _findRuntimeRun(
      currentRuns,
      patchRun,
    );
    if (currentRun == null) {
      continue;
    }
    final OpenCrayChatRunSnapshot preferred = _preferRuntimeRunSnapshot(
      currentRun,
      patchRun,
    );
    if (identical(preferred, currentRun) &&
        _runtimeRunStateSignature(currentRun) !=
            _runtimeRunStateSignature(patchRun)) {
      return false;
    }
  }
  return true;
}

String _runtimeRunStateSignature(OpenCrayChatRunSnapshot run) =>
    jsonEncode(_runtimeRunDisplaySignature(run));

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
  final String leftTaskId = left.taskId.trim();
  final String rightTaskId = right.taskId.trim();
  if (leftTaskId.isNotEmpty && rightTaskId.isNotEmpty) {
    return leftTaskId == rightTaskId;
  }
  final String leftRunId = left.runId.trim();
  final String rightRunId = right.runId.trim();
  return leftRunId.isNotEmpty &&
      rightRunId.isNotEmpty &&
      leftRunId == rightRunId;
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
  final bool preferSupplementDetail =
      identical(supplement, right) &&
      right.updatedAtEpochMs >= left.updatedAtEpochMs;
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
        : _preferRuntimeRunDetail(
            preferred.llmDiagnostics,
            supplement.llmDiagnostics,
            _runtimeLlmDiagnosticsDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    liveContext: clearsTerminalState
        ? preferred.liveContext
        : _preferRuntimeRunDetail(
            preferred.liveContext,
            supplement.liveContext,
            _runtimeLiveContextDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    contextBudget: clearsTerminalState
        ? preferred.contextBudget
        : _preferRuntimeRunDetail(
            preferred.contextBudget,
            supplement.contextBudget,
            _runtimeContextBudgetDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    memoryTrace: clearsTerminalState
        ? preferred.memoryTrace
        : _preferRuntimeRunDetail(
            preferred.memoryTrace,
            supplement.memoryTrace,
            _runtimeMemoryTraceDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    stickyMemory: clearsTerminalState
        ? preferred.stickyMemory
        : _preferRuntimeRunDetail(
            preferred.stickyMemory,
            supplement.stickyMemory,
            _runtimeStickyMemoryDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    memoryFlush: clearsTerminalState
        ? preferred.memoryFlush
        : _preferRuntimeRunDetail(
            preferred.memoryFlush,
            supplement.memoryFlush,
            _runtimeMemoryFlushDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    bootstrap: clearsTerminalState
        ? preferred.bootstrap
        : _preferRuntimeRunDetail(
            preferred.bootstrap,
            supplement.bootstrap,
            _runtimeBootstrapDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    durableCompaction: clearsTerminalState
        ? preferred.durableCompaction
        : _preferRuntimeRunDetail(
            preferred.durableCompaction,
            supplement.durableCompaction,
            _runtimeDurableCompactionDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    skillInventory: clearsTerminalState
        ? preferred.skillInventory
        : _preferRuntimeRunDetail(
            preferred.skillInventory,
            supplement.skillInventory,
            _runtimeSkillInventoryDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    activeSkill: clearsTerminalState
        ? preferred.activeSkill
        : _preferRuntimeRunDetail(
            preferred.activeSkill,
            supplement.activeSkill,
            _runtimeActiveSkillDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    diagnostics: clearsTerminalState
        ? preferred.diagnostics
        : _preferRuntimeRunDetail(
            preferred.diagnostics,
            supplement.diagnostics,
            _runtimeDiagnosticsDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
    recoveryPlan: clearsTerminalState
        ? preferred.recoveryPlan
        : _preferRuntimeRunDetail(
            preferred.recoveryPlan,
            supplement.recoveryPlan,
            _runtimeRecoveryPlanDisplaySignature,
            preferSupplementWhenDifferent: preferSupplementDetail,
          ),
  );
}

String _preferNonEmpty(String preferred, String fallback) =>
    preferred.trim().isNotEmpty ? preferred : fallback;

String? _preferNonEmptyNullable(String? preferred, String? fallback) =>
    preferred?.trim().isNotEmpty == true ? preferred : fallback;

T? _preferRuntimeRunDetail<T>(
  T? preferred,
  T? supplement,
  Object? Function(T detail) displaySignature, {
  required bool preferSupplementWhenDifferent,
}) {
  if (preferred == null || supplement == null) {
    return preferred ?? supplement;
  }
  if (jsonEncode(displaySignature(preferred)) ==
      jsonEncode(displaySignature(supplement))) {
    return preferred;
  }
  return preferSupplementWhenDifferent ? supplement : preferred;
}

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
    process.workingDirectory?.trim() ?? '',
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
    (event.eventId?.length ?? 0) +
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

String _runtimeEventMergeKey(OpenCrayChatRuntimeEventSnapshot event) {
  final String eventId = event.eventId?.trim() ?? '';
  if (eventId.isNotEmpty) {
    return 'event:$eventId';
  }
  return <String>[
    event.kind,
    event.runId,
    event.taskId,
    event.executionId ?? '',
    event.executionOrdinal?.toString() ?? '',
    event.executionKind ?? '',
    event.turn?.toString() ?? '',
    event.phase ?? '',
    event.stage ?? '',
    event.toolName ?? '',
    event.entryId ?? '',
    event.childRunId ?? '',
    event.childTaskId ?? '',
    event.emittedAtEpochMs.toString(),
  ].join('\u0001');
}

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
  final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> byIdentity =
      <String, OpenCrayChatLiveAssistantDraftSnapshot>{};
  for (final draft in <OpenCrayChatLiveAssistantDraftSnapshot>[
    ...left,
    ...right,
  ]) {
    final String pendingMessageId = draft.pendingMessageId.trim();
    if (pendingMessageId.isEmpty) {
      continue;
    }
    final String identity = _runtimeDraftIdentity(
      pendingMessageId: pendingMessageId,
      executionId: draft.executionId,
    );
    final OpenCrayChatLiveAssistantDraftSnapshot? existing =
        byIdentity[identity];
    if (existing == null || _draftIsAtLeastAsNew(draft, existing)) {
      byIdentity[identity] = draft;
    }
  }
  return byIdentity.values.toList(growable: false)
    ..sort((leftDraft, rightDraft) {
      final int leftSequence = leftDraft.sequence ?? 0;
      final int rightSequence = rightDraft.sequence ?? 0;
      if (leftSequence != rightSequence) {
        return leftSequence.compareTo(rightSequence);
      }
      return leftDraft.updatedAtEpochMs.compareTo(rightDraft.updatedAtEpochMs);
    });
}

String _runtimeDraftIdentity({
  required String pendingMessageId,
  required String? executionId,
}) => '${pendingMessageId.trim()}\u0001${executionId?.trim() ?? ''}';

bool _draftIsAtLeastAsNew(
  OpenCrayChatLiveAssistantDraftSnapshot candidate,
  OpenCrayChatLiveAssistantDraftSnapshot current,
) {
  final int? candidateSequence = candidate.sequence;
  final int? currentSequence = current.sequence;
  if (candidateSequence != null && currentSequence != null) {
    return candidateSequence >= currentSequence;
  }
  return candidate.updatedAtEpochMs >= current.updatedAtEpochMs;
}

int? _maxNullableInt(int? left, int? right) {
  if (left == null || right == null) {
    return right ?? left;
  }
  return math.max(left, right);
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

bool _isRuntimeProjectedAgentMessageId(String messageId) =>
    messageId.startsWith('runtime-assistant-') ||
    messageId.startsWith('runtime-process-');
