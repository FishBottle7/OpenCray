package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPrunerTest {
  @Test
  fun pruneDropsConsecutiveDuplicateBackgroundMessages() {
    val pruner = ContextPruner()

    val pruned = pruner.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo."),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note: return only one action."),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note: return only one action."),
        RuntimeConversationMessage(
          RuntimeConversationRole.ASSISTANT,
          """tool_call Read {"file_path":"README.md"}""",
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.ASSISTANT,
          """tool_call Read {"file_path":"README.md"}""",
        ),
      ),
    )

    val summary = requireNotNull(pruned.summary)

    assertEquals(3, pruned.messages.size)
    assertEquals(2, summary.removedMessageCount)
    assertEquals(0, summary.rewrittenMessageCount)
    assertEquals(2, summary.duplicateBackgroundMessageCount)
    assertTrue(summary.text.contains("removed=2"))
    assertTrue(summary.text.contains("Dropped consecutive duplicate background messages: 2."))
  }

  @Test
  fun pruneRewritesBulkyToolOutputsAndAttachmentLikePayloads() {
    val pruner = ContextPruner(
      ContextPrunerConfig(
        maxToolChars = 80,
        maxToolLines = 3,
        maxAttachmentChars = 48,
        maxPreviewChars = 64,
      ),
    )

    val longToolOutput = """
      alpha
      beta
      gamma
      delta
      epsilon
    """.trimIndent()
    val attachmentPayload = "data:image/png;base64," + "A".repeat(160)

    val pruned = pruner.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, longToolOutput),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, attachmentPayload),
      ),
    )

    val summary = requireNotNull(pruned.summary)

    assertEquals(2, pruned.messages.size)
    assertEquals(0, summary.removedMessageCount)
    assertEquals(2, summary.rewrittenMessageCount)
    assertEquals(1, summary.bulkyToolMessageCount)
    assertEquals(1, summary.attachmentLikeMessageCount)
    assertTrue(pruned.messages[0].content.startsWith("Tool output pruned for prompt budget."))
    assertTrue(pruned.messages[1].content.startsWith("Attachment-like payload pruned from prompt."))
    assertFalse(pruned.messages[1].content.contains("A".repeat(80)))
    assertTrue(summary.text.contains("tool_output=1"))
    assertTrue(summary.text.contains("attachment_like=1"))
  }
}
