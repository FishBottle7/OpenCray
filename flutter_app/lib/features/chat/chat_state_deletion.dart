// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatStateDeletionActions on _OpenCrayChatFeatureState {
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

  Set<String> _deletingMessageIdsForSession(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    return _deletingMessageIdsBySession[normalizedSessionId] ??
        const <String>{};
  }

  void _rememberDeletingMessageIds(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return;
    }
    final Set<String> normalizedIds = messageIds
        .map((messageId) => messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    if (normalizedIds.isEmpty) {
      return;
    }
    _deletingMessageIdsBySession
        .putIfAbsent(normalizedSessionId, () => <String>{})
        .addAll(normalizedIds);
  }

  void _forgetDeletingMessageIds(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    final Set<String>? deletingIds =
        _deletingMessageIdsBySession[normalizedSessionId];
    if (deletingIds == null) {
      return;
    }
    for (final String messageId in messageIds) {
      deletingIds.remove(messageId.trim());
    }
    if (deletingIds.isEmpty) {
      _deletingMessageIdsBySession.remove(normalizedSessionId);
    }
  }

  Set<String> _stageMessageDeleteMotion(
    String sessionId,
    Iterable<String> messageIds,
  ) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    final Set<String> visibleMessageIds = _state.messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final Set<String> alreadyDeleting = _deletingMessageIdsForSession(
      normalizedSessionId,
    );
    final Set<String> deletingIds = messageIds
        .map((messageId) => messageId.trim())
        .where(
          (messageId) =>
              messageId.isNotEmpty &&
              visibleMessageIds.contains(messageId) &&
              !alreadyDeleting.contains(messageId),
        )
        .toSet();
    if (deletingIds.isEmpty) {
      return const <String>{};
    }
    setState(() {
      _rememberDeletingMessageIds(normalizedSessionId, deletingIds);
      _removeSelectionForMessages(deletingIds);
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
    });
    return deletingIds;
  }

  Future<void> _waitForMessageDeleteMotion() async {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _chatMessageDeleteMotionDuration,
    );
    if (duration == Duration.zero) {
      return;
    }
    await Future<void>.delayed(duration);
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
      _selectedTextVersionByMessageId.remove(normalizedMessageId);
    }
  }

  Set<String> _deleteTargetMessageIdsForMessages(
    Iterable<ChatMessageData> messages,
  ) {
    final Set<String> targetIds = <String>{};
    for (final message in messages) {
      targetIds.addAll(_deleteTargetMessageIdsForMessage(message));
    }
    return targetIds;
  }

  Set<String> _deleteTargetMessageIdsForMessage(ChatMessageData message) {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty) {
      return const <String>{};
    }
    final String anchorMessageId = _agentTurnDeleteAnchorMessageId(message);
    if (anchorMessageId.isEmpty) {
      return <String>{messageId};
    }
    return _agentMessageIdsForTurnAnchor(anchorMessageId)..add(messageId);
  }

  String _agentTurnDeleteAnchorMessageId(ChatMessageData message) {
    final String messageId = message.messageId.trim();
    if (messageId.isEmpty || message.kind != ChatMessageKind.inbound) {
      return '';
    }
    if (message.runtimeAnchorMessageId.trim().isNotEmpty ||
        _isRuntimeProjectedAgentMessageId(messageId)) {
      return '';
    }
    if (_state.messages.any(
          (candidate) => candidate.runtimeAnchorMessageId.trim() == messageId,
        ) ||
        _state.runTraces.any(
          (trace) => trace.anchorMessageId.trim() == messageId,
        )) {
      return messageId;
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (runtimeSnapshot != null &&
        _visibleRuns(
          runtimeSnapshot,
        ).any((run) => run.pendingMessageId?.trim() == messageId)) {
      return messageId;
    }
    final int anchorIndex = _state.messages.indexWhere(
      (candidate) => candidate.messageId.trim() == messageId,
    );
    if (anchorIndex <= 0) {
      return '';
    }
    for (int index = anchorIndex - 1; index >= 0; index -= 1) {
      final ChatMessageData candidate = _state.messages[index];
      if (candidate.kind == ChatMessageKind.outbound) {
        break;
      }
      if (candidate.kind == ChatMessageKind.inbound &&
          _isRuntimeProjectedAgentMessageId(candidate.messageId.trim())) {
        return messageId;
      }
    }
    return '';
  }

  Set<String> _agentMessageIdsForTurnAnchor(String anchorMessageId) {
    final String normalizedAnchorMessageId = anchorMessageId.trim();
    if (normalizedAnchorMessageId.isEmpty) {
      return const <String>{};
    }
    final Set<String> messageIds = <String>{normalizedAnchorMessageId};
    for (final ChatMessageData message in _state.messages) {
      final String messageId = message.messageId.trim();
      if (messageId.isEmpty) {
        continue;
      }
      if (messageId == normalizedAnchorMessageId ||
          message.runtimeAnchorMessageId.trim() == normalizedAnchorMessageId) {
        messageIds.add(messageId);
      }
    }
    final int anchorIndex = _state.messages.indexWhere(
      (message) => message.messageId.trim() == normalizedAnchorMessageId,
    );
    if (anchorIndex >= 0) {
      for (int index = anchorIndex - 1; index >= 0; index -= 1) {
        final ChatMessageData candidate = _state.messages[index];
        if (candidate.kind == ChatMessageKind.outbound) {
          break;
        }
        final String candidateId = candidate.messageId.trim();
        if (candidate.kind == ChatMessageKind.inbound &&
            candidateId.isNotEmpty) {
          messageIds.add(candidateId);
        }
      }
    }
    return messageIds;
  }

  List<ChatPendingApprovalData> _visiblePendingApprovals(
    List<ChatPendingApprovalData> approvals,
  ) {
    if (_locallyDismissedApprovalIds.isEmpty) {
      return approvals;
    }
    return approvals
        .where(
          (approval) =>
              !_locallyDismissedApprovalIds.contains(approval.approvalId),
        )
        .toList(growable: false);
  }

  ChatFeatureState _applyLocalDeletionTombstones(ChatFeatureState state) {
    final String sessionId = _sessionIdForState(state).trim();
    final bool selectedSessionDeleted =
        sessionId.isNotEmpty && _locallyDeletedSessionIds.contains(sessionId);
    final Set<String> locallyDeletedMessageIds = sessionId.isEmpty
        ? const <String>{}
        : _locallyDeletedMessageIdsBySession[sessionId] ?? const <String>{};
    List<ChatMessageData> messages = state.messages;
    List<ChatRunTraceData> runTraces = state.runTraces;
    List<ChatPendingApprovalData> pendingApprovals = state.pendingApprovals;
    ChatComposerState composer = state.composer;
    if (selectedSessionDeleted) {
      messages = const <ChatMessageData>[];
      runTraces = const <ChatRunTraceData>[];
      pendingApprovals = const <ChatPendingApprovalData>[];
      composer = composer.copyWith(todos: const <ChatTodoItemData>[]);
    }
    pendingApprovals = _visiblePendingApprovals(pendingApprovals);
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
      variant: threadEmpty && composer.todos.isEmpty && pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      messages: messages,
      runTraces: runTraces,
      pendingApprovals: pendingApprovals,
      composer: composer,
      drawer: drawer,
      emptyThreadHeight: threadEmpty ? 260 : 0,
    );
  }

  void _pruneLocalDeletionTombstones(OpenCrayChatSnapshot snapshot) {
    final Set<String> snapshotApprovalIds = snapshot.pendingApprovals
        .map(
          (approval) => approval.runId.trim().isNotEmpty
              ? approval.runId.trim()
              : approval.taskId.trim(),
        )
        .where((approvalId) => approvalId.isNotEmpty)
        .toSet();
    _approvalResolutionById.removeWhere(
      (approvalId, _) => !snapshotApprovalIds.contains(approvalId),
    );
    _locallyDismissedApprovalIds.removeWhere(
      (approvalId) => !snapshotApprovalIds.contains(approvalId),
    );
    final List<String> staleApprovalTimers = _approvalResolutionDismissTimers
        .keys
        .where((approvalId) => !snapshotApprovalIds.contains(approvalId))
        .toList(growable: false);
    for (final String approvalId in staleApprovalTimers) {
      _approvalResolutionDismissTimers.remove(approvalId)?.cancel();
    }

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
    final Set<String> runtimeTombstoneIds =
        _runtimeProjectionTombstoneIdsForSession(sessionId);
    deletedMessageIds.removeWhere(
      (messageId) =>
          !snapshotMessageIds.contains(messageId) &&
          !runtimeTombstoneIds.contains(messageId),
    );
    if (deletedMessageIds.isEmpty) {
      _locallyDeletedMessageIdsBySession.remove(sessionId);
    }
  }

  Set<String> _runtimeProjectionTombstoneIdsForSession(String sessionId) {
    final String normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty) {
      return const <String>{};
    }
    final OpenCrayChatRuntimeSnapshot? runtimeSnapshot =
        _latestChatRuntimeSnapshot ?? _latestChatSnapshot?.runtimeActivity;
    if (runtimeSnapshot == null ||
        runtimeSnapshot.sessionId.trim() != normalizedSessionId) {
      return const <String>{};
    }
    final Set<String> messageIds = <String>{};
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
    for (final OpenCrayChatRunSnapshot run in visibleRuns) {
      final String pendingMessageId = run.pendingMessageId?.trim() ?? '';
      if (pendingMessageId.isNotEmpty) {
        messageIds.add(pendingMessageId);
      }
      for (final OpenCrayChatManagedProcessSnapshot process
          in run.managedProcesses) {
        messageIds.addAll(
          _projectedManagedProcessMessageIds(run: run, process: process),
        );
      }
    }
    for (final OpenCrayChatRuntimeEventSnapshot event
        in runtimeSnapshot.events) {
      if (event.kind != 'assistant_phase' ||
          event.isFinal == true ||
          _hideAssistantPhaseBubble(event)) {
        continue;
      }
      final OpenCrayChatRunSnapshot? run =
          visibleRunsByRunId[event.runId.trim()] ??
          visibleRunsByTaskId[event.taskId.trim()];
      if (run == null || !run.matchesRuntimeEvent(event)) {
        continue;
      }
      messageIds.addAll(_assistantPhaseMessageIds(event));
    }
    return messageIds;
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
}
