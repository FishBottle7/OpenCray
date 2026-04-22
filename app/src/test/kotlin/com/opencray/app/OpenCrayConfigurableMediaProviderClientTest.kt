package com.opencray.app

import com.opencray.runtime.OpenCrayImageGenerationRequest
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCrayMediaJobReceipt
import com.opencray.runtime.OpenCrayMediaJobSnapshot
import com.opencray.runtime.OpenCrayMediaJobStatus
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.OpenCraySpeechSynthesisRequest
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import com.opencray.runtime.OpenCrayVideoGenerationRequest
import com.opencray.runtime.OpenCrayVideoGenerationSettings
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        request = OpenCrayImageGenerationRequest(
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
        cancellationRequested = { false },
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
  fun generateDownloadsUrlAssetsIntoTempFilesInsteadOfBufferingAllBytes() {
    val requestBody = AtomicReference<String>()
    val downloadServed = CountDownLatch(1)
    val imageBytes = byteArrayOf(5, 4, 3, 2)
    val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = requestBody,
          )
          writeJsonResponse(
            client = client,
            body = """
              {
                "id": "img_req_2",
                "data": [
                  {
                    "url": "http://127.0.0.1:${listeningSocket.localPort}/download/result.png"
                  }
                ]
              }
            """.trimIndent(),
          )
        }
        listeningSocket.accept().use { client ->
          readRequestLine(client)
          writeBinaryResponse(
            client = client,
            contentType = "image/png",
            body = imageBytes,
          )
          downloadServed.countDown()
        }
      }
    }
    serverThread.start()

    var tempPath = null as java.nio.file.Path?
    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = "Draw a poster",
          settings = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/images",
            model = "flux-test",
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(downloadServed.await(5, TimeUnit.SECONDS))
      assertTrue(requestBody.get().contains("\"prompt\":\"Draw a poster\""))
      tempPath = result.images.single().sourcePath
      assertNotNull(tempPath)
      assertTrue(Files.exists(tempPath))
      assertFalse(result.images.single().bytes.isNotEmpty())
      assertArrayEquals(imageBytes, Files.readAllBytes(tempPath))
    } finally {
      tempPath?.let { runCatching { Files.deleteIfExists(it) } }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun generateDoesNotForwardProviderAuthHeadersToCrossOriginAssetDownloads() {
    val requestBody = AtomicReference<String>()
    val assetAuthorization = AtomicReference<String?>()
    val assetApiKey = AtomicReference<String?>()
    val downloadServed = CountDownLatch(1)
    val imageBytes = byteArrayOf(6, 5, 4, 3)
    val providerServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val assetServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val providerThread = Thread {
      providerServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = requestBody,
          )
          writeJsonResponse(
            client = client,
            body = """
              {
                "id": "img_req_cross_origin",
                "data": [
                  {
                    "url": "http://127.0.0.1:${assetServer.localPort}/download/result.png"
                  }
                ]
              }
            """.trimIndent(),
          )
        }
      }
    }
    val assetThread = Thread {
      assetServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          val (_, headers) = readRequestLineAndHeaders(client)
          assetAuthorization.set(headers["authorization"])
          assetApiKey.set(headers["x-api-key"])
          writeBinaryResponse(
            client = client,
            contentType = "image/png",
            body = imageBytes,
          )
          downloadServed.countDown()
        }
      }
    }
    providerThread.start()
    assetThread.start()

    var tempPath = null as java.nio.file.Path?
    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = "Draw a poster",
          settings = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "http://127.0.0.1:${providerServer.localPort}",
            endpoint = "/v1/images",
            model = "flux-test",
            authHeaders = mapOf(
              "Authorization" to "Bearer media-key",
              "x-api-key" to "media-key",
            ),
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(downloadServed.await(5, TimeUnit.SECONDS))
      assertTrue(requestBody.get().contains("\"prompt\":\"Draw a poster\""))
      assertNull(assetAuthorization.get())
      assertNull(assetApiKey.get())
      tempPath = result.images.single().sourcePath
      assertNotNull(tempPath)
      assertArrayEquals(imageBytes, Files.readAllBytes(tempPath))
    } finally {
      tempPath?.let { runCatching { Files.deleteIfExists(it) } }
      runCatching { providerServer.close() }
      runCatching { assetServer.close() }
      providerThread.join(5_000L)
      assetThread.join(5_000L)
    }
  }

  @Test
  fun generateDoesNotForwardProviderAuthHeadersAcrossCrossOriginApiRedirects() {
    val initialAuthorization = AtomicReference<String>()
    val redirectedAuthorization = AtomicReference<String>()
    val initialRequestBody = AtomicReference<String>()
    val redirectedRequestBody = AtomicReference<String>()
    val providerServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val redirectServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val imageBytes = byteArrayOf(1, 3, 5, 7)
    val providerThread = Thread {
      providerServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = initialAuthorization,
            requestBody = initialRequestBody,
          )
          writeEmptyResponse(
            client = client,
            statusLine = "HTTP/1.1 307 Temporary Redirect",
            extraHeaders = mapOf(
              "Location" to "http://127.0.0.1:${redirectServer.localPort}/v1/images-redirected",
            ),
          )
        }
      }
    }
    val redirectThread = Thread {
      redirectServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = redirectedAuthorization,
            requestBody = redirectedRequestBody,
          )
          writeJsonResponse(
            client = client,
            body = """
              {
                "id": "img_req_redirected",
                "data": [
                  {
                    "b64_json": "${Base64.getEncoder().encodeToString(imageBytes)}"
                  }
                ]
              }
            """.trimIndent(),
          )
        }
      }
    }
    providerThread.start()
    redirectThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = "Draw a poster",
          settings = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "http://127.0.0.1:${providerServer.localPort}",
            endpoint = "/v1/images",
            model = "flux-test",
            authHeaders = mapOf("Authorization" to "Bearer media-key"),
          ),
        ),
        cancellationRequested = { false },
      )

      assertEquals("Bearer media-key", initialAuthorization.get())
      assertNull(redirectedAuthorization.get())
      assertEquals(initialRequestBody.get(), redirectedRequestBody.get())
      assertArrayEquals(imageBytes, result.images.single().bytes)
    } finally {
      runCatching { providerServer.close() }
      runCatching { redirectServer.close() }
      providerThread.join(5_000L)
      redirectThread.join(5_000L)
    }
  }

  @Test
  fun generateDoesNotForwardProviderAuthHeadersAfterCrossOriginAssetRedirect() {
    val requestBody = AtomicReference<String>()
    val providerDownloadAuthorization = AtomicReference<String?>()
    val providerDownloadApiKey = AtomicReference<String?>()
    val assetAuthorization = AtomicReference<String?>()
    val assetApiKey = AtomicReference<String?>()
    val downloadServed = CountDownLatch(1)
    val imageBytes = byteArrayOf(9, 7, 5, 3)
    val providerServer = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val assetServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val providerThread = Thread {
      providerServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = requestBody,
          )
          writeJsonResponse(
            client = client,
            body = """
              {
                "id": "img_req_redirected_asset",
                "data": [
                  {
                    "url": "http://127.0.0.1:${listeningSocket.localPort}/download/redirect.png"
                  }
                ]
              }
            """.trimIndent(),
          )
        }
        listeningSocket.accept().use { client ->
          val (_, headers) = readRequestLineAndHeaders(client)
          providerDownloadAuthorization.set(headers["authorization"])
          providerDownloadApiKey.set(headers["x-api-key"])
          writeEmptyResponse(
            client = client,
            statusLine = "HTTP/1.1 302 Found",
            extraHeaders = mapOf(
              "Location" to "http://127.0.0.1:${assetServer.localPort}/download/result.png",
            ),
          )
        }
      }
    }
    val assetThread = Thread {
      assetServer.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          val (_, headers) = readRequestLineAndHeaders(client)
          assetAuthorization.set(headers["authorization"])
          assetApiKey.set(headers["x-api-key"])
          writeBinaryResponse(
            client = client,
            contentType = "image/png",
            body = imageBytes,
          )
          downloadServed.countDown()
        }
      }
    }
    providerThread.start()
    assetThread.start()

    var tempPath = null as java.nio.file.Path?
    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = "Draw a poster",
          settings = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "http://127.0.0.1:${providerServer.localPort}",
            endpoint = "/v1/images",
            model = "flux-test",
            authHeaders = mapOf(
              "Authorization" to "Bearer media-key",
              "x-api-key" to "media-key",
            ),
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(downloadServed.await(5, TimeUnit.SECONDS))
      assertTrue(requestBody.get().contains("\"prompt\":\"Draw a poster\""))
      assertEquals("Bearer media-key", providerDownloadAuthorization.get())
      assertEquals("media-key", providerDownloadApiKey.get())
      assertNull(assetAuthorization.get())
      assertNull(assetApiKey.get())
      tempPath = result.images.single().sourcePath
      assertNotNull(tempPath)
      assertArrayEquals(imageBytes, Files.readAllBytes(tempPath))
    } finally {
      tempPath?.let { runCatching { Files.deleteIfExists(it) } }
      runCatching { providerServer.close() }
      runCatching { assetServer.close() }
      providerThread.join(5_000L)
      assetThread.join(5_000L)
    }
  }

  @Test
  fun generateVideoPostsConfiguredPayloadAndAcceptsBinaryVideo() {
    val requestLine = AtomicReference<String>()
    val authorization = AtomicReference<String>()
    val requestBody = AtomicReference<String>()
    val responseSent = CountDownLatch(1)
    val videoBytes = byteArrayOf(1, 7, 2, 9)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(client, requestLine, authorization, requestBody)
          writeBinaryResponse(
            client = client,
            contentType = "video/mp4",
            body = videoBytes,
          )
          responseSent.countDown()
        }
      }
    }
    serverThread.start()

    var tempPath = null as java.nio.file.Path?
    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generateVideo(
        request = OpenCrayVideoGenerationRequest(
          prompt = "A drone shot over the harbor",
          durationSeconds = 8,
          size = "1280x720",
          format = "mp4",
          settings = OpenCrayVideoGenerationSettings(
            provider = "Test Video",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/videos",
            model = "gen4",
            authHeaders = mapOf("Authorization" to "Bearer media-key"),
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/videos HTTP/1.1", requestLine.get())
      assertEquals("Bearer media-key", authorization.get())
      assertTrue(requestBody.get().contains("\"prompt\":\"A drone shot over the harbor\""))
      assertTrue(requestBody.get().contains("\"model\":\"gen4\""))
      assertTrue(requestBody.get().contains("\"duration_seconds\":8"))
      assertTrue(requestBody.get().contains("\"size\":\"1280x720\""))
      assertTrue(requestBody.get().contains("\"format\":\"mp4\""))
      tempPath = result.videos.single().sourcePath
      assertNotNull(tempPath)
      assertTrue(Files.exists(tempPath))
      assertFalse(result.videos.single().bytes.isNotEmpty())
      assertArrayEquals(videoBytes, Files.readAllBytes(tempPath))
      assertEquals("video/mp4", result.videos.single().mimeType)
    } finally {
      tempPath?.let { runCatching { Files.deleteIfExists(it) } }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun synthesizeSpeechPostsConfiguredPayloadAndUsesConfiguredModel() {
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

    var tempPath = null as java.nio.file.Path?
    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.synthesize(
        request = OpenCraySpeechSynthesisRequest(
          text = "Summarize the rollout status.",
          format = "m4a",
          settings = OpenCraySpeechSynthesisSettings(
            provider = "Test Speech",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/audio/speech",
            defaultModel = "tts-omni",
            defaultVoice = "alloy",
            authHeaders = mapOf("Authorization" to "Bearer media-key"),
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(responseSent.await(5, TimeUnit.SECONDS))
      assertEquals("POST /v1/audio/speech HTTP/1.1", requestLine.get())
      assertEquals("Bearer media-key", authorization.get())
      assertTrue(requestBody.get().contains("\"input\":\"Summarize the rollout status.\""))
      assertTrue(requestBody.get().contains("\"model\":\"tts-omni\""))
      assertTrue(requestBody.get().contains("\"voice\":\"alloy\""))
      assertTrue(requestBody.get().contains("\"response_format\":\"m4a\""))
      tempPath = result.audio?.sourcePath
      assertNotNull(tempPath)
      assertTrue(Files.exists(tempPath))
      assertFalse(result.audio?.bytes?.isNotEmpty() == true)
      assertArrayEquals(audioBytes, Files.readAllBytes(tempPath))
      assertEquals("audio/mp4", result.audio?.mimeType)
      assertEquals("200", result.metadata["statusCode"])
    } finally {
      tempPath?.let { runCatching { Files.deleteIfExists(it) } }
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun generateVideoParsesAcceptedProviderJobReceipt() {
    val requestBody = AtomicReference<String>()
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = requestBody,
          )
          writeJsonResponse(
            client = client,
            statusLine = "HTTP/1.1 202 Accepted",
            extraHeaders = mapOf("Retry-After" to "2"),
            body = """
              {
                "job_id": "video-job-123",
                "status": "queued",
                "poll_url": "http://127.0.0.1:${listeningSocket.localPort}/jobs/video-job-123",
                "cancel_url": "http://127.0.0.1:${listeningSocket.localPort}/jobs/video-job-123/cancel"
              }
            """.trimIndent(),
          )
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generateVideo(
        request = OpenCrayVideoGenerationRequest(
          prompt = "A looping city timelapse",
          preferAsync = true,
          settings = OpenCrayVideoGenerationSettings(
            provider = "Test Video",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/videos",
            model = "gen4",
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(requestBody.get().contains("\"async\":true"))
      assertTrue(result.videos.isEmpty())
      assertNull(result.providerRequestId)
      assertEquals(OpenCrayMediaJobStatus.PENDING, result.pendingJob?.receipt?.status)
      assertEquals("video-job-123", result.pendingJob?.receipt?.jobId)
      assertEquals(2_000L, result.pendingJob?.receipt?.pollAfterMs)
      assertEquals(
        "http://127.0.0.1:${server.localPort}/jobs/video-job-123",
        result.pendingJob?.metadata?.get("providerPollUrl"),
      )
      assertEquals(
        "http://127.0.0.1:${server.localPort}/jobs/video-job-123/cancel",
        result.pendingJob?.metadata?.get("providerCancelUrl"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun generateVideoAcceptsRelativeLocationOnlyPendingReceipt() {
    val requestBody = AtomicReference<String>()
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = requestBody,
          )
          writeEmptyResponse(
            client = client,
            statusLine = "HTTP/1.1 202 Accepted",
            extraHeaders = mapOf(
              "Retry-After" to "2",
              "Location" to "/jobs/video-job-location-only/status",
            ),
          )
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val result = client.generateVideo(
        request = OpenCrayVideoGenerationRequest(
          prompt = "A looping city timelapse",
          preferAsync = true,
          settings = OpenCrayVideoGenerationSettings(
            provider = "Test Video",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            endpoint = "/v1/videos",
            model = "gen4",
          ),
        ),
        cancellationRequested = { false },
      )

      assertTrue(requestBody.get().contains("\"async\":true"))
      assertEquals(OpenCrayMediaJobStatus.PENDING, result.pendingJob?.receipt?.status)
      assertEquals("video-job-location-only", result.pendingJob?.receipt?.jobId)
      assertEquals(2_000L, result.pendingJob?.receipt?.pollAfterMs)
      assertEquals(
        "http://127.0.0.1:${server.localPort}/jobs/video-job-location-only/status",
        result.pendingJob?.metadata?.get("providerPollUrl"),
      )
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test
  fun pollAndCancelUseProviderJobEndpoints() {
    val pollRequestLine = AtomicReference<String>()
    val cancelRequestLine = AtomicReference<String>()
    val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          pollRequestLine.set(readRequestLine(client))
          writeJsonResponse(
            client = client,
            body = """
              {
                "status": "completed",
                "videos": [
                  { "b64_json": "${Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6, 7))}" }
                ]
              }
            """.trimIndent(),
          )
        }
        listeningSocket.accept().use { client ->
          cancelRequestLine.set(readRequestLine(client))
          writeJsonResponse(
            client = client,
            body = """
              {
                "job_id": "video-job-456",
                "status": "cancelled"
              }
            """.trimIndent(),
          )
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val settings = OpenCrayMediaToolSettings(
        videoGeneration = OpenCrayVideoGenerationSettings(
          provider = "Test Video",
          baseUrl = "http://127.0.0.1:${server.localPort}",
          endpoint = "/v1/videos",
          model = "gen4",
        ),
      )
      val job = OpenCrayMediaJobSnapshot(
        receipt = OpenCrayMediaJobReceipt(
          jobId = "video-job-456",
          toolName = "GenerateVideo",
          status = OpenCrayMediaJobStatus.PENDING,
        ),
        metadata = mapOf(
          "providerPollUrl" to "http://127.0.0.1:${server.localPort}/jobs/video-job-456",
          "providerCancelUrl" to "http://127.0.0.1:${server.localPort}/jobs/video-job-456/cancel",
        ),
      )

      val pollResult = client.poll(
        job = job,
        settings = settings,
        cancellationRequested = { false },
      )
      val cancelResult = client.cancel(
        job = job,
        settings = settings,
        cancellationRequested = { false },
      )

      assertEquals("GET /jobs/video-job-456 HTTP/1.1", pollRequestLine.get())
      assertEquals("POST /jobs/video-job-456/cancel HTTP/1.1", cancelRequestLine.get())
      assertEquals(OpenCrayMediaJobStatus.COMPLETED, pollResult.snapshot.receipt.status)
      assertArrayEquals(byteArrayOf(4, 5, 6, 7), pollResult.videos.single().bytes)
      assertEquals(OpenCrayMediaJobStatus.CANCELLED, cancelResult.receipt.status)
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  @Test(expected = CancellationException::class)
  fun mediaRequestsDisconnectWhenCancellationIsRequested() {
    val requestObserved = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val serverThread = Thread {
      server.use { listeningSocket ->
        listeningSocket.accept().use { client ->
          readJsonHttpRequest(
            client = client,
            requestLine = AtomicReference(),
            authorization = AtomicReference(),
            requestBody = AtomicReference(),
          )
          requestObserved.countDown()
          Thread.sleep(10_000L)
        }
      }
    }
    serverThread.start()

    try {
      val client = OpenCrayConfigurableMediaProviderClient(
        userAgent = "OpenCray/1.0.0-test",
      )
      val resultRef = AtomicReference<Throwable?>()
      val worker = Thread {
        try {
          client.generate(
            request = OpenCrayImageGenerationRequest(
              prompt = "Draw a poster",
              settings = OpenCrayImageGenerationSettings(
                provider = "Test Images",
                baseUrl = "http://127.0.0.1:${server.localPort}",
                endpoint = "/v1/images",
                model = "flux-test",
              ),
            ),
            cancellationRequested = { cancelled.get() },
          )
        } catch (throwable: Throwable) {
          resultRef.set(throwable)
        }
      }
      worker.start()

      assertTrue(requestObserved.await(5, TimeUnit.SECONDS))
      cancelled.set(true)
      worker.join(5_000L)
      throw resultRef.get() ?: AssertionError("Expected request cancellation.")
    } finally {
      runCatching { server.close() }
      serverThread.join(5_000L)
    }
  }

  private fun readRequestLine(client: Socket): String {
    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    val requestLine = reader.readLine()
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) {
        break
      }
    }
    return requestLine
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
    statusLine: String = "HTTP/1.1 200 OK",
    extraHeaders: Map<String, String> = emptyMap(),
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
      append("$statusLine\r\n")
      append("Content-Type: application/json\r\n")
      extraHeaders.forEach { (name, value) ->
        append("$name: $value\r\n")
      }
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

  private fun writeEmptyResponse(
    client: Socket,
    statusLine: String = "HTTP/1.1 200 OK",
    extraHeaders: Map<String, String> = emptyMap(),
  ) {
    val header = buildString {
      append("$statusLine\r\n")
      extraHeaders.forEach { (name, value) ->
        append("$name: $value\r\n")
      }
      append("Content-Length: 0\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    client.getOutputStream().use { output ->
      output.write(header)
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

  private fun readRequestLineAndHeaders(client: Socket): Pair<String, Map<String, String>> {
    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
    val requestLine = reader.readLine()
    val headers = linkedMapOf<String, String>()
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isBlank()) {
        break
      }
      val separatorIndex = line.indexOf(':')
      if (separatorIndex <= 0) {
        continue
      }
      val headerName = line.substring(0, separatorIndex).trim().lowercase()
      val headerValue = line.substring(separatorIndex + 1).trim()
      headers[headerName] = headerValue
    }
    return requestLine to headers
  }
}
