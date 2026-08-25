package com.opencray.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ApplyTextEditsTest {

  @Test
  fun replacesFirstOccurrenceLiterallyWhenOldStringContainsRegexMetacharacters() {
    val source = "int a = compute(x) + compute(y);"

    val outcome = applyTextEdits(
      source = source,
      edits = listOf(
        TextEdit(
          oldString = "compute(",
          newString = "evaluate[0](",
          replaceAll = false,
        ),
      ),
    )

    assertEquals("int a = evaluate[0](x) + compute(y);", outcome.content)
    assertEquals(1, outcome.replacementCount)
  }

  @Test
  fun writesNewStringVerbatimWhenItContainsDollarAndBackslash() {
    val source = "val greeting = \"hello\""

    val outcome = applyTextEdits(
      source = source,
      edits = listOf(
        TextEdit(
          oldString = "\"hello\"",
          newString = "\"\$HOME \\ \${var}\"",
          replaceAll = false,
        ),
      ),
    )

    assertEquals("val greeting = \"\$HOME \\ \${var}\"", outcome.content)
    assertEquals(1, outcome.replacementCount)
  }

  @Test
  fun replaceFirstOccurrenceKeepsLaterMatchesUntouchedAtIndexOfPosition() {
    val text = "a*b*a*b"
    val target = "*b*"
    val expectedIndex = text.indexOf(target)

    val result = replaceFirstOccurrence(text, target, "+")

    assertEquals(expectedIndex, result.indexOf("+"))
    assertEquals("a+b*a*b", result)
  }

  @Test
  fun singleReplacementOnlyChangesFirstOccurrence() {
    val source = "return foo(); // foo() again\nfoo()"

    val outcome = applyTextEdits(
      source = source,
      edits = listOf(
        TextEdit(
          oldString = "foo()",
          newString = "bar()",
          replaceAll = false,
        ),
      ),
    )

    assertEquals("return bar(); // foo() again\nfoo()", outcome.content)
    assertEquals(1, outcome.replacementCount)
  }

  @Test
  fun replaceAllStillReplacesEveryLiteralOccurrence() {
    val source = "path = a.b + a.b"

    val outcome = applyTextEdits(
      source = source,
      edits = listOf(
        TextEdit(
          oldString = "a.b",
          newString = "c\$d",
          replaceAll = true,
        ),
      ),
    )

    assertEquals("path = c\$d + c\$d", outcome.content)
    assertEquals(2, outcome.replacementCount)
  }

  @Test
  fun missingOldStringFailsWithUnchangedMessage() {
    try {
      applyTextEdits(
        source = "nothing to see here",
        edits = listOf(
          TextEdit(
            oldString = "absent",
            newString = "x",
            replaceAll = false,
          ),
        ),
      )
      fail("Expected missing old_string to be rejected.")
    } catch (error: IllegalArgumentException) {
      assertEquals("Edit 1 old_string was not found in the target file.", error.message)
    }
  }

  @Test
  fun ambiguousSingleMatchWithoutReplaceAllStillRejected() {
    try {
      applyTextEdits(
        source = "dup dup",
        edits = listOf(
          TextEdit(
            oldString = "dup",
            newString = "x",
            replaceAll = false,
          ),
        ),
      )
      fail("Expected ambiguous old_string to be rejected.")
    } catch (error: IllegalArgumentException) {
      assertEquals(
        "Edit 1 old_string is ambiguous; found 2 matches. Set replace_all=true to replace every match.",
        error.message,
      )
    }
  }

  @Test
  fun multiEditsApplySequentiallyWithLiteralSemantics() {
    val source = "\${a} \${a} list.append(x)"

    val outcome = applyTextEdits(
      source = source,
      edits = listOf(
        TextEdit(
          oldString = "append(",
          newString = "extend([",
          replaceAll = false,
        ),
        TextEdit(
          oldString = "\${a}",
          newString = "\${b}\$1",
          replaceAll = true,
        ),
      ),
    )

    assertEquals("\${b}\$1 \${b}\$1 list.extend([x)", outcome.content)
    assertEquals(3, outcome.replacementCount)
  }

  @Test
  fun replaceFirstOccurrenceReturnsSameInstanceWhenTargetMissing() {
    val text = "unchanged"

    val result = replaceFirstOccurrence(text, "missing", "x")

    assertSame(text, result)
  }

  @Test
  fun replaceFirstOccurrenceHandlesOverlappingPrefixTargets() {
    val text = "aaaa"

    val result = replaceFirstOccurrence(text, "aa", "b")

    assertEquals("baa", result)
  }
}
