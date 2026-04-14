package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
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
  fun executeBuildsResponsesRequestWithPreviousResponseIdAndStructuredInput() {
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
                "id": "resp_test",
                "status": "completed",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "phase": "final_answer",
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

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-responses",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "reasoning_effort" to "medium",
              "responsesContinuationSupported" to "true",
              "assistantPhaseSupported" to "true",
              "citationIncludeSupported" to "true",
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY to
                LlmPromptCacheKeyStrategies.ROUTE,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION to
                LlmPromptCacheRetentionPolicies.IN_MEMORY,
            ),
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
                content = "Inspecting the workspace before the tool call.",
                assistantPhase = LiteLlmAssistantPhase.COMMENTARY,
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
                  stdout = "probe ok",
                ),
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                includeSources = true,
              ),
            ),
            toolChoice = LiteLlmToolChoice(
              mode = LiteLlmToolChoiceMode.TOOL,
              toolName = "EchoProbe",
            ),
            parallelToolCalls = false,
            previousResponseId = "resp_previous",
            responseApiPreferred = true,
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
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

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/responses HTTP/1.1", requestLine.get())
      assertEquals(
        "OpenCray/1.0.0-test (Android; host-runtime)",
        userAgent.get(),
      )
      val success = result as LiteLlmProviderResult.Success
      assertEquals("resp_previous", success.providerLineageId)
      val payload = JSONObject(requestBody.get())
      assertEquals("resp_previous", payload.getString("previous_response_id"))
      assertEquals("system prompt", payload.getString("instructions"))
      assertEquals("medium", payload.getJSONObject("reasoning").getString("effort"))
      assertEquals(
        llmRouteFingerprint(
          LlmProviderProtocols.OPENAI_RESPONSES,
          "http://127.0.0.1:${server.localPort}/v1",
          "gpt-5-mini",
        ),
        payload.getString("prompt_cache_key"),
      )
      assertEquals(
        LlmPromptCacheRetentionPolicies.IN_MEMORY,
        payload.getString("prompt_cache_retention"),
      )
      assertEquals(false, payload.getBoolean("parallel_tool_calls"))
      assertEquals(
        "web_search_call.action.sources",
        payload.getJSONArray("include").getString(0),
      )
      val toolChoice = payload.getJSONObject("tool_choice")
      assertEquals("function", toolChoice.getString("type"))
      assertEquals("EchoProbe", toolChoice.getString("name"))
      val tools = payload.getJSONArray("tools")
      assertEquals("web_search", tools.getJSONObject(0).getString("type"))
      assertEquals("function", tools.getJSONObject(1).getString("type"))
      assertEquals("EchoProbe", tools.getJSONObject(1).getString("name"))
      val input = payload.getJSONArray("input")
      assertEquals("message", input.getJSONObject(0).getString("type"))
      assertEquals("user", input.getJSONObject(0).getString("role"))
      assertEquals("Task context", input.getJSONObject(0).getString("content"))
      assertEquals("message", input.getJSONObject(1).getString("type"))
      assertEquals("assistant", input.getJSONObject(1).getString("role"))
      assertEquals("commentary", input.getJSONObject(1).getString("phase"))
      assertEquals(
        "Inspecting the workspace before the tool call.",
        input.getJSONObject(1).getString("content"),
      )
      assertEquals("function_call", input.getJSONObject(2).getString("type"))
      assertEquals("EchoProbe", input.getJSONObject(2).getString("name"))
      assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
      assertEquals("oc-call-1", input.getJSONObject(3).getString("call_id"))
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_KEY_PRESENT])
      assertEquals(
        LlmPromptCacheRetentionPolicies.IN_MEMORY,
        success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
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
  fun executeResponsesRouteTreatsMissingFunctionCallIdAsRecoverableProtocolError() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), AtomicReference())
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "resp_test",
                "status": "completed",
                "output": [
                  {
                    "type": "function_call",
                    "name": "EchoProbe",
                    "arguments": "{\"echo\":\"hello\"}"
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-responses",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
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

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertTrue(result is LiteLlmProviderResult.Success)
      result as LiteLlmProviderResult.Success
      assertTrue(result.completion?.toolCalls?.isEmpty() == true)
      assertEquals(
        listOf("output[0].call_id must be a non-blank string."),
        result.completion?.toolCallErrors,
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeResponsesRouteTreatsDuplicateFunctionCallIdAsRecoverableProtocolError() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), AtomicReference())
          writeHttpResponse(
            client = client,
            body = """
              {
                "id": "resp_test",
                "status": "completed",
                "output": [
                  {
                    "type": "function_call",
                    "call_id": "call_duplicate",
                    "name": "EchoProbe",
                    "arguments": "{\"echo\":\"hello\"}"
                  },
                  {
                    "type": "function_call",
                    "call_id": "call_duplicate",
                    "name": "EchoProbe",
                    "arguments": "{\"echo\":\"again\"}"
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-responses",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
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

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertTrue(result is LiteLlmProviderResult.Success)
      result as LiteLlmProviderResult.Success
      assertEquals(
        listOf("output[1].call_id duplicates tool call id 'call_duplicate'."),
        result.completion?.toolCallErrors,
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeResponsesRouteDoesNotAssumeOptionalCapabilitiesWithoutMetadata() {
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
                "id": "resp_test",
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

    try {
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-responses",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "fallback prompt",
            messages = listOf(
              LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                content = "Inspect first",
                assistantPhase = LiteLlmAssistantPhase.COMMENTARY,
              ),
            ),
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(
                type = LiteLlmBuiltinToolType.WEB_SEARCH,
                includeSources = true,
              ),
            ),
            previousResponseId = "resp_previous",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
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

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertFalse(payload.has("previous_response_id"))
      assertFalse(payload.has("include"))
      val input = payload.getJSONArray("input")
      assertFalse(input.getJSONObject(0).has("phase"))
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
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("openai_tool_calls", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
  }

  @Test
  fun executeCapturesOpenAiProgressAndReasoningAlongsideNativeToolCalls() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_tool_call_with_progress",
        "choices": [
          {
            "message": {
              "content": "Updating the todo list now.",
              "reasoning_content": "Todo sync still needs one write.",
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
    assertEquals("Updating the todo list now.", completion.commentaryText)
    assertEquals("Todo sync still needs one write.", completion.reasoningText)
    assertEquals("TodoWrite", completion.toolCalls.single().toolName)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
    assertEquals("1", success.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_TURN_COUNT])
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
    assertTrue(requireNotNull(success.completion).reasoningText.isNullOrBlank())
    assertEquals("openai_reasoning_protocol", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
  }

  @Test
  fun executePreservesMalformedOpenAiToolCallDiagnostics() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_tool_call_malformed",
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
                    "arguments": "{\"todos\":[{\"content\":\"Broken payload\"}"
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
    assertTrue(completion.toolCalls.isEmpty())
    assertEquals(1, completion.toolCallErrors.size)
    assertTrue(completion.toolCallErrors.single().contains("tool_calls[0].function.arguments"))
    assertTrue(completion.toolCallErrors.single().contains("Parser error"))
    assertEquals("openai_tool_calls", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
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
      expectSuccess = false,
    )

    val failure = result as LiteLlmProviderResult.Failure
    assertEquals("PROVIDER_EMPTY_RESPONSE", failure.errorCode)
    assertEquals("I should probably call TodoWrite next.", requireNotNull(failure.completion).reasoningText)
    assertEquals("openai_reasoning_text", failure.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("false", failure.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
    assertEquals("true", failure.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
    assertEquals("1", failure.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_TURN_COUNT])
  }

  @Test
  fun executeParsesResponsesToolCallsReasoningAndCommentary() {
    val result = executeWithResponsesResponse(
      """
      {
        "id": "resp_tool_call",
        "status": "completed",
        "output": [
          {
            "type": "reasoning",
            "summary": [
              { "type": "summary_text", "text": "Need one todo write before answering." }
            ]
          },
          {
            "type": "message",
            "role": "assistant",
            "phase": "commentary",
            "content": [
              { "type": "output_text", "text": "Updating the todo list now." }
            ]
          },
          {
            "type": "function_call",
            "call_id": "call_1",
            "name": "TodoWrite",
            "arguments": "{\"todos\":[{\"content\":\"Ship update entry\",\"status\":\"in_progress\"}]}",
            "status": "completed"
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    val completion = requireNotNull(success.completion)
    assertEquals("completed", success.finishReason)
    assertEquals("Updating the todo list now.", success.outputText)
    assertEquals("Updating the todo list now.", completion.commentaryText)
    assertEquals("Need one todo write before answering.", completion.reasoningText)
    assertEquals("call_1", completion.toolCalls.single().id)
    assertEquals("TodoWrite", completion.toolCalls.single().toolName)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals("responses_text_and_tool_calls", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED])
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
  }

  @Test
  fun executeParsesResponsesFinalAnswerPhase() {
    val result = executeWithResponsesResponse(
      """
      {
        "id": "resp_final",
        "status": "completed",
        "output": [
          {
            "type": "message",
            "role": "assistant",
            "phase": "final_answer",
            "content": [
              { "type": "output_text", "text": "All set." }
            ]
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    val completion = requireNotNull(success.completion)
    assertEquals("All set.", success.outputText)
    assertEquals("All set.", completion.finalText)
    assertTrue(completion.commentaryText.isNullOrBlank())
    assertTrue(completion.toolCalls.isEmpty())
    assertEquals("responses_text", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
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
  fun executeStreamsOpenAiChatCompletionTextResponses() {
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
              data: {"id":"chatcmpl_stream_text","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}
              
              data: {"id":"chatcmpl_stream_text","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}
              
              data: {"id":"chatcmpl_stream_text","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
              
              data: [DONE]
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
            id = "route-openai-chat-stream-text",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-text",
            providerId = "openai",
            model = "gpt-4o-mini",
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
      assertEquals("stop", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiChatCompletionToolCallResponses() {
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
              data: {"id":"chatcmpl_stream_tool","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_stream_1","type":"function","function":{"name":"EchoProbe","arguments":"{\"echo\":"}}]},"finish_reason":null}]}
              
              data: {"id":"chatcmpl_stream_tool","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"hello\"}"}}]},"finish_reason":null}]}
              
              data: {"id":"chatcmpl_stream_tool","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
              
              data: [DONE]
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
            id = "route-openai-chat-stream-tool",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Call EchoProbe.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-tool",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("tool_calls", success.finishReason)
      assertEquals("EchoProbe", success.completion?.toolCalls?.single()?.toolName)
      assertEquals("\"hello\"", success.completion?.toolCalls?.single()?.arguments?.get("echo")?.toString())
      assertTrue(success.outputText.contains("\"tool_name\":\"EchoProbe\""))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesTextResponses() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_text"}}
              
              event: response.output_item.added
              data: {"type":"response.output_item.added","item":{"id":"msg_stream_text","type":"message","role":"assistant","content":[{"type":"output_text","text":""}]}}
              
              event: response.output_text.delta
              data: {"type":"response.output_text.delta","delta":"Hello"}
              
              event: response.output_text.delta
              data: {"type":"response.output_text.delta","delta":" world"}
              
              event: response.output_item.done
              data: {"type":"response.output_item.done","item":{"id":"msg_stream_text","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello world"}]}}
              
              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text","status":"completed","usage":{"input_tokens":1,"input_tokens_details":{"cached_tokens":0},"output_tokens":2,"output_tokens_details":null,"total_tokens":3}}}
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
            id = "route-openai-responses-stream-text",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-text",
            providerId = "openai",
            model = "gpt-5-mini",
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
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesTextResponsesByDefaultWithoutWaitingForFlush() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_text_default"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":" world"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text_default","status":"completed"}}
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
            id = "route-openai-responses-stream-text-default",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-text-default",
            providerId = "openai",
            model = "gpt-5-mini",
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
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeKeepsOpenAiResponsesStreamTextWhenCompletedPayloadClearsOutput() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_text_lost"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":" world"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text_lost","status":"completed","output":[]}}
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
            id = "route-openai-responses-stream-text-cleared-output",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-text-cleared-output",
            providerId = "openai",
            model = "gpt-5-mini",
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
      assertEquals("Hello world", success.completion?.finalText)
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesTextWhenOnlyOutputTextDoneIsSent() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_text_done"}}

              event: response.output_text.done
              data: {"type":"response.output_text.done","output_index":0,"content_index":0,"text":"Hello from done"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text_done","status":"completed"}}
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
            id = "route-openai-responses-stream-text-done",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-text-done",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals(listOf("Hello from done"), visibleDrafts)
      assertEquals("Hello from done", success.outputText)
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesTextFromContentPartEvents() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_content_part"}}

              event: response.content_part.added
              data: {"type":"response.content_part.added","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}

              event: response.content_part.done
              data: {"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Hello from content part"}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_content_part","status":"completed"}}
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
            id = "route-openai-responses-stream-content-part",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Say hello.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-content-part",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals(listOf("Hello from content part"), visibleDrafts)
      assertEquals("Hello from content part", success.outputText)
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesFunctionCallResponses() {
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
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_tool"}}
              
              event: response.output_item.done
              data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_stream_resp_1","name":"EchoProbe","arguments":"{\"echo\":\"hello\"}"}}
              
              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_tool","status":"completed"}}
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
            id = "route-openai-responses-stream-tool",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "stream" to "true",
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Call EchoProbe.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-tool",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(true, payload.getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("completed", success.finishReason)
      assertEquals("EchoProbe", success.completion?.toolCalls?.single()?.toolName)
      assertEquals("\"hello\"", success.completion?.toolCalls?.single()?.arguments?.get("echo")?.toString())
      assertTrue(success.outputText.contains("\"tool_name\":\"EchoProbe\""))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
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
                  structuredContent = buildJsonObject {
                    put("probe", "ok")
                  },
                  exitCode = 0,
                  stdout = "probe ok",
                  metadata = mapOf("source" to "unit-test"),
                ),
              ),
            ),
            tools = listOf(sampleToolDefinition()),
            toolChoice = LiteLlmToolChoice(
              mode = LiteLlmToolChoiceMode.TOOL,
              toolName = "EchoProbe",
            ),
            parallelToolCalls = false,
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
      assertEquals(false, payload.getBoolean("parallel_tool_calls"))
      val toolChoice = payload.getJSONObject("tool_choice")
      assertEquals("function", toolChoice.getString("type"))
      assertEquals("EchoProbe", toolChoice.getJSONObject("function").getString("name"))
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
      val toolContent = JSONObject(tool.getString("content"))
      assertEquals("""{"tool_name":"EchoProbe","status":"success"}""", toolContent.getString("content"))
      assertEquals(0, toolContent.getInt("exit_code"))
      assertEquals("probe ok", toolContent.getString("stdout"))
      assertEquals("ok", toolContent.getJSONObject("structured_content").getString("probe"))
      assertEquals("unit-test", toolContent.getJSONObject("metadata").getString("source"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsOpenAiPromptCacheHintsWhenConfigured() {
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
                "id": "req_prompt_cache_request",
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-prompt-cache",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY to
                LlmPromptCacheKeyStrategies.SESSION,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION to
                LlmPromptCacheRetentionPolicies.HOURS_24,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            metadata = mapOf("sessionId" to "session-123"),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-prompt-cache",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(
        "${llmRouteFingerprint(LlmProviderProtocols.OPENAI, "http://127.0.0.1:${server.localPort}/v1", "gpt-4o-mini")}|session=session-123",
        payload.getString("prompt_cache_key"),
      )
      assertEquals(
        LlmPromptCacheRetentionPolicies.HOURS_24,
        payload.getString("prompt_cache_retention"),
      )
      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_KEY_PRESENT])
      assertEquals(
        LlmPromptCacheRetentionPolicies.HOURS_24,
        success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSkipsOpenAiPromptCacheHintsForCustomRouteWithoutExplicitSupport() {
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
                "id": "req_prompt_cache_custom",
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-prompt-cache-custom",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY to
                LlmPromptCacheKeyStrategies.SESSION,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION to
                LlmPromptCacheRetentionPolicies.HOURS_24,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            metadata = mapOf("sessionId" to "session-123"),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-prompt-cache-custom",
            providerId = "custom",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertFalse(payload.has("prompt_cache_key"))
      assertFalse(payload.has("prompt_cache_retention"))
      val success = result as LiteLlmProviderResult.Success
      assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_KEY_PRESENT))
      assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeBuildsOpenAiPromptCacheHintsForCustomRouteWhenExplicitlyEnabled() {
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
                "id": "req_prompt_cache_custom_enabled",
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
      val client = OpenAiCompatibleLiteLlmProviderClient()
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-openai-prompt-cache-custom-enabled",
            providerId = "custom",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-4o-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_HINTS_SUPPORTED to "true",
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY to
                LlmPromptCacheKeyStrategies.SESSION,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION to
                LlmPromptCacheRetentionPolicies.HOURS_24,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            metadata = mapOf("sessionId" to "session-456"),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-prompt-cache-custom-enabled",
            providerId = "custom",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val payload = JSONObject(requestBody.get())
      assertEquals(
        "${llmRouteFingerprint(LlmProviderProtocols.OPENAI, "http://127.0.0.1:${server.localPort}/v1", "gpt-4o-mini")}|session=session-456",
        payload.getString("prompt_cache_key"),
      )
      assertEquals(
        LlmPromptCacheRetentionPolicies.HOURS_24,
        payload.getString("prompt_cache_retention"),
      )
      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_KEY_PRESENT])
      assertEquals(
        LlmPromptCacheRetentionPolicies.HOURS_24,
        success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_RETENTION],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
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
  fun executeCapturesOpenAiPromptCacheReadTokensFromChatUsage() {
    val result = executeWithOpenAiResponse(
      """
        {
          "id": "req_prompt_cache_openai",
          "choices": [
            {
              "message": { "content": "OK" },
              "finish_reason": "stop"
            }
          ],
          "usage": {
            "prompt_tokens": 1280,
            "completion_tokens": 6,
            "total_tokens": 1286,
            "prompt_tokens_details": {
              "cached_tokens": 1024
            }
          }
        }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
    assertEquals("1024", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS])
    assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_WRITE_TOKENS))
  }

  @Test
  fun executeCapturesZeroOpenAiPromptCacheReadTokensFromResponsesUsage() {
    val result = executeWithResponsesResponse(
      """
        {
          "id": "resp_prompt_cache_zero",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "role": "assistant",
              "content": [
                { "type": "output_text", "text": "OK" }
              ]
            }
          ],
          "usage": {
            "input_tokens": 900,
            "input_tokens_details": {
              "cached_tokens": 0
            },
            "output_tokens": 12,
            "total_tokens": 912
          }
        }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("false", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
    assertEquals("0", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS])
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

  private fun executeWithOpenAiResponse(
    body: String,
    expectSuccess: Boolean = true,
  ): LiteLlmProviderResult {
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
      if (!expectSuccess) {
        result
      } else {
        when (result) {
          is LiteLlmProviderResult.Success -> result
          is LiteLlmProviderResult.Failure -> throw AssertionError(
            "Expected OpenAI success but got failure ${result.errorCode}: ${result.errorMessage}; metadata=${result.metadata}",
          )
          is LiteLlmProviderResult.Timeout -> throw AssertionError(
            "Expected OpenAI success but got timeout: ${result.errorMessage}; metadata=${result.metadata}",
          )
          is LiteLlmProviderResult.RateLimited -> throw AssertionError(
            "Expected OpenAI success but got rate limited: ${result.errorMessage}; metadata=${result.metadata}",
          )
        }
      }
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun executeWithAnthropicResponse(body: String): LiteLlmProviderResult.Success {
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
      when (result) {
        is LiteLlmProviderResult.Success -> result
        is LiteLlmProviderResult.Failure -> throw AssertionError(
          "Expected Anthropic success but got failure ${result.errorCode}: ${result.errorMessage}; metadata=${result.metadata}",
        )
        is LiteLlmProviderResult.Timeout -> throw AssertionError(
          "Expected Anthropic success but got timeout: ${result.errorMessage}; metadata=${result.metadata}",
        )
        is LiteLlmProviderResult.RateLimited -> throw AssertionError(
          "Expected Anthropic success but got rate limited: ${result.errorMessage}; metadata=${result.metadata}",
        )
      }
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun executeWithResponsesResponse(body: String): LiteLlmProviderResult.Success {
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
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI_RESPONSES),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-test",
            providerId = "openai",
            model = "gpt-5-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/responses HTTP/1.1", requestLine.get())
      assertNotNull(userAgent.get())
      when (result) {
        is LiteLlmProviderResult.Success -> result
        is LiteLlmProviderResult.Failure -> throw AssertionError(
          "Expected Responses success but got failure ${result.errorCode}: ${result.errorMessage}; metadata=${result.metadata}",
        )
        is LiteLlmProviderResult.Timeout -> throw AssertionError(
          "Expected Responses success but got timeout: ${result.errorMessage}; metadata=${result.metadata}",
        )
        is LiteLlmProviderResult.RateLimited -> throw AssertionError(
          "Expected Responses success but got rate limited: ${result.errorMessage}; metadata=${result.metadata}",
        )
      }
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
    statusCode: Int = 200,
    statusText: String = "OK",
    body: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 $statusCode $statusText\r\n")
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

  private fun writeHttpEventStreamResponse(
    client: Socket,
    body: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: text/event-stream\r\n")
      append("Cache-Control: no-cache\r\n")
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

  private fun writeTempImageFile(
    prefix: String,
    suffix: String,
  ): Path {
    val directory = Files.createTempDirectory("opencray-provider-client-test-")
    val path = directory.resolve("$prefix$suffix")
    Files.write(path, byteArrayOf(1, 2, 3, 4))
    return path
  }

  private fun writeTempPdfFile(
    prefix: String,
    suffix: String,
  ): Path {
    val directory = Files.createTempDirectory("opencray-provider-client-test-")
    val path = directory.resolve("$prefix$suffix")
    Files.write(path, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    return path
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
