import 'package:flutter/material.dart';

import 'opencray_tokens.dart';

/// Semantic colour and elevation surface for the whole app, carried on
/// [ThemeData.extensions] so a single brightness change reaches every widget.
///
/// Read it as `context.palette`. [OpenCrayColors] and friends stay as the light
/// literals, and [light] is defined in terms of them on purpose: while call
/// sites migrate, every colour keeps exactly one definition, so the light theme
/// cannot drift from what shipped.
@immutable
final class OpenCrayPalette extends ThemeExtension<OpenCrayPalette> {
  const OpenCrayPalette({
    required this.brightness,
    required this.brandSky,
    required this.primary,
    required this.primaryPressed,
    required this.primaryTint,
    required this.primaryBorder,
    required this.shellBackground,
    required this.surface,
    required this.surfaceSubtle,
    required this.surfaceMuted,
    required this.surfaceSunken,
    required this.divider,
    required this.outline,
    required this.textPrimary,
    required this.textSecondary,
    required this.textTertiary,
    required this.textOnPrimary,
    required this.scrim,
    required this.scrimSoft,
    required this.success,
    required this.successTint,
    required this.successBorder,
    required this.warning,
    required this.warningTint,
    required this.warningBorder,
    required this.warningMark,
    required this.danger,
    required this.dangerTint,
    required this.dangerBorder,
    required this.codeSurface,
    required this.inkSurface,
    required this.controlThumb,
    required this.controlThumbDisabled,
    required this.shadowInk,
    required this.brandGradient,
    required this.cardShadow,
    required this.floatingShadow,
    required this.brandGlow,
    required this.brandGlowLarge,
  });

  /// The shipped light values, byte-identical to the static tokens.
  static const OpenCrayPalette light = OpenCrayPalette(
    brightness: Brightness.light,
    brandSky: OpenCrayColors.brandSky,
    primary: OpenCrayColors.primary,
    primaryPressed: OpenCrayColors.primaryPressed,
    primaryTint: OpenCrayColors.primaryTint,
    primaryBorder: OpenCrayColors.primaryBorder,
    shellBackground: OpenCrayColors.shellBackground,
    surface: OpenCrayColors.surface,
    surfaceSubtle: OpenCrayColors.surfaceSubtle,
    surfaceMuted: OpenCrayColors.surfaceMuted,
    surfaceSunken: OpenCrayColors.surfaceSunken,
    divider: OpenCrayColors.divider,
    outline: OpenCrayColors.outline,
    textPrimary: OpenCrayColors.textPrimary,
    textSecondary: OpenCrayColors.textSecondary,
    textTertiary: OpenCrayColors.textTertiary,
    textOnPrimary: OpenCrayColors.textOnPrimary,
    scrim: OpenCrayColors.scrim,
    scrimSoft: OpenCrayColors.scrimSoft,
    success: OpenCrayColors.success,
    successTint: OpenCrayColors.successTint,
    successBorder: OpenCrayColors.successBorder,
    warning: OpenCrayColors.warning,
    warningTint: OpenCrayColors.warningTint,
    warningBorder: OpenCrayColors.warningBorder,
    warningMark: OpenCrayColors.warningMark,
    danger: OpenCrayColors.danger,
    dangerTint: OpenCrayColors.dangerTint,
    dangerBorder: OpenCrayColors.dangerBorder,
    codeSurface: OpenCrayColors.codeSurface,
    inkSurface: OpenCrayColors.inkSurface,
    controlThumb: OpenCrayColors.surface,
    controlThumbDisabled: OpenCrayColors.surfaceSubtle,
    shadowInk: OpenCrayColors.shadowInk,
    brandGradient: OpenCrayGradients.brand,
    cardShadow: OpenCrayShadows.card,
    floatingShadow: OpenCrayShadows.floating,
    brandGlow: OpenCrayShadows.brandGlow,
    brandGlowLarge: OpenCrayShadows.brandGlowLarge,
  );

  /// The dark ramp. A cool slate rather than black: the workbench keeps its
  /// blue-grey cast, and the existing [OpenCrayColors.inkSurface] navy is the
  /// hue anchor. Four deliberate inversions from [light]:
  ///
  /// * **Status colours brighten.** The light ramp's `success` / `warning` /
  ///   `danger` sit near 4.5:1 on porcelain and collapse on slate, so each moves
  ///   up in luminance and its tint/border pair becomes a deep, low-chroma wash.
  /// * **Elevation moves to borders.** [cardShadow] is empty — a drop shadow is
  ///   invisible on a dark surface — and [divider] carries the card edge instead.
  ///   Only genuinely floating chrome keeps a shadow.
  /// * **[inkSurface] gets lighter, not darker.** Snack bars and tooltips have to
  ///   read as *above* the page, which on a dark page means raised in luminance.
  /// * **[controlThumb] stays near-white.** A switch thumb is content on its
  ///   track and spends half its life on the [primary] fill, so it does not
  ///   follow [surface] down; a slate thumb on a blue track reads as a hole.
  ///
  /// [primary] stays close to the light accent on purpose: ~40 call sites paint
  /// white content on an accent fill (send button, outbound bubble, approval
  /// keys), so the accent has to keep white legible while still standing off the
  /// page. `0xFF3D7BF7` — already the chat bubble's gradient head — holds 3.9:1
  /// under white and 4.8:1 as accent text on [shellBackground]; a brighter blue
  /// would win the second at the cost of the first.
  static const OpenCrayPalette dark = OpenCrayPalette(
    brightness: Brightness.dark,
    brandSky: Color(0xFF6FB4FF),
    primary: Color(0xFF3D7BF7),
    primaryPressed: Color(0xFF5A93FF),
    primaryTint: Color(0xFF1B2942),
    primaryBorder: Color(0xFF2E4670),
    shellBackground: Color(0xFF0E141C),
    surface: Color(0xFF161E29),
    surfaceSubtle: Color(0xFF1B2431),
    surfaceMuted: Color(0xFF212C3B),
    surfaceSunken: Color(0xFF0A1017),
    divider: Color(0xFF2A3646),
    outline: Color(0xFF3A4859),
    textPrimary: Color(0xFFE9EEF6),
    textSecondary: Color(0xFFA3B1C6),
    textTertiary: Color(0xFF6E7D92),
    textOnPrimary: Colors.white,
    scrim: Color(0xA3060A10),
    scrimSoft: Color(0x52060A10),
    success: Color(0xFF3DD68C),
    successTint: Color(0xFF10291E),
    successBorder: Color(0xFF1E4A36),
    warning: Color(0xFFF0A93A),
    warningTint: Color(0xFF2E2210),
    warningBorder: Color(0xFF56411C),
    warningMark: Color(0xFFFFB92E),
    danger: Color(0xFFFF6B7D),
    dangerTint: Color(0xFF33161C),
    dangerBorder: Color(0xFF5E2731),
    codeSurface: Color(0xFF121A24),
    inkSurface: Color(0xFF202B39),
    controlThumb: Color(0xFFEDF2FA),
    controlThumbDisabled: Color(0xFF4A5769),
    shadowInk: Color(0xFF000000),
    brandGradient: LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: <Color>[Color(0xFF4C8DF6), Color(0xFF3D7BF7)],
    ),
    cardShadow: <BoxShadow>[],
    floatingShadow: <BoxShadow>[
      BoxShadow(color: Color(0x66000000), offset: Offset(0, 4), blurRadius: 16),
    ],
    brandGlow: <BoxShadow>[
      BoxShadow(color: Color(0x4D3D7BF7), offset: Offset(0, 3), blurRadius: 10),
    ],
    brandGlowLarge: <BoxShadow>[
      BoxShadow(color: Color(0x4D3D7BF7), offset: Offset(0, 4), blurRadius: 14),
    ],
  );

  /// Which end of the ramp this palette sits on. Features that must pick a
  /// different literal per brightness (chat's glass and risk tones) branch on
  /// [isDark] rather than re-deriving it from a colour.
  final Brightness brightness;

  bool get isDark => brightness == Brightness.dark;

  // Brand
  final Color brandSky;
  final Color primary;
  final Color primaryPressed;
  final Color primaryTint;
  final Color primaryBorder;

  // Neutral surfaces
  final Color shellBackground;
  final Color surface;
  final Color surfaceSubtle;
  final Color surfaceMuted;
  final Color surfaceSunken;
  final Color divider;
  final Color outline;

  // Ink ramp
  final Color textPrimary;
  final Color textSecondary;
  final Color textTertiary;
  final Color textOnPrimary;
  final Color scrim;
  final Color scrimSoft;

  // Status
  final Color success;
  final Color successTint;
  final Color successBorder;
  final Color warning;
  final Color warningTint;
  final Color warningBorder;
  final Color warningMark;
  final Color danger;
  final Color dangerTint;
  final Color dangerBorder;

  // Workbench technical surfaces
  final Color codeSurface;
  final Color inkSurface;

  /// Fill of a raised control knob — the switch thumb and any similar puck that
  /// has to read as sitting *on top of* its track. White in light mode; near-white
  /// rather than [surface] in dark, because the knob spends half its life on the
  /// [primary] fill where a slate fill reads as a hole punched in the track.
  final Color controlThumb;

  /// [controlThumb] for a disabled control: dim enough to read as inert, still
  /// separated from the muted track behind it.
  final Color controlThumbDisabled;

  /// Ink every drop shadow is tinted from. Separate from [textPrimary] so a
  /// dark theme can drop shadows without dragging body copy with it.
  final Color shadowInk;

  // Elevation and hero fills. Dark mode leans on borders over shadows, so these
  // travel with the palette rather than sitting in a brightness-blind constant.
  final LinearGradient brandGradient;
  final List<BoxShadow> cardShadow;
  final List<BoxShadow> floatingShadow;
  final List<BoxShadow> brandGlow;
  final List<BoxShadow> brandGlowLarge;

  @override
  OpenCrayPalette copyWith({
    Brightness? brightness,
    Color? brandSky,
    Color? primary,
    Color? primaryPressed,
    Color? primaryTint,
    Color? primaryBorder,
    Color? shellBackground,
    Color? surface,
    Color? surfaceSubtle,
    Color? surfaceMuted,
    Color? surfaceSunken,
    Color? divider,
    Color? outline,
    Color? textPrimary,
    Color? textSecondary,
    Color? textTertiary,
    Color? textOnPrimary,
    Color? scrim,
    Color? scrimSoft,
    Color? success,
    Color? successTint,
    Color? successBorder,
    Color? warning,
    Color? warningTint,
    Color? warningBorder,
    Color? warningMark,
    Color? danger,
    Color? dangerTint,
    Color? dangerBorder,
    Color? codeSurface,
    Color? inkSurface,
    Color? controlThumb,
    Color? controlThumbDisabled,
    Color? shadowInk,
    LinearGradient? brandGradient,
    List<BoxShadow>? cardShadow,
    List<BoxShadow>? floatingShadow,
    List<BoxShadow>? brandGlow,
    List<BoxShadow>? brandGlowLarge,
  }) {
    return OpenCrayPalette(
      brightness: brightness ?? this.brightness,
      brandSky: brandSky ?? this.brandSky,
      primary: primary ?? this.primary,
      primaryPressed: primaryPressed ?? this.primaryPressed,
      primaryTint: primaryTint ?? this.primaryTint,
      primaryBorder: primaryBorder ?? this.primaryBorder,
      shellBackground: shellBackground ?? this.shellBackground,
      surface: surface ?? this.surface,
      surfaceSubtle: surfaceSubtle ?? this.surfaceSubtle,
      surfaceMuted: surfaceMuted ?? this.surfaceMuted,
      surfaceSunken: surfaceSunken ?? this.surfaceSunken,
      divider: divider ?? this.divider,
      outline: outline ?? this.outline,
      textPrimary: textPrimary ?? this.textPrimary,
      textSecondary: textSecondary ?? this.textSecondary,
      textTertiary: textTertiary ?? this.textTertiary,
      textOnPrimary: textOnPrimary ?? this.textOnPrimary,
      scrim: scrim ?? this.scrim,
      scrimSoft: scrimSoft ?? this.scrimSoft,
      success: success ?? this.success,
      successTint: successTint ?? this.successTint,
      successBorder: successBorder ?? this.successBorder,
      warning: warning ?? this.warning,
      warningTint: warningTint ?? this.warningTint,
      warningBorder: warningBorder ?? this.warningBorder,
      warningMark: warningMark ?? this.warningMark,
      danger: danger ?? this.danger,
      dangerTint: dangerTint ?? this.dangerTint,
      dangerBorder: dangerBorder ?? this.dangerBorder,
      codeSurface: codeSurface ?? this.codeSurface,
      inkSurface: inkSurface ?? this.inkSurface,
      controlThumb: controlThumb ?? this.controlThumb,
      controlThumbDisabled: controlThumbDisabled ?? this.controlThumbDisabled,
      shadowInk: shadowInk ?? this.shadowInk,
      brandGradient: brandGradient ?? this.brandGradient,
      cardShadow: cardShadow ?? this.cardShadow,
      floatingShadow: floatingShadow ?? this.floatingShadow,
      brandGlow: brandGlow ?? this.brandGlow,
      brandGlowLarge: brandGlowLarge ?? this.brandGlowLarge,
    );
  }

  @override
  OpenCrayPalette lerp(OpenCrayPalette? other, double t) {
    if (other == null) {
      return this;
    }
    Color mix(Color a, Color b) => Color.lerp(a, b, t) ?? b;
    List<BoxShadow> mixShadow(List<BoxShadow> a, List<BoxShadow> b) =>
        BoxShadow.lerpList(a, b, t) ?? b;
    return OpenCrayPalette(
      brightness: t < 0.5 ? brightness : other.brightness,
      brandSky: mix(brandSky, other.brandSky),
      primary: mix(primary, other.primary),
      primaryPressed: mix(primaryPressed, other.primaryPressed),
      primaryTint: mix(primaryTint, other.primaryTint),
      primaryBorder: mix(primaryBorder, other.primaryBorder),
      shellBackground: mix(shellBackground, other.shellBackground),
      surface: mix(surface, other.surface),
      surfaceSubtle: mix(surfaceSubtle, other.surfaceSubtle),
      surfaceMuted: mix(surfaceMuted, other.surfaceMuted),
      surfaceSunken: mix(surfaceSunken, other.surfaceSunken),
      divider: mix(divider, other.divider),
      outline: mix(outline, other.outline),
      textPrimary: mix(textPrimary, other.textPrimary),
      textSecondary: mix(textSecondary, other.textSecondary),
      textTertiary: mix(textTertiary, other.textTertiary),
      textOnPrimary: mix(textOnPrimary, other.textOnPrimary),
      scrim: mix(scrim, other.scrim),
      scrimSoft: mix(scrimSoft, other.scrimSoft),
      success: mix(success, other.success),
      successTint: mix(successTint, other.successTint),
      successBorder: mix(successBorder, other.successBorder),
      warning: mix(warning, other.warning),
      warningTint: mix(warningTint, other.warningTint),
      warningBorder: mix(warningBorder, other.warningBorder),
      warningMark: mix(warningMark, other.warningMark),
      danger: mix(danger, other.danger),
      dangerTint: mix(dangerTint, other.dangerTint),
      dangerBorder: mix(dangerBorder, other.dangerBorder),
      codeSurface: mix(codeSurface, other.codeSurface),
      inkSurface: mix(inkSurface, other.inkSurface),
      controlThumb: mix(controlThumb, other.controlThumb),
      controlThumbDisabled: mix(
        controlThumbDisabled,
        other.controlThumbDisabled,
      ),
      shadowInk: mix(shadowInk, other.shadowInk),
      brandGradient:
          LinearGradient.lerp(brandGradient, other.brandGradient, t) ??
          other.brandGradient,
      cardShadow: mixShadow(cardShadow, other.cardShadow),
      floatingShadow: mixShadow(floatingShadow, other.floatingShadow),
      brandGlow: mixShadow(brandGlow, other.brandGlow),
      brandGlowLarge: mixShadow(brandGlowLarge, other.brandGlowLarge),
    );
  }
}

/// `context.palette` — the app's semantic colours for this subtree.
///
/// Falls back to [OpenCrayPalette.light] when the extension is absent (a widget
/// pumped under a bare [ThemeData]), so a partially themed tree still paints the
/// shipped light values instead of Material defaults.
extension OpenCrayPaletteAccess on BuildContext {
  OpenCrayPalette get palette =>
      Theme.of(this).extension<OpenCrayPalette>() ?? OpenCrayPalette.light;
}
