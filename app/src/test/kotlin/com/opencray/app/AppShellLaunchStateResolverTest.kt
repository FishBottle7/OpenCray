package com.opencray.app

import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppShellLaunchStateResolverTest {
  @Test
  fun restoredStateWinsOverIntentExtrasAndPersistedDestination() {
    val resolved = AppShellLaunchStateResolver.resolve(
      restoredTabRaw = AppShellTab.FILES.name,
      restoredSettingsSubpageRaw = SettingsSubpage.ABOUT.name,
      hasRestoredState = true,
      startTabRaw = AppShellTab.SETTINGS.name,
      startSettingsSubpageRaw = SettingsSubpage.MCP.name,
      hasStartExtras = true,
      persistedDestination = AppShellDestination.default(),
    )

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.FILES,
        settingsSubpage = SettingsSubpage.ABOUT,
      ),
      resolved,
    )
  }

  @Test
  fun invalidExplicitStartTabFallsBackToChatInsteadOfPersistedDestination() {
    val resolved = AppShellLaunchStateResolver.resolve(
      restoredTabRaw = null,
      restoredSettingsSubpageRaw = null,
      hasRestoredState = false,
      startTabRaw = "not-a-real-tab",
      startSettingsSubpageRaw = SettingsSubpage.PRIVACY.name,
      hasStartExtras = true,
      persistedDestination = AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.PERSONALIZATION,
      ),
    )

    assertEquals(AppShellDestination.default(), resolved)
  }

  @Test
  fun explicitStartSettingsPageCanOpenNotificationsBackground() {
    val resolved = AppShellLaunchStateResolver.resolve(
      restoredTabRaw = null,
      restoredSettingsSubpageRaw = null,
      hasRestoredState = false,
      startTabRaw = AppShellTab.SETTINGS.routeKey,
      startSettingsSubpageRaw = SettingsSubpage.NOTIFICATIONS_BACKGROUND.routeKey,
      hasStartExtras = true,
      persistedDestination = AppShellDestination.default(),
    )

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.NOTIFICATIONS_BACKGROUND,
      ),
      resolved,
    )
    assertEquals(
      OpenCrayFlutterActivity.Destination.SETTINGS_NOTIFICATIONS_BACKGROUND,
      appShellFlutterDestination(resolved),
    )
  }
}
