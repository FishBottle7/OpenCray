// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatSessionActions on _OpenCrayChatFeatureState {
  void _handleSessionSelected(ChatSessionListItemData session) {
    _dismissMessageMenu();
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _state = _stateForLocallySelectedSession(session.sessionId);
      });
      unawaited(_selectSessionFromBridge(bridge, session.sessionId));
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

  Future<void> _selectSessionFromBridge(
    OpenCrayHostBridge bridge,
    String sessionId,
  ) async {
    try {
      await bridge.selectChatSession(sessionId);
    } catch (_) {
      if (!mounted) {
        return;
      }
      _applyHostState();
      _showSessionActionFailed();
    }
  }

  ChatFeatureState _stateForLocallySelectedSession(String sessionId) {
    final String selectedSessionId = sessionId.trim();
    final List<ChatSessionListItemData> sessions = _state.drawer.sessions
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
        .toList(growable: false);
    final bool threadEmpty = selectedSessionId != _activeSessionId;
    return _state.copyWith(
      variant: threadEmpty ? ChatPrototypeVariant.empty : _state.variant,
      messages: threadEmpty ? const <ChatMessageData>[] : _state.messages,
      runTraces: threadEmpty ? const <ChatRunTraceData>[] : _state.runTraces,
      pendingApprovals: threadEmpty
          ? const <ChatPendingApprovalData>[]
          : _state.pendingApprovals,
      composer: threadEmpty
          ? _state.composer.copyWith(todos: const <ChatTodoItemData>[])
          : _state.composer,
      drawer: ChatSessionsDrawerState(
        eyebrow: _state.drawer.eyebrow,
        title: _state.drawer.title,
        ctaLabel: _state.drawer.ctaLabel,
        sessions: sessions,
      ),
      drawerOpen: false,
      emptyThreadHeight: threadEmpty ? 260 : _state.emptyThreadHeight,
    );
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
}
