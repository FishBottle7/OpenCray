package com.opencray.app

import android.content.Intent

internal sealed interface RuntimeServiceNotificationCommand {
  val sessionId: String?
  val taskId: String?
  val runId: String?

  data class ApproveApproval(
    override val sessionId: String?,
    override val taskId: String?,
    override val runId: String?,
    val executionId: String? = null,
    val executionOrdinal: Int? = null,
  ) : RuntimeServiceNotificationCommand

  data class RejectApproval(
    override val sessionId: String?,
    override val taskId: String?,
    override val runId: String?,
    val executionId: String? = null,
    val executionOrdinal: Int? = null,
  ) : RuntimeServiceNotificationCommand

  data class RunScheduleNow(
    override val sessionId: String?,
    val scheduleId: String,
  ) : RuntimeServiceNotificationCommand {
    override val taskId: String? = null
    override val runId: String? = null
  }

  data class DisableSchedule(
    override val sessionId: String?,
    val scheduleId: String,
  ) : RuntimeServiceNotificationCommand {
    override val taskId: String? = null
    override val runId: String? = null
  }

  data class SnoozeSchedule(
    override val sessionId: String?,
    val scheduleId: String,
  ) : RuntimeServiceNotificationCommand {
    override val taskId: String? = null
    override val runId: String? = null
  }
}

internal fun parseRuntimeNotificationCommand(
  intent: Intent?,
): RuntimeServiceNotificationCommand? {
  return parseRuntimeNotificationCommand(
    action = safeNotificationAction(intent),
    sessionId = notificationCommandExtra(
      intent = intent,
      key = RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID,
    ),
    taskId = notificationCommandExtra(
      intent = intent,
      key = RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID,
    ),
    runId = notificationCommandExtra(
      intent = intent,
      key = RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_RUN_ID,
    ),
    scheduleId = notificationCommandExtra(
      intent = intent,
      key = RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID,
    ),
    executionId = notificationCommandExtra(
      intent = intent,
      key = RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_EXECUTION_ID,
    ),
    executionOrdinal = runCatching {
      intent?.getIntExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_EXECUTION_ORDINAL, 0)
        ?: 0
    }.getOrNull()
      ?.takeIf { value -> value > 0 },
  )
}

internal fun parseRuntimeNotificationCommand(
  action: String?,
  sessionId: String?,
  taskId: String?,
  runId: String?,
  scheduleId: String? = null,
  executionId: String? = null,
  executionOrdinal: Int? = null,
): RuntimeServiceNotificationCommand? {
  val normalizedExecutionId = executionId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  return when (action) {
    RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL ->
      if (taskId == null && runId == null) {
        null
      } else {
        RuntimeServiceNotificationCommand.ApproveApproval(
          sessionId = sessionId,
          taskId = taskId,
          runId = runId,
          executionId = normalizedExecutionId,
          executionOrdinal = executionOrdinal,
        )
      }

    RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL ->
      if (taskId == null && runId == null) {
        null
      } else {
        RuntimeServiceNotificationCommand.RejectApproval(
          sessionId = sessionId,
          taskId = taskId,
          runId = runId,
          executionId = normalizedExecutionId,
          executionOrdinal = executionOrdinal,
        )
      }

    RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW -> {
      val normalizedScheduleId = scheduleId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      RuntimeServiceNotificationCommand.RunScheduleNow(
        sessionId = sessionId,
        scheduleId = normalizedScheduleId,
      )
    }

    RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE -> {
      val normalizedScheduleId = scheduleId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      RuntimeServiceNotificationCommand.DisableSchedule(
        sessionId = sessionId,
        scheduleId = normalizedScheduleId,
      )
    }

    RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE -> {
      val normalizedScheduleId = scheduleId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      RuntimeServiceNotificationCommand.SnoozeSchedule(
        sessionId = sessionId,
        scheduleId = normalizedScheduleId,
      )
    }

    else -> null
  }
}

private fun notificationCommandExtra(
  intent: Intent?,
  key: String,
): String? = runCatching {
  intent?.getStringExtra(key)
}.getOrNull()
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun safeNotificationAction(
  intent: Intent?,
): String? = runCatching {
  intent?.action
}.getOrNull()
