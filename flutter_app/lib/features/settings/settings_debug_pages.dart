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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: backLabel,
                ),
                title: 'Debug tools',
                summary:
                    'Inspect runtime ownership, trace, memory, soul state, and the embedded Python runner.',
              ),
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
                          openCrayHorizontalPageRoute<void>(
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
                          openCrayHorizontalPageRoute<void>(
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
                          openCrayHorizontalPageRoute<void>(
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
                          openCrayHorizontalPageRoute<void>(
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
                          openCrayHorizontalPageRoute<void>(
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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Run Python Script',
                summary:
                    'Execute a script through the embedded Android Python runtime used by the p4a debug path.',
              ),
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
                color: OpenCrayColors.surfaceSubtle,
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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Runtime Diagnostics',
                summary:
                    'Inspect detached runtime ownership, service keepalive, and bridge transport.',
              ),
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
    final flutterAppInstanceId = snapshot.flutterAppInstanceId?.trim();
    final bridgeInstanceId = snapshot.bridgeInstanceId?.trim();
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
                if (flutterAppInstanceId?.isNotEmpty == true)
                  _DebugValueChip(
                    label: 'Flutter app',
                    value: flutterAppInstanceId!,
                  ),
                if (bridgeInstanceId?.isNotEmpty == true)
                  _DebugValueChip(label: 'Bridge', value: bridgeInstanceId!),
              ],
            ),
            if (flutterAppInstanceId?.isNotEmpty == true ||
                bridgeInstanceId?.isNotEmpty == true) ...[
              const SizedBox(height: 12),
              const Text(
                'Client attach',
                style: _SettingsTextStyles.bodyStrong,
              ),
              const SizedBox(height: 8),
              if (flutterAppInstanceId?.isNotEmpty == true)
                _DebugKeyValueLine('Flutter app', flutterAppInstanceId!),
              if (bridgeInstanceId?.isNotEmpty == true)
                _DebugKeyValueLine('Bridge', bridgeInstanceId!),
            ],
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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Memory Inspector',
                summary: 'Review durable and session memory.',
              ),
              _buildStoreSummaryCard(),
              const SizedBox(height: 16),
              _buildFilterCard(),
              const SizedBox(height: 16),
              _buildProjectedMemorySearchCard(),
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
                    color: OpenCrayColors.surfaceSubtle,
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
              OpenCrayPageHeader(
                leading: _BackLink(
                  onTap: () => Navigator.of(context).pop(),
                  label: widget.backLabel,
                ),
                title: 'Soul Inspector',
                summary: 'Base preset, overlays, and effective soul.',
              ),
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
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: selected
            ? OpenCrayColors.primaryTint
            : OpenCrayColors.surfaceMuted,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: selected
              ? OpenCrayColors.primaryBorder
              : OpenCrayColors.divider,
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          onTap: onTap,
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
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: selected ? OpenCrayColors.surfaceMuted : Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: selected
              ? OpenCrayColors.primaryBorder
              : OpenCrayColors.divider,
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
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
    return AnimatedContainer(
      duration: OpenCrayMotion.resolve(context, OpenCrayMotion.micro),
      curve: OpenCrayMotion.enter,
      decoration: BoxDecoration(
        color: selected ? OpenCrayColors.surfaceMuted : Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: selected
              ? OpenCrayColors.primaryBorder
              : OpenCrayColors.divider,
        ),
      ),
      child: OpenCrayInkSurface(
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: onTap,
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
