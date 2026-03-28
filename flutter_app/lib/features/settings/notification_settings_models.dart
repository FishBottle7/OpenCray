enum NotificationDeliveryMode { critical, all }

extension NotificationDeliveryModeWire on NotificationDeliveryMode {
  String get id {
    switch (this) {
      case NotificationDeliveryMode.critical:
        return 'critical';
      case NotificationDeliveryMode.all:
        return 'all';
    }
  }
}

NotificationDeliveryMode notificationDeliveryModeFromId(String rawValue) {
  switch (rawValue) {
    case 'all':
      return NotificationDeliveryMode.all;
    case 'critical':
    default:
      return NotificationDeliveryMode.critical;
  }
}

class NotificationSettingsSnapshot {
  const NotificationSettingsSnapshot({
    required this.masterEnabled,
    required this.defaultDeliveryMode,
    required this.quietHoursEnabled,
    required this.quietHoursStartMinutes,
    required this.quietHoursEndMinutes,
    required this.approvalRequestsEnabled,
    required this.approvalReminderEnabled,
    required this.taskFinishedEnabled,
    required this.taskFailedEnabled,
    required this.newUserMessageEnabled,
    required this.scheduledWakeEnabled,
    required this.backgroundTaskPausedEnabled,
    required this.serviceRecoveredEnabled,
  });

  final bool masterEnabled;
  final NotificationDeliveryMode defaultDeliveryMode;
  final bool quietHoursEnabled;
  final int quietHoursStartMinutes;
  final int quietHoursEndMinutes;
  final bool approvalRequestsEnabled;
  final bool approvalReminderEnabled;
  final bool taskFinishedEnabled;
  final bool taskFailedEnabled;
  final bool newUserMessageEnabled;
  final bool scheduledWakeEnabled;
  final bool backgroundTaskPausedEnabled;
  final bool serviceRecoveredEnabled;

  NotificationSettingsSnapshot copyWith({
    bool? masterEnabled,
    NotificationDeliveryMode? defaultDeliveryMode,
    bool? quietHoursEnabled,
    int? quietHoursStartMinutes,
    int? quietHoursEndMinutes,
    bool? approvalRequestsEnabled,
    bool? approvalReminderEnabled,
    bool? taskFinishedEnabled,
    bool? taskFailedEnabled,
    bool? newUserMessageEnabled,
    bool? scheduledWakeEnabled,
    bool? backgroundTaskPausedEnabled,
    bool? serviceRecoveredEnabled,
  }) {
    return NotificationSettingsSnapshot(
      masterEnabled: masterEnabled ?? this.masterEnabled,
      defaultDeliveryMode: defaultDeliveryMode ?? this.defaultDeliveryMode,
      quietHoursEnabled: quietHoursEnabled ?? this.quietHoursEnabled,
      quietHoursStartMinutes:
          quietHoursStartMinutes ?? this.quietHoursStartMinutes,
      quietHoursEndMinutes: quietHoursEndMinutes ?? this.quietHoursEndMinutes,
      approvalRequestsEnabled:
          approvalRequestsEnabled ?? this.approvalRequestsEnabled,
      approvalReminderEnabled:
          approvalReminderEnabled ?? this.approvalReminderEnabled,
      taskFinishedEnabled: taskFinishedEnabled ?? this.taskFinishedEnabled,
      taskFailedEnabled: taskFailedEnabled ?? this.taskFailedEnabled,
      newUserMessageEnabled:
          newUserMessageEnabled ?? this.newUserMessageEnabled,
      scheduledWakeEnabled: scheduledWakeEnabled ?? this.scheduledWakeEnabled,
      backgroundTaskPausedEnabled:
          backgroundTaskPausedEnabled ?? this.backgroundTaskPausedEnabled,
      serviceRecoveredEnabled:
          serviceRecoveredEnabled ?? this.serviceRecoveredEnabled,
    );
  }
}
