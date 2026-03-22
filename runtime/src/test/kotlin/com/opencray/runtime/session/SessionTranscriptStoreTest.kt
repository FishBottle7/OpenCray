package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptStoreTest {
  @Test
  fun seedIfEmptyOnlySeedsOnceAndAppendIfDistinctSkipsAdjacentDuplicates() {
    val store = InMemorySessionTranscriptStore()
    val seeded = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "First prompt",
      ),
    )

    store.seedIfEmpty(seeded)
    store.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Should not replace the existing seed",
        ),
      ),
    )
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "First prompt",
      ),
    )
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = "Answer",
      ),
    )
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = "Answer",
      ),
    )

    assertEquals(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "First prompt",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "Answer",
        ),
      ),
      store.snapshot(),
    )
  }

  @Test
  fun snapshotRepairsAdjacentDuplicatesAndTrimsContent() {
    val store = InMemorySessionTranscriptStore()

    store.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "  First prompt  ",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "  Final answer  ",
        ),
      ),
    )

    assertEquals(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "First prompt",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "Final answer",
        ),
      ),
      store.snapshot(),
    )
  }

  @Test
  fun snapshotPrunesToolReplayByInteractionCategoryAndKeepsRecentPairs() {
    val store = InMemorySessionTranscriptStore()
    val seeded = buildList {
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Start",
        ),
      )
      addToolInteraction(runId = "run-discovery-1", taskId = "task-discovery-1", turn = 1, toolName = "Read")
      addToolInteraction(runId = "run-discovery-2", taskId = "task-discovery-2", turn = 2, toolName = "Grep")
      addToolInteraction(runId = "run-discovery-3", taskId = "task-discovery-3", turn = 3, toolName = "Glob")
      addToolInteraction(runId = "run-mutation-1", taskId = "task-mutation-1", turn = 4, toolName = "Write")
      addToolInteraction(runId = "run-mutation-2", taskId = "task-mutation-2", turn = 5, toolName = "Edit")
      addToolInteraction(runId = "run-mutation-3", taskId = "task-mutation-3", turn = 6, toolName = "MultiEdit")
      addToolInteraction(runId = "run-exec-1", taskId = "task-exec-1", turn = 7, toolName = "command_exec")
      addToolInteraction(runId = "run-exec-2", taskId = "task-exec-2", turn = 8, toolName = "python_exec")
      addToolInteraction(runId = "run-state-1", taskId = "task-state-1", turn = 9, toolName = "TodoWrite")
      addToolInteraction(runId = "run-state-2", taskId = "task-state-2", turn = 10, toolName = "TodoWrite")
      addToolInteraction(runId = "run-state-3", taskId = "task-state-3", turn = 11, toolName = "TodoWrite")
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "legacy_tool_observation",
        ),
      )
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
        ),
      )
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "Waiting on the user.",
        ),
      )
    }

    store.seedIfEmpty(seeded)

    val snapshot = store.snapshot()
    val contents = snapshot.map(RuntimeConversationMessage::content)

    assertEquals(
      16,
      snapshot.size,
    )
    assertEquals(RuntimeConversationRole.USER, snapshot.first().role)
    assertFalse(contents.any { it.contains("\"run_id\":\"run-discovery-1\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-discovery-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-discovery-3\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-mutation-1\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-mutation-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-mutation-3\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-exec-1\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-exec-2\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-state-1\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-state-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-state-3\"") })
    assertTrue(contents.contains("legacy_tool_observation"))
    assertTrue(contents.any { it.startsWith("approval_rejected ") })
    assertEquals(RuntimeConversationRole.ASSISTANT, snapshot.last().role)
  }

  @Test
  fun snapshotTreatsSkillsReplayAsDiscoveryAndMutationActivity() {
    val store = InMemorySessionTranscriptStore()
    store.seedIfEmpty(
      buildList {
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = "Start",
          ),
        )
        addToolInteraction(runId = "run-skills-discovery-1", taskId = "task-skills-discovery-1", turn = 1, toolName = "SkillsFind")
        addToolInteraction(runId = "run-skills-discovery-2", taskId = "task-skills-discovery-2", turn = 2, toolName = "SkillsList")
        addToolInteraction(runId = "run-skills-discovery-3", taskId = "task-skills-discovery-3", turn = 3, toolName = "SkillsInspect")
        addToolInteraction(runId = "run-skills-discovery-4", taskId = "task-skills-discovery-4", turn = 4, toolName = "SkillsCheck")
        addToolInteraction(runId = "run-skills-mutation-1", taskId = "task-skills-mutation-1", turn = 5, toolName = "SkillsAdd")
        addToolInteraction(runId = "run-skills-mutation-2", taskId = "task-skills-mutation-2", turn = 6, toolName = "SkillsAddBatch")
        addToolInteraction(runId = "run-skills-mutation-3", taskId = "task-skills-mutation-3", turn = 7, toolName = "SkillsUpdate")
        addToolInteraction(runId = "run-skills-mutation-4", taskId = "task-skills-mutation-4", turn = 8, toolName = "SkillsRemove")
      },
    )

    val snapshot = store.snapshot()
    val contents = snapshot.map(RuntimeConversationMessage::content)

    assertEquals(9, snapshot.size)
    assertFalse(contents.any { it.contains("\"run_id\":\"run-skills-discovery-1\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-skills-discovery-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-skills-discovery-3\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-skills-discovery-4\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-skills-mutation-1\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-skills-mutation-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-skills-mutation-3\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-skills-mutation-4\"") })
  }

  @Test
  fun replaceOverwritesStoreWithNormalizedTranscript() {
    val store = InMemorySessionTranscriptStore()

    store.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "First prompt",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "First answer",
        ),
      ),
    )

    store.replace(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "  Replacement prompt  ",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
      ),
    )

    assertEquals(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Replacement prompt",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool observation",
        ),
      ),
      store.snapshot(),
    )
  }

  private fun MutableList<RuntimeConversationMessage>.addToolInteraction(
    runId: String,
    taskId: String,
    turn: Int,
    toolName: String,
  ) {
    add(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_call {"run_id":"$runId","task_id":"$taskId","turn":$turn,"tool_name":"$toolName","arguments":{"path":"."}}""",
      ),
    )
    add(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_result {"run_id":"$runId","task_id":"$taskId","turn":$turn,"tool_name":"$toolName","status":"success","content_preview":"ok"}""",
      ),
    )
  }
}
