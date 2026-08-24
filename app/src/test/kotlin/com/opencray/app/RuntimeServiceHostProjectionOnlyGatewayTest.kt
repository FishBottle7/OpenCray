package com.opencray.app

import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentSessionLink
import com.opencray.runtime.subagent.SubAgentMailbox
import com.opencray.runtime.subagent.SubAgentMailboxMessage
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostProjectionOnlyGatewayTest : RuntimeServiceHostTestBase() {
  @Test
  fun projectionOnlyShellGatewayObserverPollsProjectionSnapshotUpdates() {
    var projectionSnapshot = bridgeSnapshot(
      temporaryFolder.newFolder("projection-shell-bridge"),
    ).copy(
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
        changedAtEpochMs = 1_000L,
      ),
    ).toProjectionSnapshot()
    val gateway = ProjectionOnlyOpenCrayShellGateway(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      localeTagProvider = { "en" },
      hostLabel = "HOST",
      hostSummary = "summary",
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      projectionSnapshotProvider = { projectionSnapshot },
      mainThreadPoster = ImmediateMainThreadPoster,
      hostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      pollIntervalMs = 25L,
    )
    val observedKeepAlivePhases = mutableListOf<String?>()

    val disposer = gateway.observeShell { snapshot ->
      val keepAliveState = snapshot["runtimeServiceKeepAliveState"] as? Map<*, *>
      observedKeepAlivePhases += keepAliveState?.get("phase") as String?
    }

    try {
      assertEquals(listOf("created"), observedKeepAlivePhases)

      projectionSnapshot = projectionSnapshot.copy(
        serviceKeepAliveState = RuntimeServiceKeepAliveState(
          phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
          idleGraceMs = 30_000L,
          stopScheduled = true,
          stopDeadlineEpochMs = 31_000L,
          changedAtEpochMs = 1_500L,
        ),
      )

      waitForCondition(timeoutMs = 1_000L) {
        observedKeepAlivePhases.any { phase -> phase == RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE }
      }
    } finally {
      disposer()
    }
  }

  @Test
  fun projectionOnlyShellGatewayPrefersProjectedLocalRuntimeServerState() {
    OpenCrayLocalRuntimeServerRegistry.clearForTest()
    try {
      val projectedServerState = LocalRuntimeServerState(
        phase = LocalRuntimeServerState.PHASE_LISTENING,
        bindAddress = "127.0.0.1",
        requestedPort = 42_617,
        listeningPort = 48_200,
        lastStartedAtEpochMs = 1_250L,
        changedAtEpochMs = 1_250L,
      )
      val projectionSnapshot = bridgeSnapshot(
        temporaryFolder.newFolder("projection-shell-projected-local-state"),
      ).copy(
        localRuntimeServerState = projectedServerState,
      ).toProjectionSnapshot()
      val gateway = ProjectionOnlyOpenCrayShellGateway(
        stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
        localeTagProvider = { "en" },
        hostLabel = "HOST",
        hostSummary = "summary",
        connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
        projectionSnapshotProvider = { projectionSnapshot },
        mainThreadPoster = ImmediateMainThreadPoster,
        hostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      )

      val snapshot = gateway.loadShellSnapshot()
      @Suppress("UNCHECKED_CAST")
      val localRuntimeServerState = snapshot["localRuntimeServerState"] as Map<String, Any?>

      assertEquals(LocalRuntimeServerState.PHASE_LISTENING, localRuntimeServerState["phase"])
      assertEquals(48_200, localRuntimeServerState["listeningPort"])
    } finally {
      OpenCrayLocalRuntimeServerRegistry.clearForTest()
    }
  }

  @Test
  fun projectionOnlyShellGatewayDoesNotFallbackToProcessLocalRuntimeServerStateWithoutProjection() {
    OpenCrayLocalRuntimeServerRegistry.clearForTest()
    try {
      val gateway = ProjectionOnlyOpenCrayShellGateway(
        stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
        localeTagProvider = { "en" },
        hostLabel = "HOST",
        hostSummary = "summary",
        connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
        projectionSnapshotProvider = { null },
        mainThreadPoster = ImmediateMainThreadPoster,
        hostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      )

      val snapshot = gateway.loadShellSnapshot()

      assertFalse(snapshot.containsKey("localRuntimeServerState"))
    } finally {
      OpenCrayLocalRuntimeServerRegistry.clearForTest()
    }
  }

  @Test
  fun projectionOnlySkillsGatewayDelegatesQuerySearchToSkillsFacade() {
    val skillsFacade = RecordingProjectionSkillsFacade()
    val gateway = ProjectionOnlyOpenCraySkillsGateway(
      skillsFacade = skillsFacade,
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.loadSkillsSnapshot(query = "voice", suggestedLimit = 8)
    @Suppress("UNCHECKED_CAST")
    val suggestedSkills = payload["suggestedSkills"] as List<Map<String, Any?>>

    assertEquals(listOf("voice"), skillsFacade.loadQueries)
    assertEquals(listOf(8), skillsFacade.loadSuggestedLimits)
    assertEquals(1, suggestedSkills.size)
    assertEquals("voice-notes", suggestedSkills.single()["name"])

    val failure = runCatching {
      gateway.installSkillSource(sourceRef = "voice-notes", selectedSkillName = "")
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.message?.contains("binder-backed runtime service gateway") == true)
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsLocalDurableRunState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "hello from user")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "run-1"
    val taskId = "task-1"
    queueFactory.forChatSession(sessionId).save(
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
              input = "hello from user",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-1",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-1",
      ),
    )
    checkpointFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-run-1",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
        pendingMessageId = "pending-1",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 0,
        ),
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        text = "Inspecting workspace",
        isFinal = false,
        stage = "tool_call",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val projectedServerState = LocalRuntimeServerState(
      phase = LocalRuntimeServerState.PHASE_LISTENING,
      bindAddress = "127.0.0.1",
      requestedPort = 42_617,
      listeningPort = 48_201,
      lastStartedAtEpochMs = 1_240L,
      changedAtEpochMs = 1_240L,
    )
    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = ProjectionOnlyChatStrings(
        localeTag = "en",
        screenTitle = "Chat",
        modeLabel = "AUTO",
        sessionButtonLabel = "Sessions",
        recentSessionsEyebrow = "Recent sessions",
        recentSessionsTitle = "Recent sessions",
        newSessionLabel = "New session",
        defaultSessionTitle = "New chat",
        messagesBadge = { count -> "$count messages" },
        summaryReplyInProgress = "Reply in progress",
        summaryStartNewSession = "Start a new session",
        summaryRestored = "Restored",
        summaryApprovalRequired = "Approval required before the agent can continue.",
        approvalRequiredTitle = "Approval required",
        highRiskApprovalRequiredTitle = "High-risk approval required",
        highRiskApprovalRequiredBody =
          "High-risk approval required. Review this request carefully before approving.",
        approvalApproveLabel = "Approve",
        approvalApproveForSessionLabel = "Allow session",
        approvalRejectLabel = "Reject",
        composerPlaceholder = "Message OpenCray",
        composerRejectedPlaceholder = "Message OpenCray differently",
      ),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      localRuntimeServerStateProvider = { projectedServerState },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val localRuntimeServerState = runtimeSnapshot["localRuntimeServerState"] as Map<String, Any?>
    val runSnapshot = gateway.loadChatRunSnapshot(runId)
    val chatSnapshot = gateway.loadChatSnapshot()
    @Suppress("UNCHECKED_CAST")
    val messages = chatSnapshot["messages"] as List<Map<String, Any?>>

    assertEquals(sessionId, runtimeSnapshot["sessionId"])
    assertEquals(1, activeRuns.size)
    assertEquals(runId, activeRuns.single()["runId"])
    assertEquals("queued", activeRuns.single()["lifecycleState"])
    assertEquals(1, events.size)
    assertEquals("assistant_phase", events.single()["kind"])
    assertEquals("commentary", events.single()["phase"])
    assertEquals("Inspecting workspace", events.single()["text"])
    assertEquals(LocalRuntimeServerState.PHASE_LISTENING, localRuntimeServerState["phase"])
    assertEquals(48_201, localRuntimeServerState["listeningPort"])
    assertNotNull(runSnapshot)
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals("queued", runSnapshot?.get("lifecycleState"))
    assertEquals(1, messages.size)
    assertEquals("hello from user", messages.single()["text"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayDoesNotInterruptRunningManagedProcessFromProjectionRead() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-managed-process-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-managed-process-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "keep the remote dev server alive")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-runtime-owner",
      runtimeControllerId = "controller-runtime-owner",
    )
    val ownerProcessFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = runtimeRoot,
      controllerFactory = ManagedProcessControllerFactory { request ->
        object : ManagedProcessController {
          private val snapshot = ManagedProcessSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            command = request.command,
            args = request.args,
            workingDirectory = request.workingDirectory,
            status = ManagedProcessStatus.RUNNING,
            processStarted = true,
            timeoutMs = request.timeoutMs,
            startedAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            ownerIdentity = request.ownerIdentity,
          )

          override fun snapshot(): ManagedProcessSnapshot = snapshot

          override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot

          override fun terminate(): ManagedProcessSnapshot = snapshot
        }
      },
      runtimeIdentity = ownerIdentity,
    )
    val projectionProcessFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = runtimeRoot,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-ui",
        runtimeControllerId = "controller-ui",
      ),
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    )
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "run-managed-process"
    val taskId = "task-managed-process"
    queueFactory.forChatSession(sessionId).save(
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
              input = "keep the remote dev server alive",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-managed-process",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-managed-process",
      ),
    )
    ownerProcessFactory.forChatSession(sessionId).start(
      ManagedProcessStartRequest(
        processId = "proc-live",
        taskId = taskId,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = projectionProcessFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.bindingPending() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runSnapshot = requireNotNull(gateway.loadChatRunSnapshot(runId))

    assertEquals(runId, runSnapshot["runId"])
    assertEquals(null, runSnapshot["errorCode"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayHidesInternalSubAgentRecoveryRunsFromUserSnapshots() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-hidden-subagent-recovery-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-hidden-subagent-recovery-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "keep runtime projection stable")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "run-hidden-recovery"
    val taskId = "task-hidden-recovery"
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "agent-hidden-recovery",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.TOOL_CALL,
              input = """{"type":"tool_call","tool_name":"wait_agent","arguments":{"targets":["child-run-visible"]}}""",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-hidden",
                RunLifecycleMetadataKeys.SUBMISSION_SOURCE to
                  RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-hidden",
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        text = "Recovering detached child in background",
        isFinal = false,
        stage = "commentary",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val retainedRuns = runtimeSnapshot["retainedRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val chatSnapshot = gateway.loadChatSnapshot()
    val summary = chatSnapshot["summary"] as Map<*, *>

    assertTrue(activeRuns.isEmpty())
    assertTrue(retainedRuns.isEmpty())
    assertTrue(events.isEmpty())
    assertFalse(runtimeSnapshot.containsKey("localRuntimeServerState"))
    assertNull(gateway.loadChatRunSnapshot(runId))
    assertNull(gateway.waitForChatRun(runId, 0))
    assertEquals("Restored", summary["body"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsDurableTerminalRunStateWithoutQueueTask() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-terminal-run-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-terminal-run-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "show durable terminal run state")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "run-terminal-only"
    val taskId = "task-terminal-only"
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        lastResult = ExecutionResult(
          taskId = taskId,
          status = ExecutionStatus.CANCELLED,
          errorCode = "SUBAGENT_RECOVERY_CANCELLED",
          errorMessage = "Detached recovery was cancelled before it resumed.",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_200L,
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runSnapshot = requireNotNull(gateway.loadChatRunSnapshot(runId))

    assertEquals("cancelled", runSnapshot["lifecycleState"])
    assertEquals("cancelled", runSnapshot["taskState"])
    assertEquals("cancelled", runSnapshot["executionStatus"])
    assertEquals("SUBAGENT_RECOVERY_CANCELLED", runSnapshot["errorCode"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsDurableSubAgentHandleState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-subagent-durable-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-subagent-durable-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "track detached child state")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    val parentRunId = "parent-run-durable"
    val parentTaskId = "parent-task-durable"
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_300L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = parentTaskId,
              type = AgentTaskType.PROMPT,
              input = "track detached child state",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_300L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to parentRunId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-durable",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = parentRunId,
        taskId = parentTaskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-durable",
      ),
    )
    subAgentHandleStoreFactory.forChatSession(sessionId).upsert(
      SubAgentHandleState(
        agentId = "child-durable",
        childRunId = "child-run-durable",
        childTaskId = "child-task-durable",
        description = "Inspect runtime snapshot",
        prompt = "Inspect the runtime snapshot pipeline and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = parentRunId,
        parentTaskId = parentTaskId,
        parentTurn = 1,
        depth = 1,
        mailbox = SubAgentMailbox(
          messages = listOf(
            SubAgentMailboxMessage(
              messageId = "mailbox-projection-1",
              text = "Inspect the mailbox state before resuming.",
              createdAtEpochMs = 1_050L,
            ),
            SubAgentMailboxMessage(
              messageId = "mailbox-projection-2",
              text = "Keep the detached state projected into chat UI.",
              createdAtEpochMs = 1_150L,
            ),
          ),
          lastDeliveredMessageId = "mailbox-projection-1",
        ),
        snapshot = SubAgentExecutionSnapshot.backgroundRunning(
          headline = "Delegated child runtime is still running in the background.",
        ),
        createdAtEpochMs = 900L,
        updatedAtEpochMs = 1_300L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val subAgents = (runtimeSnapshot["subAgents"] as List<Map<String, Any?>>)
    val child = subAgents.single()

    assertEquals(parentRunId, child["parentRunId"])
    assertEquals(parentTaskId, child["parentTaskId"])
    assertEquals("child-run-durable", child["childRunId"])
    assertEquals("child-task-durable", child["childTaskId"])
    assertEquals("Inspect runtime snapshot", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals(1, child["depth"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(true, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals("Delegated child runtime is still running in the background.", child["summary"])
    assertEquals(900L, child["startedAtEpochMs"])
    assertEquals(1_300L, child["updatedAtEpochMs"])
    assertEquals(0, child["eventCount"])
    assertEquals(2, child["mailboxMessageCount"])
    assertEquals(1, child["mailboxPendingMessageCount"])
    assertEquals("mailbox-projection-1", child["mailboxLastDeliveredMessageId"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsDurableClosedSubAgentHandleState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-subagent-durable-closed-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-subagent-durable-closed-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "show durable closed child")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val subAgentHandleStoreFactory = inMemorySubAgentHandleStoreFactory()

    subAgentHandleStoreFactory.forChatSession(sessionId).upsertClosed(
      backgroundSubAgentHandle(agentId = "child-durable-closed").copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child handle was explicitly closed.",
        ),
        updatedAtEpochMs = 1_300L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    val subAgents = (runtimeSnapshot["subAgents"] as List<Map<String, Any?>>)
    val child = subAgents.single()

    assertEquals("child-durable-closed", child["agentId"])
    assertEquals("cancelled", child["phase"])
    assertEquals("cancelled", child["status"])
    assertEquals(true, child["closed"])
    assertEquals("Delegated child handle was explicitly closed.", child["summary"])
    assertEquals(0, child["eventCount"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsDurableSubAgentSessionLinkState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-subagent-link-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-subagent-link-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "project detached child from durable link")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val subAgentSessionLinkStoreFactory = inMemorySubAgentSessionLinkStoreFactory()

    val parentRunId = "parent-run-link"
    val parentTaskId = "parent-task-link"
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_300L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = parentTaskId,
              type = AgentTaskType.PROMPT,
              input = "project detached child from durable link",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_300L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to parentRunId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-link",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = parentRunId,
        taskId = parentTaskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-link",
      ),
    )
    subAgentSessionLinkStoreFactory.forChatSession(sessionId).upsert(
      SubAgentSessionLink(
        parentSessionId = sessionId,
        parentRunId = parentRunId,
        agentId = "child-link-only",
        childSessionId = "child-session-link-only",
        childRootRunId = "child-run-link-only",
        childRootTaskId = "child-task-link-only",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        label = "Inspect detached child link",
        status = "background_running",
        closed = false,
        createdAtEpochMs = 900L,
        updatedAtEpochMs = 1_300L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      subAgentSessionLinkStoreFactory = subAgentSessionLinkStoreFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val subAgents = runtimeSnapshot["subAgents"] as List<Map<String, Any?>>
    val child = subAgents.single()

    assertEquals(parentRunId, child["parentRunId"])
    assertEquals("child-link-only", child["agentId"])
    assertEquals("child-session-link-only", child["childSessionId"])
    assertEquals("child-run-link-only", child["childRunId"])
    assertEquals("child-task-link-only", child["childTaskId"])
    assertEquals("Inspect detached child link", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals(1, child["depth"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(false, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals(false, child["closed"])
    assertEquals(true, child["hasActiveExecution"])
    assertEquals(0, child["eventCount"])
    assertEquals(0, child["mailboxMessageCount"])
    assertEquals(0, child["mailboxPendingMessageCount"])
    assertNull(child["summary"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsClosedSubAgentEvent() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-subagent-closed-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-subagent-closed-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "close delegated child")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    journalFactory.forChatSession(sessionId).append(
      OpenCraySubAgentEvent(
        runId = "projection-run-close",
        taskId = "projection-task-close",
        agentId = "child-handle-close",
        phase = OpenCraySubAgentPhase.STARTED,
        childRunId = "child-run-close",
        childTaskId = "child-task-close",
        label = "Inspect README",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        executionState = SubAgentExecutionState.RUNNING,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        turn = 0,
        emittedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCraySubAgentEvent(
        runId = "projection-run-close",
        taskId = "projection-task-close",
        agentId = "child-handle-close",
        phase = OpenCraySubAgentPhase.CANCELLED,
        childRunId = "child-run-close",
        childTaskId = "child-task-close",
        label = "Inspect README",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        summary = "Delegated child handle closed.",
        executionState = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        turn = 1,
        emittedAtEpochMs = 1_200L,
        closed = true,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    val events = (runtimeSnapshot["events"] as List<Map<String, Any?>>)
    val subAgents = (runtimeSnapshot["subAgents"] as List<Map<String, Any?>>)
    val closedEvent = events.last { event -> event["kind"] == "subagent" }
    val closedChild = subAgents.single()

    assertEquals(true, closedEvent["closed"])
    assertEquals("child-handle-close", closedEvent["agentId"])
    assertEquals("cancelled", closedEvent["phase"])
    assertEquals("cancelled", closedEvent["status"])
    assertEquals("Delegated child handle closed.", closedEvent["text"])
    assertEquals(true, closedChild["closed"])
    assertEquals("child-handle-close", closedChild["agentId"])
    assertEquals("cancelled", closedChild["phase"])
    assertEquals("cancelled", closedChild["status"])
    assertEquals("Delegated child handle closed.", closedChild["summary"])
    assertEquals(2, closedChild["eventCount"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsCheckpointOnlySubAgentHandleState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-subagent-checkpoint-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-subagent-checkpoint-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "restore detached child from checkpoint")

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val parentRunId = "parent-run-checkpoint"
    val parentTaskId = "parent-task-checkpoint"
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_300L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = parentTaskId,
              type = AgentTaskType.PROMPT,
              input = "restore detached child from checkpoint",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_300L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to parentRunId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-checkpoint",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = parentRunId,
        taskId = parentTaskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-checkpoint",
      ),
    )
    checkpointFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = parentRunId,
        taskId = parentTaskId,
        checkpointId = "checkpoint-subagent-runtime",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_300L,
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
          subAgentHandles = listOf(
            SubAgentHandleState(
              agentId = "child-checkpoint",
              childRunId = "child-run-checkpoint",
              childTaskId = "child-task-checkpoint",
              description = "Inspect checkpoint recovery",
              prompt = "Inspect checkpoint-backed recovery and summarize it.",
              subagentType = "researcher",
              contextMode = "minimal",
              parentRunId = parentRunId,
              parentTaskId = parentTaskId,
              parentTurn = 1,
              depth = 1,
              snapshot = SubAgentExecutionSnapshot.backgroundRunning(
                headline = "Checkpoint-backed child runtime is still running in the background.",
              ),
              createdAtEpochMs = 900L,
              updatedAtEpochMs = 1_250L,
            ),
          ),
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val subAgents = (runtimeSnapshot["subAgents"] as List<Map<String, Any?>>)
    val child = subAgents.single()

    assertEquals(parentRunId, child["parentRunId"])
    assertEquals(parentTaskId, child["parentTaskId"])
    assertEquals("child-run-checkpoint", child["childRunId"])
    assertEquals("child-task-checkpoint", child["childTaskId"])
    assertEquals("Inspect checkpoint recovery", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals(1, child["depth"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(true, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals("Checkpoint-backed child runtime is still running in the background.", child["summary"])
    assertEquals(900L, child["startedAtEpochMs"])
    assertEquals(1_250L, child["updatedAtEpochMs"])
    assertEquals(0, child["eventCount"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayKeepsDeferredApprovalRunPausedForManualResume() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-deferred-approval-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-deferred-approval-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-deferred-approval-run-1"
    val taskId = "projection-deferred-approval-task-1"
    chatStore.appendMessage(sessionId, ChatTranscriptRole.USER, "Need approval")
    queueFactory.forChatSession(sessionId).save(
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
              input = "Need approval",
              state = AgentTaskState.SUSPENDED,
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
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            attempt = 1,
            lastErrorCode = "APPROVAL_REQUIRED",
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    checkpointFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 1_150L,
        updatedAtEpochMs = 1_150L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val chatSnapshot = gateway.loadChatSnapshot()
    val runSnapshot = requireNotNull(gateway.loadChatRunSnapshot(runId))
    val recoveryPlan = runSnapshot["recoveryPlan"] as Map<*, *>

    assertEquals("Message OpenCray", chatSnapshot["composerPlaceholder"])
    assertEquals(
      "Waiting for your next instruction.",
      (chatSnapshot["summary"] as Map<*, *>)["body"],
    )
    assertEquals("suspended", runSnapshot["lifecycleState"])
    assertEquals("resume_waiting_for_user", recoveryPlan["action"])
    assertEquals("approved_pending_resume", recoveryPlan["checkpointKind"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsPendingApprovalsIntoChatSnapshot() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-approval-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-approval-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-approval-run-1"
    val taskId = "projection-approval-task-1"
    chatStore.appendMessage(sessionId, ChatTranscriptRole.USER, "Need shell approval")
    queueFactory.forChatSession(sessionId).save(
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
              input = "Need shell approval",
              state = AgentTaskState.SUSPENDED,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-approval-1",
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            attempt = 1,
            lastErrorCode = "APPROVAL_REQUIRED",
            lastErrorMessage = "Approval is required before Bash can run.",
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = "pending-approval-1",
        lastResult = ExecutionResult(
          taskId = taskId,
          status = ExecutionStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval is required before Bash can run.",
          startedAtEpochMs = 1_100L,
          finishedAtEpochMs = 1_200L,
          metadata = mapOf(
            "toolName" to "Bash",
            "command" to "git",
            "args" to "status\u0000--short",
            "workingDirectory" to ".",
            "toolReason" to "Check repository state before editing.",
          ),
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val chatSnapshot = gateway.loadChatSnapshot()
    @Suppress("UNCHECKED_CAST")
    val summary = chatSnapshot["summary"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val pendingApprovals = chatSnapshot["pendingApprovals"] as List<Map<String, Any?>>
    val approval = pendingApprovals.single()

    assertEquals("Approval required before the agent can continue.", summary["body"])
    assertEquals("Bash", approval["toolName"])
    assertEquals("git status --short", approval["requestSummary"])
    assertEquals("git status --short", approval["primaryDetail"])
    assertEquals(".", approval["workingDirectory"])
    assertEquals("Check repository state before editing.", approval["reason"])
    assertEquals("Approval required", approval["title"])
    assertEquals("Approve", approval["approveLabel"])
    assertEquals("Reject", approval["rejectLabel"])
    assertEquals(false, approval["supportsSessionApproval"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayDeduplicatesRepeatedRuntimeEventsInSnapshot() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-dedup-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-dedup-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-dedup-run-1"
    val taskId = "projection-dedup-task-1"
    queueFactory.forChatSession(sessionId).save(
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
              input = "deduplicate repeated events",
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
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    val duplicateEvent = OpenCrayToolResultEvent(
      runId = runId,
      taskId = taskId,
      turn = 1,
      call = AgentToolCall(toolName = "Read"),
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = "README.md loaded",
      ),
      emittedAtEpochMs = 1_250L,
    )
    journalFactory.forChatSession(sessionId).append(duplicateEvent)
    journalFactory.forChatSession(sessionId).append(duplicateEvent)

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val runSnapshot = requireNotNull(gateway.loadChatRunSnapshot(runId))
    val lastEvent = runSnapshot["lastEvent"] as Map<*, *>

    assertEquals(1, events.size)
    assertEquals("tool_result", events.single()["kind"])
    assertEquals("Read", events.single()["toolName"])
    assertEquals("tool_result", lastEvent["kind"])
    assertEquals("Read", lastEvent["toolName"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayHidesInternalToolResultMetadata() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-tool-metadata-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-tool-metadata-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-tool-run-1"
    val taskId = "projection-tool-task-1"
    queueFactory.forChatSession(sessionId).save(
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
              input = "inspect metadata",
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
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCrayToolResultEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "alpha",
          metadata = mapOf(
            "filePath" to "README.md",
            "checkpointId" to "hidden-checkpoint",
            OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1,"toolCallCount":1}""",
          ),
        ),
        emittedAtEpochMs = 1_250L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val resultMetadata = events.single()["resultMetadata"] as Map<*, *>

    assertEquals("README.md", resultMetadata["filePath"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertFalse(resultMetadata.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsSupplementMetadataWithoutHiddenResumePayload() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-supplement-metadata-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-supplement-metadata-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-supplement-run-1"
    val taskId = "projection-supplement-task-1"
    queueFactory.forChatSession(sessionId).save(
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
              input = "inspect supplement metadata",
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
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCraySupplementEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        entryId = "supplement-1",
        text = "Also inspect the logs",
        checkpoint = "turn_start",
        metadata = mapOf(
          "source" to "manual",
          OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to
            """{"turnIndex":1,"toolCallCount":1}""",
        ),
        emittedAtEpochMs = 1_250L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val supplement = events.single()
    val metadata = supplement["metadata"] as Map<*, *>

    assertEquals("supplement", supplement["kind"])
    assertEquals("Also inspect the logs", supplement["text"])
    assertEquals("manual", metadata["source"])
    assertEquals(true, supplement["hasResumeCheckpointMetadata"])
    assertFalse(metadata.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayOmitsSupplementMetadataWhenOnlyResumeCheckpointMetadataExists() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-supplement-hidden-only-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-supplement-hidden-only-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "projection-supplement-hidden-run-1"
    val taskId = "projection-supplement-hidden-task-1"
    queueFactory.forChatSession(sessionId).save(
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
              input = "inspect supplement hidden metadata",
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
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCraySupplementEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        entryId = "supplement-1",
        text = "Also inspect the logs",
        checkpoint = "turn_start",
        metadata = mapOf(
          OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to
            """{"turnIndex":1,"toolCallCount":2}""",
        ),
        emittedAtEpochMs = 1_250L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val supplement = events.single()

    assertEquals("supplement", supplement["kind"])
    assertEquals("Also inspect the logs", supplement["text"])
    assertEquals(true, supplement["hasResumeCheckpointMetadata"])
    assertFalse(supplement.containsKey("metadata"))
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayObserverPollsDurableRuntimeUpdates() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-observer-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-observer-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = ProjectionOnlyChatStrings(
        localeTag = "en",
        screenTitle = "Chat",
        modeLabel = "AUTO",
        sessionButtonLabel = "Sessions",
        recentSessionsEyebrow = "Recent sessions",
        recentSessionsTitle = "Recent sessions",
        newSessionLabel = "New session",
        defaultSessionTitle = "New chat",
        messagesBadge = { count -> "$count messages" },
        summaryReplyInProgress = "Reply in progress",
        summaryStartNewSession = "Start a new session",
        summaryRestored = "Restored",
        summaryApprovalRequired = "Approval required before the agent can continue.",
        approvalRequiredTitle = "Approval required",
        highRiskApprovalRequiredTitle = "High-risk approval required",
        highRiskApprovalRequiredBody =
          "High-risk approval required. Review this request carefully before approving.",
        approvalApproveLabel = "Approve",
        approvalApproveForSessionLabel = "Allow session",
        approvalRejectLabel = "Reject",
        composerPlaceholder = "Message OpenCray",
        composerRejectedPlaceholder = "Message OpenCray differently",
      ),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { System.currentTimeMillis() },
      pollIntervalMs = 25L,
    )

    val observedRunIds = mutableListOf<String?>()
    val disposer = gateway.observeChatRuntime { snapshot ->
      @Suppress("UNCHECKED_CAST")
      val activeRuns = snapshot["activeRuns"] as? List<Map<String, Any?>>
      observedRunIds += activeRuns?.firstOrNull()?.get("runId") as String?
    }

    try {
      assertEquals(listOf(null), observedRunIds)

      val runId = "observer-run-1"
      val taskId = "observer-task-1"
      queueFactory.forChatSession(sessionId).save(
        SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "test-agent",
          lifecycleState = SessionLifecycleState.RUNNING,
          updatedAtEpochMs = 2_200L,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1L,
              task = AgentTask(
                id = taskId,
                type = AgentTaskType.PROMPT,
                input = "observe me",
                state = AgentTaskState.RUNNING,
                policyDecision = PolicyDecision(
                  outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                  reasonCode = "test",
                ),
                createdAtEpochMs = 2_000L,
                updatedAtEpochMs = 2_200L,
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
      runRecordFactory.forChatSession(sessionId).upsert(
        PersistedAgentRunRecord(
          runId = runId,
          taskId = taskId,
          acceptedAtEpochMs = 2_000L,
        ),
      )
      checkpointFactory.forChatSession(sessionId).upsert(
        PersistedPromptCheckpoint(
          sessionId = sessionId,
          runId = runId,
          taskId = taskId,
          checkpointId = "checkpoint-observer-run-1",
          checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
          createdAtEpochMs = 2_200L,
          updatedAtEpochMs = 2_200L,
          promptResumeState = OpenCrayPromptResumeState(
            turnIndex = 1,
            toolCallCount = 0,
          ),
        ),
      )
      journalFactory.forChatSession(sessionId).append(
        OpenCrayAssistantEvent(
          runId = runId,
          taskId = taskId,
          turn = 1,
          text = "Polling update",
          isFinal = false,
          emittedAtEpochMs = 2_250L,
        ),
      )

      waitForCondition(timeoutMs = 1_000L) {
        observedRunIds.any { observed -> observed == runId }
      }
    } finally {
      disposer()
    }
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsLocalMemoryDebugStateLinksAndSearch() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-memory-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-memory-store")
    val personalizationRoot = temporaryFolder.newFolder("projection-personalization-memory-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(personalizationRoot)

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    val runId = "memory-run-1"
    val taskId = "memory-task-1"
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-1",
        content = "User prefers milk tea with less sugar.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_200L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "session",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.SOURCE_TASK_ID to taskId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.USER_PREFERRED_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Milk Tea Fan",
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        lastResult = ExecutionResult(
          taskId = taskId,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_300L,
          metadata = mapOf(
            "contextMemorySelectedSummary" to "memory-1@98[milk|tea]",
          ),
        ),
      ),
    )
    journalFactory.forChatSession(sessionId).append(
      OpenCrayMemoryRetrievalEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        toolName = "memory.search",
        operation = "search",
        query = "milk tea",
        queryTerms = listOf("milk", "tea"),
        resultCount = 1,
        corpusFileCount = 2,
        recordIds = listOf("memory-1"),
        paths = listOf("MEMORY.md"),
        lineRanges = listOf("5-12"),
        emittedAtEpochMs = 1_350L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      personalizationLocalStore = personalizationStore,
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val snapshot = gateway.loadMemoryDebugSnapshot()
    @Suppress("UNCHECKED_CAST")
    val records = snapshot["records"] as List<Map<String, Any?>>
    val search = gateway.searchMemoryDebug(
      query = "milk tea",
      maxResults = 4,
      minScore = 1,
    )
    @Suppress("UNCHECKED_CAST")
    val searchResults = search["results"] as List<Map<String, Any?>>
    val slice = gateway.getMemoryDebugSlice(
      path = searchResults.single()["path"] as String,
      fromLine = searchResults.single()["startLine"] as Int,
      lines = 12,
    )
    val links = gateway.loadMemoryDebugLinksSnapshot()
    @Suppress("UNCHECKED_CAST")
    val linkRecords = links["records"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val promptRecalls = linkRecords.single()["promptRecalls"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val toolRetrievals = linkRecords.single()["toolRetrievals"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val sourceRun = linkRecords.single()["sourceRun"] as Map<String, Any?>

    assertEquals(sessionId, snapshot["sessionId"])
    assertEquals(1, records.size)
    assertEquals("memory-1", records.single()["id"])
    assertEquals("user_preference", records.single()["kind"])
    assertEquals("session", records.single()["scope"])
    assertEquals(false, records.single()["isExpired"])
    assertEquals(1, searchResults.size)
    assertEquals("memory-1", searchResults.single()["recordId"])
    assertTrue((searchResults.single()["snippet"] as String).contains("milk tea"))
    assertTrue((slice["text"] as String).contains("milk tea"))
    assertEquals(listOf("memory-1"), slice["recordIds"])
    assertEquals(taskId, linkRecords.single()["sourceTaskId"])
    assertEquals(taskId, sourceRun["taskId"])
    assertEquals(1, promptRecalls.size)
    assertEquals(98, promptRecalls.single()["score"])
    assertEquals(1, toolRetrievals.size)
    assertEquals("memory.search", toolRetrievals.single()["toolName"])
    assertEquals("search", toolRetrievals.single()["operation"])

    val actionResult = gateway.applyMemoryDebugAction(
      recordId = "memory-1",
      actionId = "suppress",
    )
    val updatedRecord = personalizationStore.listMemoryRecords().single()
    val audits = personalizationStore.listMemoryDebugActionAudits()

    assertEquals("memory-1", actionResult["recordId"])
    assertEquals("suppress", actionResult["action"])
    assertEquals(true, actionResult["applied"])
    assertEquals("resolved", updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(1, audits.size)
    assertEquals("memory-1", audits.single().recordId)
    assertEquals("suppress", audits.single().action)
    assertEquals(sessionId, audits.single().sessionId)
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsServiceKeepAliveStateFromBridgeSnapshot() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-keepalive-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-keepalive-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val bridgeSnapshot = bridgeSnapshot(temporaryFolder.newFolder("projection-bridge-keepalive")).copy(
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 30_000L,
        stopScheduled = true,
        stopDeadlineEpochMs = 35_000L,
        lastStartId = 11,
        lastStartCommandAtEpochMs = 5_000L,
        changedAtEpochMs = 5_500L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      serviceKeepAliveStateProvider = { bridgeSnapshot.serviceKeepAliveState },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    val keepAliveState = runtimeSnapshot["runtimeServiceKeepAliveState"] as Map<*, *>

    assertEquals("idle_grace", keepAliveState["phase"])
    assertEquals(30_000L, keepAliveState["idleGraceMs"])
    assertEquals(true, keepAliveState["stopScheduled"])
    assertEquals(35_000L, keepAliveState["stopDeadlineEpochMs"])
    assertEquals(11, keepAliveState["lastStartId"])
    assertEquals(5_500L, keepAliveState["changedAtEpochMs"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsLocalSoulDebugState() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-soul-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-soul-store")
    val personalizationRoot = temporaryFolder.newFolder("projection-personalization-soul-store")
    val workspaceRoot = temporaryFolder.newFolder("projection-workspace-soul-store").toPath()
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(personalizationRoot)

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot = workspaceRoot,
      profile = WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Base Soul",
        customGuidance = "Stay gentle",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-display-name",
        content = "Call yourself Nova when talking to the user.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_300L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "session",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Nova",
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionOnlyChatStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val snapshot = gateway.loadSoulDebugSnapshot()
    @Suppress("UNCHECKED_CAST")
    val storedSoul = snapshot["storedSoul"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val baseSoul = snapshot["baseSoul"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val effectiveSoul = snapshot["effectiveSoul"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val overlayRecords = snapshot["overlayRecords"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val fieldSources = snapshot["fieldSources"] as List<Map<String, Any?>>
    val displayNameSource = fieldSources.first { source ->
      source["field"] == "displayName"
    }

    assertEquals(sessionId, snapshot["sessionId"])
    assertNotNull(snapshot["workspaceId"])
    assertEquals("SOUL.md", storedSoul["relativePath"])
    assertEquals("WARM", storedSoul["presetName"])
    assertEquals("Base Soul", baseSoul["displayName"])
    assertEquals("Nova", effectiveSoul["displayName"])
    assertEquals(1, overlayRecords.size)
    assertEquals("memory-display-name", overlayRecords.single()["id"])
    assertEquals("memory_overlay", displayNameSource["sourceType"])
    assertEquals("memory-display-name", displayNameSource["recordId"])
  }
}
