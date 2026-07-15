package com.opencray.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class RuntimeIsolationEnvdEndpointServer(
  private val processPid: Int,
) : AutoCloseable {
  private val serverSocket = ServerSocket(
    0,
    2,
    InetAddress.getByName("127.0.0.1"),
  )
  private val executor = Executors.newSingleThreadExecutor()
  private val requests = CopyOnWriteArrayList<RuntimeIsolationHttpRequest>()
  private val requestLatches = List(2) { CountDownLatch(1) }
  private val releaseFirstConnection = CountDownLatch(1)
  private val failure = AtomicReference<Throwable?>(null)

  @Volatile
  private var activeSocket: Socket? = null

  val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

  init {
    executor.execute(::serveReconnects)
  }

  fun awaitRequest(index: Int, timeoutSeconds: Long = 30L): RuntimeIsolationHttpRequest {
    require(index in requestLatches.indices)
    check(requestLatches[index].await(timeoutSeconds, TimeUnit.SECONDS)) {
      failure.get()?.let { error ->
        throw AssertionError("Runtime-isolation envd endpoint failed.", error)
      }
      "Timed out waiting for envd request index=$index."
    }
    failure.get()?.let { error ->
      throw AssertionError("Runtime-isolation envd endpoint failed.", error)
    }
    return requests[index]
  }

  fun releaseFirstConnection() {
    releaseFirstConnection.countDown()
  }

  override fun close() {
    releaseFirstConnection.countDown()
    runCatching { activeSocket?.close() }
    runCatching { serverSocket.close() }
    executor.shutdownNow()
    executor.awaitTermination(5L, TimeUnit.SECONDS)
  }

  private fun serveReconnects() {
    try {
      repeat(2) { index ->
        val socket = serverSocket.accept()
        activeSocket = socket
        try {
          serveReconnect(socket = socket, index = index)
        } catch (error: Throwable) {
          if (index != 0 || releaseFirstConnection.count > 0L) {
            throw error
          }
        } finally {
          runCatching { socket.close() }
          activeSocket = null
        }
      }
    } catch (error: Throwable) {
      if (error !is SocketException || !serverSocket.isClosed) {
        failure.compareAndSet(null, error)
      }
      requestLatches.forEach(CountDownLatch::countDown)
    }
  }

  private fun serveReconnect(socket: Socket, index: Int) {
    socket.soTimeout = TimeUnit.SECONDS.toMillis(30L).toInt()
    val input = BufferedInputStream(socket.getInputStream())
    val output = BufferedOutputStream(socket.getOutputStream())
    val request = readRequest(input)
    requests += request
    requestLatches[index].countDown()

    output.write(
      (
        "HTTP/1.1 200 OK\r\n" +
          "Content-Type: application/connect+proto\r\n" +
          "Transfer-Encoding: chunked\r\n" +
          "Connection: close\r\n" +
          "\r\n"
        ).toByteArray(StandardCharsets.US_ASCII),
    )
    writeChunk(
      output,
      E2BEnvdProcessProtoCodec.encodeConnectEnvelope(
        flags = 0,
        payload = E2BEnvdProcessProtoCodec.encodeConnectResponse(
          E2BEnvdProcessEvent.Start(pid = processPid),
        ),
      ),
    )
    writeChunk(
      output,
      E2BEnvdProcessProtoCodec.encodeConnectEnvelope(
        flags = 0,
        payload = E2BEnvdProcessProtoCodec.encodeConnectResponse(
          E2BEnvdProcessEvent.Data(
            stdout = if (index == 0) {
              " attached before process death".toByteArray(StandardCharsets.UTF_8)
            } else {
              " attached after process death".toByteArray(StandardCharsets.UTF_8)
            },
          ),
        ),
      ),
    )
    output.flush()

    if (index == 0) {
      check(releaseFirstConnection.await(90L, TimeUnit.SECONDS)) {
        "Timed out waiting to release the first envd connection."
      }
      finishChunks(output)
    } else {
      while (!Thread.currentThread().isInterrupted && !serverSocket.isClosed) {
        Thread.sleep(100L)
      }
    }
  }

  private fun readRequest(input: BufferedInputStream): RuntimeIsolationHttpRequest {
    val headerBytes = ByteArrayOutputStream()
    var matched = 0
    while (matched < HTTP_HEADER_TERMINATOR.size) {
      val next = input.read()
      if (next < 0) {
        throw EOFException("Unexpected end of HTTP request headers.")
      }
      headerBytes.write(next)
      check(headerBytes.size() <= MAX_HEADER_BYTES) {
        "Runtime-isolation envd request headers exceeded $MAX_HEADER_BYTES bytes."
      }
      matched = if (next.toByte() == HTTP_HEADER_TERMINATOR[matched]) {
        matched + 1
      } else if (next.toByte() == HTTP_HEADER_TERMINATOR[0]) {
        1
      } else {
        0
      }
    }
    val lines = String(headerBytes.toByteArray(), StandardCharsets.US_ASCII)
      .trimEnd('\r', '\n')
      .split("\r\n")
    val requestLine = lines.first().split(' ')
    check(requestLine.size >= 2) { "Malformed envd HTTP request line: ${lines.first()}" }
    val headers = lines.drop(1)
      .mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) {
          null
        } else {
          line.substring(0, separator).trim().lowercase() to
            line.substring(separator + 1).trim()
        }
      }
      .toMap()
    val body = when {
      headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
        readChunkedBody(input)
      else -> readExactBytes(
        input = input,
        length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
      )
    }
    return RuntimeIsolationHttpRequest(
      method = requestLine[0],
      path = requestLine[1],
      headers = headers,
      body = body,
    )
  }

  private fun readChunkedBody(input: BufferedInputStream): ByteArray {
    val body = ByteArrayOutputStream()
    while (true) {
      val size = readAsciiLine(input).substringBefore(';').trim().toInt(16)
      if (size == 0) {
        while (readAsciiLine(input).isNotEmpty()) {
          // Consume optional trailers.
        }
        return body.toByteArray()
      }
      body.write(readExactBytes(input, size))
      check(readAsciiLine(input).isEmpty()) { "Malformed chunk terminator." }
    }
  }

  private fun readAsciiLine(input: BufferedInputStream): String {
    val line = ByteArrayOutputStream()
    while (true) {
      val next = input.read()
      if (next < 0) {
        throw EOFException("Unexpected end of chunked HTTP body.")
      }
      if (next == '\r'.code) {
        check(input.read() == '\n'.code) { "Malformed HTTP line terminator." }
        return String(line.toByteArray(), StandardCharsets.US_ASCII)
      }
      line.write(next)
    }
  }

  private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < bytes.size) {
      val read = input.read(bytes, offset, bytes.size - offset)
      if (read < 0) {
        throw EOFException("Unexpected end of HTTP request body.")
      }
      offset += read
    }
    return bytes
  }

  private fun writeChunk(output: BufferedOutputStream, bytes: ByteArray) {
    output.write(bytes.size.toString(16).toByteArray(StandardCharsets.US_ASCII))
    output.write(CRLF)
    output.write(bytes)
    output.write(CRLF)
  }

  private fun finishChunks(output: BufferedOutputStream) {
    output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
    output.flush()
  }

  companion object {
    private const val MAX_HEADER_BYTES: Int = 64 * 1024
    private val CRLF: ByteArray = "\r\n".toByteArray(StandardCharsets.US_ASCII)
    private val HTTP_HEADER_TERMINATOR: ByteArray =
      "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
  }
}

internal data class RuntimeIsolationHttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: ByteArray,
)
