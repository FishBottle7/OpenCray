// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatMessageActions on _OpenCrayChatFeatureState {
  void _dismissMessageMenu() {
    if (_activeMessageMenu == null) {
      return;
    }
    final _ActiveChatMessageMenu menu = _activeMessageMenu!;
    setState(() {
      _suppressNextTransientUiDismiss = false;
      _activeMessageMenu = null;
      _exitingMessageMenu = menu;
    });
    _scheduleMessageMenuExitClear();
  }

  void _scheduleMessageMenuExitClear() {
    _messageMenuExitEpoch += 1;
    final int epoch = _messageMenuExitEpoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _chatMessageMenuExitDuration,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      if (mounted && epoch == _messageMenuExitEpoch) {
        setState(() {
          _exitingMessageMenu = null;
        });
      }
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted ||
          epoch != _messageMenuExitEpoch ||
          _activeMessageMenu != null) {
        return;
      }
      setState(() {
        _exitingMessageMenu = null;
      });
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
    final _ActiveChatMessageMenu? menu = _activeMessageMenu;
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
      if (menu != null) {
        _exitingMessageMenu = menu;
      }
    });
    if (menu != null) {
      _scheduleMessageMenuExitClear();
    }
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
    _selectedTextVersionByMessageId[message.messageId] =
        (_selectedTextVersionByMessageId[message.messageId] ?? 0) + 1;
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
    final String fallbackSelectedText = menu.selectedText?.trim() ?? '';
    final int liveSelectionVersion =
        _selectedTextVersionByMessageId[menu.message.messageId] ?? 0;
    final bool canUseLiveSelection =
        fallbackSelectedText.isNotEmpty ||
        liveSelectionVersion > menu.selectionVersionAtOpen;
    if (canUseLiveSelection && liveSelectedText.isNotEmpty) {
      return OpenCrayMarkdownSelectionSnapshot(
        plainText: liveSelectedText,
        range: _selectedTextRangeByMessageId[menu.message.messageId],
      );
    }
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
    final Set<String> deleteIdSet = _deleteTargetMessageIdsForMessages(
      _selectedMessagesInOrder,
    )..addAll(selectedIds);
    final List<String> deleteIds = <String>[
      for (final ChatMessageData message in _state.messages)
        if (deleteIdSet.contains(message.messageId.trim()))
          message.messageId.trim(),
    ];
    for (final String selectedId in selectedIds) {
      if (!deleteIds.contains(selectedId)) {
        deleteIds.add(selectedId);
      }
    }
    final String sessionId = _activeSessionId;
    final Set<String> deletingIds = _stageMessageDeleteMotion(
      sessionId,
      deleteIds,
    );
    if (deletingIds.isEmpty) {
      return;
    }
    await _waitForMessageDeleteMotion();
    if (!mounted) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _forgetDeletingMessageIds(sessionId, deletingIds);
        _rememberLocallyDeletedMessages(sessionId, deletingIds);
        _state = _applyLocalDeletionTombstones(_state);
      });
      final Set<String> pendingIds = <String>{...deletingIds};
      final Set<String> failedOrUnsentIds = <String>{};
      for (final String messageId in deleteIds) {
        if (!deletingIds.contains(messageId)) {
          continue;
        }
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
      _forgetDeletingMessageIds(sessionId, deletingIds);
      _state = _state.copyWith(
        messages: _state.messages
            .where((message) => !deletingIds.contains(message.messageId.trim()))
            .toList(growable: false),
      );
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
      _messageMenuExitEpoch += 1;
      _exitingMessageMenu = null;
      _activeMessageMenu = _ActiveChatMessageMenu(
        message: message,
        bubbleRect: bubbleRect,
        selectionVersionAtOpen:
            _selectedTextVersionByMessageId[message.messageId] ?? 0,
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
    final Set<String> deleteIdSet = _deleteTargetMessageIdsForMessage(message);
    final List<String> deleteIds = deleteIdSet.toList(growable: false);
    if (!deleteIds.contains(messageId)) {
      deleteIds.add(messageId);
    }
    final String sessionId = _activeSessionId;
    final Set<String> deletingIds = _stageMessageDeleteMotion(
      sessionId,
      deleteIds,
    );
    if (deletingIds.isEmpty) {
      return;
    }
    await _waitForMessageDeleteMotion();
    if (!mounted) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      setState(() {
        _forgetDeletingMessageIds(sessionId, deletingIds);
        _rememberLocallyDeletedMessages(sessionId, deletingIds);
        _state = _applyLocalDeletionTombstones(_state);
      });
      final Set<String> pendingIds = <String>{...deletingIds};
      final Set<String> failedOrUnsentIds = <String>{};
      try {
        for (final String deletedMessageId in deleteIds) {
          if (!deletingIds.contains(deletedMessageId)) {
            continue;
          }
          await bridge.deleteChatMessage(
            sessionId: sessionId,
            messageId: deletedMessageId,
          );
          pendingIds.remove(deletedMessageId);
        }
      } catch (_) {
        if (!mounted) {
          return;
        }
        failedOrUnsentIds.addAll(pendingIds);
        _forgetLocallyDeletedMessages(sessionId, failedOrUnsentIds);
        _applyHostState();
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _forgetDeletingMessageIds(sessionId, deletingIds);
      _state = _state.copyWith(
        messages: _state.messages
            .where(
              (candidate) => !deletingIds.contains(candidate.messageId.trim()),
            )
            .toList(growable: false),
      );
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
}
