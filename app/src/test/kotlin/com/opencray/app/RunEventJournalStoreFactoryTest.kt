package com.opencray.app

import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.persistence.PersistenceJson
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun fileBackedStoreAllocatesSeqFromDiskAcrossFactoryInstances() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-cross-owner")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val firstStore = firstFactory.forChatSession("session-1")
    val secondStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")

    val firstEntry = firstStore.append(
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 0,
        text = "First owner",
        isFinal = false,
        stage = "scan",
        emittedAtEpochMs = 100L,
      ),
    )
    val secondEntry = secondStore.append(
      OpenCrayToolCallEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        emittedAtEpochMs = 200L,
      ),
    )
    val thirdEntry = firstStore.append(
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 2,
        text = "First owner after reconnect",
        isFinal = true,
        stage = "final",
        emittedAtEpochMs = 300L,
      ),
    )

    val restoredStore = FileBackedRunEventJournalStoreFactory(runtimeRoot)
      .forChatSession("session-1")
    val entries = restoredStore.listForRun("run-1")
    val sessionDirectory = firstFactory.directoryForSession("session-1")

    assertEquals(listOf(1L, 2L, 3L), listOf(firstEntry.seq, secondEntry.seq, thirdEntry.seq))
    assertEquals(listOf(1L, 2L, 3L), entries.map(PersistedRunJournalEntry::seq))
    assertEquals(3, entries.size)
    assertTrue(File(sessionDirectory, ".run-journal.lock").isFile)
    assertEquals(3, restoredStore.list().size)
  }

  @Test
  fun knownSessionIdsOnlyIncludesSessionsWithJournalEntries() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-known-sessions")
    val factory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    factory.forChatSession("session-empty")
    factory.forChatSession("session-with-journal").append(
      OpenCrayAssistantEvent(
        runId = "run-journal",
        taskId = "task-journal",
        turn = 0,
        text = "Durable journal evidence",
        isFinal = false,
        stage = "scan",
        emittedAtEpochMs = 100L,
      ),
    )

    val restoredFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)

    assertEquals(listOf("session-with-journal"), restoredFactory.knownSessionIds())
  }

  @Test
  fun knownSessionIdsIgnoresSessionsWithOnlyMalformedJournalFiles() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-malformed-known-sessions")
    val factory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    factory.forChatSession("session-malformed")
    val malformedFile = journalFileFor(
      sessionDirectory = factory.directoryForSession("session-malformed"),
      runId = "run-malformed",
      seq = 1L,
      kind = "assistant_phase",
    )
    malformedFile.parentFile?.mkdirs()
    malformedFile.writeText("{not-json", Charsets.UTF_8)

    val restoredFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val restoredStore = restoredFactory.forChatSession("session-malformed")

    assertFalse(restoredStore.hasEntries())
    assertTrue(restoredStore.list().isEmpty())
    assertTrue(restoredStore.listForRun("run-malformed").isEmpty())
    assertTrue(restoredFactory.knownSessionIds().isEmpty())
  }

  @Test
  fun fileBackedStoreCanAppendAfterMalformedJournalFile() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-malformed-append")
    val factory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    factory.forChatSession("session-malformed")
    val malformedFile = journalFileFor(
      sessionDirectory = factory.directoryForSession("session-malformed"),
      runId = "run-malformed",
      seq = 1L,
      kind = "assistant_phase",
    )
    malformedFile.parentFile?.mkdirs()
    malformedFile.writeText("{not-json", Charsets.UTF_8)

    val restoredFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val restoredStore = restoredFactory.forChatSession("session-malformed")
    val appended = restoredStore.append(
      OpenCrayAssistantEvent(
        runId = "run-malformed",
        taskId = "task-malformed",
        turn = 1,
        text = "Recovered after malformed journal file.",
        isFinal = false,
        stage = "repair",
        emittedAtEpochMs = 200L,
      ),
    )
    val entries = restoredStore.listForRun("run-malformed")

    assertEquals(2L, appended.seq)
    assertEquals(1, entries.size)
    assertEquals(2L, entries.single().seq)
    assertTrue(restoredFactory.knownSessionIds().contains("session-malformed"))
  }

  @Test
  fun fileBackedStoreClearsEntriesWithoutListingLockSidecar() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-clear")
    val factory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val store = factory.forChatSession("session-1")

    store.append(
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 0,
        text = "Before clear",
        isFinal = false,
        stage = "scan",
        emittedAtEpochMs = 100L,
      ),
    )

    store.clear()
    val postClearEntry = store.append(
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 1,
        text = "After clear",
        isFinal = true,
        stage = "final",
        emittedAtEpochMs = 200L,
      ),
    )

    val entries = store.listForRun("run-1")
    val sessionDirectory = factory.directoryForSession("session-1")

    assertTrue(File(sessionDirectory, ".run-journal.lock").isFile)
    assertEquals(1L, postClearEntry.seq)
    assertEquals(listOf(1L), entries.map(PersistedRunJournalEntry::seq))
    assertEquals(1, store.list().size)
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

  @Test
  fun fileBackedStoreRepairsLegacyNestedResumeMetadataWhenReloaded() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-store-repair")
    val factory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val store = factory.forChatSession("session-1")
    store.append(
      OpenCrayToolResultEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 1,
        call = AgentToolCall(toolName = "WebSearch"),
        result = AgentToolResult(
          toolName = "WebSearch",
          status = AgentToolResultStatus.SUCCESS,
          content = "done",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json = json,
            checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
          ),
        ),
        emittedAtEpochMs = 200L,
      ),
    )

    val sessionDirectory = factory.directoryForSession("session-1")
    val journalFile = sessionDirectory
      .resolve("run-journal")
      .walkTopDown()
      .firstOrNull { file -> file.isFile && file.name.endsWith(".json") }
      ?: error("Expected a single journal file.")
    val persistedEntry = PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRunJournalEntry.serializer(),
      string = journalFile.readText(),
    )
    val legacyState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          content = legacyToolResultPayload(),
          toolResult = RuntimeConversationToolResult(
            toolName = "WebSearch",
            status = "success",
            isError = false,
          ),
        ),
      ),
    )
    journalFile.writeText(
      PersistenceJson.instance.encodeToString(
        serializer = PersistedRunJournalEntry.serializer(),
        value = persistedEntry.copy(
          payload = persistedEntry.payload.copy(
            resultMetadata = mapOf(
              OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to json.encodeToString(
                OpenCrayPromptResumeState.serializer(),
                legacyState,
              ),
              OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY to
                OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED.wireValue,
            ),
          ),
        ),
      ),
    )

    val restoredEntry = factory.forChatSession("session-1").listForRun("run-1").single()
    val restoredState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = restoredEntry.payload.resultMetadata,
      json = json,
    )

    assertNotNull(restoredState)
    assertFalse(
      restoredState
        ?.transcript
        ?.single()
        ?.content
        ?.contains(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON)
        ?: true,
    )
    assertFalse(journalFile.readText().contains(encodedPayloadForRecord(legacyToolResultPayload())))
    val journalFileDirectory = checkNotNull(journalFile.parentFile)
    assertTrue(
      journalFileDirectory
        .listFiles()
        .orEmpty()
        .none { file -> file.name.endsWith(".tmp") },
    )
  }

  private fun legacyToolResultPayload(): String = """
    {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"WebSearch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com","opencray_prompt_resume_json":"{\"turnIndex\":0,\"toolCallCount\":0}","opencray_prompt_checkpoint_boundary":"tool_result_committed"}}
  """.trimIndent()

  private fun encodedPayloadForRecord(payload: String): String = payload
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

  private fun journalFileFor(
    sessionDirectory: File,
    runId: String,
    seq: Long,
    kind: String,
  ): File = File(
    File(
      sessionDirectory,
      "run-journal/run-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(runId)}",
    ),
    "${seq.toString().padStart(12, '0')}-$kind.json",
  )
}
