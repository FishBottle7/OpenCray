part of 'chat_feature_screen.dart';

class _ChatDecorations {
  const _ChatDecorations._();

  static BoxDecoration card() {
    return BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: _ChatPalette.border),
      boxShadow: OpenCrayShadows.card,
    );
  }
}

class _ChatPalette {
  _ChatPalette._();

  static const Color background = OpenCrayColors.shellBackground;
  static const Color accent = OpenCrayColors.primary;
  static const Color highRiskAccent = Color(0xFFC2491D);
  static const Color highRiskBorder = Color(0xFFF0CFC0);
  static const Color highRiskBadgeSurface = Color(0xFFFBE4D8);
  static const Color highRiskSurface = Color(0xFFFDF6F2);
  static const Color highRiskReasonText = Color(0xFF8A5A3B);
  static const Color textPrimary = OpenCrayColors.textPrimary;
  static const Color textSecondary = OpenCrayColors.textSecondary;
  static const Color textTertiary = OpenCrayColors.textTertiary;
  static const Color border = OpenCrayColors.divider;

  /// Zero-alpha counterparts used as the resting end of a fade. Kept as
  /// literals so the interpolated mid-tones stay identical to the values the
  /// layout was tuned against.
  static const Color surfaceClear = Color(0x00FFFFFF);
  static const Color borderClear = Color(0x00E5E9F0);

  /// Summary card title once a thread is active — a step quieter than
  /// [textPrimary] without dropping all the way to [textSecondary].
  static const Color summaryQuietTitle = Color(0xFF3B4757);

  static const Color runTraceBorder = Color(0xFFDDE4F0);
  static const Color runTraceStatusSurface = OpenCrayColors.primaryTint;
  static const Color runTraceStatusText = OpenCrayColors.primaryPressed;
  static const Color runTraceActivityText = OpenCrayColors.textSecondary;
  static const Color runTraceDetailSurface = OpenCrayColors.surfaceSubtle;
  static const Color runTracePreviewSurface = OpenCrayColors.surfaceSubtle;
  static const Color runTracePreviewBorder = Color(0xFFDCE5F4);
  static const Color runTraceUrlText = OpenCrayColors.primary;
  static const Color runTraceInterruptSurface = Color(0xFFFCEFE8);
  static const Color runTraceInterruptBorder = Color(0xFFF0CFC0);
  static const Color runTraceInterruptAction = Color(0xFFC2491D);
  static const Color runTraceRetryMark = OpenCrayColors.warningMark;
  static const Color runTraceTabDivider = OpenCrayColors.divider;
  static const Color inspectorAction = OpenCrayColors.primary;
  static const Color inspectorTarget = Color(0xFF7C3AED);
  static const Color inspectorScope = OpenCrayColors.success;
  static const Color inspectorResult = OpenCrayColors.textSecondary;
  static const Color inspectorConnector = Color(0xFFCBD6EE);
  static const Color composerStroke = OpenCrayColors.outline;
  static const Color plusActiveSurface = OpenCrayColors.primaryTint;
  static const Color subtleSurface = OpenCrayColors.surfaceSubtle;
  static const Color todoCompletedFill = OpenCrayColors.textTertiary;
  static const Color selectionRowHighlight = OpenCrayColors.surfaceSunken;
  static const Color selectionControlBorder = OpenCrayColors.outline;

  /// Text selection tints — bright over the accent-filled outbound bubble,
  /// accent-tinted everywhere else.
  static const Color textSelectionOnAccent = Color(0x52FFFFFF);
  static const Color textSelectionOnSurface = Color(0x332563EB);

  /// Markdown links rendered on a dark bubble.
  static const Color linkOnDarkSurface = Color(0xFFDCEBFF);

  /// Modal barriers: previews dim to workbench ink, full-bleed images go
  /// darker so the photo owns the screen.
  static const Color previewBarrier = Color(0x8A0B0E14);
  static const Color imageBarrier = Color(0xB3000000);
}

/// Translucent chrome painted over blurred content — top bar, composer sheen,
/// message popover, approval card.
///
/// These stay literal rather than derived: they are tuned against whatever the
/// blur behind them produces, so an alpha computed off a solid token would
/// drift the moment the underlying surface changes.
class _ChatGlass {
  const _ChatGlass._();

  /// Top bar. Each `Rest`/`Active` pair is the scroll-driven lerp range.
  static const Color barTopRest = Color(0xA8FFFFFF);
  static const Color barTopActive = Color(0xE8FFFFFF);
  static const Color barMidRest = Color(0x70FFFFFF);
  static const Color barMidActive = Color(0xC2FFFFFF);
  static const Color barFootRest = Color(0x14F8FAFE);
  static const Color barFootActive = Color(0x54F8FAFE);
  static const Color barFootClear = Color(0x00F8FAFE);
  static const Color barBorderActive = Color(0x24DCE7F6);
  static const Color barShadowActive = Color(0x0A101828);

  /// Composer sheen behind the input row, plus its lift ink.
  static const Color composerHighlight = Color(0x73FFFFFF);
  static const Color composerSheen = Color(0x61F0F5FF);
  static const Color composerSheenClear = Color(0x00F0F5FF);
  static const Color composerShadowInk = Color(0xFF0D1B2A);

  /// Floating popovers and inline controls.
  static const Color popoverBorder = Color(0xCCFFFFFF);
  static const Color approvalShadow = Color(0x14101828);
  static const Color interruptThumbShadow = Color(0x1E0F172A);
}

/// Chat gradients that are deliberately tighter than [OpenCrayGradients.brand]
/// so the bubble keeps its own weight next to the send button.
class _ChatGradients {
  const _ChatGradients._();

  static const LinearGradient outboundBubble = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: <Color>[Color(0xFF3D7BF7), OpenCrayColors.primary],
  );
}

class _ChatTextStyles {
  const _ChatTextStyles._();

  /// Shared with the other tabs so every large-title header agrees on metrics.
  /// The chat header still animates this style (colour lerp on scroll).
  static const TextStyle pageTitle = OpenCrayTypography.pageTitle;

  static const TextStyle cardTitle = TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.2,
  );

  static const TextStyle bodyMuted = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w400,
    color: _ChatPalette.textSecondary,
  );

  static const TextStyle bubble = TextStyle(
    fontSize: 15,
    height: 1.4,
    fontWeight: FontWeight.w400,
  );

  static const TextStyle runTraceHeadline = TextStyle(
    fontSize: 15,
    height: 1.3,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle runTraceDetailLabel = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle runTraceDetailValue = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle runTraceFooter = TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textTertiary,
  );

  static const TextStyle runInspectorTitle = TextStyle(
    fontSize: 28,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.5,
  );

  static const TextStyle runInspectorLog = TextStyle(
    fontSize: 13,
    height: 1.4,
    fontWeight: FontWeight.w600,
  );

  static const TextStyle runInspectorDetail = TextStyle(
    fontSize: 12,
    height: 1.45,
    fontWeight: FontWeight.w500,
  );

  static const TextStyle runInspectorResult = TextStyle(
    fontSize: 12,
    height: 1.45,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.inspectorResult,
  );

  static const TextStyle runInspectorResultBranch = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.inspectorConnector,
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

  static const TextStyle toolbarStatus = TextStyle(
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

  static const TextStyle approvalRequest = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ChatPalette.textPrimary,
  );

  static const TextStyle approvalReason = TextStyle(
    fontSize: 12,
    height: 1.4,
    fontWeight: FontWeight.w500,
    color: _ChatPalette.textSecondary,
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
