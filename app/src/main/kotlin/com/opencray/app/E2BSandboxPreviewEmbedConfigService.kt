package com.opencray.app

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

internal data class SandboxPreviewEmbedConfig(
  val previewUrl: String,
  val providerId: String,
  val headers: Map<String, String> = emptyMap(),
  val sessionMatched: Boolean,
  val accessTokenConfigured: Boolean,
  val unavailableReason: String? = null,
) {
  fun toMap(): Map<String, Any?> = buildMap {
    put("previewUrl", previewUrl)
    put("providerId", providerId)
    put("headers", headers)
    put("sessionMatched", sessionMatched)
    put("accessTokenConfigured", accessTokenConfigured)
    unavailableReason?.trim()?.takeIf(String::isNotBlank)?.let { reason ->
      put("unavailableReason", reason)
    }
  }
}

internal interface SandboxPreviewEmbedConfigService {
  fun resolve(
    previewUrl: String,
    workspaceRoot: Path,
  ): SandboxPreviewEmbedConfig
}

internal class E2BSandboxPreviewEmbedConfigService(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot? = { null },
) : SandboxPreviewEmbedConfigService {
  override fun resolve(
    previewUrl: String,
    workspaceRoot: Path,
  ): SandboxPreviewEmbedConfig {
    val normalizedUrl = previewUrl.trim()
    if (normalizedUrl.isEmpty()) {
      return unavailableConfig(
        previewUrl = normalizedUrl,
        reason = "Sandbox preview embedding is unavailable because the preview URL is empty.",
      )
    }
    val settings = settingsProvider()
    if (!settings.canUseSandbox()) {
      return unavailableConfig(
        previewUrl = normalizedUrl,
        providerId = settings.state.providerId.trim(),
        reason = "Sandbox preview embedding is unavailable because the cloud sandbox backend is not configured.",
      )
    }
    val providerId = SandboxProviderId.fromWireValue(settings.state.providerId)
      ?: return unavailableConfig(
        previewUrl = normalizedUrl,
        providerId = settings.state.providerId.trim(),
        reason = "Sandbox preview embedding is unavailable because the sandbox provider is not recognized.",
      )
    if (providerId != SandboxProviderId.E2B) {
      return unavailableConfig(
        previewUrl = normalizedUrl,
        providerId = providerId.wireValue,
        reason = "Sandbox preview embedding is only implemented for E2B right now.",
      )
    }
    val previewUri = runCatching { URI(normalizedUrl) }.getOrNull()
      ?: return unavailableConfig(
        previewUrl = normalizedUrl,
        providerId = providerId.wireValue,
        reason = "Sandbox preview embedding is unavailable because the preview URL is invalid.",
      )
    val requestedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val matchingSessions = listOfNotNull(activeSessionProvider(), sessionStore.load())
      .filter { session ->
        normalizeWorkspaceRoot(session.workspaceRoot) == requestedWorkspaceRoot
      }
      .filter { session ->
        sessionMatchesPreviewUrl(
          session = session,
          normalizedPreviewUrl = normalizedUrl,
          previewUri = previewUri,
        )
      }
    val resolvedSession = mergeMatchingSessions(
      matchingSessions = matchingSessions,
      normalizedPreviewUrl = normalizedUrl,
    ) ?: return unavailableConfig(
      previewUrl = normalizedUrl,
      providerId = providerId.wireValue,
      reason = "Sandbox preview embedding is unavailable because the preview URL does not match the current cloud sandbox session.",
    )
    val trafficAccessToken = resolvedSession.trafficAccessToken
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val headers = buildMap<String, String> {
      trafficAccessToken?.let { token ->
        put(E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME, token)
      }
    }
    return SandboxPreviewEmbedConfig(
      previewUrl = normalizedUrl,
      providerId = providerId.wireValue,
      headers = headers,
      sessionMatched = true,
      accessTokenConfigured = trafficAccessToken != null,
    )
  }
}

private fun mergeMatchingSessions(
  matchingSessions: List<E2BSandboxSessionSnapshot>,
  normalizedPreviewUrl: String,
): E2BSandboxSessionSnapshot? {
  val preferred = matchingSessions.maxByOrNull { session ->
    previewMatchScore(session, normalizedPreviewUrl)
  } ?: return null
  return matchingSessions.fold(preferred) { current, candidate ->
    if (candidate.sandboxId != current.sandboxId) {
      current
    } else {
      current.copy(
        envdAccessToken = current.envdAccessToken ?: candidate.envdAccessToken,
        trafficAccessToken = current.trafficAccessToken ?: candidate.trafficAccessToken,
        remoteWorkspaceRoot = current.remoteWorkspaceRoot ?: candidate.remoteWorkspaceRoot,
      )
    }
  }
}

private fun previewMatchScore(
  session: E2BSandboxSessionSnapshot,
  normalizedPreviewUrl: String,
): Long {
  val exactUrlBonus = if (
    session.lastPreviewUrl
      ?.trim()
      ?.equals(normalizedPreviewUrl, ignoreCase = true) == true
  ) {
    1_000_000_000_000L
  } else {
    0L
  }
  return exactUrlBonus + session.updatedAtEpochMs
}

private fun sessionMatchesPreviewUrl(
  session: E2BSandboxSessionSnapshot,
  normalizedPreviewUrl: String,
  previewUri: URI,
): Boolean {
  if (
    session.lastPreviewUrl
      ?.trim()
      ?.equals(normalizedPreviewUrl, ignoreCase = true) == true
  ) {
    return true
  }
  val host = previewUri.host
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotBlank)
    ?: return false
  val sandboxHost = "${session.sandboxId}.${session.sandboxDomain}"
    .trim()
    .lowercase()
  return host == sandboxHost || host.endsWith("-$sandboxHost")
}

private fun unavailableConfig(
  previewUrl: String,
  providerId: String = "",
  reason: String,
): SandboxPreviewEmbedConfig = SandboxPreviewEmbedConfig(
  previewUrl = previewUrl,
  providerId = providerId,
  sessionMatched = false,
  accessTokenConfigured = false,
  unavailableReason = reason,
)

private fun normalizeWorkspaceRoot(rawWorkspaceRoot: String): Path =
  Paths.get(rawWorkspaceRoot).toAbsolutePath().normalize()
