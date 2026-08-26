package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteLlmClientRetryAfterParsingTest {
  private val client = OpenAiCompatibleLiteLlmProviderClient()

  @Test
  fun parsesDeltaSecondsIntoMillis() {
    assertEquals(120_000L, client.parseRetryAfterMillis("120"))
    assertEquals(1_000L, client.parseRetryAfterMillis(" 1 "))
  }

  @Test
  fun clampsNegativeDeltaSecondsToZero() {
    assertEquals(0L, client.parseRetryAfterMillis("-5"))
  }

  @Test
  fun returnsNullForBlankOrUnparseableHeader() {
    assertNull(client.parseRetryAfterMillis(null))
    assertNull(client.parseRetryAfterMillis(""))
    assertNull(client.parseRetryAfterMillis("   "))
    assertNull(client.parseRetryAfterMillis("soon"))
  }

  @Test
  fun convertsHttpDateRelativeToInjectedClock() {
    assertEquals(
      100_000L,
      client.parseRetryAfterMillis(
        "Wed, 26 Aug 2026 12:00:00 GMT",
        clockEpochMs = 1_787_745_500_000L,
      ),
    )
    assertEquals(
      0L,
      client.parseRetryAfterMillis(
        "Wed, 26 Aug 2026 12:00:00 GMT",
        clockEpochMs = 1_787_745_600_000L,
      ),
    )
  }

  @Test
  fun clampsPastHttpDateToZeroMillis() {
    assertEquals(
      0L,
      client.parseRetryAfterMillis(
        "Wed, 26 Aug 2026 12:00:00 GMT",
        clockEpochMs = 1_787_755_600_000L,
      ),
    )
  }

  @Test
  fun returnsNullWhenHttpDateIsMalformed() {
    assertNull(
      client.parseRetryAfterMillis(
        "Wed, 99 Xxx 2026 12:00:00 GMT",
        clockEpochMs = 1_787_745_500_000L,
      ),
    )
  }
}
