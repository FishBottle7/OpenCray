package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxSettingsStoreTest {
  @Test
  fun loadDefaultsToSafeLocalBackend() {
    val store = SandboxSettingsStore(InMemorySandboxSettingsKeyValueStore())

    val state = store.load()

    assertFalse(state.enabled)
    assertEquals("e2b", state.providerId)
    assertEquals("local", state.defaultBackend)
    assertEquals("ephemeral", state.sessionMode)
    assertEquals("kill", state.timeoutAction)
  }

  @Test
  fun saveAndLoadRoundTripsSanitizedState() {
    val store = SandboxSettingsStore(InMemorySandboxSettingsKeyValueStore())
    val saved = SandboxSettingsState(
      enabled = true,
      providerId = "E2B",
      defaultBackend = "AUTO",
      sessionMode = "sticky",
      autoResume = true,
      idleTimeoutMinutes = 45,
      startupTimeoutMs = 40_000L,
      requestTimeoutMs = 600_000L,
      timeoutAction = "pause",
      templateId = "  tmpl-opencray  ",
      e2bApiKeyCredentialRef = "secret://sandbox/e2b/api-key",
    )

    store.save(saved)

    assertEquals(saved.sanitized(), store.load())
  }

  @Test
  fun sanitizedStateClampsTimeoutsAndDropsBlankCredentialRef() {
    val sanitized = SandboxSettingsState(
      idleTimeoutMinutes = -1,
      startupTimeoutMs = 1L,
      requestTimeoutMs = Long.MAX_VALUE,
      e2bApiKeyCredentialRef = "   ",
    ).sanitized()

    assertEquals(SandboxSettingsState.MIN_IDLE_TIMEOUT_MINUTES, sanitized.idleTimeoutMinutes)
    assertEquals(SandboxSettingsState.MIN_STARTUP_TIMEOUT_MS, sanitized.startupTimeoutMs)
    assertEquals(SandboxSettingsState.MAX_REQUEST_TIMEOUT_MS, sanitized.requestTimeoutMs)
    assertTrue(sanitized.e2bApiKeyCredentialRef.isEmpty())
  }
}
