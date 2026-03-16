package com.opencray.runtime.web

import java.io.Closeable
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUrlWebContentFetcherTest {
  @Test
  fun fetchExtractsReadableTextFromHtml() {
    LocalHttpServer(
      routes = mapOf(
        "/article" to TestResponse(
          contentType = "text/html; charset=utf-8",
          body = """
          <html>
            <head>
              <title>Example &amp; Test</title>
              <style>.hidden { display:none; }</style>
            </head>
            <body>
              <article>
                <h1>Heading</h1>
                <p>First paragraph.</p>
                <p>Second&nbsp;paragraph with &lt;entities&gt;.</p>
                <script>console.log('ignored')</script>
              </article>
            </body>
          </html>
          """.trimIndent(),
        ),
      ),
    ).use { server ->
      val fetcher = HttpUrlWebContentFetcher()
      val result = fetcher.fetch(
        WebFetchRequest(
          url = "http://127.0.0.1:${server.port}/article",
          maxChars = 5000,
        ),
      )

      assertTrue(result.isSuccess)
      assertEquals("Example & Test", result.title)
      assertEquals(200, result.statusCode)
      assertTrue(result.content.contains("Heading"))
      assertTrue(result.content.contains("First paragraph."))
      assertTrue(result.content.contains("Second paragraph with <entities>."))
      assertTrue(!result.content.contains("console.log"))
    }
  }

  @Test
  fun fetchRejectsUnsupportedContentTypes() {
    LocalHttpServer(
      routes = mapOf(
        "/image" to TestResponse(
          contentType = "image/png",
          body = "PNG",
        ),
      ),
    ).use { server ->
      val fetcher = HttpUrlWebContentFetcher()
      val result = fetcher.fetch(
        WebFetchRequest(
          url = "http://127.0.0.1:${server.port}/image",
        ),
      )

      assertTrue(!result.isSuccess)
      assertEquals("WEB_FETCH_UNSUPPORTED_CONTENT_TYPE", result.errorCode)
    }
  }

  private data class TestResponse(
    val statusCode: Int = 200,
    val contentType: String,
    val body: String,
  )

  private class LocalHttpServer(
    private val routes: Map<String, TestResponse>,
  ) : Closeable {
    private val serverSocket = ServerSocket(0)
    private val worker = thread(start = true, isDaemon = true, name = "web-fetch-test-server") {
      while (!serverSocket.isClosed) {
        val socket = try {
          serverSocket.accept()
        } catch (_: Exception) {
          break
        }
        socket.use(::handleClient)
      }
    }

    val port: Int = serverSocket.localPort

    override fun close() {
      serverSocket.close()
      worker.join(1_000)
    }

    private fun handleClient(socket: Socket) {
      val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
      val requestLine = reader.readLine() ?: throw EOFException("Expected HTTP request line.")
      while (reader.readLine()?.isNotEmpty() == true) {
        // Consume request headers until the blank line.
      }
      val path = requestLine.split(" ").getOrNull(1) ?: "/"
      val response = routes[path] ?: TestResponse(
        statusCode = 404,
        contentType = "text/plain; charset=utf-8",
        body = "Not found",
      )
      val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
      val statusText = when (response.statusCode) {
        200 -> "OK"
        404 -> "Not Found"
        else -> "Test Response"
      }
      val headers = buildString {
        append("HTTP/1.1 ${response.statusCode} $statusText\r\n")
        append("Content-Type: ${response.contentType}\r\n")
        append("Content-Length: ${bodyBytes.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
      }.toByteArray(StandardCharsets.UTF_8)
      socket.getOutputStream().use { output ->
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
      }
    }
  }
}
