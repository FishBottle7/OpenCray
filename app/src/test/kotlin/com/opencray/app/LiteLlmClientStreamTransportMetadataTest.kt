package com.opencray.app

import com.opencray.app.projection.llmDiagnosticsFromMetadata
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmMetadataKeys
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmClientStreamTransportMetadataTest : LiteLlmClientTestBase() {
  @Test
  fun executeWithRequestedStreamAndPlainJsonBodyRecordsDowngradeMetadata() {
    val result = executeWithCapturedProviderRequest(
      routeProviderId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      model = "gpt-4o-mini",
      responseBody = """
        {
          "id": "req_stream_downgrade",
          "choices": [
            {
              "message": { "content": "OK" },
              "finish_reason": "stop"
            }
          ]
        }
      """.trimIndent(),
      capturedBody = AtomicReference(),
      routeMetadata = mapOf("stream" to "true"),
    )

    val success = result as LiteLlmProviderResult.Success
    assertEquals("true", success.metadata[LiteLlmMetadataKeys.STREAM_REQUESTED])
    assertEquals(STREAM_TRANSPORT_MODE_PLAIN_BODY, success.metadata[LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE])
    assertEquals(
      STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM,
      success.metadata[LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON],
    )
    assertEquals("OK", success.outputText)
  }

  @Test
  fun executeWithMissingContentTypeRecordsContentTypeMissingDowngradeReason() {
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, AtomicReference(), AtomicReference())
          writeHttpResponseWithoutContentType(
            client = client,
            body = """
              {
                "id": "req_no_content_type",
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
      val result = client.execute(
        LiteLlmProviderRequest(
          route = ProviderRoute(
            id = "route-stream-no-content-type",
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
            routeId = "route-stream-no-content-type",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )
      assertTrue(responseSent.await(5, TimeUnit.SECONDS))

      val success = result as LiteLlmProviderResult.Success
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.STREAM_REQUESTED])
      assertEquals(STREAM_TRANSPORT_MODE_PLAIN_BODY, success.metadata[LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE])
      assertEquals(
        STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM_CONTENT_TYPE_MISSING,
        success.metadata[LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON],
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeWithEventStreamResponseRecordsSseTransportModeWithoutDowngradeReason() {
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
              data: {"id":"chatcmpl_stream_meta","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_meta","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

              data: {"id":"chatcmpl_stream_meta","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

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
            id = "route-stream-meta-sse",
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
            routeId = "route-stream-meta-sse",
            providerId = "openai",
            model = "gpt-4o-mini",
            attemptIndex = 0,
          ),
        ),
      )
      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals(true, JSONObject(requestBody.get()).getBoolean("stream"))

      val success = result as LiteLlmProviderResult.Success
      assertEquals("Hello world", success.outputText)
      assertEquals("true", success.metadata[LiteLlmMetadataKeys.STREAM_REQUESTED])
      assertEquals(STREAM_TRANSPORT_MODE_SSE, success.metadata[LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE])
      assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON))
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun executeWithoutStreamPreferenceOmitsDowngradeReason() {
    val result = executeWithOpenAiResponse(
      body = """
        {
          "id": "req_plain_non_streaming",
          "choices": [
            {
              "message": { "content": "OK" },
              "finish_reason": "stop"
            }
          ]
        }
      """.trimIndent(),
    )
    val success = result as LiteLlmProviderResult.Success

    assertEquals("false", success.metadata[LiteLlmMetadataKeys.STREAM_REQUESTED])
    assertEquals(STREAM_TRANSPORT_MODE_PLAIN_BODY, success.metadata[LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE])
    assertFalse(success.metadata.containsKey(LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON))
  }

  @Test
  fun llmDiagnosticsSurfacesStreamTransportMetadataForInspector() {
    val downgradedDiagnostics = llmDiagnosticsFromMetadata(
      mapOf(
        LiteLlmMetadataKeys.STREAM_REQUESTED to "true",
        LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE to STREAM_TRANSPORT_MODE_PLAIN_BODY,
        LiteLlmMetadataKeys.STREAM_DOWNGRADE_REASON to STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM,
      ),
    )
    assertEquals(true, downgradedDiagnostics?.get("streamRequested"))
    assertEquals(STREAM_TRANSPORT_MODE_PLAIN_BODY, downgradedDiagnostics?.get("streamTransportMode"))
    assertEquals(
      STREAM_DOWNGRADE_REASON_NON_EVENT_STREAM,
      downgradedDiagnostics?.get("streamDowngradeReason"),
    )

    val normalStreamedDiagnostics = llmDiagnosticsFromMetadata(
      mapOf(
        LiteLlmMetadataKeys.STREAM_REQUESTED to "true",
        LiteLlmMetadataKeys.STREAM_TRANSPORT_MODE to STREAM_TRANSPORT_MODE_SSE,
      ),
    )
    assertNull(normalStreamedDiagnostics?.get("streamDowngradeReason"))
    assertEquals(STREAM_TRANSPORT_MODE_SSE, normalStreamedDiagnostics?.get("streamTransportMode"))
  }

  private fun writeHttpResponseWithoutContentType(
    client: Socket,
    body: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 200 OK\r\n")
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
