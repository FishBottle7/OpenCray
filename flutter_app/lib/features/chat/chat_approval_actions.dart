// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatApprovalActions on _OpenCrayChatFeatureState {
  Future<void> _approvePendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.approved,
      action: (bridge) => bridge.approveChatApproval(approval.approvalId),
    );
  }

  Future<void> _approvePendingApprovalForSession(
    ChatPendingApprovalData approval,
  ) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.approvedForSession,
      action: (bridge) =>
          bridge.approveChatApprovalForSession(approval.approvalId),
    );
  }

  Future<void> _approvePendingApprovalAsBatch(
    ChatPendingApprovalData approval,
  ) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.approved,
      action: (bridge) => bridge.approveChatApprovalAsBatch(approval.approvalId),
    );
  }

  Future<void> _rejectPendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      resolutionKind: _ApprovalResolutionKind.rejected,
      action: (bridge) => bridge.rejectChatApproval(approval.approvalId),
    );
  }

  Future<void> _runApprovalAction({
    required String approvalId,
    required _ApprovalResolutionKind resolutionKind,
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
      if (!mounted) {
        return;
      }
      setState(() {
        _approvalResolutionById[approvalId] = resolutionKind;
      });
      _scheduleApprovalResolutionDismiss(approvalId);
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

  void _scheduleApprovalResolutionDismiss(String approvalId) {
    _approvalResolutionDismissTimers.remove(approvalId)?.cancel();
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.panel,
    );
    final Duration holdDuration = duration == Duration.zero
        ? Duration.zero
        : duration + const Duration(milliseconds: 280);
    if (holdDuration == Duration.zero) {
      _dismissResolvedApproval(approvalId);
      return;
    }
    _approvalResolutionDismissTimers[approvalId] = Timer(holdDuration, () {
      _approvalResolutionDismissTimers.remove(approvalId);
      _dismissResolvedApproval(approvalId);
    });
  }

  void _dismissResolvedApproval(String approvalId) {
    if (!mounted) {
      _approvalResolutionById.remove(approvalId);
      _locallyDismissedApprovalIds.add(approvalId);
      return;
    }
    setState(() {
      _approvalResolutionById.remove(approvalId);
      _locallyDismissedApprovalIds.add(approvalId);
      _state = _applyLocalDeletionTombstones(_state);
    });
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
}
