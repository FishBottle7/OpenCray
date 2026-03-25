import 'opencray_shell_snapshot.dart'
    show OpenCrayRuntimeServiceConnectionStateSnapshot;

class OpenCrayStrongBackgroundSnapshot {
  const OpenCrayStrongBackgroundSnapshot({
    required this.source,
    required this.available,
    required this.tierId,
    required this.setupComplete,
    required this.recommendedActionIds,
    required this.notifications,
    required this.exactAlarms,
    required this.batteryOptimization,
    required this.actions,
    this.runtimeServiceConnectionState,
  });

  final String source;
  final bool available;
  final String tierId;
  final bool setupComplete;
  final List<String> recommendedActionIds;
  final OpenCrayStrongBackgroundNotificationsSnapshot notifications;
  final OpenCrayStrongBackgroundExactAlarmSnapshot exactAlarms;
  final OpenCrayStrongBackgroundBatteryOptimizationSnapshot batteryOptimization;
  final List<OpenCrayStrongBackgroundActionSnapshot> actions;
  final OpenCrayRuntimeServiceConnectionStateSnapshot?
  runtimeServiceConnectionState;

  factory OpenCrayStrongBackgroundSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    final rawNotifications = payload['notifications'];
    final rawExactAlarms = payload['exactAlarms'];
    final rawBatteryOptimization = payload['batteryOptimization'];
    final rawRuntimeServiceConnectionState =
        payload['runtimeServiceConnectionState'];
    return OpenCrayStrongBackgroundSnapshot(
      source: payload['source'] as String? ?? 'strong-background',
      available: payload['available'] as bool? ?? false,
      tierId: payload['tierId'] as String? ?? 'baseline',
      setupComplete: payload['setupComplete'] as bool? ?? false,
      recommendedActionIds: _listOfStrings(payload['recommendedActionIds']),
      notifications: rawNotifications is Map<Object?, Object?>
          ? OpenCrayStrongBackgroundNotificationsSnapshot.fromMap(
              rawNotifications,
            )
          : const OpenCrayStrongBackgroundNotificationsSnapshot(),
      exactAlarms: rawExactAlarms is Map<Object?, Object?>
          ? OpenCrayStrongBackgroundExactAlarmSnapshot.fromMap(rawExactAlarms)
          : const OpenCrayStrongBackgroundExactAlarmSnapshot(),
      batteryOptimization: rawBatteryOptimization is Map<Object?, Object?>
          ? OpenCrayStrongBackgroundBatteryOptimizationSnapshot.fromMap(
              rawBatteryOptimization,
            )
          : const OpenCrayStrongBackgroundBatteryOptimizationSnapshot(),
      actions: _listOfMaps(payload['actions'])
          .map(OpenCrayStrongBackgroundActionSnapshot.fromMap)
          .toList(growable: false),
      runtimeServiceConnectionState:
          rawRuntimeServiceConnectionState is Map<Object?, Object?>
          ? OpenCrayRuntimeServiceConnectionStateSnapshot.fromMap(
              rawRuntimeServiceConnectionState,
            )
          : null,
    );
  }
}

class OpenCrayStrongBackgroundNotificationsSnapshot {
  const OpenCrayStrongBackgroundNotificationsSnapshot({
    this.permissionRequired = false,
    this.permissionGranted = false,
    this.enabled = false,
    this.configured = false,
  });

  final bool permissionRequired;
  final bool permissionGranted;
  final bool enabled;
  final bool configured;

  factory OpenCrayStrongBackgroundNotificationsSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayStrongBackgroundNotificationsSnapshot(
      permissionRequired: payload['permissionRequired'] as bool? ?? false,
      permissionGranted: payload['permissionGranted'] as bool? ?? false,
      enabled: payload['enabled'] as bool? ?? false,
      configured: payload['configured'] as bool? ?? false,
    );
  }
}

class OpenCrayStrongBackgroundExactAlarmSnapshot {
  const OpenCrayStrongBackgroundExactAlarmSnapshot({
    this.accessRequired = false,
    this.accessGranted = false,
    this.configured = false,
  });

  final bool accessRequired;
  final bool accessGranted;
  final bool configured;

  factory OpenCrayStrongBackgroundExactAlarmSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayStrongBackgroundExactAlarmSnapshot(
      accessRequired: payload['accessRequired'] as bool? ?? false,
      accessGranted: payload['accessGranted'] as bool? ?? false,
      configured: payload['configured'] as bool? ?? false,
    );
  }
}

class OpenCrayStrongBackgroundBatteryOptimizationSnapshot {
  const OpenCrayStrongBackgroundBatteryOptimizationSnapshot({
    this.supported = false,
    this.exempt = false,
    this.configured = false,
  });

  final bool supported;
  final bool exempt;
  final bool configured;

  factory OpenCrayStrongBackgroundBatteryOptimizationSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayStrongBackgroundBatteryOptimizationSnapshot(
      supported: payload['supported'] as bool? ?? false,
      exempt: payload['exempt'] as bool? ?? false,
      configured: payload['configured'] as bool? ?? false,
    );
  }
}

class OpenCrayStrongBackgroundActionSnapshot {
  const OpenCrayStrongBackgroundActionSnapshot({
    required this.id,
    required this.available,
    required this.recommended,
  });

  final String id;
  final bool available;
  final bool recommended;

  factory OpenCrayStrongBackgroundActionSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayStrongBackgroundActionSnapshot(
      id: payload['id'] as String? ?? '',
      available: payload['available'] as bool? ?? false,
      recommended: payload['recommended'] as bool? ?? false,
    );
  }
}

class OpenCrayStrongBackgroundActionResult {
  const OpenCrayStrongBackgroundActionResult({
    required this.source,
    required this.actionId,
    required this.available,
    required this.launched,
    this.reason,
    this.fallbackActionId,
  });

  final String source;
  final String actionId;
  final bool available;
  final bool launched;
  final String? reason;
  final String? fallbackActionId;

  factory OpenCrayStrongBackgroundActionResult.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayStrongBackgroundActionResult(
      source: payload['source'] as String? ?? 'strong-background-action',
      actionId: payload['actionId'] as String? ?? '',
      available: payload['available'] as bool? ?? false,
      launched: payload['launched'] as bool? ?? false,
      reason: payload['reason'] as String?,
      fallbackActionId: payload['fallbackActionId'] as String?,
    );
  }
}

List<String> _listOfStrings(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <String>[];
  }
  return list.whereType<String>().toList(growable: false);
}

List<Map<Object?, Object?>> _listOfMaps(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Map<Object?, Object?>>[];
  }
  return list.whereType<Map<Object?, Object?>>().toList(growable: false);
}
