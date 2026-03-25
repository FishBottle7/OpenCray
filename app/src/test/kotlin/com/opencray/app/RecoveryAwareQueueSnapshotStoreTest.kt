package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryAwareQueueSnapshotStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadRewritesInterruptedTaskToQueuedWhenGeneralResumeCheckpointExists() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-general-resume")
    val sessionId = "session-general-resume"
    val taskId = "task-general-resume"
    val runId = "run-general-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Resume the interrupted run.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    promptCheckpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-general-resume",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_150L,
        updatedAtEpochMs = 1_150L,
        toolName = "Read",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 2,
          toolCallCount = 1,
        ),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("5000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals("running", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertEquals("durable_general_resume_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(5_000L, restoredTask.task.updatedAtEpochMs)
    assertNull(restoredTask.lastErrorCode)
    assertNull(restoredTask.lastErrorMessage)
  }
}
