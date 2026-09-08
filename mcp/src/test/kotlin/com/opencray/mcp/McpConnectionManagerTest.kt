package com.opencray.mcp

import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.persistence.security.CredentialRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted HTTP endpoint: records every POST and replays canned responses so
 * the connection manager tests run without real network.
 */
class ScriptedMcpEndpoint : McpHttpEndpoint {
  data class RecordedRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val method: String?,
    val requestId: Long?,
    val timeoutMs: Long,
  )

  val requests = mutableListOf<RecordedRequest>()
  var responder: (RecordedRequest) -> McpHttpResult = { request ->
    throw IllegalStateException("Unexpected MCP request: ${request.method}")
  }

  override fun postJson(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Long,
  ): McpHttpResult {
    val payload = try {
      Json.parseToJsonElement(body).jsonObject
    } catch (error: Exception) {
      throw IllegalStateException("Malformed request body recorded: $body", error)
    }
    val request = RecordedRequest(
      url = url,
      headers = headers,
      body = body,
      method = payload["method"]?.jsonPrimitive?.contentOrNull,
      requestId = (payload["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
      timeoutMs = timeoutMs,
    )
    requests += request
    return responder(request)
  }
}

object McpTestFixtures {
  const val DOCS_URL: String = "https://docs.example.test/mcp"

  fun httpServerRecord(
    id: String = "docs-proxy",
    trustState: McpServerTrustState = McpServerTrustState.ENABLED,
    auth: McpServerAuthState = McpServerAuthState(status = McpServerAuthStatus.NOT_REQUIRED),
    transport: McpTransportDescriptor = McpTransportDescriptor.RemoteHttp(url = DOCS_URL),
  ): McpRegistryServerRecord = McpRegistryServerRecord(
    spec = McpServerSpec(
      id = id,
      displayName = "Docs Proxy",
      transport = transport,
      trustState = trustState,
    ),
    authState = auth,
    registeredAtEpochMs = 1_710_000_000_000L,
    updatedAtEpochMs = 1_710_000_000_000L,
  )

  fun initializeResultJson(
    protocolVersion: String = MCP_PROTOCOL_VERSION,
    serverName: String = "docs-proxy",
  ): String =
    """{"protocolVersion":"$protocolVersion","serverInfo":{"name":"$serverName","version":"0.3.1"},""" +
      """"capabilities":{"tools":{"listChanged":true}}}"""

  fun jsonResponse(
    requestId: Long,
    resultJson: String,
    extraHeaders: Map<String, List<String>> = mapOf(
      "Content-Type" to listOf("application/json"),
    ),
  ): McpHttpResult = McpHttpResult(
    statusCode = 200,
    headers = extraHeaders,
    body = """{"jsonrpc":"2.0","id":$requestId,"result":$resultJson}""",
  )

  fun errorResponse(
    requestId: Long,
    code: Int,
    message: String,
  ): McpHttpResult = McpHttpResult(
    statusCode = 200,
    headers = mapOf("Content-Type" to listOf("application/json")),
    body = """{"jsonrpc":"2.0","id":$requestId,"error":{"code":$code,"message":"$message"}}""",
  )

  fun acceptedNotificationResponse(): McpHttpResult = McpHttpResult(
    statusCode = 202,
    headers = emptyMap(),
    body = "",
  )

  fun sseResponse(
    requestId: Long,
    resultJson: String,
  ): McpHttpResult = McpHttpResult(
    statusCode = 200,
    headers = mapOf("Content-Type" to listOf("text/event-stream")),
    body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$requestId,\"result\":$resultJson}\n\n",
  )

  fun newManager(
    serverId: String = "docs-proxy",
    credentialResolver: McpCredentialResolver = McpCredentialResolver { null },
    requestTimeoutMs: Long = 30_000L,
  ): McpConnectionManager {
    var nextId = 0L
    return McpConnectionManager(
      serverId = serverId,
      credentialResolver = credentialResolver,
      requestTimeoutMs = requestTimeoutMs,
      nextRequestId = { ++nextId },
    )
  }
}

class McpConnectionManagerTest {

  @Test
  fun Connect_initializesNotifiesAndDiscoversTools() {
    val endpoint = ScriptedMcpEndpoint()
    endpoint.responder = { request ->
      when (request.method) {
        "initialize" -> McpTestFixtures.jsonResponse(
          requestId = request.requestId ?: 0L,
          resultJson = McpTestFixtures.initializeResultJson(),
          extraHeaders = mapOf(
            "Content-Type" to listOf("application/json"),
            "Mcp-Session-Id" to listOf("session-7"),
          ),
        )

        "notifications/initialized" -> McpTestFixtures.acceptedNotificationResponse()

        "tools/list" -> McpTestFixtures.jsonResponse(
          requestId = request.requestId ?: 0L,
          resultJson = """{"tools":[{"name":"search","description":"Search docs"}]}""",
        )

        else -> throw IllegalStateException("Unexpected method: ${request.method}")
      }
    }
    val manager = McpTestFixtures.newManager()

    val snapshot = manager.connect(
      record = McpTestFixtures.httpServerRecord(),
      endpoint = endpoint,
    )

    assertEquals(McpConnectionState.CONNECTED, manager.state)
    assertEquals("docs-proxy", snapshot.serverId)
    assertEquals("docs-proxy", snapshot.serverInfo.name)
    assertEquals(MCP_PROTOCOL_VERSION, snapshot.protocolVersion)
    assertEquals("session-7", snapshot.sessionId)
    assertEquals(listOf("search"), snapshot.discoveredTools.map { it.name })

    assertEquals(3, endpoint.requests.size)
    assertEquals("initialize", endpoint.requests[0].method)
    assertEquals("notifications/initialized", endpoint.requests[1].method)
    assertEquals("tools/list", endpoint.requests[2].method)
    assertEquals(McpTestFixtures.DOCS_URL, endpoint.requests[0].url)
  }

  @Test
  fun Connect_rejectsNonEnabledTrustStateWithoutNetwork() {
    val endpoint = ScriptedMcpEndpoint()
    endpoint.responder = { throw IllegalStateException("Must not reach network") }
    val manager = McpTestFixtures.newManager()

    val error = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(trustState = McpServerTrustState.DISABLED),
        endpoint = endpoint,
      )
    }.exceptionOrNull()

    assertTrue(error is McpConnectionRejectedException)
    assertEquals("trust_state_disabled", (error as McpConnectionRejectedException).reason)
    assertTrue(endpoint.requests.isEmpty())
    assertEquals(McpConnectionState.DISCONNECTED, manager.state)
  }

  @Test
  fun Connect_rejectsUnsupportedTransportWithoutNetwork() {
    val endpoint = ScriptedMcpEndpoint()
    val manager = McpTestFixtures.newManager()

    val stdioError = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(
          transport = McpTransportDescriptor.LocalStdio(command = "opencray-mcp"),
        ),
        endpoint = endpoint,
      )
    }.exceptionOrNull()

    assertTrue(stdioError is McpConnectionRejectedException)
    assertEquals("transport_unsupported", (stdioError as McpConnectionRejectedException).reason)
    assertTrue(endpoint.requests.isEmpty())

    val sseError = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(
          transport = McpTransportDescriptor.RemoteSse(
            eventsUrl = "https://community.example.test/mcp/events",
            postUrl = "https://community.example.test/mcp",
          ),
        ),
        endpoint = endpoint,
      )
    }.exceptionOrNull()

    assertTrue(sseError is McpConnectionRejectedException)
    assertEquals("transport_unsupported", (sseError as McpConnectionRejectedException).reason)
    assertTrue(endpoint.requests.isEmpty())
  }

  @Test
  fun Connect_rejectsMissingOrErrorAuthWithoutNetwork() {
    val endpoint = ScriptedMcpEndpoint()
    val manager = McpTestFixtures.newManager()

    val missingError = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(auth = McpServerAuthState.missing()),
        endpoint = endpoint,
      )
    }.exceptionOrNull()
    assertEquals("credential_missing", (missingError as McpConnectionRejectedException).reason)

    val errorAuth = McpServerAuthState.error(code = "VAULT_READ_FAILED")
    val errorAuthError = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(auth = errorAuth),
        endpoint = endpoint,
      )
    }.exceptionOrNull()
    assertEquals("credential_error", (errorAuthError as McpConnectionRejectedException).reason)

    assertTrue(endpoint.requests.isEmpty())
  }

  @Test
  fun Connect_rejectsUnresolvedCredentialReference() {
    val endpoint = ScriptedMcpEndpoint()
    val manager = McpTestFixtures.newManager(
      credentialResolver = McpCredentialResolver { null },
    )

    val error = runCatching {
      manager.connect(
        record = McpTestFixtures.httpServerRecord(
          auth = McpServerAuthState.configured(
            credentialRef = CredentialRef("secret://mcp/docs-token"),
          ),
        ),
        endpoint = endpoint,
      )
    }.exceptionOrNull()

    assertEquals("credential_unresolved", (error as McpConnectionRejectedException).reason)
    assertTrue(endpoint.requests.isEmpty())
  }

  @Test
  fun Connect_rollsBackStateWhenInitializeFailsAndSupportsRetry() {
    val endpoint = ScriptedMcpEndpoint()
    var failInitialize = true
    endpoint.responder = { request ->
      if (request.method == "initialize" && failInitialize) {
        failInitialize = false
        McpTestFixtures.errorResponse(request.requestId ?: 0L, code = -32603, message = "boom")
      } else {
        when (request.method) {
          "initialize" -> McpTestFixtures.jsonResponse(
            requestId = request.requestId ?: 0L,
            resultJson = McpTestFixtures.initializeResultJson(),
          )

          "notifications/initialized" -> McpTestFixtures.acceptedNotificationResponse()

          "tools/list" -> McpTestFixtures.jsonResponse(
            requestId = request.requestId ?: 0L,
            resultJson = """{"tools":[]}""",
          )

          else -> throw IllegalStateException("Unexpected method: ${request.method}")
        }
      }
    }
    val manager = McpTestFixtures.newManager()

    val failure = runCatching {
      manager.connect(record = McpTestFixtures.httpServerRecord(), endpoint = endpoint)
    }.exceptionOrNull()
    assertTrue(failure is McpHttpException)
    assertTrue(failure?.message?.contains("MCP initialize failed") == true)
    assertEquals(McpConnectionState.DISCONNECTED, manager.state)

    val snapshot = manager.connect(record = McpTestFixtures.httpServerRecord(), endpoint = endpoint)
    assertEquals(McpConnectionState.CONNECTED, manager.state)
    assertEquals("docs-proxy", snapshot.serverInfo.name)
  }

  @Test
  fun ListTools_followsPaginationCursor() {
    val endpoint = ScriptedMcpEndpoint()
    endpoint.responder = { request ->
      when (request.method) {
        "initialize" -> McpTestFixtures.jsonResponse(
          requestId = request.requestId ?: 0L,
          resultJson = McpTestFixtures.initializeResultJson(),
        )

        "notifications/initialized" -> McpTestFixtures.acceptedNotificationResponse()

        "tools/list" -> {
          val body = Json.parseToJsonElement(request.body).jsonObject
          val cursor = body["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.contentOrNull
          if (cursor == null) {
            McpTestFixtures.jsonResponse(
              requestId = request.requestId ?: 0L,
              resultJson = """{"tools":[{"name":"first"}],"nextCursor":"page-2"}""",
            )
          } else {
            McpTestFixtures.jsonResponse(
              requestId = request.requestId ?: 0L,
              resultJson = """{"tools":[{"name":"second"}]}""",
            )
          }
        }

        else -> throw IllegalStateException("Unexpected method: ${request.method}")
      }
    }
    val manager = McpTestFixtures.newManager()

    val snapshot = manager.connect(record = McpTestFixtures.httpServerRecord(), endpoint = endpoint)

    assertEquals(listOf("first", "second"), snapshot.discoveredTools.map { it.name })
    val toolsListBodies = endpoint.requests.filter { it.method == "tools/list" }
    assertEquals(2, toolsListBodies.size)
    assertTrue(toolsListBodies[1].body.contains("\"cursor\":\"page-2\""))

    assertEquals(listOf("first", "second"), manager.listTools().map { it.name })
  }

  @Test
  fun Close_resetsConnectionAndBlocksFurtherCalls() {
    val endpoint = ScriptedMcpEndpoint()
    endpoint.responder = { request ->
      when (request.method) {
        "initialize" -> McpTestFixtures.jsonResponse(
          requestId = request.requestId ?: 0L,
          resultJson = McpTestFixtures.initializeResultJson(),
        )

        "notifications/initialized" -> McpTestFixtures.acceptedNotificationResponse()

        "tools/list" -> McpTestFixtures.jsonResponse(
          requestId = request.requestId ?: 0L,
          resultJson = """{"tools":[]}""",
        )

        else -> throw IllegalStateException("Unexpected method: ${request.method}")
      }
    }
    val manager = McpTestFixtures.newManager()
    manager.connect(record = McpTestFixtures.httpServerRecord(), endpoint = endpoint)
    manager.close()

    assertEquals(McpConnectionState.DISCONNECTED, manager.state)
    val error = runCatching { manager.callTool("search", null) }.exceptionOrNull()
    assertTrue(error is IllegalStateException)
    assertTrue(error?.message?.contains("not connected") == true)
  }
}
