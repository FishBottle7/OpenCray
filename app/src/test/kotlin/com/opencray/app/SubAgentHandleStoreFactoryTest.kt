package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentSessionLink
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
  fun fileBackedStoreRoundTripsClosedHandles() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-store-closed")
    val firstStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    val closedHandle = sampleHandle(
      agentId = "child-closed-roundtrip",
      childRunId = "child-run-closed-roundtrip",
      childTaskId = "child-task-closed-roundtrip",
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "Delegated child run was closed.",
      ),
      updatedAtEpochMs = 1_300L,
    ).copy(
      childExecutionStatus = ExecutionStatus.CANCELLED.name,
    )

    firstStore.upsertClosed(closedHandle)

    val restoredStore = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1")
    assertTrue(restoredStore.list().isEmpty())
    assertEquals(listOf(closedHandle), restoredStore.listClosed())
    assertEquals(
      closedHandle,
      restoredStore.getClosed(parentRunId = closedHandle.parentRunId, agentId = closedHandle.agentId),
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
  fun fileBackedSessionLinkStoreRoundTripsTrackedLinks() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-session-link-store")
    val firstStore = FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot).forChatSession("session-1")
    val handle = sampleHandle(
      agentId = "child-link-roundtrip",
      childRunId = "child-run-link-roundtrip",
      childTaskId = "child-task-link-roundtrip",
      updatedAtEpochMs = 1_220L,
    )
    val link = SubAgentSessionLink.fromHandle(
      parentSessionId = "session-1",
      handle = handle,
      closed = false,
    )

    firstStore.upsert(link)

    val restoredStore = FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot).forChatSession("session-1")
    assertEquals(listOf(link), restoredStore.list())
    assertEquals(link, restoredStore.get(parentRunId = handle.parentRunId, agentId = handle.agentId))
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
      sessionId = "session-1",
      store = repairedStore,
      linkStore = FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot).forChatSession("session-1"),
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
    assertEquals(
      repaired?.childSessionId,
      FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot)
        .forChatSession("session-1")
        .list()
        .single()
        .childSessionId,
    )
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
      sessionId = "session-1",
      store = repairedStore,
      linkStore = FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot).forChatSession("session-1"),
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
  fun persistentCoordinatorRejectsStaleExecutionOwnedWrites() {
    val runtimeRoot = temporaryFolder.newFolder("subagent-handle-owned-write-guard")
    val coordinator = PersistentSessionSubAgentExecutionCoordinator(
      sessionId = "session-1",
      store = FileBackedSubAgentHandleStoreFactory(runtimeRoot).forChatSession("session-1"),
      linkStore = FileBackedSubAgentSessionLinkStoreFactory(runtimeRoot).forChatSession("session-1"),
    )
    val initialHandle = sampleHandle(updatedAtEpochMs = 1_000L)
    val executionKey = SubAgentExecutionKey.from(initialHandle)
    val staleExecution = activeExecution()
    val currentExecution = activeExecution()

    try {
      coordinator.upsertHandle(initialHandle)
      coordinator.registerActiveExecution(executionKey, staleExecution)

      coordinator.takeActiveExecution(executionKey, expectedExecution = staleExecution)
      val restartedHandle = initialHandle.copy(
        snapshot = SubAgentExecutionSnapshot.backgroundRunning(
          headline = "Restarted delegated child is running.",
        ),
        updatedAtEpochMs = 1_100L,
      )
      coordinator.upsertHandle(restartedHandle)
      coordinator.registerActiveExecution(executionKey, currentExecution)

      val staleCheckpointHandle = initialHandle.copy(
        childPromptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 2,
        ),
        childPromptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
        childPromptCheckpointAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
      )
      assertNull(
        coordinator.upsertHandleIfOwnedByExecution(
          handle = staleCheckpointHandle,
          expectedExecution = staleExecution,
        ),
      )
      assertEquals(restartedHandle, coordinator.currentHandle(executionKey))

      val staleCompletedHandle = initialHandle.copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Stale delegated child completion.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        updatedAtEpochMs = 1_300L,
      )
      assertNull(
        coordinator.finishExecution(
          handle = staleCompletedHandle,
          expectedExecution = staleExecution,
        ),
      )
      assertSame(currentExecution, coordinator.activeExecution(executionKey))
      assertEquals(restartedHandle, coordinator.currentHandle(executionKey))

      val currentCompletedHandle = restartedHandle.copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Fresh delegated child completion.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        updatedAtEpochMs = 1_400L,
      )
      assertEquals(
        currentCompletedHandle,
        coordinator.finishExecution(
          handle = currentCompletedHandle,
          expectedExecution = currentExecution,
        ),
      )
      assertNull(coordinator.activeExecution(executionKey))
      assertEquals(currentCompletedHandle, coordinator.currentHandle(executionKey))
    } finally {
      staleExecution.executor.shutdownNow()
      currentExecution.executor.shutdownNow()
    }
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

  private fun activeExecution(): SubAgentActiveExecution {
    val executor = Executors.newSingleThreadExecutor()
    return SubAgentActiveExecution(
      executor = executor,
      future = FutureTask<Unit> { },
      cancelRequested = AtomicBoolean(false),
      closed = AtomicBoolean(false),
    )
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
