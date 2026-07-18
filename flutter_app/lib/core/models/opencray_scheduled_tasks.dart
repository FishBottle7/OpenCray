class OpenCrayScheduledTasksSnapshot {
  const OpenCrayScheduledTasksSnapshot({
    required this.tasks,
    required this.totalCount,
    required this.enabledCount,
  });

  final List<OpenCrayScheduledTaskSummary> tasks;
  final int totalCount;
  final int enabledCount;

  factory OpenCrayScheduledTasksSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) => OpenCrayScheduledTasksSnapshot(
    tasks: _listOfMaps(
      payload['tasks'],
    ).map(OpenCrayScheduledTaskSummary.fromMap).toList(growable: false),
    totalCount: payload['totalCount'] as int? ?? 0,
    enabledCount:
        payload['enabledCount'] as int? ??
        _listOfMaps(
          payload['tasks'],
        ).where((task) => task['enabled'] == true).length,
  );
}

class OpenCrayScheduledTaskSummary {
  const OpenCrayScheduledTaskSummary({
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

  factory OpenCrayScheduledTaskSummary.fromMap(Map<Object?, Object?> payload) =>
      OpenCrayScheduledTaskSummary(
        scheduleId: payload['scheduleId'] as String? ?? '',
        sessionId: payload['sessionId'] as String? ?? '',
        title: payload['title'] as String? ?? '',
        enabled: payload['enabled'] as bool? ?? false,
        triggerKind: payload['triggerKind'] as String? ?? '',
        triggerSummary: payload['triggerSummary'] as String? ?? '',
        nextTriggerAtEpochMs: payload['nextTriggerAtEpochMs'] as int?,
        snoozedUntilEpochMs: payload['snoozedUntilEpochMs'] as int?,
      );
}

class OpenCrayScheduledTaskDetailSnapshot {
  const OpenCrayScheduledTaskDetailSnapshot({
    required this.task,
    required this.recentRuns,
    required this.totalRunCount,
  });

  final OpenCrayScheduledTaskDetails task;
  final List<OpenCrayScheduledTaskRunRecord> recentRuns;
  final int totalRunCount;

  factory OpenCrayScheduledTaskDetailSnapshot.fromMap(
    Map<Object?, Object?> payload,
  ) {
    final rawTask = payload['task'];
    if (rawTask is! Map<Object?, Object?>) {
      throw const FormatException('Scheduled task detail is missing task.');
    }
    return OpenCrayScheduledTaskDetailSnapshot(
      task: OpenCrayScheduledTaskDetails.fromMap(rawTask),
      recentRuns: _listOfMaps(
        payload['recentRuns'],
      ).map(OpenCrayScheduledTaskRunRecord.fromMap).toList(growable: false),
      totalRunCount: payload['totalRunCount'] as int? ?? 0,
    );
  }
}

class OpenCrayScheduledTaskDetails extends OpenCrayScheduledTaskSummary {
  const OpenCrayScheduledTaskDetails({
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

  factory OpenCrayScheduledTaskDetails.fromMap(Map<Object?, Object?> payload) =>
      OpenCrayScheduledTaskDetails(
        scheduleId: payload['scheduleId'] as String? ?? '',
        sessionId: payload['sessionId'] as String? ?? '',
        title: payload['title'] as String? ?? '',
        enabled: payload['enabled'] as bool? ?? false,
        triggerKind: payload['triggerKind'] as String? ?? '',
        triggerSummary: payload['triggerSummary'] as String? ?? '',
        prompt: payload['prompt'] as String? ?? '',
        nextTriggerAtEpochMs: payload['nextTriggerAtEpochMs'] as int?,
        snoozedUntilEpochMs: payload['snoozedUntilEpochMs'] as int?,
        conflictPolicy: payload['conflictPolicy'] as String? ?? '',
        foregroundNotificationRequired:
            payload['foregroundNotificationRequired'] as bool? ?? true,
        notifyOnQueued: payload['notifyOnQueued'] as bool? ?? false,
        notifyOnApproval: payload['notifyOnApproval'] as bool? ?? false,
        notifyOnCompletion: payload['notifyOnCompletion'] as bool? ?? false,
        notifyOnInterruption: payload['notifyOnInterruption'] as bool? ?? false,
        createdAtEpochMs: payload['createdAtEpochMs'] as int? ?? 0,
        updatedAtEpochMs: payload['updatedAtEpochMs'] as int? ?? 0,
      );
}

class OpenCrayScheduledTaskRunRecord {
  const OpenCrayScheduledTaskRunRecord({
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

  factory OpenCrayScheduledTaskRunRecord.fromMap(
    Map<Object?, Object?> payload,
  ) => OpenCrayScheduledTaskRunRecord(
    scheduleRunId: payload['scheduleRunId'] as String? ?? '',
    triggerReason: payload['triggerReason'] as String? ?? '',
    result: payload['result'] as String? ?? '',
    triggeredAtEpochMs: payload['triggeredAtEpochMs'] as int? ?? 0,
    acceptedAtEpochMs: payload['acceptedAtEpochMs'] as int?,
    createdRunId: payload['createdRunId'] as String?,
    createdTaskId: payload['createdTaskId'] as String?,
    failureReason: payload['failureReason'] as String?,
    recoverySource: payload['recoverySource'] as String?,
    updatedAtEpochMs: payload['updatedAtEpochMs'] as int? ?? 0,
  );
}

class OpenCrayScheduledTaskActionResult {
  const OpenCrayScheduledTaskActionResult({
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

  factory OpenCrayScheduledTaskActionResult.fromMap(
    Map<Object?, Object?> payload,
  ) => OpenCrayScheduledTaskActionResult(
    action: payload['action'] as String? ?? '',
    scheduleId: payload['scheduleId'] as String? ?? '',
    title: payload['title'] as String? ?? '',
    enabled: payload['enabled'] as bool?,
    scheduleRunId: payload['scheduleRunId'] as String?,
    requestedAtEpochMs: payload['requestedAtEpochMs'] as int?,
    nextTriggerAtEpochMs: payload['nextTriggerAtEpochMs'] as int?,
    snoozedUntilEpochMs: payload['snoozedUntilEpochMs'] as int?,
  );
}

List<Map<Object?, Object?>> _listOfMaps(Object? payload) {
  final list = payload as List<Object?>?;
  if (list == null) {
    return const <Map<Object?, Object?>>[];
  }
  return list.whereType<Map<Object?, Object?>>().toList(growable: false);
}
