package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionQueueForeignSnapshotTest {

  private class IncrementingClock(
    start: Long,
  ) : QueueClock {
    private var now = start

    override fun nowEpochMs(): Long = now++
  }

  @Test
  fun constructorWithForeignSnapshotLeavesStoreUntouchedAndInitializesEmptyMemoryState() {
    val foreignSnapshot = SessionQueueSnapshot(
      sessionId = "foreign-session",
      agentId = "foreign-agent",
      lifecycleState = SessionLifecycleState.RUNNING,
      nextEnqueueOrder = 7L,
      tasks = listOf(
        SessionQueueTaskSnapshot(
          enqueueOrder = 3L,
          task = task(id = "foreign-task", createdAt = 500L),
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          attempt = 2,
        ),
      ),
      updatedAtEpochMs = 600L,
    )
    val store = InMemorySessionQueueSnapshotStore(foreignSnapshot)
    var transformerObserved: SessionQueueSnapshot? = null

    val queue = SessionQueue(
      sessionId = "own-session",
      agentId = "own-agent",
      runtime = failingRuntime(),
      snapshotStore = store,
      restoreTransformer = SessionQueueRestoreTransformer { snapshot, _ ->
        transformerObserved = snapshot
        snapshot
      },
      clock = IncrementingClock(start = 10_000L),
    )

    assertEquals(foreignSnapshot, store.load())
    assertEquals(foreignSnapshot, transformerObserved)
    assertTrue(queue.snapshot().tasks.isEmpty())
    assertEquals(1L, queue.snapshot().nextEnqueueOrder)
    assertEquals(SessionLifecycleState.IDLE, queue.currentSessionState())

    queue.enqueue(task(id = "own-task", createdAt = 700L))

    val ownSnapshot = queue.snapshot()
    assertEquals(1L, ownSnapshot.tasks.single().enqueueOrder)
    assertEquals(2L, ownSnapshot.nextEnqueueOrder)
    val persistedAfterEnqueue = store.load()
    assertNotNull(persistedAfterEnqueue)
    assertEquals("own-session", persistedAfterEnqueue!!.sessionId)
    assertTrue(persistedAfterEnqueue.tasks.none { it.task.id == "foreign-task" })
  }

  @Test
  fun constructorWithEmptyStoreStillPersistsInitialSnapshot() {
    val store = InMemorySessionQueueSnapshotStore()

    SessionQueue(
      sessionId = "fresh-session",
      agentId = "fresh-agent",
      runtime = failingRuntime(),
      snapshotStore = store,
      clock = IncrementingClock(start = 11_000L),
    )

    val persisted = store.load()
    assertNotNull(persisted)
    assertEquals("fresh-session", persisted!!.sessionId)
    assertEquals("fresh-agent", persisted.agentId)
    assertEquals(SessionLifecycleState.IDLE, persisted.lifecycleState)
    assertEquals(1L, persisted.nextEnqueueOrder)
    assertTrue(persisted.tasks.isEmpty())
  }

  @Test
  fun constructorWithMatchingSnapshotPersistsNormalizedRestartState() {
    val store = InMemorySessionQueueSnapshotStore(
      SessionQueueSnapshot(
        sessionId = "restore-session",
        agentId = "restore-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        nextEnqueueOrder = 4L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = task(id = "inflight-task", createdAt = 800L),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
        updatedAtEpochMs = 900L,
      ),
    )

    SessionQueue(
      sessionId = "restore-session",
      agentId = "restore-agent",
      runtime = failingRuntime(),
      snapshotStore = store,
      clock = IncrementingClock(start = 12_000L),
    )

    val persisted = store.load()
    assertNotNull(persisted)
    assertEquals(QueueTaskLifecycleState.FAILED, persisted!!.tasks.single().lifecycleState)
    assertEquals(
      ERROR_RESTART_REQUIRES_EXPLICIT_RETRY,
      persisted.tasks.single().lastErrorCode,
    )
  }

  private fun failingRuntime(): SessionTaskRuntime = SessionTaskRuntime { task, _ ->
    throw IllegalStateException("Runtime must not execute in this test: ${task.id}")
  }

  private fun task(id: String, createdAt: Long): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = "input-$id",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = createdAt,
  )
}
