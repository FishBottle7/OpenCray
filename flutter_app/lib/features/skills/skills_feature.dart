import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_tokens.dart';
import '../../core/design/opencray_widgets.dart';
import '../../core/models/opencray_skills_snapshot.dart';

enum SkillsPage { manage, install }

enum _SkillInstallVisualState { idle, installing, installed, failed }

enum _InstalledSkillLifecycleState {
  updating,
  updated,
  deleting,
  deleted,
  enabling,
  enabled,
  disabling,
  disabled,
  failed,
}

const _shellBackground = OpenCrayColors.shellBackground;
const _surface = OpenCrayColors.surface;
const _surfaceRaised = OpenCrayColors.surfaceSubtle;
const _textPrimary = OpenCrayColors.textPrimary;
const _textSecondary = OpenCrayColors.textSecondary;
const _border = OpenCrayColors.divider;
const _danger = OpenCrayColors.danger;
const _accent = OpenCrayColors.primary;
const _initialSearchResultLimit = 8;
const _expandedSearchResultLimit = 20;
final RegExp _windowsAbsolutePathPattern = RegExp(r'^[A-Za-z]:[\\/].+');
final RegExp _explicitRepoSourcePattern = RegExp(
  r'^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:[@#][^\s]+)?$',
);

class SkillsFeatureScreen extends StatefulWidget {
  const SkillsFeatureScreen({
    super.key,
    required this.bridge,
    required this.copy,
    this.initialPage = SkillsPage.manage,
    this.showActionsMenuOnStart = false,
    this.isTabActive = true,
    this.autoRefreshPollInterval = const Duration(seconds: 2),
  });

  final OpenCrayHostBridge bridge;
  final OpenCrayUiCopy copy;
  final SkillsPage initialPage;
  final bool showActionsMenuOnStart;
  final bool isTabActive;
  final Duration autoRefreshPollInterval;

  @override
  State<SkillsFeatureScreen> createState() => _SkillsFeatureScreenState();
}

class _SkillsFeatureScreenState extends State<SkillsFeatureScreen>
    with WidgetsBindingObserver {
  late SkillsPage _selectedPage = widget.initialPage;
  late final TextEditingController _searchController = TextEditingController()
    ..addListener(_onQueryChanged);
  Timer? _searchDebounce;
  Timer? _autoRefreshTimer;
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
  final Set<String> _recentlyInstalledSourceRefs = <String>{};
  final Set<String> _failedInstallSourceRefs = <String>{};
  final Map<String, _InstalledSkillLifecycleState>
  _installedSkillLifecycleById = <String, _InstalledSkillLifecycleState>{};
  final Set<String> _locallyRemovedInstalledSkillIds = <String>{};
  String _query = '';
  int _searchResultLimit = _initialSearchResultLimit;
  int _skillsRequestEpoch = 0;
  late AppLifecycleState _appLifecycleState;
  bool _isReloadInFlight = false;
  bool _hasQueuedReload = false;
  bool _queuedReloadShowErrorMessage = false;
  bool _queuedReloadShowProgressIndicator = false;

  @override
  void initState() {
    super.initState();
    _appLifecycleState =
        WidgetsBinding.instance.lifecycleState ?? AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    unawaited(_hydrate());
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
        unawaited(
          _reloadSkillsSnapshot(
            showErrorMessage: false,
            showProgressIndicator: false,
          ),
        );
      }
    });
    _syncAutoRefreshTimer();
  }

  @override
  void didUpdateWidget(covariant SkillsFeatureScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.isTabActive &&
        widget.isTabActive &&
        _appLifecycleState == AppLifecycleState.resumed) {
      _scheduleSilentRefresh();
    }
    if (oldWidget.isTabActive != widget.isTabActive ||
        oldWidget.autoRefreshPollInterval != widget.autoRefreshPollInterval) {
      _syncAutoRefreshTimer();
    }
  }

  @override
  void dispose() {
    _autoRefreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _skillsSubscription?.cancel();
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _appLifecycleState = state;
    _syncAutoRefreshTimer();
    if (state == AppLifecycleState.resumed && widget.isTabActive) {
      _scheduleSilentRefresh();
    }
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
                  fontSize: 11,
                  height: 1.1,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.1,
                  color: OpenCrayColors.textTertiary,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                widget.copy.skillsTitle,
                style: const TextStyle(
                  fontSize: 28,
                  height: 1.08,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.6,
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
              OpenCrayDirectionalSwitcher(
                activeKey: ValueKey<String>(
                  'skills-page-${_selectedPage.name}',
                ),
                direction: _selectedPage == SkillsPage.install ? 1 : -1,
                child: _selectedPage == SkillsPage.manage
                    ? _buildManagePage(installedSkills)
                    : _buildInstallPage(availableSkills),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildManagePage(
    List<OpenCrayInstalledSkillSnapshot> installedSkills,
  ) {
    final List<OpenCrayInstalledSkillSnapshot> visibleInstalledSkills =
        installedSkills
            .where(
              (skill) => !_locallyRemovedInstalledSkillIds.contains(skill.id),
            )
            .toList(growable: false);
    if (!_isLoaded) {
      return const _LoadingCard();
    }
    if (visibleInstalledSkills.isEmpty) {
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
          decoration: BoxDecoration(
            color: _surface,
            borderRadius: const BorderRadius.all(Radius.circular(16)),
            border: Border.all(color: OpenCrayColors.divider),
            boxShadow: OpenCrayShadows.card,
          ),
          child: Column(
            children: [
              for (
                var index = 0;
                index < visibleInstalledSkills.length;
                index++
              ) ...[
                _SkillRow(
                  skill: visibleInstalledSkills[index],
                  lifecycleState:
                      _installedSkillLifecycleById[visibleInstalledSkills[index]
                          .id],
                  copy: widget.copy,
                  onToggle: (enabled) =>
                      _setSkillEnabled(visibleInstalledSkills[index], enabled),
                  onMore: () =>
                      _openActionsSheet(visibleInstalledSkills[index]),
                ),
                if (index < visibleInstalledSkills.length - 1)
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
    final hasQuery = trimmedQuery.isNotEmpty;
    final showDirectInstall = _looksLikeExplicitInstallSource(trimmedQuery);
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
        if (hasQuery) ...[
          const SizedBox(height: 14),
          _buildSuggestedSkillsSection(
            title: widget.copy.skillsResultsTitle,
            emptyTitle: widget.copy.skillsNoResultsTitle,
            emptyBody: widget.copy.skillsNoResultsBody(trimmedQuery),
            availableSkills: availableSkills,
            showMore:
                _snapshot.suggestedSkillsMayHaveMore &&
                _searchResultLimit < _expandedSearchResultLimit,
          ),
          if (showDirectInstall) ...[
            const SizedBox(height: 14),
            _DirectInstallCard(
              title: widget.copy.skillsDirectInstallTitle,
              body: widget.copy.skillsDirectInstallBody(trimmedQuery),
              installLabel: widget.copy.skillsInstallButton,
              installingLabel: widget.copy.skillsInstallingButton,
              installedLabel: widget.copy.skillsInstalledButton,
              retryLabel: widget.copy.skillsRetryInstallButton,
              installState: _installVisualStateFor(trimmedQuery),
              onInstall: () => _installFromSource(trimmedQuery),
            ),
          ],
          const SizedBox(height: 14),
          _buildInstallSourcesSection(),
        ] else ...[
          const SizedBox(height: 14),
          _buildInstallSourcesSection(),
          const SizedBox(height: 14),
          _buildSuggestedSkillsSection(
            title: widget.copy.skillsSuggestedTitle,
            emptyTitle: widget.copy.skillsNoCatalogTitle,
            emptyBody: widget.copy.skillsNoCatalogBody,
            availableSkills: availableSkills,
          ),
        ],
      ],
    );
  }

  Widget _buildInstallSourcesSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
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
          decoration: BoxDecoration(
            color: _surface,
            borderRadius: const BorderRadius.all(Radius.circular(16)),
            border: Border.all(color: OpenCrayColors.divider),
            boxShadow: OpenCrayShadows.card,
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
                  onTap: () {
                    _handleInstallSource(_snapshot.installSources[index]);
                  },
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
      ],
    );
  }

  Widget _buildSuggestedSkillsSection({
    required String title,
    required String emptyTitle,
    required String emptyBody,
    required List<OpenCraySuggestedSkillSnapshot> availableSkills,
    bool showMore = false,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          title,
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
          _EmptyCard(title: emptyTitle, body: emptyBody)
        else ...[
          DecoratedBox(
            decoration: BoxDecoration(
              color: _surface,
              borderRadius: const BorderRadius.all(Radius.circular(16)),
              border: Border.all(color: OpenCrayColors.divider),
              boxShadow: OpenCrayShadows.card,
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
                    installsLabel: availableSkills[index].installs == null
                        ? null
                        : widget.copy.skillsInstallsCount(
                            availableSkills[index].installs!,
                          ),
                    previewLabel: widget.copy.skillsPreviewButton,
                    installLabel: widget.copy.skillsInstallButton,
                    installingLabel: widget.copy.skillsInstallingButton,
                    installedLabel: widget.copy.skillsInstalledButton,
                    retryLabel: widget.copy.skillsRetryInstallButton,
                    installState: _installVisualStateFor(
                      availableSkills[index].sourceRef,
                    ),
                    onPreview: () =>
                        _previewSuggestedSkill(availableSkills[index]),
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
          if (showMore) ...[
            const SizedBox(height: 10),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: _isSearching ? null : _loadMoreSearchResults,
                child: Text(widget.copy.skillsShowMoreResults),
              ),
            ),
          ],
        ],
      ],
    );
  }

  Future<void> _hydrate() async {
    await _reloadSkillsSnapshot(showErrorMessage: true);
  }

  void _onQueryChanged() {
    setState(() {
      _query = _searchController.text;
      _searchResultLimit = _initialSearchResultLimit;
    });
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 250), () {
      unawaited(
        _reloadSkillsSnapshot(
          showErrorMessage: false,
          showProgressIndicator: true,
        ),
      );
    });
  }

  Future<void> _reloadSkillsSnapshot({
    required bool showErrorMessage,
    bool showProgressIndicator = true,
  }) async {
    final requestEpoch = ++_skillsRequestEpoch;
    if (_isReloadInFlight) {
      _queueReload(
        showErrorMessage: showErrorMessage,
        showProgressIndicator: showProgressIndicator,
      );
      return;
    }
    return _runSkillsReload(
      requestEpoch: requestEpoch,
      showErrorMessage: showErrorMessage,
      showProgressIndicator: showProgressIndicator,
    );
  }

  Future<void> _runSkillsReload({
    required int requestEpoch,
    required bool showErrorMessage,
    required bool showProgressIndicator,
  }) async {
    _isReloadInFlight = true;
    final query = _query.trim();
    if (mounted && showProgressIndicator) {
      setState(() {
        _isSearching = query.isNotEmpty;
      });
    }
    try {
      final snapshot = await widget.bridge.loadSkillsSnapshot(
        query: query,
        suggestedLimit: query.isEmpty ? null : _searchResultLimit,
      );
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
    } finally {
      _isReloadInFlight = false;
      _flushQueuedReload();
    }
  }

  bool get _shouldAutoRefresh =>
      widget.isTabActive &&
      _appLifecycleState == AppLifecycleState.resumed &&
      widget.autoRefreshPollInterval > Duration.zero;

  void _scheduleSilentRefresh() {
    if (!mounted || !widget.isTabActive) {
      return;
    }
    unawaited(
      _reloadSkillsSnapshot(
        showErrorMessage: false,
        showProgressIndicator: false,
      ),
    );
  }

  void _syncAutoRefreshTimer() {
    _autoRefreshTimer?.cancel();
    if (!_shouldAutoRefresh) {
      _autoRefreshTimer = null;
      return;
    }
    _autoRefreshTimer = Timer.periodic(widget.autoRefreshPollInterval, (_) {
      _scheduleSilentRefresh();
    });
  }

  void _queueReload({
    required bool showErrorMessage,
    required bool showProgressIndicator,
  }) {
    _hasQueuedReload = true;
    _queuedReloadShowErrorMessage =
        _queuedReloadShowErrorMessage || showErrorMessage;
    _queuedReloadShowProgressIndicator =
        _queuedReloadShowProgressIndicator || showProgressIndicator;
  }

  void _flushQueuedReload() {
    if (!mounted || !_hasQueuedReload || _isReloadInFlight) {
      return;
    }
    final showErrorMessage = _queuedReloadShowErrorMessage;
    final showProgressIndicator = _queuedReloadShowProgressIndicator;
    _hasQueuedReload = false;
    _queuedReloadShowErrorMessage = false;
    _queuedReloadShowProgressIndicator = false;
    unawaited(
      _reloadSkillsSnapshot(
        showErrorMessage: showErrorMessage,
        showProgressIndicator: showProgressIndicator,
      ),
    );
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
    _setInstalledSkillLifecycle(
      skill.id,
      enabled
          ? _InstalledSkillLifecycleState.enabling
          : _InstalledSkillLifecycleState.disabling,
    );
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
        suggestedSkillsMayHaveMore: _snapshot.suggestedSkillsMayHaveMore,
      );
    });
    try {
      await widget.bridge.setSkillEnabled(skill.id, enabled);
      if (mounted) {
        _setInstalledSkillLifecycle(
          skill.id,
          enabled
              ? _InstalledSkillLifecycleState.enabled
              : _InstalledSkillLifecycleState.disabled,
        );
        _scheduleInstalledSkillLifecycleClear(skill.id);
      }
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
          suggestedSkillsMayHaveMore: _snapshot.suggestedSkillsMayHaveMore,
        );
        _installedSkillLifecycleById[skill.id] =
            _InstalledSkillLifecycleState.failed;
      });
      _showMessage(widget.copy.skillsUpdateFailed(skill.name));
    }
  }

  void _setInstalledSkillLifecycle(
    String skillId,
    _InstalledSkillLifecycleState state,
  ) {
    if (!mounted) {
      return;
    }
    setState(() {
      _installedSkillLifecycleById[skillId] = state;
    });
  }

  void _scheduleInstalledSkillLifecycleClear(String skillId) {
    Future<void>.delayed(
      OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
      () {
        if (!mounted) {
          return;
        }
        setState(() {
          _installedSkillLifecycleById.remove(skillId);
        });
      },
    );
  }

  void _scheduleInstalledSkillRemoval(String skillId) {
    Future<void>.delayed(
      OpenCrayMotion.resolve(context, OpenCrayMotion.panel),
      () {
        if (!mounted) {
          return;
        }
        setState(() {
          _installedSkillLifecycleById.remove(skillId);
          _locallyRemovedInstalledSkillIds.add(skillId);
        });
      },
    );
  }

  Future<void> _installSuggestedSkill(
    OpenCraySuggestedSkillSnapshot skill,
  ) async {
    await _installFromSource(skill.sourceRef, fallbackName: skill.name);
  }

  _SkillInstallVisualState _installVisualStateFor(String sourceRef) {
    final normalizedSourceRef = sourceRef.trim();
    if (normalizedSourceRef.isEmpty) {
      return _SkillInstallVisualState.idle;
    }
    if (_pendingInstallSourceRef == normalizedSourceRef) {
      return _SkillInstallVisualState.installing;
    }
    if (_failedInstallSourceRefs.contains(normalizedSourceRef)) {
      return _SkillInstallVisualState.failed;
    }
    if (_recentlyInstalledSourceRefs.contains(normalizedSourceRef)) {
      return _SkillInstallVisualState.installed;
    }
    return _SkillInstallVisualState.idle;
  }

  Future<void> _loadMoreSearchResults() async {
    if (_searchResultLimit >= _expandedSearchResultLimit) {
      return;
    }
    setState(() {
      _searchResultLimit = _expandedSearchResultLimit;
    });
    await _reloadSkillsSnapshot(
      showErrorMessage: true,
      showProgressIndicator: true,
    );
  }

  Future<void> _installFromSource(
    String sourceRef, {
    String? fallbackName,
    String selectedSkillName = '',
    bool showMessage = true,
  }) async {
    final normalizedSourceRef = sourceRef.trim();
    final normalizedSelectedSkillName = selectedSkillName.trim();
    if (normalizedSourceRef.isEmpty) {
      return;
    }
    setState(() {
      _pendingInstallSourceRef = normalizedSourceRef;
      _failedInstallSourceRefs.remove(normalizedSourceRef);
      _recentlyInstalledSourceRefs.remove(normalizedSourceRef);
    });
    try {
      final message = await widget.bridge.installSkillSource(
        normalizedSourceRef,
        selectedSkillName: normalizedSelectedSkillName,
      );
      if (mounted && showMessage && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
      if (mounted) {
        setState(() {
          _recentlyInstalledSourceRefs.add(normalizedSourceRef);
        });
      }
      return;
    } catch (error) {
      if (mounted) {
        setState(() {
          _failedInstallSourceRefs.add(normalizedSourceRef);
        });
      }
      if (mounted && showMessage) {
        _showMessage(
          _errorMessage(error) ??
              widget.copy.skillsInstallFailed(
                fallbackName ??
                    (normalizedSelectedSkillName.isNotEmpty
                        ? normalizedSelectedSkillName
                        : normalizedSourceRef),
              ),
        );
      }
      return;
    } finally {
      if (mounted && _pendingInstallSourceRef == normalizedSourceRef) {
        setState(() {
          _pendingInstallSourceRef = null;
        });
      }
    }
  }

  Future<void> _handleInstallSource(
    OpenCraySkillInstallSourceSnapshot source,
  ) async {
    if (!source.isAvailable) {
      _showMessage(source.subtitle);
      return;
    }
    if (source.id == 'curated-library') {
      if (_searchController.text.isNotEmpty) {
        _searchController.clear();
      }
      return;
    }
    final sourceRef = await _promptInstallSource(source);
    if (!mounted || sourceRef == null) {
      return;
    }
    final normalizedSourceRef = sourceRef.trim();
    if (normalizedSourceRef.isEmpty) {
      return;
    }
    try {
      final inspection = await widget.bridge.inspectSkillSource(
        normalizedSourceRef,
      );
      if (!mounted) {
        return;
      }
      if (inspection.candidates.isEmpty) {
        _showMessage(
          widget.copy.skillsNoInstallableSkills(normalizedSourceRef),
        );
        return;
      }
      if (inspection.candidates.length == 1) {
        final candidate = inspection.candidates.single;
        await _installFromSource(
          normalizedSourceRef,
          fallbackName: candidate.name,
          selectedSkillName: candidate.name,
        );
        return;
      }
      final selectedSkillNames = await _promptSkillSelection(inspection);
      if (!mounted ||
          selectedSkillNames == null ||
          selectedSkillNames.isEmpty) {
        return;
      }
      await _installSelectedSkills(
        sourceRef: normalizedSourceRef,
        selectedSkillNames: selectedSkillNames,
      );
    } catch (error) {
      if (mounted) {
        _showMessage(
          _errorMessage(error) ??
              widget.copy.skillsInspectFailed(normalizedSourceRef),
        );
      }
    }
  }

  bool _looksLikeExplicitInstallSource(String query) {
    final normalizedQuery = query.trim();
    if (normalizedQuery.isEmpty) {
      return false;
    }
    return normalizedQuery.startsWith('http://') ||
        normalizedQuery.startsWith('https://') ||
        normalizedQuery.startsWith('github:') ||
        normalizedQuery.startsWith('gitlab:') ||
        normalizedQuery.startsWith('.') ||
        normalizedQuery.startsWith('/') ||
        normalizedQuery.startsWith('\\') ||
        normalizedQuery.contains('\\') ||
        _windowsAbsolutePathPattern.hasMatch(normalizedQuery) ||
        _explicitRepoSourcePattern.hasMatch(normalizedQuery);
  }

  Future<String?> _promptInstallSource(
    OpenCraySkillInstallSourceSnapshot source,
  ) async {
    final initialValue = _searchController.text.trim();
    var draftValue = initialValue;
    final submitted = await showDialog<String>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(widget.copy.skillsInspectSourceTitle(source.title)),
          content: SizedBox(
            width: 520,
            child: TextFormField(
              initialValue: initialValue,
              autofocus: true,
              decoration: InputDecoration(
                labelText: widget.copy.skillsSourceInputLabel(source.title),
                hintText: widget.copy.skillsSourceInputHint(source.id),
              ),
              onChanged: (value) {
                draftValue = value;
              },
              onFieldSubmitted: (value) {
                Navigator.of(context).pop(value.trim());
              },
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(widget.copy.skillsCancelAction),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(draftValue.trim()),
              child: Text(widget.copy.skillsInspectButton),
            ),
          ],
        );
      },
    );
    return submitted?.trim().isEmpty == true ? null : submitted?.trim();
  }

  Future<List<String>?> _promptSkillSelection(
    OpenCraySkillSourceInspectionSnapshot inspection,
  ) async {
    final selectedNames = inspection.candidates
        .map((candidate) => candidate.name)
        .toSet();
    return showDialog<List<String>>(
      context: context,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setState) {
            final allSelected =
                selectedNames.length == inspection.candidates.length;
            return AlertDialog(
              title: Text(
                widget.copy.skillsSelectSkillsTitle(inspection.sourceRef),
              ),
              content: SizedBox(
                width: 520,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 420),
                  child: SingleChildScrollView(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.copy.skillsSelectSkillsBody(
                            inspection.sourceRef,
                          ),
                          style: const TextStyle(
                            fontSize: 14,
                            height: 1.35,
                            color: _textSecondary,
                          ),
                        ),
                        const SizedBox(height: 12),
                        CheckboxListTile(
                          contentPadding: EdgeInsets.zero,
                          value: allSelected,
                          controlAffinity: ListTileControlAffinity.leading,
                          title: Text(widget.copy.skillsSelectAllAction),
                          onChanged: (_) {
                            setState(() {
                              if (allSelected) {
                                selectedNames.clear();
                              } else {
                                selectedNames
                                  ..clear()
                                  ..addAll(
                                    inspection.candidates.map(
                                      (candidate) => candidate.name,
                                    ),
                                  );
                              }
                            });
                          },
                        ),
                        const Divider(height: 1, color: _border),
                        for (final candidate in inspection.candidates)
                          CheckboxListTile(
                            contentPadding: EdgeInsets.zero,
                            value: selectedNames.contains(candidate.name),
                            controlAffinity: ListTileControlAffinity.leading,
                            title: Text(candidate.name),
                            subtitle: Text(
                              candidate.description.isEmpty
                                  ? candidate.relativePath
                                  : '${candidate.description}\n${candidate.relativePath}',
                            ),
                            onChanged: (_) {
                              setState(() {
                                if (selectedNames.contains(candidate.name)) {
                                  selectedNames.remove(candidate.name);
                                } else {
                                  selectedNames.add(candidate.name);
                                }
                              });
                            },
                          ),
                      ],
                    ),
                  ),
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(widget.copy.skillsCancelAction),
                ),
                FilledButton(
                  onPressed: selectedNames.isEmpty
                      ? null
                      : () {
                          final orderedSelection = inspection.candidates
                              .map((candidate) => candidate.name)
                              .where(selectedNames.contains)
                              .toList(growable: false);
                          Navigator.of(context).pop(orderedSelection);
                        },
                  child: Text(
                    widget.copy.skillsInstallSelectedAction(
                      selectedNames.length,
                    ),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Future<void> _installSelectedSkills({
    required String sourceRef,
    required List<String> selectedSkillNames,
  }) async {
    final normalizedSelectedSkillNames = selectedSkillNames
        .map((skillName) => skillName.trim())
        .where((skillName) => skillName.isNotEmpty)
        .toSet()
        .toList(growable: false);
    if (normalizedSelectedSkillNames.isEmpty) {
      return;
    }
    try {
      await widget.bridge.installSkillSourceBatch(
        sourceRef,
        selectedSkillNames: normalizedSelectedSkillNames,
      );
      if (!mounted) {
        return;
      }
      _showMessage(
        widget.copy.skillsInstallBatchSummary(
          normalizedSelectedSkillNames.length,
          normalizedSelectedSkillNames.length,
        ),
      );
    } catch (error) {
      if (!mounted) {
        return;
      }
      _showMessage(
        _errorMessage(error) ?? widget.copy.skillsInstallFailed(sourceRef),
      );
    }
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
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: _surfaceRaised,
                borderRadius: const BorderRadius.all(Radius.circular(24)),
                border: Border.all(color: OpenCrayColors.divider),
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
                          color: OpenCrayColors.outline,
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
                        _updateInstalledSkill(skill);
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
      await _showSkillInstructionsDialog(details);
    } catch (_) {
      if (mounted) {
        _showMessage(widget.copy.skillsPreviewFailed(skill.name));
      }
    }
  }

  Future<void> _previewSuggestedSkill(
    OpenCraySuggestedSkillSnapshot skill,
  ) async {
    try {
      final details = await widget.bridge.loadSuggestedSkillInstructions(
        skill.sourceRef,
        selectedSkillName: skill.name,
      );
      if (!mounted || details == null) {
        return;
      }
      await _showSkillInstructionsDialog(details);
    } catch (_) {
      if (mounted) {
        _showMessage(widget.copy.skillsSuggestedPreviewFailed(skill.name));
      }
    }
  }

  Future<void> _showSkillInstructionsDialog(
    OpenCraySkillInstructionsSnapshot details,
  ) {
    return showDialog<void>(
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
  }

  Future<void> _updateInstalledSkill(
    OpenCrayInstalledSkillSnapshot skill,
  ) async {
    _setInstalledSkillLifecycle(
      skill.id,
      _InstalledSkillLifecycleState.updating,
    );
    try {
      final message = await widget.bridge.updateInstalledSkill(skill.id);
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
      if (mounted) {
        _setInstalledSkillLifecycle(
          skill.id,
          _InstalledSkillLifecycleState.updated,
        );
        _scheduleInstalledSkillLifecycleClear(skill.id);
      }
    } catch (error) {
      if (mounted) {
        _setInstalledSkillLifecycle(
          skill.id,
          _InstalledSkillLifecycleState.failed,
        );
        _showMessage(
          _errorMessage(error) ?? widget.copy.skillsUpdateFailed(skill.name),
        );
      }
    }
  }

  Future<void> _deleteInstalledSkill(
    OpenCrayInstalledSkillSnapshot skill,
  ) async {
    _setInstalledSkillLifecycle(
      skill.id,
      _InstalledSkillLifecycleState.deleting,
    );
    try {
      final message = await widget.bridge.deleteInstalledSkill(skill.id);
      if (mounted && message != null && message.isNotEmpty) {
        _showMessage(message);
      }
      if (mounted) {
        _setInstalledSkillLifecycle(
          skill.id,
          _InstalledSkillLifecycleState.deleted,
        );
        _scheduleInstalledSkillRemoval(skill.id);
      }
    } catch (_) {
      if (mounted) {
        _setInstalledSkillLifecycle(
          skill.id,
          _InstalledSkillLifecycleState.failed,
        );
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
        decoration: BoxDecoration(
          color: _surface,
          borderRadius: const BorderRadius.all(Radius.circular(16)),
          border: Border.all(color: OpenCrayColors.divider),
          boxShadow: OpenCrayShadows.card,
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
                  letterSpacing: -0.2,
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
    final bool installSelected = selectedPage == SkillsPage.install;
    return SizedBox(
      height: 36,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: OpenCrayColors.surfaceSunken,
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
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(999),
                        boxShadow: OpenCrayShadows.card,
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
                  ? OpenCrayColors.textPrimary
                  : OpenCrayColors.textSecondary,
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
                  style: const TextStyle(
                    fontSize: 17,
                    height: 1.2,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.2,
                    color: _textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  skill.description,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.3,
                    color: OpenCrayColors.textTertiary,
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
              color: OpenCrayColors.surfaceMuted,
              borderRadius: BorderRadius.circular(999),
            ),
            child: IconButton(
              padding: EdgeInsets.zero,
              splashRadius: 18,
              icon: const Icon(Icons.more_horiz_rounded, size: 16),
              color: OpenCrayColors.textTertiary,
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
        ? _danger
        : isDone
        ? OpenCrayColors.success
        : _textSecondary;
    final Color surfaceColor = isFailed
        ? OpenCrayColors.dangerTint
        : isDone
        ? OpenCrayColors.successTint
        : OpenCrayColors.surfaceMuted;
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
    return GestureDetector(
      onTap: onChanged == null ? null : () => onChanged!(!value),
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
        curve: OpenCrayMotion.enter,
        width: 50,
        height: 30,
        decoration: BoxDecoration(
          color: value ? OpenCrayColors.primary : OpenCrayColors.surfaceSunken,
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
        : OpenCrayColors.textTertiary;
    final accentColor = source.isAvailable
        ? _accent
        : OpenCrayColors.textTertiary;
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
      _SkillInstallVisualState.installing => OpenCrayColors.surfaceMuted,
      _SkillInstallVisualState.installed => OpenCrayColors.successTint,
      _SkillInstallVisualState.failed => OpenCrayColors.dangerTint,
      _SkillInstallVisualState.idle => OpenCrayColors.primaryTint,
    };
    final Color installTextColor = switch (installState) {
      _SkillInstallVisualState.installing => _textSecondary,
      _SkillInstallVisualState.installed => OpenCrayColors.success,
      _SkillInstallVisualState.failed => OpenCrayColors.danger,
      _SkillInstallVisualState.idle => _accent,
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
                        color: OpenCrayColors.surfaceMuted,
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
                    if (installsLabel != null)
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 3,
                        ),
                        decoration: BoxDecoration(
                          color: OpenCrayColors.primaryTint,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          installsLabel!,
                          style: const TextStyle(
                            fontSize: 11,
                            height: 1.1,
                            fontWeight: FontWeight.w600,
                            color: _accent,
                          ),
                        ),
                      ),
                  ],
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
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: onPreview,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: OpenCrayColors.surfaceMuted,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    previewLabel,
                    style: const TextStyle(
                      fontSize: 12,
                      height: 1.1,
                      fontWeight: FontWeight.w600,
                      color: _textSecondary,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: isInstalling || isInstalled ? null : onInstall,
                child: AnimatedContainer(
                  key: ValueKey<String>(
                    'skills-install-action-${item.sourceRef}-${installState.name}',
                  ),
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.micro,
                  ),
                  curve: OpenCrayMotion.enter,
                  constraints: const BoxConstraints(minWidth: 76),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: installBackground,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: didFail
                          ? OpenCrayColors.danger.withValues(alpha: 0.22)
                          : Colors.transparent,
                    ),
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
      _SkillInstallVisualState.installing => OpenCrayColors.surfaceMuted,
      _SkillInstallVisualState.installed => OpenCrayColors.successTint,
      _SkillInstallVisualState.failed => OpenCrayColors.dangerTint,
      _SkillInstallVisualState.idle => OpenCrayColors.primaryTint,
    };
    final Color installTextColor = switch (installState) {
      _SkillInstallVisualState.installing => _textSecondary,
      _SkillInstallVisualState.installed => OpenCrayColors.success,
      _SkillInstallVisualState.failed => OpenCrayColors.danger,
      _SkillInstallVisualState.idle => _accent,
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: const BorderRadius.all(Radius.circular(16)),
        border: Border.all(color: OpenCrayColors.divider),
        boxShadow: OpenCrayShadows.card,
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
              onTap: isInstalling || isInstalled ? null : onInstall,
              child: AnimatedContainer(
                key: ValueKey<String>(
                  'skills-direct-install-action-${installState.name}',
                ),
                duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
                curve: OpenCrayMotion.enter,
                constraints: const BoxConstraints(minWidth: 76),
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: installBackground,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: didFail
                        ? OpenCrayColors.danger.withValues(alpha: 0.22)
                        : Colors.transparent,
                  ),
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
              color: OpenCrayColors.textTertiary,
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
                    color: OpenCrayColors.textTertiary,
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
    this.color = OpenCrayColors.textPrimary,
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
    return const OpenCrayStateCard(
      key: ValueKey<String>('skills-state-loading'),
      isLoading: true,
      padding: EdgeInsets.all(20),
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
