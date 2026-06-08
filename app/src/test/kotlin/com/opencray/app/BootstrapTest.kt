package com.opencray.app

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
      "/settings/notification-channels",
      OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATION_CHANNELS.route,
    )
  }
}
