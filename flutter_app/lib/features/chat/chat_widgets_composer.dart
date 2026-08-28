part of 'chat_feature_screen.dart';

const int _chatComposerMaxImageAttachments = 9;

class _ComposerCard extends StatelessWidget {
  const _ComposerCard({
    required this.copy,
    required this.state,
    required this.bridge,
    required this.controller,
    required this.focusNode,
    required this.onPlusPressed,
    required this.onSendPressed,
    required this.interruptTrace,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onAddActionSelected,
    required this.onCommandSelected,
    required this.onAttachmentRemoved,
  });

  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final OpenCrayHostBridge? bridge;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback onPlusPressed;
  final VoidCallback onSendPressed;
  final ChatRunTraceData? interruptTrace;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatAddActionData> onAddActionSelected;
  final VoidCallback onCommandSelected;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    final bool hasTodos = state.composer.todos.isNotEmpty;
    final bool hasCommands = state.composer.commandOptions.isNotEmpty;
    final bool hasPersistentIntegratedSurface =
        hasCommands || state.composer.attachments.isNotEmpty;

    return TweenAnimationBuilder<double>(
      tween: Tween<double>(end: state.composer.showAddMenu ? 1 : 0),
      duration: OpenCrayMotion.resolve(
        context,
        state.composer.showAddMenu
            ? OpenCrayMotion.expand
            : OpenCrayMotion.quick,
      ),
      curve: OpenCrayMotion.expandCurve,
      builder: (context, addMenuProgress, child) {
        final double surfaceProgress = hasPersistentIntegratedSurface
            ? 1
            : addMenuProgress;
        final Widget content = Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            if (hasTodos) ...<Widget>[
              _TodoListPanel(todos: state.composer.todos),
              const SizedBox(height: 12),
            ],
            _ComposerSecondarySection(
              sectionKey: 'commands',
              isVisible: hasCommands,
              bottomGap: 10,
              child: hasCommands
                  ? Container(
                      decoration: BoxDecoration(
                        color: _ChatPalette.subtleSurface,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            copy.chatCommands,
                            style: _ChatTextStyles.commandsLabel,
                          ),
                          const SizedBox(height: 8),
                          ...state.composer.commandOptions.map(
                            (ChatCommandOptionData option) =>
                                _CommandOptionTile(
                                  option: option,
                                  onPressed: onCommandSelected,
                                ),
                          ),
                        ],
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            _ComposerAttachmentSection(
              attachments: state.composer.attachments,
              bridge: bridge,
              onAttachmentRemoved: onAttachmentRemoved,
            ),
            AnimatedBuilder(
              animation: controller,
              builder: (BuildContext context, Widget? child) {
                final bool hasSendableContent =
                    controller.text.trim().isNotEmpty ||
                    state.composer.attachments.isNotEmpty;
                final ChatRunTraceData? effectiveInterruptTrace =
                    state.isInputEnabled && !hasSendableContent
                    ? interruptTrace
                    : null;
                final bool showInterruptConfirm =
                    effectiveInterruptTrace != null &&
                    interruptConfirmRunId ==
                        effectiveInterruptTrace.interruptId;
                if (showInterruptConfirm) {
                  return _ComposerInterruptConfirmSurface(
                    copy: copy,
                    trace: effectiveInterruptTrace,
                    isBusy: busyInterruptRunIds.contains(
                      effectiveInterruptTrace.interruptId,
                    ),
                    onDismiss: () =>
                        onDismissInterruptRunTrace(effectiveInterruptTrace),
                    onConfirmed: () =>
                        onInterruptRunTrace(effectiveInterruptTrace),
                  );
                }
                return _InputRow(
                  placeholder: state.composer.placeholder,
                  controller: controller,
                  focusNode: focusNode,
                  enabled: state.isInputEnabled,
                  surfaceProgress: surfaceProgress,
                  defaultGlassProgress: hasTodos ? 0 : 1 - surfaceProgress,
                  plusProgress: addMenuProgress,
                  interruptTrace: effectiveInterruptTrace,
                  isInterruptBusy:
                      effectiveInterruptTrace != null &&
                      busyInterruptRunIds.contains(
                        effectiveInterruptTrace.interruptId,
                      ),
                  onInterruptPressed: effectiveInterruptTrace == null
                      ? null
                      : () => onArmInterruptRunTrace(effectiveInterruptTrace),
                  onPlusPressed: onPlusPressed,
                  onSendPressed: onSendPressed,
                );
              },
            ),
            _ComposerAddTray(
              progress: addMenuProgress,
              isOpen: state.composer.showAddMenu,
              copy: copy,
              actions: state.composer.addActions,
              onAddActionSelected: onAddActionSelected,
            ),
          ],
        );

        final Widget surface;
        if (hasTodos) {
          surface = _ComposerGlassSurface(
            child: Padding(padding: const EdgeInsets.all(12), child: content),
          );
        } else {
          surface = _ComposerMaterialSurface(
            progress: surfaceProgress,
            child: content,
          );
        }

        return AnimatedSize(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.expand),
          curve: OpenCrayMotion.expandCurve,
          alignment: Alignment.bottomCenter,
          child: surface,
        );
      },
    );
  }
}

class _ComposerSecondarySection extends StatefulWidget {
  const _ComposerSecondarySection({
    required this.sectionKey,
    required this.isVisible,
    required this.child,
    this.bottomGap = 0,
    this.updateChildDuringExit = false,
  });

  final String sectionKey;
  final bool isVisible;
  final Widget child;
  final double bottomGap;
  final bool updateChildDuringExit;

  @override
  State<_ComposerSecondarySection> createState() =>
      _ComposerSecondarySectionState();
}

class _ComposerSecondarySectionState extends State<_ComposerSecondarySection> {
  Widget? _motionChild;
  int _exitEpoch = 0;

  @override
  void initState() {
    super.initState();
    if (widget.isVisible) {
      _motionChild = widget.child;
    }
  }

  @override
  void didUpdateWidget(covariant _ComposerSecondarySection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isVisible) {
      _exitEpoch += 1;
      _motionChild = widget.child;
      return;
    }
    if (widget.updateChildDuringExit) {
      _motionChild = widget.child;
    }
    if (oldWidget.isVisible && _motionChild != null) {
      _scheduleClearAfterExit();
    }
  }

  @override
  Widget build(BuildContext context) {
    final Widget? child = widget.isVisible ? widget.child : _motionChild;
    if (child == null) {
      return SizedBox.shrink(
        key: ValueKey<String>('chat-composer-${widget.sectionKey}-empty'),
      );
    }
    final bool reduce = OpenCrayMotion.reduce(context);
    final double target = widget.isVisible ? 1 : 0;
    return TweenAnimationBuilder<double>(
      key: ValueKey<String>('chat-composer-${widget.sectionKey}-section'),
      tween: Tween<double>(end: target),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.expand),
      curve: OpenCrayMotion.expandCurve,
      child: Padding(
        padding: EdgeInsets.only(bottom: widget.bottomGap),
        child: child,
      ),
      builder: (BuildContext context, double value, Widget? child) {
        final double t = reduce ? target : value.clamp(0.0, 1.0).toDouble();
        return ClipRect(
          child: Align(
            alignment: Alignment.bottomCenter,
            heightFactor: t,
            child: IgnorePointer(
              ignoring: !widget.isVisible,
              child: Opacity(
                opacity: reduce
                    ? target
                    : (t * 1.18).clamp(0.0, 1.0).toDouble(),
                child: Transform.translate(
                  offset: Offset(0, (1 - t) * 6),
                  child: child,
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  void _scheduleClearAfterExit() {
    _exitEpoch += 1;
    final int epoch = _exitEpoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.expand,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      if (mounted && epoch == _exitEpoch) {
        setState(() {
          _motionChild = null;
        });
      }
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted || widget.isVisible || epoch != _exitEpoch) {
        return;
      }
      setState(() {
        _motionChild = null;
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

class _ComposerAttachmentSection extends StatelessWidget {
  const _ComposerAttachmentSection({
    required this.attachments,
    required this.bridge,
    required this.onAttachmentRemoved,
  });

  final List<ChatAttachmentData> attachments;
  final OpenCrayHostBridge? bridge;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  Widget build(BuildContext context) {
    return _ComposerSecondarySection(
      sectionKey: 'attachments',
      isVisible: attachments.isNotEmpty,
      bottomGap: 10,
      updateChildDuringExit: true,
      child: _ComposerAttachmentStrip(
        key: const ValueKey<String>('chat-composer-attachment-strip'),
        attachments: attachments,
        bridge: bridge,
        onAttachmentRemoved: onAttachmentRemoved,
      ),
    );
  }
}

class _ComposerAttachmentStrip extends StatefulWidget {
  const _ComposerAttachmentStrip({
    super.key,
    required this.attachments,
    required this.bridge,
    required this.onAttachmentRemoved,
  });

  final List<ChatAttachmentData> attachments;
  final OpenCrayHostBridge? bridge;
  final ValueChanged<ChatAttachmentData> onAttachmentRemoved;

  @override
  State<_ComposerAttachmentStrip> createState() =>
      _ComposerAttachmentStripState();
}

class _ComposerAttachmentStripState extends State<_ComposerAttachmentStrip> {
  final Map<String, ChatAttachmentData> _exitingAttachments =
      <String, ChatAttachmentData>{};
  final Map<String, int> _exitEpochByAttachment = <String, int>{};

  @override
  void didUpdateWidget(covariant _ComposerAttachmentStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    final Set<String> currentIds = widget.attachments
        .map((ChatAttachmentData attachment) => attachment.id)
        .toSet();
    for (final ChatAttachmentData attachment in oldWidget.attachments) {
      if (!currentIds.contains(attachment.id)) {
        _exitingAttachments[attachment.id] = attachment;
        _scheduleAttachmentExitClear(attachment.id);
      }
    }
    for (final ChatAttachmentData attachment in widget.attachments) {
      _exitingAttachments.remove(attachment.id);
      _exitEpochByAttachment.remove(attachment.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final Set<String> currentIds = widget.attachments
        .map((ChatAttachmentData attachment) => attachment.id)
        .toSet();
    final List<_ComposerAttachmentStripEntry> entries =
        <_ComposerAttachmentStripEntry>[
          ...widget.attachments.map(
            (ChatAttachmentData attachment) => _ComposerAttachmentStripEntry(
              attachment: attachment,
              isPresent: true,
            ),
          ),
          ..._exitingAttachments.values
              .where(
                (ChatAttachmentData attachment) =>
                    !currentIds.contains(attachment.id),
              )
              .map(
                (ChatAttachmentData attachment) =>
                    _ComposerAttachmentStripEntry(
                      attachment: attachment,
                      isPresent: false,
                    ),
              ),
        ];
    if (entries.isEmpty) {
      return const SizedBox.shrink(
        key: ValueKey<String>('chat-composer-attachments-empty'),
      );
    }
    return SizedBox(
      key: const ValueKey<String>('chat-composer-attachments'),
      height: 68,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: EdgeInsets.zero,
        physics: const BouncingScrollPhysics(),
        itemCount: entries.length,
        separatorBuilder: (BuildContext context, int index) =>
            const SizedBox(width: 8),
        itemBuilder: (BuildContext context, int index) {
          final _ComposerAttachmentStripEntry entry = entries[index];
          return _ComposerAttachmentCardMotion(
            key: ValueKey<String>(
              'chat-composer-attachment-motion-${entry.attachment.id}',
            ),
            isPresent: entry.isPresent,
            child: _AttachmentCard(
              attachment: entry.attachment,
              bridge: widget.bridge,
              onRemove: entry.isPresent
                  ? () => widget.onAttachmentRemoved(entry.attachment)
                  : () {},
            ),
          );
        },
      ),
    );
  }

  void _scheduleAttachmentExitClear(String attachmentId) {
    final int epoch = (_exitEpochByAttachment[attachmentId] ?? 0) + 1;
    _exitEpochByAttachment[attachmentId] = epoch;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.expand,
    );
    if (duration == Duration.zero || _isAutomatedWidgetTest) {
      _exitingAttachments.remove(attachmentId);
      _exitEpochByAttachment.remove(attachmentId);
      return;
    }
    Future<void>.delayed(duration, () {
      if (!mounted || _exitEpochByAttachment[attachmentId] != epoch) {
        return;
      }
      final bool isCurrent = widget.attachments.any(
        (ChatAttachmentData attachment) => attachment.id == attachmentId,
      );
      if (isCurrent) {
        return;
      }
      setState(() {
        _exitingAttachments.remove(attachmentId);
        _exitEpochByAttachment.remove(attachmentId);
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

class _ComposerAttachmentStripEntry {
  const _ComposerAttachmentStripEntry({
    required this.attachment,
    required this.isPresent,
  });

  final ChatAttachmentData attachment;
  final bool isPresent;
}

class _ComposerAttachmentCardMotion extends StatefulWidget {
  const _ComposerAttachmentCardMotion({
    super.key,
    required this.isPresent,
    required this.child,
  });

  final bool isPresent;
  final Widget child;

  @override
  State<_ComposerAttachmentCardMotion> createState() =>
      _ComposerAttachmentCardMotionState();
}

class _ComposerAttachmentCardMotionState
    extends State<_ComposerAttachmentCardMotion> {
  late bool _isPresent = widget.isPresent;

  @override
  void initState() {
    super.initState();
    if (widget.isPresent && !_isAutomatedWidgetTest) {
      _isPresent = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        setState(() {
          _isPresent = true;
        });
      });
    }
  }

  @override
  void didUpdateWidget(covariant _ComposerAttachmentCardMotion oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.isPresent != widget.isPresent) {
      setState(() {
        _isPresent = widget.isPresent;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _isPresent ? OpenCrayMotion.expand : OpenCrayMotion.quick,
    );
    final Curve curve = _isPresent ? OpenCrayMotion.enter : OpenCrayMotion.exit;
    return AnimatedSlide(
      offset: _isPresent ? Offset.zero : const Offset(0, 0.14),
      duration: duration,
      curve: curve,
      child: AnimatedOpacity(
        opacity: _isPresent ? 1 : 0,
        duration: duration,
        curve: curve,
        child: IgnorePointer(ignoring: !_isPresent, child: widget.child),
      ),
    );
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

class _ComposerMaterialSurface extends StatelessWidget {
  const _ComposerMaterialSurface({required this.progress, required this.child});

  final double progress;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final double t = progress.clamp(0.0, 1.0).toDouble();
    return DecoratedBox(
      key: const ValueKey<String>('chat-composer-material-surface'),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: t),
        borderRadius: BorderRadius.circular(16 + (8 * t)),
        border: Border.all(color: _ChatPalette.border.withValues(alpha: t)),
        boxShadow: t == 0
            ? const <BoxShadow>[]
            : <BoxShadow>[
                BoxShadow(
                  color: _ChatGlass.composerShadowInk.withValues(
                    alpha: 0.05 * t,
                  ),
                  blurRadius: 18 * t,
                  offset: Offset(0, 5 * t),
                ),
              ],
      ),
      child: Padding(padding: EdgeInsets.all(10 * t), child: child),
    );
  }
}

class _ComposerAddTray extends StatelessWidget {
  const _ComposerAddTray({
    required this.progress,
    required this.isOpen,
    required this.copy,
    required this.actions,
    required this.onAddActionSelected,
  });

  final double progress;
  final bool isOpen;
  final OpenCrayUiCopy copy;
  final List<ChatAddActionData> actions;
  final ValueChanged<ChatAddActionData> onAddActionSelected;

  @override
  Widget build(BuildContext context) {
    if (!isOpen && progress <= 0) {
      return const SizedBox.shrink(
        key: ValueKey<String>('chat-composer-add-menu-empty'),
      );
    }
    final double t = progress.clamp(0.0, 1.0).toDouble();
    return ClipRect(
      key: const ValueKey<String>('chat-composer-add-tray'),
      child: Align(
        alignment: Alignment.topCenter,
        heightFactor: OpenCrayMotion.reduce(context) ? (isOpen ? 1 : 0) : t,
        child: Opacity(
          opacity: OpenCrayMotion.reduce(context)
              ? (isOpen ? 1 : 0)
              : (t * 1.2).clamp(0.0, 1.0).toDouble(),
          child: IgnorePointer(
            ignoring: !isOpen,
            child: _ComposerAddMenu(
              key: const ValueKey<String>('chat-composer-add-menu'),
              copy: copy,
              actions: actions,
              onAddActionSelected: onAddActionSelected,
            ),
          ),
        ),
      ),
    );
  }
}

class _ComposerAddMenu extends StatelessWidget {
  const _ComposerAddMenu({
    super.key,
    required this.copy,
    required this.actions,
    required this.onAddActionSelected,
  });

  final OpenCrayUiCopy copy;
  final List<ChatAddActionData> actions;
  final ValueChanged<ChatAddActionData> onAddActionSelected;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(copy.chatAddToMessage, style: _ChatTextStyles.sectionLabel),
          const SizedBox(height: 10),
          Row(
            children: actions
                .map(
                  (ChatAddActionData action) => Expanded(
                    flex: action.label == copy.chatActionCommand ? 12 : 9,
                    child: Padding(
                      padding: EdgeInsets.only(
                        right: action == actions.last ? 0 : 8,
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
      ),
    );
  }
}

class _ComposerInterruptConfirmSurface extends StatefulWidget {
  const _ComposerInterruptConfirmSurface({
    required this.copy,
    required this.trace,
    required this.isBusy,
    required this.onDismiss,
    required this.onConfirmed,
  });

  final OpenCrayUiCopy copy;
  final ChatRunTraceData trace;
  final bool isBusy;
  final VoidCallback onDismiss;
  final VoidCallback onConfirmed;

  @override
  State<_ComposerInterruptConfirmSurface> createState() =>
      _ComposerInterruptConfirmSurfaceState();
}

class _ComposerInterruptConfirmSurfaceState
    extends State<_ComposerInterruptConfirmSurface> {
  bool _outsideDismissReady = false;

  @override
  void initState() {
    super.initState();
    _scheduleOutsideDismissReady();
  }

  @override
  void didUpdateWidget(covariant _ComposerInterruptConfirmSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.trace.interruptId != widget.trace.interruptId) {
      _outsideDismissReady = false;
      _scheduleOutsideDismissReady();
    }
  }

  void _scheduleOutsideDismissReady() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _outsideDismissReady = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return TapRegion(
      onTapOutside: _outsideDismissReady ? (_) => widget.onDismiss() : null,
      child: _RunTraceInterruptConfirmRow(
        key: ValueKey<String>(
          'chat-composer-interrupt-confirm-${widget.trace.interruptId}',
        ),
        copy: widget.copy,
        runId: widget.trace.interruptId,
        isBusy: widget.isBusy,
        onConfirmed: widget.onConfirmed,
      ),
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
                        OpenCrayColors.primaryTint.withValues(alpha: 0.18),
                        OpenCrayColors.primaryBorder.withValues(alpha: 0.12),
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

class _TodoListPanel extends StatefulWidget {
  const _TodoListPanel({required this.todos});

  static const int _maxVisibleTodoCount = 4;
  static const double _itemHeight = 28;
  static const double _itemGap = 6;

  final List<ChatTodoItemData> todos;

  @override
  State<_TodoListPanel> createState() => _TodoListPanelState();
}

class _TodoListPanelState extends State<_TodoListPanel> {
  bool _isExpanded = true;

  @override
  Widget build(BuildContext context) {
    final int visibleCount = math.min(
      widget.todos.length,
      _TodoListPanel._maxVisibleTodoCount,
    );
    final double listHeight =
        (visibleCount * _TodoListPanel._itemHeight) +
        (visibleCount > 0 ? (visibleCount - 1) * _TodoListPanel._itemGap : 0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Row(
          children: <Widget>[
            Text('TODO', style: _ChatTextStyles.todoLabel),
            const Spacer(),
            GestureDetector(
              key: const ValueKey<String>('chat-composer-todo-chevron'),
              behavior: HitTestBehavior.opaque,
              onTap: () {
                setState(() {
                  _isExpanded = !_isExpanded;
                });
              },
              child: SizedBox.square(
                dimension: 24,
                child: Center(
                  child: AnimatedRotation(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.quick,
                    ),
                    turns: _isExpanded ? 0.5 : 0,
                    child: const Icon(
                      CupertinoIcons.chevron_up,
                      size: 13,
                      color: _ChatPalette.textTertiary,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        if (_isExpanded) ...<Widget>[
          const SizedBox(height: 10),
          SizedBox(
            key: const ValueKey<String>('chat-composer-todo-list'),
            height: listHeight,
            child: ListView.separated(
              padding: EdgeInsets.zero,
              physics: widget.todos.length > _TodoListPanel._maxVisibleTodoCount
                  ? const ClampingScrollPhysics()
                  : const NeverScrollableScrollPhysics(),
              itemCount: widget.todos.length,
              itemBuilder: (BuildContext context, int index) {
                return _TodoRow(todo: widget.todos[index], index: index);
              },
              separatorBuilder: (BuildContext context, int index) {
                return const SizedBox(height: _TodoListPanel._itemGap);
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
    required this.surfaceProgress,
    required this.defaultGlassProgress,
    required this.plusProgress,
    required this.interruptTrace,
    required this.isInterruptBusy,
    required this.onInterruptPressed,
    required this.onPlusPressed,
    required this.onSendPressed,
  });

  final String placeholder;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool enabled;
  final double surfaceProgress;
  final double defaultGlassProgress;
  final double plusProgress;
  final ChatRunTraceData? interruptTrace;
  final bool isInterruptBusy;
  final VoidCallback? onInterruptPressed;
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
                final double surfaceT = surfaceProgress
                    .clamp(0.0, 1.0)
                    .toDouble();
                final double outlineT = focusNode.hasFocus ? 1 : surfaceT;
                final Color fieldOutlineColor = focusNode.hasFocus
                    ? _ChatPalette.accent
                    : _ChatPalette.composerStroke;

                return AnimatedSize(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.expand,
                  ),
                  curve: OpenCrayMotion.expandCurve,
                  alignment: Alignment.bottomCenter,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(
                      minHeight: messageFieldMinHeight,
                      maxHeight: messageFieldMaxHeight,
                    ),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Color.lerp(
                          Colors.white,
                          fieldOutlineColor,
                          outlineT,
                        ),
                        borderRadius: messageFieldRadius,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(outlineT),
                        child: ClipRRect(
                          borderRadius: BorderRadius.lerp(
                            messageFieldRadius,
                            messageFieldInnerRadius,
                            outlineT,
                          )!,
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
                                onTapOutside: enabled
                                    ? (_) => focusNode.unfocus()
                                    : null,
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
          key: const ValueKey<String>('chat-composer-plus-button'),
          backgroundColor: Color.lerp(
            Colors.white,
            _ChatPalette.plusActiveSurface,
            plusProgress.clamp(0.0, 1.0).toDouble(),
          )!,
          foregroundColor: Color.lerp(
            _ChatPalette.textSecondary,
            _ChatPalette.accent,
            plusProgress.clamp(0.0, 1.0).toDouble(),
          )!,
          icon: Icons.add_rounded,
          onPressed: enabled ? onPlusPressed : null,
        ),
        const SizedBox(width: 8),
        AnimatedBuilder(
          animation: controller,
          builder: (BuildContext context, Widget? child) {
            final bool showInterruptButton =
                enabled &&
                interruptTrace != null &&
                controller.text.trim().isEmpty;
            if (showInterruptButton) {
              return _CircleButton(
                key: const ValueKey<String>('chat-composer-interrupt-button'),
                backgroundColor: _ChatPalette.runTraceInterruptAction,
                foregroundColor: Colors.white,
                icon: Icons.stop_rounded,
                onPressed: isInterruptBusy ? null : onInterruptPressed,
              );
            }
            return _CircleButton(
              key: const ValueKey<String>('chat-composer-send-button'),
              backgroundColor: _ChatPalette.accent,
              gradient: OpenCrayGradients.brand,
              foregroundColor: Colors.white,
              icon: Icons.arrow_upward_rounded,
              onPressed: enabled ? onSendPressed : null,
            );
          },
        ),
      ],
    );

    final double glassT = defaultGlassProgress.clamp(0.0, 1.0).toDouble();
    if (glassT <= 0) {
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
                child: Opacity(
                  opacity: glassT,
                  child: const DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: <Color>[
                          _ChatPalette.surfaceClear,
                          _ChatGlass.composerHighlight,
                          _ChatGlass.composerSheen,
                          _ChatGlass.composerSheenClear,
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
        ),
        inputRow,
      ],
    );
  }
}

class _CircleButton extends StatefulWidget {
  const _CircleButton({
    super.key,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.icon,
    required this.onPressed,
    this.gradient,
  });

  final Color backgroundColor;
  final Color foregroundColor;
  final IconData icon;
  final VoidCallback? onPressed;
  final Gradient? gradient;

  @override
  State<_CircleButton> createState() => _CircleButtonState();
}

class _CircleButtonState extends State<_CircleButton> {
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    final bool enabled = widget.onPressed != null;
    final Color backgroundColor = widget.backgroundColor;
    return GestureDetector(
      onTap: enabled ? _handleTap : null,
      onTapDown: enabled ? (_) => _setPressed(true) : null,
      onTapUp: enabled ? (_) => _setPressed(false) : null,
      onTapCancel: enabled ? () => _setPressed(false) : null,
      behavior: HitTestBehavior.opaque,
      child: AnimatedScale(
        scale: _isPressed ? 0.9 : 1,
        duration: OpenCrayMotion.resolve(context, OpenCrayMotion.instant),
        curve: OpenCrayMotion.enter,
        child: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: enabled ? backgroundColor : backgroundColor.withValues(alpha: 0.4),
            gradient: enabled ? widget.gradient : null,
            borderRadius: BorderRadius.circular(14),
            boxShadow: enabled && widget.gradient != null
                ? OpenCrayShadows.brandGlow
                : null,
          ),
          child: Icon(
            widget.icon,
            color: enabled
                ? widget.foregroundColor
                : widget.foregroundColor.withValues(alpha: 0.5),
            size: 18,
          ),
        ),
      ),
    );
  }

  void _setPressed(bool value) {
    if (_isPressed == value) {
      return;
    }
    setState(() => _isPressed = value);
  }

  void _handleTap() {
    // Send / interrupt / add all commit something; confirm the tap in the hand.
    HapticFeedback.selectionClick();
    widget.onPressed!();
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
          color: OpenCrayColors.surfaceSubtle,
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
  const _AttachmentCard({
    required this.attachment,
    required this.onRemove,
    this.bridge,
  });

  final ChatAttachmentData attachment;
  final VoidCallback onRemove;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>('chat-composer-attachment-${attachment.id}'),
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
                _ComposerAttachmentLeadingVisual(
                  attachment: attachment,
                  bridge: bridge,
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

class _ComposerAttachmentLeadingVisual extends StatefulWidget {
  const _ComposerAttachmentLeadingVisual({
    required this.attachment,
    required this.bridge,
  });

  final ChatAttachmentData attachment;
  final OpenCrayHostBridge? bridge;

  @override
  State<_ComposerAttachmentLeadingVisual> createState() =>
      _ComposerAttachmentLeadingVisualState();
}

class _ComposerAttachmentLeadingVisualState
    extends State<_ComposerAttachmentLeadingVisual> {
  Future<OpenCrayFileImagePreview>? _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _ComposerAttachmentLeadingVisual oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.bridge != widget.bridge ||
        oldWidget.attachment.id != widget.attachment.id ||
        oldWidget.attachment.draftAttachment?.relativePath !=
            widget.attachment.draftAttachment?.relativePath) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OpenCrayFileImagePreview>? _loadPreview() {
    if (widget.attachment.kind != ChatAttachmentKind.image) {
      return null;
    }
    final OpenCrayHostBridge? bridge = widget.bridge;
    final String relativePath =
        widget.attachment.draftAttachment?.relativePath.trim() ?? '';
    if (bridge == null || relativePath.isEmpty) {
      return null;
    }
    return bridge.loadWorkspaceImagePreview(relativePath);
  }

  @override
  Widget build(BuildContext context) {
    final ChatAttachmentData attachment = widget.attachment;
    final IconData icon = attachment.kind == ChatAttachmentKind.image
        ? Icons.image_outlined
        : Icons.description_outlined;
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    if (previewFuture == null) {
      return _buildAnimatedLeadingVisual(
        context,
        _buildIconPlaceholder(icon, attachment.id),
      );
    }
    return FutureBuilder<OpenCrayFileImagePreview>(
      future: previewFuture,
      builder: (context, snapshot) {
        final OpenCrayFileImagePreview? preview = snapshot.data;
        final bool ready = preview != null && preview.bytes.isNotEmpty;
        if (!ready) {
          return _buildAnimatedLeadingVisual(
            context,
            _buildIconPlaceholder(icon, attachment.id),
          );
        }
        return _buildAnimatedLeadingVisual(
          context,
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Container(
              key: ValueKey<String>(
                'chat-composer-image-preview-${attachment.id}',
              ),
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.75),
                borderRadius: BorderRadius.circular(10),
              ),
              child: OpenCrayImageBytesView(
                bytes: preview.bytes,
                mimeType: preview.mimeType,
                fit: BoxFit.cover,
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildAnimatedLeadingVisual(BuildContext context, Widget child) {
    return AnimatedSwitcher(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
      switchInCurve: OpenCrayMotion.enter,
      switchOutCurve: OpenCrayMotion.exit,
      transitionBuilder: (Widget child, Animation<double> animation) {
        return FadeTransition(opacity: animation, child: child);
      },
      child: child,
    );
  }

  Widget _buildIconPlaceholder(IconData icon, String attachmentId) {
    return Container(
      key: ValueKey<String>('chat-composer-attachment-icon-$attachmentId'),
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(icon, size: 16, color: _ChatPalette.textPrimary),
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
            color: OpenCrayColors.surfaceSubtle,
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
