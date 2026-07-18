package com.opencray.app

import com.opencray.runtime.ScheduledTaskDetails
import com.opencray.runtime.ScheduledTaskGetRequest
import com.opencray.runtime.ScheduledTaskListRequest
import com.opencray.runtime.ScheduledTaskManager
import com.opencray.runtime.ScheduledTaskRunNowRequest
import com.opencray.runtime.ScheduledTaskRunRecordSummary
import com.opencray.runtime.ScheduledTaskSnoozeRequest
import com.opencray.runtime.ScheduledTaskSummary
import com.opencray.runtime.ScheduledTaskUpdateRequest

internal fun ScheduledTaskManager.loadScheduledTasksGatewayMap(
  limit: Int = DEFAULT_SCHEDULED_TASK_GATEWAY_LIMIT,
): Map<String, Any?> {
  val result = list(ScheduledTaskListRequest(limit = limit))
  val enabledCount = list(
    ScheduledTaskListRequest(
      enabled = true,
      limit = 1,
    ),
  ).totalCount
  return mapOf(
    "tasks" to result.tasks.map(ScheduledTaskSummary::toGatewayMap),
    "totalCount" to result.totalCount,
    "enabledCount" to enabledCount,
  )
}

internal fun ScheduledTaskManager.loadScheduledTaskGatewayMap(
  scheduleId: String,
  recentRunLimit: Int = DEFAULT_SCHEDULED_TASK_RUN_HISTORY_LIMIT,
): Map<String, Any?> {
  val result = get(
    ScheduledTaskGetRequest(
      scheduleId = scheduleId,
      recentRunLimit = recentRunLimit,
    ),
  )
  return mapOf(
    "task" to result.task.toGatewayMap(),
    "recentRuns" to result.recentRuns.map(ScheduledTaskRunRecordSummary::toGatewayMap),
    "totalRunCount" to result.totalRunCount,
  )
}

internal fun ScheduledTaskManager.updateScheduledTaskEnabledGatewayMap(
  scheduleId: String,
  enabled: Boolean,
): Map<String, Any?> {
  val result = update(
    ScheduledTaskUpdateRequest(
      scheduleId = scheduleId,
      enabled = enabled,
    ),
  )
  return mapOf(
    "action" to "update_enabled",
    "scheduleId" to result.scheduleId,
    "sessionId" to result.sessionId,
    "title" to result.title,
    "enabled" to result.enabled,
    "nextTriggerAtEpochMs" to result.nextTriggerAtEpochMs,
    "snoozedUntilEpochMs" to result.snoozedUntilEpochMs,
  )
}

internal fun ScheduledTaskManager.runScheduledTaskNowGatewayMap(
  scheduleId: String,
): Map<String, Any?> {
  val result = runNow(ScheduledTaskRunNowRequest(scheduleId))
  return mapOf(
    "action" to "run_now",
    "scheduleId" to result.scheduleId,
    "sessionId" to result.sessionId,
    "title" to result.title,
    "scheduleRunId" to result.scheduleRunId,
    "requestedAtEpochMs" to result.requestedAtEpochMs,
  )
}

internal fun ScheduledTaskManager.snoozeScheduledTaskGatewayMap(
  scheduleId: String,
  durationMinutes: Int,
): Map<String, Any?> {
  val result = snooze(
    ScheduledTaskSnoozeRequest(
      scheduleId = scheduleId,
      durationMinutes = durationMinutes,
    ),
  )
  return mapOf(
    "action" to "snooze",
    "scheduleId" to result.scheduleId,
    "sessionId" to result.sessionId,
    "title" to result.title,
    "snoozedUntilEpochMs" to result.snoozedUntilEpochMs,
    "nextTriggerAtEpochMs" to result.nextTriggerAtEpochMs,
  )
}

private fun ScheduledTaskSummary.toGatewayMap(): Map<String, Any?> = mapOf(
  "scheduleId" to scheduleId,
  "sessionId" to sessionId,
  "title" to title,
  "enabled" to enabled,
  "triggerKind" to triggerKind,
  "triggerSummary" to triggerSummary,
  "nextTriggerAtEpochMs" to nextTriggerAtEpochMs,
  "snoozedUntilEpochMs" to snoozedUntilEpochMs,
)

private fun ScheduledTaskDetails.toGatewayMap(): Map<String, Any?> = mapOf(
  "scheduleId" to scheduleId,
  "sessionId" to sessionId,
  "title" to title,
  "prompt" to prompt,
  "enabled" to enabled,
  "triggerKind" to triggerKind,
  "triggerSummary" to triggerSummary,
  "nextTriggerAtEpochMs" to nextTriggerAtEpochMs,
  "snoozedUntilEpochMs" to snoozedUntilEpochMs,
  "conflictPolicy" to conflictPolicy,
  "foregroundNotificationRequired" to foregroundNotificationRequired,
  "notifyOnQueued" to notifyOnQueued,
  "notifyOnApproval" to notifyOnApproval,
  "notifyOnCompletion" to notifyOnCompletion,
  "notifyOnInterruption" to notifyOnInterruption,
  "createdAtEpochMs" to createdAtEpochMs,
  "updatedAtEpochMs" to updatedAtEpochMs,
)

private fun ScheduledTaskRunRecordSummary.toGatewayMap(): Map<String, Any?> = mapOf(
  "scheduleRunId" to scheduleRunId,
  "triggerReason" to triggerReason,
  "result" to result,
  "triggeredAtEpochMs" to triggeredAtEpochMs,
  "acceptedAtEpochMs" to acceptedAtEpochMs,
  "createdRunId" to createdRunId,
  "createdTaskId" to createdTaskId,
  "failureReason" to failureReason,
  "recoverySource" to recoverySource,
  "updatedAtEpochMs" to updatedAtEpochMs,
)

private const val DEFAULT_SCHEDULED_TASK_GATEWAY_LIMIT: Int = 256
private const val DEFAULT_SCHEDULED_TASK_RUN_HISTORY_LIMIT: Int = 20
