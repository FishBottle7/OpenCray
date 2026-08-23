// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatRealtimeIngestionActions on _OpenCrayChatFeatureState {
  void _handleChatSnapshot(OpenCrayChatSnapshot snapshot) {
    if (!shouldReplaceObservedChatSnapshot(_latestChatSnapshot, snapshot)) {
      return;
    }
    final OpenCrayChatRuntimeSnapshot? embeddedRuntime =
        snapshot.runtimeActivity;
    if (embeddedRuntime != null) {
      final OpenCrayChatRuntimeSnapshot resolvedRuntime =
          _latestChatRuntimeSnapshot != null &&
              !_runtimeSnapshotsShareSession(
                _latestChatRuntimeSnapshot!,
                embeddedRuntime,
              ) &&
              embeddedRuntime.sessionId.trim().isNotEmpty
          ? embeddedRuntime
          : resolveChatRuntimeSnapshot(
                  _latestChatRuntimeSnapshot,
                  embeddedRuntime,
                ) ??
                embeddedRuntime;
      _reconcileLiveAssistantDraftOverrides(resolvedRuntime);
      if (shouldReplaceObservedRuntimeSnapshot(
        _latestChatRuntimeSnapshot,
        resolvedRuntime,
      )) {
        _latestChatRuntimeSnapshot = resolvedRuntime;
        _recordRuntimeSnapshotWatermark(resolvedRuntime);
      }
    }
    _latestChatSnapshot = snapshot;
    _applyHostState();
  }

  bool _handleChatRuntimeSnapshot(
    OpenCrayChatRuntimeSnapshot snapshot, {
    bool authoritative = false,
  }) {
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final bool hasOrderedEnvelope =
        streamInstanceId.isNotEmpty && snapshot.lastSequence != null;
    if (hasOrderedEnvelope) {
      if (_runtimeSnapshotIsBehindCurrentWatermark(snapshot)) {
        return false;
      }
      authoritative = true;
    }
    final OpenCrayChatRuntimeSnapshot resolvedSnapshot = authoritative
        ? snapshot
        : _latestChatRuntimeSnapshot != null &&
              !_runtimeSnapshotsShareSession(
                _latestChatRuntimeSnapshot!,
                snapshot,
              ) &&
              snapshot.sessionId.trim().isNotEmpty
        ? snapshot
        : resolveChatRuntimeSnapshot(_latestChatRuntimeSnapshot, snapshot) ??
              snapshot;
    if (!authoritative &&
        !shouldReplaceObservedRuntimeSnapshot(
          _latestChatRuntimeSnapshot,
          resolvedSnapshot,
        )) {
      return false;
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
    _recordRuntimeSnapshotWatermark(
      resolvedSnapshot,
      authoritative: authoritative,
    );
    _queueRuntimeActivityPatch(resolvedSnapshot);
    return true;
  }

  bool _runtimeSnapshotIsBehindCurrentWatermark(
    OpenCrayChatRuntimeSnapshot snapshot,
  ) {
    final String sessionId = snapshot.sessionId.trim();
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final int? lastSequence = snapshot.lastSequence;
    if (sessionId.isEmpty || streamInstanceId.isEmpty || lastSequence == null) {
      return false;
    }
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final int? currentSequence = _runtimeEventDeltaSequenceBySession[sessionId];
    return currentStreamInstanceId == streamInstanceId &&
        currentSequence != null &&
        lastSequence < currentSequence;
  }

  void _recordRuntimeSnapshotWatermark(
    OpenCrayChatRuntimeSnapshot snapshot, {
    bool authoritative = false,
  }) {
    final String sessionId = snapshot.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final String streamInstanceId = snapshot.streamInstanceId?.trim() ?? '';
    final String previousStreamInstanceId =
        _runtimeStreamInstanceBySession[sessionId] ?? '';
    final String bridgeEpoch = snapshot.bridgeEpoch?.trim() ?? '';
    final String previousBridgeEpoch =
        _runtimeBridgeEpochBySession[sessionId] ?? '';
    final bool identityChanged =
        (streamInstanceId.isNotEmpty &&
            previousStreamInstanceId != streamInstanceId) ||
        (bridgeEpoch.isNotEmpty && previousBridgeEpoch != bridgeEpoch);
    if (identityChanged) {
      _resetRealtimeSessionIdentity(
        sessionId: sessionId,
        streamInstanceId: streamInstanceId,
        bridgeEpoch: bridgeEpoch,
      );
    }
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    final int? lastSequence = snapshot.lastSequence;
    if (lastSequence != null) {
      _runtimeEventDeltaSequenceBySession[sessionId] = math.max(
        0,
        lastSequence,
      );
    } else if (authoritative) {
      _runtimeEventDeltaSequenceBySession.remove(sessionId);
    }
  }

  String _currentRuntimeStreamInstanceId(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return '';
    }
    final String recorded =
        _runtimeStreamInstanceBySession[normalizedSessionId] ?? '';
    if (recorded.isNotEmpty) {
      return recorded;
    }
    final OpenCrayChatRuntimeSnapshot? snapshot = _latestChatRuntimeSnapshot;
    if (snapshot?.sessionId.trim() == normalizedSessionId) {
      return snapshot?.streamInstanceId?.trim() ?? '';
    }
    return '';
  }

  String _currentRuntimeBridgeEpoch(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return '';
    }
    final String recorded =
        _runtimeBridgeEpochBySession[normalizedSessionId] ?? '';
    if (recorded.isNotEmpty) {
      return recorded;
    }
    final OpenCrayChatRuntimeSnapshot? snapshot = _latestChatRuntimeSnapshot;
    if (snapshot?.sessionId.trim() == normalizedSessionId) {
      return snapshot?.bridgeEpoch?.trim() ?? '';
    }
    return '';
  }

  void _handleRuntimeEventDelta(OpenCrayChatRuntimeEventDelta delta) {
    if (!mounted) {
      return;
    }
    final String sessionId = delta.sessionId.trim();
    if (!_runtimeDeltaTargetsActiveSession(sessionId)) {
      return;
    }
    if (_runtimeEventDeltaResyncInFlight) {
      _queueRuntimeEventDeltaForResync(delta);
      return;
    }
    final String deltaStreamInstanceId = delta.streamInstanceId?.trim() ?? '';
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final String deltaBridgeEpoch = delta.bridgeEpoch?.trim() ?? '';
    final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
    if ((deltaStreamInstanceId.isNotEmpty &&
            currentStreamInstanceId.isNotEmpty &&
            deltaStreamInstanceId != currentStreamInstanceId) ||
        (deltaBridgeEpoch.isNotEmpty &&
            currentBridgeEpoch.isNotEmpty &&
            deltaBridgeEpoch != currentBridgeEpoch) ||
        (delta.lastSequence != null &&
            delta.sequence > 0 &&
            delta.lastSequence != delta.sequence)) {
      _queueRuntimeEventDeltaForResync(delta);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    final bool hasSequenceWatermark = _runtimeEventDeltaSequenceBySession
        .containsKey(sessionId);
    final int previousSequence =
        _runtimeEventDeltaSequenceBySession[sessionId] ?? 0;
    if (delta.sequence > 0 && hasSequenceWatermark) {
      if (delta.sequence <= previousSequence) {
        return;
      }
      if (delta.sequence != previousSequence + 1) {
        _queueRuntimeEventDeltaForResync(delta);
        unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        return;
      }
    }
    if (delta.runPatchMode.trim().toLowerCase() == 'snapshot') {
      _queueRuntimeEventDeltaForResync(delta);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    if (_realtimeEventIdWasSeen(sessionId, delta.eventId)) {
      _recordAppliedRuntimeDelta(delta);
      return;
    }
    if (!delta.hasRuntimeActivityPatch) {
      _recordAppliedRuntimeDelta(delta);
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
    final OpenCrayChatRuntimeSnapshot
    deltaSnapshot = OpenCrayChatRuntimeSnapshot(
      sessionId: sessionId,
      activeRuns: delta.activeRuns,
      retainedRuns: delta.retainedRuns,
      subAgents: delta.subAgents,
      events: delta.events,
      liveAssistantDrafts: delta.liveAssistantDrafts,
      streamInstanceId: delta.streamInstanceId,
      lastSequence:
          delta.lastSequence ?? (delta.sequence > 0 ? delta.sequence : null),
      bridgeInstanceId: delta.bridgeInstanceId,
      bridgeEpoch: delta.bridgeEpoch,
      hostLifecycle: delta.hostLifecycle,
      updatedAtEpochMs: math.max(delta.updatedAtEpochMs, latestDeltaEpochMs),
    );
    final OpenCrayChatRuntimeSnapshot patchedSnapshot = currentSnapshot == null
        ? deltaSnapshot
        : _mergeRuntimeDeltaSnapshot(
            currentSnapshot,
            deltaSnapshot,
            hasActiveRunsPatch:
                delta.runPatchMode.trim().toLowerCase() == 'replace' &&
                delta.hasActiveRunsPatch &&
                (delta.activeRuns.isEmpty ||
                    _runtimeRunListPatchCanReplace(
                      currentSnapshot.activeRuns,
                      deltaSnapshot.activeRuns,
                    )),
            hasRetainedRunsPatch:
                delta.runPatchMode.trim().toLowerCase() == 'replace' &&
                delta.hasRetainedRunsPatch &&
                (delta.retainedRuns.isEmpty ||
                    _runtimeRunListPatchCanReplace(
                      currentSnapshot.retainedRuns,
                      deltaSnapshot.retainedRuns,
                    )),
            hasSubAgentsPatch: delta.hasSubAgentsPatch,
            hasLiveAssistantDraftsPatch: delta.hasLiveAssistantDraftsPatch,
          );
    if (!shouldReplaceObservedRuntimeSnapshot(
      currentSnapshot,
      patchedSnapshot,
    )) {
      if (delta.hasLiveAssistantDraftsPatch) {
        final OpenCrayChatRuntimeSnapshot draftPatchedSnapshot =
            _runtimeSnapshotWithLiveDraftPatch(currentSnapshot, deltaSnapshot);
        _reconcileLiveAssistantDraftOverrides(
          draftPatchedSnapshot,
          liveAssistantDraftsAuthoritative: true,
        );
        if (_latestChatSnapshot != null) {
          _queueRuntimeActivityPatch(draftPatchedSnapshot);
        }
      }
      _recordAppliedRuntimeDelta(delta);
      return;
    }
    if (delta.hasLiveAssistantDraftsPatch) {
      _reconcileLiveAssistantDraftOverrides(
        patchedSnapshot,
        liveAssistantDraftsAuthoritative: true,
      );
    }
    _latestChatRuntimeSnapshot = patchedSnapshot;
    _recordAppliedRuntimeDelta(delta);
    if (_latestChatSnapshot == null) {
      return;
    }
    _queueRuntimeActivityPatch(patchedSnapshot);
  }

  OpenCrayChatRuntimeSnapshot _runtimeSnapshotWithLiveDraftPatch(
    OpenCrayChatRuntimeSnapshot? currentSnapshot,
    OpenCrayChatRuntimeSnapshot deltaSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot base = currentSnapshot ?? deltaSnapshot;
    return OpenCrayChatRuntimeSnapshot(
      sessionId: deltaSnapshot.sessionId.trim().isNotEmpty
          ? deltaSnapshot.sessionId
          : base.sessionId,
      activeRuns: base.activeRuns,
      retainedRuns: base.retainedRuns,
      subAgents: base.subAgents,
      events: base.events,
      liveAssistantDrafts: deltaSnapshot.liveAssistantDrafts,
      streamInstanceId: deltaSnapshot.streamInstanceId ?? base.streamInstanceId,
      lastSequence: deltaSnapshot.lastSequence ?? base.lastSequence,
      flutterAppInstanceId:
          deltaSnapshot.flutterAppInstanceId ?? base.flutterAppInstanceId,
      bridgeInstanceId:
          deltaSnapshot.bridgeInstanceId ?? base.bridgeInstanceId,
      bridgeEpoch: deltaSnapshot.bridgeEpoch ?? base.bridgeEpoch,
      hostLifecycle: deltaSnapshot.hostLifecycle ?? base.hostLifecycle,
      updatedAtEpochMs: math.max(
        base.updatedAtEpochMs,
        deltaSnapshot.updatedAtEpochMs,
      ),
    );
  }

  Future<void> _resyncRuntimeSnapshotAfterDeltaMiss() async {
    if (_runtimeEventDeltaResyncInFlight) {
      return;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    final int bindingEpoch = _bridgeBindingEpoch;
    _runtimeDeltaResyncRetryTimer?.cancel();
    _runtimeDeltaResyncRetryTimer = null;
    _runtimeEventDeltaResyncInFlight = true;
    bool resynced = false;
    try {
      final OpenCrayChatRuntimeSnapshot snapshot = await bridge
          .loadChatRuntimeSnapshot();
      if (!mounted ||
          _bridgeBindingEpoch != bindingEpoch ||
          widget.bridge != bridge) {
        return;
      }
      resynced = _handleChatRuntimeSnapshot(snapshot, authoritative: true);
    } catch (_) {}
    if (!mounted ||
        _bridgeBindingEpoch != bindingEpoch ||
        widget.bridge != bridge) {
      return;
    }
    _runtimeEventDeltaResyncInFlight = false;
    if (resynced) {
      _drainQueuedRealtimeEventsAfterResync();
    } else {
      _scheduleRuntimeDeltaResyncRetry();
    }
  }

  void _recordAppliedRuntimeDelta(OpenCrayChatRuntimeEventDelta delta) {
    final String sessionId = delta.sessionId.trim();
    if (sessionId.isEmpty) {
      return;
    }
    final String streamInstanceId = delta.streamInstanceId?.trim() ?? '';
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    final String bridgeEpoch = delta.bridgeEpoch?.trim() ?? '';
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    if (delta.sequence > 0) {
      _runtimeEventDeltaSequenceBySession[sessionId] = delta.sequence;
    }
    _markRealtimeEventIdSeen(sessionId, delta.eventId);
  }

  bool _realtimeEventIdWasSeen(String sessionId, String? eventId) {
    final String normalizedEventId = eventId?.trim() ?? '';
    if (normalizedEventId.isEmpty) {
      return false;
    }
    return _seenRealtimeEventIdsBySession[sessionId]?.contains(
          normalizedEventId,
        ) ??
        false;
  }

  void _markRealtimeEventIdSeen(String sessionId, String? eventId) {
    final String normalizedEventId = eventId?.trim() ?? '';
    if (sessionId.isEmpty || normalizedEventId.isEmpty) {
      return;
    }
    _seenRealtimeEventIdsBySession
        .putIfAbsent(sessionId, () => <String>{})
        .add(normalizedEventId);
  }

  void _queueRuntimeEventDeltaForResync(OpenCrayChatRuntimeEventDelta delta) {
    _queueRealtimeEnvelope(
      _QueuedRealtimeEnvelope.delta(
        value: delta,
        arrivalOrdinal: _queuedRealtimeArrivalOrdinal++,
      ),
    );
  }

  void _queueLiveAssistantDraftEventForResync(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    _queueRealtimeEnvelope(
      _QueuedRealtimeEnvelope.draft(
        value: event,
        arrivalOrdinal: _queuedRealtimeArrivalOrdinal++,
      ),
    );
  }

  void _resetRealtimeSessionIdentity({
    required String sessionId,
    required String streamInstanceId,
    required String bridgeEpoch,
  }) {
    _runtimeEventDeltaSequenceBySession.remove(sessionId);
    _seenRealtimeEventIdsBySession.remove(sessionId);
    _liveAssistantDraftOverridesBySession.remove(sessionId);
    _liveAssistantDraftEventEpochBySessionAndMessage.remove(sessionId);
    _liveAssistantDraftEventSequenceBySessionAndIdentity.remove(sessionId);
    _liveAssistantDraftEventClearedBySessionAndIdentity.remove(sessionId);
    _queuedRealtimeEventsAfterResync.removeWhere((queued) {
      if (queued.sessionId != sessionId) {
        return false;
      }
      final bool streamMismatch =
          streamInstanceId.isNotEmpty &&
          queued.streamInstanceId.isNotEmpty &&
          queued.streamInstanceId != streamInstanceId;
      final bool bridgeMismatch =
          bridgeEpoch.isNotEmpty &&
          queued.bridgeEpoch.isNotEmpty &&
          queued.bridgeEpoch != bridgeEpoch;
      return streamMismatch || bridgeMismatch;
    });
  }

  void _drainQueuedRealtimeEventsAfterResync() {
    if (_runtimeEventDeltaResyncInFlight) {
      return;
    }
    final List<_QueuedRealtimeEnvelope> queuedEvents =
        _queuedRealtimeEventsAfterResync.toList(growable: false)
          ..sort((left, right) {
            final int leftSequence = left.sequence;
            final int rightSequence = right.sequence;
            if (leftSequence > 0 && rightSequence > 0 &&
                leftSequence != rightSequence) {
              return leftSequence.compareTo(rightSequence);
            }
            if (leftSequence > 0 && rightSequence <= 0) {
              return -1;
            }
            if (leftSequence <= 0 && rightSequence > 0) {
              return 1;
            }
            if (leftSequence == rightSequence &&
                left.kind != right.kind) {
              return left.kind == _QueuedRealtimeKind.runtimeDelta ? -1 : 1;
            }
            return left.arrivalOrdinal.compareTo(right.arrivalOrdinal);
          });
    _queuedRealtimeEventsAfterResync.clear();
    for (final envelope in queuedEvents) {
      final String sessionId = envelope.sessionId;
      final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
        sessionId,
      );
      final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
      if ((currentStreamInstanceId.isNotEmpty &&
              envelope.streamInstanceId.isNotEmpty &&
              currentStreamInstanceId != envelope.streamInstanceId) ||
          (currentBridgeEpoch.isNotEmpty &&
              envelope.bridgeEpoch.isNotEmpty &&
              currentBridgeEpoch != envelope.bridgeEpoch)) {
        continue;
      }
      switch (envelope.value) {
        case OpenCrayChatRuntimeEventDelta delta:
          _handleRuntimeEventDelta(delta);
          break;
        case OpenCrayChatLiveAssistantDraftEvent event:
          _handleLiveAssistantDraftEvent(event);
          break;
        default:
          break;
      }
    }
  }

  void _scheduleRuntimeDeltaResyncRetry() {
    if (!mounted ||
        widget.bridge == null ||
        _runtimeDeltaResyncRetryTimer != null) {
      return;
    }
    _runtimeDeltaResyncRetryTimer = Timer(
      const Duration(milliseconds: 300),
      () {
        _runtimeDeltaResyncRetryTimer = null;
        if (mounted) {
          unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        }
      },
    );
  }

  void _handleLiveAssistantDraftEvent(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    if (_runtimeEventDeltaResyncInFlight) {
      _queueLiveAssistantDraftEventForResync(event);
      return;
    }
    final String sessionId = event.sessionId.trim();
    final String currentStreamInstanceId = _currentRuntimeStreamInstanceId(
      sessionId,
    );
    final String eventStreamInstanceId = event.streamInstanceId?.trim() ?? '';
    final String currentBridgeEpoch = _currentRuntimeBridgeEpoch(sessionId);
    final String eventBridgeEpoch = event.bridgeEpoch?.trim() ?? '';
    if ((currentStreamInstanceId.isNotEmpty &&
            eventStreamInstanceId.isNotEmpty &&
            currentStreamInstanceId != eventStreamInstanceId) ||
        (currentBridgeEpoch.isNotEmpty &&
            eventBridgeEpoch.isNotEmpty &&
            currentBridgeEpoch != eventBridgeEpoch) ||
        (event.lastSequence != null &&
            event.sequence != null &&
            event.lastSequence != event.sequence)) {
      _queueLiveAssistantDraftEventForResync(event);
      unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
      return;
    }
    final int? eventSequence = event.sequence;
    final bool hasSequenceWatermark = _runtimeEventDeltaSequenceBySession
        .containsKey(sessionId);
    final int previousSequence =
        _runtimeEventDeltaSequenceBySession[sessionId] ?? 0;
    if (eventSequence != null && hasSequenceWatermark) {
      if (eventSequence <= previousSequence) {
        return;
      }
      if (eventSequence != previousSequence + 1) {
        _queueLiveAssistantDraftEventForResync(event);
        unawaited(_resyncRuntimeSnapshotAfterDeltaMiss());
        return;
      }
    }
    if (_realtimeEventIdWasSeen(sessionId, event.eventId)) {
      return;
    }
    if (!_storeLiveAssistantDraftOverride(event)) {
      return;
    }
    _recordAppliedLiveDraftEvent(event);
    if (!mounted || _latestChatSnapshot == null) {
      return;
    }
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
      streamInstanceId:
          event.streamInstanceId ?? runtimeSnapshot?.streamInstanceId,
      lastSequence:
          event.lastSequence ?? event.sequence ?? runtimeSnapshot?.lastSequence,
      flutterAppInstanceId: runtimeSnapshot?.flutterAppInstanceId,
      bridgeInstanceId:
          event.bridgeInstanceId ?? runtimeSnapshot?.bridgeInstanceId,
      bridgeEpoch: event.bridgeEpoch ?? runtimeSnapshot?.bridgeEpoch,
      hostLifecycle: runtimeSnapshot?.hostLifecycle,
      updatedAtEpochMs: math.max(
        runtimeSnapshot?.updatedAtEpochMs ?? 0,
        event.updatedAtEpochMs,
      ),
    );
    _queueRuntimeActivityPatch(effectiveRuntime);
  }

  void _recordAppliedLiveDraftEvent(OpenCrayChatLiveAssistantDraftEvent event) {
    final String sessionId = event.sessionId.trim();
    final String streamInstanceId = event.streamInstanceId?.trim() ?? '';
    final String bridgeEpoch = event.bridgeEpoch?.trim() ?? '';
    if (streamInstanceId.isNotEmpty) {
      _runtimeStreamInstanceBySession[sessionId] = streamInstanceId;
    }
    if (bridgeEpoch.isNotEmpty) {
      _runtimeBridgeEpochBySession[sessionId] = bridgeEpoch;
    }
    if (event.sequence != null && event.sequence! > 0) {
      _runtimeEventDeltaSequenceBySession[sessionId] = event.sequence!;
    }
    _markRealtimeEventIdSeen(sessionId, event.eventId);
  }

  bool _storeLiveAssistantDraftOverride(
    OpenCrayChatLiveAssistantDraftEvent event,
  ) {
    final String sessionId = event.sessionId.trim();
    final String pendingMessageId = event.pendingMessageId.trim();
    if (sessionId.isEmpty || pendingMessageId.isEmpty) {
      return false;
    }
    final String identity = _runtimeDraftIdentity(
      pendingMessageId: pendingMessageId,
      executionId: event.executionId,
    );
    final int? eventSequence = event.sequence;
    final Map<String, int> sessionEventSequences =
        _liveAssistantDraftEventSequenceBySessionAndIdentity.putIfAbsent(
          sessionId,
          () => <String, int>{},
        );
    final int? previousSequence = sessionEventSequences[identity];
    if (eventSequence != null &&
        previousSequence != null &&
        eventSequence <= previousSequence) {
      return false;
    }
    final Map<String, int> sessionEventEpochs =
        _liveAssistantDraftEventEpochBySessionAndMessage.putIfAbsent(
          sessionId,
          () => <String, int>{},
        );
    final int eventEpochMs = event.updatedAtEpochMs;
    final int previousEventEpochMs = sessionEventEpochs[identity] ?? 0;
    if (eventEpochMs > 0 && previousEventEpochMs > eventEpochMs) {
      return false;
    }
    final Map<String, bool> sessionClearedStates =
        _liveAssistantDraftEventClearedBySessionAndIdentity.putIfAbsent(
          sessionId,
          () => <String, bool>{},
        );
    if (eventSequence == null &&
        eventEpochMs > 0 &&
        previousEventEpochMs == eventEpochMs &&
        sessionClearedStates[identity] == true &&
        !event.cleared) {
      return false;
    }
    if (eventSequence != null) {
      sessionEventSequences[identity] = eventSequence;
    }
    if (eventEpochMs > 0) {
      sessionEventEpochs[identity] = eventEpochMs;
    }
    sessionClearedStates[identity] = event.cleared;
    final Map<String, OpenCrayChatLiveAssistantDraftSnapshot> sessionDrafts =
        _liveAssistantDraftOverridesBySession.putIfAbsent(
          sessionId,
          () => <String, OpenCrayChatLiveAssistantDraftSnapshot>{},
        );
    if (event.cleared) {
      sessionDrafts.remove(identity);
      if (sessionDrafts.isEmpty) {
        _liveAssistantDraftOverridesBySession.remove(sessionId);
      }
      return true;
    }
    sessionDrafts[identity] = OpenCrayChatLiveAssistantDraftSnapshot(
      runId: event.runId,
      taskId: event.taskId,
      pendingMessageId: pendingMessageId,
      text: event.text,
      updatedAtEpochMs: event.updatedAtEpochMs,
      executionId: event.executionId,
      streamInstanceId: event.streamInstanceId,
      sequence: event.sequence,
      lastSequence: event.lastSequence,
      eventId: event.eventId,
      bridgeInstanceId: event.bridgeInstanceId,
      bridgeEpoch: event.bridgeEpoch,
    );
    return true;
  }

  void _reconcileLiveAssistantDraftOverrides(
    OpenCrayChatRuntimeSnapshot? authoritativeSnapshot, {
    bool liveAssistantDraftsAuthoritative = false,
  }) {
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
    authoritativeDraftsByIdentity =
        <String, OpenCrayChatLiveAssistantDraftSnapshot>{
          for (final draft in authoritativeSnapshot!.liveAssistantDrafts)
            if (draft.pendingMessageId.trim().isNotEmpty)
              _runtimeDraftIdentity(
                pendingMessageId: draft.pendingMessageId,
                executionId: draft.executionId,
              ): draft,
        };
    final int authoritativeVersion = runtimeSnapshotVersion(
      authoritativeSnapshot,
    );
    final List<String> keysToRemove = <String>[];
    final String authoritativeStreamInstanceId =
        authoritativeSnapshot.streamInstanceId?.trim() ?? '';
    sessionDrafts.forEach((identity, overrideDraft) {
      final String overrideStreamInstanceId =
          overrideDraft.streamInstanceId?.trim() ?? '';
      if (authoritativeStreamInstanceId.isNotEmpty &&
          overrideStreamInstanceId.isNotEmpty &&
          authoritativeStreamInstanceId != overrideStreamInstanceId) {
        keysToRemove.add(identity);
        return;
      }
      final OpenCrayChatLiveAssistantDraftSnapshot? authoritativeDraft =
          authoritativeDraftsByIdentity[identity];
      if (authoritativeDraft != null &&
          _draftIsAtLeastAsNew(authoritativeDraft, overrideDraft)) {
        keysToRemove.add(identity);
        return;
      }
      if (authoritativeDraft == null &&
          (liveAssistantDraftsAuthoritative ||
              authoritativeVersion >= overrideDraft.updatedAtEpochMs)) {
        keysToRemove.add(identity);
      }
    });
    for (final String key in keysToRemove) {
      sessionDrafts.remove(key);
    }
    if (sessionDrafts.isEmpty) {
      _liveAssistantDraftOverridesBySession.remove(sessionId);
    }
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
    final bool wasPinnedToBottom = _isChatScrollPinnedToBottom();
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
      _scheduleScrollToBottom(
        onlyIfPinned: true,
        wasPinnedToBottom: wasPinnedToBottom,
      );
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
    final String expectedSessionId = _runtimeProjectionExpectedSessionId(
      snapshot,
    );
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(
          expectedSessionId: expectedSessionId,
          embedded: snapshot.runtimeActivity,
          streamed: runtimeSnapshot,
        );
    final OpenCrayChatSnapshot projectionSnapshot =
        expectedSessionId.isNotEmpty &&
            expectedSessionId != _snapshotActiveSessionId(snapshot)
        ? _projectionSnapshotForLocalSession(
            source: snapshot,
            runtimeSnapshot: effectiveRuntime,
          )
        : snapshot;
    final _RuntimeProjectionPatch runtimeProjection = _mapRuntimeProjection(
      projectionSnapshot,
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
    final bool wasPinnedToBottom = _isChatScrollPinnedToBottom();
    final bool shouldScrollToBottom =
        nextState.messages.length > _state.messages.length ||
        nextState.runTraces.length > _state.runTraces.length;
    final bool hasOpenInspector =
        _RunTraceBubbleState.hasOpenInspectorForTraces(nextState.runTraces);
    if (!hasOpenInspector &&
        _runtimeProjectionVisibleStateEquivalent(_state, nextState)) {
      _syncSandboxSessionAutoRefresh();
      _syncSandboxSessionLifecycleAutoRefresh();
      return;
    }
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
      _scheduleScrollToBottom(
        animated: false,
        onlyIfPinned: true,
        wasPinnedToBottom: wasPinnedToBottom,
      );
    }
    _syncSandboxSessionAutoRefresh();
    _syncSandboxSessionLifecycleAutoRefresh();
  }
}
