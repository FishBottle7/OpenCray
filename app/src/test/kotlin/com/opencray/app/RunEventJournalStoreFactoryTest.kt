package com.opencray.app

import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json

class RunEventJournalStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()
  private val json: Json = Json { prettyPrint = false }

  @Test
  fun fileBackedStoreAppendsAndReloadsEventsInOrder() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val firstStore = firstFactory.forChatSession("session-1")

    firstStore.append(
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 0,
        text = "Scanning workspace",
        isFinal = false,
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
    assertEquals(PersistedAgentRunEventKind.ASSISTANT_PHASE, entries[0].kind)
    assertEquals(PersistedAgentRunEventKind.TOOL_CALL, entries[1].kind)
    assertEquals(listOf(100L, 200L), entries.map(PersistedRunJournalEntry::emittedAtEpochMs))
    assertEquals(listOf("run-1", "run-1"), runtimeEvents.map { event -> event.runId })
    assertTrue(runtimeEvents.any { event -> event is OpenCrayAssistantEvent && !event.isFinal })
    assertTrue(runtimeEvents.any { event -> event is OpenCrayToolCallEvent })
  }

  @Test
  fun fileBackedStorePreservesSupplementMetadataAcrossReload() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-supplement")
    val firstStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")

    firstStore.append(
      OpenCraySupplementEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 2,
        entryId = "supplement-1",
        text = "Use the cached result.",
        checkpoint = "post_tool_pre_model",
        metadata = mapOf(
          "checkpointKind" to "general_resume",
          "promptResumeState" to "encoded-state",
        ),
        emittedAtEpochMs = 300L,
      ),
    )

    val restoredStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val entries = restoredStore.listForRun("run-1")
    val runtimeEvent = restoredStore.listRuntimeEvents().single() as OpenCraySupplementEvent

    assertEquals(1, entries.size)
    assertEquals(PersistedAgentRunEventKind.SUPPLEMENT, entries.single().kind)
    assertEquals(
      mapOf(
        "checkpointKind" to "general_resume",
        "promptResumeState" to "encoded-state",
      ),
      entries.single().payload.resultMetadata,
    )
    assertEquals("supplement-1", runtimeEvent.entryId)
    assertEquals("post_tool_pre_model", runtimeEvent.checkpoint)
    assertEquals(
      mapOf(
        "checkpointKind" to "general_resume",
        "promptResumeState" to "encoded-state",
      ),
      runtimeEvent.metadata,
    )
  }

  @Test
  fun fileBackedStorePersistsCheckpointEntriesWithoutProjectingRuntimeEvents() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-checkpoint")
    val firstStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val resumeState = OpenCrayPromptResumeState(turnIndex = 3, toolCallCount = 1)

    firstStore.appendCheckpoint(
      runId = "run-1",
      taskId = "task-1",
      emission = OpenCrayPromptCheckpointEmission(
        boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
        state = resumeState,
        emittedAtEpochMs = 150L,
      ),
    )

    val restoredStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val entries = restoredStore.listForRun("run-1")
    val checkpointEntry = entries.single()
    val restoredResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = checkpointEntry.payload.resultMetadata,
      json = json,
    )

    assertEquals(1, entries.size)
    assertEquals(PersistedAgentRunEventKind.CHECKPOINT, checkpointEntry.kind)
    assertEquals(
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(checkpointEntry.payload.resultMetadata),
    )
    assertEquals(resumeState, restoredResumeState)
    assertNotNull(restoredResumeState)
    assertTrue(restoredStore.listRuntimeEvents().isEmpty())
  }

  @Test
  fun fileBackedStorePersistsRecoveryEntriesWithoutProjectingRuntimeEvents() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-recovery")
    val firstStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")

    firstStore.appendRecovery(
      runId = "run-1",
      taskId = "task-1",
      emittedAtEpochMs = 250L,
      metadata = mapOf(
        RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_from_checkpoint",
        METADATA_RECOVERY_REASON to "durable_general_resume_checkpoint",
        RunLifecycleMetadataKeys.RUN_ATTEMPT to "2",
        RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID to "checkpoint-1",
      ),
    )

    val restoredStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val recoveryEntry = restoredStore.listForRun("run-1").single()

    assertEquals(PersistedAgentRunEventKind.RECOVERY, recoveryEntry.kind)
    assertEquals(250L, recoveryEntry.emittedAtEpochMs)
    assertEquals(
      "resume_from_checkpoint",
      recoveryEntry.payload.resultMetadata[RunLifecycleMetadataKeys.RECOVERY_ACTION],
    )
    assertEquals(
      "durable_general_resume_checkpoint",
      recoveryEntry.payload.resultMetadata[METADATA_RECOVERY_REASON],
    )
    assertEquals(
      "2",
      recoveryEntry.payload.resultMetadata[RunLifecycleMetadataKeys.RUN_ATTEMPT],
    )
    assertEquals(
      "checkpoint-1",
      recoveryEntry.payload.resultMetadata[RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID],
    )
    assertTrue(restoredStore.listRuntimeEvents().isEmpty())
  }
}
