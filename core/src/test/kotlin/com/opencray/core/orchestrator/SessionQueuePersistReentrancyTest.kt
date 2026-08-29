package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionQueuePersistReentrancyTest {

  @Test(timeout = 5000L)
  fun saveCallbackToEnqueueFailsFastInsteadOfDeadlocking() {
    val store = ReentrantSnapshotStore(
      reentrantCall = { queue ->
        queue.enqueue(task(id = "task-reentrant-enqueue", createdAt = 900L))
      },
    )
    val queue = newQueue("session-persist-reentrancy-enqueue", store)
    store.armReentrancy(queue)

    try {
      queue.enqueue(task(id = "task-trigger-enqueue", createdAt = 1_000L))
      fail("Expected IllegalStateException from re-entrant save callback.")
    } catch (expected: IllegalStateException) {
      assertReentrancyFailure(expected)
    }
  }

  @Test(timeout = 5000L)
  fun saveCallbackToSnapshotFailsFastInsteadOfReentering() {
    val store = ReentrantSnapshotStore(
      reentrantCall = { queue -> queue.snapshot() },
    )
    val queue = newQueue("session-persist-reentrancy-snapshot", store)
    store.armReentrancy(queue)

    try {
      queue.enqueue(task(id = "task-trigger-snapshot", createdAt = 1_100L))
      fail("Expected IllegalStateException from re-entrant save callback.")
    } catch (expected: IllegalStateException) {
      assertReentrancyFailure(expected)
    }
  }

  @Test(timeout = 5000L)
  fun saveCallbackToRequestCancelFailsFastInsteadOfDeadlocking() {
    val store = ReentrantSnapshotStore(
      reentrantCall = { queue -> queue.requestCancel("task-trigger-cancel") },
    )
    val queue = newQueue("session-persist-reentrancy-cancel", store)
    store.armReentrancy(queue)

    try {
      queue.enqueue(task(id = "task-trigger-cancel", createdAt = 1_200L))
      fail("Expected IllegalStateException from re-entrant save callback.")
    } catch (expected: IllegalStateException) {
      assertReentrancyFailure(expected)
    }
  }

  @Test(timeout = 5000L)
  fun queueStaysUsableAfterRejectedSaveReentrancy() {
    val store = ReentrantSnapshotStore(
      reentrantCall = { queue -> queue.snapshot() },
    )
    val queue = newQueue("session-persist-reentrancy-recovery", store)
    store.armReentrancy(queue)

    try {
      queue.enqueue(task(id = "task-reentrancy-recovered", createdAt = 1_300L))
      fail("Expected IllegalStateException from re-entrant save callback.")
    } catch (expected: IllegalStateException) {
      assertReentrancyFailure(expected)
    }

    assertEquals(1, queue.snapshot().tasks.size)

    try {
      queue.enqueue(task(id = "task-after-sticky-error", createdAt = 1_400L))
      fail("Expected sticky persist error to surface once.")
    } catch (expected: IllegalStateException) {
      assertReentrancyFailure(expected)
    }

    queue.enqueue(task(id = "task-clean-after-error", createdAt = 1_500L))

    val results = queue.drain()

    assertEquals(
      listOf(
        "task-reentrancy-recovered",
        "task-after-sticky-error",
        "task-clean-after-error",
      ),
      results.map { it.taskId },
    )
    assertEquals(listOf(ExecutionStatus.SUCCESS, ExecutionStatus.SUCCESS, ExecutionStatus.SUCCESS), results.map { it.status })
  }

  private fun assertReentrancyFailure(failure: IllegalStateException) {
    val message = failure.message ?: ""
    assertTrue(message.contains("persist executor thread"))
    assertTrue(message.contains("must not call back into SessionQueue"))
  }

  private fun newQueue(
    sessionId: String,
    store: ReentrantSnapshotStore,
  ): SessionQueue = SessionQueue(
    sessionId = sessionId,
    agentId = "agent-$sessionId",
    runtime = SessionTaskRuntime { task, _ ->
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        startedAtEpochMs = 5_000L,
        finishedAtEpochMs = 5_001L,
      )
    },
    snapshotStore = store,
    clock = IncrementingClock(start = 160_000L),
  )

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

  private class IncrementingClock(
    start: Long,
  ) : QueueClock {
    private var now = start

    override fun nowEpochMs(): Long = now++
  }

  private class ReentrantSnapshotStore(
    private val reentrantCall: (SessionQueue) -> Unit,
  ) : SessionQueueSnapshotStore {
    private val delegate = InMemorySessionQueueSnapshotStore()
    private val queueReference = AtomicReference<SessionQueue?>()
    private val reentrancyArmed = AtomicBoolean(false)

    fun armReentrancy(queue: SessionQueue) {
      queueReference.set(queue)
      reentrancyArmed.set(true)
    }

    override fun load(): SessionQueueSnapshot? = delegate.load()

    override fun save(snapshot: SessionQueueSnapshot) {
      if (reentrancyArmed.compareAndSet(true, false)) {
        reentrantCall(requireNotNull(queueReference.get()))
      }
      delegate.save(snapshot)
    }

    override fun clear() {
      delegate.clear()
    }
  }
}
