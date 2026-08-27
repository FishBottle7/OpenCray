package com.opencray.app

import android.content.Intent
import androidx.work.ListenableWorker
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.InMemorySessionQueueSnapshotStore
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessDeliveredObservationState
import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
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
  fun scheduledTaskWakeWorkResultRetriesRejectedServiceWake() {
    assertEquals(
      ListenableWorker.Result.success().javaClass,
      scheduledTaskWakeWorkResult(serviceStartRequested = true).javaClass,
    )
    assertEquals(
      ListenableWorker.Result.retry().javaClass,
      scheduledTaskWakeWorkResult(serviceStartRequested = false).javaClass,
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceUsesRuntimeProjectionWorkForUnrepresentedSession() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-runtime-projection")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          activeSessionIds = listOf("session-projection", "session-projection"),
          pendingWorkSessionIds = listOf(" "),
          liveManagedProcessSessionIds = listOf("session-projection-managed"),
        ),
      ),
    )

    assertEquals(
      listOf(
        InterruptedRunRepairEvidenceKind.RUNTIME_PROJECTION_WORK,
        InterruptedRunRepairEvidenceKind.RUNTIME_PROJECTION_WORK,
      ),
      evidence.map(InterruptedRunRepairEvidence::kind),
    )
    assertEquals(
      listOf("session-projection", "session-projection-managed"),
      evidence.map(InterruptedRunRepairEvidence::sessionId),
    )
    evidence.forEach { item ->
      assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
      assertEquals(RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS, item.runtimeExecutionOwnershipTier)
      assertEquals("durable-controller-projection", item.durableRuntimeControllerId)
    }
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceRestoresProjectionManagedProcessReconnectEvidence() {
    val root = temporaryFolder.newFolder(
      "scheduled-task-repair-evidence-runtime-projection-reconnect",
    )
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val sessionId = "session-projection-reconnect"

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          activeSessionIds = listOf(sessionId),
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = mapOf(
              sessionId to listOf(
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
                  target = RuntimeServiceTarget.DETACHED_BACKGROUND,
                  runId = "run-projection-reconnect",
                  taskId = "task-projection-reconnect",
                  detailId = "process-projection-reconnect",
                  repairAfterEpochMs = 2_500L,
                  managedProcessReconnectStatus = "connecting",
                  managedProcessReconnectRecoveryState = "retry_scheduled",
                  managedProcessReconnectAttemptCount = 4,
                  managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
                  managedProcessRestoreScope = ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
                  managedProcessRestoreDecision =
                    ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
                ),
              ),
            ),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
    )

    assertEquals(1, evidence.size)
    val item = evidence.single()
    assertEquals(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT, item.kind)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
    assertEquals(sessionId, item.sessionId)
    assertEquals("run-projection-reconnect", item.runId)
    assertEquals("task-projection-reconnect", item.taskId)
    assertEquals("process-projection-reconnect", item.detailId)
    assertEquals(2_500L, item.repairAfterEpochMs)
    assertEquals("connecting", item.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", item.managedProcessReconnectRecoveryState)
    assertEquals(4, item.managedProcessReconnectAttemptCount)
    assertEquals(
      RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
      item.runtimeExecutionOwnershipTier,
    )
    assertEquals("durable-controller-projection", item.durableRuntimeControllerId)
    assertEquals(
      ManagedProcessContinuationBases.RECONNECT_HOLD,
      item.managedProcessContinuationBasis,
    )
    assertEquals(ManagedProcessRestoreScope.CROSS_PROCESS.wireValue, item.managedProcessRestoreScope)
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      item.managedProcessRestoreDecision,
    )
    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(
      500L,
      nextInterruptedRunRepairDelayMs(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceMergesProjectionReconnectIntoDurableRunRecord() {
    val root = temporaryFolder.newFolder(
      "scheduled-task-repair-evidence-runtime-projection-run-record",
    )
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()
    val sessionId = chatSessionStore.loadState().activeSession.sessionId

    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-projection-reconnect-record",
        taskId = "task-projection-reconnect-record",
        acceptedAtEpochMs = 1_000L,
        lastEvent = OpenCrayAssistantEvent(
          runId = "run-projection-reconnect-record",
          taskId = "task-projection-reconnect-record",
          turn = 0,
          text = "Waiting for projected process reconnect.",
          isFinal = false,
          emittedAtEpochMs = 1_100L,
        ).toPersistedRecord(),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          activeSessionIds = listOf(sessionId),
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = mapOf(
              sessionId to listOf(
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
                  target = RuntimeServiceTarget.DETACHED_BACKGROUND,
                  runId = "run-projection-reconnect-record",
                  taskId = "task-projection-reconnect-record",
                  detailId = "process-projection-reconnect-record",
                  repairAfterEpochMs = 2_500L,
                  managedProcessReconnectStatus = "connecting",
                  managedProcessReconnectRecoveryState = "retry_scheduled",
                  managedProcessReconnectAttemptCount = 6,
                  runtimeExecutionOwnershipTier = RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
                  durableRuntimeControllerId = "durable-controller-projection",
                  managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
                  managedProcessRestoreScope = ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
                  managedProcessRestoreDecision =
                    ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
                ),
              ),
            ),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
    )

    assertEquals(
      listOf(
        InterruptedRunRepairEvidenceKind.RUN_RECORD,
        InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
      ),
      evidence.map(InterruptedRunRepairEvidence::kind),
    )
    val runRecordEvidence = evidence
      .first { item -> item.kind == InterruptedRunRepairEvidenceKind.RUN_RECORD }
    val reconnectEvidence = evidence
      .first { item -> item.kind == InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT }
    listOf(runRecordEvidence, reconnectEvidence).forEach { item ->
      assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
      assertEquals("run-projection-reconnect-record", item.runId)
      assertEquals("task-projection-reconnect-record", item.taskId)
      assertEquals(2_500L, item.repairAfterEpochMs)
      assertEquals("connecting", item.managedProcessReconnectStatus)
      assertEquals("retry_scheduled", item.managedProcessReconnectRecoveryState)
      assertEquals(6, item.managedProcessReconnectAttemptCount)
      assertEquals(RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS, item.runtimeExecutionOwnershipTier)
      assertEquals("durable-controller-projection", item.durableRuntimeControllerId)
      assertEquals(ManagedProcessContinuationBases.RECONNECT_HOLD, item.managedProcessContinuationBasis)
      assertEquals(ManagedProcessRestoreScope.CROSS_PROCESS.wireValue, item.managedProcessRestoreScope)
      assertEquals(ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue, item.managedProcessRestoreDecision)
    }
    assertEquals("process-projection-reconnect-record", reconnectEvidence.detailId)
    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(
      ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
      nextInterruptedRunRepairRetry(
        evidence = evidence,
        nowEpochMs = 2_000L,
      )?.repairReason,
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidencePrefersDurableEvidenceOverRuntimeProjectionWork() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-runtime-projection-durable")
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
          taskId = "task-projection-durable",
          runId = "run-projection-durable",
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          activeSessionIds = listOf(sessionId),
        ),
      ),
    )

    assertEquals(listOf(InterruptedRunRepairEvidenceKind.QUEUE_TASK), evidence.map { it.kind })
    assertEquals(sessionId, evidence.single().sessionId)
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceSuppressesProjectedEvidenceForTerminalQueueTask() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-terminal-projection")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val runId = "run-terminal-projection"
    val taskId = "task-terminal-projection"

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = taskId,
          runId = runId,
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
        ),
      ),
    )
    val projectedKinds = listOf(
      InterruptedRunRepairEvidenceKind.QUEUE_TASK,
      InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
      InterruptedRunRepairEvidenceKind.RUN_RECORD,
      InterruptedRunRepairEvidenceKind.JOURNAL_TAIL,
      InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          activeSessionIds = listOf(sessionId),
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = mapOf(
              sessionId to projectedKinds.map { kind ->
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = kind,
                  target = RuntimeServiceTarget.DETACHED_BACKGROUND,
                  runId = runId,
                  taskId = taskId,
                  detailId = when (kind) {
                    InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT -> "checkpoint-terminal"
                    InterruptedRunRepairEvidenceKind.JOURNAL_TAIL -> "journal-terminal"
                    InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT -> "process-terminal"
                    else -> null
                  },
                )
              },
            ),
            recordedAtEpochMs = 1_200L,
          ),
        ),
      ),
    )

    assertEquals(emptyList<InterruptedRunRepairEvidence>(), evidence)
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
  fun potentialInterruptedRunRepairEvidenceClassifiesScheduledQueueTask() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-scheduled")
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
          taskId = "task-scheduled-evidence",
          runId = "run-scheduled-evidence",
          lifecycleState = QueueTaskLifecycleState.QUEUED,
          taskState = AgentTaskState.QUEUED,
          metadata = mapOf(
            ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-evidence",
            RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID to "durable-controller-evidence",
          ),
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    )

    assertEquals(1, evidence.size)
    val item = evidence.single()
    assertEquals(sessionId, item.sessionId)
    assertEquals(InterruptedRunRepairEvidenceKind.QUEUE_TASK, item.kind)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
    assertEquals("run-scheduled-evidence", item.runId)
    assertEquals("task-scheduled-evidence", item.taskId)
    assertEquals("runtime_process", item.runtimeExecutionOwnershipTier)
    assertEquals("durable-controller-evidence", item.durableRuntimeControllerId)
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceClassifiesQueueStoredManagedProcessReconnectHold() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-queue-reconnect")
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
          taskId = "task-reconnect-hold",
          runId = "run-reconnect-hold",
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          taskState = AgentTaskState.SUSPENDED,
          metadata = mapOf(
            ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-reconnect-hold",
            RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_reconnect_process",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to
              "process-from-queue",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE to
              "retry_scheduled",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS to
              "connecting",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to
              "2500",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT to
              "3",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS to
              ManagedProcessContinuationBases.RECONNECT_HOLD,
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE to
              ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION to
              ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
            RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID to
              "durable-controller-reconnect-hold",
          ),
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    )

    assertEquals(1, evidence.size)
    val item = evidence.single()
    assertEquals(sessionId, item.sessionId)
    assertEquals(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT, item.kind)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
    assertEquals("run-reconnect-hold", item.runId)
    assertEquals("task-reconnect-hold", item.taskId)
    assertEquals("process-from-queue", item.detailId)
    assertEquals(2_500L, item.repairAfterEpochMs)
    assertEquals("runtime_process", item.runtimeExecutionOwnershipTier)
    assertEquals("durable-controller-reconnect-hold", item.durableRuntimeControllerId)
    assertEquals("connecting", item.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", item.managedProcessReconnectRecoveryState)
    assertEquals(3, item.managedProcessReconnectAttemptCount)
    assertEquals(ManagedProcessContinuationBases.RECONNECT_HOLD, item.managedProcessContinuationBasis)
    assertEquals(ManagedProcessRestoreScope.CROSS_PROCESS.wireValue, item.managedProcessRestoreScope)
    assertEquals(ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue, item.managedProcessRestoreDecision)
    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(
      500L,
      nextInterruptedRunRepairDelayMs(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceDefersMatchingRunRecordDuringReconnectBackoff() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-reconnect-run-record")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-reconnect-record",
          runId = "run-reconnect-record",
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          taskState = AgentTaskState.SUSPENDED,
          metadata = mapOf(
            ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-reconnect-record",
            RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_reconnect_process",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to
              "process-reconnect-record",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS to
              "connecting",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE to
              "retry_scheduled",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to
              "2500",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT to
              "3",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS to
              ManagedProcessContinuationBases.RECONNECT_HOLD,
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE to
              ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION to
              ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
            RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID to
              "durable-controller-reconnect-record",
          ),
        ),
      ),
    )
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-reconnect-record",
        taskId = "task-reconnect-record",
        acceptedAtEpochMs = 1_000L,
        lastEvent = OpenCrayAssistantEvent(
          runId = "run-reconnect-record",
          taskId = "task-reconnect-record",
          turn = 0,
          text = "Waiting for process reconnect.",
          isFinal = false,
          emittedAtEpochMs = 1_100L,
        ).toPersistedRecord(),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
    )

    assertEquals(2, evidence.size)
    assertEquals(
      listOf(
        InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
        InterruptedRunRepairEvidenceKind.RUN_RECORD,
      ),
      evidence.map(InterruptedRunRepairEvidence::kind),
    )
    evidence.forEach { item ->
      assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
      assertEquals("run-reconnect-record", item.runId)
      assertEquals("task-reconnect-record", item.taskId)
      assertEquals(2_500L, item.repairAfterEpochMs)
    }
    val reconnectEvidence = evidence
      .first { item -> item.kind == InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT }
    val runRecordEvidence = evidence
      .first { item -> item.kind == InterruptedRunRepairEvidenceKind.RUN_RECORD }
    listOf(reconnectEvidence, runRecordEvidence).forEach { item ->
      assertEquals("connecting", item.managedProcessReconnectStatus)
      assertEquals("retry_scheduled", item.managedProcessReconnectRecoveryState)
      assertEquals(3, item.managedProcessReconnectAttemptCount)
      assertEquals("runtime_process", item.runtimeExecutionOwnershipTier)
      assertEquals("durable-controller-reconnect-record", item.durableRuntimeControllerId)
      assertEquals(ManagedProcessContinuationBases.RECONNECT_HOLD, item.managedProcessContinuationBasis)
      assertEquals(ManagedProcessRestoreScope.CROSS_PROCESS.wireValue, item.managedProcessRestoreScope)
      assertEquals(ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue, item.managedProcessRestoreDecision)
    }
    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(
      500L,
      nextInterruptedRunRepairDelayMs(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceClassifiesManagedProcessReconnectAndRoutesTaskTarget() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-managed-process")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val processRegistryFactory = FixedAgentProcessRegistryFactory(
      sessionId to listOf(
        reconnectingManagedProcessSnapshot(
          processId = "process-reconnect",
          taskId = "task-managed-reconnect",
          metadata = mapOf(
            MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY to
              ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
            MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY to
              ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
            "sandboxCommandReconnectAttemptCount" to "2",
          ),
        ),
      ),
    )

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-managed-reconnect",
          runId = "run-managed-reconnect",
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
          metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-managed"),
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      processRegistryFactory = processRegistryFactory,
    )

    assertEquals(1, evidence.size)
    val item = evidence.single()
    assertEquals(sessionId, item.sessionId)
    assertEquals(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT, item.kind)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
    assertEquals("run-managed-reconnect", item.runId)
    assertEquals("task-managed-reconnect", item.taskId)
    assertEquals("process-reconnect", item.detailId)
    assertEquals("connecting", item.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", item.managedProcessReconnectRecoveryState)
    assertEquals(2, item.managedProcessReconnectAttemptCount)
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      item.managedProcessRestoreScope,
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      item.managedProcessRestoreDecision,
    )
  }

  @Test
  fun potentialInterruptedRunRepairEvidenceIgnoresManagedProcessWhenMetadataShowsAttached() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-evidence-managed-process-attached")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val processRegistryFactory = FixedAgentProcessRegistryFactory(
      sessionId to listOf(
        reconnectingManagedProcessSnapshot(
          processId = "process-attached",
          taskId = "task-managed-attached",
          metadata = mapOf(
            "sandboxCommandReconnectStatus" to "attached",
            "sandboxCommandReconnectRecoveryState" to "attached_live",
            "sandboxCommandReconnectRetryable" to "false",
            "sandboxCommandReconnectAttemptCount" to "2",
          ),
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
      subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory(),
      processRegistryFactory = processRegistryFactory,
    )

    assertTrue(evidence.none { item ->
      item.kind == InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT &&
        item.detailId == "process-attached"
    })
  }

  @Test
  fun dueInterruptedRunRepairTargetsDefersFutureManagedProcessReconnectUntilRetryAfter() {
    val evidence = listOf(
      InterruptedRunRepairEvidence(
        sessionId = "session-managed-delay",
        kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        runId = "run-managed-delay",
        taskId = "task-managed-delay",
        detailId = "process-managed-delay",
        repairAfterEpochMs = 2_500L,
      ),
    )

    assertEquals(
      emptySet<RuntimeServiceTarget>(),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(
      500L,
      nextInterruptedRunRepairDelayMs(
        evidence = evidence,
        nowEpochMs = 2_000L,
      ),
    )
  }

  @Test
  fun dueInterruptedRunRepairTargetsRoutesManagedProcessReconnectWhenRetryAfterDue() {
    val evidence = listOf(
      InterruptedRunRepairEvidence(
        sessionId = "session-managed-due",
        kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        runId = "run-managed-due",
        taskId = "task-managed-due",
        detailId = "process-managed-due",
        repairAfterEpochMs = 2_500L,
      ),
    )

    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      dueInterruptedRunRepairTargets(
        evidence = evidence,
        nowEpochMs = 2_500L,
      ),
    )
    assertEquals(
      null,
      nextInterruptedRunRepairDelayMs(
        evidence = evidence,
        nowEpochMs = 2_500L,
      ),
    )
  }

  @Test
  fun dueRuntimeServiceProjectionRepairTargetsDefersFutureProjectionDeadline() {
    val targets = dueRuntimeServiceProjectionRepairTargets(
      snapshotsByTarget = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertEquals(emptySet<RuntimeServiceTarget>(), targets)
  }

  @Test
  fun dueInterruptedRunRepairTargetsUsesDueProjectionDeadlineWithoutEvidence() {
    val targets = dueInterruptedRunRepairTargets(
      evidence = emptyList(),
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_500L,
    )

    assertEquals(setOf(RuntimeServiceTarget.DETACHED_BACKGROUND), targets)
  }

  @Test
  fun dueInterruptedRunRepairTargetsMergesEvidenceAndProjectionTargets() {
    val targets = dueInterruptedRunRepairTargets(
      evidence = listOf(
        InterruptedRunRepairEvidence(
          sessionId = "session-interactive",
          kind = InterruptedRunRepairEvidenceKind.RUN_RECORD,
          target = RuntimeServiceTarget.INTERACTIVE,
        ),
      ),
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_500L,
    )

    assertEquals(
      setOf(RuntimeServiceTarget.INTERACTIVE, RuntimeServiceTarget.DETACHED_BACKGROUND),
      targets,
    )
  }

  @Test
  fun delayedRepairWorkNameIsPartitionedByReason() {
    assertEquals(
      delayedRepairWorkName(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT),
      delayedRepairWorkName(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT),
    )
    assertFalse(
      delayedRepairWorkName(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT) ==
        delayedRepairWorkName(ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED),
    )
    assertFalse(
      delayedRepairWorkName(ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY) ==
        delayedRepairWorkName(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT),
    )
  }

  @Test
  fun nextInterruptedRunRepairRetryCarriesReasonForEarliestFutureEvidence() {
    val retry = nextInterruptedRunRepairRetry(
      evidence = listOf(
        InterruptedRunRepairEvidence(
          sessionId = "session-generic",
          kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
          target = RuntimeServiceTarget.INTERACTIVE,
          repairAfterEpochMs = 2_750L,
        ),
        InterruptedRunRepairEvidence(
          sessionId = "session-managed",
          kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
          target = RuntimeServiceTarget.INTERACTIVE,
          repairAfterEpochMs = 2_500L,
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertEquals(2_500L, retry?.repairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, retry?.repairReason)
  }

  @Test
  fun nextInterruptedRunRepairRetryUsesGenericReasonForNonManagedEvidence() {
    val retry = nextInterruptedRunRepairRetry(
      evidence = listOf(
        InterruptedRunRepairEvidence(
          sessionId = "session-generic",
          kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
          target = RuntimeServiceTarget.INTERACTIVE,
          repairAfterEpochMs = 2_500L,
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertEquals(2_500L, retry?.repairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY, retry?.repairReason)
  }

  @Test
  fun nextRuntimeServiceProjectionRepairRetryUsesProjectionDeadlineWithoutEvidence() {
    val retry = nextRuntimeServiceProjectionRepairRetry(
      snapshotsByTarget = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertEquals(2_500L, retry?.repairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, retry?.repairReason)
  }

  @Test
  fun scheduleNextInterruptedRunRepairRetryEnqueuesRequestedRepairReason() {
    val workScheduler = RecordingScheduledWorkScheduler()

    val scheduled = scheduleNextInterruptedRunRepairRetry(
      workScheduler = workScheduler,
      nextRepairAfterEpochMs = 2_500L,
      repairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
      nowEpochMs = 2_000L,
    )

    assertTrue(scheduled)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT to 500L),
      workScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextInterruptedRunRepairRetryEnqueuesEvidenceDerivedRepairReason() {
    val genericScheduler = RecordingScheduledWorkScheduler()
    val managedScheduler = RecordingScheduledWorkScheduler()

    assertTrue(
      scheduleNextInterruptedRunRepairRetry(
        workScheduler = genericScheduler,
        evidence = listOf(
          InterruptedRunRepairEvidence(
            sessionId = "session-generic",
            kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
            target = RuntimeServiceTarget.INTERACTIVE,
            repairAfterEpochMs = 2_500L,
          ),
        ),
        nowEpochMs = 2_000L,
      ),
    )
    assertTrue(
      scheduleNextInterruptedRunRepairRetry(
        workScheduler = managedScheduler,
        evidence = listOf(
          InterruptedRunRepairEvidence(
            sessionId = "session-managed",
            kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
            target = RuntimeServiceTarget.DETACHED_BACKGROUND,
            repairAfterEpochMs = 2_750L,
          ),
          InterruptedRunRepairEvidence(
            sessionId = "session-generic-later",
            kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
            target = RuntimeServiceTarget.INTERACTIVE,
            repairAfterEpochMs = 3_000L,
          ),
        ),
        nowEpochMs = 2_000L,
      ),
    )

    assertEquals(
      listOf(ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY to 500L),
      genericScheduler.repairRequests,
    )
    assertEquals(
      listOf(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT to 750L),
      managedScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextInterruptedRunRepairRetryEnqueuesProjectionDeadlineWhenEvidenceIsMissing() {
    val workScheduler = RecordingScheduledWorkScheduler()

    val scheduled = scheduleNextInterruptedRunRepairRetry(
      workScheduler = workScheduler,
      evidence = emptyList(),
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 2_500L,
            nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(scheduled)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT to 500L),
      workScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextInterruptedRunRepairRetryKeepsEarlierEvidenceDeadline() {
    val workScheduler = RecordingScheduledWorkScheduler()

    val scheduled = scheduleNextInterruptedRunRepairRetry(
      workScheduler = workScheduler,
      evidence = listOf(
        InterruptedRunRepairEvidence(
          sessionId = "session-managed",
          kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
          repairAfterEpochMs = 2_500L,
        ),
      ),
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.DETACHED_BACKGROUND to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = emptyMap(),
            nextRepairAfterEpochMs = 3_000L,
            nextRepairReason = ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY,
            recordedAtEpochMs = 2_000L,
          ),
        ),
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(scheduled)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT to 500L),
      workScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextInterruptedRunRepairRetryIgnoresMissingOrDueDeadline() {
    val workScheduler = RecordingScheduledWorkScheduler()

    assertFalse(
      scheduleNextInterruptedRunRepairRetry(
        workScheduler = workScheduler,
        nextRepairAfterEpochMs = null,
        nowEpochMs = 2_000L,
      ),
    )
    assertFalse(
      scheduleNextInterruptedRunRepairRetry(
        workScheduler = workScheduler,
        nextRepairAfterEpochMs = 2_000L,
        nowEpochMs = 2_000L,
      ),
    )
    assertEquals(emptyList<Pair<String, Long>>(), workScheduler.repairRequests)
  }

  @Test
  fun scheduleRuntimeOwnerLeaseExpiryRepairEnqueuesRepairAtHeldLeaseExpiry() {
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val workScheduler = RecordingScheduledWorkScheduler()
    ownerLeaseStore.save(
      runtimeServiceOwnerLease(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
          processStartId = "process-lease",
          processStartedAtEpochMs = 1_000L,
          controllerInstanceId = "controller-lease",
          durableControllerId = "durable-controller-lease",
          controllerCreatedAtEpochMs = 1_200L,
        ),
        runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
          processStartId = "process-lease",
          processStartedAtEpochMs = 1_000L,
          hostInstanceId = "host-lease",
          runtimeOwnerId = "owner-lease",
          runtimeControllerId = "controller-lease",
          hostCreatedAtEpochMs = 1_200L,
          durableRuntimeControllerId = "durable-controller-lease",
        ),
        serviceLifecycle = RuntimeServiceLifecycleDescriptor(
          processStartId = "process-lease",
          processStartedAtEpochMs = 1_000L,
          serviceInstanceId = "service-lease",
          serviceCreatedAtEpochMs = 1_300L,
        ),
        acquiredAtEpochMs = 2_000L,
        heartbeatAtEpochMs = 2_500L,
        leaseDurationMs = 5_000L,
      ),
    )

    val scheduled = scheduleRuntimeOwnerLeaseExpiryRepair(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      nowEpochMs = 4_000L,
      ownerLeaseStore = ownerLeaseStore,
      workScheduler = workScheduler,
    )

    assertTrue(scheduled)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED to 3_500L),
      workScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextRuntimeOwnerLeaseExpiryRepairEnqueuesEarliestFutureHeldLease() {
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val workScheduler = RecordingScheduledWorkScheduler()
    ownerLeaseStore.save(
      heldRuntimeOwnerLease(
        target = RuntimeServiceTarget.INTERACTIVE,
        heartbeatAtEpochMs = 2_000L,
        leaseDurationMs = 7_000L,
      ),
    )
    ownerLeaseStore.save(
      heldRuntimeOwnerLease(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        heartbeatAtEpochMs = 2_500L,
        leaseDurationMs = 5_000L,
      ),
    )

    val scheduled = scheduleNextRuntimeOwnerLeaseExpiryRepair(
      nowEpochMs = 4_000L,
      ownerLeaseStore = ownerLeaseStore,
      workScheduler = workScheduler,
    )

    assertTrue(scheduled)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED to 3_500L),
      workScheduler.repairRequests,
    )
  }

  @Test
  fun scheduleNextRuntimeOwnerLeaseExpiryRepairIgnoresExpiredAndReleasedLeases() {
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val workScheduler = RecordingScheduledWorkScheduler()
    ownerLeaseStore.save(
      heldRuntimeOwnerLease(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        heartbeatAtEpochMs = 1_000L,
        leaseDurationMs = 1_000L,
      ),
    )
    val releasedLease = heldRuntimeOwnerLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      heartbeatAtEpochMs = 2_500L,
      leaseDurationMs = 5_000L,
    )
    ownerLeaseStore.save(releasedLease)
    ownerLeaseStore.release(releasedLease.released(3_000L))

    val scheduled = scheduleNextRuntimeOwnerLeaseExpiryRepair(
      nowEpochMs = 4_000L,
      ownerLeaseStore = ownerLeaseStore,
      workScheduler = workScheduler,
    )

    assertFalse(scheduled)
    assertEquals(emptyList<Pair<String, Long>>(), workScheduler.repairRequests)
  }

  @Test
  fun dueRuntimeOwnerLeaseExpiryRepairTargetsRoutesExpiredHeldLease() {
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    ownerLeaseStore.save(
      heldRuntimeOwnerLease(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        heartbeatAtEpochMs = 2_000L,
        leaseDurationMs = 2_000L,
      ),
    )
    ownerLeaseStore.save(
      heldRuntimeOwnerLease(
        target = RuntimeServiceTarget.INTERACTIVE,
        heartbeatAtEpochMs = 3_000L,
        leaseDurationMs = 5_000L,
      ),
    )

    val targets = dueRuntimeOwnerLeaseExpiryRepairTargets(
      targets = RuntimeServiceTarget.entries,
      ownerLeaseStore = ownerLeaseStore,
      nowEpochMs = 4_000L,
    )

    assertEquals(setOf(RuntimeServiceTarget.DETACHED_BACKGROUND), targets)
  }

  @Test
  fun dueRuntimeOwnerLeaseExpiryRepairTargetsIgnoresReleasedLease() {
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val releasedLease = heldRuntimeOwnerLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      heartbeatAtEpochMs = 2_000L,
      leaseDurationMs = 2_000L,
    )
    ownerLeaseStore.save(releasedLease)
    ownerLeaseStore.release(releasedLease.released(3_000L))

    val targets = dueRuntimeOwnerLeaseExpiryRepairTargets(
      targets = RuntimeServiceTarget.entries,
      ownerLeaseStore = ownerLeaseStore,
      nowEpochMs = 4_000L,
    )

    assertEquals(emptySet<RuntimeServiceTarget>(), targets)
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
            METADATA_SYNTHETIC_SUBAGENT_TASK_KIND to SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
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
  fun potentialInterruptedRunRepairEvidenceIgnoresBudgetExhaustedCheckpointUntilRetry() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-checkpoint-budget")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()
    val taskId = "task-checkpoint-budget"
    val checkpoint = PersistedPromptCheckpoint(
      sessionId = sessionId,
      runId = "run-checkpoint-budget",
      taskId = taskId,
      checkpointId = "checkpoint-budget",
      checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = taskId,
          runId = checkpoint.runId,
          lifecycleState = QueueTaskLifecycleState.FAILED,
          taskState = AgentTaskState.FAILED,
          metadata = mapOf(
            METADATA_RECOVERY_REASON to
              RUN_RECOVERY_REASON_AUTOMATIC_CHECKPOINT_RESUME_BUDGET_EXHAUSTED,
            RunLifecycleMetadataKeys.CHECKPOINT_RESUME_ATTEMPT_COUNT to
              DEFAULT_MAX_AUTOMATIC_CHECKPOINT_RESUME_ATTEMPTS.toString(),
          ),
        ),
      ),
    )
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    checkpointStore.upsert(checkpoint)
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = checkpoint.runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        lastEvent = OpenCrayAssistantEvent(
          runId = checkpoint.runId,
          taskId = taskId,
          turn = 0,
          text = "Waiting before the recovery budget was exhausted.",
          isFinal = false,
          emittedAtEpochMs = 1_100L,
        ).toPersistedRecord(),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runtimeServiceProjectionSnapshots = mapOf(
        RuntimeServiceTarget.INTERACTIVE to runtimeProjectionSnapshot(
          lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
            repairEvidenceBySession = mapOf(
              sessionId to listOf(
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
                  target = RuntimeServiceTarget.INTERACTIVE,
                  runId = checkpoint.runId,
                  taskId = taskId,
                  detailId = checkpoint.checkpointId,
                ),
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = InterruptedRunRepairEvidenceKind.RUN_RECORD,
                  target = RuntimeServiceTarget.INTERACTIVE,
                  runId = checkpoint.runId,
                  taskId = taskId,
                ),
                InterruptedRunRepairEvidence(
                  sessionId = sessionId,
                  kind = InterruptedRunRepairEvidenceKind.JOURNAL_TAIL,
                  target = RuntimeServiceTarget.INTERACTIVE,
                  runId = checkpoint.runId,
                  taskId = taskId,
                  detailId = "journal-tail-before-budget-exhaustion",
                ),
              ),
            ),
            recordedAtEpochMs = 1_200L,
          ),
        ),
      ),
    )

    assertEquals(emptyList<InterruptedRunRepairEvidence>(), evidence)
    assertEquals(checkpoint.checkpointId, checkpointStore.get(taskId)?.checkpointId)

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = taskId,
          runId = checkpoint.runId,
          lifecycleState = QueueTaskLifecycleState.QUEUED,
          taskState = AgentTaskState.QUEUED,
          metadata = mapOf(
            METADATA_RECOVERY_REASON to
              RUN_RECOVERY_REASON_AUTOMATIC_CHECKPOINT_RESUME_BUDGET_EXHAUSTED,
          ),
        ),
      ),
    )

    val retriedEvidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
    )

    assertEquals(
      setOf(
        InterruptedRunRepairEvidenceKind.QUEUE_TASK,
        InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
        InterruptedRunRepairEvidenceKind.RUN_RECORD,
      ),
      retriedEvidence.mapTo(mutableSetOf(), InterruptedRunRepairEvidence::kind),
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
  fun hasPotentialInteractiveRunRepairWorkIgnoresFinalizationCheckpointOnlySession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-final-checkpoint")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val finalizationSessionId = "session-finalization-checkpoint"

    promptCheckpointStoreFactory.forChatSession(finalizationSessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = finalizationSessionId,
        runId = "run-finalization-checkpoint",
        taskId = "task-finalization-checkpoint",
        checkpointId = "checkpoint-finalization",
        checkpointKind = PromptCheckpointKind.FINALIZATION_COMPLETE,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    assertFalse(
      hasPotentialInteractiveRunRepairWork(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      ),
    )
    assertEquals(
      emptyList<InterruptedRunRepairEvidence>(),
      potentialInterruptedRunRepairEvidence(
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
  fun hasPotentialInteractiveRunRepairWorkFindsDurableCheckpointForChatTrackedSession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-durable-only")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val sessionId = chatSessionStore.loadState().activeSession.sessionId

    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
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
    val durableOnlySessionId = chatSessionStore.loadState().activeSession.sessionId

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
    val durableOnlySessionId = chatSessionStore.loadState().activeSession.sessionId

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
  fun potentialInterruptedRunRepairEvidenceRoutesJournalTailThroughMatchingScheduledTask() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-journal-target")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-journal-scheduled",
          runId = "run-journal-scheduled",
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
          metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-journal"),
        ),
      ),
    )
    runEventJournalStoreFactory.forChatSession(sessionId).append(
      OpenCrayAssistantEvent(
        runId = "run-journal-scheduled",
        taskId = "task-journal-scheduled",
        turn = 0,
        text = "Recovered scheduled journal progress.",
        isFinal = false,
        emittedAtEpochMs = 1_100L,
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    assertEquals(1, evidence.size)
    val item = evidence.single()
    assertEquals(InterruptedRunRepairEvidenceKind.JOURNAL_TAIL, item.kind)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, item.target)
    assertEquals("run-journal-scheduled", item.runId)
    assertEquals("task-journal-scheduled", item.taskId)
    assertEquals(
      setOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
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
  fun potentialInterruptedRunRepairEvidenceUsesRecoveryAwareQueueRestoreForTerminalResult() {
    val root = temporaryFolder.newFolder("scheduled-task-repair-terminal-queue")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runRecordStoreFactory = InMemoryAgentRunRecordStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()

    snapshotStoreFactory.forChatSession(sessionId).save(
      queueSnapshot(
        sessionId = sessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = sessionId,
          taskId = "task-terminal-queue",
          runId = "run-terminal-queue",
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
        ),
      ),
    )
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-terminal-queue",
        taskId = "task-terminal-queue",
        acceptedAtEpochMs = 1_000L,
        lastResult = ExecutionResult(
          taskId = "task-terminal-queue",
          status = ExecutionStatus.SUCCESS,
          stdout = "Done",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_100L,
        ),
      ),
    )

    val evidence = potentialInterruptedRunRepairEvidence(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    assertEquals(emptyList<InterruptedRunRepairEvidence>(), evidence)
  }

  @Test
  fun hasPotentialInteractiveRunRepairWorkIgnoresFinalJournalOnlySession() {
    val root = temporaryFolder.newFolder("scheduled-task-interactive-repair-final-journal")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val snapshotStoreFactory = InMemoryAgentQueueSnapshotStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val sessionId = chatSessionStore.loadState().activeSession.sessionId

    runEventJournalStoreFactory.forChatSession(sessionId).append(
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
    assertEquals(
      emptyList<InterruptedRunRepairEvidence>(),
      potentialInterruptedRunRepairEvidence(
        chatSessionStore = chatSessionStore,
        snapshotStoreFactory = snapshotStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        subAgentHandleStoreFactory = subAgentHandleStoreFactory,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        runtimeServiceProjectionSnapshots = mapOf(
          RuntimeServiceTarget.INTERACTIVE to runtimeProjectionSnapshot(
            activeSessionIds = listOf(sessionId),
            lastInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
              repairEvidenceBySession = mapOf(
                sessionId to listOf(
                  InterruptedRunRepairEvidence(
                    sessionId = sessionId,
                    kind = InterruptedRunRepairEvidenceKind.JOURNAL_TAIL,
                    target = RuntimeServiceTarget.INTERACTIVE,
                    runId = "run-final-journal",
                    taskId = "task-final-journal",
                    detailId = "stale-projected-journal-tail",
                  ),
                ),
              ),
              recordedAtEpochMs = 1_200L,
            ),
          ),
        ),
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

  private fun reconnectingManagedProcessSnapshot(
    processId: String,
    taskId: String,
    retryAfterEpochMs: Long? = null,
    metadata: Map<String, String> = emptyMap(),
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = processId,
    taskId = taskId,
    command = "python",
    args = listOf("-m", "opencray.worker"),
    status = ManagedProcessStatus.RUNNING,
    processStarted = true,
    timeoutMs = 30_000L,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
    reconnectState = ManagedProcessReconnectState(
      status = "connecting",
      recoveryState = "retry_scheduled",
      retryable = true,
      retryAfterEpochMs = retryAfterEpochMs,
    ),
    metadata = metadata,
  )

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

  private fun runtimeProjectionSnapshot(
    activeSessionIds: List<String> = emptyList(),
    pendingWorkSessionIds: List<String> = emptyList(),
    liveManagedProcessSessionIds: List<String> = emptyList(),
    liveSubAgentSessionIds: List<String> = emptyList(),
    lastInterruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection? = null,
  ): RuntimeServiceProjectionSnapshot = RuntimeServiceProjectionSnapshot(
    runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
      controllerInstanceId = "controller-projection",
      durableControllerId = "durable-controller-projection",
    ),
    runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      runtimeOwnerId = "runtime-owner-projection",
      runtimeControllerId = "controller-projection",
      durableRuntimeControllerId = "durable-controller-projection",
    ),
    runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = activeSessionIds.size +
        pendingWorkSessionIds.size +
        liveManagedProcessSessionIds.size +
        liveSubAgentSessionIds.size,
      activeRunCount = activeSessionIds.size,
      activeSessionIds = activeSessionIds,
      pendingWorkSessionIds = pendingWorkSessionIds,
      liveManagedProcessSessionIds = liveManagedProcessSessionIds,
      liveSubAgentSessionIds = liveSubAgentSessionIds,
    ),
    serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
    serviceWorkState = RuntimeServiceWorkState(
      hasActiveWork = activeSessionIds.isNotEmpty() || pendingWorkSessionIds.isNotEmpty(),
      activeRunCount = activeSessionIds.size,
      activeSessionCount = activeSessionIds.size,
      pendingWorkSessionCount = pendingWorkSessionIds.size,
      liveManagedProcessSessionCount = liveManagedProcessSessionIds.size,
      liveSubAgentSessionCount = liveSubAgentSessionIds.size,
    ),
    serviceKeepAliveState = RuntimeServiceKeepAliveState(),
    lastInterruptedRunRepair = lastInterruptedRunRepair,
  )

  private fun heldRuntimeOwnerLease(
    target: RuntimeServiceTarget,
    heartbeatAtEpochMs: Long,
    leaseDurationMs: Long,
  ): RuntimeServiceOwnerLease = runtimeServiceOwnerLease(
    target = target,
    runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
      processStartId = "process-${target.wireValue}",
      processStartedAtEpochMs = 1_000L,
      controllerInstanceId = "controller-${target.wireValue}",
      durableControllerId = "durable-controller-${target.wireValue}",
      controllerCreatedAtEpochMs = 1_200L,
    ),
    runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = "process-${target.wireValue}",
      processStartedAtEpochMs = 1_000L,
      hostInstanceId = "host-${target.wireValue}",
      runtimeOwnerId = "owner-${target.wireValue}",
      runtimeControllerId = "controller-${target.wireValue}",
      hostCreatedAtEpochMs = 1_200L,
      durableRuntimeControllerId = "durable-controller-${target.wireValue}",
    ),
    serviceLifecycle = RuntimeServiceLifecycleDescriptor(
      processStartId = "process-${target.wireValue}",
      processStartedAtEpochMs = 1_000L,
      serviceInstanceId = "service-${target.wireValue}",
      serviceCreatedAtEpochMs = 1_300L,
    ),
    acquiredAtEpochMs = 2_000L,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    leaseDurationMs = leaseDurationMs,
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

  private class FixedAgentProcessRegistryFactory(
    vararg entries: Pair<String, List<ManagedProcessSnapshot>>,
  ) : AgentProcessRegistryFactory {
    private val snapshotsBySession = linkedMapOf(*entries)

    override fun forChatSession(sessionId: String): AgentProcessRegistry =
      FixedAgentProcessRegistry(snapshotsBySession[sessionId].orEmpty())

    override fun knownSessionIds(): List<String> = snapshotsBySession.keys.toList()
  }

  private class FixedAgentProcessRegistry(
    private val snapshots: List<ManagedProcessSnapshot>,
  ) : AgentProcessRegistry {
    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
      error("FixedAgentProcessRegistry does not start processes.")

    override fun list(): List<ManagedProcessSnapshot> = snapshots

    override fun read(processId: String): ManagedProcessSnapshot? =
      snapshots.firstOrNull { snapshot -> snapshot.processId == processId }

    override fun wait(
      processId: String,
      timeoutMs: Long,
    ): ManagedProcessSnapshot? = read(processId)

    override fun terminate(processId: String): ManagedProcessSnapshot? = read(processId)

    override fun recordObservationDelivery(
      processId: String,
      deliveredObservationState: ManagedProcessDeliveredObservationState?,
    ) = Unit
  }

  private class RecordingScheduledWorkScheduler : ScheduledWorkScheduler {
    val repairRequests = mutableListOf<Pair<String, Long>>()

    override fun scheduleWake(
      scheduleId: String,
      triggerAtEpochMs: Long,
    ) = Unit

    override fun cancel(scheduleId: String) = Unit

    override fun enqueueRepair(
      reason: String,
      initialDelayMs: Long,
    ) {
      repairRequests += reason to initialDelayMs
    }

    override fun ensurePeriodicRepair() = Unit
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
