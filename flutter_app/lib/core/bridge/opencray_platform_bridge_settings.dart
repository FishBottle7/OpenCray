part of 'opencray_platform_bridge.dart';

mixin _PlatformBridgeSettingsDomain on _PlatformBridgeDeps {
  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _parseSettingsOverview(await _invokeMap('loadSettingsOverview'));

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() =>
      _settingsOverviewChannel
          .receiveBroadcastStream()
          .map(_requireMap)
          .map(_parseSettingsOverview);

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _parseSettingsDetail(
    await _invokeMap(
      'loadSettingsDetail',
      arguments: <String, Object?>{'routeId': routeId},
    ),
  );

  @override
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async =>
      OpenCrayNotificationSettingsSnapshot.fromMap(
        await _invokeMap('loadNotificationSettings'),
      );

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async => OpenCrayNotificationSettingsSnapshot.fromMap(
    await _invokeMap('saveNotificationSettings', arguments: snapshot.toMap()),
  );

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      OpenCrayScheduledTasksSnapshot.fromMap(
        await _invokeMap('loadScheduledTasks'),
      );

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => OpenCrayScheduledTaskDetailSnapshot.fromMap(
    await _invokeMap(
      'loadScheduledTask',
      arguments: <String, Object?>{'scheduleId': scheduleId},
    ),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _invokeMap(
      'updateScheduledTaskEnabled',
      arguments: <String, Object?>{
        'scheduleId': scheduleId,
        'enabled': enabled,
      },
    ),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _invokeMap(
      'runScheduledTaskNow',
      arguments: <String, Object?>{'scheduleId': scheduleId},
    ),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _invokeMap(
      'snoozeScheduledTask',
      arguments: <String, Object?>{
        'scheduleId': scheduleId,
        'durationMinutes': durationMinutes,
      },
    ),
  );

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      OpenCrayStrongBackgroundSnapshot.fromMap(
        await _invokeMap('loadStrongBackgroundSnapshot'),
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult.fromMap(
    await _invokeMap(
      'performStrongBackgroundAction',
      arguments: <String, Object?>{'actionId': actionId},
    ),
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      OpenCrayNetworkSearchConfigSnapshot.fromMap(
        await _invokeMap('loadNetworkSearchConfig'),
      );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async => OpenCrayNetworkSearchConfigSnapshot.fromMap(
    await _invokeMap(
      'saveNetworkSearchConfig',
      arguments: <String, Object?>{
        'slots': slots.map((slot) => slot.toMap()).toList(growable: false),
      },
    ),
  );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      OpenCrayMediaSpeechConfigSnapshot.fromMap(
        await _invokeMap('loadMediaSpeechConfig'),
      );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async => OpenCrayMediaSpeechConfigSnapshot.fromMap(
    await _invokeMap('saveMediaSpeechConfig', arguments: snapshot.toMap()),
  );

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      OpenCraySandboxSettingsSnapshot.fromMap(
        await _invokeMap('loadSandboxSettings'),
      );

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async => OpenCraySandboxSettingsSnapshot.fromMap(
    await _invokeMap('saveSandboxSettings', arguments: snapshot.toMap()),
  );
}
