package com.opencray.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingErrorCodesTest {
  @Test
  fun mapsKnownStringCodesToShortCodes() {
    assertEquals("E0001", UserFacingErrorCodes.shortCodeOf("DENY_POLICY"))
    assertEquals("E0002", UserFacingErrorCodes.shortCodeOf("APPROVAL_REQUIRED"))
    assertEquals("E1001", UserFacingErrorCodes.shortCodeOf("TIMEOUT"))
    assertEquals("E2010", UserFacingErrorCodes.shortCodeOf("PROVIDER_TIMEOUT_FALLBACK_APPLIED"))
    assertEquals(
      "E2022",
      UserFacingErrorCodes.shortCodeOf("PROVIDER_RATE_LIMIT_429_FALLBACK_EXHAUSTED"),
    )
    assertEquals("E2040", UserFacingErrorCodes.shortCodeOf("MISSING_LLM_CONFIG"))
    assertEquals("E3003", UserFacingErrorCodes.shortCodeOf("RESTART_REQUIRES_EXPLICIT_RETRY"))
    assertEquals("E4005", UserFacingErrorCodes.shortCodeOf("ROLLBACK_FAILED"))
    assertEquals("E5002", UserFacingErrorCodes.shortCodeOf("MISSING_FRONT_MATTER"))
    assertEquals("E7001", UserFacingErrorCodes.shortCodeOf("TERMUX_UNAVAILABLE"))
  }

  @Test
  fun returnsNullForNullBlankUnknownAndWhitespacePaddedInput() {
    assertNull(UserFacingErrorCodes.shortCodeOf(null))
    assertNull(UserFacingErrorCodes.shortCodeOf(""))
    assertNull(UserFacingErrorCodes.shortCodeOf("   "))
    assertNull(UserFacingErrorCodes.shortCodeOf("HTTP_401"))
    assertEquals("E1002", UserFacingErrorCodes.shortCodeOf("  EXEC_ERROR  "))
  }

  @Test
  fun everyShortCodeIsUniqueAndWellFormed() {
    val shortCodes = UserFacingErrorCodes.all().values
    assertEquals(shortCodes.size, shortCodes.distinct().size)
    assertTrue(shortCodes.all { it.matches(Regex("^E\\d{4}$")) })
  }

  @Test
  fun unknownConstantIsReserveOnlyAndNotMappedFromAnyString() {
    assertTrue(UserFacingErrorCodes.UNKNOWN == "E9999")
    assertTrue(UserFacingErrorCodes.UNKNOWN !in UserFacingErrorCodes.all().values)
  }
}
