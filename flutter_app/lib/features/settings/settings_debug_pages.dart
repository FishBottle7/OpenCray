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
                'Inspect runtime ownership, trace, memory, soul state, and the embedded Python runner.',
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
                      'Host/service lifecycle, trace runs, memory, soul state, and embedded Python execution.',
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
                      title: 'Run Python Script',
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => _DebugPythonRunnerPage(
                              bridge: bridge,
                              backLabel: 'Debug tools',
                            ),
                          ),
                        );
                      },
                    ),
                    const Divider(height: 1, color: OpenCrayColors.divider),
                    _HomeEntryRow(
                      title: 'Runtime Diagnostics',
                      onTap: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => _RuntimeDiagnosticsPage(
                              bridge: bridge,
                              backLabel: 'Debug tools',
                            ),
                          ),
                        );
                      },
                    ),
                    const Divider(height: 1, color: OpenCrayColors.divider),
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

class _DebugPythonRunnerPage extends StatefulWidget {
  const _DebugPythonRunnerPage({required this.bridge, required this.backLabel});

  final OpenCrayHostBridge bridge;
  final String backLabel;

  @override
  State<_DebugPythonRunnerPage> createState() => _DebugPythonRunnerPageState();
}

class _DebugPythonRunnerPageState extends State<_DebugPythonRunnerPage> {
  static const String _defaultFileName = 'hello.py';
  static const String _defaultScript = '''print("hello from embedded Python")
import os
import sys

print(sys.version)
print(os.getcwd())
''';

  final TextEditingController _fileNameController = TextEditingController(
    text: _defaultFileName,
  );
  final TextEditingController _scriptController = TextEditingController(
    text: _defaultScript,
  );

  bool _isRunning = false;
  String? _runError;
  OpenCrayDebugPythonRunResult? _result;

  @override
  void dispose() {
    _fileNameController.dispose();
    _scriptController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    final canRun = !_isRunning && _scriptController.text.trim().isNotEmpty;
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          key: const ValueKey<String>('settings-debug-python-page'),
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
                'Run Python Script',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Execute a script through the embedded Android Python runtime used by the p4a debug path.',
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
                            'Embedded runtime only',
                            style: _SettingsTextStyles.cardTitle,
                          ),
                          SizedBox(height: 8),
                          Text(
                            'The script is written into `.opencray/debug-python/` inside the workspace, then executed via the embedded p4a runtime with a 20s startup budget and a 60s script timeout.',
                            style: _SettingsTextStyles.body,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    _HeaderActionChip(
                      label: _isRunning ? 'Running' : 'Run',
                      onTap: canRun ? _runScript : null,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              _SettingsCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Script', style: _SettingsTextStyles.cardTitle),
                    const SizedBox(height: 10),
                    TextField(
                      key: const ValueKey<String>('settings-debug-python-file'),
                      controller: _fileNameController,
                      autocorrect: false,
                      enableSuggestions: false,
                      enableIMEPersonalizedLearning: true,
                      spellCheckConfiguration:
                          const SpellCheckConfiguration.disabled(),
                      smartDashesType: SmartDashesType.disabled,
                      smartQuotesType: SmartQuotesType.disabled,
                      decoration: const InputDecoration(
                        labelText: 'File name',
                        hintText: 'hello.py',
                        border: OutlineInputBorder(),
                      ),
                      onChanged: (_) => setState(() {}),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      key: const ValueKey<String>(
                        'settings-debug-python-script',
                      ),
                      controller: _scriptController,
                      minLines: 12,
                      maxLines: 18,
                      keyboardType: TextInputType.multiline,
                      autocorrect: false,
                      enableSuggestions: false,
                      enableIMEPersonalizedLearning: true,
                      spellCheckConfiguration:
                          const SpellCheckConfiguration.disabled(),
                      smartDashesType: SmartDashesType.disabled,
                      smartQuotesType: SmartQuotesType.disabled,
                      style: _SettingsTextStyles.body.copyWith(
                        fontFamily: 'monospace',
                        height: 1.45,
                      ),
                      decoration: const InputDecoration(
                        labelText: 'Python script',
                        alignLabelWithHint: true,
                        border: OutlineInputBorder(),
                      ),
                      onChanged: (_) => setState(() {}),
                    ),
                    if (_runError != null) ...[
                      const SizedBox(height: 10),
                      Text(_runError!, style: _SettingsTextStyles.bodyStrong),
                    ],
                  ],
                ),
              ),
              if (result != null) ...[
                const SizedBox(height: 16),
                _buildResultOverviewCard(result),
                const SizedBox(height: 16),
                _buildResultTextCard(
                  title: 'Stdout',
                  text: result.stdout,
                  emptyText: 'No stdout emitted.',
                ),
                const SizedBox(height: 16),
                _buildResultTextCard(
                  title: 'Stderr',
                  text: result.stderr,
                  emptyText: 'No stderr emitted.',
                ),
                if (result.metadata.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  _buildResultTextCard(
                    title: 'Runtime metadata',
                    text: _renderDebugStringMap(result.metadata),
                    emptyText: 'No runtime metadata was emitted.',
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildResultOverviewCard(OpenCrayDebugPythonRunResult result) {
    final chips = <Widget>[
      _DebugValueChip(
        label: 'Status',
        value: _humanizeDebugCode(result.status),
      ),
      if (result.exitCode != null)
        _DebugValueChip(label: 'Exit', value: '${result.exitCode}'),
      _DebugValueChip(
        label: 'Duration',
        value: _formatDebugDuration(result.durationMs),
      ),
      if (_trimmedDebugValue(result.errorCode) != null)
        _DebugValueChip(label: 'Error', value: result.errorCode!),
    ];
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Last result', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          Wrap(spacing: 8, runSpacing: 8, children: chips),
          const SizedBox(height: 12),
          _DebugKeyValueLine(
            'Script',
            result.fileName.isEmpty ? 'Unavailable' : result.fileName,
          ),
          _DebugKeyValueLine(
            'Workspace path',
            result.scriptRelativePath.isEmpty
                ? 'Unavailable'
                : result.scriptRelativePath,
          ),
          _DebugKeyValueLine(
            'Started',
            _formatDebugClockTime(result.startedAtEpochMs),
          ),
          _DebugKeyValueLine(
            'Finished',
            _formatDebugClockTime(result.finishedAtEpochMs),
          ),
          if (_trimmedDebugValue(result.errorMessage) != null)
            _DebugKeyValueLine('Message', result.errorMessage!),
        ],
      ),
    );
  }

  Widget _buildResultTextCard({
    required String title,
    required String text,
    required String emptyText,
  }) {
    final normalizedText = text.trimRight();
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (normalizedText.isEmpty)
            Text(emptyText, style: _SettingsTextStyles.body)
          else
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFF7F8FA),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: OpenCrayColors.divider),
              ),
              child: SelectableText(
                normalizedText,
                style: _SettingsTextStyles.body.copyWith(
                  fontFamily: 'monospace',
                  height: 1.45,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Future<void> _runScript() async {
    if (_isRunning) {
      return;
    }
    final scriptText = _scriptController.text;
    if (scriptText.trim().isEmpty) {
      setState(() {
        _runError = 'Script content cannot be empty.';
      });
      return;
    }
    setState(() {
      _isRunning = true;
      _runError = null;
      _result = null;
    });
    try {
      final result = await widget.bridge.runDebugPythonScript(
        fileName: _fileNameController.text,
        scriptText: scriptText,
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _result = result;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _runError = '$error';
      });
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _isRunning = false;
    });
  }
}

class _RuntimeDiagnosticsPage extends StatefulWidget {
  const _RuntimeDiagnosticsPage({
    required this.bridge,
    required this.backLabel,
  });

  final OpenCrayHostBridge bridge;
  final String backLabel;

  @override
  State<_RuntimeDiagnosticsPage> createState() =>
      _RuntimeDiagnosticsPageState();
}

class _RuntimeDiagnosticsPageState extends State<_RuntimeDiagnosticsPage> {
  bool _isLoading = true;
  bool _isRefreshing = false;
  String? _loadError;
  OpenCrayShellSnapshot? _snapshot;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = _snapshot;
    return Scaffold(
      backgroundColor: OpenCrayColors.shellBackground,
      body: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          key: const ValueKey<String>('settings-runtime-diagnostics-page'),
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
                'Runtime Diagnostics',
                style: _SettingsTextStyles.pageTitleSubpage,
              ),
              const SizedBox(height: 8),
              const Text(
                'Inspect detached runtime ownership, service keepalive, and bridge transport.',
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
                            'Runtime status source',
                            style: _SettingsTextStyles.cardTitle,
                          ),
                          SizedBox(height: 8),
                          Text(
                            'Uses the existing shell snapshot path. No separate host diagnostics channel is required.',
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
                  key: ValueKey<String>('runtime-diagnostics-loading'),
                )
              else if (snapshot != null) ...[
                _buildConnectionCard(snapshot),
                const SizedBox(height: 16),
                _buildLocalRuntimeServerCard(snapshot),
                const SizedBox(height: 16),
                _buildHostOwnershipCard(snapshot),
                const SizedBox(height: 16),
                _buildRuntimeOwnerCard(snapshot),
                const SizedBox(height: 16),
                _buildRuntimeServiceCard(snapshot),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildConnectionCard(OpenCrayShellSnapshot snapshot) {
    final connection = snapshot.runtimeServiceConnectionState;
    final phase = connection?.phase?.trim().isNotEmpty == true
        ? connection!.phase!.trim()
        : null;
    final transport = connection?.transport?.trim().isNotEmpty == true
        ? connection!.transport!.trim()
        : null;
    final fallbackReason = connection?.fallbackReason?.trim().isNotEmpty == true
        ? connection!.fallbackReason!.trim()
        : null;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Connection & transport',
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _DebugValueChip(
                label: 'Host',
                value: snapshot.isHostConnected ? 'connected' : 'disconnected',
              ),
              _DebugValueChip(
                label: 'Phase',
                value:
                    phase ??
                    (snapshot.isHostConnected ? 'connected' : 'disconnected'),
              ),
              if (transport != null)
                _DebugValueChip(label: 'Transport', value: transport),
              _DebugValueChip(
                label: 'Binder',
                value: connection?.binderAvailable == true
                    ? 'available'
                    : 'unavailable',
              ),
              if (fallbackReason != null)
                _DebugValueChip(label: 'Fallback', value: fallbackReason),
            ],
          ),
          const SizedBox(height: 12),
          if (connection == null)
            const Text(
              'No runtime service connection state was projected into the shell snapshot.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            _DebugKeyValueLine(
              'Service start requested',
              _formatDebugBool(connection.serviceStartRequested),
            ),
            _DebugKeyValueLine(
              'Binding requested',
              _formatDebugBool(connection.bindingRequested),
            ),
            _DebugKeyValueLine(
              'Binder available',
              _formatDebugBool(connection.binderAvailable),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildHostOwnershipCard(OpenCrayShellSnapshot snapshot) {
    final hostLifecycle = snapshot.hostLifecycle;
    final ownerLifecycle = snapshot.runtimeOwnerLifecycle;
    final detachedOwner =
        hostLifecycle != null &&
        ownerLifecycle != null &&
        hostLifecycle.hostInstanceId != ownerLifecycle.hostInstanceId;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Host & ownership', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (hostLifecycle == null && ownerLifecycle == null)
            const Text(
              'No host or runtime owner lifecycle data is visible in the shell snapshot.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _DebugValueChip(
                  label: 'Detached owner',
                  value: detachedOwner ? 'yes' : 'no',
                ),
                if (hostLifecycle?.hostInstanceId?.trim().isNotEmpty == true)
                  _DebugValueChip(
                    label: 'Host',
                    value: hostLifecycle!.hostInstanceId!.trim(),
                  ),
                if (ownerLifecycle?.runtimeOwnerId?.trim().isNotEmpty == true)
                  _DebugValueChip(
                    label: 'Owner',
                    value: ownerLifecycle!.runtimeOwnerId!.trim(),
                  ),
              ],
            ),
            if (hostLifecycle != null) ...[
              const SizedBox(height: 12),
              const Text('Current host', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              ..._buildHostLifecycleLines(hostLifecycle),
            ],
            if (ownerLifecycle != null) ...[
              const SizedBox(height: 12),
              const Text(
                'Runtime owner',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              ..._buildHostLifecycleLines(ownerLifecycle),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildLocalRuntimeServerCard(OpenCrayShellSnapshot snapshot) {
    final server = snapshot.localRuntimeServerState;
    final phase = server?.phase?.trim().isNotEmpty == true
        ? server!.phase!.trim()
        : null;
    final bindAddress = server?.bindAddress?.trim().isNotEmpty == true
        ? server!.bindAddress!.trim()
        : null;
    final failureReason = server?.failureReason?.trim().isNotEmpty == true
        ? server!.failureReason!.trim()
        : null;
    final listeningPort = server?.listeningPort;
    final requestedPort = server?.requestedPort;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Local runtime server',
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 10),
          if (server == null)
            const Text(
              'No local runtime server state was projected into the shell snapshot.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _DebugValueChip(label: 'Phase', value: phase ?? 'unknown'),
                if (bindAddress != null)
                  _DebugValueChip(label: 'Address', value: bindAddress),
                _DebugValueChip(
                  label: 'Port',
                  value:
                      (listeningPort ?? requestedPort)?.toString() ?? 'unknown',
                ),
                if (failureReason != null)
                  _DebugValueChip(label: 'Failure', value: failureReason),
              ],
            ),
            const SizedBox(height: 12),
            _DebugKeyValueLine(
              'Requested port',
              requestedPort?.toString() ?? 'n/a',
            ),
            _DebugKeyValueLine(
              'Listening port',
              listeningPort?.toString() ?? 'n/a',
            ),
            _DebugKeyValueLine(
              'Last start attempt',
              _formatDebugEpochMs(server.lastStartAttemptAtEpochMs),
            ),
            _DebugKeyValueLine(
              'Last started',
              _formatDebugEpochMs(server.lastStartedAtEpochMs),
            ),
            _DebugKeyValueLine(
              'Failure reason',
              _normalizedValue(server.failureReason),
            ),
            _DebugKeyValueLine(
              'Changed',
              _formatDebugEpochMs(server.changedAtEpochMs),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildRuntimeOwnerCard(OpenCrayShellSnapshot snapshot) {
    final summary = snapshot.runtimeOwnerWorkSummary;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Runtime owner work',
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 10),
          if (summary == null)
            const Text(
              'No runtime owner work summary was projected into the shell snapshot.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _DebugValueChip(
                  label: 'Active work',
                  value: summary.hasActiveWork ? 'yes' : 'no',
                ),
                _DebugValueChip(
                  label: 'Tracked sessions',
                  value: '${summary.trackedSessionCount}',
                ),
                _DebugValueChip(
                  label: 'Active runs',
                  value: '${summary.activeRunCount}',
                ),
                _DebugValueChip(
                  label: 'Active sessions',
                  value: '${summary.activeSessionCount}',
                ),
                _DebugValueChip(
                  label: 'Managed processes',
                  value: '${summary.liveManagedProcessSessionIds.length}',
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (summary.activeSessionIds.isNotEmpty)
              _DebugKeyValueLine(
                'Active session ids',
                summary.activeSessionIds.join(', '),
              ),
            if (summary.pendingWorkSessionIds.isNotEmpty)
              _DebugKeyValueLine(
                'Pending work sessions',
                summary.pendingWorkSessionIds.join(', '),
              ),
            if (summary.liveManagedProcessSessionIds.isNotEmpty)
              _DebugKeyValueLine(
                'Managed process sessions',
                summary.liveManagedProcessSessionIds.join(', '),
              ),
            if (summary.activeSessionIds.isEmpty &&
                summary.pendingWorkSessionIds.isEmpty &&
                summary.liveManagedProcessSessionIds.isEmpty)
              const Text(
                'No active session ids or managed process owners are currently reported.',
                style: _SettingsTextStyles.body,
              ),
          ],
        ],
      ),
    );
  }

  Widget _buildRuntimeServiceCard(OpenCrayShellSnapshot snapshot) {
    final lifecycle = snapshot.runtimeServiceLifecycle;
    final workState = snapshot.runtimeServiceWorkState;
    final keepAlive = snapshot.runtimeServiceKeepAliveState;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Runtime service', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          if (lifecycle == null && workState == null && keepAlive == null)
            const Text(
              'No runtime service lifecycle or keepalive state is visible in the shell snapshot.',
              style: _SettingsTextStyles.body,
            )
          else ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (workState?.phase?.trim().isNotEmpty == true)
                  _DebugValueChip(
                    label: 'Work',
                    value: workState!.phase!.trim(),
                  ),
                _DebugValueChip(
                  label: 'Active work',
                  value: workState?.hasActiveWork == true ? 'yes' : 'no',
                ),
                _DebugValueChip(
                  label: 'Keepalive',
                  value: keepAlive?.phase?.trim().isNotEmpty == true
                      ? keepAlive!.phase!.trim()
                      : 'n/a',
                ),
                _DebugValueChip(
                  label: 'Stop scheduled',
                  value: keepAlive?.stopScheduled == true ? 'yes' : 'no',
                ),
              ],
            ),
            if (lifecycle != null) ...[
              const SizedBox(height: 12),
              const Text(
                'Service lifecycle',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              _DebugKeyValueLine(
                'Service instance',
                _normalizedValue(lifecycle.serviceInstanceId),
              ),
              _DebugKeyValueLine(
                'Process start id',
                _normalizedValue(lifecycle.processStartId),
              ),
              _DebugKeyValueLine(
                'Service created',
                _formatDebugEpochMs(lifecycle.serviceCreatedAtEpochMs),
              ),
              _DebugKeyValueLine(
                'Process started',
                _formatDebugEpochMs(lifecycle.processStartedAtEpochMs),
              ),
            ],
            if (workState != null) ...[
              const SizedBox(height: 12),
              const Text('Work state', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              _DebugKeyValueLine(
                'Keepalive required',
                _formatDebugBool(workState.keepAliveRequired),
              ),
              _DebugKeyValueLine(
                'Keepalive reason',
                _normalizedValue(workState.keepAliveReason),
              ),
              _DebugKeyValueLine(
                'Changed',
                _formatDebugEpochMs(workState.changedAtEpochMs),
              ),
              _DebugKeyValueLine(
                'Active since',
                _formatDebugEpochMs(workState.activeSinceEpochMs),
              ),
              _DebugKeyValueLine(
                'Idle since',
                _formatDebugEpochMs(workState.idleSinceEpochMs),
              ),
            ],
            if (keepAlive != null) ...[
              const SizedBox(height: 12),
              const Text('Keepalive', style: _SettingsTextStyles.bodyStrong),
              const SizedBox(height: 8),
              _DebugKeyValueLine(
                'Idle grace',
                keepAlive.idleGraceMs == null
                    ? 'n/a'
                    : '${keepAlive.idleGraceMs} ms',
              ),
              _DebugKeyValueLine(
                'Stop deadline',
                _formatDebugEpochMs(keepAlive.stopDeadlineEpochMs),
              ),
              _DebugKeyValueLine(
                'Seen start command',
                _formatDebugBool(keepAlive.hasSeenStartCommand),
              ),
              _DebugKeyValueLine(
                'Last start id',
                keepAlive.lastStartId?.toString() ?? 'n/a',
              ),
              _DebugKeyValueLine(
                'Last start command',
                _formatDebugEpochMs(keepAlive.lastStartCommandAtEpochMs),
              ),
              _DebugKeyValueLine(
                'Last stop request',
                _formatDebugEpochMs(keepAlive.lastStopRequestAtEpochMs),
              ),
              _DebugKeyValueLine(
                'Last stop succeeded',
                _formatDebugNullableBool(keepAlive.lastStopSucceeded),
              ),
              _DebugKeyValueLine(
                'Changed',
                _formatDebugEpochMs(keepAlive.changedAtEpochMs),
              ),
            ],
          ],
        ],
      ),
    );
  }

  List<Widget> _buildHostLifecycleLines(
    OpenCrayHostLifecycleSnapshot snapshot,
  ) {
    return <Widget>[
      _DebugKeyValueLine(
        'Process start id',
        _normalizedValue(snapshot.processStartId),
      ),
      _DebugKeyValueLine(
        'Host instance',
        _normalizedValue(snapshot.hostInstanceId),
      ),
      _DebugKeyValueLine(
        'Runtime owner id',
        _normalizedValue(snapshot.runtimeOwnerId),
      ),
      _DebugKeyValueLine(
        'Host created',
        _formatDebugEpochMs(snapshot.hostCreatedAtEpochMs),
      ),
      _DebugKeyValueLine(
        'Process started',
        _formatDebugEpochMs(snapshot.processStartedAtEpochMs),
      ),
    ];
  }

  String _normalizedValue(String? value, {String? fallback = 'n/a'}) {
    final normalized = value?.trim();
    if (normalized == null || normalized.isEmpty) {
      return fallback ?? '';
    }
    return normalized;
  }

  String _formatDebugBool(bool value) => value ? 'yes' : 'no';

  String _formatDebugNullableBool(bool? value) {
    if (value == null) {
      return 'n/a';
    }
    return value ? 'yes' : 'no';
  }

  String _formatDebugEpochMs(int? epochMs) {
    if (epochMs == null || epochMs <= 0) {
      return 'n/a';
    }
    return _formatDebugClockTime(epochMs);
  }

  Future<void> _refresh() async {
    final shouldShowLoading = _snapshot == null && !_isRefreshing;
    setState(() {
      _loadError = null;
      _isLoading = shouldShowLoading;
      _isRefreshing = true;
    });
    try {
      final snapshot = await widget.bridge.loadShellSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoading = false;
        _isRefreshing = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = 'Failed to load runtime diagnostics: $error';
        _isLoading = false;
        _isRefreshing = false;
      });
    }
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
              memoryFlush == null &&
              durableCompaction == null)
            const Text(
              'No LLM diagnostics, live-context, context-budget, bootstrap, memory flush, or durable compaction trace was captured for this run.',
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
              if (liveContext != null ||
                  contextBudget != null ||
                  bootstrap != null ||
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
              enableSuggestions: true,
              enableIMEPersonalizedLearning: true,
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
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _isApplyingMemoryAction = false;
    });
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

String _renderDebugStringMap(Map<String, String> values) {
  final entries = values.entries.toList(growable: false)
    ..sort((left, right) => left.key.compareTo(right.key));
  return entries.map((entry) => '${entry.key}: ${entry.value}').join('\n');
}

String? _trimmedDebugValue(String? value) {
  final normalized = value?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

Map<String, dynamic>? _decodeDebugJsonObject(String? value) {
  final normalized = _trimmedDebugValue(value);
  if (normalized == null) {
    return null;
  }
  try {
    final decoded = jsonDecode(normalized);
    if (decoded is! Map) {
      return null;
    }
    return decoded.map<String, dynamic>(
      (key, rawValue) => MapEntry(key.toString(), rawValue),
    );
  } catch (_) {
    return null;
  }
}

String? _debugArgumentString(
  Map<String, dynamic>? arguments,
  String key, {
  String? fallbackKey,
}) {
  final dynamic rawValue =
      arguments?[key] ?? (fallbackKey == null ? null : arguments?[fallbackKey]);
  final normalized = rawValue?.toString().trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

int? _debugArgumentInt(Map<String, dynamic>? arguments, String key) {
  final dynamic rawValue = arguments?[key];
  if (rawValue is int) {
    return rawValue;
  }
  if (rawValue is num) {
    return rawValue.toInt();
  }
  if (rawValue is String) {
    return int.tryParse(rawValue.trim());
  }
  return null;
}

List<dynamic>? _debugArgumentList(Map<String, dynamic>? arguments, String key) {
  final dynamic rawValue = arguments?[key];
  if (rawValue is List<dynamic>) {
    return rawValue;
  }
  if (rawValue is List) {
    return rawValue.cast<dynamic>();
  }
  return null;
}

List<String> _debugArgumentStringList(
  Map<String, dynamic>? arguments,
  String key,
) {
  final values = _debugArgumentList(arguments, key);
  if (values == null || values.isEmpty) {
    return const <String>[];
  }
  return values
      .map((value) => value?.toString().trim() ?? '')
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

String _debugReadRangeSummary({required int? offset, required int? limit}) {
  if (offset == null && limit == null) {
    return '';
  }
  if (offset != null && limit != null) {
    final int endLine = offset + limit - 1;
    return 'lines $offset-$endLine';
  }
  if (offset != null) {
    return 'from line $offset';
  }
  return 'first $limit lines';
}

String _debugSubagentTypeDisplay(String rawValue) => rawValue
    .split(RegExp(r'[-_\s]+'))
    .where((segment) => segment.isNotEmpty)
    .map(
      (segment) =>
          '${segment[0].toUpperCase()}${segment.substring(1).toLowerCase()}',
    )
    .join(' ');

String _debugToolActionSummary({
  required String toolName,
  required Map<String, dynamic>? arguments,
}) {
  switch (toolName) {
    case 'Read':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      if (path == null) {
        return 'Call $toolName';
      }
      final int? offset = _debugArgumentInt(arguments, 'offset');
      final int? limit = _debugArgumentInt(arguments, 'limit');
      final String range = _debugReadRangeSummary(offset: offset, limit: limit);
      return range.isEmpty ? 'Read $path' : 'Read $path $range';
    case 'LS':
      final String path =
          _debugArgumentString(arguments, 'path', fallbackKey: 'file_path') ??
          '.';
      return 'List $path';
    case 'Grep':
      final String? pattern = _debugArgumentString(arguments, 'pattern');
      if (pattern == null) {
        return 'Call $toolName';
      }
      final String path = _debugArgumentString(arguments, 'path') ?? '.';
      final String? glob = _debugArgumentString(arguments, 'glob');
      return glob == null
          ? 'Search "$pattern" in $path'
          : 'Search "$pattern" in $path (glob: $glob)';
    case 'Glob':
      final String? pattern = _debugArgumentString(arguments, 'pattern');
      if (pattern == null) {
        return 'Call $toolName';
      }
      final String path = _debugArgumentString(arguments, 'path') ?? '.';
      return 'Match $pattern in $path';
    case 'Write':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      return path == null ? 'Call $toolName' : 'Write $path';
    case 'Edit':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      return path == null ? 'Call $toolName' : 'Edit $path';
    case 'MultiEdit':
      final String? path = _debugArgumentString(
        arguments,
        'file_path',
        fallbackKey: 'path',
      );
      if (path == null) {
        return 'Call $toolName';
      }
      final int editCount = _debugArgumentList(arguments, 'edits')?.length ?? 0;
      return editCount <= 0
          ? 'MultiEdit $path'
          : 'Apply $editCount edit(s) to $path';
    case 'WebSearch':
      final String operation =
          _debugArgumentString(arguments, 'operation')?.toLowerCase() ?? '';
      final String? directQuery = _debugArgumentString(arguments, 'query');
      final List<String> queryList = _debugArgumentStringList(
        arguments,
        'queries',
      );
      final String? query =
          directQuery ?? (queryList.isEmpty ? null : queryList.first);
      final String? url = _debugArgumentString(arguments, 'url');
      final String? text =
          _debugArgumentString(arguments, 'text') ??
          _debugArgumentString(arguments, 'pattern');
      final List<String> domains = _debugArgumentStringList(
        arguments,
        'domains',
      );
      final String domainSuffix = domains.isEmpty
          ? ''
          : ' within ${domains.join(', ')}';
      switch (operation) {
        case 'open_page':
          return url == null
              ? 'Call $toolName'
              : 'Open search result page $url';
        case 'find_in_page':
          if (url == null && text == null) {
            return 'Call $toolName';
          }
          final String target = text == null ? '' : ' "$text"';
          final String location = url == null ? '' : ' in $url';
          return 'Find in page$target$location';
        default:
          return query == null
              ? 'Search the web$domainSuffix'
              : 'Search the web for "$query"$domainSuffix';
      }
    case 'TodoWrite':
      if (arguments?.containsKey('todos') != true) {
        return 'Read current todo list';
      }
      final int todoCount = _debugArgumentList(arguments, 'todos')?.length ?? 0;
      return todoCount <= 0
          ? 'Update todo list'
          : 'Update $todoCount todo item(s)';
    case 'Task':
      final String? description = _debugArgumentString(
        arguments,
        'description',
      );
      final String? subagentType =
          _debugArgumentString(arguments, 'subagent_type') ??
          _debugArgumentString(arguments, 'subagentType');
      final String target = subagentType == null
          ? 'subagent'
          : _debugSubagentTypeDisplay(subagentType);
      return description == null
          ? 'Delegate to $target'
          : 'Delegate to $target: ${_truncateDebugText(description, 64)}';
    default:
      return toolName.trim().isEmpty ? 'Tool call' : 'Call $toolName';
  }
}

String? _debugPreviewValue(dynamic rawValue) {
  if (rawValue == null) {
    return null;
  }
  if (rawValue is String) {
    final normalized = rawValue.trim();
    return normalized.isEmpty ? null : _truncateDebugText(normalized, 48);
  }
  if (rawValue is bool) {
    return rawValue ? 'true' : 'false';
  }
  if (rawValue is num) {
    return '$rawValue';
  }
  if (rawValue is List) {
    if (rawValue.isEmpty) {
      return null;
    }
    final List<String> preview = rawValue
        .map(_debugPreviewValue)
        .whereType<String>()
        .take(2)
        .toList(growable: false);
    if (preview.isEmpty) {
      return '${rawValue.length} item(s)';
    }
    final String suffix = rawValue.length > preview.length ? ', ...' : '';
    return '${preview.join(', ')}$suffix';
  }
  if (rawValue is Map) {
    return '${rawValue.length} field(s)';
  }
  final normalized = rawValue.toString().trim();
  return normalized.isEmpty ? null : _truncateDebugText(normalized, 48);
}

String? _debugMapPreview(Map<String, dynamic>? values) {
  if (values == null || values.isEmpty) {
    return null;
  }
  const priorityKeys = <String>[
    'file_path',
    'path',
    'query',
    'pattern',
    'url',
    'command',
    'script_path',
    'name',
    'process_id',
    'agent_id',
    'description',
    'prompt',
    'text',
  ];
  final parts = <String>[];
  for (final key in priorityKeys) {
    final String? preview = _debugPreviewValue(values[key]);
    if (preview == null) {
      continue;
    }
    parts.add('$key $preview');
    if (parts.length >= 2) {
      return parts.join(' · ');
    }
  }
  for (final entry in values.entries) {
    if (priorityKeys.contains(entry.key)) {
      continue;
    }
    final String? preview = _debugPreviewValue(entry.value);
    if (preview == null) {
      continue;
    }
    parts.add('${entry.key} $preview');
    if (parts.length >= 2) {
      break;
    }
  }
  return parts.isEmpty ? null : parts.join(' · ');
}

String? _debugToolCallDetailPreview({
  required String toolName,
  required Map<String, dynamic>? arguments,
  required String? rawArgumentsJson,
}) {
  switch (toolName) {
    case 'Task':
      final String? prompt = _debugArgumentString(arguments, 'prompt');
      final String? contextMode = _debugArgumentString(
        arguments,
        'context_mode',
      );
      final parts = <String>[
        if (prompt != null) 'prompt ${_truncateDebugText(prompt, 72)}',
        if (contextMode != null) 'context $contextMode',
      ];
      final List<String> allowedTools = _debugArgumentStringList(
        arguments,
        'allowed_tools',
      );
      if (allowedTools.isNotEmpty) {
        parts.add('allowed ${_truncateDebugText(allowedTools.join(', '), 72)}');
      }
      return parts.isEmpty ? null : parts.join(' · ');
    case 'WebSearch':
      final List<String> sourceUrls = _debugArgumentStringList(
        arguments,
        'sourceUrls',
      );
      if (sourceUrls.isEmpty) {
        return null;
      }
      return 'sources ${_truncateDebugText(sourceUrls.join(', '), 72)}';
    case 'TodoWrite':
      final List<dynamic>? todos = _debugArgumentList(arguments, 'todos');
      if (todos == null || todos.isEmpty) {
        return null;
      }
      final List<String> preview = <String>[];
      for (final dynamic rawTodo in todos) {
        if (rawTodo is! Map) {
          continue;
        }
        final Map<String, dynamic> todo = rawTodo.map<String, dynamic>(
          (key, value) => MapEntry(key.toString(), value),
        );
        final String? content = _debugArgumentString(todo, 'content');
        if (content == null) {
          continue;
        }
        final String? status = _debugArgumentString(todo, 'status');
        preview.add(
          status == null
              ? _truncateDebugText(content, 48)
              : '${_truncateDebugText(content, 40)} [$status]',
        );
        if (preview.length >= 2) {
          break;
        }
      }
      return preview.isEmpty ? null : preview.join(' · ');
    default:
      final String? mapPreview = _debugMapPreview(arguments);
      if (mapPreview != null) {
        return mapPreview;
      }
      final String? normalizedRawArguments = _trimmedDebugValue(
        rawArgumentsJson,
      );
      return normalizedRawArguments == null
          ? null
          : _truncateDebugText(normalizedRawArguments, 120);
  }
}

String? _debugResultMetadataValue(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final normalized = event.resultMetadata[key]?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized;
}

int? _debugResultMetadataInt(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final String? value = _debugResultMetadataValue(event, key);
  return value == null ? null : int.tryParse(value);
}

bool? _debugResultMetadataBool(
  OpenCrayChatRuntimeEventSnapshot event,
  String key,
) {
  final String? value = _debugResultMetadataValue(event, key)?.toLowerCase();
  if (value == 'true') {
    return true;
  }
  if (value == 'false') {
    return false;
  }
  return null;
}

List<String> _debugCsvValues(String? value) {
  final normalized = _trimmedDebugValue(value);
  if (normalized == null) {
    return const <String>[];
  }
  return normalized
      .split(',')
      .map((entry) => entry.trim())
      .where((entry) => entry.isNotEmpty)
      .toList(growable: false);
}

bool _debugResultMetadataTruncated(OpenCrayChatRuntimeEventSnapshot event) {
  const candidateKeys = <String>[
    'truncated',
    'outputTruncated',
    'contentTruncated',
    'resultTruncated',
  ];
  for (final key in candidateKeys) {
    if (_debugResultMetadataBool(event, key) == true) {
      return true;
    }
  }
  return false;
}

Map<String, dynamic>? _debugToolResultArgumentsFallback({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) {
  switch (toolName) {
    case 'Read':
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (filePath == null) {
        return null;
      }
      return <String, dynamic>{
        'file_path': filePath,
        if (_debugResultMetadataInt(event, 'offset') != null)
          'offset': _debugResultMetadataInt(event, 'offset'),
        if (_debugResultMetadataInt(event, 'limit') != null)
          'limit': _debugResultMetadataInt(event, 'limit'),
      };
    case 'LS':
      return <String, dynamic>{
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
      };
    case 'Grep':
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      if (pattern == null) {
        return null;
      }
      return <String, dynamic>{
        'pattern': pattern,
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
        if (_debugResultMetadataValue(event, 'glob') != null)
          'glob': _debugResultMetadataValue(event, 'glob'),
      };
    case 'Glob':
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      if (pattern == null) {
        return null;
      }
      return <String, dynamic>{
        'pattern': pattern,
        if (_debugResultMetadataValue(event, 'path') != null)
          'path': _debugResultMetadataValue(event, 'path'),
      };
    case 'WebSearch':
      final String? operation = _debugResultMetadataValue(
        event,
        'providerManagedOperation',
      );
      final String? query = _debugResultMetadataValue(event, 'query');
      final String? url = _debugResultMetadataValue(event, 'url');
      final String? text = _debugResultMetadataValue(event, 'text');
      final List<String> sourceUrls = _debugCsvValues(
        _debugResultMetadataValue(event, 'sourceUrls'),
      );
      if (operation == null &&
          query == null &&
          url == null &&
          text == null &&
          sourceUrls.isEmpty) {
        return null;
      }
      return <String, dynamic>{
        if (operation != null) 'operation': operation,
        if (query != null) 'query': query,
        if (url != null) 'url': url,
        if (text != null) 'text': text,
        if (sourceUrls.isNotEmpty) 'sourceUrls': sourceUrls,
      };
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (filePath == null) {
        return null;
      }
      return <String, dynamic>{'file_path': filePath};
    case 'Task':
      final String? description = _debugResultMetadataValue(
        event,
        'delegationDescription',
      );
      final String? prompt = _debugResultMetadataValue(
        event,
        'delegationPromptPreview',
      );
      final String? subagentType =
          _debugResultMetadataValue(event, 'delegationSubagentType') ??
          _debugResultMetadataValue(event, 'subagentType');
      final String? contextMode =
          _debugResultMetadataValue(event, 'delegationContextMode') ??
          _debugResultMetadataValue(event, 'subagentContextMode');
      final List<String> allowedTools = _debugCsvValues(
        _debugResultMetadataValue(event, 'delegationAllowedTools'),
      );
      if (description == null &&
          prompt == null &&
          subagentType == null &&
          contextMode == null &&
          allowedTools.isEmpty) {
        return null;
      }
      return <String, dynamic>{
        if (description != null) 'description': description,
        if (prompt != null) 'prompt': prompt,
        if (subagentType != null) 'subagent_type': subagentType,
        if (contextMode != null) 'context_mode': contextMode,
        if (allowedTools.isNotEmpty) 'allowed_tools': allowedTools,
      };
    default:
      return null;
  }
}

String _debugToolResultActionSummary({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) => _debugToolActionSummary(
  toolName: toolName,
  arguments: _debugToolResultArgumentsFallback(
    toolName: toolName,
    event: event,
  ),
);

String? _debugGenericResultMetadataPreview(
  OpenCrayChatRuntimeEventSnapshot event,
) {
  if (event.resultMetadata.isEmpty) {
    return null;
  }
  final parts = <String>[];
  for (final entry in event.resultMetadata.entries) {
    final String key = entry.key.trim();
    final String value = entry.value.trim();
    if (key.isEmpty || value.isEmpty) {
      continue;
    }
    parts.add('$key ${_truncateDebugText(value, 48)}');
    if (parts.length >= 3) {
      break;
    }
  }
  return parts.isEmpty ? null : parts.join(' · ');
}

String? _debugToolResultMetadataSummary({
  required String toolName,
  required OpenCrayChatRuntimeEventSnapshot event,
}) {
  switch (toolName) {
    case 'LS':
      final int? entryCount = _debugResultMetadataInt(event, 'entryCount');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (entryCount == null) {
        return null;
      }
      final String summary = path == null
          ? 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'}'
          : 'Listed $entryCount entr${entryCount == 1 ? 'y' : 'ies'} in $path';
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'Read':
      final int? returnedLineCount = _debugResultMetadataInt(
        event,
        'returnedLineCount',
      );
      final int? totalLineCount = _debugResultMetadataInt(
        event,
        'totalLineCount',
      );
      final bool truncated = _debugResultMetadataTruncated(event);
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (returnedLineCount == null &&
          totalLineCount == null &&
          !truncated &&
          filePath == null) {
        return null;
      }
      final parts = <String>[
        if (returnedLineCount != null)
          returnedLineCount == 1
              ? 'Returned 1 line'
              : 'Returned $returnedLineCount lines',
        if (filePath != null) 'from $filePath',
        if (totalLineCount != null)
          totalLineCount == 1 ? '(1-line file)' : '($totalLineCount-line file)',
        if (truncated) 'Output truncated to the read budget.',
      ];
      return parts.join(' ');
    case 'Grep':
      final int? matchCount = _debugResultMetadataInt(event, 'matchCount');
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (matchCount == null) {
        return null;
      }
      final String target = path ?? '.';
      final String summary = pattern == null
          ? (matchCount == 1
                ? 'Found 1 match in $target'
                : 'Found $matchCount matches in $target')
          : (matchCount == 1
                ? 'Found 1 match for "$pattern" in $target'
                : 'Found $matchCount matches for "$pattern" in $target');
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'Glob':
      final int? matchCount = _debugResultMetadataInt(event, 'matchCount');
      final String? pattern = _debugResultMetadataValue(event, 'pattern');
      final String? path = _debugResultMetadataValue(event, 'path');
      final bool truncated = _debugResultMetadataTruncated(event);
      if (matchCount == null) {
        return null;
      }
      final String target = path ?? '.';
      final String summary = pattern == null
          ? 'Matched $matchCount path(s) in $target'
          : 'Matched $matchCount path(s) for $pattern in $target';
      return truncated
          ? '$summary. Output truncated at the tool result limit.'
          : summary;
    case 'WebSearch':
      final int? sourceCount = _debugResultMetadataInt(event, 'sourceCount');
      final String? operation = _debugResultMetadataValue(
        event,
        'providerManagedOperation',
      )?.toLowerCase();
      final String? status = _debugResultMetadataValue(
        event,
        'providerManagedStatus',
      );
      final String? query = _debugResultMetadataValue(event, 'query');
      final String? url = _debugResultMetadataValue(event, 'url');
      final String? text = _debugResultMetadataValue(event, 'text');
      final bool managed =
          _debugResultMetadataValue(event, 'providerManaged') == 'true';
      if (sourceCount == null &&
          operation == null &&
          status == null &&
          query == null &&
          url == null &&
          text == null) {
        return null;
      }
      return <String>[
        if (managed) 'Provider-managed search',
        switch (operation) {
          'open_page' => url == null ? '' : 'opened $url',
          'find_in_page' => <String>[
            if (text != null) 'find "$text"',
            if (url != null) 'in $url',
          ].where((part) => part.isNotEmpty).join(' '),
          _ => query == null ? '' : 'search "$query"',
        },
        if (sourceCount != null)
          sourceCount == 1 ? '1 source' : '$sourceCount sources',
        if (status != null) 'status $status',
      ].where((part) => part.isNotEmpty).join(' ');
    case 'Edit':
      final int? replacementCount = _debugResultMetadataInt(
        event,
        'replacementCount',
      );
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (replacementCount == null && filePath == null) {
        return null;
      }
      return <String>[
        if (replacementCount != null)
          replacementCount == 1
              ? 'Applied 1 replacement'
              : 'Applied $replacementCount replacements',
        if (filePath != null) 'in $filePath',
      ].join(' ');
    case 'MultiEdit':
      final int? replacementCount = _debugResultMetadataInt(
        event,
        'replacementCount',
      );
      final int? editCount = _debugResultMetadataInt(event, 'editCount');
      final String? filePath = _debugResultMetadataValue(event, 'filePath');
      if (replacementCount == null && editCount == null && filePath == null) {
        return null;
      }
      return <String>[
        if (editCount != null)
          editCount == 1 ? 'Applied 1 edit' : 'Applied $editCount edits',
        if (replacementCount != null)
          replacementCount == 1
              ? '(1 replacement)'
              : '($replacementCount replacements)',
        if (filePath != null) 'in $filePath',
      ].join(' ');
    case 'Task':
      final String? executionState = _debugResultMetadataValue(
        event,
        'executionState',
      );
      final String? childStatus =
          _debugResultMetadataValue(event, 'status') ??
          _debugResultMetadataValue(event, 'delegationStatus');
      final String? subagentType =
          _debugResultMetadataValue(event, 'delegationSubagentType') ??
          _debugResultMetadataValue(event, 'subagentType');
      final String? contextMode =
          _debugResultMetadataValue(event, 'delegationContextMode') ??
          _debugResultMetadataValue(event, 'subagentContextMode');
      final int? turnCount = _debugResultMetadataInt(event, 'childTurnCount');
      final int? toolCallCount = _debugResultMetadataInt(
        event,
        'childToolCallCount',
      );
      final List<String> allowedTools = _debugCsvValues(
        _debugResultMetadataValue(event, 'delegationAllowedTools'),
      );
      final parts = <String>[
        if (executionState != null) 'state $executionState',
        if (childStatus != null) 'status $childStatus',
        if (subagentType != null)
          'subagent ${_debugSubagentTypeDisplay(subagentType)}',
        if (contextMode != null) 'context $contextMode',
        if (turnCount != null) 'turns $turnCount',
        if (toolCallCount != null) 'tool calls $toolCallCount',
        if (allowedTools.isNotEmpty)
          'allowed ${_truncateDebugText(allowedTools.join(', '), 72)}',
      ];
      return parts.isEmpty ? null : parts.join(' · ');
    case 'TodoWrite':
      final int? todoCount = _debugResultMetadataInt(event, 'todoCount');
      final int addedTodoCount =
          _debugResultMetadataInt(event, 'addedTodoCount') ?? 0;
      final int removedTodoCount =
          _debugResultMetadataInt(event, 'removedTodoCount') ?? 0;
      final int statusChangedTodoCount =
          _debugResultMetadataInt(event, 'statusChangedTodoCount') ?? 0;
      final bool mutated = _debugResultMetadataBool(event, 'mutated') ?? false;
      if (todoCount == null &&
          addedTodoCount == 0 &&
          removedTodoCount == 0 &&
          statusChangedTodoCount == 0 &&
          !mutated) {
        return null;
      }
      return <String>[
        if (todoCount != null)
          todoCount == 1 ? '1 todo in list' : '$todoCount todos in list',
        if (mutated) 'mutated',
        if (addedTodoCount > 0)
          addedTodoCount == 1 ? 'added 1' : 'added $addedTodoCount',
        if (removedTodoCount > 0)
          removedTodoCount == 1 ? 'removed 1' : 'removed $removedTodoCount',
        if (statusChangedTodoCount > 0)
          statusChangedTodoCount == 1
              ? '1 status change'
              : '$statusChangedTodoCount status changes',
      ].join(' · ');
    default:
      return _debugGenericResultMetadataPreview(event);
  }
}

String _summarizeToolCallEvent(OpenCrayChatRuntimeEventSnapshot event) {
  final String? toolName = _trimmedDebugValue(event.toolName);
  final Map<String, dynamic>? arguments = _decodeDebugJsonObject(
    event.argumentsJson,
  );
  final parts = <String>[
    toolName == null
        ? 'Tool call'
        : _debugToolActionSummary(toolName: toolName, arguments: arguments),
  ];
  final String? reason = _trimmedDebugValue(event.toolReason);
  if (reason != null) {
    parts.add('reason ${_truncateDebugText(reason, 72)}');
  }
  if (toolName != null) {
    final String? detail = _debugToolCallDetailPreview(
      toolName: toolName,
      arguments: arguments,
      rawArgumentsJson: event.argumentsJson,
    );
    if (detail != null) {
      parts.add(detail);
    }
  } else {
    final String? normalizedArguments = _trimmedDebugValue(event.argumentsJson);
    if (normalizedArguments != null) {
      parts.add(_truncateDebugText(normalizedArguments, 120));
    }
  }
  return parts.join(' · ');
}

String _summarizeToolResultEvent(OpenCrayChatRuntimeEventSnapshot event) {
  final String? toolName = _trimmedDebugValue(event.toolName);
  final parts = <String>[
    toolName == null
        ? 'Tool result'
        : _debugToolResultActionSummary(toolName: toolName, event: event),
  ];
  final String? status =
      _trimmedDebugValue(event.toolStatus) ?? _trimmedDebugValue(event.status);
  if (status != null) {
    parts.add(status);
  }
  if (toolName != null) {
    final String? metadataSummary = _debugToolResultMetadataSummary(
      toolName: toolName,
      event: event,
    );
    if (metadataSummary != null) {
      parts.add(metadataSummary);
    }
  } else {
    final String? metadataPreview = _debugGenericResultMetadataPreview(event);
    if (metadataPreview != null) {
      parts.add(metadataPreview);
    }
  }
  final String? errorCode = _trimmedDebugValue(event.errorCode);
  final String? errorMessage = _trimmedDebugValue(event.errorMessage);
  if (errorCode != null) {
    parts.add(errorCode);
  }
  if (errorMessage != null) {
    parts.add(_truncateDebugText(errorMessage, 96));
  } else {
    final String? contentPreview =
        _trimmedDebugValue(event.contentPreview) ??
        _trimmedDebugValue(event.content);
    if (contentPreview != null) {
      parts.add(_truncateDebugText(contentPreview, 96));
    }
  }
  return parts.join(' · ');
}

String? _runAttemptReasonSummary(OpenCrayChatRunSnapshot run) {
  final recoverySummary = _trimmedDebugValue(run.recoveryPlan?.summary);
  if (recoverySummary != null) {
    return recoverySummary;
  }
  final recoveryReason = _trimmedDebugValue(run.diagnostics?.recoveryReason);
  if (recoveryReason != null) {
    return _debugRecoveryReasonSummary(recoveryReason);
  }
  final continuationKind = _trimmedDebugValue(run.lastEvent?.continuationKind);
  if (continuationKind != null) {
    return 'Continued via ${_humanizeDebugCode(continuationKind)}.';
  }
  return null;
}

String? _runAttemptReasonCode(OpenCrayChatRunSnapshot run) =>
    _trimmedDebugValue(run.recoveryPlan?.reasonCode) ??
    _trimmedDebugValue(run.diagnostics?.recoveryReason) ??
    _trimmedDebugValue(run.lastEvent?.continuationKind);

String _debugRecoveryReasonSummary(String code) {
  switch (code) {
    case 'host_restart_inflight_task_interrupted':
      return 'The host restarted while this run was in flight, so it required an explicit retry before continuing.';
    default:
      return '${_humanizeDebugCode(code)}.';
  }
}

String _humanizeDebugCode(String value) => value
    .split(RegExp(r'[_-]+'))
    .where((segment) => segment.isNotEmpty)
    .map(
      (segment) =>
          '${segment[0].toUpperCase()}${segment.substring(1).toLowerCase()}',
    )
    .join(' ');

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
    case 'tool_call':
      return _summarizeToolCallEvent(event);
    case 'tool_result':
      return _summarizeToolResultEvent(event);
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
