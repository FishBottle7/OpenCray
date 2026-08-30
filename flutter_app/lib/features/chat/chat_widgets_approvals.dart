part of 'chat_feature_screen.dart';

class _PendingApprovalOverlaySurface extends StatelessWidget {
  const _PendingApprovalOverlaySurface({
    required this.copy,
    required this.approvals,
    required this.busyApprovalTaskIds,
    required this.approvalResolutionById,
    required this.onApproveApproval,
    required this.onApproveApprovalForSession,
    required this.onApproveApprovalAsBatch,
    required this.onRejectApproval,
  });

  final OpenCrayUiCopy copy;
  final List<ChatPendingApprovalData> approvals;
  final Set<String> busyApprovalTaskIds;
  final Map<String, _ApprovalResolutionKind> approvalResolutionById;
  final ValueChanged<ChatPendingApprovalData> onApproveApproval;
  final ValueChanged<ChatPendingApprovalData> onApproveApprovalForSession;
  final ValueChanged<ChatPendingApprovalData> onApproveApprovalAsBatch;
  final ValueChanged<ChatPendingApprovalData> onRejectApproval;

  @override
  Widget build(BuildContext context) {
    final ChatPendingApprovalData activeApproval = approvals.first;
    final List<ChatPendingApprovalData> queuedApprovals = approvals.length <= 1
        ? const <ChatPendingApprovalData>[]
        : approvals.sublist(1);

    return _ApprovalGlassSurface(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          key: const ValueKey<String>('chat-approval-surface-content'),
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            _PendingApprovalCardStack(
              copy: copy,
              activeApproval: activeApproval,
              queuedApprovals: queuedApprovals,
            ),
            const SizedBox(height: 8),
            _ApprovalActionRow(
              copy: copy,
              approval: activeApproval,
              isBusy: busyApprovalTaskIds.contains(activeApproval.approvalId),
              resolutionKind: approvalResolutionById[activeApproval.approvalId],
              onApprove: () => onApproveApproval(activeApproval),
              onApproveForSession: () =>
                  onApproveApprovalForSession(activeApproval),
              onApproveAsBatch: () => onApproveApprovalAsBatch(activeApproval),
              onReject: () => onRejectApproval(activeApproval),
            ),
          ],
        ),
      ),
    );
  }
}

class _ApprovalGlassSurface extends StatelessWidget {
  const _ApprovalGlassSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(28),
      child: Stack(
        children: <Widget>[
          Positioned.fill(
            child: IgnorePointer(
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
                child: DecoratedBox(
                  key: const ValueKey<String>('chat-approval-surface'),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(28),
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.52),
                    ),
                    boxShadow: <BoxShadow>[
                      BoxShadow(
                        color: context.chatGlass.approvalShadow,
                        blurRadius: 22,
                        offset: const Offset(0, 10),
                      ),
                    ],
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: <Color>[
                        Colors.white.withValues(alpha: 0.42),
                        Colors.white.withValues(alpha: 0.32),
                        context.palette.primaryTint.withValues(alpha: 0.26),
                        context.palette.primaryBorder.withValues(alpha: 0.18),
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

class _PendingApprovalCardStack extends StatelessWidget {
  const _PendingApprovalCardStack({
    required this.copy,
    required this.activeApproval,
    required this.queuedApprovals,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData activeApproval;
  final List<ChatPendingApprovalData> queuedApprovals;

  @override
  Widget build(BuildContext context) {
    final List<ChatPendingApprovalData> previewApprovals = queuedApprovals
        .take(2)
        .toList(growable: false);
    if (previewApprovals.isEmpty) {
      return _ApprovalActiveCardSwitcher(
        approvalId: activeApproval.approvalId,
        child: _PendingApprovalCard(copy: copy, approval: activeApproval),
      );
    }
    final int previewCount = previewApprovals.length;
    return Padding(
      padding: EdgeInsets.only(bottom: 12.0 * previewCount),
      child: Stack(
        key: const ValueKey<String>('chat-approval-stack'),
        clipBehavior: Clip.none,
        children: <Widget>[
          for (int index = previewApprovals.length - 1; index >= 0; index -= 1)
            Positioned(
              left: 6.0 * (index + 1),
              right: 6.0 * (index + 1),
              top: 12.0 * (index + 1),
              child: IgnorePointer(
                child: Opacity(
                  opacity: 0.9 - (index * 0.16),
                  child: _PendingApprovalCard(
                    copy: copy,
                    approval: previewApprovals[index],
                    isPreview: true,
                  ),
                ),
              ),
            ),
          _ApprovalActiveCardSwitcher(
            approvalId: activeApproval.approvalId,
            child: _PendingApprovalCard(copy: copy, approval: activeApproval),
          ),
        ],
      ),
    );
  }
}

class _ApprovalActiveCardSwitcher extends StatelessWidget {
  const _ApprovalActiveCardSwitcher({
    required this.approvalId,
    required this.child,
  });

  final String approvalId;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (OpenCrayMotion.reduce(context)) {
      return KeyedSubtree(key: ValueKey<String>(approvalId), child: child);
    }
    return AnimatedSwitcher(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
      reverseDuration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
      switchInCurve: OpenCrayMotion.enter,
      switchOutCurve: OpenCrayMotion.exit,
      transitionBuilder: (Widget child, Animation<double> animation) {
        final Animation<double> curved = CurvedAnimation(
          parent: animation,
          curve: OpenCrayMotion.enter,
          reverseCurve: OpenCrayMotion.exit,
        );
        return FadeTransition(
          opacity: curved,
          child: SlideTransition(
            position: Tween<Offset>(
              begin: const Offset(0, 0.05),
              end: Offset.zero,
            ).animate(curved),
            child: child,
          ),
        );
      },
      child: KeyedSubtree(key: ValueKey<String>(approvalId), child: child),
    );
  }
}

class _PendingApprovalCard extends StatelessWidget {
  const _PendingApprovalCard({
    required this.copy,
    required this.approval,
    this.isPreview = false,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData approval;
  final bool isPreview;

  @override
  Widget build(BuildContext context) {
    final _PendingApprovalPresentation presentation =
        _PendingApprovalPresentation.fromApproval(copy, approval);
    final Color surfaceColor = approval.isHighRisk
        ? context.chatPalette.highRiskSurface
        : context.palette.surfaceSubtle;
    final Color borderColor = approval.isHighRisk
        ? context.chatPalette.highRiskBorder
        : context.chatPalette.runTraceBorder;
    final Color reasonColor = approval.isHighRisk
        ? context.chatPalette.highRiskReasonText
        : context.palette.textSecondary;
    final TextStyle detailStyle = context.chatText.approvalRequest.copyWith(
      color: context.palette.textPrimary,
    );
    final List<_ApprovalInfoRowData> visibleRows = isPreview
        ? presentation.impactRows.take(1).toList(growable: false)
        : presentation.impactRows;

    return DecoratedBox(
      key: ValueKey<String>('chat-approval-card-${approval.approvalId}'),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: borderColor),
      ),
      child: Stack(
        children: <Widget>[
          if (approval.isHighRisk && !isPreview)
            Positioned(
              left: 0,
              top: 14,
              bottom: 14,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: context.chatPalette.highRiskAccent.withValues(alpha: 0.68),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: const SizedBox(width: 3),
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Expanded(
                      child: Text(
                        approval.title,
                        style: context.chatText.cardTitle,
                      ),
                    ),
                    if (approval.isHighRisk && !isPreview) ...<Widget>[
                      const SizedBox(width: 12),
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: context.chatPalette.highRiskBadgeSurface,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 4,
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: <Widget>[
                              // Shape as well as colour, so the risk still
                              // reads without colour vision.
                              Icon(
                                Icons.warning_amber_rounded,
                                size: 13,
                                color: context.chatPalette.highRiskAccent,
                              ),
                              const SizedBox(width: 4),
                              Text(
                                copy.chatHighRiskApproval,
                                style: context.chatText.highRiskBadge,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                if (presentation.headline.isNotEmpty) ...<Widget>[
                  const SizedBox(height: 10),
                  Text(
                    presentation.headline,
                    key: ValueKey<String>(
                      'chat-approval-headline-${approval.approvalId}',
                    ),
                    maxLines: isPreview ? 1 : null,
                    overflow: isPreview
                        ? TextOverflow.ellipsis
                        : TextOverflow.visible,
                    style: detailStyle,
                  ),
                ],
                if (visibleRows.isNotEmpty) ...<Widget>[
                  const SizedBox(height: 10),
                  for (int index = 0; index < visibleRows.length; index += 1)
                    Padding(
                      padding: EdgeInsets.only(top: index == 0 ? 0 : 7),
                      child: _ApprovalInfoRow(
                        row: visibleRows[index],
                        color: reasonColor,
                        compact: isPreview,
                      ),
                    ),
                ],
                if (presentation.messageLine != null && !isPreview) ...<Widget>[
                  const SizedBox(height: 8),
                  Text(
                    presentation.messageLine!,
                    style: context.chatText.approvalReason.copyWith(
                      color: context.chatPalette.textSecondary,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ApprovalInfoRowData {
  const _ApprovalInfoRowData({required this.label, required this.value});

  final String label;
  final String value;
}

class _ApprovalInfoRow extends StatelessWidget {
  const _ApprovalInfoRow({
    required this.row,
    required this.color,
    required this.compact,
  });

  final _ApprovalInfoRowData row;
  final Color color;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: compact ? 0.34 : 0.48),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.white.withValues(alpha: 0.54)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SizedBox(
              width: compact ? 58 : 86,
              child: Text(
                row.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.chatText.approvalReason.copyWith(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  color: color.withValues(alpha: 0.78),
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                row.value,
                maxLines: compact ? 1 : 3,
                overflow: TextOverflow.ellipsis,
                style: context.chatText.approvalReason.copyWith(color: color),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ApprovalResolutionBanner extends StatelessWidget {
  const _ApprovalResolutionBanner({
    required this.label,
    required this.isRejected,
  });

  final String label;
  final bool isRejected;

  @override
  Widget build(BuildContext context) {
    final Color color = isRejected
        ? context.palette.danger
        : context.palette.success;
    final Color surface = isRejected
        ? context.palette.dangerTint
        : context.palette.successTint;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.18)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(
              isRejected ? Icons.block_rounded : Icons.check_circle_rounded,
              size: 15,
              color: color,
            ),
            const SizedBox(width: 7),
            Flexible(
              child: Text(
                label,
                key: const ValueKey<String>('chat-approval-resolution-label'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.chatText.approvalAction.copyWith(color: color),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _approvalResolutionLabel(
  OpenCrayUiCopy copy,
  _ApprovalResolutionKind kind,
) => switch (kind) {
  _ApprovalResolutionKind.approved => copy.chatApprovalDecisionApproved,
  _ApprovalResolutionKind.approvedForSession =>
    copy.chatApprovalDecisionApprovedForSession,
  _ApprovalResolutionKind.rejected => copy.chatApprovalDecisionRejected,
};

class _ApprovalActionRow extends StatelessWidget {
  const _ApprovalActionRow({
    required this.copy,
    required this.approval,
    required this.isBusy,
    required this.resolutionKind,
    required this.onApprove,
    required this.onApproveForSession,
    required this.onApproveAsBatch,
    required this.onReject,
  });

  final OpenCrayUiCopy copy;
  final ChatPendingApprovalData approval;
  final bool isBusy;
  final _ApprovalResolutionKind? resolutionKind;
  final VoidCallback onApprove;
  final VoidCallback onApproveForSession;
  final VoidCallback onApproveAsBatch;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
    if (resolutionKind != null) {
      return SizedBox(
        width: double.infinity,
        child: _ApprovalResolutionBanner(
          label: _approvalResolutionLabel(copy, resolutionKind!),
          isRejected: resolutionKind == _ApprovalResolutionKind.rejected,
        ),
      );
    }
    final Color accentColor = approval.isHighRisk
        ? context.chatPalette.highRiskAccent
        : context.chatPalette.accent;
    final Widget rejectButton = _ApprovalActionButton(
      label: approval.rejectLabel,
      foregroundColor: context.palette.textSecondary,
      backgroundColor: context.palette.surface,
      borderColor: context.palette.outline,
      onPressed: isBusy ? null : onReject,
    );
    final Widget? approveForSessionButton = approval.supportsSessionApproval
        ? _ApprovalActionButton(
            label: approval.approveForSessionLabel,
            foregroundColor: accentColor,
            backgroundColor: context.palette.surface,
            borderColor: accentColor.withValues(alpha: 0.65),
            onPressed: isBusy ? null : onApproveForSession,
          )
        : null;
    final Widget approveButton = _ApprovalActionButton(
      label: approval.approveLabel,
      foregroundColor: Colors.white,
      backgroundColor: accentColor,
      borderColor: accentColor,
      onPressed: isBusy ? null : onApprove,
      isBusy: isBusy,
    );
    final Widget batchApproveButton = SizedBox(
      width: double.infinity,
      child: OpenCrayInkSurface(
        child: InkWell(
          key: const ValueKey<String>('chat-approval-batch-action'),
          onTap: isBusy ? null : onApproveAsBatch,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Text(
              copy.chatApprovalBatchAction,
              textAlign: TextAlign.center,
              style: context.chatText.approvalReason.copyWith(
                color: accentColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ),
    );
    return Column(
      children: <Widget>[
        LayoutBuilder(
          builder: (BuildContext context, BoxConstraints constraints) {
            final bool compact =
                constraints.maxWidth < 344 && approveForSessionButton != null;
            if (compact) {
              return Column(
                children: <Widget>[
                  Row(
                    children: <Widget>[
                      Expanded(child: rejectButton),
                      const SizedBox(width: 10),
                      Expanded(child: approveForSessionButton),
                    ],
                  ),
                  const SizedBox(height: 8),
                  approveButton,
                ],
              );
            }
            return Row(
              children: <Widget>[
                Expanded(child: rejectButton),
                const SizedBox(width: 10),
                if (approveForSessionButton != null) ...<Widget>[
                  Expanded(child: approveForSessionButton),
                  const SizedBox(width: 10),
                ],
                Expanded(child: approveButton),
              ],
            );
          },
        ),
        const SizedBox(height: 6),
        batchApproveButton,
      ],
    );
  }
}

class _PendingApprovalPresentation {
  const _PendingApprovalPresentation({
    required this.headline,
    required this.impactRows,
    required this.messageLine,
  });

  final String headline;
  final List<_ApprovalInfoRowData> impactRows;
  final String? messageLine;

  factory _PendingApprovalPresentation.fromApproval(
    OpenCrayUiCopy copy,
    ChatPendingApprovalData approval,
  ) {
    final String toolName = approval.toolName.trim();
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

    final String headline = requestSummary.isNotEmpty
        ? requestSummary
        : primaryDetail.isNotEmpty
        ? primaryDetail
        : pathDetails.isNotEmpty
        ? pathDetails.first
        : body;
    final List<_ApprovalInfoRowData> impactRows = <_ApprovalInfoRowData>[
      if (toolName.isNotEmpty)
        _ApprovalInfoRowData(
          label: copy.chatApprovalToolLabel,
          value: toolName,
        ),
      if (primaryDetail.isNotEmpty &&
          primaryDetail != headline &&
          !pathDetails.contains(primaryDetail))
        _ApprovalInfoRowData(
          label: copy.chatApprovalDetailsLabel,
          value: primaryDetail,
        ),
      if (pathDetails.where((path) => path != headline).isNotEmpty)
        _ApprovalInfoRowData(
          label: copy.chatApprovalPathsLabel,
          value: pathDetails.where((path) => path != headline).join('\n'),
        ),
      if (workingDirectory.isNotEmpty)
        _ApprovalInfoRowData(
          label: copy.chatApprovalWorkingDirectoryLabel,
          value: workingDirectory,
        ),
      if (reason.isNotEmpty)
        _ApprovalInfoRowData(
          label: copy.chatApprovalReasonLabel,
          value: reason,
        ),
    ];
    final String? messageLine =
        message.isNotEmpty &&
            message != reason &&
            !_runTraceTextContains(body, message)
        ? message
        : null;
    return _PendingApprovalPresentation(
      headline: headline,
      impactRows: impactRows,
      messageLine: messageLine,
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
    this.isBusy = false,
  });

  final String label;
  final Color foregroundColor;
  final Color backgroundColor;
  final Color borderColor;
  final VoidCallback? onPressed;
  final bool isBusy;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onPressed != null;
    final Color surfaceColor = enabled
        ? backgroundColor
        : backgroundColor.withValues(alpha: 0.55);
    // Filled variants need a light ripple; the app-wide 7% blue splash
    // disappears on terracotta and on the accent fill.
    final bool filled =
        ThemeData.estimateBrightnessForColor(surfaceColor) == Brightness.dark;
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        border: Border.all(
          color: enabled ? borderColor : borderColor.withValues(alpha: 0.55),
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        child: InkWell(
          onTap: enabled ? _handleTap : null,
          splashColor: filled ? Colors.white24 : null,
          highlightColor: filled ? Colors.white10 : null,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 38),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Center(
                child: AnimatedSwitcher(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.quick,
                  ),
                  switchInCurve: OpenCrayMotion.enter,
                  switchOutCurve: OpenCrayMotion.exit,
                  child: isBusy
                      ? Row(
                          key: const ValueKey<String>('approval-action-busy'),
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: <Widget>[
                            SizedBox.square(
                              dimension: 13,
                              child: CircularProgressIndicator(
                                strokeWidth: 1.6,
                                color: foregroundColor.withValues(alpha: 0.88),
                              ),
                            ),
                            const SizedBox(width: 7),
                            Flexible(
                              child: Text(
                                label,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: context.chatText.approvalAction.copyWith(
                                  color: foregroundColor.withValues(
                                    alpha: 0.88,
                                  ),
                                ),
                              ),
                            ),
                          ],
                        )
                      : Text(
                          label,
                          key: const ValueKey<String>('approval-action-label'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: context.chatText.approvalAction.copyWith(
                            color: enabled
                                ? foregroundColor
                                : foregroundColor.withValues(alpha: 0.6),
                          ),
                        ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _handleTap() {
    // Approving or rejecting is the most consequential tap in the app; confirm
    // it in the hand before the request leaves.
    HapticFeedback.selectionClick();
    onPressed!();
  }
}
