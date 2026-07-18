package com.opencray.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class AppShellInvalidDestinationTest {
  @Test
  fun invalidTabFallsBackToDefaultDestination() {
    val stateStore = AppShellStateStore(
      InMemoryAppShellKeyValueStore(
        initialValues = mapOf(
          AppShellStateStoreKeys.SELECTED_TAB to "unknown-tab",
          AppShellStateStoreKeys.SETTINGS_SUBPAGE to SettingsSubpage.MCP.name,
        ),
      ),
    )

    assertEquals(AppShellDestination.default(), stateStore.load())
  }

  @Test
  fun invalidSettingsSubpageFallsBackToSettingsHome() {
    val stateStore = AppShellStateStore(
      InMemoryAppShellKeyValueStore(
        initialValues = mapOf(
          AppShellStateStoreKeys.SELECTED_TAB to AppShellTab.SETTINGS.name,
          AppShellStateStoreKeys.SETTINGS_SUBPAGE to "unknown-subpage",
        ),
      ),
    )

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.HOME,
      ),
      stateStore.load(),
    )
  }

  @Test
  fun legacyNotificationChannelsSubpageRestoresEventAlerts() {
    val stateStore = AppShellStateStore(
      InMemoryAppShellKeyValueStore(
        initialValues = mapOf(
          AppShellStateStoreKeys.SELECTED_TAB to AppShellTab.SETTINGS.name,
          AppShellStateStoreKeys.SETTINGS_SUBPAGE to "notification_channels",
        ),
      ),
    )

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.EVENT_ALERTS,
      ),
      stateStore.load(),
    )
  }
}
