import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import '../../core/models/opencray_file_image_preview.dart';
import '../../core/models/opencray_file_text_preview.dart';
import '../../core/models/opencray_file_voice_playback_source.dart';
import 'chat_models.dart';
import 'chat_seed_data.dart';
import 'chat_voice_playback.dart';

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
  final int embeddedVersion = runtimeSnapshotVersion(embedded);
  final int streamedVersion = runtimeSnapshotVersion(streamed);
  if (streamedVersion > embeddedVersion) {
    return streamed;
  }
  if (embeddedVersion > streamedVersion) {
    return embedded;
  }
  if (_visibleRunCount(embedded) != _visibleRunCount(streamed)) {
    return _visibleRunCount(embedded) < _visibleRunCount(streamed)
        ? embedded
        : streamed;
  }
  return streamed;
}

@visibleForTesting
int runtimeSnapshotVersion(OpenCrayChatRuntimeSnapshot snapshot) {
  final int latestEventEpochMs = snapshot.events.fold<int>(
    0,
    (latest, event) =>
        latest > event.emittedAtEpochMs ? latest : event.emittedAtEpochMs,
  );
  final int latestRunEpochMs = _visibleRuns(snapshot).fold<int>(
    0,
    (latest, run) =>
        latest > run.updatedAtEpochMs ? latest : run.updatedAtEpochMs,
  );
  return latestEventEpochMs > latestRunEpochMs
      ? latestEventEpochMs
      : latestRunEpochMs;
}

List<OpenCrayChatRunSnapshot> _visibleRuns(
  OpenCrayChatRuntimeSnapshot snapshot,
) => <OpenCrayChatRunSnapshot>[
  ...snapshot.activeRuns,
  ...snapshot.retainedRuns,
];

int _visibleRunCount(OpenCrayChatRuntimeSnapshot snapshot) =>
    snapshot.activeRuns.length + snapshot.retainedRuns.length;

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

  String get copyText {
    final String normalized = selectedText?.trim() ?? '';
    if (normalized.isNotEmpty) {
      return normalized;
    }
    return message.text;
  }
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
  OpenCrayChatSnapshot? _latestChatSnapshot;
  OpenCrayChatRuntimeSnapshot? _latestChatRuntimeSnapshot;
  final Set<String> _approvalTaskIdsInFlight = <String>{};
  final Set<String> _selectedMessageIds = <String>{};
  final Map<String, String> _selectedTextByMessageId = <String, String>{};
  _ActiveChatMessageMenu? _activeMessageMenu;
  double _composerHeight = 0;
  bool _todoPanelExpanded = true;

  bool get _usesHostBridge => widget.bridge != null;

  String get _activeSessionId {
    for (final session in _state.drawer.sessions) {
      if (session.isSelected) {
        return session.sessionId;
      }
    }
    if (_state.drawer.sessions.isNotEmpty) {
      return _state.drawer.sessions.first.sessionId;
    }
    return 'chat-session';
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
  }

  @override
  void dispose() {
    widget.controller?._backPressHandler = null;
    _chatScrollController.removeListener(_handleChatScrollChanged);
    _chatSubscription?.cancel();
    _chatRuntimeSubscription?.cancel();
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
      _activeMessageMenu = null;
    });
  }

  void _dismissMessageMenu() {
    if (_activeMessageMenu == null) {
      return;
    }
    setState(() {
      _activeMessageMenu = null;
    });
  }

  void _dismissTransientUi() {
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
      _activeMessageMenu = null;
    });
  }

  void _showMessageFeedback(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  void _emitSelectionHaptic() {
    unawaited(HapticFeedback.selectionClick());
  }

  void _handleMessageTextSelectionChanged(
    ChatMessageData message,
    String? selectedText,
  ) {
    if (message.messageId.isEmpty) {
      return;
    }
    final String normalized = selectedText?.trim() ?? '';
    if (normalized.isEmpty) {
      _selectedTextByMessageId.remove(message.messageId);
      return;
    }
    _selectedTextByMessageId[message.messageId] = normalized;
  }

  String _resolvedCopyTextForMenu(_ActiveChatMessageMenu menu) {
    final String liveSelectedText =
        _selectedTextByMessageId[menu.message.messageId]?.trim() ?? '';
    if (liveSelectedText.isNotEmpty) {
      return liveSelectedText;
    }
    return menu.copyText;
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
    final List<String> selectedIds = _selectedMessageIds.toList(
      growable: false,
    );
    if (selectedIds.isEmpty) {
      return;
    }
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        for (final messageId in selectedIds) {
          await bridge.deleteChatMessage(
            sessionId: _activeSessionId,
            messageId: messageId,
          );
        }
        if (!mounted) {
          return;
        }
        setState(() {
          _selectedMessageIds.clear();
        });
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showMessageFeedback(widget.copy.chatMessageActionFailed);
      }
      return;
    }
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages
            .where(
              (message) => !_selectedMessageIds.contains(message.messageId),
            )
            .toList(growable: false),
      );
      _selectedMessageIds.clear();
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
        await Clipboard.setData(
          ClipboardData(text: _resolvedCopyTextForMenu(activeMenu)),
        );
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
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.deleteChatMessage(
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
    setState(() {
      _state = _state.copyWith(
        messages: _state.messages
            .where((candidate) => candidate.messageId != message.messageId)
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
    if (bridge != null) {
      try {
        await bridge.recallChatMessage(
          sessionId: _activeSessionId,
          messageId: redoPrompt.messageId,
        );
        await bridge.submitChatMessage(redoPrompt.text);
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
    _composerController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
    _composerFocusNode.requestFocus();
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
        : _ComposerCard(
            copy: widget.copy,
            state: _state,
            controller: _composerController,
            focusNode: _composerFocusNode,
            onPlusPressed: _togglePlusMenu,
            onSendPressed: () {
              _sendCurrentState();
            },
            onAddActionSelected: _handleAddAction,
            onCommandSelected: _showCommandMenu,
            onAttachmentRemoved: _removeAttachment,
            todoPanelExpanded: _todoPanelExpanded,
            onTodoPanelToggle: _toggleTodoPanel,
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
                              voicePlaybackControllerFactory:
                                  widget.voicePlaybackControllerFactory,
                              selectedMessageIds: _selectedMessageIds,
                              busyApprovalTaskIds: _approvalTaskIdsInFlight,
                              onApproveApproval: _approvePendingApproval,
                              onRejectApproval: _rejectPendingApproval,
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
              child: GestureDetector(
                onTap: _dismissMessageMenu,
                behavior: HitTestBehavior.translucent,
                child: _ChatMessageMenuOverlay(
                  copy: widget.copy,
                  menu: _activeMessageMenu!,
                  onActionSelected: _handleMessageMenuAction,
                ),
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
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: true);
    });
  }

  void _closeDrawer() {
    setState(() {
      _activeMessageMenu = null;
      _state = _state.copyWith(drawerOpen: false);
    });
  }

  void _showEmpty() {
    final bridge = widget.bridge;
    _dismissMessageMenu();
    if (bridge != null) {
      bridge.createChatSession();
      return;
    }
    setState(() {
      _state = OpenCrayChatSeedData.empty(widget.copy);
    });
  }

  void _togglePlusMenu() {
    setState(() {
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
    setState(() {
      if (action.label == widget.copy.chatActionCommand) {
        _state = _state.copyWith(
          composer: _state.composer.copyWith(
            showAddMenu: false,
            commandOptions: OpenCrayChatSeedData.sampleCommandOptions(
              widget.copy,
            ),
            clearSelectedCommand: true,
          ),
        );
      } else {
        final currentAttachments = List<ChatAttachmentData>.of(
          _state.composer.attachments,
        );
        final attachment = _attachmentForAction(action.label);
        final alreadyPresent = currentAttachments.any(
          (ChatAttachmentData item) => item.label == attachment.label,
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
      }
    });
  }

  void _showCommandMenu() {
    setState(() {
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
      if (text.isEmpty) {
        return;
      }
      try {
        await bridge.submitChatMessage(text);
        if (!mounted) {
          return;
        }
        _composerController.clear();
      } catch (_) {
        if (!mounted) {
          return;
        }
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(widget.copy.chatSubmitFailed)));
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
    _latestChatSnapshot = snapshot;
    _applyHostState();
  }

  void _handleChatRuntimeSnapshot(OpenCrayChatRuntimeSnapshot snapshot) {
    _latestChatRuntimeSnapshot = snapshot;
    _applyHostState();
  }

  void _applyHostState() {
    if (!mounted) {
      return;
    }
    final snapshot = _latestChatSnapshot;
    if (snapshot == null) {
      return;
    }
    final ChatFeatureState nextState = _mapSnapshot(
      snapshot,
      _latestChatRuntimeSnapshot,
    );
    final bool hadTodos = _state.composer.todos.isNotEmpty;
    final bool hasTodos = nextState.composer.todos.isNotEmpty;
    final bool shouldScrollToBottom =
        nextState.messages.length > _state.messages.length ||
        nextState.runTraces.length > _state.runTraces.length ||
        nextState.pendingApprovals.length > _state.pendingApprovals.length;
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _activeMessageMenu = null;
      _state = nextState.copyWith(drawerOpen: _state.drawerOpen);
      if (!hasTodos || !hadTodos) {
        _todoPanelExpanded = true;
      }
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (shouldScrollToBottom) {
      _scheduleScrollToBottom();
    }
  }

  Future<void> _approvePendingApproval(ChatPendingApprovalData approval) async {
    await _runApprovalAction(
      approvalId: approval.approvalId,
      action: (bridge) => bridge.approveChatApproval(approval.approvalId),
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

  void _removeAttachment(ChatAttachmentData attachment) {
    setState(() {
      final attachments =
          List<ChatAttachmentData>.of(_state.composer.attachments)..removeWhere(
            (ChatAttachmentData item) => item.label == attachment.label,
          );
      _state = _state.copyWith(
        composer: _state.composer.copyWith(attachments: attachments),
      );
    });
  }

  void _toggleTodoPanel() {
    if (_state.composer.todos.isEmpty) {
      return;
    }
    setState(() {
      _todoPanelExpanded = !_todoPanelExpanded;
    });
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
    final bridge = widget.bridge;
    if (bridge != null) {
      try {
        await bridge.deleteChatSession(session.sessionId);
      } catch (_) {
        if (!mounted) {
          return;
        }
        _showSessionActionFailed();
      }
      return;
    }
    setState(() {
      final remainingSessions = _state.drawer.sessions
          .where((item) => item.sessionId != session.sessionId)
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
    final snapshot = await bridge.loadChatSnapshot();
    final runtimeSnapshot = await bridge.loadChatRuntimeSnapshot();
    if (!mounted) {
      return;
    }
    _latestChatSnapshot = snapshot;
    _latestChatRuntimeSnapshot = runtimeSnapshot;
    final ChatFeatureState nextState = _mapSnapshot(snapshot, runtimeSnapshot);
    final Set<String> retainedSelection = _selectedMessageIds
        .where(
          (messageId) => nextState.messages.any(
            (message) => message.messageId == messageId,
          ),
        )
        .toSet();
    setState(() {
      _state = nextState.copyWith(drawerOpen: _state.drawerOpen);
      _selectedMessageIds
        ..clear()
        ..addAll(retainedSelection);
    });
    if (snapshot.messages.isNotEmpty || _visibleRunCount(runtimeSnapshot) > 0) {
      _scheduleScrollToBottom(animated: false);
    }
  }

  ChatFeatureState _mapSnapshot(
    OpenCrayChatSnapshot snapshot,
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    final OpenCrayChatRuntimeSnapshot? effectiveRuntime =
        _resolveRuntimeSnapshot(snapshot.runtimeActivity, runtimeSnapshot);
    final List<ChatRunTraceData> runTraces = _mapRunTraces(effectiveRuntime);
    final List<ChatMessageData> mappedMessages = _mapMessages(
      snapshot.messages,
      hideThinkingPlaceholder: runTraces.isNotEmpty,
    );
    final Set<String> existingMessageIds = mappedMessages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();
    final List<ChatMessageData> messages = <ChatMessageData>[
      ...mappedMessages,
      ..._mapProjectedProgressMessages(effectiveRuntime, existingMessageIds),
    ];
    return ChatFeatureState(
      variant:
          messages.isEmpty &&
              runTraces.isEmpty &&
              snapshot.todos.isEmpty &&
              snapshot.pendingApprovals.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      screenTitle: snapshot.screenTitle,
      summary: ChatSessionSummary(
        title: snapshot.summary.title,
        badge: snapshot.summary.badge,
        body: snapshot.summary.body,
      ),
      messages: messages,
      runTraces: runTraces,
      composer: ChatComposerState(
        placeholder: snapshot.composerPlaceholder,
        todos: snapshot.todos
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
            .toList(growable: false),
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
      emptyThreadHeight: messages.isEmpty && runTraces.isEmpty ? 260 : 0,
      isInputEnabled: snapshot.isInputEnabled,
    );
  }

  List<ChatMessageData> _mapMessages(
    List<OpenCrayChatMessageSnapshot> messages, {
    required bool hideThinkingPlaceholder,
  }) {
    final mapped = messages
        .asMap()
        .entries
        .map(
          (entry) => ChatMessageData(
            messageId: entry.value.messageId.trim().isNotEmpty
                ? entry.value.messageId
                : 'message-${entry.key}-${entry.value.kind}',
            kind: switch (entry.value.kind) {
              'timeline' => ChatMessageKind.timeline,
              'outbound' => ChatMessageKind.outbound,
              _ => ChatMessageKind.inbound,
            },
            text: entry.value.text,
            meta: entry.value.meta,
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
          ),
        )
        .toList(growable: true);
    if (hideThinkingPlaceholder && mapped.isNotEmpty) {
      final lastMessage = mapped.last;
      if (lastMessage.kind == ChatMessageKind.inbound &&
          _thinkingPlaceholders.contains(lastMessage.text.trim())) {
        mapped.removeLast();
      }
    }
    return mapped;
  }

  List<ChatRunTraceData> _mapRunTraces(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
  ) {
    if (runtimeSnapshot == null || _visibleRunCount(runtimeSnapshot) == 0) {
      return const <ChatRunTraceData>[];
    }
    final activeRuns = _visibleRuns(runtimeSnapshot).toList(growable: false)
      ..sort(
        (left, right) =>
            left.acceptedAtEpochMs.compareTo(right.acceptedAtEpochMs),
      );
    return activeRuns
        .map((run) => _mapRunTrace(run: run, runtimeSnapshot: runtimeSnapshot))
        .toList(growable: false);
  }

  List<ChatMessageData> _mapProjectedProgressMessages(
    OpenCrayChatRuntimeSnapshot? runtimeSnapshot,
    Set<String> existingMessageIds,
  ) {
    if (runtimeSnapshot == null || runtimeSnapshot.activeRuns.isEmpty) {
      return const <ChatMessageData>[];
    }
    final Set<String> activeRunIds = runtimeSnapshot.activeRuns
        .map((run) => run.runId.trim())
        .where((runId) => runId.isNotEmpty)
        .toSet();
    if (activeRunIds.isEmpty) {
      return const <ChatMessageData>[];
    }
    final Set<String> seenMessageIds = <String>{};
    final List<ChatMessageData> projected = <ChatMessageData>[];
    final List<OpenCrayChatRuntimeEventSnapshot> sortedEvents =
        runtimeSnapshot.events.toList(growable: false)..sort(
          (left, right) =>
              left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
        );
    for (final event in sortedEvents) {
      final String runId = event.runId.trim();
      if (event.kind != 'progress' || !activeRunIds.contains(runId)) {
        continue;
      }
      final String messageId =
          'runtime-progress-$runId-${event.emittedAtEpochMs}';
      if (!seenMessageIds.add(messageId) ||
          existingMessageIds.contains(messageId)) {
        continue;
      }
      final String text = _projectedProgressMessageText(event);
      if (text.trim().isEmpty) {
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

  ChatRunTraceData _mapRunTrace({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) {
    final List<OpenCrayChatRuntimeEventSnapshot> runEvents = _runEventsFor(
      run: run,
      runtimeSnapshot: runtimeSnapshot,
    );
    final event = run.lastEvent;
    final toolName = event?.toolName?.trim();
    final bool waitingApproval = _isWaitingApproval(run);
    final List<ChatRunTraceHistoryEntry> history = _buildRunTraceHistory(
      run: run,
      runEvents: runEvents,
    );
    String compactBody(String fallbackBody) =>
        _buildCompactTraceBody(history: history, fallbackBody: fallbackBody);
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
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          history: history,
          isHighRisk:
              event.isHighRisk ||
              (waitingApproval &&
                  run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED'),
        );
      case 'approval_result':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _approvalTraceLabel(event!),
          body: compactBody(_buildApprovalPreviewBody(event)),
          history: history,
          isHighRisk: event.isHighRisk,
        );
      case 'cancelled':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _cancellationTraceLabel(event!),
          body: compactBody(_buildCancellationPreviewBody(event)),
          history: history,
        );
      case 'subagent':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _subagentTraceLabel(event!),
          body: compactBody(_buildSubagentPreviewBody(event)),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_call':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(_buildToolCallPreviewBody(event!)),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'tool_result':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
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
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_retrieval':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: toolName?.isNotEmpty == true
              ? toolName!
              : widget.copy.chatRunWorkingLabel,
          body: compactBody(_buildMemoryRetrievalPreviewBody(event!)),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'memory_write':
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _memoryMaintenanceLabel(),
          body: compactBody(_buildMemoryWritePreviewBody(event!)),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'assistant':
        final text = event?.text?.trim();
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: widget.copy.chatRunWorkingLabel,
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : widget.copy.chatRunThinkingActive,
          ),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'progress':
        final text = event?.text?.trim();
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
          label: _progressEntryLabel(event!),
          body: compactBody(
            text?.isNotEmpty == true
                ? text!
                : widget.copy.chatRunThinkingActive,
          ),
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      default:
        return ChatRunTraceData(
          runId: run.runId,
          taskId: run.taskId,
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
          history: history,
          isHighRisk:
              waitingApproval && run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
    }
  }

  List<ChatRunTraceHistoryEntry> _buildRunTraceHistory({
    required OpenCrayChatRunSnapshot run,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
  }) {
    final history = <ChatRunTraceHistoryEntry>[];
    for (int index = 0; index < runEvents.length; index += 1) {
      final mapped = _mapRunTraceHistoryEntry(
        event: runEvents[index],
        runEvents: runEvents,
        index: index,
      );
      if (mapped != null) {
        history.add(mapped);
      }
    }
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
    if (history.isEmpty) {
      history.add(
        ChatRunTraceHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: widget.copy.chatRunThinkingActive,
        ),
      );
    }
    final bool hasApprovalWaitEvent = runEvents.any(
      (event) => event.kind == 'approval_wait',
    );
    if (_isWaitingApproval(run) && !hasApprovalWaitEvent) {
      final approvalBody = run.errorMessage?.trim();
      final waitingEntry = ChatRunTraceHistoryEntry(
        label: widget.copy.chatRunWaitingApprovalLabel,
        body: approvalBody?.isNotEmpty == true
            ? approvalBody!
            : widget.copy.chatRunWaitingApprovalLabel,
        isHighRisk: run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
      );
      if (history.isEmpty ||
          history.last.label != waitingEntry.label ||
          history.last.body != waitingEntry.body) {
        history.add(waitingEntry);
      }
    }
    return history;
  }

  ChatRunTraceHistoryEntry? _mapRunTraceHistoryEntry({
    required OpenCrayChatRuntimeEventSnapshot event,
    required List<OpenCrayChatRuntimeEventSnapshot> runEvents,
    required int index,
  }) {
    final toolName = event.toolName?.trim();
    switch (event.kind) {
      case 'lifecycle':
        if (event.phase?.toLowerCase() == 'start') {
          return ChatRunTraceHistoryEntry(
            label: widget.copy.chatRunWorkingLabel,
            body: widget.copy.chatRunThinkingActive,
          );
        }
        return null;
      case 'tool_call':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        return ChatRunTraceHistoryEntry(
          label: resolvedToolName,
          body: _buildToolCallHistoryBody(event),
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
        return ChatRunTraceHistoryEntry(
          label: resolvedToolName,
          body: _buildToolResultHistoryBody(
            event: event,
            pairedToolCall: pairedToolCall,
          ),
          isHighRisk: event.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED',
        );
      case 'approval_wait':
      case 'approval_result':
        return ChatRunTraceHistoryEntry(
          label: _approvalTraceLabel(event),
          body: _buildApprovalHistoryBody(event),
          isHighRisk: event.isHighRisk,
        );
      case 'cancelled':
        return ChatRunTraceHistoryEntry(
          label: _cancellationTraceLabel(event),
          body: _buildCancellationHistoryBody(event),
        );
      case 'subagent':
        return ChatRunTraceHistoryEntry(
          label: _subagentTraceLabel(event),
          body: _buildSubagentHistoryBody(event),
        );
      case 'memory_retrieval':
        final resolvedToolName = toolName?.isNotEmpty == true
            ? toolName!
            : widget.copy.chatRunWorkingLabel;
        return ChatRunTraceHistoryEntry(
          label: resolvedToolName,
          body: _buildMemoryRetrievalHistoryBody(event),
        );
      case 'memory_write':
        return ChatRunTraceHistoryEntry(
          label: _memoryMaintenanceLabel(),
          body: _buildMemoryWriteHistoryBody(event),
        );
      case 'assistant':
        final text = event.text?.trim();
        return ChatRunTraceHistoryEntry(
          label: widget.copy.chatRunWorkingLabel,
          body: text?.isNotEmpty == true
              ? text!
              : widget.copy.chatRunThinkingActive,
        );
      case 'progress':
        final text = event.text?.trim();
        return ChatRunTraceHistoryEntry(
          label: _progressEntryLabel(event),
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
        ChatRunTraceHistoryEntry(
          label: _traceSectionLabel(english: 'Live Context', chinese: '实时上下文'),
          body: liveContextBody,
        ),
      );
    }
    if (bootstrapBody != null) {
      history.add(
        ChatRunTraceHistoryEntry(
          label: _traceSectionLabel(english: 'Bootstrap', chinese: '启动上下文'),
          body: bootstrapBody,
        ),
      );
    }
    if (memoryTraceBody != null) {
      history.add(
        ChatRunTraceHistoryEntry(
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
        ChatRunTraceHistoryEntry(
          label: _traceSectionLabel(english: 'Memory Flush', chinese: '记忆刷新'),
          body: memoryFlushBody,
        ),
      );
    }
    if (durableCompactionBody != null) {
      history.add(
        ChatRunTraceHistoryEntry(
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
        ChatRunTraceHistoryEntry(
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
        ChatRunTraceHistoryEntry(
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

  bool _isWaitingApproval(OpenCrayChatRunSnapshot run) =>
      run.errorCode == 'APPROVAL_REQUIRED' ||
      run.errorCode == 'HIGH_RISK_APPROVAL_REQUIRED';

  List<OpenCrayChatRuntimeEventSnapshot> _runEventsFor({
    required OpenCrayChatRunSnapshot run,
    required OpenCrayChatRuntimeSnapshot runtimeSnapshot,
  }) =>
      runtimeSnapshot.events
          .where((event) => event.runId == run.runId)
          .toList(growable: false)
        ..sort(
          (left, right) =>
              left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
        );

  OpenCrayChatRuntimeEventSnapshot? _findPreviousToolCall(
    List<OpenCrayChatRuntimeEventSnapshot> runEvents, {
    required int beforeIndex,
    String? toolName,
  }) {
    final String? normalizedToolName = toolName?.trim();
    for (int index = beforeIndex - 1; index >= 0; index -= 1) {
      final candidate = runEvents[index];
      if (candidate.kind != 'tool_call') {
        continue;
      }
      final String? candidateToolName = candidate.toolName?.trim();
      if (normalizedToolName == null ||
          normalizedToolName.isEmpty ||
          candidateToolName == normalizedToolName) {
        return candidate;
      }
    }
    return null;
  }

  String _buildToolCallPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
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
        _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
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
  }) {
    final List<String> bodies = history
        .where(_shouldIncludeCompactHistoryEntry)
        .map((entry) => entry.body.trim())
        .where((body) => body.isNotEmpty)
        .toList(growable: false);
    if (bodies.isEmpty) {
      return fallbackBody;
    }
    final int startIndex = bodies.length > 3 ? bodies.length - 3 : 0;
    final String compactBody = bodies.sublist(startIndex).join('\n\n');
    return compactBody.trim().isNotEmpty ? compactBody.trim() : fallbackBody;
  }

  bool _shouldIncludeCompactHistoryEntry(ChatRunTraceHistoryEntry entry) {
    final String body = entry.body.trim();
    if (body.isEmpty) {
      return false;
    }
    return !_thinkingPlaceholders.contains(body);
  }

  String _progressEntryLabel(OpenCrayChatRuntimeEventSnapshot event) {
    final String? stage = _nonEmpty(event.stage);
    if (stage != null) {
      return stage;
    }
    return widget.copy.chatRunWorkingLabel;
  }

  String _projectedProgressMessageText(OpenCrayChatRuntimeEventSnapshot event) {
    final String? stage = _nonEmpty(event.stage);
    final String body =
        _nonEmpty(event.text) ?? widget.copy.chatRunThinkingActive;
    if (stage == null) {
      return body;
    }
    return '$stage\n\n$body';
  }

  String _buildSubagentPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentContextSection(event),
      _subagentSummarySection(event),
    ]);
  }

  String _buildSubagentHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _joinTraceSections(<String?>[
      _subagentPhaseSummary(event),
      _subagentContextSection(event),
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

  String _subagentPhaseSummary(OpenCrayChatRuntimeEventSnapshot event) {
    final String actor = _subagentTraceLabel(event);
    final String? description = _nonEmpty(event.label);
    final String suffix = description == null || description == actor
        ? ''
        : widget.copy.isChinese
        ? '：$description'
        : ': $description';
    switch (_nonEmpty(event.phase)?.toLowerCase()) {
      case 'started':
        return widget.copy.isChinese
            ? '$actor 已启动$suffix'
            : '$actor started$suffix';
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

  String _buildToolCallHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
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

  String _buildToolResultHistoryBody({
    required OpenCrayChatRuntimeEventSnapshot event,
    required OpenCrayChatRuntimeEventSnapshot? pairedToolCall,
  }) {
    final String resolvedToolName =
        _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
    final String summary = _toolResultActionSummary(
      toolName: resolvedToolName,
      event: event,
      pairedToolCall: pairedToolCall,
    );
    final String? resultSummary = _toolResultMetadataSummary(
      toolName: resolvedToolName,
      event: event,
    );
    final String? errorMessage = _nonEmpty(event.errorMessage);
    final String? preview = _nonEmpty(event.contentPreview);
    return _joinTraceSections(<String?>[
      summary,
      resultSummary,
      errorMessage ??
          preview ??
          widget.copy.chatRunToolFollowUp(resolvedToolName),
    ]);
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
    return _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
  }

  String _buildCancellationPreviewBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _buildCancellationHistoryBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _cancellationEventBody(event);
  }

  String _cancellationEventBody(OpenCrayChatRuntimeEventSnapshot event) {
    return _nonEmpty(event.text) ??
        (widget.copy.isChinese ? '本次运行已取消。' : 'Run cancelled.');
  }

  String _cancellationTraceLabel(OpenCrayChatRuntimeEventSnapshot event) {
    return _nonEmpty(event.toolName) ?? widget.copy.chatRunWorkingLabel;
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
    final String fallback = widget.copy.chatRunCallingTool(toolName);
    switch (toolName) {
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
      case 'TodoWrite':
        final int todoCount = _argumentList(arguments, 'todos')?.length ?? 0;
        if (todoCount <= 0) {
          return widget.copy.isChinese ? '读取当前待办列表' : 'Read current todo list';
        }
        return widget.copy.isChinese
            ? '更新 $todoCount 条待办'
            : 'Update $todoCount todo(s)';
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
    return _toolActionSummaryFromArguments(
      toolName: toolName,
      arguments: _toolResultArgumentsFallback(toolName: toolName, event: event),
    );
  }

  String? _toolCallDetailBody({
    required String toolName,
    required String? argumentsJson,
  }) {
    final Map<String, dynamic>? arguments = _decodeJsonObject(argumentsJson);
    if (arguments == null || arguments.isEmpty) {
      return _nonEmpty(argumentsJson);
    }
    switch (toolName) {
      case 'Edit':
        return _editDetailBody(arguments);
      case 'MultiEdit':
        return _multiEditDetailBody(arguments);
      case 'Write':
        return _writeDetailBody(arguments);
      case 'Task':
        return _taskDetailBody(arguments);
      default:
        return _prettyJson(arguments);
    }
  }

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
    switch (toolName) {
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
      case 'Write':
      case 'Edit':
      case 'MultiEdit':
        final String? filePath = _resultMetadataValue(event, 'filePath');
        if (filePath == null) {
          return null;
        }
        return <String, dynamic>{'file_path': filePath};
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
      default:
        return null;
    }
  }

  String? _toolResultMetadataSummary({
    required String toolName,
    required OpenCrayChatRuntimeEventSnapshot event,
  }) {
    switch (toolName) {
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
      case 'TodoWrite':
        final int? todoCount = _resultMetadataInt(event, 'todoCount');
        final bool? mutated = _resultMetadataBool(event, 'mutated');
        if (todoCount == null) {
          return null;
        }
        if (widget.copy.isChinese) {
          if (mutated == true) {
            return '待办列表已更新，共 $todoCount 项';
          }
          return '当前待办列表共 $todoCount 项';
        }
        if (mutated == true) {
          return 'Updated the todo list to $todoCount item(s)';
        }
        return 'Current todo list has $todoCount item(s)';
      case 'Task':
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
        final String statusSummary = switch (status?.toLowerCase()) {
          'success' =>
            widget.copy.isChinese ? '$actor 已完成' : '$actor completed',
          'cancelled' =>
            widget.copy.isChinese ? '$actor 已取消' : '$actor cancelled',
          'failed' ||
          'denied' ||
          'timeout' => widget.copy.isChinese ? '$actor 失败' : '$actor failed',
          _ =>
            widget.copy.isChinese ? '$actor 已返回结果' : '$actor returned a result',
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
    '思考中',
    '思考中…',
    '思考中...',
  };

  OpenCrayChatRuntimeSnapshot? _resolveRuntimeSnapshot(
    OpenCrayChatRuntimeSnapshot? embedded,
    OpenCrayChatRuntimeSnapshot? streamed,
  ) => resolveChatRuntimeSnapshot(embedded, streamed);
}

class _ChatScrollContent extends StatelessWidget {
  const _ChatScrollContent({
    required this.bridge,
    required this.copy,
    required this.state,
    required this.voicePlaybackControllerFactory,
    required this.selectedMessageIds,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Set<String> selectedMessageIds;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, String?) onMessageTextSelectionChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text(state.screenTitle, style: _ChatTextStyles.pageTitle),
        const SizedBox(height: 20),
        _SummaryCard(summary: state.summary),
        const SizedBox(height: 20),
        if (state.messages.isEmpty &&
            state.runTraces.isEmpty &&
            state.pendingApprovals.isEmpty)
          SizedBox(height: state.emptyThreadHeight)
        else
          _MessageList(
            bridge: bridge,
            copy: copy,
            voicePlaybackControllerFactory: voicePlaybackControllerFactory,
            messages: state.messages,
            runTraces: state.runTraces,
            pendingApprovals: state.pendingApprovals,
            selectedMessageIds: selectedMessageIds,
            busyApprovalTaskIds: busyApprovalTaskIds,
            onApproveApproval: onApproveApproval,
            onRejectApproval: onRejectApproval,
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
    required this.onSessionsPressed,
    this.isSelectionMode = false,
    this.selectedCount = 0,
    this.onDonePressed,
  });

  final OpenCrayUiCopy copy;
  final String sessionButtonLabel;
  final String modeLabel;
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
        Text(modeLabel, style: _ChatTextStyles.modeLabel),
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
  const _SummaryCard({required this.summary});

  final ChatSessionSummary summary;

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
            Text(summary.body, style: _ChatTextStyles.bodyMuted),
          ],
        ),
      ),
    );
  }
}

class _PendingApprovalCard extends StatelessWidget {
  const _PendingApprovalCard({
    required this.copy,
    required this.approval,
    required this.isBusy,
    required this.onApprove,
    required this.onReject,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData approval;
  final bool isBusy;
  final VoidCallback onApprove;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
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
    final String detailMessage = message.isNotEmpty
        ? message
        : (requestSummary.isEmpty &&
              primaryDetail.isEmpty &&
              pathDetails.isEmpty &&
              workingDirectory.isEmpty &&
              reason.isEmpty)
        ? body
        : '';
    final Color accentColor = approval.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.accent;
    final Color surfaceColor = approval.isHighRisk
        ? _ChatPalette.highRiskSurface
        : Colors.white;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: approval.isHighRisk
              ? _ChatPalette.highRiskBorder
              : _ChatPalette.border,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
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
                      if (approval.toolName.trim().isNotEmpty) ...<Widget>[
                        const SizedBox(height: 6),
                        DecoratedBox(
                          decoration: BoxDecoration(
                            color: _ChatPalette.subtleSurface,
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(color: _ChatPalette.border),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 4,
                            ),
                            child: Text(
                              approval.toolName,
                              style: _ChatTextStyles.approvalAction.copyWith(
                                color: _ChatPalette.textPrimary,
                                fontSize: 12,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                if (approval.isHighRisk) ...<Widget>[
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
            if (requestSummary.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              _ApprovalDetailSection(
                label: copy.chatApprovalRequestLabel,
                value: requestSummary,
                emphasize: true,
              ),
            ],
            if (primaryDetail.isNotEmpty &&
                primaryDetail != requestSummary) ...<Widget>[
              const SizedBox(height: 8),
              _ApprovalDetailSection(
                label: copy.chatApprovalDetailsLabel,
                value: primaryDetail,
              ),
            ],
            if (pathDetails.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              _ApprovalDetailSection(
                label: copy.chatApprovalPathsLabel,
                value: pathDetails.join('\n'),
              ),
            ],
            if (workingDirectory.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              _ApprovalDetailSection(
                label: copy.chatApprovalWorkingDirectoryLabel,
                value: workingDirectory,
              ),
            ],
            if (reason.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              _ApprovalDetailSection(
                label: copy.chatApprovalReasonLabel,
                value: reason,
              ),
            ],
            if (detailMessage.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              Text(detailMessage, style: _ChatTextStyles.bodyMuted),
            ],
            const SizedBox(height: 12),
            Row(
              children: <Widget>[
                Expanded(
                  child: _ApprovalActionButton(
                    label: approval.rejectLabel,
                    foregroundColor: _ChatPalette.textSecondary,
                    backgroundColor: Colors.white,
                    borderColor: _ChatPalette.border,
                    onPressed: isBusy ? null : onReject,
                  ),
                ),
                const SizedBox(width: 10),
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
            ),
          ],
        ),
      ),
    );
  }
}

class _ApprovalDetailSection extends StatelessWidget {
  const _ApprovalDetailSection({
    required this.label,
    required this.value,
    this.emphasize = false,
  });

  final String label;
  final String value;
  final bool emphasize;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text(label, style: _ChatTextStyles.sectionLabel),
        const SizedBox(height: 4),
        DecoratedBox(
          decoration: BoxDecoration(
            color: emphasize ? _ChatPalette.subtleSurface : Colors.white,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: _ChatPalette.border),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Text(
              value,
              style: _ChatTextStyles.bodyMuted.copyWith(
                color: _ChatPalette.textPrimary,
                fontWeight: emphasize ? FontWeight.w600 : FontWeight.w500,
              ),
            ),
          ),
        ),
      ],
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
  final ValueChanged<String?> onTextSelectionChanged;

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
    );
    if (!selectionMode) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Align(alignment: alignment, child: bubble),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: GestureDetector(
        key: ValueKey<String>('chat-message-row-${message.messageId}'),
        onTap: onSelectionToggle,
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          key: ValueKey<String>('chat-message-row-bg-${message.messageId}'),
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
          decoration: BoxDecoration(
            color: isSelected
                ? _ChatPalette.selectionRowHighlight
                : Colors.transparent,
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(8, 6, 10, 6),
            child: Row(
              children: <Widget>[
                _ChatSelectionControl(
                  messageId: message.messageId,
                  isSelected: isSelected,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Align(alignment: alignment, child: bubble),
                ),
              ],
            ),
          ),
        ),
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
    required this.voicePlaybackControllerFactory,
    required this.messages,
    required this.runTraces,
    required this.pendingApprovals,
    required this.selectedMessageIds,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
  final List<ChatPendingApprovalData> pendingApprovals;
  final Set<String> selectedMessageIds;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, String?) onMessageTextSelectionChanged;

  @override
  Widget build(BuildContext context) {
    final remainingApprovals = List<ChatPendingApprovalData>.of(
      pendingApprovals,
      growable: true,
    );
    final children = <Widget>[];
    int? previousTimestampEpochMs;

    for (final message in messages) {
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
            ),
          );
        case ChatMessageKind.outbound:
          children.add(
            _ChatMessageWithTimestamp(
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
            ),
          );
      }
      if (currentTimestampEpochMs != null && currentTimestampEpochMs > 0) {
        previousTimestampEpochMs = currentTimestampEpochMs;
      }
    }

    for (final trace in runTraces) {
      children.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Align(
            alignment: Alignment.centerLeft,
            child: _RunTraceBubble(
              key: ValueKey<String>('chat-run-trace-${trace.runId}'),
              trace: trace,
            ),
          ),
        ),
      );
      final matchingApprovalIndex = remainingApprovals.indexWhere(
        (approval) =>
            approval.runId == trace.runId || approval.taskId == trace.taskId,
      );
      if (matchingApprovalIndex >= 0) {
        final approval = remainingApprovals.removeAt(matchingApprovalIndex);
        children.add(
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 292),
                child: _PendingApprovalCard(
                  copy: copy,
                  approval: approval,
                  isBusy: busyApprovalTaskIds.contains(approval.approvalId),
                  onApprove: () => onApproveApproval(approval),
                  onReject: () => onRejectApproval(approval),
                ),
              ),
            ),
          ),
        );
      }
    }

    for (final approval in remainingApprovals) {
      children.add(
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Align(
            alignment: Alignment.centerLeft,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 292),
              child: _PendingApprovalCard(
                copy: copy,
                approval: approval,
                isBusy: busyApprovalTaskIds.contains(approval.approvalId),
                onApprove: () => onApproveApproval(approval),
                onReject: () => onRejectApproval(approval),
              ),
            ),
          ),
        ),
      );
    }

    return Column(children: children);
  }
}

class _RunTraceBubble extends StatefulWidget {
  const _RunTraceBubble({super.key, required this.trace});

  final ChatRunTraceData trace;

  @override
  State<_RunTraceBubble> createState() => _RunTraceBubbleState();
}

class _RunTraceBubbleState extends State<_RunTraceBubble> {
  late final ScrollController _bodyScrollController = ScrollController();

  @override
  void dispose() {
    _bodyScrollController.dispose();
    super.dispose();
  }

  Future<void> _openFullscreen() {
    return showDialog<void>(
      context: context,
      barrierColor: const Color(0x8A0B0E14),
      builder: (dialogContext) => _RunTraceFullscreenSheet(trace: widget.trace),
    );
  }

  @override
  Widget build(BuildContext context) {
    final trace = widget.trace;
    const double bubbleWidth = 252;
    const double bodyMaxHeight = 136;
    final Color surfaceColor = trace.isHighRisk
        ? _ChatPalette.highRiskSurface
        : const Color(0xFFF3F4F7);
    final Color borderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : const Color(0xFFE0E2E8);
    final Color chipColor = trace.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFE7EBF4);
    final Color chipTextColor = trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return GestureDetector(
      onDoubleTap: _openFullscreen,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: bubbleWidth,
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: surfaceColor,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(18),
              side: BorderSide(color: borderColor),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: chipColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    child: Text(
                      trace.label,
                      style: _ChatTextStyles.timeline.copyWith(
                        color: chipTextColor,
                      ),
                    ),
                  ),
                ),
                if (trace.body.trim().isNotEmpty) ...<Widget>[
                  const SizedBox(height: 8),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: bodyMaxHeight),
                    child: Scrollbar(
                      controller: _bodyScrollController,
                      child: SingleChildScrollView(
                        key: ValueKey<String>(
                          'chat-run-trace-scroll-${trace.runId}',
                        ),
                        controller: _bodyScrollController,
                        primary: false,
                        padding: EdgeInsets.zero,
                        physics: const ClampingScrollPhysics(),
                        child: Text(
                          trace.body,
                          style: _ChatTextStyles.bubble.copyWith(
                            color: _ChatPalette.textPrimary,
                          ),
                        ),
                      ),
                    ),
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

class _RunTraceFullscreenSheet extends StatefulWidget {
  const _RunTraceFullscreenSheet({required this.trace});

  final ChatRunTraceData trace;

  @override
  State<_RunTraceFullscreenSheet> createState() =>
      _RunTraceFullscreenSheetState();
}

class _RunTraceFullscreenSheetState extends State<_RunTraceFullscreenSheet> {
  late final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final trace = widget.trace;
    final history = trace.history.isNotEmpty
        ? trace.history
        : <ChatRunTraceHistoryEntry>[
            ChatRunTraceHistoryEntry(
              label: trace.label,
              body: trace.body,
              isHighRisk: trace.isHighRisk,
            ),
          ];
    final Color surfaceColor = trace.isHighRisk
        ? _ChatPalette.highRiskSurface
        : Colors.white;
    final Color borderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : _ChatPalette.border;
    final Color chipColor = trace.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFE7EBF4);
    final Color chipTextColor = trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return Dialog.fullscreen(
      key: ValueKey<String>('chat-run-trace-fullscreen-${trace.runId}'),
      backgroundColor: _ChatPalette.background,
      child: SafeArea(
        child: Column(
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(trace.label, style: _ChatTextStyles.cardTitle),
                  ),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    behavior: HitTestBehavior.opaque,
                    child: Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: _ChatPalette.border),
                      ),
                      alignment: Alignment.center,
                      child: const Icon(
                        Icons.close_rounded,
                        size: 18,
                        color: _ChatPalette.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: surfaceColor,
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: borderColor),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        DecoratedBox(
                          decoration: BoxDecoration(
                            color: chipColor,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 5,
                            ),
                            child: Text(
                              trace.label,
                              style: _ChatTextStyles.timeline.copyWith(
                                color: chipTextColor,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
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
                              padding: EdgeInsets.zero,
                              physics: const ClampingScrollPhysics(),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: history
                                    .map(
                                      (entry) => Padding(
                                        padding: const EdgeInsets.only(
                                          bottom: 12,
                                        ),
                                        child: _RunTraceHistoryCard(
                                          entry: entry,
                                        ),
                                      ),
                                    )
                                    .toList(growable: false),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RunTraceHistoryCard extends StatelessWidget {
  const _RunTraceHistoryCard({required this.entry});

  final ChatRunTraceHistoryEntry entry;

  @override
  Widget build(BuildContext context) {
    final Color chipColor = entry.isHighRisk
        ? _ChatPalette.highRiskBadgeSurface
        : const Color(0xFFEFF2F8);
    final Color chipTextColor = entry.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.textSecondary;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF7F8FB),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFE6E8EF)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            DecoratedBox(
              decoration: BoxDecoration(
                color: chipColor,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: Text(
                  entry.label,
                  style: _ChatTextStyles.timeline.copyWith(
                    color: chipTextColor,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              entry.body,
              style: _ChatTextStyles.bubble.copyWith(
                color: _ChatPalette.textPrimary,
              ),
            ),
          ],
        ),
      ),
    );
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
                            icon: CupertinoIcons.doc_on_doc,
                            label: copy.chatMessageCopyAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.copy),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            icon: secondaryIcon,
                            label: secondaryLabel,
                            enabled: secondaryEnabled,
                            onTap: secondaryEnabled
                                ? () => onActionSelected(secondaryAction)
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
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
                            icon: CupertinoIcons.check_mark_circled,
                            label: copy.chatMessageSelectAction,
                            onTap: () => onActionSelected(
                              _ChatMessageMenuAction.multiSelect,
                            ),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
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
    required this.icon,
    required this.label,
    required this.onTap,
    this.enabled = true,
    this.isDestructive = false,
  });

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
  final ValueChanged<String?> onTextSelectionChanged;

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
    final List<ChatMessageAttachmentData> imageAttachments = widget
        .message
        .attachments
        .where((attachment) => attachment.kind == ChatAttachmentKind.image)
        .toList(growable: false);
    final List<ChatMessageAttachmentData> otherAttachments = widget
        .message
        .attachments
        .where((attachment) => attachment.kind != ChatAttachmentKind.image)
        .toList(growable: false);
    final String text = widget.message.text.trim();
    final bool hasText = text.isNotEmpty;
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
                Theme(
                  data: Theme.of(
                    context,
                  ).copyWith(textSelectionTheme: selectionTheme),
                  child: SelectionArea(
                    onSelectionChanged: (selection) {
                      final String selectedText = selection?.plainText ?? '';
                      _selectedText = selectedText.isEmpty
                          ? null
                          : selectedText;
                      widget.onTextSelectionChanged(_selectedText);
                    },
                    contextMenuBuilder:
                        (
                          BuildContext context,
                          SelectableRegionState selectableRegionState,
                        ) => const SizedBox.shrink(),
                    child: Text(
                      text,
                      style: _ChatTextStyles.bubble.copyWith(
                        color: widget.textColor,
                      ),
                    ),
                  ),
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
                child: Image.memory(preview.bytes, fit: BoxFit.contain),
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
                    ? Image.memory(preview.bytes, fit: BoxFit.cover)
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
        await showDialog<void>(
          context: context,
          builder: (dialogContext) => _ChatTextPreviewDialog(preview: preview),
        );
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

bool _isPreviewableTextAttachment(ChatMessageAttachmentData attachment) {
  final String normalizedMimeType =
      attachment.mimeType?.trim().toLowerCase() ?? '';
  if (normalizedMimeType.startsWith('text/') ||
      _chatPreviewableTextMimeTypes.contains(normalizedMimeType)) {
    return true;
  }
  final String normalizedName = attachment.displayName.trim().toLowerCase();
  if (_chatPreviewableTextFileNames.contains(normalizedName)) {
    return true;
  }
  final String extension = normalizedName.contains('.')
      ? normalizedName.split('.').last
      : '';
  return _chatPreviewableTextExtensions.contains(extension);
}

class _ChatTextPreviewDialog extends StatelessWidget {
  const _ChatTextPreviewDialog({required this.preview});

  final OpenCrayFileTextPreview preview;

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
                      child: SelectionArea(
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

class _ComposerCard extends StatelessWidget {
  const _ComposerCard({
    required this.copy,
    required this.state,
    required this.controller,
    required this.focusNode,
    required this.onPlusPressed,
    required this.onSendPressed,
    required this.onAddActionSelected,
    required this.onCommandSelected,
    required this.onAttachmentRemoved,
    required this.todoPanelExpanded,
    required this.onTodoPanelToggle,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;
  final ValueChanged<ChatAddActionData> onAddActionSelected;
  final VoidCallback onCommandSelected;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;
  final bool todoPanelExpanded;
  final VoidCallback onTodoPanelToggle;

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
          _TodoListPanel(
            todos: state.composer.todos,
            expanded: todoPanelExpanded,
            onToggle: onTodoPanelToggle,
          ),
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
        _InputRow(
          placeholder: state.composer.placeholder,
          controller: controller,
          focusNode: focusNode,
          enabled: state.isInputEnabled,
          hasIntegratedSurface: hasIntegratedSurface,
          showDefaultGlass: !hasIntegratedSurface && !hasTodos,
          plusHighlighted: hasIntegratedSurface,
          onPlusPressed: onPlusPressed,
          onSendPressed: onSendPressed,
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

class _TodoListPanel extends StatelessWidget {
  const _TodoListPanel({
    required this.todos,
    required this.expanded,
    required this.onToggle,
  });

  static const int _maxVisibleTodoCount = 4;
  static const double _itemHeight = 28;
  static const double _itemGap = 6;

  final List<ChatTodoItemData> todos;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final int visibleCount = math.min(todos.length, _maxVisibleTodoCount);
    final double listHeight =
        (visibleCount * _itemHeight) +
        (visibleCount > 0 ? (visibleCount - 1) * _itemGap : 0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        GestureDetector(
          key: const ValueKey<String>('chat-composer-todo-toggle'),
          onTap: onToggle,
          behavior: HitTestBehavior.opaque,
          child: Row(
            children: <Widget>[
              Text('TODO', style: _ChatTextStyles.todoLabel),
              const Spacer(),
              Icon(
                expanded
                    ? CupertinoIcons.chevron_up
                    : CupertinoIcons.chevron_down,
                key: const ValueKey<String>('chat-composer-todo-chevron'),
                size: 13,
                color: _ChatPalette.textTertiary,
              ),
            ],
          ),
        ),
        if (expanded) ...<Widget>[
          const SizedBox(height: 10),
          SizedBox(
            key: const ValueKey<String>('chat-composer-todo-list'),
            height: listHeight,
            child: ListView.separated(
              padding: EdgeInsets.zero,
              physics: todos.length > _maxVisibleTodoCount
                  ? const ClampingScrollPhysics()
                  : const NeverScrollableScrollPhysics(),
              itemCount: todos.length,
              itemBuilder: (BuildContext context, int index) {
                return _TodoRow(todo: todos[index], index: index);
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(height: _itemGap);
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
        _CircleButton(
          backgroundColor: _ChatPalette.accent,
          foregroundColor: Colors.white,
          icon: Icons.arrow_upward_rounded,
          onPressed: enabled ? onSendPressed : null,
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
  const _AttachmentCard({required this.attachment, required this.onRemove});

  final ChatAttachmentData attachment;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final IconData icon = attachment.kind == ChatAttachmentKind.image
        ? Icons.image_outlined
        : Icons.description_outlined;

    return SizedBox(
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
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.75),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(icon, size: 16, color: _ChatPalette.textPrimary),
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
  static const Color highRiskSurface = Color(0xFFFFF5F2);
  static const Color highRiskBorder = Color(0xFFF2C6BA);
  static const Color highRiskBadgeSurface = Color(0xFFFFE0D7);
  static const Color textPrimary = Color(0xFF111111);
  static const Color textSecondary = Color(0xFF6E6E73);
  static const Color textTertiary = Color(0xFF8E8E93);
  static const Color border = Color(0xFFE8E8ED);
  static const Color composerStroke = Color(0xFFD7D7DC);
  static const Color plusActiveSurface = Color(0xFFEEF5FF);
  static const Color subtleSurface = Color(0xFFF7F7FA);
  static const Color todoCompletedFill = Color(0xFFB8BDC7);
  static const Color selectionRowHighlight = Color(0xFFE4E5EA);
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
