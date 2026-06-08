part of 'settings_feature.dart';

class _NotificationsBackgroundSettingsPage extends StatefulWidget {
  const _NotificationsBackgroundSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
    required this.onOpenPage,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;
  final ValueChanged<SettingsPage> onOpenPage;

  @override
  State<_NotificationsBackgroundSettingsPage> createState() =>
      _NotificationsBackgroundSettingsPageState();
}

class _NotificationsBackgroundSettingsPageState
    extends State<_NotificationsBackgroundSettingsPage>
    with WidgetsBindingObserver {
  SettingsDetailSnapshot? _detail;
  NotificationSettingsSnapshot? _settings;
  StrongBackgroundSnapshot? _strongBackground;
  String? _loadError;
  bool _isSaving = false;
  bool _hasQueuedSave = false;
  StrongBackgroundActionId? _activeActionId;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _detail != null) {
      unawaited(_refreshStrongBackground());
    }
  }

  @override
  Widget build(BuildContext context) {
    final copy = _NotificationSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    final detail = _detail;
    final settings = _settings;
    final strongBackground = _strongBackground;
    if (detail == null || settings == null || strongBackground == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-notifications-loading'),
            )
          : _SettingsLoadErrorCard(
              title: detail?.title ?? copy.notificationsBackgroundTitle,
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    final enabledChannelCount = _enabledChannelCount(settings);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(detail.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(detail.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _buildBackgroundProfileCard(copy, strongBackground),
          const SizedBox(height: 16),
          _buildNotificationsCard(copy, settings, enabledChannelCount),
          const SizedBox(height: 16),
          _buildDeliveryCard(copy, settings),
          const SizedBox(height: 16),
          _buildSystemCard(copy, strongBackground),
        ],
      ),
    );
  }

  Widget _buildBackgroundProfileCard(
    _NotificationSettingsCopy copy,
    StrongBackgroundSnapshot strongBackground,
  ) {
    final profile = _backgroundProfile(strongBackground);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.backgroundProfileTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(
            copy.backgroundProfileSummary(profile),
            style: _SettingsTextStyles.body,
          ),
          const SizedBox(height: 12),
          _SegmentedSelector(
            labels: <String>[
              copy.backgroundProfileAutoLabel,
              copy.backgroundProfileActiveLabel,
              copy.backgroundProfileStrongLabel,
            ],
            selectedIndex: profile.index,
          ),
          const SizedBox(height: 12),
          DecoratedBox(
            decoration: BoxDecoration(
              color: const Color(0xFFF4F4F7),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                copy.backgroundProfileBody(profile),
                style: _SettingsTextStyles.bodyStrong,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNotificationsCard(
    _NotificationSettingsCopy copy,
    NotificationSettingsSnapshot settings,
    int enabledChannelCount,
  ) {
    final totalChannelCount = _notificationChannelDescriptors(copy).length;
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.notificationsTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(copy.notificationsHelper, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          _NotificationToggleRow(
            title: copy.allowNotificationsTitle,
            subtitle: copy.allowNotificationsSubtitle,
            value: settings.masterEnabled,
            onChanged: (value) => _updateSettings(
              settings.copyWith(masterEnabled: value),
            ),
          ),
          const Divider(height: 1, color: OpenCrayColors.divider),
          _NotificationActionRow(
            title: copy.manageChannelsTitle,
            subtitle: copy.manageChannelsSubtitle(
              enabledChannelCount,
              totalChannelCount,
            ),
            valueLabel: copy.enabledCountLabel(
              enabledChannelCount,
              totalChannelCount,
            ),
            onTap: () => widget.onOpenPage(SettingsPage.notificationChannels),
          ),
        ],
      ),
    );
  }

  Widget _buildDeliveryCard(
    _NotificationSettingsCopy copy,
    NotificationSettingsSnapshot settings,
  ) {
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.deliveryTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(copy.deliveryHelper, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          _NotificationActionRow(
            title: copy.defaultDeliveryRowTitle,
            subtitle: copy.defaultDeliveryRowSubtitle(settings.defaultDeliveryMode),
            valueLabel: copy.deliveryModeLabel(settings.defaultDeliveryMode),
            onTap: _openDeliveryModeSheet,
          ),
          const Divider(height: 1, color: OpenCrayColors.divider),
          _NotificationToggleRow(
            title: copy.quietHoursTitle,
            subtitle: copy.quietHoursSummary(
              enabled: settings.quietHoursEnabled,
              startLabel: _formatMinutesOfDay(settings.quietHoursStartMinutes),
              endLabel: _formatMinutesOfDay(settings.quietHoursEndMinutes),
            ),
            value: settings.quietHoursEnabled,
            onChanged: (value) => _updateSettings(
              settings.copyWith(quietHoursEnabled: value),
            ),
          ),
          if (settings.quietHoursEnabled) ...[
            const Divider(height: 1, color: OpenCrayColors.divider),
            _SettingsPickerRow(
              title: copy.quietHoursStartTitle,
              value: _formatMinutesOfDay(settings.quietHoursStartMinutes),
              onTap: _pickQuietHoursStart,
            ),
            const Divider(height: 1, color: OpenCrayColors.divider),
            _SettingsPickerRow(
              title: copy.quietHoursEndTitle,
              value: _formatMinutesOfDay(settings.quietHoursEndMinutes),
              onTap: _pickQuietHoursEnd,
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSystemCard(
    _NotificationSettingsCopy copy,
    StrongBackgroundSnapshot strongBackground,
  ) {
    final notificationAction =
        strongBackground.action(StrongBackgroundActionId.openNotificationSettings);
    final supportAction = _preferredSupportAction(strongBackground);
    return _SettingsCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(copy.systemTitle, style: _SettingsTextStyles.cardTitle),
          const SizedBox(height: 8),
          Text(copy.systemHelper, style: _SettingsTextStyles.body),
          const SizedBox(height: 8),
          _NotificationActionRow(
            title: copy.foregroundServiceNoticeTitle,
            subtitle: copy.foregroundServiceNoticeSubtitle,
            valueLabel: copy.foregroundServiceNoticeStatus,
            onTap: notificationAction?.available == true
                ? () => _launchStrongBackgroundAction(
                    StrongBackgroundActionId.openNotificationSettings,
                  )
                : null,
            isBusy:
                _activeActionId == StrongBackgroundActionId.openNotificationSettings,
          ),
          const Divider(height: 1, color: OpenCrayColors.divider),
          _NotificationActionRow(
            title: copy.exactAlarmsBatteryTitle,
            subtitle: copy.exactAlarmsBatterySubtitle(
              exactAlarmsReady: _exactAlarmsReady(strongBackground),
              batteryReady: _batteryReady(strongBackground),
            ),
            valueLabel: _systemSupportStatus(copy, strongBackground),
            onTap: supportAction == null
                ? null
                : () => _launchStrongBackgroundAction(supportAction),
            isBusy: _activeActionId == supportAction,
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final detail = await widget.facade.loadDetail(
        SettingsPage.notificationsBackground,
      );
      final settings = await widget.facade.loadNotificationSettings();
      final strongBackground = await widget.facade.loadStrongBackgroundSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _detail = detail;
        _settings = settings;
        _strongBackground = strongBackground;
        _loadError = null;
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

  Future<void> _refreshStrongBackground() async {
    try {
      final snapshot = await widget.facade.loadStrongBackgroundSnapshot();
      if (!mounted) {
        return;
      }
      setState(() {
        _strongBackground = snapshot;
      });
    } catch (_) {}
  }

  void _updateSettings(NotificationSettingsSnapshot next) {
    setState(() {
      _settings = next;
    });
    unawaited(_saveNow());
  }

  Future<void> _saveNow() async {
    final settings = _settings;
    if (settings == null) {
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
      final updated = await widget.facade.saveNotificationSettings(settings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = updated;
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
      } else {
        _isSaving = false;
      }
      if (_hasQueuedSave) {
        _hasQueuedSave = false;
        unawaited(_saveNow());
      }
    }
  }

  Future<void> _openDeliveryModeSheet() async {
    final settings = _settings;
    if (settings == null) {
      return;
    }
    final copy = _NotificationSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    final selected = await showModalBottomSheet<NotificationDeliveryMode>(
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
                      copy.defaultDeliverySheetTitle,
                      style: _SettingsTextStyles.cardTitle,
                    ),
                    const SizedBox(height: 12),
                    for (final mode in NotificationDeliveryMode.values)
                      InkWell(
                        borderRadius: BorderRadius.circular(14),
                        onTap: () => Navigator.of(context).pop(mode),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Row(
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      copy.deliveryModeLabel(mode),
                                      style: _SettingsTextStyles.rowTitle,
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      copy.deliveryModeDescription(mode),
                                      style: _SettingsTextStyles.rowSubtitle,
                                    ),
                                  ],
                                ),
                              ),
                              if (mode == settings.defaultDeliveryMode)
                                const Icon(
                                  Icons.check_rounded,
                                  size: 18,
                                  color: OpenCrayColors.primary,
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
    if (selected == null || selected == settings.defaultDeliveryMode) {
      return;
    }
    _updateSettings(settings.copyWith(defaultDeliveryMode: selected));
  }

  Future<void> _pickQuietHoursStart() async {
    final settings = _settings;
    if (settings == null) {
      return;
    }
    final selected = await showTimePicker(
      context: context,
      initialTime: _timeOfDayFromMinutes(settings.quietHoursStartMinutes),
    );
    if (selected == null) {
      return;
    }
    _updateSettings(
      settings.copyWith(quietHoursStartMinutes: _minutesFromTimeOfDay(selected)),
    );
  }

  Future<void> _pickQuietHoursEnd() async {
    final settings = _settings;
    if (settings == null) {
      return;
    }
    final selected = await showTimePicker(
      context: context,
      initialTime: _timeOfDayFromMinutes(settings.quietHoursEndMinutes),
    );
    if (selected == null) {
      return;
    }
    _updateSettings(
      settings.copyWith(quietHoursEndMinutes: _minutesFromTimeOfDay(selected)),
    );
  }

  Future<void> _launchStrongBackgroundAction(
    StrongBackgroundActionId actionId,
  ) async {
    final copy = _NotificationSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    setState(() {
      _activeActionId = actionId;
    });
    try {
      final result = await widget.facade.performStrongBackgroundAction(actionId);
      if (mounted && !result.launched && result.reason != null) {
        _showMessage(result.reason!);
      } else if (mounted && !result.launched) {
        _showMessage(copy.systemActionUnavailable);
      }
    } catch (error) {
      if (mounted) {
        _showMessage(error.toString().replaceFirst('Exception: ', ''));
      }
    } finally {
      if (mounted) {
        setState(() {
          _activeActionId = null;
        });
      }
      await _refreshStrongBackground();
    }
  }

  int _enabledChannelCount(NotificationSettingsSnapshot settings) {
    var count = 0;
    for (final channel in _notificationChannelDescriptors(
      _NotificationSettingsCopy.fromLocale(Localizations.localeOf(context)),
    )) {
      if (channel.valueOf(settings)) {
        count += 1;
      }
    }
    return count;
  }

  StrongBackgroundActionId? _preferredSupportAction(
    StrongBackgroundSnapshot strongBackground,
  ) {
    if (!_exactAlarmsReady(strongBackground)) {
      final action =
          strongBackground.action(StrongBackgroundActionId.openExactAlarmSettings);
      if (action?.available == true) {
        return StrongBackgroundActionId.openExactAlarmSettings;
      }
    }
    if (!_batteryReady(strongBackground)) {
      final requestAction = strongBackground.action(
        StrongBackgroundActionId.requestIgnoreBatteryOptimizations,
      );
      if (requestAction?.available == true) {
        return StrongBackgroundActionId.requestIgnoreBatteryOptimizations;
      }
      final openAction = strongBackground.action(
        StrongBackgroundActionId.openBatteryOptimizationSettings,
      );
      if (openAction?.available == true) {
        return StrongBackgroundActionId.openBatteryOptimizationSettings;
      }
    }
    final fallbackAction = strongBackground.action(
      StrongBackgroundActionId.openBatteryOptimizationSettings,
    );
    if (fallbackAction?.available == true) {
      return StrongBackgroundActionId.openBatteryOptimizationSettings;
    }
    return null;
  }

  bool _exactAlarmsReady(StrongBackgroundSnapshot strongBackground) =>
      !strongBackground.exactAlarms.accessRequired ||
      strongBackground.exactAlarms.configured;

  bool _batteryReady(StrongBackgroundSnapshot strongBackground) =>
      !strongBackground.batteryOptimization.supported ||
      strongBackground.batteryOptimization.configured;

  String _systemSupportStatus(
    _NotificationSettingsCopy copy,
    StrongBackgroundSnapshot strongBackground,
  ) {
    return _exactAlarmsReady(strongBackground) && _batteryReady(strongBackground)
        ? copy.systemReadyStatus
        : copy.systemReviewStatus;
  }

  _BackgroundProfile _backgroundProfile(
    StrongBackgroundSnapshot strongBackground,
  ) {
    switch (strongBackground.tier) {
      case StrongBackgroundTier.baseline:
        return _BackgroundProfile.auto;
      case StrongBackgroundTier.activeBackground:
        return _BackgroundProfile.active;
      case StrongBackgroundTier.strongBackground:
        return _BackgroundProfile.strong;
    }
  }

  String _formatMinutesOfDay(int minutesOfDay) {
    final time = _timeOfDayFromMinutes(minutesOfDay);
    final mediaQuery = MediaQuery.maybeOf(context);
    return MaterialLocalizations.of(context).formatTimeOfDay(
      time,
      alwaysUse24HourFormat: mediaQuery?.alwaysUse24HourFormat ?? false,
    );
  }

  TimeOfDay _timeOfDayFromMinutes(int minutesOfDay) {
    final normalized = ((minutesOfDay % 1440) + 1440) % 1440;
    return TimeOfDay(hour: normalized ~/ 60, minute: normalized % 60);
  }

  int _minutesFromTimeOfDay(TimeOfDay time) => (time.hour * 60) + time.minute;

  void _showMessage(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }
}

class _NotificationChannelsSettingsPage extends StatefulWidget {
  const _NotificationChannelsSettingsPage({
    super.key,
    required this.facade,
    required this.onBack,
    required this.backLabel,
  });

  final SettingsFacade facade;
  final VoidCallback onBack;
  final String backLabel;

  @override
  State<_NotificationChannelsSettingsPage> createState() =>
      _NotificationChannelsSettingsPageState();
}

class _NotificationChannelsSettingsPageState
    extends State<_NotificationChannelsSettingsPage> {
  SettingsDetailSnapshot? _detail;
  NotificationSettingsSnapshot? _settings;
  String? _loadError;
  bool _isSaving = false;
  bool _hasQueuedSave = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final copy = _NotificationSettingsCopy.fromLocale(
      Localizations.localeOf(context),
    );
    final detail = _detail;
    final settings = _settings;
    if (detail == null || settings == null) {
      return _loadError == null
          ? const _SettingsLoading(
              key: ValueKey<String>('settings-notification-channels-loading'),
            )
          : _SettingsLoadErrorCard(
              title: detail?.title ?? copy.notificationChannelsTitle,
              message: _loadError!,
              onBack: widget.onBack,
              backLabel: widget.backLabel,
              onRetry: _load,
            );
    }
    final channels = _notificationChannelDescriptors(copy);
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackLink(onTap: widget.onBack, label: widget.backLabel),
          const SizedBox(height: 8),
          Text(detail.title, style: _SettingsTextStyles.pageTitleSubpage),
          const SizedBox(height: 8),
          Text(detail.subtitle, style: _SettingsTextStyles.subtitle),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  copy.notificationChannelsHelper,
                  style: _SettingsTextStyles.body,
                ),
                const SizedBox(height: 8),
                for (int index = 0; index < channels.length; index++) ...[
                  _NotificationToggleRow(
                    title: channels[index].title,
                    subtitle: channels[index].subtitle,
                    value: channels[index].valueOf(settings),
                    onChanged: (value) => _updateSettings(
                      channels[index].update(settings, value),
                    ),
                  ),
                  if (index < channels.length - 1)
                    const Divider(height: 1, color: OpenCrayColors.divider),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _load() async {
    try {
      final detail = await widget.facade.loadDetail(
        SettingsPage.notificationChannels,
      );
      final settings = await widget.facade.loadNotificationSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _detail = detail;
        _settings = settings;
        _loadError = null;
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

  void _updateSettings(NotificationSettingsSnapshot next) {
    setState(() {
      _settings = next;
    });
    unawaited(_saveNow());
  }

  Future<void> _saveNow() async {
    final settings = _settings;
    if (settings == null) {
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
      final updated = await widget.facade.saveNotificationSettings(settings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = updated;
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
      } else {
        _isSaving = false;
      }
      if (_hasQueuedSave) {
        _hasQueuedSave = false;
        unawaited(_saveNow());
      }
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }
}

class _NotificationToggleRow extends StatelessWidget {
  const _NotificationToggleRow({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
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
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: _PrototypeSwitch(value: value, onChanged: onChanged),
          ),
        ],
      ),
    );
  }
}

class _NotificationActionRow extends StatelessWidget {
  const _NotificationActionRow({
    required this.title,
    required this.subtitle,
    required this.valueLabel,
    this.onTap,
    this.isBusy = false,
  });

  final String title;
  final String subtitle;
  final String valueLabel;
  final VoidCallback? onTap;
  final bool isBusy;

  @override
  Widget build(BuildContext context) {
    final content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
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
          if (isBusy) ...[
            const Padding(
              padding: EdgeInsets.only(top: 4),
              child: SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: OpenCrayColors.primary,
                ),
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
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              child: Text(valueLabel, style: _SettingsTextStyles.valueChip),
            ),
          ),
          if (onTap != null) ...[
            const SizedBox(width: 6),
            const Padding(
              padding: EdgeInsets.only(top: 2),
              child: Icon(
                Icons.chevron_right_rounded,
                size: 18,
                color: OpenCrayColors.textTertiary,
              ),
            ),
          ],
        ],
      ),
    );
    if (onTap == null) {
      return content;
    }
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: content,
    );
  }
}

enum _BackgroundProfile { auto, active, strong }

class _NotificationChannelDescriptor {
  const _NotificationChannelDescriptor({
    required this.title,
    required this.subtitle,
    required this.valueOf,
    required this.update,
  });

  final String title;
  final String subtitle;
  final bool Function(NotificationSettingsSnapshot snapshot) valueOf;
  final NotificationSettingsSnapshot Function(
    NotificationSettingsSnapshot snapshot,
    bool value,
  )
  update;
}

List<_NotificationChannelDescriptor> _notificationChannelDescriptors(
  _NotificationSettingsCopy copy,
) {
  return <_NotificationChannelDescriptor>[
    _NotificationChannelDescriptor(
      title: copy.approvalRequestsTitle,
      subtitle: copy.approvalRequestsSubtitle,
      valueOf: (snapshot) => snapshot.approvalRequestsEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(approvalRequestsEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.approvalReminderTitle,
      subtitle: copy.approvalReminderSubtitle,
      valueOf: (snapshot) => snapshot.approvalReminderEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(approvalReminderEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.taskFinishedTitle,
      subtitle: copy.taskFinishedSubtitle,
      valueOf: (snapshot) => snapshot.taskFinishedEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(taskFinishedEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.taskFailedTitle,
      subtitle: copy.taskFailedSubtitle,
      valueOf: (snapshot) => snapshot.taskFailedEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(taskFailedEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.scheduledWakeTitle,
      subtitle: copy.scheduledWakeSubtitle,
      valueOf: (snapshot) => snapshot.scheduledWakeEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(scheduledWakeEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.backgroundTaskPausedTitle,
      subtitle: copy.backgroundTaskPausedSubtitle,
      valueOf: (snapshot) => snapshot.backgroundTaskPausedEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(backgroundTaskPausedEnabled: value),
    ),
    _NotificationChannelDescriptor(
      title: copy.serviceRecoveredTitle,
      subtitle: copy.serviceRecoveredSubtitle,
      valueOf: (snapshot) => snapshot.serviceRecoveredEnabled,
      update: (snapshot, value) =>
          snapshot.copyWith(serviceRecoveredEnabled: value),
    ),
  ];
}

class _NotificationSettingsCopy {
  const _NotificationSettingsCopy._({
    required this.notificationsBackgroundTitle,
    required this.notificationChannelsTitle,
    required this.backgroundProfileTitle,
    required this.backgroundProfileAutoLabel,
    required this.backgroundProfileActiveLabel,
    required this.backgroundProfileStrongLabel,
    required this.notificationsTitle,
    required this.notificationsHelper,
    required this.allowNotificationsTitle,
    required this.allowNotificationsSubtitle,
    required this.manageChannelsTitle,
    required this.deliveryTitle,
    required this.deliveryHelper,
    required this.defaultDeliveryRowTitle,
    required this.quietHoursTitle,
    required this.quietHoursStartTitle,
    required this.quietHoursEndTitle,
    required this.defaultDeliverySheetTitle,
    required this.systemTitle,
    required this.systemHelper,
    required this.foregroundServiceNoticeTitle,
    required this.foregroundServiceNoticeSubtitle,
    required this.foregroundServiceNoticeStatus,
    required this.exactAlarmsBatteryTitle,
    required this.systemReadyStatus,
    required this.systemReviewStatus,
    required this.systemActionUnavailable,
    required this.notificationChannelsHelper,
    required this.approvalRequestsTitle,
    required this.approvalRequestsSubtitle,
    required this.approvalReminderTitle,
    required this.approvalReminderSubtitle,
    required this.taskFinishedTitle,
    required this.taskFinishedSubtitle,
    required this.taskFailedTitle,
    required this.taskFailedSubtitle,
    required this.scheduledWakeTitle,
    required this.scheduledWakeSubtitle,
    required this.backgroundTaskPausedTitle,
    required this.backgroundTaskPausedSubtitle,
    required this.serviceRecoveredTitle,
    required this.serviceRecoveredSubtitle,
  });

  factory _NotificationSettingsCopy.fromLocale(Locale locale) {
    final isChinese = locale.languageCode.toLowerCase().startsWith('zh');
    if (isChinese) {
      return const _NotificationSettingsCopy._(
        notificationsBackgroundTitle: '通知与后台服务',
        notificationChannelsTitle: '通知频道',
        backgroundProfileTitle: '后台档位',
        backgroundProfileAutoLabel: 'AUTO',
        backgroundProfileActiveLabel: 'ACTIVE',
        backgroundProfileStrongLabel: 'STRONG',
        notificationsTitle: '通知总控',
        notificationsHelper: '先决定系统是否允许提醒，再决定哪些事件可以打扰你。',
        allowNotificationsTitle: '允许通知',
        allowNotificationsSubtitle: '关闭后，本地提醒会被统一静音，但各频道配置会保留。',
        manageChannelsTitle: '管理通知频道',
        deliveryTitle: '投递时机',
        deliveryHelper: '先按默认投递规则筛选，再叠加静音时段限制。',
        defaultDeliveryRowTitle: '默认投递',
        quietHoursTitle: '静音时段',
        quietHoursStartTitle: '开始时间',
        quietHoursEndTitle: '结束时间',
        defaultDeliverySheetTitle: '默认投递',
        systemTitle: '系统能力',
        systemHelper: '这些入口会打开 Android 系统设置，不会伪造应用内开关。',
        foregroundServiceNoticeTitle: '前台服务通知',
        foregroundServiceNoticeSubtitle: '运行时服务在后台保活时，Android 会强制显示可见通知。',
        foregroundServiceNoticeStatus: '系统必需',
        exactAlarmsBatteryTitle: '精确闹钟与电池',
        systemReadyStatus: '已就绪',
        systemReviewStatus: '需处理',
        systemActionUnavailable: '当前设备上没有可用的系统设置入口。',
        notificationChannelsHelper: '这些频道会在总开关、默认投递和静音时段之后生效。',
        approvalRequestsTitle: '审批请求',
        approvalRequestsSubtitle: '当任务需要你 approve 或 reject 时提醒。',
        approvalReminderTitle: '等待提醒',
        approvalReminderSubtitle: '任务挂起等待时，周期性提醒你回来处理。',
        taskFinishedTitle: '任务完成',
        taskFinishedSubtitle: '任务成功结束时提醒。',
        taskFailedTitle: '任务失败',
        taskFailedSubtitle: '任务失败、被中断或恢复失败时提醒。',
        scheduledWakeTitle: '定时唤醒触发',
        scheduledWakeSubtitle: '计划中的定时任务或唤醒事件被触发时提醒。',
        backgroundTaskPausedTitle: '后台任务暂停',
        backgroundTaskPausedSubtitle: '后台执行失去条件、任务被挂起时提醒。',
        serviceRecoveredTitle: '服务恢复',
        serviceRecoveredSubtitle: '运行时服务从重建或异常中恢复后提醒。',
      );
    }
    return const _NotificationSettingsCopy._(
      notificationsBackgroundTitle: 'Notifications & Background',
      notificationChannelsTitle: 'Notification Channels',
      backgroundProfileTitle: 'Background profile',
      backgroundProfileAutoLabel: 'AUTO',
      backgroundProfileActiveLabel: 'ACTIVE',
      backgroundProfileStrongLabel: 'STRONG',
      notificationsTitle: 'Notifications',
      notificationsHelper:
          'Decide whether alerts are allowed at all, then decide which events can interrupt you.',
      allowNotificationsTitle: 'Allow notifications',
      allowNotificationsSubtitle:
          'Turning this off mutes local alerts globally without losing per-channel choices.',
      manageChannelsTitle: 'Manage notification channels',
      deliveryTitle: 'Default delivery',
      deliveryHelper:
          'Default delivery filters events first, then quiet hours suppress non-critical alerts.',
      defaultDeliveryRowTitle: 'Default delivery',
      quietHoursTitle: 'Quiet hours',
      quietHoursStartTitle: 'Starts',
      quietHoursEndTitle: 'Ends',
      defaultDeliverySheetTitle: 'Default delivery',
      systemTitle: 'System controls',
      systemHelper:
          'These rows open Android system settings when available. No fake app-only toggles are used here.',
      foregroundServiceNoticeTitle: 'Foreground service notice',
      foregroundServiceNoticeSubtitle:
          'Android requires a visible notification whenever the runtime service keeps work alive.',
      foregroundServiceNoticeStatus: 'Required',
      exactAlarmsBatteryTitle: 'Exact alarms & battery',
      systemReadyStatus: 'Ready',
      systemReviewStatus: 'Review',
      systemActionUnavailable: 'No system settings action is available here.',
      notificationChannelsHelper:
          'These channels apply after the master switch, default delivery, and quiet-hours rules.',
      approvalRequestsTitle: 'Approval requests',
      approvalRequestsSubtitle:
          'Alert when a run needs an approve or reject decision.',
      approvalReminderTitle: 'Reminder while waiting',
      approvalReminderSubtitle:
          'Send a follow-up reminder while a run stays blocked on user input.',
      taskFinishedTitle: 'Task finished',
      taskFinishedSubtitle: 'Alert when a run completes successfully.',
      taskFailedTitle: 'Task failed',
      taskFailedSubtitle:
          'Alert when a run fails, is interrupted, or cannot be recovered.',
      scheduledWakeTitle: 'Scheduled wake fired',
      scheduledWakeSubtitle:
          'Alert when a scheduled wake or automation starts running.',
      backgroundTaskPausedTitle: 'Background task paused',
      backgroundTaskPausedSubtitle:
          'Alert when background execution loses prerequisites and the task pauses.',
      serviceRecoveredTitle: 'Service recovered',
      serviceRecoveredSubtitle:
          'Alert when the runtime service recovers after a rebuild or interruption.',
    );
  }

  final String notificationsBackgroundTitle;
  final String notificationChannelsTitle;
  final String backgroundProfileTitle;
  final String backgroundProfileAutoLabel;
  final String backgroundProfileActiveLabel;
  final String backgroundProfileStrongLabel;
  final String notificationsTitle;
  final String notificationsHelper;
  final String allowNotificationsTitle;
  final String allowNotificationsSubtitle;
  final String manageChannelsTitle;
  final String deliveryTitle;
  final String deliveryHelper;
  final String defaultDeliveryRowTitle;
  final String quietHoursTitle;
  final String quietHoursStartTitle;
  final String quietHoursEndTitle;
  final String defaultDeliverySheetTitle;
  final String systemTitle;
  final String systemHelper;
  final String foregroundServiceNoticeTitle;
  final String foregroundServiceNoticeSubtitle;
  final String foregroundServiceNoticeStatus;
  final String exactAlarmsBatteryTitle;
  final String systemReadyStatus;
  final String systemReviewStatus;
  final String systemActionUnavailable;
  final String notificationChannelsHelper;
  final String approvalRequestsTitle;
  final String approvalRequestsSubtitle;
  final String approvalReminderTitle;
  final String approvalReminderSubtitle;
  final String taskFinishedTitle;
  final String taskFinishedSubtitle;
  final String taskFailedTitle;
  final String taskFailedSubtitle;
  final String scheduledWakeTitle;
  final String scheduledWakeSubtitle;
  final String backgroundTaskPausedTitle;
  final String backgroundTaskPausedSubtitle;
  final String serviceRecoveredTitle;
  final String serviceRecoveredSubtitle;

  String backgroundProfileSummary(_BackgroundProfile profile) {
    switch (profile) {
      case _BackgroundProfile.auto:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '当前没有强保活能力，任务仍按常规前台或系统调度推进。'
            : 'No strong background hold is active. Runs rely on normal foreground use or scheduled wakes.';
      case _BackgroundProfile.active:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '通知和精确闹钟条件已经就绪，但还没进入更强的本地保活档位。'
            : 'Notifications and exact alarms are ready, but the device is not yet in the strongest local background tier.';
      case _BackgroundProfile.strong:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '通知、精确闹钟与电池豁免条件已经具备，可进入更强的本地后台运行档位。'
            : 'Notifications, exact alarms, and battery exemption are ready for the strongest local background posture.';
    }
  }

  String backgroundProfileBody(_BackgroundProfile profile) {
    switch (profile) {
      case _BackgroundProfile.auto:
        return foregroundServiceNoticeStatus == '系统必需'
            ? 'AUTO 适合默认使用。任务离开当前页面后，是否能继续推进更依赖系统调度。'
            : 'AUTO is the baseline profile. Once you leave the session, progress depends more on normal Android scheduling.';
      case _BackgroundProfile.active:
        return foregroundServiceNoticeStatus == '系统必需'
            ? 'ACTIVE 表示通知和精确闹钟已配置完成，任务具备更稳定的提醒和唤醒条件，但还没有电池豁免。'
            : 'ACTIVE means notifications and exact alarms are configured, so alerts and scheduled wakes are ready, but battery exemption is still missing.';
      case _BackgroundProfile.strong:
        return foregroundServiceNoticeStatus == '系统必需'
            ? 'STRONG 表示这台设备已经具备当前 Android 本地侧可做到的更强后台姿态，更适合长任务脱离页面继续推进。'
            : 'STRONG means this device is in the strongest local Android posture we can provide for detached long-running work.';
    }
  }

  String manageChannelsSubtitle(int enabledCount, int totalCount) =>
      foregroundServiceNoticeStatus == '系统必需'
      ? '当前已启用 $enabledCount / $totalCount 个事件频道。'
      : '$enabledCount of $totalCount event channels are currently enabled.';

  String enabledCountLabel(int enabledCount, int totalCount) =>
      '$enabledCount/$totalCount';

  String defaultDeliveryRowSubtitle(NotificationDeliveryMode mode) =>
      mode == NotificationDeliveryMode.critical
      ? (foregroundServiceNoticeStatus == '系统必需'
            ? '只放行关键提醒；静音时段内也只保留关键提醒。'
            : 'Only critical alerts pass through by default, including during quiet hours.')
      : (foregroundServiceNoticeStatus == '系统必需'
            ? '放行所有已启用频道；静音时段内仍只保留关键提醒。'
            : 'All enabled channels can notify by default. Quiet hours still reduce delivery to critical alerts.');

  String deliveryModeLabel(NotificationDeliveryMode mode) {
    switch (mode) {
      case NotificationDeliveryMode.critical:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '仅关键提醒'
            : 'Critical only';
      case NotificationDeliveryMode.all:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '全部已启用'
            : 'All enabled';
    }
  }

  String deliveryModeDescription(NotificationDeliveryMode mode) {
    switch (mode) {
      case NotificationDeliveryMode.critical:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '仅审批请求、失败等关键事件允许主动打断你。'
            : 'Only approval requests, failures, and other critical events are allowed to interrupt you.';
      case NotificationDeliveryMode.all:
        return foregroundServiceNoticeStatus == '系统必需'
            ? '所有已启用频道都能在默认时段内发出提醒。'
            : 'Every enabled channel can alert you outside quiet hours.';
    }
  }

  String quietHoursSummary({
    required bool enabled,
    required String startLabel,
    required String endLabel,
  }) {
    if (!enabled) {
      return foregroundServiceNoticeStatus == '系统必需'
          ? '关闭后，默认投递规则会整天生效。'
          : 'When off, the default delivery rule applies all day.';
    }
    return foregroundServiceNoticeStatus == '系统必需'
        ? '$startLabel 至 $endLabel，仅保留关键提醒。'
        : '$startLabel to $endLabel. Only critical alerts get through.';
  }

  String exactAlarmsBatterySubtitle({
    required bool exactAlarmsReady,
    required bool batteryReady,
  }) {
    final exactLabel = exactAlarmsReady
        ? (foregroundServiceNoticeStatus == '系统必需' ? '精确闹钟已就绪' : 'Exact alarms ready')
        : (foregroundServiceNoticeStatus == '系统必需' ? '精确闹钟待处理' : 'Exact alarms pending');
    final batteryLabel = batteryReady
        ? (foregroundServiceNoticeStatus == '系统必需' ? '电池优化已处理' : 'Battery ready')
        : (foregroundServiceNoticeStatus == '系统必需' ? '电池限制待处理' : 'Battery review needed');
    return '$exactLabel · $batteryLabel';
  }
}
