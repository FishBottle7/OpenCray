package com.opencray.mcp

import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import kotlinx.serialization.json.JsonObject

/** Result of a successful connect(): identity plus the first tools page. */
data class McpConnectionSnapshot(
  val serverId: String,
  val serverInfo: McpServerInfo,
  val protocolVersion: String,
  val sessionId: String?,
  val discoveredTools: List<McpToolDescriptor>,
)

/** Factory-level rejection before any network traffic. */
class McpConnectionRejectedException(
  override val message: String,
  val reason: String,
) : Exception(message)

enum class McpConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
}

/**
 * Lifecycle owner for one MCP server connection built from a registry record.
 *
 * Rejections are fail-closed: a server that is not ENABLED, or whose auth is
 * not ready, never reaches the network. Credentials resolve from references
 * at call time and are only materialized inside the transport.
 *
 * Learning: Pure Kotlin — no Android Context, mirroring McpRegistry so the
 * app layer wires the resolver and endpoint via fromContext-style factories.
 */
class McpConnectionManager(
  private val serverId: String,
  private val transportFactory: (McpHttpEndpoint) -> McpHttpTransport = ::McpHttpTransport,
  private val credentialResolver: McpCredentialResolver = McpNoCredentialResolver,
  private val requestTimeoutMs: Long = 30_000L,
  private val nextRequestId: () -> Long,
) {
  private var transport: McpHttpTransport? = null
  private var serverInfo: McpServerInfo? = null
  private var protocolVersion: String = MCP_PROTOCOL_VERSION
  private var url: String? = null
  private var auth: McpHttpAuth? = null
  private var connectedTimeoutMs: Long = requestTimeoutMs

  var state: McpConnectionState = McpConnectionState.DISCONNECTED
    private set

  fun connect(
    record: McpRegistryServerRecord,
    endpoint: McpHttpEndpoint,
    clientInfo: McpClientInfo = DEFAULT_CLIENT_INFO,
  ): McpConnectionSnapshot {
    check(state != McpConnectionState.CONNECTED) {
      "MCP connection '$serverId' is already connected."
    }
    require(record.id == serverId) {
      "Record id '${record.id}' does not match connection '$serverId'."
    }

    if (record.trustState != McpServerTrustState.ENABLED) {
      throw McpConnectionRejectedException(
        message = "MCP server '$serverId' is not enabled; refusing to connect.",
        reason = "trust_state_${record.trustState.name.lowercase()}",
      )
    }
    val descriptor = record.spec.transport as? McpTransportDescriptor.RemoteHttp
      ?: throw McpConnectionRejectedException(
        message = "MCP server '$serverId' uses transport " +
          "'${record.spec.transport::class.simpleName}' which has no runtime bridge yet.",
        reason = "transport_unsupported",
      )
    val resolvedAuth = resolveAuth(record)
    val activeTransport = transportFactory(endpoint)

    state = McpConnectionState.CONNECTING
    try {
      val initializeResponse = activeTransport.initialize(
        url = descriptor.url,
        requestId = nextRequestId(),
        clientInfo = clientInfo,
        auth = resolvedAuth,
        requestTimeoutMs = descriptor.requestTimeoutMs,
      )
      initializeResponse.error?.let { error ->
        throw McpHttpException(
          message = "MCP initialize failed for '$serverId': ${error.message} (${error.code}).",
        )
      }
      val initializeResult = McpProtocol.decodeInitializeResult(
        initializeResponse.result ?: JsonObject(emptyMap()),
      )
      activeTransport.sendNotification(
        url = descriptor.url,
        method = McpProtocol.NOTIFICATION_INITIALIZED,
        auth = resolvedAuth,
        requestTimeoutMs = descriptor.requestTimeoutMs,
      )
      val tools = listToolsOn(
        activeTransport,
        descriptor.url,
        resolvedAuth,
        descriptor.requestTimeoutMs,
      )
      transport = activeTransport
      serverInfo = initializeResult.serverInfo
      protocolVersion = activeTransport.protocolVersion
      url = descriptor.url
      auth = resolvedAuth
      connectedTimeoutMs = descriptor.requestTimeoutMs
      state = McpConnectionState.CONNECTED
      return McpConnectionSnapshot(
        serverId = serverId,
        serverInfo = initializeResult.serverInfo,
        protocolVersion = activeTransport.protocolVersion,
        sessionId = activeTransport.sessionId,
        discoveredTools = tools,
      )
    } catch (error: Exception) {
      activeTransport.close()
      state = McpConnectionState.DISCONNECTED
      throw error
    }
  }

  fun listTools(): List<McpToolDescriptor> {
    val connectedTransport = requireTransport()
    return listToolsOn(
      connectedTransport,
      requireNotNull(url) { "Connection '$serverId' has no endpoint url." },
      auth,
      connectedTimeoutMs,
    )
  }

  fun callTool(
    toolName: String,
    arguments: JsonObject?,
  ): McpToolCallResult {
    val response = requireTransport().call(
      url = requireNotNull(url) { "Connection '$serverId' has no endpoint url." },
      requestId = nextRequestId(),
      method = McpProtocol.METHOD_TOOLS_CALL,
      params = McpProtocol.toolsCallParams(toolName, arguments),
      auth = auth,
      requestTimeoutMs = connectedTimeoutMs,
    )
    response.error?.let { error ->
      throw McpHttpException(
        message = "MCP tools/call '$toolName' failed: ${error.message} (${error.code}).",
      )
    }
    return McpProtocol.decodeToolCallResult(response.result ?: JsonObject(emptyMap()))
  }

  fun close() {
    transport?.close()
    transport = null
    url = null
    auth = null
    serverInfo = null
    protocolVersion = MCP_PROTOCOL_VERSION
    connectedTimeoutMs = requestTimeoutMs
    state = McpConnectionState.DISCONNECTED
  }

  private fun listToolsOn(
    activeTransport: McpHttpTransport,
    url: String,
    auth: McpHttpAuth?,
    timeoutMs: Long,
  ): List<McpToolDescriptor> {
    var cursor: String? = null
    val tools = mutableListOf<McpToolDescriptor>()
    do {
      val response = activeTransport.call(
        url = url,
        requestId = nextRequestId(),
        method = McpProtocol.METHOD_TOOLS_LIST,
        params = McpProtocol.toolsListParams(cursor),
        auth = auth,
        requestTimeoutMs = timeoutMs,
      )
      response.error?.let { error ->
        throw McpHttpException(
          message = "MCP tools/list failed for '$serverId': ${error.message} (${error.code}).",
        )
      }
      val page = McpProtocol.decodeToolsListResult(response.result ?: JsonObject(emptyMap()))
      tools += page.tools
      cursor = page.nextCursor
    } while (cursor != null)
    return tools
  }

  private fun requireTransport(): McpHttpTransport = transport
    ?: throw IllegalStateException("MCP connection '$serverId' is not connected.")

  private fun resolveAuth(record: McpRegistryServerRecord): McpHttpAuth? {
    val authState = record.authState
    return when (authState.status) {
      McpServerAuthStatus.NOT_REQUIRED -> null

      McpServerAuthStatus.CONFIGURED -> {
        val ref = requireNotNull(authState.credentialRef) {
          "MCP server '$serverId' auth status is CONFIGURED but has no credentialRef."
        }
        val secret = credentialResolver.resolve(ref)
        if (secret.isNullOrBlank()) {
          throw McpConnectionRejectedException(
            message = "MCP server '$serverId' credential '${ref.uri}' could not be resolved.",
            reason = "credential_unresolved",
          )
        }
        McpHttpAuth(
          headerName = authState.headerName ?: "Authorization",
          secret = secret,
        )
      }

      McpServerAuthStatus.MISSING -> throw McpConnectionRejectedException(
        message = "MCP server '$serverId' auth is not configured; refusing to connect.",
        reason = "credential_missing",
      )

      McpServerAuthStatus.ERROR -> throw McpConnectionRejectedException(
        message = "MCP server '$serverId' auth is in error state " +
          "'${authState.errorCode ?: "unknown"}'; refusing to connect.",
        reason = "credential_error",
      )
    }
  }

  companion object {
    val DEFAULT_CLIENT_INFO: McpClientInfo = McpClientInfo(
      name = "OpenCray",
      version = "1.0",
    )
  }
}
