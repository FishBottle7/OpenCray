package com.opencray.app

import com.opencray.app.facade.llm.processAnthropicStreamEvent
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmClientAnthropicDialectTest : LiteLlmClientTestBase() {
  @Test
  fun processAnthropicStreamEventMergesMessageDeltaUsageIntoMessageStartUsage() {
    val client = OpenAiCompatibleLiteLlmProviderClient()
    val payload = JSONObject()
    val coalescer = VisibleTextSnapshotCoalescer(
      observer = object : LiteLlmVisibleTextObserver {
        override fun onVisibleTextSnapshot(text: String) {
        }
      },
      minIntervalMs = 0L,
    )

    client.processAnthropicStreamEvent(
      eventName = "message_start",
      data = """{"type":"message_start","message":{"id":"msg_usage_merge","usage":{"input_tokens":512,"output_tokens":1,"cache_creation_input_tokens":4096,"cache_read_input_tokens":2048}}}""",
      payload = payload,
      contentBlocks = mutableMapOf(),
      toolInputBuffers = mutableMapOf(),
      visibleTextCoalescer = coalescer,
    )
    client.processAnthropicStreamEvent(
      eventName = "message_delta",
      data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
      payload = payload,
      contentBlocks = mutableMapOf(),
      toolInputBuffers = mutableMapOf(),
      visibleTextCoalescer = coalescer,
    )

    val usage = payload.getJSONObject("usage")
    assertEquals(512, usage.getLong("input_tokens"))
    assertEquals(2048, usage.getLong("cache_read_input_tokens"))
    assertEquals(4096, usage.getLong("cache_creation_input_tokens"))
    assertEquals(42, usage.getLong("output_tokens"))
    assertEquals("end_turn", payload.getString("stop_reason"))
  }

  @Test
  fun executeAutoStreamsAnthropicKimiRequestsForThirdPartyRoutes() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "req_third_party_kimi",
                "content": [
                  {
                    "type": "text",
                    "text": "OK"
                  }
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-third-party-kimi-anthropic",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "thinking_budget_tokens" to "4096",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-third-party-kimi-anthropic",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("OK", success.outputText)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeRespectsExplicitStreamFalseForAnthropicKimiThirdPartyRoutes() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "req_third_party_kimi_no_stream",
                "content": [
                  {
                    "type": "text",
                    "text": "OK"
                  }
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-third-party-kimi-anthropic-no-stream",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "thinking_budget_tokens" to "4096",
              "stream" to "false",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-third-party-kimi-anthropic-no-stream",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertFalse(payload.has("stream"))
      assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("OK", success.outputText)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
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
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("anthropic_tool_use", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeCapturesAnthropicThinkingAndTextAroundToolUse() {
    val result = executeWithAnthropicResponse(
      """
      {
        "id": "msg_tool_use_with_thinking",
        "content": [
          {
            "type": "thinking",
            "thinking": "Need one todo write before answering."
          },
          {
            "type": "text",
            "text": "Updating the todo list now."
          },
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
    assertEquals("Updating the todo list now.", completion.commentaryText)
    assertEquals("Need one todo write before answering.", completion.reasoningText)
    assertEquals("TodoWrite", completion.toolCalls.single().toolName)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
  }

  @Test
  fun executeStreamsAnthropicKimiTextResponses() {
    val requestBody = AtomicReference<String>()
    val visibleDrafts = mutableListOf<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpEventStreamResponse(
            client = client,
            body = """
              event: message_start
              data: {"type":"message_start","message":{"id":"msg_kimi_stream","type":"message","role":"assistant","content":[],"model":"kimi-k2.5","stop_reason":null}}
              
              event: content_block_start
              data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}
              
              event: content_block_stop
              data: {"type":"content_block_stop","index":0}
              
              event: message_delta
              data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
              
              event: message_stop
              data: {"type":"message_stop"}
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
        streamUpdateMinIntervalMs = 0L,
      )
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-kimi-anthropic-stream",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("x-api-key" to "test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-anthropic-stream",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals(listOf("Hello", "Hello world"), visibleDrafts)
      assertEquals("Hello world", success.outputText)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsAnthropicKimiToolUseResponses() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpEventStreamResponse(
            client = client,
            body = """
              event: message_start
              data: {"type":"message_start","message":{"id":"msg_kimi_tool_stream","type":"message","role":"assistant","content":[],"model":"kimi-k2.5","stop_reason":null}}
              
              event: content_block_start
              data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_kimi_1","name":"EchoProbe"}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"echo\":"}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"hello\"}"}}
              
              event: content_block_stop
              data: {"type":"content_block_stop","index":0}
              
              event: message_delta
              data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
              
              event: message_stop
              data: {"type":"message_stop"}
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-kimi-anthropic-tool-stream",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Call EchoProbe.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-anthropic-tool-stream",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals(1, success.completion?.toolCalls?.size)
      assertEquals("EchoProbe", success.completion?.toolCalls?.single()?.toolName)
      assertEquals("\"hello\"", success.completion?.toolCalls?.single()?.arguments?.get("echo")?.toString())
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsAnthropicErrorEventAsNonTransientProviderFailure() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpEventStreamResponse(
            client = client,
            body = """
              event: message_start
              data: {"type":"message_start","message":{"id":"msg_kimi_stream_error","type":"message","role":"assistant","content":[],"model":"kimi-k2.5","stop_reason":null}}
              
              event: content_block_start
              data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Partial ans"}}
              
              event: error
              data: {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-kimi-anthropic-stream-error",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-anthropic-stream-error",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val failure = result as LiteLlmProviderResult.Failure
      assertEquals("PROVIDER_FAILURE", failure.errorCode)
      assertFalse(failure.errorCode == "PROVIDER_TRANSPORT_ERROR")
      assertEquals("invalid x-api-key", failure.errorMessage)
      assertEquals("true", failure.metadata[LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_EVENT])
      assertEquals("authentication_error", failure.metadata[LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_TYPE])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeRecordsCorruptedAnthropicToolInputJsonAsRecoverableToolCallError() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpEventStreamResponse(
            client = client,
            body = """
              event: message_start
              data: {"type":"message_start","message":{"id":"msg_kimi_tool_broken","type":"message","role":"assistant","content":[],"model":"kimi-k2.5","stop_reason":null}}
              
              event: content_block_start
              data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_kimi_broken","name":"EchoProbe"}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"echo\":"}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"hello unterminated"}}
              
              event: content_block_stop
              data: {"type":"content_block_stop","index":0}
              
              event: message_delta
              data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
              
              event: message_stop
              data: {"type":"message_stop"}
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-kimi-anthropic-tool-broken-stream",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Call EchoProbe.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-anthropic-tool-broken-stream",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val success = result as LiteLlmProviderResult.Success
      val completion = requireNotNull(success.completion)
      assertTrue(completion.toolCalls.isEmpty())
      assertFalse(completion.hasStructuredActions)
      assertEquals(1, completion.toolCallErrors.size)
      assertTrue(completion.toolCallErrors.single().contains("content[0].input"))
      assertTrue(completion.toolCallErrors.single().contains("Parser error"))
      assertTrue(completion.toolCallErrors.single().contains("Received"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsAnthropicPromptCacheUsageAcrossMessageStartAndDelta() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpEventStreamResponse(
            client = client,
            body = """
              event: message_start
              data: {"type":"message_start","message":{"id":"msg_stream_usage","type":"message","role":"assistant","content":[],"model":"claude-3-5-sonnet","stop_reason":null,"usage":{"input_tokens":512,"output_tokens":1,"cache_creation_input_tokens":4096,"cache_read_input_tokens":2048}}}
              
              event: content_block_start
              data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
              
              event: content_block_delta
              data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}
              
              event: content_block_stop
              data: {"type":"content_block_stop","index":0}
              
              event: message_delta
              data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}
              
              event: message_stop
              data: {"type":"message_stop"}
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-stream-usage",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-stream-usage",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals(true, JSONObject(requestBody.get()).getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("OK", success.outputText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
      assertEquals("2048", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS])
      assertEquals("4096", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_TOKENS])
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
                  stderr = "probe warning",
                  errorCode = "WARN_ONLY",
                ),
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            toolChoice = LiteLlmToolChoice(
              mode = LiteLlmToolChoiceMode.TOOL,
              toolName = "EchoProbe",
            ),
            parallelToolCalls = false,
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
      val toolChoice = payload.getJSONObject("tool_choice")
      assertEquals("tool", toolChoice.getString("type"))
      assertEquals("EchoProbe", toolChoice.getString("name"))
      assertEquals(true, toolChoice.getBoolean("disable_parallel_tool_use"))
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
      val toolContent = JSONObject(resultBlock.getString("content"))
      assertEquals("""{"tool_name":"EchoProbe","status":"success"}""", toolContent.getString("content"))
      assertEquals("probe warning", toolContent.getString("stderr"))
      assertEquals("WARN_ONLY", toolContent.getString("error_code"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeMergesAnthropicToolBoundarySupplementIntoSameUserTurn() {
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
            id = "route-anthropic-tool-boundary",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
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
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Use the repository root as the workspace.",
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-tool-boundary",
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
      assertEquals(3, messages.length())
      val toolBoundaryTurn = messages.getJSONObject(2)
      assertEquals("user", toolBoundaryTurn.getString("role"))
      val boundaryBlocks = toolBoundaryTurn.getJSONArray("content")
      assertEquals(2, boundaryBlocks.length())
      val resultBlock = boundaryBlocks.getJSONObject(0)
      assertEquals("tool_result", resultBlock.getString("type"))
      assertEquals("oc-call-1", resultBlock.getString("tool_use_id"))
      val supplementBlock = boundaryBlocks.getJSONObject(1)
      assertEquals("text", supplementBlock.getString("type"))
      assertEquals(
        "Use the repository root as the workspace.",
        supplementBlock.getString("text"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsAnthropicPromptCacheControlWhenEnabledFor1hTtl() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_anthropic_prompt_cache_1h",
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-prompt-cache-1h",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHING_ENABLED to "true",
              LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHE_TTL to
                AnthropicPromptCacheTtlPolicies.HOUR_1,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            systemPrompt = "system prompt",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-prompt-cache-1h",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val cacheControl = payload.getJSONObject("cache_control")
      assertEquals("ephemeral", cacheControl.getString("type"))
      assertEquals(AnthropicPromptCacheTtlPolicies.HOUR_1, cacheControl.getString("ttl"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_CONTROL_PRESENT])
      assertEquals(
        AnthropicPromptCacheTtlPolicies.HOUR_1,
        success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsAnthropicPromptCacheControlWithDefault5mTtlWhenEnabled() {
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_anthropic_prompt_cache_default",
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-prompt-cache-default",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              LlmPromptCachingMetadataKeys.ANTHROPIC_PROMPT_CACHING_ENABLED to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-prompt-cache-default",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val cacheControl = payload.getJSONObject("cache_control")
      assertEquals("ephemeral", cacheControl.getString("type"))
      assertFalse(cacheControl.has("ttl"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_CONTROL_PRESENT])
      assertEquals(
        AnthropicPromptCacheTtlPolicies.MINUTES_5,
        success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeCapturesAnthropicPromptCacheReadAndWriteTokensFromFlatUsage() {
    val result = executeWithAnthropicResponse(
      """
        {
          "id": "msg_prompt_cache_flat",
          "content": [
            {
              "type": "text",
              "text": "OK"
            }
          ],
          "usage": {
            "input_tokens": 512,
            "output_tokens": 8,
            "cache_creation_input_tokens": 4096,
            "cache_read_input_tokens": 2048
          },
          "stop_reason": "end_turn"
        }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
    assertEquals("2048", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS])
    assertEquals("4096", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_TOKENS])
    assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION))
  }

  @Test
  fun executeCapturesAnthropicPromptCacheTieredWriteUsage() {
    val result = executeWithAnthropicResponse(
      """
        {
          "id": "msg_prompt_cache_tiered",
          "content": [
            {
              "type": "text",
              "text": "OK"
            }
          ],
          "usage": {
            "input_tokens": 256,
            "output_tokens": 6,
            "cache_creation": {
              "ephemeral_1h_input_tokens": 8192
            }
          },
          "stop_reason": "end_turn"
        }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
    assertEquals("8192", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_TOKENS])
    assertEquals("8192", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_1H_TOKENS])
    assertEquals("1h", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION])
  }
}
