import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/models/opencray_agent_snapshot.dart';
import '../../core/models/opencray_chat_snapshot.dart';
import '../../core/models/opencray_debug_snapshot.dart';
import '../../core/models/opencray_image_reference.dart';
import '../../core/models/opencray_shell_snapshot.dart';
import '../../core/copy/opencray_ui_copy.dart';
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

class _SettingsFeatureScreenState extends State<SettingsFeatureScreen> {
  late SettingsPage _page = widget.initialPage;
  final Map<SettingsPage, SettingsDetailSnapshot> _detailCache =
      <SettingsPage, SettingsDetailSnapshot>{};
  SettingsOverviewSnapshot? _overview;
  StreamSubscription<SettingsOverviewSnapshot>? _overviewSubscription;

  @override
  void initState() {
    super.initState();
    _loadOverview();
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
  }

  @override
  void dispose() {
    _overviewSubscription?.cancel();
    super.dispose();
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
    final content = DefaultTextStyle(
      style: defaultTextStyle,
      child: SafeArea(
        bottom: false,
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: _buildCurrentPage(context),
        ),
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
        setState(() {
          _page = nestedBackTarget;
        });
        return;
      }
      if (widget.standalone) {
        Navigator.of(context).pop();
        return;
      }
      setState(() => _page = SettingsPage.home);
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
        final detailSnapshot = _detailCache[_page];
        if (detailSnapshot == null) {
          return const _SettingsLoading(
            key: ValueKey<String>('settings-detail-loading'),
          );
        }
        return _AboutVersionPage(
          key: const ValueKey<String>('settings-about-version'),
          snapshot: detailSnapshot,
          onBack: onBack,
          backLabel: backLabel,
          facade: widget.facade,
          debugBridge: widget.debugBridge,
        );
    }
  }

  void _openPage(SettingsPage page) {
    if (!widget.standalone && page != SettingsPage.home) {
      Navigator.of(context).push(
        MaterialPageRoute<void>(
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
          ...snapshot.entries.map(
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

class _AboutVersionPage extends StatelessWidget {
  const _AboutVersionPage({
    super.key,
    required this.snapshot,
    required this.onBack,
    required this.backLabel,
    required this.facade,
    required this.debugBridge,
  });

  final SettingsDetailSnapshot snapshot;
  final VoidCallback onBack;
  final String backLabel;
  final SettingsFacade facade;
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
          if (debugBridge != null) ...[
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
                          MaterialPageRoute<void>(
                            builder: (context) => _DebugToolsPage(
                              bridge: debugBridge!,
                              facade: facade,
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

  final TextEditingController _providerNameController = TextEditingController();
  final TextEditingController _providerNotesController =
      TextEditingController();
  final TextEditingController _baseUrlController = TextEditingController();
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _modelController = TextEditingController();
  final TextEditingController _systemPromptController = TextEditingController();
  final FocusNode _providerNameFocusNode = FocusNode();
  final FocusNode _providerNotesFocusNode = FocusNode();
  final FocusNode _baseUrlFocusNode = FocusNode();
  final FocusNode _apiKeyFocusNode = FocusNode();
  final FocusNode _modelFocusNode = FocusNode();
  final FocusNode _systemPromptFocusNode = FocusNode();

  LlmConfigSnapshot? _snapshot;
  String _selectedProviderOptionId = 'custom';
  String _providerId = 'custom';
  String _protocol = 'openai';
  String _reasoningEffort = 'medium';
  bool _streamingEnabled = true;
  String _openAiPromptCacheKeyStrategy = 'none';
  String _openAiPromptCacheRetention = '';
  bool _anthropicPromptCachingEnabled = false;
  String _anthropicPromptCacheTtl = '5m';
  bool _isApplyingSnapshot = false;
  bool _isSavingDraft = false;
  bool _isSavingCustomProvider = false;
  bool _hasQueuedSave = false;
  bool _isValidating = false;
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
    _providerNameController.addListener(_handleCustomProviderDraftChanged);
    _providerNotesController.addListener(_handleCustomProviderDraftChanged);
    _baseUrlController.addListener(_handleCustomProviderDraftChanged);
    _apiKeyController.addListener(_handleCustomProviderDraftChanged);
    _modelController.addListener(_handleCustomProviderDraftChanged);
    _load();
  }

  @override
  void dispose() {
    _providerNameController.dispose();
    _providerNotesController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    _modelController.dispose();
    _systemPromptController.dispose();
    _providerNameFocusNode.dispose();
    _providerNotesFocusNode.dispose();
    _baseUrlFocusNode.dispose();
    _apiKeyFocusNode.dispose();
    _modelFocusNode.dispose();
    _systemPromptFocusNode.dispose();
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
    final showsReasoning =
        selectedProtocol == 'anthropic' ||
        _modelController.text.toLowerCase().contains('gpt');
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
          _SettingsCard(
            child: _PrototypeToggleRow(
              rowKey: const ValueKey<String>('settings-llm-streaming-toggle'),
              title: copy.llmStreamingTitle,
              subtitle: copy.llmStreamingSubtitle,
              value: _streamingEnabled,
              onChanged: _handleStreamingEnabledChanged,
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
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
                        key: const ValueKey<String>(
                          'settings-llm-save-provider',
                        ),
                        borderRadius: BorderRadius.circular(8),
                        onTap: _isSavingCustomProvider
                            ? null
                            : _saveCustomProvider,
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
                  Text(
                    copy.llmProtocolTitle,
                    style: _SettingsTextStyles.fieldLabel,
                  ),
                  const SizedBox(height: 8),
                  _PrototypeSelectionRow(
                    title: _protocolTitle(selectedProtocol),
                    trailingLabel: copy.llmOptionsCount(
                      _protocolOptions.length,
                    ),
                    compact: true,
                    onTap: _openProtocolSheet,
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: copy.llmProviderNameLabel,
                    controller: _providerNameController,
                    focusNode: _providerNameFocusNode,
                    hintText: copy.llmProviderNameHint,
                    keyboardType: TextInputType.visiblePassword,
                  ),
                  const SizedBox(height: 12),
                  _PrototypeField(
                    label: copy.llmNotesLabel,
                    controller: _providerNotesController,
                    focusNode: _providerNotesFocusNode,
                    hintText: copy.llmNotesHint,
                    keyboardType: TextInputType.visiblePassword,
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
                Text(
                  copy.llmPromptCacheTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
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
                ] else if (_showsAnthropicPromptCacheControls(
                  selectedProtocol,
                )) ...[
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
                      title: _anthropicPromptCacheTtlTitle(
                        _anthropicPromptCacheTtl,
                      ),
                      onTap: _openAnthropicPromptCacheTtlSheet,
                    ),
                  ],
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
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
                          : (_isSavingDraft
                                ? copy.llmSaving
                                : copy.llmValidateModel),
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
                  trailingText: _apiKeyController.text.trim().isEmpty
                      ? null
                      : copy.llmStoredLocally,
                  onChanged: (_) => setState(() {}),
                ),
                const SizedBox(height: 12),
                _PrototypeField(
                  label: copy.llmModelNameLabel,
                  controller: _modelController,
                  focusNode: _modelFocusNode,
                  hintText: copy.llmModelHint,
                  keyboardType: TextInputType.visiblePassword,
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
          ),
          const SizedBox(height: 16),
          _SettingsCard(
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
                Text(snapshot.helperText, style: _SettingsTextStyles.body),
              ],
            ),
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
  }

  void _applyProvider(LlmProviderOption option) {
    _isApplyingSnapshot = true;
    setState(() {
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
    if (_isValidating) {
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

  void _registerAutosaveFocusNode(FocusNode focusNode) {
    focusNode.addListener(() {
      if (!focusNode.hasFocus && !_isSavingCustomProvider) {
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
    _isApplyingSnapshot = false;
  }

  bool _hasDraftChanges() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return false;
    }
    return _providerId != snapshot.providerId ||
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
        _anthropicPromptCacheTtl != snapshot.anthropicPromptCacheTtl;
  }

  bool _draftIsConfigured() =>
      _baseUrlController.text.trim().isNotEmpty &&
      _apiKeyController.text.trim().isNotEmpty;

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
                    duration: const Duration(milliseconds: 160),
                    curve: Curves.easeOutCubic,
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
    this.onChanged,
  });

  final String title;
  final String hintText;
  final TextEditingController controller;
  final FocusNode focusNode;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
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
                enableSuggestions: false,
                enableIMEPersonalizedLearning: false,
                spellCheckConfiguration:
                    const SpellCheckConfiguration.disabled(),
                smartDashesType: SmartDashesType.disabled,
                smartQuotesType: SmartQuotesType.disabled,
                keyboardType: TextInputType.visiblePassword,
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
    this.trailingText,
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
  final String? trailingText;
  final ValueChanged<String>? onChanged;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) {
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
                    enableSuggestions: false,
                    enableIMEPersonalizedLearning: false,
                    spellCheckConfiguration:
                        const SpellCheckConfiguration.disabled(),
                    smartDashesType: SmartDashesType.disabled,
                    smartQuotesType: SmartQuotesType.disabled,
                    keyboardType:
                        keyboardType ??
                        (maxLines == 1
                            ? TextInputType.visiblePassword
                            : TextInputType.multiline),
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
                if (trailingText != null) ...[
                  const SizedBox(width: 8),
                  Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: Text(
                      trailingText!,
                      style: _SettingsTextStyles.selectionMeta,
                    ),
                  ),
                ],
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
                  duration: const Duration(milliseconds: 160),
                  curve: Curves.easeOutCubic,
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
