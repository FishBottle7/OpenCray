package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocaleSettingsStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadDefaultsToEnglish() {
    val store = LocaleSettingsStore(InMemoryLocaleSettingsKeyValueStore())

    assertEquals(AppLanguage.ENGLISH, store.loadLanguage())
  }

  @Test
  fun saveAndLoadLanguage() {
    val store = LocaleSettingsStore(InMemoryLocaleSettingsKeyValueStore())

    store.saveLanguage(AppLanguage.SIMPLIFIED_CHINESE)

    assertEquals(AppLanguage.SIMPLIFIED_CHINESE, store.loadLanguage())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("locale-settings-file-backed")
    val firstStore = LocaleSettingsStore(
      FileBackedLocaleSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )

    firstStore.saveLanguage(AppLanguage.SIMPLIFIED_CHINESE)

    val secondStore = LocaleSettingsStore(
      FileBackedLocaleSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(AppLanguage.SIMPLIFIED_CHINESE, secondStore.loadLanguage())

    secondStore.clear()

    assertEquals(AppLanguage.ENGLISH, firstStore.loadLanguage())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("locale-settings-migration")
    val legacyKeyValueStore = InMemoryLocaleSettingsKeyValueStore()
    val legacyStore = LocaleSettingsStore(legacyKeyValueStore)
    legacyStore.saveLanguage(AppLanguage.SIMPLIFIED_CHINESE)
    val fileBackedKeyValueStore = FileBackedLocaleSettingsKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = LocaleSettingsStore(fileBackedKeyValueStore)
    assertEquals(AppLanguage.SIMPLIFIED_CHINESE, fileBackedStore.loadLanguage())

    fileBackedStore.saveLanguage(AppLanguage.ENGLISH)
    legacyStore.saveLanguage(AppLanguage.SIMPLIFIED_CHINESE)

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(AppLanguage.ENGLISH, fileBackedStore.loadLanguage())
  }
}
