package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmCompactRequest
import com.opencray.llm.LiteLlmCompactResult
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderCompactRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiteLlmClientCoreTest : LiteLlmClientTestBase() {
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
      val success = result as LiteLlmProviderResult.Success
      assertEquals("prompt_projection", success.metadata["conversationTransportMode"])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeTreatsHttp499AsTimeout() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpResponse(
            client = client,
            statusCode = 499,
            statusText = "Client Closed Request",
            body = """
              {
                "error": {
                  "message": "Upstream request timed out."
                }
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
            id = "route-timeout-499",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-timeout-499",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val timeout = result as LiteLlmProviderResult.Timeout
      assertEquals("Upstream request timed out.", timeout.errorMessage)
      assertEquals("499", timeout.metadata["statusCode"])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeTreatsHttp449AsTimeout() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpResponse(
            client = client,
            statusCode = 449,
            statusText = "Retry With",
            body = """
              {
                "error": {
                  "message": "Upstream request timed out."
                }
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
            id = "route-timeout-449",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-timeout-449",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val timeout = result as LiteLlmProviderResult.Timeout
      assertEquals("Upstream request timed out.", timeout.errorMessage)
      assertEquals("449", timeout.metadata["statusCode"])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeInfersGlmBuiltinWebSearchDialectFromModelName() {
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
                "id": "glm_search_response",
                "choices": [
                  {
                    "message": { "content": "https://example.com" },
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-glm-search",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "zhipuai/glm-4.6:online",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use web search to find https://example.com.",
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                includeSources = true,
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-glm-search",
            providerId = "custom",
            model = "zhipuai/glm-4.6:online",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val tool = payload.getJSONArray("tools").getJSONObject(0)
      assertEquals("web_search", tool.getString("type"))
      assertTrue(tool.getJSONObject("web_search").getBoolean("enable"))
      assertTrue(tool.getJSONObject("web_search").getBoolean("search_result"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
      assertEquals(
        "openai_chat_web_search",
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeUsesLastStructuredUserMessageForOpenAiBuiltinWebSearchFallbackQuery() {
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
                "id": "glm_search_messages_response",
                "choices": [
                  {
                    "message": { "content": "https://example.com" },
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-glm-search-messages",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "zhipuai/glm-4.6:online",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "legacy prompt blob should stay non-authoritative here",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "[Durable Context]\nRepository uses Gradle.",
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Use web search to find the canonical example.com URL.",
              ),
            ),
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                includeSources = true,
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-glm-search-messages",
            providerId = "custom",
            model = "zhipuai/glm-4.6:online",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      assertEquals(2, messages.length())
      assertEquals("[Durable Context]\nRepository uses Gradle.", messages.getJSONObject(0).getString("content"))
      assertEquals(
        "Use web search to find the canonical example.com URL.",
        messages.getJSONObject(1).getString("content"),
      )
      val success = result as LiteLlmProviderResult.Success
      assertEquals("messages", success.metadata["conversationTransportMode"])
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(
        "Use web search to find the canonical example.com URL.",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeInfersKimiBuiltinWebSearchDialectFromModelNameAndAutoContinues() {
    val requestBodies = mutableListOf<String>()
    val responseSent = CountDownLatch(2)
    val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        repeat(2) { index ->
          listeningSocket.accept().use { client ->
            val body = AtomicReference<String>()
            readHttpRequest(client, AtomicReference(), AtomicReference(), body)
            requestBodies += body.get()
            val responseBody = if (index == 0) {
              """
              {
                "id": "kimi_search_round_1",
                "choices": [
                  {
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {
                          "id": "call_web_1",
                          "type": "function",
                          "function": {
                            "name": "${'$'}web_search",
                            "arguments": "{\"query\":\"OpenAI\"}"
                          }
                        }
                      ]
                    },
                    "finish_reason": "tool_calls"
                  }
                ]
              }
              """.trimIndent()
            } else {
              """
              {
                "id": "kimi_search_round_2",
                "choices": [
                  {
                    "message": {
                      "role": "assistant",
                      "content": "https://example.com"
                    },
                    "finish_reason": "stop"
                  }
                ]
              }
              """.trimIndent()
            }
            writeHttpResponse(client = client, body = responseBody)
            responseSent.countDown()
          }
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
            id = "route-kimi-search",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "moonshotai/kimi-k2:online",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use web search to find https://example.com.",
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-search",
            providerId = "custom",
            model = "moonshotai/kimi-k2:online",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals(2, requestBodies.size)
      val firstPayload = JSONObject(requestBodies[0])
      val firstTool = firstPayload.getJSONArray("tools").getJSONObject(0)
      assertEquals("builtin_function", firstTool.getString("type"))
      assertEquals(
        "\$web_search",
        firstTool.getJSONObject("function").getString("name"),
      )
      assertEquals("disabled", firstPayload.getJSONObject("thinking").getString("type"))

      val secondPayload = JSONObject(requestBodies[1])
      val messages = secondPayload.getJSONArray("messages")
      assertEquals("user", messages.getJSONObject(0).getString("role"))
      assertEquals("assistant", messages.getJSONObject(1).getString("role"))
      assertEquals("tool", messages.getJSONObject(2).getString("role"))
      assertEquals("call_web_1", messages.getJSONObject(2).getString("tool_call_id"))
      assertEquals("{\"query\":\"OpenAI\"}", messages.getJSONObject(2).getString("content"))

      val success = result as LiteLlmProviderResult.Success
      assertEquals("https://example.com", success.outputText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
      assertEquals(
        "kimi_builtin_function_web_search",
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_DIALECT],
      )
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals("OpenAI", observations.getJSONObject(0).getJSONArray("queries").getString(0))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun compactConversationReturnsUnavailableForNonResponsesProtocols() {
    val client = OpenAiCompatibleLiteLlmProviderClient()

    val result = client.compactConversation(
      LiteLlmProviderCompactRequest(
        route = ProviderRoute(
          id = "route-openai-chat",
          providerId = "openai",
          baseUrl = "http://127.0.0.1:1/v1",
          model = "gpt-4o-mini",
          metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
        ),
        request = LiteLlmCompactRequest(
          gatewayRequest = LiteLlmGatewayRequest(prompt = "Compact this conversation."),
        ),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-test",
          routeId = "route-openai-chat",
          providerId = "openai",
          model = "gpt-4o-mini",
          attemptIndex = 0,
        ),
      ),
    )

    assertTrue(result is LiteLlmCompactResult.Unavailable)
    assertEquals(
      "protocol_remote_compaction_not_supported",
      (result as LiteLlmCompactResult.Unavailable).reason,
    )
  }

  @Test
  fun executeRejectsAssistantToolCallWithoutIdBeforeSendingProviderRequest() {
    val client = OpenAiCompatibleLiteLlmProviderClient()

    val result = client.execute(
      LiteLlmProviderRequest(
        route = ProviderRoute(
          id = "route-openai-responses",
          providerId = "openai",
          baseUrl = "http://127.0.0.1:1/v1",
          model = "gpt-5-mini",
          timeoutMs = 5_000L,
          metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
        ),
        request = LiteLlmGatewayRequest(
          prompt = "fallback prompt",
          messages = listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = null,
                  toolName = "EchoProbe",
                  arguments = buildJsonObject {
                    put("echo", "hello")
                  },
                ),
              ),
            ),
          ),
          tools = listOf(sampleToolDefinition()),
        ),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-test",
          routeId = "route-openai-responses",
          providerId = "openai",
          model = "gpt-5-mini",
          attemptIndex = 0,
        ),
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Failure)
    result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_REQUEST_INVALID_TOOL_CALL_ID", result.errorCode)
    assertTrue(result.errorMessage.contains("toolCalls[0].id"))
  }

  @Test
  fun executeRejectsDuplicateAssistantToolCallIdsBeforeSendingProviderRequest() {
    val client = OpenAiCompatibleLiteLlmProviderClient()

    val result = client.execute(
      LiteLlmProviderRequest(
        route = ProviderRoute(
          id = "route-openai-responses",
          providerId = "openai",
          baseUrl = "http://127.0.0.1:1/v1",
          model = "gpt-5-mini",
          timeoutMs = 5_000L,
          metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
        ),
        request = LiteLlmGatewayRequest(
          prompt = "fallback prompt",
          messages = listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              toolCalls = listOf(
                sampleStructuredToolCall(id = "oc-call-1"),
                sampleStructuredToolCall(id = "oc-call-1"),
              ),
            ),
          ),
          tools = listOf(sampleToolDefinition()),
        ),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-test",
          routeId = "route-openai-responses",
          providerId = "openai",
          model = "gpt-5-mini",
          attemptIndex = 0,
        ),
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Failure)
    result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_REQUEST_INVALID_TOOL_CALL_ID", result.errorCode)
    assertTrue(result.errorMessage.contains("duplicates provider-native tool call id 'oc-call-1'"))
  }

  @Test
  fun executeRejectsToolResultWithoutIdBeforeSendingProviderRequest() {
    val client = OpenAiCompatibleLiteLlmProviderClient()

    val result = client.execute(
      LiteLlmProviderRequest(
        route = ProviderRoute(
          id = "route-openai-responses",
          providerId = "openai",
          baseUrl = "http://127.0.0.1:1/v1",
          model = "gpt-5-mini",
          timeoutMs = 5_000L,
          metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
        ),
        request = LiteLlmGatewayRequest(
          prompt = "fallback prompt",
          messages = listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.TOOL,
              toolResult = LiteLlmGatewayToolResult(
                toolCallId = null,
                toolName = "EchoProbe",
                content = """{"status":"success"}""",
              ),
            ),
          ),
          tools = listOf(sampleToolDefinition()),
        ),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-test",
          routeId = "route-openai-responses",
          providerId = "openai",
          model = "gpt-5-mini",
          attemptIndex = 0,
        ),
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Failure)
    result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_REQUEST_INVALID_TOOL_CALL_ID", result.errorCode)
    assertTrue(result.errorMessage.contains("toolResult.toolCallId"))
  }

  @Test
  fun executeRejectsDuplicateToolResultIdsBeforeSendingProviderRequest() {
    val client = OpenAiCompatibleLiteLlmProviderClient()

    val result = client.execute(
      LiteLlmProviderRequest(
        route = ProviderRoute(
          id = "route-openai-responses",
          providerId = "openai",
          baseUrl = "http://127.0.0.1:1/v1",
          model = "gpt-5-mini",
          timeoutMs = 5_000L,
          metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
        ),
        request = LiteLlmGatewayRequest(
          prompt = "fallback prompt",
          messages = listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.TOOL,
              toolResult = LiteLlmGatewayToolResult(
                toolCallId = "oc-call-1",
                toolName = "EchoProbe",
                content = """{"status":"success"}""",
              ),
            ),
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.TOOL,
              toolResult = LiteLlmGatewayToolResult(
                toolCallId = "oc-call-1",
                toolName = "EchoProbe",
                content = """{"status":"success"}""",
              ),
            ),
          ),
          tools = listOf(sampleToolDefinition()),
        ),
        selection = LiteLlmRouteSelectionMetadata(
          profileId = "profile-test",
          routeId = "route-openai-responses",
          providerId = "openai",
          model = "gpt-5-mini",
          attemptIndex = 0,
        ),
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Failure)
    result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_REQUEST_INVALID_TOOL_CALL_ID", result.errorCode)
    assertTrue(result.errorMessage.contains("duplicates provider-native tool result id 'oc-call-1'"))
  }

  @Test
  fun executeCapturesResponsesBuiltinWebSearchMetadataAndCitations() {
    val result = executeWithResponsesResponse(
      """
      {
        "id": "resp_search",
        "status": "completed",
        "output": [
          {
            "type": "web_search_call",
            "status": "completed",
            "action": {
              "sources": [
                { "type": "url_citation", "title": "OpenAI", "url": "https://openai.com" }
              ]
            }
          },
          {
            "type": "message",
            "role": "assistant",
            "content": [
              {
                "type": "output_text",
                "text": "OpenAI source found.",
                "annotations": [
                  { "type": "url_citation", "title": "OpenAI", "url": "https://openai.com" }
                ]
              }
            ]
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("OpenAI source found.", success.completion?.finalText)
    assertEquals(
      "responses_text_and_builtin_web_search",
      success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE],
    )
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
    val observations = JSONArray(
      success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
    )
    assertEquals(1, observations.length())
    assertEquals("search", observations.getJSONObject(0).getString("actionType"))
    assertEquals("completed", observations.getJSONObject(0).getString("status"))
    assertEquals(
      "https://openai.com",
      observations
        .getJSONObject(0)
        .getJSONArray("sources")
        .getJSONObject(0)
        .getString("url"),
    )
    assertEquals("2", success.metadata[LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT])
  }

  @Test
  fun executeBuildsAnthropicBuiltinWebSearchToolAndCapturesSearchMetadata() {
    val requestLine = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_builtin_web_search",
                "content": [
                  {
                    "type": "server_tool_use",
                    "id": "srvtoolu_1",
                    "name": "web_search",
                    "input": {
                      "query": "example canonical url"
                    }
                  },
                  {
                    "type": "web_search_tool_result",
                    "tool_use_id": "srvtoolu_1",
                    "content": [
                      {
                        "type": "web_search_result",
                        "title": "Example Domain",
                        "url": "https://example.com"
                      }
                    ]
                  },
                  {
                    "type": "text",
                    "text": "The canonical URL is https://example.com.",
                    "citations": [
                      {
                        "type": "web_search_result_location",
                        "url": "https://example.com",
                        "title": "Example Domain"
                      }
                    ]
                  }
                ],
                "usage": {
                  "input_tokens": 12,
                  "output_tokens": 24,
                  "server_tool_use": {
                    "web_search_requests": 1
                  }
                },
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
            id = "route-anthropic-builtin-web-search",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-sonnet-4-5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use web search to find the canonical example.com URL.",
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                domains = listOf("example.com"),
                includeSources = true,
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-builtin-web-search",
            providerId = "anthropic",
            model = "claude-sonnet-4-5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/messages HTTP/1.1", requestLine.get())
      val payload = JSONObject(requestBody.get())
      val tool = payload.getJSONArray("tools").getJSONObject(0)
      assertEquals("web_search_20250305", tool.getString("type"))
      assertEquals("web_search", tool.getString("name"))
      assertEquals(5, tool.getInt("max_uses"))
      assertEquals("example.com", tool.getJSONArray("allowed_domains").getString(0))
      assertFalse(payload.has("thinking"))

      val success = result as LiteLlmProviderResult.Success
      assertEquals(
        "The canonical URL is https://example.com.",
        success.outputText,
      )
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
      assertEquals("2", success.metadata[LiteLlmMetadataKeys.PROVIDER_CITATION_COUNT])
      assertEquals(
        "anthropic_text_and_builtin_web_search",
        success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE],
      )
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(
        "example canonical url",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
      assertEquals(
        "https://example.com",
        observations.getJSONObject(0).getJSONArray("sources").getJSONObject(0).getString("url"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeDerivesAnthropicBuiltinWebSearchFallbackQueryFromStructuredMessages() {
    val requestLine = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "msg_builtin_web_search",
                "type": "message",
                "role": "assistant",
                "content": [
                  {
                    "type": "text",
                    "text": "The canonical URL is https://example.com."
                  }
                ],
                "stop_reason": "end_turn",
                "usage": {
                  "server_tool_use": {
                    "web_search_requests": 1
                  }
                }
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
            id = "route-anthropic-builtin-web-search",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-sonnet-4-5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "stale fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Search the canonical example.com URL.",
              ),
            ),
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                domains = listOf("example.com"),
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-builtin-web-search",
            providerId = "anthropic",
            model = "claude-sonnet-4-5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/messages HTTP/1.1", requestLine.get())
      val success = result as LiteLlmProviderResult.Success
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(
        "Search the canonical example.com URL.",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeAutoContinuesAnthropicBuiltinWebSearchPauseTurnForKimi() {
    val requestBodies = mutableListOf<String>()
    val responseSent = CountDownLatch(2)
    val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        repeat(2) { index ->
          listeningSocket.accept().use { client ->
            val body = AtomicReference<String>()
            readHttpRequest(client, AtomicReference(), AtomicReference(), body)
            requestBodies += body.get()
            val responseBody = if (index == 0) {
              """
              {
                "id": "kimi_anthropic_search_round_1",
                "content": [
                  {
                    "type": "server_tool_use",
                    "id": "srvtoolu_1",
                    "name": "web_search",
                    "input": {
                      "query": "example canonical url"
                    }
                  },
                  {
                    "type": "web_search_tool_result",
                    "tool_use_id": "srvtoolu_1",
                    "content": [
                      {
                        "type": "web_search_result",
                        "title": "Example Domain",
                        "url": "https://example.com"
                      }
                    ]
                  }
                ],
                "usage": {
                  "server_tool_use": {
                    "web_search_requests": 1
                  }
                },
                "stop_reason": "pause_turn"
              }
              """.trimIndent()
            } else {
              """
              {
                "id": "kimi_anthropic_search_round_2",
                "content": [
                  {
                    "type": "text",
                    "text": "The canonical URL is https://example.com."
                  }
                ],
                "stop_reason": "end_turn"
              }
              """.trimIndent()
            }
            writeHttpResponse(client = client, body = responseBody)
            responseSent.countDown()
          }
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
            id = "route-kimi-anthropic-builtin-web-search",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "kimi-k2.5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Use web search to find the canonical https://example.com URL.",
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                includeSources = true,
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-kimi-anthropic-builtin-web-search",
            providerId = "custom",
            model = "kimi-k2.5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals(2, requestBodies.size)

      val firstPayload = JSONObject(requestBodies[0])
      val firstTool = firstPayload.getJSONArray("tools").getJSONObject(0)
      assertEquals("web_search_20250305", firstTool.getString("type"))
      assertEquals("web_search", firstTool.getString("name"))

      val secondPayload = JSONObject(requestBodies[1])
      val secondMessages = secondPayload.getJSONArray("messages")
      assertEquals("user", secondMessages.getJSONObject(0).getString("role"))
      assertEquals("assistant", secondMessages.getJSONObject(1).getString("role"))
      val continuedBlocks = secondMessages.getJSONObject(1).getJSONArray("content")
      assertEquals("server_tool_use", continuedBlocks.getJSONObject(0).getString("type"))
      assertEquals("web_search_tool_result", continuedBlocks.getJSONObject(1).getString("type"))

      val success = result as LiteLlmProviderResult.Success
      assertEquals("The canonical URL is https://example.com.", success.outputText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(
        "example canonical url",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeUsesLastStructuredUserMessageForAnthropicBuiltinWebSearchFallbackQuery() {
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
                "id": "anthropic_search_messages_response",
                "content": [
                  {
                    "type": "text",
                    "text": "The canonical URL is https://example.com."
                  }
                ],
                "usage": {
                  "server_tool_use": {
                    "web_search_requests": 1
                  }
                },
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
            id = "route-anthropic-builtin-web-search-messages",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-sonnet-4-5",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.ANTHROPIC),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "legacy anthropic prompt blob should stay non-authoritative here",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "[Dynamic Context]\nUse concise answers.",
              ),
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Use web search to find the canonical example.com URL.",
              ),
            ),
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                domains = listOf("example.com"),
                includeSources = true,
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-builtin-web-search-messages",
            providerId = "anthropic",
            model = "claude-sonnet-4-5",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      assertEquals(2, messages.length())
      assertEquals("[Dynamic Context]\nUse concise answers.", messages.getJSONObject(0).getString("content"))
      assertEquals(
        "Use web search to find the canonical example.com URL.",
        messages.getJSONObject(1).getString("content"),
      )
      val success = result as LiteLlmProviderResult.Success
      assertEquals("messages", success.metadata["conversationTransportMode"])
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(
        "Use web search to find the canonical example.com URL.",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSendsOpenAiStructuredFinalJsonSchemaWhenRouteExplicitlySupportsIt() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      responseBody = """
        {
          "id": "req_structured_final_schema",
          "choices": [
            {
              "message": {
                "content": "{\"type\":\"final\",\"answer\":\"OK\",\"attachments\":[]}"
              },
              "finish_reason": "stop"
            }
          ]
        }
      """.trimIndent(),
      capturedBody = capturedBody,
      routeMetadata = mapOf(
        LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED to "true",
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED])
    val payload = JSONObject(capturedBody.get())
    val responseFormat = payload.getJSONObject("response_format")
    assertEquals("json_schema", responseFormat.getString("type"))
    val jsonSchema = responseFormat.getJSONObject("json_schema")
    assertEquals("opencray_final_response", jsonSchema.getString("name"))
    assertEquals(true, jsonSchema.getBoolean("strict"))
    val schema = jsonSchema.getJSONObject("schema")
    assertEquals(false, schema.getBoolean("additionalProperties"))
    assertEquals("attachments", schema.getJSONArray("required").getString(2))
    val attachmentSchema = schema
      .getJSONObject("properties")
      .getJSONObject("attachments")
      .getJSONObject("items")
    assertEquals(false, attachmentSchema.getBoolean("additionalProperties"))
    assertEquals("artifact_id", attachmentSchema.getJSONArray("required").getString(1))
  }

  @Test
  fun executeOmitsStructuredFinalJsonSchemaForThirdPartyOpenAiRoutesByDefault() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "custom-openai-compatible",
      protocol = LlmProviderProtocols.OPENAI,
      model = "third-party-model",
      responseBody = """
        {
          "id": "req_third_party_no_schema",
          "choices": [
            {
              "message": { "content": "OK" },
              "finish_reason": "stop"
            }
          ]
        }
      """.trimIndent(),
      capturedBody = capturedBody,
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val payload = JSONObject(capturedBody.get())
    assertFalse(payload.has("response_format"))
  }

  @Test
  fun executeOmitsStructuredFinalJsonSchemaForOpenAiProviderWithCustomBaseUrlByDefault() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      responseBody = """
        {
          "id": "req_openai_provider_custom_base_no_schema",
          "choices": [
            {
              "message": { "content": "OK" },
              "finish_reason": "stop"
            }
          ]
        }
      """.trimIndent(),
      capturedBody = capturedBody,
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val payload = JSONObject(capturedBody.get())
    assertFalse(payload.has("response_format"))
  }

  @Test
  fun executeSendsOpenAiResponsesStructuredFinalJsonSchemaWhenRouteExplicitlySupportsIt() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      responseBody = """
        {
          "id": "resp_structured_final_schema",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "role": "assistant",
              "content": [
                { "type": "output_text", "text": "{\"type\":\"final\",\"answer\":\"OK\",\"attachments\":[]}" }
              ]
            }
          ]
        }
      """.trimIndent(),
      capturedBody = capturedBody,
      routeMetadata = mapOf(
        LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED to "true",
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LlmStructuredFinalMetadataKeys.STRUCTURED_FINAL_SCHEMA_SUPPORTED])
    val payload = JSONObject(capturedBody.get())
    val format = payload
      .getJSONObject("text")
      .getJSONObject("format")
    assertEquals("json_schema", format.getString("type"))
    assertEquals("opencray_final_response", format.getString("name"))
    assertEquals(true, format.getBoolean("strict"))
    assertEquals("object", format.getJSONObject("schema").getString("type"))
  }

  @Test
  fun executeOmitsOpenAiResponsesStructuredFinalJsonSchemaForOpenAiProviderWithCustomBaseUrlByDefault() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "openai",
      protocol = LlmProviderProtocols.OPENAI_RESPONSES,
      model = "gpt-5-mini",
      responseBody = """
        {
          "id": "resp_openai_provider_custom_base_no_schema",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "role": "assistant",
              "content": [
                { "type": "output_text", "text": "OK" }
              ]
            }
          ]
        }
      """.trimIndent(),
      capturedBody = capturedBody,
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val payload = JSONObject(capturedBody.get())
    assertFalse(payload.has("text"))
  }

  @Test
  fun executeSendsAndParsesAnthropicStructuredFinalToolWhenRouteExplicitlySupportsIt() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "anthropic",
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "claude-3-5-sonnet",
      authHeaders = mapOf("x-api-key" to "test-key"),
      responseBody = """
        {
          "id": "msg_structured_final_tool",
          "content": [
            {
              "type": "tool_use",
              "id": "toolu_final_1",
              "name": "OpenCrayFinalResponse",
              "input": {
                "type": "final",
                "answer": "Created the image.",
                "attachments": [
                  {
                    "kind": "image",
                    "artifact_id": "artifact-image-1",
                    "relative_path": null,
                    "path": null,
                    "chat_attachment_id": null,
                    "display_name": "diagram.png",
                    "mime_type": "image/png",
                    "duration_ms": null,
                    "waveform_bars": [],
                    "transcript_text": null
                  }
                ]
              }
            }
          ],
          "stop_reason": "tool_use"
        }
      """.trimIndent(),
      capturedBody = capturedBody,
      routeMetadata = mapOf(
        LlmStructuredFinalMetadataKeys.ANTHROPIC_STRUCTURED_FINAL_TOOL_SUPPORTED to "true",
      ),
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LlmStructuredFinalMetadataKeys.ANTHROPIC_STRUCTURED_FINAL_TOOL_SUPPORTED])
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
    val tool = JSONObject(capturedBody.get())
      .getJSONArray("tools")
      .let { tools ->
        (0 until tools.length())
          .map { index -> tools.getJSONObject(index) }
          .single { candidate -> candidate.getString("name") == "OpenCrayFinalResponse" }
      }
    assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
    val completion = requireNotNull(success.completion)
    assertTrue(completion.toolCalls.isEmpty())
    assertEquals("Created the image.", completion.finalText)
    assertEquals(1, completion.finalAttachments.size)
    assertEquals("artifact-image-1", completion.finalAttachments.single().artifactId)
    assertEquals("image/png", completion.finalAttachments.single().mimeType)
  }

  @Test
  fun executeOmitsAnthropicStructuredFinalToolForAnthropicProviderWithCustomBaseUrlByDefault() {
    val capturedBody = AtomicReference<String>()
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "anthropic",
      protocol = LlmProviderProtocols.ANTHROPIC,
      model = "claude-3-5-sonnet",
      authHeaders = mapOf("x-api-key" to "test-key"),
      responseBody = """
        {
          "id": "msg_anthropic_provider_custom_base_no_final_tool",
          "content": [
            {
              "type": "text",
              "text": "OK"
            }
          ],
          "stop_reason": "end_turn"
        }
      """.trimIndent(),
      capturedBody = capturedBody,
    )

    assertTrue(result is LiteLlmProviderResult.Success)
    val payload = JSONObject(capturedBody.get())
    assertFalse(payload.has("tools"))
  }

  @Test
  fun executeBuildsOpenAiMultimodalUserMessageWhenVisionInputSupported() {
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
                "id": "req_multimodal_openai",
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

    val imagePath = writeTempImageFile(
      prefix = "openai-multimodal",
      suffix = ".png",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-multimodal",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              "visionInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Describe the uploaded image.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "user-image-1",
                    kind = LiteLlmGatewayAttachmentKind.IMAGE,
                    displayName = "camera-first.png",
                    filePath = imagePath.toString(),
                    mimeType = "image/png",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-multimodal",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      val content = messages.getJSONObject(0).getJSONArray("content")
      assertEquals("text", content.getJSONObject(0).getString("type"))
      assertEquals("Describe the uploaded image.", content.getJSONObject(0).getString("text"))
      assertEquals("image_url", content.getJSONObject(1).getString("type"))
      assertTrue(
        content.getJSONObject(1)
          .getJSONObject("image_url")
          .getString("url")
          .startsWith("data:image/png;base64,"),
      )
    } finally {
      runCatching { Files.deleteIfExists(imagePath) }
      runCatching { Files.deleteIfExists(imagePath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsResponsesMultimodalUserMessageWhenVisionInputSupported() {
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
                "id": "resp_multimodal_openai",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                      { "type": "output_text", "text": "OK" }
                    ]
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

    val imagePath = writeTempImageFile(
      prefix = "responses-multimodal",
      suffix = ".png",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-responses-multimodal",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "visionInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Review this screenshot.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "user-image-1",
                    kind = LiteLlmGatewayAttachmentKind.IMAGE,
                    displayName = "camera-first.png",
                    filePath = imagePath.toString(),
                    mimeType = "image/png",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-responses-multimodal",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val input = payload.getJSONArray("input")
      val message = input.getJSONObject(0)
      val content = message.getJSONArray("content")
      assertEquals("input_text", content.getJSONObject(0).getString("type"))
      assertEquals("Review this screenshot.", content.getJSONObject(0).getString("text"))
      assertEquals("input_image", content.getJSONObject(1).getString("type"))
      assertTrue(
        content.getJSONObject(1)
          .getString("image_url")
          .startsWith("data:image/png;base64,"),
      )
    } finally {
      runCatching { Files.deleteIfExists(imagePath) }
      runCatching { Files.deleteIfExists(imagePath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsAnthropicMultimodalUserMessageWhenVisionInputSupported() {
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
                "id": "msg_multimodal_anthropic",
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

    val imagePath = writeTempImageFile(
      prefix = "anthropic-multimodal",
      suffix = ".png",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-multimodal",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-5-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "visionInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Inspect this photo.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "user-image-1",
                    kind = LiteLlmGatewayAttachmentKind.IMAGE,
                    displayName = "camera-first.png",
                    filePath = imagePath.toString(),
                    mimeType = "image/png",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-multimodal",
            providerId = "anthropic",
            model = "claude-3-5-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val messages = payload.getJSONArray("messages")
      val content = messages.getJSONObject(0).getJSONArray("content")
      assertEquals("text", content.getJSONObject(0).getString("type"))
      assertEquals("Inspect this photo.", content.getJSONObject(0).getString("text"))
      val imageBlock = content.getJSONObject(1)
      assertEquals("image", imageBlock.getString("type"))
      val source = imageBlock.getJSONObject("source")
      assertEquals("base64", source.getString("type"))
      assertEquals("image/png", source.getString("media_type"))
      assertEquals("AQIDBA==", source.getString("data"))
    } finally {
      runCatching { Files.deleteIfExists(imagePath) }
      runCatching { Files.deleteIfExists(imagePath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsOpenAiPdfUserMessageWhenPdfInputSupported() {
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
                "id": "req_pdf_openai",
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

    val pdfPath = writeTempPdfFile(
      prefix = "openai-pdf",
      suffix = ".pdf",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-pdf",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              "pdfInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Summarize this PDF.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "workspace-pdf-1",
                    kind = LiteLlmGatewayAttachmentKind.FILE,
                    displayName = "report.pdf",
                    filePath = pdfPath.toString(),
                    mimeType = "application/pdf",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-pdf",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
      assertEquals("text", content.getJSONObject(0).getString("type"))
      assertEquals("Summarize this PDF.", content.getJSONObject(0).getString("text"))
      assertEquals("file", content.getJSONObject(1).getString("type"))
      val file = content.getJSONObject(1).getJSONObject("file")
      assertEquals("report.pdf", file.getString("filename"))
      assertTrue(file.getString("file_data").startsWith("data:application/pdf;base64,"))
    } finally {
      runCatching { Files.deleteIfExists(pdfPath) }
      runCatching { Files.deleteIfExists(pdfPath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsResponsesPdfUserMessageWhenPdfInputSupported() {
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
                "id": "resp_pdf_openai",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                      { "type": "output_text", "text": "OK" }
                    ]
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

    val pdfPath = writeTempPdfFile(
      prefix = "responses-pdf",
      suffix = ".pdf",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-responses-pdf",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "pdfInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Extract the key points from this PDF.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "workspace-pdf-1",
                    kind = LiteLlmGatewayAttachmentKind.FILE,
                    displayName = "report.pdf",
                    filePath = pdfPath.toString(),
                    mimeType = "application/pdf",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-responses-pdf",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val content = payload.getJSONArray("input")
        .getJSONObject(0)
        .getJSONArray("content")
      assertEquals("input_text", content.getJSONObject(0).getString("type"))
      assertEquals("Extract the key points from this PDF.", content.getJSONObject(0).getString("text"))
      assertEquals("input_file", content.getJSONObject(1).getString("type"))
      assertEquals("report.pdf", content.getJSONObject(1).getString("filename"))
      assertTrue(content.getJSONObject(1).getString("file_data").startsWith("data:application/pdf;base64,"))
    } finally {
      runCatching { Files.deleteIfExists(pdfPath) }
      runCatching { Files.deleteIfExists(pdfPath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsAnthropicPdfUserMessageWhenPdfInputSupported() {
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
                "id": "msg_pdf_anthropic",
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

    val pdfPath = writeTempPdfFile(
      prefix = "anthropic-pdf",
      suffix = ".pdf",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-anthropic-pdf",
            providerId = "anthropic",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "claude-3-7-sonnet",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.ANTHROPIC,
              "pdfInputSupported" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Review this PDF.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "workspace-pdf-1",
                    kind = LiteLlmGatewayAttachmentKind.FILE,
                    displayName = "report.pdf",
                    filePath = pdfPath.toString(),
                    mimeType = "application/pdf",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("x-api-key" to "test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-anthropic-pdf",
            providerId = "anthropic",
            model = "claude-3-7-sonnet",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
      assertEquals("text", content.getJSONObject(0).getString("type"))
      assertEquals("Review this PDF.", content.getJSONObject(0).getString("text"))
      val documentBlock = content.getJSONObject(1)
      assertEquals("document", documentBlock.getString("type"))
      val source = documentBlock.getJSONObject("source")
      assertEquals("base64", source.getString("type"))
      assertEquals("application/pdf", source.getString("media_type"))
      assertEquals("JVBERg==", source.getString("data"))
    } finally {
      runCatching { Files.deleteIfExists(pdfPath) }
      runCatching { Files.deleteIfExists(pdfPath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeFallsBackToAttachmentInventoryWhenVisionInputUnsupported() {
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
                "id": "req_attachment_fallback",
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

    val imagePath = writeTempImageFile(
      prefix = "openai-fallback",
      suffix = ".png",
    )
    try {
      val client = OpenAiCompatibleLiteLlmProviderClient()
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-attachment-fallback",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              "visionInputSupported" to "false",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "Summarize the upload.",
                attachments = listOf(
                  LiteLlmGatewayAttachment(
                    attachmentId = "user-image-1",
                    kind = LiteLlmGatewayAttachmentKind.IMAGE,
                    displayName = "camera-first.png",
                    filePath = imagePath.toString(),
                    mimeType = "image/png",
                  ),
                ),
              ),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-attachment-fallback",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      val content = payload.getJSONArray("messages").getJSONObject(0).getString("content")
      assertTrue(content.contains("Summarize the upload."))
      assertTrue(content.contains("Attachments:"))
      assertTrue(content.contains("camera-first.png"))
      assertTrue(content.contains("kind=image"))
      assertFalse(content.contains("data:image/png;base64"))
    } finally {
      runCatching { Files.deleteIfExists(imagePath) }
      runCatching { Files.deleteIfExists(imagePath.parent) }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }
}
