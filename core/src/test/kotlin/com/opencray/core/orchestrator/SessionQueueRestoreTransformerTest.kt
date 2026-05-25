package com.opencray.core.orchestrator

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionQueueRestoreTransformerTest {
  @Test
  fun restoreTransformerRewritesSnapshotBeforeDefaultRestartNormalization() {
    val snapshotStore = InMemorySessionQueueSnapshotStore(
      SessionQueueSnapshot(
        sessionId = "session",
        agentId = "agent",
        updatedAtEpochMs = 200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = "task-1",
              type = AgentTaskType.PROMPT,
              input = "resume",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 100L,
              updatedAtEpochMs = 150L,
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
          ),
        ),
      ),
    )

    val queue = SessionQueue(
      sessionId = "session",
      agentId = "agent",
      runtime = SessionTaskRuntime { task, _ ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 0L,
          finishedAtEpochMs = 0L,
        )
      },
      snapshotStore = snapshotStore,
      restoreTransformer = SessionQueueRestoreTransformer { snapshot, restoreEpochMs ->
        snapshot?.copy(
          tasks = snapshot.tasks.map { entry ->
            entry.copy(
              lifecycleState = QueueTaskLifecycleState.SUSPENDED,
              task = entry.task.copy(
                state = AgentTaskState.SUSPENDED,
                updatedAtEpochMs = restoreEpochMs,
              ),
            )
          },
        )
      },
    )

    val restored = queue.snapshot().tasks.single()

    assertEquals(QueueTaskLifecycleState.SUSPENDED, restored.lifecycleState)
    assertEquals(AgentTaskState.SUSPENDED, restored.task.state)
  }
}
