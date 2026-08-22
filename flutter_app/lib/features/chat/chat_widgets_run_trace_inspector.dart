part of 'chat_feature_screen.dart';

class _RunTraceFullscreenSheet extends StatefulWidget {
  const _RunTraceFullscreenSheet({
    required this.copy,
    required this.traceListenable,
    required this.showSandboxPreviewCard,
    this.bridge,
    this.onRetry,
    this.isRetryBusy = false,
  });

  final OpenCrayUiCopy copy;
  final ValueListenable<ChatRunTraceData> traceListenable;
  final bool showSandboxPreviewCard;
  final OpenCrayHostBridge? bridge;
  final VoidCallback? onRetry;
  final bool isRetryBusy;

  @override
  State<_RunTraceFullscreenSheet> createState() =>
      _RunTraceFullscreenSheetState();
}

class _RunTraceFullscreenSheetState extends State<_RunTraceFullscreenSheet> {
  late final ScrollController _scrollController = ScrollController();
  late ChatRunTraceData _trace = widget.traceListenable.value;
  String? _selectedActorId;

  @override
  void initState() {
    super.initState();
    widget.traceListenable.addListener(_handleTraceChanged);
    _scheduleScrollToBottom();
  }

  @override
  void didUpdateWidget(covariant _RunTraceFullscreenSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.traceListenable != widget.traceListenable) {
      oldWidget.traceListenable.removeListener(_handleTraceChanged);
      _trace = widget.traceListenable.value;
      widget.traceListenable.addListener(_handleTraceChanged);
      _scheduleScrollToBottom();
    }
  }

  @override
  void dispose() {
    widget.traceListenable.removeListener(_handleTraceChanged);
    _scrollController.dispose();
    super.dispose();
  }

  void _handleTraceChanged() {
    final ChatRunTraceData nextTrace = widget.traceListenable.value;
    if (identical(_trace, nextTrace)) {
      return;
    }
    final int previousProcessEntryCount = _trace.history
        .where(_isRunTraceProcessEntry)
        .length;
    final int nextProcessEntryCount = nextTrace.history
        .where(_isRunTraceProcessEntry)
        .length;
    _runTraceDebug(
      'fullscreen.traceChanged run=${nextTrace.runId} history=${_trace.history.length}->${nextTrace.history.length} processEntries=$previousProcessEntryCount->$nextProcessEntryCount',
    );
    final bool shouldStickToBottom =
        !_scrollController.hasClients ||
        (_scrollController.position.maxScrollExtent -
                    _scrollController.position.pixels)
                .abs() <
            24;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || identical(_trace, nextTrace)) {
        return;
      }
      setState(() {
        _trace = nextTrace;
      });
      if (shouldStickToBottom) {
        _scheduleScrollToBottom();
      }
    });
  }

  Future<void> _openPreviewCard(ChatRunTracePreviewCardData preview) async {
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge == null) {
      return;
    }
    try {
      await bridge.openExternalUri(preview.url);
    } catch (_) {
      await bridge.showNativeToast(widget.copy.markdownLinkOpenFailed);
    }
  }

  Future<void> _copyPreviewUrl(ChatRunTracePreviewCardData preview) async {
    await Clipboard.setData(ClipboardData(text: preview.url));
    final OpenCrayHostBridge? bridge = widget.bridge;
    if (bridge != null) {
      await bridge.showNativeToast(widget.copy.chatRunPreviewCopied);
    }
  }

  void _scheduleScrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) {
        return;
      }
      final ScrollPosition position = _scrollController.position;
      final double target = position.maxScrollExtent;
      if ((position.pixels - target).abs() < 0.5) {
        return;
      }
      _scrollController.jumpTo(target);
    });
  }

  List<_RunTraceInspectorActorSection> _buildActorSections(
    List<ChatRunTraceHistoryEntry> history,
  ) {
    final Map<String, _RunTraceInspectorActorSection> sections =
        <String, _RunTraceInspectorActorSection>{};
    for (final entry in history) {
      final String actorId = entry.inspectorActorId.trim().isNotEmpty
          ? entry.inspectorActorId.trim()
          : _runTraceMainActorId;
      final String actorLabel = entry.inspectorActorLabel.trim().isNotEmpty
          ? entry.inspectorActorLabel.trim()
          : entry.label;
      final _RunTraceInspectorActorSection? existing = sections[actorId];
      if (existing == null) {
        sections[actorId] = _RunTraceInspectorActorSection(
          id: actorId,
          label: actorLabel,
          entries: <ChatRunTraceHistoryEntry>[entry],
        );
        continue;
      }
      sections[actorId] = _RunTraceInspectorActorSection(
        id: existing.id,
        label: existing.label,
        entries: <ChatRunTraceHistoryEntry>[...existing.entries, entry],
      );
    }
    final List<_RunTraceInspectorActorSection> resolved = sections.values
        .toList(growable: false);
    final Map<String, int> totalsByLabel = <String, int>{};
    for (final section in resolved) {
      totalsByLabel.update(
        section.label,
        (count) => count + 1,
        ifAbsent: () => 1,
      );
    }
    final Map<String, int> seenByLabel = <String, int>{};
    return resolved
        .map((section) {
          final int total = totalsByLabel[section.label] ?? 1;
          if (total <= 1) {
            return section;
          }
          final int seen = seenByLabel.update(
            section.label,
            (count) => count + 1,
            ifAbsent: () => 1,
          );
          return _RunTraceInspectorActorSection(
            id: section.id,
            label: '${section.label} $seen',
            entries: section.entries,
          );
        })
        .toList(growable: false);
  }

  Widget _buildActorTabs(List<_RunTraceInspectorActorSection> sections) {
    final List<Widget> tabChildren = sections
        .map((section) {
          final bool selected =
              (_selectedActorId ?? sections.first.id) == section.id;
          return Padding(
            padding: const EdgeInsets.only(right: 16),
            child: GestureDetector(
              onTap: () {
                setState(() => _selectedActorId = section.id);
                _scheduleScrollToBottom();
              },
              behavior: HitTestBehavior.opaque,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text(
                  section.label,
                  style: _ChatTextStyles.timeline.copyWith(
                    color: selected
                        ? _ChatPalette.inspectorAction
                        : _ChatPalette.textSecondary,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ),
            ),
          );
        })
        .toList(growable: false);
    if (sections.length <= 1) {
      return Row(children: tabChildren);
    }
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: tabChildren),
    );
  }

  @override
  Widget build(BuildContext context) {
    final OpenCrayUiCopy copy = widget.copy;
    final ChatRunTraceData trace = _trace;
    final ChatRunTraceSandboxSessionCardData? session =
        widget.showSandboxPreviewCard ? trace.sessionCard : null;
    final ChatRunTracePreviewCardData? preview = widget.showSandboxPreviewCard
        ? trace.previewCard
        : null;
    final List<ChatRunTraceHistoryEntry> history = trace.history.isNotEmpty
        ? trace.history
        : <ChatRunTraceHistoryEntry>[
            ChatRunTraceHistoryEntry(
              label: trace.label,
              body: trace.body,
              isHighRisk: trace.isHighRisk,
            ),
          ];
    final List<_RunTraceInspectorActorSection> actorSections =
        _buildActorSections(history);
    final String selectedActorId =
        actorSections.any((section) => section.id == _selectedActorId)
        ? _selectedActorId!
        : actorSections.first.id;
    final List<ChatRunTraceHistoryEntry> visibleHistory = actorSections
        .firstWhere((section) => section.id == selectedActorId)
        .entries;
    final ChatRunTraceHistoryEntry? compactEntry = _resolveCompactRunTraceEntry(
      trace: trace,
      visibleHistory: history
          .where(
            (entry) =>
                !_runTraceThinkingPlaceholders.contains(entry.body.trim()),
          )
          .toList(growable: false),
    );
    final _RunTraceCompactPresentation compactPresentation =
        _buildRunTraceCompactPresentation(trace: trace, copy: copy);
    final bool showCompactSummaryCard =
        selectedActorId == _runTraceMainActorId &&
        compactEntry != null &&
        compactEntry.inspectorActorId != selectedActorId;
    final String? supplementalBody = _supplementalRunTraceBody(
      trace: trace,
      history: history,
    );
    final String inspectorTitle = copy.isChinese ? '运行检查' : 'Run inspector';
    final String summaryTitle = copy.isChinese
        ? (actorSections.length > 1 ? '代理检查器' : '代理检查')
        : (actorSections.length > 1 ? 'Agent inspectors' : 'Agent inspector');
    final String summaryBody = actorSections.length > 1
        ? (copy.isChinese
              ? '用顶部标签切换不同代理的检查记录，不要把子代理细节混在同一条滚动里。'
              : 'Use tabs to switch the current inspector instead of mixing child details into one scroll.')
        : (copy.isChinese
              ? '当前运行细节会按工具调用和结果分组展示。'
              : 'Current run details are grouped by tool call and result.');
    final Color containerBorderColor = trace.isHighRisk
        ? _ChatPalette.highRiskBorder
        : _ChatPalette.runTraceBorder;
    return Dialog.fullscreen(
      key: ValueKey<String>('chat-run-trace-fullscreen-${trace.runId}'),
      backgroundColor: _ChatPalette.background,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Expanded(
                    child: Text(
                      inspectorTitle,
                      style: _ChatTextStyles.runInspectorTitle,
                    ),
                  ),
                  if (trace.isRetryable && widget.onRetry != null) ...<Widget>[
                    const SizedBox(width: 12),
                    _RunTraceActionButton(
                      label: widget.isRetryBusy
                          ? '${trace.retryLabel!}...'
                          : trace.retryLabel!,
                      onTap: widget.isRetryBusy ? null : widget.onRetry,
                    ),
                  ],
                  const SizedBox(width: 4),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    behavior: HitTestBehavior.opaque,
                    child: const Padding(
                      padding: EdgeInsets.all(4),
                      child: Icon(
                        Icons.close_rounded,
                        size: 20,
                        color: _ChatPalette.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(color: containerBorderColor),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Row(
                              children: <Widget>[
                                Expanded(
                                  child: Text(
                                    summaryTitle,
                                    style: _ChatTextStyles.cardTitle,
                                  ),
                                ),
                                const SizedBox(width: 12),
                                _RunTracePill(
                                  label: compactPresentation.statusLabel,
                                  foregroundColor: trace.isHighRisk
                                      ? _ChatPalette.highRiskAccent
                                      : _ChatPalette.runTraceStatusText,
                                  backgroundColor: trace.isHighRisk
                                      ? _ChatPalette.highRiskBadgeSurface
                                      : _ChatPalette.runTraceStatusSurface,
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Text(summaryBody, style: _ChatTextStyles.bodyMuted),
                            if (showCompactSummaryCard) ...<Widget>[
                              const SizedBox(height: 12),
                              Text(
                                compactPresentation.headline,
                                style: _ChatTextStyles.runTraceHeadline
                                    .copyWith(fontSize: 18),
                              ),
                              if (compactPresentation.description !=
                                  null) ...<Widget>[
                                const SizedBox(height: 8),
                                _OpenCrayMarkdownTextBlock(
                                  copy: copy,
                                  data: compactPresentation.description!,
                                  bodyStyle: _ChatTextStyles.bodyMuted.copyWith(
                                    color: _ChatPalette.textPrimary,
                                  ),
                                  surfaceColor: Colors.white,
                                  bridge: widget.bridge,
                                ),
                              ],
                              if (compactPresentation
                                  .detailLines
                                  .isNotEmpty) ...<Widget>[
                                const SizedBox(height: 10),
                                ...compactPresentation.detailLines.map(
                                  (line) => Padding(
                                    padding: const EdgeInsets.only(bottom: 6),
                                    child: RichText(
                                      text: TextSpan(
                                        children: <InlineSpan>[
                                          TextSpan(
                                            text:
                                                '${line.label}${copy.isChinese ? '  ' : '  '}',
                                            style: _ChatTextStyles
                                                .runTraceDetailLabel,
                                          ),
                                          WidgetSpan(
                                            alignment:
                                                PlaceholderAlignment.baseline,
                                            baseline: TextBaseline.alphabetic,
                                            child: _OpenCrayMarkdownTextBlock(
                                              copy: copy,
                                              data: line.value,
                                              bodyStyle: _ChatTextStyles
                                                  .runTraceDetailValue,
                                              surfaceColor: Colors.white,
                                              bridge: widget.bridge,
                                              preferAccentForStrong: true,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: containerBorderColor),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
                        child: _buildActorTabs(actorSections),
                      ),
                      const Divider(
                        height: 1,
                        thickness: 1,
                        color: _ChatPalette.runTraceTabDivider,
                      ),
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
                            padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
                            physics: const ClampingScrollPhysics(),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: <Widget>[
                                if (session != null) ...<Widget>[
                                  _RunTraceSandboxSessionCard(
                                    key: ValueKey<String>(
                                      'chat-run-trace-fullscreen-session-card-${trace.runId}',
                                    ),
                                    copy: copy,
                                    runId: trace.runId,
                                    session: session,
                                    bridge: widget.bridge,
                                    expanded: true,
                                    keyNamespace:
                                        'chat-run-trace-fullscreen-session',
                                  ),
                                  const SizedBox(height: 14),
                                ],
                                if (preview != null) ...<Widget>[
                                  _RunTracePreviewCard(
                                    key: ValueKey<String>(
                                      'chat-run-trace-fullscreen-preview-card-${trace.runId}',
                                    ),
                                    copy: copy,
                                    runId: trace.runId,
                                    preview: preview,
                                    bridge: widget.bridge,
                                    expanded: true,
                                    keyNamespace:
                                        'chat-run-trace-fullscreen-preview',
                                    onOpen: widget.bridge == null
                                        ? null
                                        : () => _openPreviewCard(preview),
                                    onCopy: () => _copyPreviewUrl(preview),
                                  ),
                                  const SizedBox(height: 14),
                                ],
                                ...visibleHistory.asMap().entries.map(
                                  (historyEntry) => Padding(
                                    padding: const EdgeInsets.only(bottom: 14),
                                    child: _RunTraceHistoryCard(
                                      entryKey:
                                          '${trace.runId}-$selectedActorId-${historyEntry.key}',
                                      copy: copy,
                                      entry: historyEntry.value,
                                      bridge: widget.bridge,
                                    ),
                                  ),
                                ),
                                if (supplementalBody != null)
                                  DecoratedBox(
                                    decoration: BoxDecoration(
                                      color: _ChatPalette.runTraceDetailSurface,
                                      borderRadius: BorderRadius.circular(18),
                                    ),
                                    child: Padding(
                                      padding: const EdgeInsets.all(14),
                                      child: _OpenCrayMarkdownTextBlock(
                                        copy: copy,
                                        data: supplementalBody,
                                        bodyStyle: _ChatTextStyles.bodyMuted
                                            .copyWith(
                                              color: _ChatPalette.textPrimary,
                                            ),
                                        surfaceColor:
                                            _ChatPalette.runTraceDetailSurface,
                                        bridge: widget.bridge,
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
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

class _RunTraceInlineInterruptAction extends StatelessWidget {
  const _RunTraceInlineInterruptAction({
    super.key,
    required this.label,
    required this.enabled,
    this.onTap,
  });

  final String label;
  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerUp: onTap == null ? null : (_) => onTap!(),
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.72,
        child: Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 4),
          child: Text(
            label,
            style: _ChatTextStyles.timeline.copyWith(
              color: _ChatPalette.runTraceInterruptAction,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

class _RunTraceInterruptConfirmRow extends StatefulWidget {
  const _RunTraceInterruptConfirmRow({
    super.key,
    required this.copy,
    required this.runId,
    required this.isBusy,
    this.onConfirmed,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final bool isBusy;
  final VoidCallback? onConfirmed;

  @override
  State<_RunTraceInterruptConfirmRow> createState() =>
      _RunTraceInterruptConfirmRowState();
}

class _RunTraceInterruptConfirmRowState
    extends State<_RunTraceInterruptConfirmRow> {
  static const double _horizontalInset = 6;
  static const double _thumbWidth = 42;
  static const double _confirmThreshold = 0.82;

  double _progress = 0;

  void _reset() {
    if (_progress == 0) {
      return;
    }
    setState(() {
      _progress = 0;
    });
  }

  void _updateProgress(DragUpdateDetails details, double travelDistance) {
    if (travelDistance <= 0) {
      return;
    }
    setState(() {
      _progress = (_progress - (details.delta.dx / travelDistance)).clamp(
        0.0,
        1.0,
      );
    });
  }

  void _finishGesture() {
    final bool confirmed = _progress >= _confirmThreshold;
    setState(() {
      _progress = 0;
    });
    if (confirmed) {
      widget.onConfirmed?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: ValueKey<String>('chat-run-trace-interrupt-slider-${widget.runId}'),
      height: 42,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final double travelDistance = math.max(
            0,
            constraints.maxWidth - (_horizontalInset * 2) - _thumbWidth,
          );
          final double thumbLeft =
              _horizontalInset + (1 - _progress) * travelDistance;
          return DecoratedBox(
            decoration: BoxDecoration(
              color: _ChatPalette.runTraceInterruptSurface,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: _ChatPalette.runTraceInterruptBorder),
            ),
            child: GestureDetector(
              onHorizontalDragUpdate: widget.isBusy
                  ? null
                  : (details) => _updateProgress(details, travelDistance),
              onHorizontalDragEnd: widget.isBusy
                  ? null
                  : (_) => _finishGesture(),
              onHorizontalDragCancel: widget.isBusy ? null : _reset,
              behavior: HitTestBehavior.opaque,
              child: Stack(
                children: <Widget>[
                  Positioned.fill(
                    child: Center(
                      child: AnimatedOpacity(
                        duration: OpenCrayMotion.resolve(
                          context,
                          OpenCrayMotion.instant,
                        ),
                        opacity: widget.isBusy
                            ? 1
                            : math.max(0.24, 1 - _progress),
                        child: Text(
                          widget.isBusy
                              ? widget.copy.chatRunInterruptBusy
                              : widget.copy.chatRunInterruptConfirmLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: _ChatTextStyles.timeline.copyWith(
                            color: widget.isBusy
                                ? _ChatPalette.runTraceInterruptAction
                                : _ChatPalette.textSecondary,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  ),
                  if (!widget.isBusy)
                    Positioned(
                      left: thumbLeft,
                      top: 4,
                      bottom: 4,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: _ChatPalette.runTraceInterruptAction,
                          borderRadius: BorderRadius.circular(999),
                          boxShadow: const <BoxShadow>[
                            BoxShadow(
                              color: Color(0x1E0F172A),
                              blurRadius: 10,
                              offset: Offset(0, 4),
                            ),
                          ],
                        ),
                        child: SizedBox(
                          width: _thumbWidth,
                          child: Center(
                            child: Icon(
                              widget.copy.isChinese
                                  ? Icons.keyboard_double_arrow_left_rounded
                                  : Icons.keyboard_double_arrow_left_rounded,
                              size: 18,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _RunTraceActionButton extends StatelessWidget {
  const _RunTraceActionButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Opacity(
        opacity: enabled ? 1 : 0.56,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: OpenCrayColors.surfaceSunken,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: OpenCrayColors.outline),
          ),
          child: Text(
            label,
            style: _ChatTextStyles.timeline.copyWith(
              color: _ChatPalette.textPrimary,
            ),
          ),
        ),
      ),
    );
  }
}

class _ToolInspectorCallDisplay {
  const _ToolInspectorCallDisplay({
    required this.text,
    required this.parts,
    this.detail,
  });

  final String text;
  final List<ChatRunTraceInspectorTextPart> parts;
  final String? detail;
}

class _ResolvedApprovalPreview {
  const _ResolvedApprovalPreview({required this.label, required this.body});

  final String label;
  final String body;
}

class _RunTraceCompactPresentation {
  const _RunTraceCompactPresentation({
    required this.statusLabel,
    required this.headline,
    required this.detailLines,
    this.activityLabel,
    this.description,
    this.footer,
  });

  final String statusLabel;
  final String? activityLabel;
  final String headline;
  final String? description;
  final List<_RunTraceCompactDetailLine> detailLines;
  final String? footer;
}

class _RunTraceCompactDetailLine {
  const _RunTraceCompactDetailLine({required this.label, required this.value});

  final String label;
  final String value;
}

class _RunTracePill extends StatelessWidget {
  const _RunTracePill({
    required this.label,
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final String label;
  final Color foregroundColor;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _ChatTextStyles.timeline.copyWith(
            color: foregroundColor,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

const Set<String> _runTraceThinkingPlaceholders = <String>{
  'Thinking',
  'Thinking…',
  'Thinking...',
  '思考中',
  '思考中…',
  '思考中...',
  'Analyzing the request and deciding the next step.',
  '正在分析请求，并决定下一步要做什么。',
};

ChatRunTraceHistoryEntry? _resolveCompactRunTraceEntry({
  required ChatRunTraceData trace,
  required List<ChatRunTraceHistoryEntry> visibleHistory,
}) {
  final String traceBody = trace.body.trim();
  if (traceBody.isNotEmpty) {
    for (int index = visibleHistory.length - 1; index >= 0; index -= 1) {
      final ChatRunTraceHistoryEntry candidate = visibleHistory[index];
      final String compactBody = _compactBodyForHistoryEntry(candidate);
      if (compactBody.isEmpty) {
        continue;
      }
      if (_runTraceTextsMatch(traceBody, compactBody) ||
          _runTraceTextContains(traceBody, compactBody)) {
        return candidate;
      }
    }
  }
  return visibleHistory.isEmpty ? null : visibleHistory.last;
}

_RunTraceCompactPresentation _buildRunTraceCompactPresentation({
  required ChatRunTraceData trace,
  required OpenCrayUiCopy copy,
}) {
  final List<ChatRunTraceHistoryEntry> visibleHistory = trace.history
      .where(
        (entry) => !_runTraceThinkingPlaceholders.contains(entry.body.trim()),
      )
      .toList(growable: false);
  if (trace.isWritingAssistantDraft && !trace.isTerminal) {
    return _RunTraceCompactPresentation(
      statusLabel: copy.isChinese ? '正在写回复' : 'WRITING REPLY',
      activityLabel: copy.isChinese ? '写回复' : 'Writing reply',
      headline: copy.isChinese ? '生成最终回答' : 'Writing final answer',
      description: null,
      detailLines: const <_RunTraceCompactDetailLine>[],
      footer: trace.history.isNotEmpty || trace.isRetryable
          ? (copy.isChinese
                ? '双击查看完整历史。'
                : 'Double tap to inspect the full history.')
          : null,
    );
  }
  final ChatRunTraceHistoryEntry? currentEntry = _resolveCompactRunTraceEntry(
    trace: trace,
    visibleHistory: visibleHistory,
  );
  final String? structuredDetail = currentEntry == null
      ? null
      : _compactStructuredInspectorDetail(currentEntry);
  final String? supplementalBody = _supplementalRunTraceBody(
    trace: trace,
    history: visibleHistory,
  );
  final List<String> currentSections = _splitRunTraceSections(
    currentEntry == null
        ? trace.body
        : _compactBodyForHistoryEntry(currentEntry),
  );
  final String? activityLabel = _runTraceActivityLabel(
    trace: trace,
    currentEntry: currentEntry,
    currentSections: currentSections,
    copy: copy,
  );
  final String statusLabel = _runTraceStatusLabel(
    trace: trace,
    activityLabel: activityLabel,
    copy: copy,
  );
  final String? resultSummary = _firstRunTraceLine(
    currentEntry?.inspectorResultBody ?? '',
  );
  final String headline =
      _firstNonEmptyRunTraceText(<String?>[
        currentSections.isEmpty ? null : currentSections.first,
        currentEntry?.hasStructuredInspectorContent == true
            ? _joinRunTraceInspectorParts(currentEntry!.inspectorCallParts)
            : null,
        _firstRunTraceLine(trace.body),
        trace.label,
      ]) ??
      trace.label;
  final String? processSummary = _recentRunTraceProcessSummary(
    history: visibleHistory,
    currentEntry: currentEntry,
    copy: copy,
  );
  final String? description = _firstDistinctRunTraceText(
    candidates: <String?>[
      processSummary,
      resultSummary,
      currentSections.length > 1 ? currentSections[1] : null,
      structuredDetail,
      supplementalBody,
    ],
    existing: <String>[headline],
  );
  final String? inputSummary = currentEntry == null
      ? null
      : _firstDistinctRunTraceText(
          candidates: <String?>[
            structuredDetail,
            _firstRunTraceLine(_compactBodyForHistoryEntry(currentEntry)),
          ],
          existing: <String>[headline, if (description != null) description],
        );

  final List<_RunTraceCompactDetailLine> detailLines =
      <_RunTraceCompactDetailLine>[
        if (processSummary != null &&
            !_runTraceTextsMatch(processSummary, description) &&
            !_runTraceTextsMatch(processSummary, headline) &&
            !_runTraceTextContains(processSummary, headline) &&
            !_runTraceTextContains(headline, processSummary))
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '过程' : 'Process',
            value: processSummary,
          ),
        if (inputSummary != null)
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '输入' : 'Input',
            value: inputSummary,
          ),
        if (resultSummary != null &&
            !_runTraceTextsMatch(resultSummary, description) &&
            !_runTraceTextsMatch(resultSummary, headline))
          _RunTraceCompactDetailLine(
            label: copy.isChinese ? '结果' : 'Result',
            value: resultSummary,
          ),
      ];

  if (detailLines.isEmpty) {
    final String? resultRemainder = _remainingRunTraceLines(
      currentEntry?.inspectorResultBody,
    );
    if (resultRemainder != null &&
        !_runTraceTextsMatch(resultRemainder, headline) &&
        !_runTraceTextsMatch(resultRemainder, description)) {
      detailLines.add(
        _RunTraceCompactDetailLine(
          label: copy.isChinese ? '预览' : 'Preview',
          value: resultRemainder,
        ),
      );
    }
    final List<String> fallbackSections = currentSections
        .where(
          (section) =>
              !_runTraceTextsMatch(section, headline) &&
              !_runTraceTextsMatch(section, description) &&
              !_runTraceTextContains(section, headline) &&
              !_runTraceTextContains(section, description) &&
              !_runTraceTextsMatch(section, resultRemainder) &&
              !_runTraceTextContains(section, resultSummary),
        )
        .toList(growable: false);
    for (
      int index = 0;
      index < fallbackSections.length && index < 2;
      index += 1
    ) {
      detailLines.add(
        _RunTraceCompactDetailLine(
          label: switch (detailLines.isEmpty ? index : index + 1) {
            0 => copy.isChinese ? '预览' : 'Preview',
            _ => copy.isChinese ? '说明' : 'Note',
          },
          value: fallbackSections[index],
        ),
      );
    }
  }

  return _RunTraceCompactPresentation(
    statusLabel: statusLabel,
    activityLabel: activityLabel,
    headline: headline,
    description: description,
    detailLines: detailLines.take(3).toList(growable: false),
    footer: trace.history.isNotEmpty || trace.isRetryable
        ? (copy.isChinese
              ? '双击查看完整历史。'
              : 'Double tap to inspect the full history.')
        : null,
  );
}

String? _recentRunTraceProcessSummary({
  required List<ChatRunTraceHistoryEntry> history,
  required ChatRunTraceHistoryEntry? currentEntry,
  required OpenCrayUiCopy copy,
}) {
  final bool hasProcessEntries = history.any(_isRunTraceProcessEntry);
  if (currentEntry != null && _isRunTraceProcessEntry(currentEntry)) {
    return _compactBodyForHistoryEntry(currentEntry);
  }
  final String? currentCompactBody = currentEntry == null
      ? null
      : _compactBodyForHistoryEntry(currentEntry);
  final int currentIndex = currentEntry == null
      ? -1
      : history.indexOf(currentEntry);
  for (
    int index = (currentIndex <= 0 ? history.length : currentIndex) - 1;
    index >= 0;
    index -= 1
  ) {
    final ChatRunTraceHistoryEntry candidate = history[index];
    if (identical(candidate, currentEntry)) {
      continue;
    }
    if (currentEntry != null &&
        currentEntry.inspectorActorId != _runTraceMainActorId &&
        candidate.inspectorActorId != currentEntry.inspectorActorId) {
      continue;
    }
    if (hasProcessEntries && !_isRunTraceProcessEntry(candidate)) {
      continue;
    }
    final String compactBody = _compactBodyForHistoryEntry(candidate);
    if (_runTraceThinkingPlaceholders.contains(compactBody)) {
      continue;
    }
    if (currentCompactBody != null &&
        (_runTraceTextsMatch(compactBody, currentCompactBody) ||
            _runTraceTextContains(compactBody, currentCompactBody) ||
            _runTraceTextContains(currentCompactBody, compactBody))) {
      continue;
    }
    return compactBody;
  }
  return null;
}

bool _isRunTraceProcessEntry(ChatRunTraceHistoryEntry entry) {
  final String label = entry.label.trim();
  return label.startsWith('Process ') || label.startsWith('进程 ');
}

String? _compactStructuredInspectorDetail(ChatRunTraceHistoryEntry entry) {
  if (!entry.hasStructuredInspectorContent) {
    return null;
  }
  final String detail = entry.inspectorCallDetail.trim();
  if (detail.isEmpty) {
    return null;
  }
  final List<String> lines = detail
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toList(growable: false);
  if (lines.length <= 1) {
    return detail;
  }
  for (final String line in lines) {
    final String normalized = line.toLowerCase();
    if (normalized.contains('[in_progress]') ||
        normalized.contains('active:') ||
        normalized.contains('当前动作')) {
      return detail;
    }
  }
  return detail;
}

String? _supplementalRunTraceBody({
  required ChatRunTraceData trace,
  required List<ChatRunTraceHistoryEntry> history,
}) {
  final String rawBody = trace.body.trim();
  if (rawBody.isEmpty || _runTraceThinkingPlaceholders.contains(rawBody)) {
    return null;
  }
  final List<String> rawSections = _splitRunTraceSections(rawBody);
  if (rawSections.isEmpty) {
    return null;
  }
  final List<String> representedTexts = history
      .expand(
        (entry) => <String>[
          entry.body,
          _compactBodyForHistoryEntry(entry),
          if (entry.inspectorCallParts.isNotEmpty)
            _joinRunTraceInspectorParts(entry.inspectorCallParts),
          entry.inspectorCallDetail,
          entry.inspectorResultBody,
        ],
      )
      .map((text) => text.trim())
      .where((text) => text.isNotEmpty)
      .toList(growable: false);
  final bool allSectionsCovered = rawSections.every(
    (section) => representedTexts.any(
      (text) =>
          _runTraceTextsMatch(text, section) ||
          _runTraceTextContains(text, section),
    ),
  );
  return allSectionsCovered ? null : rawBody;
}

String _runTraceStatusLabel({
  required ChatRunTraceData trace,
  required String? activityLabel,
  required OpenCrayUiCopy copy,
}) {
  final String normalizedLabel = trace.label.trim().toLowerCase();
  if (normalizedLabel == copy.chatRunWaitingApprovalLabel.toLowerCase() ||
      normalizedLabel == 'waiting for approval' ||
      normalizedLabel == 'approval required') {
    return copy.isChinese ? '等待中' : 'WAITING';
  }
  if (normalizedLabel == copy.chatRunAwaitingDirectionLabel.toLowerCase() ||
      normalizedLabel == 'awaiting direction') {
    return copy.isChinese ? '等待指示' : 'AWAITING';
  }
  if (trace.isRetryable || normalizedLabel.contains('interrupt')) {
    return copy.isChinese ? '已中断' : 'INTERRUPTED';
  }
  if (normalizedLabel.contains('cancel')) {
    return copy.isChinese ? '已取消' : 'CANCELLED';
  }
  final String? mappedActivity = _runTraceActivityStatusFromLabel(
    activityLabel,
    copy,
  );
  if (trace.isTerminal) {
    final String terminalActivity = mappedActivity?.trim().toLowerCase() ?? '';
    if (terminalActivity == 'failed' ||
        terminalActivity == '失败' ||
        terminalActivity == 'cancelled' ||
        terminalActivity == '已取消' ||
        terminalActivity == 'timed out' ||
        terminalActivity == '已超时') {
      return mappedActivity!;
    }
    return copy.isChinese ? '已完成' : 'FINISHED';
  }
  return mappedActivity ?? (copy.isChinese ? '运行中' : 'RUNNING');
}

String? _runTraceActivityLabel({
  required ChatRunTraceData trace,
  required ChatRunTraceHistoryEntry? currentEntry,
  required List<String> currentSections,
  required OpenCrayUiCopy copy,
}) {
  final String? candidateFromLabel = _normalizedRunTraceActivityLabel(
    trace.label,
  );
  if (candidateFromLabel != null &&
      !_runTraceTextsMatch(candidateFromLabel, copy.chatRunWorkingLabel) &&
      !_runTraceTextsMatch(
        candidateFromLabel,
        copy.chatRunWaitingApprovalLabel,
      ) &&
      !_runTraceTextsMatch(
        candidateFromLabel,
        copy.chatRunAwaitingDirectionLabel,
      )) {
    return candidateFromLabel;
  }
  final String? entryLabel = currentEntry == null
      ? null
      : _normalizedRunTraceActivityLabel(currentEntry.label);
  if (entryLabel != null &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunWorkingLabel) &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunWaitingApprovalLabel) &&
      !_runTraceTextsMatch(entryLabel, copy.chatRunAwaitingDirectionLabel)) {
    return entryLabel;
  }
  final String? headline = currentSections.isEmpty
      ? null
      : currentSections.first;
  final String? firstWord = headline == null
      ? null
      : _firstRunTraceWord(headline);
  final String? normalizedFirstWord = _normalizedRunTraceActivityLabel(
    firstWord,
  );
  return _runTraceActivityStatusFromLabel(normalizedFirstWord, copy) == null
      ? null
      : normalizedFirstWord;
}

String? _runTraceActivityStatusFromLabel(String? label, OpenCrayUiCopy copy) {
  final String normalized = label?.trim().toLowerCase() ?? '';
  switch (normalized) {
    case 'finished':
    case 'finish':
    case 'success':
    case 'completed':
    case 'complete':
    case 'done':
      return copy.isChinese ? '已完成' : 'FINISHED';
    case 'failed':
    case 'failure':
    case 'error':
    case 'spawn_error':
      return copy.isChinese ? '失败' : 'FAILED';
    case 'cancelled':
    case 'canceled':
      return copy.isChinese ? '已取消' : 'CANCELLED';
    case 'timeout':
    case 'timed':
    case 'timedout':
      return copy.isChinese ? '已超时' : 'TIMED OUT';
    case 'read':
      return copy.isChinese ? '读取中' : 'READING';
    case 'write':
      return copy.isChinese ? '写入中' : 'WRITING';
    case 'edit':
    case 'multiedit':
      return copy.isChinese ? '编辑中' : 'EDITING';
    case 'ls':
      return copy.isChinese ? '查看中' : 'LISTING';
    case 'grep':
      return copy.isChinese ? '搜索中' : 'SEARCHING';
    case 'glob':
      return copy.isChinese ? '匹配中' : 'MATCHING';
    case 'todowrite':
      return copy.isChinese ? '整理中' : 'UPDATING';
    case 'task':
      return copy.isChinese ? '委派中' : 'DELEGATING';
    case 'memory':
      return copy.isChinese ? '记忆处理中' : 'MEMORY';
    case 'bash':
      return copy.isChinese ? '执行中' : 'RUNNING';
    default:
      return null;
  }
}

String? _normalizedRunTraceActivityLabel(String? label) {
  final String normalized = label?.trim() ?? '';
  if (normalized.isEmpty) {
    return null;
  }
  if (!RegExp(r'^[A-Za-z][A-Za-z0-9]+$').hasMatch(normalized)) {
    return null;
  }
  if (normalized.toUpperCase() == normalized) {
    return normalized;
  }
  return normalized[0].toUpperCase() + normalized.substring(1);
}

List<String> _splitRunTraceSections(String text) {
  return text
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .split(RegExp(r'\n\s*\n'))
      .map((section) => section.trim())
      .where((section) => section.isNotEmpty)
      .toList(growable: false);
}

String _compactBodyForHistoryEntry(ChatRunTraceHistoryEntry entry) =>
    (entry.compactBody?.trim().isNotEmpty == true
            ? entry.compactBody!
            : entry.body)
        .trim();

String _joinRunTraceInspectorParts(List<ChatRunTraceInspectorTextPart> parts) =>
    parts.map((part) => part.text).join();

String? _firstRunTraceLine(String text) {
  final String normalized = text
      .replaceAll('\r\n', '\n')
      .replaceAll('\r', '\n')
      .trim();
  if (normalized.isEmpty) {
    return null;
  }
  return normalized.split('\n').first.trim();
}

String? _firstRunTraceWord(String text) {
  final Match? match = RegExp(r'[A-Za-z][A-Za-z0-9]+').firstMatch(text);
  return match?.group(0);
}

String? _remainingRunTraceLines(String? text) {
  final String normalized =
      text?.replaceAll('\r\n', '\n').replaceAll('\r', '\n').trim() ?? '';
  if (normalized.isEmpty) {
    return null;
  }
  final List<String> lines = normalized.split('\n');
  if (lines.length <= 1) {
    return null;
  }
  final String remainder = lines.sublist(1).join('\n').trim();
  return remainder.isEmpty ? null : remainder;
}

String? _firstNonEmptyRunTraceText(List<String?> values) {
  for (final String? value in values) {
    final String trimmed = value?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      return trimmed;
    }
  }
  return null;
}

String? _firstDistinctRunTraceText({
  required List<String?> candidates,
  required List<String> existing,
}) {
  for (final String? candidate in candidates) {
    final String trimmed = candidate?.trim() ?? '';
    if (trimmed.isEmpty) {
      continue;
    }
    final bool duplicate = existing.any(
      (existingText) =>
          _runTraceTextsMatch(existingText, trimmed) ||
          _runTraceTextContains(existingText, trimmed) ||
          _runTraceTextContains(trimmed, existingText),
    );
    if (!duplicate) {
      return trimmed;
    }
  }
  return null;
}

bool _runTraceTextsMatch(String? left, String? right) {
  String normalize(String? value) =>
      (value ?? '').toLowerCase().replaceAll(RegExp(r'\s+'), ' ').trim();
  return normalize(left).isNotEmpty && normalize(left) == normalize(right);
}

bool _runTraceTextContains(String? source, String? fragment) {
  String normalize(String? value) =>
      (value ?? '').toLowerCase().replaceAll(RegExp(r'\s+'), ' ').trim();
  final String normalizedSource = normalize(source);
  final String normalizedFragment = normalize(fragment);
  if (normalizedSource.isEmpty || normalizedFragment.isEmpty) {
    return false;
  }
  return normalizedSource.contains(normalizedFragment);
}

const int _runInspectorCollapsedLineCount = 3;
const int _runInspectorCollapseCharacterThreshold = 320;

bool _shouldCollapseRunInspectorText(String text) {
  final String previewText = _runInspectorPlainPreviewText(text);
  if (previewText.isEmpty) {
    return false;
  }
  return _runInspectorLineCount(previewText) >
          _runInspectorCollapsedLineCount ||
      previewText.runes.length > _runInspectorCollapseCharacterThreshold;
}

String _runInspectorCollapsedPreviewText(String text) {
  final String previewText = _runInspectorPlainPreviewText(text);
  if (previewText.isEmpty) {
    return '';
  }
  final List<String> lines = previewText.split(RegExp(r'\r\n?|\n'));
  if (lines.length > _runInspectorCollapsedLineCount) {
    final String visibleLines = lines
        .take(_runInspectorCollapsedLineCount)
        .join('\n')
        .trimRight();
    return visibleLines.endsWith('...') ? visibleLines : '$visibleLines...';
  }
  if (previewText.runes.length > _runInspectorCollapseCharacterThreshold) {
    final String truncated = String.fromCharCodes(
      previewText.runes.take(_runInspectorCollapseCharacterThreshold),
    ).trimRight();
    return truncated.endsWith('...') ? truncated : '$truncated...';
  }
  return previewText;
}

int _runInspectorLineCount(String text) {
  if (text.trim().isEmpty) {
    return 0;
  }
  return text.split(RegExp(r'\r\n?|\n')).length;
}

String _runInspectorPlainPreviewText(String markdown) {
  String text = markdown.trim();
  if (text.isEmpty) {
    return '';
  }
  text = text.replaceAll(RegExp(r'```[^\n]*\n?'), '');
  text = text.replaceAll('```', '');
  text = text.replaceAllMapped(
    RegExp(r'!\[([^\]]*)\]\([^)]+\)'),
    (match) => match.group(1) ?? '',
  );
  text = text.replaceAllMapped(
    RegExp(r'\[([^\]]+)\]\([^)]+\)'),
    (match) => match.group(1) ?? '',
  );
  text = text.replaceAllMapped(
    RegExp(r'(^|\n)\s{0,3}#{1,6}\s*'),
    (match) => match.group(1) ?? '',
  );
  text = text.replaceAllMapped(
    RegExp(r'(^|\n)\s{0,3}>\s?'),
    (match) => match.group(1) ?? '',
  );
  text = text.replaceAllMapped(
    RegExp(r'(^|\n)\s*[-*+]\s+'),
    (match) => '${match.group(1) ?? ''}- ',
  );
  text = text.replaceAllMapped(
    RegExp(r'(^|\n)\s*\d+\.\s+'),
    (match) => match.group(1) ?? '',
  );
  text = text.replaceAll(RegExp(r'[*_`~]'), '');
  text = text.replaceAll(RegExp(r'[ \t]+\n'), '\n');
  text = text.replaceAll(RegExp(r'\n{3,}'), '\n\n');
  return text.trim();
}

class _RunInspectorCollapsibleTextBlock extends StatefulWidget {
  const _RunInspectorCollapsibleTextBlock({
    super.key,
    required this.blockKey,
    required this.copy,
    required this.data,
    required this.bodyStyle,
    required this.surfaceColor,
    this.bridge,
  });

  final String blockKey;
  final OpenCrayUiCopy copy;
  final String data;
  final TextStyle bodyStyle;
  final Color surfaceColor;
  final OpenCrayHostBridge? bridge;

  @override
  State<_RunInspectorCollapsibleTextBlock> createState() =>
      _RunInspectorCollapsibleTextBlockState();
}

class _RunInspectorCollapsibleTextBlockState
    extends State<_RunInspectorCollapsibleTextBlock> {
  bool _isExpanded = false;

  @override
  Widget build(BuildContext context) {
    final String data = widget.data.trim();
    final bool shouldCollapse = _shouldCollapseRunInspectorText(data);
    if (!shouldCollapse) {
      return _buildMarkdownBlock(data);
    }
    final String semanticsLabel = widget.copy.isChinese
        ? (_isExpanded ? '收起' : '展开')
        : (_isExpanded ? 'Collapse' : 'Expand');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (_isExpanded)
          KeyedSubtree(
            key: ValueKey<String>('${widget.blockKey}-expanded'),
            child: _buildMarkdownBlock(data),
          )
        else
          Text(
            _runInspectorCollapsedPreviewText(data),
            key: ValueKey<String>('${widget.blockKey}-collapsed'),
            maxLines: _runInspectorCollapsedLineCount,
            overflow: TextOverflow.ellipsis,
            style: widget.bodyStyle,
          ),
        const SizedBox(height: 4),
        Align(
          alignment: Alignment.centerRight,
          child: Semantics(
            button: true,
            label: semanticsLabel,
            child: GestureDetector(
              key: ValueKey<String>('${widget.blockKey}-toggle'),
              behavior: HitTestBehavior.opaque,
              onTap: () {
                setState(() {
                  _isExpanded = !_isExpanded;
                });
              },
              child: SizedBox.square(
                dimension: 28,
                child: Center(
                  child: AnimatedRotation(
                    key: ValueKey<String>('${widget.blockKey}-rotation'),
                    duration: const Duration(milliseconds: 180),
                    turns: _isExpanded ? 0.5 : 0,
                    child: const Icon(
                      Icons.keyboard_arrow_down_rounded,
                      size: 18,
                      color: _ChatPalette.textSecondary,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildMarkdownBlock(String data) {
    return _OpenCrayMarkdownTextBlock(
      copy: widget.copy,
      data: data,
      bodyStyle: widget.bodyStyle,
      surfaceColor: widget.surfaceColor,
      bridge: widget.bridge,
    );
  }
}

class _RunTraceHistoryCard extends StatelessWidget {
  const _RunTraceHistoryCard({
    required this.entryKey,
    required this.copy,
    required this.entry,
    this.bridge,
  });

  final String entryKey;
  final OpenCrayUiCopy copy;
  final ChatRunTraceHistoryEntry entry;
  final OpenCrayHostBridge? bridge;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (entry.hasStructuredInspectorContent)
          _buildStructuredInspectorBody()
        else ...<Widget>[
          if (entry.label.trim().isNotEmpty) ...<Widget>[
            Text(
              entry.label,
              style: _ChatTextStyles.timeline.copyWith(
                color: entry.isHighRisk
                    ? _ChatPalette.highRiskAccent
                    : _ChatPalette.textSecondary,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 4),
          ],
          _RunInspectorCollapsibleTextBlock(
            key: ValueKey<String>('chat-run-inspector-body-$entryKey'),
            blockKey: 'chat-run-inspector-body-$entryKey',
            copy: copy,
            data: entry.body,
            bodyStyle: _ChatTextStyles.bubble.copyWith(
              color: _ChatPalette.textPrimary,
            ),
            surfaceColor: Colors.white,
            bridge: bridge,
          ),
        ],
      ],
    );
  }

  Widget _buildStructuredInspectorBody() {
    final List<Widget> children = <Widget>[
      RichText(
        text: TextSpan(
          children: entry.inspectorCallParts
              .map(
                (part) => TextSpan(
                  text: part.text,
                  style: _ChatTextStyles.runInspectorLog.copyWith(
                    color: _inspectorSemanticColor(part.semantic),
                  ),
                ),
              )
              .toList(growable: false),
        ),
      ),
    ];
    final String inspectorCallDetail = entry.inspectorCallDetail.trim();
    if (inspectorCallDetail.isNotEmpty) {
      children.add(const SizedBox(height: 4));
      children.add(
        _OpenCrayMarkdownTextBlock(
          copy: copy,
          data: inspectorCallDetail,
          bodyStyle: _ChatTextStyles.runInspectorDetail.copyWith(
            color: _ChatPalette.textSecondary,
          ),
          surfaceColor: Colors.white,
          bridge: bridge,
        ),
      );
    }
    final String inspectorResultBody = entry.inspectorResultBody.trim();
    if (inspectorResultBody.isNotEmpty) {
      children.add(const SizedBox(height: 6));
      children.add(_buildInspectorResultText(inspectorResultBody));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }

  Widget _buildInspectorResultText(String body) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text('└', style: _ChatTextStyles.runInspectorResultBranch),
        const SizedBox(width: 8),
        Expanded(
          child: _RunInspectorCollapsibleTextBlock(
            key: ValueKey<String>('chat-run-inspector-result-$entryKey'),
            blockKey: 'chat-run-inspector-result-$entryKey',
            copy: copy,
            data: body,
            bodyStyle: _ChatTextStyles.runInspectorResult,
            surfaceColor: Colors.white,
            bridge: bridge,
          ),
        ),
      ],
    );
  }

  Color _inspectorSemanticColor(ChatRunTraceInspectorTextSemantic semantic) {
    return switch (semantic) {
      ChatRunTraceInspectorTextSemantic.action => _ChatPalette.inspectorAction,
      ChatRunTraceInspectorTextSemantic.target => _ChatPalette.inspectorTarget,
      ChatRunTraceInspectorTextSemantic.scope => _ChatPalette.inspectorScope,
      ChatRunTraceInspectorTextSemantic.connector => _ChatPalette.textPrimary,
      ChatRunTraceInspectorTextSemantic.result => _ChatPalette.inspectorResult,
      ChatRunTraceInspectorTextSemantic.neutral => _ChatPalette.textPrimary,
    };
  }
}
