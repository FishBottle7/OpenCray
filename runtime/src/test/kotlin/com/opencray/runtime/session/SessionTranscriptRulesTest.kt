package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptRulesTest {
  @Test
  fun pruneGroupsStructuredAssistantToolCallsWithTheirToolResults() {
    val pruned = SessionTranscriptRules.prune(
      listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "user-1"),
        structuredToolCall(turn = 1, toolCallId = "call-1"),
        structuredToolResult(turn = 1, toolCallId = "call-1"),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "user-2"),
        structuredToolCall(turn = 2, toolCallId = "call-2"),
        structuredToolResult(turn = 2, toolCallId = "call-2"),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "user-3"),
        structuredToolCall(turn = 3, toolCallId = "call-3"),
        structuredToolResult(turn = 3, toolCallId = "call-3"),
      ),
    )

    assertFalse(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_CALL && it.toolCall?.id == "call-1" })
    assertFalse(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_RESULT && it.toolResult?.toolCallId == "call-1" })
    assertTrue(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_CALL && it.toolCall?.id == "call-2" })
    assertTrue(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_RESULT && it.toolResult?.toolCallId == "call-2" })
    assertTrue(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_CALL && it.toolCall?.id == "call-3" })
    assertTrue(pruned.any { it.kind == RuntimeConversationMessageKind.TOOL_RESULT && it.toolResult?.toolCallId == "call-3" })
  }

  private fun structuredToolCall(
    turn: Int,
    toolCallId: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.ASSISTANT,
    content = """{"run_id":"run-1","task_id":"task-1","turn":$turn,"tool_call_id":"$toolCallId","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
    kind = RuntimeConversationMessageKind.TOOL_CALL,
    toolCall = RuntimeConversationToolCall(
      id = toolCallId,
      toolName = "Read",
    ),
  )

  private fun structuredToolResult(
    turn: Int,
    toolCallId: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = """{"run_id":"run-1","task_id":"task-1","turn":$turn,"tool_call_id":"$toolCallId","tool_name":"Read","status":"success","content":"README body"}""",
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    toolResult = RuntimeConversationToolResult(
      toolCallId = toolCallId,
      toolName = "Read",
      status = "success",
      isError = false,
    ),
  )
}
