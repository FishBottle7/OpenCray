// ignore_for_file: annotate_overrides

part of 'chat_feature_screen.dart';

mixin _ProjectorHistoryDomain on _ChatRuntimeProjectorDeps {
  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    List<OpenCrayChatPendingApprovalSnapshot> pendingApprovals,
    Set<String> visibleAnchorMessageIds,
  ) {
    if (runtimeSnapshot == null) {
      return const <ChatRunTraceData>[];
    }
    final activeRuns =
        _visibleRuns(runtimeSnapshot)
            .where((run) {
              final String pendingMessageId =
                  run.pendingMessageId?.trim() ?? '';
              return pendingMessageId.isEmpty ||
                  visibleAnchorMessageIds.contains(pendingMessageId);
            })
            .toList(growable: false)
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
      'hasActiveExecution': '${snapshot.hasActiveExecution}',
      'mailboxMessageCount': '${snapshot.mailboxMessageCount}',
      'mailboxPendingMessageCount': '${snapshot.mailboxPendingMessageCount}',
      'hasPendingApprovalResume': '${snapshot.hasPendingApprovalResume}',
      if (snapshot.pendingApprovalToolName?.trim().isNotEmpty == true)
        'pendingApprovalToolName': snapshot.pendingApprovalToolName!.trim(),
      'pendingApprovalIsHighRisk': '${snapshot.pendingApprovalIsHighRisk}',
      if (snapshot.pendingApprovalChildRunId?.trim().isNotEmpty == true)
        'pendingApprovalChildRunId': snapshot.pendingApprovalChildRunId!.trim(),
      if (snapshot.pendingApprovalChildTaskId?.trim().isNotEmpty == true)
        'pendingApprovalChildTaskId': snapshot.pendingApprovalChildTaskId!.trim(),
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
    final String childKey = childTaskId.isNotEmpty
        ? childTaskId
        : (childRunId.isNotEmpty ? childRunId : label);
    final String parentTaskId = event.taskId.trim();
    final String parentRunId = event.runId.trim();
    final String parentKey = parentTaskId.isNotEmpty
        ? parentTaskId
        : parentRunId;
    return '$parentKey|$childKey';
  }

  String _subAgentStateSignature(OpenCrayChatRuntimeEventSnapshot event) =>
      <String>[
        _subAgentRegistryKeyForEvent(event),
        event.phase?.trim().toLowerCase() ?? '',
        event.status?.trim().toLowerCase() ?? '',
        event.executionState?.trim().toLowerCase() ?? '',
        event.continuationKind?.trim().toLowerCase() ?? '',
        event.text?.trim() ?? '',
        event.resultMetadata['hasActiveExecution']?.trim() ?? '',
        event.resultMetadata['mailboxMessageCount']?.trim() ?? '',
        event.resultMetadata['mailboxPendingMessageCount']?.trim() ?? '',
        event.resultMetadata['hasPendingApprovalResume']?.trim() ?? '',
        event.resultMetadata['pendingApprovalToolName']?.trim() ?? '',
        event.resultMetadata['pendingApprovalIsHighRisk']?.trim() ?? '',
        event.resultMetadata['pendingApprovalChildRunId']?.trim() ?? '',
        event.resultMetadata['pendingApprovalChildTaskId']?.trim() ?? '',
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
        retryLabel: copy.chatRunResumeAction,
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
        retryLabel: copy.chatRunResumeAction,
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
              : copy.chatRunWorkingLabel,
          body: compactBody(_buildToolCallPreviewBody(event!)),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_result':
        return buildTrace(
          label: waitingApproval
              ? copy.chatRunWaitingApprovalLabel
              : toolName?.isNotEmpty == true
              ? toolName!
              : copy.chatRunWorkingLabel,
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
              : copy.chatRunWorkingLabel,
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
          label: copy.chatRunWorkingLabel,
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : copy.chatRunThinkingActive,
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
                : copy.chatRunThinkingActive,
          ),
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      default:
        return buildTrace(
          label: waitingApproval
              ? copy.chatRunWaitingApprovalLabel
              : copy.chatRunWorkingLabel,
          body: compactBody(
            waitingApproval
                ? run.errorMessage?.trim().isNotEmpty == true
                      ? run.errorMessage!.trim()
                      : copy.chatRunWaitingApprovalLabel
                : copy.chatRunThinkingActive,
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
    final Set<String> durableSubAgentKeys = durableSubAgentEvents
        .map(_subAgentRegistryKeyForEvent)
        .toSet();
    for (int index = 0; index < runEvents.length; index += 1) {
      if (consumedIndexes.contains(index)) {
        continue;
      }
      if (runEvents[index].kind == 'subagent' &&
          durableSubAgentKeys.contains(
            _subAgentRegistryKeyForEvent(runEvents[index]),
          )) {
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
              history.first.label == copy.chatRunWorkingLabel &&
              history.first.body == copy.chatRunThinkingActive
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
        label: copy.chatRunWaitingApprovalLabel,
        body: approvalBody?.isNotEmpty == true
            ? approvalBody!
            : copy.chatRunWaitingApprovalLabel,
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
          label: copy.chatRunWorkingLabel,
          body: copy.chatRunThinkingActive,
        ),
      );
    }
    return history;
  }

  String _mainInspectorActorLabel() =>
      copy.isChinese ? '主代理' : 'Main agent';

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
    final String label = copy.isChinese ? '最终附件' : 'Final attachments';
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
    return copy.isChinese ? '未命名附件' : 'Unnamed attachment';
  }

  String _finalAttachmentInspectorSection(
    OpenCrayChatAttachmentSnapshot attachment,
  ) {
    final List<String> sections = <String>[
      _joinTraceSections(<String?>[
        copy.isChinese
            ? '名称：${_finalAttachmentTitle(attachment)}'
            : 'Name: ${_finalAttachmentTitle(attachment)}',
        copy.isChinese
            ? '类型：${attachment.kind}'
            : 'Kind: ${attachment.kind}',
        _nonEmpty(attachment.mimeType) != null
            ? (copy.isChinese
                  ? 'MIME：${attachment.mimeType!}'
                  : 'MIME: ${attachment.mimeType!}')
            : null,
        _nonEmpty(attachment.localPath) != null
            ? (copy.isChinese
                  ? '路径：${attachment.localPath}'
                  : 'Path: ${attachment.localPath}')
            : null,
        attachment.sizeBytes != null
            ? (copy.isChinese
                  ? '大小：${attachment.sizeBytes} bytes'
                  : 'Size: ${attachment.sizeBytes} bytes')
            : null,
        attachment.widthPx != null && attachment.heightPx != null
            ? (copy.isChinese
                  ? '尺寸：${attachment.widthPx} x ${attachment.heightPx}'
                  : 'Dimensions: ${attachment.widthPx} x ${attachment.heightPx}')
            : null,
        attachment.durationMs != null
            ? (copy.isChinese
                  ? '时长：${attachment.durationMs} ms'
                  : 'Duration: ${attachment.durationMs} ms')
            : null,
        attachment.waveformBars.isNotEmpty
            ? (copy.isChinese
                  ? '波形：${attachment.waveformBars.join(', ')}'
                  : 'Waveform: ${attachment.waveformBars.join(', ')}')
            : null,
        _nonEmpty(attachment.transcriptText) != null
            ? (copy.isChinese
                  ? '转写：${attachment.transcriptText!}'
                  : 'Transcript: ${attachment.transcriptText!}')
            : null,
        _nonEmpty(attachment.contentSha256) != null
            ? (copy.isChinese
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
          ? (copy.isChinese
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
        _inspectorAction(copy.isChinese ? '进程 ' : 'Process '),
        _inspectorTarget(process.processId),
      ],
      inspectorCallDetail: inspectorDetail,
      inspectorResultBody: inspectorResultBody,
    );
  }

  String _managedProcessHistoryLabel(
    OpenCrayChatManagedProcessSnapshot process,
  ) {
    return copy.isChinese
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
        return copy.isChinese ? '运行中' : 'running';
      case 'success':
        return copy.isChinese ? '已完成' : 'finished';
      case 'failed':
      case 'spawn_error':
        return copy.isChinese ? '失败' : 'failed';
      case 'cancelled':
        return copy.isChinese ? '已取消' : 'cancelled';
      case 'timeout':
        return copy.isChinese ? '已超时' : 'timed out';
      default:
        return status.isEmpty
            ? (copy.isChinese ? '未知' : 'unknown')
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
    return copy.isChinese
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
        ? (copy.isChinese ? '\n[输出已截断]' : '\n[output truncated]')
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
            label: copy.chatRunWorkingLabel,
            body: copy.chatRunThinkingActive,
          );
        }
        return null;
      case 'tool_call':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : copy.chatRunWorkingLabel;
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
            : copy.chatRunWorkingLabel;
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
            : copy.chatRunWorkingLabel;
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
          label: copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : copy.chatRunThinkingActive,
        );
      case 'assistant_phase':
        final text = event.text?.trim();
        return _mainHistoryEntry(
          label: _assistantPhaseEntryLabel(event),
          body: text?.isNotEmpty == true
              ? text!
              : copy.chatRunThinkingActive,
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
    final String? stickyMemoryBody = _buildRunStickyMemoryHistoryBody(
      run.stickyMemory,
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
    if (stickyMemoryBody != null) {
      history.add(
        _mainHistoryEntry(
          label: _traceSectionLabel(english: 'Sticky Memory', chinese: '固定记忆'),
          body: stickyMemoryBody,
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
        copy.isChinese
            ? '模式 ${liveContext.mode}'
            : 'Mode: ${liveContext.mode}',
      if (liveContext.soulEnabled != null)
        copy.isChinese
            ? (liveContext.soulEnabled! ? 'Soul 已启用' : 'Soul 已关闭')
            : (liveContext.soulEnabled! ? 'Soul enabled' : 'Soul disabled'),
      if (liveContext.memoryRecallEnabled != null)
        copy.isChinese
            ? (liveContext.memoryRecallEnabled! ? '自动记忆召回已启用' : '自动记忆召回已关闭')
            : (liveContext.memoryRecallEnabled!
                  ? 'Automatic memory recall enabled'
                  : 'Automatic memory recall disabled'),
    ];
    return summary.isEmpty
        ? null
        : summary.join(copy.isChinese ? '，' : ', ');
  }

  String? _buildRunMemoryTraceHistoryBody(
    OpenCrayChatRunMemoryTraceSnapshot? trace,
  ) {
    if (trace == null) {
      return null;
    }
    final List<String> countParts = <String>[
      if (trace.matchedRecordCount != null)
        copy.isChinese
            ? '命中 ${trace.matchedRecordCount} 条'
            : '${trace.matchedRecordCount} matched',
      if (trace.injectedRecordCount != null)
        copy.isChinese
            ? '注入 ${trace.injectedRecordCount} 条'
            : '${trace.injectedRecordCount} injected',
      if (trace.omittedRecordCount != null)
        copy.isChinese
            ? '省略 ${trace.omittedRecordCount} 条'
            : '${trace.omittedRecordCount} omitted',
    ];
    final String? queryTerms = trace.queryTerms.isEmpty
        ? null
        : copy.isChinese
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
        : copy.isChinese
        ? '过滤统计：${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join('，')}'
        : 'Filtered counts: ${trace.filteredCounts.entries.map((entry) => '${entry.key} ${entry.value}').join(', ')}';
    return _joinTraceSections(<String?>[
      countParts.isEmpty
          ? null
          : countParts.join(copy.isChinese ? '，' : ', '),
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
        copy.isChinese
            ? '分数 ${selected.score}'
            : 'score ${selected.score}',
      );
    }
    if (selected.matchedTerms.isNotEmpty) {
      parts.add(
        copy.isChinese
            ? '匹配 ${selected.matchedTerms.join(', ')}'
            : 'matched ${selected.matchedTerms.join(', ')}',
      );
    }
    return parts.join(copy.isChinese ? '，' : ', ');
  }

  String _formatRunMemoryOmittedSummary(
    OpenCrayChatRunMemoryOmittedSnapshot omitted,
  ) {
    if (omitted.reason.trim().isEmpty) {
      return omitted.id;
    }
    return copy.isChinese
        ? '${omitted.id}，原因 ${omitted.reason}'
        : '${omitted.id}, reason ${omitted.reason}';
  }

  String? _buildRunStickyMemoryHistoryBody(
    OpenCrayChatRunStickyMemorySnapshot? stickyMemory,
  ) {
    if (stickyMemory == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (stickyMemory.injectedRecordCount != null)
        copy.isChinese
            ? '注入 ${stickyMemory.injectedRecordCount} 条'
            : '${stickyMemory.injectedRecordCount} injected',
      if (stickyMemory.omittedRecordCount != null)
        copy.isChinese
            ? '省略 ${stickyMemory.omittedRecordCount} 条'
            : '${stickyMemory.omittedRecordCount} omitted',
    ];
    final String? recordIds = stickyMemory.recordIds.isEmpty
        ? null
        : _labeledMultilineSection(
            englishLabel: 'Pinned record ids',
            chineseLabel: '固定记录',
            values: stickyMemory.recordIds,
          );
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      recordIds,
    ]);
  }

  String? _buildRunMemoryFlushHistoryBody(
    OpenCrayChatRunMemoryFlushSnapshot? flush,
  ) {
    if (flush == null) {
      return null;
    }
    final List<String> summary = <String>[
      if (flush.outcome != null)
        copy.isChinese
            ? '结果 ${flush.outcome}'
            : 'Outcome: ${flush.outcome}',
      if (flush.candidateCount != null)
        copy.isChinese
            ? '候选 ${flush.candidateCount} 条'
            : '${flush.candidateCount} candidate(s)',
      if (flush.writtenRecordCount != null)
        copy.isChinese
            ? '写入 ${flush.writtenRecordCount} 条'
            : '${flush.writtenRecordCount} written',
    ];
    final List<String> omitted = <String>[
      if (flush.omittedMessageCount != null)
        copy.isChinese
            ? '省略消息 ${flush.omittedMessageCount} 条'
            : '${flush.omittedMessageCount} omitted message(s)',
      if (flush.omittedCharCount != null)
        copy.isChinese
            ? '省略字符 ${flush.omittedCharCount}'
            : '${flush.omittedCharCount} omitted char(s)',
    ];
    final List<String> pressure = <String>[
      if (_nonEmpty(flush.triggerStage) != null)
        copy.isChinese
            ? '触发 ${flush.triggerStage}'
            : 'Trigger: ${flush.triggerStage}',
      if (_nonEmpty(flush.executionMode) != null)
        copy.isChinese
            ? '模式 ${flush.executionMode}'
            : 'Mode: ${flush.executionMode}',
      if (flush.contextWindowTokens != null)
        copy.isChinese
            ? '窗口 ${flush.contextWindowTokens}'
            : 'Window ${flush.contextWindowTokens}',
      if (flush.previousContextWindowTokens != null)
        copy.isChinese
            ? '原窗口 ${flush.previousContextWindowTokens}'
            : 'Previous window ${flush.previousContextWindowTokens}',
      if (flush.autoCompactTokenLimit != null)
        copy.isChinese
            ? '阈值 ${flush.autoCompactTokenLimit}'
            : 'Threshold ${flush.autoCompactTokenLimit}',
      if (flush.estimatedReplayTokens != null)
        copy.isChinese
            ? '估算 ${flush.estimatedReplayTokens}'
            : 'Estimated ${flush.estimatedReplayTokens}',
      if (flush.tokenThresholdTriggered != null)
        copy.isChinese
            ? (flush.tokenThresholdTriggered! ? '已达阈值' : '未达阈值')
            : (flush.tokenThresholdTriggered!
                  ? 'Threshold triggered'
                  : 'Threshold not triggered'),
      if (flush.smallerWindowModelSwitchDetected == true)
        copy.isChinese ? '检测到更小窗口模型' : 'Smaller-window model switch',
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      omitted.isEmpty ? null : omitted.join(copy.isChinese ? '，' : ', '),
      pressure.isEmpty
          ? null
          : pressure.join(copy.isChinese ? '，' : ', '),
      flush.signature == null
          ? null
          : copy.isChinese
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
        copy.isChinese
            ? '模式 ${bootstrap.mode}'
            : 'Mode: ${bootstrap.mode}',
      if (bootstrap.visibleFileCount != null)
        copy.isChinese
            ? '可见 ${bootstrap.visibleFileCount} 个文件'
            : '${bootstrap.visibleFileCount} visible file(s)',
      if (bootstrap.injectedFileCount != null)
        copy.isChinese
            ? '注入 ${bootstrap.injectedFileCount} 个'
            : '${bootstrap.injectedFileCount} injected',
      if (bootstrap.omittedFileCount != null)
        copy.isChinese
            ? '省略 ${bootstrap.omittedFileCount} 个'
            : '${bootstrap.omittedFileCount} omitted',
      if (bootstrap.truncatedFileCount != null)
        copy.isChinese
            ? '截断 ${bootstrap.truncatedFileCount} 个'
            : '${bootstrap.truncatedFileCount} truncated',
    ];
    final List<String> files = bootstrap.files
        .map((file) {
          final List<String> suffix = <String>[
            if (file.injectedCharCount != null)
              copy.isChinese
                  ? '注入 ${file.injectedCharCount}'
                  : 'injected ${file.injectedCharCount}',
            if (file.sourceCharCount != null)
              copy.isChinese
                  ? '原始 ${file.sourceCharCount}'
                  : 'source ${file.sourceCharCount}',
            if (file.truncated == true)
              copy.isChinese ? '已截断' : 'truncated',
          ];
          final String detail = suffix.isEmpty
              ? ''
              : copy.isChinese
              ? '，${suffix.join('，')}'
              : ' (${suffix.join(', ')})';
          return '${file.name} (${file.relativePath})$detail';
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
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
        copy.isChinese
            ? (durableCompaction.compactedThisRun! ? '本轮已压缩' : '本轮未压缩')
            : (durableCompaction.compactedThisRun!
                  ? 'Compacted this run'
                  : 'No compaction this run'),
      if (durableCompaction.sourceTranscriptMessageCount != null &&
          durableCompaction.retainedTranscriptMessageCount != null)
        copy.isChinese
            ? '保留 ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} 条消息'
            : 'Retained ${durableCompaction.retainedTranscriptMessageCount}/${durableCompaction.sourceTranscriptMessageCount} transcript messages',
      if (durableCompaction.latestCompactedMessageCount != null)
        copy.isChinese
            ? '最近压缩 ${durableCompaction.latestCompactedMessageCount} 条'
            : 'Latest compacted ${durableCompaction.latestCompactedMessageCount} message(s)',
    ];
    final List<String> summaryCounts = <String>[
      if (durableCompaction.includedSummaryCount != null)
        copy.isChinese
            ? '纳入摘要 ${durableCompaction.includedSummaryCount} 个'
            : '${durableCompaction.includedSummaryCount} included summary(s)',
      if (durableCompaction.totalSummaryCount != null)
        copy.isChinese
            ? '总摘要 ${durableCompaction.totalSummaryCount} 个'
            : '${durableCompaction.totalSummaryCount} total summary(ies)',
      if (durableCompaction.totalCompactedMessageCount != null)
        copy.isChinese
            ? '累计压缩 ${durableCompaction.totalCompactedMessageCount} 条'
            : '${durableCompaction.totalCompactedMessageCount} total compacted message(s)',
    ];
    final List<String> pressure = <String>[
      if (_nonEmpty(durableCompaction.triggerStage) != null)
        copy.isChinese
            ? '触发 ${durableCompaction.triggerStage}'
            : 'Trigger: ${durableCompaction.triggerStage}',
      if (_nonEmpty(durableCompaction.executionMode) != null)
        copy.isChinese
            ? '模式 ${durableCompaction.executionMode}'
            : 'Mode: ${durableCompaction.executionMode}',
      if (durableCompaction.contextWindowTokens != null)
        copy.isChinese
            ? '窗口 ${durableCompaction.contextWindowTokens}'
            : 'Window ${durableCompaction.contextWindowTokens}',
      if (durableCompaction.previousContextWindowTokens != null)
        copy.isChinese
            ? '原窗口 ${durableCompaction.previousContextWindowTokens}'
            : 'Previous window ${durableCompaction.previousContextWindowTokens}',
      if (durableCompaction.autoCompactTokenLimit != null)
        copy.isChinese
            ? '阈值 ${durableCompaction.autoCompactTokenLimit}'
            : 'Threshold ${durableCompaction.autoCompactTokenLimit}',
      if (durableCompaction.estimatedReplayTokens != null)
        copy.isChinese
            ? '估算 ${durableCompaction.estimatedReplayTokens}'
            : 'Estimated ${durableCompaction.estimatedReplayTokens}',
      if (durableCompaction.tokenThresholdTriggered != null)
        copy.isChinese
            ? (durableCompaction.tokenThresholdTriggered! ? '已达阈值' : '未达阈值')
            : (durableCompaction.tokenThresholdTriggered!
                  ? 'Threshold triggered'
                  : 'Threshold not triggered'),
      if (durableCompaction.smallerWindowModelSwitchDetected == true)
        copy.isChinese
            ? '检测到更小窗口模型'
            : 'Smaller-window model switch',
    ];
    final remote = durableCompaction.remoteCompaction;
    final List<String> remoteState = <String>[];
    final List<String> remoteCounts = <String>[];
    if (remote != null) {
      if (remote.used != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.used! ? '已使用远端压缩' : '未使用远端压缩')
              : (remote.used! ? 'used' : 'not used'),
        );
      }
      if (remote.supported != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.supported! ? '支持' : '不支持')
              : (remote.supported! ? 'supported' : 'unsupported'),
        );
      }
      if (remote.requested != null) {
        remoteState.add(
          copy.isChinese
              ? (remote.requested! ? '已请求' : '未请求')
              : (remote.requested! ? 'requested' : 'not requested'),
        );
      }
      final remoteTriggerStage = _nonEmpty(remote.triggerStage);
      if (remoteTriggerStage != null) {
        remoteState.add(
          copy.isChinese
              ? '触发 $remoteTriggerStage'
              : 'trigger $remoteTriggerStage',
        );
      }
      if (remote.outputItemCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '输出 ${remote.outputItemCount}'
              : 'output ${remote.outputItemCount}',
        );
      }
      if (remote.compactionItemCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '压缩项 ${remote.compactionItemCount}'
              : 'compaction ${remote.compactionItemCount}',
        );
      }
      if (remote.encryptedContentCount != null) {
        remoteCounts.add(
          copy.isChinese
              ? '加密内容 ${remote.encryptedContentCount}'
              : 'encrypted ${remote.encryptedContentCount}',
        );
      }
      final fallbackReason = _nonEmpty(remote.fallbackReason);
      if (fallbackReason != null) {
        remoteCounts.add(
          copy.isChinese
              ? '回退 $fallbackReason'
              : 'fallback $fallbackReason',
        );
      }
    }
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
      summaryCounts.isEmpty
          ? null
          : summaryCounts.join(copy.isChinese ? '，' : ', '),
      pressure.isEmpty
          ? null
          : pressure.join(copy.isChinese ? '，' : ', '),
      remoteState.isEmpty
          ? null
          : copy.isChinese
          ? '远端压缩：${remoteState.join('，')}'
          : 'Remote compaction: ${remoteState.join(', ')}',
      remoteCounts.isEmpty
          ? null
          : copy.isChinese
          ? '远端压缩明细：${remoteCounts.join('，')}'
          : 'Remote compaction details: ${remoteCounts.join(', ')}',
      durableCompaction.latestCompactedAtEpochMs == null
          ? null
          : copy.isChinese
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
        copy.isChinese
            ? '可见 ${skillInventory.visibleSkillCount} 个'
            : '${skillInventory.visibleSkillCount} visible',
      if (skillInventory.injectedSkillCount != null)
        copy.isChinese
            ? '注入 ${skillInventory.injectedSkillCount} 个'
            : '${skillInventory.injectedSkillCount} injected',
      if (skillInventory.omittedSkillCount != null)
        copy.isChinese
            ? '省略 ${skillInventory.omittedSkillCount} 个'
            : '${skillInventory.omittedSkillCount} omitted',
      if (skillInventory.implicitSkillCount != null)
        copy.isChinese
            ? '隐式 ${skillInventory.implicitSkillCount} 个'
            : '${skillInventory.implicitSkillCount} implicit',
      if (skillInventory.invalidSkillCount != null)
        copy.isChinese
            ? '无效 ${skillInventory.invalidSkillCount} 个'
            : '${skillInventory.invalidSkillCount} invalid',
    ];
    final String? omittedTrace = skillInventory.omittedTraceSkillCount == null
        ? null
        : copy.isChinese
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
          return parts.join(copy.isChinese ? '，' : ' · ');
        })
        .toList(growable: false);
    return _joinTraceSections(<String?>[
      counts.isEmpty ? null : counts.join(copy.isChinese ? '，' : ', '),
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
        copy.isChinese
            ? '名称 ${activeSkill.name}'
            : 'Name: ${activeSkill.name}',
      if (_nonEmpty(activeSkill.relativePath) != null)
        copy.isChinese
            ? '路径 ${activeSkill.relativePath}'
            : 'Path: ${activeSkill.relativePath}',
      if (_nonEmpty(activeSkill.activationSource) != null)
        copy.isChinese
            ? '来源 ${activeSkill.activationSource}'
            : 'Activation: ${activeSkill.activationSource}',
      if (_nonEmpty(activeSkill.executionContext) != null)
        copy.isChinese
            ? '上下文 ${activeSkill.executionContext}'
            : 'Context: ${activeSkill.executionContext}',
      if (activeSkill.toolRestrictionEnabled != null)
        copy.isChinese
            ? (activeSkill.toolRestrictionEnabled! ? '已启用工具限制' : '未启用工具限制')
            : (activeSkill.toolRestrictionEnabled!
                  ? 'Tool restriction enabled'
                  : 'Tool restriction disabled'),
      if (activeSkill.pinned != null)
        copy.isChinese
            ? (activeSkill.pinned! ? '已固定' : '未固定')
            : (activeSkill.pinned! ? 'Pinned' : 'Not pinned'),
      if (activeSkill.truncated != null)
        copy.isChinese
            ? (activeSkill.truncated! ? '胶囊已截断' : '胶囊未截断')
            : (activeSkill.truncated! ? 'Capsule truncated' : 'Capsule intact'),
    ];
    return _joinTraceSections(<String?>[
      summary.isEmpty ? null : summary.join(copy.isChinese ? '，' : ', '),
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
        copy.chatRunWorkingLabel;
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
        ? (copy.isChinese
              ? '审批已通过，正在继续执行 $label。'
              : 'Approval granted. Resuming $label.')
        : (copy.isChinese
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
      copy.isChinese ? '运行已中断' : 'Run interrupted';

  String _retryInterruptedRunLabel() =>
      copy.isChinese ? '重新启动' : 'Restart run';

  String _pausedRunLabel() => copy.chatRunAwaitingDirectionLabel;

  String _interruptedRunBody(OpenCrayChatRunSnapshot run) {
    final body = run.errorMessage?.trim();
    if (body != null && body.isNotEmpty) {
      return body;
    }
    return copy.isChinese
        ? '宿主重建时这次运行被显式中断，避免从头静默重跑。需要你明确触发后才会继续。'
        : 'This run was interrupted during host recovery to avoid silently rerunning from the beginning. Restart it explicitly when you want to continue.';
  }

  String _pausedRunBody() => copy.chatRunLlmRetryPausedBody;

  String _deferredApprovalDecisionBody() =>
      copy.chatRunApprovalDecisionDeferredBody;

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
}
