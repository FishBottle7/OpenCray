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
  ) : RuntimeServiceNotificationCommand

  data class RejectApproval(
    override val sessionId: String?,
    override val taskId: String?,
    override val runId: String?,
  ) : RuntimeServiceNotificationCommand

  data class RunScheduleNow(
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
  )
}

internal fun parseRuntimeNotificationCommand(
  action: String?,
  sessionId: String?,
  taskId: String?,
  runId: String?,
  scheduleId: String? = null,
): RuntimeServiceNotificationCommand? {
  return when (action) {
    RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL ->
      if (taskId == null && runId == null) {
        null
      } else {
        RuntimeServiceNotificationCommand.ApproveApproval(
          sessionId = sessionId,
          taskId = taskId,
          runId = runId,
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
