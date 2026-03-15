import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import 'chat_models.dart';
import 'chat_seed_data.dart';

class OpenCrayChatFeature extends StatefulWidget {
  const OpenCrayChatFeature({
    super.key,
    required this.copy,
    this.state,
    this.bridge,
    this.bottomInset = 10,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState? state;
  final OpenCrayHostBridge? bridge;
  final double bottomInset;

  @override
  State<OpenCrayChatFeature> createState() => _OpenCrayChatFeatureState();
}

class _OpenCrayChatFeatureState extends State<OpenCrayChatFeature> {
  late ChatFeatureState _state =
      widget.state ?? OpenCrayChatSeedData.main(widget.copy);
  late final TextEditingController _composerController =
      TextEditingController();
  late final FocusNode _composerFocusNode = FocusNode();
  final ScrollController _chatScrollController = ScrollController();
  final GlobalKey _composerKey = GlobalKey();
  StreamSubscription<OpenCrayChatSnapshot>? _chatSubscription;
  final Set<String> _approvalTaskIdsInFlight = <String>{};
  double _composerHeight = 0;

  bool get _usesHostBridge => widget.bridge != null;

  @override
  void initState() {
    super.initState();
    final bridge = widget.bridge;
    if (bridge != null) {
      _hydrateFromHost(bridge);
      _chatSubscription = bridge.watchChatSnapshot().listen((snapshot) {
        if (!mounted) {
          return;
        }
        final ChatFeatureState nextState = _mapSnapshot(snapshot);
        final bool shouldScrollToBottom =
            nextState.messages.length > _state.messages.length;
        setState(() {
          _state = nextState;
        });
        if (shouldScrollToBottom) {
          _scheduleScrollToBottom();
        }
      });
    }
  }

  @override
  void dispose() {
    _chatSubscription?.cancel();
    _composerController.dispose();
    _composerFocusNode.dispose();
    _chatScrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const double toolbarReserveHeight = 44;
    final double topGlassBarHeight =
        MediaQuery.paddingOf(context).top + toolbarReserveHeight + 4;
    _scheduleComposerHeightSync();

    return ColoredBox(
      color: _ChatPalette.background,
      child: Stack(
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
                          onTap: _closeComposerMenus,
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
                              copy: widget.copy,
                              state: _state,
                              busyApprovalTaskIds: _approvalTaskIdsInFlight,
                              onApproveApproval: _approvePendingApproval,
                              onRejectApproval: _rejectPendingApproval,
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
                            child: _ComposerCard(
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
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: _TopGlassBar(height: topGlassBarHeight),
            ),
          ),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 0),
              child: _ChatToolbar(
                sessionButtonLabel: _state.sessionButtonLabel,
                modeLabel: _state.modeLabel,
                onSessionsPressed: _showDrawer,
              ),
            ),
          ),
          if (_state.drawerOpen)
            _SessionsDrawerOverlay(
              drawer: _state.drawer,
              onDismiss: _closeDrawer,
              onNewSessionPressed: _showEmpty,
              onSessionPressed: _handleSessionSelected,
            ),
        ],
      ),
    );
  }

  double _composerScrollInset() {
    final double measuredHeight = _composerHeight;
    if (measuredHeight > 0) {
      return widget.bottomInset + measuredHeight + 12;
    }
    return widget.bottomInset + 84;
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
      _state = _state.copyWith(drawerOpen: true);
    });
  }

  void _closeDrawer() {
    setState(() {
      _state = _state.copyWith(drawerOpen: false);
    });
  }

  void _showEmpty() {
    final bridge = widget.bridge;
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
        composer: ChatComposerState(placeholder: _state.composer.placeholder),
      );
    });
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

  void _closeComposerMenus() {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!_state.composer.showAddMenu &&
        _state.composer.commandOptions.isEmpty) {
      return;
    }
    setState(() {
      _state = _state.copyWith(
        composer: _state.composer.copyWith(
          showAddMenu: false,
          commandOptions: const <ChatCommandOptionData>[],
          clearSelectedCommand: true,
        ),
      );
    });
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

  Future<void> _hydrateFromHost(OpenCrayHostBridge bridge) async {
    final snapshot = await bridge.loadChatSnapshot();
    if (!mounted) {
      return;
    }
    setState(() {
      _state = _mapSnapshot(snapshot);
    });
    if (snapshot.messages.isNotEmpty) {
      _scheduleScrollToBottom(animated: false);
    }
  }

  ChatFeatureState _mapSnapshot(OpenCrayChatSnapshot snapshot) {
    return ChatFeatureState(
      variant: snapshot.messages.isEmpty
          ? ChatPrototypeVariant.empty
          : ChatPrototypeVariant.main,
      screenTitle: snapshot.screenTitle,
      summary: ChatSessionSummary(
        title: snapshot.summary.title,
        badge: snapshot.summary.badge,
        body: snapshot.summary.body,
      ),
      messages: snapshot.messages
          .map(
            (message) => ChatMessageData(
              kind: switch (message.kind) {
                'timeline' => ChatMessageKind.timeline,
                'outbound' => ChatMessageKind.outbound,
                _ => ChatMessageKind.inbound,
              },
              text: message.text,
              meta: message.meta,
            ),
          )
          .toList(growable: false),
      composer: ChatComposerState(
        placeholder: snapshot.composerPlaceholder,
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
            ),
          )
          .toList(growable: false),
      modeLabel: snapshot.modeLabel,
      sessionButtonLabel: snapshot.sessionButtonLabel,
      emptyThreadHeight: snapshot.messages.isEmpty ? 260 : 0,
      isInputEnabled: snapshot.isInputEnabled,
    );
  }
}

class _ChatScrollContent extends StatelessWidget {
  const _ChatScrollContent({
    required this.copy,
    required this.state,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text(state.screenTitle, style: _ChatTextStyles.pageTitle),
        const SizedBox(height: 20),
        _SummaryCard(summary: state.summary),
        if (state.pendingApprovals.isNotEmpty) ...<Widget>[
          const SizedBox(height: 16),
          _PendingApprovalList(
            copy: copy,
            approvals: state.pendingApprovals,
            busyApprovalTaskIds: busyApprovalTaskIds,
            onApproveApproval: onApproveApproval,
            onRejectApproval: onRejectApproval,
          ),
        ],
        const SizedBox(height: 20),
        if (state.messages.isEmpty)
          SizedBox(height: state.emptyThreadHeight)
        else
          _MessageList(messages: state.messages),
      ],
    );
  }
}

class _TopGlassBar extends StatelessWidget {
  const _TopGlassBar({required this.height});

  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: height,
      child: ClipRect(
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
          child: DecoratedBox(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: <Color>[
                  Color(0xF5FFFFFF),
                  Color(0xD9FFFFFF),
                  Color(0x85F8FAFE),
                  Color(0x00F8FAFE),
                ],
                stops: <double>[0, 0.34, 0.76, 1],
              ),
              border: Border(bottom: BorderSide(color: Color(0x30FFFFFF))),
              boxShadow: <BoxShadow>[
                BoxShadow(
                  color: Color(0x10000000),
                  blurRadius: 20,
                  offset: Offset(0, 8),
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
    required this.sessionButtonLabel,
    required this.modeLabel,
    required this.onSessionsPressed,
  });

  final String sessionButtonLabel;
  final String modeLabel;
  final VoidCallback onSessionsPressed;

  @override
  Widget build(BuildContext context) {
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

class _PendingApprovalList extends StatelessWidget {
  const _PendingApprovalList({
    required this.copy,
    required this.approvals,
    required this.busyApprovalTaskIds,
    required this.onApproveApproval,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final List<ChatPendingApprovalData> approvals;
  final Set<String> busyApprovalTaskIds;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text(
          copy.chatPendingApprovalsTitle,
          style: _ChatTextStyles.sectionLabel,
        ),
        const SizedBox(height: 10),
        ...approvals.map(
          (approval) => Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: _PendingApprovalCard(
              copy: copy,
              approval: approval,
              isBusy: busyApprovalTaskIds.contains(approval.approvalId),
              onApprove: () => onApproveApproval(approval),
              onReject: () => onRejectApproval(approval),
            ),
          ),
        ),
      ],
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
                  child: Text(approval.title, style: _ChatTextStyles.cardTitle),
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
            if (approval.body.trim().isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              Text(approval.body, style: _ChatTextStyles.bodyMuted),
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

class _MessageList extends StatelessWidget {
  const _MessageList({required this.messages});

  final List<ChatMessageData> messages;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: messages.map((ChatMessageData message) {
        switch (message.kind) {
          case ChatMessageKind.timeline:
            return Padding(
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
            );
          case ChatMessageKind.inbound:
            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: _Bubble(
                  text: message.text,
                  backgroundColor: Colors.white,
                  textColor: _ChatPalette.textPrimary,
                  maxWidth: 252,
                ),
              ),
            );
          case ChatMessageKind.outbound:
            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Align(
                alignment: Alignment.centerRight,
                child: _Bubble(
                  text: message.text,
                  backgroundColor: _ChatPalette.accent,
                  textColor: Colors.white,
                  maxWidth: 236,
                ),
              ),
            );
        }
      }).toList(),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({
    required this.text,
    required this.backgroundColor,
    required this.textColor,
    required this.maxWidth,
  });

  final String text;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: backgroundColor,
          shape: const RoundedSuperellipseBorder(
            borderRadius: BorderRadius.all(Radius.circular(18)),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Text(
            text,
            style: _ChatTextStyles.bubble.copyWith(color: textColor),
          ),
        ),
      ),
    );
  }
}

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

  @override
  Widget build(BuildContext context) {
    final bool hasIntegratedSurface =
        state.composer.commandOptions.isNotEmpty ||
        state.composer.attachments.isNotEmpty ||
        state.composer.showAddMenu;

    final Widget content = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
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
          showDefaultGlass: !hasIntegratedSurface,
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

    if (!hasIntegratedSurface) {
      return content;
    }

    return DecoratedBox(
      decoration: _ChatDecorations.card(),
      child: Padding(padding: const EdgeInsets.all(10), child: content),
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
                              padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
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
    required this.drawer,
    required this.onDismiss,
    required this.onNewSessionPressed,
    required this.onSessionPressed,
  });

  final ChatSessionsDrawerState drawer;
  final VoidCallback onDismiss;
  final VoidCallback onNewSessionPressed;
  final ValueChanged<ChatSessionListItemData> onSessionPressed;

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
                        session: drawer.sessions[index],
                        onPressed: () =>
                            onSessionPressed(drawer.sessions[index]),
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
  const _SessionListTile({required this.session, required this.onPressed});

  final ChatSessionListItemData session;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
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
                  Text(session.meta, style: _ChatTextStyles.sessionMeta),
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

  static const TextStyle timeline = TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
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
