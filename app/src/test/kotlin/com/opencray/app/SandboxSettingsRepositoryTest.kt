package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxSettingsRepositoryTest {
  @Test
  fun saveStoresE2bApiKeyBehindCredentialRef() {
    val repository = testSandboxSettingsRepository()

    val resolved = repository.save(
      state = SandboxSettingsState(
        enabled = true,
        defaultBackend = "sandbox",
      ),
      e2bApiKey = "secret-token",
    )

    assertEquals(SandboxSettingsRepository.E2B_API_KEY_REF.uri, resolved.state.e2bApiKeyCredentialRef)
    assertEquals("secret-token", resolved.e2bApiKey)
    assertTrue(resolved.canUseSandbox())
  }

  @Test
  fun saveBlankApiKeyClearsCredentialRef() {
    val repository = testSandboxSettingsRepository()
    repository.save(
      state = SandboxSettingsState(enabled = true),
      e2bApiKey = "secret-token",
    )

    val resolved = repository.save(
      state = repository.load().state,
      e2bApiKey = "",
    )

    assertTrue(resolved.state.e2bApiKeyCredentialRef.isEmpty())
    assertFalse(resolved.hasResolvedE2bApiKey())
    assertFalse(resolved.canUseSandbox())
  }

  @Test
  fun clearE2bApiKeyRemovesStoredCredential() {
    val repository = testSandboxSettingsRepository()
    repository.save(
      state = SandboxSettingsState(enabled = true),
      e2bApiKey = "secret-token",
    )

    val resolved = repository.clearE2bApiKey()

    assertEquals("", resolved.state.e2bApiKeyCredentialRef)
    assertEquals(null, resolved.e2bApiKey)
  }
}
