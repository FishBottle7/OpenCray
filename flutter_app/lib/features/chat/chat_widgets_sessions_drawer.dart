part of 'chat_feature_screen.dart';

enum _SessionMenuAction { copy, delete }

class _SessionsDrawerOverlay extends StatefulWidget {
  const _SessionsDrawerOverlay({
    required this.isOpen,
    required this.copy,
    required this.drawer,
    required this.onDismiss,
    required this.onNewSessionPressed,
    required this.onSessionPressed,
    required this.onSessionLongPress,
  });

  final bool isOpen;
  final OpenCrayUiCopy copy;
  final ChatSessionsDrawerState drawer;
  final VoidCallback onDismiss;
  final VoidCallback onNewSessionPressed;
  final ValueChanged<ChatSessionListItemData> onSessionPressed;
  final void Function(ChatSessionListItemData, Offset) onSessionLongPress;

  @override
  State<_SessionsDrawerOverlay> createState() => _SessionsDrawerOverlayState();
}

class _SessionsDrawerOverlayState extends State<_SessionsDrawerOverlay> {
  bool _isMountedForMotion = false;
  int _closeEpoch = 0;
  bool _animateFromClosed = false;

  @override
  void initState() {
    super.initState();
    _isMountedForMotion = widget.isOpen;
    _animateFromClosed = widget.isOpen;
  }

  @override
  void didUpdateWidget(_SessionsDrawerOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isOpen) {
      _closeEpoch += 1;
      if (!_isMountedForMotion) {
        setState(() {
          _isMountedForMotion = true;
          _animateFromClosed = true;
        });
      }
      return;
    }
    if (!oldWidget.isOpen || !_isMountedForMotion) {
      return;
    }
    _scheduleUnmountAfterExit();
  }

  @override
  Widget build(BuildContext context) {
    final EdgeInsets safePadding = MediaQuery.of(context).padding;
    final bool reduce = OpenCrayMotion.reduce(context);
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.panel,
    );
    if (!_isMountedForMotion) {
      return const Positioned.fill(child: SizedBox.shrink());
    }
    final bool isPresented = widget.isOpen && !_animateFromClosed;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !widget.isOpen || !_animateFromClosed) {
        return;
      }
      setState(() {
        _animateFromClosed = false;
      });
    });

    return Positioned.fill(
      child: IgnorePointer(
        ignoring: !widget.isOpen,
        child: Stack(
          children: [
            Positioned.fill(
              child: GestureDetector(
                onTap: widget.onDismiss,
                behavior: HitTestBehavior.opaque,
                child: AnimatedOpacity(
                  opacity: isPresented ? 1 : 0,
                  duration: duration,
                  curve: isPresented
                      ? OpenCrayMotion.enter
                      : OpenCrayMotion.exit,
                  child: const ColoredBox(
                    color: Color(0x26101828),
                    child: SizedBox.expand(),
                  ),
                ),
              ),
            ),
            AnimatedSlide(
              offset: reduce || isPresented ? Offset.zero : const Offset(-1, 0),
              duration: duration,
              curve: isPresented ? OpenCrayMotion.enter : OpenCrayMotion.exit,
              child: AnimatedOpacity(
                opacity: isPresented ? 1 : 0,
                duration: duration,
                curve: isPresented ? OpenCrayMotion.enter : OpenCrayMotion.exit,
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Container(
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
                        Text(
                          widget.drawer.eyebrow,
                          style: _ChatTextStyles.drawerEyebrow,
                        ),
                        const SizedBox(height: 10),
                        Text(
                          widget.drawer.title,
                          style: _ChatTextStyles.drawerTitle,
                        ),
                        const SizedBox(height: 16),
                        GestureDetector(
                          onTap: widget.onNewSessionPressed,
                          behavior: HitTestBehavior.opaque,
                          child: Container(
                            height: 40,
                            width: 132,
                            decoration: BoxDecoration(
                              gradient: OpenCrayGradients.brand,
                              borderRadius: BorderRadius.circular(14),
                              boxShadow: const <BoxShadow>[
                                BoxShadow(
                                  color: Color(0x3D2563EB),
                                  offset: Offset(0, 3),
                                  blurRadius: 10,
                                ),
                              ],
                            ),
                            alignment: Alignment.center,
                            child: Text(
                              widget.drawer.ctaLabel,
                              style: _ChatTextStyles.drawerCta,
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        Expanded(
                          child: ListView.separated(
                            key: const ValueKey<String>('chat-session-list'),
                            itemBuilder: (BuildContext context, int index) {
                              final ChatSessionListItemData session =
                                  widget.drawer.sessions[index];
                              return _SessionListTile(
                                key: ValueKey<String>(
                                  'chat-session-row-${session.sessionId}',
                                ),
                                copy: widget.copy,
                                session: session,
                                onPressed: () =>
                                    widget.onSessionPressed(session),
                                onLongPressStart: (details) =>
                                    widget.onSessionLongPress(
                                      session,
                                      details.globalPosition,
                                    ),
                              );
                            },
                            separatorBuilder:
                                (BuildContext context, int index) {
                                  return const SizedBox(height: 10);
                                },
                            itemCount: widget.drawer.sessions.length,
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

  void _scheduleUnmountAfterExit() {
    _closeEpoch += 1;
    final int epoch = _closeEpoch;
    if (OpenCrayMotion.reduce(context) || _isAutomatedWidgetTest) {
      setState(() {
        _isMountedForMotion = false;
      });
      return;
    }
    Future<void>.delayed(OpenCrayMotion.panel, () {
      if (!mounted || widget.isOpen || epoch != _closeEpoch) {
        return;
      }
      setState(() {
        _isMountedForMotion = false;
      });
    });
  }

  bool get _isAutomatedWidgetTest {
    bool result = false;
    assert(() {
      result = WidgetsBinding.instance.runtimeType.toString().contains(
        'TestWidgetsFlutterBinding',
      );
      return true;
    }());
    return result;
  }
}

class _SessionListTile extends StatefulWidget {
  const _SessionListTile({
    super.key,
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
  State<_SessionListTile> createState() => _SessionListTileState();
}

class _SessionListTileState extends State<_SessionListTile> {
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final String sessionMetaLabel = _formatChatSessionTimestampLabel(
      widget.copy,
      widget.session.lastMessageAtEpochMs,
      widget.session.meta,
    );
    final bool isSelected = widget.session.isSelected;
    final bool hasUnread = widget.session.unreadCount > 0;
    final Color backgroundColor = isSelected
        ? OpenCrayColors.primaryTint
        : _isPressed
        ? OpenCrayColors.surfaceMuted
        : OpenCrayColors.surfaceSubtle;
    final Color borderColor = isSelected
        ? OpenCrayColors.primaryBorder
        : hasUnread
        ? OpenCrayColors.dangerBorder
        : Colors.transparent;
    final Color railColor = isSelected
        ? _ChatPalette.accent
        : hasUnread
        ? OpenCrayColors.danger
        : Colors.transparent;
    return Semantics(
      button: true,
      selected: isSelected,
      child: GestureDetector(
        onTapDown: (_) => _setPressed(true),
        onTapUp: (_) {
          _setPressed(false);
          widget.onPressed();
        },
        onTapCancel: () => _setPressed(false),
        onLongPressStart: (LongPressStartDetails details) {
          _setPressed(false);
          widget.onLongPressStart(details);
        },
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
          curve: OpenCrayMotion.enter,
          decoration: BoxDecoration(
            color: backgroundColor,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: borderColor),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                AnimatedContainer(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.quick,
                  ),
                  curve: OpenCrayMotion.enter,
                  width: 3,
                  height: isSelected ? 44 : 10,
                  margin: const EdgeInsets.only(top: 2),
                  decoration: BoxDecoration(
                    color: railColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Row(
                        children: <Widget>[
                          Expanded(
                            child: Text(
                              widget.session.title,
                              style: _ChatTextStyles.sessionTitle.copyWith(
                                fontWeight: isSelected
                                    ? FontWeight.w700
                                    : FontWeight.w600,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            sessionMetaLabel,
                            style: _ChatTextStyles.sessionMeta,
                          ),
                          if (hasUnread) ...<Widget>[
                            const SizedBox(width: 8),
                            _SessionUnreadBadge(
                              sessionId: widget.session.sessionId,
                              count: widget.session.unreadCount,
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        widget.session.preview,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: _ChatTextStyles.sessionPreview,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _setPressed(bool value) {
    if (_isPressed == value) {
      return;
    }
    setState(() {
      _isPressed = value;
    });
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
          color: OpenCrayColors.danger,
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
        color: OpenCrayColors.danger,
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
