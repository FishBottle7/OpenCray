import 'package:flutter/material.dart';

import '../../app/opencray_tabs.dart';
import '../copy/opencray_ui_copy.dart';
import '../models/opencray_shell_snapshot.dart';
import 'opencray_controls.dart';
import 'opencray_motion.dart';
import 'opencray_tokens.dart';

/// Large-title page header shared by the tabs and every settings subpage.
///
/// Owns the whole title block rhythm (eyebrow → title → summary → first
/// section) so pages stop hand-rolling slightly different gaps and metrics.
class OpenCrayPageHeader extends StatelessWidget {
  const OpenCrayPageHeader({
    super.key,
    this.leading,
    this.eyebrow,
    required this.title,
    this.summary,
    this.trailing,
    this.titleStyle,
    this.leadingGap = OpenCrayTypography.eyebrowGap,
    this.bottomGap = OpenCrayTypography.headerBottomGap,
  });

  /// Rendered above the eyebrow — typically a back affordance.
  final Widget? leading;
  final String? eyebrow;
  final String title;
  final String? summary;

  /// Rendered on the title baseline row, right aligned.
  final Widget? trailing;
  final TextStyle? titleStyle;
  final double leadingGap;
  final double bottomGap;

  @override
  Widget build(BuildContext context) {
    final bool hasEyebrow = eyebrow?.trim().isNotEmpty ?? false;
    final bool hasSummary = summary?.trim().isNotEmpty ?? false;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (leading != null) ...[leading!, SizedBox(height: leadingGap)],
        if (hasEyebrow) ...[
          Text(eyebrow!, style: OpenCrayTypography.pageEyebrow),
          const SizedBox(height: OpenCrayTypography.eyebrowGap),
        ],
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(
              child: Text(
                title,
                style: titleStyle ?? OpenCrayTypography.pageTitle,
              ),
            ),
            if (trailing != null) trailing!,
          ],
        ),
        if (hasSummary) ...[
          const SizedBox(height: OpenCrayTypography.summaryGap),
          Text(summary!, style: OpenCrayTypography.pageSummary),
        ],
        if (bottomGap > 0) SizedBox(height: bottomGap),
      ],
    );
  }
}

class OpenCrayCard extends StatelessWidget {
  const OpenCrayCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(OpenCraySpacing.md),
    this.backgroundColor = OpenCrayColors.surface,
    this.borderColor,
    this.showShadow = true,
  });

  final Widget child;
  final EdgeInsets padding;
  final Color backgroundColor;
  final Color? borderColor;
  final bool showShadow;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
        border: Border.all(color: borderColor ?? OpenCrayColors.divider),
        boxShadow: showShadow ? OpenCrayShadows.card : null,
      ),
      child: OpenCrayInkSurface(
        child: Padding(padding: padding, child: child),
      ),
    );
  }
}

enum OpenCrayStateTone { neutral, accent, success, danger }

class OpenCrayStateCard extends StatelessWidget {
  const OpenCrayStateCard({
    super.key,
    this.title,
    this.body,
    this.action,
    this.leadingIcon,
    this.isLoading = false,
    this.tone = OpenCrayStateTone.neutral,
    this.padding = const EdgeInsets.all(OpenCraySpacing.md),
  });

  final String? title;
  final String? body;
  final Widget? action;
  final IconData? leadingIcon;
  final bool isLoading;
  final OpenCrayStateTone tone;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final _OpenCrayStateToneColors colors = _stateToneColors(tone);
    final bool hasText =
        (title?.trim().isNotEmpty ?? false) ||
        (body?.trim().isNotEmpty ?? false);

    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      padding: padding,
      decoration: BoxDecoration(
        color: colors.background,
        borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
        border: Border.all(color: colors.border),
        boxShadow: OpenCrayShadows.card,
      ),
      child: hasText
          ? Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _OpenCrayStateLeading(
                  icon: leadingIcon,
                  isLoading: isLoading,
                  colors: colors,
                ),
                const SizedBox(width: OpenCraySpacing.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (title?.trim().isNotEmpty ?? false)
                        Text(
                          title!,
                          style: const TextStyle(
                            fontSize: 16,
                            height: 1.25,
                            fontWeight: FontWeight.w600,
                            letterSpacing: -0.2,
                            color: OpenCrayColors.textPrimary,
                          ),
                        ),
                      if ((title?.trim().isNotEmpty ?? false) &&
                          (body?.trim().isNotEmpty ?? false))
                        const SizedBox(height: OpenCraySpacing.xs),
                      if (body?.trim().isNotEmpty ?? false)
                        Text(
                          body!,
                          style: const TextStyle(
                            fontSize: 14,
                            height: 1.4,
                            color: OpenCrayColors.textSecondary,
                          ),
                        ),
                      if (action != null) ...[
                        const SizedBox(height: OpenCraySpacing.sm),
                        action!,
                      ],
                    ],
                  ),
                ),
              ],
            )
          : Center(
              child: _OpenCrayStateLeading(
                icon: leadingIcon,
                isLoading: isLoading,
                colors: colors,
              ),
            ),
    );
  }
}

class _OpenCrayStateLeading extends StatelessWidget {
  const _OpenCrayStateLeading({
    required this.icon,
    required this.isLoading,
    required this.colors,
  });

  final IconData? icon;
  final bool isLoading;
  final _OpenCrayStateToneColors colors;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: 32,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colors.markerBackground,
          borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        ),
        child: Center(
          child: isLoading
              ? SizedBox.square(
                  dimension: 15,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: colors.markerForeground,
                  ),
                )
              : Icon(
                  icon ?? Icons.info_outline,
                  size: 17,
                  color: colors.markerForeground,
                ),
        ),
      ),
    );
  }
}

class _OpenCrayStateToneColors {
  const _OpenCrayStateToneColors({
    required this.background,
    required this.border,
    required this.markerBackground,
    required this.markerForeground,
  });

  final Color background;
  final Color border;
  final Color markerBackground;
  final Color markerForeground;
}

_OpenCrayStateToneColors _stateToneColors(OpenCrayStateTone tone) {
  switch (tone) {
    case OpenCrayStateTone.neutral:
      return const _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.divider,
        markerBackground: OpenCrayColors.surfaceMuted,
        markerForeground: OpenCrayColors.textSecondary,
      );
    case OpenCrayStateTone.accent:
      return const _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.primaryBorder,
        markerBackground: OpenCrayColors.primaryTint,
        markerForeground: OpenCrayColors.primary,
      );
    case OpenCrayStateTone.success:
      return const _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.successBorder,
        markerBackground: OpenCrayColors.successTint,
        markerForeground: OpenCrayColors.success,
      );
    case OpenCrayStateTone.danger:
      return const _OpenCrayStateToneColors(
        background: OpenCrayColors.dangerTint,
        border: OpenCrayColors.dangerBorder,
        markerBackground: OpenCrayColors.surface,
        markerForeground: OpenCrayColors.danger,
      );
  }
}

class OpenCrayBottomNavigation extends StatelessWidget {
  const OpenCrayBottomNavigation({
    super.key,
    required this.snapshot,
    required this.selectedTab,
    required this.onTabSelected,
  });

  final OpenCrayShellSnapshot snapshot;
  final OpenCrayTab selectedTab;
  final ValueChanged<OpenCrayTab> onTabSelected;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: OpenCrayColors.surface,
        border: Border(top: BorderSide(color: OpenCrayColors.divider)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            OpenCraySpacing.sm,
            6,
            OpenCraySpacing.sm,
            8,
          ),
          child: SizedBox(
            height: OpenCraySizes.bottomNavHeight,
            child: Row(
              children: [
                for (final tab in OpenCrayTab.values)
                  Expanded(
                    child: _BottomNavItem(
                      snapshot: snapshot,
                      tab: tab,
                      selected: selectedTab == tab,
                      onTap: () => onTabSelected(tab),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _BottomNavItem extends StatelessWidget {
  const _BottomNavItem({
    required this.snapshot,
    required this.tab,
    required this.selected,
    required this.onTap,
  });

  final OpenCrayShellSnapshot snapshot;
  final OpenCrayTab tab;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
    final color = selected
        ? OpenCrayColors.primary
        : OpenCrayColors.textTertiary;
    final Color indicatorColor = selected
        ? OpenCrayColors.primaryTint
        : Colors.transparent;
    final Duration duration = OpenCrayMotion.resolve(
      context,
      OpenCrayMotion.micro,
    );
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: const BorderRadius.all(OpenCrayRadii.md),
        child: Padding(
          padding: const EdgeInsets.only(
            top: OpenCraySizes.bottomNavItemTopPadding,
            bottom: OpenCraySizes.bottomNavItemBottomPadding,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.start,
            children: [
              AnimatedContainer(
                duration: duration,
                curve: OpenCrayMotion.enter,
                width: 46,
                height: 26,
                decoration: BoxDecoration(
                  color: indicatorColor,
                  borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
                ),
                alignment: Alignment.center,
                child: Icon(
                  _iconFor(tab, selected: selected),
                  size: OpenCraySizes.bottomNavIconSize,
                  color: color,
                ),
              ),
              const SizedBox(height: OpenCraySizes.bottomNavItemGap),
              // The bar keeps a fixed height, so cap label growth instead of
              // letting a large system font clip the row.
              MediaQuery.withClampedTextScaling(
                maxScaleFactor: 1.2,
                child: AnimatedDefaultTextStyle(
                  duration: duration,
                  curve: OpenCrayMotion.enter,
                  style:
                      Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: color,
                        fontSize: 11,
                        height: 14 / 11,
                        letterSpacing: 0.1,
                        fontWeight: selected
                            ? FontWeight.w600
                            : FontWeight.w500,
                      ) ??
                      TextStyle(
                        color: color,
                        fontSize: 11,
                        height: 14 / 11,
                        letterSpacing: 0.1,
                        fontWeight: selected
                            ? FontWeight.w600
                            : FontWeight.w500,
                      ),
                  child: Text(copy.tabLabel(tab)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  IconData _iconFor(OpenCrayTab tab, {required bool selected}) {
    switch (tab) {
      case OpenCrayTab.chat:
        return selected
            ? Icons.chat_bubble_rounded
            : Icons.chat_bubble_outline_rounded;
      case OpenCrayTab.skills:
        return selected ? Icons.auto_awesome : Icons.auto_awesome_outlined;
      case OpenCrayTab.files:
        return selected ? Icons.folder_rounded : Icons.folder_outlined;
      case OpenCrayTab.settings:
        return Icons.tune_rounded;
    }
  }
}
