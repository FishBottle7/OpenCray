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
    final bootstrap = run.bootstrap;
    final memoryFlush = run.memoryFlush;
    final durableCompaction = run.durableCompaction;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Context setup', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (bootstrap == null &&
              memoryFlush == null &&
              durableCompaction == null)
            const Text(
              'No bootstrap, memory flush, or durable compaction trace was captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            if (bootstrap != null) ...[
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
              'Run-level soul attribution is not exposed yet.',
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
  String? _loadError;
  _MemoryInspectorFilter _activeFilter = _MemoryInspectorFilter.all;
  OpenCrayMemoryDebugSnapshot? _snapshot;
  OpenCrayMemoryDebugLinksSnapshot? _linksSnapshot;
  OpenCraySoulDebugSnapshot? _soulSnapshot;
  List<OpenCrayMemoryDebugRecordSnapshot> _records =
      const <OpenCrayMemoryDebugRecordSnapshot>[];
  String? _selectedRecordId;

  @override
  void initState() {
    super.initState();
    _refresh();
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
                              '${source.sourceLabel.isEmpty ? source.sourceType : source.sourceLabel}: ${source.value}',
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
          '${source.sourceLabel.isEmpty ? source.sourceType : source.sourceLabel}: ${source.value}',
        ),
      );
      if (source.preferenceKey.isNotEmpty) {
        widgets.add(_DebugKeyValueLine('Preference key', source.preferenceKey));
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
  final fallback = <OpenCraySoulFieldSourceSnapshot>[];
  void addSource({
    required String field,
    required String value,
    required String sourceType,
    required String sourceLabel,
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
    sourceType: snapshot.overlayRecords.isEmpty
        ? 'stored_soul'
        : 'memory_overlay',
    sourceLabel: snapshot.overlayRecords.isEmpty
        ? 'stored soul'
        : 'memory overlay',
  );
  addSource(
    field: 'voice',
    value: effectiveSoul.voice,
    sourceType: snapshot.overlayRecords.isEmpty
        ? 'stored_soul'
        : 'memory_overlay',
    sourceLabel: snapshot.overlayRecords.isEmpty
        ? 'stored soul'
        : 'memory overlay',
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
    sourceType: snapshot.overlayRecords.isEmpty
        ? 'stored_soul'
        : 'memory_overlay',
    sourceLabel: snapshot.overlayRecords.isEmpty
        ? 'stored soul'
        : 'memory overlay',
  );
  addSource(
    field: 'verbosity',
    value: effectiveSoul.verbosity,
    sourceType: snapshot.overlayRecords.isEmpty
        ? 'stored_soul'
        : 'memory_overlay',
    sourceLabel: snapshot.overlayRecords.isEmpty
        ? 'stored soul'
        : 'memory overlay',
  );
  addSource(
    field: 'userRelationshipStyle',
    value: effectiveSoul.userRelationshipStyle,
    sourceType: snapshot.overlayRecords.isEmpty
        ? 'stored_soul'
        : 'memory_overlay',
    sourceLabel: snapshot.overlayRecords.isEmpty
        ? 'stored soul'
        : 'memory overlay',
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
    case 'customGuidance':
      return 'custom guidance';
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
      return 'written ${event.writtenRecordIds.length} · resolved ${event.resolvedRecordIds.length} · reaffirmed ${event.reaffirmedRecordIds.length} · expired ${event.expiredRecordIds.length}';
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
