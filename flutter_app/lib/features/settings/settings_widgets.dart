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
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 8),
          Text(title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 16),
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
    return Align(
      alignment: Alignment.centerLeft,
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const Icon(
                Icons.arrow_back_ios_new_rounded,
                size: 14,
                color: OpenCrayColors.primary,
              ),
              if (label.trim().isNotEmpty) ...[
                const SizedBox(width: 6),
                Text(label, style: _SettingsTextStyles.actionChip),
              ],
            ],
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
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: _PrototypeFieldSurface(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: compact ? 44 : 52),
          child: Padding(
            padding: EdgeInsets.symmetric(
              horizontal: 12,
              vertical: compact ? 12 : 14,
            ),
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
                  Center(
                    child: Text(
                      trailingLabel!,
                      style: _SettingsTextStyles.selectionMeta,
                    ),
                  ),
                  const SizedBox(width: 6),
                  const Center(
                    child: Text(
                      '›',
                      style: _SettingsTextStyles.selectionChevron,
                    ),
                  ),
                ] else ...[
                  const Spacer(),
                  const Center(
                    child: Text(
                      '›',
                      style: _SettingsTextStyles.selectionChevron,
                    ),
                  ),
                ],
              ],
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
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSunken,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          children: [
            for (final label in labels)
              Expanded(
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () => onSelected(label),
                  child: AnimatedContainer(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.micro,
                    ),
                    curve: OpenCrayMotion.enter,
                    decoration: BoxDecoration(
                      color: label == selectedId
                          ? Colors.white
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(999),
                      boxShadow: label == selectedId
                          ? OpenCrayShadows.card
                          : null,
                    ),
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(
                      labelBuilder(label),
                      textAlign: TextAlign.center,
                      style: _SettingsTextStyles.valueChip.copyWith(
                        color: label == selectedId
                            ? OpenCrayColors.textPrimary
                            : OpenCrayColors.textSecondary,
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

class _PrototypeSwitch extends StatelessWidget {
  const _PrototypeSwitch({required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Switch(
      value: value,
      onChanged: onChanged,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      thumbColor: const WidgetStatePropertyAll<Color>(Colors.white),
      trackColor: WidgetStateProperty.resolveWith<Color>((states) {
        if (states.contains(WidgetState.selected)) {
          return OpenCrayColors.primary;
        }
        return OpenCrayColors.surfaceSunken;
      }),
      trackOutlineColor: const WidgetStatePropertyAll<Color>(
        Colors.transparent,
      ),
      trackOutlineWidth: const WidgetStatePropertyAll<double>(0),
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
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: OpenCrayColors.primaryTint,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          child: Text(
            label,
            style: textStyle ?? _SettingsTextStyles.actionChip,
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
      child: Padding(padding: const EdgeInsets.all(16), child: child),
    );
  }
}

class _HomeEntryRow extends StatelessWidget {
  const _HomeEntryRow({
    required this.title,
    required this.onTap,
    this.selected = false,
  });

  final String title;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: _SettingsTextStyles.homeRow.copyWith(
                  color: selected
                      ? OpenCrayColors.primary
                      : OpenCrayColors.textPrimary,
                ),
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
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

class _SettingsTextStyles {
  const _SettingsTextStyles._();

  static const TextStyle eyebrow = TextStyle(
    fontSize: 11,
    height: 1.1,
    fontWeight: FontWeight.w700,
    letterSpacing: 1.1,
    color: OpenCrayColors.textTertiary,
  );

  static const TextStyle pageTitle = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.6,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle pageTitleSubpage = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.6,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle subtitle = TextStyle(
    fontSize: 14,
    height: 1.35,
    color: OpenCrayColors.textSecondary,
  );

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

  static const TextStyle selectionChevron = TextStyle(
    fontSize: 16,
    height: 1.0,
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
    return DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSunken,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          children: [
            for (final value in values)
              Expanded(
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: onChanged == null ? null : () => onChanged!(value),
                  child: AnimatedContainer(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.micro,
                    ),
                    curve: OpenCrayMotion.enter,
                    decoration: BoxDecoration(
                      color: value == currentValue
                          ? Colors.white
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    child: Text(
                      labelBuilder(value),
                      textAlign: TextAlign.center,
                      style: _SettingsTextStyles.valueChip.copyWith(
                        color: value == currentValue
                            ? OpenCrayColors.textPrimary
                            : OpenCrayColors.textSecondary,
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
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
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
          _PrototypeSwitch(value: value, onChanged: enabled ? onChanged : null),
        ],
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
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
          const SizedBox(width: 12),
          _PrototypeSwitch(value: value, onChanged: enabled ? onChanged : null),
        ],
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
              const SizedBox(width: 8),
            ],
            const Text(
              '›',
              style: TextStyle(
                color: OpenCrayColors.outline,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
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
          Row(
            children: [
              Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
              const SizedBox(width: 12),
              _StepperButton(
                label: decrementLabel,
                enabled: canDecrement,
                onTap: onDecrement,
              ),
              const SizedBox(width: 8),
              _PrototypeValuePill(
                key: valueKey,
                value: value,
                onTap: onValueTap,
              ),
              const SizedBox(width: 8),
              _StepperButton(
                label: incrementLabel,
                enabled: canIncrement,
                onTap: onIncrement,
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
        ],
      ),
    );
  }
}

class _StepperButton extends StatelessWidget {
  const _StepperButton({
    required this.label,
    required this.enabled,
    required this.onTap,
  });

  final String label;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: enabled ? onTap : null,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: enabled ? OpenCrayColors.surfaceSubtle : OpenCrayColors.surfaceMuted,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Text(
            label,
            style: _SettingsTextStyles.valueChip.copyWith(
              color: enabled
                  ? OpenCrayColors.textPrimary
                  : OpenCrayColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _PrototypeValuePill extends StatelessWidget {
  const _PrototypeValuePill({super.key, required this.value, this.onTap});

  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final child = DecoratedBox(
      decoration: BoxDecoration(
        color: OpenCrayColors.surfaceSubtle,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(value, style: _SettingsTextStyles.valueChip),
      ),
    );
    if (onTap == null) {
      return child;
    }
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: child,
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
        padding: const EdgeInsets.symmetric(vertical: 16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            const SizedBox(width: 12),
            if (selected)
              const Icon(
                Icons.check_rounded,
                size: 18,
                color: OpenCrayColors.primary,
              )
            else if (trailingLabel != null)
              Text(
                trailingLabel!,
                style: _SettingsTextStyles.rowTitle.copyWith(
                  color: OpenCrayColors.textSecondary,
                ),
              ),
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
