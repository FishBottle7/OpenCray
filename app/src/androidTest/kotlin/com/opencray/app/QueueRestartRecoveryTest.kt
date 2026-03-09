package com.opencray.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueClock
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueue
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueRestartRecoveryTest {

  @Test
  fun pendingTasksResumeExactlyOnceAfterRestart() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = File(context.cacheDir, "queue-restart-recovery")
    if (workspace.exists()) {
      workspace.deleteRecursively()
    }
    assertTrue(workspace.mkdirs())

    val executionOrder = mutableListOf<String>()
    var runtimeNow = 70_000L

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

    val queueBeforeRestart = SessionQueue(
      sessionId = "session-restart-queue-1",
      agentId = "agent-restart-queue-1",
      runtime = runtime,
      snapshotStore = SessionStoreQueueSnapshotStore(JsonFileSessionStore(workspace)),
      clock = IncrementingClock(start = 1_000L),
    )

    queueBeforeRestart.enqueue(task(id = "task-1", createdAt = 1_000L))
    queueBeforeRestart.enqueue(task(id = "task-2", createdAt = 1_100L))
    queueBeforeRestart.enqueue(task(id = "task-3", createdAt = 1_200L))

    queueBeforeRestart.drain(maxTasks = 1)
    assertEquals(listOf("task-1"), executionOrder)

    // Simulate process restart by rebuilding queue + persistence objects.
    val queueAfterRestart = SessionQueue(
      sessionId = "session-restart-queue-1",
      agentId = "agent-restart-queue-1",
      runtime = runtime,
      snapshotStore = SessionStoreQueueSnapshotStore(JsonFileSessionStore(workspace)),
      clock = IncrementingClock(start = 2_000L),
    )

    val resumedResults = queueAfterRestart.drain()
    assertEquals(2, resumedResults.size)
    assertEquals(listOf("task-1", "task-2", "task-3"), executionOrder)
    assertEquals(1, executionOrder.count { it == "task-2" })
    assertEquals(1, executionOrder.count { it == "task-3" })

    val byTaskId = queueAfterRestart.snapshot().tasks.associateBy { it.task.id }
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-1").lifecycleState)
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-2").lifecycleState)
    assertEquals(QueueTaskLifecycleState.COMPLETED, byTaskId.getValue("task-3").lifecycleState)

    val sessionRecord = JsonFileSessionStore(workspace).load()
    assertNotNull(sessionRecord)
    assertEquals("idle", sessionRecord!!.state[SessionStoreQueueSnapshotStore.StateKeys.QUEUE_STATE])
    assertTrue(
      sessionRecord.state.containsKey(SessionStoreQueueSnapshotStore.StateKeys.QUEUE_SNAPSHOT_JSON),
    )
  }

  private fun task(id: String, createdAt: Long): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = "queue-$id",
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
}
