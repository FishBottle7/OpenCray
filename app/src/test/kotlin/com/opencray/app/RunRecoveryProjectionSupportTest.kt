package com.opencray.app

import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RunRecoveryProjectionSupportTest {
  @Test
  fun loadStoredRunRecoveryPlanSkipsCheckpointJournalTail() {
    val sessionId = "session-1"
    val runId = "run-1"
    val taskId = "task-1"
    val checkpointStore = inMemoryPromptCheckpointStoreFactory().forChatSession(sessionId)
    val journalStore = inMemoryRunEventJournalStoreFactory().forChatSession(sessionId)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
      ),
    )
    journalStore.appendCheckpoint(
      runId = runId,
      taskId = taskId,
      emission = OpenCrayPromptCheckpointEmission(
        boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
        state = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 0),
        emittedAtEpochMs = 150L,
      ),
    )

    val plan = loadStoredRunRecoveryPlan(
      run = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        lifecycleState = QueueTaskLifecycleState.QUEUED,
        taskState = null,
      ),
      checkpointStore = checkpointStore,
      journalStore = journalStore,
    )

    assertNotNull(plan)
    assertEquals(RunRecoveryAction.RESUME_FROM_CHECKPOINT, plan?.action)
    assertEquals("durable_general_resume_checkpoint", plan?.reasonCode)
  }
}
