package com.opencray.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPrunerTest {
  @Test
  fun pruneKeepsDuplicateBackgroundMessages() {
    val pruner = ContextPruner()

    val pruned = pruner.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo."),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note: return only one action."),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note: return only one action."),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
      ),
    )

    assertEquals(5, pruned.messages.size)
    assertEquals(null, pruned.summary)
  }

  @Test
  fun pruneOnlyRewritesAttachmentLikePayloads() {
    val pruner = ContextPruner(
      ContextPrunerConfig(
        maxToolChars = 128,
        maxToolLines = 4,
        maxAttachmentChars = 64,
        maxPreviewChars = 64,
      ),
    )

    val ordinaryLongToolOutput = """
      alpha
      beta
      gamma
      delta
      epsilon
    """.trimIndent()
    val attachmentPayload = "data:image/png;base64," + "A".repeat(160)

    val pruned = pruner.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, ordinaryLongToolOutput),
        RuntimeConversationMessage(RuntimeConversationRole.TOOL, attachmentPayload),
      ),
    )

    val summary = requireNotNull(pruned.summary)

    assertEquals(2, pruned.messages.size)
    assertEquals(1, summary.rewrittenMessageCount)
    assertEquals(0, summary.removedMessageCount)
    assertEquals(0, summary.bulkyToolMessageCount)
    assertEquals(1, summary.attachmentLikeMessageCount)
    assertEquals(ordinaryLongToolOutput, pruned.messages[0].content)
    assertTrue(pruned.messages[1].content.startsWith("Attachment-like payload pruned by prompt guardrail."))
    assertFalse(pruned.messages[1].content.contains("A".repeat(80)))
    assertTrue(summary.text.contains("removed=0, rewritten=1"))
    assertTrue(summary.text.contains("tool_output=0"))
    assertTrue(summary.text.contains("attachment_like=1"))
  }

  @Test
  fun pruneKeepsDuplicateStructuredAssistantToolCalls() {
    val pruner = ContextPruner()

    val pruned = pruner.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect README."),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
      ),
    )

    assertEquals(3, pruned.messages.size)
    assertEquals(null, pruned.summary)
    assertEquals(2, pruned.messages.count { it.kind == RuntimeConversationMessageKind.TOOL_CALL })
  }
}
