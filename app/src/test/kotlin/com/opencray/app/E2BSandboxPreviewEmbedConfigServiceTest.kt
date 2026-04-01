package com.opencray.app

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E2BSandboxPreviewEmbedConfigServiceTest {

  @Test
  fun resolveUsesActiveSessionPreviewTokenForMatchingPreviewUrl() {
    val workspaceRoot = Paths.get("/workspace/project").toAbsolutePath().normalize()
    val service = E2BSandboxPreviewEmbedConfigService(
      settingsProvider = ::sandboxSettings,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      activeSessionProvider = {
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-active",
          sandboxDomain = "e2b.app",
          trafficAccessToken = "traffic-active",
          workspaceRoot = workspaceRoot.toString(),
          templateId = "tmpl-dev",
          updatedAtEpochMs = 1_000L,
          lastPreviewUrl = "https://3000-sb-active.e2b.app/",
        )
      },
    )

    val config = service.resolve(
      previewUrl = "https://3000-sb-active.e2b.app/",
      workspaceRoot = workspaceRoot,
    )

    assertTrue(config.sessionMatched)
    assertTrue(config.accessTokenConfigured)
    assertEquals(SandboxProviderId.E2B.wireValue, config.providerId)
    assertEquals(
      "traffic-active",
      config.headers[E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME],
    )
  }

  @Test
  fun resolveMergesMatchingSnapshotsToRecoverPersistedPreviewToken() {
    val workspaceRoot = Paths.get("/workspace/project").toAbsolutePath().normalize()
    val store = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    store.save(
      E2BSandboxSessionSnapshot(
        sandboxId = "sb-merged",
        sandboxDomain = "e2b.app",
        trafficAccessToken = "traffic-persisted",
        workspaceRoot = workspaceRoot.toString(),
        templateId = "tmpl-dev",
        updatedAtEpochMs = 900L,
        lastPreviewUrl = "https://3000-sb-merged.e2b.app/app",
      ),
    )
    val service = E2BSandboxPreviewEmbedConfigService(
      settingsProvider = ::sandboxSettings,
      sessionStore = store,
      activeSessionProvider = {
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-merged",
          sandboxDomain = "e2b.app",
          trafficAccessToken = null,
          workspaceRoot = workspaceRoot.toString(),
          templateId = "tmpl-dev",
          updatedAtEpochMs = 1_000L,
          lastPreviewUrl = "https://3000-sb-merged.e2b.app/app",
        )
      },
    )

    val config = service.resolve(
      previewUrl = "https://3000-sb-merged.e2b.app/app",
      workspaceRoot = workspaceRoot,
    )

    assertTrue(config.sessionMatched)
    assertTrue(config.accessTokenConfigured)
    assertEquals(
      "traffic-persisted",
      config.headers[E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME],
    )
  }

  @Test
  fun resolveReturnsUnavailableWhenPreviewUrlDoesNotMatchWorkspaceSession() {
    val workspaceRoot = Paths.get("/workspace/project").toAbsolutePath().normalize()
    val service = E2BSandboxPreviewEmbedConfigService(
      settingsProvider = ::sandboxSettings,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      activeSessionProvider = {
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-active",
          sandboxDomain = "e2b.app",
          trafficAccessToken = "traffic-active",
          workspaceRoot = workspaceRoot.toString(),
          templateId = "tmpl-dev",
          updatedAtEpochMs = 1_000L,
          lastPreviewUrl = "https://3000-sb-active.e2b.app/",
        )
      },
    )

    val config = service.resolve(
      previewUrl = "https://4000-sb-other.e2b.app/",
      workspaceRoot = workspaceRoot,
    )

    assertFalse(config.sessionMatched)
    assertFalse(config.accessTokenConfigured)
    assertTrue(config.headers.isEmpty())
    assertTrue(
      config.unavailableReason.orEmpty().contains("does not match"),
    )
  }

  private fun sandboxSettings(): ResolvedSandboxSettings = ResolvedSandboxSettings(
    state = SandboxSettingsState(
      enabled = true,
      providerId = SandboxProviderId.E2B.wireValue,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    ),
    e2bApiKey = "secret-token",
  )
}
