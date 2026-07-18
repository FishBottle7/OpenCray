class ScheduledTasksSnapshot {
  const ScheduledTasksSnapshot({
    required this.tasks,
    required this.totalCount,
    required this.enabledCount,
  });

  final List<ScheduledTaskSummary> tasks;
  final int totalCount;
  final int enabledCount;
}

class ScheduledTaskSummary {
  const ScheduledTaskSummary({
    required this.scheduleId,
    required this.sessionId,
    required this.title,
    required this.enabled,
    required this.triggerKind,
    required this.triggerSummary,
    this.nextTriggerAtEpochMs,
    this.snoozedUntilEpochMs,
  });

  final String scheduleId;
  final String sessionId;
  final String title;
  final bool enabled;
  final String triggerKind;
  final String triggerSummary;
  final int? nextTriggerAtEpochMs;
  final int? snoozedUntilEpochMs;
}

class ScheduledTaskDetailSnapshot {
  const ScheduledTaskDetailSnapshot({
    required this.task,
    required this.recentRuns,
    required this.totalRunCount,
  });

  final ScheduledTaskDetails task;
  final List<ScheduledTaskRunRecord> recentRuns;
  final int totalRunCount;
}

class ScheduledTaskDetails extends ScheduledTaskSummary {
  const ScheduledTaskDetails({
    required super.scheduleId,
    required super.sessionId,
    required super.title,
    required super.enabled,
    required super.triggerKind,
    required super.triggerSummary,
    required this.prompt,
    required this.conflictPolicy,
    required this.foregroundNotificationRequired,
    required this.notifyOnQueued,
    required this.notifyOnApproval,
    required this.notifyOnCompletion,
    required this.notifyOnInterruption,
    required this.createdAtEpochMs,
    required this.updatedAtEpochMs,
    super.nextTriggerAtEpochMs,
    super.snoozedUntilEpochMs,
  });

  final String prompt;
  final String conflictPolicy;
  final bool foregroundNotificationRequired;
  final bool notifyOnQueued;
  final bool notifyOnApproval;
  final bool notifyOnCompletion;
  final bool notifyOnInterruption;
  final int createdAtEpochMs;
  final int updatedAtEpochMs;
}

class ScheduledTaskRunRecord {
  const ScheduledTaskRunRecord({
    required this.scheduleRunId,
    required this.triggerReason,
    required this.result,
    required this.triggeredAtEpochMs,
    required this.updatedAtEpochMs,
    this.acceptedAtEpochMs,
    this.createdRunId,
    this.createdTaskId,
    this.failureReason,
    this.recoverySource,
  });

  final String scheduleRunId;
  final String triggerReason;
  final String result;
  final int triggeredAtEpochMs;
  final int? acceptedAtEpochMs;
  final String? createdRunId;
  final String? createdTaskId;
  final String? failureReason;
  final String? recoverySource;
  final int updatedAtEpochMs;
}

class ScheduledTaskActionResult {
  const ScheduledTaskActionResult({
    required this.action,
    required this.scheduleId,
    required this.title,
    this.enabled,
    this.scheduleRunId,
    this.requestedAtEpochMs,
    this.nextTriggerAtEpochMs,
    this.snoozedUntilEpochMs,
  });

  final String action;
  final String scheduleId;
  final String title;
  final bool? enabled;
  final String? scheduleRunId;
  final int? requestedAtEpochMs;
  final int? nextTriggerAtEpochMs;
  final int? snoozedUntilEpochMs;
}
