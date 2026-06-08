package com.opencray.app

import android.content.Intent
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.InMemorySessionQueueSnapshotStore
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
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
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

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
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsRoutesInteractiveQueueTaskToInteractive() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-interactive")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-interactive",
          runId = "run-interactive",
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
        ),
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.INTERACTIVE),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsRoutesScheduledQueueTaskToDetachedBackground() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-scheduled")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-scheduled",
          runId = "run-scheduled",
          lifecycleState = QueueTaskLifecycleState.QUEUED,
          taskState = AgentTaskState.QUEUED,
          metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-nightly"),
        ),
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsRoutesDetachedControlQueueTaskToDetachedBackground() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-detached-control")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-detached-control",
          runId = "run-detached-control",
          lifecycleState = QueueTaskLifecycleState.RETRY_PENDING,
          taskState = AgentTaskState.QUEUED,
          metadata = mapOf(
            METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
          ),
        ),
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
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
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

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
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsRoutesCheckpointThroughMatchingQueueTask() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-checkpoint")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-checkpoint-scheduled",
          runId = "run-checkpoint-scheduled",
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
          metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-checkpoint"),
        ),
      ),
    )
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-checkpoint-scheduled",
        taskId = "task-checkpoint-scheduled",
        checkpointId = "checkpoint-scheduled",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsDefaultsCheckpointWithoutQueueTaskToInteractive() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-checkpoint-only")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-checkpoint-only",
        taskId = "task-checkpoint-only",
        checkpointId = "checkpoint-only",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.INTERACTIVE),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkReturnsTrueWhenDurableBackgroundSubAgentHandleExists() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-subagent")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val subAgentSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    subAgentHandleStoreFactory.forChatSession(subAgentSessionId).upsert(
      backgroundSubAgentHandle(agentId = "child-repair"),
    )

    assertTrue(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairTargetsRoutesDurableBackgroundSubAgentToDetachedBackground() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-target-subagent")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    subAgentHandleStoreFactory.forChatSession(sessionId).upsert(
      backgroundSubAgentHandle(agentId = "child-detached"),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkFindsDurableSessionOutsideChatSessionStoreState() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-durable-only")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val durableOnlySessionId = "session-durable-only"

    promptCheckpointStoreFactory.forChatSession(durableOnlySessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = durableOnlySessionId,
        runId = "run-durable-only",
        taskId = "task-durable-only",
        checkpointId = "checkpoint-durable-only",
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
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkFindsDurableRunRecordOnlySession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-run-record")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()
    val durableOnlySessionId = "session-run-record-only"

    runRecordStoreFactory.forChatSession(durableOnlySessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-record-only",
        taskId = "task-record-only",
        acceptedAtEpochMs = 1_000L,
        lastEvent = OpenCrayAssistantEvent(
          runId = "run-record-only",
          taskId = "task-record-only",
          turn = 0,
          text = "Recovered partial progress.",
          isFinal = false,
          emittedAtEpochMs = 1_100L,
        ).toPersistedRecord(),
      ),
    )

    assertTrue(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runRecordStoreFactory = runRecordStoreFactory,
      ),
    )
    assertEquals(
      setOf(RuntimeServiceTarget.INTERACTIVE),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runRecordStoreFactory = runRecordStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkFindsJournalOnlySession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-journal")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val durableOnlySessionId = "session-journal-only"

    runEventJournalStoreFactory.forChatSession(durableOnlySessionId).append(
      OpenCrayAssistantEvent(
        runId = "run-journal-only",
        taskId = "task-journal-only",
        turn = 0,
        text = "Recovered journal-only progress.",
        isFinal = false,
        emittedAtEpochMs = 1_100L,
      ),
    )

    assertTrue(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
    assertEquals(
      setOf(RuntimeServiceTarget.INTERACTIVE),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkIgnoresTerminalRunRecordWithStaleJournal() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-terminal-run-record")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()

    runRecordStoreFactory.forChatSession("session-terminal-record").upsert(
      PersistedAgentRunRecord(
        runId = "run-terminal-record",
        taskId = "task-terminal-record",
        acceptedAtEpochMs = 1_000L,
        lastResult = ExecutionResult(
          taskId = "task-terminal-record",
          status = ExecutionStatus.SUCCESS,
          stdout = "Done",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_100L,
        ),
      ),
    )
    runEventJournalStoreFactory.forChatSession("session-terminal-record").append(
      OpenCrayAssistantEvent(
        runId = "run-terminal-record",
        taskId = "task-terminal-record",
        turn = 0,
        text = "Older non-terminal journal event.",
        isFinal = false,
        emittedAtEpochMs = 1_050L,
      ),
    )

    assertFalse(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runRecordStoreFactory = runRecordStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkIgnoresFinalJournalOnlySession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-final-journal")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()

    runEventJournalStoreFactory.forChatSession("session-final-journal").append(
      OpenCrayAssistantEvent(
        runId = "run-final-journal",
        taskId = "task-final-journal",
        turn = 0,
        text = "Done.",
        isFinal = true,
        emittedAtEpochMs = 1_100L,
      ),
    )

    assertFalse(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      potentialInterruptedRunRepairTargets(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
      ),
    )
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkReturnsFalseWhenOnlyTerminalTasksAndTerminalSubAgentsExist() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-idle")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
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
    subAgentHandleStoreFactory.forChatSession(sessionId).upsert(
      completedSubAgentHandle(agentId = "child-completed"),
    )

    assertFalse(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
  }

  @Test
  fun startInterruptedRunRepairTargetsAttemptsAllTargetsAndReportsAggregateResult() {
    val startedTargets = mutableListOf<RuntimeServiceTarget>()

    val started = startInterruptedRunRepairTargets(
      targets = setOf(
        RuntimeServiceTarget.INTERACTIVE,
        RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
      startRepair = { target ->
        startedTargets += target
        target == RuntimeServiceTarget.INTERACTIVE
      },
    )

    assertFalse(started)
    assertEquals(
      listOf(RuntimeServiceTarget.DETACHED_BACKGROUND, RuntimeServiceTarget.INTERACTIVE),
      startedTargets,
    )
  }

  @Test
  fun startInterruptedRunRepairTargetsContinuesAfterTargetStartThrows() {
    val startedTargets = mutableListOf<RuntimeServiceTarget>()

    val started = startInterruptedRunRepairTargets(
      targets = setOf(
        RuntimeServiceTarget.INTERACTIVE,
        RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
      startRepair = { target ->
        startedTargets += target
        if (target == RuntimeServiceTarget.DETACHED_BACKGROUND) {
          error("detached start failed")
        }
        true
      },
    )

    assertFalse(started)
    assertEquals(
      listOf(RuntimeServiceTarget.DETACHED_BACKGROUND, RuntimeServiceTarget.INTERACTIVE),
      startedTargets,
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
    metadata: Map<String, String> = emptyMap(),
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
      ) + metadata,
    ),
    lifecycleState = lifecycleState,
  )

  private fun backgroundSubAgentHandle(
    agentId: String,
  ): SubAgentHandleState = SubAgentHandleState.queued(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Recover child $agentId",
    prompt = "Resume background child $agentId",
    subagentType = "worker",
    contextMode = "delegated",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 0,
    depth = 1,
    activeSkillName = null,
    activeSkillActivationSource = null,
    createdAtEpochMs = 1_000L,
  )

  private fun completedSubAgentHandle(
    agentId: String,
  ): SubAgentHandleState = backgroundSubAgentHandle(agentId).copy(
    snapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.COMPLETED,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = false,
      headline = "Delegated child run completed.",
    ),
    updatedAtEpochMs = 1_100L,
  )

  private class InMemoryAgentQueueSnapshotStoreFactory : AgentQueueSnapshotStoreFactory {
    private val stores = linkedMapOf<String, SessionQueueSnapshotStore>()

    override fun forChatSession(sessionId: String): SessionQueueSnapshotStore =
      stores.getOrPut(sessionId) { InMemorySessionQueueSnapshotStore() }

    override fun knownSessionIds(): List<String> = stores.keys.toList()
  }

  private class InMemoryAgentRunRecordStoreFactory : AgentRunRecordStoreFactory {
    private val stores = linkedMapOf<String, InMemoryAgentRunRecordStore>()

    override fun forChatSession(sessionId: String): AgentRunRecordStore =
      stores.getOrPut(sessionId) { InMemoryAgentRunRecordStore() }

    override fun knownSessionIds(): List<String> = stores.keys.toList()
  }

  private class InMemoryAgentRunRecordStore : AgentRunRecordStore {
    private val recordsByRunId = linkedMapOf<String, PersistedAgentRunRecord>()

    override fun list(): List<PersistedAgentRunRecord> = recordsByRunId.values.toList()

    override fun upsert(record: PersistedAgentRunRecord) {
      recordsByRunId[record.runId] = record
    }
  }
}
