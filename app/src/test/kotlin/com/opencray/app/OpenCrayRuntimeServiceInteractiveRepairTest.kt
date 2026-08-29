package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayRuntimeServiceInteractiveRepairTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resumeInterruptedRuntimeServiceRunsResumesOnlySessionsWithActiveRuns() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val recoverableSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    val completedSessionId = chatSessionStore.copySession(recoverableSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val recoverableRun = AgentRunSnapshot(
      sessionId = recoverableSessionId,
      runId = "run-recoverable",
      taskId = "task-recoverable",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )
    val completedRun = AgentRunSnapshot(
      sessionId = completedSessionId,
      runId = "run-completed",
      taskId = "task-completed",
      acceptedAtEpochMs = 2_000L,
      updatedAtEpochMs = 2_100L,
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val recoverableSession = RecordingRuntimeSessionAccess(
      recoverableSessionId,
      runs = listOf(recoverableRun),
    )
    val completedSession = RecordingRuntimeSessionAccess(
      completedSessionId,
      runs = listOf(completedRun),
    )
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          recoverableSessionId to recoverableSession,
          completedSessionId to completedSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
    )

    assertEquals(
      setOf(activeSessionId, recoverableSessionId, completedSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(recoverableSessionId), result.resumedSessionIds)
    assertEquals(listOf(recoverableSessionId), result.repairedSessionIds)
    assertEquals(0, activeSession.resumeCallCount)
    assertEquals(1, recoverableSession.resumeCallCount)
    assertEquals(0, completedSession.resumeCallCount)
    assertEquals(listOf(recoverableSessionId), repairedSessions)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsAlsoResumesSessionsWithLiveSubAgents() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-subagent")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val subAgentSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    val idleSessionId = chatSessionStore.copySession(subAgentSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val subAgentSession = RecordingRuntimeSessionAccess(
      subAgentSessionId,
      runs = emptyList(),
      subAgentHandles = listOf(backgroundSubAgentHandle(agentId = "child-live")),
    )
    val idleSession = RecordingRuntimeSessionAccess(idleSessionId, runs = emptyList())
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          subAgentSessionId to subAgentSession,
          idleSessionId to idleSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
    )

    assertEquals(
      setOf(activeSessionId, subAgentSessionId, idleSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(subAgentSessionId), result.resumedSessionIds)
    assertEquals(emptyList<String>(), result.repairedSessionIds)
    assertEquals(0, activeSession.resumeCallCount)
    assertEquals(1, subAgentSession.resumeCallCount)
    assertEquals(0, idleSession.resumeCallCount)
    assertEquals(emptyList<String>(), repairedSessions)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsAlsoResumesSessionsWithDurablePromptCheckpoint() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-checkpoint")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val checkpointSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    val idleSessionId = chatSessionStore.copySession(checkpointSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)
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

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val checkpointSession = RecordingRuntimeSessionAccess(checkpointSessionId, runs = emptyList())
    val idleSession = RecordingRuntimeSessionAccess(idleSessionId, runs = emptyList())
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          checkpointSessionId to checkpointSession,
          idleSessionId to idleSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    )

    assertEquals(
      setOf(activeSessionId, checkpointSessionId, idleSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(checkpointSessionId), result.resumedSessionIds)
    assertEquals(emptyList<String>(), result.repairedSessionIds)
    assertEquals(0, activeSession.resumeCallCount)
    assertEquals(1, checkpointSession.resumeCallCount)
    assertEquals(0, idleSession.resumeCallCount)
    assertEquals(emptyList<String>(), repairedSessions)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsDefersFutureManagedProcessReconnectEvidence() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-reconnect-delay")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val reconnectSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)

    snapshotStoreFactory.forChatSession(reconnectSessionId).save(
      queueSnapshot(
        sessionId = reconnectSessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = reconnectSessionId,
          taskId = "task-reconnect-delay",
          runId = "run-reconnect-delay",
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          taskState = AgentTaskState.SUSPENDED,
          metadata = mapOf(
            RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_reconnect_process",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to
              "process-reconnect-delay",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to
              "2500",
          ),
        ),
      ),
    )

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val reconnectSession = RecordingRuntimeSessionAccess(reconnectSessionId, runs = emptyList())
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          reconnectSessionId to reconnectSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      nowEpochMs = 2_000L,
    )

    assertTrue(reconnectSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, reconnectSession.resumeCallCount)
    assertEquals(2_500L, result.nextRepairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, result.nextRepairReason)
    val evidence = result.repairEvidenceBySession.getValue(reconnectSessionId)
    assertEquals(listOf(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT), evidence.map { it.kind })
    assertEquals(2_500L, evidence.single().repairAfterEpochMs)
  }

  @Test
  fun bootstrapRuntimeServiceSessionsDefersFutureManagedProcessReconnectEvidence() {
    val root = temporaryFolder.newFolder("runtime-service-bootstrap-reconnect-delay")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val reconnectSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot)

    snapshotStoreFactory.forChatSession(reconnectSessionId).save(
      queueSnapshot(
        sessionId = reconnectSessionId,
        taskSnapshot = queueTaskSnapshot(
          sessionId = reconnectSessionId,
          taskId = "task-bootstrap-reconnect-delay",
          runId = "run-bootstrap-reconnect-delay",
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          taskState = AgentTaskState.SUSPENDED,
          metadata = mapOf(
            RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_reconnect_process",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to
              "process-bootstrap-reconnect-delay",
            RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to
              "2500",
          ),
        ),
      ),
    )

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val reconnectSession = RecordingRuntimeSessionAccess(reconnectSessionId, runs = emptyList())
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          reconnectSessionId to reconnectSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    val result = bootstrapRuntimeServiceSessions(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      nowEpochMs = 2_000L,
    )

    assertTrue(reconnectSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, reconnectSession.resumeCallCount)
    assertEquals(2_500L, result.nextRepairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, result.nextRepairReason)
    val evidence = result.repairEvidenceBySession.getValue(reconnectSessionId)
    assertEquals(listOf(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT), evidence.map { it.kind })
    assertEquals(2_500L, evidence.single().repairAfterEpochMs)
  }

  @Test
  fun bootstrapRuntimeServiceSessionsKeepsProjectedReconnectEvidenceWithoutDurableStores() {
    val root = temporaryFolder.newFolder("runtime-service-bootstrap-projected-reconnect")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val projectedSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val projectedSession = RecordingRuntimeSessionAccess(projectedSessionId, runs = emptyList())
    val runtimeAccess = runtimeAccessForSessions(listOf(activeSession, projectedSession))

    val result = bootstrapRuntimeServiceSessions(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      projectedRepairEvidenceBySession = projectedReconnectEvidenceBySession(
        sessionId = projectedSessionId,
        runId = "run-projected-bootstrap-reconnect",
        taskId = "task-projected-bootstrap-reconnect",
        processId = "process-projected-bootstrap-reconnect",
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(projectedSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, projectedSession.resumeCallCount)
    assertEquals(2_500L, result.nextRepairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, result.nextRepairReason)
    val evidence = result.repairEvidenceBySession.getValue(projectedSessionId)
    assertEquals(
      listOf(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT),
      evidence.map { it.kind },
    )
    val reconnectEvidence = evidence.single()
    assertEquals(2_500L, reconnectEvidence.repairAfterEpochMs)
    assertEquals("connecting", reconnectEvidence.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", reconnectEvidence.managedProcessReconnectRecoveryState)
    assertEquals(2, reconnectEvidence.managedProcessReconnectAttemptCount)
    assertEquals(
      RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
      reconnectEvidence.runtimeExecutionOwnershipTier,
    )
    assertEquals("durable-controller-projected-reconnect", reconnectEvidence.durableRuntimeControllerId)
    assertEquals(
      ManagedProcessContinuationBases.RECONNECT_HOLD,
      reconnectEvidence.managedProcessContinuationBasis,
    )
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      reconnectEvidence.managedProcessRestoreScope,
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      reconnectEvidence.managedProcessRestoreDecision,
    )
  }

  @Test
  fun bootstrapRuntimeServiceSessionsOnlyConstructsSessionForEvidenceTarget() {
    val root = temporaryFolder.newFolder("runtime-service-bootstrap-target-owner")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val sessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val session = RecordingRuntimeSessionAccess(sessionId, runs = emptyList())
    val hostAccess = RecordingRuntimeHostAccess(mapOf(sessionId to session))
    val replayAccess = runtimeAccessForSessions(listOf(session)).replayAccess
    val projectedEvidence = projectedReconnectEvidenceBySession(
      sessionId = sessionId,
      runId = "run-detached-owner",
      taskId = "task-detached-owner",
      processId = "process-detached-owner",
    )

    bootstrapRuntimeServiceSessions(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = hostAccess,
      runtimeReplayAccess = replayAccess,
      projectedRepairEvidenceBySession = projectedEvidence,
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      nowEpochMs = 2_000L,
    )
    assertTrue(sessionId !in hostAccess.requestedSessionIds)

    bootstrapRuntimeServiceSessions(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = hostAccess,
      runtimeReplayAccess = replayAccess,
      projectedRepairEvidenceBySession = projectedEvidence,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      nowEpochMs = 2_000L,
    )
    assertEquals(listOf(sessionId), hostAccess.requestedSessionIds)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsDefersRunRecordWithProjectedReconnectBackoff() {
    val root = temporaryFolder.newFolder("runtime-service-repair-projected-reconnect-run-record")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val reconnectSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val runRecord = PersistedAgentRunRecord(
      runId = "run-projected-run-record-reconnect",
      taskId = "task-projected-run-record-reconnect",
      acceptedAtEpochMs = 1_000L,
      managedProcessIds = listOf("process-projected-run-record-reconnect"),
      lastEvent = com.opencray.runtime.OpenCrayAssistantEvent(
        runId = "run-projected-run-record-reconnect",
        taskId = "task-projected-run-record-reconnect",
        turn = 0,
        text = "Waiting for projected reconnect.",
        isFinal = false,
        emittedAtEpochMs = 1_100L,
      ).toPersistedRecord(),
    )
    runRecordStoreFactory.forChatSession(reconnectSessionId).upsert(runRecord)
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val reconnectSession = RecordingRuntimeSessionAccess(reconnectSessionId, runs = emptyList())
    val runtimeAccess = runtimeAccessForSessions(listOf(activeSession, reconnectSession))

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot),
      runRecordStoreFactory = runRecordStoreFactory,
      projectedRepairEvidenceBySession = projectedReconnectEvidenceBySession(
        sessionId = reconnectSessionId,
        runId = runRecord.runId,
        taskId = runRecord.taskId,
        processId = "process-projected-run-record-reconnect",
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(reconnectSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, reconnectSession.resumeCallCount)
    assertEquals(2_500L, result.nextRepairAfterEpochMs)
    assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, result.nextRepairReason)
    val evidence = result.repairEvidenceBySession.getValue(reconnectSessionId)
    assertEquals(
      listOf(
        InterruptedRunRepairEvidenceKind.RUN_RECORD,
        InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
      ),
      evidence.map { it.kind },
    )
    val runRecordEvidence = evidence.first { item ->
      item.kind == InterruptedRunRepairEvidenceKind.RUN_RECORD
    }
    assertEquals(2_500L, runRecordEvidence.repairAfterEpochMs)
    assertEquals("connecting", runRecordEvidence.managedProcessReconnectStatus)
    assertEquals("retry_scheduled", runRecordEvidence.managedProcessReconnectRecoveryState)
    assertEquals(2, runRecordEvidence.managedProcessReconnectAttemptCount)
    assertEquals(
      RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
      runRecordEvidence.runtimeExecutionOwnershipTier,
    )
    assertEquals("durable-controller-projected-reconnect", runRecordEvidence.durableRuntimeControllerId)
    assertEquals(
      ManagedProcessContinuationBases.RECONNECT_HOLD,
      runRecordEvidence.managedProcessContinuationBasis,
    )
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      runRecordEvidence.managedProcessRestoreScope,
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      runRecordEvidence.managedProcessRestoreDecision,
    )
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsIgnoresProjectedReconnectForTerminalRunRecord() {
    val root = temporaryFolder.newFolder("runtime-service-repair-projected-reconnect-terminal")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val terminalSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val terminalRecord = PersistedAgentRunRecord(
      runId = "run-projected-terminal-reconnect",
      taskId = "task-projected-terminal-reconnect",
      acceptedAtEpochMs = 1_000L,
      managedProcessIds = listOf("process-projected-terminal-reconnect"),
      lastResult = ExecutionResult(
        taskId = "task-projected-terminal-reconnect",
        status = ExecutionStatus.SUCCESS,
        stdout = "done",
        stderr = "",
        errorMessage = null,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_500L,
      ),
    )
    runRecordStoreFactory.forChatSession(terminalSessionId).upsert(terminalRecord)
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val terminalSession = RecordingRuntimeSessionAccess(terminalSessionId, runs = emptyList())
    val runtimeAccess = runtimeAccessForSessions(listOf(activeSession, terminalSession))

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot),
      runRecordStoreFactory = runRecordStoreFactory,
      projectedRepairEvidenceBySession = projectedReconnectEvidenceBySession(
        sessionId = terminalSessionId,
        runId = terminalRecord.runId,
        taskId = terminalRecord.taskId,
        processId = "process-projected-terminal-reconnect",
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(terminalSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, terminalSession.resumeCallCount)
    assertEquals(null, result.nextRepairAfterEpochMs)
    assertEquals(null, result.nextRepairReason)
    assertEquals(null, result.repairEvidenceBySession[terminalSessionId])
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsIgnoresProjectedReconnectForFinalJournal() {
    val root = temporaryFolder.newFolder("runtime-service-repair-projected-reconnect-final-journal")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val terminalSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val runId = "run-projected-final-journal"
    val taskId = "task-projected-final-journal"
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    runEventJournalStoreFactory.forChatSession(terminalSessionId).append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 0,
        text = "Done.",
        isFinal = true,
        emittedAtEpochMs = 1_500L,
      ),
    )
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val terminalSession = RecordingRuntimeSessionAccess(terminalSessionId, runs = emptyList())
    val runtimeAccess = runtimeAccessForSessions(listOf(activeSession, terminalSession))

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      projectedRepairEvidenceBySession = projectedReconnectEvidenceBySession(
        sessionId = terminalSessionId,
        runId = runId,
        taskId = taskId,
        processId = "process-projected-final-journal",
      ),
      nowEpochMs = 2_000L,
    )

    assertTrue(terminalSessionId in result.scannedSessionIds)
    assertEquals(emptyList<String>(), result.resumedSessionIds)
    assertEquals(0, terminalSession.resumeCallCount)
    assertEquals(null, result.nextRepairAfterEpochMs)
    assertEquals(null, result.nextRepairReason)
    assertEquals(null, result.repairEvidenceBySession[terminalSessionId])
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsScansDurableRunRecordOnlySessions() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-run-record")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val durableOnlySessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    val runRecord = PersistedAgentRunRecord(
      runId = "run-record-only",
      taskId = "task-record-only",
      acceptedAtEpochMs = 1_000L,
      lastEvent = com.opencray.runtime.OpenCrayAssistantEvent(
        runId = "run-record-only",
        taskId = "task-record-only",
        turn = 0,
        text = "Recovered partial progress.",
        isFinal = false,
        emittedAtEpochMs = 1_100L,
      ).toPersistedRecord(),
    )
    runRecordStoreFactory.forChatSession(durableOnlySessionId).upsert(runRecord)

    val activeSession = RecordingRuntimeSessionAccess(
      activeSessionId,
      runs = emptyList(),
    )
    val durableRun = AgentRunSnapshot(
      sessionId = durableOnlySessionId,
      runId = runRecord.runId,
      taskId = runRecord.taskId,
      acceptedAtEpochMs = runRecord.acceptedAtEpochMs,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
    )
    val durableOnlySession = RecordingRuntimeSessionAccess(
      durableOnlySessionId,
      runs = listOf(durableRun),
    )
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSession.sessionId to activeSession,
          durableOnlySessionId to durableOnlySession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot),
      runRecordStoreFactory = runRecordStoreFactory,
    )

    assertTrue(durableOnlySessionId in result.scannedSessionIds)
    assertEquals(listOf(durableOnlySessionId), result.resumedSessionIds)
    assertEquals(listOf(durableOnlySessionId), result.repairedSessionIds)
    assertEquals(1, durableOnlySession.resumeCallCount)
    assertEquals(listOf(durableOnlySessionId), repairedSessions)
    val evidence = result.repairEvidenceBySession.getValue(durableOnlySessionId)
    assertEquals(listOf(InterruptedRunRepairEvidenceKind.RUN_RECORD), evidence.map { it.kind })
    assertEquals(RuntimeServiceTarget.INTERACTIVE, evidence.single().target)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsScansDurableJournalOnlySessions() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-journal")
    val runtimeRoot = root.resolve("runtime")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val durableOnlySessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)
    runEventJournalStoreFactory.forChatSession(durableOnlySessionId).append(
      com.opencray.runtime.OpenCrayAssistantEvent(
        runId = "run-journal-only",
        taskId = "task-journal-only",
        turn = 0,
        text = "Recovered journal-only progress.",
        isFinal = false,
        emittedAtEpochMs = 1_100L,
      ),
    )

    val activeSession = RecordingRuntimeSessionAccess(
      activeSessionId,
      runs = emptyList(),
    )
    val durableRun = AgentRunSnapshot(
      sessionId = durableOnlySessionId,
      runId = "run-journal-only",
      taskId = "task-journal-only",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
    )
    val durableOnlySession = RecordingRuntimeSessionAccess(
      durableOnlySessionId,
      runs = listOf(durableRun),
    )
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSession.sessionId to activeSession,
          durableOnlySessionId to durableOnlySession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory(runtimeRoot),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    assertTrue(durableOnlySessionId in result.scannedSessionIds)
    assertEquals(listOf(durableOnlySessionId), result.resumedSessionIds)
    assertEquals(listOf(durableOnlySessionId), result.repairedSessionIds)
    assertEquals(1, durableOnlySession.resumeCallCount)
    assertEquals(listOf(durableOnlySessionId), repairedSessions)
    val evidence = result.repairEvidenceBySession.getValue(durableOnlySessionId)
    assertEquals(listOf(InterruptedRunRepairEvidenceKind.JOURNAL_TAIL), evidence.map { it.kind })
    assertEquals(RuntimeServiceTarget.INTERACTIVE, evidence.single().target)
  }

  @Test
  fun resumeInterruptedRuntimeServiceRunsSubmitsRecoveryTaskForQueuedDetachedSubAgent() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-subagent-recovery")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val recoverySessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val recoveryHandle = queuedSubAgentHandle(agentId = "child-resume")
    val recoverySession = RecordingRuntimeSessionAccess(
      recoverySessionId,
      runs = emptyList(),
      subAgentHandles = listOf(recoveryHandle),
    )
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          recoverySessionId to recoverySession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    resumeInterruptedRuntimeServiceRuns(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
    )

    assertEquals(1, recoverySession.submittedTasks.size)
    val recoveryTask = recoverySession.submittedTasks.single()
    assertEquals(AgentTaskType.SYSTEM, recoveryTask.type)
    assertEquals(
      SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
      recoveryTask.metadata[METADATA_SYNTHETIC_SUBAGENT_TASK_KIND],
    )
    assertEquals(
      syntheticSubAgentRecoveryTaskId(
        sessionId = recoverySessionId,
        agentId = "child-resume",
        parentRunId = "parent-run-child-resume",
      ),
      recoveryTask.id,
    )
    assertEquals("child-resume", recoveryTask.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID])
    assertEquals(
      RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      recoveryTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals(0, recoverySession.ensureProcessingCallCount)
  }

  private fun runtimeAccessForSessions(
    sessions: List<RecordingRuntimeSessionAccess>,
    repairedSessions: MutableList<String> = mutableListOf(),
  ): OpenCrayRuntimeOwnerAccess = OpenCrayRuntimeOwnerAccess(
    lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    hostAccess = RecordingRuntimeHostAccess(
      sessions = sessions.associateBy(RecordingRuntimeSessionAccess::sessionId),
    ),
    transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
    memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = InMemoryMemoryStore(),
    ),
    replayAccess = OpenCrayRuntimeReplayAccess(
      approvalRejectionRecorder = { _, _, _, _, _, _ -> },
      approvalApprovedRecorder = { _, _, _, _, _, _ -> },
      subAgentReplayRecorder = { _, _ -> },
      runCancellationRecorder = { _, _, _, _, _ -> },
      terminalReplayRepairer = { sessionId, _ ->
        repairedSessions += sessionId
      },
    ),
  )

  private fun projectedReconnectEvidenceBySession(
    sessionId: String,
    runId: String,
    taskId: String,
    processId: String,
    repairAfterEpochMs: Long = 2_500L,
  ): Map<String, List<InterruptedRunRepairEvidence>> = mapOf(
    sessionId to listOf(
      InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        runId = runId,
        taskId = taskId,
        detailId = processId,
        repairAfterEpochMs = repairAfterEpochMs,
        managedProcessReconnectStatus = "connecting",
        managedProcessReconnectRecoveryState = "retry_scheduled",
        managedProcessReconnectAttemptCount = 2,
        runtimeExecutionOwnershipTier = RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
        durableRuntimeControllerId = "durable-controller-projected-reconnect",
        managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
        managedProcessRestoreScope = ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
        managedProcessRestoreDecision = ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      ),
    ),
  )

  private class RecordingRuntimeHostAccess(
    private val sessions: Map<String, RecordingRuntimeSessionAccess>,
  ) : OpenCrayRuntimeHostAccess {
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStores = linkedMapOf<String, SessionSupplementStore>()
    val requestedSessionIds = mutableListOf<String>()

    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = sessions.size,
      activeRunCount = sessions.values.sumOf { session ->
        session.runs.count(AgentRunSnapshot::isActive)
      },
      activeSessionIds = sessions.values
        .filter { session -> session.runs.any(AgentRunSnapshot::isActive) }
        .map(RecordingRuntimeSessionAccess::sessionId),
    )

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess {
      requestedSessionIds += sessionId
      return requireNotNull(sessions[sessionId]) { "Session '$sessionId' is unavailable." }
    }

    override fun existingSession(sessionId: String): OpenCrayRuntimeSessionAccess? = sessions[sessionId]

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      runEventJournalStoreFactory.forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      promptCheckpointStoreFactory.forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore =
      supplementStores.getOrPut(sessionId) { InMemorySessionSupplementStore() }

    override fun markApprovalApproved(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
      approvedRequestFingerprint: String?,
      commandBatchApproval: CommandBatchApprovalSpec?,
    ) = Unit

    override fun markApprovalRejected(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun clearApproval(sessionId: String, taskId: String) = Unit

    override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) = Unit

    override fun isApprovalApproved(sessionId: String, taskId: String): Boolean = false

    override fun isApprovalRejected(sessionId: String, taskId: String): Boolean = false
  }

  private class RecordingRuntimeSessionAccess(
    override val sessionId: String,
    val runs: List<AgentRunSnapshot>,
    private val subAgentHandles: List<SubAgentHandleState> = emptyList(),
  ) : OpenCrayRuntimeSessionAccess {
    var resumeCallCount: Int = 0
      private set
    val submittedTasks = mutableListOf<AgentTask>()
    var ensureProcessingCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?: "run-${task.id}"
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? =
      runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumeCallCount += 1
      scheduleRecoverableSubAgents()
      return SessionLifecycleState.RUNNING
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.IDLE,
      updatedAtEpochMs = 0L,
      tasks = (
        runs.mapIndexed { index, run ->
          SessionQueueTaskSnapshot(
            enqueueOrder = index.toLong(),
            task = AgentTask(
              id = run.taskId,
              type = com.opencray.core.contracts.AgentTaskType.PROMPT,
              input = "Test",
              state = run.taskState ?: AgentTaskState.QUEUED,
              policyDecision = PolicyDecision(
                outcome = PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = run.acceptedAtEpochMs,
              updatedAtEpochMs = run.updatedAtEpochMs,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to run.runId,
              ),
            ),
            lifecycleState = run.lifecycleState ?: QueueTaskLifecycleState.QUEUED,
          )
        } +
          submittedTasks.mapIndexed { index, task ->
            SessionQueueTaskSnapshot(
              enqueueOrder = (runs.size + index).toLong(),
              task = task,
              lifecycleState = QueueTaskLifecycleState.QUEUED,
            )
          }
        ),
    )

    override fun hasPendingWork(): Boolean = runs.any { run -> !run.isTerminal }

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      emptyList()

    override fun hasLiveManagedProcesses(): Boolean = runs.any(AgentRunSnapshot::hasLiveManagedProcesses)

    override fun terminateRunningManagedProcesses():
      List<com.opencray.runtime.process.ManagedProcessSnapshot> = emptyList()

    override fun listSubAgentHandles(): List<SubAgentHandleState> = subAgentHandles

    override fun submitSubAgentRecoveryTask(
      agentId: String,
      parentRunId: String,
      taskId: String,
      createdAtEpochMs: Long,
      submissionSource: String,
    ): AgentRunSubmission = submitTask(
      syntheticSubAgentRecoveryWaitTask(
        sessionId = sessionId,
        agentId = agentId,
        parentRunId = parentRunId,
        taskId = taskId,
        createdAtEpochMs = createdAtEpochMs,
        metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
          submissionSource = submissionSource,
        ),
      ),
    )

    fun scheduleRecoverableSubAgents(): Int {
      val activeParentRunIds = runs
        .filter(AgentRunSnapshot::isActive)
        .map(AgentRunSnapshot::runId)
        .toSet()
      val pendingRecoveryKeys = submittedTasks.mapNotNull { task ->
        val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        if (agentId == null || parentRunId == null) {
          null
        } else {
          parentRunId to agentId
        }
      }.toSet()
      val resumableHandles = subAgentHandles.filter { handle ->
        handle.snapshot.state == com.opencray.runtime.subagent.SubAgentExecutionState.BACKGROUND_QUEUED &&
          handle.pendingApprovalResume == null &&
          handle.parentRunId !in activeParentRunIds &&
          (handle.parentRunId to handle.agentId) !in pendingRecoveryKeys
      }
      resumableHandles.forEach { handle ->
        val taskId = syntheticSubAgentRecoveryTaskId(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
        submitSubAgentRecoveryTask(
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
          taskId = taskId,
          createdAtEpochMs = 1_500L,
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        )
      }
      return resumableHandles.size
    }
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
      input = "Test reconnect repair.",
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

  private fun backgroundSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundRunning(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  private fun queuedSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadEntries = records.isNotEmpty()
      records.clear()
      return hadEntries
    }
  }
}
