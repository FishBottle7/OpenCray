package com.opencray.app

import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableToolCall
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.subagent.SubAgentApprovalResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskApprovalRegistryTest {
  @Test
  fun consumeApprovedReturnsPromptResumeStateOnce() {
    val registry = AgentTaskApprovalRegistry()
    val resumeState = OpenCrayPromptResumeState(
      transcriptDelta = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"type":"tool_call","tool_name":"Write"}""",
        ),
      ),
      turnIndex = 0,
      toolCallCount = 0,
      pendingToolCall = OpenCraySerializableToolCall(
        toolName = "Write",
      ),
    )

    registry.markApproved(
      sessionId = "session-1",
      taskId = "task-1",
      toolName = "Write",
      promptResumeState = resumeState,
    )

    val grant = requireNotNull(registry.consumeApproved("session-1", "task-1"))

    assertEquals("Write", grant.toolName)
    assertEquals(resumeState, grant.promptResumeState)
    assertNull(registry.consumeApproved("session-1", "task-1"))
  }

  @Test
  fun consumeApprovedReturnsSubAgentResumeStateOnce() {
    val registry = AgentTaskApprovalRegistry()
    val parentResumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val childResumeState = OpenCrayPromptResumeState(
      turnIndex = 0,
      toolCallCount = 1,
    )
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Read",
      promptResumeState = childResumeState,
      isHighRisk = true,
    )

    registry.markApproved(
      sessionId = "session-1",
      taskId = "task-1",
      toolName = "Read",
      promptResumeState = parentResumeState,
      subAgentApprovalResume = childResume,
    )

    val grant = requireNotNull(registry.consumeApproved("session-1", "task-1"))

    assertEquals("Read", grant.toolName)
    assertEquals(parentResumeState, grant.promptResumeState)
    assertEquals(childResume, grant.subAgentApprovalResume)
    assertNull(registry.consumeApproved("session-1", "task-1"))
  }

  @Test
  fun consumeRejectedReturnsResumeStateOnce() {
    val registry = AgentTaskApprovalRegistry()
    val parentResumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val childResumeState = OpenCrayPromptResumeState(
      turnIndex = 0,
      toolCallCount = 1,
    )
    val childResume = SubAgentApprovalResume(
      approvedToolName = "Read",
      promptResumeState = childResumeState,
      isHighRisk = true,
    )

    registry.markRejected(
      sessionId = "session-1",
      taskId = "task-1",
      toolName = "Read",
      promptResumeState = parentResumeState,
      subAgentApprovalResume = childResume,
    )

    assertNull(registry.consumeApproved("session-1", "task-1"))
    val rejection = requireNotNull(registry.consumeRejected("session-1", "task-1"))

    assertEquals("Read", rejection.toolName)
    assertEquals(parentResumeState, rejection.promptResumeState)
    assertEquals(childResume, rejection.subAgentApprovalResume)
    assertFalse(registry.isRejected("session-1", "task-1"))
    assertFalse(registry.isApproved("session-1", "task-1"))
    assertNull(registry.consumeRejected("session-1", "task-1"))
  }
}
