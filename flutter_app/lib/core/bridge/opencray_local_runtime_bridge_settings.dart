part of 'opencray_local_runtime_bridge.dart';

mixin _LocalRuntimeBridgeSettingsDomain on _LocalRuntimeBridgeDeps {
  @override
  Future<OpenCraySettingsOverviewSnapshot> loadSettingsOverview() async =>
      _parseSettingsOverview(await _getMap('v1/settings_overview'));

  @override
  Stream<OpenCraySettingsOverviewSnapshot> watchSettingsOverview() =>
      _watchMap(() => _getMap('v1/settings_overview'), _parseSettingsOverview);

  @override
  Future<OpenCraySettingsDetailSnapshot> loadSettingsDetail(
    String routeId,
  ) async => _parseSettingsDetail(
    await _getMap(
      'v1/settings_detail',
      queryParameters: <String, String>{'routeId': routeId},
    ),
  );

  @override
  Future<OpenCrayNotificationSettingsSnapshot>
  loadNotificationSettings() async =>
      OpenCrayNotificationSettingsSnapshot.fromMap(
        await _getMap('v1/notification_settings'),
      );

  @override
  Future<OpenCrayNotificationSettingsSnapshot> saveNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) async => OpenCrayNotificationSettingsSnapshot.fromMap(
    await _postMap('v1/save_notification_settings', snapshot.toMap()),
  );

  @override
  Future<OpenCrayScheduledTasksSnapshot> loadScheduledTasks() async =>
      OpenCrayScheduledTasksSnapshot.fromMap(
        await _getMap('v1/scheduled_tasks'),
      );

  @override
  Future<OpenCrayScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async => OpenCrayScheduledTaskDetailSnapshot.fromMap(
    await _getMap(
      'v1/scheduled_task',
      queryParameters: <String, String>{'scheduleId': scheduleId},
    ),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/update_scheduled_task_enabled', <String, Object?>{
      'scheduleId': scheduleId,
      'enabled': enabled,
    }),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/run_scheduled_task_now', <String, Object?>{
      'scheduleId': scheduleId,
    }),
  );

  @override
  Future<OpenCrayScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => OpenCrayScheduledTaskActionResult.fromMap(
    await _postMap('v1/snooze_scheduled_task', <String, Object?>{
      'scheduleId': scheduleId,
      'durationMinutes': durationMinutes,
    }),
  );

  @override
  Future<OpenCrayStrongBackgroundSnapshot>
  loadStrongBackgroundSnapshot() async =>
      OpenCrayStrongBackgroundSnapshot.fromMap(
        await _getMap('v1/strong_background_snapshot'),
      );

  @override
  Future<OpenCrayStrongBackgroundActionResult> performStrongBackgroundAction(
    String actionId,
  ) async => OpenCrayStrongBackgroundActionResult.fromMap(
    await _postMap('v1/perform_strong_background_action', <String, Object?>{
      'actionId': actionId,
    }),
  );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      OpenCrayNetworkSearchConfigSnapshot.fromMap(
        await _getMap('v1/network_search_config'),
      );

  @override
  Future<OpenCrayNetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<OpenCrayNetworkSearchSlotSnapshot> slots,
  ) async => OpenCrayNetworkSearchConfigSnapshot.fromMap(
    await _postMap('v1/save_network_search_config', <String, Object?>{
      'slots': slots.map((slot) => slot.toMap()).toList(growable: false),
    }),
  );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      OpenCrayMediaSpeechConfigSnapshot.fromMap(
        await _getMap('v1/media_speech_config'),
      );

  @override
  Future<OpenCrayMediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) async => OpenCrayMediaSpeechConfigSnapshot.fromMap(
    await _postMap('v1/save_media_speech_config', snapshot.toMap()),
  );

  @override
  Future<OpenCraySandboxSettingsSnapshot> loadSandboxSettings() async =>
      OpenCraySandboxSettingsSnapshot.fromMap(
        await _getMap('v1/sandbox_settings'),
      );

  @override
  Future<OpenCraySandboxSettingsSnapshot> saveSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) async => OpenCraySandboxSettingsSnapshot.fromMap(
    await _postMap('v1/save_sandbox_settings', snapshot.toMap()),
  );
}
