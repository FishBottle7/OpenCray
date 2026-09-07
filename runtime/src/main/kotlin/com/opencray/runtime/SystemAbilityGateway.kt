package com.opencray.runtime

/**
 * Host bridge for Android system abilities (clock app alarms/timers, local
 * notifications, installed apps, whitelisted system settings pages).
 *
 * Runtime stays pure Kotlin: the app layer implements this interface with an
 * application context and injects it via OpenCrayToolDispatcherConfig. When
 * no gateway is injected the system ability tools are not registered.
 */
interface SystemAbilityGateway {
  fun createAlarm(request: SystemAlarmCreateRequest): SystemAlarmResult

  fun startTimer(request: SystemTimerRequest): SystemTimerResult

  fun postNotification(request: SystemNotificationRequest): SystemNotificationResult

  fun listApps(request: SystemAppListRequest): SystemAppListResult

  fun openApp(request: SystemAppOpenRequest): SystemAppOpenResult

  fun openSettings(request: SystemSettingsOpenRequest): SystemSettingsOpenResult

  fun listUpcomingCalendarEvents(
    request: SystemCalendarUpcomingRequest,
  ): SystemCalendarEventListResult

  fun createCalendarEvent(request: SystemCalendarEventCreateRequest): SystemCalendarEventResult

  fun searchContacts(request: SystemContactSearchRequest): SystemContactListResult
}

data class SystemAlarmCreateRequest(
  val hour: Int,
  val minute: Int,
  val label: String? = null,
  val daysOfWeek: List<Int> = emptyList(),
)

data class SystemAlarmResult(
  val success: Boolean,
  val available: Boolean = true,
  val summary: String,
)

data class SystemTimerRequest(
  val durationSeconds: Int,
  val label: String? = null,
)

data class SystemTimerResult(
  val success: Boolean,
  val available: Boolean = true,
  val summary: String,
)

data class SystemNotificationRequest(
  val title: String,
  val body: String,
)

data class SystemNotificationResult(
  val success: Boolean,
  val permissionRequired: Boolean = false,
  val summary: String,
)

data class SystemAppListRequest(
  val query: String? = null,
  val limit: Int = 50,
)

data class SystemAppEntry(
  val packageName: String,
  val label: String,
)

data class SystemAppListResult(
  val success: Boolean,
  val apps: List<SystemAppEntry> = emptyList(),
  val truncated: Boolean = false,
)

data class SystemAppOpenRequest(
  val packageName: String,
)

data class SystemAppOpenResult(
  val success: Boolean,
  val available: Boolean = true,
  val label: String? = null,
)

data class SystemSettingsOpenRequest(
  val page: String,
)

data class SystemSettingsOpenResult(
  val success: Boolean,
  val page: String,
  val summary: String,
)

/**
 * Milliseconds-since-epoch timestamps with explicit offset handling kept on the
 * app side; runtime only carries plain numbers and strings.
 */
data class SystemCalendarUpcomingRequest(
  val days: Int,
  val limit: Int,
)

data class SystemCalendarEventEntry(
  val eventId: Long,
  val title: String,
  val beginEpochMillis: Long,
  val endEpochMillis: Long,
  val allDay: Boolean,
  val calendarName: String? = null,
  val location: String? = null,
)

data class SystemCalendarEventListResult(
  val success: Boolean,
  val events: List<SystemCalendarEventEntry> = emptyList(),
  val truncated: Boolean = false,
  val permissionRequired: Boolean = false,
  val summary: String,
)

data class SystemCalendarEventCreateRequest(
  val title: String,
  val beginEpochMillis: Long,
  val durationMinutes: Int,
  val location: String? = null,
  val description: String? = null,
)

data class SystemCalendarEventResult(
  val success: Boolean,
  val eventId: Long? = null,
  val permissionRequired: Boolean = false,
  val summary: String,
)

data class SystemContactSearchRequest(
  val query: String,
  val limit: Int,
)

data class SystemContactEntry(
  val contactId: Long,
  val displayName: String,
  val phoneNumbers: List<String> = emptyList(),
  val emails: List<String> = emptyList(),
  val organization: String? = null,
)

data class SystemContactListResult(
  val success: Boolean,
  val contacts: List<SystemContactEntry> = emptyList(),
  val truncated: Boolean = false,
  val permissionRequired: Boolean = false,
  val summary: String,
)

internal object SystemAbilityToolMetadataKeys {
  const val SYSTEM_ABILITY_ACTION: String = "systemAction"
  const val SYSTEM_ACTION_SUMMARY: String = "systemActionSummary"
  const val MISSING_PERMISSIONS: String = "missingPermissions"
  const val APP_COUNT: String = "appCount"
  const val APP_TRUNCATED: String = "appListTruncated"
  const val EVENT_COUNT: String = "eventCount"
  const val CONTACT_COUNT: String = "contactCount"
}
