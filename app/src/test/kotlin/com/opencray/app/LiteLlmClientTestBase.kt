package com.opencray.app

import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolDefinition
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

abstract class LiteLlmClientTestBase {
  protected fun executeWithCapturedProviderRequest(
    routeProviderId: String,
    protocol: String,
    model: String,
    responseBody: String,
    capturedBody: AtomicReference<String>,
    authHeaders: Map<String, String> = mapOf("Authorization" to "Bearer test-key"),
    routeMetadata: Map<String, String> = emptyMap(),
    requestMetadata: Map<String, String> = emptyMap(),
  ): LiteLlmProviderResult {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readHttpRequest(client, requestLine, userAgent, capturedBody)
          writeHttpResponse(
            client = client,
            body = responseBody,
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
            id = "route-captured",
            providerId = routeProviderId,
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = model,
            timeoutMs = 5_000L,
            metadata = mapOf("protocol" to protocol) + routeMetadata,
          ),
          request = LiteLlmGatewayRequest(
            prompt = "Reply with OK.",
            metadata = requestMetadata,
            authHeaders = authHeaders,
          ),
          selection = LiteLlmRouteSelectionMetadata(
            profileId = "profile-test",
            routeId = "route-captured",
            providerId = routeProviderId,
            model = model,
            attemptIndex = 0,
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      val expectedPath = when (protocol) {
        LlmProviderProtocols.ANTHROPIC -> "/v1/messages"
        LlmProviderProtocols.OPENAI_RESPONSES -> "/v1/responses"
        else -> "/v1/chat/completions"
      }
      assertEquals("POST $expectedPath HTTP/1.1", requestLine.get())
      assertNotNull(userAgent.get())
      result
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  protected fun executeWithOpenAiResponse(
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

  protected fun executeWithAnthropicResponse(body: String): LiteLlmProviderResult.Success {
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

  protected fun executeWithResponsesResponse(body: String): LiteLlmProviderResult.Success {
    val requestLine = AtomicReference<String>()
    val userAgent = AtomicReference<String>()
    val serverReady = CountDownLatch(1)
    val responseSent = CountDownLatch(1)
    val serverFailure = AtomicReference<Throwable?>()
    val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        serverReady.countDown()
        repeat(2) {
          val acceptedClient = try {
            listeningSocket.accept()
          } catch (error: Throwable) {
            serverFailure.compareAndSet(null, error)
            return@Thread
          }
          acceptedClient.use { client ->
            try {
              readHttpRequest(client, requestLine, userAgent)
              writeHttpResponse(
                client = client,
                body = body,
              )
              responseSent.countDown()
              return@Thread
            } catch (error: Throwable) {
              serverFailure.compareAndSet(null, error)
            }
          }
        }
      }
    }
    serverThread.start()

    return try {
      assertTrue(serverReady.await(5, TimeUnit.SECONDS))
      val client = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = OpenAiCompatibleLiteLlmProviderClient.providerUserAgent(
          "1.0.0-test",
        ),
      )
      val providerRequest = LiteLlmProviderRequest(
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
      )
      val initialResult = client.execute(providerRequest)
      val result = if (
        initialResult is LiteLlmProviderResult.Failure &&
        initialResult.errorCode == "PROVIDER_TRANSPORT_ERROR" &&
        initialResult.metadata["exceptionType"] == "java.net.ConnectException" &&
        initialResult.errorMessage.contains("Connection refused", ignoreCase = true)
      ) {
        client.execute(providerRequest)
      } else {
        initialResult
      }

      if (!responseSent.await(5, TimeUnit.SECONDS)) {
        serverFailure.get()?.let { error ->
          throw AssertionError("Responses test server failed before sending a reply.", error)
        }
        throw AssertionError("Timed out waiting for the Responses test server reply.")
      }

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

  protected fun readHttpRequest(
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

  protected fun writeHttpResponse(
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

  protected fun writeHttpEventStreamResponse(
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

  protected fun writeTempImageFile(
    prefix: String,
    suffix: String,
  ): Path {
    val directory = Files.createTempDirectory("opencray-provider-client-test-")
    val path = directory.resolve("$prefix$suffix")
    Files.write(path, byteArrayOf(1, 2, 3, 4))
    return path
  }

  protected fun writeTempPdfFile(
    prefix: String,
    suffix: String,
  ): Path {
    val directory = Files.createTempDirectory("opencray-provider-client-test-")
    val path = directory.resolve("$prefix$suffix")
    Files.write(path, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    return path
  }

  protected fun sampleToolDefinition(): LiteLlmToolDefinition = LiteLlmToolDefinition(
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

  protected fun sampleStructuredToolCall(id: String): com.opencray.llm.LiteLlmStructuredToolCall =
    com.opencray.llm.LiteLlmStructuredToolCall(
      id = id,
      toolName = "EchoProbe",
      arguments = buildJsonObject {
        put("echo", "hello")
      },
    )
}
