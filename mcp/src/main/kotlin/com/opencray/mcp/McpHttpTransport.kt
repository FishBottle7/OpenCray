package com.opencray.mcp

import com.opencray.persistence.security.CredentialRef
import kotlinx.serialization.json.JsonObject

/** Resolves a credential reference to its secret value for header injection. */
fun interface McpCredentialResolver {
  fun resolve(ref: CredentialRef): String?
}

object McpNoCredentialResolver : McpCredentialResolver {
  override fun resolve(ref: CredentialRef): String? = null
}

/**
 * Streamable-HTTP transport for one MCP server endpoint.
 *
 * Handles the client half of MCP 2025-11-05 streamable HTTP: every call is a
 * single POST that the server answers either with a JSON body or with an SSE
 * stream whose first response frame (matched by id) carries the reply.
 * Session and protocol-version headers are tracked here so callers never
 * touch raw HTTP.
 *
 * Learning: Credential values exist only inside sendRequest; nothing here logs
 * or persists header values.
 */
class McpHttpTransport(
  private val endpoint: McpHttpEndpoint,
) {
  internal var sessionId: String? = null
    private set

  internal var protocolVersion: String = MCP_PROTOCOL_VERSION
    private set

  fun initialize(
    url: String,
    requestId: Long,
    clientInfo: McpClientInfo,
    auth: McpHttpAuth?,
    requestTimeoutMs: Long,
  ): McpJsonRpcResponse = request(
    method = McpProtocol.METHOD_INITIALIZE,
    params = McpProtocol.initializeParams(clientInfo),
    requestId = requestId,
    url = url,
    auth = auth,
    requestTimeoutMs = requestTimeoutMs,
    includeProtocolHeader = false,
  ).also { response ->
    val negotiated = response.result?.let(McpProtocol::decodeInitializeResult)?.protocolVersion
    if (!negotiated.isNullOrBlank()) {
      protocolVersion = negotiated
    }
  }

  fun sendNotification(
    url: String,
    method: String,
    auth: McpHttpAuth?,
    requestTimeoutMs: Long,
  ) {
    val headers = mutableMapOf<String, String>()
    auth?.let { value -> headers[value.headerName] = value.secret }
    headers[CONTENT_TYPE_HEADER] = JSON_CONTENT_TYPE
    headers[ACCEPT_HEADER] = SSE_ACCEPT
    sessionIdHeader()?.let { headers[SESSION_ID_HEADER] = it }
    endpoint.postJson(
      url = url,
      headers = headers,
      body = McpJsonRpc.encodeNotification(McpJsonRpc.notification(method)),
      timeoutMs = requestTimeoutMs,
    )
  }

  fun call(
    url: String,
    requestId: Long,
    method: String,
    params: JsonObject?,
    auth: McpHttpAuth?,
    requestTimeoutMs: Long,
  ): McpJsonRpcResponse = request(
    method = method,
    params = params,
    requestId = requestId,
    url = url,
    auth = auth,
    requestTimeoutMs = requestTimeoutMs,
    includeProtocolHeader = true,
  )

  fun close() {
    sessionId = null
    protocolVersion = MCP_PROTOCOL_VERSION
  }

  private fun request(
    method: String,
    params: JsonObject?,
    requestId: Long,
    url: String,
    auth: McpHttpAuth?,
    requestTimeoutMs: Long,
    includeProtocolHeader: Boolean,
  ): McpJsonRpcResponse {
    val headers = mutableMapOf<String, String>()
    auth?.let { value -> headers[value.headerName] = value.secret }
    headers[CONTENT_TYPE_HEADER] = JSON_CONTENT_TYPE
    headers[ACCEPT_HEADER] = SSE_ACCEPT
    if (includeProtocolHeader) {
      headers[PROTOCOL_VERSION_HEADER] = protocolVersion
    }
    sessionIdHeader()?.let { headers[SESSION_ID_HEADER] = it }

    val result = endpoint.postJson(
      url = url,
      headers = headers,
      body = McpJsonRpc.encodeRequest(McpJsonRpc.request(id = requestId, method = method, params = params)),
      timeoutMs = requestTimeoutMs,
    )

    if (result.statusCode !in 200..299) {
      throw McpHttpException(
        statusCode = result.statusCode,
        message = "MCP server returned HTTP ${result.statusCode} for '$method'.",
      )
    }

    result.firstHeader(SESSION_ID_HEADER)?.takeIf(String::isNotBlank)?.let { session ->
      sessionId = session
    }

    return parseResponseBody(result, requestId)
  }

  private fun parseResponseBody(
    result: McpHttpResult,
    requestId: Long,
  ): McpJsonRpcResponse {
    val contentType = result.firstHeader(CONTENT_TYPE_HEADER).orEmpty()
    return if (contentType.contains(SSE_CONTENT_TYPE, ignoreCase = true)) {
      decodeSseResponseFrame(result.body, requestId)
    } else {
      McpJsonRpc.decodeResponse(result.body)
    }
  }

  private fun decodeSseResponseFrame(
    body: String,
    requestId: Long,
  ): McpJsonRpcResponse {
    body.lineSequence().forEach { line ->
      val payload = line.removePrefix(SSE_DATA_PREFIX).trimStart()
      if (line.startsWith(SSE_DATA_PREFIX) && payload.isNotBlank()) {
        val frame = McpJsonRpc.decodeResponse(payload)
        if (frame.id == requestId) {
          return frame
        }
      }
    }
    throw McpHttpException(
      message = "MCP SSE stream closed without a response frame for request $requestId.",
    )
  }

  private fun sessionIdHeader(): String? = sessionId?.takeIf(String::isNotBlank)

  companion object {
    const val SESSION_ID_HEADER: String = "Mcp-Session-Id"
    const val PROTOCOL_VERSION_HEADER: String = "Mcp-Protocol-Version"
    private const val CONTENT_TYPE_HEADER: String = "Content-Type"
    private const val JSON_CONTENT_TYPE: String = "application/json"
    private const val ACCEPT_HEADER: String = "Accept"
    private const val SSE_ACCEPT: String = "application/json, text/event-stream"
    private const val SSE_CONTENT_TYPE: String = "text/event-stream"
    private const val SSE_DATA_PREFIX: String = "data:"
  }
}

/** Header-materialized credential for one request; never persisted or logged. */
data class McpHttpAuth(
  val headerName: String,
  val secret: String,
)
