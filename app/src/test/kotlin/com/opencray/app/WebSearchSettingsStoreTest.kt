package com.opencray.app

import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSearchSettingsStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadReturnsEmptyWhenNothingIsPersisted() {
    val store = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore())

    val restored = store.load()

    assertTrue(restored.isEmpty())
  }

  @Test
  fun saveAndLoadPreservesSlotOrderAndFields() {
    val store = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore())
    val original = listOf(
      WebSearchSlotConfig.create(
        id = "slot-a",
        providerId = "exa",
        label = "Primary Exa",
        apiKey = "sk-live-a",
        enabled = true,
      ),
      WebSearchSlotConfig.create(
        id = "slot-b",
        providerId = "brave",
        label = "Brave Backup",
        apiKey = "brave-live-b",
        enabled = false,
      ),
    )

    store.save(original)

    assertEquals(original, store.load())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("web-search-settings-file-backed")
    val firstStore = WebSearchSettingsStore(
      FileBackedWebSearchSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )
    val slots = listOf(
      WebSearchSlotConfig.create(
        id = "slot-exa",
        providerId = "exa",
        label = "Exa",
        apiKey = "exa-secret",
      ),
      WebSearchSlotConfig.create(
        id = "slot-openai",
        providerId = "openai_web_search",
        label = "OpenAI",
        baseUrl = "https://proxy.example.com/v1",
        model = "gpt-5-mini",
        apiKey = "openai-secret",
        enabled = false,
      ),
    )

    firstStore.save(slots)

    val secondStore = WebSearchSettingsStore(
      FileBackedWebSearchSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(slots, secondStore.load())

    secondStore.clear()

    assertTrue(firstStore.load().isEmpty())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("web-search-settings-migration")
    val legacyKeyValueStore = InMemoryWebSearchSettingsKeyValueStore()
    val legacyStore = WebSearchSettingsStore(legacyKeyValueStore)
    val legacySlots = listOf(
      WebSearchSlotConfig.create(
        id = "slot-legacy",
        providerId = "brave",
        label = "Legacy Brave",
        apiKey = "legacy-secret",
      ),
    )
    legacyStore.save(legacySlots)
    val fileBackedKeyValueStore = FileBackedWebSearchSettingsKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = WebSearchSettingsStore(fileBackedKeyValueStore)
    assertEquals(legacySlots, fileBackedStore.load())

    val durableSlots = listOf(
      WebSearchSlotConfig.create(
        id = "slot-durable",
        providerId = "exa",
        label = "Durable Exa",
        apiKey = "durable-secret",
      ),
    )
    fileBackedStore.save(durableSlots)
    legacyStore.save(
      legacySlots + WebSearchSlotConfig.create(
        id = "slot-new-legacy",
        providerId = "tavily",
        label = "New Legacy Tavily",
        apiKey = "new-legacy-secret",
      ),
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(durableSlots, fileBackedStore.load())
  }

  @Test
  fun facadeSaveNormalizesUnsupportedProvidersToExa() {
    val store = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore())
    val facade = LocalNetworkSearchConfigFacade.create(store)

    val snapshot = facade.save(
      SaveNetworkSearchConfigRequest(
        slots = listOf(
          SaveNetworkSearchSlotRequest(
            id = "slot-a",
            providerId = "unknown",
            label = "Fallback",
            baseUrl = "https://api.example.com/v1",
            model = "gpt-5",
            apiKey = "secret",
            enabled = true,
          ),
        ),
      ),
    )

    assertEquals(1, snapshot.slots.size)
    assertEquals("exa", snapshot.slots.single().providerId)
    assertEquals("", snapshot.slots.single().baseUrl)
    assertEquals("", snapshot.slots.single().model)
  }

  @Test
  fun openAiWebSearchPreservesBaseUrlWhileOtherProvidersClearIt() {
    val store = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore())
    store.save(
      listOf(
        WebSearchSlotConfig.create(
          id = "slot-openai",
          providerId = "openai_web_search",
          label = "OpenAI Search",
          baseUrl = "https://proxy.example.com/v1",
          model = "gpt-5-mini",
          apiKey = "openai-secret",
          enabled = true,
        ),
        WebSearchSlotConfig.create(
          id = "slot-exa",
          providerId = "exa",
          label = "Exa",
          baseUrl = "https://should-be-cleared.example.com",
          model = "should-be-cleared",
          apiKey = "exa-secret",
          enabled = true,
        ),
      ),
    )

    val restored = store.load()

    assertEquals("https://proxy.example.com/v1", restored[0].baseUrl)
    assertEquals("gpt-5-mini", restored[0].model)
    assertEquals("", restored[1].baseUrl)
    assertEquals("", restored[1].model)
  }
}
