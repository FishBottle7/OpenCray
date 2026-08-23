package com.opencray.app

import com.opencray.app.e2b.E2BBinaryResponse
import com.opencray.app.e2b.E2BDownloadRequest
import com.opencray.app.e2b.E2BRequest
import com.opencray.app.e2b.E2BResponse
import com.opencray.app.e2b.E2BUploadRequest
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

internal interface E2BTransport {
  fun request(request: E2BRequest): E2BResponse

  fun upload(request: E2BUploadRequest): E2BResponse

  fun download(request: E2BDownloadRequest): E2BBinaryResponse

  fun stream(
    request: E2BRequest,
    onLine: (String) -> Unit,
  ): E2BResponse
}

internal class UrlConnectionE2BTransport : E2BTransport {
  override fun request(request: E2BRequest): E2BResponse {
    val connection = openConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.body != null,
    )
    return try {
      request.body?.let { body ->
        connection.outputStream.use { output ->
          output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val statusCode = connection.responseCode
      E2BResponse(
        statusCode = statusCode,
        body = readFully(
          input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
        ),
      )
    } finally {
      connection.disconnect()
    }
  }

  override fun upload(request: E2BUploadRequest): E2BResponse {
    val boundary = "----OpenCrayE2B${UUID.randomUUID()}"
    val headers = request.headers + mapOf(
      "Content-Type" to "multipart/form-data; boundary=$boundary",
    )
    val connection = openConnection(
      method = "POST",
      url = request.url,
      headers = headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = true,
    )
    return try {
      connection.outputStream.use { output ->
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(
          buildString {
            append("Content-Disposition: form-data; name=\"")
            append(request.fieldName)
            append("\"; filename=\"")
            append(request.fileName)
            append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
          }.toByteArray(StandardCharsets.UTF_8),
        )
        output.write(request.fileBytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
      }
      val statusCode = connection.responseCode
      E2BResponse(
        statusCode = statusCode,
        body = readFully(
          input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
        ),
      )
    } finally {
      connection.disconnect()
    }
  }

  override fun download(request: E2BDownloadRequest): E2BBinaryResponse {
    val connection = openConnection(
      method = "GET",
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = false,
    )
    return try {
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        E2BBinaryResponse(
          statusCode = statusCode,
          bodyBytes = readFullyBytes(connection.inputStream),
        )
      } else {
        E2BBinaryResponse(
          statusCode = statusCode,
          errorBody = readFully(connection.errorStream),
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  override fun stream(
    request: E2BRequest,
    onLine: (String) -> Unit,
  ): E2BResponse {
    val connection = openConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.body != null,
    )
    return try {
      request.body?.let { body ->
        connection.outputStream.use { output ->
          output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        BufferedReader(
          InputStreamReader(connection.inputStream, StandardCharsets.UTF_8),
        ).use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) {
              onLine(line)
            }
          }
        }
        E2BResponse(statusCode = statusCode)
      } else {
        E2BResponse(
          statusCode = statusCode,
          body = readFully(connection.errorStream),
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(
    method: String,
    url: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    doOutput: Boolean,
  ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = method
    connectTimeout = connectTimeoutMs
    readTimeout = readTimeoutMs
    instanceFollowRedirects = true
    doInput = true
    this.doOutput = doOutput
    useCaches = false
    headers.forEach { (name, value) ->
      if (name.isNotBlank() && value.isNotBlank()) {
        setRequestProperty(name, value)
      }
    }
  }

  private fun readFully(input: InputStream?): String {
    if (input == null) {
      return ""
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toString(StandardCharsets.UTF_8.name())
    }
  }

  private fun readFullyBytes(input: InputStream?): ByteArray {
    if (input == null) {
      return ByteArray(0)
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toByteArray()
    }
  }
}
