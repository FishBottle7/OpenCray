part of 'skills_feature.dart';

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.copy,
    required this.page,
    required this.enabledCount,
    required this.installedCount,
    required this.suggestedCount,
  });

  final OpenCrayUiCopy copy;
  final SkillsPage page;
  final int enabledCount;
  final int installedCount;
  final int suggestedCount;

  @override
  Widget build(BuildContext context) {
    final title = page == SkillsPage.manage
        ? copy.skillsSummaryManageTitle
        : copy.skillsSummaryInstallTitle;
    final subtitle = page == SkillsPage.manage
        ? copy.skillsSummaryManageBody(enabledCount, installedCount)
        : copy.skillsSummaryInstallBody(suggestedCount);
    return SizedBox(
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: context.palette.surface,
          borderRadius: const BorderRadius.all(Radius.circular(16)),
          border: Border.all(color: context.palette.divider),
          boxShadow: context.palette.cardShadow,
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: TextStyle(
                  fontSize: 17,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                  letterSpacing: -0.2,
                  color: context.palette.textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: context.palette.textSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SegmentedControl extends StatelessWidget {
  const _SegmentedControl({
    required this.copy,
    required this.selectedPage,
    required this.onChanged,
  });

  final OpenCrayUiCopy copy;
  final SkillsPage selectedPage;
  final ValueChanged<SkillsPage> onChanged;

  @override
  Widget build(BuildContext context) {
    final bool installSelected = selectedPage == SkillsPage.install;
    return SizedBox(
      height: 36,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: context.palette.surfaceSunken,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: LayoutBuilder(
            builder: (context, constraints) {
              const double segmentGap = 4;
              final double indicatorWidth =
                  (constraints.maxWidth - segmentGap) / 2;
              return Stack(
                fit: StackFit.expand,
                children: [
                  AnimatedPositioned(
                    key: const ValueKey<String>('skills-segment-indicator'),
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.expand,
                    ),
                    curve: OpenCrayMotion.spatial,
                    left: installSelected ? indicatorWidth + segmentGap : 0,
                    top: 0,
                    bottom: 0,
                    width: indicatorWidth,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: context.palette.surface,
                        borderRadius: BorderRadius.circular(999),
                        boxShadow: context.palette.cardShadow,
                      ),
                    ),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: _SegmentButton(
                          key: const ValueKey<String>('skills-segment-manage'),
                          label: copy.skillsManageTab,
                          selected: selectedPage == SkillsPage.manage,
                          onTap: () => onChanged(SkillsPage.manage),
                        ),
                      ),
                      const SizedBox(width: segmentGap),
                      Expanded(
                        child: _SegmentButton(
                          key: const ValueKey<String>('skills-segment-install'),
                          label: copy.skillsInstallTab,
                          selected: installSelected,
                          onTap: () => onChanged(SkillsPage.install),
                        ),
                      ),
                    ],
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

class _SegmentButton extends StatelessWidget {
  const _SegmentButton({
    super.key,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: SizedBox(
        height: 28,
        child: Center(
          child: AnimatedDefaultTextStyle(
            duration: OpenCrayMotion.resolve(context, OpenCrayMotion.quick),
            curve: OpenCrayMotion.enter,
            style: TextStyle(
              fontSize: 14,
              height: 1.1,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
              color: selected
                  ? context.palette.textPrimary
                  : context.palette.textSecondary,
            ),
            child: Text(label),
          ),
        ),
      ),
    );
  }
}

class _SkillRow extends StatelessWidget {
  const _SkillRow({
    required this.skill,
    required this.copy,
    required this.lifecycleState,
    required this.onToggle,
    required this.onMore,
  });

  final OpenCrayInstalledSkillSnapshot skill;
  final OpenCrayUiCopy copy;
  final _InstalledSkillLifecycleState? lifecycleState;
  final ValueChanged<bool> onToggle;
  final VoidCallback onMore;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  skill.name,
                  style: TextStyle(
                    fontSize: 17,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.2,
                    color: context.palette.textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  skill.description,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.3,
                    color: context.palette.textTertiary,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                if (lifecycleState != null) ...[
                  const SizedBox(height: 8),
                  _InstalledSkillLifecyclePill(
                    skillId: skill.id,
                    state: lifecycleState!,
                    copy: copy,
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: context.palette.surfaceMuted,
              borderRadius: BorderRadius.circular(999),
            ),
            child: IconButton(
              padding: EdgeInsets.zero,
              splashRadius: 18,
              icon: const Icon(Icons.more_horiz_rounded, size: 16),
              color: context.palette.textTertiary,
              onPressed: lifecycleState == null ? onMore : null,
            ),
          ),
          const SizedBox(width: 12),
          _SkillToggle(
            value: skill.isEnabled,
            onChanged: lifecycleState == null ? onToggle : null,
          ),
        ],
      ),
    );
  }
}

class _InstalledSkillLifecyclePill extends StatelessWidget {
  const _InstalledSkillLifecyclePill({
    required this.skillId,
    required this.state,
    required this.copy,
  });

  final String skillId;
  final _InstalledSkillLifecycleState state;
  final OpenCrayUiCopy copy;

  @override
  Widget build(BuildContext context) {
    final bool isFailed = state == _InstalledSkillLifecycleState.failed;
    final bool isDone =
        state == _InstalledSkillLifecycleState.updated ||
        state == _InstalledSkillLifecycleState.deleted ||
        state == _InstalledSkillLifecycleState.enabled ||
        state == _InstalledSkillLifecycleState.disabled;
    final bool isPending =
        state == _InstalledSkillLifecycleState.updating ||
        state == _InstalledSkillLifecycleState.deleting ||
        state == _InstalledSkillLifecycleState.enabling ||
        state == _InstalledSkillLifecycleState.disabling;
    final Color textColor = isFailed
        ? context.palette.danger
        : isDone
        ? context.palette.success
        : context.palette.textSecondary;
    final Color surfaceColor = isFailed
        ? context.palette.dangerTint
        : isDone
        ? context.palette.successTint
        : context.palette.surfaceMuted;
    return AnimatedContainer(
      key: ValueKey<String>('skills-manage-lifecycle-$skillId-${state.name}'),
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: textColor.withValues(alpha: 0.14)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (isPending) ...[
            SizedBox.square(
              dimension: 12,
              child: CircularProgressIndicator(
                strokeWidth: 1.5,
                color: textColor,
              ),
            ),
            const SizedBox(width: 6),
          ],
          Text(
            _installedSkillLifecycleLabel(copy, state),
            style: TextStyle(
              fontSize: 12,
              height: 1.1,
              fontWeight: FontWeight.w700,
              color: textColor,
            ),
          ),
        ],
      ),
    );
  }
}

String _installedSkillLifecycleLabel(
  OpenCrayUiCopy copy,
  _InstalledSkillLifecycleState state,
) => switch (state) {
  _InstalledSkillLifecycleState.updating => copy.skillsUpdatingButton,
  _InstalledSkillLifecycleState.updated => copy.skillsUpdatedButton,
  _InstalledSkillLifecycleState.deleting => copy.skillsDeletingButton,
  _InstalledSkillLifecycleState.deleted => copy.skillsDeletedButton,
  _InstalledSkillLifecycleState.enabling => copy.skillsEnablingButton,
  _InstalledSkillLifecycleState.enabled => copy.skillsEnabledButton,
  _InstalledSkillLifecycleState.disabling => copy.skillsDisablingButton,
  _InstalledSkillLifecycleState.disabled => copy.skillsDisabledButton,
  _InstalledSkillLifecycleState.failed => copy.skillsActionFailedButton,
};

class _SkillToggle extends StatelessWidget {
  const _SkillToggle({required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return OpenCraySwitch(value: value, onChanged: onChanged);
  }
}

class _SourceRow extends StatelessWidget {
  const _SourceRow({required this.source, required this.onTap});

  final OpenCraySkillInstallSourceSnapshot source;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final titleColor = source.isAvailable
        ? context.palette.textPrimary
        : context.palette.textTertiary;
    final accentColor = source.isAvailable
        ? context.palette.primary
        : context.palette.textTertiary;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              Icons.add_circle_outline_rounded,
              color: accentColor,
              size: 20,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    source.title,
                    style: TextStyle(
                      fontSize: 16,
                      height: 1.2,
                      fontWeight: FontWeight.w600,
                      color: titleColor,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    source.subtitle,
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.3,
                      color: context.palette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Text(
              source.ctaLabel,
              style: TextStyle(
                fontSize: 14,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: accentColor,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SuggestedRow extends StatelessWidget {
  const _SuggestedRow({
    required this.item,
    required this.installsLabel,
    required this.previewLabel,
    required this.installLabel,
    required this.installingLabel,
    required this.installedLabel,
    required this.retryLabel,
    required this.installState,
    required this.onPreview,
    required this.onInstall,
  });

  final OpenCraySuggestedSkillSnapshot item;
  final String? installsLabel;
  final String previewLabel;
  final String installLabel;
  final String installingLabel;
  final String installedLabel;
  final String retryLabel;
  final _SkillInstallVisualState installState;
  final VoidCallback onPreview;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    final bool isInstalling =
        installState == _SkillInstallVisualState.installing;
    final bool isInstalled = installState == _SkillInstallVisualState.installed;
    final bool didFail = installState == _SkillInstallVisualState.failed;
    final String resolvedInstallLabel = switch (installState) {
      _SkillInstallVisualState.installing => installingLabel,
      _SkillInstallVisualState.installed => installedLabel,
      _SkillInstallVisualState.failed => retryLabel,
      _SkillInstallVisualState.idle => installLabel,
    };
    final Color installBackground = switch (installState) {
      _SkillInstallVisualState.installing => context.palette.surfaceMuted,
      _SkillInstallVisualState.installed => context.palette.successTint,
      _SkillInstallVisualState.failed => context.palette.dangerTint,
      _SkillInstallVisualState.idle => context.palette.primaryTint,
    };
    final Color installTextColor = switch (installState) {
      _SkillInstallVisualState.installing => context.palette.textSecondary,
      _SkillInstallVisualState.installed => context.palette.success,
      _SkillInstallVisualState.failed => context.palette.danger,
      _SkillInstallVisualState.idle => context.palette.primary,
    };
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 3,
                      ),
                      decoration: BoxDecoration(
                        color: context.palette.surfaceMuted,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        item.sourceLabel,
                        style: TextStyle(
                          fontSize: 11,
                          height: 1.1,
                          fontWeight: FontWeight.w600,
                          color: context.palette.textSecondary,
                        ),
                      ),
                    ),
                    if (installsLabel != null)
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 3,
                        ),
                        decoration: BoxDecoration(
                          color: context.palette.primaryTint,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          installsLabel!,
                          style: TextStyle(
                            fontSize: 11,
                            height: 1.1,
                            fontWeight: FontWeight.w600,
                            color: context.palette.primary,
                          ),
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  item.name,
                  style: TextStyle(
                    fontSize: 16,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    color: context.palette.textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  item.description,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.3,
                    color: context.palette.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              DecoratedBox(
                decoration: BoxDecoration(
                  color: context.palette.surfaceMuted,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: context.palette.divider),
                ),
                child: OpenCrayInkSurface(
                  borderRadius: BorderRadius.circular(999),
                  child: InkWell(
                    onTap: onPreview,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 6,
                      ),
                      child: Text(
                        previewLabel,
                        style: TextStyle(
                          fontSize: 12,
                          height: 1.1,
                          fontWeight: FontWeight.w600,
                          color: context.palette.textSecondary,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              AnimatedContainer(
                key: ValueKey<String>(
                  'skills-install-action-${item.sourceRef}-${installState.name}',
                ),
                duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
                curve: OpenCrayMotion.enter,
                decoration: BoxDecoration(
                  color: installBackground,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: didFail
                        ? context.palette.danger.withValues(alpha: 0.22)
                        : Colors.transparent,
                  ),
                ),
                child: OpenCrayInkSurface(
                  borderRadius: BorderRadius.circular(999),
                  child: InkWell(
                    onTap: isInstalling || isInstalled ? null : onInstall,
                    child: Container(
                      constraints: const BoxConstraints(minWidth: 76),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 6,
                      ),
                      child: AnimatedSwitcher(
                        duration: OpenCrayMotion.resolve(
                          context,
                          OpenCrayMotion.micro,
                        ),
                        switchInCurve: OpenCrayMotion.enter,
                        switchOutCurve: OpenCrayMotion.exit,
                        child: Text(
                          resolvedInstallLabel,
                          key: ValueKey<String>(
                            'skills-install-label-${item.sourceRef}-${installState.name}',
                          ),
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 12,
                            height: 1.1,
                            fontWeight: FontWeight.w600,
                            color: installTextColor,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _DirectInstallCard extends StatelessWidget {
  const _DirectInstallCard({
    required this.title,
    required this.body,
    required this.installLabel,
    required this.installingLabel,
    required this.installedLabel,
    required this.retryLabel,
    required this.installState,
    required this.onInstall,
  });

  final String title;
  final String body;
  final String installLabel;
  final String installingLabel;
  final String installedLabel;
  final String retryLabel;
  final _SkillInstallVisualState installState;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    final bool isInstalling =
        installState == _SkillInstallVisualState.installing;
    final bool isInstalled = installState == _SkillInstallVisualState.installed;
    final bool didFail = installState == _SkillInstallVisualState.failed;
    final String resolvedInstallLabel = switch (installState) {
      _SkillInstallVisualState.installing => installingLabel,
      _SkillInstallVisualState.installed => installedLabel,
      _SkillInstallVisualState.failed => retryLabel,
      _SkillInstallVisualState.idle => installLabel,
    };
    final Color installBackground = switch (installState) {
      _SkillInstallVisualState.installing => context.palette.surfaceMuted,
      _SkillInstallVisualState.installed => context.palette.successTint,
      _SkillInstallVisualState.failed => context.palette.dangerTint,
      _SkillInstallVisualState.idle => context.palette.primaryTint,
    };
    final Color installTextColor = switch (installState) {
      _SkillInstallVisualState.installing => context.palette.textSecondary,
      _SkillInstallVisualState.installed => context.palette.success,
      _SkillInstallVisualState.failed => context.palette.danger,
      _SkillInstallVisualState.idle => context.palette.primary,
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.surface,
        borderRadius: const BorderRadius.all(Radius.circular(16)),
        border: Border.all(color: context.palette.divider),
        boxShadow: context.palette.cardShadow,
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.bolt_rounded, color: context.palette.primary, size: 20),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 16,
                      height: 1.2,
                      fontWeight: FontWeight.w600,
                      color: context.palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    body,
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.3,
                      color: context.palette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            AnimatedContainer(
              key: ValueKey<String>(
                'skills-direct-install-action-${installState.name}',
              ),
              duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
              curve: OpenCrayMotion.enter,
              decoration: BoxDecoration(
                color: installBackground,
                borderRadius: BorderRadius.circular(999),
                border: Border.all(
                  color: didFail
                      ? context.palette.danger.withValues(alpha: 0.22)
                      : Colors.transparent,
                ),
              ),
              child: OpenCrayInkSurface(
                borderRadius: BorderRadius.circular(999),
                child: InkWell(
                  onTap: isInstalling || isInstalled ? null : onInstall,
                  child: Container(
                    constraints: const BoxConstraints(minWidth: 76),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    child: AnimatedSwitcher(
                      duration: OpenCrayMotion.resolve(
                        context,
                        OpenCrayMotion.micro,
                      ),
                      switchInCurve: OpenCrayMotion.enter,
                      switchOutCurve: OpenCrayMotion.exit,
                      child: Text(
                        resolvedInstallLabel,
                        key: ValueKey<String>(
                          'skills-direct-install-label-${installState.name}',
                        ),
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 12,
                          height: 1.1,
                          fontWeight: FontWeight.w600,
                          color: installTextColor,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.controller, required this.hintText});

  final TextEditingController controller;
  final String hintText;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.surface,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
        child: Row(
          children: [
            Icon(
              Icons.search_rounded,
              size: 18,
              color: context.palette.textTertiary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: TextField(
                controller: controller,
                decoration: openCrayBareInputDecoration.copyWith(
                  hintText: hintText,
                  hintStyle: TextStyle(
                    fontSize: 14,
                    height: 1.2,
                    color: context.palette.textTertiary,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ActionRow extends StatelessWidget {
  const _ActionRow({
    required this.icon,
    required this.label,
    required this.onTap,
    this.color,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  /// Defaults to the primary ink when omitted; resolved in [build] because the
  /// palette comes from the theme.
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final Color color = this.color ?? context.palette.textPrimary;
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(width: 12),
            Text(
              label,
              style: TextStyle(
                fontSize: 15,
                height: 1.2,
                fontWeight: FontWeight.w500,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LoadingCard extends StatelessWidget {
  const _LoadingCard({required this.copy, this.showsToggle = false});

  final OpenCrayUiCopy copy;

  /// Manage rows end in a switch, install rows do not; the placeholder keeps the
  /// difference so the row does not reflow when the real list arrives.
  final bool showsToggle;

  @override
  Widget build(BuildContext context) {
    return OpenCraySkeletonPulse(
      key: const ValueKey<String>('skills-state-loading'),
      semanticsLabel: copy.contentLoadingLabel,
      child: OpenCraySkeletonCard(
        dividerIndent: 16,
        rows: <Widget>[
          for (final double titleWidthFactor in const <double>[0.46, 0.62, 0.5])
            OpenCraySkeletonListRow(
              titleWidthFactor: titleWidthFactor,
              metaWidthFactor: 0.78,
              titleHeight: 16,
              metaHeight: 11,
              trailing: Row(
                children: <Widget>[
                  const OpenCraySkeletonBar(
                    width: 28,
                    height: 28,
                    radius: BorderRadius.all(OpenCrayRadii.pill),
                  ),
                  if (showsToggle) ...<Widget>[
                    const SizedBox(width: 12),
                    const OpenCraySkeletonBar(
                      width: OpenCraySizes.switchTrackWidth,
                      height: OpenCraySizes.switchTrackHeight,
                      radius: BorderRadius.all(OpenCrayRadii.pill),
                    ),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _EmptyCard extends StatelessWidget {
  const _EmptyCard({required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return OpenCrayStateCard(
      key: const ValueKey<String>('skills-state-empty'),
      leadingIcon: Icons.extension_outlined,
      title: title,
      body: body,
    );
  }
}
