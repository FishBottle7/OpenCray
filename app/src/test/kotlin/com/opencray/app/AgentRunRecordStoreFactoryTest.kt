package com.opencray.app

import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import org.junit.Assert.assertEquals
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
      turn = 2,
      emittedAtEpochMs = 1_234L,
    )

    val restored = event.toPersistedRecord().toRuntimeEvent() as OpenCraySubAgentEvent

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
    ).toRuntimeEvent() as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.STARTED, restored.phase)
    assertEquals("run-parent", restored.childRunId)
    assertEquals("task-parent", restored.childTaskId)
    assertEquals("Task", restored.label)
    assertEquals("general-purpose", restored.subagentType)
    assertEquals("delegated", restored.contextMode)
    assertEquals(1, restored.depth)
    assertTrue(restored.summary == null)
  }
}
