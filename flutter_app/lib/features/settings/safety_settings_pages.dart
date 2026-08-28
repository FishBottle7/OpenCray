part of 'settings_feature.dart';

enum _SafetySubpage {
  root,
  sensitiveActions,
  fileChanges,
  shellCommands,
  fileDeletes,
  externalAccess,
  approvedPaths,
}

enum _WorkspaceSubpage { root, approvedPaths, childAgentContext }

class _SafetySettingsPage extends StatefulWidget {
  const _SafetySettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_SafetySettingsPage> createState() => _SafetySettingsPageState();
}

class _SafetySettingsPageState extends State<_SafetySettingsPage> {
  final List<_SafetySubpage> _stack = <_SafetySubpage>[_SafetySubpage.root];
  SafetySettingsSnapshot? _snapshot;
  bool _isLoading = true;
  bool _isSaving = false;
  String? _errorMessage;

  _SafetySubpage get _currentPage => _stack.last;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(onTap: _handleBack, label: _backLabel()),
            title: _title(),
            summary: _subtitle(_snapshot),
          ),
          if (_isLoading)
            const _SettingsLoading(
              key: ValueKey<String>('settings-safety-loading'),
            )
          else if (_snapshot == null)
            _SettingsCard(
              child: Text(
                _errorMessage ?? 'Safety settings are unavailable.',
                style: _SettingsTextStyles.body,
              ),
            )
          else ...[
            if (_errorMessage != null) ...[
              _SettingsCard(
                backgroundColor: OpenCrayColors.warningTint,
                child: Text(_errorMessage!, style: _SettingsTextStyles.body),
              ),
              const SizedBox(height: 16),
            ],
            ..._buildCurrentPage(_snapshot!),
          ],
        ],
      ),
    );
  }

  List<Widget> _buildCurrentPage(SafetySettingsSnapshot snapshot) {
    switch (_currentPage) {
      case _SafetySubpage.root:
        return _buildRoot(snapshot);
      case _SafetySubpage.sensitiveActions:
        return _buildSensitiveActions(snapshot);
      case _SafetySubpage.fileChanges:
        return _buildFileChanges(snapshot);
      case _SafetySubpage.shellCommands:
        return _buildShellCommands(snapshot);
      case _SafetySubpage.fileDeletes:
        return _buildFileDeletes(snapshot);
      case _SafetySubpage.externalAccess:
        return _buildExternalAccess(snapshot);
      case _SafetySubpage.approvedPaths:
        return _buildApprovedPaths(snapshot);
    }
  }

  List<Widget> _buildRoot(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Automation mode', style: _SettingsTextStyles.cardTitle),
            const SizedBox(height: 10),
            _EnumSegmentedSelector<SafetyAutomationMode>(
              values: SafetyAutomationMode.values,
              currentValue: snapshot.automationMode,
              labelBuilder: SafetySettingsCopy.automationModeLabel,
              onChanged: _isSaving
                  ? null
                  : (value) {
                      _persist(snapshot.copyWith(automationMode: value));
                    },
            ),
            const SizedBox(height: 12),
            Text(
              'Mode presets already control approvals and protected actions.',
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
              'Advanced overrides',
              style: _SettingsTextStyles.cardTitle,
            ),
            const SizedBox(height: 8),
            Text(
              'Optional. Most people can stay with the mode preset.',
              style: _SettingsTextStyles.body,
            ),
            const SizedBox(height: 12),
            _PrototypeDisclosureRow(
              title: 'Customize sensitive actions',
              onTap: () => _push(_SafetySubpage.sensitiveActions),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _SettingsCard(
        child: Column(
          children: [
            const Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Rollback & limits',
                style: _SettingsTextStyles.cardTitle,
              ),
            ),
            const SizedBox(height: 12),
            _PrototypeToggleTile(
              title: 'Rollback journal',
              subtitle: 'Keep reversible actions recoverable.',
              value: snapshot.rollbackJournalEnabled,
              enabled: !_isSaving,
              onChanged: (value) {
                _persist(snapshot.copyWith(rollbackJournalEnabled: value));
              },
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeValueRow(
              title: 'Max files per batch',
              value: '${snapshot.maxFilesPerBatch}',
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeStepperRow(
              title: SafetySettingsCopy.agentTurnLimitTitle,
              subtitle: SafetySettingsCopy.agentTurnLimitSubtitle,
              value: SafetySettingsCopy.agentTurnLimitValue(
                snapshot.maxAgentTurns,
              ),
              valueKey: const ValueKey<String>(
                'settings-safety-max-agent-turns-value',
              ),
              decrementLabel: '-',
              incrementLabel: '+',
              canDecrement: !_isSaving && snapshot.maxAgentTurns > 0,
              canIncrement: !_isSaving,
              onDecrement: () {
                if (_isSaving) {
                  return;
                }
                _persist(
                  snapshot.copyWith(
                    maxAgentTurns: snapshot.maxAgentTurns > 0
                        ? snapshot.maxAgentTurns - 1
                        : 0,
                  ),
                );
              },
              onValueTap: _isSaving
                  ? null
                  : () => _editLimitValue(
                      title: SafetySettingsCopy.agentTurnLimitTitle,
                      subtitle: SafetySettingsCopy.agentTurnLimitSubtitle,
                      currentValue: snapshot.maxAgentTurns,
                      onSaved: (value) =>
                          snapshot.copyWith(maxAgentTurns: value),
                    ),
              onIncrement: () {
                if (_isSaving) {
                  return;
                }
                _persist(
                  snapshot.copyWith(maxAgentTurns: snapshot.maxAgentTurns + 1),
                );
              },
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeStepperRow(
              title: SafetySettingsCopy.toolCallLimitTitle,
              subtitle: SafetySettingsCopy.toolCallLimitSubtitle,
              value: SafetySettingsCopy.toolCallLimitValue(
                snapshot.maxToolCalls,
              ),
              valueKey: const ValueKey<String>(
                'settings-safety-max-tool-calls-value',
              ),
              decrementLabel: '-',
              incrementLabel: '+',
              canDecrement: !_isSaving && snapshot.maxToolCalls > 0,
              canIncrement: !_isSaving,
              onDecrement: () {
                if (_isSaving) {
                  return;
                }
                _persist(
                  snapshot.copyWith(
                    maxToolCalls: snapshot.maxToolCalls > 0
                        ? snapshot.maxToolCalls - 1
                        : 0,
                  ),
                );
              },
              onValueTap: _isSaving
                  ? null
                  : () => _editLimitValue(
                      title: SafetySettingsCopy.toolCallLimitTitle,
                      subtitle: SafetySettingsCopy.toolCallLimitSubtitle,
                      currentValue: snapshot.maxToolCalls,
                      onSaved: (value) =>
                          snapshot.copyWith(maxToolCalls: value),
                    ),
              onIncrement: () {
                if (_isSaving) {
                  return;
                }
                _persist(
                  snapshot.copyWith(maxToolCalls: snapshot.maxToolCalls + 1),
                );
              },
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeValueRow(
              title: 'Undo window',
              value: '${snapshot.undoWindowHours} hours',
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildSensitiveActions(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _SettingsCard(
        child: Column(
          children: [
            _PrototypeDisclosureRow(
              title: 'File changes',
              value: _effectivePolicyLabel(
                snapshot.fileChangesPolicy,
                inherited: _inheritedOutcomeLabelForWrite(
                  snapshot.automationMode,
                ),
              ),
              verticalPadding: 14,
              onTap: () => _push(_SafetySubpage.fileChanges),
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeDisclosureRow(
              title: 'File deletes',
              value: _effectivePolicyLabel(
                snapshot.fileDeletesPolicy,
                inherited: _inheritedOutcomeLabelForDelete(
                  snapshot.automationMode,
                ),
              ),
              verticalPadding: 14,
              onTap: () => _push(_SafetySubpage.fileDeletes),
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeDisclosureRow(
              title: 'Shell commands',
              value: _effectivePolicyLabel(
                snapshot.shellCommandsPolicy,
                inherited: _inheritedOutcomeLabelForCommand(
                  snapshot.automationMode,
                ),
              ),
              verticalPadding: 14,
              onTap: () => _push(_SafetySubpage.shellCommands),
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _PrototypeDisclosureRow(
              title: 'External access',
              value: SafetySettingsCopy.externalAccessModeShortLabel(
                snapshot.externalAccessMode,
              ),
              verticalPadding: 14,
              onTap: () => _push(_SafetySubpage.externalAccess),
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildFileChanges(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _PolicySelectionCard(
        selected: snapshot.fileChangesPolicy,
        inheritedLabel: SafetySettingsCopy.automationModeDisplayLabel(
          snapshot.automationMode,
        ),
        onSelect: _isSaving
            ? null
            : (policy) {
                _persist(snapshot.copyWith(fileChangesPolicy: policy));
              },
      ),
    ];
  }

  List<Widget> _buildShellCommands(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _PolicySelectionCard(
        selected: snapshot.shellCommandsPolicy,
        inheritedLabel: SafetySettingsCopy.automationModeDisplayLabel(
          snapshot.automationMode,
        ),
        footnote:
            'Ask every time keeps commands available without making them automatic.',
        onSelect: _isSaving
            ? null
            : (policy) {
                _persist(snapshot.copyWith(shellCommandsPolicy: policy));
              },
      ),
    ];
  }

  List<Widget> _buildFileDeletes(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _PolicySelectionCard(
        selected: snapshot.fileDeletesPolicy,
        inheritedLabel: SafetySettingsCopy.automationModeDisplayLabel(
          snapshot.automationMode,
        ),
        onSelect: _isSaving
            ? null
            : (policy) {
                _persist(snapshot.copyWith(fileDeletesPolicy: policy));
              },
      ),
    ];
  }

  List<Widget> _buildExternalAccess(SafetySettingsSnapshot snapshot) {
    final togglesEnabled =
        !_isSaving &&
        snapshot.externalAccessMode == ExternalAccessMode.selectPaths;
    return <Widget>[
      _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Access model', style: _SettingsTextStyles.cardTitle),
            const SizedBox(height: 10),
            _EnumSegmentedSelector<ExternalAccessMode>(
              values: ExternalAccessMode.values,
              currentValue: snapshot.externalAccessMode,
              labelBuilder: SafetySettingsCopy.externalAccessModeLabel,
              onChanged: _isSaving
                  ? null
                  : (value) {
                      _persist(snapshot.copyWith(externalAccessMode: value));
                    },
            ),
            const SizedBox(height: 12),
            Text(
              'Private app sandboxes stay blocked.',
              style: _SettingsTextStyles.rowSubtitle,
            ),
          ],
        ),
      ),
      const SizedBox(height: 12),
      Text(
        'Public locations',
        style: _SettingsTextStyles.rowSubtitle.copyWith(
          fontWeight: FontWeight.w600,
        ),
      ),
      const SizedBox(height: 10),
      _SettingsCard(
        child: Column(
          children: [
            for (int index = 0; index < snapshot.locations.length; index++) ...[
              _PrototypeSwitchRow(
                title: SafetySettingsCopy.locationLabel(
                  snapshot.locations[index].id,
                ),
                value: snapshot.locations[index].enabled,
                enabled: togglesEnabled,
                onChanged: (value) {
                  _handleExternalLocationToggle(
                    snapshot: snapshot,
                    locationId: snapshot.locations[index].id,
                    enabled: value,
                  );
                },
              ),
              if (index < snapshot.locations.length - 1)
                const Divider(height: 1, color: OpenCrayColors.divider),
            ],
          ],
        ),
      ),
      const SizedBox(height: 12),
      Text(
        'Good for photos and shared downloads.',
        style: _SettingsTextStyles.rowSubtitle,
      ),
    ];
  }

  List<Widget> _buildApprovedPaths(SafetySettingsSnapshot snapshot) {
    final visibleLocations =
        snapshot.externalAccessMode == ExternalAccessMode.selectPaths
        ? snapshot.locations.where((location) => location.enabled).toList()
        : const <SafetyLocationSetting>[];
    return <Widget>[
      _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Always approved', style: _SettingsTextStyles.cardTitle),
            const SizedBox(height: 12),
            const _ApprovedPathTile(
              title: 'Workspace root',
              subtitle: 'Primary root for agent reads, edits, and checkpoints.',
              tone: 'positive',
            ),
            if (visibleLocations.isNotEmpty) ...[
              const SizedBox(height: 14),
              const Text(
                'Selective public locations',
                style: _SettingsTextStyles.cardTitle,
              ),
              const SizedBox(height: 10),
              for (int index = 0; index < visibleLocations.length; index++) ...[
                _ApprovedPathTile(
                  title: SafetySettingsCopy.locationLabel(
                    visibleLocations[index].id,
                  ),
                  subtitle:
                      'Enabled through External access while selective paths stay active.',
                ),
                if (index < visibleLocations.length - 1)
                  const SizedBox(height: 10),
              ],
            ] else ...[
              const SizedBox(height: 14),
              Text(
                'No extra public locations are currently approved.',
                style: _SettingsTextStyles.body,
              ),
            ],
          ],
        ),
      ),
    ];
  }

  String _title() {
    switch (_currentPage) {
      case _SafetySubpage.root:
        return SafetySettingsCopy.safetyTitle;
      case _SafetySubpage.sensitiveActions:
        return SafetySettingsCopy.sensitiveActionsTitle;
      case _SafetySubpage.fileChanges:
        return SafetySettingsCopy.fileChangesTitle;
      case _SafetySubpage.shellCommands:
        return SafetySettingsCopy.shellCommandsTitle;
      case _SafetySubpage.fileDeletes:
        return SafetySettingsCopy.fileDeletesTitle;
      case _SafetySubpage.externalAccess:
        return SafetySettingsCopy.externalAccessTitle;
      case _SafetySubpage.approvedPaths:
        return SafetySettingsCopy.approvedPathsTitle;
    }
  }

  String _subtitle(SafetySettingsSnapshot? snapshot) {
    switch (_currentPage) {
      case _SafetySubpage.root:
        return SafetySettingsCopy.safetySubtitle;
      case _SafetySubpage.sensitiveActions:
        return 'Overrides only the actions you change.\nCurrent preset: ${SafetySettingsCopy.automationModeDisplayLabel(snapshot?.automationMode ?? SafetyAutomationMode.auto)}';
      case _SafetySubpage.fileChanges:
        return SafetySettingsCopy.fileChangesSubtitle;
      case _SafetySubpage.shellCommands:
        return SafetySettingsCopy.shellCommandsSubtitle;
      case _SafetySubpage.fileDeletes:
        return SafetySettingsCopy.fileDeletesSubtitle;
      case _SafetySubpage.externalAccess:
        return SafetySettingsCopy.externalAccessSubtitle;
      case _SafetySubpage.approvedPaths:
        return SafetySettingsCopy.approvedPathsSubtitle;
    }
  }

  String _backLabel() {
    if (_stack.length == 1) {
      return widget.backLabel;
    }
    final previous = _stack[_stack.length - 2];
    switch (previous) {
      case _SafetySubpage.root:
        return SafetySettingsCopy.safetyTitle;
      case _SafetySubpage.sensitiveActions:
        return SafetySettingsCopy.sensitiveActionsTitle;
      case _SafetySubpage.fileChanges:
        return SafetySettingsCopy.fileChangesTitle;
      case _SafetySubpage.shellCommands:
        return SafetySettingsCopy.shellCommandsTitle;
      case _SafetySubpage.fileDeletes:
        return SafetySettingsCopy.fileDeletesTitle;
      case _SafetySubpage.externalAccess:
        return SafetySettingsCopy.externalAccessTitle;
      case _SafetySubpage.approvedPaths:
        return SafetySettingsCopy.approvedPathsTitle;
    }
  }

  void _push(_SafetySubpage page) {
    setState(() {
      _stack.add(page);
    });
  }

  void _handleBack() {
    if (_stack.length == 1) {
      widget.onBack();
      return;
    }
    setState(() {
      _stack.removeLast();
    });
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadSafetySettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoading = false;
        _errorMessage = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _errorMessage = 'Failed to load safety settings: $error';
      });
    }
  }

  Future<void> _persist(SafetySettingsSnapshot snapshot) async {
    setState(() {
      _snapshot = snapshot;
      _isSaving = true;
      _errorMessage = null;
    });
    try {
      final saved = await widget.facade.saveSafetySettings(snapshot);
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = saved;
        _isSaving = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSaving = false;
        _errorMessage = 'Failed to save safety settings: $error';
      });
    }
  }

  Future<void> _editLimitValue({
    required String title,
    required String subtitle,
    required int currentValue,
    required SafetySettingsSnapshot Function(int value) onSaved,
  }) async {
    final nextValue = await _promptForNonNegativeInt(
      title: title,
      subtitle: subtitle,
      currentValue: currentValue,
    );
    if (!mounted || nextValue == null) {
      return;
    }
    await _persist(onSaved(nextValue));
  }

  Future<int?> _promptForNonNegativeInt({
    required String title,
    required String subtitle,
    required int currentValue,
  }) async {
    final controller = TextEditingController(text: currentValue.toString());
    final result = await showDialog<int>(
      context: context,
      builder: (dialogContext) {
        String? errorText;

        return StatefulBuilder(
          builder: (dialogContext, setDialogState) {
            void submit() {
              final parsed = int.tryParse(controller.text.trim());
              if (parsed == null || parsed < 0) {
                setDialogState(() {
                  errorText = SafetySettingsCopy.limitDialogValidationError;
                });
                return;
              }
              Navigator.of(dialogContext).pop(parsed);
            }

            return AlertDialog(
              title: Text(title),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    subtitle,
                    style: Theme.of(dialogContext).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: controller,
                    autofocus: true,
                    keyboardType: TextInputType.number,
                    decoration: InputDecoration(
                      labelText: SafetySettingsCopy.limitDialogValueLabel,
                      helperText: SafetySettingsCopy.limitDialogNoLimitHint,
                      errorText: errorText,
                    ),
                    onChanged: (_) {
                      if (errorText == null) {
                        return;
                      }
                      setDialogState(() {
                        errorText = null;
                      });
                    },
                    onSubmitted: (_) => submit(),
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(dialogContext).pop(),
                  child: const Text('Cancel'),
                ),
                TextButton(onPressed: submit, child: const Text('Save')),
              ],
            );
          },
        );
      },
    );
    await WidgetsBinding.instance.endOfFrame;
    controller.dispose();
    return result;
  }

  Future<void> _handleExternalLocationToggle({
    required SafetySettingsSnapshot snapshot,
    required String locationId,
    required bool enabled,
  }) async {
    final nextSnapshot = snapshot.withLocationEnabled(locationId, enabled);
    if (!enabled) {
      await _persist(nextSnapshot);
      return;
    }
    try {
      final granted = await widget.facade.authorizeExternalAccessLocation(
        locationId,
      );
      if (!mounted) {
        return;
      }
      if (!granted) {
        setState(() {
          _errorMessage =
              '${SafetySettingsCopy.locationLabel(locationId)} access is unavailable or was not granted.';
        });
        return;
      }
      await _persist(nextSnapshot);
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _errorMessage =
            'Failed to authorize ${SafetySettingsCopy.locationLabel(locationId)}: $error';
      });
    }
  }
}

class _WorkspaceAccessSettingsPage extends StatefulWidget {
  const _WorkspaceAccessSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_WorkspaceAccessSettingsPage> createState() =>
      _WorkspaceAccessSettingsPageState();
}

class _WorkspaceAccessSettingsPageState
    extends State<_WorkspaceAccessSettingsPage> {
  _WorkspaceSubpage _page = _WorkspaceSubpage.root;
  SafetySettingsSnapshot? _snapshot;
  bool _isLoading = true;
  bool _isSaving = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OpenCrayPageHeader(
            leading: _BackLink(
              onTap: () {
              if (_page == _WorkspaceSubpage.root) {
              widget.onBack();
              } else {
              setState(() {
              _page = _WorkspaceSubpage.root;
              });
              }
              },
              label: _page == _WorkspaceSubpage.root
              ? widget.backLabel
              : SafetySettingsCopy.workspaceTitle,
            ),
            title:
                _page == _WorkspaceSubpage.root ? SafetySettingsCopy.workspaceTitle : _page == _WorkspaceSubpage.approvedPaths ? SafetySettingsCopy.approvedPathsTitle : SafetySettingsCopy.childAgentContextTitle,
            summary:
                _page == _WorkspaceSubpage.root ? SafetySettingsCopy.workspaceSubtitle : _page == _WorkspaceSubpage.approvedPaths ? SafetySettingsCopy.approvedPathsSubtitle : SafetySettingsCopy.childAgentContextSubtitle,
          ),
          if (_isLoading)
            const _SettingsLoading(
              key: ValueKey<String>('settings-workspace-loading'),
            )
          else if (_snapshot == null)
            _SettingsCard(
              child: Text(
                _errorMessage ?? 'Workspace access settings are unavailable.',
                style: _SettingsTextStyles.body,
              ),
            )
          else ...[
            if (_errorMessage != null) ...[
              _SettingsCard(
                backgroundColor: OpenCrayColors.warningTint,
                child: Text(_errorMessage!, style: _SettingsTextStyles.body),
              ),
              const SizedBox(height: 16),
            ],
            ...(_page == _WorkspaceSubpage.root
                ? _buildWorkspaceAccessShared(
                    _snapshot!,
                    isSaving: _isSaving,
                    onPersist: _persist,
                    onOpenLiveContextModePicker: _openLiveContextModePicker,
                    onOpenChildAgentContext: () {
                      setState(() {
                        _page = _WorkspaceSubpage.childAgentContext;
                      });
                    },
                    onOpenApprovedPaths: () {
                      setState(() {
                        _page = _WorkspaceSubpage.approvedPaths;
                      });
                    },
                  )
                : _page == _WorkspaceSubpage.approvedPaths
                ? _buildApprovedPathsContent(_snapshot!)
                : _buildChildAgentContextContent(_snapshot!)),
          ],
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadSafetySettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _isLoading = false;
        _errorMessage = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _errorMessage = 'Failed to load workspace access: $error';
      });
    }
  }

  Future<void> _openLiveContextModePicker() async {
    final snapshot = _snapshot;
    if (snapshot == null || _isSaving) {
      return;
    }
    final selected = await showModalBottomSheet<LiveContextMode>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        final maxHeight = MediaQuery.sizeOf(context).height * 0.85;
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxHeight: maxHeight),
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.all(Radius.circular(22)),
                ),
                child: SingleChildScrollView(
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
                        SafetySettingsCopy.liveContextTitle,
                        style: _SettingsTextStyles.cardTitle,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        SafetySettingsCopy.liveContextSubtitle,
                        style: _SettingsTextStyles.body,
                      ),
                      const SizedBox(height: 12),
                      for (final option in LiveContextMode.values)
                        InkWell(
                          borderRadius: BorderRadius.circular(14),
                          onTap: () => Navigator.of(context).pop(option),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        SafetySettingsCopy.liveContextModeLabel(
                                          option,
                                        ),
                                        style: _SettingsTextStyles.rowTitle,
                                      ),
                                      const SizedBox(height: 4),
                                      Text(
                                        SafetySettingsCopy.liveContextModeSummary(
                                          option,
                                        ),
                                        style: _SettingsTextStyles.rowSubtitle,
                                      ),
                                    ],
                                  ),
                                ),
                                if (option == snapshot.liveContextMode)
                                  const Padding(
                                    padding: EdgeInsets.only(left: 12, top: 2),
                                    child: Icon(
                                      Icons.check_rounded,
                                      color: OpenCrayColors.primary,
                                      size: 18,
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
          ),
        );
      },
    );
    if (selected == null || selected == snapshot.liveContextMode) {
      return;
    }
    await _persist(snapshot.copyWith(liveContextMode: selected));
  }

  Future<void> _openSubAgentContextDefaultModePicker() async {
    final snapshot = _snapshot;
    if (snapshot == null || _isSaving) {
      return;
    }
    final selectedMode = await _showSubAgentContextModePicker(
      title: SafetySettingsCopy.childAgentContextTitle,
      subtitle: SafetySettingsCopy.childAgentContextSubtitle,
      selectedMode: snapshot.subAgentContextDefaultMode,
      inheritLabel: 'Profile default',
      inheritSummary: SafetySettingsCopy.subAgentContextModeSummary(null),
    );
    if (selectedMode == null && snapshot.subAgentContextDefaultMode == null) {
      return;
    }
    if (selectedMode == snapshot.subAgentContextDefaultMode) {
      return;
    }
    await _persist(snapshot.withSubAgentContextDefaultMode(selectedMode));
  }

  Future<void> _openSubAgentContextProfileOverridePicker(String profileId) async {
    final snapshot = _snapshot;
    if (snapshot == null || _isSaving) {
      return;
    }
    final currentMode = snapshot.subAgentContextModeForProfile(profileId);
    final selectedMode = await _showSubAgentContextModePicker(
      title: SafetySettingsCopy.subAgentProfileLabel(profileId),
      subtitle: SafetySettingsCopy.subAgentContextOverrideSummary(profileId),
      selectedMode: currentMode,
      inheritLabel: 'Use default',
      inheritSummary:
          'Fall through to the global child-agent default, or the built-in profile default when no global default is set.',
    );
    if (selectedMode == null && currentMode == null) {
      return;
    }
    if (selectedMode == currentMode) {
      return;
    }
    await _persist(
      snapshot.withSubAgentContextProfileOverride(profileId, selectedMode),
    );
  }

  Future<SubAgentContextMode?> _showSubAgentContextModePicker({
    required String title,
    required String subtitle,
    required SubAgentContextMode? selectedMode,
    required String inheritLabel,
    required String inheritSummary,
  }) async {
    final selectedId = selectedMode?.id ?? '__inherit__';
    final result = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      sheetAnimationStyle: OpenCrayMotion.sheetAnimationStyle(context),
      builder: (context) {
        final maxHeight = MediaQuery.sizeOf(context).height * 0.85;
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxHeight: maxHeight),
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.all(Radius.circular(22)),
                ),
                child: SingleChildScrollView(
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
                      const SizedBox(height: 8),
                      Text(subtitle, style: _SettingsTextStyles.body),
                      const SizedBox(height: 12),
                      for (final option in <MapEntry<String, SubAgentContextMode?>>[
                        MapEntry<String, SubAgentContextMode?>(
                          '__inherit__',
                          null,
                        ),
                        ...SubAgentContextMode.values.map(
                          (mode) => MapEntry<String, SubAgentContextMode?>(
                            mode.id,
                            mode,
                          ),
                        ),
                      ])
                        InkWell(
                          borderRadius: BorderRadius.circular(14),
                          onTap: () => Navigator.of(context).pop(option.key),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        option.value == null
                                            ? inheritLabel
                                            : SafetySettingsCopy
                                                  .subAgentContextModeLabel(
                                                    option.value,
                                                  ),
                                        style: _SettingsTextStyles.rowTitle,
                                      ),
                                      const SizedBox(height: 4),
                                      Text(
                                        option.value == null
                                            ? inheritSummary
                                            : SafetySettingsCopy
                                                  .subAgentContextModeSummary(
                                                    option.value,
                                                  ),
                                        style: _SettingsTextStyles.rowSubtitle,
                                      ),
                                    ],
                                  ),
                                ),
                                if (option.key == selectedId)
                                  const Padding(
                                    padding: EdgeInsets.only(left: 12, top: 2),
                                    child: Icon(
                                      Icons.check_rounded,
                                      color: OpenCrayColors.primary,
                                      size: 18,
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
          ),
        );
      },
    );
    if (result == null) {
      return selectedMode;
    }
    return result == '__inherit__' ? null : subAgentContextModeFromId(result);
  }

  Future<void> _persist(SafetySettingsSnapshot snapshot) async {
    setState(() {
      _snapshot = snapshot;
      _isSaving = true;
      _errorMessage = null;
    });
    try {
      final saved = await widget.facade.saveSafetySettings(snapshot);
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = saved;
        _isSaving = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSaving = false;
        _errorMessage = 'Failed to save workspace access: $error';
      });
    }
  }

  List<Widget> _buildChildAgentContextContent(SafetySettingsSnapshot snapshot) {
    return <Widget>[
      _SettingsCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Default mode', style: _SettingsTextStyles.cardTitle),
            const SizedBox(height: 8),
            Text(
              'This applies when a specific child-agent profile has no explicit override.',
              style: _SettingsTextStyles.body,
            ),
            const SizedBox(height: 12),
            _PrototypeDisclosureRow(
              title: 'Mode',
              value: SafetySettingsCopy.subAgentContextModeLabel(
                snapshot.subAgentContextDefaultMode,
              ),
              onTap: _isSaving ? null : _openSubAgentContextDefaultModePicker,
            ),
            const SizedBox(height: 12),
            Text(
              SafetySettingsCopy.subAgentContextModeSummary(
                snapshot.subAgentContextDefaultMode,
              ),
              style: _SettingsTextStyles.rowSubtitle,
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
              'Profile overrides',
              style: _SettingsTextStyles.cardTitle,
            ),
            const SizedBox(height: 8),
            Text(
              'Optional. Use these only when one built-in child profile should behave differently from the shared default.',
              style: _SettingsTextStyles.body,
            ),
            const SizedBox(height: 12),
            for (int index = 0;
                index < builtInSubAgentProfileIds.length;
                index++) ...[
              _PrototypeDisclosureRow(
                title: SafetySettingsCopy.subAgentProfileLabel(
                  builtInSubAgentProfileIds[index],
                ),
                value: SafetySettingsCopy.subAgentContextOverrideLabel(
                  snapshot.subAgentContextModeForProfile(
                    builtInSubAgentProfileIds[index],
                  ),
                ),
                verticalPadding: 14,
                onTap: _isSaving
                    ? null
                    : () => _openSubAgentContextProfileOverridePicker(
                        builtInSubAgentProfileIds[index],
                      ),
              ),
              const SizedBox(height: 8),
              Text(
                SafetySettingsCopy.subAgentContextOverrideSummary(
                  builtInSubAgentProfileIds[index],
                ),
                style: _SettingsTextStyles.rowSubtitle,
              ),
              if (index < builtInSubAgentProfileIds.length - 1) ...[
                const SizedBox(height: 12),
                const Divider(height: 1, color: OpenCrayColors.divider),
                const SizedBox(height: 12),
              ],
            ],
          ],
        ),
      ),
    ];
  }
}

List<Widget> _buildWorkspaceAccessShared(
  SafetySettingsSnapshot snapshot, {
  required bool isSaving,
  required Future<void> Function(SafetySettingsSnapshot snapshot) onPersist,
  required VoidCallback onOpenLiveContextModePicker,
  required VoidCallback onOpenChildAgentContext,
  required VoidCallback onOpenApprovedPaths,
}) {
  return <Widget>[
    _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            SafetySettingsCopy.liveContextTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 8),
          Text(
            SafetySettingsCopy.liveContextSubtitle,
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 12),
          _PrototypeDisclosureRow(
            title: 'Mode',
            value: SafetySettingsCopy.liveContextModeLabel(
              snapshot.liveContextMode,
            ),
            onTap: isSaving ? null : onOpenLiveContextModePicker,
          ),
          const SizedBox(height: 12),
          Text(
            SafetySettingsCopy.liveContextModeSummary(snapshot.liveContextMode),
            style: _SettingsTextStyles.rowSubtitle,
          ),
          const SizedBox(height: 16),
          _PrototypeSwitchRow(
            title: SafetySettingsCopy.memoryToolsTitle,
            value: snapshot.memoryToolsEnabled,
            enabled: !isSaving,
            onChanged: (value) {
              onPersist(snapshot.copyWith(memoryToolsEnabled: value));
            },
          ),
          const SizedBox(height: 8),
          Text(
            SafetySettingsCopy.memoryToolsSummary(snapshot.memoryToolsEnabled),
            style: _SettingsTextStyles.rowSubtitle,
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
            SafetySettingsCopy.childAgentContextTitle,
            style: _SettingsTextStyles.cardTitle,
          ),
          const SizedBox(height: 8),
          Text(
            SafetySettingsCopy.childAgentContextSubtitle,
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 12),
          _PrototypeDisclosureRow(
            title: 'Default mode',
            value: SafetySettingsCopy.subAgentContextModeLabel(
              snapshot.subAgentContextDefaultMode,
            ),
            onTap: isSaving ? null : onOpenChildAgentContext,
          ),
          const SizedBox(height: 8),
          Text(
            SafetySettingsCopy.subAgentContextModeSummary(
              snapshot.subAgentContextDefaultMode,
            ),
            style: _SettingsTextStyles.rowSubtitle,
          ),
        ],
      ),
    ),
    const SizedBox(height: 16),
    _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Access profile', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 10),
          _EnumSegmentedSelector<WorkspaceAccessProfile>(
            values: WorkspaceAccessProfile.values,
            currentValue: snapshot.workspaceAccessProfile,
            labelBuilder: SafetySettingsCopy.workspaceProfileLabel,
            onChanged: isSaving
                ? null
                : (value) {
                    onPersist(snapshot.copyWith(workspaceAccessProfile: value));
                  },
          ),
          const SizedBox(height: 12),
          Text(
            'Profiles decide read and write scope.',
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
          const Text('Allowed roots', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(
            'Keep file work inside approved folders.',
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 12),
          _PrototypeDisclosureRow(
            title: 'Review approved paths',
            onTap: onOpenApprovedPaths,
          ),
        ],
      ),
    ),
    const SizedBox(height: 16),
    _SettingsCard(
      child: Column(
        children: [
          const Align(
            alignment: Alignment.centerLeft,
            child: Text('Write behavior', style: _SettingsTextStyles.cardTitle),
          ),
          const SizedBox(height: 12),
          _PrototypeToggleTile(
            title: 'Read-only outside workspace',
            subtitle: 'Writes stay inside approved roots.',
            value: snapshot.readOnlyOutsideWorkspace,
            enabled: !isSaving,
            onChanged: (value) {
              onPersist(snapshot.copyWith(readOnlyOutsideWorkspace: value));
            },
          ),
          const Divider(height: 1, color: OpenCrayColors.divider),
          _PrototypeValueRow(
            title: 'Approved roots',
            value: '${snapshot.approvedRootsCount}',
          ),
          const Divider(height: 1, color: OpenCrayColors.divider),
          _PrototypeValueRow(
            title: 'Ask before edit',
            value: _askBeforeEditLabel(snapshot.workspaceAccessProfile),
          ),
        ],
      ),
    ),
  ];
}

List<Widget> _buildApprovedPathsContent(SafetySettingsSnapshot snapshot) {
  final visibleLocations =
      snapshot.externalAccessMode == ExternalAccessMode.selectPaths
      ? snapshot.locations.where((location) => location.enabled).toList()
      : const <SafetyLocationSetting>[];
  return <Widget>[
    _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Always approved', style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 12),
          const _ApprovedPathTile(
            title: 'Workspace root',
            subtitle: 'Primary root for agent reads, edits, and checkpoints.',
            tone: 'positive',
          ),
          if (visibleLocations.isNotEmpty) ...[
            const SizedBox(height: 14),
            const Text(
              'Selective public locations',
              style: _SettingsTextStyles.cardTitle,
            ),
            const SizedBox(height: 10),
            for (int index = 0; index < visibleLocations.length; index++) ...[
              _ApprovedPathTile(
                title: SafetySettingsCopy.locationLabel(
                  visibleLocations[index].id,
                ),
                subtitle:
                    'Enabled through External access while selective paths stay active.',
              ),
              if (index < visibleLocations.length - 1)
                const SizedBox(height: 10),
            ],
          ] else ...[
            const SizedBox(height: 14),
            Text(
              'No extra public locations are currently approved.',
              style: _SettingsTextStyles.body,
            ),
          ],
        ],
      ),
    ),
  ];
}

String _askBeforeEditLabel(WorkspaceAccessProfile profile) {
  switch (profile) {
    case WorkspaceAccessProfile.work:
      return 'Always';
    case WorkspaceAccessProfile.ask:
      return 'Always';
    case WorkspaceAccessProfile.open:
      return 'Approved';
  }
}

String _effectivePolicyLabel(
  ToolPolicyOverride policy, {
  required String inherited,
}) {
  switch (policy) {
    case ToolPolicyOverride.inherit:
      return inherited;
    case ToolPolicyOverride.ask:
      return 'Ask';
    case ToolPolicyOverride.allow:
      return 'Allow';
    case ToolPolicyOverride.block:
      return 'Block';
  }
}

String _inheritedOutcomeLabelForWrite(SafetyAutomationMode mode) {
  switch (mode) {
    case SafetyAutomationMode.safe:
      return 'Ask';
    case SafetyAutomationMode.auto:
      return 'Allow';
    case SafetyAutomationMode.dev:
      return 'Allow';
  }
}

String _inheritedOutcomeLabelForDelete(SafetyAutomationMode mode) {
  switch (mode) {
    case SafetyAutomationMode.safe:
      return 'Ask';
    case SafetyAutomationMode.auto:
      return 'Ask';
    case SafetyAutomationMode.dev:
      return 'Allow';
  }
}

String _inheritedOutcomeLabelForCommand(SafetyAutomationMode mode) {
  switch (mode) {
    case SafetyAutomationMode.safe:
      return 'Ask';
    case SafetyAutomationMode.auto:
      return 'Ask';
    case SafetyAutomationMode.dev:
      return 'Allow';
  }
}
