package com.opencray.app

import com.opencray.runtime.compaction.DurableCompactionEntry
import com.opencray.runtime.compaction.DurableCompactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionCompactionStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsCompactionStateAcrossFactoryRecreation() {
    val root = temporaryFolder.newFolder("agent-runtime-compaction")
    val firstFactory = FileBackedAgentSessionCompactionStoreFactory(root)
    firstFactory.forChatSession("session-1").save(
      DurableCompactionState(
        entries = listOf(
          DurableCompactionEntry(
            text = "Compacted 6 older message(s) outside the active transcript window.",
            compactedMessageCount = 6,
            compactedAtEpochMs = 1_000L,
          ),
        ),
      ),
    )

    val secondFactory = FileBackedAgentSessionCompactionStoreFactory(root)
    val restored = secondFactory.forChatSession("session-1").load()

    assertEquals(1, restored.entries.size)
    assertEquals(6, restored.entries.single().compactedMessageCount)
    assertEquals(1_000L, restored.entries.single().compactedAtEpochMs)
  }

  @Test
  fun fileBackedStoreSeparatesSessionsByDirectory() {
    val root = temporaryFolder.newFolder("agent-runtime-compaction-isolated")
    val factory = FileBackedAgentSessionCompactionStoreFactory(root)

    factory.forChatSession("session-a").save(
      DurableCompactionState(
        entries = listOf(
          DurableCompactionEntry(
            text = "Compacted 4 older message(s).",
            compactedMessageCount = 4,
          ),
        ),
      ),
    )
    factory.forChatSession("session-b").save(
      DurableCompactionState(
        entries = listOf(
          DurableCompactionEntry(
            text = "Compacted 8 older message(s).",
            compactedMessageCount = 8,
          ),
        ),
      ),
    )

    assertEquals(4, factory.forChatSession("session-a").load().entries.single().compactedMessageCount)
    assertEquals(8, factory.forChatSession("session-b").load().entries.single().compactedMessageCount)
    assertTrue(root.listFiles().orEmpty().size >= 2)
  }
}
