part of 'chat_feature_screen.dart';

class _ChatScrollContent extends StatelessWidget {
  const _ChatScrollContent({
    required this.bridge,
    required this.copy,
    required this.state,
    required this.scrollController,
    required this.showSandboxPreviewCards,
    required this.voicePlaybackControllerFactory,
    required this.selectedMessageIds,
    required this.deletingMessageIds,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.busyRetryRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onRetryRunTrace,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final ScrollController scrollController;
  final bool showSandboxPreviewCards;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final Set<String> selectedMessageIds;
  final Set<String> deletingMessageIds;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onRetryRunTrace;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, OpenCrayMarkdownSelectionSnapshot?)
  onMessageTextSelectionChanged;

  @override
  Widget build(BuildContext context) {
    return SliverMainAxisGroup(
      slivers: <Widget>[
        SliverToBoxAdapter(
          child: _ChatHeaderCluster(
            bridge: bridge,
            copy: copy,
            state: state,
            scrollController: scrollController,
          ),
        ),
        if (state.messages.isEmpty &&
            state.runTraces.isEmpty &&
            state.pendingApprovals.isEmpty)
          SliverToBoxAdapter(child: SizedBox(height: state.emptyThreadHeight))
        else
          _MessageList(
            bridge: bridge,
            copy: copy,
            showSandboxPreviewCards: showSandboxPreviewCards,
            voicePlaybackControllerFactory: voicePlaybackControllerFactory,
            messages: state.messages,
            runTraces: state.runTraces,
            selectedMessageIds: selectedMessageIds,
            deletingMessageIds: deletingMessageIds,
            interruptConfirmRunId: interruptConfirmRunId,
            busyInterruptRunIds: busyInterruptRunIds,
            busyRetryRunIds: busyRetryRunIds,
            onArmInterruptRunTrace: onArmInterruptRunTrace,
            onDismissInterruptRunTrace: onDismissInterruptRunTrace,
            onInterruptRunTrace: onInterruptRunTrace,
            onRetryRunTrace: onRetryRunTrace,
            onMessageLongPress: onMessageLongPress,
            onMessageSelectionToggle: onMessageSelectionToggle,
            onMessageTextSelectionChanged: onMessageTextSelectionChanged,
          ),
      ],
    );
  }
}

class _ChatHeaderCluster extends StatelessWidget {
  const _ChatHeaderCluster({
    required this.bridge,
    required this.copy,
    required this.state,
    required this.scrollController,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatFeatureState state;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context) {
    final bool isActiveThread =
        state.messages.isNotEmpty ||
        state.runTraces.isNotEmpty ||
        state.pendingApprovals.isNotEmpty;
    return AnimatedBuilder(
      animation: scrollController,
      builder: (BuildContext context, Widget? child) {
        final double scrollOffset = scrollController.hasClients
            ? scrollController.offset
            : 0;
        final double scrollProgress = (scrollOffset / 96).clamp(0.0, 1.0);
        final TextStyle titleStyle = OpenCrayTypography.pageTitle(
          context.palette,
        ).copyWith(
          color: Color.lerp(
            context.chatPalette.textPrimary,
            context.chatPalette.textSecondary,
            isActiveThread ? scrollProgress * 0.28 : 0,
          ),
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            AnimatedDefaultTextStyle(
              duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
              curve: OpenCrayMotion.enter,
              style: titleStyle,
              child: Text(state.screenTitle),
            ),
            SizedBox(height: isActiveThread ? 14 : 20),
            if (state.isAwaitingFirstSnapshot && !isActiveThread)
              _SummaryCardSkeleton(copy: copy)
            else
              _SummaryCard(
                copy: copy,
                summary: state.summary,
                bridge: bridge,
                isActiveThread: isActiveThread,
                scrollProgress: scrollProgress,
              ),
            SizedBox(height: isActiveThread ? 14 : 20),
          ],
        );
      },
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
      context.chatPalette.surfaceClear,
      context.chatGlass.barBorderActive,
      strength,
    )!;
    final Color shadowColor = Color.lerp(
      Colors.transparent,
      context.chatGlass.barShadowActive,
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
                    context.chatGlass.barTopRest,
                    context.chatGlass.barTopActive,
                    strength,
                  )!,
                  Color.lerp(
                    context.chatGlass.barMidRest,
                    context.chatGlass.barMidActive,
                    strength,
                  )!,
                  Color.lerp(
                    context.chatGlass.barFootRest,
                    context.chatGlass.barFootActive,
                    strength,
                  )!,
                  context.chatGlass.barFootClear,
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

/// Horizontal page gutter the chat transcript and title use.
const double _pageGutter = 20;

/// Tap target and glyph size for the top-strip icon button.
const double _toolbarTapTarget = 44;
const double _toolbarGlyphSize = 22;

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
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: _pageGutter),
        child: SizedBox(
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
                      style: context.chatText.selectionToolbarAction,
                    ),
                  ),
                ),
              ),
              Expanded(
                child: Center(
                  child: Text(
                    copy.chatSelectionCount(selectedCount),
                    style: context.chatText.selectionToolbarTitle,
                  ),
                ),
              ),
              const SizedBox(width: 56),
            ],
          ),
        ),
      );
    }
    // The glyph, not its hit box, is what should line up with the page gutter and
    // the title below. Inset the row by the leftover on each side of the icon so
    // the 44dp target overhangs the margin instead of pushing the glyph inward.
    const double leadingInset =
        _pageGutter - (_toolbarTapTarget - _toolbarGlyphSize) / 2;
    return Padding(
      padding: const EdgeInsets.fromLTRB(leadingInset, 0, _pageGutter, 0),
      child: SizedBox(
        height: _toolbarTapTarget,
        child: Row(
          children: <Widget>[
            _ChatToolbarIconButton(
              key: const ValueKey<String>('chat-sessions-button'),
              tooltip: sessionButtonLabel,
              icon: Icons.menu_rounded,
              onPressed: onSessionsPressed,
            ),
            const Spacer(),
            _ChatAutomationModeStamp(copy: copy, modeLabel: modeLabel),
          ],
        ),
      ),
    );
  }
}

class _ChatToolbarIconButton extends StatefulWidget {
  const _ChatToolbarIconButton({
    super.key,
    required this.tooltip,
    required this.icon,
    required this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  State<_ChatToolbarIconButton> createState() => _ChatToolbarIconButtonState();
}

class _ChatToolbarIconButtonState extends State<_ChatToolbarIconButton> {
  bool _isPressed = false;

  @override
  Widget build(BuildContext context) {
    // A bare glyph rather than a card: this is a frequent, low-stakes control,
    // and giving it `surface` + `border` + `cardShadow` announced it with the
    // same weight as a content card. Depth for the strip comes from the glass
    // bar behind it, so the press state only needs to move the ink.
    return Semantics(
      button: true,
      label: widget.tooltip,
      onTap: widget.onPressed,
      child: Tooltip(
        message: widget.tooltip,
        child: GestureDetector(
          onTapDown: (_) => _setPressed(true),
          onTapUp: (_) => _setPressed(false),
          onTapCancel: () => _setPressed(false),
          onTap: widget.onPressed,
          behavior: HitTestBehavior.opaque,
          child: SizedBox.square(
            dimension: _toolbarTapTarget,
            child: Center(
              child: AnimatedContainer(
                duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
                curve: OpenCrayMotion.enter,
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: _isPressed
                      ? context.palette.surfaceMuted
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(
                  widget.icon,
                  size: _toolbarGlyphSize,
                  color: _isPressed
                      ? context.chatPalette.textPrimary
                      : context.chatPalette.textSecondary,
                ),
              ),
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

/// The automation mode the host is running under (SAFE / AUTO / DEV), stamped in
/// the top strip as ambient text.
///
/// Deliberately not a control. This reads out the approval policy, which is
/// worth keeping pinned while you work; picking a runtime backend is a
/// configuration decision and lives with the rest of the sandbox settings.
class _ChatAutomationModeStamp extends StatelessWidget {
  const _ChatAutomationModeStamp({required this.copy, required this.modeLabel});

  final OpenCrayUiCopy copy;
  final String modeLabel;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: copy.chatAutomationModeSemanticLabel(modeLabel),
      excludeSemantics: true,
      child: Text(
        modeLabel,
        key: const ValueKey<String>('chat-runtime-mode-label'),
        style: context.chatText.toolbarModeStamp,
      ),
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
      decoration: _ChatDecorations.card(context),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          children: <Widget>[
            Expanded(
              child: _ChatSelectionActionButton(
                key: const ValueKey<String>('chat-selection-copy'),
                icon: Icons.content_copy_rounded,
                label: copy.chatMessageCopyAction,
                onPressed: selectedCount > 0 ? onCopyPressed : null,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: _ChatSelectionActionButton(
                key: const ValueKey<String>('chat-selection-delete'),
                icon: Icons.delete_outline_rounded,
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
        ? context.palette.danger
        : context.chatPalette.textPrimary;
    final VoidCallback? handlePress = onPressed == null
        ? null
        : () {
            // Feedback first: the delete path pops a confirmation and the copy
            // path writes the clipboard, and both read better after the tick.
            if (isDestructive) {
              HapticFeedback.mediumImpact();
            } else {
              HapticFeedback.selectionClick();
            }
            onPressed!();
          };
    return GestureDetector(
      onTap: handlePress,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: onPressed == null ? 0.38 : 1,
        child: Container(
          height: 44,
          decoration: BoxDecoration(
            color: isDestructive
                ? context.palette.dangerTint
                : context.chatPalette.subtleSurface,
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
                  style: context.chatText.selectionAction.copyWith(
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

/// Stands in for [_SummaryCard] until the first host snapshot lands.
///
/// A restored session and a brand-new one are both empty on the first frame, so
/// showing the real card early would announce "new session" for every restore.
/// The bars mirror the card's title line and two-line body to keep the header
/// from reflowing when the summary arrives.
class _SummaryCardSkeleton extends StatelessWidget {
  const _SummaryCardSkeleton({required this.copy});

  final OpenCrayUiCopy copy;

  @override
  Widget build(BuildContext context) {
    return OpenCraySkeletonPulse(
      key: const ValueKey<String>('chat-summary-loading'),
      semanticsLabel: copy.contentLoadingLabel,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: context.palette.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: context.chatPalette.border),
        ),
        child: const Padding(
          padding: EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              OpenCraySkeletonBar(height: 17, widthFactor: 0.42),
              SizedBox(height: 14),
              OpenCraySkeletonBar(height: 13, widthFactor: 0.92),
              SizedBox(height: 8),
              OpenCraySkeletonBar(height: 13, widthFactor: 0.64),
            ],
          ),
        ),
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.copy,
    required this.summary,
    required this.isActiveThread,
    required this.scrollProgress,
    this.bridge,
  });

  final OpenCrayUiCopy copy;
  final ChatSessionSummary summary;
  final bool isActiveThread;
  final double scrollProgress;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    final double quietProgress = isActiveThread ? 1 : 0;
    final Color surfaceColor = Color.lerp(
      context.palette.surface,
      context.chatPalette.surfaceClear,
      quietProgress * 0.22,
    )!;
    final Color borderColor = Color.lerp(
      context.chatPalette.borderClear,
      context.chatPalette.border,
      quietProgress,
    )!;
    final Color titleColor = Color.lerp(
      context.chatPalette.textPrimary,
      context.chatPalette.summaryQuietTitle,
      quietProgress,
    )!;
    final Color bodyColor = Color.lerp(
      context.chatPalette.textSecondary,
      context.chatPalette.textTertiary,
      isActiveThread ? scrollProgress * 0.34 : 0,
    )!;
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: borderColor),
      ),
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: 14,
          vertical: isActiveThread ? 10 : 12,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                Expanded(
                  child: AnimatedDefaultTextStyle(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.micro,
                    ),
                    curve: OpenCrayMotion.enter,
                    style: context.chatText.cardTitle.copyWith(
                      color: titleColor,
                      fontSize: isActiveThread ? 16 : 17,
                    ),
                    child: Text(summary.title),
                  ),
                ),
                const SizedBox(width: 12),
                Text(summary.badge, style: context.chatText.summaryBadge),
              ],
            ),
            SizedBox(height: isActiveThread ? 6 : 8),
            _OpenCrayMarkdownTextBlock(
              copy: copy,
              data: summary.body,
              bodyStyle: context.chatText.bodyMuted.copyWith(color: bodyColor),
              surfaceColor: surfaceColor,
              bridge: bridge,
            ),
          ],
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
            color: context.palette.surfaceSunken,
            borderRadius: BorderRadius.circular(999),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            child: Text(label, style: context.chatText.timeline),
          ),
        ),
      ),
    );
  }
}

class _ChatTranscriptRowMotion extends StatelessWidget {
  const _ChatTranscriptRowMotion({
    required this.rowKey,
    required this.reveal,
    required this.child,
  });

  final String rowKey;
  final bool reveal;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!reveal || OpenCrayMotion.reduce(context)) {
      return child;
    }
    return TweenAnimationBuilder<double>(
      key: ValueKey<String>('chat-transcript-row-motion-$rowKey'),
      tween: Tween<double>(begin: 0, end: 1),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.expand),
      curve: OpenCrayMotion.enter,
      child: child,
      builder: (BuildContext context, double value, Widget? child) {
        return ClipRect(
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: value.clamp(0.0, 1.0),
            child: Opacity(
              opacity: value.clamp(0.0, 1.0),
              child: Transform.translate(
                offset: Offset(0, _chatTranscriptInsertOffset * (1 - value)),
                child: child,
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatMessageWithTimestamp extends StatelessWidget {
  const _ChatMessageWithTimestamp({
    super.key,
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
    required this.isDeleting,
    required this.onLongPress,
    required this.onSelectionToggle,
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
  final Alignment alignment;
  final Color backgroundColor;
  final Color textColor;
  final double maxWidth;
  final bool selectionMode;
  final bool isSelected;
  final bool isDeleting;
  final void Function(ChatMessageData, Rect, String?) onLongPress;
  final VoidCallback onSelectionToggle;
  final ValueChanged<OpenCrayMarkdownSelectionSnapshot?> onTextSelectionChanged;
  final List<ChatRunTraceData> attachedRunTraces;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData>? onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData>? onRetryRunTrace;

  static const double _threadHorizontalInset = 20;
  static const double _selectionControlGutter = 42;

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
      attachedRunTraces: attachedRunTraces,
      interruptConfirmRunId: interruptConfirmRunId,
      busyInterruptRunIds: busyInterruptRunIds,
      busyRetryRunIds: busyRetryRunIds,
      onArmInterruptRunTrace: onArmInterruptRunTrace,
      onDismissInterruptRunTrace: onDismissInterruptRunTrace,
      onInterruptRunTrace: onInterruptRunTrace,
      onRetryRunTrace: onRetryRunTrace,
    );
    final bool isOutgoing = message.kind == ChatMessageKind.outbound;
    late final Widget row;
    if (!selectionMode) {
      row = Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Align(alignment: alignment, child: bubble),
      );
      return _ChatMessageDeleteMotion(
        messageId: message.messageId,
        isDeleting: isDeleting,
        isOutgoing: isOutgoing,
        child: row,
      );
    }
    row = Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          return SizedBox(
            width: constraints.maxWidth,
            child: GestureDetector(
              key: ValueKey<String>('chat-message-row-${message.messageId}'),
              onTap: onSelectionToggle,
              behavior: HitTestBehavior.opaque,
              child: Stack(
                clipBehavior: Clip.none,
                children: <Widget>[
                  Positioned(
                    left: -_threadHorizontalInset,
                    right: -_threadHorizontalInset,
                    top: 0,
                    bottom: 0,
                    child: AnimatedContainer(
                      key: ValueKey<String>(
                        'chat-message-row-bg-${message.messageId}',
                      ),
                      duration: OpenCrayMotion.resolve(
                        context,
                        OpenCrayMotion.micro,
                      ),
                      curve: OpenCrayMotion.enter,
                      color: isSelected
                          ? context.chatPalette.selectionRowHighlight
                          : Colors.transparent,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: <Widget>[
                        SizedBox(
                          width: _selectionControlGutter,
                          child: Center(
                            child: _ChatSelectionControl(
                              messageId: message.messageId,
                              isSelected: isSelected,
                            ),
                          ),
                        ),
                        Expanded(
                          child: Align(alignment: alignment, child: bubble),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
    return _ChatMessageDeleteMotion(
      messageId: message.messageId,
      isDeleting: isDeleting,
      isOutgoing: isOutgoing,
      child: row,
    );
  }
}

class _ChatMessageDeleteMotion extends StatefulWidget {
  const _ChatMessageDeleteMotion({
    required this.messageId,
    required this.isDeleting,
    required this.isOutgoing,
    required this.child,
  });

  final String messageId;
  final bool isDeleting;
  final bool isOutgoing;
  final Widget child;

  @override
  State<_ChatMessageDeleteMotion> createState() =>
      _ChatMessageDeleteMotionState();
}

class _ChatMessageDeleteMotionState extends State<_ChatMessageDeleteMotion>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: _chatMessageDeleteMotionDuration,
  );

  @override
  void initState() {
    super.initState();
    if (widget.isDeleting) {
      _controller.forward(from: 0);
    }
  }

  @override
  void didUpdateWidget(covariant _ChatMessageDeleteMotion oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isDeleting && !oldWidget.isDeleting) {
      _startDeleteMotion();
      return;
    }
    if (!widget.isDeleting && oldWidget.isDeleting) {
      _controller.value = 0;
    }
  }

  void _startDeleteMotion() {
    final Duration duration = OpenCrayMotion.resolve(
      context,
      _chatMessageDeleteMotionDuration,
    );
    _controller.duration = duration;
    if (duration == Duration.zero) {
      _controller.value = 1;
      return;
    }
    _controller.forward(from: 0);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.isDeleting && _controller.value == 0) {
      return widget.child;
    }
    return AnimatedBuilder(
      animation: _controller,
      child: widget.child,
      builder: (BuildContext context, Widget? child) {
        final double rawT = _controller.value.clamp(0.0, 1.0);
        final double moveT = OpenCrayMotion.enter.transform(rawT);
        final double fadeT = Curves.easeIn.transform(
          (rawT / 0.72).clamp(0.0, 1.0),
        );
        final double collapseT = OpenCrayMotion.expandCurve.transform(
          ((rawT - 0.18) / 0.82).clamp(0.0, 1.0),
        );
        final double side = widget.isOutgoing ? 1 : -1;
        final Offset offset = Offset(
          side * _chatMessageDeleteSlideDistance * moveT,
          2 * moveT,
        );
        return ClipRect(
          key: ValueKey<String>(
            'chat-message-delete-motion-${widget.messageId}',
          ),
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: (1 - collapseT).clamp(0.0, 1.0),
            child: IgnorePointer(
              ignoring: widget.isDeleting,
              child: Opacity(
                opacity: (1 - fadeT).clamp(0.0, 1.0),
                child: RepaintBoundary(
                  child: Transform.translate(offset: offset, child: child),
                ),
              ),
            ),
          ),
        );
      },
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
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      width: 22,
      height: 22,
      decoration: BoxDecoration(
        color: isSelected ? context.chatPalette.accent : context.palette.surface,
        shape: BoxShape.circle,
        border: Border.all(
          color: isSelected
              ? context.chatPalette.accent
              : context.chatPalette.selectionControlBorder,
          width: isSelected ? 0 : 1.5,
        ),
      ),
      alignment: Alignment.center,
      child: AnimatedOpacity(
        duration: OpenCrayMotion.resolve(context, OpenCrayMotion.instant),
        opacity: isSelected ? 1 : 0,
        child: const Icon(
          Icons.check_rounded,
          size: 12,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _MessageList extends StatefulWidget {
  const _MessageList({
    required this.bridge,
    required this.copy,
    required this.showSandboxPreviewCards,
    required this.voicePlaybackControllerFactory,
    required this.messages,
    required this.runTraces,
    required this.selectedMessageIds,
    required this.deletingMessageIds,
    required this.interruptConfirmRunId,
    required this.busyInterruptRunIds,
    required this.busyRetryRunIds,
    required this.onArmInterruptRunTrace,
    required this.onDismissInterruptRunTrace,
    required this.onInterruptRunTrace,
    required this.onRetryRunTrace,
    required this.onMessageLongPress,
    required this.onMessageSelectionToggle,
    required this.onMessageTextSelectionChanged,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final bool showSandboxPreviewCards;
  final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory;
  final List<ChatMessageData> messages;
  final List<ChatRunTraceData> runTraces;
  final Set<String> selectedMessageIds;
  final Set<String> deletingMessageIds;
  final String? interruptConfirmRunId;
  final Set<String> busyInterruptRunIds;
  final Set<String> busyRetryRunIds;
  final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onInterruptRunTrace;
  final ValueChanged<ChatRunTraceData> onRetryRunTrace;
  final void Function(ChatMessageData, Rect, String?) onMessageLongPress;
  final ValueChanged<ChatMessageData> onMessageSelectionToggle;
  final void Function(ChatMessageData, OpenCrayMarkdownSelectionSnapshot?)
  onMessageTextSelectionChanged;

  @override
  State<_MessageList> createState() => _MessageListState();
}

class _MessageListState extends State<_MessageList> {
  final Set<String> _knownTranscriptRowKeys = <String>{};
  bool _hasBuiltTranscriptOnce = false;

  void _addTranscriptRow(
    List<_ChatTranscriptRow> rows,
    String rowKey,
    WidgetBuilder builder,
  ) {
    final bool shouldReveal =
        _hasBuiltTranscriptOnce && !_knownTranscriptRowKeys.contains(rowKey);
    _knownTranscriptRowKeys.add(rowKey);
    rows.add(
      _ChatTranscriptRow(
        rowKey: rowKey,
        reveal: shouldReveal,
        builder: builder,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final OpenCrayHostBridge? bridge = widget.bridge;
    final OpenCrayUiCopy copy = widget.copy;
    final bool showSandboxPreviewCards = widget.showSandboxPreviewCards;
    final ChatVoicePlaybackControllerFactory? voicePlaybackControllerFactory =
        widget.voicePlaybackControllerFactory;
    final List<ChatMessageData> messages = widget.messages;
    final List<ChatRunTraceData> runTraces = widget.runTraces;
    final Set<String> selectedMessageIds = widget.selectedMessageIds;
    final Set<String> deletingMessageIds = widget.deletingMessageIds;
    final String? interruptConfirmRunId = widget.interruptConfirmRunId;
    final Set<String> busyInterruptRunIds = widget.busyInterruptRunIds;
    final Set<String> busyRetryRunIds = widget.busyRetryRunIds;
    final ValueChanged<ChatRunTraceData> onArmInterruptRunTrace =
        widget.onArmInterruptRunTrace;
    final ValueChanged<ChatRunTraceData> onDismissInterruptRunTrace =
        widget.onDismissInterruptRunTrace;
    final ValueChanged<ChatRunTraceData> onInterruptRunTrace =
        widget.onInterruptRunTrace;
    final ValueChanged<ChatRunTraceData> onRetryRunTrace =
        widget.onRetryRunTrace;
    final void Function(ChatMessageData, Rect, String?) onMessageLongPress =
        widget.onMessageLongPress;
    final ValueChanged<ChatMessageData> onMessageSelectionToggle =
        widget.onMessageSelectionToggle;
    final void Function(ChatMessageData, OpenCrayMarkdownSelectionSnapshot?)
    onMessageTextSelectionChanged = widget.onMessageTextSelectionChanged;
    final double contentWidth = math.max(
      0,
      MediaQuery.sizeOf(context).width - 40,
    );
    final double inboundBubbleMaxWidth = math.min(
      360,
      math.max(252, contentWidth * 0.78),
    );
    final double outboundBubbleMaxWidth = math.min(
      340,
      math.max(236, contentWidth * 0.76),
    );
    final rows = <_ChatTranscriptRow>[];
    int? previousTimestampEpochMs;
    final Map<String, List<ChatRunTraceData>> anchoredRunTracesByMessageId =
        <String, List<ChatRunTraceData>>{};
    final List<ChatRunTraceData> anchoredRunTraces = <ChatRunTraceData>[];
    final Set<ChatRunTraceData> renderedRunTraces = <ChatRunTraceData>{};
    final Set<String> visibleMessageIds = messages
        .map((message) => message.messageId.trim())
        .where((messageId) => messageId.isNotEmpty)
        .toSet();

    for (final trace in runTraces) {
      final String anchorMessageId = trace.anchorMessageId.trim();
      if (anchorMessageId.isEmpty) {
        continue;
      }
      anchoredRunTraces.add(trace);
      anchoredRunTracesByMessageId
          .putIfAbsent(anchorMessageId, () => <ChatRunTraceData>[])
          .add(trace);
    }

    void addRunTrace(
      ChatRunTraceData trace, {
      required bool showRetryAction,
      required bool showInterruptAction,
    }) {
      if (!renderedRunTraces.add(trace)) {
        return;
      }
      final bool canShowInterrupt = showInterruptAction && trace.canInterrupt;
      final bool canShowRetry = showRetryAction && trace.isRetryable;
      final String rowKey = 'trace-${_stableRunTraceKey(trace)}';
      _addTranscriptRow(
        rows,
        rowKey,
        (context) => Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Align(
            alignment: Alignment.centerLeft,
            child: _RunTraceBubble(
              key: ValueKey<String>(
                'chat-run-trace-${_stableRunTraceKey(trace)}',
              ),
              bridge: bridge,
              copy: copy,
              trace: trace,
              showSandboxPreviewCard: showSandboxPreviewCards,
              showRetryAction: canShowRetry,
              showInterruptAction: canShowInterrupt,
              showInterruptConfirm:
                  canShowInterrupt &&
                  interruptConfirmRunId == trace.interruptId,
              isInterruptBusy:
                  canShowInterrupt &&
                  busyInterruptRunIds.contains(trace.interruptId),
              onInterruptRequest: canShowInterrupt
                  ? () => onArmInterruptRunTrace(trace)
                  : null,
              onInterruptDismiss:
                  canShowInterrupt && interruptConfirmRunId == trace.interruptId
                  ? () => onDismissInterruptRunTrace(trace)
                  : null,
              onInterruptConfirm: canShowInterrupt
                  ? () => onInterruptRunTrace(trace)
                  : null,
              isRetryBusy:
                  canShowRetry && busyRetryRunIds.contains(trace.retryId),
              onRetry: canShowRetry ? () => onRetryRunTrace(trace) : null,
            ),
          ),
        ),
      );
    }

    for (final message in messages) {
      final String leadingTraceAnchorMessageId =
          _leadingTraceAnchorMessageIdForMessage(
            message,
            anchoredRunTracesByMessageId,
            anchoredRunTraces,
          );
      final List<ChatRunTraceData> leadingTraces =
          leadingTraceAnchorMessageId.isEmpty
          ? const <ChatRunTraceData>[]
          : anchoredRunTracesByMessageId[leadingTraceAnchorMessageId] ??
                const <ChatRunTraceData>[];
      for (final trace in leadingTraces) {
        addRunTrace(trace, showRetryAction: false, showInterruptAction: false);
      }
      final List<ChatRunTraceData> anchoredTraces =
          anchoredRunTracesByMessageId[message.messageId.trim()] ??
          const <ChatRunTraceData>[];
      final int? currentTimestampEpochMs = message.createdAtEpochMs;
      if (currentTimestampEpochMs != null &&
          currentTimestampEpochMs > 0 &&
          message.kind != ChatMessageKind.timeline &&
          _shouldInsertChatTimestampDivider(
            currentTimestampEpochMs,
            previousTimestampEpochMs,
          )) {
        _addTranscriptRow(
          rows,
          'divider-${message.messageId}',
          (context) => _ChatTimestampDivider(
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
          _addTranscriptRow(
            rows,
            _chatMessageListItemKey(message),
            (context) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: context.palette.surfaceSunken,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 5,
                    ),
                    child: Text(message.text, style: context.chatText.timeline),
                  ),
                ),
              ),
            ),
          );
        case ChatMessageKind.inbound:
          _addTranscriptRow(
            rows,
            _chatMessageListItemKey(message),
            (context) => _ChatMessageWithTimestamp(
              key: ValueKey<String>(_chatMessageListItemKey(message)),
              bridge: bridge,
              copy: copy,
              message: message,
              voicePlaybackControllerFactory: voicePlaybackControllerFactory,
              alignment: Alignment.centerLeft,
              backgroundColor: context.palette.surface,
              textColor: context.chatPalette.textPrimary,
              maxWidth: inboundBubbleMaxWidth,
              selectionMode: selectedMessageIds.isNotEmpty,
              isSelected: selectedMessageIds.contains(message.messageId),
              isDeleting: deletingMessageIds.contains(message.messageId),
              onLongPress: onMessageLongPress,
              onSelectionToggle: () => onMessageSelectionToggle(message),
              onTextSelectionChanged: (selectedText) =>
                  onMessageTextSelectionChanged(message, selectedText),
              attachedRunTraces: anchoredTraces,
              interruptConfirmRunId: interruptConfirmRunId,
              busyInterruptRunIds: busyInterruptRunIds,
              busyRetryRunIds: busyRetryRunIds,
              onArmInterruptRunTrace: onArmInterruptRunTrace,
              onDismissInterruptRunTrace: onDismissInterruptRunTrace,
              onInterruptRunTrace: onInterruptRunTrace,
              onRetryRunTrace: onRetryRunTrace,
            ),
          );
        case ChatMessageKind.outbound:
          _addTranscriptRow(
            rows,
            _chatMessageListItemKey(message),
            (context) => _ChatMessageWithTimestamp(
              key: ValueKey<String>(_chatMessageListItemKey(message)),
              bridge: bridge,
              copy: copy,
              message: message,
              voicePlaybackControllerFactory: voicePlaybackControllerFactory,
              alignment: Alignment.centerRight,
              backgroundColor: context.chatPalette.accent,
              textColor: Colors.white,
              maxWidth: outboundBubbleMaxWidth,
              selectionMode: selectedMessageIds.isNotEmpty,
              isSelected: selectedMessageIds.contains(message.messageId),
              isDeleting: deletingMessageIds.contains(message.messageId),
              onLongPress: onMessageLongPress,
              onSelectionToggle: () => onMessageSelectionToggle(message),
              onTextSelectionChanged: (selectedText) =>
                  onMessageTextSelectionChanged(message, selectedText),
              attachedRunTraces: anchoredTraces,
              interruptConfirmRunId: interruptConfirmRunId,
              busyInterruptRunIds: busyInterruptRunIds,
              busyRetryRunIds: busyRetryRunIds,
              onArmInterruptRunTrace: onArmInterruptRunTrace,
              onDismissInterruptRunTrace: onDismissInterruptRunTrace,
              onInterruptRunTrace: onInterruptRunTrace,
              onRetryRunTrace: onRetryRunTrace,
            ),
          );
      }
      if (currentTimestampEpochMs != null && currentTimestampEpochMs > 0) {
        previousTimestampEpochMs = currentTimestampEpochMs;
      }
    }

    for (final trace in runTraces) {
      final String anchorMessageId = trace.anchorMessageId.trim();
      final bool showActions =
          anchorMessageId.isEmpty ||
          !visibleMessageIds.contains(anchorMessageId);
      addRunTrace(
        trace,
        showRetryAction: showActions,
        showInterruptAction: false,
      );
    }

    if (!_hasBuiltTranscriptOnce) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        _hasBuiltTranscriptOnce = true;
      });
    }
    return SliverList(
      delegate: SliverChildBuilderDelegate(
        (BuildContext context, int index) {
          return rows[index].build(context);
        },
        childCount: rows.length,
        addSemanticIndexes: false,
      ),
    );
  }
}

class _ChatTranscriptRow {
  const _ChatTranscriptRow({
    required this.rowKey,
    required this.reveal,
    required this.builder,
  });

  final String rowKey;
  final bool reveal;
  final WidgetBuilder builder;

  Widget build(BuildContext context) {
    return _ChatTranscriptRowMotion(
      rowKey: rowKey,
      reveal: reveal,
      child: builder(context),
    );
  }
}

String _leadingTraceAnchorMessageIdForMessage(
  ChatMessageData message,
  Map<String, List<ChatRunTraceData>> anchoredRunTracesByMessageId,
  Iterable<ChatRunTraceData> anchoredRunTraces,
) {
  final String messageId = message.messageId.trim();
  if (messageId.isNotEmpty &&
      anchoredRunTracesByMessageId.containsKey(messageId)) {
    return messageId;
  }
  final String runtimeAnchorMessageId = message.runtimeAnchorMessageId.trim();
  if (runtimeAnchorMessageId.isNotEmpty &&
      anchoredRunTracesByMessageId.containsKey(runtimeAnchorMessageId)) {
    return runtimeAnchorMessageId;
  }
  final String runtimeProjectedAnchorMessageId =
      _runtimeProjectedTraceAnchorMessageIdForMessage(
        messageId,
        anchoredRunTraces,
      );
  if (runtimeProjectedAnchorMessageId.isNotEmpty &&
      anchoredRunTracesByMessageId.containsKey(
        runtimeProjectedAnchorMessageId,
      )) {
    return runtimeProjectedAnchorMessageId;
  }
  return '';
}

String _runtimeProjectedTraceAnchorMessageIdForMessage(
  String messageId,
  Iterable<ChatRunTraceData> anchoredRunTraces,
) {
  if (messageId.isEmpty ||
      (!messageId.startsWith('runtime-process-') &&
          !messageId.startsWith('runtime-assistant-'))) {
    return '';
  }
  String bestAnchorMessageId = '';
  int bestKeyLength = 0;
  void considerKey(String key, String anchorMessageId) {
    if (key.isEmpty || anchorMessageId.isEmpty || key.length <= bestKeyLength) {
      return;
    }
    final bool matchesProcessId = messageId.startsWith('runtime-process-$key-');
    final bool matchesAssistantId =
        messageId.startsWith('runtime-assistant-') &&
        messageId.contains('-$key-');
    if (!matchesProcessId && !matchesAssistantId) {
      return;
    }
    bestKeyLength = key.length;
    bestAnchorMessageId = anchorMessageId;
  }

  for (final trace in anchoredRunTraces) {
    final String anchorMessageId = trace.anchorMessageId.trim();
    considerKey(trace.runId.trim(), anchorMessageId);
    considerKey(trace.taskId.trim(), anchorMessageId);
  }
  return bestAnchorMessageId;
}

String _stableRunTraceKey(ChatRunTraceData trace) {
  final String runId = trace.runId.trim();
  if (runId.isNotEmpty) {
    return runId;
  }
  final String taskId = trace.taskId.trim();
  if (taskId.isNotEmpty) {
    return taskId;
  }
  return trace.label.trim();
}

String _chatMessageListItemKey(ChatMessageData message) {
  final String messageId = message.messageId.trim();
  if (messageId.isNotEmpty) {
    return 'chat-message-item-$messageId';
  }
  return 'chat-message-item-${message.kind.name}-${message.createdAtEpochMs ?? 0}-${javaStringHashCode(message.text)}';
}
