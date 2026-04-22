package com.opencray.app

import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.Json

class RunRecoveryProjectionSupportTest {
  private val json: Json = Json { prettyPrint = false }

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

  @Test
  fun loadStoredRunRecoveryPlanUsesLatestPersistedRuntimeTailWhenEmissionOrderDiffers() {
    val sessionId = "session-tail-order"
    val runId = "run-tail-order"
    val taskId = "task-tail-order"
    val checkpointStore = inMemoryPromptCheckpointStoreFactory().forChatSession(sessionId)
    val journalStore = inMemoryRunEventJournalStoreFactory().forChatSession(sessionId)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-tail-order",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 175L,
        updatedAtEpochMs = 175L,
      ),
    )
    journalStore.append(
      OpenCrayToolResultEvent(
        runId = runId,
        taskId = taskId,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Earlier persisted event with later emitted timestamp.",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json = json,
            checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
          ),
        ),
        emittedAtEpochMs = 200L,
      ),
    )
    journalStore.append(
      OpenCrayToolCallEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Write"),
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
    assertEquals("tool_call", plan?.journalTailKind)
  }
}
