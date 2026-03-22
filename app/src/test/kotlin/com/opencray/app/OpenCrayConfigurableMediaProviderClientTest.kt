package com.opencray.app

import com.opencray.runtime.OpenCrayImageGenerationRequest
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCraySpeechSynthesisRequest
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCrayConfigurableMediaProviderClientTest {
  @Test
  fun generatePostsConfiguredPayloadAndDecodesBase64JsonImage() {
    val requestLine = AtomicReference<String>()
    val authorization = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val imageBytes = byteArrayOf(1, 2, 3, 4)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(client, requestLine, authorization, requestBody)
          writeJsonResponse(
            client = client,
            body = """
              {
                "id": "img_req_1",
                "data": [
                  {
                    "b64_json": "${Base64.getEncoder().encodeToString(imageBytes)}"
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
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generate(
        OpenCrayImageGenerationRequest(
          prompt = "Draw a launch poster",
          count = 2,
          size = "1024x1024",
          format = "png",
          settings = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/images",
            model = "flux-test",
            authHeaders = mapOf("Authorization" to "Bearer media-key"),
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/images HTTP/1.1", requestLine.get())
      assertEquals("Bearer media-key", authorization.get())
      assertTrue(requestBody.get().contains("\"prompt\":\"Draw a launch poster\""))
      assertTrue(requestBody.get().contains("\"model\":\"flux-test\""))
      assertTrue(requestBody.get().contains("\"n\":2"))
      assertTrue(requestBody.get().contains("\"size\":\"1024x1024\""))
      assertTrue(requestBody.get().contains("\"format\":\"png\""))
      assertEquals("img_req_1", result.providerRequestId)
      assertEquals(1, result.images.size)
      assertArrayEquals(imageBytes, result.images.single().bytes)
      assertEquals("200", result.metadata["statusCode"])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun synthesizeSpeechPostsConfiguredPayloadAndAcceptsBinaryAudio() {
    val requestLine = AtomicReference<String>()
    val authorization = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val audioBytes = byteArrayOf(9, 8, 7, 6)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(client, requestLine, authorization, requestBody)
          writeBinaryResponse(
            client = client,
            contentType = "audio/mp4",
            body = audioBytes,
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.synthesize(
        OpenCraySpeechSynthesisRequest(
          text = "Summarize the rollout status.",
          format = "m4a",
          settings = OpenCraySpeechSynthesisSettings(
            provider = "Test Speech",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/audio/speech",
            defaultModel = "tts-1",
            defaultVoice = "alloy",
            authHeaders = mapOf("Authorization" to "Bearer media-key"),
          ),
        ),
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/audio/speech HTTP/1.1", requestLine.get())
      assertEquals("Bearer media-key", authorization.get())
      assertTrue(requestBody.get().contains("\"input\":\"Summarize the rollout status.\""))
      assertTrue(requestBody.get().contains("\"model\":\"tts-1\""))
      assertTrue(requestBody.get().contains("\"voice\":\"alloy\""))
      assertTrue(requestBody.get().contains("\"response_format\":\"m4a\""))
      assertArrayEquals(audioBytes, result.audio.bytes)
      assertEquals("audio/mp4", result.audio.mimeType)
      assertEquals("200", result.metadata["statusCode"])
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun readJsonHttpRequest(
    client: Socket,
    requestLine: AtomicReference<String>,
    authorization: AtomicReference<String>,
    requestBody: AtomicReference<String>,
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
      if (headerName.equals("Authorization", ignoreCase = true)) {
        authorization.set(headerValue)
      }
      if (headerName.equals("Content-Length", ignoreCase = true)) {
        contentLength = headerValue.toIntOrNull() ?: 0
      }
    }
    val bodyChars = CharArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
      val read = reader.read(bodyChars, offset, contentLength - offset)
      if (read < 0) {
        break
      }
      offset += read
    }
    requestBody.set(String(bodyChars, 0, offset))
  }

  private fun writeJsonResponse(
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

  private fun writeBinaryResponse(
    client: Socket,
    contentType: String,
    body: ByteArray,
  ) {
    val header = buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: $contentType\r\n")
      append("Content-Length: ${body.size}\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    client.getOutputStream().use { output ->
      output.write(header)
      output.write(body)
      output.flush()
    }
  }
}
