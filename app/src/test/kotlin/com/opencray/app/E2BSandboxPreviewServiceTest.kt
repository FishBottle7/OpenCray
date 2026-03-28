package com.opencray.app

import com.opencray.runtime.SandboxPreviewRequest
import com.opencray.runtime.SandboxPreviewProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException

class E2BSandboxPreviewServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun openBuildsPreviewUrlForMatchingWorkspaceSession() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-preview-workspace").toPath()
    val probeRequests = mutableListOf<SandboxPreviewProbeRequest>()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-preview",
          sandboxDomain = "e2b.app",
          envdAccessToken = "envd-token",
          trafficAccessToken = "traffic-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val service = E2BSandboxPreviewService(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            providerId = SandboxProviderId.E2B.wireValue,
            defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      sessionStore = sessionStore,
      probeTransport = SandboxPreviewProbeTransport { request ->
        probeRequests += request
        SandboxPreviewProbeResponse(statusCode = 200)
      },
    )

    val preview = service.open(
      SandboxPreviewRequest(
        workspaceRoot = workspaceRoot,
        port = 4173,
        path = "preview",
      ),
    )

    assertEquals("https://4173-sb-preview.e2b.app/preview", preview.url)
    assertEquals("e2b", preview.providerId)
    assertEquals("sb-preview", preview.sandboxId)
    assertEquals("e2b.app", preview.sandboxDomain)
    assertEquals(E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME, preview.accessHeaderName)
    assertTrue(preview.accessTokenConfigured)
    assertEquals(SandboxPreviewProbeStatus.READY, preview.probeStatus)
    assertEquals(200, preview.probeHttpStatusCode)
    assertEquals(1, probeRequests.size)
    assertEquals("HEAD", probeRequests.single().method)
    assertEquals(
      "traffic-token",
      probeRequests.single().headers[E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME],
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun openRejectsPreviewForDifferentWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-preview-workspace-mismatch").toPath()
    val otherWorkspaceRoot = temporaryFolder.newFolder("e2b-preview-workspace-other").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-preview",
          sandboxDomain = "e2b.app",
          workspaceRoot = otherWorkspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val service = E2BSandboxPreviewService(
      settingsProvider = {
        ResolvedSandboxSettings(
          state = SandboxSettingsState(
            enabled = true,
            providerId = SandboxProviderId.E2B.wireValue,
            defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
            e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
          ),
          e2bApiKey = "secret-token",
        )
      },
      sessionStore = sessionStore,
    )

    service.open(
      SandboxPreviewRequest(
        workspaceRoot = workspaceRoot,
        port = 3000,
      ),
    )
  }

  @Test
  fun openMarksPreviewAsReachableWhenProbeGetsHttpError() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-preview-http-error").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-preview-http-error",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val service = E2BSandboxPreviewService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      probeTransport = SandboxPreviewProbeTransport {
        SandboxPreviewProbeResponse(statusCode = 404)
      },
    )

    val preview = service.open(
      SandboxPreviewRequest(
        workspaceRoot = workspaceRoot,
        port = 4173,
        path = "/health",
      ),
    )

    assertEquals(SandboxPreviewProbeStatus.REACHABLE, preview.probeStatus)
    assertEquals(404, preview.probeHttpStatusCode)
    assertTrue(preview.probeMessage.orEmpty().contains("not found"))
  }

  @Test
  fun openUsesActiveInMemorySessionWhenPreviewSessionIsNotPersisted() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-preview-active-session").toPath()
    val probeRequests = mutableListOf<SandboxPreviewProbeRequest>()
    val activeSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-active-only",
      sandboxDomain = "e2b.app",
      trafficAccessToken = "traffic-token",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 1L,
    )
    val service = E2BSandboxPreviewService(
      settingsProvider = { sandboxSettings() },
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      activeSessionProvider = { activeSession },
      probeTransport = SandboxPreviewProbeTransport { request ->
        probeRequests += request
        SandboxPreviewProbeResponse(statusCode = 200)
      },
    )

    val preview = service.open(
      SandboxPreviewRequest(
        workspaceRoot = workspaceRoot,
        port = 3000,
        path = "/health",
      ),
    )

    assertEquals("https://3000-sb-active-only.e2b.app/health", preview.url)
    assertEquals("sb-active-only", preview.sandboxId)
    assertEquals(1, probeRequests.size)
    assertEquals(
      "traffic-token",
      probeRequests.single().headers[E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME],
    )
  }

  @Test
  fun openMarksPreviewAsUnreachableWhenProbeFails() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-preview-unreachable").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-preview-unreachable",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val service = E2BSandboxPreviewService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      probeTransport = SandboxPreviewProbeTransport {
        throw ConnectException("Connection refused")
      },
    )

    val preview = service.open(
      SandboxPreviewRequest(
        workspaceRoot = workspaceRoot,
        port = 3000,
      ),
    )

    assertEquals(SandboxPreviewProbeStatus.UNREACHABLE, preview.probeStatus)
    assertEquals(null, preview.probeHttpStatusCode)
    assertTrue(preview.probeMessage.orEmpty().contains("may not be listening"))
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
