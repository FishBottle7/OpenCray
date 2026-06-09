class OpenCrayNotificationSettingsSnapshot {
  const OpenCrayNotificationSettingsSnapshot({
    required this.masterEnabled,
    required this.defaultDeliveryModeId,
    required this.quietHoursEnabled,
    required this.quietHoursStartMinutes,
    required this.quietHoursEndMinutes,
    required this.approvalRequestsEnabled,
    required this.approvalReminderEnabled,
    required this.taskFinishedEnabled,
    required this.taskFailedEnabled,
    required this.scheduledWakeEnabled,
    required this.backgroundTaskPausedEnabled,
    required this.serviceRecoveredEnabled,
  });

  final bool masterEnabled;
  final String defaultDeliveryModeId;
  final bool quietHoursEnabled;
  final int quietHoursStartMinutes;
  final int quietHoursEndMinutes;
  final bool approvalRequestsEnabled;
  final bool approvalReminderEnabled;
  final bool taskFinishedEnabled;
  final bool taskFailedEnabled;
  final bool scheduledWakeEnabled;
  final bool backgroundTaskPausedEnabled;
  final bool serviceRecoveredEnabled;

  factory OpenCrayNotificationSettingsSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    return OpenCrayNotificationSettingsSnapshot(
      masterEnabled: payload['masterEnabled'] as bool? ?? true,
      defaultDeliveryModeId:
          payload['defaultDeliveryModeId'] as String? ?? 'all',
      quietHoursEnabled: payload['quietHoursEnabled'] as bool? ?? true,
      quietHoursStartMinutes: payload['quietHoursStartMinutes'] as int? ?? 1380,
      quietHoursEndMinutes: payload['quietHoursEndMinutes'] as int? ?? 480,
      approvalRequestsEnabled:
          payload['approvalRequestsEnabled'] as bool? ?? true,
      approvalReminderEnabled:
          payload['approvalReminderEnabled'] as bool? ?? true,
      taskFinishedEnabled: payload['taskFinishedEnabled'] as bool? ?? true,
      taskFailedEnabled: payload['taskFailedEnabled'] as bool? ?? true,
      scheduledWakeEnabled: payload['scheduledWakeEnabled'] as bool? ?? true,
      backgroundTaskPausedEnabled:
          payload['backgroundTaskPausedEnabled'] as bool? ?? true,
      serviceRecoveredEnabled:
          payload['serviceRecoveredEnabled'] as bool? ?? true,
    );
  }

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'masterEnabled': masterEnabled,
      'defaultDeliveryModeId': defaultDeliveryModeId,
      'quietHoursEnabled': quietHoursEnabled,
      'quietHoursStartMinutes': quietHoursStartMinutes,
      'quietHoursEndMinutes': quietHoursEndMinutes,
      'approvalRequestsEnabled': approvalRequestsEnabled,
      'approvalReminderEnabled': approvalReminderEnabled,
      'taskFinishedEnabled': taskFinishedEnabled,
      'taskFailedEnabled': taskFailedEnabled,
      'scheduledWakeEnabled': scheduledWakeEnabled,
      'backgroundTaskPausedEnabled': backgroundTaskPausedEnabled,
      'serviceRecoveredEnabled': serviceRecoveredEnabled,
    };
  }
}
