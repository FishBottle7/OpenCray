package com.opencray.app

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
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
}
