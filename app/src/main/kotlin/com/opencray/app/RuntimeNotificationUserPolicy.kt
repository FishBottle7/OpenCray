package com.opencray.app

import java.time.LocalTime

internal enum class RuntimeNotificationUserEvent {
  APPROVAL_REQUEST,
  APPROVAL_REMINDER,
  TASK_FINISHED,
  TASK_FAILED,
  SCHEDULED_WAKE,
  BACKGROUND_TASK_PAUSED,
  SERVICE_RECOVERED,
  ;
}

internal object RuntimeNotificationUserPolicy {
  fun allows(
    settings: RuntimeNotificationSettingsState,
    event: RuntimeNotificationUserEvent,
    minutesOfDay: Int = currentLocalMinutesOfDay(),
  ): Boolean {
    if (!settings.masterEnabled || !isChannelEnabled(settings, event)) {
      return false
    }
    val critical = isCritical(event)
    if (settings.isQuietHoursActiveAt(minutesOfDay)) {
      return critical
    }
    return when (settings.defaultDeliveryMode) {
      RuntimeNotificationDeliveryMode.CRITICAL -> critical
      RuntimeNotificationDeliveryMode.ALL -> true
    }
  }

  private fun isChannelEnabled(
    settings: RuntimeNotificationSettingsState,
    event: RuntimeNotificationUserEvent,
  ): Boolean = when (event) {
    RuntimeNotificationUserEvent.APPROVAL_REQUEST -> settings.approvalRequestsEnabled
    RuntimeNotificationUserEvent.APPROVAL_REMINDER -> settings.approvalReminderEnabled
    RuntimeNotificationUserEvent.TASK_FINISHED -> settings.taskFinishedEnabled
    RuntimeNotificationUserEvent.TASK_FAILED -> settings.taskFailedEnabled
    RuntimeNotificationUserEvent.SCHEDULED_WAKE -> settings.scheduledWakeEnabled
    RuntimeNotificationUserEvent.BACKGROUND_TASK_PAUSED -> settings.backgroundTaskPausedEnabled
    RuntimeNotificationUserEvent.SERVICE_RECOVERED -> settings.serviceRecoveredEnabled
  }

  private fun isCritical(event: RuntimeNotificationUserEvent): Boolean = when (event) {
    RuntimeNotificationUserEvent.APPROVAL_REQUEST,
    RuntimeNotificationUserEvent.TASK_FAILED,
    -> true

    RuntimeNotificationUserEvent.APPROVAL_REMINDER,
    RuntimeNotificationUserEvent.TASK_FINISHED,
    RuntimeNotificationUserEvent.SCHEDULED_WAKE,
    RuntimeNotificationUserEvent.BACKGROUND_TASK_PAUSED,
    RuntimeNotificationUserEvent.SERVICE_RECOVERED,
    -> false
  }
}

internal fun currentLocalMinutesOfDay(now: LocalTime = LocalTime.now()): Int =
  (now.hour * 60) + now.minute
