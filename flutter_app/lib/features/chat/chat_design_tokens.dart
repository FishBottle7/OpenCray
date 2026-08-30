part of 'chat_feature_screen.dart';

/// Chat-scoped tokens, resolved against the app palette so the thread follows a
/// brightness change. Reach them as `context.chatPalette`, `context.chatGlass`,
/// `context.chatGradients` and `context.chatText`.
extension _ChatTokensAccess on BuildContext {
  _ChatPalette get chatPalette => _ChatPalette(palette);

  _ChatGlass get chatGlass => _ChatGlass(palette);

  _ChatGradients get chatGradients => _ChatGradients(palette);

  _ChatTextStyles get chatText => _ChatTextStyles(_ChatPalette(palette));
}

/// Alpha as an 8-bit step, so values tuned as `0xAARRGGBB` stay byte-exact when
/// their base colour moves into the palette.
Color _chatAlpha(Color base, int step) => base.withValues(alpha: step / 0xFF);

class _ChatDecorations {
  const _ChatDecorations._();

  static BoxDecoration card(BuildContext context) {
    return BoxDecoration(
      color: context.palette.surface,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: context.chatPalette.border),
      boxShadow: context.palette.cardShadow,
    );
  }
}

class _ChatPalette {
  const _ChatPalette(this._base);

  final OpenCrayPalette _base;

  Color get background => _base.shellBackground;
  Color get accent => _base.primary;
  Color get highRiskAccent =>
      _base.isDark ? const Color(0xFFFF8A5C) : const Color(0xFFC2491D);
  Color get highRiskBorder =>
      _base.isDark ? const Color(0xFF5A3020) : const Color(0xFFF0CFC0);
  Color get highRiskBadgeSurface =>
      _base.isDark ? const Color(0xFF3A1F14) : const Color(0xFFFBE4D8);
  Color get highRiskSurface =>
      _base.isDark ? const Color(0xFF221510) : const Color(0xFFFDF6F2);
  Color get highRiskReasonText =>
      _base.isDark ? const Color(0xFFD3A183) : const Color(0xFF8A5A3B);
  Color get textPrimary => _base.textPrimary;
  Color get textSecondary => _base.textSecondary;
  Color get textTertiary => _base.textTertiary;
  Color get border => _base.divider;

  /// Zero-alpha counterparts used as the resting end of a fade. Derived from the
  /// solid token so the interpolated mid-tones stay on the same hue.
  Color get surfaceClear => _chatAlpha(_base.surface, 0);
  Color get borderClear => _chatAlpha(_base.divider, 0);

  /// Summary card title once a thread is active — a step quieter than
  /// [textPrimary] without dropping all the way to [textSecondary].
  Color get summaryQuietTitle =>
      _base.isDark ? const Color(0xFFC7D2E2) : const Color(0xFF3B4757);

  Color get runTraceBorder =>
      _base.isDark ? const Color(0xFF2A3546) : const Color(0xFFDDE4F0);
  Color get runTraceStatusSurface => _base.primaryTint;
  Color get runTraceStatusText => _base.primaryPressed;
  Color get runTraceActivityText => _base.textSecondary;
  Color get runTraceDetailSurface => _base.surfaceSubtle;
  Color get runTracePreviewSurface => _base.surfaceSubtle;
  Color get runTracePreviewBorder =>
      _base.isDark ? const Color(0xFF2B384B) : const Color(0xFFDCE5F4);
  Color get runTraceUrlText => _base.primary;
  Color get runTraceInterruptSurface =>
      _base.isDark ? const Color(0xFF261813) : const Color(0xFFFCEFE8);
  Color get runTraceInterruptBorder =>
      _base.isDark ? const Color(0xFF5A3020) : const Color(0xFFF0CFC0);
  Color get runTraceInterruptAction =>
      _base.isDark ? const Color(0xFFFF8A5C) : const Color(0xFFC2491D);
  Color get runTraceRetryMark => _base.warningMark;
  Color get runTraceTabDivider => _base.divider;
  Color get inspectorAction => _base.primary;
  Color get inspectorTarget =>
      _base.isDark ? const Color(0xFFB292FF) : const Color(0xFF7C3AED);
  Color get inspectorScope => _base.success;
  Color get inspectorResult => _base.textSecondary;
  Color get inspectorConnector =>
      _base.isDark ? const Color(0xFF4C5C77) : const Color(0xFFCBD6EE);
  Color get composerStroke => _base.outline;
  Color get plusActiveSurface => _base.primaryTint;
  Color get subtleSurface => _base.surfaceSubtle;
  Color get todoCompletedFill => _base.textTertiary;
  Color get selectionRowHighlight => _base.surfaceSunken;
  Color get selectionControlBorder => _base.outline;

  /// Text selection tints — bright over the accent-filled outbound bubble,
  /// accent-tinted everywhere else.
  Color get textSelectionOnAccent => _chatAlpha(_base.textOnPrimary, 0x52);
  Color get textSelectionOnSurface => _chatAlpha(_base.primary, 0x33);

  /// Markdown links rendered on a dark bubble.
  Color get linkOnDarkSurface => const Color(0xFFDCEBFF);

  /// Modal barriers: previews dim to workbench ink, full-bleed images go
  /// darker so the photo owns the screen.
  Color get previewBarrier =>
      _base.isDark ? const Color(0xB305080C) : const Color(0x8A0B0E14);
  Color get imageBarrier => const Color(0xB3000000);
}

/// Translucent chrome painted over blurred content — top bar, composer sheen,
/// message popover, approval card.
///
/// The alphas stay literal: they are tuned against whatever the blur behind them
/// produces. Where the tinted base is exactly a palette colour it is named as
/// one (so a brightness swap carries the glass with it); the few off-white tints
/// the layout was measured against remain literals.
class _ChatGlass {
  const _ChatGlass(this._base);

  final OpenCrayPalette _base;

  /// Top bar. Each `Rest`/`Active` pair is the scroll-driven lerp range.
  Color get barTopRest => _chatAlpha(_base.surface, 0xA8);
  Color get barTopActive => _chatAlpha(_base.surface, 0xE8);
  Color get barMidRest => _chatAlpha(_base.surface, 0x70);
  Color get barMidActive => _chatAlpha(_base.surface, 0xC2);
  Color get barFootRest =>
      _base.isDark ? const Color(0x14243244) : const Color(0x14F8FAFE);
  Color get barFootActive =>
      _base.isDark ? const Color(0x54243244) : const Color(0x54F8FAFE);
  Color get barFootClear =>
      _base.isDark ? const Color(0x00243244) : const Color(0x00F8FAFE);
  Color get barBorderActive =>
      _base.isDark ? const Color(0x3D2A3546) : const Color(0x24DCE7F6);
  Color get barShadowActive => _chatAlpha(_base.shadowInk, 0x0A);

  /// Composer sheen behind the input row, plus its lift ink.
  Color get composerHighlight => _chatAlpha(_base.surface, 0x73);
  Color get composerSheen =>
      _base.isDark ? const Color(0x4D1E2A3C) : const Color(0x61F0F5FF);
  Color get composerSheenClear =>
      _base.isDark ? const Color(0x001E2A3C) : const Color(0x00F0F5FF);
  Color get composerShadowInk =>
      _base.isDark ? const Color(0xFF000000) : const Color(0xFF0D1B2A);

  /// Floating popovers and inline controls.
  Color get popoverBorder => _chatAlpha(_base.surface, 0xCC);
  Color get approvalShadow => _chatAlpha(_base.shadowInk, 0x14);
  Color get interruptThumbShadow =>
      _base.isDark ? const Color(0x52000000) : const Color(0x1E0F172A);
}

/// Chat gradients that are deliberately tighter than the shared brand gradient
/// so the bubble keeps its own weight next to the send button.
class _ChatGradients {
  const _ChatGradients(this._base);

  final OpenCrayPalette _base;

  LinearGradient get outboundBubble => LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: <Color>[const Color(0xFF3D7BF7), _base.primary],
  );
}

/// Chat type ramp. Instance-based so the ink follows the palette; reach it as
/// `context.chatText`.
class _ChatTextStyles {
  const _ChatTextStyles(this._ink);

  final _ChatPalette _ink;

  TextStyle get cardTitle => TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
    letterSpacing: -0.2,
  );

  TextStyle get bodyMuted => TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w400,
    color: _ink.textSecondary,
  );

  TextStyle get bubble =>
      const TextStyle(fontSize: 15, height: 1.4, fontWeight: FontWeight.w400);

  TextStyle get runTraceHeadline => TextStyle(
    fontSize: 15,
    height: 1.3,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get runTraceDetailLabel => TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get runTraceDetailValue => TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get runTraceFooter => TextStyle(
    fontSize: 12,
    height: 1.35,
    fontWeight: FontWeight.w500,
    color: _ink.textTertiary,
  );

  TextStyle get runInspectorTitle => TextStyle(
    fontSize: 28,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ink.textPrimary,
    letterSpacing: -0.5,
  );

  TextStyle get runInspectorLog =>
      const TextStyle(fontSize: 13, height: 1.4, fontWeight: FontWeight.w600);

  TextStyle get runInspectorDetail =>
      const TextStyle(fontSize: 12, height: 1.45, fontWeight: FontWeight.w500);

  TextStyle get runInspectorResult => TextStyle(
    fontSize: 12,
    height: 1.45,
    fontWeight: FontWeight.w500,
    color: _ink.inspectorResult,
  );

  TextStyle get runInspectorResultBranch => TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ink.inspectorConnector,
  );

  TextStyle get messageMenuLabel => const TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    letterSpacing: -0.1,
  );

  TextStyle get timeline => TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get todoLabel => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w600,
    letterSpacing: 0.5,
    color: _ink.textSecondary,
  );

  TextStyle get todoItem => TextStyle(
    fontSize: 14,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ink.textPrimary,
  );

  TextStyle get toolbarButton => TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get toolbarStatus => TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ink.accent,
  );

  TextStyle get selectionToolbarAction => TextStyle(
    fontSize: 16,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ink.accent,
  );

  TextStyle get selectionToolbarTitle => TextStyle(
    fontSize: 16,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
    letterSpacing: -0.2,
  );

  TextStyle get selectionAction =>
      const TextStyle(fontSize: 14, height: 1.15, fontWeight: FontWeight.w600);

  TextStyle get summaryBadge => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get highRiskBadge => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ink.highRiskAccent,
  );

  TextStyle get placeholder => TextStyle(
    fontSize: 15,
    height: 1.2,
    fontWeight: FontWeight.w400,
    color: _ink.textTertiary,
  );

  TextStyle get sectionLabel => TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w700,
    color: _ink.textSecondary,
  );

  TextStyle get commandsLabel => TextStyle(
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ink.textSecondary,
  );

  TextStyle get addAction => TextStyle(
    fontSize: 13,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get approvalAction =>
      const TextStyle(fontSize: 13, height: 1.1, fontWeight: FontWeight.w700);

  TextStyle get approvalRequest => TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get approvalReason => TextStyle(
    fontSize: 12,
    height: 1.4,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get attachmentLabel => TextStyle(
    fontSize: 13,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get attachmentDetail => TextStyle(
    fontSize: 11,
    height: 1.2,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get commandTitle => TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get commandDescription => TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ink.textSecondary,
  );

  TextStyle get drawerEyebrow => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: _ink.textSecondary,
  );

  TextStyle get drawerTitle => TextStyle(
    fontSize: 24,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get drawerCta => const TextStyle(
    fontSize: 14,
    height: 1.1,
    fontWeight: FontWeight.w700,
    color: Colors.white,
  );

  TextStyle get sessionTitle => TextStyle(
    fontSize: 14,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: _ink.textPrimary,
  );

  TextStyle get sessionMeta => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w500,
    color: _ink.textSecondary,
  );

  TextStyle get sessionPreview => TextStyle(
    fontSize: 12,
    height: 1.3,
    fontWeight: FontWeight.w400,
    color: _ink.textSecondary,
  );
}
