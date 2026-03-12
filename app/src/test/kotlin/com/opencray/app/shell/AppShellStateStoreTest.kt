package com.opencray.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class AppShellStateStoreTest {
  @Test
  fun persistsSelectedSettingsDestinationAcrossReload() {
    val keyValueStore = InMemoryAppShellKeyValueStore()
    val stateStore = AppShellStateStore(keyValueStore)
    val destination = AppShellDestination(
      selectedTab = AppShellTab.SETTINGS,
      settingsSubpage = SettingsSubpage.PERSONALIZATION,
    )

    stateStore.save(destination)

    val restoredStateStore = AppShellStateStore(keyValueStore)
    assertEquals(destination, restoredStateStore.load())
  }

  @Test
  fun preservesLastSettingsSubpageWhenTopLevelTabChanges() {
    val keyValueStore = InMemoryAppShellKeyValueStore()
    val stateStore = AppShellStateStore(keyValueStore)

    stateStore.save(
      AppShellDestination(
        selectedTab = AppShellTab.CHAT,
        settingsSubpage = SettingsSubpage.PRIVACY,
      ),
    )

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.CHAT,
        settingsSubpage = SettingsSubpage.PRIVACY,
      ),
      stateStore.load(),
    )
  }
}
