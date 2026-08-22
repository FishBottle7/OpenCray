part of 'chat_feature_screen.dart';

class _RunTraceBubble extends StatefulWidget {
  const _RunTraceBubble({
    super.key,
    required this.bridge,
    required this.copy,
    required this.trace,
    this.showSandboxPreviewCard = false,
    this.showRetryAction = false,
    this.showInterruptAction = false,
    this.showInterruptConfirm = false,
    this.onInterruptRequest,
    this.onInterruptDismiss,
    this.onInterruptConfirm,
    this.isInterruptBusy = false,
    this.onRetry,
    this.isRetryBusy = false,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatRunTraceData trace;
  final bool showSandboxPreviewCard;
  final bool showRetryAction;
  final bool showInterruptAction;
  final bool showInterruptConfirm;
  final VoidCallback? onInterruptRequest;
  final VoidCallback? onInterruptDismiss;
  final VoidCallback? onInterruptConfirm;
  final bool isInterruptBusy;
  final VoidCallback? onRetry;
  final bool isRetryBusy;

  @override
  State<_RunTraceBubble> createState() => _RunTraceBubbleState();
}

class _RunTraceBubbleState extends State<_RunTraceBubble> {
  static final Map<String, Set<ValueNotifier<ChatRunTraceData>>>
  _openTraceNotifiersByRunKey =
      <String, Set<ValueNotifier<ChatRunTraceData>>>{};

  @override
  void initState() {
    super.initState();
    _publishTraceUpdate();
  }

  @override
  void didUpdateWidget(covariant _RunTraceBubble oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.trace, widget.trace)) {
      _runTraceDebug(
        'bubble.traceUpdate run=${widget.trace.runId} openInspectors=${_openNotifierCount(widget.trace)} history=${widget.trace.history.length}',
      );
    }
    _publishTraceUpdate();
  }

  static List<String> _traceNotifierKeys(ChatRunTraceData trace) {
    final List<String> keys = <String>[];
    final String taskId = trace.taskId.trim();
    final String runId = trace.runId.trim();
    if (taskId.isNotEmpty) {
      keys.add('task:$taskId');
    }
    if (runId.isNotEmpty) {
      keys.add('run:$runId');
    }
    final String label = trace.label.trim();
    if (label.isNotEmpty) {
      keys.add('label:$label');
    }
    if (keys.isEmpty) {
      keys.add('label:${trace.label.trim()}');
    }
    return keys;
  }

  static int _openNotifierCount(ChatRunTraceData trace) =>
      _traceNotifierKeys(trace).fold<int>(
        0,
        (total, key) => total + (_openTraceNotifiersByRunKey[key]?.length ?? 0),
      );

  static bool hasOpenInspectorForTraces(Iterable<ChatRunTraceData> traces) {
    for (final trace in traces) {
      if (_openNotifierCount(trace) > 0) {
        return true;
      }
    }
    return false;
  }

  void _publishTraceUpdate() {
    final Set<ValueNotifier<ChatRunTraceData>> notifiers =
        <ValueNotifier<ChatRunTraceData>>{};
    for (final key in _traceNotifierKeys(widget.trace)) {
      notifiers.addAll(
        _openTraceNotifiersByRunKey[key] ??
            const <ValueNotifier<ChatRunTraceData>>{},
      );
    }
    for (final notifier in notifiers) {
      notifier.value = widget.trace;
    }
  }

  Future<void> _openFullscreen() {
    final ValueNotifier<ChatRunTraceData> traceNotifier =
        ValueNotifier<ChatRunTraceData>(widget.trace);
    final List<String> traceKeys = _traceNotifierKeys(widget.trace);
    for (final traceKey in traceKeys) {
      _openTraceNotifiersByRunKey
          .putIfAbsent(traceKey, () => <ValueNotifier<ChatRunTraceData>>{})
          .add(traceNotifier);
    }
    return showDialog<void>(
      context: context,
      barrierColor: const Color(0x8A0B0E14),
      builder: (dialogContext) => _RunTraceFullscreenSheet(
        copy: widget.copy,
        traceListenable: traceNotifier,
        showSandboxPreviewCard: widget.showSandboxPreviewCard,
        bridge: widget.bridge,
        onRetry: widget.onRetry,
        isRetryBusy: widget.isRetryBusy,
      ),
    ).whenComplete(() {
      for (final traceKey in traceKeys) {
        final Set<ValueNotifier<ChatRunTraceData>>? notifiers =
            _openTraceNotifiersByRunKey[traceKey];
        notifiers?.remove(traceNotifier);
        if (notifiers != null && notifiers.isEmpty) {
          _openTraceNotifiersByRunKey.remove(traceKey);
        }
      }
      traceNotifier.dispose();
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

  @override
  Widget build(BuildContext context) {
    final ChatRunTraceData trace = widget.trace;
    final ChatRunTraceSandboxSessionCardData? sessionCard =
        widget.showSandboxPreviewCard ? trace.sessionCard : null;
    final ChatRunTracePreviewCardData? previewCard =
        widget.showSandboxPreviewCard ? trace.previewCard : null;
    final _RunTraceCompactPresentation presentation =
        _buildRunTraceCompactPresentation(trace: trace, copy: widget.copy);
    final bool showInlineActions =
        widget.showRetryAction || widget.showInterruptAction;
    final double bubbleWidth = math.min(
      MediaQuery.sizeOf(context).width - 76,
      314,
    );
    return GestureDetector(
      onDoubleTap: _openFullscreen,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: bubbleWidth,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            _RunTraceStatusLine(
              trace: trace,
              presentation: presentation,
              copy: widget.copy,
              onTap: _openFullscreen,
            ),
            if (showInlineActions) ...<Widget>[
              const SizedBox(height: 8),
              _ChatRunTraceInlineActions(
                copy: widget.copy,
                traces: <ChatRunTraceData>[trace],
                showRetryActions: widget.showRetryAction,
                showInterruptActions: widget.showInterruptAction,
                interruptConfirmRunId: widget.showInterruptConfirm
                    ? trace.interruptId
                    : null,
                busyInterruptRunIds: widget.isInterruptBusy
                    ? <String>{trace.interruptId}
                    : const <String>{},
                busyRetryRunIds: widget.isRetryBusy
                    ? <String>{trace.retryId}
                    : const <String>{},
                onArmInterruptRunTrace: widget.onInterruptRequest == null
                    ? null
                    : (_) => widget.onInterruptRequest!(),
                onDismissInterruptRunTrace: widget.onInterruptDismiss == null
                    ? null
                    : (_) => widget.onInterruptDismiss!(),
                onInterruptRunTrace: widget.onInterruptConfirm == null
                    ? null
                    : (_) => widget.onInterruptConfirm!(),
                onRetryRunTrace: widget.onRetry == null
                    ? null
                    : (_) => widget.onRetry!(),
              ),
            ],
            if (sessionCard != null) ...<Widget>[
              const SizedBox(height: 10),
              _RunTraceSandboxSessionCard(
                key: ValueKey<String>(
                  'chat-run-trace-session-card-${trace.runId}',
                ),
                copy: widget.copy,
                runId: trace.runId,
                session: sessionCard,
                bridge: widget.bridge,
              ),
            ],
            if (previewCard != null) ...<Widget>[
              const SizedBox(height: 10),
              _RunTracePreviewCard(
                key: ValueKey<String>(
                  'chat-run-trace-preview-card-${trace.runId}',
                ),
                copy: widget.copy,
                runId: trace.runId,
                preview: previewCard,
                bridge: widget.bridge,
                onOpen: widget.bridge == null
                    ? null
                    : () => _openPreviewCard(previewCard),
                onCopy: () => _copyPreviewUrl(previewCard),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _RunTraceStatusLine extends StatefulWidget {
  const _RunTraceStatusLine({
    required this.trace,
    required this.presentation,
    required this.copy,
    required this.onTap,
  });

  final ChatRunTraceData trace;
  final _RunTraceCompactPresentation presentation;
  final OpenCrayUiCopy copy;
  final VoidCallback onTap;

  @override
  State<_RunTraceStatusLine> createState() => _RunTraceStatusLineState();
}

class _RunTraceStatusLineState extends State<_RunTraceStatusLine>
    with SingleTickerProviderStateMixin {
  late final AnimationController _shimmerController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1450),
  );

  bool get _shouldAnimate {
    return !widget.trace.isTerminal &&
        !widget.trace.isRetryable &&
        !OpenCrayMotion.reduce(context) &&
        !_isAutomatedWidgetTest;
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

  @override
  void initState() {
    super.initState();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncAnimation();
  }

  @override
  void didUpdateWidget(covariant _RunTraceStatusLine oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncAnimation();
  }

  void _syncAnimation() {
    if (_shouldAnimate) {
      if (!_shimmerController.isAnimating) {
        _shimmerController.repeat();
      }
    } else {
      _shimmerController.stop();
      _shimmerController.value = 0;
    }
  }

  @override
  void dispose() {
    _shimmerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bool animate = _shouldAnimate;
    final Color baseColor = widget.trace.isHighRisk
        ? _ChatPalette.highRiskAccent
        : _ChatPalette.runTraceActivityText;
    final Color mutedColor = widget.trace.isTerminal || widget.trace.isRetryable
        ? _ChatPalette.textSecondary
        : baseColor;
    final String lead = _runTraceStatusLineLead(
      trace: widget.trace,
      presentation: widget.presentation,
      copy: widget.copy,
    );
    final String? detail = _runTraceStatusLineDetail(
      lead: lead,
      presentation: widget.presentation,
    );
    final String label = detail == null ? lead : '$lead · $detail';
    final _RunTraceStatusTone tone = _RunTraceStatusTone.fromTrace(
      widget.trace,
      animate: animate,
    );
    final TextStyle lineStyle = _ChatTextStyles.timeline.copyWith(
      color: tone.textColor,
      fontWeight: FontWeight.w700,
    );
    final Widget text = Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Text(
          lead,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: lineStyle,
        ),
        if (detail != null) ...<Widget>[
          Text(' · ', style: lineStyle),
          Flexible(
            child: Text(
              detail,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: lineStyle,
            ),
          ),
        ],
      ],
    );
    final Widget animatedText = animate
        ? AnimatedBuilder(
            animation: _shimmerController,
            child: text,
            builder: (context, child) {
              final double position = _shimmerController.value;
              return ShaderMask(
                shaderCallback: (bounds) {
                  final double width = math.max(bounds.width, 1);
                  final double center = (position * 2.4 - 0.7) * width;
                  return LinearGradient(
                    begin: Alignment.centerLeft,
                    end: Alignment.centerRight,
                    colors: <Color>[
                      mutedColor,
                      mutedColor.withValues(alpha: 0.54),
                      mutedColor,
                    ],
                    stops: const <double>[0.0, 0.5, 1.0],
                  ).createShader(
                    Rect.fromLTWH(center - width, 0, width * 2, bounds.height),
                  );
                },
                child: child,
              );
            },
          )
        : text;
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        onTap: widget.onTap,
        onDoubleTap: widget.onTap,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: double.infinity,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 3),
            child: Row(
              children: <Widget>[
                SizedBox(
                  width: 14,
                  height: 14,
                  child: _RunTraceStatusMark(tone: tone),
                ),
                const SizedBox(width: 8),
                Flexible(child: animatedText),
                const SizedBox(width: 4),
                Icon(
                  Icons.chevron_right_rounded,
                  size: 16,
                  color: tone.textColor.withValues(alpha: 0.78),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RunTraceStatusTone {
  const _RunTraceStatusTone({
    required this.textColor,
    required this.markColor,
    required this.icon,
    required this.showProgress,
  });

  final Color textColor;
  final Color markColor;
  final IconData? icon;
  final bool showProgress;

  factory _RunTraceStatusTone.fromTrace(
    ChatRunTraceData trace, {
    required bool animate,
  }) {
    if (trace.isRetryable) {
      return const _RunTraceStatusTone(
        textColor: OpenCrayColors.warning,
        markColor: Color(0xFFF59E0B),
        icon: Icons.priority_high_rounded,
        showProgress: false,
      );
    }
    if (trace.isTerminal) {
      return const _RunTraceStatusTone(
        textColor: _ChatPalette.textSecondary,
        markColor: OpenCrayColors.textTertiary,
        icon: Icons.check_rounded,
        showProgress: false,
      );
    }
    if (trace.isHighRisk) {
      return const _RunTraceStatusTone(
        textColor: _ChatPalette.highRiskAccent,
        markColor: _ChatPalette.highRiskAccent,
        icon: null,
        showProgress: true,
      );
    }
    return _RunTraceStatusTone(
      textColor: _ChatPalette.runTraceActivityText,
      markColor: _ChatPalette.runTraceStatusText,
      icon: null,
      showProgress: animate,
    );
  }
}

class _RunTraceStatusMark extends StatelessWidget {
  const _RunTraceStatusMark({required this.tone});

  final _RunTraceStatusTone tone;

  @override
  Widget build(BuildContext context) {
    if (tone.icon != null) {
      return DecoratedBox(
        decoration: BoxDecoration(
          color: tone.markColor.withValues(alpha: 0.12),
          shape: BoxShape.circle,
        ),
        child: Icon(tone.icon, size: 10, color: tone.markColor),
      );
    }
    return Stack(
      alignment: Alignment.centerLeft,
      children: <Widget>[
        Container(
          width: 14,
          height: 4,
          decoration: BoxDecoration(
            color: tone.markColor.withValues(alpha: 0.18),
            borderRadius: BorderRadius.circular(999),
          ),
        ),
        AnimatedContainer(
          duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
          curve: OpenCrayMotion.enter,
          width: tone.showProgress ? 9 : 5,
          height: 4,
          decoration: BoxDecoration(
            color: tone.markColor.withValues(
              alpha: tone.showProgress ? 0.9 : 0.54,
            ),
            borderRadius: BorderRadius.circular(999),
          ),
        ),
      ],
    );
  }
}

String _runTraceStatusLineLead({
  required ChatRunTraceData trace,
  required _RunTraceCompactPresentation presentation,
  required OpenCrayUiCopy copy,
}) {
  final String statusLabel = presentation.statusLabel.trim();
  if (trace.isTerminal || trace.isRetryable) {
    return statusLabel;
  }
  final String genericRunningLabel = copy.isChinese ? '运行中' : 'RUNNING';
  if (!_runTraceTextsMatch(statusLabel, genericRunningLabel)) {
    return statusLabel;
  }
  return presentation.activityLabel ?? (copy.isChinese ? '思考中' : 'Thinking');
}

String? _runTraceStatusLineDetail({
  required String lead,
  required _RunTraceCompactPresentation presentation,
}) {
  final List<String> candidates = <String>[
    presentation.headline,
    if (presentation.description != null) presentation.description!,
    ...presentation.detailLines.map((line) => line.value),
  ];
  final List<String> details = <String>[];
  for (final candidate in candidates) {
    final String text = candidate.trim();
    final bool placeholder = _runTraceThinkingPlaceholders.contains(text);
    if (text.isEmpty ||
        placeholder ||
        _runTraceTextsMatch(lead, text) ||
        _runTraceTextContains(text, lead) ||
        details.any(
          (detail) =>
              _runTraceTextsMatch(detail, text) ||
              _runTraceTextContains(detail, text) ||
              _runTraceTextContains(text, detail),
        )) {
      continue;
    }
    details.add(text);
  }
  if (details.isEmpty) {
    return null;
  }
  return details.join(' · ');
}

class _RunTracePreviewCard extends StatelessWidget {
  const _RunTracePreviewCard({
    super.key,
    required this.copy,
    required this.runId,
    required this.preview,
    this.bridge,
    this.expanded = false,
    this.keyNamespace = 'chat-run-trace-preview',
    this.onOpen,
    this.onCopy,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTracePreviewCardData preview;
  final OpenCrayHostBridge? bridge;
  final bool expanded;
  final String keyNamespace;
  final VoidCallback? onOpen;
  final VoidCallback? onCopy;

  @override
  Widget build(BuildContext context) {
    final _RunTracePreviewStatusStyle statusStyle = _runTracePreviewStatusStyle(
      preview.status,
    );
    final List<String> detailParts = <String>[
      if (preview.provider?.trim().isNotEmpty == true)
        preview.provider!.trim().toUpperCase(),
      if (preview.port != null) 'Port ${preview.port}',
      if (preview.path?.trim().isNotEmpty == true) preview.path!.trim(),
      if (preview.httpStatusCode != null) 'HTTP ${preview.httpStatusCode}',
    ];
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.runTracePreviewSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                const Icon(
                  Icons.cloud_outlined,
                  size: 16,
                  color: _ChatPalette.runTraceActivityText,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    copy.chatRunPreviewTitle,
                    style: _ChatTextStyles.cardTitle.copyWith(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 12),
                _RunTracePill(
                  label: _runTracePreviewStatusLabel(preview.status, copy),
                  foregroundColor: statusStyle.foregroundColor,
                  backgroundColor: statusStyle.backgroundColor,
                ),
              ],
            ),
            if (bridge != null) ...<Widget>[
              const SizedBox(height: 10),
              _EmbeddedSandboxPreviewSurface(
                key: ValueKey<String>('$keyNamespace-embedded-$runId'),
                copy: copy,
                runId: runId,
                preview: preview,
                bridge: bridge!,
                expanded: expanded,
                keyNamespace: keyNamespace,
              ),
            ],
            const SizedBox(height: 10),
            KeyedSubtree(
              key: ValueKey<String>('$keyNamespace-url-$runId'),
              child: _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: preview.url,
                bodyStyle: _ChatTextStyles.runTraceDetailValue.copyWith(
                  color: _ChatPalette.runTraceUrlText,
                  fontWeight: FontWeight.w600,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
                preferAccentForStrong: true,
              ),
            ),
            if (detailParts.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              if (expanded)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: detailParts
                      .map((detail) => _RunTracePreviewFactChip(label: detail))
                      .toList(growable: false),
                )
              else
                Text(
                  detailParts.join(' • '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: _ChatPalette.textSecondary,
                  ),
                ),
            ],
            if (preview.message?.trim().isNotEmpty == true) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: preview.message!.trim(),
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                _RunTracePreviewActionButton(
                  key: ValueKey<String>('$keyNamespace-open-$runId'),
                  label: copy.chatRunPreviewOpenAction,
                  icon: Icons.open_in_new_rounded,
                  emphasized: true,
                  onTap: onOpen,
                ),
                _RunTracePreviewActionButton(
                  key: ValueKey<String>('$keyNamespace-copy-$runId'),
                  label: copy.chatRunPreviewCopyUrlAction,
                  icon: Icons.content_copy_rounded,
                  onTap: onCopy,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _EmbeddedSandboxPreviewSurface extends StatefulWidget {
  const _EmbeddedSandboxPreviewSurface({
    super.key,
    required this.copy,
    required this.runId,
    required this.preview,
    required this.bridge,
    required this.expanded,
    required this.keyNamespace,
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTracePreviewCardData preview;
  final OpenCrayHostBridge bridge;
  final bool expanded;
  final String keyNamespace;

  @override
  State<_EmbeddedSandboxPreviewSurface> createState() =>
      _EmbeddedSandboxPreviewSurfaceState();
}

class _EmbeddedSandboxPreviewSurfaceState
    extends State<_EmbeddedSandboxPreviewSurface> {
  WebViewController? _controller;
  String? _statusMessage;
  String? _activePreviewUrl;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _resolvePreview();
  }

  @override
  void didUpdateWidget(covariant _EmbeddedSandboxPreviewSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.preview.url != widget.preview.url ||
        oldWidget.bridge != widget.bridge) {
      _resolvePreview();
    }
  }

  Future<void> _resolvePreview() async {
    final String previewUrl = widget.preview.url.trim();
    if (previewUrl.isEmpty) {
      if (!mounted) {
        return;
      }
      setState(() {
        _controller = null;
        _statusMessage = widget.copy.chatRunPreviewEmbedUnavailable;
        _isLoading = false;
      });
      return;
    }
    setState(() {
      _activePreviewUrl = previewUrl;
      _controller = null;
      _statusMessage = null;
      _isLoading = true;
    });
    try {
      final OpenCraySandboxPreviewEmbedConfig config = await widget.bridge
          .resolveSandboxPreviewEmbedConfig(previewUrl);
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      if (!config.sessionMatched) {
        setState(() {
          _controller = null;
          _statusMessage =
              config.unavailableReason ??
              widget.copy.chatRunPreviewEmbedUnavailable;
          _isLoading = false;
        });
        return;
      }
      final WebViewController controller = WebViewController()
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..setBackgroundColor(_ChatPalette.runTracePreviewSurface)
        ..setNavigationDelegate(
          NavigationDelegate(
            onWebResourceError: (WebResourceError error) {
              if (!mounted || _activePreviewUrl != previewUrl) {
                return;
              }
              final String message = error.description.trim().isNotEmpty
                  ? error.description.trim()
                  : widget.copy.chatRunPreviewEmbedUnavailable;
              setState(() {
                _statusMessage = message;
              });
            },
          ),
        );
      await controller.loadRequest(
        Uri.parse(config.previewUrl),
        headers: config.headers,
      );
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      setState(() {
        _controller = controller;
        _statusMessage = null;
        _isLoading = false;
      });
    } catch (_) {
      if (!mounted || _activePreviewUrl != previewUrl) {
        return;
      }
      setState(() {
        _controller = null;
        _statusMessage = widget.copy.chatRunPreviewEmbedUnsupported;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final double aspectRatio = widget.expanded ? 1.45 : 1.65;
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: AspectRatio(
        aspectRatio: aspectRatio,
        child: DecoratedBox(
          decoration: const BoxDecoration(
            color: _ChatPalette.runTraceDetailSurface,
          ),
          child: _buildBody(),
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_controller != null && _statusMessage == null) {
      return KeyedSubtree(
        key: ValueKey<String>(
          '${widget.keyNamespace}-embedded-webview-${widget.runId}',
        ),
        child: WebViewWidget(controller: _controller!),
      );
    }
    final bool isLoading = _isLoading;
    final IconData icon = isLoading
        ? Icons.hourglass_bottom_rounded
        : Icons.public_off_outlined;
    final String message = isLoading
        ? widget.copy.chatRunPreviewEmbedLoading
        : (_statusMessage ?? widget.copy.chatRunPreviewEmbedUnavailable);
    return Container(
      key: ValueKey<String>(
        '${widget.keyNamespace}-embedded-unavailable-${widget.runId}',
      ),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      alignment: Alignment.center,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          if (isLoading)
            const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.2),
            )
          else
            Icon(icon, size: 22, color: _ChatPalette.textSecondary),
          const SizedBox(height: 10),
          Text(
            message,
            textAlign: TextAlign.center,
            maxLines: widget.expanded ? 3 : 2,
            overflow: TextOverflow.ellipsis,
            style: _ChatTextStyles.bodyMuted.copyWith(
              color: _ChatPalette.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _RunTraceSandboxSessionCard extends StatelessWidget {
  const _RunTraceSandboxSessionCard({
    super.key,
    required this.copy,
    required this.runId,
    required this.session,
    this.bridge,
    this.expanded = false,
    this.keyNamespace = 'chat-run-trace-session',
  });

  final OpenCrayUiCopy copy;
  final String runId;
  final ChatRunTraceSandboxSessionCardData session;
  final OpenCrayHostBridge? bridge;
  final bool expanded;
  final String keyNamespace;

  @override
  Widget build(BuildContext context) {
    final _RunTraceSandboxSessionStatusStyle statusStyle =
        _runTraceSandboxSessionStatusStyle(session.lifecycleStatus);
    final List<String> detailParts = <String>[
      _runTraceSandboxSessionSourceLabel(session.source, copy),
      if (session.provider?.trim().isNotEmpty == true)
        session.provider!.trim().toUpperCase(),
      if (session.templateId?.trim().isNotEmpty == true)
        copy.chatRunSandboxSessionTemplate(session.templateId!.trim()),
      if (session.previewCandidatePorts.isNotEmpty)
        copy.chatRunSandboxSessionPorts(
          session.previewCandidatePorts.join(', '),
        ),
      if (session.runningRequestIds.isNotEmpty)
        copy.chatRunSandboxSessionRunningCount(
          session.runningRequestIds.length,
        ),
      if (session.lastPreviewProbeStatus != null)
        copy.chatRunSandboxSessionPreviewStatus(
          _runTracePreviewStatusLabel(session.lastPreviewProbeStatus!, copy),
        ),
    ];
    final String summary = session.sessionPresent
        ? (session.sandboxId?.trim().isNotEmpty == true
              ? session.sandboxId!.trim()
              : copy.chatRunSandboxSessionTitle)
        : copy.chatRunSandboxSessionMissing;
    final String? subtitle = session.sandboxDomain?.trim().isNotEmpty == true
        ? session.sandboxDomain!.trim()
        : null;
    final String? updatedLabel = _formatRunTraceSandboxSessionUpdated(
      copy,
      session.updatedAtEpochMs,
    );
    final String? lastActiveLabel = _formatRunTraceSandboxSessionLastActive(
      copy,
      session.sessionLastActivityAtEpochMs,
    );
    final String? staleAfterLabel = _formatRunTraceSandboxSessionStaleAfter(
      copy,
      session.sessionStaleAfterEpochMs,
    );
    final String? previewCheckedLabel =
        _formatRunTraceSandboxSessionPreviewChecked(
          copy,
          session.lastPreviewProbeObservedAtEpochMs,
        );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.runTracePreviewSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                const Icon(
                  Icons.cloud_sync_outlined,
                  size: 16,
                  color: _ChatPalette.runTraceActivityText,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    copy.chatRunSandboxSessionTitle,
                    style: _ChatTextStyles.cardTitle.copyWith(fontSize: 14),
                  ),
                ),
                const SizedBox(width: 12),
                _RunTracePill(
                  label: _runTraceSandboxSessionLifecycleLabel(
                    session.lifecycleStatus,
                    copy,
                  ),
                  foregroundColor: statusStyle.foregroundColor,
                  backgroundColor: statusStyle.backgroundColor,
                ),
              ],
            ),
            const SizedBox(height: 10),
            KeyedSubtree(
              key: ValueKey<String>('$keyNamespace-summary-$runId'),
              child: _OpenCrayMarkdownTextBlock(
                key: ValueKey<String>('$keyNamespace-summary-markdown-$runId'),
                copy: copy,
                data: summary,
                bodyStyle: _ChatTextStyles.runTraceDetailValue.copyWith(
                  color: session.sessionPresent
                      ? _ChatPalette.textPrimary
                      : _ChatPalette.textSecondary,
                  fontWeight: FontWeight.w600,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
                preferAccentForStrong: true,
              ),
            ),
            if (subtitle != null) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: subtitle,
                bodyStyle: _ChatTextStyles.runTraceFooter.copyWith(
                  color: _ChatPalette.textSecondary,
                ),
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (detailParts.isNotEmpty) ...<Widget>[
              const SizedBox(height: 8),
              if (expanded)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: detailParts
                      .map((detail) => _RunTracePreviewFactChip(label: detail))
                      .toList(growable: false),
                )
              else
                Text(
                  detailParts.join(' • '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: _ChatPalette.textSecondary,
                  ),
                ),
            ],
            if (updatedLabel != null) ...<Widget>[
              const SizedBox(height: 8),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: updatedLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (lastActiveLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: lastActiveLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (staleAfterLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: staleAfterLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (previewCheckedLabel != null) ...<Widget>[
              const SizedBox(height: 6),
              _OpenCrayMarkdownTextBlock(
                copy: copy,
                data: previewCheckedLabel,
                bodyStyle: _ChatTextStyles.bodyMuted,
                surfaceColor: _ChatPalette.runTracePreviewSurface,
                bridge: bridge,
              ),
            ],
            if (expanded && session.runningRequestIds.isNotEmpty) ...<Widget>[
              const SizedBox(height: 10),
              Text(
                copy.chatRunSandboxSessionRunningRequestsTitle,
                style: _ChatTextStyles.runTraceDetailLabel,
              ),
              const SizedBox(height: 6),
              Text(
                session.runningRequestIds.join(', '),
                style: _ChatTextStyles.runTraceFooter.copyWith(
                  color: _ChatPalette.textPrimary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _RunTracePreviewFactChip extends StatelessWidget {
  const _RunTracePreviewFactChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _ChatPalette.background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: _ChatPalette.runTracePreviewBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _ChatTextStyles.runTraceFooter.copyWith(
            color: _ChatPalette.textSecondary,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _RunTracePreviewActionButton extends StatelessWidget {
  const _RunTracePreviewActionButton({
    super.key,
    required this.label,
    required this.icon,
    this.emphasized = false,
    this.onTap,
  });

  final String label;
  final IconData icon;
  final bool emphasized;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    final Color foregroundColor = emphasized
        ? Colors.white
        : enabled
        ? _ChatPalette.runTraceActivityText
        : _ChatPalette.textSecondary;
    final Color backgroundColor = emphasized
        ? (enabled
              ? _ChatPalette.runTraceActivityText
              : _ChatPalette.runTraceActivityText.withValues(alpha: 0.32))
        : _ChatPalette.background;
    return Semantics(
      button: true,
      enabled: enabled,
      child: Listener(
        onPointerUp: enabled ? (_) => onTap!() : null,
        behavior: HitTestBehavior.opaque,
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: backgroundColor,
            shape: StadiumBorder(
              side: BorderSide(
                color: emphasized
                    ? Colors.transparent
                    : _ChatPalette.runTracePreviewBorder,
              ),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Icon(icon, size: 15, color: foregroundColor),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: _ChatTextStyles.runTraceFooter.copyWith(
                    color: foregroundColor,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _runTracePreviewStatusLabel(
  ChatRunTracePreviewStatus status,
  OpenCrayUiCopy copy,
) {
  switch (status) {
    case ChatRunTracePreviewStatus.ready:
      return copy.chatRunPreviewStatusReady;
    case ChatRunTracePreviewStatus.reachable:
      return copy.chatRunPreviewStatusReachable;
    case ChatRunTracePreviewStatus.unreachable:
      return copy.chatRunPreviewStatusUnreachable;
    case ChatRunTracePreviewStatus.skipped:
      return copy.chatRunPreviewStatusSkipped;
  }
}

String _runTraceSandboxSessionSourceLabel(
  ChatRunTraceSandboxSessionSource source,
  OpenCrayUiCopy copy,
) {
  switch (source) {
    case ChatRunTraceSandboxSessionSource.activeMemory:
      return copy.chatRunSandboxSessionSourceActive;
    case ChatRunTraceSandboxSessionSource.persisted:
      return copy.chatRunSandboxSessionSourcePersisted;
    case ChatRunTraceSandboxSessionSource.activeAndPersisted:
      return copy.chatRunSandboxSessionSourceActiveAndPersisted;
    case ChatRunTraceSandboxSessionSource.none:
      return copy.chatRunSandboxSessionSourceNone;
  }
}

String? _formatRunTraceSandboxSessionUpdated(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  if (epochMs == null || epochMs <= 0) {
    return null;
  }
  final DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(
    epochMs,
  ).toLocal();
  final DateTime now = DateTime.now().toLocal();
  final String clock = _formatChatClockLabel(copy, dateTime);
  final String label = _isSameChatDay(dateTime, now)
      ? clock
      : '${_formatChatDateLabel(copy, dateTime, includeYear: now.year != dateTime.year)} $clock';
  return copy.chatRunSandboxSessionUpdated(label);
}

String? _formatRunTraceSandboxSessionLastActive(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionLastActive(label);
}

String? _formatRunTraceSandboxSessionStaleAfter(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionStaleAfter(label);
}

String? _formatRunTraceSandboxSessionPreviewChecked(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  final String? label = _formatRunTraceSandboxSessionTimestamp(copy, epochMs);
  return label == null ? null : copy.chatRunSandboxSessionPreviewChecked(label);
}

String? _formatRunTraceSandboxSessionTimestamp(
  OpenCrayUiCopy copy,
  int? epochMs,
) {
  if (epochMs == null || epochMs <= 0) {
    return null;
  }
  final DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(
    epochMs,
  ).toLocal();
  final DateTime now = DateTime.now().toLocal();
  final String clock = _formatChatClockLabel(copy, dateTime);
  return _isSameChatDay(dateTime, now)
      ? clock
      : '${_formatChatDateLabel(copy, dateTime, includeYear: now.year != dateTime.year)} $clock';
}

String _runTraceSandboxSessionLifecycleLabel(
  ChatRunTraceSandboxSessionLifecycleStatus lifecycleStatus,
  OpenCrayUiCopy copy,
) {
  switch (lifecycleStatus) {
    case ChatRunTraceSandboxSessionLifecycleStatus.active:
      return copy.chatRunSandboxSessionLifecycleActive;
    case ChatRunTraceSandboxSessionLifecycleStatus.stale:
      return copy.chatRunSandboxSessionLifecycleStale;
    case ChatRunTraceSandboxSessionLifecycleStatus.reclaimed:
      return copy.chatRunSandboxSessionLifecycleReclaimed;
    case ChatRunTraceSandboxSessionLifecycleStatus.none:
      return copy.chatRunSandboxSessionLifecycleNone;
  }
}

_RunTracePreviewStatusStyle _runTracePreviewStatusStyle(
  ChatRunTracePreviewStatus status,
) {
  switch (status) {
    case ChatRunTracePreviewStatus.ready:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: OpenCrayColors.success,
        backgroundColor: OpenCrayColors.successTint,
      );
    case ChatRunTracePreviewStatus.reachable:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: OpenCrayColors.primaryPressed,
        backgroundColor: OpenCrayColors.primaryTint,
      );
    case ChatRunTracePreviewStatus.unreachable:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: OpenCrayColors.warning,
        backgroundColor: OpenCrayColors.warningTint,
      );
    case ChatRunTracePreviewStatus.skipped:
      return const _RunTracePreviewStatusStyle(
        foregroundColor: OpenCrayColors.textSecondary,
        backgroundColor: OpenCrayColors.surfaceMuted,
      );
  }
}

_RunTraceSandboxSessionStatusStyle _runTraceSandboxSessionStatusStyle(
  ChatRunTraceSandboxSessionLifecycleStatus lifecycleStatus,
) {
  switch (lifecycleStatus) {
    case ChatRunTraceSandboxSessionLifecycleStatus.active:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: OpenCrayColors.success,
        backgroundColor: OpenCrayColors.successTint,
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.stale:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: OpenCrayColors.warning,
        backgroundColor: OpenCrayColors.warningTint,
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.reclaimed:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: OpenCrayColors.textSecondary,
        backgroundColor: OpenCrayColors.surfaceMuted,
      );
    case ChatRunTraceSandboxSessionLifecycleStatus.none:
      return const _RunTraceSandboxSessionStatusStyle(
        foregroundColor: OpenCrayColors.textSecondary,
        backgroundColor: OpenCrayColors.surfaceMuted,
      );
  }
}

class _RunTracePreviewStatusStyle {
  const _RunTracePreviewStatusStyle({
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final Color foregroundColor;
  final Color backgroundColor;
}

class _RunTraceSandboxSessionStatusStyle {
  const _RunTraceSandboxSessionStatusStyle({
    required this.foregroundColor,
    required this.backgroundColor,
  });

  final Color foregroundColor;
  final Color backgroundColor;
}

const String _runTraceMainActorId = 'main';

class _RunTraceInspectorActorSection {
  const _RunTraceInspectorActorSection({
    required this.id,
    required this.label,
    required this.entries,
  });

  final String id;
  final String label;
  final List<ChatRunTraceHistoryEntry> entries;
}
