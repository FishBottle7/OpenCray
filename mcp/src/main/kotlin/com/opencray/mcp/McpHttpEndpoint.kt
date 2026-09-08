package com.opencray.mcp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Injectable HTTP endpoint so tests (and alternative providers) can swap the
 * transport without opening sockets.
 *
 * Learning: The endpoint returns status + headers + body as plain data; only
 * the default JVM implementation knows about HttpURLConnection.
 */
interface McpHttpEndpoint {
  fun postJson(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Long,
  ): McpHttpResult
}

data class McpHttpResult(
  val statusCode: Int,
  val headers: Map<String, List<String>>,
  val body: String,
) {
  fun firstHeader(name: String): String? =
    headers.entries.firstOrNull { entry -> entry.key?.equals(name, ignoreCase = true) == true }
      ?.value?.firstOrNull()
}

class JvmMcpHttpEndpoint : McpHttpEndpoint {
  override fun postJson(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Long,
  ): McpHttpResult {
    val connection = try {
      URL(url).openConnection() as HttpURLConnection
    } catch (error: IOException) {
      throw McpHttpException("Failed to open MCP connection to '$url'.", error)
    }
    return try {
      connection.apply {
        connectTimeout = timeoutMs.toInt().coerceAtLeast(1)
        readTimeout = timeoutMs.toInt().coerceAtLeast(1)
        requestMethod = "POST"
        doOutput = true
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
      }
      try {
        connection.outputStream.use { output ->
          output.write(body.toByteArray(Charsets.UTF_8))
        }
      } catch (error: IOException) {
        throw McpHttpException("Failed to send MCP request to '$url'.", error)
      }
      val statusCode = connection.responseCode
      val responseBody = try {
        (if (statusCode in 200..399) connection.inputStream else connection.errorStream)
          ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
          ?: ""
      } catch (error: IOException) {
        throw McpHttpException("Failed to read MCP response from '$url'.", error)
      }
      McpHttpResult(
        statusCode = statusCode,
        headers = connection.headerFields.orEmpty(),
        body = responseBody,
      )
    } catch (error: SocketTimeoutException) {
      throw McpHttpException(
        message = "MCP request to '$url' timed out after ${timeoutMs}ms.",
        cause = error,
        causeCode = McpHttpException.TRANSPORT_TIMEOUT,
      )
    } finally {
      connection.disconnect()
    }
  }
}
