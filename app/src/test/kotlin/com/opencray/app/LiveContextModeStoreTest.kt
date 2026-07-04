package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveContextModeStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadDefaultsToFullMode() {
    val store = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore())

    assertEquals(LiveContextMode.FULL, store.load())
  }

  @Test
  fun saveAndLoadRoundTripsLiveContextMode() {
    val store = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore())

    store.save(LiveContextMode.NO_MEMORY_OR_SOUL)

    assertEquals(LiveContextMode.NO_MEMORY_OR_SOUL, store.load())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("live-context-mode-file-backed")
    val firstStore = LiveContextModeStore(
      FileBackedLiveContextModeKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )

    firstStore.save(LiveContextMode.NO_SOUL)

    val secondStore = LiveContextModeStore(
      FileBackedLiveContextModeKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(LiveContextMode.NO_SOUL, secondStore.load())

    secondStore.clear()

    assertEquals(LiveContextMode.FULL, firstStore.load())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("live-context-mode-migration")
    val legacyKeyValueStore = InMemoryLiveContextModeKeyValueStore()
    val legacyStore = LiveContextModeStore(legacyKeyValueStore)
    legacyStore.save(LiveContextMode.LIGHTWEIGHT)
    val fileBackedKeyValueStore = FileBackedLiveContextModeKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = LiveContextModeStore(fileBackedKeyValueStore)
    assertEquals(LiveContextMode.LIGHTWEIGHT, fileBackedStore.load())

    fileBackedStore.save(LiveContextMode.NO_MEMORY_OR_SOUL)
    legacyStore.save(LiveContextMode.FULL)

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(LiveContextMode.NO_MEMORY_OR_SOUL, fileBackedStore.load())
  }
}
