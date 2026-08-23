package com.opencray.app.e2b

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

private fun openHttpConnection(
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

private fun readResponseText(
  connection: HttpURLConnection,
  statusCode: Int,
): String = readFully(
  input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
)

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
    val connection = openHttpConnection(
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
        body = readResponseText(connection, statusCode),
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
    val connection = openHttpConnection(
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
        body = readResponseText(connection, statusCode),
      )
    } finally {
      connection.disconnect()
    }
  }

  override fun download(request: E2BDownloadRequest): E2BBinaryResponse {
    val connection = openHttpConnection(
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
    val connection = openHttpConnection(
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

internal interface E2BEnvdCommandTransport {
  fun stream(
    request: E2BEnvdCommandTransportRequest,
    onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
  ): E2BResponse

  fun unary(
    request: E2BEnvdCommandTransportRequest,
  ): E2BResponse = error("Unary E2B envd transport is not implemented.")
}

internal data class E2BEnvdCommandTransportRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String>,
  val bodyBytes: ByteArray,
  val connectTimeoutMs: Int,
  val readTimeoutMs: Int,
)

internal class UrlConnectionE2BEnvdCommandTransport : E2BEnvdCommandTransport {
  override fun stream(
    request: E2BEnvdCommandTransportRequest,
    onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
  ): E2BResponse {
    val connection = openHttpConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.bodyBytes.isNotEmpty(),
    )
    return try {
      if (request.bodyBytes.isNotEmpty()) {
        connection.outputStream.use { output ->
          output.write(request.bodyBytes)
        }
      }
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        connection.inputStream.use { input ->
          while (true) {
            val header = ByteArray(5)
            val firstByte = input.read()
            if (firstByte < 0) {
              break
            }
            header[0] = firstByte.toByte()
            readExact(input, header, offset = 1, length = 4)
            val flags = header[0].toInt() and 0xFF
            val payloadLength = (
              ((header[1].toInt() and 0xFF) shl 24) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 8) or
                (header[4].toInt() and 0xFF)
              )
            val payload = ByteArray(payloadLength)
            readExact(input, payload, offset = 0, length = payloadLength)
            onEnvelope(flags, payload)
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

  override fun unary(
    request: E2BEnvdCommandTransportRequest,
  ): E2BResponse {
    val connection = openHttpConnection(
      method = request.method,
      url = request.url,
      headers = request.headers,
      connectTimeoutMs = request.connectTimeoutMs,
      readTimeoutMs = request.readTimeoutMs,
      doOutput = request.bodyBytes.isNotEmpty(),
    )
    return try {
      if (request.bodyBytes.isNotEmpty()) {
        connection.outputStream.use { output ->
          output.write(request.bodyBytes)
        }
      }
      val statusCode = connection.responseCode
      val body = readResponseText(connection, statusCode)
      E2BResponse(statusCode = statusCode, body = body)
    } finally {
      connection.disconnect()
    }
  }

  private fun readExact(
    input: InputStream,
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ) {
    var remaining = length
    var currentOffset = offset
    while (remaining > 0) {
      val read = input.read(buffer, currentOffset, remaining)
      if (read < 0) {
        throw EOFException("Unexpected end of E2B envd stream.")
      }
      remaining -= read
      currentOffset += read
    }
  }
}
