part of 'settings_feature.dart';

class _ScheduledTasksSettingsPage extends StatefulWidget {
  const _ScheduledTasksSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
    required this.onOpenTask,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;
  final ValueChanged<String> onOpenTask;

  @override
  State<_ScheduledTasksSettingsPage> createState() =>
      _ScheduledTasksSettingsPageState();
}

class _ScheduledTasksSettingsPageState
    extends State<_ScheduledTasksSettingsPage> {
  ScheduledTasksSnapshot? _snapshot;
  String? _loadError;
  String? _busyScheduleId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final copy = _ScheduledTaskSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-scheduled-tasks-loading'),
            )
          : _SettingsLoadErrorCard(
              title: copy.tasksTitle,
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
          Text(copy.tasksTitle, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(
            copy.taskCount(snapshot.totalCount),
            style: _SettingsTextStyles.subtitle,
          ),
          const SizedBox(height: 16),
          if (snapshot.tasks.isEmpty)
            _SettingsCard(
              child: Text(copy.emptyTasks, style: _SettingsTextStyles.body),
            )
          else
            for (int index = 0; index < snapshot.tasks.length; index++) ...[
              _ScheduledTaskListCard(
                task: snapshot.tasks[index],
                copy: copy,
                isBusy: _busyScheduleId == snapshot.tasks[index].scheduleId,
                onOpen: () =>
                    widget.onOpenTask(snapshot.tasks[index].scheduleId),
                onEnabledChanged: (enabled) =>
                    _setEnabled(task: snapshot.tasks[index], enabled: enabled),
              ),
              if (index != snapshot.tasks.length - 1)
                const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadScheduledTasks();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _loadError = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = _scheduledTaskErrorMessage(error);
      });
    }
  }

  Future<void> _setEnabled({
    required ScheduledTaskSummary task,
    required bool enabled,
  }) async {
    if (_busyScheduleId != null) {
      return;
    }
    setState(() {
      _busyScheduleId = task.scheduleId;
    });
    try {
      await widget.facade.updateScheduledTaskEnabled(
        scheduleId: task.scheduleId,
        enabled: enabled,
      );
      final snapshot = await widget.facade.loadScheduledTasks();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
      });
    } catch (error) {
      if (mounted) {
        _showScheduledTaskMessage(context, _scheduledTaskErrorMessage(error));
      }
    } finally {
      if (mounted) {
        setState(() {
          _busyScheduleId = null;
        });
      }
    }
  }
}

class _ScheduledTaskListCard extends StatelessWidget {
  const _ScheduledTaskListCard({
    required this.task,
    required this.copy,
    required this.isBusy,
    required this.onOpen,
    required this.onEnabledChanged,
  });

  final ScheduledTaskSummary task;
  final _ScheduledTaskSettingsCopy copy;
  final bool isBusy;
  final VoidCallback onOpen;
  final ValueChanged<bool> onEnabledChanged;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: InkWell(
        onTap: isBusy ? null : onOpen,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      task.title.isEmpty ? copy.untitledTask : task.title,
                      style: _SettingsTextStyles.rowTitle,
                    ),
                    const SizedBox(height: 5),
                    Text(
                      task.triggerSummary,
                      style: _SettingsTextStyles.rowSubtitle,
                    ),
                    const SizedBox(height: 5),
                    Text(
                      copy.nextRun(task.nextTriggerAtEpochMs),
                      style: _SettingsTextStyles.rowSubtitle,
                    ),
                    if (task.snoozedUntilEpochMs != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        copy.snoozedUntil(task.snoozedUntilEpochMs!),
                        style: _SettingsTextStyles.rowSubtitle,
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 12),
              if (isBusy)
                const Padding(
                  padding: EdgeInsets.only(top: 8, right: 8),
                  child: SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                )
              else
                _PrototypeSwitch(
                  value: task.enabled,
                  onChanged: onEnabledChanged,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ScheduledTaskDetailSettingsPage extends StatefulWidget {
  const _ScheduledTaskDetailSettingsPage({
    super.key,
    required this.facade,
    required this.scheduleId,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final String scheduleId;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_ScheduledTaskDetailSettingsPage> createState() =>
      _ScheduledTaskDetailSettingsPageState();
}

class _ScheduledTaskDetailSettingsPageState
    extends State<_ScheduledTaskDetailSettingsPage> {
  ScheduledTaskDetailSnapshot? _snapshot;
  String? _loadError;
  String? _busyAction;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final copy = _ScheduledTaskSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    final snapshot = _snapshot;
    if (snapshot == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-scheduled-task-loading'),
            )
          : _SettingsLoadErrorCard(
              title: copy.taskDetailTitle,
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    final task = snapshot.task;
    final busy = _busyAction != null;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(
            task.title.isEmpty ? copy.untitledTask : task.title,
            style: _SettingsTextStyles.pageTitleSubpage,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _NotificationToggleRow(
                  key: const ValueKey<String>('scheduled-task-enabled'),
                  title: copy.enabledTitle,
                  subtitle: copy.enabledSubtitle(task.enabled),
                  value: task.enabled,
                  onChanged: busy ? null : _setEnabled,
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _ScheduledTaskValueRow(
                  label: copy.triggerLabel,
                  value: task.triggerSummary,
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _ScheduledTaskValueRow(
                  label: copy.nextRunLabel,
                  value: copy.epoch(task.nextTriggerAtEpochMs),
                ),
                if (task.snoozedUntilEpochMs != null) ...[
                  const Divider(height: 1, color: OpenCrayColors.divider),
                  _ScheduledTaskValueRow(
                    label: copy.snoozedUntilLabel,
                    value: copy.epoch(task.snoozedUntilEpochMs),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 12),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(copy.promptTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 8),
                SelectableText(task.prompt, style: _SettingsTextStyles.body),
              ],
            ),
          ),
          const SizedBox(height: 12),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(copy.actionsTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    FilledButton.icon(
                      key: const ValueKey<String>('scheduled-task-run-now'),
                      onPressed: !task.enabled || busy ? null : _runNow,
                      icon: _busyAction == 'run'
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Icon(Icons.play_arrow_rounded),
                      label: Text(copy.runNow),
                      style: _scheduledTaskFilledButtonStyle(),
                    ),
                    OutlinedButton.icon(
                      key: const ValueKey<String>('scheduled-task-snooze'),
                      onPressed: !task.enabled || busy ? null : _snooze,
                      icon: _busyAction == 'snooze'
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.snooze_rounded),
                      label: Text(copy.snooze15Minutes),
                      style: _scheduledTaskOutlinedButtonStyle(),
                    ),
                  ],
                ),
                if (!task.enabled) ...[
                  const SizedBox(height: 10),
                  Text(
                    copy.enableBeforeActions,
                    style: _SettingsTextStyles.rowSubtitle,
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 12),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(copy.policyTitle, style: _SettingsTextStyles.cardTitle),
                const SizedBox(height: 4),
                _ScheduledTaskValueRow(
                  label: copy.conflictPolicyLabel,
                  value: copy.conflictPolicy(task.conflictPolicy),
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _ScheduledTaskValueRow(
                  label: copy.foregroundNoticeLabel,
                  value: task.foregroundNotificationRequired
                      ? copy.requiredStatus
                      : copy.notRequiredStatus,
                ),
                const Divider(height: 1, color: OpenCrayColors.divider),
                _ScheduledTaskValueRow(
                  label: copy.eventAlertsLabel,
                  value: copy.enabledAlertCount(task),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          _ScheduledTaskHistoryCard(snapshot: snapshot, copy: copy),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final snapshot = await widget.facade.loadScheduledTask(widget.scheduleId);
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
        _loadError = null;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = _scheduledTaskErrorMessage(error);
      });
    }
  }

  Future<void> _setEnabled(bool enabled) async {
    await _perform(
      action: 'enabled',
      operation: () => widget.facade.updateScheduledTaskEnabled(
        scheduleId: widget.scheduleId,
        enabled: enabled,
      ),
    );
  }

  Future<void> _runNow() async {
    final copy = _ScheduledTaskSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    await _perform(
      action: 'run',
      operation: () => widget.facade.runScheduledTaskNow(widget.scheduleId),
      successMessage: copy.runRequested,
    );
  }

  Future<void> _snooze() async {
    final copy = _ScheduledTaskSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    await _perform(
      action: 'snooze',
      operation: () =>
          widget.facade.snoozeScheduledTask(scheduleId: widget.scheduleId),
      successMessage: copy.snoozed,
    );
  }

  Future<void> _perform({
    required String action,
    required Future<ScheduledTaskActionResult> Function() operation,
    String? successMessage,
  }) async {
    if (_busyAction != null) {
      return;
    }
    setState(() {
      _busyAction = action;
    });
    try {
      await operation();
      final snapshot = await widget.facade.loadScheduledTask(widget.scheduleId);
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = snapshot;
      });
      if (successMessage != null) {
        _showScheduledTaskMessage(context, successMessage);
      }
    } catch (error) {
      if (mounted) {
        _showScheduledTaskMessage(context, _scheduledTaskErrorMessage(error));
      }
    } finally {
      if (mounted) {
        setState(() {
          _busyAction = null;
        });
      }
    }
  }
}

class _ScheduledTaskHistoryCard extends StatelessWidget {
  const _ScheduledTaskHistoryCard({required this.snapshot, required this.copy});

  final ScheduledTaskDetailSnapshot snapshot;
  final _ScheduledTaskSettingsCopy copy;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.historyTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 4),
          Text(
            copy.runCount(snapshot.totalRunCount),
            style: _SettingsTextStyles.rowSubtitle,
          ),
          const SizedBox(height: 8),
          if (snapshot.recentRuns.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Text(copy.emptyHistory, style: _SettingsTextStyles.body),
            )
          else
            for (
              int index = 0;
              index < snapshot.recentRuns.length;
              index++
            ) ...[
              if (index > 0)
                const Divider(height: 1, color: OpenCrayColors.divider),
              _ScheduledTaskHistoryRow(
                run: snapshot.recentRuns[index],
                copy: copy,
              ),
            ],
        ],
      ),
    );
  }
}

class _ScheduledTaskHistoryRow extends StatelessWidget {
  const _ScheduledTaskHistoryRow({required this.run, required this.copy});

  final ScheduledTaskRunRecord run;
  final _ScheduledTaskSettingsCopy copy;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  copy.runResult(run.result),
                  style: _SettingsTextStyles.rowTitle,
                ),
              ),
              const SizedBox(width: 12),
              Text(
                copy.epoch(run.triggeredAtEpochMs),
                style: _SettingsTextStyles.rowSubtitle,
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            copy.triggerReason(run.triggerReason),
            style: _SettingsTextStyles.rowSubtitle,
          ),
          if (run.failureReason?.isNotEmpty == true) ...[
            const SizedBox(height: 4),
            Text(
              run.failureReason!,
              style: _SettingsTextStyles.rowSubtitle.copyWith(
                color: OpenCrayColors.dangerText,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ScheduledTaskValueRow extends StatelessWidget {
  const _ScheduledTaskValueRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Text(label, style: _SettingsTextStyles.rowTitle)),
          const SizedBox(width: 16),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: _SettingsTextStyles.rowSubtitle,
            ),
          ),
        ],
      ),
    );
  }
}

class _ScheduledTaskMissingSelectionPage extends StatelessWidget {
  const _ScheduledTaskMissingSelectionPage({
    super.key,
    required this.onBack,
    required this.backLabel,
  });

  final VoidCallback onBack;
  final String backLabel;

  @override
  Widget build(BuildContext context) {
    final copy = _ScheduledTaskSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: onBack, label: backLabel),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Text(copy.missingTask, style: _SettingsTextStyles.body),
          ),
        ],
      ),
    );
  }
}

ButtonStyle _scheduledTaskFilledButtonStyle() => FilledButton.styleFrom(
  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
);

ButtonStyle _scheduledTaskOutlinedButtonStyle() => OutlinedButton.styleFrom(
  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
);

void _showScheduledTaskMessage(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}

String _scheduledTaskErrorMessage(Object error) => error
    .toString()
    .replaceFirst('Exception: ', '')
    .replaceFirst('Bad state: ', '');

class _ScheduledTaskSettingsCopy {
  const _ScheduledTaskSettingsCopy({required this.zh});

  final bool zh;

  factory _ScheduledTaskSettingsCopy.fromLocale(Locale locale) =>
      _ScheduledTaskSettingsCopy(
        zh: locale.languageCode.toLowerCase().startsWith('zh'),
      );

  String get tasksTitle => zh ? '定时任务' : 'Scheduled tasks';
  String get taskDetailTitle => zh ? '任务详情' : 'Task details';
  String get untitledTask => zh ? '未命名任务' : 'Untitled task';
  String get emptyTasks => zh ? '目前没有定时任务。' : 'No scheduled tasks yet.';
  String get missingTask => zh ? '未指定定时任务。' : 'No scheduled task selected.';
  String get enabledTitle => zh ? '启用任务' : 'Task enabled';
  String get triggerLabel => zh ? '触发规则' : 'Trigger';
  String get nextRunLabel => zh ? '下次运行' : 'Next run';
  String get snoozedUntilLabel => zh ? '延后至' : 'Snoozed until';
  String get promptTitle => zh ? '任务内容' : 'Task prompt';
  String get actionsTitle => zh ? '操作' : 'Actions';
  String get runNow => zh ? '立即运行' : 'Run now';
  String get snooze15Minutes => zh ? '延后 15 分钟' : 'Snooze 15 minutes';
  String get enableBeforeActions =>
      zh ? '启用任务后才能立即运行或延后。' : 'Enable the task to run or snooze it.';
  String get policyTitle => zh ? '运行策略' : 'Runtime policy';
  String get conflictPolicyLabel => zh ? '冲突处理' : 'Conflict policy';
  String get foregroundNoticeLabel =>
      zh ? '前台服务通知' : 'Foreground service notice';
  String get eventAlertsLabel => zh ? '任务事件提醒' : 'Task event alerts';
  String get requiredStatus => zh ? '始终需要' : 'Always required';
  String get notRequiredStatus => zh ? '不需要' : 'Not required';
  String get historyTitle => zh ? '最近运行' : 'Recent runs';
  String get emptyHistory => zh ? '暂无运行记录。' : 'No run history yet.';
  String get runRequested => zh ? '已请求立即运行。' : 'Run requested.';
  String get snoozed => zh ? '任务已延后 15 分钟。' : 'Task snoozed for 15 minutes.';
  String get never => zh ? '暂无' : 'Not scheduled';

  String taskCount(int count) => zh ? '共 $count 个任务' : '$count tasks';
  String runCount(int count) => zh ? '共 $count 次运行' : '$count total runs';
  String nextRun(int? epochMs) => '${zh ? '下次运行' : 'Next'}: ${epoch(epochMs)}';
  String snoozedUntil(int epochMs) =>
      '${zh ? '延后至' : 'Snoozed until'}: ${epoch(epochMs)}';
  String enabledSubtitle(bool enabled) => enabled
      ? (zh
            ? '系统会按触发规则唤醒 detached runtime。'
            : 'Detached runtime wakes on schedule.')
      : (zh ? '任务已停用，不会触发。' : 'Disabled tasks do not trigger.');

  String epoch(int? epochMs) {
    if (epochMs == null || epochMs <= 0) {
      return never;
    }
    final date = DateTime.fromMillisecondsSinceEpoch(epochMs).toLocal();
    String two(int value) => value.toString().padLeft(2, '0');
    return '${date.year}-${two(date.month)}-${two(date.day)} '
        '${two(date.hour)}:${two(date.minute)}';
  }

  String conflictPolicy(String value) {
    switch (value.toLowerCase()) {
      case 'queue':
        return zh ? '排队' : 'Queue';
      case 'skip':
        return zh ? '跳过' : 'Skip';
      case 'replace':
        return zh ? '替换' : 'Replace';
      default:
        return value;
    }
  }

  String enabledAlertCount(ScheduledTaskDetails task) {
    final count = <bool>[
      task.notifyOnQueued,
      task.notifyOnApproval,
      task.notifyOnCompletion,
      task.notifyOnInterruption,
    ].where((enabled) => enabled).length;
    return zh ? '$count / 4 已启用' : '$count of 4 enabled';
  }

  String runResult(String result) {
    switch (result.toLowerCase()) {
      case 'accepted':
        return zh ? '已接受' : 'Accepted';
      case 'queued':
        return zh ? '已排队' : 'Queued';
      case 'skipped':
        return zh ? '已跳过' : 'Skipped';
      case 'failed':
        return zh ? '失败' : 'Failed';
      default:
        return result.isEmpty ? (zh ? '未知' : 'Unknown') : result;
    }
  }

  String triggerReason(String reason) {
    switch (reason.toLowerCase()) {
      case 'manual':
      case 'run_now':
        return zh ? '手动触发' : 'Manual trigger';
      case 'alarm':
        return zh ? '精确闹钟触发' : 'Exact alarm trigger';
      case 'work':
        return zh ? '后台工作触发' : 'Background work trigger';
      default:
        return reason;
    }
  }
}
