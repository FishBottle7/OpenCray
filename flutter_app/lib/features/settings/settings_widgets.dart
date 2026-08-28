part of 'settings_feature.dart';

class _SettingsLoadErrorCard extends StatelessWidget {
  const _SettingsLoadErrorCard({
    required this.title,
    required this.message,
    required this.onBack,
    required this.backLabel,
    required this.onRetry,
  });

  final String title;
  final String message;
  final VoidCallback onBack;
  final String backLabel;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(onTap: onBack, label: backLabel),
            title: title,
          ),
          OpenCrayStateCard(
            key: const ValueKey<String>('settings-state-error-card'),
            tone: OpenCrayStateTone.danger,
            leadingIcon: Icons.error_outline,
            title: 'Unable to load this page',
            body: message,
            action: _HeaderActionChip(label: 'Retry', onTap: onRetry),
          ),
        ],
      ),
    );
  }
}

class _SettingsStatusPill extends StatelessWidget {
  const _SettingsStatusPill({required this.label, required this.tone});

  final String label;
  final String tone;

  @override
  Widget build(BuildContext context) {
    final Color backgroundColor;
    final Color textColor;
    switch (tone) {
      case 'active':
      case 'positive':
      case 'success':
        backgroundColor = OpenCrayColors.successTint;
        textColor = OpenCrayColors.success;
        break;
      case 'warning':
      case 'attention':
      case 'caution':
        backgroundColor = OpenCrayColors.warningTint;
        textColor = OpenCrayColors.warning;
        break;
      case 'danger':
      case 'blocked':
        backgroundColor = OpenCrayColors.dangerTint;
        textColor = OpenCrayColors.dangerText;
        break;
      default:
        backgroundColor = OpenCrayColors.surfaceMuted;
        textColor = OpenCrayColors.textSecondary;
        break;
    }
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(
          label,
          style: _SettingsTextStyles.valueChip.copyWith(color: textColor),
        ),
      ),
    );
  }
}

class _BackLink extends StatelessWidget {
  const _BackLink({required this.onTap, required this.label});

  final VoidCallback onTap;
  final String label;

  @override
  Widget build(BuildContext context) {
    final bool hasLabel = label.trim().isNotEmpty;
    return Align(
      alignment: Alignment.centerLeft,
      child: OpenCrayInkSurface(
        borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
        child: InkWell(
          borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
          onTap: onTap,
          child: Padding(
            padding: EdgeInsets.fromLTRB(4, 6, hasLabel ? 12 : 4, 6),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                const SizedBox.square(
                  dimension: 24,
                  child: Center(
                    child: Icon(
                      Icons.arrow_back_ios_new_rounded,
                      size: 15,
                      color: OpenCrayColors.primary,
                    ),
                  ),
                ),
                if (hasLabel) ...[
                  const SizedBox(width: 2),
                  Text(label, style: _SettingsTextStyles.actionChip),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingsPickerRow extends StatelessWidget {
  const _SettingsPickerRow({
    required this.title,
    required this.value,
    required this.onTap,
    this.isBusy = false,
  });

  final String title;
  final String value;
  final VoidCallback? onTap;
  final bool isBusy;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            if (isBusy) ...[
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: OpenCrayColors.primary,
                ),
              ),
              const SizedBox(width: 10),
            ],
            DecoratedBox(
              decoration: BoxDecoration(
                color: OpenCrayColors.surfaceMuted,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                child: Text(value, style: _SettingsTextStyles.valueChip),
              ),
            ),
            const SizedBox(width: 6),
            const Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: OpenCrayColors.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeSelectionRow extends StatelessWidget {
  const _PrototypeSelectionRow({
    required this.title,
    this.trailingLabel,
    this.onTap,
    this.compact = false,
  });

  final String title;
  final String? trailingLabel;
  final VoidCallback? onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return _PrototypeFieldSurface(
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          onTap: onTap,
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: compact ? 44 : 52),
            child: Padding(
              padding: EdgeInsets.fromLTRB(12, compact ? 12 : 14, 8, compact ? 12 : 14),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        title,
                        style: _SettingsTextStyles.fieldValue,
                        strutStyle: _SettingsTextStyles.fieldValueStrut,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ),
                  if (trailingLabel != null) ...[
                    Text(
                      trailingLabel!,
                      style: _SettingsTextStyles.selectionMeta,
                    ),
                    const SizedBox(width: 2),
                  ],
                  const Icon(
                    Icons.chevron_right_rounded,
                    size: 20,
                    color: OpenCrayColors.textTertiary,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _InteractiveSegmentedSelector extends StatelessWidget {
  const _InteractiveSegmentedSelector({
    required this.labels,
    required this.selectedId,
    required this.labelBuilder,
    required this.onSelected,
  });

  final List<String> labels;
  final String selectedId;
  final String Function(String value) labelBuilder;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return OpenCraySegmentedControl(
      labels: labels.map(labelBuilder).toList(growable: false),
      selectedIndex: labels.indexOf(selectedId),
      textStyle: _SettingsTextStyles.valueChip,
      onSelected: (index) => onSelected(labels[index]),
    );
  }
}

class _InlineTextAction extends StatelessWidget {
  const _InlineTextAction({
    required this.label,
    required this.onTap,
    this.color = OpenCrayColors.primary,
  });

  final String label;
  final VoidCallback? onTap;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Text(
          label,
          style: _SettingsTextStyles.inlineAction.copyWith(color: color),
        ),
      ),
    );
  }
}

class _FieldClearButton extends StatelessWidget {
  const _FieldClearButton({required this.onTap, this.buttonKey});

  final VoidCallback onTap;
  final Key? buttonKey;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(right: 4),
      child: Tooltip(
        message: 'Clear',
        child: InkWell(
          key: buttonKey,
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: const SizedBox(
            width: 28,
            height: 28,
            child: Icon(
              Icons.close_rounded,
              size: 18,
              color: OpenCrayColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _PrototypeFieldSurface extends StatelessWidget {
  const _PrototypeFieldSurface({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSubtle,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: OpenCrayColors.divider),
      ),
      child: child,
    );
  }
}

class _PrototypeField extends StatelessWidget {
  const _PrototypeField({
    required this.label,
    required this.controller,
    this.focusNode,
    required this.hintText,
    this.enabled = true,
    this.obscureText = false,
    this.minLines = 1,
    this.maxLines = 1,
    this.trailing,
    this.onChanged,
    this.keyboardType,
  });

  final String label;
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String hintText;
  final bool enabled;
  final bool obscureText;
  final int minLines;
  final int maxLines;
  final Widget? trailing;
  final ValueChanged<String>? onChanged;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) {
    final TextInputType resolvedKeyboardType =
        keyboardType ??
        (obscureText
            ? TextInputType.visiblePassword
            : (maxLines == 1 ? TextInputType.text : TextInputType.multiline));
    final bool usesPrivateIme =
        obscureText || resolvedKeyboardType == TextInputType.visiblePassword;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _PrototypeFieldSurface(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: minLines == 1 ? 44 : 44),
            child: Row(
              crossAxisAlignment: minLines == 1
                  ? CrossAxisAlignment.center
                  : CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    focusNode: focusNode,
                    enabled: enabled,
                    onChanged: onChanged,
                    obscureText: obscureText,
                    autocorrect: false,
                    enableSuggestions: !usesPrivateIme,
                    enableIMEPersonalizedLearning: !usesPrivateIme,
                    spellCheckConfiguration:
                        const SpellCheckConfiguration.disabled(),
                    smartDashesType: SmartDashesType.disabled,
                    smartQuotesType: SmartQuotesType.disabled,
                    keyboardType: resolvedKeyboardType,
                    minLines: minLines,
                    maxLines: maxLines,
                    style: _SettingsTextStyles.fieldValue.copyWith(
                      fontWeight: obscureText ? FontWeight.w500 : null,
                    ),
                    strutStyle: _SettingsTextStyles.fieldValueStrut,
                    decoration: InputDecoration(
                      hintText: hintText,
                      hintStyle: _SettingsTextStyles.fieldValue.copyWith(
                        color: OpenCrayColors.textTertiary,
                        fontWeight: FontWeight.w400,
                      ),
                      filled: true,
                      fillColor: Colors.transparent,
                      isCollapsed: true,
                      contentPadding: EdgeInsets.fromLTRB(
                        12,
                        minLines == 1 ? 14 : 12,
                        12,
                        minLines == 1 ? 14 : 12,
                      ),
                      border: InputBorder.none,
                    ),
                  ),
                ),
                if (trailing != null) ...[const SizedBox(width: 8), trailing!],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _PrototypeSelectionField extends StatelessWidget {
  const _PrototypeSelectionField({
    required this.label,
    required this.title,
    this.trailingLabel,
    this.onTap,
  });

  final String label;
  final String title;
  final String? trailingLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _SettingsTextStyles.fieldLabel),
        const SizedBox(height: 6),
        _PrototypeSelectionRow(
          title: title,
          trailingLabel: trailingLabel,
          compact: true,
          onTap: onTap,
        ),
      ],
    );
  }
}

class _HeaderActionChip extends StatelessWidget {
  const _HeaderActionChip({required this.label, required this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final textStyle = Theme.of(context).textTheme.labelMedium?.copyWith(
      fontSize: 12,
      height: 16 / 12,
      fontWeight: FontWeight.w600,
      letterSpacing: 0,
      color: OpenCrayColors.primary,
    );
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.primaryTint,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: OpenCrayColors.primaryBorder),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
            child: Text(
              label,
              style: textStyle ?? _SettingsTextStyles.actionChip,
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({
    required this.child,
    this.backgroundColor = Colors.white,
    this.borderColor = OpenCrayColors.divider,
  });

  final Widget child;
  final Color backgroundColor;
  final Color borderColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: borderColor),
        boxShadow: OpenCrayShadows.card,
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(16),
        child: Padding(padding: const EdgeInsets.all(16), child: child),
      ),
    );
  }
}

class _DeviceSummaryCard extends StatelessWidget {
  const _DeviceSummaryCard({required this.title, required this.summary});

  final String title;
  final String summary;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: OpenCrayGradients.brand,
              borderRadius: BorderRadius.all(OpenCrayRadii.md),
            ),
            child: SizedBox.square(
              dimension: 38,
              child: Center(
                child: Icon(
                  Icons.hub_rounded,
                  size: 19,
                  color: OpenCrayColors.textOnPrimary,
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 4),
                Text(summary, style: _SettingsTextStyles.body),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingsEntryGroupCard extends StatelessWidget {
  const _SettingsEntryGroupCard({
    required this.entries,
    required this.onOpenPage,
  });

  final List<SettingsHomeEntrySnapshot> entries;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: OpenCrayColors.divider),
        boxShadow: OpenCrayShadows.card,
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (int index = 0; index < entries.length; index++) ...[
              _HomeEntryRow(
                title: entries[index].title,
                icon: _iconForSettingsPage(entries[index].page),
                horizontalPadding: 16,
                onTap: () => onOpenPage(entries[index].page),
              ),
              if (index < entries.length - 1)
                const Padding(
                  padding: EdgeInsets.only(left: 56),
                  child: Divider(height: 1, color: OpenCrayColors.divider),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _HomeEntryRow extends StatelessWidget {
  const _HomeEntryRow({
    required this.title,
    required this.onTap,
    this.icon,
    this.horizontalPadding = 0,
    this.selected = false,
  });

  final String title;
  final IconData? icon;
  final double horizontalPadding;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final Color tint = selected
        ? OpenCrayColors.primary
        : OpenCrayColors.textPrimary;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: horizontalPadding,
          vertical: 14,
        ),
        child: Row(
          children: [
            if (icon != null) ...[
              DecoratedBox(
                decoration: BoxDecoration(
                  color: selected
                      ? OpenCrayColors.primaryTint
                      : OpenCrayColors.surfaceSubtle,
                  borderRadius: const BorderRadius.all(OpenCrayRadii.sm),
                  border: Border.all(
                    color: selected
                        ? OpenCrayColors.primaryBorder
                        : OpenCrayColors.divider,
                  ),
                ),
                child: SizedBox.square(
                  dimension: 28,
                  child: Center(
                    child: Icon(
                      icon,
                      size: 16,
                      color: selected
                          ? OpenCrayColors.primary
                          : OpenCrayColors.textSecondary,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 12),
            ],
            Expanded(
              child: Text(
                title,
                style: _SettingsTextStyles.homeRow.copyWith(color: tint),
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              size: 20,
              color: selected
                  ? OpenCrayColors.primary
                  : OpenCrayColors.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

/// Home entries grouped so the overview reads as a few short lists instead of
/// one long undifferentiated column. Unknown pages keep working: they fall into
/// a trailing group in facade order.
const List<List<SettingsPage>> _homeEntryGroupOrder = <List<SettingsPage>>[
  <SettingsPage>[
    SettingsPage.llm,
    SettingsPage.mcp,
    SettingsPage.apiIntegrations,
    SettingsPage.networkSearch,
    SettingsPage.mediaSpeech,
    SettingsPage.sandboxProviders,
    SettingsPage.sandboxE2b,
  ],
  <SettingsPage>[
    SettingsPage.personalization,
    SettingsPage.notificationsBackground,
    SettingsPage.scheduledTasks,
    SettingsPage.eventAlerts,
  ],
  <SettingsPage>[
    SettingsPage.workspaceAccess,
    SettingsPage.safetyLimits,
    SettingsPage.privacyTelemetry,
  ],
  <SettingsPage>[SettingsPage.aboutVersion],
];

List<List<SettingsHomeEntrySnapshot>> _groupSettingsHomeEntries(
  List<SettingsHomeEntrySnapshot> entries,
) {
  final List<List<SettingsHomeEntrySnapshot>> groups =
      <List<SettingsHomeEntrySnapshot>>[];
  final Set<SettingsPage> claimed = <SettingsPage>{};
  for (final List<SettingsPage> pages in _homeEntryGroupOrder) {
    final List<SettingsHomeEntrySnapshot> group = entries
        .where((entry) => pages.contains(entry.page))
        .toList(growable: false);
    if (group.isEmpty) {
      continue;
    }
    groups.add(group);
    claimed.addAll(group.map((entry) => entry.page));
  }
  final List<SettingsHomeEntrySnapshot> rest = entries
      .where((entry) => !claimed.contains(entry.page))
      .toList(growable: false);
  if (rest.isNotEmpty) {
    groups.add(rest);
  }
  return groups;
}

IconData _iconForSettingsPage(SettingsPage page) {
  switch (page) {
    case SettingsPage.llm:
      return Icons.memory_rounded;
    case SettingsPage.mcp:
      return Icons.extension_outlined;
    case SettingsPage.apiIntegrations:
      return Icons.api_rounded;
    case SettingsPage.networkSearch:
      return Icons.travel_explore_rounded;
    case SettingsPage.mediaSpeech:
      return Icons.graphic_eq_rounded;
    case SettingsPage.sandboxProviders:
      return Icons.dns_outlined;
    case SettingsPage.sandboxE2b:
      return Icons.terminal_rounded;
    case SettingsPage.personalization:
      return Icons.palette_outlined;
    case SettingsPage.notificationsBackground:
      return Icons.notifications_none_rounded;
    case SettingsPage.eventAlerts:
      return Icons.campaign_outlined;
    case SettingsPage.scheduledTasks:
    case SettingsPage.scheduledTaskDetail:
      return Icons.schedule_rounded;
    case SettingsPage.workspaceAccess:
      return Icons.folder_open_rounded;
    case SettingsPage.safetyLimits:
      return Icons.verified_user_outlined;
    case SettingsPage.privacyTelemetry:
      return Icons.shield_outlined;
    case SettingsPage.agents:
      return Icons.smart_toy_outlined;
    case SettingsPage.aboutVersion:
      return Icons.info_outline_rounded;
    case SettingsPage.home:
      return Icons.tune_rounded;
  }
}

class _SettingsTextStyles {
  const _SettingsTextStyles._();

  static const TextStyle cardTitle = TextStyle(
    fontSize: 17,
    height: 1.25,
    fontWeight: FontWeight.w600,
    letterSpacing: -0.2,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle body = TextStyle(
    fontSize: 13,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle inlineAction = TextStyle(
    fontSize: 13,
    height: 1.35,
    fontWeight: FontWeight.w700,
    color: OpenCrayColors.primary,
  );

  static const TextStyle bodyStrong = TextStyle(
    fontSize: 15,
    height: 1.3,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle fieldLabel = TextStyle(
    fontSize: 13,
    height: 18 / 13,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle fieldValue = TextStyle(
    fontSize: 15,
    height: 20 / 15,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const StrutStyle fieldValueStrut = StrutStyle(
    fontSize: 15,
    height: 20 / 15,
    forceStrutHeight: true,
  );

  static const TextStyle rowTitle = TextStyle(
    fontSize: 15,
    height: 1.25,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle rowSubtitle = TextStyle(
    fontSize: 13,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle valueChip = TextStyle(
    fontSize: 11,
    height: 14 / 11,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle selectionMeta = TextStyle(
    fontSize: 12,
    height: 16 / 12,
    fontWeight: FontWeight.w500,
    color: OpenCrayColors.textTertiary,
  );

  static const TextStyle actionChip = TextStyle(
    fontSize: 12,
    height: 16 / 12,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.primary,
  );

  static const TextStyle homeRow = TextStyle(
    fontSize: 16,
    height: 1.2,
    fontWeight: FontWeight.w500,
  );
}

class _EnumSegmentedSelector<T> extends StatelessWidget {
  const _EnumSegmentedSelector({
    required this.values,
    required this.currentValue,
    required this.labelBuilder,
    required this.onChanged,
  });

  final List<T> values;
  final T currentValue;
  final String Function(T value) labelBuilder;
  final ValueChanged<T>? onChanged;

  @override
  Widget build(BuildContext context) {
    return OpenCraySegmentedControl(
      labels: values.map(labelBuilder).toList(growable: false),
      selectedIndex: values.indexOf(currentValue),
      textStyle: _SettingsTextStyles.valueChip,
      verticalPadding: 10,
      onSelected: onChanged == null
          ? null
          : (index) => onChanged!(values[index]),
    );
  }
}

class _PrototypeToggleTile extends StatelessWidget {
  const _PrototypeToggleTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: enabled ? () => onChanged(!value) : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: _SettingsTextStyles.rowTitle),
                  const SizedBox(height: 2),
                  Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
                ],
              ),
            ),
            const SizedBox(width: 12),
            OpenCraySwitch(
              value: value,
              onChanged: enabled ? onChanged : null,
              semanticLabel: title,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeSwitchRow extends StatelessWidget {
  const _PrototypeSwitchRow({
    required this.title,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final String title;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: enabled ? () => onChanged(!value) : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            const SizedBox(width: 12),
            OpenCraySwitch(
              value: value,
              onChanged: enabled ? onChanged : null,
              semanticLabel: title,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeDisclosureRow extends StatelessWidget {
  const _PrototypeDisclosureRow({
    required this.title,
    required this.onTap,
    this.value,
    this.verticalPadding = 12,
  });

  final String title;
  final String? value;
  final VoidCallback? onTap;
  final double verticalPadding;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: verticalPadding),
        child: Row(
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            if (value != null) ...[
              Text(
                value!,
                style: _SettingsTextStyles.rowTitle.copyWith(
                  color: OpenCrayColors.textSecondary,
                ),
              ),
              const SizedBox(width: 6),
            ],
            const Icon(
              Icons.chevron_right_rounded,
              size: 20,
              color: OpenCrayColors.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeValueRow extends StatelessWidget {
  const _PrototypeValueRow({required this.title, required this.value});

  final String title;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
          _PrototypeValuePill(value: value),
        ],
      ),
    );
  }
}

class _PrototypeStepperRow extends StatelessWidget {
  const _PrototypeStepperRow({
    required this.title,
    required this.subtitle,
    required this.value,
    this.valueKey,
    required this.decrementLabel,
    required this.incrementLabel,
    required this.canDecrement,
    required this.canIncrement,
    required this.onDecrement,
    this.onValueTap,
    required this.onIncrement,
  });

  final String title;
  final String subtitle;
  final String value;
  final Key? valueKey;
  final String decrementLabel;
  final String incrementLabel;
  final bool canDecrement;
  final bool canIncrement;
  final VoidCallback onDecrement;
  final VoidCallback? onValueTap;
  final VoidCallback onIncrement;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: _SettingsTextStyles.rowTitle),
          const SizedBox(height: 4),
          Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
          const SizedBox(height: 10),
          Align(
            alignment: Alignment.centerLeft,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: OpenCrayColors.surfaceSubtle,
                borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
                border: Border.all(color: OpenCrayColors.divider),
              ),
              child: OpenCrayInkSurface(
                borderRadius: const BorderRadius.all(OpenCrayRadii.pill),
                child: IntrinsicHeight(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _StepperButton(
                        icon: Icons.remove_rounded,
                        label: decrementLabel,
                        enabled: canDecrement,
                        onTap: onDecrement,
                      ),
                      const _StepperDivider(),
                      _StepperValueCell(
                        key: valueKey,
                        value: value,
                        onTap: onValueTap,
                      ),
                      const _StepperDivider(),
                      _StepperButton(
                        icon: Icons.add_rounded,
                        label: incrementLabel,
                        enabled: canIncrement,
                        onTap: onIncrement,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StepperButton extends StatelessWidget {
  const _StepperButton({
    required this.icon,
    required this.label,
    required this.enabled,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: enabled ? onTap : null,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 44, minHeight: 38),
        child: Center(
          child: Icon(
            icon,
            size: 18,
            semanticLabel: label,
            color: enabled
                ? OpenCrayColors.textPrimary
                : OpenCrayColors.textTertiary,
          ),
        ),
      ),
    );
  }
}

class _StepperDivider extends StatelessWidget {
  const _StepperDivider();

  @override
  Widget build(BuildContext context) {
    return const SizedBox(
      width: 1,
      child: ColoredBox(color: OpenCrayColors.divider),
    );
  }
}

class _StepperValueCell extends StatelessWidget {
  const _StepperValueCell({super.key, required this.value, this.onTap});

  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 84, minHeight: 38),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Center(
            child: Text(
              value,
              textAlign: TextAlign.center,
              style: _SettingsTextStyles.valueChip.copyWith(
                fontSize: 13,
                height: 18 / 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PrototypeValuePill extends StatelessWidget {
  const _PrototypeValuePill({required this.value});

  final String value;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSubtle,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: OpenCrayColors.divider),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(value, style: _SettingsTextStyles.valueChip),
      ),
    );
  }
}

class _PolicySelectionCard extends StatelessWidget {
  const _PolicySelectionCard({
    required this.selected,
    required this.onSelect,
    required this.inheritedLabel,
    this.footnote,
  });

  final ToolPolicyOverride selected;
  final ValueChanged<ToolPolicyOverride>? onSelect;
  final String inheritedLabel;
  final String? footnote;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SettingsCard(
          child: Column(
            children: [
              for (
                int index = 0;
                index < ToolPolicyOverride.values.length;
                index++
              ) ...[
                _PolicyOptionTile(
                  title: SafetySettingsCopy.policyLabel(
                    ToolPolicyOverride.values[index],
                  ),
                  trailingLabel:
                      ToolPolicyOverride.values[index] ==
                              ToolPolicyOverride.inherit &&
                          selected != ToolPolicyOverride.inherit
                      ? inheritedLabel
                      : null,
                  selected: selected == ToolPolicyOverride.values[index],
                  onTap: onSelect == null
                      ? null
                      : () {
                          onSelect!(ToolPolicyOverride.values[index]);
                        },
                ),
                if (index < ToolPolicyOverride.values.length - 1)
                  const Divider(height: 1, color: OpenCrayColors.divider),
              ],
            ],
          ),
        ),
        if (footnote != null) ...[
          const SizedBox(height: 12),
          Text(footnote!, style: _SettingsTextStyles.rowSubtitle),
        ],
      ],
    );
  }
}

class _PolicyOptionTile extends StatelessWidget {
  const _PolicyOptionTile({
    required this.title,
    required this.selected,
    required this.onTap,
    this.trailingLabel,
  });

  final String title;
  final bool selected;
  final VoidCallback? onTap;
  final String? trailingLabel;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            const SizedBox(width: 12),
            if (trailingLabel != null && !selected) ...[
              Text(
                trailingLabel!,
                style: _SettingsTextStyles.rowTitle.copyWith(
                  color: OpenCrayColors.textSecondary,
                ),
              ),
              const SizedBox(width: 10),
            ],
            OpenCraySelectionCheck(selected: selected),
          ],
        ),
      ),
    );
  }
}

class _ApprovedPathTile extends StatelessWidget {
  const _ApprovedPathTile({
    required this.title,
    required this.subtitle,
    this.tone = 'neutral',
  });

  final String title;
  final String subtitle;
  final String tone;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSubtle,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: _SettingsTextStyles.rowTitle),
                  const SizedBox(height: 4),
                  Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
                ],
              ),
            ),
            const SizedBox(width: 12),
            _SettingsStatusPill(
              label: tone == 'positive' ? 'Included' : 'Approved',
              tone: tone,
            ),
          ],
        ),
      ),
    );
  }
}
