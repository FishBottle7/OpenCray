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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionQueueOrderingTest {

  @Test
  fun executesTasksInStrictInsertionOrder() {
    val store = RecordingSnapshotStore()
    val queueClock = IncrementingClock(start = 10_000L)

    val executionOrder = mutableListOf<String>()
    var runtimeNow = 30_000L
    val runtime = SessionTaskRuntime { task, _ ->
      executionOrder += task.id
      val started = runtimeNow++
      val finished = runtimeNow++
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        startedAtEpochMs = started,
        finishedAtEpochMs = finished,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-ordering-1",
      agentId = "agent-ordering-1",
      runtime = runtime,
      snapshotStore = store,
      clock = queueClock,
    )

    queue.enqueue(task(id = "task-1", createdAt = 1_000L))
    queue.enqueue(task(id = "task-2", createdAt = 1_100L))
    queue.enqueue(task(id = "task-3", createdAt = 1_200L))

    val results = queue.drain()

    assertEquals(listOf("task-1", "task-2", "task-3"), executionOrder)
    assertEquals(listOf("task-1", "task-2", "task-3"), results.map { it.taskId })

    val byTaskId = queue.snapshot().tasks.associateBy { it.task.id }
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-1").lifecycleState)
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-2").lifecycleState)
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-3").lifecycleState)
    assertEquals(SessionLifecycleState.IDLE, queue.currentSessionState())
  }

  @Test
  fun cancellationAndRetryHooksProduceExplicitStateTransitions() {
    val store = RecordingSnapshotStore()
    val queueClock = IncrementingClock(start = 50_000L)

    val attempts = linkedMapOf<String, Int>()
    lateinit var queue: SessionQueue

    var runtimeNow = 90_000L
    val runtime = SessionTaskRuntime { task, hooks ->
      val attempt = (attempts[task.id] ?: 0) + 1
      attempts[task.id] = attempt

      val started = runtimeNow++
      val finished = runtimeNow++

      when (task.id) {
        "task-retry" -> {
          if (attempt == 1) {
            hooks.requestRetry(
              RetryRequest(
                reasonCode = "TRANSIENT_RUNTIME_FAILURE",
                detail = "first attempt failed; retry requested",
              ),
            )
            ExecutionResult(
              taskId = task.id,
              status = ExecutionStatus.FAILED,
              errorCode = "TRANSIENT_RUNTIME_FAILURE",
              startedAtEpochMs = started,
              finishedAtEpochMs = finished,
            )
          } else {
            ExecutionResult(
              taskId = task.id,
              status = ExecutionStatus.SUCCESS,
              startedAtEpochMs = started,
              finishedAtEpochMs = finished,
            )
          }
        }

        "task-cancel-running" -> {
          queue.requestCancel(task.id)
          val cancelled = hooks.isCancellationRequested()
          ExecutionResult(
            taskId = task.id,
            status = if (cancelled) ExecutionStatus.CANCELLED else ExecutionStatus.SUCCESS,
            errorCode = if (cancelled) "CANCELLED_BY_RUNTIME_HOOK" else null,
            startedAtEpochMs = started,
            finishedAtEpochMs = finished,
          )
        }

        else -> ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = started,
          finishedAtEpochMs = finished,
        )
      }
    }

    queue = SessionQueue(
      sessionId = "session-hooks-1",
      agentId = "agent-hooks-1",
      runtime = runtime,
      snapshotStore = store,
      clock = queueClock,
      config = SessionQueueConfig(maxAttempts = 3),
    )

    queue.enqueue(task(id = "task-retry", createdAt = 2_000L))
    queue.enqueue(task(id = "task-cancel-queued", createdAt = 2_100L))
    queue.enqueue(task(id = "task-cancel-running", createdAt = 2_200L))

    assertTrue(queue.requestCancel("task-cancel-queued"))

    queue.drain()

    val byTaskId = queue.snapshot().tasks.associateBy { it.task.id }
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-retry").lifecycleState)
    assertEquals(2, byTaskId.getValue("task-retry").attempt)
    assertEquals(QueueTaskLifecycleState.CANCELLED, byTaskId.getValue("task-cancel-queued").lifecycleState)
    assertEquals(QueueTaskLifecycleState.CANCELLED, byTaskId.getValue("task-cancel-running").lifecycleState)
    assertFalse(attempts.containsKey("task-cancel-queued"))

    val retryTransitions = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.firstOrNull { it.task.id == "task-retry" }?.lifecycleState
      }
    assertTrue(retryTransitions.contains(QueueTaskLifecycleState.RETRY_PENDING))

    val runningCancelTransitions = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.firstOrNull { it.task.id == "task-cancel-running" }?.lifecycleState
      }
    assertTrue(runningCancelTransitions.contains(QueueTaskLifecycleState.CANCEL_REQUESTED))
  }

  @Test
  fun suspensionHooksPauseTaskUntilExplicitResume() {
    val store = RecordingSnapshotStore()
    val queueClock = IncrementingClock(start = 70_000L)

    val attempts = linkedMapOf<String, Int>()
    var runtimeNow = 95_000L
    val runtime = SessionTaskRuntime { task, hooks ->
      val attempt = (attempts[task.id] ?: 0) + 1
      attempts[task.id] = attempt

      val started = runtimeNow++
      val finished = runtimeNow++

      if (attempt == 1) {
        hooks.requestSuspend(
          SuspensionRequest(
            reasonCode = "APPROVAL_REQUIRED",
            detail = "Approval is required before Write can run.",
          ),
        )
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval is required before Write can run.",
          startedAtEpochMs = started,
          finishedAtEpochMs = finished,
        )
      } else {
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = started,
          finishedAtEpochMs = finished,
        )
      }
    }

    val queue = SessionQueue(
      sessionId = "session-suspension-1",
      agentId = "agent-suspension-1",
      runtime = runtime,
      snapshotStore = store,
      clock = queueClock,
    )

    queue.enqueue(task(id = "task-approval", createdAt = 3_000L))

    val firstResults = queue.drain()
    val suspendedSnapshot = queue.snapshot().tasks.single()

    assertEquals(listOf("task-approval"), firstResults.map { it.taskId })
    assertEquals(ExecutionStatus.DENIED, firstResults.single().status)
    assertEquals(QueueTaskLifecycleState.SUSPENDED, suspendedSnapshot.lifecycleState)
    assertEquals(1, suspendedSnapshot.attempt)

    assertTrue(queue.requestResumeTask("task-approval"))

    val resumedResults = queue.drain()
    val completedSnapshot = queue.snapshot().tasks.single()

    assertEquals(ExecutionStatus.SUCCESS, resumedResults.single().status)
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedSnapshot.lifecycleState)
    assertEquals(2, completedSnapshot.attempt)

    val approvalTransitions = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.firstOrNull { it.task.id == "task-approval" }?.lifecycleState
      }

    assertTrue(approvalTransitions.contains(QueueTaskLifecycleState.SUSPENDED))
    assertTrue(approvalTransitions.contains(QueueTaskLifecycleState.COMPLETED))
  }

  @Test
  fun externalCancelWhileRuntimeExecutesStillReachesCancellationHook() {
    val store = RecordingSnapshotStore()
    val queueClock = IncrementingClock(start = 80_000L)
    val runtimeStarted = CountDownLatch(1)
    val observedCancellation = AtomicBoolean(false)

    val runtime = SessionTaskRuntime { task, hooks ->
      runtimeStarted.countDown()
      repeat(100) {
        if (hooks.isCancellationRequested()) {
          observedCancellation.set(true)
          return@repeat
        }
        Thread.sleep(5)
      }

      val cancelled = hooks.isCancellationRequested()
      observedCancellation.set(cancelled)
      ExecutionResult(
        taskId = task.id,
        status = if (cancelled) ExecutionStatus.CANCELLED else ExecutionStatus.SUCCESS,
        errorCode = if (cancelled) "CANCELLED_BY_RUNTIME_HOOK" else null,
        startedAtEpochMs = 100_000L,
        finishedAtEpochMs = 100_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-external-cancel-1",
      agentId = "agent-external-cancel-1",
      runtime = runtime,
      snapshotStore = store,
      clock = queueClock,
    )
    queue.enqueue(task(id = "task-cancel-external", createdAt = 4_000L))

    val drainThread = Thread { queue.drain() }
    drainThread.start()

    assertTrue(runtimeStarted.await(2, TimeUnit.SECONDS))
    assertTrue(queue.requestCancel("task-cancel-external"))
    drainThread.join(2_000L)

    assertTrue(observedCancellation.get())
    assertEquals(
      QueueTaskLifecycleState.CANCELLED,
      queue.snapshot().tasks.single().lifecycleState,
    )
  }

  @Test
  fun resumeDuringSuspensionSaveDoesNotEnterSnapshotStoreConcurrently() {
    val suspendedSaveEntered = CountDownLatch(1)
    val allowSuspendedSave = CountDownLatch(1)
    val store = BlockingConcurrentSnapshotStore(
      suspendedSaveEntered = suspendedSaveEntered,
      allowSuspendedSave = allowSuspendedSave,
    )
    val queueClock = IncrementingClock(start = 90_000L)

    val runtime = SessionTaskRuntime { task, hooks ->
      hooks.requestSuspend(
        SuspensionRequest(
          reasonCode = "APPROVAL_REQUIRED",
          detail = "Approval is required before Write can run.",
        ),
      )
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 110_000L,
        finishedAtEpochMs = 110_001L,
      )
    }

    val queue = SessionQueue(
      sessionId = "session-suspension-race-1",
      agentId = "agent-suspension-race-1",
      runtime = runtime,
      snapshotStore = store,
      clock = queueClock,
    )
    queue.enqueue(task(id = "task-approval-race", createdAt = 5_000L))

    val drainThread = Thread { queue.drain() }
    drainThread.start()

    assertTrue(suspendedSaveEntered.await(2, TimeUnit.SECONDS))
    val resumed = AtomicBoolean(false)
    val resumeThread = Thread {
      resumed.set(queue.requestResumeTask("task-approval-race"))
    }
    resumeThread.start()

    Thread.sleep(50)
    allowSuspendedSave.countDown()
    drainThread.join(2_000L)
    resumeThread.join(2_000L)

    assertTrue(resumed.get())
    assertFalse(store.concurrentAccessDetected.get())
    assertEquals(
      QueueTaskLifecycleState.QUEUED,
      queue.snapshot().tasks.single().lifecycleState,
    )
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

  private class BlockingConcurrentSnapshotStore(
    private val suspendedSaveEntered: CountDownLatch,
    private val allowSuspendedSave: CountDownLatch,
  ) : SessionQueueSnapshotStore {
    val concurrentAccessDetected: AtomicBoolean = AtomicBoolean(false)
    private val activeCalls = AtomicInteger(0)
    @Volatile private var latest: SessionQueueSnapshot? = null

    override fun load(): SessionQueueSnapshot? = withinCall { latest }

    override fun save(snapshot: SessionQueueSnapshot) {
      withinCall {
        if (snapshot.tasks.any { task -> task.lifecycleState == QueueTaskLifecycleState.SUSPENDED }) {
          suspendedSaveEntered.countDown()
          assertTrue(allowSuspendedSave.await(2, TimeUnit.SECONDS))
        }
        latest = snapshot
      }
    }

    override fun clear() {
      withinCall {
        latest = null
      }
    }

    private fun <T> withinCall(block: () -> T): T {
      if (activeCalls.incrementAndGet() > 1) {
        concurrentAccessDetected.set(true)
      }
      try {
        return block()
      } finally {
        activeCalls.decrementAndGet()
      }
    }
  }
}
