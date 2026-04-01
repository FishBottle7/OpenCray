package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentMailboxTest {
  @Test
  fun pendingMessagesStartAfterLastDeliveredMarker() {
    val mailbox = SubAgentMailbox(
      messages = listOf(
        SubAgentMailboxMessage(
          messageId = "message-1",
          text = "First input",
          createdAtEpochMs = 100L,
        ),
        SubAgentMailboxMessage(
          messageId = "message-2",
          text = "Second input",
          createdAtEpochMs = 200L,
        ),
        SubAgentMailboxMessage(
          messageId = "message-3",
          text = "Third input",
          createdAtEpochMs = 300L,
        ),
      ),
      lastDeliveredMessageId = "message-2",
    )

    assertEquals(listOf("Third input"), mailbox.pendingMessages().map(SubAgentMailboxMessage::text))
  }

  @Test
  fun legacySupplementalInputsStayPendingWhileChildIsStillQueued() {
    val handle = sampleHandle(
      supplementalInputs = listOf(
        "Also inspect docs/notes.md.",
        "Mention the test layout too.",
      ),
    ).withNormalizedMailbox()

    assertTrue(handle.supplementalInputs.isEmpty())
    assertEquals(
      listOf(
        "Also inspect docs/notes.md.",
        "Mention the test layout too.",
      ),
      handle.mailbox.messages.map(SubAgentMailboxMessage::text),
    )
    assertEquals(null, handle.mailbox.lastDeliveredMessageId)
    assertEquals(2, handle.mailbox.pendingMessages().size)
  }

  @Test
  fun legacySupplementalInputsMigrateAsDeliveredWhenResumeStateAlreadyExists() {
    val handle = sampleHandle(
      supplementalInputs = listOf("Check docs/notes.md before you continue."),
      childPromptResumeState = OpenCrayPromptResumeState(
        turnIndex = 1,
        toolCallCount = 1,
      ),
    ).withNormalizedMailbox()

    assertTrue(handle.supplementalInputs.isEmpty())
    assertEquals(
      listOf("Check docs/notes.md before you continue."),
      handle.mailbox.messages.map(SubAgentMailboxMessage::text),
    )
    assertEquals("legacy-child-1-1", handle.mailbox.lastDeliveredMessageId)
    assertTrue(handle.mailbox.pendingMessages().isEmpty())
  }

  private fun sampleHandle(
    supplementalInputs: List<String> = emptyList(),
    childPromptResumeState: OpenCrayPromptResumeState? = null,
  ): SubAgentHandleState = SubAgentHandleState(
    agentId = "child-1",
    childRunId = "child-run-1",
    childTaskId = "child-task-1",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    supplementalInputs = supplementalInputs,
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "run-parent",
    parentTaskId = "task-parent",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(
      headline = "Queued delegated child run 'Inspect README'.",
    ),
    childPromptResumeState = childPromptResumeState,
    createdAtEpochMs = 100L,
    updatedAtEpochMs = 200L,
  )
}
