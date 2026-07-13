package com.opencray.app

import com.opencray.runtime.SandboxPreviewRequest
import com.opencray.runtime.SandboxPreviewProbeStatus
import com.opencray.runtime.SandboxPreviewResult
import com.opencray.runtime.SandboxPreviewService
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Path
import java.nio.file.Paths

internal class E2BSandboxPreviewService(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot? = { null },
  private val activeSessionRecorder: (E2BSandboxSessionSnapshot) -> Unit = { },
  private val probeTransport: SandboxPreviewProbeTransport = UrlConnectionSandboxPreviewProbeTransport(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SandboxPreviewService {
  override fun open(request: SandboxPreviewRequest): SandboxPreviewResult {
    val settings = settingsProvider()
    require(settings.canUseSandbox()) { "Sandbox preview is unavailable because the cloud sandbox backend is not configured." }
    val providerId = SandboxProviderId.fromWireValue(settings.state.providerId)
    require(providerId == SandboxProviderId.E2B) { "Sandbox preview is only implemented for E2B right now." }
    val requestedWorkspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
    val session = resolveSessionForWorkspace(requestedWorkspaceRoot)
    val resolvedPort = resolvePreviewPort(request.port, session)
    val normalizedPath = normalizePreviewPath(request.path)
    val trafficAccessToken = session.trafficAccessToken
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val previewUrl = "https://${resolvedPort}-${session.sandboxId}.${session.sandboxDomain}$normalizedPath"
    val observedAtEpochMs = clock()
    val probeOutcome = probeSandboxPreviewUrl(
      url = previewUrl,
      trafficAccessToken = trafficAccessToken,
      probeTransport = probeTransport,
    )
    persistPreviewLifecycle(
      previousSession = session,
      updatedSession = session.copy(
        lastPreviewUrl = previewUrl,
        lastPreviewPort = resolvedPort,
        lastPreviewPath = normalizedPath.takeIf { it != "/" },
        lastPreviewProbeStatus = probeOutcome.status.wireValue,
        lastPreviewProbeHttpStatusCode = probeOutcome.httpStatusCode,
        lastPreviewProbeMessage = probeOutcome.message,
        lastPreviewOpenedAtEpochMs = observedAtEpochMs,
        lastPreviewProbeObservedAtEpochMs = observedAtEpochMs,
        lastPreviewProbeSource = SandboxPreviewProbeSource.PREVIEW_OPEN,
      ),
    )
    return SandboxPreviewResult(
      url = previewUrl,
      providerId = providerId.wireValue,
      sandboxId = session.sandboxId,
      sandboxDomain = session.sandboxDomain,
      port = resolvedPort,
      path = normalizedPath.takeIf { it != "/" },
      accessHeaderName = trafficAccessToken?.let { TRAFFIC_ACCESS_HEADER_NAME },
      accessTokenConfigured = trafficAccessToken != null,
      probeStatus = probeOutcome.status,
      probeHttpStatusCode = probeOutcome.httpStatusCode,
      probeMessage = probeOutcome.message,
    )
  }

  private fun persistPreviewLifecycle(
    previousSession: E2BSandboxSessionSnapshot,
    updatedSession: E2BSandboxSessionSnapshot,
  ) {
    activeSessionRecorder(updatedSession)
    sessionStore.update { stored ->
      stored?.takeIf { current ->
        current.sandboxId == previousSession.sandboxId &&
          normalizeWorkspaceRoot(current.workspaceRoot) == normalizeWorkspaceRoot(previousSession.workspaceRoot)
      }?.let { current ->
        mergePreviewLifecycle(updatedSession, current)
      } ?: stored
    }
  }

  private fun normalizeWorkspaceRoot(rawWorkspaceRoot: String): Path =
    Paths.get(rawWorkspaceRoot).toAbsolutePath().normalize()

  private fun resolveSessionForWorkspace(
    requestedWorkspaceRoot: Path,
  ): E2BSandboxSessionSnapshot {
    val sessions = listOfNotNull(activeSessionProvider(), sessionStore.load())
    sessions.firstOrNull { session ->
      normalizeWorkspaceRoot(session.workspaceRoot) == requestedWorkspaceRoot
    }?.let { return it }
    if (sessions.isNotEmpty()) {
      throw IllegalArgumentException("The active E2B sandbox session belongs to a different workspace.")
    }
    error("No active reusable E2B sandbox session is available for preview.")
  }

  private fun resolvePreviewPort(
    requestedPort: Int?,
    session: E2BSandboxSessionSnapshot,
  ): Int {
    requestedPort?.let { port ->
      require(port in 1..65_535) { "Sandbox preview port must be between 1 and 65535." }
      return port
    }
    val candidatePorts = session.previewCandidatePorts
      .filter { it in 1..65_535 }
      .distinct()
      .sorted()
    return when (candidatePorts.size) {
      0 -> throw IllegalArgumentException(
        "Sandbox preview port is required because no candidate preview port has been discovered for the active sandbox session yet.",
      )

      1 -> candidatePorts.first()

      else -> throw IllegalArgumentException(
        "Sandbox preview port is required because multiple candidate ports are available: ${candidatePorts.joinToString(separator = ", ")}.",
      )
    }
  }

  private fun normalizePreviewPath(rawPath: String?): String {
    val normalized = rawPath
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "/"
    return if (normalized.startsWith("/")) normalized else "/$normalized"
  }

  companion object {
    const val TRAFFIC_ACCESS_HEADER_NAME: String = "E2B-Traffic-Access-Token"
  }
}

internal fun mergePreviewLifecycle(
  primary: E2BSandboxSessionSnapshot,
  secondary: E2BSandboxSessionSnapshot,
): E2BSandboxSessionSnapshot {
  val primaryOpenedAt = primary.lastPreviewOpenedAtEpochMs ?: Long.MIN_VALUE
  val secondaryOpenedAt = secondary.lastPreviewOpenedAtEpochMs ?: Long.MIN_VALUE
  val previewSource = if (secondaryOpenedAt > primaryOpenedAt) secondary else primary
  return primary.copy(
    remoteWorkspaceRoot = primary.remoteWorkspaceRoot
      ?.takeIf(String::isNotBlank)
      ?: secondary.remoteWorkspaceRoot,
    lastPreviewUrl = previewSource.lastPreviewUrl,
    lastPreviewPort = previewSource.lastPreviewPort,
    lastPreviewPath = previewSource.lastPreviewPath,
    lastPreviewProbeStatus = previewSource.lastPreviewProbeStatus,
    lastPreviewProbeHttpStatusCode = previewSource.lastPreviewProbeHttpStatusCode,
    lastPreviewProbeMessage = previewSource.lastPreviewProbeMessage,
    lastPreviewOpenedAtEpochMs = previewSource.lastPreviewOpenedAtEpochMs,
    lastPreviewProbeObservedAtEpochMs = previewSource.lastPreviewProbeObservedAtEpochMs,
    lastPreviewProbeSource = previewSource.lastPreviewProbeSource,
  )
}

internal object SandboxPreviewProbeSource {
  const val PREVIEW_OPEN: String = "preview_open"
  const val SESSION_INFO_AUTO: String = "session_info_auto"
}

internal data class SandboxPreviewProbeRequest(
  val method: String = "HEAD",
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val connectTimeoutMs: Int,
  val readTimeoutMs: Int,
)

internal data class SandboxPreviewProbeResponse(
  val statusCode: Int,
)

internal fun interface SandboxPreviewProbeTransport {
  fun probe(request: SandboxPreviewProbeRequest): SandboxPreviewProbeResponse
}

internal class UrlConnectionSandboxPreviewProbeTransport : SandboxPreviewProbeTransport {
  override fun probe(request: SandboxPreviewProbeRequest): SandboxPreviewProbeResponse {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      connectTimeout = request.connectTimeoutMs
      readTimeout = request.readTimeoutMs
      instanceFollowRedirects = true
      doInput = true
      useCaches = false
      request.headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      SandboxPreviewProbeResponse(statusCode = connection.responseCode)
    } finally {
      connection.disconnect()
    }
  }
}

internal data class PreviewProbeOutcome(
  val status: SandboxPreviewProbeStatus,
  val httpStatusCode: Int? = null,
  val message: String? = null,
)

internal fun probeSandboxPreviewUrl(
  url: String,
  trafficAccessToken: String?,
  probeTransport: SandboxPreviewProbeTransport,
): PreviewProbeOutcome {
  val headers = buildMap<String, String> {
    trafficAccessToken?.let { put(E2BSandboxPreviewService.TRAFFIC_ACCESS_HEADER_NAME, it) }
  }
  return runCatching {
    probeTransport.probe(
      SandboxPreviewProbeRequest(
        method = "HEAD",
        url = url,
        headers = headers,
        connectTimeoutMs = DEFAULT_SANDBOX_PREVIEW_PROBE_CONNECT_TIMEOUT_MS,
        readTimeoutMs = DEFAULT_SANDBOX_PREVIEW_PROBE_READ_TIMEOUT_MS,
      ),
    )
  }.fold(
    onSuccess = { response ->
      when (response.statusCode) {
        in 200..399 -> PreviewProbeOutcome(
          status = SandboxPreviewProbeStatus.READY,
          httpStatusCode = response.statusCode,
        )

        else -> PreviewProbeOutcome(
          status = SandboxPreviewProbeStatus.REACHABLE,
          httpStatusCode = response.statusCode,
          message = sandboxPreviewProbeMessageForStatus(response.statusCode),
        )
      }
    },
    onFailure = { error ->
      PreviewProbeOutcome(
        status = SandboxPreviewProbeStatus.UNREACHABLE,
        message = sandboxPreviewProbeFailureMessage(error),
      )
    },
  )
}

private fun sandboxPreviewProbeMessageForStatus(statusCode: Int): String = when (statusCode) {
  401,
  403,
  -> "Preview endpoint responded with HTTP $statusCode. Sandbox access control may be rejecting the request."

  404 -> "Preview endpoint responded with HTTP 404. The sandbox service is reachable, but the requested path was not found."
  502,
  503,
  504,
  -> "Preview endpoint responded with HTTP $statusCode. The sandbox service may still be starting."

  else -> "Preview endpoint responded with HTTP $statusCode."
}

private fun sandboxPreviewProbeFailureMessage(error: Throwable): String = when (error) {
  is SocketTimeoutException -> "Preview endpoint did not respond before the probe timeout."
  is ConnectException -> "Preview endpoint refused the connection. The sandbox service may not be listening on that port yet."
  is UnknownHostException -> "Preview endpoint hostname could not be resolved."
  else -> error.message
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: "Preview endpoint could not be reached."
}

private const val DEFAULT_SANDBOX_PREVIEW_PROBE_CONNECT_TIMEOUT_MS: Int = 2_000
private const val DEFAULT_SANDBOX_PREVIEW_PROBE_READ_TIMEOUT_MS: Int = 2_000
