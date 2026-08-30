

import 'dart:async';
import 'dart:convert';
import 'dart:ui' show FlutterView;

import 'package:flutter/material.dart';

import '../../app/opencray_tabs.dart';
import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/models/opencray_agent_snapshot.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import '../../core/models/opencray_debug_snapshot.dart';
import '../../core/models/opencray_image_reference.dart';
import '../../core/models/opencray_shell_snapshot.dart';
import '../../core/copy/opencray_ui_copy.dart';
import '../../core/design/opencray_controls.dart';
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_palette.dart';
import '../../core/design/opencray_tokens.dart';
import '../../core/design/opencray_widgets.dart';
import 'notification_settings_models.dart';
import 'safety_settings_copy.dart';
import 'safety_settings_models.dart';
import 'scheduled_task_settings_models.dart';
import 'settings_facade.dart';
import 'settings_models.dart';
import 'strong_background_settings_models.dart';

part 'agent_settings_pages.dart';
part 'agent_settings_widgets.dart';
part 'agent_gradient_data.dart';
part 'settings_notification_pages.dart';
part 'settings_scheduled_task_pages.dart';
part 'settings_api_pages.dart';
part 'safety_settings_pages.dart';
part 'settings_debug_pages.dart';
part 'settings_debug_formatters.dart';
part 'settings_widgets.dart';
part 'llm_settings_pages.dart';
part 'context_memory_trace_page.dart';

void _dismissActiveInput() {
  final FocusNode? primaryFocus = FocusManager.instance.primaryFocus;
  if (primaryFocus == null) {
    return;
  }
  primaryFocus.unfocus(disposition: UnfocusDisposition.previouslyFocusedChild);
  if (FocusManager.instance.primaryFocus == primaryFocus) {
    primaryFocus.unfocus(disposition: UnfocusDisposition.scope);
  }
}

class SettingsFeatureScreen extends StatefulWidget {
  const SettingsFeatureScreen({
    super.key,
    required this.facade,
    this.initialPage = SettingsPage.home,
    this.initialScheduleId,
    this.standalone = false,
    this.debugBridge,
  });

  final SettingsPage initialPage;
  final String? initialScheduleId;
  final SettingsFacade facade;
  final bool standalone;
  final OpenCrayHostBridge? debugBridge;

  @override
  State<SettingsFeatureScreen> createState() => _SettingsFeatureScreenState();
}

class _SettingsFeatureScreenState extends State<SettingsFeatureScreen>
    with WidgetsBindingObserver {
  late SettingsPage _page = widget.initialPage;
  late String? _selectedScheduleId = widget.initialScheduleId;
  int _pageTransitionDirection = 1;
  bool _hasPageTransition = false;
  final Map<SettingsPage, SettingsDetailSnapshot> _detailCache =
      <SettingsPage, SettingsDetailSnapshot>{};
  SettingsOverviewSnapshot? _overview;
  StreamSubscription<SettingsOverviewSnapshot>? _overviewSubscription;
  bool _keyboardWasVisible = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadOverview();
    _persistShellTarget(_page);
    _overviewSubscription = widget.facade.watchOverview().listen((overview) {
      if (!mounted) {
        return;
      }
      setState(() {
        _overview = overview;
      });
    });
    if (!_usesDedicatedPage(_page) && _page != SettingsPage.home) {
      _loadDetail(_page);
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      _keyboardWasVisible = _isKeyboardVisible();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _overviewSubscription?.cancel();
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    final bool keyboardVisible = _isKeyboardVisible();
    if (_keyboardWasVisible && !keyboardVisible) {
      _dismissActiveInput();
    }
    _keyboardWasVisible = keyboardVisible;
  }

  @override
  Widget build(BuildContext context) {
    final defaultTextStyle =
        Theme.of(context).textTheme.bodyMedium ??
        TextStyle(
          fontSize: 14,
          height: 20 / 14,
          color: context.palette.textSecondary,
        );
    final Widget currentPage = _buildCurrentPage(context);
    final Widget pageBody = _hasPageTransition
        ? OpenCrayDirectionalSwitcher(
            activeKey: ValueKey<String>('settings-page-${_page.routeId}'),
            direction: _pageTransitionDirection,
            child: currentPage,
          )
        : currentPage;
    final content = GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: _dismissActiveInput,
      child: DefaultTextStyle(
        style: defaultTextStyle,
        child: SafeArea(bottom: false, child: pageBody),
      ),
    );
    if (widget.standalone) {
      return Scaffold(
        backgroundColor: context.palette.shellBackground,
        body: content,
      );
    }
    return Material(color: context.palette.shellBackground, child: content);
  }

  Widget _buildCurrentPage(BuildContext context) {
    final nestedBackTarget = _nestedBackTargetForPage(_page);
    void onBack() {
      if (nestedBackTarget != null) {
        _persistShellTarget(nestedBackTarget);
        setState(() {
          _hasPageTransition = true;
          _pageTransitionDirection = _directionForPageChange(
            _page,
            nestedBackTarget,
          );
          _page = nestedBackTarget;
        });
        return;
      }
      if (widget.standalone) {
        Navigator.of(context).pop();
        return;
      }
      _persistShellTarget(SettingsPage.home);
      setState(() {
        _hasPageTransition = true;
        _pageTransitionDirection = _directionForPageChange(
          _page,
          SettingsPage.home,
        );
        _page = SettingsPage.home;
      });
    }

    final backLabel = _backLabelForPage(_page);
    switch (_page) {
      case SettingsPage.home:
        final overview = _overview;
        if (overview == null) {
          return const _SettingsLoading(
            key: ValueKey<String>('settings-loading'),
          );
        }
        return _SettingsHome(
          key: const ValueKey<String>('settings-home'),
          snapshot: overview,
          onOpenPage: _openPage,
        );
      case SettingsPage.notificationsBackground:
        return _NotificationsBackgroundSettingsPage(
          key: const ValueKey<String>('settings-notifications-background'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
          onOpenPage: _openPage,
        );
      case SettingsPage.eventAlerts:
        return _NotificationEventAlertsSettingsPage(
          key: const ValueKey<String>('settings-event-alerts'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.scheduledTasks:
        return _ScheduledTasksSettingsPage(
          key: const ValueKey<String>('settings-scheduled-tasks'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
          onOpenTask: _openScheduledTask,
        );
      case SettingsPage.scheduledTaskDetail:
        final scheduleId = _selectedScheduleId;
        if (scheduleId == null || scheduleId.isEmpty) {
          return _ScheduledTaskMissingSelectionPage(
            key: const ValueKey<String>('settings-scheduled-task-missing'),
            onBack: onBack,
            backLabel: backLabel,
          );
        }
        return _ScheduledTaskDetailSettingsPage(
          key: ValueKey<String>('settings-scheduled-task-$scheduleId'),
          facade: widget.facade,
          scheduleId: scheduleId,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.llm:
        return _LlmSettingsPage(
          key: const ValueKey<String>('settings-llm-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.personalization:
        return _PersonalizationSettingsPage(
          key: const ValueKey<String>('settings-personalization-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.agents:
        return _AgentsSettingsPage(
          key: const ValueKey<String>('settings-agents-editor'),
          onBack: onBack,
          backLabel: backLabel,
          debugBridge: widget.debugBridge,
        );
      case SettingsPage.mcp:
        return _McpSettingsPage(
          key: const ValueKey<String>('settings-mcp-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.apiIntegrations:
        return _ApiIntegrationsSettingsPage(
          key: const ValueKey<String>('settings-api-integrations-editor'),
          onBack: onBack,
          backLabel: backLabel,
          onOpenPage: _openPage,
        );
      case SettingsPage.networkSearch:
        return _NetworkSearchSettingsPage(
          key: const ValueKey<String>('settings-network-search-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.mediaSpeech:
        return _MediaSpeechSettingsPage(
          key: const ValueKey<String>('settings-media-speech-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.sandboxProviders:
        return _SandboxProvidersSettingsPage(
          key: const ValueKey<String>('settings-sandbox-providers-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
          onOpenPage: _openPage,
        );
      case SettingsPage.sandboxE2b:
        return _SandboxE2bSettingsPage(
          key: const ValueKey<String>('settings-sandbox-e2b-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.workspaceAccess:
        return _WorkspaceAccessSettingsPage(
          key: const ValueKey<String>('settings-workspace-access-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.safetyLimits:
        return _SafetySettingsPage(
          key: const ValueKey<String>('settings-safety-editor'),
          facade: widget.facade,
          onBack: onBack,
          backLabel: backLabel,
        );
      case SettingsPage.aboutVersion:
      case SettingsPage.privacyTelemetry:
        final detailSnapshot = _detailCache[_page];
        if (detailSnapshot == null) {
          return const _SettingsLoading(
            key: ValueKey<String>('settings-detail-loading'),
          );
        }
        return _DetailSettingsPage(
          key: ValueKey<String>('settings-detail-${_page.routeId}'),
          snapshot: detailSnapshot,
          onBack: onBack,
          backLabel: backLabel,
          facade: _page == SettingsPage.aboutVersion ? widget.facade : null,
          debugBridge: _page == SettingsPage.aboutVersion
              ? widget.debugBridge
              : null,
        );
    }
  }

  void _openPage(SettingsPage page) {
    _persistShellTarget(page);
    if (!widget.standalone && page != SettingsPage.home) {
      Navigator.of(context).push(
        openCrayHorizontalPageRoute<void>(
          builder: (context) => SettingsFeatureScreen(
            facade: widget.facade,
            initialPage: page,
            standalone: true,
            debugBridge: widget.debugBridge,
          ),
        ),
      );
      return;
    }
    setState(() {
      _hasPageTransition = true;
      _pageTransitionDirection = _directionForPageChange(_page, page);
      _page = page;
    });
    if (!_usesDedicatedPage(page) && page != SettingsPage.home) {
      _loadDetail(page);
    }
  }

  void _openScheduledTask(String scheduleId) {
    _persistShellTarget(SettingsPage.scheduledTasks);
    setState(() {
      _selectedScheduleId = scheduleId;
      _hasPageTransition = true;
      _pageTransitionDirection = 1;
      _page = SettingsPage.scheduledTaskDetail;
    });
  }

  Future<void> _loadOverview() async {
    final overview = await widget.facade.loadOverview();
    if (!mounted) {
      return;
    }
    setState(() {
      _overview = overview;
    });
  }

  Future<void> _loadDetail(SettingsPage page) async {
    if (_detailCache.containsKey(page)) {
      return;
    }
    final detail = await widget.facade.loadDetail(page);
    if (!mounted) {
      return;
    }
    setState(() {
      _detailCache[page] = detail;
    });
  }

  bool _usesDedicatedPage(SettingsPage page) =>
      page == SettingsPage.notificationsBackground ||
      page == SettingsPage.eventAlerts ||
      page == SettingsPage.scheduledTasks ||
      page == SettingsPage.scheduledTaskDetail ||
      page == SettingsPage.llm ||
      page == SettingsPage.apiIntegrations ||
      page == SettingsPage.networkSearch ||
      page == SettingsPage.mediaSpeech ||
      page == SettingsPage.sandboxProviders ||
      page == SettingsPage.sandboxE2b ||
      page == SettingsPage.personalization ||
      page == SettingsPage.agents ||
      page == SettingsPage.mcp ||
      page == SettingsPage.workspaceAccess ||
      page == SettingsPage.safetyLimits;

  SettingsPage? _nestedBackTargetForPage(SettingsPage page) {
    switch (page) {
      case SettingsPage.eventAlerts:
        return SettingsPage.notificationsBackground;
      case SettingsPage.scheduledTasks:
        return SettingsPage.notificationsBackground;
      case SettingsPage.scheduledTaskDetail:
        return SettingsPage.scheduledTasks;
      case SettingsPage.networkSearch:
      case SettingsPage.mediaSpeech:
      case SettingsPage.sandboxProviders:
        return SettingsPage.apiIntegrations;
      case SettingsPage.sandboxE2b:
        return SettingsPage.sandboxProviders;
      default:
        return null;
    }
  }

  int _directionForPageChange(SettingsPage from, SettingsPage to) {
    if (from == to) {
      return _pageTransitionDirection;
    }
    if (to == SettingsPage.home || _nestedBackTargetForPage(from) == to) {
      return -1;
    }
    if (from == SettingsPage.home || _nestedBackTargetForPage(to) == from) {
      return 1;
    }
    return SettingsPage.values.indexOf(to) > SettingsPage.values.indexOf(from)
        ? 1
        : -1;
  }

  String _backLabelForPage(SettingsPage page) {
    switch (page) {
      case SettingsPage.eventAlerts:
        return _homeEntryTitleFor(SettingsPage.notificationsBackground);
      case SettingsPage.scheduledTasks:
        return _homeEntryTitleFor(SettingsPage.notificationsBackground);
      case SettingsPage.scheduledTaskDetail:
        return _ScheduledTaskSettingsCopy.fromLocale(
          Localizations.localeOf(context),
        ).tasksTitle;
      case SettingsPage.networkSearch:
      case SettingsPage.mediaSpeech:
      case SettingsPage.sandboxProviders:
        return 'API Integrations';
      case SettingsPage.sandboxE2b:
        return 'Sandbox Providers';
      default:
        return _overview?.title ?? '';
    }
  }

  String _homeEntryTitleFor(SettingsPage page) {
    final overview = _overview;
    if (overview == null) {
      return '';
    }
    for (final entry in overview.entries) {
      if (entry.page == page) {
        return entry.title;
      }
    }
    return '';
  }

  bool _isKeyboardVisible() {
    final FlutterView? view = View.maybeOf(context);
    if (view == null) {
      return false;
    }
    return view.viewInsets.bottom > 0;
  }

  void _persistShellTarget(SettingsPage page) {
    final bridge = widget.debugBridge;
    if (bridge == null) {
      return;
    }
    unawaited(
      bridge
          .saveShellDestination(
            selectedTab: OpenCrayTab.settings.routeSegment,
            settingsSubpage: page.routeId,
          )
          .catchError((Object _) {}),
    );
  }
}

class _SettingsHome extends StatelessWidget {
  const _SettingsHome({
    super.key,
    required this.snapshot,
    required this.onOpenPage,
  });

  final SettingsOverviewSnapshot snapshot;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  Widget build(BuildContext context) {
    final visibleEntries = snapshot.entries
        .where((entry) => entry.page != SettingsPage.agents)
        .toList(growable: false);
    final groups = _groupSettingsHomeEntries(visibleEntries);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            eyebrow: snapshot.eyebrow,
            title: snapshot.title,
            summary: snapshot.subtitle,
          ),
          _DeviceSummaryCard(
            title: snapshot.deviceTitle,
            summary: snapshot.deviceSummary,
          ),
          for (final group in groups) ...[
            const SizedBox(height: 14),
            _SettingsEntryGroupCard(entries: group, onOpenPage: onOpenPage),
          ],
        ],
      ),
    );
  }
}

class _SettingsLoading extends StatelessWidget {
  const _SettingsLoading({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: SizedBox(
        width: 96,
        child: OpenCrayStateCard(
          key: ValueKey<String>('settings-state-loading-card'),
          isLoading: true,
          padding: EdgeInsets.all(14),
        ),
      ),
    );
  }
}

List<Widget> _buildDetailSectionCards(
  BuildContext context,
  List<SettingsSectionSnapshot> sections,
) => sections
    .map(
      (SettingsSectionSnapshot section) => Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: _SettingsCard(
          backgroundColor:
              section.backgroundTone == SettingsSectionBackgroundTone.danger
              ? context.palette.dangerTint
              : context.palette.surface,
          borderColor:
              section.backgroundTone == SettingsSectionBackgroundTone.danger
              ? context.palette.dangerBorder
              : context.palette.divider,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (section.title.isNotEmpty)
                Text(section.title, style: context.settingsText.cardTitle),
              if (section.title.isNotEmpty && section.segmentedOptions == null)
                const SizedBox(height: 8),
              if (section.segmentedOptions != null) ...[
                const SizedBox(height: 12),
                _SegmentedSelector(
                  labels: section.segmentedOptions!,
                  selectedIndex: section.segmentedIndex ?? 0,
                ),
              ],
              if (section.helperText != null) ...[
                const SizedBox(height: 12),
                Text(section.helperText!, style: context.settingsText.body),
              ],
              if (section.inlinePanelText != null) ...[
                const SizedBox(height: 12),
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: context.palette.surfaceMuted,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      section.inlinePanelText!,
                      style: context.settingsText.bodyStrong,
                    ),
                  ),
                ),
              ],
              if (section.rows.isNotEmpty) ...[
                const SizedBox(height: 8),
                for (int index = 0; index < section.rows.length; index++) ...[
                  _DetailRow(row: section.rows[index]),
                  if (index < section.rows.length - 1)
                    Divider(height: 1, color: context.palette.divider),
                ],
              ],
            ],
          ),
        ),
      ),
    )
    .toList(growable: false);

class _DetailSettingsPage extends StatelessWidget {
  const _DetailSettingsPage({
    super.key,
    required this.snapshot,
    required this.onBack,
    required this.backLabel,
    this.facade,
    this.debugBridge,
  });

  final SettingsDetailSnapshot snapshot;
  final VoidCallback onBack;
  final String backLabel;
  final SettingsFacade? facade;
  final OpenCrayHostBridge? debugBridge;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(onTap: onBack, label: backLabel),
            title: snapshot.title,
            summary: snapshot.subtitle,
          ),
          ..._buildDetailSectionCards(context, snapshot.sections),
          if (debugBridge != null && facade != null) ...[
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Debug tools', style: context.settingsText.cardTitle),
                    const SizedBox(height: 8),
                    Text(
                      'Open runtime diagnostics for context, memory, and soul state.',
                      style: context.settingsText.body,
                    ),
                    const SizedBox(height: 12),
                    _HomeEntryRow(
                      title: 'Open Debug Tools',
                      onTap: () {
                        Navigator.of(context).push(
                          openCrayHorizontalPageRoute<void>(
                            builder: (context) => _DebugToolsPage(
                              bridge: debugBridge!,
                              facade: facade!,
                              backLabel: snapshot.title,
                            ),
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _PersonalizationSettingsPage extends StatefulWidget {
  const _PersonalizationSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_PersonalizationSettingsPage> createState() =>
      _PersonalizationSettingsPageState();
}

class _PersonalizationSettingsPageState
    extends State<_PersonalizationSettingsPage> {
  final TextEditingController _customLabelController = TextEditingController();
  final TextEditingController _customGuidanceController =
      TextEditingController();
  final TextEditingController _memoryResetController = TextEditingController();
  final TextEditingController _soulResetController = TextEditingController();

  PersonalizationConfigSnapshot? _snapshot;
  String? _loadError;
  String _selectedPresetId = 'steady';
  bool _isSaving = false;
  bool _isChangingLanguage = false;
  bool _hasQueuedSave = false;
  bool _isApplyingSnapshot = false;
  String? _activeResetScopeId;
  Timer? _saveDebounce;

  @override
  void initState() {
    super.initState();
    _customLabelController.addListener(_scheduleSave);
    _customGuidanceController.addListener(_scheduleSave);
    _memoryResetController.addListener(_refreshResetCards);
    _soulResetController.addListener(_refreshResetCards);
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _customLabelController.dispose();
    _customGuidanceController.dispose();
    _memoryResetController.dispose();
    _soulResetController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-personalization-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'Personalization',
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(onTap: widget.onBack, label: widget.backLabel),
            title: snapshot.title,
            summary: snapshot.subtitle,
          ),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.introTitle, style: context.settingsText.cardTitle),
                const SizedBox(height: 8),
                Text(snapshot.introBody, style: context.settingsText.body),
                const SizedBox(height: 8),
                Text(snapshot.introHelper, style: context.settingsText.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.presetsTitle,
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.presetsHelper, style: context.settingsText.body),
                const SizedBox(height: 12),
                for (final preset in snapshot.presets) ...[
                  _PresetOptionCard(
                    preset: preset,
                    isSelected: preset.id == _selectedPresetId,
                    onTap: () => _selectPreset(preset.id),
                  ),
                  if (preset != snapshot.presets.last)
                    const SizedBox(height: 10),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.customOverlayTitle,
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customOverlayHelper,
                  style: context.settingsText.body,
                ),
                const SizedBox(height: 14),
                _PrototypeField(
                  label: snapshot.customLabelHint,
                  controller: _customLabelController,
                  hintText: snapshot.customLabelHint,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customLabelHelper,
                  style: context.settingsText.body,
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: snapshot.customGuidanceHint,
                  controller: _customGuidanceController,
                  hintText: snapshot.customGuidanceHint,
                  minLines: 4,
                  maxLines: 7,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customGuidanceHelper,
                  style: context.settingsText.body,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.behaviorDefaultsTitle,
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 12),
                _SettingsPickerRow(
                  title: snapshot.appLanguageTitle,
                  value: _selectedLanguageTitle(snapshot),
                  isBusy: _isChangingLanguage || _isSaving,
                  onTap: (_isChangingLanguage || _isSaving)
                      ? null
                      : _openAppLanguageSheet,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.livePreviewTitle,
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.livePreviewName,
                  style: context.settingsText.bodyStrong,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.livePreviewSummary,
                  style: context.settingsText.body,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.queueTitle, style: context.settingsText.cardTitle),
                const SizedBox(height: 10),
                Text(snapshot.queueBody, style: context.settingsText.body),
              ],
            ),
          ),
          if (snapshot.lastResetMessage.isNotEmpty) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: context.palette.primaryTint,
              borderColor: context.palette.primaryBorder,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    snapshot.lastResetTitle,
                    style: context.settingsText.cardTitle,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    snapshot.lastResetMessage,
                    style: context.settingsText.body,
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          for (final action in snapshot.resetActions) ...[
            _DangerResetCard(
              action: action,
              controller: _controllerForScope(action.scopeId),
              isBusy: _activeResetScopeId == action.scopeId,
              onChanged: () => setState(() {}),
              onReset: () async {
                await _runReset(action.scopeId);
              },
            ),
            if (action != snapshot.resetActions.last)
              const SizedBox(height: 16),
          ],
        ],
      ),
    );
  }

  TextEditingController _controllerForScope(String scopeId) {
    if (scopeId == 'soul') {
      return _soulResetController;
    }
    return _memoryResetController;
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadPersonalizationConfig();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
      });
      _applySnapshot(snapshot);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = error.toString().replaceFirst('Exception: ', '');
      });
    }
  }

  void _applySnapshot(PersonalizationConfigSnapshot snapshot) {
    _isApplyingSnapshot = true;
    _customLabelController.text = snapshot.customLabel;
    _customGuidanceController.text = snapshot.customGuidance;
    setState(() {
      _snapshot = snapshot;
      _selectedPresetId = snapshot.selectedPresetId;
    });
    _isApplyingSnapshot = false;
  }

  void _refreshResetCards() {
    if (mounted) {
      setState(() {});
    }
  }

  void _scheduleSave() {
    if (_isApplyingSnapshot || _snapshot == null) {
      return;
    }
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 350), () {
      _saveNow();
    });
  }

  void _selectPreset(String presetId) {
    if (_selectedPresetId == presetId) {
      return;
    }
    setState(() {
      _selectedPresetId = presetId;
    });
    _saveNow();
  }

  String _selectedLanguageTitle(PersonalizationConfigSnapshot snapshot) {
    final selected = snapshot.appLanguageOptions.firstWhere(
      (candidate) => candidate.id == snapshot.selectedAppLanguageId,
      orElse: () => snapshot.appLanguageOptions.isNotEmpty
          ? snapshot.appLanguageOptions.first
          : const PersonalizationLanguageOption(
              id: '',
              title: '',
              isSelected: false,
            ),
    );
    return selected.title;
  }

  Future<void> _openAppLanguageSheet() async {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    final selected = await showModalBottomSheet<PersonalizationLanguageOption>(
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
                color: context.palette.surface,
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
                          color: context.palette.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      snapshot.appLanguageTitle,
                      style: context.settingsText.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in snapshot.appLanguageOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  option.title,
                                  style: context.settingsText.rowTitle,
                                ),
                              ),
                              if (option.id == snapshot.selectedAppLanguageId)
                                Icon(
                                  Icons.check_rounded,
                                  color: context.palette.primary,
                                  size: 18,
                                ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected == null || selected.id == snapshot.selectedAppLanguageId) {
      return;
    }
    await _saveNow();
    if (!mounted) {
      return;
    }
    setState(() {
      _isChangingLanguage = true;
    });
    try {
      final updated = await widget.facade.setAppLanguage(selected.id);
      if (mounted) {
        _applySnapshot(updated);
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isChangingLanguage = false;
        });
      }
    }
  }

  Future<void> _saveNow() async {
    _saveDebounce?.cancel();
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    if (_isSaving) {
      _hasQueuedSave = true;
      return;
    }
    setState(() {
      _isSaving = true;
    });
    try {
      final updated = await widget.facade.savePersonalizationConfig(
        presetId: _selectedPresetId,
        customLabel: _customLabelController.text,
        customGuidance: _customGuidanceController.text,
      );
      if (mounted) {
        _applySnapshot(updated);
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
        if (_hasQueuedSave) {
          _hasQueuedSave = false;
          _saveNow();
        }
      }
    }
  }

  Future<void> _runReset(String scopeId) async {
    if (_activeResetScopeId != null) {
      return;
    }
    setState(() {
      _activeResetScopeId = scopeId;
    });
    try {
      final snapshot = await widget.facade.runPersonalizationReset(scopeId);
      if (mounted) {
        _controllerForScope(scopeId).clear();
        _applySnapshot(snapshot);
        if (snapshot.lastResetMessage.isNotEmpty) {
          _showMessage(snapshot.lastResetMessage);
        }
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _activeResetScopeId = null;
        });
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _McpSettingsPage extends StatefulWidget {
  const _McpSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_McpSettingsPage> createState() => _McpSettingsPageState();
}

class _McpSettingsPageState extends State<_McpSettingsPage> {
  McpSettingsSnapshot? _snapshot;
  String? _loadError;
  bool _isUpdatingMaster = false;
  String? _activeServerId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-mcp-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'MCP',
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(onTap: widget.onBack, label: widget.backLabel),
            title: snapshot.title,
            summary: snapshot.subtitle,
          ),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            snapshot.masterTitle,
                            style: context.settingsText.cardTitle,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            snapshot.masterSummary,
                            style: context.settingsText.body,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    OpenCraySwitch(
                      value: snapshot.masterEnabled,
                      onChanged: _isUpdatingMaster ? null : _toggleMaster,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.summaryLine,
                  style: context.settingsText.bodyStrong,
                ),
              ],
            ),
          ),
          if (!snapshot.masterEnabled &&
              (snapshot.masterDisabledTitle.isNotEmpty ||
                  snapshot.masterDisabledBody.isNotEmpty)) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: context.palette.warningTint,
              borderColor: context.palette.warningBorder,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (snapshot.masterDisabledTitle.isNotEmpty)
                    Text(
                      snapshot.masterDisabledTitle,
                      style: context.settingsText.cardTitle,
                    ),
                  if (snapshot.masterDisabledBody.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(
                      snapshot.masterDisabledBody,
                      style: context.settingsText.body,
                    ),
                  ],
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.serversTitle,
                  style: context.settingsText.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.serversHelper, style: context.settingsText.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          for (final server in snapshot.servers) ...[
            _McpServerCard(
              server: server,
              isBusy: _activeServerId == server.id,
              onAction: () async {
                await _toggleServer(server);
              },
            ),
            if (server != snapshot.servers.last) const SizedBox(height: 16),
          ],
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadMcpSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
        _snapshot = snapshot;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = error.toString().replaceFirst('Exception: ', '');
      });
    }
  }

  Future<void> _toggleMaster(bool enabled) async {
    setState(() {
      _isUpdatingMaster = true;
    });
    try {
      final snapshot = await widget.facade.setMcpMasterEnabled(enabled);
      if (mounted) {
        setState(() {
          _snapshot = snapshot;
        });
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingMaster = false;
        });
      }
    }
  }

  Future<void> _toggleServer(McpServerSnapshot server) async {
    if (_activeServerId != null) {
      return;
    }
    setState(() {
      _activeServerId = server.id;
    });
    try {
      final snapshot = await widget.facade.setMcpServerEnabled(
        serverId: server.id,
        enabled: server.actionTurnsOn,
      );
      if (mounted) {
        setState(() {
          _snapshot = snapshot;
        });
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _activeServerId = null;
        });
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _PresetOptionCard extends StatelessWidget {
  const _PresetOptionCard({
    required this.preset,
    required this.isSelected,
    required this.onTap,
  });

  final PersonalizationPresetOption preset;
  final bool isSelected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: isSelected
            ? context.palette.primaryTint
            : context.palette.surfaceSubtle,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isSelected ? context.palette.primary : context.palette.divider,
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        preset.title,
                        style: context.settingsText.rowTitle,
                      ),
                    ),
                    _SettingsStatusPill(
                      label: preset.status,
                      tone: isSelected ? 'active' : 'neutral',
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(preset.summary, style: context.settingsText.body),
                const SizedBox(height: 8),
                Text(preset.voice, style: context.settingsText.bodyStrong),
                const SizedBox(height: 8),
                Text(preset.status, style: context.settingsText.body),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DangerResetCard extends StatelessWidget {
  const _DangerResetCard({
    required this.action,
    required this.controller,
    required this.isBusy,
    required this.onChanged,
    required this.onReset,
  });

  final PersonalizationResetAction action;
  final TextEditingController controller;
  final bool isBusy;
  final VoidCallback onChanged;
  final Future<void> Function() onReset;

  @override
  Widget build(BuildContext context) {
    final isArmed =
        action.isInputEnabled && controller.text == action.confirmationToken;
    final guidance = !action.isInputEnabled
        ? action.disabledGuidance
        : (isArmed ? action.armedGuidance : action.typeExactGuidance);
    return _SettingsCard(
      backgroundColor: context.palette.dangerTint,
      borderColor: context.palette.dangerBorder,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(action.title, style: context.settingsText.cardTitle),
          const SizedBox(height: 10),
          Text(action.scopeBody, style: context.settingsText.body),
          const SizedBox(height: 8),
          Text(action.retainBody, style: context.settingsText.body),
          const SizedBox(height: 12),
          _PrototypeField(
            label: action.inputHint,
            controller: controller,
            hintText: action.confirmationToken,
            enabled: action.isInputEnabled && !isBusy,
            onChanged: (_) => onChanged(),
          ),
          const SizedBox(height: 10),
          Text(guidance, style: context.settingsText.body),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: isBusy || !isArmed
                  ? null
                  : () {
                      onReset();
                    },
              style: FilledButton.styleFrom(
                backgroundColor: context.palette.danger,
                foregroundColor: Colors.white,
                disabledBackgroundColor: context.palette.surfaceSunken,
                disabledForegroundColor: context.palette.textSecondary,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (isBusy) ...[
                    const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                      ),
                    ),
                    const SizedBox(width: 10),
                  ],
                  Text(action.title),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _McpServerCard extends StatelessWidget {
  const _McpServerCard({
    required this.server,
    required this.isBusy,
    required this.onAction,
  });

  final McpServerSnapshot server;
  final bool isBusy;
  final Future<void> Function() onAction;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(server.title, style: context.settingsText.cardTitle),
                    const SizedBox(height: 8),
                    _SettingsStatusPill(
                      label: server.statusLabel,
                      tone: server.statusTone,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              _HeaderActionChip(
                label: server.actionLabel,
                onTap: !server.isActionEnabled || isBusy
                    ? null
                    : () {
                        onAction();
                      },
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(server.trustLine, style: context.settingsText.bodyStrong),
          const SizedBox(height: 6),
          Text(server.authLine, style: context.settingsText.body),
          const SizedBox(height: 6),
          Text(server.readinessLine, style: context.settingsText.body),
          const SizedBox(height: 6),
          Text(server.transportLine, style: context.settingsText.body),
          const SizedBox(height: 6),
          Text(server.exposureLine, style: context.settingsText.bodyStrong),
          const SizedBox(height: 10),
          Text(server.guidance, style: context.settingsText.body),
        ],
      ),
    );
  }
}

class _SegmentedSelector extends StatelessWidget {
  const _SegmentedSelector({required this.labels, required this.selectedIndex});

  final List<String> labels;
  final int selectedIndex;

  @override
  Widget build(BuildContext context) {
    return OpenCraySegmentedControl(
      labels: labels,
      selectedIndex: selectedIndex,
      textStyle: context.settingsText.valueChip,
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.row});

  final SettingsRowSnapshot row;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(row.title, style: context.settingsText.rowTitle),
                if (row.subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(row.subtitle!, style: context.settingsText.rowSubtitle),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          if (row.trailingKind == SettingsRowTrailingKind.chevron)
            Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: context.palette.textTertiary,
            )
          else if (row.trailingKind == SettingsRowTrailingKind.toggle)
            OpenCraySwitch(value: row.toggleValue ?? false, onChanged: (_) {})
          else
            DecoratedBox(
              decoration: BoxDecoration(
                color: context.palette.surfaceMuted,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                child: Text(
                  row.valueLabel ?? '',
                  style: context.settingsText.valueChip,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
