part of 'opencray_seed_bridge.dart';

mixin _SeedBridgeSettingsDomain on _SeedBridgeDeps {
  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _settingsOverview;

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() async* {
    yield _settingsOverview;
    yield* _settingsController.stream;
  }

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _seedSettingsDetailFor(routeId);

  @override
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async => _notificationSettings;

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async {
    _notificationSettings = snapshot;
    return _notificationSettings;
  }

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      _scheduledTasks;

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => throw StateError('Scheduled task $scheduleId was not found.');

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async => _strongBackgroundSnapshot;

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult(
    source: 'strong-background-action',
    actionId: actionId,
    available: false,
    launched: false,
    reason: 'Seed bridge does not support Android strong-background actions.',
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      _networkSearchConfig;

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async {
    _networkSearchConfig = OpenCrayNetworkSearchConfigSnapshot(
      localeTag: _networkSearchConfig.localeTag,
      title: _networkSearchConfig.title,
      subtitle: _networkSearchConfig.subtitle,
      slots: slots,
    );
    _refreshSettingsOverview();
    return _networkSearchConfig;
  }

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      _mediaSpeechConfig;

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async {
    _mediaSpeechConfig = snapshot;
    return _mediaSpeechConfig;
  }

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      _sandboxSettings;

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async {
    _sandboxSettings = snapshot;
    return _sandboxSettings;
  }
}
