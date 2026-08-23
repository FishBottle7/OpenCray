package com.opencray.app

import com.opencray.app.e2b.E2BCodeInterpreterPythonRuntime
import com.opencray.runtime.SandboxSessionCloseOutcome
import com.opencray.runtime.SandboxSessionCloseRequest
import com.opencray.runtime.SandboxSessionCloseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BSandboxSessionControlServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun closeUsesActiveInMemorySessionWhenStoredSnapshotMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-close-active").toPath()
    val activeSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-active",
      sandboxDomain = "e2b.app",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 1L,
      previewCandidatePorts = listOf(4173),
    )
    var closedSession: E2BSandboxSessionSnapshot? = null
    val service = E2BSandboxSessionControlService(
      settingsProvider = { sandboxSettings() },
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      activeSessionProvider = { activeSession },
      sessionCloser = { session ->
        closedSession = session
        SandboxSessionCloseResult(
          providerId = SandboxProviderId.E2B.wireValue,
          outcome = SandboxSessionCloseOutcome.TERMINATED,
          sandboxId = session.sandboxId,
          sandboxDomain = session.sandboxDomain,
          previewCandidatePorts = session.previewCandidatePorts,
        )
      },
    )

    val result = service.close(
      SandboxSessionCloseRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionCloseOutcome.TERMINATED, result.outcome)
    assertEquals("sb-active", closedSession?.sandboxId)
    assertEquals(listOf(4173), closedSession?.previewCandidatePorts)
  }

  @Test
  fun closeUsesStoredSessionWhenInMemorySessionIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-close-stored").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-stored",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
          previewCandidatePorts = listOf(3000),
        ),
      )
    }
    var closedSession: E2BSandboxSessionSnapshot? = null
    val service = E2BSandboxSessionControlService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      sessionCloser = { session ->
        closedSession = session
        SandboxSessionCloseResult(
          providerId = SandboxProviderId.E2B.wireValue,
          outcome = SandboxSessionCloseOutcome.TERMINATED,
          sandboxId = session.sandboxId,
          sandboxDomain = session.sandboxDomain,
          previewCandidatePorts = session.previewCandidatePorts,
        )
      },
    )

    val result = service.close(
      SandboxSessionCloseRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionCloseOutcome.TERMINATED, result.outcome)
    assertEquals("sb-stored", closedSession?.sandboxId)
  }

  @Test
  fun closeReturnsNotFoundWhenNoSessionExists() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-close-missing").toPath()
    val service = E2BSandboxSessionControlService(
      settingsProvider = { sandboxSettings() },
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      sessionCloser = { error("sessionCloser should not be called when no session exists.") },
    )

    val result = service.close(
      SandboxSessionCloseRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionCloseOutcome.NOT_FOUND, result.outcome)
  }

  @Test
  fun closeRejectsSessionForDifferentWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-close-target").toPath()
    val otherWorkspaceRoot = temporaryFolder.newFolder("e2b-session-close-other").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-other",
          sandboxDomain = "e2b.app",
          workspaceRoot = otherWorkspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val service = E2BSandboxSessionControlService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      sessionCloser = { error("sessionCloser should not be called for workspace mismatch.") },
    )

    val error = runCatching {
      service.close(
        SandboxSessionCloseRequest(
          workspaceRoot = workspaceRoot,
        ),
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
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
