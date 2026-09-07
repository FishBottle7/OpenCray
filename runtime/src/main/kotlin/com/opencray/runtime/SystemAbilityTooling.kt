package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.policy.SystemAbilityIntent
import com.opencray.runtime.policy.SystemAbilityIntentKind
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.policy.ToolResultEnvelope
import com.opencray.runtime.policy.ToolResultLimitKind
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import kotlinx.serialization.json.JsonObject

private const val SYSTEM_ABILITY_GATEWAY_UNAVAILABLE_CONTENT =
  "System ability tools are unavailable in the current execution environment."

internal val supportedSystemSettingsPages = setOf(
  "battery_optimization",
  "notifications",
  "exact_alarms",
  "app_details",
)

internal fun OpenCrayToolDispatcher.createSystemAlarm(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_alarm_create"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val hour = arguments.requiredInt("hour")
  val minute = arguments.requiredInt("minute")
  if (hour !in 0..23) {
    throw IllegalArgumentException("$toolName hour must be within 0-23.")
  }
  if (minute !in 0..59) {
    throw IllegalArgumentException("$toolName minute must be within 0-59.")
  }
  val label = arguments.optionalString("label")?.trim()?.takeIf(String::isNotBlank)
  val days = arguments.optionalIntArray("days").orEmpty().filter { it in 1..7 }.distinct().sorted()
  val daysSummary = if (days.isEmpty()) "one-shot" else "weekly ${days.joinToString(",")}"
  val actionSummary = "%02d:%02d %s".format(hour, minute, label ?: "(no label)")
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.CREATE_ALARM,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can create this device alarm.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.createAlarm(
      SystemAlarmCreateRequest(
        hour = hour,
        minute = minute,
        label = label,
        daysOfWeek = days,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success && result.available) "Device alarm created." else "Device alarm was not created.")
      append("time=%02d:%02d".format(hour, minute))
      appendLine()
      append("repeat=$daysSummary")
      label?.let { appendLine(); append("label=$it") }
      appendLine()
      append(result.summary)
    },
    success = result.success && result.available,
    errorCode = if (!result.available) "SYSTEM_ABILITY_UNAVAILABLE" else null,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "alarm.set",
    ),
  )
}

internal fun OpenCrayToolDispatcher.startSystemTimer(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_timer_start"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val seconds = arguments.requiredInt("seconds")
  if (seconds < 1) {
    throw IllegalArgumentException("$toolName seconds must be at least 1.")
  }
  val label = arguments.optionalString("label")?.trim()?.takeIf(String::isNotBlank)
  val actionSummary = "${seconds}s ${label ?: "(no label)"}"
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.START_TIMER,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can start this device timer.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.startTimer(
      SystemTimerRequest(
        durationSeconds = seconds,
        label = label,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success && result.available) "Device timer started." else "Device timer was not started.")
      append("duration_seconds=$seconds")
      label?.let { appendLine(); append("label=$it") }
      appendLine()
      append(result.summary)
    },
    success = result.success && result.available,
    errorCode = if (!result.available) "SYSTEM_ABILITY_UNAVAILABLE" else null,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "timer.start",
    ),
  )
}

internal fun OpenCrayToolDispatcher.postSystemNotification(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_notification_post"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val title = arguments.requiredString("title").trim().takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$toolName requires a non-blank title.")
  val body = arguments.requiredString("body").trim().takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$toolName requires a non-blank body.")
  val actionSummary = title
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.POST_NOTIFICATION,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can post this notification.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.postNotification(
      SystemNotificationRequest(
        title = title,
        body = body,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success) "Notification posted." else "Notification was not posted.")
      append("title=$title")
      appendLine()
      append(result.summary)
    },
    success = result.success,
    errorCode = if (result.permissionRequired) "SYSTEM_PERMISSION_REQUIRED" else null,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "notification.post",
    ) + if (result.permissionRequired) {
      mapOf(
        SystemAbilityToolMetadataKeys.MISSING_PERMISSIONS to "android.permission.POST_NOTIFICATIONS",
      )
    } else {
      emptyMap()
    },
  )
}

internal fun OpenCrayToolDispatcher.listSystemApps(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_app_list"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val query = arguments.optionalString("query")?.trim()?.takeIf(String::isNotBlank)
  val limit = (arguments.optionalInt("limit") ?: 50).coerceIn(1, config.maxDirectoryEntries)
  val actionSummary = query ?: "all apps"
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.LIST_APPS,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can read the installed app list.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.listApps(
      SystemAppListRequest(
        query = query,
        limit = limit,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  val appLines = result.apps.joinToString("\n") { entry ->
    "${entry.packageName}=${entry.label}"
  }
  return AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = buildString {
      appendLine("Installed apps (${result.apps.size}${if (result.truncated) ", truncated" else ""}):")
      append(appLines)
    },
    metadata = toolPolicyPipeline.resultMetadata(
      plan = plan,
      metadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "apps.list",
        SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
        SystemAbilityToolMetadataKeys.APP_COUNT to result.apps.size.toString(),
        SystemAbilityToolMetadataKeys.APP_TRUNCATED to result.truncated.toString(),
      ),
      resultEnvelope = if (result.truncated) {
        ToolResultEnvelope(
          limitApplied = true,
          truncated = true,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        )
      } else {
        null
      },
    ),
  )
}

internal fun OpenCrayToolDispatcher.openSystemApp(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_app_open"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val packageName = arguments.optionalStringFrom("package", "packageName")
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$toolName requires a package name.")
  val actionSummary = packageName
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.OPEN_APP,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can launch this app.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.openApp(
      SystemAppOpenRequest(
        packageName = packageName,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success && result.available) "App launched." else "App was not launched.")
      append("package=$packageName")
      result.label?.let { appendLine(); append("label=$it") }
    },
    success = result.success && result.available,
    errorCode = if (!result.available) "SYSTEM_ABILITY_UNAVAILABLE" else null,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "app.open",
    ),
  )
}

internal fun OpenCrayToolDispatcher.openSystemSettingsPage(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_settings_open"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val page = arguments.requiredString("page").trim().lowercase()
  if (page !in supportedSystemSettingsPages) {
    throw IllegalArgumentException(
      "$toolName page must be one of: ${supportedSystemSettingsPages.joinToString(", ")}.",
    )
  }
  val actionSummary = page
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.OPEN_SETTINGS,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can open this system settings page.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.openSettings(
      SystemSettingsOpenRequest(
        page = page,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success) "System settings page opened." else "System settings page was not opened.")
      append("page=${result.page}")
      appendLine()
      append(result.summary)
    },
    success = result.success,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "settings.open",
    ),
  )
}

internal fun OpenCrayToolDispatcher.listUpcomingSystemCalendarEvents(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_calendar_upcoming"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val days = (arguments.optionalInt("days") ?: 7).coerceIn(1, 31)
  val limit = (arguments.optionalInt("limit") ?: 20).coerceIn(1, config.maxDirectoryEntries)
  val actionSummary = "next $days days"
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.LIST_CALENDAR_EVENTS,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can read the device calendar.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.listUpcomingCalendarEvents(
      SystemCalendarUpcomingRequest(
        days = days,
        limit = limit,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  if (result.permissionRequired) {
    return systemPermissionRequiredOutcome(
      toolName = toolName,
      plan = plan,
      actionSummary = actionSummary,
      summary = result.summary,
      permissions = "android.permission.READ_CALENDAR",
    )
  }
  if (!result.success) {
    return systemAbilityOutcome(
      toolName = toolName,
      plan = plan,
      actionSummary = actionSummary,
      content = buildString {
        appendLine("Upcoming calendar events were not read.")
        append(result.summary)
      },
      success = false,
      errorCode = "SYSTEM_ABILITY_UNAVAILABLE",
      extraMetadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "calendar.list",
      ),
    )
  }
  val eventLines = result.events.joinToString("\n") { event ->
    buildString {
      append(event.eventId)
      append(" | ")
      append(formatSystemCalendarEventTime(event.beginEpochMillis))
      append(" | ")
      append(event.title)
      event.calendarName?.let { append(" | calendar=$it") }
      event.location?.takeIf(String::isNotBlank)?.let { append(" | location=$it") }
    }
  }
  return AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = buildString {
      appendLine("Upcoming calendar events (${result.events.size}${if (result.truncated) ", truncated" else ""}):")
      append(eventLines)
    },
    metadata = toolPolicyPipeline.resultMetadata(
      plan = plan,
      metadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "calendar.list",
        SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
        SystemAbilityToolMetadataKeys.EVENT_COUNT to result.events.size.toString(),
      ),
      resultEnvelope = if (result.truncated) {
        ToolResultEnvelope(
          limitApplied = true,
          truncated = true,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        )
      } else {
        null
      },
    ),
  )
}

internal fun OpenCrayToolDispatcher.createSystemCalendarEvent(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_calendar_create"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val title = arguments.requiredString("title").trim().takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$toolName requires a non-blank title.")
  val begin = arguments.requiredLong("begin")
  val durationMinutes = arguments.requiredInt("duration_minutes")
  if (durationMinutes < 1) {
    throw IllegalArgumentException("$toolName duration_minutes must be at least 1.")
  }
  val location = arguments.optionalString("location")?.trim()?.takeIf(String::isNotBlank)
  val description = arguments.optionalString("description")?.trim()?.takeIf(String::isNotBlank)
  val actionSummary = "$title at ${formatSystemCalendarEventTime(begin)}"
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.CREATE_CALENDAR_EVENT,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can create this calendar event.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.createCalendarEvent(
      SystemCalendarEventCreateRequest(
        title = title,
        beginEpochMillis = begin,
        durationMinutes = durationMinutes,
        location = location,
        description = description,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  if (result.permissionRequired) {
    return systemPermissionRequiredOutcome(
      toolName = toolName,
      plan = plan,
      actionSummary = actionSummary,
      summary = result.summary,
      permissions = "android.permission.WRITE_CALENDAR",
    )
  }
  return systemAbilityOutcome(
    toolName = toolName,
    plan = plan,
    actionSummary = actionSummary,
    content = buildString {
      appendLine(if (result.success) "Calendar event created." else "Calendar event was not created.")
      append("title=$title")
      appendLine()
      append("begin=${formatSystemCalendarEventTime(begin)}")
      appendLine()
      append("duration_minutes=$durationMinutes")
      result.eventId?.let { appendLine(); append("event_id=$it") }
      appendLine()
      append(result.summary)
    },
    success = result.success,
    errorCode = if (!result.success) "SYSTEM_ACTION_FAILED" else null,
    extraMetadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "calendar.create",
    ),
  )
}

internal fun OpenCrayToolDispatcher.searchSystemContacts(
  task: AgentTask,
  arguments: JsonObject,
): AgentToolResult {
  val toolName = "system_contact_search"
  val gateway = config.systemAbilityGateway
    ?: return unavailableSystemAbilityGateway(toolName)
  val query = arguments.requiredString("query").trim().takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("$toolName requires a non-blank query.")
  val limit = (arguments.optionalInt("limit") ?: 20).coerceIn(1, config.maxDirectoryEntries)
  val actionSummary = query
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = actionSummary,
    ),
    intent = SystemAbilityIntent(
      kind = SystemAbilityIntentKind.SEARCH_CONTACTS,
      actionSummary = actionSummary,
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before $toolName can read device contacts.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val result = runCatching {
    gateway.searchContacts(
      SystemContactSearchRequest(
        query = query,
        limit = limit,
      ),
    )
  }.getOrElse { throwable ->
    return systemAbilityFailure(toolName, plan, actionSummary, throwable.message)
  }
  if (result.permissionRequired) {
    return systemPermissionRequiredOutcome(
      toolName = toolName,
      plan = plan,
      actionSummary = actionSummary,
      summary = result.summary,
      permissions = "android.permission.READ_CONTACTS",
    )
  }
  if (!result.success) {
    return systemAbilityOutcome(
      toolName = toolName,
      plan = plan,
      actionSummary = actionSummary,
      content = buildString {
        appendLine("Contacts were not read.")
        append(result.summary)
      },
      success = false,
      errorCode = "SYSTEM_ABILITY_UNAVAILABLE",
      extraMetadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "contacts.search",
      ),
    )
  }
  val contactLines = result.contacts.joinToString("\n") { contact ->
    buildString {
      append(contact.contactId)
      append(" | ")
      append(contact.displayName)
      if (contact.phoneNumbers.isNotEmpty()) {
        append(" | tel=")
        append(contact.phoneNumbers.joinToString(","))
      }
      if (contact.emails.isNotEmpty()) {
        append(" | email=")
        append(contact.emails.joinToString(","))
      }
      contact.organization?.takeIf(String::isNotBlank)?.let { append(" | org=$it") }
    }
  }
  return AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = buildString {
      appendLine("Matching contacts (${result.contacts.size}${if (result.truncated) ", truncated" else ""}):")
      append(contactLines)
    },
    metadata = toolPolicyPipeline.resultMetadata(
      plan = plan,
      metadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ABILITY_ACTION to "contacts.search",
        SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
        SystemAbilityToolMetadataKeys.CONTACT_COUNT to result.contacts.size.toString(),
      ),
      resultEnvelope = if (result.truncated) {
        ToolResultEnvelope(
          limitApplied = true,
          truncated = true,
          limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
        )
      } else {
        null
      },
    ),
  )
}

internal fun formatSystemCalendarEventTime(epochMillis: Long): String =
  java.time.Instant.ofEpochMilli(epochMillis).toString()

private fun OpenCrayToolDispatcher.systemPermissionRequiredOutcome(
  toolName: String,
  plan: ToolPolicyPlan,
  actionSummary: String,
  summary: String,
  permissions: String,
): AgentToolResult = AgentToolResult(
  toolName = toolName,
  status = AgentToolResultStatus.FAILED,
  content = buildString {
    appendLine("This system ability needs a runtime permission that is not granted yet.")
    appendLine("permission=$permissions")
    append(summary)
  },
  errorCode = "SYSTEM_PERMISSION_REQUIRED",
  metadata = toolPolicyPipeline.resultMetadata(
    plan = plan,
    metadata = mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
      SystemAbilityToolMetadataKeys.MISSING_PERMISSIONS to permissions,
    ),
  ),
)

private fun OpenCrayToolDispatcher.unavailableSystemAbilityGateway(
  toolName: String,
): AgentToolResult = AgentToolResult(
  toolName = toolName,
  status = AgentToolResultStatus.FAILED,
  content = SYSTEM_ABILITY_GATEWAY_UNAVAILABLE_CONTENT,
  errorCode = "SYSTEM_ABILITY_UNAVAILABLE",
  metadata = toolPolicyPipeline.resultMetadata(
    toolName = toolName,
    request = ToolMetadataContextRequest(
      workspaceRelation = ToolWorkspaceRelation.NONE,
    ),
  ),
)

private fun OpenCrayToolDispatcher.systemAbilityFailure(
  toolName: String,
  plan: ToolPolicyPlan,
  actionSummary: String,
  detail: String?,
): AgentToolResult {
  val message = detail?.trim()?.takeIf(String::isNotBlank)
    ?: "The system ability action failed."
  return AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "SYSTEM_ACTION_FAILED",
    errorMessage = message,
    metadata = toolPolicyPipeline.resultMetadata(
      plan = plan,
      metadata = mapOf(
        SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
      ),
    ),
  )
}

private fun OpenCrayToolDispatcher.systemAbilityOutcome(
  toolName: String,
  plan: ToolPolicyPlan,
  actionSummary: String,
  content: String,
  success: Boolean,
  errorCode: String? = null,
  extraMetadata: Map<String, String> = emptyMap(),
): AgentToolResult = AgentToolResult(
  toolName = toolName,
  status = if (success) AgentToolResultStatus.SUCCESS else AgentToolResultStatus.FAILED,
  content = content,
  errorCode = errorCode,
  metadata = toolPolicyPipeline.resultMetadata(
    plan = plan,
    metadata = extraMetadata + mapOf(
      SystemAbilityToolMetadataKeys.SYSTEM_ACTION_SUMMARY to actionSummary,
    ),
  ),
)
