package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAiCompatibleLiteLlmProviderClientTest {
  @Test
  fun executeSendsFixedUserAgentForOpenAiRequests() {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "req_test",
                "choices": [
                  {
                    "message": { "content": "OK" },
                    "finish_reason": "stop"
                  }
                ]
              }
            """.trimIndent(),
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent(
          "1.0.0-test",
        ),
      )
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-test",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-test",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(result is LiteLlmProviderResult.Success)
      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/chat/completions HTTP/1.1", requestLine.get())
      assertEquals(
        "OpenCray/1.0.0-test (Android; host-runtime)",
        userAgent.get(),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSynthesizesToolProtocolPayloadFromOpenAiToolCalls() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_tool_call",
        "choices": [
          {
            "message": {
              "content": "",
              "tool_calls": [
                {
                  "id": "call_1",
                  "type": "function",
                  "function": {
                    "name": "TodoWrite",
                    "arguments": "{\"todos\":[{\"content\":\"Ship update entry\",\"status\":\"in_progress\"}]}"
                  }
                }
              ]
            },
            "finish_reason": "tool_calls"
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    val completion = requireNotNull(success.completion)
    assertEquals("tool_calls", success.finishReason)
    assertTrue(success.outputText.contains("\"tool_calls\""))
    assertTrue(success.outputText.contains("\"tool_name\":\"TodoWrite\""))
    assertTrue(success.outputText.contains("\"content\":\"Ship update entry\""))
    assertTrue(success.outputText.contains("\"status\":\"in_progress\""))
    assertEquals("TodoWrite", completion.toolCalls.single().toolName)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("openai_tool_calls", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeUsesReasoningContentWhenItCarriesProtocolJson() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_reasoning_protocol",
        "choices": [
          {
            "message": {
              "content": "",
              "reasoning_content": "{\"type\":\"tool_call\",\"tool_name\":\"TodoWrite\",\"arguments\":{\"todos\":[{\"content\":\"Track refresh fix\",\"status\":\"pending\"}]}}"
            },
            "finish_reason": "stop"
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("stop", success.finishReason)
    assertTrue(success.outputText.contains("\"tool_name\":\"TodoWrite\""))
    assertTrue(success.outputText.contains("\"content\":\"Track refresh fix\""))
    assertTrue(success.outputText.contains("\"status\":\"pending\""))
    assertEquals("openai_reasoning_protocol", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeDoesNotTreatPlainReasoningTextAsVisibleCompletion() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_reasoning_only",
        "choices": [
          {
            "message": {
              "content": "",
              "reasoning_content": "I should probably call TodoWrite next."
            },
            "finish_reason": "stop"
          }
        ]
      }
      """.trimIndent(),
    )

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_EMPTY_RESPONSE", failure.errorCode)
    assertEquals("openai_reasoning_text", failure.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("false", failure.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeTreatsAnthropicToolUseAsStructuredSuccessWithoutTextCompletion() {
    val result = executeWithAnthropicResponse(
      """
      {
        "id": "msg_tool_use",
        "content": [
          {
            "type": "tool_use",
            "id": "toolu_1",
            "name": "TodoWrite",
            "input": {
              "todos": [
                {
                  "content": "Ship update entry",
                  "status": "in_progress"
                }
              ]
            }
          }
        ],
        "stop_reason": "tool_use"
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    val completion = requireNotNull(success.completion)
    assertEquals("tool_use", success.finishReason)
    assertTrue(success.outputText.isBlank())
    assertEquals("TodoWrite", completion.toolCalls.single().toolName)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("anthropic_tool_use", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeSendsOpenAiToolsWhenRequestIncludesToolDefinitions() {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent, requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "req_text",
                "choices": [
                  {
                    "message": { "content": "OK" },
                    "finish_reason": "stop"
                  }
                ]
              }
            """.trimIndent(),
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-tools",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use tools when needed.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-tools",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/chat/completions HTTP/1.1", requestLine.get())
      val payload = JSONObject(requestBody.get())
      val tool = payload.getJSONArray("tools").getJSONObject(0)
      val function = tool.getJSONObject("function")
      assertEquals("function", tool.getString("type"))
      assertEquals("EchoProbe", function.getString("name"))
      assertEquals("object", function.getJSONObject("parameters").getString("type"))
      assertEquals(
        "string",
        function.getJSONObject("parameters")
          .getJSONObject("properties")
          .getJSONObject("echo")
          .getString("type"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSendsAnthropicToolsWhenRequestIncludesToolDefinitions() {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent, requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_text",
                "content": [
                  { "type": "text", "text": "OK" }
                ],
                "stop_reason": "end_turn"
              }
            """.trimIndent(),
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-tools",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use tools when needed.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-tools",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/messages HTTP/1.1", requestLine.get())
      val payload = JSONObject(requestBody.get())
      val tool = payload.getJSONArray("tools").getJSONObject(0)
      assertEquals("EchoProbe", tool.getString("name"))
      assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
      assertEquals(
        "string",
        tool.getJSONObject("input_schema")
          .getJSONObject("properties")
          .getJSONObject("echo")
          .getString("type"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsOpenAiCanonicalMessagesWhenGatewayMessagesArePresent() {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent, requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "req_text",
                "choices": [
                  {
                    "message": { "content": "OK" },
                    "finish_reason": "stop"
                  }
                ]
              }
            """.trimIndent(),
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-messages",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            systemPrompt = "system prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Task context",
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                toolCalls = listOf(
                  sampleStructuredToolCall(id = "oc-call-1"),
                ),
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.TOOL,
                toolResult = LiteLlmGatewayToolResult(
                  toolCallId = "oc-call-1",
                  toolName = "EchoProbe",
                  content = """{"tool_name":"EchoProbe","status":"success"}""",
                ),
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-messages",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/chat/completions HTTP/1.1", requestLine.get())
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      assertEquals("system", messages.getJSONObject(0).getString("role"))
      assertEquals("Task context", messages.getJSONObject(1).getString("content"))
      val assistant = messages.getJSONObject(2)
      assertEquals("assistant", assistant.getString("role"))
      assertTrue(assistant.isNull("content"))
      assertEquals("EchoProbe", assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
      val tool = messages.getJSONObject(3)
      assertEquals("tool", tool.getString("role"))
      assertEquals("oc-call-1", tool.getString("tool_call_id"))
      assertEquals("""{"tool_name":"EchoProbe","status":"success"}""", tool.getString("content"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsAnthropicCanonicalMessagesWhenGatewayMessagesArePresent() {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent, requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_text",
                "content": [
                  { "type": "text", "text": "OK" }
                ],
                "stop_reason": "end_turn"
              }
            """.trimIndent(),
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-messages",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            systemPrompt = "system prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Task context",
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                toolCalls = listOf(
                  sampleStructuredToolCall(id = "oc-call-1"),
                ),
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.TOOL,
                toolResult = LiteLlmGatewayToolResult(
                  toolCallId = "oc-call-1",
                  toolName = "EchoProbe",
                  content = """{"tool_name":"EchoProbe","status":"success"}""",
                ),
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-messages",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/messages HTTP/1.1", requestLine.get())
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      assertEquals("user", messages.getJSONObject(0).getString("role"))
      assertEquals("Task context", messages.getJSONObject(0).getString("content"))
      val assistant = messages.getJSONObject(1)
      assertEquals("assistant", assistant.getString("role"))
      val assistantBlocks = assistant.getJSONArray("content")
      assertEquals("tool_use", assistantBlocks.getJSONObject(0).getString("type"))
      assertEquals("EchoProbe", assistantBlocks.getJSONObject(0).getString("name"))
      val toolResult = messages.getJSONObject(2)
      assertEquals("user", toolResult.getString("role"))
      val resultBlock = toolResult.getJSONArray("content").getJSONObject(0)
      assertEquals("tool_result", resultBlock.getString("type"))
      assertEquals("oc-call-1", resultBlock.getString("tool_use_id"))
      assertEquals("""{"tool_name":"EchoProbe","status":"success"}""", resultBlock.getString("content"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun executeWithOpenAiResponse(body: String): LiteLlmProviderResult {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent)
          writeHttpResponse(
            client = client,
            body = body,
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    return try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent(
          "1.0.0-test",
        ),
      )
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-test",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-test",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/chat/completions HTTP/1.1", requestLine.get())
      assertNotNull(userAgent.get())
      result
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun executeWithAnthropicResponse(body: String): LiteLlmProviderResult {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent)
          writeHttpResponse(
            client = client,
            body = body,
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    return try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent(
          "1.0.0-test",
        ),
      )
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-test",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-test",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/messages HTTP/1.1", requestLine.get())
      assertNotNull(userAgent.get())
      result
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun readHttpRequest(
    client: Socket,
    requestLine: AtomicReference<String>,
    userAgent: AtomicReference<String>,
    requestBody: AtomicReference<String>? = null,
  ) {
    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    requestLine.set(reader.readLine())
    var contentLength = 0
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) {
        break
      }
      val separatorIndex = line.indexOf(':')
      if (separatorIndex <= 0) {
        continue
      }
      val headerName = line.substring(0, separatorIndex).trim()
      val headerValue = line.substring(separatorIndex + 1).trim()
      if (headerName.equals("User-Agent", ignoreCase = true)) {
        userAgent.set(headerValue)
      }
      if (headerName.equals("Content-Length", ignoreCase = true)) {
        contentLength = headerValue.toIntOrNull() ?: 0
      }
    }
    if (requestBody != null && contentLength > 0) {
      val buffer = CharArray(contentLength)
      var totalRead = 0
      while (totalRead < contentLength) {
        val read = reader.read(buffer, totalRead, contentLength - totalRead)
        if (read <= 0) {
          break
        }
        totalRead += read
      }
      requestBody.set(String(buffer, 0, totalRead))
    }
  }

  private fun writeHttpResponse(
    client: Socket,
    body: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: application/json\r\n")
      append("Content-Length: ${bodyBytes.size}\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    client.getOutputStream().use { output ->
      output.write(header)
      output.write(bodyBytes)
      output.flush()
    }
  }

  private fun sampleToolDefinition(): LiteLlmToolDefinition = LiteLlmToolDefinition(
    name = "EchoProbe",
    description = "Echo back the probe payload.",
    inputSchema = buildJsonObject {
      put("type", "object")
      put(
        "properties",
        buildJsonObject {
          put(
            "echo",
            buildJsonObject {
              put("type", "string")
            },
          )
        },
      )
    },
  )

  private fun sampleStructuredToolCall(id: String): com.opencray.llm.LiteLlmStructuredToolCall =
    com.opencray.llm.LiteLlmStructuredToolCall(
      id = id,
      toolName = "EchoProbe",
      arguments = buildJsonObject {
        put("echo", "hello")
      },
    )
}
