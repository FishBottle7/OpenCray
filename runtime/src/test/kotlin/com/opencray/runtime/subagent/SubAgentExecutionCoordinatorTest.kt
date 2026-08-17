package com.opencray.runtime.subagent

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SubAgentExecutionCoordinatorTest {
  @Test
  fun inMemoryCoordinatorRejectsStaleExecutionOwnedWrites() {
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val initialHandle = sampleHandle(updatedAtEpochMs = 1_000L)
    val executionKey = SubAgentExecutionKey.from(initialHandle)
    val staleExecution = activeExecution()
    val currentExecution = activeExecution()

    try {
      coordinator.upsertHandle(initialHandle)
      coordinator.registerActiveExecution(executionKey, staleExecution)

      coordinator.takeActiveExecution(executionKey, expectedExecution = staleExecution)
      val restartedHandle = initialHandle.copy(
        snapshot = SubAgentExecutionSnapshot.backgroundRunning(
          headline = "Restarted delegated child is running.",
        ),
        updatedAtEpochMs = 1_100L,
      )
      coordinator.upsertHandle(restartedHandle)
      coordinator.registerActiveExecution(executionKey, currentExecution)

      val staleCheckpointHandle = initialHandle.copy(
        childPromptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 2,
        ),
        childPromptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
        childPromptCheckpointAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
      )

      assertNull(
        coordinator.upsertHandleIfOwnedByExecution(
          handle = staleCheckpointHandle,
          expectedExecution = staleExecution,
        ),
      )
      assertEquals(restartedHandle, coordinator.currentHandle(executionKey))

      val staleCompletedHandle = initialHandle.copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Stale delegated child completion.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        updatedAtEpochMs = 1_300L,
      )

      assertNull(
        coordinator.finishExecution(
          handle = staleCompletedHandle,
          expectedExecution = staleExecution,
        ),
      )
      assertSame(currentExecution, coordinator.activeExecution(executionKey))
      assertEquals(restartedHandle, coordinator.currentHandle(executionKey))

      val currentCompletedHandle = restartedHandle.copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Fresh delegated child completion.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        updatedAtEpochMs = 1_400L,
      )

      assertEquals(
        currentCompletedHandle,
        coordinator.finishExecution(
          handle = currentCompletedHandle,
          expectedExecution = currentExecution,
        ),
      )
      assertNull(coordinator.activeExecution(executionKey))
      assertEquals(currentCompletedHandle, coordinator.currentHandle(executionKey))
    } finally {
      staleExecution.executor.shutdownNow()
      currentExecution.executor.shutdownNow()
    }
  }

  private fun sampleHandle(
    updatedAtEpochMs: Long,
  ): SubAgentHandleState = SubAgentHandleState(
    agentId = "child-1",
    childRunId = "child-run-1",
    childTaskId = "child-task-1",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-1",
    parentTaskId = "parent-task-1",
    parentTurn = 0,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(
      headline = "Queued delegated child run 'Inspect README'.",
    ),
    createdAtEpochMs = 900L,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private fun activeExecution(): SubAgentActiveExecution {
    val executor = Executors.newSingleThreadExecutor()
    return SubAgentActiveExecution(
      executor = executor,
      future = FutureTask<Unit> { },
      cancelRequested = AtomicBoolean(false),
      closed = AtomicBoolean(false),
    )
  }
}
