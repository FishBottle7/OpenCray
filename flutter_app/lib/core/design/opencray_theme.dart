import 'package:flutter/material.dart';

import 'opencray_tokens.dart';

final class OpenCrayTheme {
  static ThemeData light() {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: OpenCrayColors.shellBackground,
      colorScheme: const ColorScheme.light(
        primary: OpenCrayColors.primary,
        onPrimary: OpenCrayColors.textOnPrimary,
        primaryContainer: OpenCrayColors.primaryTint,
        onPrimaryContainer: OpenCrayColors.primaryPressed,
        secondary: OpenCrayColors.textSecondary,
        surface: OpenCrayColors.surface,
        onSurface: OpenCrayColors.textPrimary,
        onSurfaceVariant: OpenCrayColors.textSecondary,
        outline: OpenCrayColors.outline,
        outlineVariant: OpenCrayColors.divider,
        error: OpenCrayColors.danger,
        surfaceTint: Colors.transparent,
      ),
    );

    const textTheme = TextTheme(
      headlineLarge: TextStyle(
        fontSize: 28,
        height: 34 / 28,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.6,
        color: OpenCrayColors.textPrimary,
      ),
      headlineMedium: TextStyle(
        fontSize: 20,
        height: 26 / 20,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.3,
        color: OpenCrayColors.textPrimary,
      ),
      titleMedium: TextStyle(
        fontSize: 17,
        height: 22 / 17,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.2,
        color: OpenCrayColors.textPrimary,
      ),
      bodyLarge: TextStyle(
        fontSize: 15,
        height: 22 / 15,
        fontWeight: FontWeight.w400,
        color: OpenCrayColors.textPrimary,
      ),
      bodyMedium: TextStyle(
        fontSize: 14,
        height: 20 / 14,
        fontWeight: FontWeight.w400,
        color: OpenCrayColors.textSecondary,
      ),
      labelLarge: TextStyle(
        fontSize: 15,
        height: 20 / 15,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.1,
        color: OpenCrayColors.textPrimary,
      ),
      labelMedium: TextStyle(
        fontSize: 13,
        height: 18 / 13,
        fontWeight: FontWeight.w500,
        color: OpenCrayColors.textSecondary,
      ),
      labelSmall: TextStyle(
        fontSize: 11,
        height: 14 / 11,
        fontWeight: FontWeight.w600,
        letterSpacing: 0.2,
        color: OpenCrayColors.textTertiary,
      ),
    );

    return base.copyWith(
      scaffoldBackgroundColor: OpenCrayColors.shellBackground,
      splashColor: Colors.transparent,
      highlightColor: OpenCrayColors.textPrimary.withValues(alpha: 0.04),
      dividerColor: OpenCrayColors.divider,
      textTheme: textTheme,
      appBarTheme: const AppBarTheme(
        backgroundColor: OpenCrayColors.shellBackground,
        foregroundColor: OpenCrayColors.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: OpenCrayColors.surface,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
          side: BorderSide(
            color: OpenCrayColors.divider.withValues(alpha: 0.9),
          ),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: OpenCrayColors.primary,
          foregroundColor: OpenCrayColors.textOnPrimary,
          disabledBackgroundColor: OpenCrayColors.surfaceSunken,
          disabledForegroundColor: OpenCrayColors.textTertiary,
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
          backgroundColor: OpenCrayColors.primary,
          foregroundColor: OpenCrayColors.textOnPrimary,
          disabledBackgroundColor: OpenCrayColors.surfaceSunken,
          disabledForegroundColor: OpenCrayColors.textTertiary,
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
          foregroundColor: OpenCrayColors.textPrimary,
          side: const BorderSide(color: OpenCrayColors.outline),
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
          foregroundColor: OpenCrayColors.primary,
          minimumSize: const Size(44, 40),
          padding: const EdgeInsets.symmetric(horizontal: 12),
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(OpenCrayRadii.sm),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: const WidgetStatePropertyAll(Colors.white),
        trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return OpenCrayColors.primary;
          }
          return OpenCrayColors.surfaceSunken;
        }),
      ),
      checkboxTheme: CheckboxThemeData(
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(6)),
        ),
        side: const BorderSide(color: OpenCrayColors.outline, width: 1.5),
        fillColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return OpenCrayColors.primary;
          }
          return Colors.transparent;
        }),
      ),
      radioTheme: RadioThemeData(
        fillColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return OpenCrayColors.primary;
          }
          return OpenCrayColors.outline;
        }),
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: OpenCrayColors.primary,
        inactiveTrackColor: OpenCrayColors.surfaceSunken,
        thumbColor: Colors.white,
        overlayColor: Color(0x142563EB),
        trackHeight: 4,
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: OpenCrayColors.primary,
        linearTrackColor: OpenCrayColors.surfaceSunken,
        circularTrackColor: Colors.transparent,
      ),
      dialogTheme: const DialogThemeData(
        backgroundColor: OpenCrayColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.xl),
        ),
        titleTextStyle: TextStyle(
          fontSize: 18,
          height: 24 / 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: OpenCrayColors.textPrimary,
        ),
        contentTextStyle: TextStyle(
          fontSize: 14.5,
          height: 21 / 14.5,
          color: OpenCrayColors.textSecondary,
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: OpenCrayColors.surface,
        surfaceTintColor: Colors.transparent,
        modalBackgroundColor: OpenCrayColors.surface,
        elevation: 0,
        modalElevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: OpenCrayRadii.sheet),
        ),
        showDragHandle: true,
        dragHandleColor: OpenCrayColors.outline,
        dragHandleSize: Size(36, 4),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: OpenCrayColors.inkSurface,
        contentTextStyle: const TextStyle(
          fontSize: 14,
          height: 20 / 14,
          color: Colors.white,
        ),
        actionTextColor: OpenCrayColors.brandSky,
        behavior: SnackBarBehavior.floating,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: OpenCrayColors.surfaceMuted,
        selectedColor: OpenCrayColors.primaryTint,
        side: BorderSide.none,
        labelStyle: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w500,
          color: OpenCrayColors.textSecondary,
        ),
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.pill),
        ),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: OpenCrayColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 3,
        shadowColor: const Color(0x1F101828),
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          side: BorderSide(
            color: OpenCrayColors.divider.withValues(alpha: 0.9),
          ),
        ),
        textStyle: const TextStyle(
          fontSize: 14.5,
          color: OpenCrayColors.textPrimary,
        ),
      ),
      tooltipTheme: TooltipThemeData(
        decoration: const BoxDecoration(
          color: OpenCrayColors.inkSurface,
          borderRadius: BorderRadius.all(OpenCrayRadii.sm),
        ),
        textStyle: const TextStyle(fontSize: 12.5, color: Colors.white),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: OpenCrayColors.surface,
        hintStyle: const TextStyle(
          color: OpenCrayColors.textTertiary,
          fontSize: 15,
          height: 22 / 15,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 12,
        ),
        enabledBorder: const OutlineInputBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: OpenCrayColors.divider),
        ),
        focusedBorder: const OutlineInputBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(
            color: OpenCrayColors.primary,
            width: 1.4,
          ),
        ),
        errorBorder: const OutlineInputBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: OpenCrayColors.danger),
        ),
        focusedErrorBorder: const OutlineInputBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.md),
          borderSide: BorderSide(color: OpenCrayColors.danger, width: 1.4),
        ),
      ),
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: OpenCrayColors.primary,
        selectionColor: OpenCrayColors.primary.withValues(alpha: 0.22),
        selectionHandleColor: OpenCrayColors.primary,
      ),
      listTileTheme: const ListTileThemeData(
        iconColor: OpenCrayColors.textSecondary,
        textColor: OpenCrayColors.textPrimary,
      ),
      dividerTheme: const DividerThemeData(
        color: OpenCrayColors.divider,
        thickness: 1,
        space: 1,
      ),
    );
  }

  const OpenCrayTheme._();
}
