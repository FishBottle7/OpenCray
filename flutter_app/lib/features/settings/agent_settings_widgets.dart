part of 'settings_feature.dart';

class _AgentListCard extends StatelessWidget {
  const _AgentListCard({required this.agent, required this.onTap});

  final _SavedAgent agent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                _AgentAvatarCircle(
                  letter: agent.name.substring(0, 1).toUpperCase(),
                  colors: agent.avatarColors,
                  size: 44,
                  fontSize: 20,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(agent.name, style: context.settingsText.cardTitle),
                      const SizedBox(height: 2),
                      Text(
                        agent.summary,
                        style: context.settingsText.body.copyWith(
                          fontSize: 12,
                          color: context.palette.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              agent.description,
              style: context.settingsText.bodyStrong.copyWith(
                fontSize: 13,
                fontWeight: FontWeight.w400,
                color: context.palette.textSecondary,
              ),
            ),
            const SizedBox(height: 10),
            Text(agent.meta, style: context.settingsText.selectionMeta),
          ],
        ),
      ),
    );
  }
}

class _AgentCreateStatusCard extends StatelessWidget {
  const _AgentCreateStatusCard({
    super.key,
    required this.status,
    required this.detail,
    required this.draft,
  });

  final _AgentCreateStatus status;
  final String? detail;
  final _AgentDraft draft;

  @override
  Widget build(BuildContext context) {
    final _AgentStatusVisual visual = _AgentStatusVisual.fromStatus(
      status,
      context.palette,
    );
    final String modelLabel = draft.model.trim().isEmpty
        ? 'Model'
        : draft.model.trim();
    final String modelSummary =
        '${_labelForProvider(draft.provider)} · $modelLabel';
    final String behaviorSummary =
        '${draft.modeLabel} · ${_labelForRiskTolerance(draft.riskTolerance)} risk';
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              AnimatedContainer(
                key: ValueKey<String>('agent-create-status-${status.name}'),
                duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
                curve: OpenCrayMotion.enter,
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
                decoration: BoxDecoration(
                  color: visual.surfaceColor,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: visual.borderColor),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    if (status == _AgentCreateStatus.saving) ...<Widget>[
                      SizedBox.square(
                        dimension: 12,
                        child: CircularProgressIndicator(
                          strokeWidth: 1.5,
                          color: visual.textColor,
                        ),
                      ),
                      const SizedBox(width: 6),
                    ],
                    Text(
                      visual.label,
                      style: context.settingsText.selectionMeta.copyWith(
                        color: visual.textColor,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
              const Spacer(),
              Text(
                modelSummary,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.settingsText.selectionMeta,
              ),
            ],
          ),
          const SizedBox(height: 9),
          Text(
            draft.agentName.trim().isEmpty ? 'Unnamed agent' : draft.agentName,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: context.settingsText.cardTitle.copyWith(fontSize: 15),
          ),
          const SizedBox(height: 4),
          Text(
            detail?.trim().isNotEmpty == true ? detail! : behaviorSummary,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: context.settingsText.body.copyWith(
              fontSize: 12,
              color: status == _AgentCreateStatus.failed
                  ? context.palette.danger
                  : context.palette.textTertiary,
            ),
          ),
        ],
      ),
    );
  }
}

class _AgentStatusVisual {
  const _AgentStatusVisual({
    required this.label,
    required this.surfaceColor,
    required this.borderColor,
    required this.textColor,
  });

  final String label;
  final Color surfaceColor;
  final Color borderColor;
  final Color textColor;

  factory _AgentStatusVisual.fromStatus(
    _AgentCreateStatus status,
    OpenCrayPalette palette,
  ) {
    return switch (status) {
      _AgentCreateStatus.clean => _AgentStatusVisual(
        label: 'Draft ready',
        surfaceColor: palette.surfaceMuted,
        borderColor: palette.divider,
        textColor: palette.textSecondary,
      ),
      _AgentCreateStatus.edited => _AgentStatusVisual(
        label: 'Unsaved changes',
        surfaceColor: palette.warningTint,
        borderColor: palette.warningBorder,
        textColor: palette.warning,
      ),
      _AgentCreateStatus.saving => _AgentStatusVisual(
        label: 'Saving agent',
        surfaceColor: palette.primaryTint,
        borderColor: palette.primaryBorder,
        textColor: palette.primary,
      ),
      _AgentCreateStatus.saved => _AgentStatusVisual(
        label: 'Saved',
        surfaceColor: palette.successTint,
        borderColor: palette.successBorder,
        textColor: palette.success,
      ),
      _AgentCreateStatus.failed => _AgentStatusVisual(
        label: 'Save failed',
        surfaceColor: palette.dangerTint,
        borderColor: palette.dangerBorder,
        textColor: palette.danger,
      ),
    };
  }
}

class _AgentAvatarField extends StatelessWidget {
  const _AgentAvatarField({required this.draft, required this.onTap});

  final _AgentDraft draft;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Avatar', style: context.settingsText.fieldLabel),
        const SizedBox(height: 6),
        InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: _PrototypeFieldSurface(
            child: SizedBox(
              height: 44,
              child: Center(
                child: _AgentAvatarCircle(
                  letter: draft.avatarLetter,
                  colors: draft.avatarColors,
                  size: 28,
                  fontSize: 14,
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _AgentAvatarCircle extends StatelessWidget {
  const _AgentAvatarCircle({
    required this.letter,
    required this.colors,
    required this.size,
    required this.fontSize,
  });

  final String letter;
  final List<Color> colors;
  final double size;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: colors,
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        letter,
        style: TextStyle(
          fontSize: fontSize,
          height: 1,
          fontWeight: FontWeight.w700,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _AgentSummaryLinkRow extends StatelessWidget {
  const _AgentSummaryLinkRow({
    super.key,
    required this.title,
    required this.value,
    required this.onTap,
  });

  final String title;
  final String value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          children: [
            Expanded(child: Text(title, style: context.settingsText.rowTitle)),
            const SizedBox(width: 12),
            Flexible(
              child: Text(
                value,
                textAlign: TextAlign.right,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.settingsText.selectionMeta.copyWith(fontSize: 13),
              ),
            ),
            const SizedBox(width: 6),
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: context.palette.textTertiary,
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentChoiceTile extends StatelessWidget {
  const _AgentChoiceTile({
    required this.title,
    required this.body,
    required this.selected,
    required this.selectedLabel,
    required this.onTap,
  });

  final String title;
  final String body;
  final bool selected;
  final String selectedLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      constraints: const BoxConstraints(minHeight: 64),
      decoration: BoxDecoration(
        color: selected
            ? context.palette.primaryTint
            : context.palette.surfaceSubtle,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: selected
              ? context.palette.primaryBorder
              : context.palette.divider,
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: context.settingsText.rowTitle.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    if (selected)
                      _AgentSoftBadge(label: selectedLabel)
                    else
                      const OpenCraySelectionCheck(selected: false, dimension: 18),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  body,
                  style: context.settingsText.body.copyWith(
                    fontSize: 13,
                    color: context.palette.textSecondary,
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

class _AgentInfoCard extends StatelessWidget {
  const _AgentInfoCard({required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: context.settingsText.cardTitle),
          const SizedBox(height: 8),
          Text(body, style: context.settingsText.body),
        ],
      ),
    );
  }
}

class _AgentSoftBadge extends StatelessWidget {
  const _AgentSoftBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: context.palette.primaryTint,
        borderRadius: BorderRadius.circular(999),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: context.palette.primary,
        ),
      ),
    );
  }
}

class _TwinImportNeutralBadge extends StatelessWidget {
  const _TwinImportNeutralBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: context.palette.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: context.palette.textSecondary,
        ),
      ),
    );
  }
}

class _TwinImportSignalPill extends StatelessWidget {
  const _TwinImportSignalPill({required this.label, this.active = false});

  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 32,
      decoration: BoxDecoration(
        color: active
            ? context.palette.primaryTint
            : context.palette.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
        border: active
            ? Border.all(color: context.palette.primaryBorder)
            : null,
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: active ? context.palette.primary : context.palette.textSecondary,
        ),
      ),
    );
  }
}

class _AgentPillButton extends StatelessWidget {
  const _AgentPillButton({super.key, required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.primary,
        borderRadius: BorderRadius.circular(999),
        boxShadow: <BoxShadow>[
          BoxShadow(
            color: context.palette.primary.withValues(alpha: 0x33 / 0xFF),
            offset: const Offset(0, 2),
            blurRadius: 8,
          ),
        ],
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          key: key,
          onTap: onTap,
          splashColor: Colors.white24,
          highlightColor: Colors.white10,
          child: Container(
            constraints: const BoxConstraints(minHeight: 34),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            alignment: Alignment.center,
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 13,
                height: 1.1,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentPrimaryButton extends StatelessWidget {
  const _AgentPrimaryButton({
    super.key,
    required this.label,
    required this.onTap,
  });

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: context.palette.brandGradient,
        borderRadius: BorderRadius.circular(16),
        boxShadow: context.palette.brandGlowLarge,
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: onTap,
          splashColor: Colors.white24,
          highlightColor: Colors.white10,
          child: Container(
            constraints: const BoxConstraints(minHeight: 48),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            alignment: Alignment.center,
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 17,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentTertiaryButton extends StatelessWidget {
  const _AgentTertiaryButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.primaryTint,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: context.palette.primaryBorder),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 11,
                height: 1.1,
                fontWeight: FontWeight.w600,
                color: context.palette.primary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentMutedButton extends StatelessWidget {
  const _AgentMutedButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: context.palette.divider),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 11,
                height: 1.1,
                fontWeight: FontWeight.w600,
                color: context.palette.textSecondary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentPlayDot extends StatelessWidget {
  const _AgentPlayDot();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 20,
      height: 20,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: context.palette.primary,
      ),
    );
  }
}

class _AgentWaveBar extends StatelessWidget {
  const _AgentWaveBar({required this.height});

  final double height;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 4,
      height: height,
      decoration: BoxDecoration(
        color: context.palette.brandSky,
        borderRadius: BorderRadius.circular(999),
      ),
    );
  }
}

class _AgentUploadZone extends StatelessWidget {
  const _AgentUploadZone({
    required this.title,
    required this.body,
    required this.buttonLabel,
    required this.onTap,
  });

  final String title;
  final String body;
  final String buttonLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: context.palette.surfaceSubtle,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: context.palette.outline),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
          child: Container(
            height: 148,
            padding: const EdgeInsets.all(14),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  title,
                  style: context.settingsText.bodyStrong.copyWith(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  body,
                  textAlign: TextAlign.center,
                  style: context.settingsText.body,
                ),
                const SizedBox(height: 10),
                _AgentTertiaryButton(label: buttonLabel, onTap: onTap),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentReferenceCard extends StatelessWidget {
  const _AgentReferenceCard({required this.image});

  final _AgentImageReference image;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 92,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: image.colors,
        ),
      ),
      padding: const EdgeInsets.all(8),
      child: Align(
        alignment: Alignment.bottomLeft,
        child: Container(
          decoration: BoxDecoration(
            color: context.palette.surface.withValues(alpha: 0xE0 / 0xFF),
            borderRadius: BorderRadius.circular(999),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Text(
            image.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 10,
              height: 1.2,
              fontWeight: FontWeight.w600,
              color: context.palette.textPrimary,
            ),
          ),
        ),
      ),
    );
  }
}

class _AgentDetailActionRow extends StatelessWidget {
  const _AgentDetailActionRow({
    required this.title,
    required this.subtitle,
    required this.trailingLabel,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final String trailingLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: context.settingsText.rowTitle.copyWith(
                      color: enabled
                          ? context.palette.textPrimary
                          : context.palette.textTertiary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: context.settingsText.selectionMeta.copyWith(
                      color: enabled
                          ? context.palette.textSecondary
                          : context.palette.textTertiary,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Text(
              trailingLabel,
              style: context.settingsText.selectionMeta.copyWith(
                color: enabled
                    ? context.palette.primary
                    : context.palette.textTertiary,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 6),
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: enabled
                  ? context.palette.textTertiary
                  : context.palette.outline,
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentMultilineField extends StatelessWidget {
  const _AgentMultilineField({
    required this.label,
    required this.controller,
    required this.onChanged,
  });

  final String label;
  final TextEditingController controller;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: context.settingsText.fieldLabel),
        const SizedBox(height: 4),
        _PrototypeFieldSurface(
          child: TextField(
            controller: controller,
            minLines: 2,
            maxLines: 2,
            onChanged: onChanged,
            autocorrect: false,
            enableSuggestions: true,
            enableIMEPersonalizedLearning: true,
            spellCheckConfiguration: const SpellCheckConfiguration.disabled(),
            smartDashesType: SmartDashesType.disabled,
            smartQuotesType: SmartQuotesType.disabled,
            style: context.settingsText.bodyStrong.copyWith(
              fontSize: 13,
              fontWeight: FontWeight.w400,
            ),
            decoration: openCrayBareInputDecoration.copyWith(
              isCollapsed: true,
              contentPadding: const EdgeInsets.all(10),
            ),
          ),
        ),
      ],
    );
  }
}

Future<T?> _showSelectionSheet<T>(
  BuildContext context, {
  required String title,
  required T selectedValue,
  required List<T> options,
  required String Function(T value) labelBuilder,
}) {
  return showModalBottomSheet<T>(
    context: context,
    backgroundColor: context.palette.surface,
    sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (BuildContext context) {
      return SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(title, style: context.settingsText.cardTitle),
              const SizedBox(height: 12),
              for (int index = 0; index < options.length; index++) ...[
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: options[index] == selectedValue
                        ? context.palette.primaryTint
                        : context.palette.surfaceSubtle,
                    borderRadius: BorderRadius.circular(14),
                    border: options[index] == selectedValue
                        ? Border.all(color: context.palette.primaryBorder)
                        : Border.all(color: context.palette.divider),
                  ),
                  child: OpenCrayInkSurface(
                    borderRadius: BorderRadius.circular(14),
                    child: InkWell(
                      onTap: () => Navigator.of(context).pop(options[index]),
                      child: Container(
                        height: 48,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                labelBuilder(options[index]),
                                style: context.settingsText.rowTitle,
                              ),
                            ),
                            OpenCraySelectionCheck(
                              selected: options[index] == selectedValue,
                              dimension: 18,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                if (index < options.length - 1) const SizedBox(height: 8),
              ],
            ],
          ),
        ),
      );
    },
  );
}

Future<String?> _showImageNamingDialog(
  BuildContext context, {
  required List<Color> colors,
}) {
  return showDialog<String>(
    context: context,
    barrierColor: context.palette.scrim,
    builder: (BuildContext context) {
      final TextEditingController controller = TextEditingController();
      String draftValue = '';
      return StatefulBuilder(
        builder: (BuildContext context, StateSetter setState) {
          final bool canConfirm = draftValue.trim().isNotEmpty;
          return Dialog(
            insetPadding: const EdgeInsets.symmetric(horizontal: 20),
            backgroundColor: Colors.transparent,
            child: Container(
              decoration: BoxDecoration(
                color: context.palette.surface,
                borderRadius: BorderRadius.circular(22),
                boxShadow: context.palette.floatingShadow,
              ),
              padding: const EdgeInsets.all(16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Container(
                    height: 204,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(18),
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: colors,
                      ),
                    ),
                    padding: const EdgeInsets.all(12),
                    alignment: Alignment.bottomLeft,
                    child: Container(
                      decoration: BoxDecoration(
                        color: context.palette.surface.withValues(alpha: 0xE0 / 0xFF),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      child: Text(
                        'New upload',
                        style: TextStyle(
                          fontSize: 10,
                          height: 1.2,
                          fontWeight: FontWeight.w600,
                          color: context.palette.textPrimary,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Image label',
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.2,
                      fontWeight: FontWeight.w600,
                      color: context.palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Container(
                    height: 48,
                    decoration: BoxDecoration(
                      color: context.palette.surfaceSubtle,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: context.palette.outline),
                    ),
                    alignment: Alignment.center,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    child: TextField(
                      controller: controller,
                      onChanged: (String value) {
                        setState(() {
                          draftValue = value;
                        });
                      },
                      autofocus: true,
                      autocorrect: false,
                      enableSuggestions: true,
                      enableIMEPersonalizedLearning: true,
                      spellCheckConfiguration:
                          const SpellCheckConfiguration.disabled(),
                      smartDashesType: SmartDashesType.disabled,
                      smartQuotesType: SmartQuotesType.disabled,
                      style: context.settingsText.fieldValue.copyWith(
                        fontWeight: FontWeight.w400,
                      ),
                      decoration: openCrayBareInputDecoration.copyWith(
                        isCollapsed: true,
                        hintText: 'e.g. front portrait, red outfit, warm light',
                        hintStyle: context.settingsText.fieldValue.copyWith(
                          color: context.palette.textTertiary,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'This label affects how the agent interprets the image. Use clear, literal wording so the reference is easier to understand later.',
                    style: context.settingsText.selectionMeta,
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: _AgentDialogButton(
                          label: 'Cancel',
                          primary: false,
                          onTap: () => Navigator.of(context).pop(),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: _AgentDialogButton(
                          label: 'Confirm',
                          primary: true,
                          onTap: canConfirm
                              ? () =>
                                    Navigator.of(context).pop(draftValue.trim())
                              : null,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          );
        },
      );
    },
  );
}

class _AgentDialogButton extends StatelessWidget {
  const _AgentDialogButton({
    required this.label,
    required this.primary,
    required this.onTap,
  });

  final String label;
  final bool primary;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool enabled = onTap != null;
    final Color background = primary
        ? (enabled ? context.palette.primary : context.palette.surfaceSunken)
        : context.palette.surfaceMuted;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(14),
        border: primary
            ? null
            : Border.all(color: context.palette.divider),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
          splashColor: primary && enabled ? Colors.white24 : null,
          highlightColor: primary && enabled ? Colors.white10 : null,
          child: Container(
            constraints: const BoxConstraints(minHeight: 44),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
            alignment: Alignment.center,
            child: Text(
              label,
              style: TextStyle(
                fontSize: 14,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: primary
                    ? (enabled ? Colors.white : context.palette.textTertiary)
                    : context.palette.textSecondary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
