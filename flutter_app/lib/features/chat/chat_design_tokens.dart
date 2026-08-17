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
  static const Color textPrimary = OpenCrayColors.textPrimary;
  static const Color textSecondary = OpenCrayColors.textSecondary;
  static const Color textTertiary = OpenCrayColors.textTertiary;
  static const Color border = OpenCrayColors.divider;
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
}

class _ChatTextStyles {
  const _ChatTextStyles._();

  static const TextStyle pageTitle = TextStyle(
    fontSize: 30,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ChatPalette.textPrimary,
    letterSpacing: -0.6,
  );

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
