package com.opencray.app

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
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
}
