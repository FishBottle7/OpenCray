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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    assertEquals(1, suspendedSnapshot.executionOrdinal)
    assertEquals(EXECUTION_KIND_INITIAL, suspendedSnapshot.executionKind)

    assertTrue(queue.requestResumeTask("task-approval"))

    val resumedResults = queue.drain()
    val completedSnapshot = queue.snapshot().tasks.single()

    assertEquals(ExecutionStatus.SUCCESS, resumedResults.single().status)
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedSnapshot.lifecycleState)
    assertEquals(1, completedSnapshot.attempt)
    assertEquals(2, completedSnapshot.executionOrdinal)
    assertEquals(EXECUTION_KIND_APPROVAL_RESUME, completedSnapshot.executionKind)

    val approvalTransitions = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.firstOrNull { it.task.id == "task-approval" }?.lifecycleState
      }

    assertTrue(approvalTransitions.contains(QueueTaskLifecycleState.SUSPENDED))
    assertTrue(approvalTransitions.contains(QueueTaskLifecycleState.COMPLETED))
  }

  @Test
  fun checkpointResumeAttemptsPersistAndExplicitRetryResetsCount() {
    val store = RecordingSnapshotStore()
    var executionCount = 0
    val runtime = SessionTaskRuntime { task, hooks ->
      executionCount += 1
      when (executionCount) {
        1 -> {
          hooks.requestSuspend(
            SuspensionRequest(
              reasonCode = "CHECKPOINT_READY",
              detail = "Resume from a durable checkpoint.",
            ),
          )
          ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.DENIED,
            errorCode = "CHECKPOINT_READY",
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 1_001L,
          )
        }

        2 -> ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.FAILED,
          errorCode = "RUNTIME_INTERRUPTED",
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_001L,
        )

        else -> ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 3_000L,
          finishedAtEpochMs = 3_001L,
        )
      }
    }
    val queue = SessionQueue(
      sessionId = "session-checkpoint-resume-count",
      agentId = "agent-checkpoint-resume-count",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 100_000L),
    )
    queue.enqueue(task(id = "task-checkpoint-resume-count", createdAt = 900L))
    queue.drain()

    assertTrue(
      queue.requestResumeTask(
        taskId = "task-checkpoint-resume-count",
        executionKind = EXECUTION_KIND_CHECKPOINT_RESUME,
      ),
    )
    queue.drain()

    val failedCheckpointResume = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.FAILED, failedCheckpointResume.lifecycleState)
    assertEquals(
      "1",
      failedCheckpointResume.task.metadata[METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT],
    )

    assertTrue(queue.requestRetry("task-checkpoint-resume-count"))
    assertNull(
      queue.snapshot().tasks.single().task.metadata[METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT],
    )
    queue.drain()

    val completedRetry = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedRetry.lifecycleState)
    assertEquals(EXECUTION_KIND_RETRY, completedRetry.executionKind)
    assertNull(completedRetry.task.metadata[METADATA_CHECKPOINT_RESUME_ATTEMPT_COUNT])
  }

  @Test
  fun manualRetryClearsPreviousErrorState() {
    var executionCount = 0
    val queue = SessionQueue(
      sessionId = "session-manual-retry-clear-error",
      agentId = "agent-manual-retry-clear-error",
      runtime = SessionTaskRuntime { task, _ ->
        executionCount += 1
        ExecutionResult(
          taskId = task.id,
          status = if (executionCount == 1) ExecutionStatus.FAILED else ExecutionStatus.SUCCESS,
          errorCode = if (executionCount == 1) "TRANSIENT_FAILURE" else null,
          errorMessage = if (executionCount == 1) "Try again." else null,
          startedAtEpochMs = 1_000L + executionCount,
          finishedAtEpochMs = 2_000L + executionCount,
        )
      },
      snapshotStore = RecordingSnapshotStore(),
      clock = IncrementingClock(start = 75_000L),
    )
    queue.enqueue(task(id = "task-manual-retry-clear-error", createdAt = 3_500L))
    queue.drain()

    assertTrue(
      queue.requestRetry(
        taskId = "task-manual-retry-clear-error",
        taskMetadataUpdates = mapOf("_test.retryAcknowledgement" to "process-1"),
      ),
    )

    val retriedTask = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.QUEUED, retriedTask.lifecycleState)
    assertNull(retriedTask.lastErrorCode)
    assertNull(retriedTask.lastErrorMessage)
    assertEquals("process-1", retriedTask.task.metadata["_test.retryAcknowledgement"])
  }

  @Test
  fun interruptedRuntimeExecutionProducesCancelledResultAndPreservesInterruptFlag() {
    Thread.interrupted()
    val queue = SessionQueue(
      sessionId = "session-runtime-interrupted",
      agentId = "agent-runtime-interrupted",
      runtime = SessionTaskRuntime { _, _ -> throw InterruptedException("cancel requested") },
      snapshotStore = RecordingSnapshotStore(),
      clock = IncrementingClock(start = 77_000L),
    )
    queue.enqueue(task(id = "task-runtime-interrupted", createdAt = 3_700L))

    try {
      val result = queue.drain().single()

      assertEquals(ExecutionStatus.CANCELLED, result.status)
      assertEquals("RUNTIME_INTERRUPTED", result.errorCode)
      assertEquals(QueueTaskLifecycleState.CANCELLED, queue.snapshot().tasks.single().lifecycleState)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun interruptedRuntimeExecutionStopsDrainBeforeStartingNextTask() {
    Thread.interrupted()
    val executedTaskIds = mutableListOf<String>()
    val queue = SessionQueue(
      sessionId = "session-runtime-interrupted-drain",
      agentId = "agent-runtime-interrupted-drain",
      runtime = SessionTaskRuntime { task, _ ->
        executedTaskIds += task.id
        if (task.id == "task-interrupted-first") {
          throw InterruptedException("cancel requested")
        }
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        )
      },
      snapshotStore = RecordingSnapshotStore(),
      clock = IncrementingClock(start = 78_000L),
    )
    queue.enqueue(task(id = "task-interrupted-first", createdAt = 3_800L))
    queue.enqueue(task(id = "task-waits-for-clean-worker", createdAt = 3_801L))

    try {
      val interruptedDrain = queue.drain()

      assertEquals(listOf("task-interrupted-first"), executedTaskIds)
      assertEquals(ExecutionStatus.CANCELLED, interruptedDrain.single().status)
      assertEquals(
        QueueTaskLifecycleState.QUEUED,
        queue.snapshot().tasks.last().lifecycleState,
      )
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }

    val resumedDrain = queue.drain()

    assertEquals(
      listOf("task-interrupted-first", "task-waits-for-clean-worker"),
      executedTaskIds,
    )
    assertEquals(ExecutionStatus.SUCCESS, resumedDrain.single().status)
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

  @Test
  fun restoreOfInFlightTaskRequiresExplicitRetryBeforeExecutionResumes() {
    val executionOrder = mutableListOf<String>()
    val queue = SessionQueue(
      sessionId = "session-restore-1",
      agentId = "agent-restore-1",
      runtime = SessionTaskRuntime { task, _ ->
        executionOrder += task.id
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 120_000L,
          finishedAtEpochMs = 120_001L,
        )
      },
      snapshotStore = InMemorySessionQueueSnapshotStore(
        SessionQueueSnapshot(
          sessionId = "session-restore-1",
          agentId = "agent-restore-1",
          lifecycleState = SessionLifecycleState.RUNNING,
          nextEnqueueOrder = 2,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1,
              task = task(id = "task-restore", createdAt = 6_000L),
              lifecycleState = QueueTaskLifecycleState.RUNNING,
              attempt = 3,
            ),
          ),
          updatedAtEpochMs = 6_100L,
        ),
      ),
      clock = IncrementingClock(start = 100_000L),
      config = SessionQueueConfig(maxAttempts = 3),
    )

    val restoredTask = queue.snapshot().tasks.single()

    assertEquals(QueueTaskLifecycleState.FAILED, restoredTask.lifecycleState)
    assertEquals(ERROR_RESTART_REQUIRES_EXPLICIT_RETRY, restoredTask.lastErrorCode)
    assertTrue(restoredTask.lastErrorMessage?.contains("Retry explicitly") == true)
    assertEquals("100000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals(
      RECOVERY_REASON_HOST_RESTART_INFLIGHT_TASK_INTERRUPTED,
      restoredTask.task.metadata[METADATA_RECOVERY_REASON],
    )
    assertEquals("running", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertTrue(queue.drain().isEmpty())
    assertTrue(queue.requestRetry("task-restore"))

    val retriedResults = queue.drain()

    assertEquals(listOf("task-restore"), executionOrder)
    assertEquals(listOf(ExecutionStatus.SUCCESS), retriedResults.map(ExecutionResult::status))
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      queue.snapshot().tasks.single().lifecycleState,
    )
  }

  @Test
  fun requestResumeTaskClearsApprovalErrorStateWhenRequeueing() {
    val store = RecordingSnapshotStore()
    val queue = SessionQueue(
      sessionId = "session-resume-clear-error",
      agentId = "agent-resume-clear-error",
      runtime = SessionTaskRuntime { task, hooks ->
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
          startedAtEpochMs = 130_000L,
          finishedAtEpochMs = 130_001L,
        )
      },
      snapshotStore = store,
      clock = IncrementingClock(start = 130_500L),
    )
    queue.enqueue(task(id = "task-resume-clear-error", createdAt = 7_000L))

    queue.drain()
    assertTrue(queue.requestResumeTask("task-resume-clear-error"))

    val resumedSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.QUEUED, resumedSnapshot.lifecycleState)
    assertEquals(null, resumedSnapshot.lastErrorCode)
    assertEquals(null, resumedSnapshot.lastErrorMessage)
  }

  @Test
  fun restorePreservesExistingRecoveryMetadataForNonInterruptedTask() {
    val queue = SessionQueue(
      sessionId = "session-restore-preserve",
      agentId = "agent-restore-preserve",
      runtime = SessionTaskRuntime { task, _ ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 120_000L,
          finishedAtEpochMs = 120_001L,
        )
      },
      snapshotStore = InMemorySessionQueueSnapshotStore(
        SessionQueueSnapshot(
          sessionId = "session-restore-preserve",
          agentId = "agent-restore-preserve",
          lifecycleState = SessionLifecycleState.RUNNING,
          nextEnqueueOrder = 2,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1,
              task = task(id = "task-restore-preserve", createdAt = 6_000L).copy(
                metadata = mapOf(
                  METADATA_QUEUE_RESTORE_EPOCH_MS to "5000",
                  METADATA_PREVIOUS_LIFECYCLE_STATE to "running",
                  METADATA_RECOVERY_REASON to "live_managed_process_detected",
                ),
              ),
              lifecycleState = QueueTaskLifecycleState.SUSPENDED,
              attempt = 1,
            ),
          ),
          updatedAtEpochMs = 6_100L,
        ),
      ),
      clock = IncrementingClock(start = 100_000L),
      config = SessionQueueConfig(maxAttempts = 3),
    )

    val restoredTask = queue.snapshot().tasks.single()

    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredTask.lifecycleState)
    assertEquals("5000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals("running", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertEquals(
      "live_managed_process_detected",
      restoredTask.task.metadata[METADATA_RECOVERY_REASON],
    )
  }

  @Test
  fun retryHookWhileCancellationRequestedEndsCancelledAndNeverRequeues() {
    val store = RecordingSnapshotStore()
    val attempts = linkedMapOf<String, Int>()
    lateinit var queue: SessionQueue

    val runtime = SessionTaskRuntime { task, hooks ->
      attempts[task.id] = (attempts[task.id] ?: 0) + 1
      queue.requestCancel(task.id)
      hooks.requestRetry(
        RetryRequest(
          reasonCode = "TRANSIENT_RUNTIME_FAILURE",
          detail = "retry requested while cancellation was pending",
        ),
      )
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.FAILED,
        errorCode = "TRANSIENT_RUNTIME_FAILURE",
        errorMessage = "attempt failed before observing cancellation",
        startedAtEpochMs = 140_000L,
        finishedAtEpochMs = 140_001L,
      )
    }

    queue = SessionQueue(
      sessionId = "session-cancel-beats-retry-1",
      agentId = "agent-cancel-beats-retry-1",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 140_500L),
      config = SessionQueueConfig(maxAttempts = 3),
    )
    queue.enqueue(task(id = "task-cancel-beats-retry", createdAt = 8_000L))

    val results = queue.drain()

    assertEquals(listOf("task-cancel-beats-retry"), results.map { it.taskId })
    assertEquals(1, attempts["task-cancel-beats-retry"])

    val cancelledSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.CANCELLED, cancelledSnapshot.lifecycleState)
    assertEquals(1, cancelledSnapshot.attempt)

    val lifecycleHistory = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.singleOrNull { it.task.id == "task-cancel-beats-retry" }?.lifecycleState
      }
    assertTrue(lifecycleHistory.contains(QueueTaskLifecycleState.CANCEL_REQUESTED))
    assertFalse(lifecycleHistory.contains(QueueTaskLifecycleState.RETRY_PENDING))

    assertTrue(queue.drain().isEmpty())
    assertEquals(1, attempts["task-cancel-beats-retry"])
  }

  @Test
  fun runtimeRetryWithoutCancellationStillRequeuesAndCompletes() {
    val store = RecordingSnapshotStore()
    val attempts = linkedMapOf<String, Int>()

    var runtimeNow = 141_000L
    val runtime = SessionTaskRuntime { task, hooks ->
      val attempt = (attempts[task.id] ?: 0) + 1
      attempts[task.id] = attempt
      val started = runtimeNow++
      val finished = runtimeNow++

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

    val queue = SessionQueue(
      sessionId = "session-runtime-retry-regression",
      agentId = "agent-runtime-retry-regression",
      runtime = runtime,
      snapshotStore = store,
      clock = IncrementingClock(start = 141_500L),
      config = SessionQueueConfig(maxAttempts = 3),
    )
    queue.enqueue(task(id = "task-runtime-retry", createdAt = 8_100L))

    val results = queue.drain()

    assertEquals(listOf(ExecutionStatus.FAILED, ExecutionStatus.SUCCESS), results.map { it.status })
    assertEquals(2, attempts["task-runtime-retry"])

    val completedSnapshot = queue.snapshot().tasks.single()
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedSnapshot.lifecycleState)
    assertEquals(2, completedSnapshot.attempt)
    assertEquals(EXECUTION_KIND_RETRY, completedSnapshot.executionKind)

    val lifecycleHistory = store.history
      .mapNotNull { snapshot ->
        snapshot.tasks.singleOrNull { it.task.id == "task-runtime-retry" }?.lifecycleState
      }
    assertTrue(lifecycleHistory.contains(QueueTaskLifecycleState.RETRY_PENDING))
    assertTrue(lifecycleHistory.lastIndexOf(QueueTaskLifecycleState.COMPLETED) >
      lifecycleHistory.lastIndexOf(QueueTaskLifecycleState.RETRY_PENDING))
  }

  @Test
  fun virtualMachineErrorPropagatesFromDrainInsteadOfBecomingFailedResult() {
    val store = RecordingSnapshotStore()
    val queue = SessionQueue(
      sessionId = "session-fatal-error-propagation",
      agentId = "agent-fatal-error-propagation",
      runtime = SessionTaskRuntime { currentTask, _ ->
        if (currentTask.id == "task-fatal-error") {
          throw OutOfMemoryError("simulated heap exhaustion")
        }
        ExecutionResult(
          taskId = currentTask.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 142_001L,
          finishedAtEpochMs = 142_002L,
        )
      },
      snapshotStore = store,
      clock = IncrementingClock(start = 142_000L),
    )
    queue.enqueue(task(id = "task-fatal-error", createdAt = 9_000L))
    queue.enqueue(task(id = "task-after-fatal-error", createdAt = 9_001L))

    try {
      queue.drain()
      fail("Expected OutOfMemoryError to propagate out of drain.")
    } catch (expected: OutOfMemoryError) {
      assertEquals("simulated heap exhaustion", expected.message)
    }

    assertEquals(
      QueueTaskLifecycleState.RUNNING,
      queue.snapshot().tasks.first().lifecycleState,
    )

    val followUpResults = queue.drain()

    assertEquals(listOf("task-after-fatal-error"), followUpResults.map { it.taskId })
  }

  @Test
  fun runtimeExceptionStillProducesFailedExecutionResult() {
    val store = RecordingSnapshotStore()
    val queue = SessionQueue(
      sessionId = "session-runtime-exception-failed",
      agentId = "agent-runtime-exception-failed",
      runtime = SessionTaskRuntime { _, _ ->
        throw IllegalStateException("runtime blew up")
      },
      snapshotStore = store,
      clock = IncrementingClock(start = 143_000L),
    )
    queue.enqueue(task(id = "task-runtime-exception", createdAt = 9_100L))

    val results = queue.drain()

    val result = results.single()
    assertEquals("task-runtime-exception", result.taskId)
    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("RUNTIME_EXCEPTION", result.errorCode)
    assertEquals("runtime blew up", result.errorMessage)
    assertEquals(
      QueueTaskLifecycleState.FAILED,
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
