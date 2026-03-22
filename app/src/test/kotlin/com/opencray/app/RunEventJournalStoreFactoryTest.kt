package com.opencray.app

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunEventJournalStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreAppendsAndReloadsEventsInOrder() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val firstStore = firstFactory.forChatSession("session-1")

    firstStore.append(
      OpenCrayProgressEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 0,
        text = "Scanning workspace",
        stage = "scan",
        emittedAtEpochMs = 100L,
      ),
    )
    firstStore.append(
      OpenCrayToolCallEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        emittedAtEpochMs = 200L,
      ),
    )

    val restoredStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val entries = restoredStore.listForRun("run-1")
    val runtimeEvents = restoredStore.listRuntimeEvents()

    assertEquals(2, entries.size)
    assertEquals(1L, entries[0].seq)
    assertEquals(2L, entries[1].seq)
    assertEquals(PersistedAgentRunEventKind.PROGRESS, entries[0].kind)
    assertEquals(PersistedAgentRunEventKind.TOOL_CALL, entries[1].kind)
    assertEquals(listOf(100L, 200L), entries.map(PersistedRunJournalEntry::emittedAtEpochMs))
    assertEquals(listOf("run-1", "run-1"), runtimeEvents.map { event -> event.runId })
    assertTrue(runtimeEvents.any { event -> event is OpenCrayProgressEvent })
    assertTrue(runtimeEvents.any { event -> event is OpenCrayToolCallEvent })
  }
}
