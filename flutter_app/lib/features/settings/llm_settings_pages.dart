part of 'settings_feature.dart';

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
                  if (subAgents[index].hasActiveExecution)
                    const _DebugValueChip(
                      label: 'Execution',
                      value: 'active',
                    ),
                  if (subAgents[index].hasPendingApprovalResume)
                    _DebugValueChip(
                      label: 'Approval',
                      value: _formatSubAgentApprovalStatus(subAgents[index]),
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
              if (subAgents[index].pendingApprovalToolName?.trim().isNotEmpty ==
                  true)
                _DebugKeyValueLine(
                  'Pending approval tool',
                  subAgents[index].pendingApprovalIsHighRisk
                      ? '${subAgents[index].pendingApprovalToolName!.trim()} (high risk)'
                      : subAgents[index].pendingApprovalToolName!.trim(),
                ),
              if (_pendingApprovalChildLabel(subAgents[index]) != null)
                _DebugKeyValueLine(
                  'Pending approval child',
                  _pendingApprovalChildLabel(subAgents[index])!,
                ),
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

  String _formatSubAgentApprovalStatus(OpenCrayChatSubAgentSnapshot subAgent) {
    if (!subAgent.hasPendingApprovalResume) {
      return 'none';
    }
    return subAgent.pendingApprovalIsHighRisk ? 'high risk' : 'pending';
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

  String? _pendingApprovalChildLabel(OpenCrayChatSubAgentSnapshot subAgent) {
    final List<String> parts = <String>[
      if (subAgent.pendingApprovalChildRunId?.trim().isNotEmpty == true)
        'run ${subAgent.pendingApprovalChildRunId!.trim()}',
      if (subAgent.pendingApprovalChildTaskId?.trim().isNotEmpty == true)
        'task ${subAgent.pendingApprovalChildTaskId!.trim()}',
    ];
    return parts.isEmpty ? null : parts.join(' / ');
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
        color: OpenCrayColors.surfaceMuted,
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
  static const String _contextWindowPresetAuto = 'auto';
  static const String _contextWindowPresetDev = 'dev';
  static const Map<String, int> _contextWindowPresetValues = <String, int>{
    '128k': 128000,
    '200k': 200000,
    '256k': 262144,
    '400k': 400000,
    '1m': 1048576,
  };
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
  int? _manualContextWindowTokens;
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
    final resolvedContextWindowTokens = _snapshot?.resolvedContextWindowTokens;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  copy.llmContextBudgetTitle,
                  style: _SettingsTextStyles.cardTitle,
                ),
              ),
              InkWell(
                key: const ValueKey<String>(
                  'settings-llm-context-budget-raw-action',
                ),
                borderRadius: BorderRadius.circular(8),
                onTap: _openContextWindowRawSheet,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 4,
                    vertical: 2,
                  ),
                  child: Text(
                    copy.llmContextBudgetEditRawAction,
                    style: _SettingsTextStyles.inlineAction,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(copy.llmContextBudgetHelper, style: _SettingsTextStyles.body),
          const SizedBox(height: 12),
          KeyedSubtree(
            key: const ValueKey<String>(
              'settings-llm-context-budget-preset',
            ),
            child: _PrototypeSelectionField(
              label: copy.llmContextBudgetPresetLabel,
              title: _contextWindowPresetTitle(_contextWindowPresetId()),
              trailingLabel: resolvedContextWindowTokens == null
                  ? null
                  : _formatTokenCount(resolvedContextWindowTokens),
              onTap: _openContextWindowPresetSheet,
            ),
          ),
          if (resolvedContextWindowTokens != null) ...[
            const SizedBox(height: 8),
            Text(
              copy.llmContextBudgetResolved(
                _formatTokenCount(resolvedContextWindowTokens),
              ),
              style: _SettingsTextStyles.body,
            ),
          ],
          if (_manualContextWindowTokens != null) ...[
            const SizedBox(height: 4),
            Text(
              copy.llmContextBudgetOverride(
                _formatTokenCount(_manualContextWindowTokens!),
              ),
              style: _SettingsTextStyles.body,
            ),
          ],
          const SizedBox(height: 12),
          const Divider(height: 1, color: OpenCrayColors.divider),
          const SizedBox(height: 12),
          _SegmentedSettingRow(
            label: 'Envelope preset',
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
        contextWindowTokensOverride: _manualContextWindowTokens,
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
        contextWindowTokensOverride: _manualContextWindowTokens,
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

  Future<void> _openContextWindowPresetSheet() async {
    final copy = _copyForSnapshot();
    final selected = await _openStringSelectionSheet(
      title: copy.llmContextBudgetPresetLabel,
      options: <String>[
        _contextWindowPresetAuto,
        ..._contextWindowPresetValues.keys,
      ],
      selectedValue: _contextWindowPresetId(),
      labelBuilder: _contextWindowPresetTitle,
    );
    if (selected == null || !mounted) {
      return;
    }
    setState(() {
      _manualContextWindowTokens = switch (selected) {
        _contextWindowPresetAuto => null,
        _ => _contextWindowPresetValues[selected],
      };
    });
    unawaited(_saveDraft());
  }

  Future<void> _openContextWindowRawSheet() async {
    final copy = _copyForSnapshot();
    var draftValue = _manualContextWindowTokens?.toString() ?? '';
    final rawResult = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        return SafeArea(
          top: false,
          child: SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(
              12,
              0,
              12,
              MediaQuery.of(context).viewInsets.bottom + 12,
            ),
            child: DecoratedBox(
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.all(Radius.circular(22)),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 16),
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
                      copy.llmContextBudgetRawTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      copy.llmContextBudgetRawHelper,
                      style: _SettingsTextStyles.body,
                    ),
                    const SizedBox(height: 12),
                    _PrototypeFieldSurface(
                      child: TextFormField(
                        initialValue: draftValue,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.done,
                        onChanged: (value) => draftValue = value,
                        onFieldSubmitted: (value) {
                          FocusScope.of(context).unfocus();
                          Navigator.of(context).pop(value.trim());
                        },
                        decoration: InputDecoration(
                          hintText: copy.llmContextBudgetRawHint,
                          border: InputBorder.none,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 14,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        TextButton(
                          onPressed: () {
                            FocusScope.of(context).unfocus();
                            Navigator.of(context).pop('');
                          },
                          child: Text(copy.llmContextBudgetResetAuto),
                        ),
                        const Spacer(),
                        FilledButton(
                          onPressed: () {
                            FocusScope.of(context).unfocus();
                            Navigator.of(context).pop(draftValue.trim());
                          },
                          child: Text(copy.llmContextBudgetRawApply),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (rawResult == null || !mounted) {
      return;
    }
    final trimmed = rawResult.trim();
    if (trimmed.isEmpty) {
      setState(() {
        _manualContextWindowTokens = null;
      });
      unawaited(_saveDraft());
      return;
    }
    final parsed = int.tryParse(trimmed);
    if (parsed == null || parsed <= 0) {
      _showMessage(copy.llmContextBudgetInvalid);
      return;
    }
    setState(() {
      _manualContextWindowTokens = parsed;
    });
    unawaited(_saveDraft());
  }

  String _contextWindowPresetId() {
    final manualOverride = _manualContextWindowTokens;
    if (manualOverride == null) {
      return _contextWindowPresetAuto;
    }
    return _contextWindowPresetValues.entries
        .firstWhere(
          (entry) => entry.value == manualOverride,
          orElse: () => const MapEntry(_contextWindowPresetDev, -1),
        )
        .key;
  }

  String _contextWindowPresetTitle(String presetId) {
    final copy = _copyForSnapshot();
    return switch (presetId) {
      _contextWindowPresetAuto => copy.llmContextBudgetPresetAuto,
      _contextWindowPresetDev => copy.llmContextBudgetPresetDev,
      '128k' => '128K',
      '200k' => '200K',
      '256k' => '256K',
      '400k' => '400K',
      '1m' => '1M',
      _ => presetId,
    };
  }

  String _formatTokenCount(int value) {
    if (value >= 1000000) {
      return '${(value / 1048576).toStringAsFixed(1)}M';
    }
    if (value >= 1000) {
      return '${(value / 1000).toStringAsFixed(value % 1000 == 0 ? 0 : 1)}K';
    }
    return '$value';
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
    _manualContextWindowTokens = snapshot.manualContextWindowTokens;
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
        _manualContextWindowTokens != snapshot.manualContextWindowTokens ||
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
        contextWindowTokensOverride: _manualContextWindowTokens,
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
        ? OpenCrayColors.primaryTint
        : OpenCrayColors.surfaceMuted;
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
                          backgroundColor: OpenCrayColors.divider,
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
