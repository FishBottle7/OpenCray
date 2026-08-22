part of 'chat_feature_screen.dart';

class _ChatImageAttachmentGroup extends StatelessWidget {
  const _ChatImageAttachmentGroup({
    required this.bridge,
    required this.copy,
    required this.messageId,
    required this.attachments,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
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
                copy: copy,
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
    required this.copy,
    required this.attachment,
    required this.maxWidth,
    required this.isOutgoing,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
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

  Future<void> _showFullscreenPreview(
    BuildContext context,
    OpenCrayFileImagePreview preview,
  ) {
    return _showChatPreviewDialog(
      context,
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
                child: OpenCrayImageBytesView(
                  bytes: preview.bytes,
                  mimeType: preview.mimeType,
                  fit: BoxFit.contain,
                ),
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
        : OpenCrayColors.surfaceMuted;
    final Future<OpenCrayFileImagePreview>? previewFuture = _previewFuture;
    final double stableAspectRatio = _chatMessageAttachmentStableAspectRatio(
      widget.attachment,
    );
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
        return GestureDetector(
          key: ValueKey<String>(
            'chat-message-image-attachment-${widget.attachment.attachmentId}',
          ),
          onTap: ready ? () => _showFullscreenPreview(context, preview) : null,
          child: AspectRatio(
            aspectRatio: stableAspectRatio,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: Stack(
                fit: StackFit.expand,
                children: <Widget>[
                  DecoratedBox(
                    decoration: BoxDecoration(
                      color: placeholderColor,
                      border: Border.all(color: borderColor),
                    ),
                    child: AnimatedSwitcher(
                      duration: OpenCrayMotion.resolve(
                        context,
                        OpenCrayMotion.quick,
                      ),
                      switchInCurve: OpenCrayMotion.enter,
                      switchOutCurve: OpenCrayMotion.exit,
                      transitionBuilder:
                          (Widget child, Animation<double> animation) {
                            return FadeTransition(
                              opacity: animation,
                              child: child,
                            );
                          },
                      child: ready
                          ? OpenCrayImageBytesView(
                              key: ValueKey<String>(
                                'chat-message-image-ready-${widget.attachment.attachmentId}',
                              ),
                              bytes: preview.bytes,
                              mimeType: preview.mimeType,
                              fit: BoxFit.cover,
                            )
                          : _ChatImageAttachmentPlaceholderBody(
                              key: ValueKey<String>(
                                'chat-message-image-placeholder-${widget.attachment.attachmentId}',
                              ),
                              attachment: widget.attachment,
                              isOutgoing: widget.isOutgoing,
                            ),
                    ),
                  ),
                  Positioned(
                    top: 6,
                    right: 6,
                    child: _ChatAttachmentActions(
                      bridge: widget.bridge,
                      copy: widget.copy,
                      attachment: widget.attachment,
                      isOutgoing: widget.isOutgoing,
                      keyPrefix: 'chat-message-image-attachment',
                      overlay: true,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

double _chatMessageAttachmentStableAspectRatio(
  ChatMessageAttachmentData attachment,
) {
  final int width = attachment.widthPx ?? 0;
  final int height = attachment.heightPx ?? 0;
  if (width <= 0 || height <= 0) {
    return 1;
  }
  return (width / height).clamp(0.65, 1.65).toDouble();
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
      width: maxWidth,
      child: AspectRatio(
        aspectRatio: _chatMessageAttachmentStableAspectRatio(attachment),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: isOutgoing
                  ? Colors.white.withValues(alpha: 0.14)
                  : OpenCrayColors.surfaceMuted,
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
      ),
    );
  }
}

class _ChatImageAttachmentPlaceholderBody extends StatelessWidget {
  const _ChatImageAttachmentPlaceholderBody({
    super.key,
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
        await _showChatTextPreviewDialog(context, preview, bridge: hostBridge);
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
        : OpenCrayColors.surfaceMuted;
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
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: borderColor),
      ),
      child: Material(
        color: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: <Widget>[
              Expanded(
                child: InkWell(
                  key: ValueKey<String>(
                    'chat-message-attachment-${attachment.attachmentId}',
                  ),
                  borderRadius: BorderRadius.circular(10),
                  onTap: canOpen ? () => _openAttachment(context) : null,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
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
                                style: _ChatTextStyles.attachmentDetail
                                    .copyWith(color: detailColor),
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
              if (canOpen) ...<Widget>[
                const SizedBox(width: 8),
                _ChatAttachmentActions(
                  bridge: bridge,
                  copy: copy,
                  attachment: attachment,
                  isOutgoing: isOutgoing,
                  keyPrefix: 'chat-message-attachment',
                ),
              ],
            ],
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

  Future<void> _shareAttachment() async {
    _recordChildInteraction();
    await _shareChatAttachment(
      context,
      bridge: widget.bridge,
      copy: widget.copy,
      attachment: widget.attachment,
    );
  }

  Future<void> _saveAttachment() async {
    _recordChildInteraction();
    await _saveChatAttachment(
      context,
      bridge: widget.bridge,
      copy: widget.copy,
      attachment: widget.attachment,
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
        : OpenCrayColors.surfaceMuted;
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

        final bool canUseAttachmentActions =
            widget.bridge != null &&
            widget.attachment.localPath.trim().isNotEmpty;

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
                                : OpenCrayColors.outline,
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
                    if (canUseAttachmentActions) ...<Widget>[
                      const SizedBox(width: 8),
                      _ChatAttachmentActions(
                        bridge: widget.bridge,
                        copy: widget.copy,
                        attachment: widget.attachment,
                        isOutgoing: widget.isOutgoing,
                        keyPrefix: 'chat-message-attachment',
                        onShare: _shareAttachment,
                        onSave: _saveAttachment,
                      ),
                    ],
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

class _ChatAttachmentActions extends StatelessWidget {
  const _ChatAttachmentActions({
    required this.bridge,
    required this.copy,
    required this.attachment,
    required this.isOutgoing,
    required this.keyPrefix,
    this.overlay = false,
    this.onShare,
    this.onSave,
  });

  final OpenCrayHostBridge? bridge;
  final OpenCrayUiCopy copy;
  final ChatMessageAttachmentData attachment;
  final bool isOutgoing;
  final String keyPrefix;
  final bool overlay;
  final Future<void> Function()? onShare;
  final Future<void> Function()? onSave;

  @override
  Widget build(BuildContext context) {
    final bool enabled =
        bridge != null && attachment.localPath.trim().isNotEmpty;
    final Color foregroundColor = overlay || isOutgoing
        ? Colors.white
        : _ChatPalette.textSecondary;
    final Color backgroundColor = overlay
        ? Colors.black.withValues(alpha: 0.42)
        : (isOutgoing
              ? Colors.white.withValues(alpha: 0.14)
              : Colors.white.withValues(alpha: 0.72));
    final BorderSide border = BorderSide(
      color: overlay || isOutgoing
          ? Colors.white.withValues(alpha: 0.18)
          : _ChatPalette.border,
    );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(8),
        border: Border.fromBorderSide(border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          _ChatAttachmentActionButton(
            key: ValueKey<String>(
              '$keyPrefix-share-${attachment.attachmentId}',
            ),
            tooltip: copy.chatAttachmentShareAction,
            icon: Icons.share_outlined,
            color: foregroundColor,
            onPressed: enabled
                ? () async {
                    if (onShare != null) {
                      await onShare!();
                      return;
                    }
                    await _shareChatAttachment(
                      context,
                      bridge: bridge,
                      copy: copy,
                      attachment: attachment,
                    );
                  }
                : null,
          ),
          _ChatAttachmentActionButton(
            key: ValueKey<String>('$keyPrefix-save-${attachment.attachmentId}'),
            tooltip: copy.chatAttachmentSaveAction,
            icon: Icons.download_rounded,
            color: foregroundColor,
            onPressed: enabled
                ? () async {
                    if (onSave != null) {
                      await onSave!();
                      return;
                    }
                    await _saveChatAttachment(
                      context,
                      bridge: bridge,
                      copy: copy,
                      attachment: attachment,
                    );
                  }
                : null,
          ),
        ],
      ),
    );
  }
}

class _ChatAttachmentActionButton extends StatelessWidget {
  const _ChatAttachmentActionButton({
    super.key,
    required this.tooltip,
    required this.icon,
    required this.color,
    required this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final Color color;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: tooltip,
      icon: Icon(icon, size: 16),
      color: color,
      disabledColor: color.withValues(alpha: 0.36),
      visualDensity: VisualDensity.compact,
      constraints: const BoxConstraints.tightFor(width: 30, height: 30),
      padding: EdgeInsets.zero,
      onPressed: onPressed,
    );
  }
}

Future<void> _shareChatAttachment(
  BuildContext context, {
  required OpenCrayHostBridge? bridge,
  required OpenCrayUiCopy copy,
  required ChatMessageAttachmentData attachment,
}) async {
  final OpenCrayHostBridge? hostBridge = bridge;
  final String localPath = attachment.localPath.trim();
  if (hostBridge == null || localPath.isEmpty) {
    return;
  }
  try {
    await hostBridge.shareWorkspaceEntries(<String>[localPath]);
  } catch (_) {
    if (!context.mounted) {
      return;
    }
    _showChatAttachmentFeedback(context, copy.chatAttachmentShareFailed);
  }
}

Future<void> _saveChatAttachment(
  BuildContext context, {
  required OpenCrayHostBridge? bridge,
  required OpenCrayUiCopy copy,
  required ChatMessageAttachmentData attachment,
}) async {
  final OpenCrayHostBridge? hostBridge = bridge;
  final String localPath = attachment.localPath.trim();
  if (hostBridge == null || localPath.isEmpty) {
    return;
  }
  try {
    final OpenCraySavedWorkspaceMediaAttachment saved = await hostBridge
        .saveWorkspaceMediaAttachment(
          relativePath: localPath,
          kind: attachment.kind.name,
        );
    if (!context.mounted) {
      return;
    }
    final String message = saved.collection == 'recordings'
        ? copy.chatAttachmentSavedToRecordings
        : copy.chatAttachmentSavedToDownloads;
    _showChatAttachmentFeedback(context, message);
  } catch (_) {
    if (!context.mounted) {
      return;
    }
    _showChatAttachmentFeedback(context, copy.chatAttachmentSaveFailed);
  }
}

void _showChatAttachmentFeedback(BuildContext context, String message) {
  ScaffoldMessenger.maybeOf(context)
    ?..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
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

Future<void> _showChatTextPreviewDialog(
  BuildContext context,
  OpenCrayFileTextPreview preview, {
  OpenCrayHostBridge? bridge,
}) {
  return _showChatPreviewDialog(
    context,
    builder: (dialogContext) =>
        _ChatTextPreviewDialog(preview: preview, bridge: bridge),
  );
}

Future<void> _showChatPreviewDialog(
  BuildContext context, {
  required WidgetBuilder builder,
  Color barrierColor = const Color(0x8A0B0E14),
}) {
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: MaterialLocalizations.of(context).modalBarrierDismissLabel,
    barrierColor: barrierColor,
    transitionDuration: OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
    pageBuilder:
        (
          BuildContext dialogContext,
          Animation<double> animation,
          Animation<double> secondaryAnimation,
        ) => builder(dialogContext),
    transitionBuilder:
        (
          BuildContext dialogContext,
          Animation<double> animation,
          Animation<double> secondaryAnimation,
          Widget child,
        ) {
          final bool reduce = OpenCrayMotion.reduce(dialogContext);
          final Animation<double> curvedAnimation = CurvedAnimation(
            parent: animation,
            curve: OpenCrayMotion.enter,
            reverseCurve: OpenCrayMotion.exit,
          );
          final Widget faded = FadeTransition(
            opacity: curvedAnimation,
            child: child,
          );
          if (reduce) {
            return faded;
          }
          return SlideTransition(
            position: Tween<Offset>(
              begin: const Offset(0, 0.03),
              end: Offset.zero,
            ).animate(curvedAnimation),
            child: faded,
          );
        },
  );
}

bool _isPreviewableTextAttachment(ChatMessageAttachmentData attachment) {
  final String normalizedMimeType =
      attachment.mimeType?.trim().toLowerCase() ?? '';
  if (normalizedMimeType.startsWith('text/') ||
      _chatPreviewableTextMimeTypes.contains(normalizedMimeType)) {
    return true;
  }
  return _isPreviewableTextFileName(attachment.displayName);
}

bool _isPreviewableTextRelativePath(String relativePath) =>
    _isPreviewableTextFileName(_chatRelativePathFileName(relativePath));

bool _isPreviewableTextFileName(String fileName) {
  final String normalizedName = fileName.trim().toLowerCase();
  if (normalizedName.isEmpty) {
    return false;
  }
  if (_chatPreviewableTextFileNames.contains(normalizedName)) {
    return true;
  }
  final String extension = normalizedName.contains('.')
      ? normalizedName.split('.').last
      : '';
  return _chatPreviewableTextExtensions.contains(extension);
}

bool _isWorkspaceRelativeChatLink(Uri? uri, String href) {
  if (href.trim().isEmpty) {
    return false;
  }
  if (uri != null && uri.hasScheme) {
    return false;
  }
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(href);
  return normalizedPath.isNotEmpty && !normalizedPath.startsWith('/');
}

String _normalizeChatWorkspaceRelativePath(String href) {
  final String trimmed = href.trim();
  if (trimmed.isEmpty) {
    return '';
  }
  final Uri? uri = Uri.tryParse(trimmed);
  final String path = uri != null && !uri.hasScheme ? uri.path : trimmed;
  return _safeDecodeChatLinkPath(path).replaceAll('\\', '/').trim();
}

String _chatRelativePathFileName(String relativePath) {
  final String normalizedPath = _normalizeChatWorkspaceRelativePath(
    relativePath,
  );
  if (normalizedPath.isEmpty) {
    return '';
  }
  final int slashIndex = normalizedPath.lastIndexOf('/');
  if (slashIndex < 0) {
    return normalizedPath;
  }
  return normalizedPath.substring(slashIndex + 1);
}

String _safeDecodeChatLinkPath(String value) {
  try {
    return Uri.decodeFull(value);
  } on FormatException {
    return value;
  }
}

MarkdownStyleSheet _chatTextPreviewMarkdownStyleSheet(BuildContext context) {
  final MarkdownStyleSheet base = MarkdownStyleSheet.fromTheme(
    Theme.of(context),
  );
  final Color linkColor = Theme.of(context).colorScheme.primary;
  return base.copyWith(
    a: TextStyle(
      fontSize: 13,
      height: 1.5,
      fontWeight: FontWeight.w600,
      color: linkColor,
      decoration: TextDecoration.underline,
      decorationColor: linkColor.withValues(alpha: 0.75),
    ),
    p: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    h1: const TextStyle(
      fontSize: 23,
      height: 1.2,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h2: const TextStyle(
      fontSize: 19,
      height: 1.24,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    h3: const TextStyle(
      fontSize: 16,
      height: 1.3,
      fontWeight: FontWeight.w700,
      color: _ChatPalette.textPrimary,
    ),
    listBullet: const TextStyle(
      fontSize: 13,
      height: 1.5,
      color: _ChatPalette.textPrimary,
    ),
    code: const TextStyle(
      fontSize: 12.5,
      height: 1.45,
      fontFamily: 'monospace',
      color: _ChatPalette.textPrimary,
    ),
    codeblockPadding: const EdgeInsets.all(12),
    codeblockDecoration: BoxDecoration(
      color: OpenCrayColors.surfaceMuted,
      borderRadius: BorderRadius.circular(12),
    ),
    blockSpacing: 14,
    blockquote: const TextStyle(
      fontSize: 12.5,
      height: 1.5,
      color: _ChatPalette.textSecondary,
    ),
    blockquoteDecoration: BoxDecoration(
      color: OpenCrayColors.surfaceMuted,
      borderRadius: BorderRadius.circular(12),
      border: const Border(
        left: BorderSide(color: _ChatPalette.border, width: 3),
      ),
    ),
    horizontalRuleDecoration: const BoxDecoration(
      border: Border(top: BorderSide(color: _ChatPalette.border, width: 1)),
    ),
  );
}

class _ChatTextPreviewDialog extends StatelessWidget {
  const _ChatTextPreviewDialog({required this.preview, this.bridge});

  final OpenCrayFileTextPreview preview;
  final OpenCrayHostBridge? bridge;

  Widget _buildMarkdownSelectionContextMenu(
    BuildContext context,
    SelectableRegionState selectableRegionState,
    OpenCrayMarkdownSelectionSnapshot? selection,
    String markdown,
  ) {
    final List<ContextMenuButtonItem> buttonItems = selectableRegionState
        .contextMenuButtonItems
        .map((item) {
          if (item.type != ContextMenuButtonType.copy) {
            return item;
          }
          return item.copyWith(
            onPressed: () async {
              final OpenCrayMarkdownClipboardPayload? payload =
                  openCrayBuildMarkdownSelectionClipboardPayload(
                    markdown,
                    selectedText: selection?.plainText ?? '',
                    selectionStartOffset: selection?.range?.startOffset,
                    selectionEndOffset: selection?.range?.endOffset,
                  );
              if (payload == null) {
                item.onPressed?.call();
                return;
              }
              final OpenCrayHostBridge? hostBridge = bridge;
              if (hostBridge == null) {
                await Clipboard.setData(ClipboardData(text: payload.plainText));
              } else {
                await hostBridge.copyRichTextToClipboard(
                  plainText: payload.plainText,
                  htmlText: payload.htmlText,
                );
              }
              openCrayFinalizeSelectionCopyUi(selectableRegionState);
            },
          );
        })
        .toList(growable: false);
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: selectableRegionState.contextMenuAnchors,
      buttonItems: buttonItems,
    );
  }

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
                    color: OpenCrayColors.surfaceSubtle,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: _ChatPalette.border),
                  ),
                  child: Scrollbar(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(14),
                      child: openCrayIsMarkdownFileName(preview.name)
                          ? OpenCraySelectableMarkdownBody(
                              key: const ValueKey<String>(
                                'chat-text-preview-markdown',
                              ),
                              data: content,
                              hostBridge: bridge,
                              documentRelativePath: preview.relativePath,
                              latexTextStyle: const TextStyle(
                                fontSize: 13,
                                height: 1.5,
                                color: _ChatPalette.textPrimary,
                              ),
                              styleSheet: _chatTextPreviewMarkdownStyleSheet(
                                context,
                              ),
                              imageBackgroundColor: OpenCrayColors.surfaceMuted,
                              imageBorderColor: _ChatPalette.border,
                              contextMenuBuilder:
                                  (
                                    BuildContext context,
                                    SelectableRegionState selectableRegionState,
                                    OpenCrayMarkdownSelectionSnapshot?
                                    selection,
                                  ) => _buildMarkdownSelectionContextMenu(
                                    context,
                                    selectableRegionState,
                                    selection,
                                    content,
                                  ),
                            )
                          : SelectionArea(
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
