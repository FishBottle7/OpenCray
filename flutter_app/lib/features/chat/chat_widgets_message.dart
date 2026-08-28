part of 'chat_feature_screen.dart';

class _ChatMessageMenuOverlay extends StatelessWidget {
  const _ChatMessageMenuOverlay({
    required this.copy,
    required this.menu,
    required this.isExiting,
    required this.onActionSelected,
  });

  final OpenCrayUiCopy copy;
  final _ActiveChatMessageMenu menu;
  final bool isExiting;
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
            duration: OpenCrayMotion.resolve(
              context,
              isExiting ? _chatMessageMenuExitDuration : OpenCrayMotion.micro,
            ),
            curve: isExiting ? OpenCrayMotion.exit : OpenCrayMotion.enter,
            tween: Tween<double>(
              begin: isExiting ? 1 : 0,
              end: isExiting ? 0 : 1,
            ),
            builder: (BuildContext context, double value, Widget? child) {
              final double side = menu.isOutgoing ? 1 : -1;
              final double offsetX = side * (1 - value) * 12;
              final double offsetY = (1 - value) * -4;
              return Opacity(
                opacity: value.clamp(0, 1),
                child: Transform.translate(
                  offset: Offset(offsetX, offsetY),
                  child: child,
                ),
              );
            },
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
                child: Container(
                  key: ValueKey<String>(
                    'chat-message-menu-${menu.message.messageId}',
                  ),
                  width: menuWidth,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.92),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: _ChatGlass.popoverBorder),
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-copy',
                            ),
                            icon: CupertinoIcons.doc_on_doc,
                            label: copy.chatMessageCopyAction,
                            onTap: () =>
                                onActionSelected(_ChatMessageMenuAction.copy),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${secondaryAction.name}',
                            ),
                            icon: secondaryIcon,
                            label: secondaryLabel,
                            enabled: secondaryEnabled,
                            onTap: secondaryEnabled
                                ? () => onActionSelected(secondaryAction)
                                : null,
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: ValueKey<String>(
                              'chat-message-menu-action-${tertiaryAction.name}',
                            ),
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
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-delete',
                            ),
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
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-multiSelect',
                            ),
                            icon: CupertinoIcons.check_mark_circled,
                            label: copy.chatMessageSelectAction,
                            onTap: () => onActionSelected(
                              _ChatMessageMenuAction.multiSelect,
                            ),
                          ),
                          const SizedBox(width: 12),
                          _ChatMessageMenuItem(
                            itemKey: const ValueKey<String>(
                              'chat-message-menu-action-quote',
                            ),
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
    this.itemKey,
    required this.icon,
    required this.label,
    required this.onTap,
    this.enabled = true,
    this.isDestructive = false,
  });

  final Key? itemKey;
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool enabled;
  final bool isDestructive;

  @override
  Widget build(BuildContext context) {
    final Color foregroundColor = isDestructive
        ? OpenCrayColors.danger
        : OpenCrayColors.textPrimary;
    final Color labelColor = isDestructive
        ? OpenCrayColors.danger
        : OpenCrayColors.textSecondary;
    return GestureDetector(
      key: itemKey,
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

class _ChatRunTraceInlineActions extends StatefulWidget {
  const _ChatRunTraceInlineActions({
    required this.copy,
    required this.traces,
    this.showRetryActions = true,
    this.showInterruptActions = true,
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
  });

  final OpenCrayUiCopy copy;
  final List<ChatRunTraceData> traces;
  final bool showRetryActions;
  final bool showInterruptActions;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  @override
  State<_ChatRunTraceInlineActions> createState() =>
      _ChatRunTraceInlineActionsState();
}

class _ChatRunTraceInlineActionsState
    extends State<_ChatRunTraceInlineActions> {
  bool _outsideDismissReady = false;

  ChatRunTraceData? get _confirmTrace {
    if (!widget.showInterruptActions) {
      return null;
    }
    final String confirmRunId = widget.interruptConfirmRunId?.trim() ?? '';
    if (confirmRunId.isEmpty) {
      return null;
    }
    for (final trace in widget.traces) {
      if (trace.interruptId == confirmRunId &&
          trace.canInterrupt &&
          !trace.isTerminal) {
        return trace;
      }
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ChatRunTraceInlineActions oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.interruptConfirmRunId != widget.interruptConfirmRunId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    if (_confirmTrace == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _confirmTrace == null) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final List<Widget> rows = <Widget>[];
    for (final trace in widget.traces) {
      final bool showConfirm = _confirmTrace == trace;
      final bool interruptBusy = widget.busyInterruptRunIds.contains(
        trace.interruptId,
      );
      final bool retryBusy = widget.busyRetryRunIds.contains(trace.retryId);
      final bool canShowInterrupt =
          widget.showInterruptActions &&
          !trace.isTerminal &&
          (trace.canInterrupt || showConfirm || interruptBusy) &&
          (widget.onArmInterruptRunTrace != null ||
              showConfirm ||
              interruptBusy);
      final bool canShowRetry =
          widget.showRetryActions &&
          trace.isRetryable &&
          widget.onRetryRunTrace != null;
      if (!canShowInterrupt && !canShowRetry) {
        continue;
      }
      if (showConfirm) {
        rows.add(
          _RunTraceInterruptConfirmRow(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-confirm-${trace.interruptId}',
            ),
            copy: widget.copy,
            runId: trace.interruptId,
            isBusy: interruptBusy,
            onConfirmed: widget.onInterruptRunTrace == null
                ? null
                : () => widget.onInterruptRunTrace!(trace),
          ),
        );
        continue;
      }
      final List<Widget> actions = <Widget>[];
      if (canShowRetry) {
        actions.add(
          _RunTraceActionButton(
            label: retryBusy ? '${trace.retryLabel!}...' : trace.retryLabel!,
            onTap: retryBusy ? null : () => widget.onRetryRunTrace!(trace),
          ),
        );
      }
      if (canShowInterrupt) {
        actions.add(
          _RunTraceInlineInterruptAction(
            key: ValueKey<String>(
              'chat-run-trace-interrupt-${trace.interruptId}',
            ),
            label: interruptBusy
                ? widget.copy.chatRunInterruptBusy
                : widget.copy.chatRunInterruptAction,
            enabled: !interruptBusy,
            onTap: interruptBusy || widget.onArmInterruptRunTrace == null
                ? null
                : () => widget.onArmInterruptRunTrace!(trace),
          ),
        );
      }
      if (actions.isNotEmpty) {
        rows.add(Wrap(spacing: 10, runSpacing: 8, children: actions));
      }
    }
    if (rows.isEmpty) {
      return const SizedBox.shrink();
    }
    return TapRegion(
      onTapOutside: _confirmTrace != null && _outsideDismissReady
          ? (_) {
              final ChatRunTraceData? trace = _confirmTrace;
              if (trace != null) {
                widget.onDismissInterruptRunTrace?.call(trace);
              }
            }
          : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: rows
            .asMap()
            .entries
            .map(
              (entry) => Padding(
                padding: EdgeInsets.only(
                  bottom: entry.key == rows.length - 1 ? 0 : 8,
                ),
                child: entry.value,
              ),
            )
            .toList(growable: false),
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
    this.attachedRunTraces = const <ChatRunTraceData>[],
    this.interruptConfirmRunId,
    this.busyInterruptRunIds = const <String>{},
    this.busyRetryRunIds = const <String>{},
    this.onArmInterruptRunTrace,
    this.onDismissInterruptRunTrace,
    this.onInterruptRunTrace,
    this.onRetryRunTrace,
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
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onTextSelectionChanged;
  final List<ChatRunTraceData> attachedRunTraces;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

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
      _selectedTextAtPointerDown,
    );
  }

  @override
  Widget build(BuildContext context) {
    final selectionTheme = chatBubbleSelectionTheme(widget.message.kind);
    final _ChatInlineAttachmentContent inlineBody =
        _buildChatInlineAttachmentContent(
          widget.message.text.trim(),
          widget.message.attachments,
        );
    final Set<String> inlineAttachmentIds = inlineBody.referencedAttachmentIds;
    final List<ChatMessageAttachmentData> imageAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind == ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final List<ChatMessageAttachmentData> otherAttachments = widget
        .message
        .attachments
        .where(
          (attachment) =>
              attachment.kind != ChatAttachmentKind.image &&
              !inlineAttachmentIds.contains(attachment.attachmentId),
        )
        .toList(growable: false);
    final bool hasText = inlineBody.segments.isNotEmpty;
    final bool hasImages = imageAttachments.isNotEmpty;
    final bool hasOtherAttachments = otherAttachments.isNotEmpty;
    final bool showStreamingIndicator =
        hasText &&
        widget.message.kind == ChatMessageKind.inbound &&
        widget.message.isStreaming;
    final String indicatorKeySuffix = widget.message.messageId.trim().isNotEmpty
        ? widget.message.messageId.trim()
        : 'anonymous';
    final Widget? streamingIndicator = showStreamingIndicator
        ? _ChatStreamingTailIndicator(
            key: ValueKey<String>(
              'chat-streaming-indicator-$indicatorKeySuffix',
            ),
            color: widget.textColor,
          )
        : null;
    final _ChatInlineAttachmentSegment? lastSegment =
        inlineBody.segments.isNotEmpty ? inlineBody.segments.last : null;
    final bool canInlineStreamingIndicator =
        streamingIndicator != null &&
        lastSegment is _ChatInlineMarkdownSegment &&
        openCrayMarkdownCanInlineTrailingWidget(lastSegment.markdown);
    final bool isOutboundBubble =
        widget.message.kind == ChatMessageKind.outbound;
    final bool useBrandGradient =
        isOutboundBubble && widget.backgroundColor == _ChatPalette.accent;
    final bool showInboundOutline =
        !isOutboundBubble && widget.backgroundColor == Colors.white;
    final Widget bubble = ConstrainedBox(
      key: ValueKey<String>('chat-bubble-${widget.message.messageId}'),
      constraints: BoxConstraints(maxWidth: widget.maxWidth),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: useBrandGradient ? null : widget.backgroundColor,
          gradient: useBrandGradient ? _ChatGradients.outboundBubble : null,
          shape: RoundedSuperellipseBorder(
            borderRadius: const BorderRadius.all(Radius.circular(18)),
            side: showInboundOutline
                ? BorderSide(
                    color: OpenCrayColors.divider.withValues(alpha: 0.85),
                  )
                : BorderSide.none,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              if (hasText)
                _ChatBubbleMarkdownBody(
                  bridge: widget.bridge,
                  copy: widget.copy,
                  content: inlineBody,
                  textColor: widget.textColor,
                  backgroundColor: widget.backgroundColor,
                  messageId: widget.message.messageId,
                  contentMaxWidth: widget.maxWidth - 28,
                  selectionTheme: selectionTheme,
                  trailingInlineIndicator: canInlineStreamingIndicator
                      ? streamingIndicator
                      : null,
                  onSelectionChanged: (selection) {
                    if (_didOpenMenu && _pointerDownGlobalPosition != null) {
                      return;
                    }
                    _selectedText = selection?.plainText;
                    widget.onTextSelectionChanged(selection);
                  },
                  contextMenuBuilder:
                      (
                        BuildContext context,
                        SelectableRegionState selectableRegionState,
                        OpenCrayMarkdownSelectionSnapshot? selection,
                      ) => const SizedBox.shrink(),
                  voicePlaybackControllerFactory:
                      widget.voicePlaybackControllerFactory,
                  isOutgoing: widget.message.kind == ChatMessageKind.outbound,
                ),
              if (streamingIndicator != null && !canInlineStreamingIndicator)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: streamingIndicator,
                ),
              if (hasImages) ...<Widget>[
                if (hasText) const SizedBox(height: 10),
                _ChatImageAttachmentGroup(
                  bridge: widget.bridge,
                  copy: widget.copy,
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
              if (widget.attachedRunTraces.isNotEmpty) ...<Widget>[
                if (hasText || hasImages || hasOtherAttachments)
                  const SizedBox(height: 10),
                _ChatRunTraceInlineActions(
                  copy: widget.copy,
                  traces: widget.attachedRunTraces,
                  showInterruptActions: false,
                  interruptConfirmRunId: widget.interruptConfirmRunId,
                  busyInterruptRunIds: widget.busyInterruptRunIds,
                  busyRetryRunIds: widget.busyRetryRunIds,
                  onArmInterruptRunTrace: widget.onArmInterruptRunTrace,
                  onDismissInterruptRunTrace: widget.onDismissInterruptRunTrace,
                  onInterruptRunTrace: widget.onInterruptRunTrace,
                  onRetryRunTrace: widget.onRetryRunTrace,
                ),
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

class _ChatStreamingTailIndicator extends StatefulWidget {
  const _ChatStreamingTailIndicator({super.key, required this.color});

  final Color color;

  @override
  State<_ChatStreamingTailIndicator> createState() =>
      _ChatStreamingTailIndicatorState();
}

class _ChatStreamingTailIndicatorState
    extends State<_ChatStreamingTailIndicator> {
  Timer? _pulseTimer;
  bool _highlighted = true;

  @override
  void initState() {
    super.initState();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncPulseTimer();
  }

  void _syncPulseTimer() {
    final bool disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (disableAnimations) {
      _pulseTimer?.cancel();
      _pulseTimer = null;
      _highlighted = true;
      return;
    }
    _pulseTimer ??= Timer.periodic(const Duration(milliseconds: 920), (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _highlighted = !_highlighted;
      });
    });
  }

  @override
  void didUpdateWidget(covariant _ChatStreamingTailIndicator oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.color != widget.color) {
      _syncPulseTimer();
    }
  }

  @override
  void dispose() {
    _pulseTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bool disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    return ExcludeSemantics(
      child: SizedBox(
        width: 10,
        height: 10,
        child: Center(
          child: AnimatedOpacity(
            opacity: disableAnimations || _highlighted ? 0.95 : 0.38,
            duration: const Duration(milliseconds: 460),
            curve: Curves.easeInOut,
            child: AnimatedScale(
              scale: disableAnimations || _highlighted ? 1.08 : 0.82,
              duration: const Duration(milliseconds: 460),
              curve: Curves.easeInOut,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: widget.color.withValues(alpha: 0.78),
                  shape: BoxShape.circle,
                ),
                child: const SizedBox(width: 5.5, height: 5.5),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

final RegExp _chatAttachmentMarkdownReferencePattern = RegExp(
  r'(!?)\[([^\]]*)\]\((attachment:[^)]+)\)',
);

@immutable
class _ChatInlineAttachmentContent {
  const _ChatInlineAttachmentContent({
    required this.segments,
    required this.referencedAttachmentIds,
  });

  final List<_ChatInlineAttachmentSegment> segments;
  final Set<String> referencedAttachmentIds;
}

abstract class _ChatInlineAttachmentSegment {
  const _ChatInlineAttachmentSegment();
}

class _ChatInlineMarkdownSegment extends _ChatInlineAttachmentSegment {
  const _ChatInlineMarkdownSegment(this.markdown);

  final String markdown;
}

class _ChatInlineResolvedAttachmentSegment
    extends _ChatInlineAttachmentSegment {
  const _ChatInlineResolvedAttachmentSegment({
    required this.reference,
    required this.attachment,
  });

  final _ChatInlineAttachmentReference reference;
  final ChatMessageAttachmentData attachment;
}

@immutable
class _ChatInlineAttachmentReference {
  const _ChatInlineAttachmentReference({
    required this.raw,
    required this.label,
    required this.targetToken,
    required this.isImage,
  });

  final String raw;
  final String label;
  final String targetToken;
  final bool isImage;

  String get fallbackLabel {
    final String trimmedLabel = label.trim();
    if (trimmedLabel.isNotEmpty) {
      return trimmedLabel;
    }
    final String normalizedToken = targetToken.trim();
    if (normalizedToken.isEmpty) {
      return '';
    }
    final String stripped = normalizedToken
        .replaceAll('\\', '/')
        .split('/')
        .last
        .trim();
    return stripped;
  }
}

_ChatInlineAttachmentContent _buildChatInlineAttachmentContent(
  String text,
  List<ChatMessageAttachmentData> attachments,
) {
  final String normalizedText = text.trim();
  if (normalizedText.isEmpty) {
    return const _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[],
      referencedAttachmentIds: <String>{},
    );
  }
  final List<RegExpMatch> matches = _chatAttachmentMarkdownReferencePattern
      .allMatches(normalizedText)
      .toList();
  if (matches.isEmpty) {
    return _ChatInlineAttachmentContent(
      segments: <_ChatInlineAttachmentSegment>[
        _ChatInlineMarkdownSegment(normalizedText),
      ],
      referencedAttachmentIds: <String>{},
    );
  }

  final List<_ChatInlineAttachmentSegment> segments =
      <_ChatInlineAttachmentSegment>[];
  final Set<String> referencedAttachmentIds = <String>{};
  int cursor = 0;

  void addMarkdownSegment(String chunk) {
    final String normalizedChunk = chunk.trim();
    if (normalizedChunk.isEmpty) {
      return;
    }
    segments.add(_ChatInlineMarkdownSegment(normalizedChunk));
  }

  for (final RegExpMatch match in matches) {
    addMarkdownSegment(normalizedText.substring(cursor, match.start));
    final _ChatInlineAttachmentReference reference =
        _chatInlineAttachmentReferenceFromMatch(match);
    final ChatMessageAttachmentData? attachment =
        _resolveChatInlineAttachmentReference(reference, attachments);
    if (attachment != null) {
      segments.add(
        _ChatInlineResolvedAttachmentSegment(
          reference: reference,
          attachment: attachment,
        ),
      );
      final String attachmentId = attachment.attachmentId.trim();
      if (attachmentId.isNotEmpty) {
        referencedAttachmentIds.add(attachmentId);
      }
    } else {
      addMarkdownSegment(reference.fallbackLabel);
    }
    cursor = match.end;
  }
  addMarkdownSegment(normalizedText.substring(cursor));

  return _ChatInlineAttachmentContent(
    segments: segments,
    referencedAttachmentIds: referencedAttachmentIds,
  );
}

_ChatInlineAttachmentReference _chatInlineAttachmentReferenceFromMatch(
  RegExpMatch match,
) {
  final String href = (match.group(3) ?? '')
      .trim()
      .split(' ')
      .first
      .trim()
      .replaceFirst(RegExp(r'^attachment:'), '')
      .replaceFirst(RegExp(r'^//'), '')
      .trim();
  return _ChatInlineAttachmentReference(
    raw: match.group(0) ?? '',
    label: (match.group(2) ?? '').trim(),
    targetToken: _normalizeChatAttachmentMarkdownToken(href),
    isImage: (match.group(1) ?? '') == '!',
  );
}

ChatMessageAttachmentData? _resolveChatInlineAttachmentReference(
  _ChatInlineAttachmentReference reference,
  List<ChatMessageAttachmentData> attachments,
) {
  if (attachments.isEmpty) {
    return null;
  }
  final List<ChatMessageAttachmentData> compatibleAttachments =
      reference.isImage
      ? attachments
            .where((attachment) => attachment.kind == ChatAttachmentKind.image)
            .toList(growable: false)
      : attachments;
  if (compatibleAttachments.isEmpty) {
    return null;
  }

  if (reference.targetToken.isNotEmpty && reference.targetToken != 'artifact') {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, reference.targetToken)) {
        return attachment;
      }
    }
  }

  final String labelToken = _normalizeChatAttachmentMarkdownToken(
    reference.label,
  );
  if (labelToken.isNotEmpty) {
    for (final ChatMessageAttachmentData attachment in compatibleAttachments) {
      if (_chatAttachmentMatchesToken(attachment, labelToken)) {
        return attachment;
      }
    }
  }

  if ((reference.targetToken.isEmpty || reference.targetToken == 'artifact') &&
      compatibleAttachments.length == 1) {
    return compatibleAttachments.single;
  }
  return null;
}

bool _chatAttachmentMatchesToken(
  ChatMessageAttachmentData attachment,
  String token,
) {
  final String normalizedToken = _normalizeChatAttachmentMarkdownToken(token);
  if (normalizedToken.isEmpty) {
    return false;
  }
  final String normalizedAttachmentId = _normalizeChatAttachmentMarkdownToken(
    attachment.attachmentId,
  );
  final String normalizedLocalPath = _normalizeChatAttachmentMarkdownToken(
    attachment.localPath,
  );
  final String normalizedDisplayName = _normalizeChatAttachmentMarkdownToken(
    attachment.displayName,
  );
  final String normalizedBaseName = normalizedLocalPath.contains('/')
      ? normalizedLocalPath.split('/').last
      : normalizedLocalPath;
  return normalizedToken == normalizedAttachmentId ||
      normalizedToken == normalizedLocalPath ||
      normalizedToken == normalizedDisplayName ||
      normalizedToken == normalizedBaseName;
}

String _normalizeChatAttachmentMarkdownToken(String value) => value
    .trim()
    .replaceAll('\\', '/')
    .replaceFirst(RegExp(r'^/'), '')
    .toLowerCase();

Future<void> _handleOpenCrayMarkdownLinkTap(
  BuildContext context, {
  required String? href,
  required OpenCrayUiCopy copy,
  OpenCrayHostBridge? bridge,
}) async {
  final String target = href?.trim() ?? '';
  if (target.isEmpty) {
    return;
  }
  final ScaffoldMessengerState? messenger = ScaffoldMessenger.maybeOf(context);
  try {
    final String? routeName = openCrayResolveMarkdownInternalRoute(target);
    if (routeName != null) {
      await Navigator.of(context).pushNamed(routeName);
      return;
    }
    final OpenCrayHostBridge? hostBridge = bridge;
    if (hostBridge == null) {
      throw StateError('Missing host bridge for markdown link target.');
    }
    final Uri? externalUri = openCrayResolveMarkdownExternalUri(target);
    if (externalUri != null) {
      await hostBridge.openExternalUri(externalUri.toString());
      return;
    }
    final Uri? uri = Uri.tryParse(target);
    if (_isWorkspaceRelativeChatLink(uri, target)) {
      final String relativePath = _normalizeChatWorkspaceRelativePath(target);
      if (_isPreviewableTextRelativePath(relativePath)) {
        final OpenCrayFileTextPreview preview = await hostBridge
            .loadWorkspaceTextPreview(relativePath);
        if (!context.mounted) {
          return;
        }
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
        return;
      }
      await hostBridge.openWorkspaceEntry(relativePath);
      return;
    }
    throw StateError('Unsupported markdown link target.');
  } catch (error) {
    if (!context.mounted) {
      return;
    }
    final String message = openCrayMarkdownLocalizedErrorMessage(
      error,
      copy,
      fallback: copy.chatMessageActionFailed,
    );
    messenger
      ?..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

bool _fontWeightIsEmphasized(FontWeight? fontWeight) {
  return fontWeight != null && fontWeight.index >= FontWeight.w600.index;
}

MarkdownStyleSheet _openCrayMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  required Color textColor,
  Color? linkColor,
  bool preferAccentForStrong = false,
  Color? strongAccentColor,
}) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final bool darkSurface =
      ThemeData.estimateBrightnessForColor(surfaceColor) == Brightness.dark;
  final Color resolvedLinkColor =
      linkColor ??
      (darkSurface
          ? _ChatPalette.linkOnDarkSurface
          : Theme.of(context).colorScheme.primary);
  final Color resolvedStrongAccentColor =
      strongAccentColor ?? Theme.of(context).colorScheme.primary;
  final Color chromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.18)
      : Colors.black.withValues(alpha: 0.08);
  final Color subtleChromeColor = darkSurface
      ? Colors.white.withValues(alpha: 0.12)
      : Colors.black.withValues(alpha: 0.05);
  final bool accentStrong =
      preferAccentForStrong || _fontWeightIsEmphasized(bodyStyle.fontWeight);
  final TextStyle headingStyle = bodyStyle.copyWith(
    fontWeight: FontWeight.w700,
    height: 1.3,
  );
  return base.copyWith(
    a: bodyStyle.copyWith(
      color: resolvedLinkColor,
      fontWeight: FontWeight.w600,
      decoration: TextDecoration.underline,
      decorationThickness: 1.2,
      decorationColor: resolvedLinkColor.withValues(alpha: 0.75),
    ),
    p: bodyStyle,
    pPadding: EdgeInsets.zero,
    strong: accentStrong
        ? bodyStyle.copyWith(color: resolvedStrongAccentColor)
        : bodyStyle.copyWith(fontWeight: FontWeight.w700),
    em: bodyStyle.copyWith(fontStyle: FontStyle.italic),
    del: bodyStyle.copyWith(decoration: TextDecoration.lineThrough),
    h1: headingStyle.copyWith(fontSize: 20),
    h2: headingStyle.copyWith(fontSize: 18),
    h3: headingStyle.copyWith(fontSize: 16),
    h4: headingStyle.copyWith(fontSize: 15),
    h5: headingStyle.copyWith(fontSize: 14),
    h6: headingStyle.copyWith(fontSize: 14),
    listBullet: bodyStyle,
    code: TextStyle(
      fontSize: 13,
      height: 1.45,
      fontFamily: 'monospace',
      color: textColor,
    ),
    codeblockPadding: const EdgeInsets.all(10),
    codeblockDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border.all(color: chromeColor),
    ),
    blockquote: bodyStyle.copyWith(
      color: textColor.withValues(alpha: darkSurface ? 0.88 : 0.82),
    ),
    blockquotePadding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
    blockquoteDecoration: BoxDecoration(
      color: subtleChromeColor,
      borderRadius: BorderRadius.circular(12),
      border: Border(left: BorderSide(color: chromeColor, width: 3)),
    ),
    tableHead: bodyStyle.copyWith(fontWeight: FontWeight.w700),
    tableBody: bodyStyle,
    tableHeadAlign: TextAlign.left,
    tablePadding: const EdgeInsets.only(top: 2, bottom: 4),
    tableBorder: TableBorder.all(color: chromeColor),
    tableColumnWidth: const IntrinsicColumnWidth(),
    tableCellsPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
    tableCellsDecoration: BoxDecoration(color: subtleChromeColor),
    tableHeadCellsDecoration: BoxDecoration(color: chromeColor),
    horizontalRuleDecoration: BoxDecoration(
      border: Border(top: BorderSide(color: chromeColor)),
    ),
    blockSpacing: 10,
  );
}

MarkdownStyleSheet _runTraceMarkdownStyleSheet(
  BuildContext context, {
  required TextStyle bodyStyle,
  required Color surfaceColor,
  bool preferAccentForStrong = false,
}) {
  final Color textColor = bodyStyle.color ?? _ChatPalette.textPrimary;
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: surfaceColor,
    textColor: textColor,
    linkColor: _ChatPalette.inspectorAction,
    preferAccentForStrong: preferAccentForStrong,
    strongAccentColor: _ChatPalette.inspectorAction,
  );
}

class _OpenCrayMarkdownTextBlock extends StatelessWidget {
  const _OpenCrayMarkdownTextBlock({
    super.key,
    required this.copy,
    required this.data,
    required this.bodyStyle,
    required this.surfaceColor,
    this.bridge,
    this.preferAccentForStrong = false,
  });

  final OpenCrayUiCopy copy;
  final String data;
  final TextStyle bodyStyle;
  final Color surfaceColor;
  final OpenCrayHostBridge? bridge;
  final bool preferAccentForStrong;

  @override
  Widget build(BuildContext context) {
    final String markdown = data.trim();
    if (markdown.isEmpty) {
      return const SizedBox.shrink();
    }
    return OpenCrayMarkdownBody(
      data: markdown,
      hostBridge: bridge,
      onTapLink: (_, href, __) {
        unawaited(
          _handleOpenCrayMarkdownLinkTap(
            context,
            href: href,
            copy: copy,
            bridge: bridge,
          ),
        );
      },
      latexTextStyle: bodyStyle,
      styleSheet: _runTraceMarkdownStyleSheet(
        context,
        bodyStyle: bodyStyle,
        surfaceColor: surfaceColor,
        preferAccentForStrong: preferAccentForStrong,
      ),
      imageBackgroundColor: surfaceColor.withValues(alpha: 0.45),
      imageBorderColor: (bodyStyle.color ?? _ChatPalette.textPrimary)
          .withValues(alpha: 0.16),
    );
  }
}

class _ChatBubbleMarkdownBody extends StatelessWidget {
  const _ChatBubbleMarkdownBody({
    required this.bridge,
    required this.copy,
    required this.content,
    required this.textColor,
    required this.backgroundColor,
    required this.messageId,
    required this.contentMaxWidth,
    required this.selectionTheme,
    required this.onSelectionChanged,
    required this.contextMenuBuilder,
    required this.voicePlaybackControllerFactory,
    required this.isOutgoing,
    this.trailingInlineIndicator,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final _ChatInlineAttachmentContent content;
  final Color textColor;
  final Color backgroundColor;
  final String messageId;
  final double contentMaxWidth;
  final TextSelectionThemeData selectionTheme;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onSelectionChanged;
  final OpenCrayMarkdownContextMenuBuilder contextMenuBuilder;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final bool isOutgoing;

  /// Streaming indicator rendered inline right after the final character of
  /// the last markdown segment while the message is still streaming.
  final Widget? trailingInlineIndicator;

  Future<void> _handleLinkTap(BuildContext context, String? href) async {
    await _handleOpenCrayMarkdownLinkTap(
      context,
      href: href,
      copy: copy,
      bridge: bridge,
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(
      color: textColor,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: content.segments
          .asMap()
          .entries
          .map((entry) {
            final _ChatInlineAttachmentSegment segment = entry.value;
            final bool isLastSegment =
                entry.key == content.segments.length - 1;
            final Widget child;
            if (segment is _ChatInlineMarkdownSegment) {
              child = OpenCraySelectableMarkdownBody(
                key: ValueKey<String>(
                  'chat-bubble-markdown-$messageId-${entry.key}',
                ),
                data: segment.markdown,
                hostBridge: bridge,
                selectionTheme: selectionTheme,
                onSelectionChanged: onSelectionChanged,
                contextMenuBuilder: contextMenuBuilder,
                trailingInlineWidget: isLastSegment
                    ? trailingInlineIndicator
                    : null,
                onTapLink: (_, href, __) {
                  unawaited(_handleLinkTap(context, href));
                },
                latexTextStyle: bodyStyle,
                styleSheet: _chatMarkdownStyleSheet(
                  context,
                  textColor: textColor,
                  backgroundColor: backgroundColor,
                ),
                imageBackgroundColor: backgroundColor.withValues(alpha: 0.12),
                imageBorderColor: textColor.withValues(alpha: 0.18),
              );
            } else if (segment is _ChatInlineResolvedAttachmentSegment) {
              if (segment.reference.isImage &&
                  segment.attachment.kind == ChatAttachmentKind.image) {
                child = _ChatImageAttachmentPreview(
                  bridge: bridge,
                  copy: copy,
                  attachment: segment.attachment,
                  maxWidth: contentMaxWidth,
                  isOutgoing: isOutgoing,
                );
              } else {
                child = _ChatAttachmentTile(
                  bridge: bridge,
                  copy: copy,
                  attachment: segment.attachment,
                  voicePlaybackControllerFactory:
                      voicePlaybackControllerFactory,
                  isOutgoing: isOutgoing,
                );
              }
            } else {
              child = const SizedBox.shrink();
            }
            return Padding(
              padding: EdgeInsets.only(
                bottom: entry.key == content.segments.length - 1 ? 0 : 10,
              ),
              child: child,
            );
          })
          .toList(growable: false),
    );
  }
}

MarkdownStyleSheet _chatMarkdownStyleSheet(
  BuildContext context, {
  required Color textColor,
  required Color backgroundColor,
}) {
  final TextStyle bodyStyle = _ChatTextStyles.bubble.copyWith(color: textColor);
  return _openCrayMarkdownStyleSheet(
    context,
    bodyStyle: bodyStyle,
    surfaceColor: backgroundColor,
    textColor: textColor,
  );
}
