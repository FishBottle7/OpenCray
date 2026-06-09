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

enum _WorkspaceSubpage { root, approvedPaths }

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
          _BackLink(onTap: _handleBack, label: _backLabel()),
          const SizedBox(height: 8),
          Text(_title(), style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(_subtitle(_snapshot), style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
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
                backgroundColor: const Color(0xFFFFF3E4),
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
          _BackLink(
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
          const SizedBox(height: 8),
          Text(
            _page == _WorkspaceSubpage.root
                ? SafetySettingsCopy.workspaceTitle
                : SafetySettingsCopy.approvedPathsTitle,
            style: _SettingsTextStyles.pageTitleSubpage,
          ),
          const SizedBox(height: 8),
          Text(
            _page == _WorkspaceSubpage.root
                ? SafetySettingsCopy.workspaceSubtitle
                : SafetySettingsCopy.approvedPathsSubtitle,
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
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
                backgroundColor: const Color(0xFFFFF3E4),
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
                    onOpenApprovedPaths: () {
                      setState(() {
                        _page = _WorkspaceSubpage.approvedPaths;
                      });
                    },
                  )
                : _buildApprovedPathsContent(_snapshot!)),
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
}

List<Widget> _buildWorkspaceAccessShared(
  SafetySettingsSnapshot snapshot, {
  required bool isSaving,
  required Future<void> Function(SafetySettingsSnapshot snapshot) onPersist,
  required VoidCallback onOpenLiveContextModePicker,
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

class _EnumSegmentedSelector<T> extends StatelessWidget {
  const _EnumSegmentedSelector({
    required this.values,
    required this.currentValue,
    required this.labelBuilder,
    required this.onChanged,
  });

  final List<T> values;
  final T currentValue;
  final String Function(T value) labelBuilder;
  final ValueChanged<T>? onChanged;

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
            for (final value in values)
              Expanded(
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: onChanged == null ? null : () => onChanged!(value),
                  child: AnimatedContainer(
                    duration: OpenCrayMotion.resolve(
                      context,
                      OpenCrayMotion.micro,
                    ),
                    curve: OpenCrayMotion.enter,
                    decoration: BoxDecoration(
                      color: value == currentValue
                          ? Colors.white
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(999),
                    ),
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    child: Text(
                      labelBuilder(value),
                      textAlign: TextAlign.center,
                      style: _SettingsTextStyles.valueChip.copyWith(
                        color: value == currentValue
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

class _PrototypeToggleTile extends StatelessWidget {
  const _PrototypeToggleTile({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: _SettingsTextStyles.rowTitle),
                const SizedBox(height: 2),
                Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
              ],
            ),
          ),
          const SizedBox(width: 12),
          _PrototypeSwitch(value: value, onChanged: enabled ? onChanged : null),
        ],
      ),
    );
  }
}

class _PrototypeSwitchRow extends StatelessWidget {
  const _PrototypeSwitchRow({
    required this.title,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final String title;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
          const SizedBox(width: 12),
          _PrototypeSwitch(value: value, onChanged: enabled ? onChanged : null),
        ],
      ),
    );
  }
}

class _PrototypeDisclosureRow extends StatelessWidget {
  const _PrototypeDisclosureRow({
    required this.title,
    required this.onTap,
    this.value,
    this.verticalPadding = 12,
  });

  final String title;
  final String? value;
  final VoidCallback? onTap;
  final double verticalPadding;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: verticalPadding),
        child: Row(
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            if (value != null) ...[
              Text(
                value!,
                style: _SettingsTextStyles.rowTitle.copyWith(
                  color: OpenCrayColors.textSecondary,
                ),
              ),
              const SizedBox(width: 8),
            ],
            const Text(
              '›',
              style: TextStyle(
                color: Color(0xFFC7C7CC),
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PrototypeValueRow extends StatelessWidget {
  const _PrototypeValueRow({required this.title, required this.value});

  final String title;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
          _PrototypeValuePill(value: value),
        ],
      ),
    );
  }
}

class _PrototypeStepperRow extends StatelessWidget {
  const _PrototypeStepperRow({
    required this.title,
    required this.subtitle,
    required this.value,
    this.valueKey,
    required this.decrementLabel,
    required this.incrementLabel,
    required this.canDecrement,
    required this.canIncrement,
    required this.onDecrement,
    this.onValueTap,
    required this.onIncrement,
  });

  final String title;
  final String subtitle;
  final String value;
  final Key? valueKey;
  final String decrementLabel;
  final String incrementLabel;
  final bool canDecrement;
  final bool canIncrement;
  final VoidCallback onDecrement;
  final VoidCallback? onValueTap;
  final VoidCallback onIncrement;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
              const SizedBox(width: 12),
              _StepperButton(
                label: decrementLabel,
                enabled: canDecrement,
                onTap: onDecrement,
              ),
              const SizedBox(width: 8),
              _PrototypeValuePill(
                key: valueKey,
                value: value,
                onTap: onValueTap,
              ),
              const SizedBox(width: 8),
              _StepperButton(
                label: incrementLabel,
                enabled: canIncrement,
                onTap: onIncrement,
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
        ],
      ),
    );
  }
}

class _StepperButton extends StatelessWidget {
  const _StepperButton({
    required this.label,
    required this.enabled,
    required this.onTap,
  });

  final String label;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(999),
      onTap: enabled ? onTap : null,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: enabled ? const Color(0xFFF7F7FA) : const Color(0xFFF0F0F3),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Text(
            label,
            style: _SettingsTextStyles.valueChip.copyWith(
              color: enabled
                  ? OpenCrayColors.textPrimary
                  : OpenCrayColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _PrototypeValuePill extends StatelessWidget {
  const _PrototypeValuePill({super.key, required this.value, this.onTap});

  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final child = DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF7F7FA),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(value, style: _SettingsTextStyles.valueChip),
      ),
    );
    if (onTap == null) {
      return child;
    }
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: child,
    );
  }
}

class _PolicySelectionCard extends StatelessWidget {
  const _PolicySelectionCard({
    required this.selected,
    required this.onSelect,
    required this.inheritedLabel,
    this.footnote,
  });

  final ToolPolicyOverride selected;
  final ValueChanged<ToolPolicyOverride>? onSelect;
  final String inheritedLabel;
  final String? footnote;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SettingsCard(
          child: Column(
            children: [
              for (
                int index = 0;
                index < ToolPolicyOverride.values.length;
                index++
              ) ...[
                _PolicyOptionTile(
                  title: SafetySettingsCopy.policyLabel(
                    ToolPolicyOverride.values[index],
                  ),
                  trailingLabel:
                      ToolPolicyOverride.values[index] ==
                              ToolPolicyOverride.inherit &&
                          selected != ToolPolicyOverride.inherit
                      ? inheritedLabel
                      : null,
                  selected: selected == ToolPolicyOverride.values[index],
                  onTap: onSelect == null
                      ? null
                      : () {
                          onSelect!(ToolPolicyOverride.values[index]);
                        },
                ),
                if (index < ToolPolicyOverride.values.length - 1)
                  const Divider(height: 1, color: OpenCrayColors.divider),
              ],
            ],
          ),
        ),
        if (footnote != null) ...[
          const SizedBox(height: 12),
          Text(footnote!, style: _SettingsTextStyles.rowSubtitle),
        ],
      ],
    );
  }
}

class _PolicyOptionTile extends StatelessWidget {
  const _PolicyOptionTile({
    required this.title,
    required this.selected,
    required this.onTap,
    this.trailingLabel,
  });

  final String title;
  final bool selected;
  final VoidCallback? onTap;
  final String? trailingLabel;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(child: Text(title, style: _SettingsTextStyles.rowTitle)),
            const SizedBox(width: 12),
            if (selected)
              const Icon(
                Icons.check_rounded,
                size: 18,
                color: OpenCrayColors.primary,
              )
            else if (trailingLabel != null)
              Text(
                trailingLabel!,
                style: _SettingsTextStyles.rowTitle.copyWith(
                  color: OpenCrayColors.textSecondary,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _ApprovedPathTile extends StatelessWidget {
  const _ApprovedPathTile({
    required this.title,
    required this.subtitle,
    this.tone = 'neutral',
  });

  final String title;
  final String subtitle;
  final String tone;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFF7F7FA),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: _SettingsTextStyles.rowTitle),
                  const SizedBox(height: 4),
                  Text(subtitle, style: _SettingsTextStyles.rowSubtitle),
                ],
              ),
            ),
            const SizedBox(width: 12),
            _SettingsStatusPill(
              label: tone == 'positive' ? 'Included' : 'Approved',
              tone: tone,
            ),
          ],
        ),
      ),
    );
  }
}
