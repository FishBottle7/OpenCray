import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
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
    required this.copy,
    this.initialPage = SkillsPage.manage,
    this.showActionsMenuOnStart = false,
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayUiCopy copy;
  final SkillsPage initialPage;
  final bool showActionsMenuOnStart;

  @override
  State<SkillsFeatureScreen> createState() => _SkillsFeatureScreenState();
}

class _SkillsFeatureScreenState extends State<SkillsFeatureScreen> {
  late SkillsPage _selectedPage = widget.initialPage;
  late final TextEditingController _searchController = TextEditingController()
    ..addListener(_onQueryChanged);
  Timer? _searchDebounce;
  StreamSubscription<OpenCraySkillsSnapshot>? _skillsSubscription;

  OpenCraySkillsSnapshot _snapshot = const OpenCraySkillsSnapshot(
    installedSkills: <OpenCrayInstalledSkillSnapshot>[],
    installSources: <OpenCraySkillInstallSourceSnapshot>[],
    suggestedSkills: <OpenCraySuggestedSkillSnapshot>[],
  );
  bool _isLoaded = false;
  bool _isSearching = false;
  bool _hasOpenedInitialActions = false;
  String? _pendingInstallSourceRef;
  String _query = '';
  int _skillsRequestEpoch = 0;

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
      if (_query.trim().isEmpty) {
        setState(() {
          _snapshot = snapshot;
          _isLoaded = true;
          _isSearching = false;
        });
        _openInitialActionsIfNeeded();
      } else {
        _reloadSkillsSnapshot(showErrorMessage: false);
      }
    });
  }

  @override
  void dispose() {
    _skillsSubscription?.cancel();
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final availableSkills = _snapshot.suggestedSkills;
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
              Text(
                widget.copy.skillsEyebrow,
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.1,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0.2,
                  color: _textSecondary,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                widget.copy.skillsTitle,
                style: const TextStyle(
                  fontSize: 28,
                  height: 1.08,
                  fontWeight: FontWeight.w600,
                  color: _textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _selectedPage == SkillsPage.manage
                    ? widget.copy.skillsManageSubtitle
                    : widget.copy.skillsInstallSubtitle,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.35,
                  color: _textSecondary,
                ),
              ),
              const SizedBox(height: 18),
              _SummaryCard(
                copy: widget.copy,
                page: _selectedPage,
                enabledCount: installedSkills
                    .where((skill) => skill.isEnabled)
                    .length,
                installedCount: installedSkills.length,
                suggestedCount: availableSkills.length,
              ),
              const SizedBox(height: 12),
              _SegmentedControl(
                copy: widget.copy,
                selectedPage: _selectedPage,
                onChanged: (page) => setState(() => _selectedPage = page),
              ),
              const SizedBox(height: 16),
              if (_selectedPage == SkillsPage.manage)
                _buildManagePage(installedSkills)
              else
                _buildInstallPage(availableSkills),
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
      return _EmptyCard(
        title: widget.copy.skillsNoInstalledTitle,
        body: widget.copy.skillsNoInstalledBody,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          widget.copy.skillsManageSectionTitle,
          style: const TextStyle(
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
    List<OpenCraySuggestedSkillSnapshot> availableSkills,
  ) {
    final trimmedQuery = _query.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SearchField(
          controller: _searchController,
          hintText: widget.copy.skillsSearchHint,
        ),
        if (_isSearching) ...[
          const SizedBox(height: 10),
          const ClipRRect(
            borderRadius: BorderRadius.all(Radius.circular(999)),
            child: LinearProgressIndicator(minHeight: 3),
          ),
        ],
        const SizedBox(height: 14),
        Text(
          widget.copy.skillsInstallFromTitle,
          style: const TextStyle(
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
        if (trimmedQuery.isNotEmpty) ...[
          const SizedBox(height: 14),
          _DirectInstallCard(
            title: widget.copy.skillsDirectInstallTitle,
            body: widget.copy.skillsDirectInstallBody(trimmedQuery),
            buttonLabel: widget.copy.skillsInstallButton,
            isInstalling: _pendingInstallSourceRef == trimmedQuery,
            onInstall: () => _installFromSource(trimmedQuery),
          ),
        ],
        const SizedBox(height: 14),
        Text(
          trimmedQuery.isEmpty
              ? widget.copy.skillsSuggestedTitle
              : widget.copy.skillsResultsTitle,
          style: const TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: _textSecondary,
          ),
        ),
        const SizedBox(height: 10),
        if (!_isLoaded)
          const _LoadingCard()
        else if (availableSkills.isEmpty)
          _EmptyCard(
            title: trimmedQuery.isEmpty
                ? widget.copy.skillsNoCatalogTitle
                : widget.copy.skillsNoResultsTitle,
            body: trimmedQuery.isEmpty
                ? widget.copy.skillsNoCatalogBody
                : widget.copy.skillsNoResultsBody(trimmedQuery),
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
                  index < availableSkills.length;
                  index++
                ) ...[
                  _SuggestedRow(
                    item: availableSkills[index],
                    installLabel: widget.copy.skillsInstallButton,
                    isInstalling:
                        _pendingInstallSourceRef ==
                        availableSkills[index].sourceRef,
                    onInstall: () =>
                        _installSuggestedSkill(availableSkills[index]),
                  ),
                  if (index < availableSkills.length - 1)
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

  Future<void> _hydrate() async {
    await _reloadSkillsSnapshot(showErrorMessage: true);
  }

  void _onQueryChanged() {
    setState(() {
      _query = _searchController.text;
    });
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 250), () {
      _reloadSkillsSnapshot(showErrorMessage: false);
    });
  }

  Future<void> _reloadSkillsSnapshot({required bool showErrorMessage}) async {
    final requestEpoch = ++_skillsRequestEpoch;
    final query = _query.trim();
    if (mounted) {
      setState(() {
        _isSearching = query.isNotEmpty;
      });
    }
    try {
      final snapshot = await widget.bridge.loadSkillsSnapshot(query: query);
      if (!mounted || requestEpoch != _skillsRequestEpoch) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoaded = true;
        _isSearching = false;
      });
      _openInitialActionsIfNeeded();
    } catch (error) {
      if (!mounted || requestEpoch != _skillsRequestEpoch) {
        return;
      }
      setState(() {
        _isLoaded = true;
        _isSearching = false;
      });
      if (showErrorMessage) {
        _showMessage(_errorMessage(error) ?? widget.copy.skillsLoadFailed);
      }
    }
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
      _showMessage(widget.copy.skillsUpdateFailed(skill.name));
    }
  }

  Future<void> _installSuggestedSkill(
    OpenCraySuggestedSkillSnapshot skill,
  ) async {
    await _installFromSource(skill.sourceRef, fallbackName: skill.name);
  }

  Future<void> _installFromSource(
    String sourceRef, {
    String? fallbackName,
  }) async {
    final normalizedSourceRef = sourceRef.trim();
    if (normalizedSourceRef.isEmpty) {
      return;
    }
    setState(() {
      _pendingInstallSourceRef = normalizedSourceRef;
    });
    try {
      final message = await widget.bridge.installSkillSource(
        normalizedSourceRef,
      );
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
      if (mounted && _query.trim() == normalizedSourceRef) {
        _searchController.clear();
      }
    } catch (error) {
      if (mounted) {
        _showMessage(
          _errorMessage(error) ??
              widget.copy.skillsInstallFailed(
                fallbackName ?? normalizedSourceRef,
              ),
        );
      }
    } finally {
      if (mounted && _pendingInstallSourceRef == normalizedSourceRef) {
        setState(() {
          _pendingInstallSourceRef = null;
        });
      }
    }
  }

  void _handleInstallSource(OpenCraySkillInstallSourceSnapshot source) {
    if (source.id == 'curated-library' && _searchController.text.isNotEmpty) {
      _searchController.clear();
    }
    _showMessage(source.subtitle);
  }

  String? _errorMessage(Object error) {
    if (error is PlatformException) {
      final message = error.message?.trim();
      if (message != null && message.isNotEmpty) {
        return message;
      }
    }
    return null;
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
                      label: widget.copy.skillsPreviewInstructions,
                      onTap: () {
                        Navigator.of(context).pop();
                        _previewInstructions(skill);
                      },
                    ),
                    _ActionRow(
                      icon: Icons.system_update_alt_rounded,
                      label: widget.copy.skillsUpdateAction,
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
                          ? widget.copy.skillsDisableForWorkspace
                          : widget.copy.skillsEnableForWorkspace,
                      onTap: () {
                        Navigator.of(context).pop();
                        _setSkillEnabled(skill, !skill.isEnabled);
                      },
                    ),
                    if (skill.canDelete)
                      _ActionRow(
                        icon: Icons.delete_outline_rounded,
                        label: widget.copy.skillsRemoveAction,
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
                child: Text(widget.copy.skillsClose),
              ),
            ],
          );
        },
      );
    } catch (_) {
      if (mounted) {
        _showMessage(widget.copy.skillsPreviewFailed(skill.name));
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
        _showMessage(widget.copy.skillsRefreshFailed);
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
        _showMessage(widget.copy.skillsRemoveFailed(skill.name));
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
    required this.copy,
    required this.selectedPage,
    required this.onChanged,
  });

  final OpenCrayUiCopy copy;
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
                label: copy.skillsManageTab,
                selected: selectedPage == SkillsPage.manage,
                onTap: () => onChanged(SkillsPage.manage),
              ),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: _SegmentButton(
                label: copy.skillsInstallTab,
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
  const _SuggestedRow({
    required this.item,
    required this.installLabel,
    required this.isInstalling,
    required this.onInstall,
  });

  final OpenCraySuggestedSkillSnapshot item;
  final String installLabel;
  final bool isInstalling;
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
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF1F2F6),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    item.sourceLabel,
                    style: const TextStyle(
                      fontSize: 11,
                      height: 1.1,
                      fontWeight: FontWeight.w600,
                      color: _textSecondary,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
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
            onTap: isInstalling ? null : onInstall,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: isInstalling
                    ? const Color(0xFFF1F2F6)
                    : const Color(0xFFEEF5FF),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                isInstalling ? '...' : installLabel,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.1,
                  fontWeight: FontWeight.w600,
                  color: isInstalling ? _textSecondary : _accent,
                ),
              ),
            ),
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
    required this.buttonLabel,
    required this.isInstalling,
    required this.onInstall,
  });

  final String title;
  final String body;
  final String buttonLabel;
  final bool isInstalling;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.bolt_rounded, color: _accent, size: 20),
            const SizedBox(width: 12),
            Expanded(
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
                  const SizedBox(height: 4),
                  Text(
                    body,
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
              onTap: isInstalling ? null : onInstall,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: isInstalling
                      ? const Color(0xFFF1F2F6)
                      : const Color(0xFFEEF5FF),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  isInstalling ? '...' : buttonLabel,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.1,
                    fontWeight: FontWeight.w600,
                    color: isInstalling ? _textSecondary : _accent,
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
                decoration: InputDecoration(
                  border: InputBorder.none,
                  hintText: hintText,
                  hintStyle: const TextStyle(
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
