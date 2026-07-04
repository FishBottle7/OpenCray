package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpSettingsStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadDefaultsMasterEnabled() {
    val store = McpSettingsStore(InMemoryMcpSettingsKeyValueStore())

    assertTrue(store.loadMasterEnabled())
  }

  @Test
  fun saveAndLoadMasterEnabled() {
    val store = McpSettingsStore(InMemoryMcpSettingsKeyValueStore())

    store.saveMasterEnabled(false)

    assertFalse(store.loadMasterEnabled())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("mcp-settings-file-backed")
    val firstStore = McpSettingsStore(
      FileBackedMcpSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )

    firstStore.saveMasterEnabled(false)

    val secondStore = McpSettingsStore(
      FileBackedMcpSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertFalse(secondStore.loadMasterEnabled())

    secondStore.clear()

    assertTrue(firstStore.loadMasterEnabled())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("mcp-settings-migration")
    val legacyKeyValueStore = InMemoryMcpSettingsKeyValueStore()
    val legacyStore = McpSettingsStore(legacyKeyValueStore)
    legacyStore.saveMasterEnabled(false)
    val fileBackedKeyValueStore = FileBackedMcpSettingsKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = McpSettingsStore(fileBackedKeyValueStore)
    assertFalse(fileBackedStore.loadMasterEnabled())

    fileBackedStore.saveMasterEnabled(true)
    legacyStore.saveMasterEnabled(false)

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertTrue(fileBackedStore.loadMasterEnabled())
  }
}
