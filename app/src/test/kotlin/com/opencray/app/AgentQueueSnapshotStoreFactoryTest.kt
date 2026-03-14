package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueue
import com.opencray.core.orchestrator.SessionTaskRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentQueueSnapshotStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun sameChatSessionRestoresPersistedQueueSnapshot() {
    val factory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root)
    val snapshotStore = factory.forChatSession("session/a")
    val runtime = SessionTaskRuntime { _, _ -> error("Runtime should not be invoked in this snapshot test.") }

    val queue = SessionQueue(
      sessionId = "session/a",
      agentId = "opencray-app",
      runtime = runtime,
      snapshotStore = snapshotStore,
      clock = FixedQueueClock(1_000L),
    )
    queue.enqueue(task("task-1"))

    val restored = SessionQueue(
      sessionId = "session/a",
      agentId = "opencray-app",
      runtime = runtime,
      snapshotStore = factory.forChatSession("session/a"),
      clock = FixedQueueClock(2_000L),
    ).snapshot()

    assertEquals(SessionLifecycleState.IDLE, restored.lifecycleState)
    assertEquals(listOf("task-1"), restored.tasks.map { it.task.id })
  }

  @Test
  fun differentChatSessionsDoNotShareQueueSnapshots() {
    val factory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root)
    val runtime = SessionTaskRuntime { _, _ -> error("Runtime should not be invoked in this snapshot test.") }

    val queueA = SessionQueue(
      sessionId = "session-a",
      agentId = "opencray-app",
      runtime = runtime,
      snapshotStore = factory.forChatSession("session-a"),
      clock = FixedQueueClock(1_000L),
    )
    queueA.enqueue(task("task-a"))

    val snapshotB = SessionQueue(
      sessionId = "session-b",
      agentId = "opencray-app",
      runtime = runtime,
      snapshotStore = factory.forChatSession("session-b"),
      clock = FixedQueueClock(2_000L),
    ).snapshot()

    assertEquals(emptyList<String>(), snapshotB.tasks.map { it.task.id })
  }

  @Test
  fun sessionDirectoryEncodingAvoidsSanitizationCollisions() {
    val factory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root)

    val slashPath = factory.directoryForSession("session/a").name
    val underscorePath = factory.directoryForSession("session_a").name

    assertNotEquals(slashPath, underscorePath)
  }

  private fun task(id: String): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = "prompt-$id",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )

  private class FixedQueueClock(
    private val now: Long,
  ) : com.opencray.core.orchestrator.QueueClock {
    override fun nowEpochMs(): Long = now
  }
}
