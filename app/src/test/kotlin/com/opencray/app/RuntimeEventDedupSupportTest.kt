package com.opencray.app

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeEventDedupSupportTest {
  @Test
  fun dedupeRuntimeEventsKeepsLaterDetailsForSameSemanticEvent() {
    val call = AgentToolCall(toolName = "Read")
    val thin = OpenCrayToolResultEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 1,
      call = call,
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = "thin result",
      ),
      emittedAtEpochMs = 2_000L,
    )
    val rich = thin.copy(
      result = thin.result.copy(
        content = "full file contents",
        metadata = mapOf("lineRange" to "1-40"),
      ),
    )

    val deduped = dedupeRuntimeEventsPreservingOrder(listOf(thin, rich))

    assertEquals(1, deduped.size)
    val event = deduped.single() as OpenCrayToolResultEvent
    assertEquals("full file contents", event.result.content)
    assertEquals("1-40", event.result.metadata["lineRange"])
  }

  @Test
  fun dedupeRuntimeEventsKeepsDistinctToolCallIds() {
    val first = OpenCrayToolResultEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 1,
      call = AgentToolCall(id = "call-1", toolName = "Read"),
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = "same result",
      ),
      emittedAtEpochMs = 2_000L,
    )
    val second = first.copy(
      call = first.call.copy(id = "call-2"),
      emittedAtEpochMs = 2_001L,
    )

    val deduped = dedupeRuntimeEventsPreservingOrder(listOf(first, second))

    assertEquals(2, deduped.size)
  }

  @Test
  fun dedupeRuntimeEventsKeepsLaterAssistantPhaseTextForSameStage() {
    val first = OpenCrayAssistantEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 0,
      text = "Planning",
      stage = "Planning",
      emittedAtEpochMs = 2_000L,
    )
    val richer = first.copy(
      text = "Planning the next tool call",
    )

    val deduped = dedupeRuntimeEventsPreservingOrder(listOf(first, richer))

    assertEquals(1, deduped.size)
    val event = deduped.single() as OpenCrayAssistantEvent
    assertEquals("Planning the next tool call", event.text)
  }
}
