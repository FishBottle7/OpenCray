import '../../core/models/opencray_shell_snapshot.dart'
    show OpenCrayRuntimeServiceConnectionStateSnapshot;

enum StrongBackgroundTier { baseline, strongBackground }

enum StrongBackgroundActionId {
  openNotificationSettings,
  openExactAlarmSettings,
  openBatteryOptimizationSettings,
  requestIgnoreBatteryOptimizations,
}

extension StrongBackgroundTierWire on StrongBackgroundTier {
  String get id {
    switch (this) {
      case StrongBackgroundTier.baseline:
        return 'baseline';
      case StrongBackgroundTier.strongBackground:
        return 'strong_background';
    }
  }
}

extension StrongBackgroundActionIdWire on StrongBackgroundActionId {
  String get id {
    switch (this) {
      case StrongBackgroundActionId.openNotificationSettings:
        return 'open_notification_settings';
      case StrongBackgroundActionId.openExactAlarmSettings:
        return 'open_exact_alarm_settings';
      case StrongBackgroundActionId.openBatteryOptimizationSettings:
        return 'open_battery_optimization_settings';
      case StrongBackgroundActionId.requestIgnoreBatteryOptimizations:
        return 'request_ignore_battery_optimizations';
    }
  }
}

StrongBackgroundTier strongBackgroundTierFromId(String id) {
  switch (id) {
    case 'strong_background':
      return StrongBackgroundTier.strongBackground;
    default:
      return StrongBackgroundTier.baseline;
  }
}

StrongBackgroundActionId strongBackgroundActionIdFromId(String id) {
  switch (id) {
    case 'open_notification_settings':
      return StrongBackgroundActionId.openNotificationSettings;
    case 'open_exact_alarm_settings':
      return StrongBackgroundActionId.openExactAlarmSettings;
    case 'open_battery_optimization_settings':
      return StrongBackgroundActionId.openBatteryOptimizationSettings;
    case 'request_ignore_battery_optimizations':
    default:
      return StrongBackgroundActionId.requestIgnoreBatteryOptimizations;
  }
}

class StrongBackgroundNotificationsSnapshot {
  const StrongBackgroundNotificationsSnapshot({
    required this.permissionRequired,
    required this.permissionGranted,
    required this.enabled,
    required this.configured,
  });

  final bool permissionRequired;
  final bool permissionGranted;
  final bool enabled;
  final bool configured;
}

class StrongBackgroundExactAlarmSnapshot {
  const StrongBackgroundExactAlarmSnapshot({
    required this.accessRequired,
    required this.accessGranted,
    required this.configured,
  });

  final bool accessRequired;
  final bool accessGranted;
  final bool configured;
}

class StrongBackgroundBatteryOptimizationSnapshot {
  const StrongBackgroundBatteryOptimizationSnapshot({
    required this.supported,
    required this.exempt,
    required this.configured,
  });

  final bool supported;
  final bool exempt;
  final bool configured;
}

class StrongBackgroundActionSnapshot {
  const StrongBackgroundActionSnapshot({
    required this.id,
    required this.available,
    required this.recommended,
  });

  final StrongBackgroundActionId id;
  final bool available;
  final bool recommended;
}

class StrongBackgroundSnapshot {
  const StrongBackgroundSnapshot({
    required this.source,
    required this.available,
    required this.tier,
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
  final StrongBackgroundTier tier;
  final bool setupComplete;
  final List<StrongBackgroundActionId> recommendedActionIds;
  final StrongBackgroundNotificationsSnapshot notifications;
  final StrongBackgroundExactAlarmSnapshot exactAlarms;
  final StrongBackgroundBatteryOptimizationSnapshot batteryOptimization;
  final List<StrongBackgroundActionSnapshot> actions;
  final OpenCrayRuntimeServiceConnectionStateSnapshot?
  runtimeServiceConnectionState;

  StrongBackgroundActionSnapshot? action(StrongBackgroundActionId id) {
    for (final candidate in actions) {
      if (candidate.id == id) {
        return candidate;
      }
    }
    return null;
  }
}

class StrongBackgroundActionResult {
  const StrongBackgroundActionResult({
    required this.source,
    required this.actionId,
    required this.available,
    required this.launched,
    this.reason,
    this.fallbackActionId,
  });

  final String source;
  final StrongBackgroundActionId actionId;
  final bool available;
  final bool launched;
  final String? reason;
  final StrongBackgroundActionId? fallbackActionId;
}
