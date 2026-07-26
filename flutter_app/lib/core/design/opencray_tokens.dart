import 'package:flutter/material.dart';

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

  // Status
  static const success = Color(0xFF179457);
  static const successTint = Color(0xFFE7F6EE);
  static const successBorder = Color(0xFFBFE5D0);
  static const warning = Color(0xFFB45309);
  static const warningTint = Color(0xFFFBF1E0);
  static const warningBorder = Color(0xFFEFD9B3);
  static const danger = Color(0xFFD93B4E);
  static const dangerTint = Color(0xFFFCEDEF);
  static const dangerBorder = Color(0xFFF3C5CC);

  // Legacy aliases (older call sites; prefer the names above)
  static const dangerText = danger;
  static const dangerSurface = dangerTint;

  // Workbench technical surfaces
  static const codeSurface = Color(0xFFF5F7FA);
  static const inkSurface = Color(0xFF16202E);

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

  const OpenCraySizes._();
}
