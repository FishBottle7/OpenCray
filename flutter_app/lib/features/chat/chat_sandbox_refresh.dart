part of 'chat_feature_screen.dart';

extension _ChatSandboxRefreshActions on _OpenCrayChatFeatureState {
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
}
