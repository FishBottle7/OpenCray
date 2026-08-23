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
                      Text(agent.name, style: _SettingsTextStyles.cardTitle),
                      const SizedBox(height: 2),
                      Text(
                        agent.summary,
                        style: _SettingsTextStyles.body.copyWith(
                          fontSize: 12,
                          color: OpenCrayColors.textTertiary,
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
              style: _SettingsTextStyles.bodyStrong.copyWith(
                fontSize: 13,
                fontWeight: FontWeight.w400,
                color: OpenCrayColors.textSecondary,
              ),
            ),
            const SizedBox(height: 10),
            Text(agent.meta, style: _SettingsTextStyles.selectionMeta),
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
    final _AgentStatusVisual visual = _AgentStatusVisual.fromStatus(status);
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
                      style: _SettingsTextStyles.selectionMeta.copyWith(
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
                style: _SettingsTextStyles.selectionMeta,
              ),
            ],
          ),
          const SizedBox(height: 9),
          Text(
            draft.agentName.trim().isEmpty ? 'Unnamed agent' : draft.agentName,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: _SettingsTextStyles.cardTitle.copyWith(fontSize: 15),
          ),
          const SizedBox(height: 4),
          Text(
            detail?.trim().isNotEmpty == true ? detail! : behaviorSummary,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: _SettingsTextStyles.body.copyWith(
              fontSize: 12,
              color: status == _AgentCreateStatus.failed
                  ? OpenCrayColors.dangerText
                  : OpenCrayColors.textTertiary,
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

  factory _AgentStatusVisual.fromStatus(_AgentCreateStatus status) {
    return switch (status) {
      _AgentCreateStatus.clean => const _AgentStatusVisual(
        label: 'Draft ready',
        surfaceColor: OpenCrayColors.surfaceMuted,
        borderColor: OpenCrayColors.divider,
        textColor: OpenCrayColors.textSecondary,
      ),
      _AgentCreateStatus.edited => const _AgentStatusVisual(
        label: 'Unsaved changes',
        surfaceColor: OpenCrayColors.warningTint,
        borderColor: OpenCrayColors.warningBorder,
        textColor: OpenCrayColors.warning,
      ),
      _AgentCreateStatus.saving => const _AgentStatusVisual(
        label: 'Saving agent',
        surfaceColor: OpenCrayColors.primaryTint,
        borderColor: OpenCrayColors.primaryBorder,
        textColor: OpenCrayColors.primary,
      ),
      _AgentCreateStatus.saved => const _AgentStatusVisual(
        label: 'Saved',
        surfaceColor: OpenCrayColors.successTint,
        borderColor: OpenCrayColors.successBorder,
        textColor: OpenCrayColors.success,
      ),
      _AgentCreateStatus.failed => const _AgentStatusVisual(
        label: 'Save failed',
        surfaceColor: OpenCrayColors.dangerTint,
        borderColor: OpenCrayColors.dangerBorder,
        textColor: OpenCrayColors.dangerText,
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
        const Text('Avatar', style: _SettingsTextStyles.fieldLabel),
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
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            const SizedBox(width: 12),
            Flexible(
              child: Text(
                value,
                textAlign: TextAlign.right,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: _SettingsTextStyles.selectionMeta.copyWith(fontSize: 13),
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
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 64),
        decoration: BoxDecoration(
          color: selected
              ? OpenCrayColors.surfaceAccent
              : OpenCrayColors.surfaceSubtle,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected
                ? OpenCrayColors.primaryBorder
                : OpenCrayColors.divider,
          ),
        ),
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: _SettingsTextStyles.rowTitle.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                if (selected)
                  _AgentSoftBadge(label: selectedLabel)
                else
                  Container(
                    width: 18,
                    height: 18,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      shape: BoxShape.circle,
                      border: Border.all(color: OpenCrayColors.outline),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 2),
            Text(
              body,
              style: _SettingsTextStyles.body.copyWith(
                fontSize: 13,
                color: OpenCrayColors.textSecondary,
              ),
            ),
          ],
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
          Text(title, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(body, style: _SettingsTextStyles.body),
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
        color: OpenCrayColors.primaryTint,
        borderRadius: BorderRadius.circular(999),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      child: Text(
        label,
        style: const TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: OpenCrayColors.primary,
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
        color: OpenCrayColors.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      child: Text(
        label,
        style: const TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: OpenCrayColors.textSecondary,
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
            ? OpenCrayColors.surfaceAccent
            : OpenCrayColors.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
        border: active
            ? Border.all(color: OpenCrayColors.primaryBorder)
            : null,
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          height: 1.2,
          fontWeight: FontWeight.w600,
          color: active ? OpenCrayColors.primary : OpenCrayColors.textSecondary,
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
    return InkWell(
      key: key,
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: Container(
        height: 34,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: BoxDecoration(
          color: OpenCrayColors.primary,
          borderRadius: BorderRadius.circular(999),
        ),
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
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: Container(
        height: 48,
        decoration: BoxDecoration(
          color: OpenCrayColors.primary,
          borderRadius: BorderRadius.circular(16),
        ),
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
    );
  }
}

class _AgentTertiaryButton extends StatelessWidget {
  const _AgentTertiaryButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: OpenCrayColors.primaryTint,
          borderRadius: BorderRadius.circular(999),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 11,
            height: 1.1,
            fontWeight: FontWeight.w600,
            color: OpenCrayColors.primary,
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
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: OpenCrayColors.surfaceMuted,
          borderRadius: BorderRadius.circular(999),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(
          label,
          style: const TextStyle(
            fontSize: 11,
            height: 1.1,
            fontWeight: FontWeight.w600,
            color: OpenCrayColors.textSecondary,
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
      decoration: const BoxDecoration(
        shape: BoxShape.circle,
        color: OpenCrayColors.primary,
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
        color: OpenCrayColors.brandSky,
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
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        height: 148,
        decoration: BoxDecoration(
          color: OpenCrayColors.surfaceSubtle,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: OpenCrayColors.outline),
        ),
        padding: const EdgeInsets.all(14),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              title,
              style: _SettingsTextStyles.bodyStrong.copyWith(
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              body,
              textAlign: TextAlign.center,
              style: _SettingsTextStyles.body,
            ),
            const SizedBox(height: 10),
            _AgentTertiaryButton(label: buttonLabel, onTap: onTap),
          ],
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
            color: const Color(0xE0FFFFFF),
            borderRadius: BorderRadius.circular(999),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Text(
            image.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 10,
              height: 1.2,
              fontWeight: FontWeight.w600,
              color: OpenCrayColors.textPrimary,
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
                    style: _SettingsTextStyles.rowTitle.copyWith(
                      color: enabled
                          ? OpenCrayColors.textPrimary
                          : OpenCrayColors.textTertiary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: _SettingsTextStyles.selectionMeta.copyWith(
                      color: enabled
                          ? OpenCrayColors.textSecondary
                          : OpenCrayColors.textTertiary,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Text(
              trailingLabel,
              style: _SettingsTextStyles.selectionMeta.copyWith(
                color: enabled
                    ? OpenCrayColors.primary
                    : OpenCrayColors.textTertiary,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(width: 6),
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: enabled
                  ? OpenCrayColors.textTertiary
                  : OpenCrayColors.outline,
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
        Text(label, style: _SettingsTextStyles.fieldLabel),
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
            style: _SettingsTextStyles.bodyStrong.copyWith(
              fontSize: 13,
              fontWeight: FontWeight.w400,
            ),
            decoration: const InputDecoration(
              isCollapsed: true,
              contentPadding: EdgeInsets.all(10),
              border: InputBorder.none,
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
    backgroundColor: Colors.white,
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
              Text(title, style: _SettingsTextStyles.cardTitle),
              const SizedBox(height: 12),
              for (int index = 0; index < options.length; index++) ...[
                InkWell(
                  borderRadius: BorderRadius.circular(14),
                  onTap: () => Navigator.of(context).pop(options[index]),
                  child: Container(
                    height: 48,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    decoration: BoxDecoration(
                      color: options[index] == selectedValue
                          ? OpenCrayColors.surfaceAccent
                          : OpenCrayColors.surfaceSubtle,
                      borderRadius: BorderRadius.circular(14),
                      border: options[index] == selectedValue
                          ? Border.all(color: OpenCrayColors.primaryBorder)
                          : Border.all(color: OpenCrayColors.divider),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            labelBuilder(options[index]),
                            style: _SettingsTextStyles.rowTitle,
                          ),
                        ),
                        if (options[index] == selectedValue)
                          const Icon(
                            Icons.check_rounded,
                            size: 18,
                            color: OpenCrayColors.primary,
                          ),
                      ],
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
    barrierColor: OpenCrayColors.scrim,
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
                color: Colors.white,
                borderRadius: BorderRadius.circular(22),
                boxShadow: OpenCrayShadows.floating,
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
                        color: const Color(0xE0FFFFFF),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      child: const Text(
                        'New upload',
                        style: TextStyle(
                          fontSize: 10,
                          height: 1.2,
                          fontWeight: FontWeight.w600,
                          color: OpenCrayColors.textPrimary,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Image label',
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.2,
                      fontWeight: FontWeight.w600,
                      color: OpenCrayColors.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Container(
                    height: 48,
                    decoration: BoxDecoration(
                      color: OpenCrayColors.surfaceSubtle,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: OpenCrayColors.outline),
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
                      style: _SettingsTextStyles.fieldValue.copyWith(
                        fontWeight: FontWeight.w400,
                      ),
                      decoration: InputDecoration(
                        isCollapsed: true,
                        border: InputBorder.none,
                        hintText: 'e.g. front portrait, red outfit, warm light',
                        hintStyle: _SettingsTextStyles.fieldValue.copyWith(
                          color: OpenCrayColors.textTertiary,
                          fontWeight: FontWeight.w400,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'This label affects how the agent interprets the image. Use clear, literal wording so the reference is easier to understand later.',
                    style: _SettingsTextStyles.selectionMeta,
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
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: Container(
        height: 44,
        decoration: BoxDecoration(
          color: primary
              ? (onTap == null
                    ? OpenCrayColors.surfaceSunken
                    : OpenCrayColors.primary)
              : OpenCrayColors.surfaceMuted,
          borderRadius: BorderRadius.circular(14),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: TextStyle(
            fontSize: 14,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: primary
                ? (onTap == null
                      ? OpenCrayColors.textTertiary
                      : Colors.white)
                : OpenCrayColors.textSecondary,
          ),
        ),
      ),
    );
  }
}
