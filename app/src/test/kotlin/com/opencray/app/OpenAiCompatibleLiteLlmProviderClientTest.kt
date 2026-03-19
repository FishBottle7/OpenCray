package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.ProviderRoute
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    assertEquals("tool_calls", success.finishReason)
    assertTrue(success.outputText.contains("\"tool_calls\""))
    assertTrue(success.outputText.contains("\"tool_name\":\"TodoWrite\""))
    assertTrue(success.outputText.contains("\"content\":\"Ship update entry\""))
    assertTrue(success.outputText.contains("\"status\":\"in_progress\""))
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

  private fun readHttpRequest(
    client: Socket,
    requestLine: AtomicReference<String>,
    userAgent: AtomicReference<String>,
  ) {
    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    requestLine.set(reader.readLine())
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
}
