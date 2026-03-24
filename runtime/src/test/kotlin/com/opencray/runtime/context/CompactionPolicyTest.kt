package com.opencray.runtime.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionPolicyTest {
  @Test
  fun summarizeClassifiesStructuredToolReplayWithoutTreatingToolCallAsAssistantReply() {
    val summary = requireNotNull(
      CompactionPolicy().summarize(
        listOf(
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
            role = RuntimeConversationRole.TOOL,
            content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"structured provider path"}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolCallId = "call-1",
              toolName = "Read",
              status = "success",
              isError = false,
            ),
          ),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = "assistant summary",
          ),
        ),
      ),
    )

    assertTrue(summary.text.contains("Omitted tool activity: discovery=1."))
    assertTrue(summary.text.contains("Most recent omitted assistant reply: assistant summary"))
    assertFalse(summary.text.contains("Most recent omitted assistant reply: {"))
  }
}
