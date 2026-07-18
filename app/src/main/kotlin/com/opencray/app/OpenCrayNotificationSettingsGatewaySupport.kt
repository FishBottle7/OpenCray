package com.opencray.app

import com.opencray.app.facade.notifications.NotificationSettingsSnapshot
import com.opencray.app.facade.notifications.SaveNotificationSettingsRequest

internal fun NotificationSettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "masterEnabled" to masterEnabled,
  "defaultDeliveryModeId" to defaultDeliveryMode.wireValue,
  "quietHoursEnabled" to quietHoursEnabled,
  "quietHoursStartMinutes" to quietHoursStartMinutes,
  "quietHoursEndMinutes" to quietHoursEndMinutes,
  "approvalRequestsEnabled" to approvalRequestsEnabled,
  "approvalReminderEnabled" to approvalReminderEnabled,
  "taskFinishedEnabled" to taskFinishedEnabled,
  "taskFailedEnabled" to taskFailedEnabled,
  "scheduledWakeEnabled" to scheduledWakeEnabled,
  "backgroundTaskPausedEnabled" to backgroundTaskPausedEnabled,
  "serviceRecoveredEnabled" to serviceRecoveredEnabled,
)

internal fun Map<String, Any?>.toSaveNotificationSettingsRequest(): SaveNotificationSettingsRequest {
  val defaults = RuntimeNotificationSettingsState()
  return SaveNotificationSettingsRequest(
    masterEnabled = this["masterEnabled"] as? Boolean ?: defaults.masterEnabled,
    defaultDeliveryModeId = this["defaultDeliveryModeId"]
      ?.toString()
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: defaults.defaultDeliveryMode.wireValue,
    quietHoursEnabled = this["quietHoursEnabled"] as? Boolean ?: defaults.quietHoursEnabled,
    quietHoursStartMinutes = (this["quietHoursStartMinutes"] as? Number)?.toInt()
      ?: defaults.quietHoursStartMinutes,
    quietHoursEndMinutes = (this["quietHoursEndMinutes"] as? Number)?.toInt()
      ?: defaults.quietHoursEndMinutes,
    approvalRequestsEnabled = this["approvalRequestsEnabled"] as? Boolean
      ?: defaults.approvalRequestsEnabled,
    approvalReminderEnabled = this["approvalReminderEnabled"] as? Boolean
      ?: defaults.approvalReminderEnabled,
    taskFinishedEnabled = this["taskFinishedEnabled"] as? Boolean
      ?: defaults.taskFinishedEnabled,
    taskFailedEnabled = this["taskFailedEnabled"] as? Boolean ?: defaults.taskFailedEnabled,
    scheduledWakeEnabled = this["scheduledWakeEnabled"] as? Boolean
      ?: defaults.scheduledWakeEnabled,
    backgroundTaskPausedEnabled = this["backgroundTaskPausedEnabled"] as? Boolean
      ?: defaults.backgroundTaskPausedEnabled,
    serviceRecoveredEnabled = this["serviceRecoveredEnabled"] as? Boolean
      ?: defaults.serviceRecoveredEnabled,
  )
}
