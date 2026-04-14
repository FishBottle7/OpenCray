package com.opencray.app

import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecordStoreFactoryTest {
  @Test
  fun subagentEventRoundTripsThroughPersistedRecord() {
    val event = OpenCraySubAgentEvent(
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.COMPLETED,
      childRunId = "run-child",
      childTaskId = "task-child",
      label = "Inspect README",
      subagentType = "researcher",
      contextMode = "minimal",
      depth = 1,
      summary = "README inspection finished.",
      executionState = SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
      continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
      resumable = true,
      requiresUserAction = true,
      isHighRisk = true,
      turn = 2,
      emittedAtEpochMs = 1_234L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.COMPLETED, restored.phase)
    assertEquals("run-parent", restored.runId)
    assertEquals("task-parent", restored.taskId)
    assertEquals("run-child", restored.childRunId)
    assertEquals("task-child", restored.childTaskId)
    assertEquals("Inspect README", restored.label)
    assertEquals("researcher", restored.subagentType)
    assertEquals("minimal", restored.contextMode)
    assertEquals(1, restored.depth)
    assertEquals("README inspection finished.", restored.summary)
    assertEquals(SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL, restored.executionState)
    assertEquals(SubAgentContinuationKind.PROMPT_RESUME, restored.continuationKind)
    assertTrue(restored.resumable)
    assertTrue(restored.requiresUserAction)
    assertTrue(restored.isHighRisk)
    assertEquals(2, restored.turn)
    assertEquals(1_234L, restored.emittedAtEpochMs)
  }

  @Test
  fun persistedSubagentEventFallsBackToSafeDefaults() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      emittedAtEpochMs = 8L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.STARTED, restored.phase)
    assertEquals("run-parent", restored.childRunId)
    assertEquals("task-parent", restored.childTaskId)
    assertEquals("Task", restored.label)
    assertEquals("general-purpose", restored.subagentType)
    assertEquals("delegated", restored.contextMode)
    assertEquals(1, restored.depth)
    assertEquals(SubAgentExecutionState.RUNNING, restored.executionState)
    assertEquals(SubAgentContinuationKind.NONE, restored.continuationKind)
    assertFalse(restored.resumable)
    assertFalse(restored.requiresUserAction)
    assertFalse(restored.isHighRisk)
    assertTrue(restored.summary == null)
  }

  @Test
  fun resumedSubagentEventDefaultsToRunningExecutionState() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.RESUMED.name,
      emittedAtEpochMs = 9L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.RESUMED, restored.phase)
    assertEquals(SubAgentExecutionState.RUNNING, restored.executionState)
    assertEquals(SubAgentContinuationKind.NONE, restored.continuationKind)
    assertFalse(restored.resumable)
    assertFalse(restored.requiresUserAction)
  }

  @Test
  fun backgroundQueuedSubagentEventDefaultsToBackgroundResumeContinuation() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.STARTED.name,
      subAgentExecutionState = "background_queued",
      emittedAtEpochMs = 10L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.STARTED, restored.phase)
    assertEquals(SubAgentExecutionState.BACKGROUND_QUEUED, restored.executionState)
    assertEquals(SubAgentContinuationKind.BACKGROUND_RESUME, restored.continuationKind)
    assertTrue(restored.resumable)
    assertFalse(restored.requiresUserAction)
    assertFalse(restored.isHighRisk)
  }

  @Test
  fun supplementEventRoundTripsThroughPersistedRecordMetadata() {
    val event = OpenCraySupplementEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 3,
      entryId = "supplement-1",
      text = "Also inspect the docs.",
      checkpoint = "post_tool_pre_model",
      metadata = mapOf(
        "checkpointKind" to "general_resume",
        "promptResumeState" to "encoded-state",
      ),
      emittedAtEpochMs = 2_345L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCraySupplementEvent

    assertEquals("run-1", restored.runId)
    assertEquals("task-1", restored.taskId)
    assertEquals(3, restored.turn)
    assertEquals("supplement-1", restored.entryId)
    assertEquals("Also inspect the docs.", restored.text)
    assertEquals("post_tool_pre_model", restored.checkpoint)
    assertEquals(event.metadata, restored.metadata)
    assertEquals(2_345L, restored.emittedAtEpochMs)
  }

  @Test
  fun persistedSupplementEventDefaultsMetadataToEmptyMap() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUPPLEMENT,
      runId = "run-1",
      taskId = "task-1",
      turn = 2,
      emittedAtEpochMs = 44L,
      supplementEntryId = "supplement-legacy",
      text = "Legacy supplement",
    ).let(::runtimeEventForTest) as OpenCraySupplementEvent

    assertEquals("supplement-legacy", restored.entryId)
    assertEquals("Legacy supplement", restored.text)
    assertEquals("turn_start", restored.checkpoint)
    assertTrue(restored.metadata.isEmpty())
  }

  @Test
  fun checkpointEventsDoNotProjectIntoRuntimeEvents() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.CHECKPOINT,
      runId = "run-checkpoint",
      taskId = "task-checkpoint",
      emittedAtEpochMs = 55L,
    ).toRuntimeEventOrNull()

    assertNull(restored)
  }
}
