package com.opencray.app

import com.opencray.runtime.SandboxSessionInfoRequest
import com.opencray.runtime.SandboxSessionLifecycleStatus
import com.opencray.runtime.SandboxSessionInfoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BSandboxSessionInfoServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun inspectUsesActiveInMemorySessionWhenStoredSnapshotMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-active").toPath()
    val activeSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-active",
      sandboxDomain = "e2b.app",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 11L,
      previewCandidatePorts = listOf(4173),
    )
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      activeSessionProvider = { activeSession },
      runningRequestIdsProvider = { sandboxId ->
        if (sandboxId == "sb-active") listOf("req-2", "req-1") else emptyList()
      },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxProviderId.E2B.wireValue, result.providerId)
    assertEquals(SandboxSessionInfoSource.ACTIVE_MEMORY, result.source)
    assertEquals("sb-active", result.sandboxId)
    assertEquals(listOf(4173), result.previewCandidatePorts)
    assertEquals(listOf("req-1", "req-2"), result.runningRequestIds)
    assertEquals(11L, result.updatedAtEpochMs)
    assertEquals(SandboxSessionLifecycleStatus.ACTIVE, result.lifecycleStatus)
    assertEquals(11L, result.sessionLastActivityAtEpochMs)
  }

  @Test
  fun inspectUsesStoredSessionWhenInMemorySessionIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-stored").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-stored",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 17L,
          previewCandidatePorts = listOf(3000),
        ),
      )
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      runningRequestIdsProvider = { sandboxId ->
        if (sandboxId == "sb-stored") listOf("req-stored") else emptyList()
      },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionInfoSource.PERSISTED, result.source)
    assertEquals("sb-stored", result.sandboxId)
    assertEquals(listOf(3000), result.previewCandidatePorts)
    assertEquals(listOf("req-stored"), result.runningRequestIds)
    assertEquals(17L, result.updatedAtEpochMs)
    assertEquals(SandboxSessionLifecycleStatus.ACTIVE, result.lifecycleStatus)
  }

  @Test
  fun inspectMergesActiveAndStoredSessionStateForSameSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-merged").toPath()
    val activeSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-merged",
      sandboxDomain = "e2b.app",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 30L,
      previewCandidatePorts = listOf(4173),
    )
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-merged",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 20L,
          previewCandidatePorts = listOf(3000),
        ),
      )
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { activeSession },
      runningRequestIdsProvider = { sandboxId ->
        if (sandboxId == "sb-merged") listOf("req-1") else emptyList()
      },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionInfoSource.ACTIVE_AND_PERSISTED, result.source)
    assertEquals("sb-merged", result.sandboxId)
    assertEquals(listOf(3000, 4173), result.previewCandidatePorts)
    assertEquals(listOf("req-1"), result.runningRequestIds)
    assertEquals(30L, result.updatedAtEpochMs)
    assertEquals(SandboxSessionLifecycleStatus.ACTIVE, result.lifecycleStatus)
  }

  @Test
  fun inspectReturnsRemoteWorkspaceRootAndLatestPreviewLifecycle() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-lifecycle").toPath()
    val activeSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-lifecycle",
      sandboxDomain = "e2b.app",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 50L,
      remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sb-lifecycle",
      previewCandidatePorts = listOf(4173),
      lastPreviewUrl = "https://4173-sb-lifecycle.e2b.app/new",
      lastPreviewPort = 4173,
      lastPreviewPath = "/new",
      lastPreviewProbeStatus = "ready",
      lastPreviewProbeHttpStatusCode = 200,
      lastPreviewProbeMessage = null,
      lastPreviewOpenedAtEpochMs = 40L,
    )
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-lifecycle",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 20L,
          previewCandidatePorts = listOf(3000),
          lastPreviewUrl = "https://3000-sb-lifecycle.e2b.app/old",
          lastPreviewPort = 3000,
          lastPreviewPath = "/old",
          lastPreviewProbeStatus = "reachable",
          lastPreviewProbeHttpStatusCode = 404,
          lastPreviewProbeMessage = "older",
          lastPreviewOpenedAtEpochMs = 10L,
        ),
      )
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { activeSession },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals("/home/user/opencray/workspace-sticky/sb-lifecycle", result.remoteWorkspaceRoot)
    assertEquals("https://4173-sb-lifecycle.e2b.app/new", result.lastPreviewUrl)
    assertEquals(4173, result.lastPreviewPort)
    assertEquals("/new", result.lastPreviewPath)
    assertEquals("ready", result.lastPreviewProbeStatus)
    assertEquals(200, result.lastPreviewProbeHttpStatusCode)
    assertEquals(null, result.lastPreviewProbeMessage)
    assertEquals(40L, result.lastPreviewOpenedAtEpochMs)
    assertNull(result.lastPreviewProbeObservedAtEpochMs)
    assertNull(result.lastPreviewProbeSource)
  }

  @Test
  fun inspectAutoProbesPreviewLifecycleWhenStoredProbeIsOld() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-auto-probe").toPath()
    var latestActiveSession: E2BSandboxSessionSnapshot? = E2BSandboxSessionSnapshot(
      sandboxId = "sb-auto-probe",
      sandboxDomain = "e2b.app",
      trafficAccessToken = "traffic-token",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 5_000L,
      previewCandidatePorts = listOf(4173),
      lastPreviewUrl = "https://4173-sb-auto-probe.e2b.app/",
      lastPreviewPort = 4173,
      lastPreviewProbeStatus = "ready",
      lastPreviewOpenedAtEpochMs = 10_000L,
      lastPreviewProbeObservedAtEpochMs = 1_000L,
      lastPreviewProbeSource = SandboxPreviewProbeSource.PREVIEW_OPEN,
    )
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(requireNotNull(latestActiveSession))
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = { latestActiveSession },
      activeSessionRecorder = { snapshot -> latestActiveSession = snapshot },
      probeTransport = SandboxPreviewProbeTransport {
        SandboxPreviewProbeResponse(statusCode = 503)
      },
      clock = { 60_000L },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertTrue(result.previewAutoProbeAttempted)
    assertEquals("reachable", result.lastPreviewProbeStatus)
    assertEquals(503, result.lastPreviewProbeHttpStatusCode)
    assertEquals(60_000L, result.lastPreviewProbeObservedAtEpochMs)
    assertEquals(SandboxPreviewProbeSource.SESSION_INFO_AUTO, result.lastPreviewProbeSource)
    assertEquals(45_000L, result.recommendedRefreshAfterMs)
    assertEquals("reachable", latestActiveSession?.lastPreviewProbeStatus)
    assertEquals(60_000L, latestActiveSession?.lastPreviewProbeObservedAtEpochMs)
    assertEquals(60_000L, sessionStore.load()?.lastPreviewProbeObservedAtEpochMs)
  }

  @Test
  fun inspectMarksPauseResumeSessionAsStaleWithoutReclaim() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-stale").toPath()
    var sessionCloseCalls = 0
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-stale",
          sandboxDomain = "e2b.app",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 0L,
        ),
      )
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = {
        sandboxSettings(
          sessionMode = SandboxSessionMode.STICKY.wireValue,
          autoResume = true,
          idleTimeoutMinutes = 1,
          timeoutAction = SandboxTimeoutAction.PAUSE.wireValue,
        )
      },
      sessionStore = sessionStore,
      sessionCloser = {
        sessionCloseCalls += 1
        error("sessionCloser should not run for pause/auto-resume stale sessions.")
      },
      clock = { 181_000L },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionInfoSource.PERSISTED, result.source)
    assertTrue(result.sessionPresent)
    assertTrue(result.sessionIsStale)
    assertEquals(SandboxSessionLifecycleStatus.STALE, result.lifecycleStatus)
    assertEquals(0, sessionCloseCalls)
    assertEquals("sb-stale", sessionStore.load()?.sandboxId)
  }

  @Test
  fun inspectReclaimsStaleKillModeSessionWhenItExpires() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-reclaim").toPath()
    var sessionCloseCalls = 0
    val staleSession = E2BSandboxSessionSnapshot(
      sandboxId = "sb-reclaim",
      sandboxDomain = "e2b.app",
      workspaceRoot = workspaceRoot.toString(),
      templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
      updatedAtEpochMs = 0L,
      previewCandidatePorts = listOf(4173),
    )
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(staleSession)
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = {
        sandboxSettings(
          sessionMode = SandboxSessionMode.STICKY.wireValue,
          autoResume = false,
          idleTimeoutMinutes = 1,
          timeoutAction = SandboxTimeoutAction.KILL.wireValue,
        )
      },
      sessionStore = sessionStore,
      sessionCloser = { session ->
        sessionCloseCalls += 1
        assertEquals("sb-reclaim", session.sandboxId)
        sessionStore.clear()
        com.opencray.runtime.SandboxSessionCloseResult(
          providerId = SandboxProviderId.E2B.wireValue,
          outcome = com.opencray.runtime.SandboxSessionCloseOutcome.TERMINATED,
          sandboxId = session.sandboxId,
          sandboxDomain = session.sandboxDomain,
          previewCandidatePorts = session.previewCandidatePorts,
        )
      },
      clock = { 181_000L },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(1, sessionCloseCalls)
    assertFalse(result.sessionPresent)
    assertEquals(SandboxSessionInfoSource.NONE, result.source)
    assertEquals(SandboxSessionLifecycleStatus.RECLAIMED, result.lifecycleStatus)
    assertTrue(result.sessionIsStale)
    assertEquals("sb-reclaim", result.sandboxId)
    assertEquals(listOf(4173), result.previewCandidatePorts)
    assertNull(sessionStore.load())
  }

  @Test
  fun inspectReturnsNoneWhenNoSessionExistsForWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("e2b-session-info-target").toPath()
    val otherWorkspaceRoot = temporaryFolder.newFolder("e2b-session-info-other").toPath()
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-other",
          sandboxDomain = "e2b.app",
          workspaceRoot = otherWorkspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
          previewCandidatePorts = listOf(3000),
        ),
      )
    }
    val service = E2BSandboxSessionInfoService(
      settingsProvider = { sandboxSettings() },
      sessionStore = sessionStore,
      activeSessionProvider = {
        E2BSandboxSessionSnapshot(
          sandboxId = "sb-active-other",
          sandboxDomain = "e2b.app",
          workspaceRoot = otherWorkspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 2L,
        )
      },
      runningRequestIdsProvider = { error("runningRequestIdsProvider should not be called without a match.") },
    )

    val result = service.inspect(
      SandboxSessionInfoRequest(
        workspaceRoot = workspaceRoot,
      ),
    )

    assertEquals(SandboxSessionInfoSource.NONE, result.source)
    assertFalse(result.sessionPresent)
    assertEquals(emptyList<String>(), result.runningRequestIds)
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

  private fun sandboxSettings(
    sessionMode: String,
    autoResume: Boolean,
    idleTimeoutMinutes: Int,
    timeoutAction: String,
  ): ResolvedSandboxSettings = ResolvedSandboxSettings(
    state = SandboxSettingsState(
      enabled = true,
      providerId = SandboxProviderId.E2B.wireValue,
      defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
      sessionMode = sessionMode,
      autoResume = autoResume,
      idleTimeoutMinutes = idleTimeoutMinutes,
      timeoutAction = timeoutAction,
      e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
    ),
    e2bApiKey = "secret-token",
  )
}
