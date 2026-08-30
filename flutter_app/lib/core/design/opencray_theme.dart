import 'package:flutter/material.dart';

import 'opencray_palette.dart';
import 'opencray_tokens.dart';

final class OpenCrayTheme {
  static ThemeData light() => of(OpenCrayPalette.light, Brightness.light);

  static ThemeData dark() => of(OpenCrayPalette.dark, Brightness.dark);

  /// Builds the whole Material theme out of [palette], so every themed widget
  /// (buttons, inputs, sheets, snack bars) follows a brightness swap without a
  /// second set of definitions.
  static ThemeData of(OpenCrayPalette palette, Brightness brightness) {
    final base = ThemeData(
      useMaterial3: true,
      brightness: brightness,
      scaffoldBackgroundColor: palette.shellBackground,
      colorScheme:
          (brightness == Brightness.dark
                  ? const ColorScheme.dark()
                  : const ColorScheme.light())
              .copyWith(
                primary: palette.primary,
                onPrimary: palette.textOnPrimary,
                primaryContainer: palette.primaryTint,
                onPrimaryContainer: palette.primaryPressed,
                secondary: palette.textSecondary,
                surface: palette.surface,
                onSurface: palette.textPrimary,
                onSurfaceVariant: palette.textSecondary,
                outline: palette.outline,
                outlineVariant: palette.divider,
                error: palette.danger,
                surfaceTint: Colors.transparent,
              ),
    );

    final textTheme = TextTheme(
      headlineLarge: TextStyle(
        fontSize: 28,
        height: 34 / 28,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.6,
        color: palette.textPrimary,
      ),
      headlineMedium: TextStyle(
        fontSize: 20,
        height: 26 / 20,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.3,
        color: palette.textPrimary,
      ),
      titleMedium: TextStyle(
        fontSize: 17,
        height: 22 / 17,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.2,
        color: palette.textPrimary,
      ),
      bodyLarge: TextStyle(
        fontSize: 15,
        height: 22 / 15,
        fontWeight: FontWeight.w400,
        color: palette.textPrimary,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        height: 20 / 14,
        fontWeight: FontWeight.w400,
        color: palette.textSecondary,
      ),
      labelLarge: TextStyle(
        fontSize: 15,
        height: 20 / 15,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.1,
        color: palette.textPrimary,
      ),
      labelMedium: TextStyle(
        fontSize: 13,
        height: 18 / 13,
        fontWeight: FontWeight.w500,
        color: palette.textSecondary,
      ),
      labelSmall: TextStyle(
        fontSize: 11,
        height: 14 / 11,
        fontWeight: FontWeight.w600,
        letterSpacing: 0.2,
        color: palette.textTertiary,
      ),
    );

    return base.copyWith(
      extensions: <ThemeExtension<dynamic>>[palette],
      scaffoldBackgroundColor: palette.shellBackground,
      // Restrained ripple: enough feedback to feel responsive, quiet enough to
      // keep the workbench surfaces calm.
      splashFactory: InkRipple.splashFactory,
      splashColor: palette.primary.withValues(alpha: 0.07),
      highlightColor: palette.textPrimary.withValues(alpha: 0.04),
      dividerColor: palette.divider,
      textTheme: textTheme,
      appBarTheme: AppBarTheme(
        backgroundColor: palette.shellBackground,
        foregroundColor: palette.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: palette.surface,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
          side: BorderSide(color: palette.divider.withValues(alpha: 0.9)),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: palette.primary,
          foregroundColor: palette.textOnPrimary,
          disabledBackgroundColor: palette.surfaceSunken,
          disabledForegroundColor: palette.textTertiary,
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(horizontal: 18),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(OpenCrayRadii.md),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          elevation: 0,
          backgroundColor: palette.primary,
          foregroundColor: palette.textOnPrimary,
          disabledBackgroundColor: palette.surfaceSunken,
          disabledForegroundColor: palette.textTertiary,
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(horizontal: 18),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(OpenCrayRadii.md),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: palette.textPrimary,
          side: BorderSide(color: palette.outline),
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(horizontal: 18),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(OpenCrayRadii.md),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: palette.primary,
          minimumSize: const Size(44, 40),
          padding: const EdgeInsets.symmetric(horizontal: 12),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(OpenCrayRadii.sm),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      switchTheme: SwitchThemeData(
        // A thumb is content on its track, so it follows controlThumb rather
        // than the surface ramp — same rule as OpenCraySwitch.
        thumbColor: WidgetStatePropertyAll(palette.controlThumb),
        trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return palette.primary;
          }
          return palette.surfaceSunken;
        }),
      ),
      checkboxTheme: CheckboxThemeData(
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(6)),
        ),
        side: BorderSide(color: palette.outline, width: 1.5),
        fillColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return palette.primary;
          }
          return Colors.transparent;
        }),
      ),
      radioTheme: RadioThemeData(
        fillColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return palette.primary;
          }
          return palette.outline;
        }),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: palette.primary,
        inactiveTrackColor: palette.surfaceSunken,
        thumbColor: Colors.white,
        overlayColor: palette.primary.withValues(alpha: 0.08),
        trackHeight: 4,
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: palette.primary,
        linearTrackColor: palette.surfaceSunken,
        circularTrackColor: Colors.transparent,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: palette.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.xl),
        ),
        titleTextStyle: TextStyle(
          fontSize: 18,
          height: 24 / 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: palette.textPrimary,
        ),
        contentTextStyle: TextStyle(
          fontSize: 14.5,
          height: 21 / 14.5,
          color: palette.textSecondary,
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: palette.surface,
        surfaceTintColor: Colors.transparent,
        modalBackgroundColor: palette.surface,
        elevation: 0,
        modalElevation: 0,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: OpenCrayRadii.sheet),
        ),
        showDragHandle: true,
        dragHandleColor: palette.outline,
        dragHandleSize: const Size(36, 4),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: palette.inkSurface,
        contentTextStyle: const TextStyle(
          fontSize: 14,
          height: 20 / 14,
          color: Colors.white,
        ),
        actionTextColor: palette.brandSky,
        behavior: SnackBarBehavior.floating,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: palette.surfaceMuted,
        selectedColor: palette.primaryTint,
        side: BorderSide.none,
        labelStyle: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w500,
          color: palette.textSecondary,
        ),
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.pill),
        ),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: palette.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 3,
        shadowColor: palette.textPrimary.withValues(alpha: 0.12),
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          side: BorderSide(color: palette.divider.withValues(alpha: 0.9)),
        ),
        textStyle: TextStyle(fontSize: 14.5, color: palette.textPrimary),
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: palette.inkSurface,
          borderRadius: const BorderRadius.all(OpenCrayRadii.sm),
        ),
        textStyle: const TextStyle(fontSize: 12.5, color: Colors.white),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: palette.surface,
        hintStyle: TextStyle(
          color: palette.textTertiary,
          fontSize: 15,
          height: 22 / 15,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 12,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: palette.divider),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: palette.primary, width: 1.4),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: palette.danger),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: palette.danger, width: 1.4),
        ),
      ),
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: palette.primary,
        selectionColor: palette.primary.withValues(alpha: 0.22),
        selectionHandleColor: palette.primary,
      ),
      listTileTheme: ListTileThemeData(
        iconColor: palette.textSecondary,
        textColor: palette.textPrimary,
      ),
      dividerTheme: DividerThemeData(
        color: palette.divider,
        thickness: 1,
        space: 1,
      ),
    );
  }

  const OpenCrayTheme._();
}
