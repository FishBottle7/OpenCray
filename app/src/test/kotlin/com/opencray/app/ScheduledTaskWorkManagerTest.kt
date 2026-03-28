package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import android.content.Intent
import com.opencray.core.orchestrator.InMemorySessionQueueSnapshotStore
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScheduledTaskWorkManagerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun scheduledTaskRepairReasonForActionMapsKnownBroadcasts() {
    assertEquals(
      ScheduledTaskRepairReasons.BOOT_COMPLETED,
      scheduledTaskRepairReasonForAction(Intent.ACTION_BOOT_COMPLETED),
    )
    assertEquals(
      ScheduledTaskRepairReasons.PACKAGE_REPLACED,
      scheduledTaskRepairReasonForAction(Intent.ACTION_MY_PACKAGE_REPLACED),
    )
    assertEquals(null, scheduledTaskRepairReasonForAction("custom.action.UNKNOWN"))
    assertEquals(null, scheduledTaskRepairReasonForAction(null))
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkReturnsTrueForNonTerminalQueueTaskInKnownSession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-queued")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val recoverableSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()

    snapshotStoreFactory.forChatSession(recoverableSessionId).save(
      queueSnapshot(
        sessionId = recoverableSessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = recoverableSessionId,
          taskId = "task-recoverable",
          runId = "run-recoverable",
          lifecycleState = QueueTaskLifecycleState.QUEUED,
          taskState = AgentTaskState.QUEUED,
        ),
      ),
    )

    assertTrue(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkReturnsTrueWhenPromptCheckpointExistsWithoutQueueWork() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-checkpoint")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val checkpointSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()

    snapshotStoreFactory.forChatSession(checkpointSessionId).save(
      queueSnapshot(
        sessionId = checkpointSessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = checkpointSessionId,
          taskId = "task-completed",
          runId = "run-completed",
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
        ),
      ),
    )
    promptCheckpointStoreFactory.forChatSession(checkpointSessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = checkpointSessionId,
        runId = "run-checkpoint",
        taskId = "task-checkpoint",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    assertTrue(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkReturnsFalseWhenOnlyTerminalTasksExistWithoutCheckpoints() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-idle")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val sessionId = chatSessionStore.loadState().activeSession.sessionId

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-finished",
          runId = "run-finished",
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
        ),
      ),
    )

    assertFalse(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      ),
    )
  }

  private fun queueSnapshot(
    sessionId: String,
    taskSnapshot: SessionQueueTaskSnapshot,
  ): SessionQueueSnapshot = SessionQueueSnapshot(
    sessionId = sessionId,
    agentId = "test-agent",
    lifecycleState = SessionLifecycleState.IDLE,
    nextEnqueueOrder = 2L,
    tasks = listOf(taskSnapshot),
    updatedAtEpochMs = 1_000L,
  )

  private fun queueTaskSnapshot(
    sessionId: String,
    taskId: String,
    runId: String,
    lifecycleState: QueueTaskLifecycleState,
    taskState: AgentTaskState,
  ): SessionQueueTaskSnapshot = SessionQueueTaskSnapshot(
    enqueueOrder = 1L,
    task = AgentTask(
      id = taskId,
      type = AgentTaskType.PROMPT,
      input = "Test interactive repair precheck.",
      state = taskState,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
        AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
      ),
    ),
    lifecycleState = lifecycleState,
  )

  private class InMemoryAgentQueueSnapshotStoreFactory : AgentQueueSnapshotStoreFactory {
    private val stores = linkedMapOf<String, SessionQueueSnapshotStore>()

    override fun forChatSession(sessionId: String): SessionQueueSnapshotStore =
      stores.getOrPut(sessionId) { InMemorySessionQueueSnapshotStore() }
  }
}
