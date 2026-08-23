package com.opencray.app

import com.opencray.app.e2b.mergePreviewCandidatePorts
import com.opencray.runtime.SandboxSessionCloseOutcome
import com.opencray.runtime.SandboxSessionCloseResult
import com.opencray.runtime.SandboxSessionLifecycleStatus
import com.opencray.runtime.SandboxSessionInfoRequest
import com.opencray.runtime.SandboxSessionInfoResult
import com.opencray.runtime.SandboxSessionInfoService
import com.opencray.runtime.SandboxSessionInfoSource
import java.nio.file.Path
import java.nio.file.Paths

internal class E2BSandboxSessionInfoService(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot? = { null },
  private val activeSessionRecorder: (E2BSandboxSessionSnapshot) -> Unit = { },
  private val runningRequestIdsProvider: (String) -> List<String> = { emptyList() },
  private val sessionCloser: (E2BSandboxSessionSnapshot) -> SandboxSessionCloseResult = {
    SandboxSessionCloseResult(
      providerId = SandboxProviderId.E2B.wireValue,
      outcome = SandboxSessionCloseOutcome.NOT_FOUND,
    )
  },
  private val probeTransport: SandboxPreviewProbeTransport = UrlConnectionSandboxPreviewProbeTransport(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SandboxSessionInfoService {
  override fun inspect(request: SandboxSessionInfoRequest): SandboxSessionInfoResult {
    val settings = settingsProvider()
    require(settings.canUseSandbox()) {
      "Sandbox session info is unavailable because the cloud sandbox backend is not configured."
    }
    val state = settings.state.sanitized()
    val providerId = SandboxProviderId.fromWireValue(state.providerId)
    require(providerId == SandboxProviderId.E2B) {
      "Sandbox session info is only implemented for E2B right now."
    }
    val requestedWorkspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
    val now = clock()

    val activeSession = activeSessionProvider()?.takeIf { session ->
      normalizeWorkspaceRoot(session.workspaceRoot) == requestedWorkspaceRoot
    }
    val persistedSession = sessionStore.load()?.takeIf { session ->
      normalizeWorkspaceRoot(session.workspaceRoot) == requestedWorkspaceRoot
    }
    val source = sandboxSessionSource(
      activeSession = activeSession,
      persistedSession = persistedSession,
    )
    val preferredSession = activeSession ?: persistedSession
      ?: return SandboxSessionInfoResult(
        providerId = providerId.wireValue,
        source = SandboxSessionInfoSource.NONE,
        lifecycleStatus = SandboxSessionLifecycleStatus.NONE,
      )

    val sameSandboxSnapshots = listOfNotNull(
      activeSession?.takeIf { session -> session.sandboxId == preferredSession.sandboxId },
      persistedSession?.takeIf { session -> session.sandboxId == preferredSession.sandboxId },
    )
    val runningRequestIds = runningRequestIdsProvider(preferredSession.sandboxId)
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .sorted()
      .toList()
    val latestPreviewSnapshot = latestPreviewSnapshot(sameSandboxSnapshots)
    val sessionLastActivityAtEpochMs = listOfNotNull(
      sameSandboxSnapshots
        .asSequence()
        .map { session -> session.updatedAtEpochMs }
        .maxOrNull(),
      latestPreviewSnapshot?.lastPreviewOpenedAtEpochMs,
    ).maxOrNull()
    val sessionStaleAfterEpochMs = sessionLastActivityAtEpochMs?.let { lastActivityAtEpochMs ->
      lastActivityAtEpochMs + (state.idleTimeoutMinutes * 60_000L) + E2B_SESSION_STALE_GRACE_MS
    }
    val sessionIsStale = sessionStaleAfterEpochMs != null &&
      runningRequestIds.isEmpty() &&
      now >= sessionStaleAfterEpochMs

    if (sessionIsStale && shouldAutoReclaimSession(state)) {
      val closeResult = runCatching { sessionCloser(preferredSession) }.getOrNull()
      if (closeResult != null && closeResult.outcome != SandboxSessionCloseOutcome.BUSY) {
        return buildSessionInfoResult(
          providerId = providerId.wireValue,
          source = SandboxSessionInfoSource.NONE,
          preferredSession = preferredSession,
          sameSandboxSnapshots = sameSandboxSnapshots,
          latestPreviewSnapshot = latestPreviewSnapshot,
          runningRequestIds = emptyList(),
          lifecycleStatus = SandboxSessionLifecycleStatus.RECLAIMED,
          sessionLastActivityAtEpochMs = sessionLastActivityAtEpochMs,
          sessionStaleAfterEpochMs = sessionStaleAfterEpochMs,
          sessionIsStale = true,
          recommendedRefreshAfterMs = null,
          previewAutoProbeAttempted = false,
        )
      }
    }

    val refreshedPreviewSnapshot = maybeAutoProbePreview(
      state = state,
      now = now,
      previewSnapshot = latestPreviewSnapshot,
      requestedWorkspaceRoot = requestedWorkspaceRoot,
    )
    val effectivePreviewSnapshot = refreshedPreviewSnapshot.snapshot ?: latestPreviewSnapshot
    val lifecycleStatus = if (sessionIsStale) {
      SandboxSessionLifecycleStatus.STALE
    } else {
      SandboxSessionLifecycleStatus.ACTIVE
    }
    val recommendedRefreshAfterMs = recommendedRefreshAfterMs(
      state = state,
      now = now,
      runningRequestIds = runningRequestIds,
      sessionStaleAfterEpochMs = sessionStaleAfterEpochMs,
      previewSnapshot = effectivePreviewSnapshot,
      lifecycleStatus = lifecycleStatus,
    )
    return buildSessionInfoResult(
      providerId = providerId.wireValue,
      source = source,
      preferredSession = preferredSession,
      sameSandboxSnapshots = sameSandboxSnapshots,
      latestPreviewSnapshot = effectivePreviewSnapshot,
      runningRequestIds = runningRequestIds,
      lifecycleStatus = lifecycleStatus,
      sessionLastActivityAtEpochMs = sessionLastActivityAtEpochMs,
      sessionStaleAfterEpochMs = sessionStaleAfterEpochMs,
      sessionIsStale = sessionIsStale,
      recommendedRefreshAfterMs = recommendedRefreshAfterMs,
      previewAutoProbeAttempted = refreshedPreviewSnapshot.attempted,
    )
  }

  private fun buildSessionInfoResult(
    providerId: String,
    source: SandboxSessionInfoSource,
    preferredSession: E2BSandboxSessionSnapshot,
    sameSandboxSnapshots: List<E2BSandboxSessionSnapshot>,
    latestPreviewSnapshot: E2BSandboxSessionSnapshot?,
    runningRequestIds: List<String>,
    lifecycleStatus: SandboxSessionLifecycleStatus,
    sessionLastActivityAtEpochMs: Long?,
    sessionStaleAfterEpochMs: Long?,
    sessionIsStale: Boolean,
    recommendedRefreshAfterMs: Long?,
    previewAutoProbeAttempted: Boolean,
  ): SandboxSessionInfoResult = SandboxSessionInfoResult(
    providerId = providerId,
    source = source,
    sandboxId = preferredSession.sandboxId,
    sandboxDomain = preferredSession.sandboxDomain,
    templateId = preferredSession.templateId,
    workspaceRoot = preferredSession.workspaceRoot,
    updatedAtEpochMs = sameSandboxSnapshots
      .asSequence()
      .map { session -> session.updatedAtEpochMs }
      .maxOrNull()
      ?: preferredSession.updatedAtEpochMs,
    previewCandidatePorts = mergePreviewCandidatePorts(
      *sameSandboxSnapshots
        .map { session -> session.previewCandidatePorts }
        .toTypedArray(),
    ),
    runningRequestIds = runningRequestIds,
    lifecycleStatus = lifecycleStatus,
    sessionLastActivityAtEpochMs = sessionLastActivityAtEpochMs,
    sessionStaleAfterEpochMs = sessionStaleAfterEpochMs,
    sessionIsStale = sessionIsStale,
    recommendedRefreshAfterMs = recommendedRefreshAfterMs,
    remoteWorkspaceRoot = sameSandboxSnapshots
      .asSequence()
      .mapNotNull { session -> session.remoteWorkspaceRoot?.trim()?.takeIf(String::isNotBlank) }
      .firstOrNull(),
    lastPreviewUrl = latestPreviewSnapshot?.lastPreviewUrl,
    lastPreviewPort = latestPreviewSnapshot?.lastPreviewPort,
    lastPreviewPath = latestPreviewSnapshot?.lastPreviewPath,
    lastPreviewProbeStatus = latestPreviewSnapshot?.lastPreviewProbeStatus,
    lastPreviewProbeHttpStatusCode = latestPreviewSnapshot?.lastPreviewProbeHttpStatusCode,
    lastPreviewProbeMessage = latestPreviewSnapshot?.lastPreviewProbeMessage,
    lastPreviewOpenedAtEpochMs = latestPreviewSnapshot?.lastPreviewOpenedAtEpochMs,
    lastPreviewProbeObservedAtEpochMs = latestPreviewSnapshot?.lastPreviewProbeObservedAtEpochMs,
    lastPreviewProbeSource = latestPreviewSnapshot?.lastPreviewProbeSource,
    previewAutoProbeAttempted = previewAutoProbeAttempted,
  )

  private fun maybeAutoProbePreview(
    state: SandboxSettingsState,
    now: Long,
    previewSnapshot: E2BSandboxSessionSnapshot?,
    requestedWorkspaceRoot: Path,
  ): AutoProbePreviewResult {
    val latestPreviewSnapshot = previewSnapshot ?: return AutoProbePreviewResult()
    val previewUrl = latestPreviewSnapshot.lastPreviewUrl
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return AutoProbePreviewResult(snapshot = latestPreviewSnapshot)
    if (!shouldAutoProbePreview(state = state, now = now, previewSnapshot = latestPreviewSnapshot)) {
      return AutoProbePreviewResult(snapshot = latestPreviewSnapshot)
    }
    val probeOutcome = probeSandboxPreviewUrl(
      url = previewUrl,
      trafficAccessToken = latestPreviewSnapshot.trafficAccessToken?.trim()?.takeIf(String::isNotBlank),
      probeTransport = probeTransport,
    )
    val updatedSnapshot = latestPreviewSnapshot.copy(
      lastPreviewProbeStatus = probeOutcome.status.wireValue,
      lastPreviewProbeHttpStatusCode = probeOutcome.httpStatusCode,
      lastPreviewProbeMessage = probeOutcome.message,
      lastPreviewProbeObservedAtEpochMs = now,
      lastPreviewProbeSource = SandboxPreviewProbeSource.SESSION_INFO_AUTO,
    )
    persistPreviewLifecycle(
      requestedWorkspaceRoot = requestedWorkspaceRoot,
      previousSession = latestPreviewSnapshot,
      updatedSession = updatedSnapshot,
    )
    return AutoProbePreviewResult(
      snapshot = updatedSnapshot,
      attempted = true,
    )
  }

  private fun persistPreviewLifecycle(
    requestedWorkspaceRoot: Path,
    previousSession: E2BSandboxSessionSnapshot,
    updatedSession: E2BSandboxSessionSnapshot,
  ) {
    activeSessionProvider()?.takeIf { active ->
      active.sandboxId == previousSession.sandboxId &&
        normalizeWorkspaceRoot(active.workspaceRoot) == requestedWorkspaceRoot
    }?.let { active ->
      activeSessionRecorder(mergePreviewLifecycle(updatedSession, active))
    }
    sessionStore.update { stored ->
      stored?.takeIf { current ->
        current.sandboxId == previousSession.sandboxId &&
          normalizeWorkspaceRoot(current.workspaceRoot) == requestedWorkspaceRoot
      }?.let { current ->
        mergePreviewLifecycle(updatedSession, current)
      } ?: stored
    }
  }

  private fun normalizeWorkspaceRoot(rawWorkspaceRoot: String): Path =
    Paths.get(rawWorkspaceRoot).toAbsolutePath().normalize()
}

private data class AutoProbePreviewResult(
  val snapshot: E2BSandboxSessionSnapshot? = null,
  val attempted: Boolean = false,
)

private fun sandboxSessionSource(
  activeSession: E2BSandboxSessionSnapshot?,
  persistedSession: E2BSandboxSessionSnapshot?,
): SandboxSessionInfoSource = when {
  activeSession != null && persistedSession != null -> SandboxSessionInfoSource.ACTIVE_AND_PERSISTED
  activeSession != null -> SandboxSessionInfoSource.ACTIVE_MEMORY
  persistedSession != null -> SandboxSessionInfoSource.PERSISTED
  else -> SandboxSessionInfoSource.NONE
}

private fun latestPreviewSnapshot(
  sameSandboxSnapshots: List<E2BSandboxSessionSnapshot>,
): E2BSandboxSessionSnapshot? = sameSandboxSnapshots.maxByOrNull { session ->
  session.lastPreviewOpenedAtEpochMs ?: Long.MIN_VALUE
}

private fun shouldAutoReclaimSession(
  state: SandboxSettingsState,
): Boolean = SandboxSessionMode.fromWireValue(state.sessionMode) == SandboxSessionMode.STICKY &&
  SandboxTimeoutAction.fromWireValue(state.timeoutAction) == SandboxTimeoutAction.KILL

private fun shouldAutoProbePreview(
  state: SandboxSettingsState,
  now: Long,
  previewSnapshot: E2BSandboxSessionSnapshot,
): Boolean {
  val openedAtEpochMs = previewSnapshot.lastPreviewOpenedAtEpochMs ?: return false
  if (now - openedAtEpochMs > previewAutoProbeWindowMs(state)) {
    return false
  }
  val observedAtEpochMs = previewSnapshot.lastPreviewProbeObservedAtEpochMs
    ?: return true
  return now - observedAtEpochMs >= E2B_PREVIEW_AUTO_PROBE_INTERVAL_MS
}

private fun recommendedRefreshAfterMs(
  state: SandboxSettingsState,
  now: Long,
  runningRequestIds: List<String>,
  sessionStaleAfterEpochMs: Long?,
  previewSnapshot: E2BSandboxSessionSnapshot?,
  lifecycleStatus: SandboxSessionLifecycleStatus,
): Long? {
  if (lifecycleStatus == SandboxSessionLifecycleStatus.NONE ||
    lifecycleStatus == SandboxSessionLifecycleStatus.RECLAIMED
  ) {
    return null
  }
  val delays = mutableListOf<Long>()
  previewAutoRefreshDelayMs(
    state = state,
    now = now,
    previewSnapshot = previewSnapshot,
  )?.let(delays::add)
  if (runningRequestIds.isEmpty() &&
    shouldAutoReclaimSession(state) &&
    sessionStaleAfterEpochMs != null &&
    sessionStaleAfterEpochMs > now
  ) {
    delays += (sessionStaleAfterEpochMs - now).coerceAtLeast(MIN_SANDBOX_SESSION_REFRESH_DELAY_MS)
  }
  return delays.minOrNull()
}

private fun previewAutoRefreshDelayMs(
  state: SandboxSettingsState,
  now: Long,
  previewSnapshot: E2BSandboxSessionSnapshot?,
): Long? {
  val latestPreviewSnapshot = previewSnapshot ?: return null
  if (latestPreviewSnapshot.lastPreviewUrl.isNullOrBlank()) {
    return null
  }
  val openedAtEpochMs = latestPreviewSnapshot.lastPreviewOpenedAtEpochMs ?: return null
  if (now - openedAtEpochMs > previewAutoProbeWindowMs(state)) {
    return null
  }
  val observedAtEpochMs = latestPreviewSnapshot.lastPreviewProbeObservedAtEpochMs ?: return MIN_SANDBOX_SESSION_REFRESH_DELAY_MS
  val remainingDelayMs = E2B_PREVIEW_AUTO_PROBE_INTERVAL_MS - (now - observedAtEpochMs)
  return remainingDelayMs.coerceAtLeast(MIN_SANDBOX_SESSION_REFRESH_DELAY_MS)
}

private fun previewAutoProbeWindowMs(
  state: SandboxSettingsState,
): Long = (state.idleTimeoutMinutes * 60_000L)
  .coerceIn(
    E2B_PREVIEW_AUTO_PROBE_MIN_WINDOW_MS,
    E2B_PREVIEW_AUTO_PROBE_MAX_WINDOW_MS,
  )

private const val E2B_SESSION_STALE_GRACE_MS: Long = 2 * 60_000L
private const val E2B_PREVIEW_AUTO_PROBE_INTERVAL_MS: Long = 45_000L
private const val E2B_PREVIEW_AUTO_PROBE_MIN_WINDOW_MS: Long = 5 * 60_000L
private const val E2B_PREVIEW_AUTO_PROBE_MAX_WINDOW_MS: Long = 30 * 60_000L
private const val MIN_SANDBOX_SESSION_REFRESH_DELAY_MS: Long = 1_000L
