package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import java.io.File
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubAgentHandleStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreRoundTripsTrackedHandles() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-store")
    val firstStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    val handle = sampleHandle(
      agentId = "child-roundtrip",
      childRunId = "child-run-roundtrip",
      childTaskId = "child-task-roundtrip",
      updatedAtEpochMs = 1_200L,
    )

    firstStore.upsert(handle)

    val restoredStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    assertEquals(listOf(handle), restoredStore.list())
    assertEquals(handle, restoredStore.get(parentRunId = handle.parentRunId, agentId = handle.agentId))
  }

  @Test
  fun fileBackedStoreKeepsHandlesIsolatedPerSession() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-store-isolation")
    val factory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)
    val sessionAHandle = sampleHandle(
      agentId = "child-session-a",
      childRunId = "child-run-session-a",
      childTaskId = "child-task-session-a",
      updatedAtEpochMs = 1_150L,
    )
    val sessionBHandle = sampleHandle(
      agentId = "child-session-b",
      childRunId = "child-run-session-b",
      childTaskId = "child-task-session-b",
      updatedAtEpochMs = 1_250L,
    )

    factory.forChatSession("session-a").upsert(sessionAHandle)
    factory.forChatSession("session-b").upsert(sessionBHandle)

    assertEquals(listOf(sessionAHandle), factory.forChatSession("session-a").list())
    assertEquals(listOf(sessionBHandle), factory.forChatSession("session-b").list())
    assertTrue(
      factory.forChatSession("session-a").list().none { handle ->
        handle.agentId == sessionBHandle.agentId
      },
    )
  }

  @Test
  fun fileBackedStoreListRepairUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedSubAgentHandleStore(
      sessionId = "session-1",
      storage = storage,
      clock = { 10_000L },
    )
    store.upsert(
      sampleHandle(
        agentId = "child-older",
        childRunId = "child-run-older",
        childTaskId = "child-task-older",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val staleBeforeConcurrentWrite = storage.currentText
    store.upsert(
      sampleHandle(
        agentId = "child-concurrent",
        childRunId = "child-run-concurrent",
        childTaskId = "child-task-concurrent",
        updatedAtEpochMs = 2_000L,
      ),
    )
    val updateCallsBeforeList = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentWrite)
    val handles = store.list()

    assertEquals(updateCallsBeforeList + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("child-concurrent", "child-older"),
      handles.map(SubAgentHandleState::agentId),
    )
    assertEquals(
      listOf("child-concurrent", "child-older"),
      store.list().map(SubAgentHandleState::agentId),
    )
  }

  @Test
  fun persistentCoordinatorRepairsInterruptedBackgroundHandlesOnStartup() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-repair")
    FileBackedSubAgentHandleStoreFactory(runtimeRoot)
      .forChatSession("session-1")
      .upsert(
        sampleHandle(
          agentId = "child-interrupted",
          childRunId = "child-run-interrupted",
          childTaskId = "child-task-interrupted",
          snapshot = SubAgentExecutionSnapshot.backgroundRunning(
            headline = "Delegated child run is still running in the background.",
          ),
          updatedAtEpochMs = 1_150L,
        ),
      )

    val repairedStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    val coordinator = PersistentSessionSubAgentExecutionCoordinator(
      store = repairedStore,
      clock = { 2_000L },
    )

    val repaired = coordinator.currentHandle(
      SubAgentExecutionKey(
        parentRunId = "run-parent",
        agentId = "child-interrupted",
      ),
    )

    assertNotNull(repaired)
    assertEquals(SubAgentExecutionState.FAILED, repaired?.snapshot?.state)
    assertFalse(repaired?.snapshot?.resumable ?: true)
    assertEquals("SUBAGENT_BACKGROUND_INTERRUPTED", repaired?.snapshot?.childErrorCode)
    assertEquals(ExecutionStatus.FAILED.name, repaired?.childExecutionStatus)
    assertEquals(2_000L, repaired?.updatedAtEpochMs)
    assertEquals(repaired, repairedStore.list().single())
  }

  @Test
  fun persistentCoordinatorRequeuesInterruptedBackgroundHandlesWithDurableCheckpoint() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-resume-repair")
    FileBackedSubAgentHandleStoreFactory(runtimeRoot)
      .forChatSession("session-1")
      .upsert(
        sampleHandle(
          agentId = "child-resumable",
          childRunId = "child-run-resumable",
          childTaskId = "child-task-resumable",
          snapshot = SubAgentExecutionSnapshot.backgroundRunning(
            headline = "Delegated child run is still running in the background.",
          ),
          childPromptResumeState = OpenCrayPromptResumeState(
            turnIndex = 0,
            toolCallCount = 0,
          ),
          childPromptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
          childPromptCheckpointAtEpochMs = 1_140L,
          updatedAtEpochMs = 1_150L,
        ),
      )

    val repairedStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    val coordinator = PersistentSessionSubAgentExecutionCoordinator(
      store = repairedStore,
      clock = { 2_000L },
    )

    val repaired = coordinator.currentHandle(
      SubAgentExecutionKey(
        parentRunId = "run-parent",
        agentId = "child-resumable",
      ),
    )

    assertNotNull(repaired)
    assertEquals(SubAgentExecutionState.BACKGROUND_QUEUED, repaired?.snapshot?.state)
    assertEquals(true, repaired?.snapshot?.resumable)
    assertEquals(OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST, repaired?.childPromptCheckpointBoundary)
    assertEquals(1_140L, repaired?.childPromptCheckpointAtEpochMs)
    assertEquals(2_000L, repaired?.updatedAtEpochMs)
  }

  @Test
  fun fileBackedStoreMigratesLegacySupplementalInputsIntoMailbox() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-store-legacy-mailbox")
    val factory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)
    val sessionDirectory = factory.directoryForSession("session-legacy").apply { mkdirs() }
    File(sessionDirectory, "runtime-subagent-handles.json").writeText(
      PersistenceJson.instance.encodeToString(
        serializer = LegacySubAgentHandleRecord.serializer(),
        value = LegacySubAgentHandleRecord(
          sessionId = "session-legacy",
          recordVersion = 1L,
          updatedAtEpochMs = 1_200L,
          handles = listOf(
            LegacySubAgentHandleState(
              agentId = "child-legacy",
              childRunId = "child-run-legacy",
              childTaskId = "child-task-legacy",
              description = "Inspect README",
              prompt = "Read README.md and summarize it.",
              supplementalInputs = listOf("Also inspect docs/notes.md."),
              subagentType = "researcher",
              contextMode = "minimal",
              parentRunId = "run-parent",
              parentTaskId = "task-parent",
              parentTurn = 1,
              depth = 1,
              snapshot = SubAgentExecutionSnapshot.backgroundQueued(
                headline = "Queued delegated child run 'Inspect README'.",
              ),
              createdAtEpochMs = 900L,
              updatedAtEpochMs = 1_100L,
            ),
          ),
        ),
      ),
    )

    val restoredHandle = factory.forChatSession("session-legacy").list().single()

    assertTrue(restoredHandle.supplementalInputs.isEmpty())
    assertEquals(
      listOf("Also inspect docs/notes.md."),
      restoredHandle.mailbox.messages.map { message -> message.text },
    )
    assertEquals(null, restoredHandle.mailbox.lastDeliveredMessageId)
  }

  private fun sampleHandle(
    agentId: String = "child-1",
    childRunId: String = "child-run-1",
    childTaskId: String = "child-task-1",
    snapshot: SubAgentExecutionSnapshot = SubAgentExecutionSnapshot.backgroundQueued(
      headline = "Queued delegated child run 'Inspect README'.",
    ),
    childPromptResumeState: OpenCrayPromptResumeState? = null,
    childPromptCheckpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
    childPromptCheckpointAtEpochMs: Long? = null,
    updatedAtEpochMs: Long = 1_100L,
  ): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = childRunId,
    childTaskId = childTaskId,
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "run-parent",
    parentTaskId = "task-parent",
    parentTurn = 1,
    depth = 1,
    snapshot = snapshot,
    childPromptResumeState = childPromptResumeState,
    childPromptCheckpointBoundary = childPromptCheckpointBoundary,
    childPromptCheckpointAtEpochMs = childPromptCheckpointAtEpochMs,
    createdAtEpochMs = 900L,
    updatedAtEpochMs = updatedAtEpochMs,
  )

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }

  @Serializable
  private data class LegacySubAgentHandleRecord(
    val schemaVersion: Int = com.opencray.persistence.PersistenceSchemaVersion.CURRENT,
    val sessionId: String,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val handles: List<LegacySubAgentHandleState> = emptyList(),
  )

  @Serializable
  private data class LegacySubAgentHandleState(
    val agentId: String,
    val childRunId: String,
    val childTaskId: String,
    val description: String,
    val prompt: String,
    val supplementalInputs: List<String> = emptyList(),
    val subagentType: String,
    val contextMode: String,
    val parentRunId: String,
    val parentTaskId: String,
    val parentTurn: Int,
    val depth: Int,
    val snapshot: SubAgentExecutionSnapshot,
    val pendingApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume? = null,
    val childPromptResumeState: OpenCrayPromptResumeState? = null,
    val childPromptCheckpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
    val childPromptCheckpointAtEpochMs: Long? = null,
    val childExecutionStatus: String? = null,
    val childTurnCount: Int? = null,
    val childToolCallCount: Int? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
  )
}
