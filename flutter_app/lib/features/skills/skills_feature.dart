import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/design/opencray_controls.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_palette.dart';
import '../../core/design/opencray_skeleton.dart';
import '../../core/design/opencray_tokens.dart';
import '../../core/design/opencray_widgets.dart';
import '../../core/models/opencray_skills_snapshot.dart';
part 'skills_widgets.dart';

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

  /// Only the first list to arrive plays the entrance; switching Manage/Install
  /// already slides the whole panel, and a search rerun should not re-stagger.
  final OpenCrayListEntranceWindow _listEntrance =
      OpenCrayListEntranceWindow();

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
          _listEntrance.restartOnce();
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
      color: context.palette.shellBackground,
      child: SafeArea(
        bottom: false,
        child: OpenCrayRefreshIndicator(
          onRefresh: _handlePullToRefresh,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                OpenCrayPageHeader(
                  eyebrow: widget.copy.skillsEyebrow,
                  title: widget.copy.skillsTitle,
                  summary: _selectedPage == SkillsPage.manage
                      ? widget.copy.skillsManageSubtitle
                      : widget.copy.skillsInstallSubtitle,
                  bottomGap: 18,
                ),
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
      return _LoadingCard(copy: widget.copy, showsToggle: true);
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
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: context.palette.textSecondary,
          ),
        ),
        const SizedBox(height: 12),
        DecoratedBox(
          decoration: BoxDecoration(
            color: context.palette.surface,
            borderRadius: const BorderRadius.all(Radius.circular(16)),
            border: Border.all(color: context.palette.divider),
            boxShadow: context.palette.cardShadow,
          ),
          child: Column(
            children: [
              for (
                var index = 0;
                index < visibleInstalledSkills.length;
                index++
              ) ...[
                OpenCrayListEntrance(
                  index: index,
                  enabled: _listEntrance.isActive,
                  child: _SkillRow(
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
                ),
                if (index < visibleInstalledSkills.length - 1)
                  Divider(
                    height: 1,
                    color: context.palette.divider,
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
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: context.palette.textSecondary,
          ),
        ),
        const SizedBox(height: 10),
        DecoratedBox(
          decoration: BoxDecoration(
            color: context.palette.surface,
            borderRadius: const BorderRadius.all(Radius.circular(16)),
            border: Border.all(color: context.palette.divider),
            boxShadow: context.palette.cardShadow,
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
                  Divider(
                    height: 1,
                    color: context.palette.divider,
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
          style: TextStyle(
            fontSize: 13,
            height: 1.2,
            fontWeight: FontWeight.w600,
            color: context.palette.textSecondary,
          ),
        ),
        const SizedBox(height: 10),
        if (!_isLoaded)
          _LoadingCard(copy: widget.copy)
        else if (availableSkills.isEmpty)
          _EmptyCard(title: emptyTitle, body: emptyBody)
        else ...[
          DecoratedBox(
            decoration: BoxDecoration(
              color: context.palette.surface,
              borderRadius: const BorderRadius.all(Radius.circular(16)),
              border: Border.all(color: context.palette.divider),
              boxShadow: context.palette.cardShadow,
            ),
            child: Column(
              children: [
                for (
                  var index = 0;
                  index < availableSkills.length;
                  index++
                ) ...[
                  OpenCrayListEntrance(
                    index: index,
                    enabled: _listEntrance.isActive,
                    child: _SuggestedRow(
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
                  ),
                  if (index < availableSkills.length - 1)
                    Divider(
                      height: 1,
                      color: context.palette.divider,
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

  /// Pull-to-refresh reuses the reload the Refresh action already runs, minus the
  /// search spinner: the gesture is its own progress affordance.
  Future<void> _handlePullToRefresh() => _reloadSkillsSnapshot(
    showErrorMessage: true,
    showProgressIndicator: false,
  );

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
        _listEntrance.restartOnce();
        _isSearching = false;
      });
      _openInitialActionsIfNeeded();
    } catch (error) {
      if (!mounted || requestEpoch != _skillsRequestEpoch) {
        return;
      }
      setState(() {
        _isLoaded = true;
        _listEntrance.restartOnce();
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
                          style: TextStyle(
                            fontSize: 14,
                            height: 1.35,
                            color: context.palette.textSecondary,
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
                        Divider(height: 1, color: context.palette.divider),
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
                color: context.palette.surfaceSubtle,
                borderRadius: const BorderRadius.all(Radius.circular(24)),
                border: Border.all(color: context.palette.divider),
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
                          color: context.palette.outline,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      skill.name,
                      style: TextStyle(
                        fontSize: 20,
                        height: 1.2,
                        fontWeight: FontWeight.w600,
                        color: context.palette.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      skill.description,
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.35,
                        color: context.palette.textSecondary,
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
                        color: context.palette.danger,
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
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.35,
                      color: context.palette.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    details.sourceDirectoryPath,
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.3,
                      color: context.palette.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 16),
                  SelectableText(
                    details.markdownBody,
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.4,
                      color: context.palette.textPrimary,
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
    unawaited(HapticFeedback.mediumImpact());
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

