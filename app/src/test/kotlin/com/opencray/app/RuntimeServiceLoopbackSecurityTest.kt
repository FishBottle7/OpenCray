package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeServiceLoopbackSecurityTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun descriptorStorePublishesPerTargetAndOnlyRevokesMatchingEpoch() {
    val store = RuntimeServiceLoopbackDescriptorStore(temporaryFolder.newFolder("descriptors"))
    val first = descriptor(
      target = RuntimeServiceTarget.INTERACTIVE,
      port = 41_001,
    )
    val detached = descriptor(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      port = 41_002,
    )
    store.publish(first)
    store.publish(detached)

    assertEquals(41_001, store.read(RuntimeServiceTarget.INTERACTIVE)?.port)
    assertEquals(
      first.credentials.epoch,
      store.read(RuntimeServiceTarget.INTERACTIVE)?.credentials?.epoch,
    )
    assertEquals(41_002, store.read(RuntimeServiceTarget.DETACHED_BACKGROUND)?.port)

    val replacement = descriptor(
      target = RuntimeServiceTarget.INTERACTIVE,
      port = 41_003,
    )
    store.publish(replacement)

    assertNotEquals(first.credentials.epoch, replacement.credentials.epoch)
    assertFalse(
      store.revoke(
        target = RuntimeServiceTarget.INTERACTIVE,
        expectedEpoch = first.credentials.epoch,
      ),
    )
    assertEquals(41_003, store.read(RuntimeServiceTarget.INTERACTIVE)?.port)
    assertTrue(
      store.revoke(
        target = RuntimeServiceTarget.INTERACTIVE,
        expectedEpoch = replacement.credentials.epoch,
      ),
    )
    assertNull(store.read(RuntimeServiceTarget.INTERACTIVE))
    assertEquals(41_002, store.read(RuntimeServiceTarget.DETACHED_BACKGROUND)?.port)
  }

  @Test
  fun eachCredentialGenerationUsesANewEpochAndSecret() {
    val first = RuntimeServiceLoopbackCredentials.create()
    val second = RuntimeServiceLoopbackCredentials.create()

    assertNotEquals(first.epoch, second.epoch)
    assertNotEquals(first.encodedSecret(), second.encodedSecret())
  }

  @Test
  fun serverAuthenticationRejectsMissingTamperedStaleAndReplayedRequests() {
    val now = 1_000_000L
    val credentials = RuntimeServiceLoopbackCredentials.create()
    val security = RuntimeServiceLoopbackServerSecurity(
      credentials = credentials,
      clock = { now },
    )
    val body = "{\"value\":1}".toByteArray(Charsets.UTF_8)

    assertNull(
      security.authenticate(
        headers = emptyMap(),
        method = "POST",
        requestTarget = "/v1/example?raw=%2F",
        body = body,
      ),
    )

    val validHeaders = requestHeaders(
      credentials = credentials,
      timestampEpochMs = now,
      nonce = "00112233445566778899aabbccddeeff",
      method = "POST",
      requestTarget = "/v1/example?raw=%2F",
      body = body,
    )
    assertNotNull(
      security.authenticate(
        headers = validHeaders,
        method = "POST",
        requestTarget = "/v1/example?raw=%2F",
        body = body,
      ),
    )
    assertNull(
      security.authenticate(
        headers = validHeaders,
        method = "POST",
        requestTarget = "/v1/example?raw=%2F",
        body = body,
      ),
    )

    val tamperedHeaders = requestHeaders(
      credentials = credentials,
      timestampEpochMs = now,
      nonce = "10112233445566778899aabbccddeeff",
      method = "POST",
      requestTarget = "/v1/example?raw=%2F",
      body = body,
    )
    assertNull(
      security.authenticate(
        headers = tamperedHeaders,
        method = "POST",
        requestTarget = "/v1/example?raw=%2f",
        body = body,
      ),
    )
    assertNull(
      security.authenticate(
        headers = requestHeaders(
          credentials = credentials,
          timestampEpochMs = now -
            RuntimeServiceLoopbackServerSecurity.DEFAULT_ALLOWED_CLOCK_SKEW_MS - 1L,
          nonce = "20112233445566778899aabbccddeeff",
          method = "POST",
          requestTarget = "/v1/example?raw=%2F",
          body = body,
        ),
        method = "POST",
        requestTarget = "/v1/example?raw=%2F",
        body = body,
      ),
    )
    assertNull(
      security.authenticate(
        headers = requestHeaders(
          credentials = RuntimeServiceLoopbackCredentials.create(),
          timestampEpochMs = now,
          nonce = "30112233445566778899aabbccddeeff",
          method = "POST",
          requestTarget = "/v1/example?raw=%2F",
          body = body,
        ),
        method = "POST",
        requestTarget = "/v1/example?raw=%2F",
        body = body,
      ),
    )
  }

  @Test
  fun responseSignatureBindsStatusRequestTargetAndExactBody() {
    val now = 2_000_000L
    val credentials = RuntimeServiceLoopbackCredentials.create()
    val security = RuntimeServiceLoopbackServerSecurity(
      credentials = credentials,
      clock = { now },
    )
    val requestBody = ByteArray(0)
    val requestNonce = "40112233445566778899aabbccddeeff"
    val exchange = requireNotNull(
      security.authenticate(
        headers = requestHeaders(
          credentials = credentials,
          timestampEpochMs = now,
          nonce = requestNonce,
          method = "GET",
          requestTarget = "/v1/shell_snapshot",
          body = requestBody,
        ),
        method = "GET",
        requestTarget = "/v1/shell_snapshot",
        body = requestBody,
      ),
    )
    val responseBody = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
    val responseHeaders = security.responseHeaders(
      exchange = exchange,
      statusCode = 200,
      body = responseBody,
    ).mapKeys { (name, _) -> name.lowercase() }

    assertTrue(
      RuntimeServiceLoopbackHttpAuth.verifyResponse(
        credentials = credentials,
        requestTimestampEpochMs = now,
        requestNonce = requestNonce,
        method = "GET",
        requestTarget = "/v1/shell_snapshot",
        statusCode = 200,
        body = responseBody,
        headers = responseHeaders,
        nowEpochMs = now,
      ),
    )
    assertFalse(
      RuntimeServiceLoopbackHttpAuth.verifyResponse(
        credentials = credentials,
        requestTimestampEpochMs = now,
        requestNonce = requestNonce,
        method = "GET",
        requestTarget = "/v1/shell_snapshot?changed=true",
        statusCode = 200,
        body = responseBody,
        headers = responseHeaders,
        nowEpochMs = now,
      ),
    )
    assertFalse(
      RuntimeServiceLoopbackHttpAuth.verifyResponse(
        credentials = credentials,
        requestTimestampEpochMs = now,
        requestNonce = requestNonce,
        method = "GET",
        requestTarget = "/v1/shell_snapshot",
        statusCode = 500,
        body = responseBody,
        headers = responseHeaders,
        nowEpochMs = now,
      ),
    )
    assertFalse(
      RuntimeServiceLoopbackHttpAuth.verifyResponse(
        credentials = credentials,
        requestTimestampEpochMs = now,
        requestNonce = requestNonce,
        method = "GET",
        requestTarget = "/v1/shell_snapshot",
        statusCode = 200,
        body = "{\"ok\":false}".toByteArray(Charsets.UTF_8),
        headers = responseHeaders,
        nowEpochMs = now,
      ),
    )
  }

  private fun descriptor(
    target: RuntimeServiceTarget,
    port: Int,
  ): RuntimeServiceLoopbackDescriptor = RuntimeServiceLoopbackDescriptor(
    target = target,
    port = port,
    credentials = RuntimeServiceLoopbackCredentials.create(),
    publishedAtEpochMs = 123L,
  )

  private fun requestHeaders(
    credentials: RuntimeServiceLoopbackCredentials,
    timestampEpochMs: Long,
    nonce: String,
    method: String,
    requestTarget: String,
    body: ByteArray,
  ): Map<String, String> = RuntimeServiceLoopbackHttpAuth.requestHeaders(
    credentials = credentials,
    timestampEpochMs = timestampEpochMs,
    nonce = nonce,
    method = method,
    requestTarget = requestTarget,
    body = body,
  ).mapKeys { (name, _) -> name.lowercase() }
}
