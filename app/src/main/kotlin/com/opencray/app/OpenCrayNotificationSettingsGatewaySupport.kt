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
  "newUserMessageEnabled" to newUserMessageEnabled,
  "scheduledWakeEnabled" to scheduledWakeEnabled,
  "backgroundTaskPausedEnabled" to backgroundTaskPausedEnabled,
  "serviceRecoveredEnabled" to serviceRecoveredEnabled,
)

internal fun Map<String, Any?>.toSaveNotificationSettingsRequest(): SaveNotificationSettingsRequest =
  SaveNotificationSettingsRequest(
    masterEnabled = this["masterEnabled"] as? Boolean ?: true,
    defaultDeliveryModeId = this["defaultDeliveryModeId"]?.toString().orEmpty(),
    quietHoursEnabled = this["quietHoursEnabled"] as? Boolean ?: true,
    quietHoursStartMinutes = (this["quietHoursStartMinutes"] as? Number)?.toInt()
      ?: RuntimeNotificationSettingsState.DEFAULT_QUIET_HOURS_START_MINUTES,
    quietHoursEndMinutes = (this["quietHoursEndMinutes"] as? Number)?.toInt()
      ?: RuntimeNotificationSettingsState.DEFAULT_QUIET_HOURS_END_MINUTES,
    approvalRequestsEnabled = this["approvalRequestsEnabled"] as? Boolean ?: true,
    approvalReminderEnabled = this["approvalReminderEnabled"] as? Boolean ?: true,
    taskFinishedEnabled = this["taskFinishedEnabled"] as? Boolean ?: false,
    taskFailedEnabled = this["taskFailedEnabled"] as? Boolean ?: true,
    newUserMessageEnabled = this["newUserMessageEnabled"] as? Boolean ?: true,
    scheduledWakeEnabled = this["scheduledWakeEnabled"] as? Boolean ?: false,
    backgroundTaskPausedEnabled = this["backgroundTaskPausedEnabled"] as? Boolean ?: true,
    serviceRecoveredEnabled = this["serviceRecoveredEnabled"] as? Boolean ?: false,
  )
