package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmCompactRequest
import com.opencray.llm.LiteLlmCompactResult
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
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmClientOpenAiResponsesDialectTest : LiteLlmClientTestBase() {
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
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_HINTS_SUPPORTED to "true",
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
  fun compactConversationUsesResponsesCompactEndpointAndParsesCompactionOutput() {
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
                "id": "resp_compact",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                      { "type": "output_text", "text": "Remote compacted summary." }
                    ]
                  },
                  {
                    "type": "compaction",
                    "encrypted_content": "opaque-capsule"
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
      val result = client.compactConversation(
        LiteLlmProviderCompactRequest(
          route = ProviderRoute(
            id = "route-openai-responses",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              "reasoning_effort" to "medium",
              "responsesRemoteCompactionSupported" to "true",
            ),
          ),
          request = LiteLlmCompactRequest(
            gatewayRequest = LiteLlmGatewayRequest(
              prompt = "fallback prompt",
              systemPrompt = "system prompt",
              messages = listOf(
                LiteLlmGatewayMessage(
                  role = LiteLlmGatewayMessageRole.USER,
                  content = "Older task context",
                ),
                LiteLlmGatewayMessage(
                  role = LiteLlmGatewayMessageRole.ASSISTANT,
                  toolCalls = listOf(sampleStructuredToolCall(id = "oc-call-compact")),
                ),
                LiteLlmGatewayMessage(
                  role = LiteLlmGatewayMessageRole.TOOL,
                  toolResult = LiteLlmGatewayToolResult(
                    toolCallId = "oc-call-compact",
                    toolName = "EchoProbe",
                    content = """{"status":"success"}""",
                  ),
                ),
              ),
              tools = listOf(sampleToolDefinition()),
              toolChoice = LiteLlmToolChoice(
                mode = LiteLlmToolChoiceMode.TOOL,
                toolName = "EchoProbe",
              ),
              parallelToolCalls = false,
              previousResponseId = "resp_previous",
              responseApiPreferred = true,
              authHeaders = mapOf("Authorization" to "Bearer test-key"),
            ),
            triggerStage = "pre_compaction",
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
      assertEquals("POST /v1/responses/compact HTTP/1.1", requestLine.get())
      assertTrue(result is LiteLlmCompactResult.Success)
      val success = result as LiteLlmCompactResult.Success
      assertEquals("Remote compacted summary.", success.summaryText)
      assertEquals(2, success.outputItemCount)
      assertEquals(1, success.compactionItemCount)
      assertEquals(1, success.encryptedContentCount)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED])
      assertEquals("pre_compaction", success.metadata[LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE])

      val payload = JSONObject(requestBody.get())
      assertEquals("gpt-5-mini", payload.getString("model"))
      assertFalse(payload.has("previous_response_id"))
      assertFalse(payload.has("stream"))
      assertEquals("system prompt", payload.getString("instructions"))
      assertEquals("medium", payload.getJSONObject("reasoning").getString("effort"))
      assertEquals(false, payload.getBoolean("parallel_tool_calls"))
      val input = payload.getJSONArray("input")
      assertEquals("Older task context", input.getJSONObject(0).getString("content"))
      assertEquals("function_call", input.getJSONObject(1).getString("type"))
      assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
      assertEquals("function", payload.getJSONArray("tools").getJSONObject(0).getString("type"))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
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
  fun executeParsesResponsesCommentaryAndFinalAnswerPhasesTogether() {
    val success = executeWithResponsesResponse(
      """
      {
        "id": "resp_commentary_final",
        "status": "completed",
        "output": [
          {
            "type": "message",
            "role": "assistant",
            "phase": "commentary",
            "content": [
              { "type": "output_text", "text": "Checking the transcript first." }
            ]
          },
          {
            "type": "message",
            "role": "assistant",
            "phase": "final_answer",
            "content": [
              { "type": "output_text", "text": "Here is the final answer." }
            ]
          }
        ]
      }
      """.trimIndent(),
    )

    val completion = requireNotNull(success.completion)
    assertEquals(
      "Checking the transcript first.\nHere is the final answer.",
      success.outputText,
    )
    assertEquals("Checking the transcript first.", completion.commentaryText)
    assertEquals("Here is the final answer.", completion.finalText)
    assertTrue(completion.toolCalls.isEmpty())
    assertEquals("responses_text", success.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
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
  fun executeStreamsOnlyCurrentUserVisibleResponsesPhaseDraft() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_phased_visible"}}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_stream_commentary","type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":""}]}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"I am checking the official timeline first."}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_stream_final","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":""}]}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"The most recent update is about the expansion plan and lawsuit."}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_phased_visible","status":"completed"}}
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
            id = "route-openai-responses-stream-phased-visible",
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
            prompt = "Summarize the latest timeline.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-phased-visible",
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
      assertEquals(
        listOf(
          "I am checking the official timeline first.",
          "The most recent update is about the expansion plan and lawsuit.",
        ),
        visibleDrafts,
      )
      assertEquals("I am checking the official timeline first.", success.completion?.commentaryText)
      assertEquals(
        "The most recent update is about the expansion plan and lawsuit.",
        success.completion?.finalText,
      )
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executePreservesResponsesCommentaryWhenToolCallReusesMessageOutputIndex() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_commentary_tool_same_index"}}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_stream_commentary_same_index","type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":""}]}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"I am checking the latest official sources first."}

              event: response.output_item.done
              data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_stream_same_index","type":"function_call","call_id":"call_stream_same_index","name":"EchoProbe","arguments":"{\"echo\":\"hello\"}"}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_commentary_tool_same_index","status":"completed"}}
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
            id = "route-openai-responses-stream-commentary-tool-same-index",
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
            prompt = "Check first, then call EchoProbe.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-commentary-tool-same-index",
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
      assertEquals(listOf("I am checking the latest official sources first."), visibleDrafts)
      assertEquals(
        "I am checking the latest official sources first.",
        success.completion?.commentaryText,
      )
      assertEquals("EchoProbe", success.completion?.toolCalls?.single()?.toolName)
      assertEquals("\"hello\"", success.completion?.toolCalls?.single()?.arguments?.get("echo")?.toString())
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executePreservesResponsesCommentaryWhenFinalAnswerReusesMessageOutputIndex() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_commentary_final_same_index"}}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_stream_commentary_same_slot","type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":""}]}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"I am checking the timeline first."}

              event: response.output_item.done
              data: {"type":"response.output_item.done","output_index":0,"item":{"id":"msg_stream_final_same_slot","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"The latest update is the product expansion."}]}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_commentary_final_same_index","status":"completed"}}
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
            id = "route-openai-responses-stream-commentary-final-same-index",
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
            prompt = "Check first, then answer.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-commentary-final-same-index",
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
      assertEquals(
        listOf(
          "I am checking the timeline first.",
          "The latest update is the product expansion.",
        ),
        visibleDrafts,
      )
      assertEquals("I am checking the timeline first.", success.completion?.commentaryText)
      assertEquals(
        "The latest update is the product expansion.",
        success.completion?.finalText,
      )
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesTextWhenEventStreamContentTypeIsMissing() {
    val requestBody = AtomicReference<String>()
    val visibleDrafts = mutableListOf<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference(), requestBody)
          writeHttpResponse(
            client = client,
            body = """
              event: response.created
              data: {"type":"response.created","response":{"id":"resp_stream_text_headerless"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":" world"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text_headerless","status":"completed"}}
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
            id = "route-openai-responses-stream-text-headerless",
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
            routeId = "route-openai-responses-stream-text-headerless",
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
  fun executePrefersCompletedResponsesOutputOverPartialStreamText() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_text_authoritative"}}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_stream_text_authoritative","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":""}]}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_stream_text_authoritative","content_index":0,"delta":"Hel"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_text_authoritative","status":"completed","output":[{"id":"msg_stream_text_authoritative","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Hello"}]}]}}
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
            id = "route-openai-responses-stream-text-authoritative-output",
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
            routeId = "route-openai-responses-stream-text-authoritative-output",
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
      assertEquals(listOf("Hel", "Hello"), visibleDrafts)
      assertEquals("Hello", success.outputText)
      assertEquals("Hello", success.completion?.finalText)
      assertTrue(success.completion?.toolCalls.isNullOrEmpty())
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeKeepsOpenAiResponsesFunctionCallWhenCompletedPayloadClearsOutput() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_tool_cleared"}}

              event: response.output_item.done
              data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_stream_resp_1","type":"function_call","call_id":"call_stream_resp_1","name":"EchoProbe","arguments":"{\"echo\":\"hello\"}"}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_tool_cleared","status":"completed","output":[]}}
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
            id = "route-openai-responses-stream-tool-cleared-output",
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
            routeId = "route-openai-responses-stream-tool-cleared-output",
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
  fun executeKeepsResponsesBuiltinWebSearchWhenCompletedOutputOnlyHasFinalAnswer() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_builtin_search"}}

              event: response.output_item.done
              data: {"type":"response.output_item.done","output_index":0,"item":{"id":"ws_stream_1","type":"web_search_call","status":"completed","action":{"type":"search","query":"OpenCray streaming inspector","sources":[{"type":"url_citation","title":"OpenCray","url":"https://example.com/opencray"}]}}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_builtin_search","status":"completed","output":[{"id":"msg_stream_builtin_search","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Search result summarized."}]}]}}
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
            id = "route-openai-responses-stream-builtin-search",
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
            prompt = "Search and summarize.",
            builtinTools = listOf(
              LiteLlmBuiltinToolDefinition(type = LiteLlmBuiltinToolType.WEB_SEARCH),
            ),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-builtin-search",
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
      assertEquals("Search result summarized.", success.completion?.finalText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED])
      val observations = JSONArray(
        success.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON],
      )
      assertEquals(1, observations.length())
      assertEquals(
        "OpenCray streaming inspector",
        observations.getJSONObject(0).getJSONArray("queries").getString(0),
      )
      assertEquals(
        "https://example.com/opencray",
        observations
          .getJSONObject(0)
          .getJSONArray("sources")
          .getJSONObject(0)
          .getString("url"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeDropsStreamOnlyFunctionCallWhenCompletedResponsesOutputHasOnlyFinalAnswer() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_tool_replaced"}}

              event: response.output_item.done
              data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_stream_resp_replaced_1","type":"function_call","call_id":"call_stream_resp_replaced_1","name":"EchoProbe","arguments":"{\"echo\":\"hello\"}"}}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_tool_replaced","status":"completed","output":[{"id":"msg_stream_resp_replaced_1","type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Hello"}]}]}}
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
            id = "route-openai-responses-stream-tool-replaced-output",
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
            prompt = "Answer without a tool in the final payload.",
            tools = listOf(sampleToolDefinition()),
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-tool-replaced-output",
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
      assertEquals("Hello", success.outputText)
      assertEquals("Hello", success.completion?.finalText)
      assertTrue(success.completion?.toolCalls.isNullOrEmpty())
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsOpenAiResponsesFunctionCallArgumentDeltasWithoutOutputItemDone() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_tool_delta"}}

              event: response.output_item.added
              data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_stream_resp_delta_1","type":"function_call","call_id":"call_stream_resp_delta_1","name":"EchoProbe","arguments":""}}

              event: response.function_call_arguments.delta
              data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_stream_resp_delta_1","delta":"{\"echo\":\"hel"}

              event: response.function_call_arguments.delta
              data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_stream_resp_delta_1","delta":"lo\"}"}

              event: response.function_call_arguments.done
              data: {"type":"response.function_call_arguments.done","output_index":0,"item_id":"fc_stream_resp_delta_1","arguments":"{\"echo\":\"hello\"}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_tool_delta","status":"completed","output":[]}}
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
            id = "route-openai-responses-stream-tool-delta",
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
            routeId = "route-openai-responses-stream-tool-delta",
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
  fun executeStreamsOnlyStructuredFinalAnswerDraftFromOpenAiResponses() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_final"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"type\":\"final\",\"answer\":\"Hel"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"lo\"}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_final","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-final",
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
            routeId = "route-openai-responses-stream-structured-final",
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
      assertEquals(listOf("Hel", "Hello"), visibleDrafts)
      assertEquals("{\"type\":\"final\",\"answer\":\"Hello\"}", success.outputText)
      assertEquals("{\"type\":\"final\",\"answer\":\"Hello\"}", success.completion?.rawText)
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeStreamsStructuredActionsDraftTextFromOpenAiResponses() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_actions"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":",{\"type\":\"final\",\"answer\":\"Here is"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":" the final answer.\"}]}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_actions","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-actions",
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
            prompt = "Check first, then answer.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-structured-actions",
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
      assertEquals(
        listOf(
          "Checking the transcript first.",
          "Here is",
          "Here is the final answer.",
        ),
        visibleDrafts,
      )
      assertEquals(
        "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"final\",\"answer\":\"Here is the final answer.\"}]}",
        success.outputText,
      )
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCallFromOpenAiResponses() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_actions_tool_final"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":",{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_actions_tool_final","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-actions-tool-final",
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
            routeId = "route-openai-responses-stream-structured-actions-tool-final",
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
      assertEquals(listOf("Checking the transcript first."), visibleDrafts)
      assertEquals(
        "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}",
        success.outputText,
      )
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSuppressesPartialStructuredResponsesJsonPrefixesUntilAnswerAppears() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_prefix"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"\"type\":\"final\",\"answer\":\"Hel"}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"lo\"}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_prefix","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-prefix",
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
            routeId = "route-openai-responses-stream-structured-prefix",
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
      assertEquals(listOf("Hel", "Hello"), visibleDrafts)
      assertEquals("{\"type\":\"final\",\"answer\":\"Hello\"}", success.outputText)
      assertEquals("{\"type\":\"final\",\"answer\":\"Hello\"}", success.completion?.rawText)
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSuppressesStructuredToolDraftFromOpenAiResponses() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_tool"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}}]}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_tool","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-tool",
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
            prompt = "Use a tool.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-structured-tool",
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
      assertTrue(visibleDrafts.isEmpty())
      assertEquals(
        "{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}}]}",
        success.outputText,
      )
      assertEquals("completed", success.finishReason)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeSuppressesNestedActionsInsideStructuredToolDraftsFromOpenAiResponses() {
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
              data: {"type":"response.created","response":{"id":"resp_stream_structured_tool_nested_actions"}}

              event: response.output_text.delta
              data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"actions\":[{\"type\":\"final\",\"answer\":\"leak\"}],\"file_path\":\"README.md\"}}]}"}

              event: response.completed
              data: {"type":"response.completed","response":{"id":"resp_stream_structured_tool_nested_actions","status":"completed"}}
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
            id = "route-openai-responses-stream-structured-tool-nested-actions",
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
            prompt = "Use a tool.",
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
            streamObserver = object : LiteLlmVisibleTextObserver {
              override fun onVisibleTextSnapshot(text: String) {
                visibleDrafts += text
              }
            },
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-stream-structured-tool-nested-actions",
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
      assertTrue(visibleDrafts.isEmpty())
      assertEquals(
        "{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"actions\":[{\"type\":\"final\",\"answer\":\"leak\"}],\"file_path\":\"README.md\"}}]}",
        success.outputText,
      )
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
  fun executeSkipsOpenAiResponsesPromptCacheHintsForOpenAiProviderWithCustomBaseUrlByDefault() {
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
                "id": "resp_prompt_cache_openai_custom_base",
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
            id = "route-openai-responses-prompt-cache-openai-custom-base",
            providerId = "openai",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "gpt-5-mini",
            timeoutMs = 5_000L,
            metadata = mapOf(
              "protocol" to LlmProviderProtocols.OPENAI_RESPONSES,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_KEY_STRATEGY to
                LlmPromptCacheKeyStrategies.SESSION,
              LlmPromptCachingMetadataKeys.PROMPT_CACHE_RETENTION to
                LlmPromptCacheRetentionPolicies.HOURS_24,
            ),
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            metadata = mapOf("sessionId" to "session-123"),
            responseApiPreferred = true,
            authHeaders = mapOf("Authorization" to "Bearer test-key"),
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-openai-responses-prompt-cache-openai-custom-base",
            providerId = "openai",
            model = "gpt-5-mini",
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
}
