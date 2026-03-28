package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxNativeToolVisibilityTest {
  @Test
  fun localBackendKeepsSandboxNativeToolsHidden() {
    val settings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = true,
        providerId = SandboxProviderId.E2B.wireValue,
        defaultBackend = SandboxExecutionBackendPreference.LOCAL.wireValue,
      ),
      e2bApiKey = "e2b-key",
    )

    assertFalse(SandboxNativeToolVisibility.shouldExposeToolDefinitions(settings))
    assertEquals(
      setOf(SandboxNativeToolVisibility.TOOL_NAME_PREFIX),
      SandboxNativeToolVisibility.hiddenToolNamePrefixes(settings),
    )
  }

  @Test
  fun autoBackendKeepsSandboxNativeToolsHidden() {
    val settings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = true,
        providerId = SandboxProviderId.E2B.wireValue,
        defaultBackend = SandboxExecutionBackendPreference.AUTO.wireValue,
      ),
      e2bApiKey = "e2b-key",
    )

    assertFalse(SandboxNativeToolVisibility.shouldExposeToolDefinitions(settings))
    assertEquals(
      setOf(SandboxNativeToolVisibility.TOOL_NAME_PREFIX),
      SandboxNativeToolVisibility.hiddenToolNamePrefixes(settings),
    )
  }

  @Test
  fun sandboxBackendExposesSandboxNativeToolsWhenConfigured() {
    val settings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = true,
        providerId = SandboxProviderId.E2B.wireValue,
        defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      ),
      e2bApiKey = "e2b-key",
    )

    assertTrue(SandboxNativeToolVisibility.shouldExposeToolDefinitions(settings))
    assertTrue(SandboxNativeToolVisibility.hiddenToolNamePrefixes(settings).isEmpty())
  }

  @Test
  fun sandboxBackendKeepsSandboxNativeToolsHiddenWhenCredentialsMissing() {
    val settings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = true,
        providerId = SandboxProviderId.E2B.wireValue,
        defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      ),
      e2bApiKey = null,
    )

    assertFalse(SandboxNativeToolVisibility.shouldExposeToolDefinitions(settings))
    assertEquals(
      setOf(SandboxNativeToolVisibility.TOOL_NAME_PREFIX),
      SandboxNativeToolVisibility.hiddenToolNamePrefixes(settings),
    )
  }
}
