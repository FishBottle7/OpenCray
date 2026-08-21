package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        content = "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
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
          content = "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
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
  fun fileBackedStorePersistsStructuredTranscriptMetadataAcrossFactoryRecreation() {
    val root = temporaryFolder.newFolder("agent-runtime-structured")
    val firstFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-structured")
    val messages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","reason":"Inspect README.","arguments":{"path":"README.md"}}""",
        kind = RuntimeConversationMessageKind.TOOL_CALL,
        toolCall = RuntimeConversationToolCall(
          id = "call-1",
          toolName = "Read",
          arguments = buildJsonObject {
            put("path", "README.md")
          },
          reason = "Inspect README.",
        ),
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"README contents","metadata":{"path":"README.md"}}""",
        kind = RuntimeConversationMessageKind.TOOL_RESULT,
        toolResult = RuntimeConversationToolResult(
          toolCallId = "call-1",
          toolName = "Read",
          status = "success",
          isError = false,
        ),
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"run-1","task_id":"task-1","turn":1,"text":"Planning the edit.","stage":"Planning"}""",
        kind = RuntimeConversationMessageKind.COMMENTARY,
        commentary = RuntimeConversationCommentary(
          runId = "run-1",
          taskId = "task-1",
          turn = 1,
          text = "Planning the edit.",
          stage = "Planning",
        ),
      ),
    )

    firstStore.replaceReplayWorkingCopy(messages)

    val secondFactory = FileBackedAgentSessionTranscriptStoreFactory(root)
    val restoredStore = secondFactory.forChatSession("session-structured")

    assertEquals(messages, restoredStore.snapshot())
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
            content = "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
          ),
        )
        add(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
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
      "approval_rejected task_id=task-approval run_id=run-approval tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
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
    firstStore.replaceReplayWorkingCopy(
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

  @Test
  fun fileBackedStoreAppendUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedSessionTranscriptStore(
      storage = storage,
      clock = { 10_000L },
    )
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "First prompt",
      ),
    )
    val staleBeforeConcurrentAppend = storage.currentText
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = "Second response",
      ),
    )
    val updateCallsBeforeAppend = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentAppend)
    store.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "Third observation",
      ),
    )

    assertEquals(updateCallsBeforeAppend + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("First prompt", "Second response", "Third observation"),
      store.snapshot().map(RuntimeConversationMessage::content),
    )
  }

  private fun MutableList<RuntimeConversationMessage>.addToolInteraction(
    runId: String,
    taskId: String,
    turn: Int,
    toolName: String,
  ) {
    val toolCallId = "$taskId-call"
    add(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = """{"run_id":"$runId","task_id":"$taskId","turn":$turn,"tool_call_id":"$toolCallId","tool_name":"$toolName","arguments":{"path":"."}}""",
        kind = RuntimeConversationMessageKind.TOOL_CALL,
        toolCall = RuntimeConversationToolCall(
          id = toolCallId,
          toolName = toolName,
        ),
      ),
    )
    add(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"$runId","task_id":"$taskId","turn":$turn,"tool_call_id":"$toolCallId","tool_name":"$toolName","status":"success","content":"ok"}""",
        kind = RuntimeConversationMessageKind.TOOL_RESULT,
        toolResult = RuntimeConversationToolResult(
          toolCallId = toolCallId,
          toolName = toolName,
          status = "success",
          isError = false,
        ),
      ),
    )
  }

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }
}
