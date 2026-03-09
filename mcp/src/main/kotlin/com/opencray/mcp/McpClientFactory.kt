package com.opencray.mcp

import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.persistence.security.CredentialRef

enum class McpToolExposure {
  ACTIVE,
  BLOCKED,
}

enum class McpClientBlockReason {
  DISABLED,
  REQUIRES_MANUAL_ENABLE,
}

data class McpClientAuthDescriptor(
  val status: McpServerAuthStatus,
  val isReady: Boolean,
  val credentialRef: CredentialRef? = null,
  val headerName: String? = null,
  val errorCode: String? = null,
)

sealed class McpClientTransportDescriptor {
  data class LocalStdio(
    val command: String,
    val args: List<String>,
    val environmentKeys: List<String>,
    val workingDirectory: String?,
    val startupTimeoutMs: Long,
  ) : McpClientTransportDescriptor()

  data class RemoteHttp(
    val url: String,
    val headerNames: List<String>,
    val protocolVersion: String,
    val requestTimeoutMs: Long,
  ) : McpClientTransportDescriptor()

  data class RemoteSse(
    val eventsUrl: String,
    val postUrl: String,
    val headerNames: List<String>,
    val protocolVersion: String,
    val requestTimeoutMs: Long,
  ) : McpClientTransportDescriptor()
}

data class McpClientDescriptor(
  val id: String,
  val displayName: String,
  val transport: McpClientTransportDescriptor,
  val auth: McpClientAuthDescriptor,
  val declaredTrustState: McpServerTrustState,
  val trustState: McpServerTrustState,
  val manuallyEnabled: Boolean,
  val toolExposure: McpToolExposure = McpToolExposure.ACTIVE,
  val registeredAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

data class McpBlockedClientDescriptor(
  val id: String,
  val displayName: String,
  val transport: McpClientTransportDescriptor,
  val auth: McpClientAuthDescriptor,
  val declaredTrustState: McpServerTrustState,
  val trustState: McpServerTrustState,
  val manuallyEnabled: Boolean,
  val blockReason: McpClientBlockReason,
  val toolExposure: McpToolExposure = McpToolExposure.BLOCKED,
  val registeredAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

data class McpClientExposureReport(
  val activeClients: List<McpClientDescriptor>,
  val blockedClients: List<McpBlockedClientDescriptor>,
) {
  fun findActiveClient(id: String): McpClientDescriptor? = activeClients.firstOrNull { it.id == id }

  fun findBlockedClient(id: String): McpBlockedClientDescriptor? = blockedClients.firstOrNull { it.id == id }

  fun toolExposure(id: String): McpToolExposure =
    findActiveClient(id)?.toolExposure
      ?: findBlockedClient(id)?.toolExposure
      ?: McpToolExposure.BLOCKED
}

class McpClientFactory {
  fun load(record: McpRegistryRecord): McpClientExposureReport {
    val activeClients = mutableListOf<McpClientDescriptor>()
    val blockedClients = mutableListOf<McpBlockedClientDescriptor>()

    record.servers.sortedBy { it.id }.forEach { server ->
      when (server.trustState) {
        McpServerTrustState.ENABLED -> activeClients += server.toActiveClientDescriptor()
        McpServerTrustState.DISABLED -> blockedClients += server.toBlockedClientDescriptor(
          blockReason = McpClientBlockReason.DISABLED,
        )

        McpServerTrustState.REQUIRES_MANUAL_ENABLE -> blockedClients += server.toBlockedClientDescriptor(
          blockReason = McpClientBlockReason.REQUIRES_MANUAL_ENABLE,
        )
      }
    }

    return McpClientExposureReport(
      activeClients = activeClients,
      blockedClients = blockedClients,
    )
  }

  fun load(registry: McpRegistry): McpClientExposureReport = load(registry.snapshot())
}

private fun McpRegistryServerRecord.toActiveClientDescriptor(): McpClientDescriptor = McpClientDescriptor(
  id = id,
  displayName = spec.displayName,
  transport = spec.transport.toClientTransportDescriptor(),
  auth = authState.toClientAuthDescriptor(),
  declaredTrustState = declaredTrustState,
  trustState = trustState,
  manuallyEnabled = manuallyEnabled,
  registeredAtEpochMs = registeredAtEpochMs,
  updatedAtEpochMs = updatedAtEpochMs,
)

private fun McpRegistryServerRecord.toBlockedClientDescriptor(
  blockReason: McpClientBlockReason,
): McpBlockedClientDescriptor = McpBlockedClientDescriptor(
  id = id,
  displayName = spec.displayName,
  transport = spec.transport.toClientTransportDescriptor(),
  auth = authState.toClientAuthDescriptor(),
  declaredTrustState = declaredTrustState,
  trustState = trustState,
  manuallyEnabled = manuallyEnabled,
  blockReason = blockReason,
  registeredAtEpochMs = registeredAtEpochMs,
  updatedAtEpochMs = updatedAtEpochMs,
)

private fun McpServerAuthState.toClientAuthDescriptor(): McpClientAuthDescriptor = McpClientAuthDescriptor(
  status = status,
  isReady = status == McpServerAuthStatus.NOT_REQUIRED || status == McpServerAuthStatus.CONFIGURED,
  credentialRef = credentialRef,
  headerName = headerName,
  errorCode = errorCode,
)

private fun McpTransportDescriptor.toClientTransportDescriptor(): McpClientTransportDescriptor = when (this) {
  is McpTransportDescriptor.LocalStdio -> McpClientTransportDescriptor.LocalStdio(
    command = command,
    args = args,
    environmentKeys = environment.keys.sorted(),
    workingDirectory = workingDirectory,
    startupTimeoutMs = startupTimeoutMs,
  )

  is McpTransportDescriptor.RemoteHttp -> McpClientTransportDescriptor.RemoteHttp(
    url = url,
    headerNames = headers.keys.sorted(),
    protocolVersion = protocolVersion,
    requestTimeoutMs = requestTimeoutMs,
  )

  is McpTransportDescriptor.RemoteSse -> McpClientTransportDescriptor.RemoteSse(
    eventsUrl = eventsUrl,
    postUrl = postUrl,
    headerNames = headers.keys.sorted(),
    protocolVersion = protocolVersion,
    requestTimeoutMs = requestTimeoutMs,
  )
}

// Learning: Safe client descriptors stay easy to test when transport output keeps only structural fields.
// Issue: Runtime connection wiring is intentionally deferred, so descriptors do not yet carry executable network/env values.
