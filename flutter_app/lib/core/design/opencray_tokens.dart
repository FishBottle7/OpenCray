import 'package:flutter/material.dart';

import 'opencray_palette.dart';

/// OpenCray design tokens — "Refined Workbench" language.
///
/// Brand anchor: the sky-blue crayfish claw. Blue stays the single accent
/// family; everything else is a cool ink-and-porcelain neutral ramp so agent
/// status colors (running / approval / danger) read instantly.
final class OpenCrayColors {
  // Brand
  static const brandSky = Color(0xFF52A4FF);
  static const primary = Color(0xFF2563EB);
  static const primaryPressed = Color(0xFF1D4FC8);
  static const primaryTint = Color(0xFFEAF1FE);
  static const primaryBorder = Color(0xFFC7DAFB);

  // Neutral surfaces
  static const shellBackground = Color(0xFFF4F6F9);
  static const surface = Colors.white;
  static const surfaceSubtle = Color(0xFFF7F9FC);
  static const surfaceMuted = Color(0xFFEDF0F5);
  static const surfaceSunken = Color(0xFFE8ECF2);
  static const surfaceAccent = primaryTint;
  static const divider = Color(0xFFE5E9F0);
  static const outline = Color(0xFFD5DCE6);

  // Ink ramp
  static const textPrimary = Color(0xFF101828);
  static const textSecondary = Color(0xFF5C6A7E);
  static const textTertiary = Color(0xFF95A0B1);
  static const textOnPrimary = Colors.white;
  static const scrim = Color(0x52101828);

  /// Lighter scrim for panels that slide in beside content (session drawer)
  /// rather than covering it.
  static const scrimSoft = Color(0x26101828);

  // Status
  static const success = Color(0xFF179457);
  static const successTint = Color(0xFFE7F6EE);
  static const successBorder = Color(0xFFBFE5D0);
  static const warning = Color(0xFFB45309);
  static const warningTint = Color(0xFFFBF1E0);
  static const warningBorder = Color(0xFFEFD9B3);

  /// Brighter amber for small status marks, where [warning] reads muddy at
  /// dot/glyph scale. Text keeps [warning] for contrast.
  static const warningMark = Color(0xFFF59E0B);
  static const danger = Color(0xFFD93B4E);
  static const dangerTint = Color(0xFFFCEDEF);
  static const dangerBorder = Color(0xFFF3C5CC);

  // Legacy aliases (older call sites; prefer the names above)
  static const dangerText = danger;
  static const dangerSurface = dangerTint;

  // Workbench technical surfaces
  static const codeSurface = Color(0xFFF5F7FA);
  static const inkSurface = Color(0xFF16202E);

  /// Ink that every drop shadow is tinted from.
  static const shadowInk = Color(0xFF101828);

  const OpenCrayColors._();
}

final class OpenCrayGradients {
  /// Hero-moment gradient (send button, user bubble). Use sparingly.
  static const brand = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [Color(0xFF4C97FF), Color(0xFF2563EB)],
  );

  const OpenCrayGradients._();
}

final class OpenCrayShadows {
  /// Hairline-bordered resting card.
  static const card = <BoxShadow>[
    BoxShadow(color: Color(0x0A101828), offset: Offset(0, 1), blurRadius: 2),
    BoxShadow(color: Color(0x07101828), offset: Offset(0, 2), blurRadius: 6),
  ];

  /// Elements floating above content (composer, sheets, popovers).
  static const floating = <BoxShadow>[
    BoxShadow(color: Color(0x0F101828), offset: Offset(0, 2), blurRadius: 8),
    BoxShadow(color: Color(0x14101828), offset: Offset(0, 8), blurRadius: 24),
  ];

  /// Coloured lift under a brand-gradient control (send, primary CTA) so it
  /// reads as the one hero affordance on the surface.
  static const brandGlow = <BoxShadow>[
    BoxShadow(color: Color(0x3D2563EB), offset: Offset(0, 3), blurRadius: 10),
  ];

  /// [brandGlow] scaled up for a full-width primary button.
  static const brandGlowLarge = <BoxShadow>[
    BoxShadow(color: Color(0x3D2563EB), offset: Offset(0, 4), blurRadius: 14),
  ];

  const OpenCrayShadows._();
}

final class OpenCraySpacing {
  static const xxs = 4.0;
  static const xs = 8.0;
  static const sm = 12.0;
  static const md = 16.0;
  static const lg = 20.0;
  static const xl = 24.0;
  static const xxl = 32.0;
  static const xxxl = 40.0;
  static const hero = 48.0;

  const OpenCraySpacing._();
}

final class OpenCrayRadii {
  static const sm = Radius.circular(8);
  static const md = Radius.circular(12);
  static const lg = Radius.circular(16);
  static const xl = Radius.circular(20);
  static const sheet = Radius.circular(24);
  static const bubble = Radius.circular(20);
  static const bubbleTail = Radius.circular(6);
  static const pill = Radius.circular(999);

  const OpenCrayRadii._();
}

final class OpenCraySizes {
  static const compactTopBarHeight = 56.0;
  static const bottomNavHeight = 54.0;
  static const bottomNavIconSize = 20.0;
  static const bottomNavItemTopPadding = 4.0;
  static const bottomNavItemBottomPadding = 6.0;
  static const bottomNavItemGap = 2.0;
  static const primaryButtonHeight = 52.0;
  static const iconButtonSize = 40.0;
  static const searchFieldHeight = 44.0;
  static const inputHeight = 50.0;
  static const sendButtonSize = 40.0;

  // Switch: the hit box keeps the Material switch footprint, the track is the
  // painted pill inside it.
  static const switchHitWidth = 52.0;
  static const switchHitHeight = 32.0;
  static const switchTrackWidth = 46.0;
  static const switchTrackHeight = 28.0;
  static const switchThumbSize = 22.0;

  const OpenCraySizes._();
}

/// Shared page-level typography. One definition for the large-title header so
/// the four tabs and every settings subpage agree on metrics.
/// See `docs/mobile-ui-layout-spec.md` — *Large-title page template*.
///
/// The styles take a palette rather than baking one in, so a header follows a
/// brightness change; the gaps below are brightness-blind and stay `const` so
/// they can still be constructor defaults.
final class OpenCrayTypography {
  static TextStyle pageEyebrow(OpenCrayPalette palette) => TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    letterSpacing: 1.1,
    color: palette.textTertiary,
  );

  static TextStyle pageTitle(OpenCrayPalette palette) => TextStyle(
    fontSize: 28,
    height: 1.12,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.6,
    color: palette.textPrimary,
  );

  static TextStyle pageSummary(OpenCrayPalette palette) =>
      TextStyle(fontSize: 14, height: 1.35, color: palette.textSecondary);

  /// Gap rhythm for the header block.
  static const double eyebrowGap = 8;
  static const double summaryGap = 6;
  static const double headerBottomGap = 20;

  const OpenCrayTypography._();
}
