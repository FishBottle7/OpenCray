package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherSystemAbilityToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun systemToolsAreRegisteredOnlyWhenGatewayIsAvailable() {
    val withGateway = dispatcher(SystemAbilityGatewayFake())
    val withGatewayNames = withGateway.toolDefinitions().map { it.name }
    assertTrue(withGatewayNames.contains("system_alarm_create"))
    assertTrue(withGatewayNames.contains("system_timer_start"))
    assertTrue(withGatewayNames.contains("system_notification_post"))
    assertTrue(withGatewayNames.contains("system_app_list"))
    assertTrue(withGatewayNames.contains("system_app_open"))
    assertTrue(withGatewayNames.contains("system_settings_open"))
    assertTrue(withGatewayNames.contains("system_calendar_upcoming"))
    assertTrue(withGatewayNames.contains("system_calendar_create"))
    assertTrue(withGatewayNames.contains("system_contact_search"))

    val withoutGateway = dispatcher(gateway = null)
    val withoutGatewayNames = withoutGateway.toolDefinitions().map { it.name }
    assertFalse(withoutGatewayNames.any { it.startsWith("system_") })
  }

  @Test
  fun systemAlarmCreateRequiresApprovalInSafeModeBeforeAction() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "system_alarm_create",
        arguments = buildJsonObject {
          put("hour", 7)
          put("minute", 0)
          put("label", "Morning run")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_SYSTEM_ACTION", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals("system_ability", result.metadata["capabilityKind"])
    assertEquals("system_ability", result.metadata["intentCategory"])
    assertEquals("create_alarm", result.metadata["systemAbilityIntentKind"])
    assertNull(gateway.lastAlarmRequest)
  }

  @Test
  fun systemAlarmCreateRunsInAutoModeAndEmitsActionMetadata() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_alarm_create",
        arguments = buildJsonObject {
          put("hour", 7)
          put("minute", 30)
          put("label", "Morning run")
          putJsonArray("days") {
            add(1)
            add(3)
          }
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("alarm.set", result.metadata["systemAction"])
    assertEquals("07:30 Morning run", result.metadata["systemActionSummary"])
    assertEquals("ALLOW_AUTO_STANDARD", result.metadata["policyReasonCode"])
    val request = requireNotNull(gateway.lastAlarmRequest)
    assertEquals(7, request.hour)
    assertEquals(30, request.minute)
    assertEquals("Morning run", request.label)
    assertEquals(listOf(1, 3), request.daysOfWeek)
    assertTrue(result.content.contains("time=07:30"))
    assertTrue(result.content.contains("repeat=weekly 1,3"))
  }

  @Test
  fun systemAppListIsAllowedInSafeModeWithoutApproval() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "system_app_list",
        arguments = buildJsonObject {
          put("query", "clock")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertNull(result.errorCode)
    assertEquals("apps.list", result.metadata["systemAction"])
    assertEquals("clock", result.metadata["systemActionSummary"])
    assertEquals("ALLOW_SAFE_READ", result.metadata["policyReasonCode"])
    assertTrue(result.content.contains("com.example.clock=Clock"))
  }

  @Test
  fun systemNotificationPostMapsPermissionMissingToDedicatedErrorCode() {
    val gateway = SystemAbilityGatewayFake(notificationPermissionRequired = true)
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "DEVELOPER"),
      call = AgentToolCall(
        toolName = "system_notification_post",
        arguments = buildJsonObject {
          put("title", "Report ready")
          put("body", "The weekly report has been generated.")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SYSTEM_PERMISSION_REQUIRED", result.errorCode)
    assertEquals(
      "android.permission.POST_NOTIFICATIONS",
      result.metadata["missingPermissions"],
    )
    assertEquals("notification.post", result.metadata["systemAction"])
  }

  @Test
  fun systemAppOpenMapsMissingAppToUnavailableErrorCode() {
    val gateway = SystemAbilityGatewayFake(openAppAvailable = false)
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_app_open",
        arguments = buildJsonObject {
          put("package", "com.example.missing")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SYSTEM_ABILITY_UNAVAILABLE", result.errorCode)
    assertEquals("app.open", result.metadata["systemAction"])
  }

  @Test
  fun systemSettingsOpenRejectsUnknownPageBeforePolicyEvaluation() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_settings_open",
        arguments = buildJsonObject {
          put("page", "developer_options")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("TOOL_EXECUTION_FAILED", result.errorCode)
    assertTrue(result.content.contains("page must be one of"))
    assertNull(gateway.lastSettingsRequest)
  }

  @Test
  fun systemSettingsOpenRunsForWhitelistedPage() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_settings_open",
        arguments = buildJsonObject {
          put("page", "exact_alarms")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("settings.open", result.metadata["systemAction"])
    assertEquals("exact_alarms", gateway.lastSettingsRequest?.page)
  }

  @Test
  fun systemAlarmCreateFailsWhenGatewayIsNotInjected() {
    val dispatcher = dispatcher(gateway = null)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_alarm_create",
        arguments = buildJsonObject {
          put("hour", 7)
          put("minute", 0)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SYSTEM_ABILITY_UNAVAILABLE", result.errorCode)
  }

  @Test
  fun systemCalendarUpcomingIsAllowedInSafeModeAndFormatsEvents() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "system_calendar_upcoming",
        arguments = buildJsonObject {
          put("days", 7)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertNull(result.errorCode)
    assertEquals("calendar.list", result.metadata["systemAction"])
    assertEquals("ALLOW_SAFE_READ", result.metadata["policyReasonCode"])
    assertEquals("2", result.metadata["eventCount"])
    assertTrue(result.content.contains("Team sync"))
    assertEquals(7, gateway.lastCalendarUpcomingRequest?.days)
  }

  @Test
  fun systemCalendarUpcomingMapsMissingPermissionToDedicatedErrorCode() {
    val gateway = SystemAbilityGatewayFake(calendarPermissionRequired = true)
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "DEVELOPER"),
      call = AgentToolCall(
        toolName = "system_calendar_upcoming",
        arguments = buildJsonObject {},
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SYSTEM_PERMISSION_REQUIRED", result.errorCode)
    assertEquals(
      "android.permission.READ_CALENDAR",
      result.metadata["missingPermissions"],
    )
    assertTrue(result.content.contains("android.permission.READ_CALENDAR"))
  }

  @Test
  fun systemCalendarCreateRequiresApprovalInSafeModeBeforeAction() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "system_calendar_create",
        arguments = buildJsonObject {
          put("title", "Dentist")
          put("begin", 1_767_357_600_000)
          put("duration_minutes", 30)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_SYSTEM_ACTION", result.metadata["policyReasonCode"])
    assertEquals("create_calendar_event", result.metadata["systemAbilityIntentKind"])
    assertNull(gateway.lastCalendarCreateRequest)
  }

  @Test
  fun systemCalendarCreateRunsInAutoModeAndEmitsEventMetadata() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_calendar_create",
        arguments = buildJsonObject {
          put("title", "Dentist")
          put("begin", 1_767_357_600_000)
          put("duration_minutes", 30)
          put("location", "Clinic")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("calendar.create", result.metadata["systemAction"])
    assertEquals("ALLOW_AUTO_STANDARD", result.metadata["policyReasonCode"])
    val request = requireNotNull(gateway.lastCalendarCreateRequest)
    assertEquals("Dentist", request.title)
    assertEquals(1_767_357_600_000, request.beginEpochMillis)
    assertEquals(30, request.durationMinutes)
    assertEquals("Clinic", request.location)
    assertTrue(result.content.contains("event_id=101"))
    assertTrue(result.content.contains("duration_minutes=30"))
  }

  @Test
  fun systemCalendarCreateRejectsNonPositiveDurationBeforePolicyEvaluation() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "system_calendar_create",
        arguments = buildJsonObject {
          put("title", "Dentist")
          put("begin", 1_767_357_600_000)
          put("duration_minutes", 0)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("TOOL_EXECUTION_FAILED", result.errorCode)
    assertTrue(result.content.contains("duration_minutes must be at least 1"))
    assertNull(gateway.lastCalendarCreateRequest)
  }

  @Test
  fun systemContactSearchIsAllowedInSafeModeAndFormatsContacts() {
    val gateway = SystemAbilityGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "system_contact_search",
        arguments = buildJsonObject {
          put("query", "Ann")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertNull(result.errorCode)
    assertEquals("contacts.search", result.metadata["systemAction"])
    assertEquals("ALLOW_SAFE_READ", result.metadata["policyReasonCode"])
    assertEquals("1", result.metadata["contactCount"])
    assertTrue(result.content.contains("Ann Chen"))
    assertTrue(result.content.contains("tel=13800001111"))
    assertEquals("Ann", gateway.lastContactSearchRequest?.query)
  }

  @Test
  fun systemContactSearchMapsMissingPermissionToDedicatedErrorCode() {
    val gateway = SystemAbilityGatewayFake(contactPermissionRequired = true)
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "DEVELOPER"),
      call = AgentToolCall(
        toolName = "system_contact_search",
        arguments = buildJsonObject {
          put("query", "Ann")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("SYSTEM_PERMISSION_REQUIRED", result.errorCode)
    assertEquals(
      "android.permission.READ_CONTACTS",
      result.metadata["missingPermissions"],
    )
    assertEquals("search_contacts", result.metadata["systemAbilityIntentKind"])
  }

  private fun dispatcher(
    gateway: SystemAbilityGateway?,
  ): OpenCrayToolDispatcher {
    dispatcherFolderCounter += 1
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(
          temporaryFolder.newFolder("system-ability-workspace-$dispatcherFolderCounter").toPath(),
        ),
        systemAbilityGateway = gateway,
      ),
    )
  }

  private var dispatcherFolderCounter: Int = 0

  private fun task(
    mode: String,
  ): AgentTask = AgentTask(
    id = "task-system-ability",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = mapOf("chatMode" to mode),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in system ability tool test.") },
  )

  private class SystemAbilityGatewayFake(
    private val notificationPermissionRequired: Boolean = false,
    private val openAppAvailable: Boolean = true,
    private val calendarPermissionRequired: Boolean = false,
    private val contactPermissionRequired: Boolean = false,
  ) : SystemAbilityGateway {
    var lastAlarmRequest: SystemAlarmCreateRequest? = null
      private set
    var lastSettingsRequest: SystemSettingsOpenRequest? = null
      private set
    var lastCalendarUpcomingRequest: SystemCalendarUpcomingRequest? = null
      private set
    var lastCalendarCreateRequest: SystemCalendarEventCreateRequest? = null
      private set
    var lastContactSearchRequest: SystemContactSearchRequest? = null
      private set

    override fun createAlarm(request: SystemAlarmCreateRequest): SystemAlarmResult {
      lastAlarmRequest = request
      return SystemAlarmResult(
        success = true,
        summary = "Alarm accepted by the fake clock app.",
      )
    }

    override fun startTimer(request: SystemTimerRequest): SystemTimerResult =
      SystemTimerResult(
        success = true,
        summary = "Timer accepted by the fake clock app.",
      )

    override fun postNotification(
      request: SystemNotificationRequest,
    ): SystemNotificationResult =
      if (notificationPermissionRequired) {
        SystemNotificationResult(
          success = false,
          permissionRequired = true,
          summary = "Notifications are disabled.",
        )
      } else {
        SystemNotificationResult(
          success = true,
          summary = "Notification posted.",
        )
      }

    override fun listApps(request: SystemAppListRequest): SystemAppListResult =
      SystemAppListResult(
        success = true,
        apps = listOf(
          SystemAppEntry(packageName = "com.example.clock", label = "Clock"),
          SystemAppEntry(packageName = "com.example.notes", label = "Notes"),
        ),
        truncated = false,
      )

    override fun openApp(request: SystemAppOpenRequest): SystemAppOpenResult =
      SystemAppOpenResult(
        success = openAppAvailable,
        available = openAppAvailable,
        label = "Clock",
      )

    override fun openSettings(
      request: SystemSettingsOpenRequest,
    ): SystemSettingsOpenResult {
      lastSettingsRequest = request
      return SystemSettingsOpenResult(
        success = true,
        page = request.page,
        summary = "Opened.",
      )
    }

    override fun listUpcomingCalendarEvents(
      request: SystemCalendarUpcomingRequest,
    ): SystemCalendarEventListResult {
      lastCalendarUpcomingRequest = request
      return if (calendarPermissionRequired) {
        SystemCalendarEventListResult(
          success = false,
          permissionRequired = true,
          summary = "Calendar read access is not granted.",
        )
      } else {
        SystemCalendarEventListResult(
          success = true,
          events = listOf(
            SystemCalendarEventEntry(
              eventId = 201,
              title = "Team sync",
              beginEpochMillis = 1_767_357_600_000,
              endEpochMillis = 1_767_357_780_000,
              allDay = false,
              calendarName = "Work",
            ),
            SystemCalendarEventEntry(
              eventId = 202,
              title = "Gym",
              beginEpochMillis = 1_767_366_240_000,
              endEpochMillis = 1_767_366_420_000,
              allDay = false,
              calendarName = "Personal",
              location = "Downtown gym",
            ),
          ),
          truncated = false,
          summary = "Read 2 upcoming calendar events.",
        )
      }
    }

    override fun createCalendarEvent(
      request: SystemCalendarEventCreateRequest,
    ): SystemCalendarEventResult {
      lastCalendarCreateRequest = request
      return if (calendarPermissionRequired) {
        SystemCalendarEventResult(
          success = false,
          permissionRequired = true,
          summary = "Calendar write access is not granted.",
        )
      } else {
        SystemCalendarEventResult(
          success = true,
          eventId = 101,
          summary = "Event created in the fake calendar.",
        )
      }
    }

    override fun searchContacts(
      request: SystemContactSearchRequest,
    ): SystemContactListResult {
      lastContactSearchRequest = request
      return if (contactPermissionRequired) {
        SystemContactListResult(
          success = false,
          permissionRequired = true,
          summary = "Contacts read access is not granted.",
        )
      } else {
        SystemContactListResult(
          success = true,
          contacts = listOf(
            SystemContactEntry(
              contactId = 11,
              displayName = "Ann Chen",
              phoneNumbers = listOf("13800001111"),
              emails = listOf("ann@example.com"),
            ),
          ),
          truncated = false,
          summary = "Found 1 contact.",
        )
      }
    }
  }
}
