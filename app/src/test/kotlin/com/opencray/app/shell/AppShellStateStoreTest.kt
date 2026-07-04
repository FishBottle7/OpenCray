package com.opencray.app.shell

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppShellStateStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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

  @Test
  fun fileBackedStorePersistsSelectedSettingsDestinationAcrossReload() {
    val runtimeRoot = temporaryFolder.newFolder("app-shell-state")
    val keyValueStore = FileBackedAppShellKeyValueStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )
    val stateStore = AppShellStateStore(keyValueStore)
    val destination = AppShellDestination(
      selectedTab = AppShellTab.SETTINGS,
      settingsSubpage = SettingsSubpage.NOTIFICATIONS_BACKGROUND,
    )

    stateStore.save(destination)

    val restoredStateStore = AppShellStateStore(
      FileBackedAppShellKeyValueStore(
        storage = DirectoryDurableTextStorage(runtimeRoot),
        clock = { 2_000L },
      ),
    )
    assertEquals(destination, restoredStateStore.load())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateWhenDurableRecordIsEmpty() {
    val runtimeRoot = temporaryFolder.newFolder("app-shell-state-migration")
    val legacyStore = InMemoryAppShellKeyValueStore(
      mapOf(
        AppShellStateStoreKeys.SELECTED_TAB to AppShellTab.SETTINGS.name,
        AppShellStateStoreKeys.SETTINGS_SUBPAGE to SettingsSubpage.MCP.name,
      ),
    )
    val fileBackedStore = FileBackedAppShellKeyValueStore(
      storage = DirectoryDurableTextStorage(runtimeRoot),
      clock = { 1_000L },
    )

    fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)

    assertEquals(
      AppShellDestination(
        selectedTab = AppShellTab.SETTINGS,
        settingsSubpage = SettingsSubpage.MCP,
      ),
      AppShellStateStore(fileBackedStore).load(),
    )
  }
}
