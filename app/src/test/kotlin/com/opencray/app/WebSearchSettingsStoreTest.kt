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
            apiKey = "secret",
            enabled = true,
          ),
        ),
      ),
    )

    assertEquals(1, snapshot.slots.size)
    assertEquals("exa", snapshot.slots.single().providerId)
  }
}
