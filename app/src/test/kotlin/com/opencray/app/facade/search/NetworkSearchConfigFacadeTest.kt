package com.opencray.app.facade.search

import com.opencray.app.InMemoryWebSearchSettingsKeyValueStore
import com.opencray.app.WebSearchSettingsStore
import com.opencray.app.toGatewayMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSearchConfigFacadeTest {
  private companion object {
    const val PERSISTED_API_KEY = "exa-secret-key-1234"
    const val MASKED_API_KEY = "••••1234"
  }

  private fun facade(): NetworkSearchConfigFacade =
    LocalNetworkSearchConfigFacade.create(
      settingsStore = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
    )

  private fun saveRequest(apiKey: String?): SaveNetworkSearchConfigRequest =
    SaveNetworkSearchConfigRequest(
      slots = listOf(
        SaveNetworkSearchSlotRequest(
          id = "slot-primary",
          providerId = "exa",
          label = "Primary Exa",
          baseUrl = "",
          model = "",
          apiKey = apiKey,
          enabled = true,
        ),
      ),
    )

  @Test
  fun loadGatewayMapDoesNotExposePlaintextApiKey() {
    val facade = facade()
    facade.save(saveRequest(PERSISTED_API_KEY))

    val payload = facade.load().toGatewayMap()
    val slot = (payload["slots"] as List<*>).single() as Map<*, *>

    assertEquals(MASKED_API_KEY, slot["apiKey"])
    assertEquals(true, slot["hasCredential"])
    assertEquals("1234", slot["credentialHint"])
    assertFalse(payload.toString().contains(PERSISTED_API_KEY))
  }

  @Test
  fun emptyCredentialReportsHasCredentialFalseWithBlankHint() {
    val facade = facade()
    facade.save(saveRequest(""))

    val payload = facade.load().toGatewayMap()
    val slot = (payload["slots"] as List<*>).single() as Map<*, *>

    assertEquals("", slot["apiKey"])
    assertEquals(false, slot["hasCredential"])
    assertEquals("", slot["credentialHint"])
    assertTrue(slot.containsKey("hasCredential"))
    assertTrue(slot.containsKey("credentialHint"))
  }

  @Test
  fun saveWithEchoedMaskedApiKeyPreservesStoredCredential() {
    val facade = facade()
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(saveRequest(MASKED_API_KEY))

    assertEquals(PERSISTED_API_KEY, saved.slots.single().apiKey)
  }

  @Test
  fun saveWithoutApiKeyFieldPreservesStoredCredential() {
    val facade = facade()
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(
      SaveNetworkSearchConfigRequest(
        slots = listOf(
          SaveNetworkSearchSlotRequest(
            id = "slot-primary",
            providerId = "exa",
            label = "Renamed Exa",
            baseUrl = "",
            model = "",
            apiKey = null,
            enabled = true,
          ),
        ),
      ),
    )

    assertEquals("Renamed Exa", saved.slots.single().label)
    assertEquals(PERSISTED_API_KEY, saved.slots.single().apiKey)
  }

  @Test
  fun saveWithExplicitEmptyApiKeyClearsStoredCredential() {
    val facade = facade()
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(saveRequest(""))

    assertEquals("", saved.slots.single().apiKey)
  }

  @Test
  fun saveWithNewPlaintextApiKeyReplacesStoredCredential() {
    val facade = facade()
    facade.save(saveRequest(PERSISTED_API_KEY))

    val saved = facade.save(saveRequest("brand-new-key-9876"))

    assertEquals("brand-new-key-9876", saved.slots.single().apiKey)
  }
}
