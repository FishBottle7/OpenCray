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
        surface: OpenCrayColors.surface,
        onSurface: OpenCrayColors.textPrimary,
      ),
    );

    return base.copyWith(
      scaffoldBackgroundColor: OpenCrayColors.shellBackground,
      splashColor: Colors.transparent,
      highlightColor: Colors.transparent,
      dividerColor: OpenCrayColors.divider,
      textTheme: const TextTheme(
        headlineLarge: TextStyle(
          fontSize: 28,
          height: 34 / 28,
          fontWeight: FontWeight.w600,
          color: OpenCrayColors.textPrimary,
        ),
        headlineMedium: TextStyle(
          fontSize: 20,
          height: 26 / 20,
          fontWeight: FontWeight.w600,
          color: OpenCrayColors.textPrimary,
        ),
        titleMedium: TextStyle(
          fontSize: 17,
          height: 22 / 17,
          fontWeight: FontWeight.w500,
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
          fontWeight: FontWeight.w500,
          color: OpenCrayColors.textPrimary,
        ),
        labelMedium: TextStyle(
          fontSize: 13,
          height: 18 / 13,
          fontWeight: FontWeight.w500,
          color: OpenCrayColors.textSecondary,
        ),
      ),
      cardTheme: const CardThemeData(
        elevation: 0,
        color: OpenCrayColors.surface,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(OpenCrayRadii.lg),
        ),
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
        enabledBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: const BorderSide(color: Colors.transparent),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
          borderSide: const BorderSide(color: OpenCrayColors.primary, width: 1),
        ),
      ),
    );
  }

  const OpenCrayTheme._();
}
