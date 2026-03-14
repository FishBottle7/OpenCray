import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/models/opencray_skills_snapshot.dart';

enum SkillsPage { manage, install }

const _shellBackground = Color(0xFFF5F5F7);
const _surface = Colors.white;
const _surfaceRaised = Color(0xFFF8F8FB);
const _textPrimary = Color(0xFF111111);
const _textSecondary = Color(0xFF6E6E73);
const _border = Color(0xFFE5E5EA);
const _danger = Color(0xFFFF3B30);
const _accent = Color(0xFF007AFF);

class SkillsFeatureScreen extends StatefulWidget {
  const SkillsFeatureScreen({
    super.key,
    required this.bridge,
    this.initialPage = SkillsPage.manage,
    this.showActionsMenuOnStart = false,
  });

  final OpenCrayHostBridge bridge;
  final SkillsPage initialPage;
  final bool showActionsMenuOnStart;

  @override
  State<SkillsFeatureScreen> createState() => _SkillsFeatureScreenState();
}

class _SkillsFeatureScreenState extends State<SkillsFeatureScreen> {
  late SkillsPage _selectedPage = widget.initialPage;
  late final TextEditingController _searchController = TextEditingController()
    ..addListener(_onQueryChanged);
  StreamSubscription<OpenCraySkillsSnapshot>? _skillsSubscription;

  OpenCraySkillsSnapshot _snapshot = const OpenCraySkillsSnapshot(
    installedSkills: <OpenCrayInstalledSkillSnapshot>[],
    installSources: <OpenCraySkillInstallSourceSnapshot>[],
    suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
  );
  bool _isLoaded = false;
  bool _hasOpenedInitialActions = false;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _hydrate();
    _skillsSubscription = widget.bridge.watchSkillsSnapshot().listen((
      snapshot,
    ) {
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoaded = true;
      });
      _openInitialActionsIfNeeded();
    });
  }

  @override
  void dispose() {
    _skillsSubscription?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final filteredSuggested = _filteredSuggestedSkills();
    final installedSkills = _snapshot.installedSkills;
    return ColoredBox(
      color: _shellBackground,
      child: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'AUTOMATION LIBRARY',
                style: TextStyle(
                  fontSize: 12,
                  height: 1.1,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.2,
                  color: _textSecondary,
                ),
              ),
              const SizedBox(height: 10),
              const Text(
                'Skills',
                style: TextStyle(
                  fontSize: 28,
                  height: 1.08,
                  fontWeight: FontWeight.w600,
                  color: _textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _selectedPage == SkillsPage.manage
                    ? 'Manage the installed skill packages for this workspace.'
                    : 'Install additional skills from the local catalog when one is available.',
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: _textSecondary,
                ),
              ),
              const SizedBox(height: 18),
              _SummaryCard(
                page: _selectedPage,
                enabledCount: installedSkills
                    .where((skill) => skill.isEnabled)
                    .length,
                installedCount: installedSkills.length,
                suggestedCount: filteredSuggested.length,
              ),
              const SizedBox(height: 12),
              _SegmentedControl(
                selectedPage: _selectedPage,
                onChanged: (page) => setState(() => _selectedPage = page),
              ),
              const SizedBox(height: 16),
              if (_selectedPage == SkillsPage.manage)
                _buildManagePage(installedSkills)
              else
                _buildInstallPage(filteredSuggested),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildManagePage(
    List<OpenCrayInstalledSkillSnapshot> installedSkills,
  ) {
    if (!_isLoaded) {
      return const _LoadingCard();
    }
    if (installedSkills.isEmpty) {
      return const _EmptyCard(
        title: 'No installed skills',
        body:
            'This workspace does not currently expose any managed skills through the host runtime.',
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          'Manage skills in this workspace',
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: _textSecondary,
          ),
        ),
        const SizedBox(height: 12),
        DecoratedBox(
          decoration: const BoxDecoration(
            color: _surface,
            borderRadius: BorderRadius.all(Radius.circular(16)),
          ),
          child: Column(
            children: [
              for (var index = 0; index < installedSkills.length; index++) ...[
                _SkillRow(
                  skill: installedSkills[index],
                  onToggle: (enabled) =>
                      _setSkillEnabled(installedSkills[index], enabled),
                  onMore: () => _openActionsSheet(installedSkills[index]),
                ),
                if (index < installedSkills.length - 1)
                  const Divider(
                    height: 1,
                    color: _border,
                    indent: 16,
                    endIndent: 16,
                  ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildInstallPage(
    List<OpenCraySuggestedSkillSnapshot> filteredSuggested,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SearchField(controller: _searchController),
        const SizedBox(height: 14),
        const Text(
          'Install from',
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: _textSecondary,
          ),
        ),
        const SizedBox(height: 10),
        DecoratedBox(
          decoration: const BoxDecoration(
            color: _surface,
            borderRadius: BorderRadius.all(Radius.circular(16)),
          ),
          child: Column(
            children: [
              for (
                var index = 0;
                index < _snapshot.installSources.length;
                index++
              ) ...[
                _SourceRow(
                  source: _snapshot.installSources[index],
                  onTap: () =>
                      _handleInstallSource(_snapshot.installSources[index]),
                ),
                if (index < _snapshot.installSources.length - 1)
                  const Divider(
                    height: 1,
                    color: _border,
                    indent: 16,
                    endIndent: 16,
                  ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        const Text(
          'Suggested',
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: _textSecondary,
          ),
        ),
        const SizedBox(height: 10),
        if (!_isLoaded)
          const _LoadingCard()
        else if (filteredSuggested.isEmpty)
          const _EmptyCard(
            title: 'No catalog skills',
            body:
                'No additional skills are available from the local catalog on this device.',
          )
        else
          DecoratedBox(
            decoration: const BoxDecoration(
              color: _surface,
              borderRadius: BorderRadius.all(Radius.circular(16)),
            ),
            child: Column(
              children: [
                for (
                  var index = 0;
                  index < filteredSuggested.length;
                  index++
                ) ...[
                  _SuggestedRow(
                    item: filteredSuggested[index],
                    onInstall: () =>
                        _installSuggestedSkill(filteredSuggested[index]),
                  ),
                  if (index < filteredSuggested.length - 1)
                    const Divider(
                      height: 1,
                      color: _border,
                      indent: 16,
                      endIndent: 16,
                    ),
                ],
              ],
            ),
          ),
      ],
    );
  }

  List<OpenCraySuggestedSkillSnapshot> _filteredSuggestedSkills() {
    final query = _query.trim().toLowerCase();
    if (query.isEmpty) {
      return _snapshot.suggestedSkills;
    }
    return _snapshot.suggestedSkills
        .where((skill) {
          return skill.name.toLowerCase().contains(query) ||
              skill.description.toLowerCase().contains(query);
        })
        .toList(growable: false);
  }

  Future<void> _hydrate() async {
    try {
      final snapshot = await widget.bridge.loadSkillsSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoaded = true;
      });
      _openInitialActionsIfNeeded();
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoaded = true;
      });
      _showMessage('Failed to load workspace skills from the host runtime.');
    }
  }

  void _onQueryChanged() {
    setState(() {
      _query = _searchController.text;
    });
  }

  void _openInitialActionsIfNeeded() {
    if (!widget.showActionsMenuOnStart || _hasOpenedInitialActions) {
      return;
    }
    if (_snapshot.installedSkills.isEmpty) {
      return;
    }
    _hasOpenedInitialActions = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _openActionsSheet(_snapshot.installedSkills.first);
      }
    });
  }

  Future<void> _setSkillEnabled(
    OpenCrayInstalledSkillSnapshot skill,
    bool enabled,
  ) async {
    final previous = skill.isEnabled;
    setState(() {
      _snapshot = OpenCraySkillsSnapshot(
        installedSkills: _snapshot.installedSkills
            .map(
              (item) => item.id == skill.id
                  ? OpenCrayInstalledSkillSnapshot(
                      id: item.id,
                      name: item.name,
                      description: item.description,
                      isEnabled: enabled,
                      canDelete: item.canDelete,
                      sourceDirectoryPath: item.sourceDirectoryPath,
                    )
                  : item,
            )
            .toList(growable: false),
        installSources: _snapshot.installSources,
        suggestedSkills: _snapshot.suggestedSkills,
      );
    });
    try {
      await widget.bridge.setSkillEnabled(skill.id, enabled);
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = OpenCraySkillsSnapshot(
          installedSkills: _snapshot.installedSkills
              .map(
                (item) => item.id == skill.id
                    ? OpenCrayInstalledSkillSnapshot(
                        id: item.id,
                        name: item.name,
                        description: item.description,
                        isEnabled: previous,
                        canDelete: item.canDelete,
                        sourceDirectoryPath: item.sourceDirectoryPath,
                      )
                    : item,
              )
              .toList(growable: false),
          installSources: _snapshot.installSources,
          suggestedSkills: _snapshot.suggestedSkills,
        );
      });
      _showMessage('Failed to update ${skill.name}.');
    }
  }

  Future<void> _installSuggestedSkill(
    OpenCraySuggestedSkillSnapshot skill,
  ) async {
    try {
      final message = await widget.bridge.installSuggestedSkill(skill.id);
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
    } catch (_) {
      if (mounted) {
        _showMessage('Failed to install ${skill.name}.');
      }
    }
  }

  void _handleInstallSource(OpenCraySkillInstallSourceSnapshot source) {
    _showMessage(source.subtitle);
  }

  Future<void> _openActionsSheet(OpenCrayInstalledSkillSnapshot skill) async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: _surfaceRaised,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 16),
                        decoration: BoxDecoration(
                          color: _border,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      skill.name,
                      style: const TextStyle(
                        fontSize: 20,
                        height: 1.2,
                        fontWeight: FontWeight.w600,
                        color: _textPrimary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      skill.description,
                      style: const TextStyle(
                        fontSize: 14,
                        height: 1.35,
                        color: _textSecondary,
                      ),
                    ),
                    const SizedBox(height: 18),
                    _ActionRow(
                      icon: Icons.visibility_outlined,
                      label: 'Preview instructions',
                      onTap: () {
                        Navigator.of(context).pop();
                        _previewInstructions(skill);
                      },
                    ),
                    _ActionRow(
                      icon: Icons.system_update_alt_rounded,
                      label: 'Update skills',
                      onTap: () {
                        Navigator.of(context).pop();
                        _refreshSkills();
                      },
                    ),
                    _ActionRow(
                      icon: skill.isEnabled
                          ? Icons.toggle_off_outlined
                          : Icons.toggle_on_outlined,
                      label: skill.isEnabled
                          ? 'Disable for this workspace'
                          : 'Enable for this workspace',
                      onTap: () {
                        Navigator.of(context).pop();
                        _setSkillEnabled(skill, !skill.isEnabled);
                      },
                    ),
                    if (skill.canDelete)
                      _ActionRow(
                        icon: Icons.delete_outline_rounded,
                        label: 'Remove skill',
                        color: _danger,
                        onTap: () {
                          Navigator.of(context).pop();
                          _deleteInstalledSkill(skill);
                        },
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _previewInstructions(
    OpenCrayInstalledSkillSnapshot skill,
  ) async {
    try {
      final details = await widget.bridge.loadSkillInstructions(skill.id);
      if (!mounted || details == null) {
        return;
      }
      await showDialog<void>(
        context: context,
        builder: (context) {
          return AlertDialog(
            title: Text(details.name),
            content: SizedBox(
              width: 520,
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      details.description,
                      style: const TextStyle(
                        fontSize: 14,
                        height: 1.35,
                        color: _textSecondary,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      details.sourceDirectoryPath,
                      style: const TextStyle(
                        fontSize: 12,
                        height: 1.3,
                        color: _textSecondary,
                      ),
                    ),
                    const SizedBox(height: 16),
                    SelectableText(
                      details.markdownBody,
                      style: const TextStyle(
                        fontSize: 13,
                        height: 1.4,
                        color: _textPrimary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Close'),
              ),
            ],
          );
        },
      );
    } catch (_) {
      if (mounted) {
        _showMessage('Failed to load ${skill.name} instructions.');
      }
    }
  }

  Future<void> _refreshSkills() async {
    try {
      final message = await widget.bridge.refreshSkills();
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
    } catch (_) {
      if (mounted) {
        _showMessage('Failed to refresh skills.');
      }
    }
  }

  Future<void> _deleteInstalledSkill(
    OpenCrayInstalledSkillSnapshot skill,
  ) async {
    try {
      final message = await widget.bridge.deleteInstalledSkill(skill.id);
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
    } catch (_) {
      if (mounted) {
        _showMessage('Failed to remove ${skill.name}.');
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.page,
    required this.enabledCount,
    required this.installedCount,
    required this.suggestedCount,
  });

  final SkillsPage page;
  final int enabledCount;
  final int installedCount;
  final int suggestedCount;

  @override
  Widget build(BuildContext context) {
    final title = page == SkillsPage.manage ? 'Workspace set' : 'Install ready';
    final subtitle = page == SkillsPage.manage
        ? '$enabledCount of $installedCount installed skills enabled'
        : '$suggestedCount skills available from the local catalog';
    return SizedBox(
      width: double.infinity,
      child: DecoratedBox(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.all(Radius.circular(16)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 17,
                  height: 1.2,
                  fontWeight: FontWeight.w600,
                  color: _textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: _textSecondary,
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
    required this.selectedPage,
    required this.onChanged,
  });

  final SkillsPage selectedPage;
  final ValueChanged<SkillsPage> onChanged;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFECEEF3),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          children: [
            Expanded(
              child: _SegmentButton(
                label: 'Manage',
                selected: selectedPage == SkillsPage.manage,
                onTap: () => onChanged(SkillsPage.manage),
              ),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: _SegmentButton(
                label: 'Install',
                selected: selectedPage == SkillsPage.install,
                onTap: () => onChanged(SkillsPage.install),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SegmentButton extends StatelessWidget {
  const _SegmentButton({
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
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        height: 28,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 14,
            height: 1.1,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            color: const Color(
              0xFF111111,
            ).withValues(alpha: selected ? 1 : 0.72),
          ),
        ),
      ),
    );
  }
}

class _SkillRow extends StatelessWidget {
  const _SkillRow({
    required this.skill,
    required this.onToggle,
    required this.onMore,
  });

  final OpenCrayInstalledSkillSnapshot skill;
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
                  style: const TextStyle(
                    fontSize: 17,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    color: _textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  skill.description,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.3,
                    color: Color(0xFF8E8E93),
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: const Color(0xFFF1F2F6),
              borderRadius: BorderRadius.circular(999),
            ),
            child: IconButton(
              padding: EdgeInsets.zero,
              splashRadius: 18,
              icon: const Icon(Icons.more_horiz_rounded, size: 16),
              color: const Color(0xFF8E8E93),
              onPressed: onMore,
            ),
          ),
          const SizedBox(width: 12),
          _SkillToggle(value: skill.isEnabled, onChanged: onToggle),
        ],
      ),
    );
  }
}

class _SkillToggle extends StatelessWidget {
  const _SkillToggle({required this.value, required this.onChanged});

  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => onChanged(!value),
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        curve: Curves.easeOutCubic,
        width: 50,
        height: 30,
        decoration: BoxDecoration(
          color: value ? const Color(0xFF34C759) : const Color(0xFFD1D1D6),
          borderRadius: BorderRadius.circular(999),
        ),
        padding: const EdgeInsets.all(3),
        child: Align(
          alignment: value ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            width: 24,
            height: 24,
            decoration: const BoxDecoration(
              color: Colors.white,
              shape: BoxShape.circle,
            ),
          ),
        ),
      ),
    );
  }
}

class _SourceRow extends StatelessWidget {
  const _SourceRow({required this.source, required this.onTap});

  final OpenCraySkillInstallSourceSnapshot source;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final titleColor = source.isAvailable
        ? _textPrimary
        : const Color(0xFF8E8E93);
    final accentColor = source.isAvailable ? _accent : const Color(0xFFB0B3B8);
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
                    style: const TextStyle(
                      fontSize: 13,
                      height: 1.3,
                      color: _textSecondary,
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
  const _SuggestedRow({required this.item, required this.onInstall});

  final OpenCraySuggestedSkillSnapshot item;
  final VoidCallback onInstall;

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
                  item.name,
                  style: const TextStyle(
                    fontSize: 16,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    color: _textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  item.description,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.3,
                    color: _textSecondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          InkWell(
            borderRadius: BorderRadius.circular(999),
            onTap: onInstall,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: const Color(0xFFEEF5FF),
                borderRadius: BorderRadius.circular(999),
              ),
              child: const Text(
                'Install',
                style: TextStyle(
                  fontSize: 12,
                  height: 1.1,
                  fontWeight: FontWeight.w600,
                  color: _accent,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.controller});

  final TextEditingController controller;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
        child: Row(
          children: [
            const Icon(
              Icons.search_rounded,
              size: 18,
              color: Color(0xFF8E8E93),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: TextField(
                controller: controller,
                decoration: const InputDecoration(
                  border: InputBorder.none,
                  hintText: 'Search the skill catalog',
                  hintStyle: TextStyle(
                    fontSize: 14,
                    height: 1.2,
                    color: Color(0xFF8E8E93),
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
    this.color = const Color(0xFF111111),
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final Color color;

  @override
  Widget build(BuildContext context) {
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
  const _LoadingCard();

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
      child: Padding(
        padding: EdgeInsets.all(20),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
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
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 16,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: _textPrimary,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              body,
              style: const TextStyle(
                fontSize: 14,
                height: 1.35,
                color: _textSecondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
