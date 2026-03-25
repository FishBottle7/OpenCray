package com.opencray.app

import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsStoreTest {
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
  fun facadeSaveNormalizesUnsupportedProvidersToExa() {
    val store = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore())
    val facade = LocalNetworkSearchConfigFacade.createForTest(store)

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
