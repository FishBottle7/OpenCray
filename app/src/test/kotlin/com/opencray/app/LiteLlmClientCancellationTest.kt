package com.opencray.app

import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.FallbackAction
import com.opencray.llm.FallbackTriggerPolicy
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.llm.ModelProfile
import com.opencray.llm.NoOpLiteLlmVisibleTextObserver
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmClientCancellationTest {
  @Test
  fun cancelBeforeFirstByteInterruptsSlowNonStreamingGenerationWithinTwoSeconds() {
    val headerOnlyResponse = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: application/json\r\n")
      append("Content-Length: 64\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    StubServer(headerOnlyResponse).use { server ->
      val cancelFlag = AtomicBoolean(false)
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      val providerRequest = providerRequest(
        routeId = "route-cancel-non-stream",
        localPort = server.localPort,
        routeMetadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
        streamObserver = null,
        isCancelled = { cancelFlag.get() },
      )
      val future = CompletableFuture.supplyAsync { client.execute(providerRequest) }

      assertTrue(server.requestReceived.await(5, TimeUnit.SECONDS))
      Thread.sleep(400)
      cancelFlag.set(true)
      val cancelledAtNanoTime = System.nanoTime()
      val result = future.get(10, TimeUnit.SECONDS)
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAtNanoTime)

      assertTrue("Cancellation took ${elapsedMs}ms; expected under 2s.", elapsedMs < 2_000)
      val failure = result as LiteLlmProviderResult.Failure
      assertEquals(PROVIDER_REQUEST_CANCELLED_ERROR_CODE, failure.errorCode)
      assertTrue(failure.errorMessage.isNotBlank())
      assertTrue(server.connectionClosed.await(20, TimeUnit.SECONDS))
    }
  }

  @Test
  fun cancelMidStreamInterruptsStreamingReadAndKeepsPartialVisibleText() {
    val sseHead = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: text/event-stream\r\n")
      append("Cache-Control: no-cache\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    val firstEvent =
      "data: {\"id\":\"chatcmpl-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}\r\n\r\n"
    StubServer((sseHead + firstEvent).toByteArray(StandardCharsets.UTF_8)).use { server ->
      val cancelFlag = AtomicBoolean(false)
      val visibleSnapshots = SynchronizedSnapshotCollector()
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      val providerRequest = providerRequest(
        routeId = "route-cancel-stream",
        localPort = server.localPort,
        routeMetadata = mapOf("protocol" to LlmProviderProtocols.OPENAI, "stream" to "true"),
        streamObserver = visibleSnapshots,
        isCancelled = { cancelFlag.get() },
      )
      val future = CompletableFuture.supplyAsync { client.execute(providerRequest) }

      assertTrue(server.requestReceived.await(5, TimeUnit.SECONDS))
      assertTrue(server.responseWritten.await(5, TimeUnit.SECONDS))
      while (visibleSnapshots.snapshot().isEmpty()) {
        assertTrue("Stream ended before any partial text was observed.", !future.isDone)
        Thread.sleep(20)
      }
      cancelFlag.set(true)
      val cancelledAtNanoTime = System.nanoTime()
      val result = future.get(10, TimeUnit.SECONDS)
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAtNanoTime)

      assertTrue("Cancellation took ${elapsedMs}ms; expected under 2s.", elapsedMs < 2_000)
      val failure = result as LiteLlmProviderResult.Failure
      assertEquals(PROVIDER_REQUEST_CANCELLED_ERROR_CODE, failure.errorCode)
      assertEquals(listOf("Hel"), visibleSnapshots.snapshot())
      assertTrue(server.connectionClosed.await(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun nonCancelledRequestWithHookPresentCompletesNormally() {
    val responseBody = """
      {
        "id": "req_cancel_regression",
        "choices": [
          {
            "message": { "content": "OK" },
            "finish_reason": "stop"
          }
        ]
      }
    """.trimIndent()
    val bodyBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
    val completeResponse = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: application/json\r\n")
      append("Content-Length: ${bodyBytes.size}\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8) + bodyBytes
    StubServer(completeResponse).use { server ->
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent("1.0.0-test"),
      )
      val result = client.execute(
        providerRequest(
          routeId = "route-cancel-regression",
          localPort = server.localPort,
          routeMetadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
          streamObserver = null,
          isCancelled = { false },
        ),
      )

      assertTrue(server.responseWritten.await(5, TimeUnit.SECONDS))
      val success = result as LiteLlmProviderResult.Success
      assertEquals("OK", success.outputText)
      assertEquals("stop", success.finishReason)
      assertTrue(server.connectionClosed.await(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun cancelledProviderResultStaysTerminalWithoutRouteFallbackOrRetryBudget() {
    val attemptedRouteIds = mutableListOf<String>()
    val providerClient = object : LiteLlmProviderClient {
      override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
        attemptedRouteIds += request.route.id
        return LiteLlmProviderResult.Failure(
          errorCode = PROVIDER_REQUEST_CANCELLED_ERROR_CODE,
          errorMessage = "Provider request was cancelled by the user.",
        )
      }
    }
    val routing = ProviderRouting(
      activeProfileId = "profile-cancel",
      profiles = listOf(
        ModelProfile(
          id = "profile-cancel",
          displayName = "Cancel Profile",
          primaryRouteId = "route-primary",
          fallbackRouteIds = listOf("route-fallback"),
          routes = listOf(testRoute("route-primary"), testRoute("route-fallback")),
          fallbackPolicy = FallbackTriggerPolicy(
            onTransportError = FallbackAction.TRY_NEXT_ROUTE,
            onHttp5xx = FallbackAction.TRY_NEXT_ROUTE,
          ),
        ),
      ),
    )
    val gateway = DefaultLiteLlmGateway(
      routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
      providerClient = providerClient,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        prompt = "Reply with OK.",
        isCancelled = { true },
      ),
    )

    assertEquals(LiteLlmGatewayStatus.FAILED, result.status)
    assertEquals(PROVIDER_REQUEST_CANCELLED_ERROR_CODE, result.errorCode)
    assertEquals(listOf("route-primary"), attemptedRouteIds)
    assertEquals(1, result.attempts.size)
  }

  private fun providerRequest(
    routeId: String,
    localPort: Int,
    routeMetadata: Map<String, String>,
    streamObserver: LiteLlmVisibleTextObserver?,
    isCancelled: (() -> Boolean)?,
  ): LiteLlmProviderRequest = LiteLlmProviderRequest(
    route = ProviderRoute(
      id = routeId,
      providerId = "openai",
      baseUrl = "http://127.0.0.1:$localPort/v1",
      model = "gpt-4o-mini",
      timeoutMs = 15_000L,
      metadata = routeMetadata,
    ),
    request = LiteLlmGatewayRequest(
      prompt = "Reply with OK.",
      authHeaders = mapOf("Authorization" to "Bearer test-key"),
      streamObserver = streamObserver ?: NoOpLiteLlmVisibleTextObserver,
      isCancelled = isCancelled,
    ),
    selection = LiteLlmRouteSelectionMetadata(
      profileId = "profile-test",
      routeId = routeId,
      providerId = "openai",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    ),
  )

  private fun testRoute(id: String): ProviderRoute = ProviderRoute(
    id = id,
    providerId = "openai",
    baseUrl = "https://provider.invalid/v1",
    model = "gpt-4o-mini",
    timeoutMs = 5_000L,
    metadata = mapOf("protocol" to LlmProviderProtocols.OPENAI),
  )

  private class StubServer(private val initialResponseBytes: ByteArray?) : AutoCloseable {
    val localPort: Int get() = server.localPort
    val requestReceived = CountDownLatch(1)
    val responseWritten = CountDownLatch(1)
    val connectionClosed = CountDownLatch(1)

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val serverThread = Thread {
      runCatching {
        server.use { listening ->
          listening.accept().use { client ->
            readClientRequest(client)
            requestReceived.countDown()
            initialResponseBytes?.let { bytes ->
              val output = client.getOutputStream()
              output.write(bytes)
              output.flush()
              responseWritten.countDown()
            }
            waitForClientClose(client)
          }
        }
      }
    }

    init {
      serverThread.isDaemon = true
      serverThread.name = "opencray-cancellation-stub-server"
      serverThread.start()
    }

    override fun close() {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }

    private fun readClientRequest(client: Socket) {
      val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
      var contentLength = 0
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
          break
        }
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
          contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
      }
      var remaining = contentLength
      val buffer = CharArray(4096)
      while (remaining > 0) {
        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) {
          break
        }
        remaining -= read
      }
    }

    private fun waitForClientClose(client: Socket) {
      try {
        val input = client.getInputStream()
        while (input.read() != -1) {
        }
      } catch (_: Throwable) {
      } finally {
        connectionClosed.countDown()
      }
    }
  }

  private class SynchronizedSnapshotCollector : LiteLlmVisibleTextObserver {
    private val snapshots = mutableListOf<String>()

    @Synchronized
    override fun onVisibleTextSnapshot(text: String) {
      snapshots += text
    }

    @Synchronized
    fun snapshot(): List<String> = snapshots.toList()
  }
}
