package com.opencray.app

import com.opencray.app.facade.settings.SettingsRouteId
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
  @Test
  fun flutterShellBootstrapKeepsRootRoute() {
    assertEquals("/", OpenCrayFlutterActivity.Destination.SHELL.route)
  }

  @Test
  fun flutterSettingsRoutesIncludeNotificationsBackground() {
    assertEquals(
      "/settings/notifications-background",
      OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATIONS_BACKGROUND.route,
    )
    assertEquals(
      "/settings/event-alerts",
      OpenCrayFlutterActivity.Destination.SETTINGS_EVENT_ALERTS.route,
    )
    assertEquals(
      "/settings/scheduled-tasks",
      OpenCrayFlutterActivity.Destination.SETTINGS_SCHEDULED_TASKS.route,
    )
  }

  @Test
  fun scheduledTaskRouteEncodesScheduleIdAsQueryParameter() {
    assertEquals(
      "/settings/scheduled-tasks?scheduleId=schedule%2F1%20review",
      openCrayFlutterDestinationRoute(
        OpenCrayFlutterActivity.Destination.SETTINGS_SCHEDULED_TASKS,
        " schedule/1 review ",
      ),
    )
    assertEquals(
      "/settings/notifications-background",
      openCrayFlutterDestinationRoute(
        OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATIONS_BACKGROUND,
        "schedule-1",
      ),
    )
  }

  @Test
  fun legacyNotificationChannelsRouteIdResolvesToEventAlerts() {
    assertEquals(
      SettingsRouteId.EVENT_ALERTS,
      SettingsRouteId.fromWireValue("notification_channels"),
    )
  }
}
