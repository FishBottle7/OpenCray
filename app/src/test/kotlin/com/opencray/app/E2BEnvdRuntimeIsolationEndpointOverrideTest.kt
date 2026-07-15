package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BEnvdRuntimeIsolationEndpointOverrideTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun storeAcceptsOnlyExplicitLoopbackHttpEndpoint() {
    val store = store()

    store.save(" http://LOCALHOST:43123/ ")

    assertEquals("http://localhost:43123", store.load())
    assertThrows(IllegalArgumentException::class.java) {
      store.save("https://127.0.0.1:43123")
    }
    assertThrows(IllegalArgumentException::class.java) {
      store.save("http://example.com:43123")
    }
    assertThrows(IllegalArgumentException::class.java) {
      store.save("http://127.0.0.1")
    }
    assertThrows(IllegalArgumentException::class.java) {
      store.save("http://127.0.0.1:43123/not-root")
    }
  }

  @Test
  fun malformedDurableOverrideFailsClosedToDefaultEndpoint() {
    val storage = DirectoryDurableTextStorage(temporaryFolder.newFolder("malformed"))
    val store = RuntimeIsolationE2BEnvdEndpointOverrideStore(storage)
    storage.writeText("runtime-isolation-e2b-envd-endpoint.txt", "http://remote.example:80")

    assertNull(store.load())
  }

  @Test
  fun transportRewritesOnlyE2BProcessRequests() {
    val delegate = RecordingTransport()
    val transport = RuntimeIsolationEndpointOverrideE2BEnvdCommandTransport(
      delegate = delegate,
      endpointOverrideProvider = { "http://127.0.0.1:43123" },
    )
    val connect = request(
      url = "https://49983-sandbox.e2b.app/process.Process/Connect?trace=1",
    )
    val unrelated = request(url = "https://49983-sandbox.e2b.app/health")

    transport.stream(connect) { _, _ -> }
    transport.unary(unrelated)

    assertEquals(
      "http://127.0.0.1:43123/process.Process/Connect?trace=1",
      delegate.streamRequest?.url,
    )
    assertSame(unrelated, delegate.unaryRequest)
  }

  private fun store(): RuntimeIsolationE2BEnvdEndpointOverrideStore =
    RuntimeIsolationE2BEnvdEndpointOverrideStore(
      DirectoryDurableTextStorage(temporaryFolder.newFolder("store")),
    )

  private fun request(url: String): E2BEnvdCommandTransportRequest =
    E2BEnvdCommandTransportRequest(
      method = "POST",
      url = url,
      headers = mapOf("Content-Type" to "application/connect+proto"),
      bodyBytes = byteArrayOf(1, 2, 3),
      connectTimeoutMs = 1_000,
      readTimeoutMs = 2_000,
    )

  private class RecordingTransport : E2BEnvdCommandTransport {
    var streamRequest: E2BEnvdCommandTransportRequest? = null
    var unaryRequest: E2BEnvdCommandTransportRequest? = null

    override fun stream(
      request: E2BEnvdCommandTransportRequest,
      onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
    ): E2BResponse {
      streamRequest = request
      return E2BResponse(statusCode = 200)
    }

    override fun unary(request: E2BEnvdCommandTransportRequest): E2BResponse {
      unaryRequest = request
      return E2BResponse(statusCode = 200)
    }
  }
}
