package com.opencray.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueClock
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueue
import com.opencray.core.orchestrator.SessionQueueSnapshot
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
class RestartPersistenceTest {

  @Test
  fun restoresPendingQueueEntriesAfterRestartWithoutReplayingCompletedSideEffects() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val workspace = File(context.cacheDir, "restart-persistence-e2e")
    if (workspace.exists()) {
      workspace.deleteRecursively()
    }
    assertTrue(workspace.mkdirs())

    val sideEffectsLog = File(workspace, "queue-side-effects.log")
    var runtimeNow = 80_000L

    val runtime = SessionTaskRuntime { task, _ ->
      sideEffectsLog.appendText("${task.id}\n", Charsets.UTF_8)
      val started = runtimeNow++
      val finished = runtimeNow++
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        startedAtEpochMs = started,
        finishedAtEpochMs = finished,
      )
    }

    val queueBeforeRestart = newQueue(
      workspace = workspace,
      runtime = runtime,
      clockStart = 1_000L,
    )

    queueBeforeRestart.enqueue(task(id = "completed-before-restart", createdAt = 1_000L))
    queueBeforeRestart.enqueue(task(id = "pending-after-restart", createdAt = 1_100L))
    queueBeforeRestart.enqueue(task(id = "pending-after-restart-2", createdAt = 1_200L))

    val preRestartResults = queueBeforeRestart.drain(maxTasks = 1)
    assertEquals(listOf("completed-before-restart"), preRestartResults.map { it.taskId })
    assertSideEffectCount(sideEffectsLog, taskId = "completed-before-restart", expectedCount = 1)
    assertSideEffectCount(sideEffectsLog, taskId = "pending-after-restart", expectedCount = 0)
    assertSideEffectCount(sideEffectsLog, taskId = "pending-after-restart-2", expectedCount = 0)

    val persistedRecordBeforeRestart = JsonFileSessionStore(workspace).load()
    assertNotNull(persistedRecordBeforeRestart)
    assertEquals(
      "idle",
      persistedRecordBeforeRestart!!.state[SessionStoreQueueSnapshotStore.StateKeys.QUEUE_STATE],
    )
    assertEquals(
      "4",
      persistedRecordBeforeRestart.state[SessionStoreQueueSnapshotStore.StateKeys.QUEUE_NEXT_ENQUEUE_ORDER],
    )
    assertTrue(
      persistedRecordBeforeRestart.state.containsKey(
        SessionStoreQueueSnapshotStore.StateKeys.QUEUE_SNAPSHOT_JSON,
      ),
    )

    val persistedSnapshotBeforeRestart = SessionStoreQueueSnapshotStore(
      JsonFileSessionStore(workspace),
    ).load()
    assertNotNull(persistedSnapshotBeforeRestart)
    assertTaskState(
      snapshot = persistedSnapshotBeforeRestart!!,
      taskId = "completed-before-restart",
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    assertTaskState(
      snapshot = persistedSnapshotBeforeRestart,
      taskId = "pending-after-restart",
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )
    assertTaskState(
      snapshot = persistedSnapshotBeforeRestart,
      taskId = "pending-after-restart-2",
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )

    val queueAfterRestart = newQueue(
      workspace = workspace,
      runtime = runtime,
      clockStart = 2_000L,
    )

    val restoredSnapshot = queueAfterRestart.snapshot()
    assertTaskState(
      snapshot = restoredSnapshot,
      taskId = "completed-before-restart",
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    assertTaskState(
      snapshot = restoredSnapshot,
      taskId = "pending-after-restart",
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )
    assertTaskState(
      snapshot = restoredSnapshot,
      taskId = "pending-after-restart-2",
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )

    val resumedResults = queueAfterRestart.drain()
    assertEquals(
      listOf("pending-after-restart", "pending-after-restart-2"),
      resumedResults.map { it.taskId },
    )
    assertSideEffectCount(sideEffectsLog, taskId = "completed-before-restart", expectedCount = 1)
    assertSideEffectCount(sideEffectsLog, taskId = "pending-after-restart", expectedCount = 1)
    assertSideEffectCount(sideEffectsLog, taskId = "pending-after-restart-2", expectedCount = 1)

    val finalSnapshot = queueAfterRestart.snapshot()
    assertTaskState(
      snapshot = finalSnapshot,
      taskId = "completed-before-restart",
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    assertTaskState(
      snapshot = finalSnapshot,
      taskId = "pending-after-restart",
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    assertTaskState(
      snapshot = finalSnapshot,
      taskId = "pending-after-restart-2",
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )

    val persistedRecordAfterDrain = JsonFileSessionStore(workspace).load()
    assertNotNull(persistedRecordAfterDrain)
    assertEquals(
      "idle",
      persistedRecordAfterDrain!!.state[SessionStoreQueueSnapshotStore.StateKeys.QUEUE_STATE],
    )
  }

  private fun newQueue(
    workspace: File,
    runtime: SessionTaskRuntime,
    clockStart: Long,
  ): SessionQueue = SessionQueue(
    sessionId = "session-restart-persistence-1",
    agentId = "agent-restart-persistence-1",
    runtime = runtime,
    snapshotStore = SessionStoreQueueSnapshotStore(JsonFileSessionStore(workspace)),
    clock = IncrementingClock(start = clockStart),
  )

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

  private fun assertTaskState(
    snapshot: SessionQueueSnapshot,
    taskId: String,
    lifecycleState: QueueTaskLifecycleState,
    taskState: AgentTaskState,
  ) {
    val entry = snapshot.tasks.associateBy { it.task.id }.getValue(taskId)
    assertEquals(lifecycleState, entry.lifecycleState)
    assertEquals(taskState, entry.task.state)
  }

  private fun assertSideEffectCount(
    logFile: File,
    taskId: String,
    expectedCount: Int,
  ) {
    val counts = if (!logFile.exists()) {
      emptyMap()
    } else {
      logFile.readLines(Charsets.UTF_8)
        .filter(String::isNotBlank)
        .groupingBy { it }
        .eachCount()
    }

    assertEquals(expectedCount, counts[taskId] ?: 0)
  }

  private class IncrementingClock(
    start: Long,
  ) : QueueClock {
    private var now = start

    override fun nowEpochMs(): Long = now++
  }
}
