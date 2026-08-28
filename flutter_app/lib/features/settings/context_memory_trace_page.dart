part of 'settings_feature.dart';

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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Context & Memory Trace',
                summary: 'Inspect one run end to end.',
              ),
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
                  backgroundColor: OpenCrayColors.dangerTint,
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
                if (_runtimeSnapshot != null) ...[
                  const SizedBox(height: 16),
                  _buildSubAgentCard(_runtimeSnapshot!),
                ],
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
    final attemptReasonSummary = run.attempt > 1
        ? _runAttemptReasonSummary(run)
        : null;
    final attemptReasonCode = run.attempt > 1
        ? _runAttemptReasonCode(run)
        : null;
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
          _DebugKeyValueLine(
            'Execution',
            run.executionOrdinal > 0 ? '${run.executionOrdinal}' : 'n/a',
          ),
          if (run.executionKind?.trim().isNotEmpty == true)
            _DebugKeyValueLine('Execution kind', run.executionKind!.trim()),
          if (run.pendingExecutionKind?.trim().isNotEmpty == true)
            _DebugKeyValueLine(
              'Pending execution',
              run.pendingExecutionKind!.trim(),
            ),
          if (attemptReasonSummary != null)
            _DebugKeyValueLine('Retry reason', attemptReasonSummary),
          if (attemptReasonCode != null)
            _DebugKeyValueLine('Retry code', attemptReasonCode),
          if (run.errorCode?.isNotEmpty == true)
            _DebugKeyValueLine(
              'Error',
              '${run.errorCode} ${run.errorMessage ?? ''}'.trim(),
            ),
        ],
      ),
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
            'Reads runtimeActivity.subAgents directly so detached child state stays visible after the parent run leaves the recent run list.',
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
              if (_formatSubAgentContextModeSource(subAgents[index]) != null)
                _DebugKeyValueLine(
                  'Context mode source',
                  _formatSubAgentContextModeSource(subAgents[index])!,
                ),
              if (_formatSubAgentLiveContext(subAgents[index]) != null)
                _DebugKeyValueLine(
                  'Live context',
                  _formatSubAgentLiveContext(subAgents[index])!,
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

  Widget _buildMemoryWritesCard(OpenCrayChatRunSnapshot run) {
    final memoryWriteEvent = _latestEventOfKind(run, 'memory_write');
    final memoryFlushEvent = _latestEventOfKind(run, 'memory_flush');
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

  String? _formatSubAgentLiveContext(OpenCrayChatSubAgentSnapshot subAgent) {
    final Map<String, Object?>? liveContext = subAgent.liveContext;
    if (liveContext == null || liveContext.isEmpty) {
      return null;
    }
    final List<String> parts = <String>[
      for (final MapEntry<String, Object?> entry in liveContext.entries)
        '${entry.key}=${entry.value}',
    ];
    return parts.isEmpty ? null : parts.join(' / ');
  }

  String? _formatSubAgentContextModeSource(
    OpenCrayChatSubAgentSnapshot subAgent,
  ) {
    final String source = subAgent.contextModeSource?.trim() ?? '';
    return source.isEmpty ? null : source;
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
    final llmDiagnostics = run.llmDiagnostics;
    final liveContext = run.liveContext;
    final contextBudget = run.contextBudget;
    final bootstrap = run.bootstrap;
    final stickyMemory = run.stickyMemory;
    final memoryFlush = run.memoryFlush;
    final durableCompaction = run.durableCompaction;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Context setup', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (llmDiagnostics == null &&
              liveContext == null &&
              contextBudget == null &&
              bootstrap == null &&
              stickyMemory == null &&
              memoryFlush == null &&
              durableCompaction == null)
            const Text(
              'No LLM diagnostics, live-context, context-budget, bootstrap, sticky memory, memory flush, or durable compaction trace was captured for this run.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            if (llmDiagnostics != null) ...[
              const Text(
                'LLM diagnostics',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Response shape',
                    value:
                        llmDiagnostics.providerResponseShape
                                ?.trim()
                                .isNotEmpty ==
                            true
                        ? llmDiagnostics.providerResponseShape!.trim()
                        : 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Cache break',
                    value:
                        llmDiagnostics.contextCacheBreakReason
                                ?.trim()
                                .isNotEmpty ==
                            true
                        ? llmDiagnostics.contextCacheBreakReason!.trim()
                        : 'clear',
                  ),
                ],
              ),
              if (llmDiagnostics.nativeToolCallRequested != null ||
                  llmDiagnostics.nativeToolCallObserved != null ||
                  llmDiagnostics.parsedToolCallObserved != null)
                _DebugKeyValueLine(
                  'Tool path',
                  'requested ${llmDiagnostics.nativeToolCallRequested == true ? 'yes' : 'no'}, observed ${llmDiagnostics.nativeToolCallObserved == true ? 'yes' : 'no'}, parsed ${llmDiagnostics.parsedToolCallObserved == true ? 'yes' : 'no'}',
                ),
              if (llmDiagnostics.fallbackParserAttempted != null ||
                  llmDiagnostics.fallbackParserSucceeded != null)
                _DebugKeyValueLine(
                  'Fallback parser',
                  'attempted ${llmDiagnostics.fallbackParserAttempted == true ? 'yes' : 'no'}, succeeded ${llmDiagnostics.fallbackParserSucceeded == true ? 'yes' : 'no'}',
                ),
              if ((llmDiagnostics.responsesContinuationRecoveryCount ?? 0) >
                      0 ||
                  llmDiagnostics.responsesContinuationRecoveryLastReason
                          ?.trim()
                          .isNotEmpty ==
                      true)
                _DebugKeyValueLine(
                  'Responses recovery',
                  '${llmDiagnostics.responsesContinuationRecoveryCount ?? 0} (${llmDiagnostics.responsesContinuationRecoveryLastReason?.trim().isNotEmpty == true ? llmDiagnostics.responsesContinuationRecoveryLastReason!.trim() : 'no_reason'})',
                ),
              if ((llmDiagnostics.localContinuationUsedCount ?? 0) > 0 ||
                  (llmDiagnostics.localContinuationFallbackCount ?? 0) > 0 ||
                  llmDiagnostics.localContinuationLastMode?.trim().isNotEmpty ==
                      true ||
                  llmDiagnostics.localContinuationLastReason
                          ?.trim()
                          .isNotEmpty ==
                      true)
                _DebugKeyValueLine(
                  'Local continuation',
                  'used ${llmDiagnostics.localContinuationUsedCount ?? 0}, fallback ${llmDiagnostics.localContinuationFallbackCount ?? 0}, mode ${llmDiagnostics.localContinuationLastMode?.trim().isNotEmpty == true ? llmDiagnostics.localContinuationLastMode!.trim() : 'unknown'}, reason ${llmDiagnostics.localContinuationLastReason?.trim().isNotEmpty == true ? llmDiagnostics.localContinuationLastReason!.trim() : 'none'}',
                ),
              if ((llmDiagnostics.responsesPendingContextUpdateCount ?? 0) >
                      0 ||
                  llmDiagnostics.responsesPendingContextUpdateHash
                          ?.trim()
                          .isNotEmpty ==
                      true)
                _DebugKeyValueLine(
                  'Responses context updates',
                  'pending ${llmDiagnostics.responsesPendingContextUpdateCount ?? 0}, hash ${llmDiagnostics.responsesPendingContextUpdateHash?.trim().isNotEmpty == true ? llmDiagnostics.responsesPendingContextUpdateHash!.trim() : 'none'}',
                ),
              if (llmDiagnostics.toolCallEventEmitted != null ||
                  llmDiagnostics.toolResultEventEmitted != null)
                _DebugKeyValueLine(
                  'Tool events',
                  'call ${llmDiagnostics.toolCallEventEmitted == true ? 'yes' : 'no'}, result ${llmDiagnostics.toolResultEventEmitted == true ? 'yes' : 'no'}',
                ),
              if (llmDiagnostics.lastSuccessfulToolName?.trim().isNotEmpty ==
                  true)
                _DebugKeyValueLine(
                  'Last successful tool',
                  llmDiagnostics.lastSuccessfulToolName!.trim(),
                ),
            ],
            if (liveContext != null) ...[
              if (llmDiagnostics != null) const SizedBox(height: 16),
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
            if (contextBudget != null) ...[
              if (liveContext != null) const SizedBox(height: 16),
              const Text(
                'Context budget',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Preset',
                    value: _formatContextBudgetPreset(contextBudget),
                  ),
                  _DebugValueChip(
                    label: 'Pressure',
                    value: contextBudget.pressureMode?.trim().isNotEmpty == true
                        ? contextBudget.pressureMode!.trim().toLowerCase()
                        : 'unknown',
                  ),
                  _DebugValueChip(
                    label: 'Layers',
                    value: _formatContextBudgetLayerCounts(contextBudget),
                  ),
                  _DebugValueChip(
                    label: 'Overflow',
                    value: contextBudget.unresolvedOverflow == true
                        ? 'unresolved'
                        : 'clear',
                  ),
                  if (contextBudget.sourcePreset?.trim().isNotEmpty == true)
                    _DebugValueChip(
                      label: 'Source caps',
                      value: contextBudget.sourcePreset!.trim(),
                    ),
                ],
              ),
              _DebugKeyValueLine(
                'Applied',
                contextBudget.applied == true ? 'yes' : 'no',
              ),
              if ((contextBudget.contextWindowTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Window tokens',
                  '${contextBudget.contextWindowTokens}',
                ),
              if ((contextBudget.hardInputTokens ?? 0) > 0 ||
                  (contextBudget.targetInputTokens ?? 0) > 0 ||
                  (contextBudget.emergencyInputTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Input envelope',
                  'hard ${contextBudget.hardInputTokens ?? 0}, target ${contextBudget.targetInputTokens ?? 0}, emergency ${contextBudget.emergencyInputTokens ?? 0}',
                ),
              if ((contextBudget.reservedOutputTokens ?? 0) > 0 ||
                  (contextBudget.safetyMarginTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Reserve',
                  'output ${contextBudget.reservedOutputTokens ?? 0}, margin ${contextBudget.safetyMarginTokens ?? 0}',
                ),
              if (_hasContextBudgetSourceCapDetails(contextBudget))
                _DebugKeyValueLine(
                  'Source cap profile',
                  _formatContextBudgetSourceProfile(contextBudget),
                ),
              if ((contextBudget.sourceTranscriptMaxMessages ?? 0) > 0 ||
                  (contextBudget.sourceInjectedMemoryMaxRecords ?? 0) > 0 ||
                  (contextBudget.sourceMemoryRecallMaxRecords ?? 0) > 0)
                _DebugKeyValueLine(
                  'Source caps',
                  'transcript ${contextBudget.sourceTranscriptMaxMessages ?? 0}, injected memory ${contextBudget.sourceInjectedMemoryMaxRecords ?? 0}, recall ${contextBudget.sourceMemoryRecallMaxRecords ?? 0}',
                ),
              if ((contextBudget.sourceBootstrapMaxChars ?? 0) > 0 ||
                  (contextBudget.sourceSkillInventoryMaxSkills ?? 0) > 0 ||
                  (contextBudget.sourceActiveSkillMaxChars ?? 0) > 0 ||
                  (contextBudget.sourceRecentObservationMaxEntries ?? 0) > 0 ||
                  (contextBudget.sourceMemoryFlushMaxToolObservations ?? 0) > 0)
                _DebugKeyValueLine(
                  'Source auxiliaries',
                  'bootstrap ${contextBudget.sourceBootstrapMaxChars ?? 0} chars, skills ${contextBudget.sourceSkillInventoryMaxSkills ?? 0}, active skill ${contextBudget.sourceActiveSkillMaxChars ?? 0} chars, observations ${contextBudget.sourceRecentObservationMaxEntries ?? 0}, flush tools ${contextBudget.sourceMemoryFlushMaxToolObservations ?? 0}',
                ),
              if (contextBudget.reducedLayerNames.isNotEmpty)
                _DebugKeyValueLine(
                  'Reduced layers',
                  contextBudget.reducedLayerNames.join(', '),
                ),
              if (contextBudget.omittedLayerNames.isNotEmpty)
                _DebugKeyValueLine(
                  'Omitted layers',
                  contextBudget.omittedLayerNames.join(', '),
                ),
              if (contextBudget.layers.isNotEmpty) ...[
                const SizedBox(height: 8),
                const Text(
                  'Layer states',
                  style: _SettingsTextStyles.bodyStrong,
                ),
                const SizedBox(height: 8),
                for (final layer in contextBudget.layers)
                  _DebugKeyValueLine(
                    layer.name,
                    _formatContextBudgetLayerDetail(layer),
                  ),
              ] else if (contextBudget.layerSummary?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Layer summary',
                  _truncateDebugText(contextBudget.layerSummary!.trim(), 180),
                ),
            ],
            if (bootstrap != null) ...[
              if (liveContext != null || contextBudget != null)
                const SizedBox(height: 16),
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
              if (liveContext != null ||
                  contextBudget != null ||
                  bootstrap != null)
                const SizedBox(height: 16),
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
                  if (memoryFlush.tokenThresholdTriggered != null)
                    _DebugValueChip(
                      label: 'Token threshold',
                      value: memoryFlush.tokenThresholdTriggered == true
                          ? 'hit'
                          : 'clear',
                    ),
                  if (memoryFlush.smallerWindowModelSwitchDetected == true)
                    const _DebugValueChip(
                      label: 'Model switch',
                      value: 'smaller window',
                    ),
                ],
              ),
              if (memoryFlush.triggerStage?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Trigger stage',
                  memoryFlush.triggerStage!.trim(),
                ),
              if (memoryFlush.executionMode?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Execution mode',
                  memoryFlush.executionMode!.trim(),
                ),
              if ((memoryFlush.contextWindowTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Context window',
                  '${memoryFlush.contextWindowTokens}',
                ),
              if ((memoryFlush.previousContextWindowTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Previous context window',
                  '${memoryFlush.previousContextWindowTokens}',
                ),
              if ((memoryFlush.autoCompactTokenLimit ?? 0) > 0)
                _DebugKeyValueLine(
                  'Auto-compact threshold',
                  '${memoryFlush.autoCompactTokenLimit}',
                ),
              if ((memoryFlush.estimatedReplayTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Estimated replay tokens',
                  '${memoryFlush.estimatedReplayTokens}',
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
            if (stickyMemory != null) ...[
              if (liveContext != null ||
                  contextBudget != null ||
                  bootstrap != null ||
                  memoryFlush != null)
                const SizedBox(height: 16),
              const Text(
                'Sticky memory',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _DebugValueChip(
                    label: 'Injected',
                    value: '${stickyMemory.injectedRecordCount ?? 0}',
                  ),
                  _DebugValueChip(
                    label: 'Omitted',
                    value: '${stickyMemory.omittedRecordCount ?? 0}',
                  ),
                ],
              ),
              if (stickyMemory.recordIds.isNotEmpty)
                _DebugKeyValueLine(
                  'Pinned ids',
                  stickyMemory.recordIds.join(', '),
                ),
            ],
            if (durableCompaction != null) ...[
              if (liveContext != null ||
                  contextBudget != null ||
                  bootstrap != null ||
                  stickyMemory != null ||
                  memoryFlush != null)
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
                  if (durableCompaction.tokenThresholdTriggered != null)
                    _DebugValueChip(
                      label: 'Token threshold',
                      value: durableCompaction.tokenThresholdTriggered == true
                          ? 'hit'
                          : 'clear',
                    ),
                  if (durableCompaction.smallerWindowModelSwitchDetected == true)
                    const _DebugValueChip(
                      label: 'Model switch',
                      value: 'smaller window',
                    ),
                ],
              ),
              if (durableCompaction.triggerStage?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Trigger stage',
                  durableCompaction.triggerStage!.trim(),
                ),
              if (durableCompaction.executionMode?.trim().isNotEmpty == true)
                _DebugKeyValueLine(
                  'Execution mode',
                  durableCompaction.executionMode!.trim(),
                ),
              if (durableCompaction.remoteCompaction != null)
                _DebugKeyValueLine(
                  'Remote compaction',
                  _formatRemoteCompactionState(
                    durableCompaction.remoteCompaction!,
                  ),
                ),
              if (_formatRemoteCompactionDetails(
                    durableCompaction.remoteCompaction,
                  ) !=
                  null)
                _DebugKeyValueLine(
                  'Remote compaction details',
                  _formatRemoteCompactionDetails(
                    durableCompaction.remoteCompaction,
                  )!,
                ),
              if ((durableCompaction.contextWindowTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Context window',
                  '${durableCompaction.contextWindowTokens}',
                ),
              if ((durableCompaction.previousContextWindowTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Previous context window',
                  '${durableCompaction.previousContextWindowTokens}',
                ),
              if ((durableCompaction.autoCompactTokenLimit ?? 0) > 0)
                _DebugKeyValueLine(
                  'Auto-compact threshold',
                  '${durableCompaction.autoCompactTokenLimit}',
                ),
              if ((durableCompaction.estimatedReplayTokens ?? 0) > 0)
                _DebugKeyValueLine(
                  'Estimated replay tokens',
                  '${durableCompaction.estimatedReplayTokens}',
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

  String _formatRemoteCompactionState(
    OpenCrayChatRunRemoteCompactionSnapshot remote,
  ) {
    final parts = <String>[];
    if (remote.used != null) {
      parts.add(remote.used! ? 'used' : 'not used');
    }
    if (remote.supported != null) {
      parts.add(remote.supported! ? 'supported' : 'unsupported');
    }
    if (remote.requested != null) {
      parts.add(remote.requested! ? 'requested' : 'not requested');
    }
    final triggerStage = remote.triggerStage?.trim();
    if (triggerStage != null && triggerStage.isNotEmpty) {
      parts.add('trigger $triggerStage');
    }
    return parts.isEmpty ? 'present' : parts.join(', ');
  }

  String? _formatRemoteCompactionDetails(
    OpenCrayChatRunRemoteCompactionSnapshot? remote,
  ) {
    if (remote == null) {
      return null;
    }
    final parts = <String>[];
    if (remote.outputItemCount != null) {
      parts.add('output ${remote.outputItemCount}');
    }
    if (remote.compactionItemCount != null) {
      parts.add('compaction ${remote.compactionItemCount}');
    }
    if (remote.encryptedContentCount != null) {
      parts.add('encrypted ${remote.encryptedContentCount}');
    }
    final fallbackReason = remote.fallbackReason?.trim();
    if (fallbackReason != null && fallbackReason.isNotEmpty) {
      parts.add('fallback $fallbackReason');
    }
    return parts.isEmpty ? null : parts.join(', ');
  }

  String _formatContextBudgetPreset(
    OpenCrayChatRunContextBudgetSnapshot budget,
  ) {
    final effective = budget.effectivePreset?.trim();
    final selected = budget.selectedPreset?.trim();
    final source = budget.presetSource?.trim();
    if (effective != null &&
        effective.isNotEmpty &&
        selected != null &&
        selected.isNotEmpty &&
        effective != selected) {
      return '$effective (selected $selected)';
    }
    if (effective != null && effective.isNotEmpty) {
      return effective;
    }
    if (selected != null &&
        selected.isNotEmpty &&
        source != null &&
        source.isNotEmpty) {
      return '$selected ($source)';
    }
    if (selected != null && selected.isNotEmpty) {
      return selected;
    }
    return 'unknown';
  }

  String _formatContextBudgetLayerCounts(
    OpenCrayChatRunContextBudgetSnapshot budget,
  ) {
    final full = budget.fullLayerCount ?? 0;
    final compact = budget.compactLayerCount ?? 0;
    final minimal = budget.minimalLayerCount ?? 0;
    final omitted = budget.omittedLayerCount ?? 0;
    return '$full/$compact/$minimal/$omitted';
  }

  bool _hasContextBudgetSourceCapDetails(
    OpenCrayChatRunContextBudgetSnapshot budget,
  ) {
    return budget.sourcePreset?.trim().isNotEmpty == true ||
        (budget.sourceTranscriptMaxMessages ?? 0) > 0 ||
        (budget.sourceInjectedMemoryMaxRecords ?? 0) > 0 ||
        (budget.sourceMemoryRecallMaxRecords ?? 0) > 0 ||
        (budget.sourceBootstrapMaxChars ?? 0) > 0 ||
        (budget.sourceSkillInventoryMaxSkills ?? 0) > 0 ||
        (budget.sourceActiveSkillMaxChars ?? 0) > 0 ||
        (budget.sourceRecentObservationMaxEntries ?? 0) > 0 ||
        (budget.sourceMemoryFlushMaxToolObservations ?? 0) > 0;
  }

  String _formatContextBudgetSourceProfile(
    OpenCrayChatRunContextBudgetSnapshot budget,
  ) {
    final sourcePreset = budget.sourcePreset?.trim();
    if (sourcePreset != null &&
        sourcePreset.isNotEmpty &&
        budget.effectivePreset?.trim().isNotEmpty == true &&
        sourcePreset != budget.effectivePreset!.trim()) {
      return '$sourcePreset (stable fallback for ${budget.effectivePreset!.trim()} envelope)';
    }
    if (sourcePreset != null && sourcePreset.isNotEmpty) {
      return sourcePreset;
    }
    return 'unknown';
  }

  String _formatContextBudgetLayerDetail(
    OpenCrayChatRunContextBudgetLayerSnapshot layer,
  ) {
    final parts = <String>[
      layer.finalState?.trim().isNotEmpty == true
          ? layer.finalState!.trim()
          : 'unknown',
    ];
    final estimatedBefore = layer.estimatedTokensBefore;
    final estimatedAfter = layer.estimatedTokensAfter;
    if (estimatedBefore != null || estimatedAfter != null) {
      parts.add('${estimatedBefore ?? 0} -> ${estimatedAfter ?? 0} tokens');
    }
    final priority = _formatContextBudgetLayerPriority(layer.priorityClass);
    if (priority.isNotEmpty) {
      final retentionPriority = layer.retentionPriority;
      parts.add(
        retentionPriority == null ? priority : '$priority #$retentionPriority',
      );
    }
    if (layer.appliedOperators.isNotEmpty) {
      parts.add('ops ${layer.appliedOperators.join(', ')}');
    }
    return parts.join(', ');
  }

  String _formatContextBudgetLayerPriority(String? raw) {
    final normalized = raw?.trim();
    if (normalized == null || normalized.isEmpty) {
      return '';
    }
    return normalized.toLowerCase().replaceAll('_', ' ');
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
                  if (activeSkill.pinned != null)
                    _DebugValueChip(
                      label: 'Pinned',
                      value: activeSkill.pinned == true ? 'yes' : 'no',
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
    final events = _eventsForRun(run);
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
    OpenCrayChatRunSnapshot run,
    String kind,
  ) {
    final matching = _eventsForRun(
      run,
    ).where((event) => event.kind == kind).toList(growable: false);
    if (matching.isEmpty) {
      final lastEvent = _selectedRunSnapshot?.lastEvent;
      if (lastEvent != null &&
          run.matchesRuntimeEvent(lastEvent) &&
          lastEvent.kind == kind) {
        return lastEvent;
      }
      return null;
    }
    return matching.last;
  }

  List<OpenCrayChatRuntimeEventSnapshot> _eventsForRun(
    OpenCrayChatRunSnapshot run,
  ) {
    final snapshot = _runtimeSnapshot;
    if (snapshot == null) {
      return const <OpenCrayChatRuntimeEventSnapshot>[];
    }
    final events =
        run.scopeRuntimeEvents(snapshot.events).toList(growable: false)..sort(
          (left, right) =>
              left.emittedAtEpochMs.compareTo(right.emittedAtEpochMs),
        );
    return events;
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
