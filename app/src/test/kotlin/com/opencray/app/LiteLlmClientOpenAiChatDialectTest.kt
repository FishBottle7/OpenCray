package com.opencray.app

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiteLlmClientOpenAiChatDialectTest : LiteLlmClientTestBase() {
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
  fun executeParsesOpenAiProtocolFinalAttachmentsIntoStructuredCompletion() {
    val result = executeWithOpenAiResponse(
      """
      {
        "id": "req_final_attachments",
        "choices": [
          {
            "message": {
              "content": "{\"attachments\":[{\"artifact_id\":\"artifact-image-1\",\"kind\":\"image\",\"display_name\":\"diagram.png\",\"mime_type\":\"image/png\"},{\"relative_path\":\"outputs/voice.m4a\",\"kind\":\"voice\",\"duration_ms\":3200,\"waveform_bars\":[10,20,30],\"transcript_text\":\"Voice summary\"}]}"
            },
            "finish_reason": "stop"
          }
        ]
      }
      """.trimIndent(),
    )

    val success = result as LiteLlmProviderResult.Success
    val completion = requireNotNull(success.completion)
    assertTrue(completion.finalText.isNullOrBlank())
    assertEquals(2, completion.finalAttachments.size)
    assertEquals("artifact-image-1", completion.finalAttachments.first().artifactId)
    assertEquals("image", completion.finalAttachments.first().kind)
    assertEquals("diagram.png", completion.finalAttachments.first().displayName)
    assertEquals("image/png", completion.finalAttachments.first().mimeType)
    assertEquals("outputs/voice.m4a", completion.finalAttachments.last().relativePath)
    assertEquals("voice", completion.finalAttachments.last().kind)
    assertEquals(3_200L, completion.finalAttachments.last().durationMs)
    assertEquals(listOf(10, 20, 30), completion.finalAttachments.last().waveformBars)
    assertEquals("Voice summary", completion.finalAttachments.last().transcriptText)
    assertEquals(
      "{\"attachments\":[{\"artifact_id\":\"artifact-image-1\",\"kind\":\"image\",\"display_name\":\"diagram.png\",\"mime_type\":\"image/png\"},{\"relative_path\":\"outputs/voice.m4a\",\"kind\":\"voice\",\"duration_ms\":3200,\"waveform_bars\":[10,20,30],\"transcript_text\":\"Voice summary\"}]}",
      completion.rawText,
    )
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
      assertEquals(
        true,
        payload.getJSONObject("stream_options").getBoolean("include_usage"),
      )
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
  fun executeStreamsOpenAiInStreamErrorAsNonTransientProviderFailure() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpEventStreamResponse(
            client = client,
            body = """
              data: {"id":"chatcmpl_stream_error","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Partial ans"},"finish_reason":null}]}
              
              data: {"error":{"code":"context_length_exceeded","type":"invalid_request_error","message":"This model's maximum context length is 40971 tokens. However, your messages resulted in 50012 tokens."}}
              
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
            id = "route-openai-chat-stream-error",
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
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-error",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val failure = result as LiteLlmProviderResult.Failure
      assertEquals("PROVIDER_FAILURE", failure.errorCode)
      assertFalse(failure.errorCode == "PROVIDER_TRANSPORT_ERROR")
      assertTrue(failure.errorMessage.contains("maximum context length"))
      assertEquals("true", failure.metadata[LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_EVENT])
      assertEquals("context_length_exceeded", failure.metadata[LiteLlmMetadataKeys.PROVIDER_STREAM_ERROR_TYPE])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiChatCompletionUsageIntoPromptCacheMetadata() {
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
              data: {"id":"chatcmpl_stream_usage","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}
              
              data: {"id":"chatcmpl_stream_usage","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":128,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":96}}}
              
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
            id = "route-openai-chat-stream-usage",
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
            prompt = "Reply with OK.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-usage",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals(true, JSONObject(requestBody.get()).getBoolean("stream"))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("OK", success.outputText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_USED])
      assertEquals("96", success.metadata[LiteLlmMetadataKeys.PROVIDER_PROMPT_CACHE_READ_TOKENS])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCallFromOpenAiChatCompletions() {
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
              data: {"id":"chatcmpl_stream_structured_actions_tool_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_structured_actions_tool_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":",{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_structured_actions_tool_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-openai-chat-stream-structured-actions-tool-final",
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
            prompt = "Check first, use a tool, then answer.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-structured-actions-tool-final",
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
      assertEquals(
        listOf("Checking the transcript first."),
        visibleDrafts,
      )
      assertEquals(
        "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}",
        success.outputText,
      )
      assertEquals("stop", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsTopLevelStructuredCommentaryDraftTextFromOpenAiChatCompletions() {
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
              data: {"id":"chatcmpl_stream_top_level_commentary","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"{\"type\":\"commentary\",\"text\":\"Che"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_commentary","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"cking the timeline first.\"}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_commentary","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-openai-chat-stream-top-level-commentary",
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
            prompt = "Report commentary only.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-top-level-commentary",
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
      assertEquals(
        listOf("Che", "Checking the timeline first."),
        visibleDrafts,
      )
      assertEquals(
        "{\"type\":\"commentary\",\"text\":\"Checking the timeline first.\"}",
        success.outputText,
      )
      assertEquals("stop", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsTopLevelStructuredProgressDraftTextFromOpenAiChatCompletions() {
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
              data: {"id":"chatcmpl_stream_top_level_progress","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"{\"type\":\"progress\",\"text\":\"Che"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_progress","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"cking the timeline\"}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_progress","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-openai-chat-stream-top-level-progress",
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
            prompt = "Report progress only.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-top-level-progress",
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
      assertEquals(listOf("Che", "Checking the timeline"), visibleDrafts)
      assertEquals(
        "{\"type\":\"progress\",\"text\":\"Checking the timeline\"}",
        success.outputText,
      )
      assertEquals("stop", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsTopLevelStructuredStatusDraftTextFromOpenAiChatCompletions() {
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
              data: {"id":"chatcmpl_stream_top_level_status","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"{\"type\":\"status\",\"text\":\"Che"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_status","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"cking the timeline\"}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_status","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-openai-chat-stream-top-level-status",
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
            prompt = "Report status only.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-top-level-status",
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
      assertEquals(listOf("Che", "Checking the timeline"), visibleDrafts)
      assertEquals(
        "{\"type\":\"status\",\"text\":\"Checking the timeline\"}",
        success.outputText,
      )
      assertEquals("stop", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsTopLevelStructuredFinalDraftTextFromOpenAiChatCompletions() {
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
              data: {"id":"chatcmpl_stream_top_level_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"{\"type\":\"final\",\"answer\":\"Hel"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"lo\"}"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_top_level_final","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-openai-chat-stream-top-level-final",
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
            prompt = "Report final answer only.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-chat-stream-top-level-final",
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
      assertEquals(listOf("Hel", "Hello"), visibleDrafts)
      assertEquals(
        "{\"type\":\"final\",\"answer\":\"Hello\"}",
        success.outputText,
      )
      assertEquals("stop", success.finishReason)
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
      assertFalse(payload.has("stream_options"))
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
  fun executeBuildsOpenAiPromptCacheHintsWhenExplicitlySupported() {
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
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_HINTS_SUPPORTED to "true",
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
  fun executeSkipsOpenAiPromptCacheHintsForOpenAiProviderWithCustomBaseUrlByDefault() {
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
                "id": "req_prompt_cache_openai_custom_base",
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
            id = "route-openai-prompt-cache-openai-custom-base",
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
            routeId = "route-openai-prompt-cache-openai-custom-base",
            providerId = "openai",
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
}
