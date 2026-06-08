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
import '../../core/design/opencray_motion.dart';
import '../../core/design/opencray_tokens.dart';
import 'notification_settings_models.dart';
import 'safety_settings_copy.dart';
import 'safety_settings_models.dart';
import 'settings_facade.dart';
import 'settings_models.dart';
import 'strong_background_settings_models.dart';

part 'agent_settings_pages.dart';
part 'settings_notification_pages.dart';
part 'settings_api_pages.dart';
part 'safety_settings_pages.dart';
part 'settings_debug_pages.dart';

bool _llmEndpointAllowsBlankApiKey({
  required String protocol,
  required String baseUrl,
}) {
  final normalizedProtocol = protocol.trim().toLowerCase();
  if (normalizedProtocol != 'openai' &&
      normalizedProtocol != 'openai_responses') {
    return false;
  }
  return _isLikelyLocalLlmBaseUrl(baseUrl);
}

bool _isLikelyLocalLlmBaseUrl(String baseUrl) {
  final host = (Uri.tryParse(baseUrl.trim())?.host ?? '').trim().toLowerCase();
  if (host.isEmpty) {
    return false;
  }
  if (host == 'localhost' ||
      host == 'localhost.localdomain' ||
      host == '0.0.0.0' ||
      host == '::1' ||
      host == '10.0.2.2' ||
      host == 'host.docker.internal' ||
      host.endsWith('.local')) {
    return true;
  }
  return host.startsWith('127.') ||
      host.startsWith('10.') ||
      host.startsWith('192.168.') ||
      _isPrivate172SubnetHost(host);
}

bool _isPrivate172SubnetHost(String host) {
  if (!host.startsWith('172.')) {
    return false;
  }
  final parts = host.split('.');
  if (parts.length < 2) {
    return false;
  }
  final secondOctet = int.tryParse(parts[1]);
  return secondOctet != null && secondOctet >= 16 && secondOctet <= 31;
}

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
    this.standalone = false,
    this.debugBridge,
  });

  final SettingsPage initialPage;
  final SettingsFacade facade;
  final bool standalone;
  final OpenCrayHostBridge? debugBridge;

  @override
  State<SettingsFeatureScreen> createState() => _SettingsFeatureScreenState();
}

class _SettingsFeatureScreenState extends State<SettingsFeatureScreen>
    with WidgetsBindingObserver {
  late SettingsPage _page = widget.initialPage;
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
        const TextStyle(
          fontSize: 14,
          height: 20 / 14,
          color: OpenCrayColors.textSecondary,
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
        backgroundColor: OpenCrayColors.shellBackground,
        body: content,
      );
    }
    return Material(color: OpenCrayColors.shellBackground, child: content);
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
      case SettingsPage.notificationChannels:
        return _NotificationChannelsSettingsPage(
          key: const ValueKey<String>('settings-notification-channels'),
          facade: widget.facade,
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
      page == SettingsPage.notificationChannels ||
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
      case SettingsPage.notificationChannels:
        return SettingsPage.notificationsBackground;
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
      case SettingsPage.notificationChannels:
        return _homeEntryTitleFor(SettingsPage.notificationsBackground);
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
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(snapshot.eyebrow, style: _SettingsTextStyles.eyebrow),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitle),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 20),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  snapshot.deviceTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.deviceSummary, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          const SizedBox(height: 16),
          ...visibleEntries.map(
            (SettingsHomeEntrySnapshot item) => _HomeEntryRow(
              title: item.title,
              selected: false,
              onTap: () => onOpenPage(item.page),
            ),
          ),
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
        width: 24,
        height: 24,
        child: CircularProgressIndicator(strokeWidth: 2),
      ),
    );
  }
}

List<Widget> _buildDetailSectionCards(
  List<SettingsSectionSnapshot> sections,
) => sections
    .map(
      (SettingsSectionSnapshot section) => Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: _SettingsCard(
          backgroundColor:
              section.backgroundTone == SettingsSectionBackgroundTone.danger
              ? OpenCrayColors.dangerSurface
              : Colors.white,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (section.title.isNotEmpty)
                Text(section.title, style: _SettingsTextStyles.cardTitle),
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
                Text(section.helperText!, style: _SettingsTextStyles.body),
              ],
              if (section.inlinePanelText != null) ...[
                const SizedBox(height: 12),
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: const Color(0xFFF4F4F7),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      section.inlinePanelText!,
                      style: _SettingsTextStyles.bodyStrong,
                    ),
                  ),
                ),
              ],
              if (section.rows.isNotEmpty) ...[
                const SizedBox(height: 8),
                for (int index = 0; index < section.rows.length; index++) ...[
                  _DetailRow(row: section.rows[index]),
                  if (index < section.rows.length - 1)
                    const Divider(height: 1, color: OpenCrayColors.divider),
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
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          ..._buildDetailSectionCards(snapshot.sections),
          if (debugBridge != null && facade != null) ...[
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Debug tools', style: _SettingsTextStyles.cardTitle),
                    const SizedBox(height: 8),
                    Text(
                      'Open runtime diagnostics for context, memory, and soul state.',
                      style: _SettingsTextStyles.body,
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

class _MemoryDebugPage extends StatefulWidget {
  const _MemoryDebugPage({required this.bridge, required this.backLabel});

  final OpenCrayHostBridge bridge;
  final String backLabel;

  @override
  State<_MemoryDebugPage> createState() => _MemoryDebugPageState();
}

class _MemoryDebugPageState extends State<_MemoryDebugPage> {
  bool _isLoading = true;
  bool _isRefreshing = false;
  String? _loadError;
  OpenCrayChatRuntimeSnapshot? _runtimeSnapshot;
  List<String> _recentRunIds = const <String>[];
  String? _selectedRunId;
  OpenCrayChatRunSnapshot? _selectedRunSnapshot;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _BackLink(
                onTap: () => Navigator.of(context).pop(),
                label: widget.backLabel,
              ),
              const SizedBox(height: 8),
              const Text('Debug', style: _SettingsTextStyles.pageTitleSubpage),
              const SizedBox(height: 8),
              const Text(
                'Recent runtime runs and the structured memory trace captured for each run.',
                style: _SettingsTextStyles.subtitle,
              ),
              const SizedBox(height: 16),
              _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Expanded(
                          child: Text(
                            'Runtime memory trace',
                            style: _SettingsTextStyles.cardTitle,
                          ),
                        ),
                        _HeaderActionChip(
                          label: _isRefreshing ? 'Refreshing' : 'Refresh',
                          onTap: _isRefreshing ? null : _refresh,
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      'Uses the existing host run snapshot path. No separate debug protocol or log scraping is required.',
                      style: _SettingsTextStyles.body,
                    ),
                    if (_loadError != null) ...[
                      const SizedBox(height: 12),
                      Text(_loadError!, style: _SettingsTextStyles.bodyStrong),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 16),
              if (_isLoading)
                const _SettingsLoading(
                  key: ValueKey<String>('memory-debug-loading'),
                )
              else ...[
                _buildRunSelectorCard(),
                if (_runtimeSnapshot != null) ...[
                  const SizedBox(height: 16),
                  _buildSubAgentCard(_runtimeSnapshot!),
                ],
                if (_selectedRunSnapshot != null) ...[
                  const SizedBox(height: 16),
                  _buildRunDetailsCard(_selectedRunSnapshot!),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRunSelectorCard() {
    if (_recentRunIds.isEmpty) {
      return _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: const [
            Text('Recent runs', style: _SettingsTextStyles.cardTitle),
            SizedBox(height: 8),
            Text(
              'No recent run ids are visible in the current runtime activity snapshot.',
              style: _SettingsTextStyles.body,
            ),
          ],
        ),
      );
    }
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Recent runs', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(
            'Select a run to inspect its memory recall trace.',
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 8),
          for (int index = 0; index < _recentRunIds.length; index++) ...[
            _HomeEntryRow(
              title: _recentRunIds[index],
              selected: _recentRunIds[index] == _selectedRunId,
              onTap: () => _selectRun(_recentRunIds[index]),
            ),
            if (index < _recentRunIds.length - 1)
              const Divider(height: 1, color: OpenCrayColors.divider),
          ],
        ],
      ),
    );
  }

  Widget _buildRunDetailsCard(OpenCrayChatRunSnapshot run) {
    final trace = run.memoryTrace;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _SettingsCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Selected run', style: _SettingsTextStyles.cardTitle),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(label: 'Run', value: run.runId),
                  _DebugValueChip(
                    label: 'Status',
                    value: run.executionStatus ?? 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Task state',
                    value: run.taskState ?? 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Format',
                    value: run.responseFormat ?? 'n/a',
                  ),
                ],
              ),
              if (run.errorCode?.isNotEmpty == true) ...[
                const SizedBox(height: 12),
                Text(
                  'Error: ${run.errorCode} ${run.errorMessage ?? ''}'.trim(),
                  style: _SettingsTextStyles.bodyStrong,
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 16),
        _SettingsCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Memory trace', style: _SettingsTextStyles.cardTitle),
              const SizedBox(height: 10),
              if (trace == null)
                const Text(
                  'No recalled memory trace was captured for this run.',
                  style: _SettingsTextStyles.body,
                )
              else ...[
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _DebugValueChip(
                      label: 'Matched',
                      value: '${trace.matchedRecordCount ?? 0}',
                    ),
                    _DebugValueChip(
                      label: 'Injected',
                      value: '${trace.injectedRecordCount ?? 0}',
                    ),
                    _DebugValueChip(
                      label: 'Omitted',
                      value: '${trace.omittedRecordCount ?? 0}',
                    ),
                  ],
                ),
                if (trace.queryTerms.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Query terms', style: _SettingsTextStyles.cardTitle),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: trace.queryTerms
                        .map(
                          (term) => _DebugValueChip(label: 'Term', value: term),
                        )
                        .toList(growable: false),
                  ),
                ],
                if (trace.selected.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(
                    'Selected records',
                    style: _SettingsTextStyles.cardTitle,
                  ),
                  const SizedBox(height: 8),
                  for (final selected in trace.selected) ...[
                    _DebugKeyValueLine('Record', selected.id),
                    if (selected.score != null)
                      _DebugKeyValueLine('Score', '${selected.score}'),
                    if (selected.matchedTerms.isNotEmpty)
                      _DebugKeyValueLine(
                        'Matched terms',
                        selected.matchedTerms.join(', '),
                      ),
                    const SizedBox(height: 8),
                  ],
                ],
                if (trace.omitted.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text('Omitted records', style: _SettingsTextStyles.cardTitle),
                  const SizedBox(height: 8),
                  for (final omitted in trace.omitted) ...[
                    _DebugKeyValueLine('Record', omitted.id),
                    _DebugKeyValueLine('Reason', omitted.reason),
                    const SizedBox(height: 8),
                  ],
                ],
                if (trace.filteredCounts.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text('Filtered counts', style: _SettingsTextStyles.cardTitle),
                  const SizedBox(height: 8),
                  ...trace.filteredCounts.entries.map(
                    (entry) => _DebugKeyValueLine(entry.key, '${entry.value}'),
                  ),
                ],
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildSubAgentCard(OpenCrayChatRuntimeSnapshot snapshot) {
    final List<OpenCrayChatSubAgentSnapshot> subAgents =
        snapshot.subAgents.toList(growable: false)..sort((left, right) {
          final int updatedComparison = right.updatedAtEpochMs.compareTo(
            left.updatedAtEpochMs,
          );
          if (updatedComparison != 0) {
            return updatedComparison;
          }
          return left.label.compareTo(right.label);
        });
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Projected subagents',
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 8),
          const Text(
            'Uses runtimeActivity.subAgents directly, even when no parent run is currently selectable.',
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 10),
          if (subAgents.isEmpty)
            const Text(
              'No projected subagents are visible in the current runtime activity snapshot.',
              style: _SettingsTextStyles.body,
            )
          else
            for (int index = 0; index < subAgents.length; index++) ...[
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Subagent',
                    value: subAgents[index].label,
                  ),
                  _DebugValueChip(
                    label: 'Status',
                    value: _formatSubAgentStatus(subAgents[index]),
                  ),
                  _DebugValueChip(
                    label: 'Parent',
                    value: subAgents[index].parentRunId,
                  ),
                  if (subAgents[index].mailboxMessageCount > 0 ||
                      subAgents[index].mailboxPendingMessageCount > 0)
                    _DebugValueChip(
                      label: 'Mailbox',
                      value:
                          '${subAgents[index].mailboxPendingMessageCount} pending / ${subAgents[index].mailboxMessageCount} total',
                    ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                _formatSubAgentSummary(subAgents[index]),
                style: _SettingsTextStyles.body,
              ),
              if (subAgents[index].mailboxLastDeliveredMessageId
                      ?.trim()
                      .isNotEmpty ==
                  true) ...[
                const SizedBox(height: 8),
                _DebugKeyValueLine(
                  'Last delivered',
                  subAgents[index].mailboxLastDeliveredMessageId!.trim(),
                ),
              ],
              if (index < subAgents.length - 1) ...[
                const SizedBox(height: 12),
                const Divider(height: 1, color: OpenCrayColors.divider),
                const SizedBox(height: 12),
              ],
            ],
        ],
      ),
    );
  }

  Future<void> _refresh() async {
    final shouldShowLoading = _recentRunIds.isEmpty && !_isRefreshing;
    setState(() {
      _loadError = null;
      _isLoading = shouldShowLoading;
      _isRefreshing = true;
    });
    try {
      final runtimeSnapshot = await widget.bridge.loadChatRuntimeSnapshot();
      final recentRunIds = _collectRecentRunIds(runtimeSnapshot);
      final nextSelectedRunId = recentRunIds.contains(_selectedRunId)
          ? _selectedRunId
          : (recentRunIds.isEmpty ? null : recentRunIds.first);
      final nextSnapshot = nextSelectedRunId == null
          ? null
          : await widget.bridge.loadChatRunSnapshot(nextSelectedRunId);
      if (!mounted) {
        return;
      }
      setState(() {
        _runtimeSnapshot = runtimeSnapshot;
        _recentRunIds = recentRunIds;
        _selectedRunId = nextSelectedRunId;
        _selectedRunSnapshot = nextSnapshot;
        _isLoading = false;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load runtime debug data: $error';
        _isLoading = false;
        _isRefreshing = false;
      });
    }
  }

  Future<void> _selectRun(String runId) async {
    setState(() {
      _selectedRunId = runId;
      _selectedRunSnapshot = null;
      _isRefreshing = true;
      _loadError = null;
    });
    try {
      final snapshot = await widget.bridge.loadChatRunSnapshot(runId);
      if (!mounted) {
        return;
      }
      setState(() {
        _selectedRunSnapshot = snapshot;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load run snapshot: $error';
        _isRefreshing = false;
      });
    }
  }

  List<String> _collectRecentRunIds(OpenCrayChatRuntimeSnapshot snapshot) {
    final runEpochs = <String, int>{};
    for (final run in snapshot.activeRuns) {
      if (run.runId.trim().isEmpty) {
        continue;
      }
      runEpochs[run.runId] = run.updatedAtEpochMs;
    }
    for (final run in snapshot.retainedRuns) {
      if (run.runId.trim().isEmpty) {
        continue;
      }
      final existingEpoch = runEpochs[run.runId];
      if (existingEpoch == null || run.updatedAtEpochMs > existingEpoch) {
        runEpochs[run.runId] = run.updatedAtEpochMs;
      }
    }
    for (final event in snapshot.events) {
      if (event.runId.trim().isEmpty) {
        continue;
      }
      final existingEpoch = runEpochs[event.runId];
      if (existingEpoch == null || event.emittedAtEpochMs > existingEpoch) {
        runEpochs[event.runId] = event.emittedAtEpochMs;
      }
    }
    final recentRuns = runEpochs.entries.toList(growable: false)
      ..sort((left, right) => right.value.compareTo(left.value));
    return recentRuns
        .map((entry) => entry.key)
        .where((runId) => runId.trim().isNotEmpty)
        .take(8)
        .toList(growable: false);
  }

  String _formatSubAgentStatus(OpenCrayChatSubAgentSnapshot subAgent) {
    final String rawStatus =
        subAgent.executionState ??
        subAgent.status ??
        subAgent.phase ??
        'unknown';
    return rawStatus.trim().replaceAll(RegExp(r'[_-]+'), ' ').toLowerCase();
  }

  String _formatSubAgentSummary(OpenCrayChatSubAgentSnapshot subAgent) {
    final List<String> parts = <String>[
      if (subAgent.subagentType.trim().isNotEmpty) subAgent.subagentType.trim(),
      if (subAgent.contextMode.trim().isNotEmpty) subAgent.contextMode.trim(),
      if (subAgent.depth > 0) 'depth ${subAgent.depth}',
      if (subAgent.summary?.trim().isNotEmpty == true) subAgent.summary!.trim(),
    ];
    return parts.join(' · ');
  }
}

class _DebugValueChip extends StatelessWidget {
  const _DebugValueChip({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF3F4F7),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text('$label: $value', style: _SettingsTextStyles.valueChip),
      ),
    );
  }
}

class _DebugKeyValueLine extends StatelessWidget {
  const _DebugKeyValueLine(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: RichText(
        text: TextSpan(
          style: _SettingsTextStyles.body,
          children: [
            TextSpan(text: '$label: ', style: _SettingsTextStyles.bodyStrong),
            TextSpan(text: value),
          ],
        ),
      ),
    );
  }
}

class _NetworkSearchSettingsPage extends StatefulWidget {
  const _NetworkSearchSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_NetworkSearchSettingsPage> createState() =>
      _NetworkSearchSettingsPageState();
}

class _NetworkSearchSettingsPageState
    extends State<_NetworkSearchSettingsPage> {
  NetworkSearchConfigSnapshot? _snapshot;
  String? _loadError;
  List<NetworkSearchSlotSnapshot> _slots = <NetworkSearchSlotSnapshot>[];
  bool _isSaving = false;
  bool _hasQueuedSave = false;
  Timer? _saveDebounce;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-network-search-loading'),
            )
          : _SettingsLoadErrorCard(
              title: 'Network & Search',
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          for (int index = 0; index < _slots.length; index++) ...[
            _NetworkSearchSlotCard(
              key: ValueKey<String>('network-search-slot-${_slots[index].id}'),
              slot: _slots[index],
              index: index,
              localeTag: snapshot.localeTag,
              canMoveUp: index > 0,
              canMoveDown: index < _slots.length - 1,
              onChanged: _updateSlot,
              onMoveUp: index > 0 ? () => _moveSlot(index, index - 1) : null,
              onMoveDown: index < _slots.length - 1
                  ? () => _moveSlot(index, index + 1)
                  : null,
              onDelete: () => _deleteSlot(_slots[index].id),
            ),
            const SizedBox(height: 16),
          ],
          InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: _addSlot,
            child: _SettingsCard(
              child: Text(
                _isSaving
                    ? copy.networkSearchSaving
                    : copy.networkSearchAddSlotAction,
                style: _SettingsTextStyles.actionChip,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadNetworkSearchConfig();
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = null;
        _applySnapshot(snapshot);
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

  void _applySnapshot(NetworkSearchConfigSnapshot snapshot) {
    _snapshot = snapshot;
    _slots = snapshot.slots.toList(growable: false);
  }

  void _updateSlot(NetworkSearchSlotSnapshot updatedSlot) {
    setState(() {
      _slots = _slots
          .map((slot) => slot.id == updatedSlot.id ? updatedSlot : slot)
          .toList(growable: false);
    });
    _scheduleSave();
  }

  void _addSlot() {
    setState(() {
      _slots = <NetworkSearchSlotSnapshot>[
        ..._slots,
        NetworkSearchSlotSnapshot(
          id: 'slot-${DateTime.now().microsecondsSinceEpoch}',
          providerId: 'exa',
          label: '',
          baseUrl: '',
          model: '',
          apiKey: '',
          enabled: true,
        ),
      ];
    });
    _saveNow();
  }

  void _moveSlot(int fromIndex, int toIndex) {
    if (fromIndex == toIndex) {
      return;
    }
    setState(() {
      final reordered = _slots.toList(growable: true);
      final slot = reordered.removeAt(fromIndex);
      reordered.insert(toIndex, slot);
      _slots = reordered;
    });
    _saveNow();
  }

  void _deleteSlot(String slotId) {
    setState(() {
      _slots = _slots
          .where((slot) => slot.id != slotId)
          .toList(growable: false);
    });
    _saveNow();
  }

  void _scheduleSave() {
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 350), _saveNow);
  }

  Future<void> _saveNow() async {
    _saveDebounce?.cancel();
    if (_snapshot == null) {
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
      final updatedSnapshot = await widget.facade.saveNetworkSearchConfig(
        _slots,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _applySnapshot(updatedSnapshot);
      });
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

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _NetworkSearchSlotCard extends StatefulWidget {
  const _NetworkSearchSlotCard({
    super.key,
    required this.slot,
    required this.index,
    required this.localeTag,
    required this.canMoveUp,
    required this.canMoveDown,
    required this.onChanged,
    required this.onDelete,
    this.onMoveUp,
    this.onMoveDown,
  });

  final NetworkSearchSlotSnapshot slot;
  final int index;
  final String localeTag;
  final bool canMoveUp;
  final bool canMoveDown;
  final ValueChanged<NetworkSearchSlotSnapshot> onChanged;
  final VoidCallback onDelete;
  final VoidCallback? onMoveUp;
  final VoidCallback? onMoveDown;

  @override
  State<_NetworkSearchSlotCard> createState() => _NetworkSearchSlotCardState();
}

class _NetworkSearchSlotCardState extends State<_NetworkSearchSlotCard> {
  static const String _openAiWebSearchProviderId = 'openai_web_search';
  static const String _defaultOpenAiBaseUrl = 'https://api.openai.com/v1';
  static const String _defaultOpenAiSearchModel = 'gpt-5';

  late final TextEditingController _labelController;
  late final TextEditingController _baseUrlController;
  late final TextEditingController _modelController;
  late final TextEditingController _apiKeyController;
  late final FocusNode _labelFocusNode;
  late final FocusNode _baseUrlFocusNode;
  late final FocusNode _modelFocusNode;
  late final FocusNode _apiKeyFocusNode;
  Timer? _saveDebounce;

  @override
  void initState() {
    super.initState();
    _labelController = TextEditingController(text: widget.slot.label);
    _baseUrlController = TextEditingController(text: widget.slot.baseUrl);
    _modelController = TextEditingController(text: widget.slot.model);
    _apiKeyController = TextEditingController(text: widget.slot.apiKey);
    _labelFocusNode = FocusNode()..addListener(_handleFocusChange);
    _baseUrlFocusNode = FocusNode()..addListener(_handleFocusChange);
    _modelFocusNode = FocusNode()..addListener(_handleFocusChange);
    _apiKeyFocusNode = FocusNode()..addListener(_handleFocusChange);
  }

  @override
  void didUpdateWidget(covariant _NetworkSearchSlotCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.slot.label != _labelController.text) {
      _labelController.text = widget.slot.label;
    }
    if (widget.slot.baseUrl != _baseUrlController.text) {
      _baseUrlController.text = widget.slot.baseUrl;
    }
    if (widget.slot.model != _modelController.text) {
      _modelController.text = widget.slot.model;
    }
    if (widget.slot.apiKey != _apiKeyController.text) {
      _apiKeyController.text = widget.slot.apiKey;
    }
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _labelFocusNode
      ..removeListener(_handleFocusChange)
      ..dispose();
    _baseUrlFocusNode
      ..removeListener(_handleFocusChange)
      ..dispose();
    _modelFocusNode
      ..removeListener(_handleFocusChange)
      ..dispose();
    _apiKeyFocusNode
      ..removeListener(_handleFocusChange)
      ..dispose();
    _labelController.dispose();
    _baseUrlController.dispose();
    _modelController.dispose();
    _apiKeyController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final copy = OpenCrayUiCopy.fromLocaleTag(widget.localeTag);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  copy.networkSearchSlotTitle(widget.index),
                  style: _SettingsTextStyles.cardTitle,
                ),
              ),
              _PrototypeSwitch(
                value: widget.slot.enabled,
                onChanged: (enabled) {
                  widget.onChanged(widget.slot.copyWith(enabled: enabled));
                },
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            copy.networkSearchProviderLabel,
            style: _SettingsTextStyles.fieldLabel,
          ),
          const SizedBox(height: 6),
          _InteractiveSegmentedSelector(
            labels: const <String>[
              'exa',
              'tavily',
              'brave',
              _openAiWebSearchProviderId,
            ],
            selectedId: widget.slot.providerId,
            labelBuilder: copy.networkSearchProviderTitle,
            onSelected: (providerId) {
              widget.onChanged(
                widget.slot.copyWith(
                  providerId: providerId,
                  baseUrl: providerId == _openAiWebSearchProviderId
                      ? widget.slot.baseUrl.trim().isEmpty
                            ? _defaultOpenAiBaseUrl
                            : widget.slot.baseUrl
                      : '',
                  model: providerId == _openAiWebSearchProviderId
                      ? widget.slot.model.trim().isEmpty
                            ? _defaultOpenAiSearchModel
                            : widget.slot.model
                      : '',
                ),
              );
            },
          ),
          const SizedBox(height: 10),
          _InlineEditableField(
            title: copy.networkSearchLabelFieldTitle,
            hintText: copy.networkSearchLabelHint,
            controller: _labelController,
            focusNode: _labelFocusNode,
            onChanged: (_) => _scheduleEmit(),
          ),
          if (_showsBaseUrlField) ...[
            const SizedBox(height: 8),
            _InlineEditableField(
              title: copy.networkSearchBaseUrlFieldTitle,
              hintText: copy.networkSearchBaseUrlHint,
              controller: _baseUrlController,
              focusNode: _baseUrlFocusNode,
              keyboardType: TextInputType.url,
              onChanged: (_) => _scheduleEmit(),
            ),
          ],
          if (_showsBaseUrlField) ...[
            const SizedBox(height: 8),
            _InlineEditableField(
              title: copy.networkSearchModelFieldTitle,
              hintText: copy.networkSearchModelHint,
              controller: _modelController,
              focusNode: _modelFocusNode,
              onChanged: (_) => _scheduleEmit(),
            ),
          ],
          const SizedBox(height: 8),
          _InlineEditableField(
            title: copy.networkSearchApiKeyFieldTitle,
            hintText: copy.networkSearchApiKeyHint,
            controller: _apiKeyController,
            focusNode: _apiKeyFocusNode,
            keyboardType: TextInputType.visiblePassword,
            onChanged: (_) => _scheduleEmit(),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              if (widget.canMoveUp)
                _InlineTextAction(
                  label: copy.networkSearchMoveUp,
                  onTap: widget.onMoveUp,
                ),
              if (widget.canMoveUp && widget.canMoveDown)
                const SizedBox(width: 12),
              if (widget.canMoveDown)
                _InlineTextAction(
                  label: copy.networkSearchMoveDown,
                  onTap: widget.onMoveDown,
                ),
              const Spacer(),
              _InlineTextAction(
                label: copy.networkSearchDelete,
                color: OpenCrayColors.dangerText,
                onTap: widget.onDelete,
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _handleFocusChange() {
    if (!_labelFocusNode.hasFocus &&
        !_baseUrlFocusNode.hasFocus &&
        !_modelFocusNode.hasFocus &&
        !_apiKeyFocusNode.hasFocus) {
      _emitNow();
    }
  }

  void _scheduleEmit() {
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 300), _emitNow);
  }

  void _emitNow() {
    _saveDebounce?.cancel();
    widget.onChanged(
      widget.slot.copyWith(
        label: _labelController.text,
        baseUrl: _showsBaseUrlField ? _baseUrlController.text : '',
        model: _showsBaseUrlField ? _modelController.text : '',
        apiKey: _apiKeyController.text,
      ),
    );
  }

  bool get _showsBaseUrlField =>
      widget.slot.providerId == _openAiWebSearchProviderId;
}

class _LlmSettingsPage extends StatefulWidget {
  const _LlmSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_LlmSettingsPage> createState() => _LlmSettingsPageState();
}

class _LlmSettingsPageState extends State<_LlmSettingsPage> {
  static const List<String> _providerModeOptions = <String>[
    'cloud',
    'on_device_model',
  ];
  static const List<String> _protocolOptions = <String>[
    'openai',
    'openai_responses',
    'anthropic',
  ];
  static const List<String> _reasoningOptions = <String>[
    'low',
    'medium',
    'high',
    'xhigh',
  ];
  static const List<String> _anthropicReasoningOptions = <String>[
    'off',
    'low',
    'medium',
    'high',
    'xhigh',
  ];
  static const List<String> _openAiPromptCacheKeyStrategyOptions = <String>[
    'none',
    'route',
    'session',
  ];
  static const List<String> _openAiPromptCacheRetentionOptions = <String>[
    '',
    'in_memory',
    '24h',
  ];
  static const List<String> _anthropicPromptCacheTtlOptions = <String>[
    '5m',
    '1h',
  ];
  static const List<String> _contextBudgetPresetOptions = <String>[
    'compact',
    'balanced',
    'expanded',
    'dev',
  ];
  static const List<String> _acceleratorOptions = <String>['gpu', 'cpu'];
  static const int _minOnDeviceContextWindow = 1024;
  static const int _maxOnDeviceContextWindow = 131072;
  static const int _minOnDeviceMaxTokens = 256;
  static const int _minOnDeviceTopK = 1;
  static const int _maxOnDeviceTopK = 128;

  final TextEditingController _providerNameController = TextEditingController();
  final TextEditingController _providerNotesController =
      TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _modelController = TextEditingController();
  final TextEditingController _systemPromptController = TextEditingController();
  final TextEditingController _onDeviceMaxContextController =
      TextEditingController();
  final TextEditingController _onDeviceMaxTokensController =
      TextEditingController();
  final TextEditingController _onDeviceTopKController = TextEditingController();
  final TextEditingController _onDeviceTopPController = TextEditingController();
  final TextEditingController _onDeviceTemperatureController =
      TextEditingController();
  final TextEditingController _contextBudgetReservedOutputController =
      TextEditingController();
  final TextEditingController _contextBudgetSafetyMarginController =
      TextEditingController();
  final TextEditingController _contextBudgetEffectiveInputPercentController =
      TextEditingController();
  final FocusNode _providerNameFocusNode = FocusNode();
  final FocusNode _providerNotesFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();
  final FocusNode _modelFocusNode = FocusNode();
  final FocusNode _systemPromptFocusNode = FocusNode();
  final FocusNode _onDeviceMaxContextFocusNode = FocusNode();
  final FocusNode _onDeviceMaxTokensFocusNode = FocusNode();
  final FocusNode _onDeviceTopKFocusNode = FocusNode();
  final FocusNode _onDeviceTopPFocusNode = FocusNode();
  final FocusNode _onDeviceTemperatureFocusNode = FocusNode();
  final FocusNode _contextBudgetReservedOutputFocusNode = FocusNode();
  final FocusNode _contextBudgetSafetyMarginFocusNode = FocusNode();
  final FocusNode _contextBudgetEffectiveInputPercentFocusNode = FocusNode();

  LlmConfigSnapshot? _snapshot;
  String _providerMode = 'cloud';
  String _selectedProviderOptionId = 'custom';
  String _providerId = 'custom';
  String _protocol = 'openai';
  String _reasoningEffort = 'medium';
  bool _streamingEnabled = true;
  String _openAiPromptCacheKeyStrategy = 'none';
  String _openAiPromptCacheRetention = '';
  bool _anthropicPromptCachingEnabled = false;
  String _anthropicPromptCacheTtl = '5m';
  String _selectedOnDeviceModelId = 'gemma-4-e2b-it';
  int _onDeviceMaxContextWindow = 32768;
  int _onDeviceMaxTokens = 4096;
  int _onDeviceTopK = 40;
  double _onDeviceTopP = 0.95;
  double _onDeviceTemperature = 0.70;
  String _onDeviceAccelerator = 'gpu';
  bool _onDeviceThinkingEnabled = false;
  bool _onDeviceLiteModeEnabled = false;
  String _contextBudgetPreset = 'balanced';
  int? _contextBudgetReservedOutputTokens;
  int? _contextBudgetSafetyMarginTokens;
  double? _contextBudgetEffectiveInputPercent;
  bool _isApplyingSnapshot = false;
  bool _isSavingDraft = false;
  bool _isSavingCustomProvider = false;
  bool _hasQueuedSave = false;
  bool _isValidating = false;
  bool _isOnDeviceModelActionPending = false;
  Timer? _onDeviceModelStatusPollTimer;
  Completer<void>? _activeSaveCompleter;

  @override
  void initState() {
    super.initState();
    _registerAutosaveFocusNode(_providerNameFocusNode);
    _registerAutosaveFocusNode(_providerNotesFocusNode);
    _registerAutosaveFocusNode(_baseUrlFocusNode);
    _registerAutosaveFocusNode(_apiKeyFocusNode);
    _registerAutosaveFocusNode(_modelFocusNode);
    _registerAutosaveFocusNode(_systemPromptFocusNode);
    _registerAutosaveFocusNode(
      _onDeviceMaxContextFocusNode,
      onBlur: _normalizeOnDeviceMaxContextField,
    );
    _registerAutosaveFocusNode(
      _onDeviceMaxTokensFocusNode,
      onBlur: _normalizeOnDeviceMaxTokensField,
    );
    _registerAutosaveFocusNode(
      _onDeviceTopKFocusNode,
      onBlur: _normalizeOnDeviceTopKField,
    );
    _registerAutosaveFocusNode(
      _onDeviceTopPFocusNode,
      onBlur: _normalizeOnDeviceTopPField,
    );
    _registerAutosaveFocusNode(
      _onDeviceTemperatureFocusNode,
      onBlur: _normalizeOnDeviceTemperatureField,
    );
    _registerAutosaveFocusNode(
      _contextBudgetReservedOutputFocusNode,
      onBlur: _normalizeContextBudgetReservedOutputField,
    );
    _registerAutosaveFocusNode(
      _contextBudgetSafetyMarginFocusNode,
      onBlur: _normalizeContextBudgetSafetyMarginField,
    );
    _registerAutosaveFocusNode(
      _contextBudgetEffectiveInputPercentFocusNode,
      onBlur: _normalizeContextBudgetEffectiveInputPercentField,
    );
    _providerNameController.addListener(_handleCustomProviderDraftChanged);
    _providerNotesController.addListener(_handleCustomProviderDraftChanged);
    _baseUrlController.addListener(_handleCustomProviderDraftChanged);
    _apiKeyController.addListener(_handleCustomProviderDraftChanged);
    _modelController.addListener(_handleCustomProviderDraftChanged);
    _load();
  }

  @override
  void dispose() {
    _onDeviceModelStatusPollTimer?.cancel();
    _providerNameController.dispose();
    _providerNotesController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelController.dispose();
    _systemPromptController.dispose();
    _onDeviceMaxContextController.dispose();
    _onDeviceMaxTokensController.dispose();
    _onDeviceTopKController.dispose();
    _onDeviceTopPController.dispose();
    _onDeviceTemperatureController.dispose();
    _contextBudgetReservedOutputController.dispose();
    _contextBudgetSafetyMarginController.dispose();
    _contextBudgetEffectiveInputPercentController.dispose();
    _providerNameFocusNode.dispose();
    _providerNotesFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    _modelFocusNode.dispose();
    _systemPromptFocusNode.dispose();
    _onDeviceMaxContextFocusNode.dispose();
    _onDeviceMaxTokensFocusNode.dispose();
    _onDeviceTopKFocusNode.dispose();
    _onDeviceTopPFocusNode.dispose();
    _onDeviceTemperatureFocusNode.dispose();
    _contextBudgetReservedOutputFocusNode.dispose();
    _contextBudgetSafetyMarginFocusNode.dispose();
    _contextBudgetEffectiveInputPercentFocusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return const _SettingsLoading(
        key: ValueKey<String>('settings-llm-loading'),
      );
    }
    final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
    final selectedProvider = _selectedProviderFor(snapshot);
    final hasTemporarySavedCustomChanges =
        _hasTemporarySavedCustomProviderChanges(snapshot);
    final selectedProtocol = _draftProtocolFor(selectedProvider);
    final optionsLabel = copy.llmOptionsCount(snapshot.providerOptions.length);
    final isCloudMode = _providerMode == 'cloud';
    final showsReasoning =
        isCloudMode &&
        (selectedProtocol == 'anthropic' ||
            _modelController.text.toLowerCase().contains('gpt'));
    final reasoningLabel = selectedProtocol == 'anthropic'
        ? copy.llmThinkingLabel
        : copy.llmReasoningEffortLabel;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          const Text('LLM', style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(copy.llmPageSubtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _buildModelSourceCard(copy),
          const SizedBox(height: 16),
          if (isCloudMode) ...[
            _buildCloudProviderCard(
              copy,
              selectedProvider,
              optionsLabel,
              hasTemporarySavedCustomChanges,
              selectedProtocol,
            ),
            const SizedBox(height: 16),
            _buildPromptCacheCard(copy, selectedProtocol),
            const SizedBox(height: 16),
            _buildCloudConnectionCard(
              copy,
              showsReasoning,
              selectedProtocol,
              reasoningLabel,
            ),
            const SizedBox(height: 16),
            _buildContextBudgetCard(copy),
            const SizedBox(height: 16),
            _buildAdvancedPromptCard(copy, snapshot.helperText),
          ] else ...[
            _buildOnDeviceModelCard(copy, snapshot.onDeviceModels),
            const SizedBox(height: 16),
            _buildOnDeviceSamplingCard(copy),
            const SizedBox(height: 16),
            _buildContextBudgetCard(copy),
            const SizedBox(height: 16),
            _buildOnDeviceRuntimeCard(copy),
          ],
        ],
      ),
    );
  }

  Widget _buildModelSourceCard(OpenCrayUiCopy copy) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.llmModelSourceTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          _InteractiveSegmentedSelector(
            labels: _providerModeOptions,
            selectedId: _providerMode,
            labelBuilder: _providerModeTitle,
            onSelected: _handleProviderModeSelected,
          ),
        ],
      ),
    );
  }

  Widget _buildCloudProviderCard(
    OpenCrayUiCopy copy,
    LlmProviderOption selectedProvider,
    String optionsLabel,
    bool hasTemporarySavedCustomChanges,
    String selectedProtocol,
  ) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.llmPrimaryProviderTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 10),
          _PrototypeSelectionRow(
            title: selectedProvider.title,
            trailingLabel: optionsLabel,
            onTap: _openProviderSheet,
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  hasTemporarySavedCustomChanges
                      ? copy.llmSaveProviderTemporary
                      : (selectedProvider.isCustom
                            ? copy.llmPrimaryProviderCustomHelper
                            : copy.llmPrimaryProviderPresetHelper),
                  maxLines: hasTemporarySavedCustomChanges ? 1 : null,
                  overflow: hasTemporarySavedCustomChanges
                      ? TextOverflow.ellipsis
                      : TextOverflow.visible,
                  softWrap: !hasTemporarySavedCustomChanges,
                  style: _SettingsTextStyles.body,
                ),
              ),
              if (selectedProvider.isCustom) ...[
                const SizedBox(width: 12),
                InkWell(
                  key: const ValueKey<String>('settings-llm-save-provider'),
                  borderRadius: BorderRadius.circular(8),
                  onTap: _isSavingCustomProvider ? null : _saveCustomProvider,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 2,
                    ),
                    child: Text(
                      _isSavingCustomProvider
                          ? copy.llmSaving
                          : copy.llmSaveProviderAction,
                      style: _SettingsTextStyles.inlineAction,
                    ),
                  ),
                ),
              ],
            ],
          ),
          if (selectedProvider.isCustom) ...[
            const SizedBox(height: 12),
            const Divider(height: 1, color: OpenCrayColors.divider),
            const SizedBox(height: 12),
            Text(copy.llmProtocolTitle, style: _SettingsTextStyles.fieldLabel),
            const SizedBox(height: 8),
            _PrototypeSelectionRow(
              title: _protocolTitle(selectedProtocol),
              trailingLabel: copy.llmOptionsCount(_protocolOptions.length),
              compact: true,
              onTap: _openProtocolSheet,
            ),
            const SizedBox(height: 12),
            _PrototypeField(
              label: copy.llmProviderNameLabel,
              controller: _providerNameController,
              focusNode: _providerNameFocusNode,
              hintText: copy.llmProviderNameHint,
            ),
            const SizedBox(height: 12),
            _PrototypeField(
              label: copy.llmNotesLabel,
              controller: _providerNotesController,
              focusNode: _providerNotesFocusNode,
              hintText: copy.llmNotesHint,
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPromptCacheCard(OpenCrayUiCopy copy, String selectedProtocol) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.llmPromptCacheTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(
            _showsAnthropicPromptCacheControls(selectedProtocol)
                ? copy.llmPromptCacheAnthropicHelper
                : copy.llmPromptCacheOpenAiHelper,
            style: _SettingsTextStyles.body,
          ),
          if (_showsOpenAiPromptCacheControls(selectedProtocol)) ...[
            const SizedBox(height: 12),
            _PrototypeSelectionField(
              label: copy.llmOpenAiPromptCacheKeyLabel,
              title: _openAiPromptCacheKeyStrategyTitle(
                _openAiPromptCacheKeyStrategy,
              ),
              onTap: _openOpenAiPromptCacheKeyStrategySheet,
            ),
            const SizedBox(height: 12),
            _PrototypeSelectionField(
              label: copy.llmOpenAiPromptCacheRetentionLabel,
              title: _openAiPromptCacheRetentionTitle(
                _openAiPromptCacheRetention,
              ),
              onTap: _openOpenAiPromptCacheRetentionSheet,
            ),
          ] else if (_showsAnthropicPromptCacheControls(selectedProtocol)) ...[
            const SizedBox(height: 12),
            _PrototypeToggleRow(
              rowKey: const ValueKey<String>(
                'settings-llm-anthropic-prompt-cache-toggle',
              ),
              title: copy.llmAnthropicPromptCachingTitle,
              subtitle: copy.llmAnthropicPromptCachingSubtitle,
              value: _anthropicPromptCachingEnabled,
              onChanged: _handleAnthropicPromptCachingChanged,
            ),
            if (_anthropicPromptCachingEnabled) ...[
              const SizedBox(height: 12),
              _PrototypeSelectionField(
                label: copy.llmAnthropicPromptCacheTtlLabel,
                title: _anthropicPromptCacheTtlTitle(_anthropicPromptCacheTtl),
                onTap: _openAnthropicPromptCacheTtlSheet,
              ),
            ],
          ],
          const SizedBox(height: 16),
          _PrototypeToggleRow(
            rowKey: const ValueKey<String>('settings-llm-streaming-toggle'),
            title: copy.llmStreamingTitle,
            subtitle: copy.llmStreamingSubtitle,
            value: _streamingEnabled,
            onChanged: _handleStreamingEnabledChanged,
          ),
        ],
      ),
    );
  }

  Widget _buildCloudConnectionCard(
    OpenCrayUiCopy copy,
    bool showsReasoning,
    String selectedProtocol,
    String reasoningLabel,
  ) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  copy.llmConnectionTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
              ),
              _HeaderActionChip(
                label: _isValidating
                    ? copy.llmValidating
                    : (_isSavingDraft ? copy.llmSaving : copy.llmValidateModel),
                onTap: _isValidating ? null : _validateLlmConfig,
              ),
            ],
          ),
          const SizedBox(height: 14),
          _PrototypeField(
            label: copy.llmBaseUrlLabel,
            controller: _baseUrlController,
            focusNode: _baseUrlFocusNode,
            hintText: copy.llmBaseUrlHint,
            keyboardType: TextInputType.url,
          ),
          const SizedBox(height: 12),
          _PrototypeField(
            label: copy.llmApiKeyLabel,
            controller: _apiKeyController,
            focusNode: _apiKeyFocusNode,
            hintText: copy.llmApiKeyHint,
            obscureText: true,
            keyboardType: TextInputType.visiblePassword,
            trailing: _apiKeyController.text.trim().isEmpty
                ? null
                : _FieldClearButton(
                    buttonKey: const ValueKey<String>(
                      'settings-llm-api-key-clear',
                    ),
                    onTap: _clearApiKey,
                  ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          _PrototypeField(
            label: copy.llmModelNameLabel,
            controller: _modelController,
            focusNode: _modelFocusNode,
            hintText: copy.llmModelHint,
            onChanged: (_) => setState(() {}),
          ),
          if (showsReasoning) ...[
            const SizedBox(height: 12),
            _PrototypeSelectionField(
              label: reasoningLabel,
              title: _reasoningEffortTitle(_reasoningEffort),
              trailingLabel: selectedProtocol == 'anthropic'
                  ? (_reasoningEffort == 'off'
                        ? copy.llmAnthropicThinkingDisabled
                        : copy.llmAnthropicThinkingEnabled)
                  : copy.llmGptModelDetected,
              onTap: _openReasoningSheet,
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildContextBudgetCard(OpenCrayUiCopy copy) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Context budget', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          const Text(
            'Preset controls the normal context envelope; raw overrides are for development.',
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 12),
          _SegmentedSettingRow(
            label: 'Preset',
            width: 248,
            selectedId: _contextBudgetPreset,
            options: _contextBudgetPresetOptions,
            labelBuilder: _contextBudgetPresetTitle,
            onSelected: _handleContextBudgetPresetSelected,
          ),
          const SizedBox(height: 12),
          _BudgetOverrideRow(
            label: 'Reserved output',
            fieldKey: const ValueKey<String>(
              'settings-llm-context-budget-reserved-output',
            ),
            controller: _contextBudgetReservedOutputController,
            focusNode: _contextBudgetReservedOutputFocusNode,
            suffix: 'tokens',
            onChanged: _handleContextBudgetReservedOutputChanged,
            onClear: () => _clearContextBudgetOverride(
              controller: _contextBudgetReservedOutputController,
              setValue: () => _contextBudgetReservedOutputTokens = null,
            ),
          ),
          const SizedBox(height: 10),
          _BudgetOverrideRow(
            label: 'Safety margin',
            fieldKey: const ValueKey<String>(
              'settings-llm-context-budget-safety-margin',
            ),
            controller: _contextBudgetSafetyMarginController,
            focusNode: _contextBudgetSafetyMarginFocusNode,
            suffix: 'tokens',
            onChanged: _handleContextBudgetSafetyMarginChanged,
            onClear: () => _clearContextBudgetOverride(
              controller: _contextBudgetSafetyMarginController,
              setValue: () => _contextBudgetSafetyMarginTokens = null,
            ),
          ),
          const SizedBox(height: 10),
          _BudgetOverrideRow(
            label: 'Effective input',
            fieldKey: const ValueKey<String>(
              'settings-llm-context-budget-effective-input',
            ),
            controller: _contextBudgetEffectiveInputPercentController,
            focusNode: _contextBudgetEffectiveInputPercentFocusNode,
            suffix: '0.50-0.99',
            decimal: true,
            onChanged: _handleContextBudgetEffectiveInputPercentChanged,
            onClear: () => _clearContextBudgetOverride(
              controller: _contextBudgetEffectiveInputPercentController,
              setValue: () => _contextBudgetEffectiveInputPercent = null,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAdvancedPromptCard(OpenCrayUiCopy copy, String helperText) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.llmAdvancedPromptTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 12),
          _PrototypeField(
            label: copy.llmPromptOverrideLabel,
            controller: _systemPromptController,
            focusNode: _systemPromptFocusNode,
            hintText: copy.llmPromptOverrideHint,
            minLines: 5,
            maxLines: 8,
          ),
          const SizedBox(height: 12),
          Text(copy.llmAutosaveHint, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          Text(helperText, style: _SettingsTextStyles.body),
        ],
      ),
    );
  }

  Widget _buildOnDeviceModelCard(
    OpenCrayUiCopy copy,
    List<LlmOnDeviceModelOption> options,
  ) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.llmOnDeviceModelTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 8),
          for (int index = 0; index < options.length; index++) ...[
            _OnDeviceModelTile(
              title: options[index].title,
              subtitle: _onDeviceModelStatusLabel(options[index]),
              actionLabel: _onDeviceModelPrimaryActionLabel(options[index]),
              selected: options[index].id == _selectedOnDeviceModelId,
              actionEnabled:
                  !_isOnDeviceModelActionPending &&
                  _onDeviceModelPrimaryActionEnabled(options[index]),
              onActionTap: () =>
                  _handleOnDeviceModelPrimaryAction(options[index]),
              secondaryActionLabel: _onDeviceModelSecondaryActionLabel(
                copy,
                options[index],
              ),
              onSecondaryActionTap:
                  _isOnDeviceModelActionPending ||
                      !_onDeviceModelHasSecondaryAction(options[index])
                  ? null
                  : () => _handleOnDeviceModelSecondaryAction(options[index]),
              progressValue: _onDeviceModelProgressValue(options[index]),
            ),
            if (index != options.length - 1) const SizedBox(height: 8),
          ],
        ],
      ),
    );
  }

  Widget _buildOnDeviceSamplingCard(OpenCrayUiCopy copy) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            copy.llmSamplingLimitsTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: Text(
                  copy.llmMaxContextWindowLabel,
                  style: _SettingsTextStyles.fieldValue,
                ),
              ),
              const SizedBox(width: 12),
              _CompactInlineValueField(
                controller: _onDeviceMaxContextController,
                focusNode: _onDeviceMaxContextFocusNode,
                width: 84,
                keyboardType: TextInputType.number,
                onChanged: _handleOnDeviceMaxContextChanged,
              ),
            ],
          ),
          const SizedBox(height: 12),
          _SliderValueRow(
            label: copy.llmMaxTokensLabel,
            minLabel: '256',
            controller: _onDeviceMaxTokensController,
            focusNode: _onDeviceMaxTokensFocusNode,
            value: _onDeviceMaxTokens.toDouble(),
            min: _minOnDeviceMaxTokens.toDouble(),
            max: _maxTokensSliderMax.toDouble(),
            onSliderChanged: (value) {
              setState(() {
                _onDeviceMaxTokens = _normalizedOnDeviceMaxTokens(
                  value.round(),
                );
                _onDeviceMaxTokensController.text = _onDeviceMaxTokens
                    .toString();
              });
            },
            onSliderChangeEnd: (_) => unawaited(_saveDraft()),
            onFieldChanged: _handleOnDeviceMaxTokensChanged,
          ),
          const SizedBox(height: 12),
          _SliderValueRow(
            label: copy.llmTopKLabel,
            minLabel: '1',
            controller: _onDeviceTopKController,
            focusNode: _onDeviceTopKFocusNode,
            value: _onDeviceTopK.toDouble(),
            min: _minOnDeviceTopK.toDouble(),
            max: _maxOnDeviceTopK.toDouble(),
            onSliderChanged: (value) {
              setState(() {
                _onDeviceTopK = _normalizedOnDeviceTopK(value.round());
                _onDeviceTopKController.text = _onDeviceTopK.toString();
              });
            },
            onSliderChangeEnd: (_) => unawaited(_saveDraft()),
            onFieldChanged: _handleOnDeviceTopKChanged,
          ),
          const SizedBox(height: 12),
          _SliderValueRow(
            label: copy.llmTopPLabel,
            minLabel: '0.0',
            controller: _onDeviceTopPController,
            focusNode: _onDeviceTopPFocusNode,
            value: _onDeviceTopP,
            min: 0,
            max: 1,
            divisions: 100,
            onSliderChanged: (value) {
              setState(() {
                _onDeviceTopP = _normalizedOnDeviceProbability(value);
                _onDeviceTopPController.text = _formatDouble(_onDeviceTopP);
              });
            },
            onSliderChangeEnd: (_) => unawaited(_saveDraft()),
            onFieldChanged: _handleOnDeviceTopPChanged,
            allowDecimal: true,
          ),
          const SizedBox(height: 12),
          _SliderValueRow(
            label: copy.llmTemperatureLabel,
            minLabel: '0.0',
            controller: _onDeviceTemperatureController,
            focusNode: _onDeviceTemperatureFocusNode,
            value: _onDeviceTemperature,
            min: 0,
            max: 2,
            divisions: 200,
            onSliderChanged: (value) {
              setState(() {
                _onDeviceTemperature = _normalizedOnDeviceTemperature(value);
                _onDeviceTemperatureController.text = _formatDouble(
                  _onDeviceTemperature,
                );
              });
            },
            onSliderChangeEnd: (_) => unawaited(_saveDraft()),
            onFieldChanged: _handleOnDeviceTemperatureChanged,
            allowDecimal: true,
          ),
        ],
      ),
    );
  }

  Widget _buildOnDeviceRuntimeCard(OpenCrayUiCopy copy) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.llmRuntimeTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          _PrototypeToggleRow(
            rowKey: const ValueKey<String>(
              'settings-llm-on-device-lite-mode-toggle',
            ),
            title: copy.llmOnDeviceLiteModeTitle,
            subtitle: copy.llmOnDeviceLiteModeBody,
            value: _onDeviceLiteModeEnabled,
            onChanged: (value) {
              setState(() => _onDeviceLiteModeEnabled = value);
              unawaited(_saveDraft());
            },
          ),
          const SizedBox(height: 12),
          _SegmentedSettingRow(
            label: copy.llmAcceleratorLabel,
            width: 144,
            selectedId: _onDeviceAccelerator,
            options: _acceleratorOptions,
            labelBuilder: (value) => value == 'cpu'
                ? copy.llmAcceleratorCpu
                : copy.llmAcceleratorGpu,
            onSelected: (value) {
              setState(() => _onDeviceAccelerator = value);
              unawaited(_saveDraft());
            },
          ),
          const SizedBox(height: 12),
          _SegmentedSettingRow(
            label: copy.llmThinkingLabel,
            width: 144,
            selectedId: _onDeviceThinkingEnabled ? 'on' : 'off',
            options: const <String>['off', 'on'],
            labelBuilder: (value) =>
                value == 'on' ? copy.llmThinkingOn : copy.llmThinkingOff,
            onSelected: (value) {
              setState(() => _onDeviceThinkingEnabled = value == 'on');
              unawaited(_saveDraft());
            },
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    final snapshot = await widget.facade.loadLlmConfig();
    if (!mounted) {
      return;
    }
    setState(() {
      _applySnapshot(snapshot);
    });
    _syncOnDeviceModelPolling(snapshot.onDeviceModels);
  }

  void _applyProvider(LlmProviderOption option) {
    _isApplyingSnapshot = true;
    setState(() {
      _providerMode = 'cloud';
      _selectedProviderOptionId = option.id;
      _providerId = option.providerId;
      final isSavedCustomProvider = option.isCustom && option.id != 'custom';
      _protocol = option.protocol;
      if (isSavedCustomProvider) {
        _providerNameController.text = option.title;
        _providerNotesController.text = option.subtitle;
        _baseUrlController.text = option.defaultBaseUrl;
        _apiKeyController.text = option.apiKey;
        _modelController.text = option.defaultModel;
      } else if (_providerNameController.text.trim().isEmpty ||
          _providerNameController.text.trim() ==
              _snapshot?.providerName.trim()) {
        _providerNameController.text = option.title;
      }
      if (!isSavedCustomProvider && option.defaultBaseUrl.isNotEmpty) {
        _baseUrlController.text = option.defaultBaseUrl;
      }
      if (!isSavedCustomProvider && option.defaultModel.isNotEmpty) {
        _modelController.text = option.defaultModel;
      }
    });
    _isApplyingSnapshot = false;
    unawaited(_saveDraft());
  }

  void _clearApiKey() {
    if (_apiKeyController.text.isEmpty) {
      return;
    }
    setState(() {
      _apiKeyController.clear();
    });
    unawaited(_saveDraft());
  }

  Future<void> _saveCustomProvider() async {
    final snapshot = _snapshot;
    if (snapshot == null || _isSavingCustomProvider) {
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _isSavingCustomProvider = true;
    });
    FocusScope.of(context).unfocus();
    if (_isSavingDraft) {
      await _activeSaveCompleter?.future;
    }
    try {
      final savedSnapshot = await widget.facade.saveCustomLlmProvider(
        selectedProviderOptionId: _selectedProviderOptionId,
        streamingEnabled: _streamingEnabled,
        protocol: _draftProtocol(),
        providerName: _providerNameController.text,
        providerNotes: _providerNotesController.text,
        baseUrl: _baseUrlController.text,
        apiKey: _apiKeyController.text,
        model: _modelController.text,
        reasoningEffort: _reasoningEffort,
        systemPrompt: _systemPromptController.text,
        openAiPromptCacheKeyStrategy: _openAiPromptCacheKeyStrategy,
        openAiPromptCacheRetention: _openAiPromptCacheRetention,
        anthropicPromptCachingEnabled: _anthropicPromptCachingEnabled,
        anthropicPromptCacheTtl: _anthropicPromptCacheTtl,
      );
      if (!mounted) {
        return;
      }
      final copy = OpenCrayUiCopy.fromLocaleTag(savedSnapshot.localeTag);
      setState(() {
        _applySnapshot(savedSnapshot);
      });
      _showMessage(copy.llmSaveProviderSuccess);
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSavingCustomProvider = false;
        });
      }
    }
  }

  Future<void> _validateLlmConfig() async {
    if (_isValidating || _providerMode != 'cloud') {
      return;
    }
    final copy = _copyForSnapshot();
    setState(() {
      _isValidating = true;
    });
    try {
      FocusScope.of(context).unfocus();
      await _saveDraft();
      if (!mounted) {
        return;
      }
      if (_baseUrlController.text.trim().isEmpty) {
        _showMessage(copy.llmValidateRequiresBaseUrl);
        return;
      }
      if (_modelController.text.trim().isEmpty) {
        _showMessage(copy.llmValidateRequiresModel);
        return;
      }
      final validationResult = await widget.facade.validateLlmConfig(
        providerId: _providerId,
        protocol: _draftProtocol(),
        baseUrl: _baseUrlController.text,
        apiKey: _apiKeyController.text,
        model: _modelController.text,
        reasoningEffort: _reasoningEffort,
      );
      if (!mounted) {
        return;
      }
      _showMessage(validationResult.message);
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isValidating = false;
        });
      }
    }
  }

  Future<void> _openProviderSheet() async {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return;
    }
    final copy = _copyForSnapshot();
    final selected = await showModalBottomSheet<LlmProviderOption>(
      context: context,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
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
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      copy.llmPrimaryProviderTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    Flexible(
                      child: SingleChildScrollView(
                        key: const ValueKey<String>(
                          'settings-llm-provider-sheet-scroll',
                        ),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            for (final option in snapshot.providerOptions)
                              InkWell(
                                borderRadius: BorderRadius.circular(14),
                                onTap: () => Navigator.of(context).pop(option),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                  ),
                                  child: Row(
                                    children: [
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            Text(
                                              option.title,
                                              style:
                                                  _SettingsTextStyles.rowTitle,
                                            ),
                                            if (option.subtitle.isNotEmpty) ...[
                                              const SizedBox(height: 4),
                                              Text(
                                                option.subtitle,
                                                style: _SettingsTextStyles
                                                    .rowSubtitle,
                                              ),
                                            ],
                                          ],
                                        ),
                                      ),
                                      if (option.id ==
                                          _selectedProviderOptionId)
                                        const Icon(
                                          Icons.check_rounded,
                                          color: OpenCrayColors.primary,
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
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (selected != null) {
      _applyProvider(selected);
    }
  }

  Future<void> _openReasoningSheet() async {
    final copy = _copyForSnapshot();
    final label = _draftProtocol() == 'anthropic'
        ? copy.llmThinkingLabel
        : copy.llmReasoningEffortLabel;
    final options = _reasoningOptionsForProtocol(_draftProtocol());
    final selected = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
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
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(label, style: _SettingsTextStyles.cardTitle),
                    const SizedBox(height: 12),
                    for (final option in options)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  _reasoningEffortTitle(option),
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option == _reasoningEffort)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
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
    if (selected != null && mounted) {
      setState(() => _reasoningEffort = selected);
      unawaited(_saveDraft());
    }
  }

  Future<void> _openProtocolSheet() async {
    final copy = _copyForSnapshot();
    final selected = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
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
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      copy.llmProtocolTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final option in _protocolOptions)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  _protocolTitle(option),
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option == _protocol)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
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
    if (selected != null && mounted) {
      setState(() {
        _protocol = selected;
        if (selected != 'anthropic' && _reasoningEffort == 'off') {
          _reasoningEffort = 'medium';
        }
      });
      _handleCustomProviderDraftChanged();
      unawaited(_saveDraft());
    }
  }

  Future<void> _openOpenAiPromptCacheKeyStrategySheet() async {
    final copy = _copyForSnapshot();
    final selected = await _openStringSelectionSheet(
      title: copy.llmOpenAiPromptCacheKeyLabel,
      options: _openAiPromptCacheKeyStrategyOptions,
      selectedValue: _openAiPromptCacheKeyStrategy,
      labelBuilder: _openAiPromptCacheKeyStrategyTitle,
    );
    if (selected != null && mounted) {
      setState(() => _openAiPromptCacheKeyStrategy = selected);
      unawaited(_saveDraft());
    }
  }

  Future<void> _openOpenAiPromptCacheRetentionSheet() async {
    final copy = _copyForSnapshot();
    final selected = await _openStringSelectionSheet(
      title: copy.llmOpenAiPromptCacheRetentionLabel,
      options: _openAiPromptCacheRetentionOptions,
      selectedValue: _openAiPromptCacheRetention,
      labelBuilder: _openAiPromptCacheRetentionTitle,
    );
    if (selected != null && mounted) {
      setState(() => _openAiPromptCacheRetention = selected);
      unawaited(_saveDraft());
    }
  }

  Future<void> _openAnthropicPromptCacheTtlSheet() async {
    final copy = _copyForSnapshot();
    final selected = await _openStringSelectionSheet(
      title: copy.llmAnthropicPromptCacheTtlLabel,
      options: _anthropicPromptCacheTtlOptions,
      selectedValue: _anthropicPromptCacheTtl,
      labelBuilder: _anthropicPromptCacheTtlTitle,
    );
    if (selected != null && mounted) {
      setState(() => _anthropicPromptCacheTtl = selected);
      unawaited(_saveDraft());
    }
  }

  void _handleAnthropicPromptCachingChanged(bool value) {
    if (!mounted) {
      return;
    }
    setState(() => _anthropicPromptCachingEnabled = value);
    unawaited(_saveDraft());
  }

  void _handleStreamingEnabledChanged(bool value) {
    if (!mounted) {
      return;
    }
    setState(() => _streamingEnabled = value);
    unawaited(_saveDraft());
  }

  Future<String?> _openStringSelectionSheet({
    required String title,
    required List<String> options,
    required String selectedValue,
    required String Function(String value) labelBuilder,
  }) async {
    return showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
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
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(title, style: _SettingsTextStyles.cardTitle),
                    const SizedBox(height: 12),
                    for (final option in options)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(option),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  labelBuilder(option),
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option == selectedValue)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
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
  }

  void _registerAutosaveFocusNode(FocusNode focusNode, {VoidCallback? onBlur}) {
    focusNode.addListener(() {
      if (!focusNode.hasFocus && !_isSavingCustomProvider) {
        onBlur?.call();
        unawaited(_saveDraft());
      }
    });
  }

  void _handleCustomProviderDraftChanged() {
    if (_isApplyingSnapshot || !mounted) {
      return;
    }
    setState(() {});
  }

  bool _draftMatchesProvider(LlmProviderOption provider) =>
      _providerId == provider.providerId &&
      _draftProtocolFor(provider) == provider.protocol &&
      _providerNameController.text.trim() == provider.title.trim() &&
      _providerNotesController.text.trim() == provider.subtitle.trim() &&
      _baseUrlController.text.trim() == provider.defaultBaseUrl.trim() &&
      _apiKeyController.text.trim() == provider.apiKey.trim() &&
      _modelController.text.trim() == provider.defaultModel.trim();

  bool _hasTemporarySavedCustomProviderChanges(LlmConfigSnapshot snapshot) {
    final selectedProvider = _selectedProviderFor(snapshot);
    return _isSavedCustomProvider(selectedProvider) &&
        !_draftMatchesProvider(selectedProvider);
  }

  bool _isSavedCustomProvider(LlmProviderOption provider) =>
      provider.isCustom && provider.id != 'custom';

  LlmProviderOption _selectedProviderFor(LlmConfigSnapshot snapshot) {
    if (snapshot.providerOptions.isEmpty) {
      final copy = OpenCrayUiCopy.fromLocaleTag(snapshot.localeTag);
      return LlmProviderOption(
        id: 'custom',
        providerId: 'custom',
        title: copy.llmFallbackCustomProviderTitle,
        subtitle: copy.llmFallbackCustomProviderSubtitle,
        defaultBaseUrl: '',
        defaultModel: '',
        protocol: 'openai',
        apiKey: '',
        isCustom: true,
      );
    }
    final candidateIds = <String>[
      _selectedProviderOptionId,
      snapshot.selectedProviderOptionId,
      _providerId,
      snapshot.providerId,
    ];
    for (final candidateId in candidateIds) {
      final match = snapshot.providerOptions.where(
        (option) => option.id == candidateId,
      );
      if (match.isNotEmpty) {
        return match.first;
      }
    }
    return snapshot.providerOptions.first;
  }

  void _applySnapshot(LlmConfigSnapshot snapshot) {
    _isApplyingSnapshot = true;
    _snapshot = snapshot;
    _providerMode = _providerModeOptions.contains(snapshot.providerMode)
        ? snapshot.providerMode
        : 'cloud';
    _selectedProviderOptionId = snapshot.selectedProviderOptionId;
    _providerId = snapshot.providerId;
    _protocol = snapshot.protocol;
    _providerNameController.text = snapshot.providerName;
    _providerNotesController.text = snapshot.providerNotes;
    _baseUrlController.text = snapshot.baseUrl;
    _apiKeyController.text = snapshot.apiKey;
    _modelController.text = snapshot.model;
    _reasoningEffort = snapshot.reasoningEffort;
    _streamingEnabled = snapshot.streamingEnabled;
    _systemPromptController.text = snapshot.systemPrompt;
    _openAiPromptCacheKeyStrategy = snapshot.openAiPromptCacheKeyStrategy;
    _openAiPromptCacheRetention = snapshot.openAiPromptCacheRetention;
    _anthropicPromptCachingEnabled = snapshot.anthropicPromptCachingEnabled;
    _anthropicPromptCacheTtl = snapshot.anthropicPromptCacheTtl;
    _selectedOnDeviceModelId = snapshot.selectedOnDeviceModelId.trim().isEmpty
        ? _selectedOnDeviceModelId
        : snapshot.selectedOnDeviceModelId;
    _onDeviceMaxContextWindow = _normalizedOnDeviceContextWindow(
      snapshot.onDeviceMaxContextWindow,
    );
    _onDeviceMaxTokens = _normalizedOnDeviceMaxTokens(
      snapshot.onDeviceMaxTokens,
    );
    _onDeviceTopK = _normalizedOnDeviceTopK(snapshot.onDeviceTopK);
    _onDeviceTopP = _normalizedOnDeviceProbability(snapshot.onDeviceTopP);
    _onDeviceTemperature = _normalizedOnDeviceTemperature(
      snapshot.onDeviceTemperature,
    );
    _onDeviceAccelerator =
        _acceleratorOptions.contains(snapshot.onDeviceAccelerator)
        ? snapshot.onDeviceAccelerator
        : 'gpu';
    _onDeviceThinkingEnabled = snapshot.onDeviceThinkingEnabled;
    _onDeviceLiteModeEnabled = snapshot.onDeviceLiteModeEnabled;
    _contextBudgetPreset = _normalizedContextBudgetPreset(
      snapshot.contextBudgetPreset,
    );
    _contextBudgetReservedOutputTokens = _normalizedContextBudgetTokenOverride(
      snapshot.contextBudgetReservedOutputTokens,
    );
    _contextBudgetSafetyMarginTokens = _normalizedContextBudgetTokenOverride(
      snapshot.contextBudgetSafetyMarginTokens,
    );
    _contextBudgetEffectiveInputPercent =
        _normalizedContextBudgetEffectiveInputPercent(
          snapshot.contextBudgetEffectiveInputPercent,
        );
    _syncOnDeviceControllers();
    _syncContextBudgetControllers();
    _isApplyingSnapshot = false;
  }

  bool _hasDraftChanges() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return false;
    }
    return _providerMode != snapshot.providerMode ||
        _providerId != snapshot.providerId ||
        _selectedProviderOptionId != snapshot.selectedProviderOptionId ||
        _draftProtocol() != snapshot.protocol ||
        _streamingEnabled != snapshot.streamingEnabled ||
        _providerNameController.text != snapshot.providerName ||
        _providerNotesController.text != snapshot.providerNotes ||
        _baseUrlController.text != snapshot.baseUrl ||
        _apiKeyController.text != snapshot.apiKey ||
        _modelController.text != snapshot.model ||
        _reasoningEffort != snapshot.reasoningEffort ||
        _systemPromptController.text != snapshot.systemPrompt ||
        _openAiPromptCacheKeyStrategy !=
            snapshot.openAiPromptCacheKeyStrategy ||
        _openAiPromptCacheRetention != snapshot.openAiPromptCacheRetention ||
        _anthropicPromptCachingEnabled !=
            snapshot.anthropicPromptCachingEnabled ||
        _anthropicPromptCacheTtl != snapshot.anthropicPromptCacheTtl ||
        _selectedOnDeviceModelId != snapshot.selectedOnDeviceModelId ||
        _onDeviceMaxContextWindow != snapshot.onDeviceMaxContextWindow ||
        _onDeviceMaxTokens != snapshot.onDeviceMaxTokens ||
        _onDeviceTopK != snapshot.onDeviceTopK ||
        _onDeviceTopP != snapshot.onDeviceTopP ||
        _onDeviceTemperature != snapshot.onDeviceTemperature ||
        _onDeviceAccelerator != snapshot.onDeviceAccelerator ||
        _onDeviceThinkingEnabled != snapshot.onDeviceThinkingEnabled ||
        _onDeviceLiteModeEnabled != snapshot.onDeviceLiteModeEnabled ||
        _contextBudgetPreset !=
            _normalizedContextBudgetPreset(snapshot.contextBudgetPreset) ||
        _contextBudgetReservedOutputTokens !=
            _normalizedContextBudgetTokenOverride(
              snapshot.contextBudgetReservedOutputTokens,
            ) ||
        _contextBudgetSafetyMarginTokens !=
            _normalizedContextBudgetTokenOverride(
              snapshot.contextBudgetSafetyMarginTokens,
            ) ||
        _contextBudgetEffectiveInputPercent !=
            _normalizedContextBudgetEffectiveInputPercent(
              snapshot.contextBudgetEffectiveInputPercent,
            );
  }

  bool _draftIsConfigured() => _providerMode == 'on_device_model'
      ? _selectedOnDeviceModelId.trim().isNotEmpty &&
            (_snapshot?.onDeviceModels.any(
                  (option) =>
                      option.id == _selectedOnDeviceModelId &&
                      _normalizedOnDeviceInstallState(option) == 'ready',
                ) ??
                false)
      : (_baseUrlController.text.trim().isNotEmpty &&
            (_apiKeyController.text.trim().isNotEmpty ||
                _llmEndpointAllowsBlankApiKey(
                  protocol: _draftProtocol(),
                  baseUrl: _baseUrlController.text,
                )));

  Future<void> _saveDraft() async {
    if (_snapshot == null ||
        _isApplyingSnapshot ||
        _isSavingCustomProvider ||
        !_hasDraftChanges()) {
      return;
    }
    if (_isSavingDraft) {
      _hasQueuedSave = true;
      await _activeSaveCompleter?.future;
      if (!_hasDraftChanges()) {
        return;
      }
    }
    final completer = Completer<void>();
    _activeSaveCompleter = completer;
    if (mounted) {
      setState(() {
        _isSavingDraft = true;
      });
    }
    try {
      final savedSnapshot = await widget.facade.saveLlmConfig(
        enabled: _draftIsConfigured(),
        streamingEnabled: _streamingEnabled,
        providerMode: _providerMode,
        providerId: _providerId,
        selectedProviderOptionId: _selectedProviderOptionId,
        protocol: _draftProtocol(),
        providerName: _providerNameController.text,
        providerNotes: _providerNotesController.text,
        baseUrl: _baseUrlController.text,
        apiKey: _apiKeyController.text,
        model: _modelController.text,
        reasoningEffort: _reasoningEffort,
        systemPrompt: _systemPromptController.text,
        openAiPromptCacheKeyStrategy: _openAiPromptCacheKeyStrategy,
        openAiPromptCacheRetention: _openAiPromptCacheRetention,
        anthropicPromptCachingEnabled: _anthropicPromptCachingEnabled,
        anthropicPromptCacheTtl: _anthropicPromptCacheTtl,
        selectedOnDeviceModelId: _selectedOnDeviceModelId,
        onDeviceMaxContextWindow: _onDeviceMaxContextWindow,
        onDeviceMaxTokens: _onDeviceMaxTokens,
        onDeviceTopK: _onDeviceTopK,
        onDeviceTopP: _onDeviceTopP,
        onDeviceTemperature: _onDeviceTemperature,
        onDeviceAccelerator: _onDeviceAccelerator,
        onDeviceThinkingEnabled: _onDeviceThinkingEnabled,
        onDeviceLiteModeEnabled: _onDeviceLiteModeEnabled,
        contextBudgetPreset: _contextBudgetPreset,
        contextBudgetReservedOutputTokens: _contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens: _contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent: _contextBudgetEffectiveInputPercent,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _applySnapshot(savedSnapshot);
      });
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      completer.complete();
      if (identical(_activeSaveCompleter, completer)) {
        _activeSaveCompleter = null;
      }
      if (mounted) {
        setState(() {
          _isSavingDraft = false;
        });
      } else {
        _isSavingDraft = false;
      }
      if (_hasQueuedSave) {
        _hasQueuedSave = false;
        unawaited(_saveDraft());
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  OpenCrayUiCopy _copyForSnapshot() =>
      OpenCrayUiCopy.fromLocaleTag(_snapshot?.localeTag ?? 'en');

  int get _maxTokensSliderMax =>
      _onDeviceMaxContextWindow < _minOnDeviceMaxTokens
      ? _minOnDeviceMaxTokens
      : _onDeviceMaxContextWindow;

  String _draftProtocol() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _protocol;
    }
    return _draftProtocolFor(_selectedProviderFor(snapshot));
  }

  String _draftProtocolFor(LlmProviderOption provider) => _protocol;

  bool _showsOpenAiPromptCacheControls(String protocol) =>
      protocol == 'openai' || protocol == 'openai_responses';

  bool _showsAnthropicPromptCacheControls(String protocol) =>
      protocol == 'anthropic';

  String _protocolTitle(String protocol) {
    final copy = _copyForSnapshot();
    switch (protocol) {
      case 'openai_responses':
        return copy.llmProtocolOpenAiResponses;
      case 'anthropic':
        return copy.llmProtocolAnthropic;
      default:
        return copy.llmProtocolOpenAiCompatible;
    }
  }

  List<String> _reasoningOptionsForProtocol(String protocol) =>
      protocol == 'anthropic' ? _anthropicReasoningOptions : _reasoningOptions;

  String _reasoningEffortTitle(String reasoningEffort) =>
      _copyForSnapshot().llmReasoningTitle(reasoningEffort);

  String _openAiPromptCacheKeyStrategyTitle(String strategy) =>
      _copyForSnapshot().llmOpenAiPromptCacheKeyStrategyTitle(strategy);

  String _openAiPromptCacheRetentionTitle(String retention) =>
      _copyForSnapshot().llmOpenAiPromptCacheRetentionTitle(retention);

  String _anthropicPromptCacheTtlTitle(String ttl) =>
      _copyForSnapshot().llmAnthropicPromptCacheTtlTitle(ttl);

  String _contextBudgetPresetTitle(String preset) {
    switch (preset) {
      case 'compact':
        return 'Compact';
      case 'expanded':
        return 'Expanded';
      case 'dev':
        return 'Dev';
      default:
        return 'Balanced';
    }
  }

  void _handleProviderModeSelected(String providerMode) {
    if (!mounted ||
        !_providerModeOptions.contains(providerMode) ||
        providerMode == _providerMode) {
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _providerMode = providerMode;
    });
    unawaited(_saveDraft());
  }

  void _handleOnDeviceModelSelected(String modelId) {
    if (!mounted ||
        modelId.trim().isEmpty ||
        modelId == _selectedOnDeviceModelId) {
      return;
    }
    setState(() {
      _selectedOnDeviceModelId = modelId;
    });
    unawaited(_saveDraft());
  }

  String _providerModeTitle(String providerMode) {
    final copy = _copyForSnapshot();
    switch (providerMode) {
      case 'on_device_model':
        return copy.llmSourceOnDeviceLabel;
      default:
        return copy.llmSourceCloudLabel;
    }
  }

  String _onDeviceModelStatusLabel(LlmOnDeviceModelOption option) {
    final copy = _copyForSnapshot();
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'ready':
      case 'installed':
        return copy.llmInstalledStatus(option.sizeLabel);
      case 'downloading':
        return copy.llmDownloadingStatus(
          _formattedOnDeviceDownloadedSize(option),
          option.sizeLabel,
          speedLabel: _formattedOnDeviceDownloadSpeed(option),
        );
      case 'verifying':
      case 'downloaded':
        return copy.llmVerifyingStatus(option.sizeLabel);
      case 'failed':
        return copy.llmFailedStatus(option.lastError ?? '');
      default:
        return copy.llmNotDownloadedStatus(option.sizeLabel);
    }
  }

  String? _onDeviceModelPrimaryActionLabel(LlmOnDeviceModelOption option) {
    final copy = _copyForSnapshot();
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'ready':
      case 'installed':
        return option.id == _selectedOnDeviceModelId
            ? copy.llmSelectedChip
            : copy.llmUseModelChip;
      case 'downloading':
        return null;
      case 'verifying':
      case 'downloaded':
        return copy.llmPreparingChip;
      case 'failed':
        return copy.llmRetryModelChip;
      default:
        return copy.llmDownloadModelChip;
    }
  }

  bool _onDeviceModelPrimaryActionEnabled(LlmOnDeviceModelOption option) {
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'ready':
      case 'installed':
        return option.id != _selectedOnDeviceModelId;
      case 'failed':
      case 'not_downloaded':
        return true;
      default:
        return false;
    }
  }

  String? _onDeviceModelSecondaryActionLabel(
    OpenCrayUiCopy copy,
    LlmOnDeviceModelOption option,
  ) {
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'downloading':
        return copy.llmCancelChip;
      case 'ready':
      case 'installed':
        return option.id == _selectedOnDeviceModelId
            ? null
            : copy.llmDeleteChip;
      default:
        return null;
    }
  }

  bool _onDeviceModelHasSecondaryAction(LlmOnDeviceModelOption option) =>
      _onDeviceModelSecondaryActionLabel(_copyForSnapshot(), option) != null;

  double? _onDeviceModelProgressValue(LlmOnDeviceModelOption option) {
    if (_normalizedOnDeviceInstallState(option) != 'downloading' ||
        option.fileSizeBytes <= 0) {
      return null;
    }
    final progress =
        option.downloadedBytes.clamp(0, option.fileSizeBytes) /
        option.fileSizeBytes;
    return progress.clamp(0.0, 1.0);
  }

  String _normalizedOnDeviceInstallState(LlmOnDeviceModelOption option) {
    final normalized = option.installState.trim().toLowerCase();
    if (normalized.isEmpty) {
      return 'not_downloaded';
    }
    return normalized == 'installed' ? 'ready' : normalized;
  }

  String _formattedOnDeviceDownloadedSize(LlmOnDeviceModelOption option) {
    final downloaded = option.downloadedBytes.clamp(0, option.fileSizeBytes);
    if (downloaded <= 0) {
      return '0 GB';
    }
    final value = downloaded / (1024 * 1024 * 1024);
    return '${value.toStringAsFixed(value >= 10 ? 1 : 2)} GB';
  }

  String? _formattedOnDeviceDownloadSpeed(LlmOnDeviceModelOption option) {
    if (_normalizedOnDeviceInstallState(option) != 'downloading') {
      return null;
    }
    final bytesPerSecond = option.downloadBytesPerSecond;
    if (bytesPerSecond <= 0) {
      return null;
    }
    const units = <String>['B/s', 'KB/s', 'MB/s', 'GB/s'];
    var value = bytesPerSecond.toDouble();
    var unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex += 1;
    }
    final digits = value >= 100 ? 0 : (value >= 10 ? 1 : 2);
    return '${value.toStringAsFixed(digits)} ${units[unitIndex]}';
  }

  void _handleOnDeviceModelPrimaryAction(LlmOnDeviceModelOption option) {
    if (!_onDeviceModelPrimaryActionEnabled(option)) {
      return;
    }
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'ready':
      case 'installed':
        _handleOnDeviceModelSelected(option.id);
        return;
      case 'failed':
      case 'not_downloaded':
        unawaited(_downloadOnDeviceModel(option));
        return;
    }
  }

  void _handleOnDeviceModelSecondaryAction(LlmOnDeviceModelOption option) {
    switch (_normalizedOnDeviceInstallState(option)) {
      case 'downloading':
        unawaited(_cancelOnDeviceModelDownload(option));
        return;
      case 'ready':
      case 'installed':
        if (option.id != _selectedOnDeviceModelId) {
          unawaited(_deleteOnDeviceModel(option));
        }
        return;
    }
  }

  Future<void> _downloadOnDeviceModel(LlmOnDeviceModelOption option) async {
    final copy = _copyForSnapshot();
    if (_isOnDeviceModelActionPending) {
      return;
    }
    setState(() {
      _isOnDeviceModelActionPending = true;
    });
    try {
      final refreshed = await widget.facade.downloadOnDeviceLlmModel(option.id);
      if (!mounted) {
        return;
      }
      _mergeOnDeviceModelSnapshot(refreshed);
      final updatedOption = refreshed.onDeviceModels.firstWhere(
        (candidate) => candidate.id == option.id,
        orElse: () => option,
      );
      if (_normalizedOnDeviceInstallState(updatedOption) == 'failed' &&
          (updatedOption.lastError?.isNotEmpty ?? false)) {
        _showMessage(updatedOption.lastError!);
      } else {
        _showMessage(copy.llmModelDownloadStarted(option.title));
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isOnDeviceModelActionPending = false;
        });
      }
    }
  }

  Future<void> _cancelOnDeviceModelDownload(
    LlmOnDeviceModelOption option,
  ) async {
    final copy = _copyForSnapshot();
    if (_isOnDeviceModelActionPending) {
      return;
    }
    setState(() {
      _isOnDeviceModelActionPending = true;
    });
    try {
      final refreshed = await widget.facade.cancelOnDeviceLlmModelDownload(
        option.id,
      );
      if (!mounted) {
        return;
      }
      _mergeOnDeviceModelSnapshot(refreshed);
      _showMessage(copy.llmModelDownloadCancelled(option.title));
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isOnDeviceModelActionPending = false;
        });
      }
    }
  }

  Future<void> _deleteOnDeviceModel(LlmOnDeviceModelOption option) async {
    final copy = _copyForSnapshot();
    if (_isOnDeviceModelActionPending) {
      return;
    }
    setState(() {
      _isOnDeviceModelActionPending = true;
    });
    try {
      final refreshed = await widget.facade.deleteOnDeviceLlmModel(option.id);
      if (!mounted) {
        return;
      }
      _mergeOnDeviceModelSnapshot(refreshed);
      _showMessage(copy.llmModelDeleted(option.title));
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isOnDeviceModelActionPending = false;
        });
      }
    }
  }

  void _mergeOnDeviceModelSnapshot(LlmConfigSnapshot refreshed) {
    setState(() {
      final currentSnapshot = _snapshot;
      if (currentSnapshot == null || !_hasDraftChanges()) {
        _applySnapshot(refreshed);
      } else {
        _snapshot = currentSnapshot.copyWith(
          onDeviceModels: refreshed.onDeviceModels,
        );
      }
    });
    _syncOnDeviceModelPolling(refreshed.onDeviceModels);
  }

  void _syncOnDeviceModelPolling(List<LlmOnDeviceModelOption> options) {
    final shouldPoll = options.any((option) {
      final state = _normalizedOnDeviceInstallState(option);
      return state == 'downloading' || state == 'verifying';
    });
    if (!shouldPoll) {
      _onDeviceModelStatusPollTimer?.cancel();
      _onDeviceModelStatusPollTimer = null;
      return;
    }
    if (_onDeviceModelStatusPollTimer != null) {
      return;
    }
    _onDeviceModelStatusPollTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _refreshOnDeviceModelStatuses(),
    );
  }

  Future<void> _refreshOnDeviceModelStatuses() async {
    if (!mounted) {
      return;
    }
    try {
      final refreshed = await widget.facade.loadLlmConfig();
      if (!mounted) {
        return;
      }
      _mergeOnDeviceModelSnapshot(refreshed);
    } catch (_) {
      _onDeviceModelStatusPollTimer?.cancel();
      _onDeviceModelStatusPollTimer = null;
    }
  }

  int _normalizedOnDeviceContextWindow(int value) {
    if (value < _minOnDeviceContextWindow) {
      return _minOnDeviceContextWindow;
    }
    if (value > _maxOnDeviceContextWindow) {
      return _maxOnDeviceContextWindow;
    }
    return value;
  }

  int _normalizedOnDeviceMaxTokens(int value) {
    if (value < _minOnDeviceMaxTokens) {
      return _minOnDeviceMaxTokens;
    }
    if (value > _maxTokensSliderMax) {
      return _maxTokensSliderMax;
    }
    return value;
  }

  int _normalizedOnDeviceTopK(int value) {
    if (value < _minOnDeviceTopK) {
      return _minOnDeviceTopK;
    }
    if (value > _maxOnDeviceTopK) {
      return _maxOnDeviceTopK;
    }
    return value;
  }

  double _normalizedOnDeviceProbability(double value) {
    final clamped = value.clamp(0.0, 1.0).toDouble();
    return (clamped * 100).round() / 100;
  }

  double _normalizedOnDeviceTemperature(double value) {
    final clamped = value.clamp(0.0, 2.0).toDouble();
    return (clamped * 100).round() / 100;
  }

  String _normalizedContextBudgetPreset(String value) {
    final normalized = value.trim().toLowerCase();
    return _contextBudgetPresetOptions.contains(normalized)
        ? normalized
        : 'balanced';
  }

  int? _normalizedContextBudgetTokenOverride(int? value) {
    if (value == null || value <= 0) {
      return null;
    }
    return value.clamp(256, 262144).toInt();
  }

  double? _normalizedContextBudgetEffectiveInputPercent(double? value) {
    if (value == null || value <= 0) {
      return null;
    }
    final clamped = value.clamp(0.50, 0.99).toDouble();
    return (clamped * 100).round() / 100;
  }

  String _formatDouble(double value) => value.toStringAsFixed(2);

  void _syncOnDeviceControllers() {
    _onDeviceMaxContextController.text = _onDeviceMaxContextWindow.toString();
    _onDeviceMaxTokensController.text = _onDeviceMaxTokens.toString();
    _onDeviceTopKController.text = _onDeviceTopK.toString();
    _onDeviceTopPController.text = _formatDouble(_onDeviceTopP);
    _onDeviceTemperatureController.text = _formatDouble(_onDeviceTemperature);
  }

  void _syncContextBudgetControllers() {
    _contextBudgetReservedOutputController.text =
        _contextBudgetReservedOutputTokens?.toString() ?? '';
    _contextBudgetSafetyMarginController.text =
        _contextBudgetSafetyMarginTokens?.toString() ?? '';
    _contextBudgetEffectiveInputPercentController.text =
        _contextBudgetEffectiveInputPercent == null
        ? ''
        : _formatDouble(_contextBudgetEffectiveInputPercent!);
  }

  void _handleOnDeviceMaxContextChanged(String value) {
    final parsed = int.tryParse(value);
    if (parsed == null || !mounted) {
      return;
    }
    setState(() {
      _onDeviceMaxContextWindow = _normalizedOnDeviceContextWindow(parsed);
      _onDeviceMaxTokens = _normalizedOnDeviceMaxTokens(_onDeviceMaxTokens);
      _onDeviceMaxTokensController.text = _onDeviceMaxTokens.toString();
    });
  }

  void _handleOnDeviceMaxTokensChanged(String value) {
    final parsed = int.tryParse(value);
    if (parsed == null || !mounted) {
      return;
    }
    setState(() {
      _onDeviceMaxTokens = _normalizedOnDeviceMaxTokens(parsed);
    });
  }

  void _handleOnDeviceTopKChanged(String value) {
    final parsed = int.tryParse(value);
    if (parsed == null || !mounted) {
      return;
    }
    setState(() {
      _onDeviceTopK = _normalizedOnDeviceTopK(parsed);
    });
  }

  void _handleOnDeviceTopPChanged(String value) {
    final parsed = double.tryParse(value);
    if (parsed == null || !mounted) {
      return;
    }
    setState(() {
      _onDeviceTopP = _normalizedOnDeviceProbability(parsed);
    });
  }

  void _handleOnDeviceTemperatureChanged(String value) {
    final parsed = double.tryParse(value);
    if (parsed == null || !mounted) {
      return;
    }
    setState(() {
      _onDeviceTemperature = _normalizedOnDeviceTemperature(parsed);
    });
  }

  void _handleContextBudgetPresetSelected(String preset) {
    final normalized = _normalizedContextBudgetPreset(preset);
    if (!mounted || normalized == _contextBudgetPreset) {
      return;
    }
    setState(() {
      _contextBudgetPreset = normalized;
    });
    unawaited(_saveDraft());
  }

  void _handleContextBudgetReservedOutputChanged(String value) {
    final parsed = int.tryParse(value.trim());
    if (!mounted) {
      return;
    }
    setState(() {
      _contextBudgetReservedOutputTokens =
          _normalizedContextBudgetTokenOverride(parsed);
    });
  }

  void _handleContextBudgetSafetyMarginChanged(String value) {
    final parsed = int.tryParse(value.trim());
    if (!mounted) {
      return;
    }
    setState(() {
      _contextBudgetSafetyMarginTokens = _normalizedContextBudgetTokenOverride(
        parsed,
      );
    });
  }

  void _handleContextBudgetEffectiveInputPercentChanged(String value) {
    final parsed = double.tryParse(value.trim());
    if (!mounted) {
      return;
    }
    setState(() {
      _contextBudgetEffectiveInputPercent =
          _normalizedContextBudgetEffectiveInputPercent(parsed);
    });
  }

  void _clearContextBudgetOverride({
    required TextEditingController controller,
    required VoidCallback setValue,
  }) {
    if (!mounted) {
      return;
    }
    setState(() {
      controller.clear();
      setValue();
    });
    unawaited(_saveDraft());
  }

  void _normalizeOnDeviceMaxContextField() {
    final normalized = _normalizedOnDeviceContextWindow(
      int.tryParse(_onDeviceMaxContextController.text) ??
          _onDeviceMaxContextWindow,
    );
    if (!mounted) {
      _onDeviceMaxContextController.text = normalized.toString();
      return;
    }
    setState(() {
      _onDeviceMaxContextWindow = normalized;
      _onDeviceMaxTokens = _normalizedOnDeviceMaxTokens(_onDeviceMaxTokens);
      _syncOnDeviceControllers();
    });
  }

  void _normalizeOnDeviceMaxTokensField() {
    final normalized = _normalizedOnDeviceMaxTokens(
      int.tryParse(_onDeviceMaxTokensController.text) ?? _onDeviceMaxTokens,
    );
    if (!mounted) {
      _onDeviceMaxTokensController.text = normalized.toString();
      return;
    }
    setState(() {
      _onDeviceMaxTokens = normalized;
      _syncOnDeviceControllers();
    });
  }

  void _normalizeOnDeviceTopKField() {
    final normalized = _normalizedOnDeviceTopK(
      int.tryParse(_onDeviceTopKController.text) ?? _onDeviceTopK,
    );
    if (!mounted) {
      _onDeviceTopKController.text = normalized.toString();
      return;
    }
    setState(() {
      _onDeviceTopK = normalized;
      _syncOnDeviceControllers();
    });
  }

  void _normalizeOnDeviceTopPField() {
    final normalized = _normalizedOnDeviceProbability(
      double.tryParse(_onDeviceTopPController.text) ?? _onDeviceTopP,
    );
    if (!mounted) {
      _onDeviceTopPController.text = _formatDouble(normalized);
      return;
    }
    setState(() {
      _onDeviceTopP = normalized;
      _syncOnDeviceControllers();
    });
  }

  void _normalizeOnDeviceTemperatureField() {
    final normalized = _normalizedOnDeviceTemperature(
      double.tryParse(_onDeviceTemperatureController.text) ??
          _onDeviceTemperature,
    );
    if (!mounted) {
      _onDeviceTemperatureController.text = _formatDouble(normalized);
      return;
    }
    setState(() {
      _onDeviceTemperature = normalized;
      _syncOnDeviceControllers();
    });
  }

  void _normalizeContextBudgetReservedOutputField() {
    final normalized = _normalizedContextBudgetTokenOverride(
      int.tryParse(_contextBudgetReservedOutputController.text.trim()),
    );
    if (!mounted) {
      _contextBudgetReservedOutputController.text =
          normalized?.toString() ?? '';
      return;
    }
    setState(() {
      _contextBudgetReservedOutputTokens = normalized;
      _syncContextBudgetControllers();
    });
  }

  void _normalizeContextBudgetSafetyMarginField() {
    final normalized = _normalizedContextBudgetTokenOverride(
      int.tryParse(_contextBudgetSafetyMarginController.text.trim()),
    );
    if (!mounted) {
      _contextBudgetSafetyMarginController.text = normalized?.toString() ?? '';
      return;
    }
    setState(() {
      _contextBudgetSafetyMarginTokens = normalized;
      _syncContextBudgetControllers();
    });
  }

  void _normalizeContextBudgetEffectiveInputPercentField() {
    final normalized = _normalizedContextBudgetEffectiveInputPercent(
      double.tryParse(
        _contextBudgetEffectiveInputPercentController.text.trim(),
      ),
    );
    if (!mounted) {
      _contextBudgetEffectiveInputPercentController.text = normalized == null
          ? ''
          : _formatDouble(normalized);
      return;
    }
    setState(() {
      _contextBudgetEffectiveInputPercent = normalized;
      _syncContextBudgetControllers();
    });
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
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.introTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 8),
                Text(snapshot.introBody, style: _SettingsTextStyles.body),
                const SizedBox(height: 8),
                Text(snapshot.introHelper, style: _SettingsTextStyles.body),
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
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.presetsHelper, style: _SettingsTextStyles.body),
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
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.customOverlayHelper,
                  style: _SettingsTextStyles.body,
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
                  style: _SettingsTextStyles.body,
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
                  style: _SettingsTextStyles.body,
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
                  style: _SettingsTextStyles.cardTitle,
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
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.livePreviewName,
                  style: _SettingsTextStyles.bodyStrong,
                ),
                const SizedBox(height: 8),
                Text(
                  snapshot.livePreviewSummary,
                  style: _SettingsTextStyles.body,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(snapshot.queueTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 10),
                Text(snapshot.queueBody, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          if (snapshot.lastResetMessage.isNotEmpty) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: const Color(0xFFF4F8FF),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    snapshot.lastResetTitle,
                    style: _SettingsTextStyles.cardTitle,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    snapshot.lastResetMessage,
                    style: _SettingsTextStyles.body,
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
              decoration: const BoxDecoration(
                color: Colors.white,
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
                          color: OpenCrayColors.divider,
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    Text(
                      snapshot.appLanguageTitle,
                      style: _SettingsTextStyles.cardTitle,
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
                                  style: _SettingsTextStyles.rowTitle,
                                ),
                              ),
                              if (option.id == snapshot.selectedAppLanguageId)
                                const Icon(
                                  Icons.check_rounded,
                                  color: OpenCrayColors.primary,
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
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(snapshot.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(snapshot.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
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
                            style: _SettingsTextStyles.cardTitle,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            snapshot.masterSummary,
                            style: _SettingsTextStyles.body,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    _PrototypeSwitch(
                      value: snapshot.masterEnabled,
                      onChanged: _isUpdatingMaster ? null : _toggleMaster,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  snapshot.summaryLine,
                  style: _SettingsTextStyles.bodyStrong,
                ),
              ],
            ),
          ),
          if (!snapshot.masterEnabled &&
              (snapshot.masterDisabledTitle.isNotEmpty ||
                  snapshot.masterDisabledBody.isNotEmpty)) ...[
            const SizedBox(height: 16),
            _SettingsCard(
              backgroundColor: const Color(0xFFFFF7E8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (snapshot.masterDisabledTitle.isNotEmpty)
                    Text(
                      snapshot.masterDisabledTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                  if (snapshot.masterDisabledBody.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(
                      snapshot.masterDisabledBody,
                      style: _SettingsTextStyles.body,
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
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(snapshot.serversHelper, style: _SettingsTextStyles.body),
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
          _SettingsCard(
            backgroundColor: OpenCrayColors.dangerSurface,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Unable to load this page',
                  style: _SettingsTextStyles.cardTitle,
                ),
                const SizedBox(height: 8),
                Text(message, style: _SettingsTextStyles.body),
                const SizedBox(height: 12),
                _HeaderActionChip(label: 'Retry', onTap: onRetry),
              ],
            ),
          ),
        ],
      ),
    );
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
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: onTap,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: isSelected ? const Color(0xFFF2F7FF) : const Color(0xFFF7F7FA),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: isSelected ? OpenCrayColors.primary : Colors.transparent,
          ),
        ),
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
                      style: _SettingsTextStyles.rowTitle,
                    ),
                  ),
                  _SettingsStatusPill(
                    label: preset.status,
                    tone: isSelected ? 'active' : 'neutral',
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(preset.summary, style: _SettingsTextStyles.body),
              const SizedBox(height: 8),
              Text(preset.voice, style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Text(preset.status, style: _SettingsTextStyles.body),
            ],
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
      backgroundColor: OpenCrayColors.dangerSurface,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(action.title, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          Text(action.scopeBody, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          Text(action.retainBody, style: _SettingsTextStyles.body),
          const SizedBox(height: 12),
          _PrototypeField(
            label: action.inputHint,
            controller: controller,
            hintText: action.confirmationToken,
            enabled: action.isInputEnabled && !isBusy,
            onChanged: (_) => onChanged(),
          ),
          const SizedBox(height: 10),
          Text(guidance, style: _SettingsTextStyles.body),
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
                backgroundColor: OpenCrayColors.dangerText,
                foregroundColor: Colors.white,
                disabledBackgroundColor: const Color(0xFFE1E2E7),
                disabledForegroundColor: OpenCrayColors.textSecondary,
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
                    Text(server.title, style: _SettingsTextStyles.cardTitle),
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
          Text(server.trustLine, style: _SettingsTextStyles.bodyStrong),
          const SizedBox(height: 6),
          Text(server.authLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.readinessLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.transportLine, style: _SettingsTextStyles.body),
          const SizedBox(height: 6),
          Text(server.exposureLine, style: _SettingsTextStyles.bodyStrong),
          const SizedBox(height: 10),
          Text(server.guidance, style: _SettingsTextStyles.body),
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
        backgroundColor = const Color(0xFFE8F8EE);
        textColor = OpenCrayColors.success;
        break;
      case 'warning':
      case 'attention':
      case 'caution':
        backgroundColor = const Color(0xFFFFF3E4);
        textColor = const Color(0xFF9C5F00);
        break;
      case 'danger':
      case 'blocked':
        backgroundColor = const Color(0xFFFFEAED);
        textColor = OpenCrayColors.dangerText;
        break;
      default:
        backgroundColor = const Color(0xFFF1F2F5);
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
                color: const Color(0xFFF3F4F7),
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
        color: const Color(0xFFECEEF3),
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

class _InlineEditableField extends StatelessWidget {
  const _InlineEditableField({
    required this.title,
    required this.hintText,
    required this.controller,
    required this.focusNode,
    this.keyboardType = TextInputType.text,
    this.onChanged,
  });

  final String title;
  final String hintText;
  final TextEditingController controller;
  final FocusNode focusNode;
  final TextInputType keyboardType;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    final bool usesPrivateIme = keyboardType == TextInputType.visiblePassword;
    return _PrototypeFieldSurface(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(
          children: [
            Text(title, style: _SettingsTextStyles.selectionMeta),
            const SizedBox(width: 12),
            Expanded(
              child: TextField(
                controller: controller,
                focusNode: focusNode,
                onChanged: onChanged,
                textAlign: TextAlign.right,
                autocorrect: false,
                enableSuggestions: !usesPrivateIme,
                enableIMEPersonalizedLearning: !usesPrivateIme,
                spellCheckConfiguration:
                    const SpellCheckConfiguration.disabled(),
                smartDashesType: SmartDashesType.disabled,
                smartQuotesType: SmartQuotesType.disabled,
                keyboardType: keyboardType,
                style: _SettingsTextStyles.fieldValue,
                strutStyle: _SettingsTextStyles.fieldValueStrut,
                decoration: InputDecoration(
                  hintText: hintText,
                  hintStyle: _SettingsTextStyles.fieldValue.copyWith(
                    color: OpenCrayColors.textTertiary,
                    fontWeight: FontWeight.w400,
                  ),
                  isCollapsed: true,
                  contentPadding: const EdgeInsets.symmetric(vertical: 8),
                  border: InputBorder.none,
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
        color: const Color(0xFFF7F7FA),
        borderRadius: BorderRadius.circular(12),
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
          return OpenCrayColors.success;
        }
        return const Color(0xFFD8DAE3);
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

class _PrototypeToggleRow extends StatelessWidget {
  const _PrototypeToggleRow({
    this.rowKey,
    required this.title,
    this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final Key? rowKey;
  final String title;
  final String? subtitle;
  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: rowKey,
      borderRadius: BorderRadius.circular(12),
      onTap: onChanged == null ? null : () => onChanged!(!value),
      child: _PrototypeFieldSurface(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: _SettingsTextStyles.fieldValue),
                    if (subtitle != null && subtitle!.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(subtitle!, style: _SettingsTextStyles.rowSubtitle),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 12),
              _PrototypeSwitch(value: value, onChanged: onChanged),
            ],
          ),
        ),
      ),
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
          color: const Color(0xFFF2F7FF),
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
  });

  final Widget child;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(padding: const EdgeInsets.all(16), child: child),
    );
  }
}

class _SegmentedSelector extends StatelessWidget {
  const _SegmentedSelector({required this.labels, required this.selectedIndex});

  final List<String> labels;
  final int selectedIndex;

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
            for (int index = 0; index < labels.length; index++)
              Expanded(
                child: AnimatedContainer(
                  duration: OpenCrayMotion.resolve(
                    context,
                    OpenCrayMotion.micro,
                  ),
                  curve: OpenCrayMotion.enter,
                  decoration: BoxDecoration(
                    color: index == selectedIndex
                        ? Colors.white
                        : Colors.transparent,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    labels[index],
                    textAlign: TextAlign.center,
                    style: _SettingsTextStyles.valueChip.copyWith(
                      color: index == selectedIndex
                          ? OpenCrayColors.textPrimary
                          : OpenCrayColors.textSecondary,
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

class _CompactInlineValueField extends StatelessWidget {
  const _CompactInlineValueField({
    this.fieldKey,
    required this.controller,
    this.focusNode,
    required this.width,
    required this.keyboardType,
    this.onChanged,
  });

  final Key? fieldKey;
  final TextEditingController controller;
  final FocusNode? focusNode;
  final double width;
  final TextInputType keyboardType;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      child: _PrototypeFieldSurface(
        child: TextField(
          key: fieldKey,
          controller: controller,
          focusNode: focusNode,
          onChanged: onChanged,
          keyboardType: keyboardType,
          textInputAction: TextInputAction.done,
          textAlign: TextAlign.center,
          autocorrect: false,
          enableSuggestions: true,
          enableIMEPersonalizedLearning: true,
          spellCheckConfiguration: const SpellCheckConfiguration.disabled(),
          smartDashesType: SmartDashesType.disabled,
          smartQuotesType: SmartQuotesType.disabled,
          style: _SettingsTextStyles.fieldValue,
          strutStyle: _SettingsTextStyles.fieldValueStrut,
          decoration: const InputDecoration(
            isCollapsed: true,
            contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            border: InputBorder.none,
          ),
        ),
      ),
    );
  }
}

class _BudgetOverrideRow extends StatelessWidget {
  const _BudgetOverrideRow({
    required this.label,
    this.fieldKey,
    required this.controller,
    this.focusNode,
    required this.suffix,
    this.decimal = false,
    this.onChanged,
    required this.onClear,
  });

  final String label;
  final Key? fieldKey;
  final TextEditingController controller;
  final FocusNode? focusNode;
  final String suffix;
  final bool decimal;
  final ValueChanged<String>? onChanged;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Text(label, style: _SettingsTextStyles.fieldValue)),
        const SizedBox(width: 12),
        _CompactInlineValueField(
          fieldKey: fieldKey,
          controller: controller,
          focusNode: focusNode,
          width: decimal ? 82 : 92,
          keyboardType: decimal
              ? const TextInputType.numberWithOptions(decimal: true)
              : TextInputType.number,
          onChanged: onChanged,
        ),
        const SizedBox(width: 8),
        SizedBox(
          width: 62,
          child: Text(
            suffix,
            overflow: TextOverflow.ellipsis,
            style: _SettingsTextStyles.body,
          ),
        ),
        InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onClear,
          child: const Padding(
            padding: EdgeInsets.all(6),
            child: Icon(
              Icons.close_rounded,
              size: 16,
              color: OpenCrayColors.textSecondary,
            ),
          ),
        ),
      ],
    );
  }
}

class _SliderValueRow extends StatelessWidget {
  const _SliderValueRow({
    required this.label,
    required this.minLabel,
    required this.controller,
    this.focusNode,
    required this.value,
    required this.min,
    required this.max,
    required this.onSliderChanged,
    this.onSliderChangeEnd,
    this.onFieldChanged,
    this.divisions,
    this.allowDecimal = false,
  });

  final String label;
  final String minLabel;
  final TextEditingController controller;
  final FocusNode? focusNode;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double> onSliderChanged;
  final ValueChanged<double>? onSliderChangeEnd;
  final ValueChanged<String>? onFieldChanged;
  final int? divisions;
  final bool allowDecimal;

  @override
  Widget build(BuildContext context) {
    final resolvedValue = value.clamp(min, max).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(child: Text(label, style: _SettingsTextStyles.fieldValue)),
            const SizedBox(width: 12),
            _CompactInlineValueField(
              controller: controller,
              focusNode: focusNode,
              width: 84,
              keyboardType: allowDecimal
                  ? const TextInputType.numberWithOptions(decimal: true)
                  : TextInputType.number,
              onChanged: onFieldChanged,
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            SizedBox(
              width: 32,
              child: Text(minLabel, style: _SettingsTextStyles.selectionMeta),
            ),
            Expanded(
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 3,
                  thumbShape: const RoundSliderThumbShape(
                    enabledThumbRadius: 8,
                  ),
                  overlayShape: const RoundSliderOverlayShape(
                    overlayRadius: 14,
                  ),
                ),
                child: Slider(
                  value: resolvedValue,
                  min: min,
                  max: max,
                  divisions: divisions,
                  onChanged: onSliderChanged,
                  onChangeEnd: onSliderChangeEnd,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _OnDeviceModelTile extends StatelessWidget {
  const _OnDeviceModelTile({
    required this.title,
    required this.subtitle,
    required this.actionLabel,
    required this.selected,
    required this.actionEnabled,
    this.onActionTap,
    this.secondaryActionLabel,
    this.onSecondaryActionTap,
    this.progressValue,
  });

  final String title;
  final String subtitle;
  final String? actionLabel;
  final bool selected;
  final bool actionEnabled;
  final VoidCallback? onActionTap;
  final String? secondaryActionLabel;
  final VoidCallback? onSecondaryActionTap;
  final double? progressValue;

  @override
  Widget build(BuildContext context) {
    final chipBackground = selected
        ? const Color(0xFFE8F1FF)
        : const Color(0xFFF1F2F6);
    final chipColor = selected
        ? OpenCrayColors.primary
        : (actionEnabled
              ? OpenCrayColors.textPrimary
              : OpenCrayColors.textTertiary);
    return _PrototypeFieldSurface(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
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
                      Text(title, style: _SettingsTextStyles.fieldValue),
                      const SizedBox(height: 4),
                      Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
                    ],
                  ),
                ),
                if (actionLabel != null) ...[
                  const SizedBox(width: 12),
                  InkWell(
                    borderRadius: BorderRadius.circular(999),
                    onTap: actionEnabled ? onActionTap : null,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: chipBackground,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 6,
                        ),
                        child: Text(
                          actionLabel!,
                          style: _SettingsTextStyles.valueChip.copyWith(
                            color: chipColor,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ],
            ),
            if (progressValue != null || secondaryActionLabel != null) ...[
              const SizedBox(height: 10),
              Row(
                children: [
                  if (progressValue != null)
                    Expanded(
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(999),
                        child: LinearProgressIndicator(
                          minHeight: 5,
                          value: progressValue,
                          backgroundColor: const Color(0xFFE2E6EE),
                          valueColor: const AlwaysStoppedAnimation<Color>(
                            OpenCrayColors.primary,
                          ),
                        ),
                      ),
                    ),
                  if (progressValue != null && secondaryActionLabel != null)
                    const SizedBox(width: 12),
                  if (secondaryActionLabel != null)
                    InkWell(
                      borderRadius: BorderRadius.circular(8),
                      onTap: onSecondaryActionTap,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 4,
                          vertical: 2,
                        ),
                        child: Text(
                          secondaryActionLabel!,
                          style: _SettingsTextStyles.inlineAction,
                        ),
                      ),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SegmentedSettingRow extends StatelessWidget {
  const _SegmentedSettingRow({
    required this.label,
    required this.width,
    required this.selectedId,
    required this.options,
    required this.labelBuilder,
    required this.onSelected,
  });

  final String label;
  final double width;
  final String selectedId;
  final List<String> options;
  final String Function(String value) labelBuilder;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Text(label, style: _SettingsTextStyles.fieldValue)),
        const SizedBox(width: 12),
        SizedBox(
          width: width,
          child: _InteractiveSegmentedSelector(
            labels: options,
            selectedId: selectedId,
            labelBuilder: labelBuilder,
            onSelected: onSelected,
          ),
        ),
      ],
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
                Text(row.title, style: _SettingsTextStyles.rowTitle),
                if (row.subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(row.subtitle!, style: _SettingsTextStyles.rowSubtitle),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          if (row.trailingKind == SettingsRowTrailingKind.chevron)
            const Icon(
              Icons.chevron_right_rounded,
              size: 18,
              color: OpenCrayColors.textTertiary,
            )
          else if (row.trailingKind == SettingsRowTrailingKind.toggle)
            _PrototypeSwitch(value: row.toggleValue ?? false, onChanged: (_) {})
          else
            DecoratedBox(
              decoration: BoxDecoration(
                color: const Color(0xFFF3F4F7),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 5,
                ),
                child: Text(
                  row.valueLabel ?? '',
                  style: _SettingsTextStyles.valueChip,
                ),
              ),
            ),
        ],
      ),
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
    fontSize: 12,
    height: 1.1,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textSecondary,
  );

  static const TextStyle pageTitle = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w600,
    color: OpenCrayColors.textPrimary,
  );

  static const TextStyle pageTitleSubpage = TextStyle(
    fontSize: 28,
    height: 1.15,
    fontWeight: FontWeight.w600,
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
    color: Color(0xFFC7C7CC),
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
