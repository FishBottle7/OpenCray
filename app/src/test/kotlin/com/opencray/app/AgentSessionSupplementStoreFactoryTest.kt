package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionSupplementStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsAndConsumesSupplementsByRun() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements")
    val firstFactory = FileBackedAgentSessionSupplementStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-1")

    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "First supplement",
    )
    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "Second supplement",
    )
    firstStore.append(
      runId = "run-2",
      taskId = "task-2",
      text = "Other run supplement",
    )

    val restoredStore = FileBackedAgentSessionSupplementStoreFactory(root).forChatSession("session-1")
    val consumed = restoredStore.consumeForRun(runId = "run-1", taskId = "task-1")

    assertEquals(
      listOf("First supplement", "Second supplement"),
      consumed.map(MidLoopSupplementEntry::text),
    )
    assertEquals(
      listOf("Other run supplement"),
      restoredStore.snapshot().map(MidLoopSupplementEntry::text),
    )
  }

  @Test
  fun fileBackedStoreSeparatesSessionsAndCanConsumeAll() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements-isolated")
    val factory = FileBackedAgentSessionSupplementStoreFactory(root)

    factory.forChatSession("session-a").append(
      runId = "run-a",
      taskId = "task-a",
      text = "Session A",
    )
    factory.forChatSession("session-b").append(
      runId = "run-b",
      taskId = "task-b",
      text = "Session B",
    )

    val sessionAStore = factory.forChatSession("session-a")
    val consumedAll = sessionAStore.consumeAll()

    assertEquals(listOf("Session A"), consumedAll.map(MidLoopSupplementEntry::text))
    assertTrue(sessionAStore.snapshot().isEmpty())
    assertEquals(listOf("Session B"), factory.forChatSession("session-b").snapshot().map(MidLoopSupplementEntry::text))
  }

  @Test
  fun fileBackedStoreConsumesRunSupplementsOnlyOnceAcrossStoreInstances() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements-consume-once")
    val factory = FileBackedAgentSessionSupplementStoreFactory(root)
    val firstStore = factory.forChatSession("session-1")
    val secondStore = factory.forChatSession("session-1")

    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "First supplement",
    )

    val consumed = firstStore.consumeForRun(runId = "run-1", taskId = "task-1")

    assertEquals(listOf("First supplement"), consumed.map(MidLoopSupplementEntry::text))
    assertTrue(secondStore.consumeForRun(runId = "run-1", taskId = "task-1").isEmpty())
    assertTrue(factory.forChatSession("session-1").snapshot().isEmpty())
  }
}
