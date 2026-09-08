package com.opencray.mcp

import com.opencray.core.contracts.McpTransportDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpTransportTest {

  private fun newTransport(endpoint: McpHttpEndpoint): McpHttpTransport =
    McpHttpTransport(endpoint = endpoint)

  private fun scriptedEndpoint(
    respond: (McpHttpResult) -> Unit,
    capture: (url: String, headers: Map<String, String>, body: String) -> Unit = { _, _, _ -> },
  ): McpHttpEndpoint {
    var result: McpHttpResult? = null
    respond.invoke(result ?: McpHttpResult(200, emptyMap(), "{}"))
    return object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult {
        capture(url, headers, body)
        return result ?: McpHttpResult(200, emptyMap(), "{}")
      }
    }
  }

  @Test
  fun Initialize_sendsProtocolHeaderAbsentAndParsesSessionHeader() {
    var capturedHeaders: Map<String, String>? = null
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult {
        capturedHeaders = headers
        return McpTestFixtures.jsonResponse(
          requestId = 1L,
          resultJson = McpTestFixtures.initializeResultJson(),
          extraHeaders = mapOf(
            "Content-Type" to listOf("application/json"),
            "Mcp-Session-Id" to listOf("session-9"),
          ),
        )
      }
    }

    val transport = newTransport(endpoint)
    val response = transport.initialize(
      url = "https://docs.example.test/mcp",
      requestId = 1L,
      clientInfo = McpConnectionManager.DEFAULT_CLIENT_INFO,
      auth = null,
      requestTimeoutMs = 30_000L,
    )

    assertEquals(McpTestFixtures.initializeResultJson().replace(" ", ""), response.result.toString().replace(" ", ""))
    val headers = requireNotNull(capturedHeaders)
    // Initialize must NOT carry a session id (none granted yet) and must NOT
    // send the protocol-version header (that is the negotiation itself).
    assertEquals(null, headers[McpHttpTransport.SESSION_ID_HEADER])
    assertEquals(null, headers[McpHttpTransport.PROTOCOL_VERSION_HEADER])
    // The session id granted by the server is captured for later calls.
    assertEquals("session-9", transport.sessionId)
  }

  @Test
  fun Call_sendsSessionAndProtocolHeadersOnSubsequentRequests() {
    val bodies = mutableListOf<Map<String, String>>()
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult {
        bodies += headers
        return when {
          bodies.size == 1 -> McpTestFixtures.jsonResponse(
            requestId = 1L,
            resultJson = McpTestFixtures.initializeResultJson(),
            extraHeaders = mapOf(
              "Content-Type" to listOf("application/json"),
              "Mcp-Session-Id" to listOf("session-42"),
            ),
          )

          else -> McpTestFixtures.jsonResponse(
            requestId = 2L,
            resultJson = """{"tools":[]}""",
          )
        }
      }
    }

    val transport = newTransport(endpoint)
    transport.initialize(
      url = "https://docs.example.test/mcp",
      requestId = 1L,
      clientInfo = McpConnectionManager.DEFAULT_CLIENT_INFO,
      auth = null,
      requestTimeoutMs = 30_000L,
    )
    transport.call(
      url = "https://docs.example.test/mcp",
      requestId = 2L,
      method = McpProtocol.METHOD_TOOLS_LIST,
      params = null,
      auth = null,
      requestTimeoutMs = 30_000L,
    )

    assertEquals(2, bodies.size)
    assertEquals("session-42", bodies[0].get(McpHttpTransport.SESSION_ID_HEADER) ?: bodies[1][McpHttpTransport.SESSION_ID_HEADER])
    // The second request must carry the session id granted during initialize.
    assertEquals("session-42", bodies[1][McpHttpTransport.SESSION_ID_HEADER])
    // Protocol version header appears on post-initialize calls only.
    assertEquals(null, bodies[0][McpHttpTransport.PROTOCOL_VERSION_HEADER])
    assertEquals(MCP_PROTOCOL_VERSION, bodies[1][McpHttpTransport.PROTOCOL_VERSION_HEADER])
  }

  @Test
  fun Call_negotiatesServerProtocolVersionAfterInitialize() {
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult =
        if (body.contains("initialize")) {
          McpTestFixtures.jsonResponse(
            requestId = 1L,
            resultJson = McpTestFixtures.initializeResultJson(protocolVersion = "2026-01-01"),
          )
        } else {
          McpTestFixtures.jsonResponse(
            requestId = 2L,
            resultJson = """{"tools":[]}""",
          )
        }
    }

    val transport = newTransport(endpoint)
    transport.initialize(
      url = "https://docs.example.test/mcp",
      requestId = 1L,
      clientInfo = McpConnectionManager.DEFAULT_CLIENT_INFO,
      auth = null,
      requestTimeoutMs = 30_000L,
    )

    assertEquals("2026-01-01", transport.protocolVersion)
  }

  @Test
  fun Call_parsesSseResponseFrameWhenServerStreams() {
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult = McpTestFixtures.sseResponse(
        requestId = 1L,
        resultJson = """{"tools":[{"name":"stream-tool"}]}""",
      )
    }

    val transport = newTransport(endpoint)
    val response = transport.call(
      url = "https://docs.example.test/mcp",
      requestId = 1L,
      method = McpProtocol.METHOD_TOOLS_LIST,
      params = null,
      auth = null,
      requestTimeoutMs = 30_000L,
    )

    assertEquals(1L, response.id)
    assertTrue(response.result.toString().contains("stream-tool"))
  }

  @Test
  fun Call_injectsCredentialIntoAuthHeader() {
    var capturedAuth: String? = null
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult {
        if (body.contains("tools/list")) {
          capturedAuth = headers["X-Custom-Auth"]
        }
        return McpTestFixtures.jsonResponse(
          requestId = 1L,
          resultJson = """{"tools":[]}""",
        )
      }
    }

    val transport = newTransport(endpoint)
    transport.call(
      url = "https://docs.example.test/mcp",
      requestId = 1L,
      method = McpProtocol.METHOD_TOOLS_LIST,
      params = null,
      auth = McpHttpAuth(headerName = "X-Custom-Auth", secret = "token-value"),
      requestTimeoutMs = 30_000L,
    )

    assertEquals("token-value", capturedAuth)
  }

  @Test
  fun Call_normalizesHttpFailuresToMcpHttpException() {
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult = McpHttpResult(
        statusCode = 503,
        headers = emptyMap(),
        body = "Service Unavailable",
      )
    }

    val transport = newTransport(endpoint)
    val error = runCatching {
      transport.call(
        url = "https://docs.example.test/mcp",
        requestId = 1L,
        method = McpProtocol.METHOD_TOOLS_LIST,
        params = null,
        auth = null,
        requestTimeoutMs = 30_000L,
      )
    }.exceptionOrNull()

    assertTrue(error is McpHttpException)
    assertEquals(503, (error as McpHttpException).statusCode)
  }

  @Test
  fun Call_throwsWhenSseStreamClosesWithoutResponseFrame() {
    val endpoint = object : McpHttpEndpoint {
      override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
      ): McpHttpResult = McpHttpResult(
        statusCode = 200,
        headers = mapOf("Content-Type" to listOf("text/event-stream")),
        body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}\n\n",
      )
    }

    val transport = newTransport(endpoint)
    val error = runCatching {
      transport.call(
        url = "https://docs.example.test/mcp",
        requestId = 1L,
        method = McpProtocol.METHOD_TOOLS_LIST,
        params = null,
        auth = null,
        requestTimeoutMs = 30_000L,
      )
    }.exceptionOrNull()

    assertTrue(error is McpHttpException)
    assertTrue(error?.message?.contains("without a response frame") == true)
  }

  @Test
  fun CredentialResolver_defaultsToNoResolution() {
    // The no-op resolver keeps fail-closed behavior observable.
    assertEquals(null, McpNoCredentialResolver.resolve(com.opencray.persistence.security.CredentialRef("secret://mcp/x")))
  }

  @Test
  fun LocalStdioAndRemoteSseRemainContractOnly() {
    // Contracts stay serializable; only RemoteHttp has a runtime bridge today.
    val stdio = McpTransportDescriptor.LocalStdio(command = "opencray-mcp")
    val http = McpTransportDescriptor.RemoteHttp(url = "https://docs.example.test/mcp")
    val sse = McpTransportDescriptor.RemoteSse(
      eventsUrl = "https://community.example.test/mcp/events",
      postUrl = "https://community.example.test/mcp",
    )
    assertEquals("local_stdio", stdio.serialName())
    assertEquals("remote_http", http.serialName())
    assertEquals("remote_sse", sse.serialName())
  }

  private fun McpTransportDescriptor.serialName(): String = when (this) {
    is McpTransportDescriptor.LocalStdio -> "local_stdio"
    is McpTransportDescriptor.RemoteHttp -> "remote_http"
    is McpTransportDescriptor.RemoteSse -> "remote_sse"
  }
}
