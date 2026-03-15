package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptWindowBuilderTest {
  @Test
  fun buildPrefersRecentHumanTurnsOverToolNoise() {
    val builder = TranscriptWindowBuilder(
      TranscriptWindowConfig(
        maxMessages = 4,
        maxCharsPerMessage = 120,
      ),
    )

    val window = builder.build(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "user-1"),
        RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "assistant-1"),
        RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, """tool_call workspace_list_files {"path":"."}"""),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "tool observation 1"),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "user-2"),
        RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "assistant-2"),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "tool observation 2"),
      ),
    )

    assertEquals(4, window.messages.size)
    assertEquals(3, window.omittedMessageCount)
    assertTrue(window.messages.any { it.role == RuntimeConversationRole.USER && it.content == "user-2" })
    assertTrue(window.messages.any { it.role == RuntimeConversationRole.ASSISTANT && it.content == "assistant-2" })
    assertFalse(window.messages.any { it.role == RuntimeConversationRole.TOOL && it.content == "tool observation 1" })
  }

  @Test
  fun buildCompactsToolPayloadsBeforeTruncation() {
    val builder = TranscriptWindowBuilder(
      TranscriptWindowConfig(
        maxMessages = 3,
        maxCharsPerMessage = 80,
      ),
    )

    val window = builder.build(
      listOf(
        RuntimeConversationMessage(
          RuntimeConversationRole.TOOL,
          "line 1\n\nline 2 with extra spacing    and more detail that should be compacted before truncation",
        ),
      ),
    )

    assertEquals(1, window.truncatedMessageCount)
    assertFalse(window.messages.first().content.contains("\n"))
    assertTrue(window.messages.first().content.endsWith("…"))
  }

  @Test
  fun buildSelectionExposesOmittedMessagesForCompaction() {
    val builder = TranscriptWindowBuilder(
      TranscriptWindowConfig(
        maxMessages = 2,
        maxCharsPerMessage = 80,
      ),
    )

    val selection = builder.buildSelection(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "older-user"),
        RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "older-assistant"),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "latest-user"),
      ),
    )

    assertEquals(1, selection.window.omittedMessageCount)
    assertEquals(listOf("older-user"), selection.omittedMessages.map { message -> message.content })
    assertEquals(listOf("older-user", "older-assistant", "latest-user"), selection.normalizedMessages.map { message -> message.content })
  }
}
