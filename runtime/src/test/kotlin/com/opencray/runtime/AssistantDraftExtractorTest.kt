package com.opencray.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantDraftExtractorTest {
  @Test
  fun partialJsonStringFieldValueDecodesCompleteUnicodeEscape() {
    assertEquals(
      "line\ncafé \uD83D\uDE00",
      partialJsonStringFieldValue(
        rawText = """{"type":"final","answer":"line\ncafé \ud83d\ude00"}""",
        fieldName = "answer",
      ),
    )
  }

  @Test
  fun partialJsonStringFieldValueKeepsIncompleteUnicodeEscapeSequenceRaw() {
    assertEquals(
      "caf\\u00e",
      partialJsonStringFieldValue(
        rawText = "{\"answer\":\"caf\\u00e",
        fieldName = "answer",
      ),
    )
  }

  @Test
  fun partialJsonStringFieldValuePassesUnknownEscapesThroughUnchanged() {
    assertEquals(
      "a\\xb",
      partialJsonStringFieldValue(
        rawText = """{"answer":"a\xb"}""",
        fieldName = "answer",
      ),
    )
  }
}
