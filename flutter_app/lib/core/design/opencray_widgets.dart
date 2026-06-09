import 'package:flutter/material.dart';

import '../../app/opencray_tabs.dart';
import '../copy/opencray_ui_copy.dart';
import '../models/opencray_shell_snapshot.dart';
import 'opencray_motion.dart';
import 'opencray_tokens.dart';

typedef OpenCrayPageContentBuilder =
    List<Widget> Function(BuildContext context);

class OpenCrayTopBar extends StatelessWidget {
  const OpenCrayTopBar({super.key, this.leading, this.trailing});

  final Widget? leading;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: OpenCraySizes.compactTopBarHeight,
      child: Row(
        children: [
          if (leading != null) leading!,
          const Spacer(),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

class OpenCrayPageTemplate extends StatelessWidget {
  const OpenCrayPageTemplate({
    super.key,
    this.eyebrow,
    required this.title,
    required this.subtitle,
    this.topBarLeading,
    this.topBarTrailing,
    required this.children,
  });

  final String? eyebrow;
  final String title;
  final String subtitle;
  final Widget? topBarLeading;
  final Widget? topBarTrailing;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return SafeArea(
      bottom: false,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: OpenCraySpacing.lg),
            child: OpenCrayTopBar(
              leading: topBarLeading,
              trailing: topBarTrailing,
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(
                OpenCraySpacing.lg,
                OpenCraySpacing.sm,
                OpenCraySpacing.lg,
                OpenCraySpacing.xxl,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (eyebrow != null)
                    Text(
                      eyebrow!,
                      style: textTheme.labelMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  if (eyebrow != null)
                    const SizedBox(height: OpenCraySpacing.xs),
                  Text(title, style: textTheme.headlineLarge),
                  const SizedBox(height: OpenCraySpacing.xs),
                  Text(subtitle, style: textTheme.bodyMedium),
                  const SizedBox(height: OpenCraySpacing.lg),
                  ...children,
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class OpenCrayCard extends StatelessWidget {
  const OpenCrayCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(OpenCraySpacing.md),
    this.backgroundColor = OpenCrayColors.surface,
  });

  final Widget child;
  final EdgeInsets padding;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: const BorderRadius.all(OpenCrayRadii.lg),
      ),
      child: Padding(padding: padding, child: child),
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
                            height: 1.2,
                            fontWeight: FontWeight.w700,
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
                            height: 1.35,
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
      return _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.divider.withValues(alpha: 0.7),
        markerBackground: OpenCrayColors.surfaceMuted,
        markerForeground: OpenCrayColors.textSecondary,
      );
    case OpenCrayStateTone.accent:
      return _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.primary.withValues(alpha: 0.18),
        markerBackground: OpenCrayColors.surfaceAccent,
        markerForeground: OpenCrayColors.primary,
      );
    case OpenCrayStateTone.success:
      return _OpenCrayStateToneColors(
        background: OpenCrayColors.surface,
        border: OpenCrayColors.success.withValues(alpha: 0.2),
        markerBackground: const Color(0xFFEAF7EF),
        markerForeground: const Color(0xFF248A3D),
      );
    case OpenCrayStateTone.danger:
      return _OpenCrayStateToneColors(
        background: OpenCrayColors.dangerSurface,
        border: OpenCrayColors.dangerText.withValues(alpha: 0.18),
        markerBackground: OpenCrayColors.surface,
        markerForeground: OpenCrayColors.dangerText,
      );
  }
}

class OpenCrayPillButton extends StatelessWidget {
  const OpenCrayPillButton({super.key, required this.label, this.icon});

  final String label;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: OpenCrayColors.surface,
        borderRadius: BorderRadius.all(OpenCrayRadii.pill),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null)
              Icon(icon, size: 15, color: OpenCrayColors.textSecondary),
            if (icon != null) const SizedBox(width: 6),
            Text(label),
          ],
        ),
      ),
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

class OpenCrayShellHeader extends StatelessWidget {
  const OpenCrayShellHeader({super.key, required this.snapshot});

  final OpenCrayShellSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context).textTheme;
    return OpenCrayCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(snapshot.hostLabel, style: theme.labelMedium),
          const SizedBox(height: OpenCraySpacing.xs),
          Text(snapshot.hostSummary, style: theme.bodyMedium),
        ],
      ),
    );
  }
}

class OpenCrayTabPlaceholder extends StatelessWidget {
  const OpenCrayTabPlaceholder({
    super.key,
    this.eyebrow,
    required this.title,
    required this.subtitle,
    required this.items,
    this.topBarLeading,
    this.topBarTrailing,
  });

  final String? eyebrow;
  final String title;
  final String subtitle;
  final List<Widget> items;
  final Widget? topBarLeading;
  final Widget? topBarTrailing;

  @override
  Widget build(BuildContext context) {
    return OpenCrayPageTemplate(
      eyebrow: eyebrow,
      title: title,
      subtitle: subtitle,
      topBarLeading: topBarLeading,
      topBarTrailing: topBarTrailing,
      children: items,
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
        : OpenCrayColors.textSecondary;
    final Color indicatorColor = selected
        ? OpenCrayColors.surfaceAccent
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
                width: 42,
                height: 24,
                decoration: BoxDecoration(
                  color: indicatorColor,
                  borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
                ),
                alignment: Alignment.center,
                child: Icon(
                  _iconFor(tab),
                  size: OpenCraySizes.bottomNavIconSize,
                  color: color,
                ),
              ),
              const SizedBox(height: OpenCraySizes.bottomNavItemGap),
              AnimatedDefaultTextStyle(
                duration: duration,
                curve: OpenCrayMotion.enter,
                style:
                    Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: color,
                      fontSize: 10,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                    ) ??
                    TextStyle(
                      color: color,
                      fontSize: 10,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                    ),
                child: Text(copy.tabLabel(tab).toUpperCase()),
              ),
            ],
          ),
        ),
      ),
    );
  }

  IconData _iconFor(OpenCrayTab tab) {
    switch (tab) {
      case OpenCrayTab.chat:
        return Icons.chat_bubble_outline_rounded;
      case OpenCrayTab.skills:
        return Icons.auto_awesome_outlined;
      case OpenCrayTab.files:
        return Icons.folder_outlined;
      case OpenCrayTab.settings:
        return Icons.tune_outlined;
    }
  }
}
