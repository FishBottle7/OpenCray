package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionQueueFinalizeRaceTest {

  @Test
  fun reconcileFailureWhileExecutingKeepsFailedStateAndDrainsRemainingTasks() {
    val store = RecordingSnapshotStore()
    val runtimeStarted = CountDownLatch(1)
    val allowRuntimeReturn = CountDownLatch(1)
    val reconciled = AtomicBoolean(false)
    val drainFailure = AtomicReference<Throwable?>()
    val drainedResults = AtomicReference<List<ExecutionResult>>()

    val runtime = SessionTaskRuntime { task, _ ->
      if (task.id == "task-raced") {
        runtimeStarted.countDown()
        assertTrue(allowRuntimeReturn.await(10, TimeUnit.SECONDS))
      }
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        startedAtEpochMs = 150_000L,
        finishedAtEpochMs = 150_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-finalize-race-1",
      agentId = "agent-finalize-race-1",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 150_000L),
    )

    queue.enqueue(task(id = "task-raced", createdAt = 10_000L))
    queue.enqueue(task(id = "task-after-race", createdAt = 10_100L))

    val drainThread = Thread {
      try {
        drainedResults.set(queue.drain())
      } catch (failure: Throwable) {
        drainFailure.set(failure)
      }
    }
    drainThread.start()

    assertTrue(runtimeStarted.await(5, TimeUnit.SECONDS))
    reconciled.set(
      queue.reconcileFailure(
        taskId = "task-raced",
        errorCode = "HOST_WATCHDOG_FAILURE",
        errorMessage = "watchdog reconciled the stalled run",
      ),
    )
    allowRuntimeReturn.countDown()
    drainThread.join(10_000L)

    assertFalse(drainThread.isAlive)
    assertNull(drainFailure.get())
    assertTrue(reconciled.get())

    val byTaskId = queue.snapshot().tasks.associateBy { it.task.id }
    assertEquals(QueueTaskLifecycleState.FAILED, byTaskId.getValue("task-raced").lifecycleState)
    assertEquals(
      "HOST_WATCHDOG_FAILURE",
      byTaskId.getValue("task-raced").lastErrorCode,
    )
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      byTaskId.getValue("task-after-race").lifecycleState,
    )
    assertEquals(SessionLifecycleState.IDLE, queue.currentSessionState())

    val supersededResults = drainedResults.get().orEmpty().filter { result ->
      result.taskId == "task-raced" &&
        result.metadata[METADATA_SUPERSEDED_BY_LIFECYCLE_STATE] == "FAILED"
    }
    assertEquals(1, supersededResults.size)
  }

  @Test
  fun externalReconcileWithRequestRetrySchedulingSurvivesFinalize() {
    val store = RecordingSnapshotStore()
    val runtimeStarted = CountDownLatch(1)
    val allowRuntimeReturn = CountDownLatch(1)
    val drainFailure = AtomicReference<Throwable?>()
    val attempts = linkedMapOf<String, Int>()

    val runtime = SessionTaskRuntime { task, _ ->
      attempts[task.id] = (attempts[task.id] ?: 0) + 1
      if (task.id == "task-raced" && attempts[task.id] == 1) {
        runtimeStarted.countDown()
        assertTrue(allowRuntimeReturn.await(10, TimeUnit.SECONDS))
      }
      ExecutionResult(
        taskId = task.id,
        status =
          if (task.id == "task-raced" && attempts[task.id] == 1) {
            ExecutionStatus.FAILED
          } else {
            ExecutionStatus.SUCCESS
          },
        errorCode = if (task.id == "task-raced" && attempts[task.id] == 1) {
          "TRANSIENT_FAILURE"
        } else {
          null
        },
        startedAtEpochMs = 151_000L,
        finishedAtEpochMs = 151_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-finalize-race-2",
      agentId = "agent-finalize-race-2",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 151_000L),
      config = SessionQueueConfig(maxAttempts = 3),
    )

    queue.enqueue(task(id = "task-raced", createdAt = 11_000L))
    queue.enqueue(task(id = "task-after-race", createdAt = 11_100L))

    val drainThread = Thread {
      try {
        queue.drain()
      } catch (failure: Throwable) {
        drainFailure.set(failure)
      }
    }
    drainThread.start()

    assertTrue(runtimeStarted.await(5, TimeUnit.SECONDS))
    assertTrue(
      queue.reconcileFailure(
        taskId = "task-raced",
        errorCode = "HOST_WATCHDOG_FAILURE",
        errorMessage = "watchdog reconciled the stalled run",
      ),
    )
    assertTrue(queue.requestRetry("task-raced"))
    allowRuntimeReturn.countDown()
    drainThread.join(10_000L)

    assertFalse(drainThread.isAlive)
    assertNull(drainFailure.get())

    val racedTask = queue.snapshot().tasks.single { it.task.id == "task-raced" }
    assertEquals(QueueTaskLifecycleState.COMPLETED, racedTask.lifecycleState)
    assertEquals(2, racedTask.attempt)
    assertEquals(EXECUTION_KIND_RETRY, racedTask.executionKind)

    val afterTask = queue.snapshot().tasks.single { it.task.id == "task-after-race" }
    assertEquals(QueueTaskLifecycleState.COMPLETED, afterTask.lifecycleState)
    assertEquals(SessionLifecycleState.IDLE, queue.currentSessionState())
  }

  @Test
  fun externalReconciledFailureBeatsRuntimeRequestedRetry() {
    val store = RecordingSnapshotStore()
    val runtimeStarted = CountDownLatch(1)
    val allowRuntimeReturn = CountDownLatch(1)
    val drainFailure = AtomicReference<Throwable?>()
    val drainedResults = AtomicReference<List<ExecutionResult>>()

    val runtime = SessionTaskRuntime { task, hooks ->
      if (task.id == "task-raced") {
        hooks.requestRetry(
          RetryRequest(
            reasonCode = "TRANSIENT_RUNTIME_FAILURE",
            detail = "runtime asked for a retry before the external reconcile landed",
          ),
        )
        runtimeStarted.countDown()
        assertTrue(allowRuntimeReturn.await(10, TimeUnit.SECONDS))
      }
      ExecutionResult(
        taskId = task.id,
        status = if (task.id == "task-raced") ExecutionStatus.FAILED else ExecutionStatus.SUCCESS,
        errorCode = if (task.id == "task-raced") "TRANSIENT_RUNTIME_FAILURE" else null,
        startedAtEpochMs = 152_000L,
        finishedAtEpochMs = 152_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-finalize-race-3",
      agentId = "agent-finalize-race-3",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 152_000L),
      config = SessionQueueConfig(maxAttempts = 3),
    )

    queue.enqueue(task(id = "task-raced", createdAt = 12_000L))
    queue.enqueue(task(id = "task-after-race", createdAt = 12_100L))

    val drainThread = Thread {
      try {
        drainedResults.set(queue.drain())
      } catch (failure: Throwable) {
        drainFailure.set(failure)
      }
    }
    drainThread.start()

    assertTrue(runtimeStarted.await(5, TimeUnit.SECONDS))
    assertTrue(
      queue.reconcileFailure(
        taskId = "task-raced",
        errorCode = "HOST_WATCHDOG_FAILURE",
        errorMessage = "watchdog reconciled the stalled run",
      ),
    )
    allowRuntimeReturn.countDown()
    drainThread.join(10_000L)

    assertFalse(drainThread.isAlive)
    assertNull(drainFailure.get())

    val byTaskId = queue.snapshot().tasks.associateBy { it.task.id }
    val racedTask = byTaskId.getValue("task-raced")
    assertEquals(QueueTaskLifecycleState.FAILED, racedTask.lifecycleState)
    assertEquals("HOST_WATCHDOG_FAILURE", racedTask.lastErrorCode)
    assertEquals(1, racedTask.attempt)
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      byTaskId.getValue("task-after-race").lifecycleState,
    )
    assertEquals(SessionLifecycleState.IDLE, queue.currentSessionState())

    val racedStates = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.firstOrNull { it.task.id == "task-raced" }?.lifecycleState
      }
    assertFalse(racedStates.contains(QueueTaskLifecycleState.RETRY_PENDING))

    val supersededResults = drainedResults.get().orEmpty().filter { result ->
      result.taskId == "task-raced" &&
        result.metadata[METADATA_SUPERSEDED_BY_LIFECYCLE_STATE] == "FAILED"
    }
    assertEquals(1, supersededResults.size)
  }

  @Test
  fun crossThreadRetryHookIsObservedByDrain() {
    val store = RecordingSnapshotStore()
    val attempts = linkedMapOf<String, Int>()
    var runtimeNow = 153_000L

    val runtime = SessionTaskRuntime { task, hooks ->
      val attempt = (attempts[task.id] ?: 0) + 1
      attempts[task.id] = attempt
      if (attempt == 1) {
        thread(start = true, isDaemon = true) {
          Thread.sleep(50)
          hooks.requestRetry(
            RetryRequest(
              reasonCode = "CROSS_THREAD_RETRY",
              detail = "retry raised off the drain thread",
            ),
          )
        }
        Thread.sleep(400)
      }
      val started = runtimeNow++
      val finished = runtimeNow++
      ExecutionResult(
        taskId = task.id,
        status = if (attempt == 1) ExecutionStatus.FAILED else ExecutionStatus.SUCCESS,
        errorCode = if (attempt == 1) "TRANSIENT_FAILURE" else null,
        startedAtEpochMs = started,
        finishedAtEpochMs = finished,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-cross-thread-retry",
      agentId = "agent-cross-thread-retry",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 153_500L),
      config = SessionQueueConfig(maxAttempts = 3),
    )

    queue.enqueue(task(id = "task-cross-thread-retry", createdAt = 13_000L))

    val results = queue.drain()

    assertEquals(listOf(ExecutionStatus.FAILED, ExecutionStatus.SUCCESS), results.map { it.status })
    assertEquals(2, attempts["task-cross-thread-retry"])

    val completedSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedSnapshot.lifecycleState)
    assertEquals(2, completedSnapshot.attempt)
    assertEquals(EXECUTION_KIND_RETRY, completedSnapshot.executionKind)

    val lifecycleHistory = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.singleOrNull { it.task.id == "task-cross-thread-retry" }?.lifecycleState
      }
    assertTrue(lifecycleHistory.contains(QueueTaskLifecycleState.RETRY_PENDING))
  }

  @Test
  fun crossThreadSuspendHookIsObservedByDrain() {
    val store = RecordingSnapshotStore()
    val attempts = linkedMapOf<String, Int>()

    val runtime = SessionTaskRuntime { task, hooks ->
      val attempt = (attempts[task.id] ?: 0) + 1
      attempts[task.id] = attempt
      if (attempt == 1) {
        thread(start = true, isDaemon = true) {
          Thread.sleep(50)
          hooks.requestSuspend(
            SuspensionRequest(
              reasonCode = "APPROVAL_REQUIRED",
              detail = "suspension raised off the drain thread",
            ),
          )
        }
        Thread.sleep(400)
      }
      ExecutionResult(
        taskId = task.id,
        status = if (attempt == 1) ExecutionStatus.DENIED else ExecutionStatus.SUCCESS,
        errorCode = if (attempt == 1) "APPROVAL_REQUIRED" else null,
        startedAtEpochMs = 154_000L,
        finishedAtEpochMs = 154_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-cross-thread-suspend",
      agentId = "agent-cross-thread-suspend",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 154_500L),
    )

    queue.enqueue(task(id = "task-cross-thread-suspend", createdAt = 14_000L))

    val firstResults = queue.drain()

    assertEquals(listOf(ExecutionStatus.DENIED), firstResults.map { it.status })
    val suspendedSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.SUSPENDED, suspendedSnapshot.lifecycleState)
    assertEquals("APPROVAL_REQUIRED", suspendedSnapshot.lastErrorCode)
    assertEquals(1, suspendedSnapshot.executionOrdinal)

    assertTrue(queue.requestResumeTask("task-cross-thread-suspend"))

    val resumedResults = queue.drain()

    assertEquals(listOf(ExecutionStatus.SUCCESS), resumedResults.map { it.status })
    val completedSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedSnapshot.lifecycleState)
    assertEquals(EXECUTION_KIND_APPROVAL_RESUME, completedSnapshot.executionKind)
    assertEquals(2, completedSnapshot.executionOrdinal)
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

  private class IncrementingClock(
    start: Long,
  ) : QueueClock {
    private var now = start

    override fun nowEpochMs(): Long = now++
  }

  private class RecordingSnapshotStore : SessionQueueSnapshotStore {
    val history: MutableList<SessionQueueSnapshot> = mutableListOf()
    private var latest: SessionQueueSnapshot? = null

    override fun load(): SessionQueueSnapshot? = latest

    override fun save(snapshot: SessionQueueSnapshot) {
      latest = snapshot
      history += snapshot
    }

    override fun clear() {
      latest = null
      history.clear()
    }
  }
}
