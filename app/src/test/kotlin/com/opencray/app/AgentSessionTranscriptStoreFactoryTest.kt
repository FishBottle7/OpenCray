package com.opencray.app

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionTranscriptStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsTranscriptAcrossFactoryRecreation() {
    val root = temporaryFolder.newFolder("agent-runtime")
    val firstFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-1")

    firstStore.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Initial prompt",
        ),
      ),
    )
    firstStore.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "approval_rejected tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
      ),
    )

    val secondFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val restoredStore = secondFactory.forChatSession("session-1")

    assertEquals(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Initial prompt",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "approval_rejected tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
        ),
      ),
      restoredStore.snapshot(),
    )
  }

  @Test
  fun fileBackedStoreSeparatesSessionsByDirectory() {
    val root = temporaryFolder.newFolder("agent-runtime-isolated")
    val factory = FileBackedAgentSessionTranscriptStoreFactory(root)

    factory.forChatSession("session-a").appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Session A",
      ),
    )
    factory.forChatSession("session-b").appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Session B",
      ),
    )

    assertEquals("Session A", factory.forChatSession("session-a").snapshot().single().content)
    assertEquals("Session B", factory.forChatSession("session-b").snapshot().single().content)
    assertTrue(root.listFiles().orEmpty().size >= 2)
  }

  @Test
  fun fileBackedStoreRepairsAndPrunesTranscriptAcrossRecreation() {
    val root = temporaryFolder.newFolder("agent-runtime-repair")
    val firstFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-repair")

    firstStore.seedIfEmpty(
      buildList {
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = "  Initial prompt  ",
          ),
        )
        addToolInteraction(runId = "run-discovery-1", taskId = "task-discovery-1", turn = 1, toolName = "Read")
        addToolInteraction(runId = "run-discovery-2", taskId = "task-discovery-2", turn = 2, toolName = "Grep")
        addToolInteraction(runId = "run-discovery-3", taskId = "task-discovery-3", turn = 3, toolName = "Glob")
        addToolInteraction(runId = "run-mutation-1", taskId = "task-mutation-1", turn = 4, toolName = "Write")
        addToolInteraction(runId = "run-mutation-2", taskId = "task-mutation-2", turn = 5, toolName = "Edit")
        addToolInteraction(runId = "run-mutation-3", taskId = "task-mutation-3", turn = 6, toolName = "MultiEdit")
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = "legacy_tool_observation",
          ),
        )
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = "approval_rejected tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
          ),
        )
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = "approval_rejected tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
          ),
        )
      },
    )

    val secondFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val restored = secondFactory.forChatSession("session-repair").snapshot()
    val contents = restored.map(RuntimeConversationMessage::content)

    assertEquals(
      11,
      restored.size,
    )
    assertEquals("Initial prompt", restored.first().content)
    assertFalse(contents.any { it.contains("\"run_id\":\"run-discovery-1\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-discovery-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-discovery-3\"") })
    assertFalse(contents.any { it.contains("\"run_id\":\"run-mutation-1\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-mutation-2\"") })
    assertTrue(contents.any { it.contains("\"run_id\":\"run-mutation-3\"") })
    assertTrue(contents.contains("legacy_tool_observation"))
    assertEquals(
      "approval_rejected tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
      restored.last().content,
    )
  }

  @Test
  fun fileBackedStoreReplacePersistsAcrossFactoryRecreation() {
    val root = temporaryFolder.newFolder("agent-runtime-replace")
    val firstFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-replace")

    firstStore.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Initial prompt",
        ),
      ),
    )
    firstStore.replace(
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

    val secondFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val restoredStore = secondFactory.forChatSession("session-replace")

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
      restoredStore.snapshot(),
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
