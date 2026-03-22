part of 'settings_feature.dart';

class _DebugToolsPage extends StatelessWidget {
  const _DebugToolsPage({
    required this.bridge,
    required this.facade,
    required this.backLabel,
  });

  final OpenCrayHostBridge bridge;
  final SettingsFacade facade;
  final String backLabel;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          key: const ValueKey<String>('settings-debug-tools-page'),
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _BackLink(
                onTap: () => Navigator.of(context).pop(),
                label: backLabel,
              ),
              const SizedBox(height: 8),
              const Text(
                'Debug tools',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Inspect runtime trace, memory, and soul state.',
                style: _SettingsTextStyles.subtitle,
              ),
              const SizedBox(height: 16),
              const _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'What you can inspect',
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    SizedBox(height: 8),
                    Text(
                      'Trace runs, memory, and soul state.',
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
                    const Text(
                      'Debug pages',
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 8),
                    _HomeEntryRow(
                      title: 'Context & Memory Trace',
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => _ContextMemoryTracePage(
                              bridge: bridge,
                              facade: facade,
                              backLabel: 'Debug tools',
                            ),
                          ),
                        );
                      },
                    ),
                    const Divider(height: 1, color: OpenCrayColors.divider),
                    _HomeEntryRow(
                      title: 'Memory Inspector',
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => _MemoryInspectorPage(
                              bridge: bridge,
                              backLabel: 'Debug tools',
                            ),
                          ),
                        );
                      },
                    ),
                    const Divider(height: 1, color: OpenCrayColors.divider),
                    _HomeEntryRow(
                      title: 'Soul Inspector',
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => _SoulInspectorPage(
                              bridge: bridge,
                              backLabel: 'Debug tools',
                            ),
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ContextMemoryTracePage extends StatefulWidget {
  const _ContextMemoryTracePage({
    required this.bridge,
    required this.facade,
    required this.backLabel,
  });

  final OpenCrayHostBridge bridge;
  final SettingsFacade facade;
  final String backLabel;

  @override
  State<_ContextMemoryTracePage> createState() =>
      _ContextMemoryTracePageState();
}

class _ContextMemoryTracePageState extends State<_ContextMemoryTracePage> {
  bool _isLoading = true;
  bool _isRefreshing = false;
  String? _loadError;
  OpenCrayChatRuntimeSnapshot? _runtimeSnapshot;
  PersonalizationConfigSnapshot? _personalizationSnapshot;
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
          key: const ValueKey<String>('settings-context-memory-trace-page'),
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _BackLink(
                onTap: () => Navigator.of(context).pop(),
                label: widget.backLabel,
              ),
              const SizedBox(height: 8),
              const Text(
                'Context & Memory Trace',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Inspect one run end to end.',
                style: _SettingsTextStyles.subtitle,
              ),
              const SizedBox(height: 16),
              _SettingsCard(
                child: Row(
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Run source',
                            style: _SettingsTextStyles.cardTitle,
                          ),
                          SizedBox(height: 8),
                          Text(
                            'Uses existing host run snapshots. No separate debug protocol is required.',
                            style: _SettingsTextStyles.body,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    _HeaderActionChip(
                      label: _isRefreshing ? 'Refreshing' : 'Refresh',
                      onTap: _isRefreshing ? null : _refresh,
                    ),
                  ],
                ),
              ),
              if (_loadError != null) ...[
                const SizedBox(height: 16),
                _SettingsCard(
                  backgroundColor: const Color(0xFFFFF3F0),
                  child: Text(
                    _loadError!,
                    style: _SettingsTextStyles.bodyStrong,
                  ),
                ),
              ],
              const SizedBox(height: 16),
              if (_isLoading)
                const _SettingsLoading(
                  key: ValueKey<String>('context-memory-trace-loading'),
                )
              else ...[
                _buildRunSelectorCard(),
                if (_selectedRunSnapshot != null) ...[
                  const SizedBox(height: 16),
                  _buildRunOverviewCard(_selectedRunSnapshot!),
                  const SizedBox(height: 16),
                  _buildContextSetupCard(_selectedRunSnapshot!),
                  const SizedBox(height: 16),
                  _buildMemoryWritesCard(_selectedRunSnapshot!),
                  const SizedBox(height: 16),
                  _buildMemoryRecallCard(_selectedRunSnapshot!),
                  const SizedBox(height: 16),
                  _buildSkillContextCard(_selectedRunSnapshot!),
                  const SizedBox(height: 16),
                  _buildSoulResolutionCard(),
                  const SizedBox(height: 16),
                  _buildRawTraceCard(_selectedRunSnapshot!),
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
      return const _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
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
          const Text(
            'Select a run before inspecting write, recall, and soul sections.',
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

  Widget _buildRunOverviewCard(OpenCrayChatRunSnapshot run) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Run overview', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _DebugValueChip(label: 'Run', value: run.runId),
              _DebugValueChip(label: 'Session', value: run.sessionId),
              _DebugValueChip(label: 'Task', value: run.taskId),
              _DebugValueChip(
                label: 'Status',
                value: run.executionStatus ?? 'unknown',
              ),
              _DebugValueChip(
                label: 'Format',
                value: run.responseFormat ?? 'n/a',
              ),
              _DebugValueChip(
                label: 'Duration',
                value: _formatDebugDuration(
                  run.updatedAtEpochMs - run.acceptedAtEpochMs,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _DebugKeyValueLine(
            'Updated',
            _formatDebugClockTime(run.updatedAtEpochMs),
          ),
          _DebugKeyValueLine('Task state', run.taskState ?? 'unknown'),
          _DebugKeyValueLine('Attempt', '${run.attempt}'),
          if (run.errorCode?.isNotEmpty == true)
            _DebugKeyValueLine(
              'Error',
              '${run.errorCode} ${run.errorMessage ?? ''}'.trim(),
            ),
        ],
      ),
    );
  }

  Widget _buildMemoryWritesCard(OpenCrayChatRunSnapshot run) {
    final memoryWriteEvent = _latestEventOfKind(run.runId, 'memory_write');
    final memoryFlushEvent = _latestEventOfKind(run.runId, 'memory_flush');
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Memory writes', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (memoryWriteEvent == null && memoryFlushEvent == null)
            const Text(
              'No memory write or flush event was captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _DebugValueChip(
                  label: 'Written',
                  value: '${memoryWriteEvent?.writtenRecordIds.length ?? 0}',
                ),
                _DebugValueChip(
                  label: 'Resolved',
                  value: '${memoryWriteEvent?.resolvedRecordIds.length ?? 0}',
                ),
                _DebugValueChip(
                  label: 'Suppressed',
                  value: '${memoryWriteEvent?.suppressedRecordIds.length ?? 0}',
                ),
                _DebugValueChip(
                  label: 'Reaffirmed',
                  value: '${memoryWriteEvent?.reaffirmedRecordIds.length ?? 0}',
                ),
                _DebugValueChip(
                  label: 'Expired',
                  value: '${memoryWriteEvent?.expiredRecordIds.length ?? 0}',
                ),
                _DebugValueChip(
                  label: 'Flush wrote',
                  value: '${memoryFlushEvent?.writtenRecordIds.length ?? 0}',
                ),
              ],
            ),
            if (memoryWriteEvent?.writtenRecordIds.isNotEmpty == true) ...[
              const SizedBox(height: 12),
              _DebugKeyValueLine(
                'Written ids',
                memoryWriteEvent!.writtenRecordIds.join(', '),
              ),
            ],
            if (memoryWriteEvent?.writtenKinds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Written kinds',
                memoryWriteEvent!.writtenKinds.join(', '),
              ),
            if (memoryWriteEvent?.resolvedRecordIds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Resolved ids',
                memoryWriteEvent!.resolvedRecordIds.join(', '),
              ),
            if (memoryWriteEvent?.suppressedRecordIds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Suppressed ids',
                memoryWriteEvent!.suppressedRecordIds.join(', '),
              ),
            if (memoryWriteEvent?.reaffirmedRecordIds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Reaffirmed ids',
                memoryWriteEvent!.reaffirmedRecordIds.join(', '),
              ),
            if (memoryWriteEvent?.expiredRecordIds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Expired ids',
                memoryWriteEvent!.expiredRecordIds.join(', '),
              ),
            if (memoryFlushEvent?.writtenRecordIds.isNotEmpty == true)
              _DebugKeyValueLine(
                'Flush ids',
                memoryFlushEvent!.writtenRecordIds.join(', '),
              ),
          ],
        ],
      ),
    );
  }

  Widget _buildMemoryRecallCard(OpenCrayChatRunSnapshot run) {
    final trace = run.memoryTrace;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Memory recall', style: _SettingsTextStyles.cardTitle),
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
              _DebugKeyValueLine('Query terms', trace.queryTerms.join(', ')),
            ],
            if (trace.selected.isNotEmpty) buildSelectedRecordsSection(trace),
            if (trace.omitted.isNotEmpty) buildOmittedRecordsSection(trace),
            if (trace.filteredCounts.isNotEmpty) ...[
              const SizedBox(height: 4),
              const Text(
                'Filtered counts',
                style: _SettingsTextStyles.cardTitle,
              ),
              const SizedBox(height: 8),
              ...trace.filteredCounts.entries.map(
                (entry) => _DebugKeyValueLine(entry.key, '${entry.value}'),
              ),
            ],
          ],
        ],
      ),
    );
  }

  Future<void> _refresh() async {
    final shouldShowLoading = _runtimeSnapshot == null && !_isRefreshing;
    setState(() {
      _loadError = null;
      _isLoading = shouldShowLoading;
      _isRefreshing = true;
    });
    try {
      final runtimeSnapshot = await widget.bridge.loadChatRuntimeSnapshot();
      final personalizationSnapshot = await widget.facade
          .loadPersonalizationConfig();
      final recentRunIds = _collectRecentDebugRunIds(runtimeSnapshot);
      final nextSelectedRunId = recentRunIds.contains(_selectedRunId)
          ? _selectedRunId
          : (recentRunIds.isEmpty ? null : recentRunIds.first);
      final nextRunSnapshot = nextSelectedRunId == null
          ? null
          : await widget.bridge.loadChatRunSnapshot(nextSelectedRunId);
      if (!mounted) {
        return;
      }
      setState(() {
        _runtimeSnapshot = runtimeSnapshot;
        _personalizationSnapshot = personalizationSnapshot;
        _recentRunIds = recentRunIds;
        _selectedRunId = nextSelectedRunId;
        _selectedRunSnapshot = nextRunSnapshot;
        _isLoading = false;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load debug data: $error';
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
}

extension on _ContextMemoryTracePageState {
  Widget buildSelectedRecordsSection(OpenCrayChatRunMemoryTraceSnapshot trace) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 4),
        const Text('Selected records', style: _SettingsTextStyles.cardTitle),
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
    );
  }

  Widget buildOmittedRecordsSection(OpenCrayChatRunMemoryTraceSnapshot trace) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 4),
        const Text('Omitted records', style: _SettingsTextStyles.cardTitle),
        const SizedBox(height: 8),
        for (final omitted in trace.omitted) ...[
          _DebugKeyValueLine('Record', omitted.id),
          _DebugKeyValueLine('Reason', omitted.reason),
          const SizedBox(height: 8),
        ],
      ],
    );
  }
}

extension _ContextMemoryTraceDetails on _ContextMemoryTracePageState {
  Widget _buildContextSetupCard(OpenCrayChatRunSnapshot run) {
    final liveContext = run.liveContext;
    final bootstrap = run.bootstrap;
    final memoryFlush = run.memoryFlush;
    final durableCompaction = run.durableCompaction;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Context setup', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (liveContext == null &&
              bootstrap == null &&
              memoryFlush == null &&
              durableCompaction == null)
            const Text(
              'No live-context, bootstrap, memory flush, or durable compaction trace was captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            if (liveContext != null) ...[
              const Text('Live context', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Mode',
                    value: liveContext.mode?.trim().isNotEmpty == true
                        ? liveContext.mode!.trim()
                        : 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Soul',
                    value: liveContext.soulEnabled == true
                        ? 'enabled'
                        : 'disabled',
                  ),
                  _DebugValueChip(
                    label: 'Memory recall',
                    value: liveContext.memoryRecallEnabled == true
                        ? 'enabled'
                        : 'disabled',
                  ),
                ],
              ),
            ],
            if (bootstrap != null) ...[
              if (liveContext != null) const SizedBox(height: 16),
              const Text('Bootstrap', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Mode',
                    value: bootstrap.mode?.trim().isNotEmpty == true
                        ? bootstrap.mode!.trim()
                        : 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Visible',
                    value: '${bootstrap.visibleFileCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Injected',
                    value: '${bootstrap.injectedFileCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Omitted',
                    value: '${bootstrap.omittedFileCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Truncated',
                    value: '${bootstrap.truncatedFileCount ?? 0}',
                  ),
                ],
              ),
              if (bootstrap.files.isNotEmpty) ...[
                const SizedBox(height: 12),
                for (final file in bootstrap.files)
                  _DebugKeyValueLine(
                    file.name,
                    _formatBootstrapFileSummary(file),
                  ),
              ],
            ],
            if (memoryFlush != null) ...[
              if (bootstrap != null) const SizedBox(height: 16),
              const Text('Memory flush', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Outcome',
                    value: memoryFlush.outcome?.trim().isNotEmpty == true
                        ? memoryFlush.outcome!.trim()
                        : 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Candidates',
                    value: '${memoryFlush.candidateCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Written',
                    value: '${memoryFlush.writtenRecordCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Omitted msgs',
                    value: '${memoryFlush.omittedMessageCount ?? 0}',
                  ),
                ],
              ),
              if ((memoryFlush.omittedCharCount ?? 0) > 0)
                _DebugKeyValueLine(
                  'Omitted chars',
                  '${memoryFlush.omittedCharCount}',
                ),
              if (memoryFlush.signature?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Omitted window',
                  _truncateDebugText(memoryFlush.signature!.trim(), 96),
                ),
              if (memoryFlush.writtenKinds.isNotEmpty)
                _DebugKeyValueLine(
                  'Written kinds',
                  memoryFlush.writtenKinds.join(', '),
                ),
              if (memoryFlush.writtenRecordIds.isNotEmpty)
                _DebugKeyValueLine(
                  'Written ids',
                  memoryFlush.writtenRecordIds.join(', '),
                ),
            ],
            if (durableCompaction != null) ...[
              if (bootstrap != null || memoryFlush != null)
                const SizedBox(height: 16),
              const Text(
                'Durable compaction',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Compacted',
                    value: durableCompaction.compactedThisRun == true
                        ? 'yes'
                        : 'no',
                  ),
                  _DebugValueChip(
                    label: 'Source msgs',
                    value:
                        '${durableCompaction.sourceTranscriptMessageCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Retained msgs',
                    value:
                        '${durableCompaction.retainedTranscriptMessageCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Summaries',
                    value: '${durableCompaction.totalSummaryCount ?? 0}',
                  ),
                ],
              ),
              if ((durableCompaction.latestCompactedMessageCount ?? 0) > 0)
                _DebugKeyValueLine(
                  'Latest compacted msgs',
                  '${durableCompaction.latestCompactedMessageCount}',
                ),
              if ((durableCompaction.totalCompactedMessageCount ?? 0) > 0)
                _DebugKeyValueLine(
                  'Total compacted msgs',
                  '${durableCompaction.totalCompactedMessageCount}',
                ),
              if ((durableCompaction.includedSummaryCount ?? 0) > 0 ||
                  (durableCompaction.omittedSummaryCount ?? 0) > 0)
                _DebugKeyValueLine(
                  'Summary window',
                  'included ${durableCompaction.includedSummaryCount ?? 0}, omitted ${durableCompaction.omittedSummaryCount ?? 0}',
                ),
              if ((durableCompaction.latestCompactedAtEpochMs ?? 0) > 0)
                _DebugKeyValueLine(
                  'Latest compacted at',
                  _formatDebugClockTime(
                    durableCompaction.latestCompactedAtEpochMs!,
                  ),
                ),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildSoulResolutionCard() {
    final snapshot = _personalizationSnapshot;
    final preset = snapshot == null ? null : _selectDebugPreset(snapshot);
    final selectedLanguage = snapshot == null
        ? null
        : _selectDebugLanguage(snapshot);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Soul resolution', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (snapshot == null)
            const Text(
              'Personalization state is not available from the current host bridge.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            _DebugKeyValueLine(
              'Base preset',
              preset?.title ?? snapshot.selectedPresetId,
            ),
            _DebugKeyValueLine(
              'Preset voice',
              preset?.voice ?? preset?.summary ?? 'Unavailable',
            ),
            _DebugKeyValueLine(
              'Custom label',
              snapshot.customLabel.trim().isEmpty
                  ? 'None'
                  : snapshot.customLabel.trim(),
            ),
            _DebugKeyValueLine(
              'Custom guidance',
              snapshot.customGuidance.trim().isEmpty
                  ? 'None'
                  : _truncateDebugText(snapshot.customGuidance.trim(), 120),
            ),
            _DebugKeyValueLine('Effective soul', snapshot.livePreviewName),
            _DebugKeyValueLine(
              'Effective summary',
              snapshot.livePreviewSummary,
            ),
            _DebugKeyValueLine(
              'Language',
              selectedLanguage?.title ?? snapshot.selectedAppLanguageId,
            ),
            const _DebugKeyValueLine(
              'Bridge note',
              'Detailed soul attribution and relationship gates live in Soul Inspector only.',
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSkillContextCard(OpenCrayChatRunSnapshot run) {
    final skillInventory = run.skillInventory;
    final activeSkill = run.activeSkill;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Skill context', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (skillInventory == null && activeSkill == null)
            const Text(
              'No skill inventory or active skill trace was captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            if (skillInventory != null) ...[
              const Text(
                'Skill inventory',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Visible',
                    value: '${skillInventory.visibleSkillCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Injected',
                    value: '${skillInventory.injectedSkillCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Omitted',
                    value: '${skillInventory.omittedSkillCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Implicit',
                    value: '${skillInventory.implicitSkillCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Invalid',
                    value: '${skillInventory.invalidSkillCount ?? 0}',
                  ),
                ],
              ),
              if ((skillInventory.omittedTraceSkillCount ?? 0) > 0)
                _DebugKeyValueLine(
                  'Trace-omitted skills',
                  '${skillInventory.omittedTraceSkillCount}',
                ),
              if (skillInventory.skills.isNotEmpty) ...[
                const SizedBox(height: 12),
                for (final skill in skillInventory.skills)
                  _DebugKeyValueLine(
                    skill.name,
                    _formatVisibleSkillSummary(skill),
                  ),
              ],
            ],
            if (activeSkill != null) ...[
              if (skillInventory != null) const SizedBox(height: 16),
              const Text('Active skill', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Restricted',
                    value: activeSkill.toolRestrictionEnabled == true
                        ? 'yes'
                        : 'no',
                  ),
                  _DebugValueChip(
                    label: 'Truncated',
                    value: activeSkill.truncated == true ? 'yes' : 'no',
                  ),
                ],
              ),
              if (activeSkill.name?.trim().isNotEmpty == true)
                _DebugKeyValueLine('Skill', activeSkill.name!.trim()),
              if (activeSkill.relativePath?.trim().isNotEmpty == true)
                _DebugKeyValueLine('Path', activeSkill.relativePath!.trim()),
              if (activeSkill.activationSource?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Activation source',
                  activeSkill.activationSource!.trim(),
                ),
              if (activeSkill.invocationControl?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Invocation',
                  activeSkill.invocationControl!.trim(),
                ),
              if (activeSkill.executionContext?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Execution context',
                  activeSkill.executionContext!.trim(),
                ),
              if (activeSkill.allowedToolKeys.isNotEmpty)
                _DebugKeyValueLine(
                  'Allowed tools',
                  activeSkill.allowedToolKeys.join(', '),
                ),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildRawTraceCard(OpenCrayChatRunSnapshot run) {
    final events = _eventsForRun(run.runId);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Raw trace', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (events.isEmpty)
            const Text(
              'No structured events were captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else
            for (int index = 0; index < events.length; index++) ...[
              _DebugEventRow(event: events[index]),
              if (index < events.length - 1)
                const Divider(height: 16, color: OpenCrayColors.divider),
            ],
        ],
      ),
    );
  }

  OpenCrayChatRuntimeEventSnapshot? _latestEventOfKind(
    String runId,
    String kind,
  ) {
    final matching = _eventsForRun(
      runId,
    ).where((event) => event.kind == kind).toList(growable: false);
    if (matching.isEmpty) {
      final lastEvent = _selectedRunSnapshot?.lastEvent;
      if (lastEvent != null &&
          lastEvent.runId == runId &&
          lastEvent.kind == kind) {
        return lastEvent;
      }
      return null;
    }
    return matching.last;
  }

  List<OpenCrayChatRuntimeEventSnapshot> _eventsForRun(String runId) {
    final snapshot = _runtimeSnapshot;
    if (snapshot == null) {
      return const <OpenCrayChatRuntimeEventSnapshot>[];
    }
    final events =
        snapshot.events
            .where((event) => event.runId == runId)
            .toList(growable: false)
          ..sort(
            (left, right) =>
                left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
          );
    return events;
  }
}

String _formatBootstrapFileSummary(OpenCrayChatRunBootstrapFileSnapshot file) {
  final parts = <String>[file.relativePath];
  if (file.injectedCharCount != null || file.sourceCharCount != null) {
    parts.add(
      'injected ${file.injectedCharCount ?? 0}/${file.sourceCharCount ?? 0} chars',
    );
  }
  if (file.truncated == true) {
    parts.add('truncated');
  }
  return parts.join(' · ');
}

String _formatVisibleSkillSummary(OpenCrayChatRunVisibleSkillSnapshot skill) {
  final parts = <String>[skill.relativePath];
  if (skill.invocationControl?.trim().isNotEmpty == true) {
    parts.add(skill.invocationControl!.trim());
  }
  if (skill.executionContext?.trim().isNotEmpty == true) {
    parts.add(skill.executionContext!.trim());
  }
  if (skill.userInvocable != null) {
    parts.add(skill.userInvocable == true ? 'user-invocable' : 'agent-only');
  }
  return parts.join(' · ');
}

class _MemoryInspectorPage extends StatefulWidget {
  const _MemoryInspectorPage({required this.bridge, required this.backLabel});

  final OpenCrayHostBridge bridge;
  final String backLabel;

  @override
  State<_MemoryInspectorPage> createState() => _MemoryInspectorPageState();
}

class _MemoryInspectorPageState extends State<_MemoryInspectorPage> {
  bool _isLoading = true;
  bool _isRefreshing = false;
  bool _isSearching = false;
  bool _isLoadingSearchSlice = false;
  bool _isApplyingMemoryAction = false;
  String? _loadError;
  String? _searchError;
  String? _actionError;
  _MemoryInspectorFilter _activeFilter = _MemoryInspectorFilter.all;
  OpenCrayMemoryDebugSnapshot? _snapshot;
  OpenCrayMemoryDebugLinksSnapshot? _linksSnapshot;
  OpenCraySoulDebugSnapshot? _soulSnapshot;
  OpenCrayMemoryDebugSearchSnapshot? _searchSnapshot;
  OpenCrayMemoryDebugSliceSnapshot? _searchSlice;
  List<OpenCrayMemoryDebugRecordSnapshot> _records =
      const <OpenCrayMemoryDebugRecordSnapshot>[];
  String? _selectedRecordId;
  String? _selectedSearchRecordId;
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final filteredRecords = _records
        .where((record) => _activeFilter.matches(record))
        .toList(growable: false);
    final selectedRecord = filteredRecords
        .cast<OpenCrayMemoryDebugRecordSnapshot?>()
        .firstWhere(
          (record) => record?.id == _selectedRecordId,
          orElse: () => filteredRecords.isEmpty ? null : filteredRecords.first,
        );
    final selectedLinks = selectedRecord == null
        ? null
        : _findMemoryDebugLinksEntry(_linksSnapshot, selectedRecord.id);
    final selectedSoulFieldSources = selectedRecord == null
        ? const <OpenCraySoulFieldSourceSnapshot>[]
        : _linkedSoulFieldSources(_soulSnapshot, selectedRecord.id);
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          key: const ValueKey<String>('settings-memory-inspector-page'),
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _BackLink(
                onTap: () => Navigator.of(context).pop(),
                label: widget.backLabel,
              ),
              const SizedBox(height: 8),
              const Text(
                'Memory Inspector',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Review durable and session memory.',
                style: _SettingsTextStyles.subtitle,
              ),
              const SizedBox(height: 16),
              _buildStoreSummaryCard(),
              const SizedBox(height: 16),
              _buildFilterCard(),
              const SizedBox(height: 16),
              _buildProjectedMemorySearchCard(),
              if (_loadError != null) ...[
                const SizedBox(height: 16),
                _SettingsCard(
                  backgroundColor: const Color(0xFFFFF3F0),
                  child: Text(
                    _loadError!,
                    style: _SettingsTextStyles.bodyStrong,
                  ),
                ),
              ],
              const SizedBox(height: 16),
              if (_isLoading)
                const _SettingsLoading(
                  key: ValueKey<String>('memory-inspector-loading'),
                )
              else ...[
                _buildRecordsCard(filteredRecords),
                const SizedBox(height: 16),
                _buildSelectedRecordCard(selectedRecord),
                const SizedBox(height: 16),
                _buildSelectedRecordLinksCard(
                  record: selectedRecord,
                  links: selectedLinks,
                  soulFieldSources: selectedSoulFieldSources,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStoreSummaryCard() {
    final snapshot = _snapshot;
    final durableCount = _records
        .where((record) => _isDurableMemoryKind(record.kind))
        .length;
    final preferenceCount = _records
        .where((record) => record.kind == 'user_preference')
        .length;
    final openCommitmentCount = _records
        .where(
          (record) =>
              record.kind == 'task_commitment' &&
              record.status != 'resolved' &&
              !record.isExpired,
        )
        .length;
    final latestRecord = _records.isEmpty ? null : _records.first;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Store summary',
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
          Text(
            '${_records.length} records · $durableCount durable · $preferenceCount preferences · $openCommitmentCount open commitments',
            style: _SettingsTextStyles.bodyStrong,
          ),
          const SizedBox(height: 6),
          Text(
            latestRecord == null
                ? 'No persisted memory records are available yet.'
                : 'Last updated ${_formatDebugClockTime(latestRecord.updatedAtEpochMs)} · ${latestRecord.id}',
            style: _SettingsTextStyles.body,
          ),
          if (snapshot != null) ...[
            const SizedBox(height: 6),
            Text(
              'Session ${snapshot.sessionId}${snapshot.workspaceId.isEmpty ? '' : ' · workspace ${_truncateDebugText(snapshot.workspaceId, 36)}'}',
              style: _SettingsTextStyles.body,
            ),
          ],
          const SizedBox(height: 8),
          const Text(
            'This view reads directly from the persisted memory store and annotates each record with current TTL expiry state.',
            style: _SettingsTextStyles.body,
          ),
        ],
      ),
    );
  }

  Widget _buildFilterCard() {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Filter', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _MemoryInspectorFilter.values
                .map(
                  (filter) => _DebugToggleChip(
                    label: filter.label,
                    selected: filter == _activeFilter,
                    onTap: () => setState(() => _activeFilter = filter),
                  ),
                )
                .toList(growable: false),
          ),
        ],
      ),
    );
  }

  Widget _buildProjectedMemorySearchCard() {
    final searchSnapshot = _searchSnapshot;
    final searchResults =
        searchSnapshot?.results ??
        const <OpenCrayMemoryDebugSearchResultSnapshot>[];
    final selectedSearchResult = searchResults
        .cast<OpenCrayMemoryDebugSearchResultSnapshot?>()
        .firstWhere(
          (result) => result?.recordId == _selectedSearchRecordId,
          orElse: () => searchResults.isEmpty ? null : searchResults.first,
        );
    return KeyedSubtree(
      key: const ValueKey<String>('settings-memory-search-card'),
      child: _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'Search projected memory',
                    style: _SettingsTextStyles.cardTitle,
                  ),
                ),
                _HeaderActionChip(
                  label: _isSearching ? 'Searching' : 'Search',
                  onTap: _isSearching ? null : _runProjectedMemorySearch,
                ),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'Query the same projected memory corpus exposed to memory_search and inspect a narrow snippet with memory_get-style slicing.',
              style: _SettingsTextStyles.body,
            ),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey<String>('settings-memory-search-input'),
              controller: _searchController,
              textInputAction: TextInputAction.search,
              autocorrect: false,
              enableSuggestions: false,
              enableIMEPersonalizedLearning: false,
              spellCheckConfiguration: const SpellCheckConfiguration.disabled(),
              smartDashesType: SmartDashesType.disabled,
              smartQuotesType: SmartQuotesType.disabled,
              decoration: const InputDecoration(
                labelText: 'Search query',
                hintText: 'name, chinese, rollback, deadline reminder',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (_) => _runProjectedMemorySearch(),
            ),
            if (_searchError != null) ...[
              const SizedBox(height: 10),
              Text(_searchError!, style: _SettingsTextStyles.bodyStrong),
            ],
            if (searchSnapshot != null) ...[
              const SizedBox(height: 12),
              Text(
                '${searchResults.length} match(es) · ${searchSnapshot.corpusFileCount} file(s)${searchSnapshot.queryTerms.isEmpty ? '' : ' · terms ${searchSnapshot.queryTerms.join(', ')}'}',
                style: _SettingsTextStyles.bodyStrong,
              ),
              if (searchResults.isEmpty) ...[
                const SizedBox(height: 8),
                const Text(
                  'No projected memory matches were found for the current query.',
                  style: _SettingsTextStyles.body,
                ),
              ] else ...[
                const SizedBox(height: 10),
                for (int index = 0; index < searchResults.length; index++) ...[
                  _MemoryDebugSearchResultRow(
                    result: searchResults[index],
                    selected:
                        searchResults[index].recordId ==
                        selectedSearchResult?.recordId,
                    onTap: () =>
                        _selectProjectedMemoryResult(searchResults[index]),
                  ),
                  if (index < searchResults.length - 1)
                    const SizedBox(height: 10),
                ],
              ],
            ],
            if (_searchSlice != null || _isLoadingSearchSlice) ...[
              const SizedBox(height: 14),
              Text('Selected snippet', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              if (_isLoadingSearchSlice)
                const Text(
                  'Loading projected snippet...',
                  style: _SettingsTextStyles.body,
                )
              else if (_searchSlice != null) ...[
                _DebugKeyValueLine(
                  'Path',
                  '${_searchSlice!.path}#${_formatMemoryLineRange(_searchSlice!.startLine, _searchSlice!.endLine)}',
                ),
                _DebugKeyValueLine(
                  'Record ids',
                  _searchSlice!.recordIds.isEmpty
                      ? 'Unavailable'
                      : _searchSlice!.recordIds.join(', '),
                ),
                _DebugKeyValueLine(
                  'Total lines',
                  '${_searchSlice!.totalLineCount}',
                ),
                const SizedBox(height: 8),
                Container(
                  key: const ValueKey<String>('settings-memory-search-snippet'),
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF7F8FA),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: OpenCrayColors.divider),
                  ),
                  child: SelectableText(
                    _searchSlice!.text,
                    style: _SettingsTextStyles.body,
                  ),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildRecordsCard(
    List<OpenCrayMemoryDebugRecordSnapshot> filteredRecords,
  ) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Memory records', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (filteredRecords.isEmpty)
            const Text(
              'No persisted records match the current filter.',
              style: _SettingsTextStyles.body,
            )
          else
            for (int index = 0; index < filteredRecords.length; index++) ...[
              _MemoryDebugRecordRow(
                record: filteredRecords[index],
                selected: filteredRecords[index].id == _selectedRecordId,
                onTap: () => setState(
                  () => _selectedRecordId = filteredRecords[index].id,
                ),
              ),
              if (index < filteredRecords.length - 1)
                const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }

  Widget _buildSelectedRecordCard(OpenCrayMemoryDebugRecordSnapshot? record) {
    final actionChips = record == null
        ? const <Widget>[]
        : _buildMemoryActionChips(record);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Selected record', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (record == null)
            const Text(
              'Select a record to inspect its persisted state.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            if (actionChips.isNotEmpty) ...[
              Wrap(spacing: 8, runSpacing: 8, children: actionChips),
              const SizedBox(height: 12),
            ],
            if (_actionError != null) ...[
              Text(
                _actionError!,
                key: const ValueKey<String>('settings-memory-action-error'),
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 12),
            ],
            _DebugKeyValueLine('Record', record.id),
            _DebugKeyValueLine(
              'Kind',
              record.kind.isEmpty ? 'Unavailable' : record.kind,
            ),
            _DebugKeyValueLine(
              'Scope',
              record.scope.isEmpty ? 'Unavailable' : record.scope,
            ),
            _DebugKeyValueLine(
              'Latest state',
              record.isExpired
                  ? 'expired'
                  : (record.status.isEmpty ? 'unknown' : record.status),
            ),
            if (record.source.isNotEmpty)
              _DebugKeyValueLine('Source', record.source),
            if (record.sourceSessionId.isNotEmpty)
              _DebugKeyValueLine('Source session', record.sourceSessionId),
            if (record.sourceTaskId.isNotEmpty)
              _DebugKeyValueLine('Source task', record.sourceTaskId),
            if (record.workspaceId.isNotEmpty)
              _DebugKeyValueLine(
                'Workspace',
                _truncateDebugText(record.workspaceId, 48),
              ),
            _DebugKeyValueLine(
              'Updated',
              _formatDebugClockTime(record.updatedAtEpochMs),
            ),
            if (record.lastConfirmedAtEpochMs != null)
              _DebugKeyValueLine(
                'Last confirmed',
                _formatDebugClockTime(record.lastConfirmedAtEpochMs!),
              ),
            if (record.preferenceKey.isNotEmpty)
              _DebugKeyValueLine('Preference key', record.preferenceKey),
            if (record.preferenceValue.isNotEmpty)
              _DebugKeyValueLine('Preference value', record.preferenceValue),
            if (record.preferenceTemporality.isNotEmpty)
              _DebugKeyValueLine(
                'Preference temporality',
                record.preferenceTemporality,
              ),
            if (record.ttlMs != null)
              _DebugKeyValueLine('TTL', _formatMemoryTtl(record.ttlMs!)),
            if (record.resolutionReason.isNotEmpty)
              _DebugKeyValueLine('Resolution reason', record.resolutionReason),
            if (record.supersededBy.isNotEmpty)
              _DebugKeyValueLine('Superseded by', record.supersededBy),
            if (record.tags.isNotEmpty)
              _DebugKeyValueLine('Tags', record.tags.join(', ')),
            _DebugKeyValueLine(
              'Content',
              _truncateDebugText(record.content, 160),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSelectedRecordLinksCard({
    required OpenCrayMemoryDebugRecordSnapshot? record,
    required OpenCrayMemoryDebugLinksEntrySnapshot? links,
    required List<OpenCraySoulFieldSourceSnapshot> soulFieldSources,
  }) {
    return KeyedSubtree(
      key: const ValueKey<String>('settings-memory-linked-activity-card'),
      child: _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Linked activity', style: _SettingsTextStyles.cardTitle),
            const SizedBox(height: 10),
            if (record == null)
              const Text(
                'Select a record to inspect linked run and soul activity.',
                style: _SettingsTextStyles.body,
              )
            else
              ..._buildMemoryLinkDetails(
                links: links,
                soulFieldSources: soulFieldSources,
                includeSoulFieldsSection: true,
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _refresh() async {
    final shouldShowLoading = _snapshot == null && !_isRefreshing;
    setState(() {
      _loadError = null;
      _isLoading = shouldShowLoading;
      _isRefreshing = true;
    });
    try {
      final results = await Future.wait<Object?>(<Future<Object?>>[
        widget.bridge.loadMemoryDebugSnapshot(),
        widget.bridge.loadMemoryDebugLinksSnapshot(),
        widget.bridge.loadSoulDebugSnapshot(),
      ]);
      final snapshot = results[0] as OpenCrayMemoryDebugSnapshot;
      final linksSnapshot = results[1] as OpenCrayMemoryDebugLinksSnapshot;
      final soulSnapshot = results[2] as OpenCraySoulDebugSnapshot;
      final records = snapshot.records;
      final nextSelectedRecordId =
          records.any((record) => record.id == _selectedRecordId)
          ? _selectedRecordId
          : (records.isEmpty ? null : records.first.id);
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _linksSnapshot = linksSnapshot;
        _soulSnapshot = soulSnapshot;
        _records = records;
        _selectedRecordId = nextSelectedRecordId;
        _isLoading = false;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load memory snapshot: $error';
        _isLoading = false;
        _isRefreshing = false;
      });
    }
  }

  Future<void> _runProjectedMemorySearch() async {
    final query = _searchController.text.trim();
    if (query.isEmpty) {
      setState(() {
        _searchError = 'Enter a query before searching projected memory.';
      });
      return;
    }
    setState(() {
      _isSearching = true;
      _searchError = null;
      _searchSnapshot = null;
      _searchSlice = null;
      _selectedSearchRecordId = null;
    });
    try {
      final snapshot = await widget.bridge.searchMemoryDebug(query: query);
      if (!mounted) {
        return;
      }
      setState(() {
        _searchSnapshot = snapshot;
        _selectedSearchRecordId = snapshot.results.isEmpty
            ? null
            : snapshot.results.first.recordId;
        _isSearching = false;
      });
      if (snapshot.results.isNotEmpty) {
        await _selectProjectedMemoryResult(snapshot.results.first);
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _searchError = 'Failed to search projected memory: $error';
        _isSearching = false;
      });
    }
  }

  Future<void> _selectProjectedMemoryResult(
    OpenCrayMemoryDebugSearchResultSnapshot result,
  ) async {
    setState(() {
      _selectedSearchRecordId = result.recordId;
      _selectedRecordId = result.recordId;
      _activeFilter = _MemoryInspectorFilter.all;
      _isLoadingSearchSlice = true;
      _searchError = null;
    });
    try {
      final slice = await widget.bridge.getMemoryDebugSlice(
        path: result.path,
        fromLine: result.startLine,
        lines: result.endLine - result.startLine + 1,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _searchSlice = slice;
        _isLoadingSearchSlice = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _searchError = 'Failed to load projected snippet: $error';
        _isLoadingSearchSlice = false;
      });
    }
  }

  List<Widget> _buildMemoryActionChips(
    OpenCrayMemoryDebugRecordSnapshot record,
  ) {
    if (_recordHasSoulObjectPayload(record)) {
      return const <Widget>[];
    }
    final status = record.status.trim().toLowerCase();
    final resolutionReason = record.resolutionReason.trim().toLowerCase();
    final canSuppress = status == 'active' || status == 'open';
    final canReaffirm =
        canSuppress ||
        (status == 'resolved' &&
            resolutionReason == _memoryOperatorSuppressedReason);
    final chips = <Widget>[];
    if (canSuppress) {
      chips.add(
        KeyedSubtree(
          key: const ValueKey<String>('settings-memory-action-suppress'),
          child: _HeaderActionChip(
            label: 'Suppress',
            onTap: _isApplyingMemoryAction
                ? null
                : () => _applyMemoryAction(
                    recordId: record.id,
                    actionId: 'suppress',
                  ),
          ),
        ),
      );
    }
    if (canReaffirm) {
      chips.add(
        KeyedSubtree(
          key: const ValueKey<String>('settings-memory-action-reaffirm'),
          child: _HeaderActionChip(
            label: 'Reaffirm',
            onTap: _isApplyingMemoryAction
                ? null
                : () => _applyMemoryAction(
                    recordId: record.id,
                    actionId: 'reaffirm',
                  ),
          ),
        ),
      );
    }
    return chips;
  }

  Future<void> _applyMemoryAction({
    required String recordId,
    required String actionId,
  }) async {
    setState(() {
      _actionError = null;
      _isApplyingMemoryAction = true;
      _activeFilter = _MemoryInspectorFilter.all;
      _selectedRecordId = recordId;
    });
    try {
      await widget.bridge.applyMemoryDebugAction(
        recordId: recordId,
        actionId: actionId,
      );
      await _refresh();
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _actionError = 'Failed to apply $actionId: $error';
      });
    } finally {
      if (!mounted) {
        return;
      }
      setState(() {
        _isApplyingMemoryAction = false;
      });
    }
  }
}

class _SoulInspectorPage extends StatefulWidget {
  const _SoulInspectorPage({required this.bridge, required this.backLabel});

  final OpenCrayHostBridge bridge;
  final String backLabel;

  @override
  State<_SoulInspectorPage> createState() => _SoulInspectorPageState();
}

class _SoulInspectorPageState extends State<_SoulInspectorPage> {
  bool _isLoading = true;
  bool _isRefreshing = false;
  String? _loadError;
  OpenCraySoulDebugSnapshot? _snapshot;
  OpenCrayMemoryDebugLinksSnapshot? _linksSnapshot;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    final fieldSources = snapshot == null
        ? const <OpenCraySoulFieldSourceSnapshot>[]
        : _resolvedSoulFieldSources(snapshot);
    final linkedFieldGroups = _groupLinkedSoulFieldSources(fieldSources);
    final interactionPreferenceDebug = snapshot?.interactionPreferenceDebug;
    final relationshipStateDebug = snapshot?.relationshipStateDebug;
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          key: const ValueKey<String>('settings-soul-inspector-page'),
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _BackLink(
                onTap: () => Navigator.of(context).pop(),
                label: widget.backLabel,
              ),
              const SizedBox(height: 8),
              const Text(
                'Soul Inspector',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Base preset, overlays, and effective soul.',
                style: _SettingsTextStyles.subtitle,
              ),
              const SizedBox(height: 16),
              Align(
                alignment: Alignment.centerRight,
                child: _HeaderActionChip(
                  label: _isRefreshing ? 'Refreshing' : 'Refresh',
                  onTap: _isRefreshing ? null : _refresh,
                ),
              ),
              if (_loadError != null) ...[
                const SizedBox(height: 16),
                _SettingsCard(
                  backgroundColor: const Color(0xFFFFF3F0),
                  child: Text(
                    _loadError!,
                    style: _SettingsTextStyles.bodyStrong,
                  ),
                ),
              ],
              const SizedBox(height: 16),
              if (_isLoading)
                const _SettingsLoading(
                  key: ValueKey<String>('soul-inspector-loading'),
                )
              else if (snapshot == null)
                const _SettingsCard(
                  child: Text(
                    'Soul state is not available from the current host snapshot.',
                    style: _SettingsTextStyles.body,
                  ),
                )
              else ...[
                KeyedSubtree(
                  key: const ValueKey<String>('settings-soul-stored-card'),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Stored soul',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (snapshot.storedSoul == null)
                          const Text(
                            'No persisted soul record exists yet.',
                            style: _SettingsTextStyles.body,
                          )
                        else ...[
                          _DebugKeyValueLine(
                            'Agent',
                            snapshot.storedSoul!.agentId,
                          ),
                          if (snapshot.storedSoul!.displayName.isNotEmpty)
                            _DebugKeyValueLine(
                              'Display name',
                              snapshot.storedSoul!.displayName,
                            ),
                          if (snapshot.storedSoul!.presetName.isNotEmpty)
                            _DebugKeyValueLine(
                              'Preset',
                              snapshot.storedSoul!.presetName,
                            ),
                          if (snapshot.storedSoul!.customGuidance.isNotEmpty)
                            _DebugKeyValueLine(
                              'Custom guidance',
                              _truncateDebugText(
                                snapshot.storedSoul!.customGuidance,
                                140,
                              ),
                            ),
                          _DebugKeyValueLine(
                            'Updated',
                            _formatDebugClockTime(
                              snapshot.storedSoul!.updatedAtEpochMs,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>('settings-soul-base-card'),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Base soul',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (snapshot.baseSoul == null)
                          const Text(
                            'No base soul is currently resolved from persisted personalization.',
                            style: _SettingsTextStyles.body,
                          )
                        else ...[
                          ..._buildSoulProfileLines(snapshot.baseSoul!),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>('settings-soul-overlays-card'),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Memory overlays',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (snapshot.overlayRecords.isEmpty)
                          const Text(
                            'No active memory-backed soul overlays apply to the current session/workspace.',
                            style: _SettingsTextStyles.body,
                          )
                        else
                          for (
                            int index = 0;
                            index < snapshot.overlayRecords.length;
                            index++
                          ) ...[
                            _MemoryDebugRecordRow(
                              record: snapshot.overlayRecords[index],
                              selected: false,
                              onTap: () {},
                            ),
                            if (index < snapshot.overlayRecords.length - 1)
                              const SizedBox(height: 10),
                          ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>('settings-soul-effective-card'),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Effective soul',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (snapshot.effectiveSoul == null)
                          const Text(
                            'The runtime has no effective soul profile yet.',
                            style: _SettingsTextStyles.body,
                          )
                        else ...[
                          ..._buildSoulProfileLines(snapshot.effectiveSoul!),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>(
                    'settings-soul-interaction-preference-card',
                  ),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Interaction preference',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (interactionPreferenceDebug == null)
                          const Text(
                            'No persisted interaction-preference snapshot currently applies to this soul.',
                            style: _SettingsTextStyles.body,
                          )
                        else ...[
                          ..._buildInteractionPreferenceDebugLines(
                            interactionPreferenceDebug,
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>(
                    'settings-soul-relationship-gates-card',
                  ),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Relationship gates',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (relationshipStateDebug == null)
                          const Text(
                            'No persisted relationship-state snapshot or event projection currently applies to this soul.',
                            style: _SettingsTextStyles.body,
                          )
                        else ...[
                          ..._buildRelationshipStateDebugLines(
                            relationshipStateDebug,
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>(
                    'settings-soul-field-sources-card',
                  ),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Field sources',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (fieldSources.isEmpty)
                          const Text(
                            'No resolved soul fields are currently populated.',
                            style: _SettingsTextStyles.body,
                          )
                        else
                          for (final source in fieldSources)
                            _DebugKeyValueLine(
                              '${_debugSoulFieldLabel(source.field)} ->',
                              _formatSoulFieldSourceSummary(source),
                            ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                KeyedSubtree(
                  key: const ValueKey<String>(
                    'settings-soul-linked-activity-card',
                  ),
                  child: _SettingsCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Linked activity',
                          style: _SettingsTextStyles.cardTitle,
                        ),
                        const SizedBox(height: 10),
                        if (linkedFieldGroups.isEmpty)
                          const Text(
                            'No memory-backed soul fields are currently linked to a persisted memory record.',
                            style: _SettingsTextStyles.body,
                          )
                        else
                          for (
                            int index = 0;
                            index < linkedFieldGroups.length;
                            index++
                          ) ...[
                            _buildSoulLinkedActivityGroup(
                              recordId: linkedFieldGroups[index].$1,
                              sources: linkedFieldGroups[index].$2,
                            ),
                            if (index < linkedFieldGroups.length - 1)
                              const SizedBox(height: 14),
                          ],
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _refresh() async {
    final shouldShowLoading = _snapshot == null && !_isRefreshing;
    setState(() {
      _loadError = null;
      _isLoading = shouldShowLoading;
      _isRefreshing = true;
    });
    try {
      final results = await Future.wait<Object?>(<Future<Object?>>[
        widget.bridge.loadSoulDebugSnapshot(),
        widget.bridge.loadMemoryDebugLinksSnapshot(),
      ]);
      final snapshot = results[0] as OpenCraySoulDebugSnapshot;
      final linksSnapshot = results[1] as OpenCrayMemoryDebugLinksSnapshot;
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _linksSnapshot = linksSnapshot;
        _isLoading = false;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load soul snapshot: $error';
        _isLoading = false;
        _isRefreshing = false;
      });
    }
  }

  Widget _buildSoulLinkedActivityGroup({
    required String recordId,
    required List<OpenCraySoulFieldSourceSnapshot> sources,
  }) {
    final links = _findMemoryDebugLinksEntry(_linksSnapshot, recordId);
    final fieldsLabel = sources
        .map((source) => _debugSoulFieldLabel(source.field))
        .toSet()
        .join(', ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '$recordId${fieldsLabel.isEmpty ? '' : ' · $fieldsLabel'}',
          style: _SettingsTextStyles.bodyStrong,
        ),
        const SizedBox(height: 8),
        ..._buildMemoryLinkDetails(
          links: links,
          soulFieldSources: sources,
          includeSoulFieldsSection: false,
        ),
      ],
    );
  }
}

class _DebugToggleChip extends StatelessWidget {
  const _DebugToggleChip({
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
      onTap: onTap,
      borderRadius: BorderRadius.circular(999),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFEAF2FF) : const Color(0xFFF3F4F7),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          child: Text(
            label,
            style: _SettingsTextStyles.actionChip.copyWith(
              color: selected
                  ? OpenCrayColors.primary
                  : OpenCrayColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _MemoryDebugRecordRow extends StatelessWidget {
  const _MemoryDebugRecordRow({
    required this.record,
    required this.selected,
    required this.onTap,
  });

  final OpenCrayMemoryDebugRecordSnapshot record;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFF3F4F7) : Colors.white,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected ? const Color(0xFFD7E4FF) : OpenCrayColors.divider,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _memoryRecordTitleLine(record),
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 6),
              Text(
                _memoryRecordSummaryLine(record),
                style: _SettingsTextStyles.body,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MemoryDebugSearchResultRow extends StatelessWidget {
  const _MemoryDebugSearchResultRow({
    required this.result,
    required this.selected,
    required this.onTap,
  });

  final OpenCrayMemoryDebugSearchResultSnapshot result;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: selected ? const Color(0xFFF3F4F7) : Colors.white,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected ? const Color(0xFFD7E4FF) : OpenCrayColors.divider,
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${result.recordId} · ${result.path}#${_formatMemoryLineRange(result.startLine, result.endLine)}',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 6),
              Text(
                'score ${result.score} · ${result.kind}/${result.scope}/${result.status}',
                style: _SettingsTextStyles.body,
              ),
              if (result.matchedTerms.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  'terms ${result.matchedTerms.join(', ')}',
                  style: _SettingsTextStyles.body,
                ),
              ],
              if (result.snippet.trim().isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  _truncateDebugText(result.snippet.trim(), 220),
                  style: _SettingsTextStyles.body,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _DebugLinkEventRow extends StatelessWidget {
  const _DebugLinkEventRow({required this.title, required this.detail});

  final String title;
  final String detail;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: _SettingsTextStyles.bodyStrong),
        const SizedBox(height: 4),
        Text(detail, style: _SettingsTextStyles.body),
      ],
    );
  }
}

class _DebugEventRow extends StatelessWidget {
  const _DebugEventRow({required this.event});

  final OpenCrayChatRuntimeEventSnapshot event;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${_formatDebugClockTime(event.emittedAtEpochMs)} · ${event.kind}',
          style: _SettingsTextStyles.bodyStrong,
        ),
        const SizedBox(height: 4),
        Text(_summarizeRuntimeEvent(event), style: _SettingsTextStyles.body),
      ],
    );
  }
}

enum _MemoryInspectorFilter {
  all('All'),
  durable('Durable'),
  commitments('Commitments'),
  resolved('Resolved');

  const _MemoryInspectorFilter(this.label);

  final String label;

  bool matches(OpenCrayMemoryDebugRecordSnapshot record) {
    switch (this) {
      case _MemoryInspectorFilter.all:
        return true;
      case _MemoryInspectorFilter.durable:
        return _isDurableMemoryKind(record.kind);
      case _MemoryInspectorFilter.commitments:
        return record.kind == 'task_commitment';
      case _MemoryInspectorFilter.resolved:
        return record.status == 'resolved' || record.isExpired;
    }
  }
}

const String _memoryOperatorSuppressedReason = 'operator_suppressed';

bool _recordHasSoulObjectPayload(OpenCrayMemoryDebugRecordSnapshot record) =>
    record.extensions['soul_object_type']?.trim().isNotEmpty == true;

OpenCrayMemoryDebugLinksEntrySnapshot? _findMemoryDebugLinksEntry(
  OpenCrayMemoryDebugLinksSnapshot? snapshot,
  String recordId,
) {
  if (snapshot == null || recordId.trim().isEmpty) {
    return null;
  }
  for (final entry in snapshot.records) {
    if (entry.recordId == recordId) {
      return entry;
    }
  }
  return null;
}

List<OpenCraySoulFieldSourceSnapshot> _linkedSoulFieldSources(
  OpenCraySoulDebugSnapshot? snapshot,
  String recordId,
) {
  if (snapshot == null || recordId.trim().isEmpty) {
    return const <OpenCraySoulFieldSourceSnapshot>[];
  }
  return _resolvedSoulFieldSources(
    snapshot,
  ).where((source) => source.recordId == recordId).toList(growable: false);
}

List<(String, List<OpenCraySoulFieldSourceSnapshot>)>
_groupLinkedSoulFieldSources(List<OpenCraySoulFieldSourceSnapshot> sources) {
  final grouped = <String, List<OpenCraySoulFieldSourceSnapshot>>{};
  for (final source in sources) {
    final recordId = source.recordId.trim();
    if (recordId.isEmpty) {
      continue;
    }
    grouped.putIfAbsent(recordId, () => <OpenCraySoulFieldSourceSnapshot>[]);
    grouped[recordId]!.add(source);
  }
  final entries = grouped.entries.toList(growable: false)
    ..sort((left, right) => left.key.compareTo(right.key));
  return entries
      .map((entry) => (entry.key, entry.value))
      .toList(growable: false);
}

List<Widget> _buildMemoryLinkDetails({
  required OpenCrayMemoryDebugLinksEntrySnapshot? links,
  required List<OpenCraySoulFieldSourceSnapshot> soulFieldSources,
  required bool includeSoulFieldsSection,
}) {
  final widgets = <Widget>[];

  void addSectionTitle(String title) {
    if (widgets.isNotEmpty) {
      widgets.add(const SizedBox(height: 12));
    }
    widgets.add(Text(title, style: _SettingsTextStyles.bodyStrong));
    widgets.add(const SizedBox(height: 8));
  }

  if (links?.sourceRun != null ||
      (links?.sourceTaskId.isNotEmpty ?? false) ||
      (links?.sourceSessionId.isNotEmpty ?? false)) {
    addSectionTitle('Origin');
    final sourceRun = links?.sourceRun;
    if (sourceRun != null) {
      widgets.add(
        _DebugKeyValueLine('Source run', _formatDebugRunLinkSummary(sourceRun)),
      );
    }
    if ((links?.sourceTaskId.isNotEmpty ?? false) &&
        sourceRun?.taskId != links!.sourceTaskId) {
      widgets.add(_DebugKeyValueLine('Source task', links.sourceTaskId));
    }
    if ((links?.sourceSessionId.isNotEmpty ?? false) &&
        sourceRun?.sessionId != links!.sourceSessionId) {
      widgets.add(_DebugKeyValueLine('Source session', links.sourceSessionId));
    }
  }

  if (includeSoulFieldsSection && soulFieldSources.isNotEmpty) {
    addSectionTitle('Soul fields');
    for (int index = 0; index < soulFieldSources.length; index++) {
      final source = soulFieldSources[index];
      widgets.add(
        _DebugKeyValueLine(
          _debugSoulFieldLabel(source.field),
          _formatSoulFieldSourceSummary(source),
        ),
      );
      if (source.preferenceKey.isNotEmpty) {
        widgets.add(_DebugKeyValueLine('Preference key', source.preferenceKey));
      }
      if (source.sourceDetail.isNotEmpty) {
        widgets.add(_DebugKeyValueLine('Why', source.sourceDetail));
      }
      if (index < soulFieldSources.length - 1) {
        widgets.add(const SizedBox(height: 8));
      }
    }
  }

  if (links != null && links.promptRecalls.isNotEmpty) {
    addSectionTitle('Prompt recall');
    for (final recall in links.promptRecalls) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(recall.occurredAtEpochMs)} · ${recall.run.runId}',
          detail: _formatPromptRecallLinkDetail(recall),
        ),
      );
      if (recall != links.promptRecalls.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (links != null && links.toolRetrievals.isNotEmpty) {
    addSectionTitle('Tool retrieval');
    for (final retrieval in links.toolRetrievals) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(retrieval.occurredAtEpochMs)} · ${retrieval.toolName}',
          detail: _formatToolRetrievalLinkDetail(retrieval),
        ),
      );
      if (retrieval != links.toolRetrievals.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (links != null && links.maintenanceActions.isNotEmpty) {
    addSectionTitle('Maintenance');
    for (final action in links.maintenanceActions) {
      widgets.add(
        _DebugLinkEventRow(
          title:
              '${_formatDebugClockTime(action.occurredAtEpochMs)} · ${_memoryMaintenanceActionLabel(action.action)}',
          detail: _formatMaintenanceActionLinkDetail(action),
        ),
      );
      if (action != links.maintenanceActions.last) {
        widgets.add(const SizedBox(height: 10));
      }
    }
  }

  if (widgets.isEmpty) {
    widgets.add(
      const Text(
        'No linked run or soul activity is available for this record yet.',
        style: _SettingsTextStyles.body,
      ),
    );
  }
  return widgets;
}

String _formatDebugRunLinkSummary(OpenCrayDebugRunLinkSnapshot run) {
  final state = run.executionStatus?.trim().isNotEmpty == true
      ? run.executionStatus!
      : (run.lifecycleState?.trim().isNotEmpty == true
            ? run.lifecycleState!
            : 'pending');
  return '${run.runId} · ${run.taskId} · $state';
}

String _formatPromptRecallLinkDetail(
  OpenCrayMemoryPromptRecallLinkSnapshot recall,
) {
  final parts = <String>[
    'Task ${recall.run.taskId}',
    'Session ${recall.run.sessionId}',
  ];
  if (recall.score != null) {
    parts.add('score ${recall.score}');
  }
  if (recall.matchedTerms.isNotEmpty) {
    parts.add('matched ${recall.matchedTerms.join(', ')}');
  }
  return parts.join(' · ');
}

String _formatToolRetrievalLinkDetail(
  OpenCrayMemoryToolRetrievalLinkSnapshot retrieval,
) {
  final parts = <String>[
    'Run ${retrieval.run.runId}',
    'Task ${retrieval.run.taskId}',
  ];
  if (retrieval.query?.trim().isNotEmpty == true) {
    parts.add('query ${retrieval.query!.trim()}');
  } else if (retrieval.path?.trim().isNotEmpty == true) {
    parts.add('path ${retrieval.path!.trim()}');
  } else if (retrieval.paths.isNotEmpty) {
    parts.add(retrieval.paths.join(', '));
  }
  if (retrieval.queryTerms.isNotEmpty) {
    parts.add('terms ${retrieval.queryTerms.join(', ')}');
  }
  if (retrieval.lineRanges.isNotEmpty) {
    parts.add('lines ${retrieval.lineRanges.join(', ')}');
  } else if (retrieval.fromLine != null &&
      retrieval.returnedLineCount != null) {
    final endLine = retrieval.fromLine! + retrieval.returnedLineCount! - 1;
    parts.add('lines ${retrieval.fromLine}-$endLine');
  }
  return parts.join(' · ');
}

String _memoryMaintenanceActionLabel(String action) {
  switch (action) {
    case 'written':
      return 'Written';
    case 'resolved':
      return 'Resolved';
    case 'suppressed':
      return 'Suppressed';
    case 'reaffirmed':
      return 'Reaffirmed';
    case 'expired':
      return 'Expired';
    case 'flush_written':
      return 'Flush write';
    default:
      return action;
  }
}

String _formatMaintenanceActionLinkDetail(
  OpenCrayMemoryMaintenanceActionLinkSnapshot action,
) {
  return 'Run ${action.run.runId} · Task ${action.run.taskId} · Session ${action.run.sessionId}';
}

List<String> _collectRecentDebugRunIds(OpenCrayChatRuntimeSnapshot snapshot) {
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

PersonalizationPresetOption? _selectDebugPreset(
  PersonalizationConfigSnapshot snapshot,
) {
  for (final preset in snapshot.presets) {
    if (preset.id == snapshot.selectedPresetId) {
      return preset;
    }
  }
  return snapshot.presets.isEmpty ? null : snapshot.presets.first;
}

PersonalizationLanguageOption? _selectDebugLanguage(
  PersonalizationConfigSnapshot snapshot,
) {
  for (final option in snapshot.appLanguageOptions) {
    if (option.id == snapshot.selectedAppLanguageId || option.isSelected) {
      return option;
    }
  }
  return snapshot.appLanguageOptions.isEmpty
      ? null
      : snapshot.appLanguageOptions.first;
}

bool _isDurableMemoryKind(String kind) =>
    kind == 'user_preference' ||
    kind == 'durable_instruction' ||
    kind == 'project_fact';

String _memoryRecordTitleLine(OpenCrayMemoryDebugRecordSnapshot record) {
  final state = record.isExpired
      ? 'expired'
      : (record.status.isEmpty ? 'unknown' : record.status);
  return '${record.id} · ${record.kind.isEmpty ? 'kind unavailable' : record.kind} · $state';
}

String _memoryRecordSummaryLine(OpenCrayMemoryDebugRecordSnapshot record) {
  final parts = <String>[];
  if (record.scope.isNotEmpty) {
    parts.add('Scope ${record.scope}');
  }
  if (record.preferenceValue.isNotEmpty) {
    parts.add('Preference ${record.preferenceValue}');
  }
  if (record.updatedAtEpochMs > 0) {
    parts.add('Updated ${_formatDebugClockTime(record.updatedAtEpochMs)}');
  }
  if (record.sourceSessionId.isNotEmpty) {
    parts.add('Session ${record.sourceSessionId}');
  }
  if (parts.isEmpty) {
    parts.add(_truncateDebugText(record.content, 90));
  }
  return parts.join(' · ');
}

String _formatMemoryTtl(int ttlMs) {
  if (ttlMs < 60 * 1000) {
    return '${(ttlMs / 1000).round()}s';
  }
  if (ttlMs < 60 * 60 * 1000) {
    return '${(ttlMs / (60 * 1000)).round()}m';
  }
  if (ttlMs < 24 * 60 * 60 * 1000) {
    return '${(ttlMs / (60 * 60 * 1000)).round()}h';
  }
  return '${(ttlMs / (24 * 60 * 60 * 1000)).round()}d';
}

String _formatMemoryLineRange(int startLine, int endLine) {
  if (startLine <= 0 || endLine <= 0) {
    return 'unknown';
  }
  return startLine == endLine ? '$startLine' : '$startLine-$endLine';
}

List<Widget> _buildSoulProfileLines(OpenCraySoulProfileDebugSnapshot snapshot) {
  final lines = <Widget>[];

  void addLine(String label, String value) {
    if (value.trim().isEmpty) {
      return;
    }
    lines.add(_DebugKeyValueLine(label, value));
  }

  addLine('Preset', snapshot.presetName);
  addLine('Display name', snapshot.displayName);
  addLine('Voice', snapshot.voice);
  addLine('Preferred naming', snapshot.preferredNaming);
  addLine('Preferred address style', snapshot.preferredAddressStyle);
  addLine('Warmth offset', snapshot.warmthPreferenceOffset);
  addLine('Formality offset', snapshot.formalityPreferenceOffset);
  addLine('Initiative offset', snapshot.initiativePreferenceOffset);
  addLine('Playfulness offset', snapshot.playfulnessPreferenceOffset);
  addLine('Reassurance offset', snapshot.reassurancePreferenceOffset);
  addLine('Intimacy band', snapshot.intimacyPermissionBand);
  addLine('Playfulness band', snapshot.playfulnessPermissionBand);
  addLine('Supportive reassurance', snapshot.supportiveReassuranceAllowed);
  addLine(
    'Proactive relational check-in',
    snapshot.proactiveRelationalCheckInAllowed,
  );
  addLine('Light playfulness', snapshot.lightPlayfulnessAllowed);
  addLine('Playful teasing', snapshot.playfulTeasingAllowed);
  addLine('High intimacy allowed', snapshot.highIntimacyBehaviorAllowed);
  addLine('Playful affection allowed', snapshot.playfulAffectionAllowed);
  addLine('Tone', snapshot.tone);
  addLine('Verbosity', snapshot.verbosity);
  addLine('Relationship', snapshot.userRelationshipStyle);
  addLine('Risk tolerance', snapshot.riskTolerance);
  addLine('Tool use bias', snapshot.toolUseBias);
  if (snapshot.customGuidance.isNotEmpty) {
    addLine(
      'Custom guidance',
      _truncateDebugText(snapshot.customGuidance, 140),
    );
  }
  if (snapshot.escalationRules.isNotEmpty) {
    addLine('Escalation rules', snapshot.escalationRules.join(' | '));
  }
  if (snapshot.forbiddenBehaviors.isNotEmpty) {
    addLine('Forbidden', snapshot.forbiddenBehaviors.join(' | '));
  }
  if (snapshot.collaborationPreferences.isNotEmpty) {
    addLine('Collaboration', snapshot.collaborationPreferences.join(' | '));
  }
  if (snapshot.extensions.isNotEmpty) {
    addLine(
      'Extensions',
      snapshot.extensions.entries
          .map((entry) => '${entry.key}=${entry.value}')
          .join(' | '),
    );
  }
  if (lines.isEmpty) {
    lines.add(
      const Text(
        'No resolved fields are populated.',
        style: _SettingsTextStyles.body,
      ),
    );
  }
  return lines;
}

List<Widget> _buildInteractionPreferenceDebugLines(
  OpenCrayInteractionPreferenceDebugSnapshot snapshot,
) {
  final lines = <Widget>[_DebugKeyValueLine('Scope', snapshot.scope)];
  if (snapshot.snapshotRecordId.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Snapshot record', snapshot.snapshotRecordId));
  }
  if (snapshot.preferredNaming.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Preferred naming', snapshot.preferredNaming));
  }
  if (snapshot.preferredAddressStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Preferred address style',
        snapshot.preferredAddressStyle,
      ),
    );
  }
  if (snapshot.derivedRelationshipStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Derived relationship style',
        snapshot.derivedRelationshipStyle,
      ),
    );
  }
  if (snapshot.state.preferredNamingSupport > 0) {
    lines.add(
      _DebugKeyValueLine(
        'Preferred naming support',
        '${snapshot.state.preferredNamingSupport}',
      ),
    );
  }
  lines.add(
    _DebugKeyValueLine(
      'Warmth axis',
      _formatPreferenceAxisSummary(snapshot.state.warmth),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Formality axis',
      _formatPreferenceAxisSummary(snapshot.state.formality),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Initiative axis',
      _formatPreferenceAxisSummary(snapshot.state.initiative),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playfulness axis',
      _formatPreferenceAxisSummary(snapshot.state.playfulness),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Reassurance axis',
      _formatPreferenceAxisSummary(snapshot.state.reassurance),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Address style state',
      _formatPreferredAddressStateSummary(snapshot.state.addressStyle),
    ),
  );
  if (snapshot.state.lastUpdatedAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Preference updated',
        _formatDebugClockTime(snapshot.state.lastUpdatedAtEpochMs!),
      ),
    );
  }
  return lines;
}

List<Widget> _buildRelationshipStateDebugLines(
  OpenCrayRelationshipStateDebugSnapshot snapshot,
) {
  final lines = <Widget>[_DebugKeyValueLine('Scope', snapshot.scope)];
  if (snapshot.snapshotRecordId.isNotEmpty) {
    lines.add(_DebugKeyValueLine('Snapshot record', snapshot.snapshotRecordId));
  }
  if (snapshot.appliedEventRecordIds.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine(
        'Applied events',
        snapshot.appliedEventRecordIds.join(', '),
      ),
    );
  }
  lines.add(
    _DebugKeyValueLine(
      'Scores',
      'familiarity ${snapshot.state.familiarity}, trust ${snapshot.state.trust}, safety ${snapshot.state.safety}, intimacy ${snapshot.state.intimacyPermission}, playfulness ${snapshot.state.playfulnessPermission}, affection ${snapshot.state.affectionTendency}, reciprocity ${snapshot.state.reciprocity}',
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Bands',
      'intimacy ${snapshot.intimacyPermissionBand}, playfulness ${snapshot.playfulnessPermissionBand}',
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Recent negative guard',
      snapshot.recentNegativeGuardActive ? 'active' : 'inactive',
    ),
  );
  if (snapshot.state.lastPositiveEventAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Last positive event',
        _formatDebugClockTime(snapshot.state.lastPositiveEventAtEpochMs!),
      ),
    );
  }
  if (snapshot.state.lastNegativeEventAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'Last negative event',
        _formatDebugClockTime(snapshot.state.lastNegativeEventAtEpochMs!),
      ),
    );
  }
  if (snapshot.state.lastUpdatedAtEpochMs != null) {
    lines.add(
      _DebugKeyValueLine(
        'State updated',
        _formatDebugClockTime(snapshot.state.lastUpdatedAtEpochMs!),
      ),
    );
  }
  if (snapshot.derivedAddressStyle.isNotEmpty) {
    lines.add(
      _DebugKeyValueLine('Derived address style', snapshot.derivedAddressStyle),
    );
  } else {
    lines.add(const _DebugKeyValueLine('Derived address style', 'none'));
  }
  lines.add(
    _DebugKeyValueLine(
      'Supportive style unlock',
      _formatGateVerdict(snapshot.supportiveStyleUnlocked),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive checks',
      _formatSoulGateChecks(snapshot.supportiveStyleChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Warm tone unlock',
      _formatGateVerdict(snapshot.warmToneUnlocked),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Warm tone checks',
      _formatSoulGateChecks(snapshot.warmToneChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Friendly address checks',
      _formatSoulGateChecks(snapshot.friendlyAddressChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Intimate address checks',
      _formatSoulGateChecks(snapshot.intimateAddressChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'High intimacy behavior',
      _formatGateVerdict(snapshot.highIntimacyBehaviorAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'High intimacy checks',
      _formatSoulGateChecks(snapshot.highIntimacyChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful affection',
      _formatGateVerdict(snapshot.playfulAffectionAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful affection checks',
      _formatSoulGateChecks(snapshot.playfulAffectionChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive reassurance',
      _formatGateVerdict(snapshot.supportiveReassuranceAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Supportive reassurance checks',
      _formatSoulGateChecks(snapshot.supportiveReassuranceChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Proactive relational check-in',
      _formatGateVerdict(snapshot.proactiveRelationalCheckInAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Proactive check-in checks',
      _formatSoulGateChecks(snapshot.proactiveRelationalCheckInChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Light playfulness',
      _formatGateVerdict(snapshot.lightPlayfulnessAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Light playfulness checks',
      _formatSoulGateChecks(snapshot.lightPlayfulnessChecks),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful teasing',
      _formatGateVerdict(snapshot.playfulTeasingAllowed),
    ),
  );
  lines.add(
    _DebugKeyValueLine(
      'Playful teasing checks',
      _formatSoulGateChecks(snapshot.playfulTeasingChecks),
    ),
  );
  return lines;
}

String _formatPreferenceAxisSummary(
  OpenCrayPreferenceAxisStateSnapshot snapshot,
) {
  return 'offset ${snapshot.offset}, higher ${snapshot.higherSupport}, lower ${snapshot.lowerSupport}';
}

String _formatPreferredAddressStateSummary(
  OpenCrayPreferredAddressStateSnapshot snapshot,
) {
  return '${snapshot.selectedStyle} | neutral ${snapshot.neutralSupport}, friendly ${snapshot.friendlySupport}, intimate ${snapshot.intimateSupport}';
}

String _formatSoulGateChecks(List<OpenCraySoulGateCheckSnapshot> checks) {
  if (checks.isEmpty) {
    return 'none';
  }
  return checks.map(_formatSoulGateCheck).join(' | ');
}

String _formatSoulGateCheck(OpenCraySoulGateCheckSnapshot check) {
  final verdict = check.passed ? 'pass' : 'fail';
  if (check.currentValue != null && check.threshold != null) {
    return '${check.key} ${check.currentValue}/${check.threshold} $verdict';
  }
  if (check.actualBoolean != null) {
    final actual = check.actualBoolean! ? 'true' : 'false';
    final expected = check.expectedBoolean == null
        ? ''
        : '/${check.expectedBoolean! ? 'true' : 'false'}';
    return '${check.key} $actual$expected $verdict';
  }
  return '${check.key} $verdict';
}

String _formatGateVerdict(bool allowed) => allowed ? 'allowed' : 'blocked';

List<OpenCraySoulFieldSourceSnapshot> _resolvedSoulFieldSources(
  OpenCraySoulDebugSnapshot snapshot,
) {
  if (snapshot.fieldSources.isNotEmpty) {
    return snapshot.fieldSources;
  }
  final effectiveSoul = snapshot.effectiveSoul;
  if (effectiveSoul == null) {
    return const <OpenCraySoulFieldSourceSnapshot>[];
  }
  final interactionPreferenceDebug = snapshot.interactionPreferenceDebug;
  final relationshipStateDebug = snapshot.relationshipStateDebug;
  final fallback = <OpenCraySoulFieldSourceSnapshot>[];

  String fallbackSourceType() {
    return snapshot.overlayRecords.isEmpty ? 'stored_soul' : 'memory_overlay';
  }

  String fallbackSourceLabel() {
    return snapshot.overlayRecords.isEmpty ? 'stored soul' : 'memory overlay';
  }

  String interactionPreferenceSourceLabel() {
    switch (interactionPreferenceDebug?.scope) {
      case 'workspace':
        return 'workspace interaction preference';
      case 'session':
        return 'session interaction preference';
      case 'user':
        return 'user interaction preference';
      default:
        return 'interaction preference';
    }
  }

  String relationshipStateSourceLabel() {
    switch (relationshipStateDebug?.scope) {
      case 'workspace':
        return 'workspace relationship state';
      case 'session':
        return 'session relationship state';
      case 'user':
        return 'user relationship state';
      default:
        return 'relationship state';
    }
  }

  void addSource({
    required String field,
    required String value,
    required String sourceType,
    required String sourceLabel,
    String recordId = '',
    String sourceScope = '',
    String sourceDetail = '',
  }) {
    final normalizedValue = value.trim();
    if (normalizedValue.isEmpty) {
      return;
    }
    fallback.add(
      OpenCraySoulFieldSourceSnapshot(
        field: field,
        value: normalizedValue,
        sourceType: sourceType,
        sourceLabel: sourceLabel,
        recordId: recordId,
        sourceScope: sourceScope,
        sourceDetail: sourceDetail,
      ),
    );
  }

  addSource(
    field: 'presetName',
    value: effectiveSoul.presetName,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul preset',
  );
  addSource(
    field: 'displayName',
    value: effectiveSoul.displayName,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'voice',
    value: effectiveSoul.voice,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'preferredNaming',
    value: effectiveSoul.preferredNaming,
    sourceType:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? 'interaction_preference'
        : fallbackSourceType(),
    sourceLabel:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceSourceLabel()
        : fallbackSourceLabel(),
    recordId:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceDebug?.snapshotRecordId ?? ''
        : '',
    sourceScope:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? interactionPreferenceDebug?.scope ?? ''
        : '',
    sourceDetail:
        interactionPreferenceDebug?.preferredNaming.trim().isNotEmpty == true
        ? 'Projected interaction-preference snapshot'
        : '',
  );
  addSource(
    field: 'preferredAddressStyle',
    value: effectiveSoul.preferredAddressStyle,
    sourceType:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? 'interaction_preference'
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? 'relationship_state'
              : fallbackSourceType()),
    sourceLabel:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceSourceLabel()
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateSourceLabel()
              : fallbackSourceLabel()),
    recordId:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceDebug?.snapshotRecordId ?? ''
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateDebug?.snapshotRecordId ?? ''
              : ''),
    sourceScope:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? interactionPreferenceDebug?.scope ?? ''
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? relationshipStateDebug?.scope ?? ''
              : ''),
    sourceDetail:
        interactionPreferenceDebug?.preferredAddressStyle.trim().isNotEmpty ==
            true
        ? 'Projected interaction-preference snapshot'
        : (relationshipStateDebug?.derivedAddressStyle.trim().isNotEmpty == true
              ? 'Derived from relationship gates'
              : ''),
  );
  addSource(
    field: 'warmthPreferenceOffset',
    value: effectiveSoul.warmthPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'formalityPreferenceOffset',
    value: effectiveSoul.formalityPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'initiativePreferenceOffset',
    value: effectiveSoul.initiativePreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'playfulnessPreferenceOffset',
    value: effectiveSoul.playfulnessPreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'reassurancePreferenceOffset',
    value: effectiveSoul.reassurancePreferenceOffset,
    sourceType: 'interaction_preference',
    sourceLabel: interactionPreferenceSourceLabel(),
    recordId: interactionPreferenceDebug?.snapshotRecordId ?? '',
    sourceScope: interactionPreferenceDebug?.scope ?? '',
    sourceDetail: 'Projected interaction-preference snapshot',
  );
  addSource(
    field: 'intimacyPermissionBand',
    value: effectiveSoul.intimacyPermissionBand,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail: 'Derived from relationship-state score band',
  );
  addSource(
    field: 'playfulnessPermissionBand',
    value: effectiveSoul.playfulnessPermissionBand,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail: 'Derived from relationship-state score band',
  );
  addSource(
    field: 'supportiveReassuranceAllowed',
    value: effectiveSoul.supportiveReassuranceAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by reassurance preference',
  );
  addSource(
    field: 'proactiveRelationalCheckInAllowed',
    value: effectiveSoul.proactiveRelationalCheckInAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by initiative preference',
  );
  addSource(
    field: 'lightPlayfulnessAllowed',
    value: effectiveSoul.lightPlayfulnessAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by playfulness preference',
  );
  addSource(
    field: 'playfulTeasingAllowed',
    value: effectiveSoul.playfulTeasingAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from relationship state and constrained by playfulness preference',
  );
  addSource(
    field: 'highIntimacyBehaviorAllowed',
    value: effectiveSoul.highIntimacyBehaviorAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from trust, safety, reciprocity, and intimacy',
  );
  addSource(
    field: 'playfulAffectionAllowed',
    value: effectiveSoul.playfulAffectionAllowed,
    sourceType: 'relationship_state',
    sourceLabel: relationshipStateSourceLabel(),
    recordId: relationshipStateDebug?.snapshotRecordId ?? '',
    sourceScope: relationshipStateDebug?.scope ?? '',
    sourceDetail:
        'Relationship gate derived from playfulness, safety, and reciprocity',
  );
  addSource(
    field: 'customGuidance',
    value: effectiveSoul.customGuidance,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  addSource(
    field: 'tone',
    value: effectiveSoul.tone,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'verbosity',
    value: effectiveSoul.verbosity,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'userRelationshipStyle',
    value: effectiveSoul.userRelationshipStyle,
    sourceType: fallbackSourceType(),
    sourceLabel: fallbackSourceLabel(),
  );
  addSource(
    field: 'riskTolerance',
    value: effectiveSoul.riskTolerance,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  addSource(
    field: 'toolUseBias',
    value: effectiveSoul.toolUseBias,
    sourceType: 'stored_soul',
    sourceLabel: 'stored soul',
  );
  if (effectiveSoul.escalationRules.isNotEmpty) {
    addSource(
      field: 'escalationRules',
      value: effectiveSoul.escalationRules.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  if (effectiveSoul.forbiddenBehaviors.isNotEmpty) {
    addSource(
      field: 'forbiddenBehaviors',
      value: effectiveSoul.forbiddenBehaviors.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  if (effectiveSoul.collaborationPreferences.isNotEmpty) {
    addSource(
      field: 'collaborationPreferences',
      value: effectiveSoul.collaborationPreferences.join(' | '),
      sourceType: 'stored_soul',
      sourceLabel: 'stored soul',
    );
  }
  return fallback;
}

String _debugSoulFieldLabel(String field) {
  switch (field) {
    case 'presetName':
      return 'preset';
    case 'displayName':
      return 'display name';
    case 'voice':
      return 'voice';
    case 'preferredNaming':
      return 'preferred naming';
    case 'preferredAddressStyle':
      return 'preferred address style';
    case 'warmthPreferenceOffset':
      return 'warmth offset';
    case 'formalityPreferenceOffset':
      return 'formality offset';
    case 'initiativePreferenceOffset':
      return 'initiative offset';
    case 'playfulnessPreferenceOffset':
      return 'playfulness offset';
    case 'reassurancePreferenceOffset':
      return 'reassurance offset';
    case 'intimacyPermissionBand':
      return 'intimacy band';
    case 'playfulnessPermissionBand':
      return 'playfulness band';
    case 'supportiveReassuranceAllowed':
      return 'supportive reassurance allowed';
    case 'proactiveRelationalCheckInAllowed':
      return 'proactive check-in allowed';
    case 'lightPlayfulnessAllowed':
      return 'light playfulness allowed';
    case 'playfulTeasingAllowed':
      return 'playful teasing allowed';
    case 'highIntimacyBehaviorAllowed':
      return 'high intimacy allowed';
    case 'playfulAffectionAllowed':
      return 'playful affection allowed';
    case 'customGuidance':
      return 'custom guidance';
    case 'tone':
      return 'tone';
    case 'verbosity':
      return 'verbosity';
    case 'userRelationshipStyle':
      return 'relationship';
    case 'riskTolerance':
      return 'risk tolerance';
    case 'toolUseBias':
      return 'tool use bias';
    case 'escalationRules':
      return 'escalation rules';
    case 'forbiddenBehaviors':
      return 'forbidden behaviors';
    case 'collaborationPreferences':
      return 'collaboration preferences';
    default:
      return field;
  }
}

String _formatSoulFieldSourceSummary(OpenCraySoulFieldSourceSnapshot source) {
  final sourcePrefix = source.sourceLabel.isEmpty
      ? source.sourceType
      : source.sourceLabel;
  final scopeSuffix = source.sourceScope.isEmpty
      ? ''
      : ' · ${source.sourceScope}';
  final detailSuffix = source.sourceDetail.isEmpty
      ? ''
      : ' · ${source.sourceDetail}';
  return '$sourcePrefix$scopeSuffix$detailSuffix: ${source.value}';
}

String _formatDebugDuration(int deltaMs) {
  if (deltaMs <= 0) {
    return '0s';
  }
  final seconds = deltaMs / 1000;
  if (seconds >= 10) {
    return '${seconds.toStringAsFixed(0)}s';
  }
  return '${seconds.toStringAsFixed(1)}s';
}

String _formatDebugClockTime(int epochMs) {
  if (epochMs <= 0) {
    return 'n/a';
  }
  final time = DateTime.fromMillisecondsSinceEpoch(epochMs);
  final hour = time.hour.toString().padLeft(2, '0');
  final minute = time.minute.toString().padLeft(2, '0');
  final second = time.second.toString().padLeft(2, '0');
  return '$hour:$minute:$second';
}

String _truncateDebugText(String value, int maxChars) {
  if (value.length <= maxChars) {
    return value;
  }
  return '${value.substring(0, maxChars - 3)}...';
}

String _summarizeRuntimeEvent(OpenCrayChatRuntimeEventSnapshot event) {
  switch (event.kind) {
    case 'memory_write':
      return 'written ${event.writtenRecordIds.length} · resolved ${event.resolvedRecordIds.length} · suppressed ${event.suppressedRecordIds.length} · reaffirmed ${event.reaffirmedRecordIds.length} · expired ${event.expiredRecordIds.length}';
    case 'memory_retrieval':
      final parts = <String>[];
      if (event.operation?.trim().isNotEmpty == true) {
        parts.add(event.operation!.trim());
      }
      if (event.queryTerms.isNotEmpty) {
        parts.add('terms ${event.queryTerms.join(', ')}');
      }
      if (event.resultCount != null) {
        parts.add('results ${event.resultCount}');
      }
      return parts.isEmpty ? 'memory retrieval' : parts.join(' · ');
    case 'memory_flush':
      return event.writtenRecordIds.isEmpty
          ? 'no durable writes'
          : 'wrote ${event.writtenRecordIds.length} durable records';
    default:
      final parts = <String>[];
      if (event.status?.trim().isNotEmpty == true) {
        parts.add(event.status!.trim());
      }
      if (event.phase?.trim().isNotEmpty == true) {
        parts.add(event.phase!.trim());
      }
      if (event.text?.trim().isNotEmpty == true) {
        parts.add(_truncateDebugText(event.text!.trim(), 90));
      }
      if (event.errorCode?.trim().isNotEmpty == true) {
        parts.add(event.errorCode!.trim());
      }
      return parts.isEmpty ? 'No additional payload.' : parts.join(' · ');
  }
}
