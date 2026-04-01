package com.opencray.app

import com.opencray.runtime.SandboxSessionCloseOutcome
import com.opencray.runtime.SandboxSessionCloseRequest
import com.opencray.runtime.SandboxSessionCloseResult
import com.opencray.runtime.SandboxSessionControlService
import java.nio.file.Path
import java.nio.file.Paths

internal class E2BSandboxSessionControlService(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot? = { null },
  private val sessionCloser: (E2BSandboxSessionSnapshot) -> SandboxSessionCloseResult,
) : SandboxSessionControlService {
  override fun close(request: SandboxSessionCloseRequest): SandboxSessionCloseResult {
    val settings = settingsProvider()
    require(settings.canUseSandbox()) {
      "Sandbox session control is unavailable because the cloud sandbox backend is not configured."
    }
    val providerId = SandboxProviderId.fromWireValue(settings.state.providerId)
    require(providerId == SandboxProviderId.E2B) {
      "Sandbox session control is only implemented for E2B right now."
    }
    val requestedWorkspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
    val session = resolveSessionForWorkspace(requestedWorkspaceRoot) ?: return SandboxSessionCloseResult(
      providerId = providerId.wireValue,
      outcome = SandboxSessionCloseOutcome.NOT_FOUND,
    )
    return sessionCloser(session)
  }

  private fun normalizeWorkspaceRoot(rawWorkspaceRoot: String): Path =
    Paths.get(rawWorkspaceRoot).toAbsolutePath().normalize()

  private fun resolveSessionForWorkspace(
    requestedWorkspaceRoot: Path,
  ): E2BSandboxSessionSnapshot? {
    val sessions = listOfNotNull(activeSessionProvider(), sessionStore.load())
    sessions.firstOrNull { session ->
      normalizeWorkspaceRoot(session.workspaceRoot) == requestedWorkspaceRoot
    }?.let { return it }
    if (sessions.isNotEmpty()) {
      throw IllegalArgumentException("The active E2B sandbox session belongs to a different workspace.")
    }
    return null
  }
}
